package com.daboss69yt.nullplugin;

import java.util.function.Consumer;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.List;

/** Plans, previews, builds, undoes, and repairs structures. The Nulls do the placing. */
public final class Builders {

    private static final int BRIDGE_LENGTH = 50;
    private static final int PREVIEW_TICKS = 100;

    public record Plan(String name, List<BuildQueue.Placement> placements) {}

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
        if ((type.equals("city") || type.equals("metropolis")) && owner.getWorld().getEnvironment() != World.Environment.THE_END) {
            owner.sendMessage(ChatColor.RED + "The Null Civilisation can only be raised in The End.");
            return;
        }
        if (queue.isBusy()) {
            owner.sendMessage(ChatColor.RED + "Already building " + queue.currentName() + ". Use /nullcmd build cancel first.");
            return;
        }
        Plan plan = planFor(owner, type);
        if (plan == null) {
            unknown(owner, type);
            return;
        }
        if (type.equals("city") || type.equals("metropolis")) {
            Location c = owner.getLocation();
            plugin.getConfig().set("city-centre", c.getWorld().getName() + "," + c.getBlockX() + "," + c.getBlockY() + "," + c.getBlockZ());
            plugin.saveConfig();
        }
        startBuild(msg -> owner.sendMessage(msg), plan.name(), plan.placements(), true);
    }

    /** Shows a particle outline of the footprint for five seconds before anything is built. */
    public void preview(Player owner, String type) {
        Plan plan = planFor(owner, type);
        if (plan == null) {
            unknown(owner, type);
            return;
        }
        if (plan.placements().isEmpty()) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        World world = null;
        for (BuildQueue.Placement p : plan.placements()) {
            Location l = p.location();
            world = l.getWorld();
            minX = Math.min(minX, l.getBlockX());
            minY = Math.min(minY, l.getBlockY());
            minZ = Math.min(minZ, l.getBlockZ());
            maxX = Math.max(maxX, l.getBlockX());
            maxY = Math.max(maxY, l.getBlockY());
            maxZ = Math.max(maxZ, l.getBlockZ());
        }
        if (world == null) return;

        final World w = world;
        final int x0 = minX, y0 = minY, z0 = minZ, x1 = maxX + 1, y1 = maxY + 1, z1 = maxZ + 1;
        owner.sendMessage(ChatColor.DARK_PURPLE + "Previewing " + plan.name() + " for 5 seconds.");
        new BukkitRunnable() {
            int runs = 0;

            @Override
            public void run() {
                if (runs * 10 >= PREVIEW_TICKS) {
                    cancel();
                    return;
                }
                runs++;
                drawBox(w, x0, y0, z0, x1, y1, z1);
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void drawBox(World w, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++)
            for (int y : new int[]{y0, y1})
                for (int z : new int[]{z0, z1})
                    dot(w, x, y, z);
        for (int y = y0; y <= y1; y++)
            for (int x : new int[]{x0, x1})
                for (int z : new int[]{z0, z1})
                    dot(w, x, y, z);
        for (int z = z0; z <= z1; z++)
            for (int x : new int[]{x0, x1})
                for (int y : new int[]{y0, y1})
                    dot(w, x, y, z);
    }

    private void dot(World w, int x, int y, int z) {
        w.spawnParticle(Particle.END_ROD, x + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
    }

    /** Removes the last finished build, block by block. */
    public void undo(Player owner) {
        if (queue.isBusy()) {
            owner.sendMessage(ChatColor.RED + "Already building " + queue.currentName() + ". Use /nullcmd build cancel first.");
            return;
        }
        if (lastPlacements.isEmpty()) {
            owner.sendMessage(ChatColor.GRAY + "Nothing to undo. Finish a build first.");
            return;
        }
        String name = lastName;
        List<BuildQueue.Placement> clear = lastPlacements.stream()
                .map(p -> new BuildQueue.Placement(p.location(), Material.AIR))
                .toList();
        lastPlacements = List.of();
        lastName = "";
        army.startSwinging();
        queue.start("Undo " + name, clear, perTick(), () -> {
            army.stopSwinging();
            owner.sendMessage(ChatColor.DARK_PURPLE + "Undid " + name + ".");
        });
        owner.sendMessage(ChatColor.DARK_PURPLE + "Undoing " + name + ".");
    }

    public void startBuild(Player owner, String name, List<BuildQueue.Placement> placements) {
        startBuild(msg -> owner.sendMessage(msg), name, placements, true);
    }

    private void startBuild(Consumer<String> say, String name, List<BuildQueue.Placement> placements, boolean remember) {
        if (queue.isBusy()) {
            say.accept(ChatColor.RED + "Already building " + queue.currentName() + ". Use /nullcmd build cancel first.");
            return;
        }
        army.startSwinging();
        boolean started = queue.start(name, placements, buildRate(name), () -> {
            army.stopSwinging();
            if (remember) {
                lastName = name;
                lastPlacements = placements;
            }
            say.accept(ChatColor.DARK_PURPLE + name + " complete.");
        });
        if (started) {
            say.accept(ChatColor.DARK_PURPLE + "Building " + name + " (" + placements.size() + " blocks).");
        }
    }

    /** Re-places any missing block from the last build, and tells the owner. */
    public void fortify(Player owner) {
        if (lastPlacements.isEmpty()) {
            owner.sendMessage(ChatColor.RED + "Nothing to repair yet. Finish a build first.");
            return;
        }
        List<BuildQueue.Placement> missing = missingBlocks();
        if (missing.isEmpty()) {
            owner.sendMessage(ChatColor.DARK_PURPLE + "The " + lastName + " is intact.");
            return;
        }
        startBuild(msg -> owner.sendMessage(msg), "Fortify " + lastName, missing, false);
    }

    /**
     * Self-repair under attack: if part of the last build is missing and Nulls are standing near it,
     * the Nulls put it back straight away. Autonomous, so Nether builds only.
     */
    public void repairUnderAttack(Player owner) {
        if (queue.isBusy() || lastPlacements.isEmpty() || !inNether(lastPlacements.get(0).location())) return;
        List<BuildQueue.Placement> missing = missingBlocks();
        if (missing.isEmpty()) return;
        Location spot = missing.get(0).location();
        if (!army.hasNullsNear(spot, 24)) return;
        startBuild(msg -> owner.sendMessage(msg), "Emergency repair of " + lastName, missing, false);
    }

    private boolean inNether(Location l) {
        return l.getWorld() != null && l.getWorld().getEnvironment() == World.Environment.NETHER;
    }

    /** Called by the timer. Repairs quietly, and only when idle and something is missing. Nether builds only. */
    public void autoFortify(Player owner) {
        if (queue.isBusy() || lastPlacements.isEmpty() || !inNether(lastPlacements.get(0).location())) return;
        List<BuildQueue.Placement> missing = missingBlocks();
        if (missing.isEmpty()) return;
        startBuild(msg -> owner.sendMessage(msg), "Auto-repair " + lastName, missing, false);
    }

    private List<BuildQueue.Placement> missingBlocks() {
        return lastPlacements.stream()
                .filter(p -> p.location().getBlock().getType() != p.material())
                .toList();
    }

    private Plan planFor(Player owner, String type) {
        Location base = owner.getLocation().clone();
        base.setY(Math.floor(base.getY()) - 1);
        return planAt(base, facingDirection(owner), type);
    }

    /** Plans a build at base, facing the direction dir. Used by players and by the offline console. */
    private Plan planAt(Location base, int[] dir, String type) {
        return switch (type) {
            case "citadel" -> new Plan("Void Citadel", Structures.voidCitadel(base));
            case "watchtower" -> new Plan("Watchtower", Structures.watchtower(base));
            case "barracks" -> new Plan("Barracks", Structures.barracks(base));
            case "forgegate" -> new Plan("Forge Gate", Structures.forgeGate(base));
            case "spire" -> new Plan("Beacon Spire", Structures.beaconSpire(base));
            case "city" -> new Plan("Null Civilisation", City.plan(base));
            case "metropolis" -> new Plan("Metropolis", Metropolis.plan(base));
            case "collector" -> new Plan("Item Line", Machines.itemLine(base));
            case "smelter" -> new Plan("Auto-Smelter Bank", Machines.smelterBank(base));
            case "door" -> new Plan("Iron Door Gate", Machines.ironDoor(base));
            case "melonfarm" -> new Plan("Melon Farm", Machines.melonFarm(base));
            case "prison" -> {
                plugin.getConfig().set("prison", base.getWorld().getName() + "," + base.getBlockX() + ","
                        + base.getBlockY() + "," + base.getBlockZ());
                plugin.saveConfig();
                yield new Plan("Prison", Structures.prison(base));
            }
            case "bridge" -> {
                Location start = base.clone().add(dir[0] * 2, 0, dir[1] * 2);
                yield new Plan("Bridge", Structures.bridge(start, dir[0], dir[1], BRIDGE_LENGTH));
            }
            default -> null;
        };
    }

    /**
     * Owner offline: builds at the given site, facing +X. Civilisation builds need the owner
     * in The End, so they stay in game. Returns false if the build did not start.
     */
    private final java.util.Random pick = new java.util.Random();

    /**
     * Terrain-aware site: the nearest spot within eight blocks where a five by five footprint
     * is solid underneath, air above, and free of lava. Null if the ground is too rough.
     */
    private Location flatSite(Location site) {
        World w = site.getWorld();
        if (w == null) return null;
        for (int tries = 0; tries < 30; tries++) {
            int x = site.getBlockX() + (tries == 0 ? 0 : pick.nextInt(17) - 8);
            int z = site.getBlockZ() + (tries == 0 ? 0 : pick.nextInt(17) - 8);
            for (int y = site.getBlockY() + 3; y >= site.getBlockY() - 3; y--) {
                if (levelAt(w, x, y, z)) return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    private boolean levelAt(World w, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Material floor = w.getBlockAt(x + dx, y - 1, z + dz).getType();
                Material air = w.getBlockAt(x + dx, y, z + dz).getType();
                if (!floor.isSolid() || floor == Material.LAVA || !air.isAir()) return false;
            }
        }
        return true;
    }

    public boolean buildOffline(Location site, String type, Consumer<String> say) {
        if (queue.isBusy()) {
            say.accept("Already building " + queue.currentName() + ".");
            return false;
        }
        if (type.equals("city") || type.equals("metropolis")) {
            say.accept("The Civilisation builds are set in game only.");
            return false;
        }
        if (site.getWorld() == null || site.getWorld().getEnvironment() != World.Environment.NETHER) {
            say.accept("Offline builds happen in the Nether only.");
            return false;
        }
        Location level = flatSite(site);
        if (level == null) {
            say.accept("No level ground near the site. Try again after the Nulls have moved.");
            return false;
        }
        Location base = level.clone();
        base.setY(Math.floor(base.getY()) - 1);
        Plan plan = planAt(base, new int[]{1, 0}, type);
        if (plan == null) {
            say.accept("Unknown build: " + type + ". Options: citadel, bridge, watchtower, barracks, forgegate, spire, collector, smelter, door, melonfarm.");
            return false;
        }
        startBuild(say, plan.name(), plan.placements(), true);
        return true;
    }

    private void unknown(Player owner, String type) {
        owner.sendMessage(ChatColor.RED + "Unknown build: " + type
                + ". Options: citadel, bridge, watchtower, barracks, forgegate, spire, city, metropolis, collector, smelter, door, melonfarm.");
    }

    /** The Civilisation gets a faster budget so it finishes in minutes, not hours. */
    private int buildRate(String name) {
        if (name.equals("Null Civilisation")) {
            return Math.max(perTick(), plugin.getConfig().getInt("city-blocks-per-tick", 12));
        }
        if (name.equals("Metropolis")) {
            return Math.max(perTick(), plugin.getConfig().getInt("metropolis-blocks-per-tick", 40));
        }
        return perTick();
    }

    private int perTick() {
        return Math.max(1, plugin.getConfig().getInt("build-blocks-per-tick", 4));
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
