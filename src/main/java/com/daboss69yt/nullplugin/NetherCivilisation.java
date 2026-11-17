package com.daboss69yt.nullplugin;

import com.daboss69yt.nullplugin.BuildQueue.Placement;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * The Nether settlement the Nulls raise around the base the owner sets. Each building sits in its
 * own 30 block cell on a 5 by 5 grid, centred on the base. The centre is the citadel. Barracks, forge
 * gate, smelter bank, item line, prison, watchtower, melon farm and spire reuse the existing layouts.
 * The stash room, regear room, planning room, mining gallery and furniture hall are built here, with
 * their own furniture. Everything is relative to the base, which sits on the ground.
 */
public final class NetherCivilisation {

    /** Width of one building cell. Each building fits inside its cell. */
    static final int CELL = 30;

    private NetherCivilisation() {}

    /** Collects placements relative to one origin, so the rooms read like plans. */
    private static final class Grid {
        final World w;
        final int bx, by, bz;
        final List<Placement> out = new ArrayList<>();

        Grid(Location base) {
            this.w = base.getWorld();
            this.bx = base.getBlockX();
            this.by = base.getBlockY();
            this.bz = base.getBlockZ();
        }

        void put(int x, int y, int z, Material m) {
            out.add(new Placement(new Location(w, bx + x, by + y, bz + z), m));
        }

        /** A room with its floor at y 0, walls up to h and a flat roof at h. The door is in the south wall. */
        void room(int cx, int cz, int sx, int sz, int h, Material floor, Material wall, Material roof) {
            int x1 = cx - sx / 2, x2 = cx + sx / 2, z1 = cz - sz / 2, z2 = cz + sz / 2;
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    put(x, 0, z, floor);
                    put(x, h, z, roof);
                }
            }
            for (int y = 1; y < h; y++) {
                for (int x = x1; x <= x2; x++) {
                    for (int z = z1; z <= z2; z++) {
                        boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                        if (!edge) continue;
                        if (z == z2 && x == cx && y <= 2) continue; // two-high door
                        put(x, y, z, wall);
                    }
                }
            }
            // Soul lanterns under the roof corners, so every room is lit.
            for (int x : new int[]{x1 + 1, x2 - 1}) {
                for (int z : new int[]{z1 + 1, z2 - 1}) put(x, h - 1, z, Material.SOUL_LANTERN);
            }
        }

        /** A table: fence leg, slab top, and a flower pot on it. */
        void table(int x, int z) {
            put(x, 1, z, Material.CRIMSON_FENCE);
            put(x, 2, z, Material.CRIMSON_SLAB);
            put(x, 3, z, Material.FLOWER_POT);
        }

        /** A plain chair: stairs on the floor. */
        void chair(int x, int z) {
            put(x, 1, z, Material.CRIMSON_STAIRS);
        }

        /** A red runner across the room floor, one block high, along the x axis. */
        void runner(int cx, int cz, int halfLen, int halfWidth) {
            for (int x = cx - halfLen; x <= cx + halfLen; x++) {
                for (int z = cz - halfWidth; z <= cz + halfWidth; z++) put(x, 1, z, Material.RED_CARPET);
            }
        }
    }

    /** The whole settlement, relative to the base. */
    public static List<Placement> plan(Location base) {
        List<Placement> all = new ArrayList<>();
        all.addAll(Structures.voidCitadel(cell(base, 0, 0)));
        all.addAll(Structures.barracks(cell(base, -1, 0)));
        all.addAll(Structures.forgeGate(cell(base, 1, 0)));
        all.addAll(Machines.smelterBank(cell(base, 0, -1)));
        all.addAll(Machines.itemLine(cell(base, 0, 1)));
        all.addAll(Structures.prison(cell(base, -1, -1)));
        all.addAll(Structures.watchtower(cell(base, 1, -1)));
        all.addAll(Machines.melonFarm(cell(base, -1, 1)));
        all.addAll(Structures.beaconSpire(cell(base, 1, 1)));
        all.addAll(stashRoom(cell(base, 2, 0)));
        all.addAll(regearRoom(cell(base, 2, -1)));
        all.addAll(planningRoom(cell(base, -2, 0)));
        all.addAll(miningGallery(cell(base, -2, -1)));
        all.addAll(furnitureHall(cell(base, 0, 2)));
        all.addAll(arrivalHall(cell(base, 0, -2)));
        all.addAll(meetingRoom(cell(base, 2, 1)));
        return all;
    }

    /**
     * Where a Null stands guard: the citadel, the arrival hall, the stash room, the meeting room and the
     * prison gate. Written to config.yml as guard-posts when the civilisation is queued.
     */
    public static List<Location> guardSpots(Location base) {
        List<Location> spots = new ArrayList<>();
        spots.add(cell(base, 0, 0));
        spots.add(cell(base, 0, -2));
        spots.add(cell(base, 2, 0));
        spots.add(cell(base, 2, 1));
        spots.add(cell(base, -1, -1).add(0, 0, 19));
        return spots;
    }

    /** Meeting room: one long table with seats down both sides, a lectern at the head and a wall of books. */
    static List<Placement> meetingRoom(Location base) {
        Grid g = new Grid(base);
        g.room(0, 0, 15, 13, 6, Material.POLISHED_BLACKSTONE_BRICKS, Material.NETHER_BRICKS, Material.NETHER_BRICK_SLAB);
        for (int x = -5; x <= 5; x++) {
            g.put(x, 2, 0, Material.CRIMSON_SLAB);
            if (x % 2 == 0) g.put(x, 1, 0, Material.CRIMSON_FENCE);
        }
        for (int x = -5; x <= 5; x += 2) {
            g.put(x, 1, -1, Material.CRIMSON_STAIRS);
            g.put(x, 1, 1, Material.CRIMSON_STAIRS);
        }
        g.put(0, 1, -3, Material.LECTERN);
        for (int x = -4; x <= 4; x++) {
            if (x == 0) continue;
            g.put(x, 1, -5, Material.BOOKSHELF);
            g.put(x, 2, -5, Material.BOOKSHELF);
        }
        g.put(-5, 1, 5, Material.CHEST);
        g.put(5, 1, 5, Material.CHEST);
        return g.out;
    }

    /** Where the arrival hall's centre sits, for the base's portal to land on. Its floor is at the base's level. */
    public static Location arrivalSpot(Location base) {
        return cell(base, 0, -2);
    }

    /**
     * Arrival hall: the room the base portal lands you in. A Nether portal sits in the north wall and
     * takes you back out. Soul lanterns light it and a supply chest is by the door.
     */
    static List<Placement> arrivalHall(Location base) {
        Grid g = new Grid(base);
        g.room(0, 0, 9, 9, 5, Material.POLISHED_BLACKSTONE_BRICKS, Material.NETHER_BRICKS, Material.NETHER_BRICK_SLAB);
        // Portal frame on the north wall (z = -3): obsidian, with a 2 by 3 Nether portal inside.
        for (int y = 0; y <= 4; y++) {
            for (int x = -2; x <= 1; x++) {
                boolean inside = y >= 1 && y <= 3 && (x == -1 || x == 0);
                g.put(x, y, -3, inside ? Material.NETHER_PORTAL : Material.OBSIDIAN);
            }
        }
        g.put(3, 1, 3, Material.CHEST);
        return g.out;
    }

    /** The base location moved to the centre of cell (cx, cz). */
    private static Location cell(Location base, int cx, int cz) {
        return base.clone().add(cx * CELL, 0, cz * CELL);
    }

    /** Stash room: chests along two walls, barrels in the middle row. */
    static List<Placement> stashRoom(Location base) {
        Grid g = new Grid(base);
        g.room(0, 0, 15, 11, 5, Material.POLISHED_BLACKSTONE_BRICKS, Material.NETHER_BRICKS, Material.NETHER_BRICK_SLAB);
        for (int x = -6; x <= 6; x += 2) {
            g.put(x, 1, -4, Material.CHEST);
            g.put(x, 1, 4, Material.CHEST);
        }
        for (int x = -6; x <= 6; x += 2) {
            if (x % 4 == 0) g.put(x, 1, 0, Material.BARREL);
        }
        return g.out;
    }

    /** Regear room: smithing table, grindstone, anvil, cauldron, enchanting table and a kit chest row. */
    static List<Placement> regearRoom(Location base) {
        Grid g = new Grid(base);
        g.room(0, 0, 15, 13, 5, Material.POLISHED_BLACKSTONE_BRICKS, Material.NETHER_BRICKS, Material.NETHER_BRICK_SLAB);
        g.put(-5, 1, -5, Material.SMITHING_TABLE);
        g.put(-3, 1, -5, Material.GRINDSTONE);
        g.put(0, 1, -5, Material.ANVIL);
        g.put(3, 1, -5, Material.CAULDRON);
        g.put(5, 1, -5, Material.ENCHANTING_TABLE);
        for (int x = -5; x <= 5; x += 2) g.put(x, 1, 5, Material.CHEST);
        g.runner(0, 0, 4, 1);
        return g.out;
    }

    /** Planning room: a lectern on a runner, bookshelves on the back wall, two crafting tables. */
    static List<Placement> planningRoom(Location base) {
        Grid g = new Grid(base);
        g.room(0, 0, 15, 15, 6, Material.POLISHED_BLACKSTONE_BRICKS, Material.NETHER_BRICKS, Material.NETHER_BRICK_SLAB);
        g.put(0, 1, -5, Material.LECTERN);
        for (int x = -6; x <= 6; x++) {
            if (Math.abs(x) <= 1) continue;
            g.put(x, 1, -6, Material.BOOKSHELF);
            g.put(x, 2, -6, Material.BOOKSHELF);
        }
        g.put(-5, 1, 5, Material.CRAFTING_TABLE);
        g.put(5, 1, 5, Material.CRAFTING_TABLE);
        g.runner(0, 0, 5, 1);
        g.chair(-3, 2);
        g.chair(3, 2);
        g.table(-3, 1);
        g.table(3, 1);
        return g.out;
    }

    /** Mining gallery: a shaft cut down into the rock, stairs down its side, a chest and lights at the bottom. */
    static List<Placement> miningGallery(Location base) {
        Grid g = new Grid(base);
        g.room(0, 0, 11, 11, 5, Material.POLISHED_BLACKSTONE_BRICKS, Material.NETHER_BRICKS, Material.NETHER_BRICK_SLAB);
        for (int y = 0; y >= -8; y--) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) g.put(x, y, z, Material.AIR);
            }
        }
        for (int k = 0; k < 8; k++) {
            g.put(-2, -k, 0, Material.POLISHED_BLACKSTONE_STAIRS);
        }
        g.put(0, -8, 0, Material.CHEST);
        g.put(-1, -8, -1, Material.SOUL_LANTERN);
        g.put(1, -8, 1, Material.SOUL_LANTERN);
        g.put(3, 1, -3, Material.CHEST);
        g.put(3, 1, 3, Material.CHEST);
        return g.out;
    }

    /** Furniture hall: long tables with chairs, chests and bookshelves along the walls, a runner down the middle. */
    static List<Placement> furnitureHall(Location base) {
        Grid g = new Grid(base);
        g.room(0, 0, 17, 11, 6, Material.POLISHED_BLACKSTONE_BRICKS, Material.NETHER_BRICKS, Material.NETHER_BRICK_SLAB);
        g.runner(0, 0, 7, 1);
        for (int x : new int[]{-5, 0, 5}) {
            g.table(x, -2);
            g.table(x, 2);
            g.chair(x - 1, -2);
            g.chair(x + 1, -2);
            g.chair(x - 1, 2);
            g.chair(x + 1, 2);
        }
        for (int x = -7; x <= 7; x++) {
            if (Math.abs(x) % 2 == 0) {
                g.put(x, 1, -5, Material.BOOKSHELF);
                g.put(x, 1, 5, Material.CHEST);
            }
        }
        g.put(-7, 1, 0, Material.CHEST);
        g.put(7, 1, 0, Material.CHEST);
        return g.out;
    }
}
