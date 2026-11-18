package com.centers25.naturalregen;

import org.bukkit.block.data.BlockData;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.centers25.core.logging.PluginLogger;
import java.util.zip.GZIPOutputStream;

/**
 * Streams the blocks a job replaced to a gzip file on a background thread.
 * Format: magic, world name, world min Y, then records:
 * 1 = palette entry (id, block data string), 2 = chunk (cx, cz, count, [packedPos, paletteId]*), 0 = end.
 */
final class UndoWriter {
    static final int MAGIC = 0x4E525531; // "NRU1"

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NaturalRegen-undo-writer");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Integer> palette = new HashMap<>();
    private final DataOutputStream out;
    private final PluginLogger log;
    private final File file;
    private volatile boolean failed;

    UndoWriter(File file, String worldName, int worldMinY, PluginLogger log) throws IOException {
        this.file = file;
        this.log = log;
        file.getParentFile().mkdirs();
        out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file), 1 << 16), 1 << 16));
        out.writeInt(MAGIC);
        out.writeUTF(worldName);
        out.writeInt(worldMinY);
    }

    File file() { return file; }

    void writeChunk(int cx, int cz, int[] pos, BlockData[] old, int count) {
        if (count <= 0) return;
        io.execute(() -> {
            if (failed) return;
            try {
                int[] ids = new int[count];
                for (int i = 0; i < count; i++) {
                    String s = old[i].getAsString();
                    Integer id = palette.get(s);
                    if (id == null) {
                        id = palette.size();
                        palette.put(s, id);
                        out.writeByte(1);
                        out.writeInt(id);
                        out.writeUTF(s);
                    }
                    ids[i] = id;
                }
                out.writeByte(2);
                out.writeInt(cx);
                out.writeInt(cz);
                out.writeInt(count);
                for (int i = 0; i < count; i++) {
                    out.writeInt(pos[i]);
                    out.writeInt(ids[i]);
                }
            } catch (IOException e) {
                failed = true;
                log.error("Undo file write failed; undo for this job will be incomplete", e);
            }
        });
    }

    void close() {
        io.execute(() -> {
            try {
                out.writeByte(0);
                out.close();
            } catch (IOException e) {
                log.error("Could not close undo file", e);
            }
        });
        io.shutdown();
    }
}
