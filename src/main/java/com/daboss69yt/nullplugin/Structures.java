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
        int halfX = 30, halfZ = 22, wallH = 8;
        List<Placement> out = new ArrayList<>();

        // Floor. The far column on each side is a trap hall: magma underfoot and cobwebs at the feet.
        for (int x = -halfX; x <= halfX; x++)
            for (int z = -halfZ; z <= halfZ; z++) {
                boolean trapHall = Math.abs(x) >= 23;
                out.add(at(w, bx, by, bz, x, 0, z, trapHall ? Material.MAGMA_BLOCK : Material.SMOOTH_STONE));
                if (trapHall && (x + z) % 5 == 0) out.add(at(w, bx, by, bz, x, 1, z, Material.COBWEB));
            }

        // Double outer wall, with no door: nothing walks in. Obsidian outside, deepslate brick inside.
        for (int y = 1; y <= wallH; y++)
            for (int x = -halfX; x <= halfX; x++)
                for (int z = -halfZ; z <= halfZ; z++) {
                    boolean outer = Math.abs(x) == halfX || Math.abs(z) == halfZ;
                    boolean inner = Math.abs(x) == halfX - 1 || Math.abs(z) == halfZ - 1;
                    if (!outer && !inner) continue;
                    boolean corner = Math.abs(x) >= halfX - 1 && Math.abs(z) >= halfZ - 1;
                    Material m = outer ? Material.OBSIDIAN : Material.DEEPSLATE_BRICKS;
                    if (corner && outer) m = Material.CRYING_OBSIDIAN;
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

        // Cell blocks. Each side: a corridor, bars at x = +-4 with a cell door at each room, then three
        // columns of rooms (near, middle, far) and a trap hall beyond them.
        int[] wallZ = {-14, -7, 0, 7, 14};        // solid walls splitting each column into rooms
        int[] doorZ = {-18, -11, -4, 4, 11, 18};  // the middle of each room, where its door is
        int[][] wallX = {{-10, -16, -22}, {10, 16, 22}};
        for (int side : new int[]{-1, 1}) {
            // Bars facing the corridor, with a gap at every cell door
            for (int y = 1; y <= wallH; y++)
                for (int z = -halfZ + 2; z <= halfZ - 2; z++) {
                    if (contains(doorZ, z)) continue;
                    out.add(at(w, bx, by, bz, side * 4, y, z, Material.IRON_BARS));
                }
            // Pressure plate in each doorway: stepping through a door is an alarm
            for (int z : doorZ) out.add(at(w, bx, by, bz, side * 3, 1, z, Material.POLISHED_BLACKSTONE_PRESSURE_PLATE));
            // Solid walls between the columns
            for (int wx : wallX[side == -1 ? 0 : 1])
                for (int y = 1; y <= wallH - 1; y++)
                    for (int z = -halfZ + 2; z <= halfZ - 2; z++)
                        out.add(at(w, bx, by, bz, wx, y, z, Material.DEEPSLATE_BRICKS));
            // Walls splitting each column into rooms
            int[][] columns = side == -1 ? new int[][]{{-9, -5}, {-15, -11}, {-21, -17}}
                    : new int[][]{{5, 9}, {11, 15}, {17, 21}};
            for (int[] col : columns)
                for (int wz : wallZ)
                    for (int y = 1; y <= wallH - 1; y++)
                        for (int x = col[0]; x <= col[1]; x++)
                            out.add(at(w, bx, by, bz, x, y, wz, Material.DEEPSLATE_BRICKS));
            // Furniture: bedding, a lantern and a barrel in each room
            for (int[] col : columns) {
                for (int i = 0; i < doorZ.length; i++) {
                    int z0 = doorZ[i] - 2, zMid = doorZ[i];
                    out.add(at(w, bx, by, bz, col[0], 1, z0, Material.WHITE_WOOL));
                    out.add(at(w, bx, by, bz, col[0] + 1, 1, z0, Material.WHITE_WOOL));
                    out.add(at(w, bx, by, bz, (col[0] + col[1]) / 2, 1, zMid + 1, Material.LANTERN));
                    out.add(at(w, bx, by, bz, col[1], 1, zMid + 2, Material.BARREL));
                    // More detail per cell: a chest for belongings, a cauldron to wash in, a plant by the bed
                    out.add(at(w, bx, by, bz, col[0], 1, zMid + 2, Material.CHEST));
                    out.add(at(w, bx, by, bz, col[1], 1, z0, Material.CAULDRON));
                    out.add(at(w, bx, by, bz, col[0] + 3, 1, z0, Material.FLOWER_POT));
                }
            }
        }

        // Corridor pressure plates along the middle: a step on one is an alarm for the Nulls
        for (int z = -halfZ + 2; z <= 12; z += 2)
            out.add(at(w, bx, by, bz, 0, 1, z, Material.POLISHED_BLACKSTONE_PRESSURE_PLATE));

        // Processing desk in the south strip, just inside the gate: a lectern for the warden, storage for
        // belongings, and a bunk for the guards on duty.
        out.add(at(w, bx, by, bz, -6, 1, 19, Material.LECTERN));
        for (int x = -10; x <= -7; x++) out.add(at(w, bx, by, bz, x, 1, 20, Material.CHEST));
        for (int x = 7; x <= 10; x++) out.add(at(w, bx, by, bz, x, 1, 20, Material.BARREL));
        out.add(at(w, bx, by, bz, -9, 1, 18, Material.LANTERN));
        out.add(at(w, bx, by, bz, 9, 1, 18, Material.LANTERN));
        for (int x = 3; x <= 5; x++) out.add(at(w, bx, by, bz, x, 1, 19, Material.RED_WOOL));

        // Visit frame at the south end of the corridor: an obsidian portal frame, no door.
        // A player lights it with flint and steel, and it opens into the Nether to visit.
        int fz = 17;
        for (int y = 0; y <= 4; y++)
            for (int x = -2; x <= 1; x++) {
                boolean interior = x >= -1 && x <= 0 && y >= 1 && y <= 3;
                if (!interior) out.add(at(w, bx, by, bz, x, y, fz, Material.OBSIDIAN));
            }

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

    private static boolean contains(int[] values, int v) {
        for (int x : values) if (x == v) return true;
        return false;
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
