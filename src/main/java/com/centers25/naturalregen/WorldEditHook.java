package com.centers25.naturalregen;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Only loaded when WorldEdit is enabled. */
final class WorldEditHook {
    private WorldEditHook() {}

    record Result(Selection selection, boolean cuboid) {}

    static Result read(Player player) throws IllegalStateException {
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
        com.sk89q.worldedit.world.World weWorld = session.getSelectionWorld();
        if (weWorld == null) throw new IllegalStateException("You don't have a WorldEdit selection.");
        Region region;
        try {
            region = session.getSelection(weWorld);
        } catch (IncompleteRegionException e) {
            throw new IllegalStateException("Your WorldEdit selection is incomplete (set both points).");
        }
        World world = BukkitAdapter.adapt(weWorld);
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        Selection sel = Selection.of(world, min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
        return new Result(sel, region instanceof CuboidRegion);
    }
}
