package com.daboss69yt.nullplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The Metropolis: a six-by-six grid of districts, about 250 blocks across. Each district has a central
 * tower core with furnished floors and a staircase, plus four furnished corner towers, and each district
 * has its own facade palette. Purpur streets with lamp poles run between them. Built relative to the
 * block under the owner, centred on it. Layout and palettes are seeded from the position, so a rebuild matches.
 */
public final class Metropolis {

    private record Pos(int x, int y, int z) {}

    private static final int GRID = 6;
    private static final int DISTRICT = 42;   // one district cell, including its street on the + sides
    private static final int YARD = 38;       // paved ground inside a cell
    private static final int CORE = 22;       // central tower footprint
    private static final int CORE_H = 48;
    private static final int FLOOR_H = 6;
    private static final int TOWER = 6;

    private static final String[] WALLS = {
            "minecraft:purpur_block",
            "minecraft:quartz_block",
            "minecraft:end_stone_bricks",
            "minecraft:polished_blackstone_bricks"
    };
    private static final String[] GLASS = {
            "minecraft:purple_stained_glass",
            "minecraft:light_blue_stained_glass",
            "minecraft:white_stained_glass",
            "minecraft:magenta_stained_glass"
    };
    private static final String YARD_BLOCK = "minecraft:end_stone_bricks";
    private static final String FLOOR_BLOCK = "minecraft:smooth_quartz";
    private static final String TRIM = "minecraft:white_concrete";
    private static final String STREET = "minecraft:purpur_block";
    private static final String LAMP = "minecraft:sea_lantern";
    private static final String POLE = "minecraft:purpur_pillar";
    private static final String ROD = "minecraft:end_rod";

    private final World world;
    private final int bx, by, bz;
    private final int origin;
    private final Random rng;
    private final Map<Pos, String> cells = new LinkedHashMap<>();

    private Metropolis(Location base) {
        this.world = base.getWorld();
        this.bx = base.getBlockX();
        this.by = base.getBlockY();
        this.bz = base.getBlockZ();
        this.origin = -(GRID * DISTRICT) / 2;
        this.rng = new Random(bx * 73856093L ^ bz * 19349663L);
    }

    /** Every block of the Metropolis, with BlockData (facing, part) resolved once per distinct block type. */
    public static List<BuildQueue.Placement> plan(Location base) {
        Metropolis m = new Metropolis(base);
        m.streets();
        for (int gx = 0; gx < GRID; gx++) {
            for (int gz = 0; gz < GRID; gz++) {
                m.district(m.origin + gx * DISTRICT, m.origin + gz * DISTRICT);
            }
        }
        Map<String, BlockData> cache = new HashMap<>();
        List<BuildQueue.Placement> out = new ArrayList<>(m.cells.size());
        for (Map.Entry<Pos, String> e : m.cells.entrySet()) {
            Pos p = e.getKey();
            BlockData d = cache.computeIfAbsent(e.getValue(), s -> Bukkit.createBlockData(s));
            out.add(new BuildQueue.Placement(new Location(m.world, p.x(), p.y(), p.z()), d.getMaterial(), d));
        }
        return out;
    }

    private void put(int x, int y, int z, String spec) {
        cells.put(new Pos(bx + x, by + y, bz + z), spec);
    }

    /** Purpur streets across the whole grid, with a lamp pole at every intersection. */
    private void streets() {
        int size = GRID * DISTRICT;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                put(origin + x, 0, origin + z, STREET);
            }
        }
        for (int gx = 1; gx < GRID; gx++) {
            for (int gz = 1; gz < GRID; gz++) {
                int px = origin + gx * DISTRICT - 2;
                int pz = origin + gz * DISTRICT - 2;
                for (int y = 1; y <= 4; y++) put(px, y, pz, POLE);
                put(px, 5, pz, LAMP);
            }
        }
    }

    private void district(int ox, int oz) {
        int palette = rng.nextInt(WALLS.length);
        String wall = WALLS[palette];
        String glass = GLASS[palette];

        for (int x = 0; x < YARD; x++) {
            for (int z = 0; z < YARD; z++) {
                put(ox + x, 0, oz + z, YARD_BLOCK);
            }
        }
        core(ox + 8, oz + 8, wall, glass);
        int[] corners = {1, YARD - 1 - TOWER};
        for (int tx : corners) {
            for (int tz : corners) {
                tower(ox + tx, oz + tz, wall, glass);
            }
        }
    }

    /** The central core: a 22-by-22 glass-and-stone tower, 48 high, with furnished floors and a staircase. */
    private void core(int x0, int z0, String wall, String glass) {
        int x1 = x0 + CORE - 1;
        int z1 = z0 + CORE - 1;

        for (int y = 1; y <= CORE_H; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    if (!edge) continue;
                    boolean gate = z == z1 && Math.abs(x - (x0 + CORE / 2)) <= 1 && y <= 3;
                    if (gate) continue;
                    String spec;
                    if (y % FLOOR_H == 0 || (x + z + y) % 3 == 0) {
                        spec = wall;
                    } else {
                        spec = glass;
                    }
                    put(x, y, z, spec);
                }
            }
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                put(x, CORE_H + 1, z, TRIM);
            }
        }
        put(x0, CORE_H + 2, z0, ROD);
        put(x1, CORE_H + 2, z0, ROD);
        put(x0, CORE_H + 2, z1, ROD);
        put(x1, CORE_H + 2, z1, ROD);

        // Floor slabs. The staircase well at z0+18 is left open so the stairs can run through it.
        for (int f = 0; f <= CORE_H; f += FLOOR_H) {
            for (int x = x0 + 1; x < x1; x++) {
                for (int z = z0 + 1; z < z1; z++) {
                    if (z == z0 + 18 && x <= x0 + 6) continue;
                    put(x, f, z, FLOOR_BLOCK);
                }
            }
        }
        for (int f = 0; f < CORE_H; f += FLOOR_H) {
            furnish(x0, z0, f);
        }
        // Stairs go after the floors so they replace the slab in the well.
        for (int f = 0; f < CORE_H; f += FLOOR_H) {
            for (int i = 0; i < FLOOR_H; i++) {
                put(x0 + 1 + i, f + 1 + i, z0 + 18, "minecraft:stone_brick_stairs[facing=east]");
            }
        }
    }

    /** One room of the core: bed, chest, bookshelves, a dining table with chairs, carpet and hanging lanterns. */
    private void furnish(int x0, int z0, int f) {
        int y = f + 1;
        put(x0 + 2, y, z0 + 2, "minecraft:red_bed[facing=east,part=foot]");
        put(x0 + 3, y, z0 + 2, "minecraft:red_bed[facing=east,part=head]");
        put(x0 + 2, y, z0 + 4, "minecraft:chest[facing=north]");
        for (int z = z0 + 10; z <= z0 + 14; z++) {
            put(x0 + 2, y, z, "minecraft:bookshelf");
            put(x0 + 2, y + 1, z, "minecraft:bookshelf");
        }
        for (int x = x0 + 8; x <= x0 + 12; x++) {
            put(x, y, z0 + 8, "minecraft:oak_fence");
            put(x, y + 1, z0 + 8, "minecraft:dark_oak_pressure_plate");
        }
        for (int x = x0 + 8; x <= x0 + 12; x += 2) {
            put(x, y, z0 + 7, "minecraft:oak_stairs[facing=south]");
            put(x, y, z0 + 9, "minecraft:oak_stairs[facing=north]");
        }
        for (int x = x0 + 6; x <= x0 + 9; x++) {
            put(x, y, z0 + 13, "minecraft:purple_carpet");
        }
        int ceiling = f + FLOOR_H - 1;
        put(x0 + 6, ceiling, z0 + 6, "minecraft:lantern[hanging=true]");
        put(x0 + 15, ceiling, z0 + 6, "minecraft:lantern[hanging=true]");
        put(x0 + 6, ceiling, z0 + 15, "minecraft:lantern[hanging=true]");
        put(x0 + 15, ceiling, z0 + 15, "minecraft:lantern[hanging=true]");
    }

    /** A six-by-six glass tower, 30 to 60 high, with floors, a lantern per floor, a chest, and a roof. */
    private void tower(int x0, int z0, String wall, String glass) {
        int h = 30 + rng.nextInt(31);
        int x1 = x0 + TOWER - 1;
        int z1 = z0 + TOWER - 1;

        for (int y = 1; y <= h; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    if (!edge) continue;
                    put(x, y, z, y % FLOOR_H == 0 ? wall : glass);
                }
            }
        }
        for (int f = 0; f <= h; f += FLOOR_H) {
            for (int x = x0 + 1; x < x1; x++) {
                for (int z = z0 + 1; z < z1; z++) {
                    put(x, f, z, FLOOR_BLOCK);
                }
            }
            if (f < h) {
                put(x0 + 2, f + FLOOR_H - 1, z0 + 2, "minecraft:lantern[hanging=true]");
                put(x1 - 2, f + 1, z1 - 2, "minecraft:chest[facing=north]");
            }
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                put(x, h + 1, z, TRIM);
            }
        }
    }
}
