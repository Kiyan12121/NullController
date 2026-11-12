package com.daboss69yt.nullplugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The Metropolis: a five-by-five grid of districts, each with four glass skyscrapers of random height,
 * joined by purpur streets. About 170,000 blocks across roughly 180 blocks square. Built relative to the
 * block under the owner, centred on it. Heights are seeded from the position, so a rebuild matches.
 */
public final class Metropolis {

    private record Pos(int x, int y, int z) {}

    private static final int GRID = 5;
    private static final int DISTRICT = 36;
    private static final int FLOOR = 32;

    private final World world;
    private final int bx, by, bz;
    private final int origin;
    private final Random rng;
    private final Map<Pos, Material> blocks = new LinkedHashMap<>();

    private Metropolis(Location base) {
        this.world = base.getWorld();
        this.bx = base.getBlockX();
        this.by = base.getBlockY();
        this.bz = base.getBlockZ();
        this.origin = -(GRID * DISTRICT) / 2;
        this.rng = new Random(bx * 73856093L ^ bz * 19349663L);
    }

    public static List<BuildQueue.Placement> plan(Location base) {
        Metropolis m = new Metropolis(base);
        m.streets();
        for (int gx = 0; gx < GRID; gx++) {
            for (int gz = 0; gz < GRID; gz++) {
                m.district(m.origin + gx * DISTRICT, m.origin + gz * DISTRICT);
            }
        }
        List<BuildQueue.Placement> out = new ArrayList<>(m.blocks.size());
        for (Map.Entry<Pos, Material> e : m.blocks.entrySet()) {
            Pos p = e.getKey();
            out.add(new BuildQueue.Placement(new Location(m.world, p.x(), p.y(), p.z()), e.getValue()));
        }
        return out;
    }

    private void put(int x, int y, int z, Material m) {
        blocks.put(new Pos(bx + x, by + y, bz + z), m);
    }

    /** The whole grid's ground is purpur street; districts overwrite the parts that are floor. */
    private void streets() {
        int size = GRID * DISTRICT;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                put(origin + x, 0, origin + z, Material.PURPUR_BLOCK);
            }
        }
    }

    private void district(int ox, int oz) {
        for (int x = 0; x < FLOOR; x++) {
            for (int z = 0; z < FLOOR; z++) {
                put(ox + x, 0, oz + z, Material.END_STONE_BRICKS);
            }
        }
        for (int tx : new int[]{4, 20}) {
            for (int tz : new int[]{4, 20}) {
                tower(ox + tx, oz + tz);
            }
        }
    }

    /** An eight-by-eight glass tower, 20 to 60 blocks high, with a floor slab every eight levels and a roof. */
    private void tower(int x0, int z0) {
        int height = 20 + rng.nextInt(41);
        for (int y = 1; y <= height; y++) {
            for (int x = x0; x < x0 + 8; x++) {
                for (int z = z0; z < z0 + 8; z++) {
                    boolean edge = x == x0 || x == x0 + 7 || z == z0 || z == z0 + 7;
                    if (edge) {
                        put(x, y, z, Material.PURPLE_STAINED_GLASS);
                    } else if (y % 8 == 0) {
                        put(x, y, z, Material.WHITE_CONCRETE);
                    }
                }
            }
        }
        for (int x = x0; x < x0 + 8; x++) {
            for (int z = z0; z < z0 + 8; z++) {
                put(x, height + 1, z, Material.WHITE_CONCRETE);
            }
        }
    }
}
