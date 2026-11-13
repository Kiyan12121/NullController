package com.daboss69yt.nullplugin;

import com.daboss69yt.nullplugin.BuildQueue.Placement;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry: each method returns the block placements for a structure.
 * Nothing here touches the world, so every layout can be reasoned about on its own.
 * Coordinates are relative to a base point on the ground. X and Z are horizontal, Y is up.
 */
public final class Structures {

    private Structures() {}

    private static Placement at(World w, int bx, int by, int bz, int x, int y, int z, Material m) {
        return new Placement(new Location(w, bx + x, by + y, bz + z), m);
    }

    /** 17x17 walled fortress, 22 tall, four corner towers, battlements, beacons and iron bar windows. */
    public static List<Placement> voidCitadel(Location base) {
        World w = base.getWorld();
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        int size = 17, half = 8, height = 22;
        List<Placement> out = new ArrayList<>();

        // Floor
        for (int x = -half; x <= half; x++)
            for (int z = -half; z <= half; z++)
                out.add(at(w, bx, by, bz, x, 0, z, Material.OBSIDIAN));

        // Walls, with iron bar windows every 4 blocks at mid height
        for (int y = 1; y < height; y++) {
            for (int x = -half; x <= half; x++) {
                for (int z = -half; z <= half; z++) {
                    boolean edge = Math.abs(x) == half || Math.abs(z) == half;
                    if (!edge) continue;
                    Material m = Material.OBSIDIAN;
                    boolean window = y >= 8 && y <= 11 && (x + z) % 4 == 0 && !isCorner(x, z, half);
                    if (window) m = Material.IRON_BARS;
                    if (y == height - 1 && (x + z) % 2 == 0) m = Material.CRYING_OBSIDIAN; // battlement tops
                    out.add(at(w, bx, by, bz, x, y, z, m));
                }
            }
        }

        // Four corner towers, 3x3, rising to height + 4
        int[][] corners = {{-half, -half}, {half, -half}, {-half, half}, {half, half}};
        for (int[] c : corners) {
            int tx = c[0] > 0 ? half - 2 : -half;
            int tz = c[1] > 0 ? half - 2 : -half;
            for (int y = height; y < height + 4; y++)
                for (int dx = 0; dx < 3; dx++)
                    for (int dz = 0; dz < 3; dz++) {
                        boolean ring = dx == 0 || dx == 2 || dz == 0 || dz == 2;
                        if (ring) out.add(at(w, bx, by, bz, tx + dx, y, tz + dz, Material.CRYING_OBSIDIAN));
                    }
            out.add(at(w, bx, by, bz, c[0] > 0 ? half - 1 : -half + 1, height + 4, c[1] > 0 ? half - 1 : -half + 1, Material.BEACON));
        }

        // Interior light and a beacon at the centre top
        out.add(at(w, bx, by, bz, 0, height, 0, Material.BEACON));
        return out;
    }

    private static boolean isCorner(int x, int z, int half) {
        return Math.abs(x) == half && Math.abs(z) == half;
    }

    /** 5 wide, 50 long, lit with glowstone every 6 blocks. dirX/dirZ must be -1, 0, or 1, one axis only. */
    public static List<Placement> bridge(Location base, int dirX, int dirZ, int length) {
        World w = base.getWorld();
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        List<Placement> out = new ArrayList<>();
        boolean alongX = dirX != 0;
        for (int s = 0; s < length; s++) {
            for (int lat = -2; lat <= 2; lat++) {
                int x = alongX ? dirX * s : lat;
                int z = alongX ? lat : dirZ * s;
                Material m = (lat == -2 || lat == 2) ? Material.NETHERITE_BLOCK : Material.OBSIDIAN;
                if (lat == 0 && s % 6 == 0) m = Material.GLOWSTONE;
                out.add(at(w, bx, by, bz, x, 0, z, m));
            }
        }
        return out;
    }

    /** Tall, thin tower with a beacon on top. */
    public static List<Placement> watchtower(Location base) {
        World w = base.getWorld();
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        int height = 20;
        List<Placement> out = new ArrayList<>();
        for (int y = 0; y < height; y++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    boolean ring = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                    if (ring) out.add(at(w, bx, by, bz, dx, y, dz, Material.DEEPSLATE_BRICKS));
                }
        out.add(at(w, bx, by, bz, 0, height, 0, Material.BEACON));
        return out;
    }

    /**
     * Prison: a 31 by 21 block with a double obsidian outer wall, a sealed roof, four corner watch
     * towers, and cell rows split by iron bars. The cells sit inside the walls; the gear vault sits
     * outside them, behind its own obsidian walls and a roof hatch, so no prisoner can reach it.
     * The only gap is the south door.
     */
    public static List<Placement> prison(Location base) {
        World w = base.getWorld();
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        int halfX = 15, halfZ = 10, wallH = 6;
        List<Placement> out = new ArrayList<>();
        // Floor
        for (int x = -halfX; x <= halfX; x++)
            for (int z = -halfZ; z <= halfZ; z++)
                out.add(at(w, bx, by, bz, x, 0, z, Material.SMOOTH_STONE));
        // Double outer wall: obsidian on the outside ring, deepslate brick on the inside ring
        for (int y = 1; y <= wallH; y++)
            for (int x = -halfX; x <= halfX; x++)
                for (int z = -halfZ; z <= halfZ; z++) {
                    boolean outer = Math.abs(x) == halfX || Math.abs(z) == halfZ;
                    boolean inner = Math.abs(x) == halfX - 1 || Math.abs(z) == halfZ - 1;
                    if (!outer && !inner) continue;
                    boolean corner = Math.abs(x) >= halfX - 1 && Math.abs(z) >= halfZ - 1;
                    Material m = outer ? Material.OBSIDIAN : Material.DEEPSLATE_BRICKS;
                    if (corner && outer) m = Material.CRYING_OBSIDIAN;
                    // Door: the south wall centre, three high, leaving the outer and inner rings open
                    if (z >= halfZ - 1 && x >= -1 && x <= 1 && y <= 3) continue;
                    out.add(at(w, bx, by, bz, x, y, z, m));
                }
        // Sealed roof
        for (int x = -halfX; x <= halfX; x++)
            for (int z = -halfZ; z <= halfZ; z++)
                out.add(at(w, bx, by, bz, x, wallH + 1, z, Material.OBSIDIAN));
        // Four corner watch towers, 3x3, rising four above the roof
        int[][] corners = {{-halfX, -halfZ}, {halfX - 2, -halfZ}, {-halfX, halfZ - 2}, {halfX - 2, halfZ - 2}};
        for (int[] c : corners)
            for (int y = wallH + 2; y <= wallH + 5; y++)
                for (int dx = 0; dx < 3; dx++)
                    for (int dz = 0; dz < 3; dz++) {
                        boolean ring = dx == 0 || dx == 2 || dz == 0 || dz == 2;
                        if (ring) out.add(at(w, bx, by, bz, c[0] + dx, y, c[1] + dz, Material.CRYING_OBSIDIAN));
                    }
        // Cell rows: iron bar partitions running north-south at x = -6, -2, 2, 6 with a gap every four blocks
        for (int y = 1; y <= wallH; y++)
            for (int x : new int[]{-6, -2, 2, 6})
                for (int z = -halfZ + 2; z <= halfZ - 2; z++) {
                    if (z % 4 == 0) continue; // a cell door every four blocks
                    out.add(at(w, bx, by, bz, x, y, z, Material.IRON_BARS));
                }
        // Corridor pressure plates: a step on one is an alarm for the Nulls
        for (int z = -halfZ + 2; z <= halfZ - 2; z += 2)
            out.add(at(w, bx, by, bz, 0, 1, z, Material.POLISHED_BLACKSTONE_PRESSURE_PLATE));
        // Gear vault outside the north wall: obsidian box, barrel inside, roof hatch for the Nulls
        int vx = -1, vz = -halfZ - 5;
        for (int y = 0; y <= 4; y++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    boolean ring = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                    if (y == 4 || ring) out.add(at(w, bx, by, bz, vx + dx, y, vz + dz, Material.OBSIDIAN));
                }
        out.add(at(w, bx, by, bz, vx, 1, vz, Material.BARREL));
        out.add(at(w, bx, by, bz, vx, 4, vz, Material.IRON_TRAPDOOR));
        return out;
    }

    /** 9x7 single-storey barracks: floor, walls, a door gap, and chests for gear. Roof is a dark block. */
    public static List<Placement> barracks(Location base) {
        World w = base.getWorld();
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        int halfX = 4, halfZ = 3, wallH = 4;
        List<Placement> out = new ArrayList<>();
        for (int x = -halfX; x <= halfX; x++)
            for (int z = -halfZ; z <= halfZ; z++)
                out.add(at(w, bx, by, bz, x, 0, z, Material.SMOOTH_STONE));
        for (int y = 1; y <= wallH; y++)
            for (int x = -halfX; x <= halfX; x++)
                for (int z = -halfZ; z <= halfZ; z++) {
                    if (Math.abs(x) != halfX && Math.abs(z) != halfZ) continue;
                    if (x == 0 && z == halfZ && y <= 2) continue; // door gap on the front
                    out.add(at(w, bx, by, bz, x, y, z, Material.DEEPSLATE_BRICKS));
                }
        for (int x = -halfX; x <= halfX; x++)
            for (int z = -halfZ; z <= halfZ; z++)
                out.add(at(w, bx, by, bz, x, wallH + 1, z, Material.DARK_OAK_SLAB));
        out.add(at(w, bx, by, bz, -halfX + 1, 1, -halfZ + 1, Material.CHEST));
        out.add(at(w, bx, by, bz, halfX - 1, 1, -halfZ + 1, Material.CHEST));
        out.add(at(w, bx, by, bz, -halfX + 1, 1, halfZ - 1, Material.CHEST));
        out.add(at(w, bx, by, bz, halfX - 1, 1, halfZ - 1, Material.CHEST));
        return out;
    }

    /** Two flanking pillars of netherite and a lintel with a forge block in the centre. */
    public static List<Placement> forgeGate(Location base) {
        World w = base.getWorld();
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        List<Placement> out = new ArrayList<>();
        for (int y = 0; y < 6; y++) {
            out.add(at(w, bx, by, bz, -3, y, 0, Material.NETHERITE_BLOCK));
            out.add(at(w, bx, by, bz, 3, y, 0, Material.NETHERITE_BLOCK));
        }
        for (int x = -2; x <= 2; x++) {
            out.add(at(w, bx, by, bz, x, 5, 0, Material.BLACKSTONE));
        }
        out.add(at(w, bx, by, bz, 0, 0, 0, Material.LODESTONE));
        out.add(at(w, bx, by, bz, 0, 1, 0, Material.CAMPFIRE));
        return out;
    }

    /** Thin, very tall spire marking territory, with a beacon at the top. */
    public static List<Placement> beaconSpire(Location base) {
        World w = base.getWorld();
        int bx = base.getBlockX(), by = base.getBlockY(), bz = base.getBlockZ();
        int height = 40;
        List<Placement> out = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            out.add(at(w, bx, by, bz, 0, y, 0, Material.POLISHED_BLACKSTONE));
        }
        out.add(at(w, bx, by, bz, 0, height, 0, Material.BEACON));
        return out;
    }

    /** 5x5x5 hollow cage around a player: reinforced deepslate floor, obsidian walls and roof. */
    public static List<Placement> cage(Location center) {
        World w = center.getWorld();
        int bx = center.getBlockX(), by = center.getBlockY(), bz = center.getBlockZ();
        List<Placement> out = new ArrayList<>();
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++)
                for (int y = -1; y <= 4; y++) {
                    boolean shell = y == -1 || y == 4 || Math.abs(x) == 2 || Math.abs(z) == 2;
                    if (!shell) continue;
                    Material m = y == -1 ? Material.REINFORCED_DEEPSLATE : Material.OBSIDIAN;
                    out.add(at(w, bx, by, bz, x, y, z, m));
                }
        return out;
    }
}
