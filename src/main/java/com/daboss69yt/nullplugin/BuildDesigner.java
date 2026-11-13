package com.daboss69yt.nullplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Commander's build designer. He describes a build as a few block operations (fill a box, hollow a box,
 * or set one block), relative to an origin in front of the owner. The plugin checks every operation, shows a
 * preview, and builds in batches only after the owner confirms. Giant builds cost time, not a few big calls.
 */
public final class BuildDesigner {
    private static final int MAX_COORD = 512;
    private static final int MAX_OPS = 300;
    private static final int STEPS_PER_RUN = 20000;
    private static final int FORWARD = 8;
    private static final int PLAYER_MARGIN = 20;
    /** Block names containing any of these are never built: explosives, command blocks, spawners, liquids, portals, fire. */
    private static final String[] DENIED = {"COMMAND", "SPAWNER", "TNT", "BARRIER", "BEDROCK", "STRUCTURE",
            "JIGSAW", "PORTAL", "GATEWAY", "WATER", "LAVA", "FIRE", "SPAWN"};

    private enum Kind { FILL, HOLLOW, SET }

    private static final class Op {
        final Kind kind;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final Material mat;

        Op(Kind kind, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Material mat) {
            this.kind = kind;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.mat = mat;
        }

        long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    private static final class Plan {
        final String name;
        final World world;
        final List<Op> ops;
        final long volume;
        final int[] bounds; // minX, minY, minZ, maxX, maxY, maxZ

        Plan(String name, World world, List<Op> ops, long volume, int[] bounds) {
            this.name = name;
            this.world = world;
            this.ops = ops;
            this.volume = volume;
            this.bounds = bounds;
        }
    }

    private final NullControllerPlugin plugin;
    private final ConsentManager consent;
    private final Map<UUID, Plan> pending = new HashMap<>();
    private final Map<UUID, Job> running = new HashMap<>();

    public BuildDesigner(NullControllerPlugin plugin, ConsentManager consent) {
        this.plugin = plugin;
        this.consent = consent;
    }

    /** Where a build starts: a few blocks in front of the owner, on their level. */
    public static Location origin(Player owner) {
        Location l = owner.getLocation();
        org.bukkit.util.Vector dir = l.getDirection().setY(0);
        if (dir.lengthSquared() < 0.0001) dir = new org.bukkit.util.Vector(1, 0, 0);
        Location spot = l.clone().add(dir.normalize().multiply(FORWARD));
        return spot.getBlock().getLocation();
    }

    /** Checks a designed build and stores it as a plan. Returns the preview, or the reason it was refused. */
    public String stage(Player owner, JsonObject build, Location origin) {
        String name = build.has("name") ? build.get("name").getAsString() : "a build";
        if (!build.has("ops") || !build.get("ops").isJsonArray()) return "That build has no operations.";
        JsonArray arr = build.getAsJsonArray("ops");
        if (arr.size() == 0 || arr.size() > MAX_OPS) return "A build needs 1 to " + MAX_OPS + " operations.";
        World w = origin.getWorld();
        if (w == null) return "There is no world to build in.";
        List<Op> ops = new ArrayList<>();
        long volume = 0;
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (var el : arr) {
            Op op;
            try {
                op = parseOp(el.getAsJsonObject(), origin);
            } catch (RuntimeException ex) {
                return "A block operation was malformed: " + ex.getMessage();
            }
            if (op == null) return "A block operation names a block that isn't allowed.";
            ops.add(op);
            volume += op.volume();
            b[0] = Math.min(b[0], op.minX);
            b[1] = Math.min(b[1], op.minY);
            b[2] = Math.min(b[2], op.minZ);
            b[3] = Math.max(b[3], op.maxX);
            b[4] = Math.max(b[4], op.maxY);
            b[5] = Math.max(b[5], op.maxZ);
        }
        long max = plugin.getConfig().getLong("build-max-volume", 2_000_000L);
        if (volume > max) return "That build is about " + volume + " blocks. The limit is " + max + ".";
        int width = b[3] - b[0] + 1, height = b[4] - b[1] + 1, depth = b[5] - b[2] + 1;
        if (width > MAX_COORD || height > MAX_COORD || depth > MAX_COORD) {
            return "That build is too big. The limit is " + MAX_COORD + " blocks in each direction.";
        }
        for (Player p : w.getPlayers()) {
            if (p.equals(owner) || consent.isTeam(p) || consent.canTarget(p)) continue;
            Location pl = p.getLocation();
            if (pl.getX() >= b[0] - PLAYER_MARGIN && pl.getX() <= b[3] + PLAYER_MARGIN
                    && pl.getZ() >= b[2] - PLAYER_MARGIN && pl.getZ() <= b[5] + PLAYER_MARGIN) {
                return "Refused. " + p.getName() + " is not on the roster and is too close to the site.";
            }
        }
        pending.put(owner.getUniqueId(), new Plan(name, w, ops, volume, b));
        return "Plan ready: " + name + ". " + width + " by " + height + " by " + depth + " blocks, up to "
                + volume + " blocks in " + ops.size() + " steps, starting at " + origin.getBlockX() + ", "
                + origin.getBlockY() + ", " + origin.getBlockZ() + ". Say 'null confirm' to build it or 'null cancel' to drop it.";
    }

    /** Starts building the pending plan in batches. */
    public void confirm(Player owner) {
        UUID id = owner.getUniqueId();
        if (running.containsKey(id)) {
            owner.sendMessage(ChatColor.GRAY + "Still building. Say 'null cancel' to stop.");
            return;
        }
        Plan plan = pending.remove(id);
        if (plan == null) {
            owner.sendMessage(ChatColor.GRAY + "There is nothing to confirm.");
            return;
        }
        Job job = new Job(id, plan);
        running.put(id, job);
        job.runTaskTimer(plugin, 1L, 2L);
        owner.sendMessage(ChatColor.DARK_PURPLE + "The crew is building " + plan.name + " in batches. Say 'null cancel' to stop.");
    }

    /** Drops the pending plan, or stops a build in progress. Blocks already placed stay. */
    public void cancel(Player owner) {
        UUID id = owner.getUniqueId();
        boolean dropped = pending.remove(id) != null;
        Job job = running.remove(id);
        if (job != null) {
            job.cancel();
            owner.sendMessage(ChatColor.DARK_PURPLE + "Build stopped. Blocks already placed stay.");
        } else if (dropped) {
            owner.sendMessage(ChatColor.DARK_PURPLE + "Plan dropped.");
        } else {
            owner.sendMessage(ChatColor.GRAY + "There is nothing to cancel.");
        }
    }

    private static Op parseOp(JsonObject o, Location origin) {
        String opName = o.get("op").getAsString().toLowerCase();
        Kind kind = switch (opName) {
            case "fill" -> Kind.FILL;
            case "hollow" -> Kind.HOLLOW;
            case "set" -> Kind.SET;
            default -> throw new IllegalArgumentException("unknown operation " + opName);
        };
        String blockName = o.get("block").getAsString().toUpperCase();
        Material mat = Material.matchMaterial(blockName);
        if (mat == null || !mat.isBlock() || denied(blockName)) return null;
        int[] a;
        int[] c;
        if (kind == Kind.SET) {
            a = vec(o.getAsJsonArray("at"));
            c = a;
        } else {
            a = vec(o.getAsJsonArray("from"));
            c = vec(o.getAsJsonArray("to"));
        }
        for (int i = 0; i < 3; i++) {
            if (Math.abs(a[i]) > MAX_COORD || Math.abs(c[i]) > MAX_COORD) {
                throw new IllegalArgumentException("coordinates are too far from the build origin");
            }
        }
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        return new Op(kind,
                ox + Math.min(a[0], c[0]), oy + Math.min(a[1], c[1]), oz + Math.min(a[2], c[2]),
                ox + Math.max(a[0], c[0]), oy + Math.max(a[1], c[1]), oz + Math.max(a[2], c[2]), mat);
    }

    private static boolean denied(String name) {
        for (String bad : DENIED) {
            if (name.contains(bad)) return true;
        }
        return false;
    }

    private static int[] vec(JsonArray arr) {
        if (arr == null || arr.size() != 3) throw new IllegalArgumentException("coordinates need three numbers");
        return new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()};
    }

    /** Places blocks a few batches per tick so the server stays responsive. */
    private final class Job extends BukkitRunnable {
        private final UUID ownerId;
        private final Plan plan;
        private int oi;
        private boolean started;
        private int x, y, z;
        private long placed;

        Job(UUID ownerId, Plan plan) {
            this.ownerId = ownerId;
            this.plan = plan;
        }

        @Override
        public void run() {
            int perTick = plugin.getConfig().getInt("build-placements-per-tick", 300);
            int placedNow = 0;
            int steps = 0;
            while (oi < plan.ops.size() && placedNow < perTick && steps < STEPS_PER_RUN) {
                Op op = plan.ops.get(oi);
                if (!started) {
                    x = op.minX;
                    y = op.minY;
                    z = op.minZ;
                    started = true;
                }
                steps++;
                boolean surface = op.kind != Kind.HOLLOW || x == op.minX || x == op.maxX
                        || y == op.minY || y == op.maxY || z == op.minZ || z == op.maxZ;
                if (surface) {
                    plan.world.getBlockAt(x, y, z).setType(op.mat, false);
                    placed++;
                    placedNow++;
                }
                z++;
                if (z > op.maxZ) {
                    z = op.minZ;
                    y++;
                    if (y > op.maxY) {
                        y = op.minY;
                        x++;
                        if (x > op.maxX) {
                            oi++;
                            started = false;
                        }
                    }
                }
            }
            if (oi >= plan.ops.size()) finish();
        }

        private void finish() {
            cancel();
            running.remove(ownerId);
            Player p = Bukkit.getPlayer(ownerId);
            if (p != null) {
                p.sendMessage(ChatColor.DARK_PURPLE + plan.name + " is finished. " + placed + " blocks placed.");
            }
        }
    }
}
