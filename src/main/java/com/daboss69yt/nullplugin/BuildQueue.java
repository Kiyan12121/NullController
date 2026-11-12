package com.daboss69yt.nullplugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Places blocks a few at a time per tick, so large structures never stall the server.
 * Each job is a list of block placements. Only one job runs at a time.
 */
public final class BuildQueue {

    /** A block to place. data is optional; when set it carries facing and state, such as hopper direction. */
    public record Placement(Location location, Material material, BlockData data) {
        public Placement(Location location, Material material) {
            this(location, material, null);
        }
    }

    private final Plugin plugin;
    private final Deque<Placement> pending = new ArrayDeque<>();
    private BukkitRunnable runner;
    private String currentName = "";

    public BuildQueue(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isBusy() {
        return runner != null;
    }

    public String currentName() {
        return currentName;
    }

    /** Starts a build. Returns false if one is already running. */
    public boolean start(String name, Iterable<Placement> placements, int blocksPerTick, Runnable onDone) {
        if (isBusy()) return false;
        pending.clear();
        placements.forEach(pending::add);
        currentName = name;

        runner = new BukkitRunnable() {
            @Override
            public void run() {
                for (int i = 0; i < blocksPerTick && !pending.isEmpty(); i++) {
                    Placement p = pending.poll();
                    Block block = p.location().getBlock();
                    if (p.data() != null) {
                        if (!block.getBlockData().matches(p.data())) block.setBlockData(p.data(), false);
                    } else if (block.getType() != p.material()) {
                        block.setType(p.material(), false);
                    }
                }
                if (pending.isEmpty()) {
                    cancel();
                    runner = null;
                    currentName = "";
                    if (onDone != null) onDone.run();
                }
            }
        };
        runner.runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    public void cancel() {
        if (runner != null) {
            runner.cancel();
            runner = null;
        }
        pending.clear();
        currentName = "";
    }
}
