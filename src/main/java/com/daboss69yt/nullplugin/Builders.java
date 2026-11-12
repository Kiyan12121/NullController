package com.daboss69yt.nullplugin;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;

/** Starts structure builds at the owner's position, repairs the last build, and drives arm swings. */
public final class Builders {

    private static final int BRIDGE_LENGTH = 50;

    private final NullControllerPlugin plugin;
    private final NullArmy army;
    private final BuildQueue queue;
    private List<BuildQueue.Placement> lastPlacements = List.of();
    private String lastName = "";

    public Builders(NullControllerPlugin plugin, NullArmy army) {
        this.plugin = plugin;
        this.army = army;
        this.queue = new BuildQueue(plugin);
    }

    public void cancel(Player owner) {
        queue.cancel();
        army.stopSwinging();
        owner.sendMessage(ChatColor.DARK_PURPLE + "Build cancelled.");
    }

    /** Handles /nullcmd build <type>. */
    public void build(Player owner, String type) {
        if (queue.isBusy()) {
            owner.sendMessage(ChatColor.RED + "Already building " + queue.currentName() + ". Use /nullcmd build cancel first.");
            return;
        }
        Location base = owner.getLocation().clone();
        base.setY(Math.floor(base.getY()) - 1);

        List<BuildQueue.Placement> placements;
        String name;
        switch (type) {
            case "citadel" -> { placements = Structures.voidCitadel(base); name = "Void Citadel"; }
            case "watchtower" -> { placements = Structures.watchtower(base); name = "Watchtower"; }
            case "barracks" -> { placements = Structures.barracks(base); name = "Barracks"; }
            case "forgegate" -> { placements = Structures.forgeGate(base); name = "Forge Gate"; }
            case "spire" -> { placements = Structures.beaconSpire(base); name = "Beacon Spire"; }
            case "bridge" -> {
                int[] dir = facingDirection(owner);
                Location start = base.clone().add(dir[0] * 2, 0, dir[1] * 2);
                placements = Structures.bridge(start, dir[0], dir[1], BRIDGE_LENGTH);
                name = "Bridge";
            }
            default -> {
                owner.sendMessage(ChatColor.RED + "Unknown build: " + type
                        + ". Options: citadel, bridge, watchtower, barracks, forgegate, spire.");
                return;
            }
        }
        startBuild(owner, name, placements);
    }

    /** Places a list of blocks through the queue. Used by builds, cages, and repairs. */
    public void startBuild(Player owner, String name, List<BuildQueue.Placement> placements) {
        if (queue.isBusy()) {
            owner.sendMessage(ChatColor.RED + "Already building " + queue.currentName() + ". Use /nullcmd build cancel first.");
            return;
        }
        int perTick = Math.max(1, plugin.getConfig().getInt("build-blocks-per-tick", 4));
        army.startSwinging();
        boolean started = queue.start(name, placements, perTick, () -> {
            army.stopSwinging();
            lastName = name;
            lastPlacements = placements;
            owner.sendMessage(ChatColor.DARK_PURPLE + name + " complete.");
        });
        if (started) {
            owner.sendMessage(ChatColor.DARK_PURPLE + "Building " + name + " (" + placements.size() + " blocks).");
        }
    }

    /** Re-places any block from the last completed build that has gone missing. */
    public void fortify(Player owner) {
        if (lastPlacements.isEmpty()) {
            owner.sendMessage(ChatColor.RED + "Nothing to repair yet. Finish a build first.");
            return;
        }
        List<BuildQueue.Placement> missing = lastPlacements.stream()
                .filter(p -> p.location().getBlock().getType() != p.material())
                .toList();
        if (missing.isEmpty()) {
            owner.sendMessage(ChatColor.DARK_PURPLE + "The " + lastName + " is intact.");
            return;
        }
        startBuild(owner, "Fortify " + lastName, missing);
    }

    /** Returns the dominant horizontal direction the owner is facing as {dx, dz}, one axis only. */
    private int[] facingDirection(Player owner) {
        Vector v = owner.getLocation().getDirection();
        if (Math.abs(v.getX()) >= Math.abs(v.getZ())) {
            return new int[]{v.getX() >= 0 ? 1 : -1, 0};
        }
        return new int[]{0, v.getZ() >= 0 ? 1 : -1};
    }
}
