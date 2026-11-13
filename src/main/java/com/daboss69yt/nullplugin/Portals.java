package com.daboss69yt.nullplugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Real nether portal blocks. A Null walks into one and comes out of its pair in the other dimension.
 * Overworld and Nether coordinates scale by eight, the way the game links portals.
 */
public final class Portals {
    /** How far from a spot the Nulls look for a portal block. */
    static final int FIND_RADIUS = 12;
    /** How far from the scaled spot the matching portal is looked for on the other side. */
    static final int PAIR_RADIUS = 16;

    private Portals() {
    }

    /** The nearest nether portal block within FIND_RADIUS of the spot, or null if there isn't one. */
    public static Location portalNear(Location spot) {
        World w = spot.getWorld();
        if (w == null) return null;
        return search(w, spot.getBlockX(), spot.getBlockY(), spot.getBlockZ(), FIND_RADIUS);
    }

    /** The portal on the other side: the scaled spot in the other dimension, searched for a portal block. */
    public static Location pairedPortal(Location portal) {
        World here = portal.getWorld();
        if (here == null) return null;
        World other = otherWorld(here);
        if (other == null) return null;
        boolean toNether = here.getEnvironment() == World.Environment.NORMAL;
        int x = toNether ? Math.floorDiv(portal.getBlockX(), 8) : portal.getBlockX() * 8;
        int z = toNether ? Math.floorDiv(portal.getBlockZ(), 8) : portal.getBlockZ() * 8;
        return search(other, x, portal.getBlockY(), z, PAIR_RADIUS);
    }

    /** The overworld and the Nether for this world, or null for The End. */
    public static World otherWorld(World w) {
        return switch (w.getEnvironment()) {
            case NORMAL -> findByEnvironment(World.Environment.NETHER);
            case NETHER -> findByEnvironment(World.Environment.NORMAL);
            default -> null;
        };
    }

    private static World findByEnvironment(World.Environment env) {
        for (World w : org.bukkit.Bukkit.getWorlds()) {
            if (w.getEnvironment() == env) return w;
        }
        return null;
    }

    private static Location search(World w, int cx, int cy, int cz, int radius) {
        Location best = null;
        double bestDist = Double.MAX_VALUE;
        int minY = Math.max(w.getMinHeight(), cy - radius);
        int maxY = Math.min(w.getMaxHeight() - 1, cy + radius);
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() != Material.NETHER_PORTAL) continue;
                    double dx = x - cx, dy = y - cy, dz = z - cz;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bestDist) {
                        bestDist = d;
                        best = b.getLocation().add(0.5, 0.5, 0.5);
                    }
                }
            }
        }
        return best;
    }

    /** Rough travel time in seconds: walking speed of about four blocks a second. */
    public static double walkSeconds(Location from, Location to) {
        return from.distance(to) / 4.0;
    }
}
