package com.daboss69yt.nullplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;

/**
 * Redstone-free machines built from hoppers, chests and furnaces, plus an iron door gate and a melon farm.
 * Every piece is placed with the facing it needs. Coordinates are relative to the block under the owner.
 */
public final class Machines {

    private Machines() {}

    private static void put(List<BuildQueue.Placement> out, Location base, int dx, int dy, int dz, String data) {
        BlockData d = Bukkit.createBlockData(data);
        out.add(new BuildQueue.Placement(base.clone().add(dx, dy, dz), d.getMaterial(), d));
    }

    /** Input chest, a line of hoppers passing items along, and an output chest at the end. */
    public static List<BuildQueue.Placement> itemLine(Location base) {
        List<BuildQueue.Placement> out = new ArrayList<>();
        put(out, base, 0, 4, 0, "minecraft:chest[facing=north]");
        for (int z = 0; z <= 5; z++) {
            put(out, base, 0, 3, z, "minecraft:hopper[facing=south]");
        }
        put(out, base, 0, 3, 6, "minecraft:chest[facing=north]");
        return out;
    }

    /**
     * Four smelting columns. Each has an input chest, a hopper feeding a furnace, and a hopper
     * taking the output into a chest beside it. Furnaces need fuel added by hand.
     */
    public static List<BuildQueue.Placement> smelterBank(Location base) {
        List<BuildQueue.Placement> out = new ArrayList<>();
        for (int x = 0; x <= 6; x += 2) {
            put(out, base, x, 4, 0, "minecraft:chest[facing=north]");
            put(out, base, x, 3, 0, "minecraft:hopper[facing=down]");
            put(out, base, x, 2, 0, "minecraft:furnace[facing=north,lit=false]");
            put(out, base, x, 1, 0, "minecraft:hopper[facing=east]");
            put(out, base, x + 1, 1, 0, "minecraft:chest[facing=north]");
        }
        return out;
    }

    /** A stone doorway with an iron door in it. Opened from a Null Remote or by redstone. */
    public static List<BuildQueue.Placement> ironDoor(Location base) {
        List<BuildQueue.Placement> out = new ArrayList<>();
        for (int x : new int[]{-2, -1, 1, 2}) {
            put(out, base, x, 1, 0, "minecraft:stone_bricks");
            put(out, base, x, 2, 0, "minecraft:stone_bricks");
        }
        for (int x = -1; x <= 1; x++) {
            put(out, base, x, 3, 0, "minecraft:stone_bricks");
        }
        put(out, base, 0, 1, 0, "minecraft:iron_door[facing=south,half=lower,hinge=left,open=false,powered=false]");
        put(out, base, 0, 2, 0, "minecraft:iron_door[facing=south,half=upper,hinge=left,open=false,powered=false]");
        return out;
    }

    /** A nine-by-nine melon field on farmland with a water channel down the middle. Stems regrow melons. */
    public static List<BuildQueue.Placement> melonFarm(Location base) {
        List<BuildQueue.Placement> out = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (x == 0) {
                    put(out, base, x, 0, z, "minecraft:water[level=0]");
                } else {
                    put(out, base, x, 0, z, "minecraft:farmland[moisture=7]");
                    put(out, base, x, 1, z, "minecraft:melon_stem[age=0]");
                }
            }
        }
        return out;
    }
}
