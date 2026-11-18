package com.centers25.naturalregen;

import com.centers25.core.logging.PluginLogger;
import com.centers25.core.logging.PluginLogs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class NaturalRegenPlugin extends JavaPlugin implements TabExecutor {
    static final UUID CONSOLE = new UUID(0, 0);
    private static final String LEGACY_FOLDER = "NaturalRegen";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final List<String> SUBCOMMANDS = List.of(
            "pos1", "pos2", "we", "sel", "fullheight", "info", "start", "confirm", "status", "cancel", "undo", "speed", "cleanup", "help");

    private final Map<UUID, Corners> corners = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private Task active;
    private volatile long budgetNanos;
    private PluginLogger log;

    private static final class Corners {
        String world;
        int[] a, b;
        Selection override; // set by /nregen we, /nregen sel or /nregen fullheight
    }

    private record Pending(String kind, Selection selection, File undoFile, long expires) {}

    static Component mm(String s) {
        return MM.deserialize(s);
    }

    @Override
    public void onEnable() {
        log = PluginLogs.get(this);
        migrateLegacyFolder();
        saveDefaultConfig();
        budgetNanos = Math.max(1, getConfig().getInt("budget-ms", 20)) * 1_000_000L;
        var cmd = getCommand("nregen");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        // Remove temp worlds left behind by a crash or restart.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (World w : new ArrayList<>(Bukkit.getWorlds())) {
                if (TempWorlds.isTemp(w)) TempWorlds.destroy(this, w);
            }
        }, 20L);
    }

    @Override
    public void onDisable() {
        if (active != null && !active.isFinished()) active.cancel("the plugin was disabled");
    }

    PluginLogger log() {
        return log;
    }

    private void migrateLegacyFolder() {
        java.io.File legacy = new java.io.File(getDataFolder().getParentFile(), LEGACY_FOLDER);
        if (!legacy.isDirectory() || getDataFolder().exists()) return;
        if (legacy.renameTo(getDataFolder())) {
            log.info("Moved existing data from plugins/" + LEGACY_FOLDER + " to plugins/" + getDataFolder().getName() + ".");
        } else {
            log.warn("Could not move plugins/" + LEGACY_FOLDER + " to plugins/" + getDataFolder().getName() + ".");
        }
    }

    long budgetNanos() { return budgetNanos; }

    File undoFolder() { return new File(getDataFolder(), "undo"); }

    void taskEnded(Task t) {
        if (active == t) active = null;
    }

    void notify(UUID owner, String message) {
        Component c = mm(message);
        Player p = owner.equals(CONSOLE) ? null : Bukkit.getPlayer(owner);
        if (p != null) p.sendMessage(c);
        else Bukkit.getConsoleSender().sendMessage(c);
    }

    private static UUID id(CommandSender s) {
        return s instanceof Player p ? p.getUniqueId() : CONSOLE;
    }

    private static void msg(CommandSender s, String m) {
        s.sendMessage(mm(m));
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "pos1", "pos2" -> setPos(sender, sub.equals("pos1"), args);
                case "we", "worldedit" -> fromWorldEdit(sender);
                case "sel" -> setSel(sender, args);
                case "fullheight" -> fullHeight(sender);
                case "info" -> info(sender);
                case "start" -> start(sender);
                case "confirm" -> confirm(sender);
                case "status" -> msg(sender, active == null ? "<gray>Nothing is running." : "<green>Running:</green> <gray>" + active.status());
                case "cancel" -> {
                    if (active == null) msg(sender, "<gray>Nothing is running.");
                    else active.cancel("cancelled by " + sender.getName());
                }
                case "undo" -> undo(sender);
                case "speed" -> speed(sender, args);
                case "cleanup" -> cleanup(sender);
                default -> help(sender, label);
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            msg(sender, "<red>" + e.getMessage());
        } catch (Exception e) {
            log().error("Command failed", e);
            msg(sender, "<red>Something went wrong: " + e.getMessage());
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        msg(s, "<gold><b>NaturalRegen</b></gold> <gray>— reset an area to what the seed generated");
        msg(s, "<yellow>/" + label + " we</yellow> <gray>use your WorldEdit selection (//pos1, //pos2 or wand)");
        msg(s, "<yellow>/" + label + " pos1 | pos2 [x y z]</yellow> <gray>set a corner (your feet if no coords)");
        msg(s, "<yellow>/" + label + " sel <world> <x1> <y1> <z1> <x2> <y2> <z2></yellow> <gray>set by coordinates");
        msg(s, "<yellow>/" + label + " fullheight</yellow> <gray>stretch the selection from bedrock to build limit");
        msg(s, "<yellow>/" + label + " info</yellow> <gray>show the selection");
        msg(s, "<yellow>/" + label + " start</yellow> <gray>then <yellow>/" + label + " confirm</yellow> <gray>to run");
        msg(s, "<yellow>/" + label + " status | cancel | undo | speed <ms> | cleanup");
    }

    private Corners cornersOf(CommandSender s) {
        return corners.computeIfAbsent(id(s), k -> new Corners());
    }

    private void setPos(CommandSender sender, boolean first, String[] args) {
        Corners c = cornersOf(sender);
        int[] p;
        String world;
        if (args.length >= 4) {
            p = new int[]{parseInt(args[1]), parseInt(args[2]), parseInt(args[3])};
            if (sender instanceof Player pl) world = pl.getWorld().getName();
            else if (c.world != null) world = c.world;
            else throw new IllegalStateException("From console use /nregen sel <world> ...");
        } else if (sender instanceof Player pl) {
            Location l = pl.getLocation();
            p = new int[]{l.getBlockX(), l.getBlockY(), l.getBlockZ()};
            world = pl.getWorld().getName();
        } else {
            throw new IllegalStateException("Usage: /nregen " + (first ? "pos1" : "pos2") + " <x> <y> <z>");
        }
        if (c.world != null && !c.world.equals(world)) {
            c.a = null;
            c.b = null;
        }
        c.world = world;
        c.override = null;
        if (first) c.a = p; else c.b = p;
        msg(sender, "<green>" + (first ? "Pos1" : "Pos2") + " set to</green> <white>" + p[0] + ", " + p[1] + ", " + p[2] + "</white> <gray>in " + world);
        Selection sel = currentOrNull(sender);
        if (sel != null) msg(sender, "<gray>Selection: " + summary(sel));
    }

    private void fromWorldEdit(CommandSender sender) {
        if (!(sender instanceof Player p)) throw new IllegalStateException("Only players have a WorldEdit selection.");
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) throw new IllegalStateException("WorldEdit isn't installed.");
        WorldEditHook.Result r = WorldEditHook.read(p);
        Corners c = cornersOf(sender);
        c.override = r.selection();
        c.world = r.selection().world().getName();
        msg(sender, "<green>Using your WorldEdit selection:</green> <gray>" + summary(r.selection()));
        if (!r.cuboid()) msg(sender, "<yellow>That isn't a box selection, so its whole bounding box will be reset.");
    }

    private void setSel(CommandSender sender, String[] args) {
        if (args.length < 8) throw new IllegalArgumentException("Usage: /nregen sel <world> <x1> <y1> <z1> <x2> <y2> <z2>");
        World w = Bukkit.getWorld(args[1]);
        if (w == null) throw new IllegalArgumentException("No world called " + args[1]);
        Selection sel = Selection.of(w, parseInt(args[2]), parseInt(args[3]), parseInt(args[4]),
                parseInt(args[5]), parseInt(args[6]), parseInt(args[7]));
        Corners c = cornersOf(sender);
        c.override = sel;
        c.world = w.getName();
        msg(sender, "<green>Selection set:</green> <gray>" + summary(sel));
    }

    private void fullHeight(CommandSender sender) {
        Selection sel = current(sender).fullHeight();
        Corners c = cornersOf(sender);
        c.override = sel;
        msg(sender, "<green>Selection now runs from Y " + sel.minY() + " to " + sel.maxY() + ":</green> <gray>" + summary(sel));
    }

    private Selection currentOrNull(CommandSender sender) {
        Corners c = corners.get(id(sender));
        if (c == null) return null;
        if (c.override != null) return c.override;
        if (c.a == null || c.b == null || c.world == null) return null;
        World w = Bukkit.getWorld(c.world);
        if (w == null) return null;
        return Selection.of(w, c.a[0], c.a[1], c.a[2], c.b[0], c.b[1], c.b[2]);
    }

    private Selection current(CommandSender sender) {
        Selection s = currentOrNull(sender);
        if (s == null) {
            throw new IllegalStateException("No selection yet. Use /nregen we (WorldEdit selection) or /nregen pos1 + /nregen pos2.");
        }
        return s;
    }

    private static String summary(Selection s) {
        return String.format("%s • %d × %d × %d (%,d blocks, %,d chunks)", s.describe(), s.sizeX(), s.sizeY(), s.sizeZ(), s.volume(), s.chunkCount());
    }

    private long seedFor(World w) {
        long o = getConfig().getLong("seed-override", 0L);
        return o != 0L ? o : w.getSeed();
    }

    private void info(CommandSender sender) {
        Selection s = current(sender);
        msg(sender, "<gold>Selection:</gold> <gray>" + summary(s));
        msg(sender, "<gold>Seed used:</gold> <white>" + seedFor(s.world()) + "</white>");
    }

    private void start(CommandSender sender) {
        if (active != null) throw new IllegalStateException("A job is already running: " + active.status());
        Selection s = current(sender);
        if (TempWorlds.isTemp(s.world())) throw new IllegalStateException("That's NaturalRegen's temporary world.");
        if (s.minY() > s.maxY()) throw new IllegalStateException("The selection is outside the world's height.");
        long seed = seedFor(s.world());
        int timeout = getConfig().getInt("confirm-timeout-seconds", 30);
        pending.put(id(sender), new Pending("regen", s, null, System.currentTimeMillis() + timeout * 1000L));
        msg(sender, "<gold><b>About to reset this area to natural terrain:</b></gold>");
        msg(sender, "<gray>" + summary(s));
        msg(sender, "<gray>World <white>" + s.world().getName() + "</white>, seed <white>" + seed + "</white>");
        msg(sender, "<gray>Everything inside — blocks, chests, item frames, armor stands, mobs — will be replaced.");
        if (s.minY() > s.world().getMinHeight() || s.maxY() < s.world().getMaxHeight() - 1) {
            msg(sender, "<yellow>Only Y " + s.minY() + " to " + s.maxY() + " is selected. Basements/tunnels outside that stay. Use /nregen fullheight to include everything.");
        }
        long playersInside = s.world().getPlayers().stream()
                .filter(p -> s.contains(p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ())).count();
        if (playersInside > 0) msg(sender, "<yellow>" + playersInside + " player(s) are standing inside the area and could end up inside terrain.");
        msg(sender, "<green>Type <b>/nregen confirm</b> within " + timeout + "s to start.");
    }

    private void confirm(CommandSender sender) {
        Pending p = pending.remove(id(sender));
        if (p == null || p.expires() < System.currentTimeMillis()) {
            throw new IllegalStateException("Nothing to confirm (or it expired). Run /nregen start or /nregen undo first.");
        }
        if (active != null) throw new IllegalStateException("A job is already running: " + active.status());
        if (p.kind().equals("undo")) {
            try {
                active = new UndoJob(this, p.undoFile(), id(sender));
            } catch (Exception e) {
                throw new IllegalStateException("Couldn't start undo: " + e.getMessage());
            }
            msg(sender, "<green>Undo started.</green> <gray>Progress shows on your action bar. /nregen status, /nregen cancel");
            return;
        }
        Selection s = p.selection();
        long seed = seedFor(s.world());
        msg(sender, "<gray>Creating the temporary seed world (the server may pause for a few seconds)...");
        World source;
        try {
            source = TempWorlds.create(s.world(), seed);
        } catch (Exception e) {
            log().error("Temp world creation failed", e);
            throw new IllegalStateException("Couldn't create the temporary world: " + e.getMessage());
        }
        active = new RegenJob(this, s, source, seed, id(sender));
        msg(sender, "<green>Started!</green> <gray>Progress shows on your action bar. /nregen status, /nregen speed <ms>, /nregen cancel");
    }

    private void undo(CommandSender sender) {
        if (active != null) throw new IllegalStateException("A job is already running: " + active.status());
        File[] files = undoFolder().listFiles((d, n) -> n.endsWith(".nru"));
        if (files == null || files.length == 0) throw new IllegalStateException("There is nothing to undo.");
        File newest = Arrays.stream(files).max(Comparator.comparing(File::getName)).orElseThrow();
        String world;
        try {
            world = UndoJob.peekWorld(newest);
        } catch (Exception e) {
            throw new IllegalStateException("The undo file " + newest.getName() + " can't be read: " + e.getMessage());
        }
        int timeout = getConfig().getInt("confirm-timeout-seconds", 30);
        pending.put(id(sender), new Pending("undo", null, newest, System.currentTimeMillis() + timeout * 1000L));
        msg(sender, "<gold>This puts back the blocks from the last regen in <white>" + world + "</white> (job " + newest.getName() + ").");
        msg(sender, "<gray>Only blocks come back: chest contents, entities and biomes from before don't.");
        msg(sender, "<green>Type <b>/nregen confirm</b> within " + timeout + "s.");
    }

    private void speed(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "<gray>Current budget: <white>" + budgetNanos / 1_000_000 + " ms</white> per tick (out of 50). /nregen speed <1-45>");
            return;
        }
        int ms = Math.max(1, Math.min(45, parseInt(args[1])));
        budgetNanos = ms * 1_000_000L;
        getConfig().set("budget-ms", ms);
        saveConfig();
        msg(sender, "<green>Now using up to " + ms + " ms per tick.");
    }

    private void cleanup(CommandSender sender) {
        if (active != null) throw new IllegalStateException("Wait until the current job finishes.");
        int n = 0;
        for (World w : new ArrayList<>(Bukkit.getWorlds())) {
            if (TempWorlds.isTemp(w)) {
                TempWorlds.destroy(this, w);
                n++;
            }
        }
        msg(sender, "<green>Removed " + n + " temporary world(s).");
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + s + "' isn't a whole number.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(p)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sel")) {
            return Bukkit.getWorlds().stream().map(World::getName).filter(n -> !n.startsWith(TempWorlds.PREFIX)).toList();
        }
        return List.of();
    }
}
