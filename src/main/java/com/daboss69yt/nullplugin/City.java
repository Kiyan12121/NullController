package com.daboss69yt.nullplugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Null Civilisation: a walled End city. A central Hall for the owner, four towers,
 * a spire, and sixteen quarters for the Nulls. Built relative to the block under the owner.
 */
public final class City {

    private record Pos(int x, int y, int z) {}

    private static final int HALF = 30;
    private static final int WALL_H = 7;
    private static final int TOWER_H = 14;
    private static final int TOWER_EDGE = 27;
    private static final int HALL_HALF = 7;
    private static final int HALL_H = 10;
    private static final int SPIRE_H = 48;
    private static final int[] HOUSE_COORDS = {-22, -14, 14, 22};

    private final World world;
    private final int bx, by, bz;
    private final Map<Pos, Material> blocks = new LinkedHashMap<>();

    private City(Location base) {
        this.world = base.getWorld();
        this.bx = base.getBlockX();
        this.by = base.getBlockY();
        this.bz = base.getBlockZ();
    }

    /** Returns every block of the city, with later layers overwriting earlier ones at the same spot. */
    public static List<BuildQueue.Placement> plan(Location base) {
        City c = new City(base);
        c.platform();
        c.wall();
        c.towers();
        c.hall();
        c.spire();
        c.quarters();
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

    /** Floor of End stone bricks with a purpur border, flush with the block under the owner. */
    private void platform() {
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                boolean border = Math.abs(x) == HALF || Math.abs(z) == HALF;
                put(x, 0, z, border ? Material.PURPUR_PILLAR : Material.END_STONE_BRICKS);
            }
        }
    }

    /** Outer wall with a three-block gate on each side. */
    private void wall() {
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                boolean edge = Math.abs(x) == HALF || Math.abs(z) == HALF;
                if (!edge) continue;
                boolean gate = (Math.abs(x) <= 1 && Math.abs(z) == HALF) || (Math.abs(z) <= 1 && Math.abs(x) == HALF);
                for (int y = 1; y <= WALL_H; y++) {
                    if (gate && y <= 3) continue;
                    put(x, y, z, Material.PURPUR_BLOCK);
                }
            }
        }
    }

    /** Four corner towers, each with a solid roof. */
    private void towers() {
        for (int sx : new int[]{-TOWER_EDGE, TOWER_EDGE}) {
            for (int sz : new int[]{-TOWER_EDGE, TOWER_EDGE}) {
                for (int y = 1; y <= TOWER_H; y++) {
                    for (int x = sx - 2; x <= sx + 2; x++) {
                        for (int z = sz - 2; z <= sz + 2; z++) {
                            boolean wallCell = Math.abs(x - sx) == 2 || Math.abs(z - sz) == 2;
                            if (wallCell) put(x, y, z, Material.END_STONE_BRICKS);
                        }
                    }
                }
                fill(sx - 2, TOWER_H + 1, sz - 2, sx + 2, TOWER_H + 1, sz + 2, Material.PURPUR_PILLAR);
            }
        }
    }

    /** The Hall: the owner's house, with a throne at the far end and a door facing the gate. */
    private void hall() {
        for (int y = 1; y <= HALL_H; y++) {
            for (int x = -HALL_HALF; x <= HALL_HALF; x++) {
                for (int z = -HALL_HALF; z <= HALL_HALF; z++) {
                    boolean wallCell = Math.abs(x) == HALL_HALF || Math.abs(z) == HALL_HALF;
                    if (!wallCell) continue;
                    boolean door = z == HALL_HALF && x == 0 && y <= 3;
                    if (door) continue;
                    put(x, y, z, Material.END_STONE_BRICKS);
                }
            }
        }
        fill(-HALL_HALF, HALL_H + 1, -HALL_HALF, HALL_HALF, HALL_H + 1, HALL_HALF, Material.PURPUR_BLOCK);
        put(0, 1, -5, Material.POLISHED_BLACKSTONE_BRICKS);
        put(0, 2, -5, Material.CHISELED_STONE_BRICKS);
    }

    /** A spire rising from the Hall roof, with an End Rod on top. */
    private void spire() {
        fill(-1, HALL_H + 2, -1, 1, SPIRE_H, 1, Material.OBSIDIAN);
        put(0, SPIRE_H + 1, 0, Material.END_ROD);
    }

    /** Sixteen five-by-five quarters for the Nulls, each door facing the centre. */
    private void quarters() {
        for (int hx : HOUSE_COORDS) {
            for (int hz : HOUSE_COORDS) {
                int doorX = hx < 0 ? hx + 2 : hx - 2;
                for (int y = 1; y <= 4; y++) {
                    for (int x = hx - 2; x <= hx + 2; x++) {
                        for (int z = hz - 2; z <= hz + 2; z++) {
                            boolean wallCell = Math.abs(x - hx) == 2 || Math.abs(z - hz) == 2;
                            if (!wallCell) continue;
                            boolean door = x == doorX && z == hz && y <= 2;
                            if (door) continue;
                            put(x, y, z, Material.END_STONE_BRICKS);
                        }
                    }
                }
                fill(hx - 2, 5, hz - 2, hx + 2, 5, hz + 2, Material.PURPUR_BLOCK);
                put(hx, 4, hz, Material.LANTERN);
            }
        }
    }
}
