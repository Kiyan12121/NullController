package com.daboss69yt.nullplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The chests the Nulls build and use: deposit (packs empty here), supply (golden apples and beef
 * for low Nulls), stash (arrested players' gear) and loot (overflow from the deposit chest).
 * Saved in config.yml under null-chests, one "KIND,world,x,y,z" entry each.
 */
public final class Chests {

    public enum Kind { DEPOSIT, SUPPLY, STASH, LOOT }

    private static final String KEY = "null-chests";

    private final NullControllerPlugin plugin;
    private final Map<Kind, List<Location>> chests = new EnumMap<>(Kind.class);
    private final Random rng = new Random();

    public Chests(NullControllerPlugin plugin) {
        this.plugin = plugin;
        for (Kind k : Kind.values()) chests.put(k, new ArrayList<>());
        load();
    }

    private void load() {
        for (String raw : plugin.getConfig().getStringList(KEY)) {
            String[] p = raw.split(",");
            if (p.length != 5) continue;
            try {
                Kind kind = Kind.valueOf(p[0].trim().toUpperCase());
                World w = Bukkit.getWorld(p[1].trim());
                if (w == null) continue;
                chests.get(kind).add(new Location(w, Integer.parseInt(p[2].trim()),
                        Integer.parseInt(p[3].trim()), Integer.parseInt(p[4].trim())));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping bad null-chests entry: " + raw);
            }
        }
    }

    private void save() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Kind, List<Location>> e : chests.entrySet()) {
            for (Location l : e.getValue()) {
                out.add(e.getKey().name() + "," + l.getWorld().getName() + "," + l.getBlockX() + ","
                        + l.getBlockY() + "," + l.getBlockZ());
            }
        }
        plugin.getConfig().set(KEY, out);
        plugin.saveConfig();
    }

    /** Registers a chest block that now exists in the world. */
    public void add(Kind kind, Block block) {
        chests.get(kind).add(block.getLocation());
        save();
    }

    /** The nearest chest of this kind in the same world, or null. Forgets any entry whose chest is gone. */
    public Container nearest(Kind kind, Location from) {
        Container best = null;
        double bestDist = Double.MAX_VALUE;
        List<Location> list = chests.get(kind);
        for (int i = list.size() - 1; i >= 0; i--) {
            Location l = list.get(i);
            if (l.getWorld() == null) {
                list.remove(i);
                continue;
            }
            if (!(l.getBlock().getState() instanceof Container c)) {
                list.remove(i);
                continue;
            }
            if (from == null || from.getWorld() != l.getWorld()) continue;
            double d = l.distance(from);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    /** Builds a new chest of this kind near the anchor, on the first free spot found. Returns its block, or null. */
    public Block place(Kind kind, Location anchor) {
        World w = anchor.getWorld();
        if (w == null) return null;
        for (int tries = 0; tries < 40; tries++) {
            int x = anchor.getBlockX() + rng.nextInt(9) - 4;
            int z = anchor.getBlockZ() + rng.nextInt(9) - 4;
            for (int y = anchor.getBlockY() + 3; y >= anchor.getBlockY() - 3; y--) {
                Block floor = w.getBlockAt(x, y - 1, z);
                Block spot = w.getBlockAt(x, y, z);
                if (floor.getType().isSolid() && spot.getType().isAir()) {
                    spot.setType(Material.CHEST);
                    add(kind, spot);
                    return spot;
                }
            }
        }
        return null;
    }

    /** A fresh supply chest: golden apples and cooked beef. */
    public static void stockSupply(Container c) {
        c.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE, 16), new ItemStack(Material.COOKED_BEEF, 32));
    }
}
