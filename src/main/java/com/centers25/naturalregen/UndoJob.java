package com.centers25.naturalregen;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/** Replays an undo file: puts back the blocks a regen job replaced (blocks only). */
final class UndoJob implements Task {
    private static final int MAX_QUEUED = 64;

    private final NaturalRegenPlugin plugin;
    private final File file;
    private final UUID ownerId;
    private final World world;
    private final int worldMinY;
    private final List<String> palette = new ArrayList<>();
    private final List<BlockData> paletteData = new ArrayList<>();
    private final ConcurrentLinkedQueue<Record> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final List<Record> loading = new ArrayList<>();
    private final BukkitTask ticker;
    private final long startedAt = System.currentTimeMillis();
    private volatile boolean readerDone;
    private volatile boolean readerFailed;
    private volatile boolean finished;
    private Record current;
    private long blocks;
    private int chunks;

    private record Header(String world, int minY) {}

    static String peekWorld(File file) throws IOException {
        try (DataInputStream in = open(file)) {
            return readHeader(in).world();
        }
    }

    private static DataInputStream open(File file) throws IOException {
        return new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file), 1 << 16), 1 << 16));
    }

    private static Header readHeader(DataInputStream in) throws IOException {
        if (in.readInt() != UndoWriter.MAGIC) throw new IOException("Not a NaturalRegen undo file");
        return new Header(in.readUTF(), in.readInt());
    }

    UndoJob(NaturalRegenPlugin plugin, File file, UUID ownerId) throws IOException {
        this.plugin = plugin;
        this.file = file;
        this.ownerId = ownerId;
        DataInputStream in = open(file);
        Header h = readHeader(in);
        this.world = Bukkit.getWorld(h.world());
        if (world == null) {
            in.close();
            throw new IOException("World " + h.world() + " is not loaded");
        }
        this.worldMinY = h.minY();
        Thread reader = new Thread(() -> read(in), "NaturalRegen-undo-reader");
        reader.setDaemon(true);
        reader.start();
        this.ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        plugin.log().info("Undoing " + file.getName() + " in world " + world.getName());
    }

    private void read(DataInputStream in) {
        try (in) {
            while (!finished) {
                int tag;
                try {
                    tag = in.readByte();
                } catch (EOFException e) {
                    break; // file from a crashed job: replay what we have
                }
                if (tag == 0) break;
                if (tag == 1) {
                    int id = in.readInt();
                    String s = in.readUTF();
                    synchronized (palette) {
                        while (palette.size() <= id) palette.add(null);
                        palette.set(id, s);
                    }
                } else if (tag == 2) {
                    int cx = in.readInt(), cz = in.readInt(), n = in.readInt();
                    int[] pos = new int[n], ids = new int[n];
                    for (int i = 0; i < n; i++) {
                        pos[i] = in.readInt();
                        ids[i] = in.readInt();
                    }
                    while (queued.get() >= MAX_QUEUED && !finished) Thread.sleep(20);
                    queue.add(new Record(cx, cz, pos, ids));
                    queued.incrementAndGet();
                } else {
                    throw new IOException("Corrupt undo file (tag " + tag + ")");
                }
            }
        } catch (EOFException e) {
            // truncated mid-record: replay what we have
        } catch (Exception e) {
            readerFailed = true;
            plugin.log().error("Error reading undo file", e);
        } finally {
            readerDone = true;
        }
    }

    private BlockData data(int id) {
        while (paletteData.size() <= id) paletteData.add(null);
        BlockData d = paletteData.get(id);
        if (d == null) {
            String s;
            synchronized (palette) {
                s = palette.get(id);
            }
            d = Bukkit.createBlockData(s);
            paletteData.set(id, d);
        }
        return d;
    }

    private void tick() {
        if (finished) return;
        try {
            long deadline = System.nanoTime() + plugin.budgetNanos();
            while (loading.size() < 8) {
                Record r = queue.poll();
                if (r == null) break;
                loading.add(r);
                world.getChunkAtAsync(r.cx, r.cz, true).whenComplete((c, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (c != null) {
                        world.addPluginChunkTicket(r.cx, r.cz, plugin);
                        r.chunk = c;
                    } else {
                        r.error = true;
                    }
                }));
            }
            while (System.nanoTime() < deadline) {
                if (current == null) {
                    Iterator<Record> it = loading.iterator();
                    while (it.hasNext()) {
                        Record r = it.next();
                        if (r.error) {
                            plugin.log().warn("Undo: could not load chunk " + r.cx + "," + r.cz + ", skipped");
                            it.remove();
                            queued.decrementAndGet();
                        } else if (r.chunk != null) {
                            current = r;
                            it.remove();
                            break;
                        }
                    }
                    if (current == null) break;
                }
                Record r = current;
                while (r.index < r.pos.length) {
                    if ((r.index & 127) == 0 && System.nanoTime() >= deadline) break;
                    int p = r.pos[r.index];
                    r.chunk.getBlock(p & 15, RegenJob.unpackY(p), (p >> 4) & 15).setBlockData(data(r.ids[r.index]), false);
                    r.index++;
                    blocks++;
                }
                if (r.index < r.pos.length) break;
                world.removePluginChunkTicket(r.cx, r.cz, plugin);
                queued.decrementAndGet();
                chunks++;
                current = null;
            }
            var p =Bukkit.getPlayer(ownerId);
            if (p != null && Bukkit.getCurrentTick() % 20 == 0) p.sendActionBar(NaturalRegenPlugin.mm("<yellow>NaturalRegen undo</yellow> <gray>" + status()));
            if (readerDone && queue.isEmpty() && loading.isEmpty() && current == null) complete();
        } catch (Throwable t) {
            plugin.log().error("Undo crashed; stopping", t);
            cancel("an internal error (see console)");
        }
    }

    private void complete() {
        shutdown();
        File doneFile = new File(file.getParentFile(), file.getName() + ".undone");
        if (!readerFailed && !file.renameTo(doneFile)) plugin.log().warn("Could not rename " + file);
        String msg = String.format("Undo finished in %s: %,d blocks restored in %,d chunks.%s",
                RegenJob.formatDuration(System.currentTimeMillis() - startedAt), blocks, chunks,
                readerFailed ? " The undo file had errors, so it may be incomplete." : "");
        plugin.log().info(msg);
        plugin.notify(ownerId, "<green>" + msg);
    }

    private void shutdown() {
        finished = true;
        ticker.cancel();
        world.removePluginChunkTickets(plugin);
        plugin.taskEnded(this);
    }

    @Override
    public String status() {
        return String.format("%,d blocks restored in %,d chunks", blocks, chunks);
    }

    @Override
    public void cancel(String reason) {
        if (finished) return;
        shutdown();
        plugin.notify(ownerId, "<red>Undo stopped:</red> <gray>" + reason + ". " + status() + ". Run /nregen undo again to finish it.");
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    private static final class Record {
        final int cx, cz;
        final int[] pos, ids;
        volatile Chunk chunk;
        volatile boolean error;
        int index;

        Record(int cx, int cz, int[] pos, int[] ids) {
            this.cx = cx;
            this.cz = cz;
            this.pos = pos;
            this.ids = ids;
        }
    }
}
