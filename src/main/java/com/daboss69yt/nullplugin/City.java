package com.daboss69yt.nullplugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Null Civilisation. A round End platform with a ringed road and four spokes, a large castle
 * in the middle with a hall and a throne for the owner, four keeps, a spire, eight glass skyscrapers,
 * four Null quarters, and a dome of light over everything. Chorus in the footprint is cleared.
 * Built relative to the block under the owner.
 */
public final class City {

    private record Pos(int x, int y, int z) {}

    private static final int PLATFORM_R = 34;
    private static final int RING_IN = 22;
    private static final int RING_OUT = 24;
    private static final int SPOKE_START = 10;
    private static final int CASTLE = 9;
    private static final int CASTLE_H = 12;
    private static final int KEEP_EDGE = 11;
    private static final int KEEP_H = 26;
    private static final int SPIRE_H = 60;
    private static final int TOWER_R = 28;
    private static final int TOWERS = 8;
    private static final int DOME_R = 32;
    private static final int[] HOUSE_COORDS = {-14, 14};

    private final World world;
    private final int bx, by, bz;
    private final Map<Pos, Material> blocks = new LinkedHashMap<>();

    private City(Location base) {
        this.world = base.getWorld();
        this.bx = base.getBlockX();
        this.by = base.getBlockY();
        this.bz = base.getBlockZ();
    }

    /** Every block of the city. Later layers overwrite earlier ones at the same spot. */
    public static List<BuildQueue.Placement> plan(Location base) {
        City c = new City(base);
        c.platform();
        c.castle();
        c.keeps();
        c.spire();
        c.towers();
        c.quarters();
        c.dome();
        c.clearChorus();
        List<BuildQueue.Placement> out = new ArrayList<>(c.blocks.size());
        for (Map.Entry<Pos, Material> e : c.blocks.entrySet()) {
            Pos p = e.getKey();
            out.add(new BuildQueue.Placement(new Location(c.world, p.x(), p.y(), p.z()), e.getValue()));
        }
        return out;
    }

    private void put(int x, int y, int z, Material m) {
        blocks.put(new Pos(bx + x, by + y, bz + z), m);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        for (int x = x1; x <= x2; x++)
            for (int y = y1; y <= y2; y++)
                for (int z = z1; z <= z2; z++)
                    put(x, y, z, m);
    }

    private static boolean onSpoke(int x, int z) {
        boolean alongX = Math.abs(z) <= 1 && Math.abs(x) >= SPOKE_START;
        boolean alongZ = Math.abs(x) <= 1 && Math.abs(z) >= SPOKE_START;
        return alongX || alongZ;
    }

    /** Round platform: purpur rim, white ringed road, lit purple spokes, End stone elsewhere. */
    private void platform() {
        int r2 = PLATFORM_R * PLATFORM_R;
        for (int x = -PLATFORM_R; x <= PLATFORM_R; x++) {
            for (int z = -PLATFORM_R; z <= PLATFORM_R; z++) {
                int d2 = x * x + z * z;
                if (d2 > r2) continue;
                Material m;
                if (d2 >= (PLATFORM_R - 1) * (PLATFORM_R - 1)) {
                    m = Material.PURPUR_PILLAR;
                } else if (d2 >= RING_IN * RING_IN && d2 <= RING_OUT * RING_OUT) {
                    m = Material.WHITE_CONCRETE;
                } else if (onSpoke(x, z)) {
                    m = (Math.abs(x) + Math.abs(z)) % 6 == 0 ? Material.SEA_LANTERN : Material.PURPLE_CONCRETE;
                } else {
                    m = Material.END_STONE_BRICKS;
                }
                put(x, 0, z, m);
            }
        }
    }

    /** The great castle: walls with a gate on the north and south, a purpur roof, and a furnished hall. */
    private void castle() {
        for (int y = 1; y <= CASTLE_H; y++) {
            for (int x = -CASTLE; x <= CASTLE; x++) {
                for (int z = -CASTLE; z <= CASTLE; z++) {
                    boolean wall = Math.abs(x) == CASTLE || Math.abs(z) == CASTLE;
                    if (!wall) continue;
                    boolean gate = Math.abs(x) <= 1 && y <= 3 && Math.abs(z) == CASTLE;
                    if (gate) continue;
                    put(x, y, z, Material.END_STONE_BRICKS);
                }
            }
        }
        fill(-CASTLE, CASTLE_H, -CASTLE, CASTLE, CASTLE_H, CASTLE, Material.PURPUR_BLOCK);
        furnishHall();
    }

    private void furnishHall() {
        // Carpet leads to the throne at the north end.
        for (int x = -2; x <= 2; x++)
            for (int z = -6; z <= -4; z++)
                put(x, 1, z, Material.PURPLE_CARPET);
        // Throne with gold flanks.
        put(0, 1, -7, Material.POLISHED_BLACKSTONE_BRICKS);
        put(0, 2, -8, Material.CHISELED_POLISHED_BLACKSTONE);
        put(-1, 1, -7, Material.GOLD_BLOCK);
        put(1, 1, -7, Material.GOLD_BLOCK);
        // Long table down the middle, with chairs either side.
        for (int x = -5; x <= 5; x++) {
            put(x, 1, 0, Material.DARK_OAK_FENCE);
            put(x, 2, 0, Material.DARK_OAK_PRESSURE_PLATE);
        }
        for (int x = -4; x <= 4; x += 2) {
            put(x, 1, -1, Material.DARK_OAK_STAIRS);
            put(x, 1, 1, Material.DARK_OAK_STAIRS);
        }
        // Bookshelves on the side walls, chests in the corners.
        for (int z = -6; z <= 6; z += 3) {
            put(-8, 1, z, Material.BOOKSHELF);
            put(-8, 2, z, Material.BOOKSHELF);
            put(8, 1, z, Material.BOOKSHELF);
            put(8, 2, z, Material.BOOKSHELF);
        }
        put(6, 1, -6, Material.CHEST);
        put(-6, 1, 6, Material.CHEST);
        put(6, 1, 6, Material.BARREL);
        // Sea lanterns on the ceiling.
        for (int x = -6; x <= 6; x += 6)
            for (int z = -6; z <= 6; z += 6)
                put(x, CASTLE_H - 1, z, Material.SEA_LANTERN);
    }

    /** Four corner keeps, each a hollow tower with a solid cap. */
    private void keeps() {
        for (int sx : new int[]{-KEEP_EDGE, KEEP_EDGE}) {
            for (int sz : new int[]{-KEEP_EDGE, KEEP_EDGE}) {
                for (int y = 1; y <= KEEP_H; y++) {
                    for (int x = sx - 1; x <= sx + 1; x++) {
                        for (int z = sz - 1; z <= sz + 1; z++) {
                            if (Math.abs(x - sx) == 1 || Math.abs(z - sz) == 1) {
                                put(x, y, z, Material.END_STONE_BRICKS);
                            }
                        }
                    }
                }
                fill(sx - 1, KEEP_H + 1, sz - 1, sx + 1, KEEP_H + 1, sz + 1, Material.PURPUR_PILLAR);
            }
        }
    }

    /** The spire rising from the castle roof, with an End Rod on top. */
    private void spire() {
        fill(-1, CASTLE_H + 1, -1, 1, SPIRE_H, 1, Material.OBSIDIAN);
        put(0, SPIRE_H + 1, 0, Material.END_ROD);
    }

    /** Eight glass skyscrapers in a ring, with floors every six levels. */
    private void towers() {
        for (int k = 0; k < TOWERS; k++) {
            double angle = (k + 0.5) * 2 * Math.PI / TOWERS;
            int cx = (int) Math.round(TOWER_R * Math.cos(angle));
            int cz = (int) Math.round(TOWER_R * Math.sin(angle));
            int height = 26 + (k % 3) * 6;
            for (int y = 1; y <= height; y++) {
                for (int x = cx - 2; x <= cx + 1; x++) {
                    for (int z = cz - 2; z <= cz + 1; z++) {
                        boolean edge = x == cx - 2 || x == cx + 1 || z == cz - 2 || z == cz + 1;
                        if (edge) {
                            put(x, y, z, Material.PURPLE_STAINED_GLASS);
                        } else if (y % 6 == 0) {
                            put(x, y, z, Material.WHITE_CONCRETE);
                        }
                    }
                }
            }
            fill(cx - 2, height + 1, cz - 2, cx + 1, height + 1, cz + 1, Material.WHITE_CONCRETE);
        }
    }

    /** Four five-by-five Null quarters with a door facing the centre, and a chest, table, barrel and lantern each. */
    private void quarters() {
        for (int hx : HOUSE_COORDS) {
            for (int hz : HOUSE_COORDS) {
                int doorX = hx < 0 ? hx + 2 : hx - 2;
                for (int y = 1; y <= 4; y++) {
                    for (int x = hx - 2; x <= hx + 2; x++) {
                        for (int z = hz - 2; z <= hz + 2; z++) {
                            boolean wall = Math.abs(x - hx) == 2 || Math.abs(z - hz) == 2;
                            if (!wall) continue;
                            if (x == doorX && z == hz && y <= 2) continue;
                            put(x, y, z, Material.END_STONE_BRICKS);
                        }
                    }
                }
                fill(hx - 2, 5, hz - 2, hx + 2, 5, hz + 2, Material.PURPUR_BLOCK);
                put(hx, 1, hz, Material.CRAFTING_TABLE);
                put(hx - 1, 1, hz - 1, Material.CHEST);
                put(hx + 1, 1, hz + 1, Material.BARREL);
                put(hx, 4, hz, Material.LANTERN);
            }
        }
    }

    /** A hemisphere of white stained glass, one block thick, over the whole city. */
    private void dome() {
        int outer = DOME_R * DOME_R;
        int inner = (DOME_R - 1) * (DOME_R - 1);
        for (int x = -DOME_R; x <= DOME_R; x++) {
            for (int y = 1; y <= DOME_R; y++) {
                for (int z = -DOME_R; z <= DOME_R; z++) {
                    int d2 = x * x + y * y + z * z;
                    if (d2 <= outer && d2 >= inner) put(x, y, z, Material.WHITE_STAINED_GLASS);
                }
            }
        }
    }

    /** Chorus inside the footprint is cleared to air, so nothing grows into the city. */
    private void clearChorus() {
        int r2 = PLATFORM_R * PLATFORM_R;
        for (int x = -PLATFORM_R; x <= PLATFORM_R; x++) {
            for (int z = -PLATFORM_R; z <= PLATFORM_R; z++) {
                if (x * x + z * z > r2) continue;
                for (int y = 1; y <= DOME_R; y++) {
                    Material m = world.getBlockAt(bx + x, by + y, bz + z).getType();
                    if (m == Material.CHORUS_PLANT || m == Material.CHORUS_FLOWER) {
                        blocks.putIfAbsent(new Pos(bx + x, by + y, bz + z), Material.AIR);
                    }
                }
            }
        }
    }
}
