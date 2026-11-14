package com.daboss69yt.nullplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Takes a built structure apart and keeps its shape as a blueprint, so the Nulls can rebuild it somewhere
 * else, the Nether included. Blueprints live in plugins/NullController/blueprints as JSON.
 */
public final class Salvage implements CommandExecutor {
    private static final int MAX_VOLUME = 60000;
    private static final int TAKE_PER_TICK = 600;
    private static final int PLACE_PER_TICK = 300;

    private final Plugin plugin;
    private final NullArmy army;
    private final ConsentManager consent;
    private final BuildQueue queue;
    private final File dir;
    private final Map<UUID, Location[]> marks = new HashMap<>();

    public Salvage(Plugin plugin, NullArmy army, ConsentManager consent) {
        this.plugin = plugin;
        this.army = army;
        this.consent = consent;
        this.queue = new BuildQueue(plugin);
        this.dir = new File(plugin.getDataFolder(), "blueprints");
        dir.mkdirs();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player owner = plugin.ownerFor(sender);
        if (owner == null || !consent.isOwner(owner)) {
            sender.sendMessage(ChatColor.RED + "Access denied, or the owner is offline.");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        String reply = switch (sub) {
            case "mark1" -> mark(owner, 0);
            case "mark2" -> mark(owner, 1);
            case "take" -> args.length < 2 ? "Usage: /nullsalvage take <name>" : take(owner, args[1], true);
            case "copy" -> args.length < 2 ? "Usage: /nullsalvage copy <name>" : take(owner, args[1], false);
            case "place" -> args.length < 2 ? "Usage: /nullsalvage place <name> (look at the spot)" : place(owner, args[1]);
            case "list" -> list();
            case "delete" -> args.length < 2 ? "Usage: /nullsalvage delete <name>" : delete(args[1]);
            default -> "Usage: mark1, mark2 (look at a corner), take <name> (removes it), copy <name> (keeps it), place <name> (look at the spot), list, delete <name>.";
        };
        owner.sendMessage(ChatColor.DARK_PURPLE + reply);
        return true;
    }

    private String mark(Player owner, int which) {
        Block b = owner.getTargetBlockExact(60);
        if (b == null) return "Look at a block to mark it.";
        marks.computeIfAbsent(owner.getUniqueId(), k -> new Location[2])[which] = b.getLocation();
        return "Corner " + (which + 1) + " set at " + b.getX() + ", " + b.getY() + ", " + b.getZ() + ".";
    }

    /** Saves the marked box as a blueprint, then dismantles it top-down while the Nulls stand beside it. */
    private String take(Player owner, String raw, boolean dismantle) {
        String name = cleanName(raw);
        if (name == null) return "Name it with letters, numbers, - or _ (32 at most).";
        Location[] box = marks.get(owner.getUniqueId());
        if (box == null || box[0] == null || box[1] == null) return "Mark both corners first: mark1, mark2.";
        World w = box[0].getWorld();
        if (!w.equals(box[1].getWorld())) return "Both corners must be in the same world.";
        if (dismantle && queue.isBusy()) return "The Nulls are busy with another job. Wait for it to finish.";

        int x1 = Math.min(box[0].getBlockX(), box[1].getBlockX()), x2 = Math.max(box[0].getBlockX(), box[1].getBlockX());
        int y1 = Math.min(box[0].getBlockY(), box[1].getBlockY()), y2 = Math.max(box[0].getBlockY(), box[1].getBlockY());
        int z1 = Math.min(box[0].getBlockZ(), box[1].getBlockZ()), z2 = Math.max(box[0].getBlockZ(), box[1].getBlockZ());
        long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (volume > MAX_VOLUME) {
            return "That box is " + volume + " blocks. The limit is " + MAX_VOLUME + ", so mark a smaller one.";
        }

        JsonArray entries = new JsonArray();
        List<BuildQueue.Placement> clear = new ArrayList<>();
        for (int y = y2; y >= y1; y--) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType().isAir()) continue;
                    JsonArray e = new JsonArray();
                    e.add(x - x1);
                    e.add(y - y1);
                    e.add(z - z1);
                    e.add(b.getBlockData().getAsString());
                    entries.add(e);
                    BlockState state = b.getState();
                    if (dismantle && state instanceof Container c) {
                        // Contents drop at the site. Clearing after the drop stops a double chest duplicating items.
                        for (ItemStack item : c.getInventory().getContents()) {
                            if (item != null) w.dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), item);
                        }
                        c.getInventory().clear();
                    }
                    if (dismantle) clear.add(new BuildQueue.Placement(b.getLocation(), Material.AIR));
                }
            }
        }
        if (entries.size() == 0) return "Nothing to take in that box.";
        try {
            saveBlueprint(name, w.getName(), entries);
        } catch (IOException ex) {
            return "Could not save the blueprint: " + ex.getMessage();
        }
        if (!dismantle) {
            return "Copied " + entries.size() + " blocks as blueprint " + name + ". Nothing was removed.";
        }
        boolean started = queue.start("taking " + name + " apart", clear, TAKE_PER_TICK,
                () -> owner.sendMessage(ChatColor.DARK_PURPLE + name + " is down. Blueprint saved."));
        if (!started) return "The Nulls are busy with another job. Wait for it to finish.";
        army.sendTo(new Location(w, x1 - 2, y1, z1 - 2));
        return "Taking " + name + " apart: " + entries.size() + " blocks saved as a blueprint. The Nulls are on it.";
    }

    /** Rebuilds a saved blueprint with its lowest corner on the spot you are looking at. */
    private String place(Player owner, String raw) {
        Block aim = owner.getTargetBlockExact(60);
        if (aim == null) return "Look at the spot to rebuild on.";
        return placeAt(raw, aim.getLocation().add(0, 1, 0), owner);
    }

    /** Rebuilds a blueprint with its lowest corner at origin. The notify sender hears when it is done. */
    private String placeAt(String raw, Location origin, CommandSender notify) {
        String name = cleanName(raw);
        if (name == null) return "Name it with letters, numbers, - or _.";
        JsonObject bp;
        try {
            bp = loadBlueprint(name);
        } catch (IOException | RuntimeException ex) {
            return "No blueprint called " + name + ". Run /nullsalvage list.";
        }
        if (queue.isBusy()) return "The Nulls are busy with another job. Wait for it to finish.";

        List<BuildQueue.Placement> plan = new ArrayList<>();
        for (JsonElement el : bp.getAsJsonArray("entries")) {
            JsonArray e = el.getAsJsonArray();
            BlockData data;
            try {
                data = Bukkit.createBlockData(e.get(3).getAsString());
            } catch (IllegalArgumentException ex) {
                continue; // a block this server version doesn't know; skipped, not fatal
            }
            Location at = origin.clone().add(e.get(0).getAsInt(), e.get(1).getAsInt(), e.get(2).getAsInt());
            plan.add(new BuildQueue.Placement(at, data.getMaterial(), data));
        }
        if (plan.isEmpty()) return "The blueprint " + name + " has nothing in it.";
        plan.sort(Comparator.comparingInt(p -> p.location().getBlockY())); // bottom-up, so nothing floats

        boolean started = queue.start("rebuilding " + name, plan, PLACE_PER_TICK,
                () -> notify.sendMessage(ChatColor.DARK_PURPLE + name + " is rebuilt."));
        if (!started) return "The Nulls are busy with another job. Wait for it to finish.";
        army.sendTo(origin.clone().add(-2, 0, -2));
        boolean nether = origin.getWorld() != null && origin.getWorld().getEnvironment() == World.Environment.NETHER;
        return "Rebuilding " + name + " here: " + plan.size() + " blocks. The Nulls are on it" + (nether ? " in the Nether." : ".");
    }

    /** Console use: list, delete, and place at coordinates. Marking and taking need the owner standing there. */
    public String consoleCommand(String[] args) {
        String usage = "Usage: salvage list | delete <name> | place <name> <world> <x> <y> <z>";
        if (args.length < 2) return usage;
        switch (args[1].toLowerCase()) {
            case "list" -> {
                return list();
            }
            case "delete" -> {
                return args.length < 3 ? "Usage: salvage delete <name>" : delete(args[2]);
            }
            case "place" -> {
                if (args.length < 7) return "Usage: salvage place <name> <world> <x> <y> <z>";
                World w = Bukkit.getWorld(args[3]);
                if (w == null) return "No world called " + args[3] + ".";
                try {
                    Location at = new Location(w, Double.parseDouble(args[4]), Double.parseDouble(args[5]), Double.parseDouble(args[6]));
                    return placeAt(args[2], at, Bukkit.getConsoleSender());
                } catch (NumberFormatException ex) {
                    return "Coordinates must be numbers.";
                }
            }
            default -> {
                return usage;
            }
        }
    }

    private String list() {
        String[] files = dir.list((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) return "No blueprints yet. Mark two corners and run take.";
        StringBuilder out = new StringBuilder("Blueprints:");
        for (String f : files) out.append(' ').append(f, 0, f.length() - 5);
        return out.toString();
    }

    private String delete(String raw) {
        String name = cleanName(raw);
        if (name == null) return "Name it with letters, numbers, - or _.";
        return new File(dir, name + ".json").delete() ? "Deleted blueprint " + name + "." : "No blueprint called " + name + ".";
    }

    private void saveBlueprint(String name, String world, JsonArray entries) throws IOException {
        dir.mkdirs();
        JsonObject root = new JsonObject();
        root.addProperty("world", world);
        root.add("entries", entries);
        Files.writeString(new File(dir, name + ".json").toPath(), root.toString(), StandardCharsets.UTF_8);
    }

    private JsonObject loadBlueprint(String name) throws IOException {
        return JsonParser.parseString(Files.readString(new File(dir, name + ".json").toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String cleanName(String raw) {
        String n = raw.replaceAll("[^A-Za-z0-9_-]", "");
        return n.isEmpty() || n.length() > 32 ? null : n;
    }
}
