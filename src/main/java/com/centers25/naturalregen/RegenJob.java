package com.centers25.naturalregen;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resets a selection to seed terrain, one chunk at a time:
 * 1. load the target chunk and generate the same chunk in the temp world (both async),
 * 2. snapshot both and work out which blocks/biomes differ (async),
 * 3. on the main thread, within a per-tick time budget, write only the differences,
 *    copy chests/spawners (with their loot tables) and entities, and remove the build's entities.
 */
final class RegenJob implements Task {
    private static final int ENTITY_WAIT_TICKS = 100;
    private static final int MAX_LOAD_ATTEMPTS = 3;

    private final NaturalRegenPlugin plugin;
    private final Selection sel;
    private final World target;
    private final World source;
    private final long seed;
    private final UUID ownerId;
    private final boolean copyBiomes;
    private final boolean replaceEntities;
    private final int maxInFlight;
    private final int worldMinY;
    private final long[] chunks;
    private final UndoWriter undo;

    private final List<Loading> loading = new ArrayList<>();
    private final ConcurrentLinkedQueue<ChunkWork> ready = new ConcurrentLinkedQueue<>();
    private final AtomicInteger diffing = new AtomicInteger();
    private final ExecutorService diffPool;
    private final BukkitTask ticker;
    private final long startedAt = System.currentTimeMillis();

    private int nextIndex;
    private int inFlight;
    private int done;
    private int failedChunks;
    private ChunkWork current;
    private long blocksChanged, biomesChanged, tilesCopied, entitiesRemoved, entitiesCopied;
    private long lastActionBar;
    private int lastLoggedTenth;
    private boolean finished;

    RegenJob(NaturalRegenPlugin plugin, Selection sel, World source, long seed, UUID ownerId) {
        this.plugin = plugin;
        this.sel = sel;
        this.target = sel.world();
        this.source = source;
        this.seed = seed;
        this.ownerId = ownerId;
        this.copyBiomes = plugin.getConfig().getBoolean("copy-biomes", true);
        this.replaceEntities = plugin.getConfig().getBoolean("replace-entities", true);
        this.maxInFlight = Math.max(1, plugin.getConfig().getInt("max-chunks-in-flight", 16));
        this.worldMinY = target.getMinHeight();
        this.chunks = sel.chunkKeys();

        UndoWriter writer = null;
        if (plugin.getConfig().getBoolean("write-undo", true)) {
            File file = new File(plugin.undoFolder(), System.currentTimeMillis() + ".nru");
            try {
                writer = new UndoWriter(file, target.getName(), worldMinY, plugin.log());
            } catch (Exception e) {
                plugin.log().warn("Could not create undo file, continuing without undo", e);
            }
        }
        this.undo = writer;

        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
        this.diffPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "NaturalRegen-diff");
            t.setDaemon(true);
            return t;
        });
        plugin.log().info("Starting regen of " + sel.describe() + " (" + chunks.length + " chunks, seed " + seed + ", source world " + source.getName() + ")");
        this.ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ------------------------------------------------------------------ main loop

    private void tick() {
        if (finished) return;
        try {
            long deadline = System.nanoTime() + plugin.budgetNanos();
            startLoads();
            snapshotLoaded();
            applyWithin(deadline);
            report();
            if (done + failedChunks >= chunks.length && current == null && ready.isEmpty() && diffing.get() == 0 && loading.isEmpty()) {
                complete();
            }
        } catch (Throwable t) {
            plugin.log().error("NaturalRegen job crashed; stopping", t);
            stop("an internal error (see console)");
        }
    }

    private void startLoads() {
        while (inFlight < maxInFlight && nextIndex < chunks.length) {
            long key = chunks[nextIndex++];
            Loading l = new Loading(Selection.keyX(key), Selection.keyZ(key));
            loading.add(l);
            inFlight++;
            l.request();
        }
    }

    private void snapshotLoaded() {
        Iterator<Loading> it = loading.iterator();
        int snapshotsThisTick = 0;
        while (it.hasNext() && snapshotsThisTick < 6) {
            Loading l = it.next();
            if (l.error) {
                if (l.attempts < MAX_LOAD_ATTEMPTS) {
                    l.request();
                } else {
                    plugin.log().warn("Giving up on chunk " + l.cx + "," + l.cz + " after " + MAX_LOAD_ATTEMPTS + " load failures");
                    releaseTickets(l.cx, l.cz);
                    it.remove();
                    inFlight--;
                    failedChunks++;
                }
                continue;
            }
            if (l.targetChunk == null || l.sourceChunk == null) continue;
            boolean entitiesReady = l.targetChunk.isEntitiesLoaded() && l.sourceChunk.isEntitiesLoaded();
            if (replaceEntities && !entitiesReady && l.waited++ < ENTITY_WAIT_TICKS) continue;

            snapshotsThisTick++;
            ChunkSnapshot tSnap = l.targetChunk.getChunkSnapshot(false, copyBiomes, false);
            ChunkSnapshot sSnap = l.sourceChunk.getChunkSnapshot(false, copyBiomes, false);
            Set<Integer> srcTiles = tilePositions(l.sourceChunk, l.cx, l.cz);
            Set<Integer> tgtTiles = tilePositions(l.targetChunk, l.cx, l.cz);
            it.remove();
            diffing.incrementAndGet();
            Chunk tc = l.targetChunk, sc = l.sourceChunk;
            int cx = l.cx, cz = l.cz;
            diffPool.execute(() -> {
                try {
                    ready.add(diff(cx, cz, tc, sc, tSnap, sSnap, srcTiles, tgtTiles));
                } catch (Throwable t) {
                    plugin.log().error("Diff failed for chunk " + cx + "," + cz, t);
                    ChunkWork f = new ChunkWork(cx, cz, tc, sc, Set.of(), Set.of());
                    f.failed = true;
                    ready.add(f);
                } finally {
                    diffing.decrementAndGet();
                }
            });
        }
    }

    private Set<Integer> tilePositions(Chunk chunk, int cx, int cz) {
        Set<Integer> out = new HashSet<>();
        for (BlockState st : chunk.getTileEntities(false)) {
            int x = st.getX(), y = st.getY(), z = st.getZ();
            if (sel.contains(x, y, z)) out.add(pack(x - (cx << 4), y, z - (cz << 4)));
        }
        return out;
    }

    private void applyWithin(long deadline) {
        while (System.nanoTime() < deadline) {
            if (current == null) {
                current = ready.poll();
                if (current == null) return;
            }
            if (current.failed) {
                releaseTickets(current.cx, current.cz);
                inFlight--;
                failedChunks++;
                current = null;
                continue;
            }
            if (!current.applyBlocks(deadline)) return; // out of time; continue next tick
            finishChunk(current);
            current = null;
        }
    }

    // ------------------------------------------------------------------ per chunk

    private ChunkWork diff(int cx, int cz, Chunk tc, Chunk sc, ChunkSnapshot t, ChunkSnapshot s,
                           Set<Integer> srcTiles, Set<Integer> tgtTiles) {
        int baseX = cx << 4, baseZ = cz << 4;
        int x0 = Math.max(sel.minX() - baseX, 0), x1 = Math.min(sel.maxX() - baseX, 15);
        int z0 = Math.max(sel.minZ() - baseZ, 0), z1 = Math.min(sel.maxZ() - baseZ, 15);
        ChunkWork w = new ChunkWork(cx, cz, tc, sc, srcTiles, tgtTiles);
        for (int y = sel.minY(); y <= sel.maxY(); y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    BlockData sd = s.getBlockData(x, y, z);
                    BlockData td = t.getBlockData(x, y, z);
                    int p = pack(x, y, z);
                    if (!sd.equals(td) || srcTiles.contains(p) || tgtTiles.contains(p)) w.add(p, sd, td);
                }
            }
        }
        if (copyBiomes) {
            int yStart = Math.floorDiv(sel.minY(), 4) * 4;
            for (int y = yStart; y <= sel.maxY(); y += 4) {
                int sy = Math.max(y, sel.minY());
                for (int z = z0 & ~3; z <= z1; z += 4) {
                    for (int x = x0 & ~3; x <= x1; x += 4) {
                        int qx = Math.max(x, x0), qz = Math.max(z, z0);
                        Biome sb = s.getBiome(qx, sy, qz);
                        Biome tb = t.getBiome(qx, sy, qz);
                        if (!Objects.equals(sb, tb)) w.addBiome(qx, sy, qz, sb);
                    }
                }
            }
        }
        return w;
    }

    private void finishChunk(ChunkWork w) {
        int baseX = w.cx << 4, baseZ = w.cz << 4;
        // Biomes
        if (w.biomeCount > 0) {
            for (int i = 0; i < w.biomeCount; i++) {
                int p = w.biomePos[i];
                try {
                    target.setBiome(baseX + (p & 15), unpackY(p), baseZ + ((p >> 4) & 15), w.biomes[i]);
                } catch (Throwable ignored) {
                }
            }
            biomesChanged += w.biomeCount;
        }
        // Entities: remove everything the build left behind, then copy what the seed generated.
        if (replaceEntities) {
            for (Entity e : w.targetChunk.getEntities()) {
                if (e instanceof Player) continue;
                Location l = e.getLocation();
                if (!sel.contains(l.getX(), l.getY(), l.getZ())) continue;
                e.remove();
                entitiesRemoved++;
            }
            for (Entity e : w.sourceChunk.getEntities()) {
                if (e instanceof Player || e instanceof EnderDragon) continue;
                Location l = e.getLocation();
                if (!sel.contains(l.getX(), l.getY(), l.getZ())) continue;
                Location to = l.clone();
                to.setWorld(target);
                try {
                    e.copy(to);
                    entitiesCopied++;
                } catch (Throwable ex) {
                    plugin.log().debug("Could not copy " + e.getType() + ": " + ex);
                }
            }
        }
        if (w.biomeCount > 0) {
            try {
                target.refreshChunk(w.cx, w.cz);
            } catch (Throwable ignored) {
            }
        }
        if (undo != null) undo.writeChunk(w.cx, w.cz, w.pos, w.oldData, w.applied);
        blocksChanged += w.applied;
        releaseTickets(w.cx, w.cz);
        inFlight--;
        done++;
    }

    private void releaseTickets(int cx, int cz) {
        target.removePluginChunkTicket(cx, cz, plugin);
        source.removePluginChunkTicket(cx, cz, plugin);
    }

    static int pack(int lx, int y, int lz) {
        return ((y + 4096) << 8) | (lz << 4) | lx;
    }

    static int unpackY(int p) {
        return (p >>> 8) - 4096;
    }

    // ------------------------------------------------------------------ status / end

    private void report() {
        long now = System.currentTimeMillis();
        int processed = done + failedChunks;
        int tenth = chunks.length == 0 ? 10 : (int) (processed * 10L / chunks.length);
        if (tenth > lastLoggedTenth && tenth < 10) {
            lastLoggedTenth = tenth;
            plugin.log().info("Regen progress: " + status());
        }
        if (now - lastActionBar >= 1000) {
            lastActionBar = now;
            Player p = Bukkit.getPlayer(ownerId);
            if (p != null) p.sendActionBar(NaturalRegenPlugin.mm("<green>NaturalRegen</green> <gray>" + status()));
        }
    }

    @Override
    public String status() {
        int processed = done + failedChunks;
        double pct = chunks.length == 0 ? 100 : processed * 100.0 / chunks.length;
        long elapsed = System.currentTimeMillis() - startedAt;
        String eta = "?";
        if (processed > 0 && processed < chunks.length) {
            eta = formatDuration(elapsed * (chunks.length - processed) / processed);
        }
        return String.format("%.1f%% • %d/%d chunks • %,d blocks • ETA %s", pct, processed, chunks.length, blocksChanged, eta);
    }

    static String formatDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    private void complete() {
        String msg = String.format("Done in %s: %,d blocks reset, %,d biome cells, %,d chests/spawners restored, %,d entities removed, %,d natural entities placed%s.",
                formatDuration(System.currentTimeMillis() - startedAt), blocksChanged, biomesChanged, tilesCopied,
                entitiesRemoved, entitiesCopied, failedChunks > 0 ? ", " + failedChunks + " chunks FAILED (see console)" : "");
        shutdown();
        plugin.log().info("Regen of " + sel.describe() + " finished. " + msg);
        plugin.notify(ownerId, "<green>NaturalRegen finished.</green> <gray>" + msg);
        if (undo != null) plugin.notify(ownerId, "<gray>Changed something by mistake? <yellow>/nregen undo</yellow> puts the old blocks back.");
    }

    private void stop(String reason) {
        if (finished) return;
        // Record the partly applied chunk so undo still covers it.
        if (current != null && !current.failed && undo != null) {
            undo.writeChunk(current.cx, current.cz, current.pos, current.oldData, current.applied);
            blocksChanged += current.applied;
        }
        shutdown();
        plugin.log().warn("Regen stopped: " + reason + ". " + status());
        plugin.notify(ownerId, "<red>NaturalRegen stopped:</red> <gray>" + reason + ". Progress: " + status());
    }

    private void shutdown() {
        finished = true;
        ticker.cancel();
        diffPool.shutdownNow();
        target.removePluginChunkTickets(plugin);
        source.removePluginChunkTickets(plugin);
        if (undo != null) undo.close();
        if (plugin.getConfig().getBoolean("delete-temp-world", true)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> TempWorlds.destroy(plugin, source), 40L);
        }
        plugin.taskEnded(this);
    }

    @Override
    public void cancel(String reason) {
        stop(reason);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    // ------------------------------------------------------------------ helpers

    /** Loads one chunk in both worlds and pins it with a plugin ticket. */
    private final class Loading {
        final int cx, cz;
        volatile Chunk targetChunk, sourceChunk;
        volatile boolean error;
        int attempts;
        int waited;

        Loading(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }

        void request() {
            attempts++;
            error = false;
            if (targetChunk == null) {
                target.getChunkAtAsync(cx, cz, true).whenComplete((c, ex) -> onMain(() -> {
                    if (ex != null || c == null) { error = true; return; }
                    target.addPluginChunkTicket(cx, cz, plugin);
                    targetChunk = c;
                }));
            }
            if (sourceChunk == null) {
                source.getChunkAtAsync(cx, cz, true).whenComplete((c, ex) -> onMain(() -> {
                    if (ex != null || c == null) { error = true; return; }
                    source.addPluginChunkTicket(cx, cz, plugin);
                    sourceChunk = c;
                }));
            }
        }
    }

    private void onMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
    }

    /** The differences for one chunk, applied gradually on the main thread. */
    private final class ChunkWork {
        final int cx, cz;
        final Chunk targetChunk, sourceChunk;
        final Set<Integer> srcTiles, tgtTiles;
        boolean failed;
        int[] pos = new int[256];
        BlockData[] newData = new BlockData[256];
        BlockData[] oldData = new BlockData[256];
        int count;
        int applied;
        int[] biomePos = new int[0];
        Biome[] biomes = new Biome[0];
        int biomeCount;

        ChunkWork(int cx, int cz, Chunk tc, Chunk sc, Set<Integer> srcTiles, Set<Integer> tgtTiles) {
            this.cx = cx;
            this.cz = cz;
            this.targetChunk = tc;
            this.sourceChunk = sc;
            this.srcTiles = srcTiles;
            this.tgtTiles = tgtTiles;
        }

        void add(int p, BlockData n, BlockData o) {
            if (count == pos.length) {
                int len = pos.length * 2;
                pos = Arrays.copyOf(pos, len);
                newData = Arrays.copyOf(newData, len);
                oldData = Arrays.copyOf(oldData, len);
            }
            pos[count] = p;
            newData[count] = n;
            oldData[count] = o;
            count++;
        }

        void addBiome(int lx, int y, int lz, Biome b) {
            if (biomeCount == biomePos.length) {
                int len = Math.max(16, biomePos.length * 2);
                biomePos = Arrays.copyOf(biomePos, len);
                biomes = Arrays.copyOf(biomes, len);
            }
            biomePos[biomeCount] = pack(lx, y, lz);
            biomes[biomeCount] = b;
            biomeCount++;
        }

        /** @return true when every block of this chunk has been written. */
        boolean applyBlocks(long deadline) {
            while (applied < count) {
                if ((applied & 127) == 0 && System.nanoTime() >= deadline) return false;
                int p = pos[applied];
                int lx = p & 15, lz = (p >> 4) & 15, y = unpackY(p);
                Block b = targetChunk.getBlock(lx, y, lz);
                if (tgtTiles.contains(p)) {
                    // Empty containers first so replacing them can't spill the build's items on the ground.
                    try {
                        BlockState st = b.getState(false);
                        if (st instanceof InventoryHolder h) h.getInventory().clear();
                    } catch (Throwable ignored) {
                    }
                }
                b.setBlockData(newData[applied], false);
                if (srcTiles.contains(p)) {
                    // Copy the natural chest/spawner/etc. including its loot table and data.
                    try {
                        BlockState natural = sourceChunk.getBlock(lx, y, lz).getState();
                        natural.copy(b.getLocation()).update(true, false);
                        tilesCopied++;
                    } catch (Throwable ex) {
                        plugin.log().debug("Could not copy block entity at " + b.getLocation() + ": " + ex);
                    }
                }
                applied++;
            }
            return true;
        }
    }
}
