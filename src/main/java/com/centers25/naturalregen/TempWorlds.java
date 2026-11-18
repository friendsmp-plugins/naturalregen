package com.centers25.naturalregen;

import net.kyori.adventure.util.TriState;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/**
 * Creates the hidden "source" world: same seed, generator and dimension as the target,
 * so its freshly generated chunks are exactly what the seed would have placed there.
 */
final class TempWorlds {
    static final String PREFIX = "nregen_";

    private TempWorlds() {}

    static String nameFor(World target, long seed) {
        return PREFIX + target.getEnvironment().name().toLowerCase(Locale.ROOT) + "_" + Long.toUnsignedString(seed, 36);
    }

    static boolean isTemp(World world) {
        return world.getName().startsWith(PREFIX);
    }

    /** Must run on the main thread. May lag for a few seconds while the world is created. */
    static World create(World target, long seed) {
        String name = nameFor(target, seed);
        World existing = Bukkit.getWorld(name);
        if (existing != null) return existing;
        WorldCreator creator = new WorldCreator(name)
                .copy(target)
                .seed(seed)
                .generateStructures(target.canGenerateStructures())
                .keepSpawnLoaded(TriState.FALSE);
        World world = creator.createWorld();
        if (world == null) throw new IllegalStateException("The server refused to create the temporary world " + name);
        world.setAutoSave(false);
        return world;
    }

    /** Unloads the world now, then deletes its folder in the background a few seconds later. */
    static void destroy(NaturalRegenPlugin plugin, World world) {
        if (world == null || !isTemp(world)) return;
        String name = world.getName();
        File folder = world.getWorldFolder();
        world.getPlayers().forEach(p -> p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()));
        if (!Bukkit.unloadWorld(world, false)) {
            plugin.log().warn("Could not unload temporary world " + name + "; it will be removed next time.");
            return;
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> deleteFolder(plugin, name, folder), 100L);
    }

    private static void deleteFolder(NaturalRegenPlugin plugin, String name, File folder) {
        try {
            File canon = folder.getCanonicalFile();
            File container = Bukkit.getWorldContainer().getCanonicalFile();
            // Safety: only ever delete a folder that is named exactly like our temp world.
            if (!canon.getName().equalsIgnoreCase(name) || canon.equals(container) || !canon.getName().startsWith(PREFIX)) {
                plugin.log().warn("Not deleting " + canon + " (folder name does not match " + name + ").");
                return;
            }
            for (World w : Bukkit.getWorlds()) {
                if (w.getWorldFolder().getCanonicalFile().equals(canon)) {
                    plugin.log().warn("Not deleting " + canon + " because a loaded world uses it.");
                    return;
                }
            }
            if (!canon.exists()) return;
            Files.walkFileTree(canon.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            plugin.log().info("Deleted temporary world folder " + canon);
        } catch (IOException e) {
            plugin.log().warn("Could not fully delete temporary world " + name, e);
        }
    }
}
