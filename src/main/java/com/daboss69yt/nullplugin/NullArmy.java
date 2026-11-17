package com.daboss69yt.nullplugin;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.GameModeTrait;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.Registry;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BlockFace;
import org.bukkit.GameMode;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Orientable;
import org.bukkit.Axis;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.entity.Monster;
import org.bukkit.util.Vector;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Owns every Null NPC, each Null's own pack, the army's mode, combat, hunters, and the behaviour loop. */
public final class NullArmy {

    public enum Mode { GUARD, FOLLOW, HUNT, GOTO, STAY, MINE, LUMBER, FARM, PATROL, FORGE, EXPLORE, NETHER, AUTO }

    /** A Null's own job in automation mode, and when it picks again. */
    private record AutoChoice(Mode mode, long until) {
    }

    private final Map<NPC, AutoChoice> autoChoices = new HashMap<>();

    /** Automation: each Null picks its own job (mostly mining, some patrol, guard and follow) and re-picks in 3-5 minutes. */
    private Mode autoPick(NPC npc) {
        long now = System.currentTimeMillis();
        AutoChoice c = autoChoices.get(npc);
        if (c != null && c.until() > now) return c.mode();
        int roll = rng.nextInt(100);
        Mode pick = roll < 40 ? Mode.MINE : roll < 60 ? Mode.PATROL : roll < 80 ? Mode.GUARD : Mode.FOLLOW;
        autoChoices.put(npc, new AutoChoice(pick, now + 180_000L + rng.nextInt(120_000)));
        if (npc.isSpawned()) setHeldItem(npc, gearForMode(pick));
        return pick;
    }

    enum WorkKind { MINE, LUMBER, FARM }

    static final int HARD_CAP = 200;
    /** The one leader of the army. It keeps this name, and it passes to the next Null if the leader falls. */
    static final String LEADER_NAME = "_______";
    private static final int PACK_SIZE = 9;
    private static final String NAME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final double TELEPORT_OVER = 64.0;
    private static final double REACH = 4.5;
    private static final double ATTACK_REACH = 3.0;
    private static final double WAYPOINT_REACH = 3.0;
    private static final double FORGE_HOLD = 4.0;
    private static final int WORK_RADIUS = 16;
    private static final int MAX_TARGETS = 300;
    private static final int MAX_WAYPOINTS = 50;
    private static final int SCAN_EVERY_CYCLES = 6;
    private static final int ATTACK_COOLDOWN_CYCLES = 2;
    private static final int ROAM_CYCLES = 8;
    private static final int FORGE_AWAKEN_CYCLES = 120;
    private static final double WOUNDED_HP = 8.0;
    private static final Set<Material> FARM_CROPS = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.NETHER_WART);

    private static final Set<Material> REPLANT_GROUND = Set.of(
            Material.DIRT, Material.GRASS_BLOCK, Material.PODZOL, Material.COARSE_DIRT);

    private record Scored(Block block, int priority, double distSq) {}

    /** A Null on a timed hunt against one roster player (used by invasions and End ambushes). */
    private record Hunt(UUID target, long expiresAt) {}

    private final NullControllerPlugin plugin;
    private final ConsentManager consent;
    private final List<NPC> nulls = new ArrayList<>();
    private final Random rng = new Random();

    private Mode mode = Mode.GUARD;
    private Location gotoTarget;
    private long cooldownUntil;

    private final Map<NPC, Inventory> packs = new HashMap<>();
    private final Map<NPC, Long> lastApple = new HashMap<>();
    private final Set<NPC> depositing = new HashSet<>();

    private final Deque<Block> workTargets = new ArrayDeque<>();
    private final Map<NPC, Block> claimed = new HashMap<>();
    private int cycle;
    private int lastScanCycle = -SCAN_EVERY_CYCLES;
    /** Which kind of work the shared target list holds right now, for idle Nulls working on their own. */
    private WorkKind autoKind = WorkKind.MINE;
    /** When the owner last placed a block: idle Nulls ring them while they build. */
    private long lastBuildingAt;

    private final List<Location> patrolRoute = new ArrayList<>();
    private final Map<NPC, Integer> waypointIndex = new HashMap<>();

    private final Map<NPC, Hunt> hunters = new HashMap<>();
    /** Individual orders. A Null with an entry here ignores its rank order and follows this one. */
    private final Map<NPC, Mode> soloModes = new HashMap<>();
    /** A focused attack: one roster player or hostile mob, for a limited time. */
    private record Focus(UUID target, long expiresAt) {}
    private final Map<NPC, Focus> focus = new HashMap<>();
    /** Hidden Nulls lying in wait for a target. They break cover when the target comes within reach. */
    private record Wait(UUID target, long expiresAt) {}
    private final Map<NPC, Wait> waits = new HashMap<>();
    private static final double WAIT_TRIGGER = 14;
    private static final long PEARL_COOLDOWN_MS = 15_000L;
    private static final long DASH_COOLDOWN_MS = 6_000L;
    private final Map<NPC, Long> lastPearl = new HashMap<>();
    private final Map<NPC, Long> lastDash = new HashMap<>();
    /** Escapees being chased right now, with when the chase ends. */
    private final Map<UUID, Long> pursuits = new HashMap<>();
    /** Cobwebs the squad laid in front of a runner, with when they were laid. */
    private final Map<Block, Long> snares = new HashMap<>();
    private long lastSnare;
    /** The Null the owner is looking through, and the owner's game mode to restore. */
    private NPC viewing;
    private GameMode viewRestore = GameMode.SURVIVAL;
    private final Map<NPC, Integer> lastAttack = new HashMap<>();
    private final Map<NPC, Location> wanderGoals = new HashMap<>();
    private final Map<NPC, Integer> wanderUntil = new HashMap<>();
    private NPC leader;
    private final Map<NPC, Character> ranks = new HashMap<>();
    /** Per-rank modes set by the owner (guard, follow, stay). A rank without one follows the army mode. */
    private final Map<Character, Mode> rankModes = new HashMap<>();
    /** Weapons the owner has armed a rank with. They survive mode changes. */
    private final Map<Character, ItemStack> rankArms = new HashMap<>();
    private Location cageGuard;
    private final Map<NPC, Character> promotions = new HashMap<>();
    private final Map<NPC, Integer> forgeCycles = new HashMap<>();
    private long alarmUntil;
    private UUID huntTarget;
    /** Nulls mid-arrival: they're in the portal effect and skip their normal moves until it lands. */
    private final Set<NPC> arriving = new HashSet<>();
    private long lastOfflineScan;
    private record Spy(UUID target, long[] nextReport) {}
    private final Map<NPC, Spy> spies = new HashMap<>();
    /** Offline roaming: where each Null is heading, and when it picks a new spot. */
    private record Wander(Location dest, long until) {}
    private final Map<NPC, Wander> wanders = new HashMap<>();
    private final Random wander = new Random();
    /** Offline Nether run: the frame built in the overworld, and where it lands in the Nether. */
    private Location netherPortal;
    private Location netherSpot;
    private static final double DEFENCE_RANGE = 24.0;
    private static final double STRIKE_SAFE_RADIUS = 22.0;
    private static final int STRIKE_SQUAD = 4;
    private static final int BREACH_PORTALS = 20;
    private static final int ORBITAL_GRID = 7;
    private static final int ORBITAL_SPACING = 2;
    private static final int STAB_STEP = 3;
    private static final double BREACH_RADIUS = 16.0;
    private long nextReplenish;
    private long nextRestock;
    private long nextAutoStrike;
    /** Learned per player: how hard the Nulls have been hit by them. Raises the damage the Nulls deal back. */
    private final Map<UUID, Double> threat;
    /** Fight threat and login habits, saved to memory.yml. */
    private final Memory memory;
    /** Per archer: when it last loosed an arrow. */
    private final Map<NPC, Long> lastShot = new HashMap<>();
    /** Per player: the hour a predicted-login alert was last sent. */
    private final Map<UUID, Integer> predictedAlert = new HashMap<>();
    private final Map<NPC, Long> potCooldown = new HashMap<>();
    /** Learned from losses: where Nulls have gone down. The most recent twenty. */
    private final List<Location> dangerSpots = new ArrayList<>();
    private final Map<NPC, Long> woundAlert = new HashMap<>();
    /** Nulls waiting, hidden, where a hurt or fleeing roster player will rejoin. Maps the Null to the player. */
    private final Map<NPC, UUID> lurkers = new HashMap<>();
    private Chests chestsRef;
    private Chests chests() {
        if (chestsRef == null) chestsRef = new Chests(plugin);
        return chestsRef;
    }
    private long nextAlarm;

    private boolean swinging;
    private BukkitRunnable swingTask;

    public NullArmy(NullControllerPlugin plugin, ConsentManager consent) {
        this.plugin = plugin;
        this.consent = consent;
        this.memory = new Memory(plugin.getDataFolder());
        this.threat = memory.threat();
    }

    /** The guard Null stationed at the prison while anyone is held there. Kept out of the normal army loops. */
    private NPC prisonGuard;
    private boolean prisonGuardNeeded;

    /** Called by the Nether law whenever prisoners are added or released. */
    public void setPrisonGuardNeeded(boolean needed) {
        prisonGuardNeeded = needed;
        if (!needed && prisonGuard != null) {
            prisonGuard.destroy();
            prisonGuard = null;
        }
    }

    /** A world-and-coordinates setting from config.yml, or null if it isn't set. */
    private Location configLoc(String key) {
        String raw = plugin.getConfig().getString(key, "");
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(",");
        if (parts.length != 4) return null;
        World w = org.bukkit.Bukkit.getWorld(parts[0].trim());
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Keeps one Null at the prison while there's a prisoner: respawned if lost, held there if moved. */
    private void tickPrisonGuard() {
        if (!prisonGuardNeeded) return;
        Location prison = configLoc("prison");
        if (prison == null) return;
        if (prisonGuard == null || !prisonGuard.isSpawned()) {
            if (prisonGuard != null) prisonGuard.destroy();
            NPC npc = createNull(prison.clone().add(0, 1, 0), gearForMode(Mode.GUARD), false);
            nulls.remove(npc); // not part of the normal army loops
            prisonGuard = npc;
            return;
        }
        Location here = prisonGuard.getStoredLocation();
        if (here == null || !sameWorld(here, prison) || here.distance(prison) > TELEPORT_OVER) {
            teleport(prisonGuard, prison.clone().add(0, 1, 0));
        } else if (here.distance(prison) > 4) {
            prisonGuard.getNavigator().setTarget(prison.clone().add(0, 1, 0));
        }
    }

    /** One Null per post listed under guard-posts in config.yml (world,x,y,z). Always on, owner online or not. */
    private final Map<String, NPC> stationGuards = new HashMap<>();

    private void tickStationGuards() {
        for (String raw : plugin.getConfig().getStringList("guard-posts")) {
            String[] p = raw.split(",");
            if (p.length != 4) continue;
            World w = org.bukkit.Bukkit.getWorld(p[0].trim());
            if (w == null) continue;
            Location spot;
            try {
                spot = new Location(w, Double.parseDouble(p[1]) + 0.5, Double.parseDouble(p[2]) + 1,
                        Double.parseDouble(p[3]) + 0.5);
            } catch (NumberFormatException ex) {
                continue;
            }
            NPC npc = stationGuards.get(raw);
            if (npc == null || !npc.isSpawned()) {
                if (npc != null) npc.destroy();
                npc = createNull(spot.clone(), gearForMode(Mode.GUARD), false);
                nulls.remove(npc); // not part of the normal army loops
                stationGuards.put(raw, npc);
                continue;
            }
            Location here = npc.getStoredLocation();
            if (here == null || !sameWorld(here, spot) || here.distance(spot) > TELEPORT_OVER) {
                teleport(npc, spot.clone());
            } else if (here.distance(spot) > 3) {
                npc.getNavigator().setTarget(spot.clone());
            }
        }
    }

    /**
     * The ender pearl cannon: a free Null throws a pearl at the aim spot. The pearl teleports the
     * Null where it lands, a long-range move faster than walking. Refuses if a non-roster player
     * is near the landing spot.
     */
    public String pearlCannonAt(Player owner, Location aim) {
        for (Player other : aim.getWorld().getPlayers()) {
            if (other.getLocation().distance(aim) <= 10 && !consent.canTarget(other) && !other.equals(owner)) {
                return "Refused. " + other.getName() + " is not on the roster and is near the landing spot.";
            }
        }
        NPC shooter = null;
        for (NPC npc : nulls) {
            if (npc.isSpawned() && npc.getEntity() instanceof LivingEntity && !hunters.containsKey(npc)
                    && !spies.containsKey(npc) && !arriving.contains(npc)) {
                shooter = npc;
                break;
            }
        }
        if (shooter == null) return "No free Null to fire the pearl cannon.";
        LivingEntity e = (LivingEntity) shooter.getEntity();
        Location from = e.getEyeLocation();
        if (!sameWorld(from, aim)) return "The pearl cannon only fires within one world.";
        Vector dir = aim.toVector().subtract(from.toVector());
        double dist = dir.length();
        if (dist < 1) return "Aim further away.";
        double speed = Math.min(3.5, 1.8 + dist * 0.05);
        Vector velocity = dir.normalize().multiply(speed).setY(dir.getY() / dist * speed + 0.6);
        e.launchProjectile(org.bukkit.entity.EnderPearl.class, velocity);
        swing(shooter);
        return "Pearl cannon fired at " + coordsOf(aim) + ".";
    }

    /** A Mark Rod: the army hits this player much harder from now on. Adds to the learned threat. */
    public void markTarget(UUID id) {
        threat.merge(id, 10.0, Double::sum);
    }

    /** The owner placed a block: idle Nulls guard them for the next minute. */
    public void noteBuilding(Player p) {
        if (consent.isOwner(p)) lastBuildingAt = System.currentTimeMillis();
    }

    /** Called when a roster player joins: learns when they usually come online. */
    public void recordLogin(UUID id) {
        memory.recordLogin(id, currentHour());
    }

    private int currentHour() {
        String zone = plugin.getConfig().getString("timezone", "Europe/London");
        try {
            return java.time.ZonedDateTime.now(java.time.ZoneId.of(zone)).getHour();
        } catch (java.time.DateTimeException e) {
            return java.time.LocalTime.now().getHour();
        }
    }

    public void startTicker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 20L, 10L);
    }

    public void shutdown() {
        stopSwinging();
        // Despawn but keep the NPCs: Citizens saves them, and adoptSurvivors() brings them back on startup.
        for (NPC npc : new ArrayList<>(nulls)) {
            if (npc.isSpawned()) npc.despawn();
        }
        resetWork();
        memory.save();
    }

    /** Citizens tag on every Null, so a restart can find them again. */
    static final String NULL_TAG = "nullcontroller-null";

    /**
     * Re-adopts Nulls that Citizens still has after a restart, and spawns them where they were.
     * Called a few seconds after enable, because Citizens loads its NPCs after NullController enables.
     */
    public void adoptSurvivors() {
        int found = 0;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.data().has(NULL_TAG) || nulls.contains(npc)) continue;
            nulls.add(npc);
            if (LEADER_NAME.equals(npc.getName()) && leader == null) leader = npc;
            packs.put(npc, Bukkit.createInventory(null, PACK_SIZE, "Null Pack"));
            Location where = npc.getStoredLocation();
            if (!npc.isSpawned() && where != null && where.getWorld() != null) npc.spawn(where);
            found++;
        }
        if (found > 0) {
            recomputeRanks();
            plugin.getLogger().info("Re-adopted " + found + " Nulls from before the restart.");
        }
    }

    // ---------- Commands ----------

    public void summon(Player owner, int requested) {
        autoSuppressed = false;
        int toSpawn = Math.min(requested, armyCap() - nulls.size());
        if (toSpawn <= 0) {
            owner.sendMessage(ChatColor.RED + "Army cap reached (" + armyCap() + "). Raise max-nulls in config.yml to add more.");
            return;
        }
        int batch = Math.max(1, plugin.getConfig().getInt("spawn-batch", 5));
        owner.sendMessage(ChatColor.DARK_PURPLE + "Summoning " + toSpawn + " Nulls...");

        new BukkitRunnable() {
            int spawned = 0;

            @Override
            public void run() {
                for (int i = 0; i < batch && spawned < toSpawn; i++, spawned++) {
                    spawnOne(owner);
                }
                if (spawned >= toSpawn) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /** Console-only: dismiss with the owner offline. Returns how many Nulls were removed. */
    public int dismissOffline() {
        int count = nulls.size();
        removeAll();
        return count;
    }

    /** Console-only mode switch with the owner offline. Returns false if the mode can't run yet. */
    public boolean setModeOffline(Mode newMode) {
        if (newMode == Mode.PATROL && patrolRoute.isEmpty()) return false;
        if (newMode == Mode.FORGE && configuredBlock("forge-block") == null) return false;
        this.mode = newMode;
        cageGuard = null;
        huntTarget = null;
        resetWork();
        wanders.clear();
        applyGear(newMode);
        return true;
    }

    public String statusOffline() {
        return "Nulls: " + nulls.size() + "/" + armyCap() + "  Mode: " + mode.name();
    }

    /**
     * Console-only summon with the owner offline. Spawns at the army's own spot, or at the
     * world spawn if no Nulls exist yet. Returns the count queued, 0 if the cap is reached,
     * or -1 while the army is regrouping.
     */
    public int summonOffline(int requested) {
        autoSuppressed = false;
        int toSpawn = Math.min(requested, armyCap() - nulls.size());
        if (toSpawn <= 0) return 0;
        Location anchor = offlineAnchor();
        int batch = Math.max(1, plugin.getConfig().getInt("spawn-batch", 5));
        new BukkitRunnable() {
            int spawned = 0;

            @Override
            public void run() {
                for (int i = 0; i < batch && spawned < toSpawn; i++, spawned++) {
                    createNull(formationPointAt(anchor, nulls.size()), gearForMode(mode), true);
                }
                if (spawned >= toSpawn) cancel();
            }
        }.runTaskTimer(plugin, 0L, 2L);
        return toSpawn;
    }

    private Location offlineAnchor() {
        for (NPC npc : nulls) {
            Location stored = npc.getStoredLocation();
            if (stored != null) return stored.clone();
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    public void dismissAll(Player owner) {
        viewStop(owner);
        soloModes.clear();
        focus.clear();
        int count = nulls.size();
        autoSuppressed = true; // dismissed means dismissed: no self-summoning until you summon again
        removeAll();
        owner.sendMessage(ChatColor.DARK_PURPLE + "Dismissed " + count + " Nulls.");
    }

    /** Rank names as the plugin labels them: R ward, A eye, I sentry, D bolt. */
    private static String rankName(char rank) {
        return switch (rank) {
            case 'R' -> "ward (R)";
            case 'A' -> "eye (A)";
            case 'I' -> "sentry (I)";
            default -> "bolt (D)";
        };
    }

    /**
     * An order for one rank only: recall those Nulls to the owner, or dismiss them.
     * Other ranks are left exactly as they are.
     */
    public String rankOrder(Player owner, char rank, String order) {
        if (order.equals("guard") || order.equals("follow") || order.equals("stay") || order.equals("clear")) {
            if (rank == 'R') return "Wards always stay beside you as your bodyguard.";
            if (rank == 'A') return "The Commander stays with you. Use recall or dismiss on him.";
            if (order.equals("clear")) {
                rankModes.remove(rank);
                return rankName(rank) + " Nulls go back to the army order.";
            }
            rankModes.put(rank, Mode.valueOf(order.toUpperCase()));
            return rankName(rank) + " Nulls now " + order + ".";
        }
        List<NPC> chosen = new ArrayList<>();
        for (NPC npc : nulls) {
            if (ranks.getOrDefault(npc, 'D') == rank) chosen.add(npc);
        }
        if (chosen.isEmpty()) return "No " + rankName(rank) + " Nulls in the army.";
        switch (order) {
            case "recall" -> {
                for (int i = 0; i < chosen.size(); i++) {
                    if (chosen.get(i).isSpawned()) teleport(chosen.get(i), formationPoint(owner, i));
                }
                return "Recalling " + chosen.size() + " " + rankName(rank) + " Nulls to you.";
            }
            case "dismiss" -> {
                for (NPC npc : chosen) dropNpc(npc);
                resetWork();
                return "Dismissed " + chosen.size() + " " + rankName(rank) + " Nulls.";
            }
            default -> {
                return "Rank orders: recall, dismiss, guard, follow, stay or clear.";
            }
        }
    }

    /** Sends the whole squad to a spot. Same world: they walk. Another world: they go through a portal. */
    public void sendTo(Location spot) {
        for (NPC npc : nulls) {
            if (!npc.isSpawned()) continue;
            Location here = npc.getStoredLocation();
            if (here != null && !sameWorld(here, spot)) teleport(npc, spot);
            else walkTo(npc, spot.clone());
        }
    }

    public void recall(Player owner) {
        for (int i = 0; i < nulls.size(); i++) {
            NPC npc = nulls.get(i);
            if (npc.isSpawned()) {
                teleport(npc, formationPoint(owner, i));
            }
        }
        owner.sendMessage(ChatColor.DARK_PURPLE + "Recalling the army to your position.");
    }

    /** Returns false (and explains why) if the mode can't run yet. */
    public boolean setMode(Mode newMode, Player owner) {
        if (newMode == Mode.PATROL && patrolRoute.isEmpty()) {
            owner.sendMessage(ChatColor.RED + "No patrol route yet. Stand at each point and run /nullcmd patrol add.");
            return false;
        }
        if (newMode == Mode.FORGE && configuredBlock("forge-block") == null) {
            owner.sendMessage(ChatColor.RED + "No Forge block set. Look at one and run /nullcmd forge set.");
            return false;
        }
        this.mode = newMode;
        soloModes.clear(); // an army-wide order overrides individual ones
        cageGuard = null;
        huntTarget = null;
        resetWork();
        wanders.clear();
        applyGear(newMode);
        owner.sendMessage(ChatColor.DARK_PURPLE + "Army mode: " + newMode.name());
        return true;
    }

    public void goTo(Location target, Player owner) {
        this.gotoTarget = target;
        this.mode = Mode.GOTO;
        resetWork();
        applyGear(Mode.GOTO);
        owner.sendMessage(ChatColor.DARK_PURPLE + String.format(
                "Army moving to %.0f, %.0f, %.0f in %s.",
                target.getX(), target.getY(), target.getZ(),
                target.getWorld() == null ? "unknown world" : target.getWorld().getName()));
    }

    /** Console version of patrol add: a waypoint at the given spot. */
    public String patrolAddAt(Location at) {
        if (patrolRoute.size() >= MAX_WAYPOINTS) return "Patrol route is full (" + MAX_WAYPOINTS + " points).";
        patrolRoute.add(at.clone());
        return "Waypoint " + patrolRoute.size() + " added.";
    }

    /** Console version of patrol clear. */
    public String patrolClearRoute() {
        patrolRoute.clear();
        return "Patrol route cleared.";
    }

    public void patrolAdd(Player owner) {
        if (patrolRoute.size() >= MAX_WAYPOINTS) {
            owner.sendMessage(ChatColor.RED + "Patrol route is full (" + MAX_WAYPOINTS + " points).");
            return;
        }
        patrolRoute.add(owner.getLocation().clone());
        owner.sendMessage(ChatColor.DARK_PURPLE + "Waypoint " + patrolRoute.size() + " added.");
    }

    public void patrolClear(Player owner) {
        patrolRoute.clear();
        waypointIndex.clear();
        owner.sendMessage(ChatColor.DARK_PURPLE + "Patrol route cleared.");
    }

    public void setDepositChest(Block block, Player owner) {
        if (!(block.getState() instanceof Container)) {
            owner.sendMessage(ChatColor.RED + "Look at a chest, barrel, or other container and try again.");
            return;
        }
        saveBlock("deposit-chest", block);
        owner.sendMessage(ChatColor.DARK_PURPLE + "Deposit chest set at " + block.getX() + ", "
                + block.getY() + ", " + block.getZ() + ".");
    }

    public void setForge(Block block, Player owner) {
        saveBlock("forge-block", block);
        owner.sendMessage(ChatColor.DARK_PURPLE + "Forge set at " + block.getX() + ", "
                + block.getY() + ", " + block.getZ() + ".");
    }

    /** Spawns a timed hunting group against one roster player. The caller checks consent first. Owner may be null. */
    public boolean spawnHunters(Player owner, Player target, Location center, int count, int minutes) {
        if (nulls.size() + count > armyCap()) {
            if (owner != null) owner.sendMessage(ChatColor.RED + "Not enough room under the army cap (" + armyCap() + ").");
            return false;
        }
        long expires = System.currentTimeMillis() + minutes * 60_000L;
        for (int i = 0; i < count; i++) {
            Location around = center != null ? center : target.getLocation();
            NPC npc = createNull(ringSpot(around, i, count), gearForMode(Mode.HUNT), false);
            if (!npc.isSpawned()) npc.spawn(ringSpot(around, i, count));
            waitFor(npc, target, expires);
            if (i % 3 == 2) {
                snipers.add(npc);
                setHeldItem(npc, sniperBow());
            }
        }
        return true;
    }

    /** Ambush: portals open around one roster player, and Nulls step out of them after a moment. */
    /**
     * Nether trap: a portal opens at the hook in the current world, and hidden Nulls wait on the
     * far side at the Nether base. They break cover when the owner comes through and gets close.
     */
    public String netherWaitAt(Player owner, Location hook, int count, int minutes) {
        Location base = offlineSite();
        if (base == null || base.getWorld() == null) return "No Nether base set yet. Set one first.";
        if (hook.getWorld() == null || hook.getWorld().getEnvironment() == World.Environment.NETHER)
            return "Cast it in the Overworld: the portal needs to lead to the Nether base.";
        if (nulls.size() + count > armyCap()) return "Not enough room under the army cap (" + armyCap() + ").";
        String made = buildPortalAt(owner, hook);
        if (!made.contains("open at")) return made;
        long expires = System.currentTimeMillis() + minutes * 60_000L;
        World nether = base.getWorld();
        for (int i = 0; i < count; i++) {
            double side = (i % 2 == 0 ? -2.2 : 2.2);
            double depth = 2.5 + (i / 2) * 1.5;
            Location spot = new Location(nether, base.getX() + side, base.getY(), base.getZ() + depth);
            NPC npc = createNull(spot, gearForMode(Mode.HUNT), false);
            if (!npc.isSpawned()) npc.spawn(spot);
            waitFor(npc, owner, expires);
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                for (int a = -2; a <= 1; a++)
                    for (int y = hook.getBlockY() - 1; y <= hook.getBlockY() + 3; y++) {
                        Block b1 = hook.getWorld().getBlockAt(hook.getBlockX() + a, y, hook.getBlockZ());
                        Block b2 = hook.getWorld().getBlockAt(hook.getBlockX(), y, hook.getBlockZ() + a);
                        for (Block b : new Block[]{b1, b2}) {
                            if (b.getType() == Material.NETHER_PORTAL || b.getType() == Material.OBSIDIAN) b.setType(Material.AIR, false);
                        }
                    }
            }
        }.runTaskLater(plugin, minutes * 60L * 20L);
        return made + " " + count + " Nulls are waiting in the Nether for " + owner.getName() + ". Walk through when ready.";
    }

    public void ambush(Player owner, Player target, int count, int minutes) {
        ambushAt(owner, target, count, minutes, true);
    }

    private void ambushAt(Player owner, Player target, int count, int minutes, boolean secondWave) {
        if (nulls.size() + count > armyCap()) {
            if (owner != null) owner.sendMessage(ChatColor.RED + "Not enough room under the army cap (" + armyCap() + ").");
            return;
        }
        Location centre = cornerSpot(target.getLocation());
        World w = centre.getWorld();
        if (w == null) return;
        // A real portal walks anyone in: refuse if anyone who isn't on the roster, or you, is close by.
        for (Player other : w.getPlayers()) {
            if (other.getLocation().distance(centre) > 20) continue;
            if (other.equals(target)) continue;
            if (!consent.canTarget(other)) {
                if (owner != null) owner.sendMessage(ChatColor.RED + "Ambush refused: " + other.getName()
                        + " is not on the roster and is too close to the portal.");
                return;
            }
        }
        boolean nether = w.getEnvironment() == World.Environment.NETHER;
        int bx = centre.getBlockX(), by = centre.getBlockY(), bz = centre.getBlockZ();
        // Overworld: the portal walks anyone in. Nether: the Nulls step out of the ground, no portal needed.
        List<Block> built = new ArrayList<>();
        if (!nether) {
            for (int x = bx - 2; x <= bx + 1; x++)
                for (int y = by - 1; y <= by + 3; y++) {
                    boolean interior = x >= bx - 1 && x <= bx && y >= by && y <= by + 2;
                    Block b = w.getBlockAt(x, y, bz);
                    if (interior) {
                        Orientable axis = (Orientable) Material.NETHER_PORTAL.createBlockData();
                        axis.setAxis(Axis.X);
                        b.setBlockData(axis, false);
                    } else {
                        b.setType(Material.OBSIDIAN, false);
                    }
                    built.add(b);
                }
            w.strikeLightningEffect(centre); // cosmetic only: no damage, no fire
            ambushPortals.put(target.getUniqueId(), built);
        }
        if (owner != null) owner.sendMessage(ChatColor.DARK_RED + (nether ? "Ambush set" : "Portal open") + " at " + coordsOf(centre)
                + ". Nulls step out for " + target.getName() + ".");
        long expires = System.currentTimeMillis() + minutes * 60_000L;
        // The Commander stays with the owner. The rest wait beside the exit, hidden, and break cover when the target comes close.
        for (int i = 0; i < count; i++) {
            double side = (i % 2 == 0 ? -2.2 : 2.2);
            double depth = 2.5 + (i / 2) * 1.5;
            Location spot = new Location(w, bx + 0.5 + side, by, bz + depth);
            NPC npc = createNull(spot, gearForMode(Mode.HUNT), false);
            if (!npc.isSpawned()) npc.spawn(spot);
            waitFor(npc, target, expires);
            ambushers.add(npc);
        }
        if (owner != null) owner.sendMessage(ChatColor.DARK_RED + "" + count + " Nulls are waiting for "
                + target.getName() + ".");
        // The portal closes when the ambush ends.
        new BukkitRunnable() {
            @Override
            public void run() {
                closePortals(target.getUniqueId());
            }
        }.runTaskLater(plugin, minutes * 60L * 20L);
        // Second wave: if the target has run well clear of the first portal, a fresh squad comes through one beside them.
        if (secondWave && minutes > 1) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!target.isOnline() || target.getWorld() != w) return;
                    if (target.getLocation().distance(centre) < 15) return;
                    if (!consent.canTarget(target)) return;
                    ambushAt(owner, target, Math.max(1, count / 2), minutes - 1, false);
                }
            }.runTaskLater(plugin, 60L * 20L);
        }
    }

    /** Closes a target's ambush portal, so they can't walk back out of the trap. */
    private void closePortals(UUID target) {
        List<Block> blocks = ambushPortals.remove(target);
        if (blocks == null) return;
        for (Block b : blocks) {
            if (b.getType() == Material.NETHER_PORTAL || b.getType() == Material.OBSIDIAN) b.setType(Material.AIR, false);
        }
    }

    /** Totem of NULL ran out of charges: the army disbands and can't be resummoned for a while. */
    public void breakArmy(Player owner) {
        int minutes = plugin.getConfig().getInt("army-cooldown-minutes", 30);
        removeAll();
        cooldownUntil = System.currentTimeMillis() + minutes * 60_000L;
        owner.sendMessage(ChatColor.RED + "The Totem of NULL is spent. Every Null has disbanded and the army "
                + "can't be resummoned for " + minutes + " minutes.");
    }

    /**
     * Scouting report: where every online roster player is, how many Nulls are out in each world,
     * and which roster players have hit the army hardest. Read-only, so nothing is ordered.
     */
    public void scoutReport(Player owner) {
        owner.sendMessage(ChatColor.DARK_PURPLE + "=== Scouting report ===");
        Map<String, Integer> byWorld = new HashMap<>();
        for (NPC npc : nulls) {
            if (npc.isSpawned()) byWorld.merge(npc.getEntity().getWorld().getName(), 1, Integer::sum);
        }
        owner.sendMessage(ChatColor.GRAY + "Nulls out: " + ChatColor.WHITE + nulls.size()
                + (byWorld.isEmpty() ? "" : ChatColor.GRAY + " (" + byWorld.entrySet().stream()
                        .map(e -> e.getKey() + " " + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("") + ")"));
        int seen = 0;
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p.equals(owner) || !consent.canTarget(p)) continue;
            seen++;
            owner.sendMessage(ChatColor.GRAY + "Roster: " + ChatColor.WHITE + p.getName() + ChatColor.GRAY
                    + " in " + p.getWorld().getEnvironment().name().toLowerCase() + " at " + coordsOf(p.getLocation()));
        }
        if (seen == 0) owner.sendMessage(ChatColor.GRAY + "No roster players are online.");
        java.util.List<java.util.Map.Entry<UUID, Double>> hardest = new java.util.ArrayList<>(threat.entrySet());
        hardest.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int shown = 0;
        for (var e : hardest) {
            if (shown++ >= 3 || e.getValue() <= 0) break;
            org.bukkit.OfflinePlayer off = org.bukkit.Bukkit.getOfflinePlayer(e.getKey());
            owner.sendMessage(ChatColor.GRAY + "Hardest hit by: " + ChatColor.WHITE
                    + (off.getName() == null ? e.getKey().toString() : off.getName())
                    + ChatColor.GRAY + " (threat " + String.format("%.0f", e.getValue()) + ")");
        }
    }

    /** Equips one weapon in the hand of every Null of a rank. Returns a message for the owner. */
    public String armRank(char rank, String key) {
        org.bukkit.inventory.ItemStack weapon = Arsenal.make(key);
        if (weapon == null) return "Unknown weapon. Options: " + String.join(", ", Arsenal.KEYS) + ".";
        rankArms.put(rank, weapon);
        int count = 0;
        for (NPC npc : nulls) {
            if (ranks.getOrDefault(npc, 'D') != rank) continue;
            npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, weapon.clone());
            count++;
        }
        if (count == 0) return "No " + rankName(rank) + " Nulls in the army.";
        return "Equipped " + count + " " + rankName(rank) + " Nulls with the " + key + ".";
    }

    /** Reports the nearest chest of each kind to the owner: how many item stacks it holds. */
    public void chestReport(Player owner) {
        owner.sendMessage(ChatColor.DARK_PURPLE + "=== Chests ===");
        for (Chests.Kind kind : Chests.Kind.values()) {
            org.bukkit.block.Container c = chests().nearest(kind, owner.getLocation());
            String label = kind.name().toLowerCase();
            if (c == null) {
                owner.sendMessage(ChatColor.GRAY + label + ": " + ChatColor.WHITE + "none placed");
                continue;
            }
            int stacks = 0;
            for (ItemStack item : c.getInventory().getContents()) {
                if (item != null && !item.getType().isAir()) stacks++;
            }
            owner.sendMessage(ChatColor.GRAY + label + ": " + ChatColor.WHITE + stacks + " stacks"
                    + ChatColor.GRAY + " at " + coordsOf(c.getLocation()));
        }
    }

    /** A short, plain summary of the army for the Commander's chat, so he knows what is going on. */
    public String chatContext(Player owner) {
        Map<Character, Integer> counts = new HashMap<>();
        for (char c : ranks.values()) counts.merge(c, 1, Integer::sum);
        StringBuilder roster = new StringBuilder();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (!consent.canTarget(p)) continue;
            String where = p.getWorld() == owner.getWorld()
                    ? (int) p.getLocation().distance(owner.getLocation()) + " blocks away"
                    : "in " + p.getWorld().getEnvironment().name().toLowerCase();
            roster.append(p.getName()).append(" (hp ").append(Math.round(p.getHealth())).append(", ").append(where).append(") ");
        }
        return "Nulls: " + nulls.size() + "/" + armyCap()
                + ". Ranks: ward R=" + counts.getOrDefault('R', 0)
                + ", commander A=" + counts.getOrDefault('A', 0)
                + ", general I=" + counts.getOrDefault('I', 0)
                + ", soldier D=" + counts.getOrDefault('D', 0)
                + ". Army mode: " + mode.name()
                + ". Owner " + owner.getName() + " (hp " + Math.round(owner.getHealth()) + "/20, food " + owner.getFoodLevel() + "/20) is in " + owner.getWorld().getEnvironment().name().toLowerCase()
                + " at " + coordsOf(owner.getLocation())
                + ". Roster players online: " + (roster.length() == 0 ? "none" : roster.toString().trim())
                + ". Recent alarms: " + (plugin.recentAlerts(6).isEmpty() ? "none" : String.join(" | ", plugin.recentAlerts(6)))
                + ". Hunts active: " + (hunters.isEmpty() ? "none" : hunters.size() + " Nulls out after roster players") + "."
                + pursuitLine();
    }

    /** Lists where Nulls went down, for the console. */
    public String dangerReport() {
        if (dangerSpots.isEmpty()) return "No danger spots marked.";
        StringBuilder sb = new StringBuilder("Danger spots (" + dangerSpots.size() + "): ");
        for (Location spot : dangerSpots) sb.append(coordsOf(spot)).append("  ");
        return sb.toString().trim();
    }

    public String clearDangers() {
        int n = dangerSpots.size();
        dangerSpots.clear();
        return "Cleared " + n + " danger spots.";
    }

    /** Calls off every pursuit, and recalls the hunters chasing them. */
    public String stopPursuits() {
        Set<UUID> ids = new HashSet<>(pursuits.keySet());
        pursuits.clear();
        for (Map.Entry<NPC, Hunt> e : new ArrayList<>(hunters.entrySet())) {
            if (ids.contains(e.getValue().target())) dropNpc(e.getKey());
        }
        return "Called off " + ids.size() + " pursuit(s).";
    }

    /** The escape side of the army state: who is being hunted, how many Nulls are hurt, and the danger spots. */
    private String pursuitLine() {
        StringBuilder chased = new StringBuilder();
        for (UUID id : pursuits.keySet()) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null && pursuing(id)) chased.append(p.getName()).append(" ");
        }
        int hurt = 0;
        for (NPC npc : nulls) {
            if (npc.isSpawned() && isWounded(npc)) hurt++;
        }
        return " Escapees being hunted: " + (chased.length() == 0 ? "none" : chased.toString().trim())
                + ". Nulls hurt: " + hurt + ". Danger spots where Nulls went down: " + dangerSpots.size() + ".";
    }

    public void status(Player owner) {
        owner.sendMessage(ChatColor.DARK_PURPLE + "=== Null Army ===");
        owner.sendMessage(ChatColor.GRAY + "Nulls: " + ChatColor.WHITE + nulls.size() + "/" + armyCap());
        owner.sendMessage(ChatColor.GRAY + "Mode: " + ChatColor.WHITE + mode.name());
        if (mode == Mode.GOTO && gotoTarget != null) {
            owner.sendMessage(ChatColor.GRAY + "Destination: " + ChatColor.WHITE
                    + String.format("%.0f, %.0f, %.0f", gotoTarget.getX(), gotoTarget.getY(), gotoTarget.getZ()));
        }
        WorkKind kind = kindOf(mode);
        if (kind != null) {
            owner.sendMessage(ChatColor.GRAY + "Blocks queued: " + ChatColor.WHITE + workTargets.size()
                    + ChatColor.GRAY + ", Nulls working: " + ChatColor.WHITE + claimed.size());
        }
        Map<Character, Integer> counts = new HashMap<>();
        for (char c : ranks.values()) counts.merge(c, 1, Integer::sum);
        owner.sendMessage(ChatColor.GRAY + "Ranks: " + ChatColor.WHITE + "R" + counts.getOrDefault('R', 0)
                + " A" + counts.getOrDefault('A', 0) + " I" + counts.getOrDefault('I', 0)
                + " D" + counts.getOrDefault('D', 0));
        owner.sendMessage(ChatColor.GRAY + "Nulls emptying packs: " + ChatColor.WHITE + depositing.size());
        owner.sendMessage(ChatColor.GRAY + "Hunters on timers: " + ChatColor.WHITE + hunters.size());
        owner.sendMessage(ChatColor.GRAY + "Deposit chest: " + ChatColor.WHITE
                + (configuredBlock("deposit-chest") == null ? "not set" : "set"));
        owner.sendMessage(ChatColor.GRAY + "Forge: " + ChatColor.WHITE
                + (configuredBlock("forge-block") == null ? "not set" : "set"));
        if (!patrolRoute.isEmpty()) {
            owner.sendMessage(ChatColor.GRAY + "Patrol waypoints: " + ChatColor.WHITE + patrolRoute.size());
        }
    }

    // ---------- Spawning and gear ----------

    private int armyCap() {
        return Math.min(plugin.getConfig().getInt("max-nulls", 20), HARD_CAP);
    }

    private void spawnOne(Player owner) {
        createNull(formationPoint(owner, nulls.size()), gearForMode(mode), true);
    }

    private NPC createNull(Location loc, ItemStack gear, boolean claimLeader) {
        boolean becomesLeader = claimLeader && leader == null;
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, becomesLeader ? LEADER_NAME : randomName());
        npc.data().setPersistent(NULL_TAG, true);
        npc.setProtected(false); // Citizens NPCs are invulnerable by default; Nulls must be hittable
        npc.getNavigator().getDefaultParameters().baseSpeed(1.4f); // Nulls run to their jobs and fights, not stroll
        npc.getOrAddTrait(GameModeTrait.class).setGameMode(GameMode.SURVIVAL); // creative players can't be hurt
        setHeldItem(npc, gear);
        equipArmour(npc);
        npc.spawn(loc);
        if (loc.getWorld() != null) loc.getWorld().strikeLightningEffect(loc); // the wiki: lightning accompanies NULL
        applySkin(npc);
        nulls.add(npc);
        if (becomesLeader) leader = npc;
        recomputeRanks();
        Inventory pack = Bukkit.createInventory(null, PACK_SIZE, "Null Pack");
        pack.addItem(new ItemStack(Material.GOLDEN_APPLE, 4), new ItemStack(Material.COOKED_BEEF, 16), new ItemStack(Material.POTION, 2));
        packs.put(npc, pack);
        return npc;
    }

    // The black skin is fetched from the URL once, then its texture is reused for every other Null.
    // Citizens logs and downloads a skin on each fetch, so fetching per Null flooded the console.
    private String skinTexture;
    private String skinSignature;
    private long skinFetchAt = Long.MIN_VALUE / 2;
    private static final long SKIN_FETCH_COOLDOWN_MS = 60_000L;

    /** Black skin for a Null. Reuses the cached texture; fetches from the URL at most once a minute. */
    private void applySkin(NPC npc) {
        String url = plugin.getConfig().getString("null-skin-url", "");
        if (url == null || url.isBlank()) {
            npc.getOrAddTrait(SkinTrait.class).setSkinName(plugin.getConfig().getString("null-skin", "NULL"));
            return;
        }
        if (skinTexture != null) {
            npc.getOrAddTrait(SkinTrait.class).setSkinPersistent("null-black", skinSignature, skinTexture);
            return;
        }
        if (System.currentTimeMillis() - skinFetchAt >= SKIN_FETCH_COOLDOWN_MS) {
            skinFetchAt = System.currentTimeMillis();
            setSkinUrl(npc, url);
            // Once the texture has arrived, keep it and hand it to every Null that spawned meanwhile.
            Bukkit.getScheduler().runTaskLater(plugin, this::captureSkinTexture, 300L);
        }
    }

    /** Copies the fetched texture off a spawned Null and applies it to the others that still lack it. */
    private void captureSkinTexture() {
        NPC source = null;
        for (NPC npc : nulls) {
            if (!npc.isSpawned()) continue;
            SkinTrait trait = npc.getOrAddTrait(SkinTrait.class);
            if (trait.getTexture() != null && trait.getSignature() != null) {
                skinTexture = trait.getTexture();
                skinSignature = trait.getSignature();
                source = npc;
                break;
            }
        }
        if (skinTexture == null) return; // not back yet; the next Null to spawn after the cooldown tries again
        for (NPC npc : nulls) {
            if (npc == source || !npc.isSpawned()) continue;
            SkinTrait trait = npc.getOrAddTrait(SkinTrait.class);
            if (trait.getTexture() == null) trait.setSkinPersistent("null-black", skinSignature, skinTexture);
        }
    }

    private void setSkinUrl(NPC npc, String url) {
        var console = Bukkit.getConsoleSender();
        CitizensAPI.getDefaultNPCSelector().select(console, npc);
        Bukkit.dispatchCommand(console, "npc skin --url " + url);
        CitizensAPI.getDefaultNPCSelector().deselect(console);
    }

    private void applyGear(Mode m) {
        ItemStack gear = gearForMode(m);
        for (NPC npc : nulls) {
            char rank = ranks.getOrDefault(npc, 'D');
            // A rank weapon chosen by the owner stays in hand, except while Nulls are working with tools.
            boolean working = kindOf(m) != null;
            setHeldItem(npc, !working && rankArms.containsKey(rank) ? rankArms.get(rank) : gear);
        }
    }

    /** Every Null wears full enchanted netherite armour. */
    /** Every Null wears netherite. Rank sets the enchantments and the armour trim. */
    private void equipArmour(NPC npc) {
        char rank = ranks.getOrDefault(npc, 'D');
        int protection = switch (rank) {
            case 'R' -> 4;
            case 'A' -> 3;
            case 'I' -> 2;
            default -> 1;
        };
        String pattern = switch (rank) {
            case 'R' -> "ward";
            case 'A' -> "eye";
            case 'I' -> "sentry";
            default -> "bolt";
        };
        // The NULL wiki's trims: leader and commanders quartz, generals redstone, soldiers netherite.
        String trimMaterial = switch (rank) {
            case 'R', 'A' -> "quartz";
            case 'I' -> "redstone";
            default -> "netherite";
        };
        Equipment eq = npc.getOrAddTrait(Equipment.class);
        eq.set(Equipment.EquipmentSlot.HELMET, rankPiece(Material.NETHERITE_HELMET, protection, pattern, trimMaterial));
        // Every Null wears a real netherite chestplate: elytra gives no protection in a fight.
        // The commander carries the totem, generals a shield.
        if (npc == leader) {
            eq.set(Equipment.EquipmentSlot.OFF_HAND, new ItemStack(Material.TOTEM_OF_UNDYING));
        } else if (rank == 'A' || rank == 'I') {
            eq.set(Equipment.EquipmentSlot.OFF_HAND, new ItemStack(Material.SHIELD));
        }
        eq.set(Equipment.EquipmentSlot.CHESTPLATE, rankPiece(Material.NETHERITE_CHESTPLATE, protection, pattern, trimMaterial));
        eq.set(Equipment.EquipmentSlot.LEGGINGS, rankPiece(Material.NETHERITE_LEGGINGS, protection, pattern, trimMaterial));
        eq.set(Equipment.EquipmentSlot.BOOTS, rankPiece(Material.NETHERITE_BOOTS, protection, pattern, trimMaterial));
    }

    private ItemStack rankPiece(Material m, int protection, String pattern, String trimMaterial) {
        ItemStack item = new ItemStack(m);
        item.addUnsafeEnchantment(Enchantment.PROTECTION, protection);
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        if (protection >= 4) {
            item.addUnsafeEnchantment(Enchantment.MENDING, 1);
            item.addUnsafeEnchantment(Enchantment.THORNS, 3);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof ArmorMeta armor) {
            TrimPattern tp = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(pattern));
            TrimMaterial tm = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(trimMaterial));
            if (tp != null && tm != null) armor.setTrim(new ArmorTrim(tm, tp));
            item.setItemMeta(armor);
        }
        return item;
    }

    /** The fighting sword by rank: the leader's has Fire Aspect and Knockback on top of Sharpness V. */
    private ItemStack rankSword(char rank) {
        switch (rank) {
            case 'R' -> {
                ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
                sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
                sword.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);
                sword.addUnsafeEnchantment(Enchantment.KNOCKBACK, 2);
                sword.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
                return sword;
            }
            case 'A' -> {
                ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
                sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 4);
                sword.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
                return sword;
            }
            case 'I' -> {
                ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
                sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 3);
                return sword;
            }
            default -> {
                ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
                sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 2);
                return sword;
            }
        }
    }

    /** Self-potting: a wounded Null drinks a healing potion on itself, every twelve seconds at most. */
    private void selfPot(NPC npc) {
        long now = System.currentTimeMillis();
        if (now < potCooldown.getOrDefault(npc, 0L)) return;
        if (!(npc.getEntity() instanceof LivingEntity drinker)) return;
        potCooldown.put(npc, now + 12_000L);
        int amplifier = ranks.getOrDefault(npc, 'D') == 'R' ? 2 : 1;
        drinker.addPotionEffect(new PotionEffect(PotionEffectType.INSTANT_HEALTH, 1, amplifier, false, false));
        swing(npc);
    }

    private ItemStack enchantedNetherite(Material m) {
        ItemStack item = new ItemStack(m);
        item.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        return item;
    }

    private void setHeldItem(NPC npc, ItemStack item) {
        npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, item);
    }

    private static WorkKind kindOf(Mode m) {
        return switch (m) {
            case MINE -> WorkKind.MINE;
            case LUMBER -> WorkKind.LUMBER;
            case FARM -> WorkKind.FARM;
            default -> null;
        };
    }

    private ItemStack gearForMode(Mode m) {
        WorkKind kind = kindOf(m);
        if (kind != null) return gearForKind(kind);
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        sword.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        return sword;
    }

    /** Mining gets Fortune III / Efficiency V netherite. Lumber gets an axe. Farming gets a hoe. */
    private ItemStack gearForKind(WorkKind k) {
        return switch (k) {
            case MINE -> {
                ItemStack pick = new ItemStack(Material.NETHERITE_PICKAXE);
                pick.addUnsafeEnchantment(Enchantment.FORTUNE, 3);
                pick.addUnsafeEnchantment(Enchantment.EFFICIENCY, 5);
                yield pick;
            }
            case LUMBER -> new ItemStack(Material.NETHERITE_AXE);
            case FARM -> new ItemStack(Material.NETHERITE_HOE);
        };
    }

    private void dropNpc(NPC npc) {
        waits.remove(npc);
        soloModes.remove(npc);
        focus.remove(npc);
        if (npc == viewing) viewing = null;
        Location l = npc.getStoredLocation();
        Inventory pack = packs.get(npc);
        if (l != null && l.getWorld() != null) {
            l.getWorld().spawnParticle(Particle.PORTAL, l, 30, 0.5, 1.0, 0.5, 0.1);
            if (pack != null) {
                for (ItemStack item : pack.getContents()) {
                    if (item != null) l.getWorld().dropItemNaturally(l, item);
                }
            }
        }
        npc.destroy();
        nulls.remove(npc);
        spies.remove(npc);
        packs.remove(npc);
        depositing.remove(npc);
        claimed.remove(npc);
        waypointIndex.remove(npc);
        lastAttack.remove(npc);
        hunters.remove(npc);
        wanderGoals.remove(npc);
        wanderUntil.remove(npc);
        ranks.remove(npc);
        arriving.remove(npc);
        promotions.remove(npc);
        forgeCycles.remove(npc);
        if (npc == leader) {
            leader = nulls.isEmpty() ? null : nulls.get(0);
            if (leader != null) leader.setName(LEADER_NAME); // the title passes on
        }
        recomputeRanks();
    }

    private void removeAll() {
        for (NPC npc : new ArrayList<>(nulls)) {
            dropNpc(npc);
        }
        resetWork();
    }

    private void resetWork() {
        claimed.clear();
        workTargets.clear();
        waypointIndex.clear();
    }

    private final Map<NPC, String> commanderNames = new HashMap<>();

    /** A commander's name: four underscores and a few letters, kept for as long as the commander lives. */
    private String commanderName(NPC npc) {
        return commanderNames.computeIfAbsent(npc, k -> {
            StringBuilder sb = new StringBuilder("____");
            for (int i = 0; i < 3; i++) sb.append(NAME_CHARS.charAt(rng.nextInt(NAME_CHARS.length())));
            return sb.toString();
        });
    }

    private String randomName() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(NAME_CHARS.charAt(rng.nextInt(NAME_CHARS.length())));
        }
        return sb.toString();
    }

    /** Commander ranks by seniority: R is the leader, A and I are officers, D are the rest. */
    private void recomputeRanks() {
        ranks.clear();
        int officer = 0;
        for (NPC npc : nulls) {
            char rank;
            if (npc == leader) {
                rank = 'R';
            } else {
                rank = officer < 2 ? 'A' : officer < 6 ? 'I' : 'D';
                officer++;
            }
            Character promoted = promotions.get(npc);
            if (promoted != null && rankLevel(promoted) > rankLevel(rank)) rank = promoted;
            ranks.put(npc, rank);
        }
        // Gear follows rank: re-equip everyone, and give the sword to whoever is fighting.
        for (NPC npc : nulls) {
            if (!npc.isSpawned()) continue;
            // The wiki: names drift to underscores as Nulls rank up. Commanders get mostly underscores.
            if (ranks.get(npc) == 'A' && npc != leader) {
                npc.setName(commanderName(npc));
            }
            equipArmour(npc);
            if (kindOf(mode) == null) setHeldItem(npc, rankSword(ranks.getOrDefault(npc, 'D')));
        }
    }

    private static int rankLevel(char r) {
        return switch (r) {
            case 'R' -> 4;
            case 'A' -> 3;
            case 'I' -> 2;
            default -> 1;
        };
    }

    /** Forge Awakening: a Null held at the Forge long enough rises one rank (D to I, I to A). */
    private void forgeHold(NPC npc, Player owner) {
        int held = forgeCycles.merge(npc, 1, Integer::sum);
        if (held < FORGE_AWAKEN_CYCLES) return;
        forgeCycles.remove(npc);
        char current = ranks.getOrDefault(npc, 'D');
        char next = current == 'D' ? 'I' : current == 'I' ? 'A' : '\0';
        if (next == '\0') return;
        promotions.put(npc, next);
        recomputeRanks();
        owner.sendMessage(ChatColor.DARK_PURPLE + "[NULL] " + npc.getName() + " rose at the Forge. Rank " + next + ".");
    }

    /** Alarm: when hostile mobs crowd the owner, the army rallies with doubled reach for 30 seconds. */
    /** Set by dismiss, cleared by summon: stops the army summoning itself back. */
    private boolean autoSuppressed;
    /** True while the owner is low; the emergency fires once per low spell, and re-arms after they recover. */
    private boolean emergencyLatched;

    /** The army keeps itself at a floor while the owner is online, so the owner never has to summon by hand. */
    private void autoReinforce(Player owner) {
        if (autoSuppressed) return;
        int floor = Math.min(armyCap(), plugin.getConfig().getInt("auto-army-floor", 10));
        for (int added = 0; added < 2 && nulls.size() < floor; added++) {
            createNull(formationPoint(owner, nulls.size()), gearForMode(mode), true);
        }
    }

    /**
     * Owner low on health (under 8): restock. One portal opens beside them and a Null steps out, and healing
     * and food go into their hands. If four or more mobs are on them, a ring of portals opens instead.
     */
    private void checkEmergency(Player owner) {
        if (owner.getHealth() >= 8) {
            emergencyLatched = false;
            return;
        }
        if (emergencyLatched) return;
        emergencyLatched = true;

        int foes = 0;
        for (Entity e : owner.getNearbyEntities(10, 6, 10)) {
            if (e instanceof Monster) foes++;
        }
        owner.addPotionEffect(new PotionEffect(PotionEffectType.INSTANT_HEALTH, 1, 1, false, true));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 200, 2, false, true));
        Map<Integer, ItemStack> left = owner.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE, 4), new ItemStack(Material.COOKED_BEEF, 16));
        for (ItemStack rest : left.values()) owner.getWorld().dropItemNaturally(owner.getLocation(), rest);

        if (foes >= 4) {
            owner.sendMessage(ChatColor.RED + "Low and surrounded. Portals are opening around you.");
            ringPortals(owner);
        } else {
            owner.sendMessage(ChatColor.DARK_PURPLE + "Low. Restock portal opening beside you.");
            Location spot = owner.getLocation().add(owner.getLocation().getDirection().setY(0).normalize().multiply(3));
            String made = buildPortalAt(owner, spot);
            if (made.contains("open at") && nulls.size() < armyCap()) {
                createNull(spot.clone().add(0.5, 0, 0.5), gearForMode(mode), false);
            }
        }
    }

    /** A ring of twelve nether portals around the owner, eight blocks out, with a Null stepping out of each. */
    private void ringPortals(Player owner) {
        Location c = owner.getLocation();
        int count = 12;
        double radius = 8;
        for (int i = 0; i < count; i++) {
            double a = 2 * Math.PI * i / count;
            double dx = Math.cos(a) * radius;
            double dz = Math.sin(a) * radius;
            Location spot = c.clone().add(dx, 0, dz);
            // A portal on the east or west side runs north-south, and the reverse on the north and south sides.
            boolean alongZ = Math.abs(dx) >= Math.abs(dz);
            String made = buildPortalAt(owner, spot, alongZ);
            if (!made.contains("open at")) continue;
            if (nulls.size() >= armyCap()) break;
            createNull(spot.clone().add(0.5, 0, 0.5), gearForMode(mode), false);
        }
    }

    /** A spot on a ring around a point, for hunters to wait on out of sight. */
    private Location ringSpot(Location around, int i, int count) {
        double a = 2 * Math.PI * i / Math.max(1, count);
        Location spot = around.clone().add(Math.cos(a) * 12, 0, Math.sin(a) * 12);
        spot.setY(spot.getWorld().getHighestBlockYAt(spot) + 1);
        return spot;
    }

    /** Hides a Null and holds it until its target comes within reach. */
    private void waitFor(NPC npc, Player target, long expires) {
        if (isCommander(npc)) return; // the Commander always stays with the owner
        hide(npc);
        waits.put(npc, new Wait(target.getUniqueId(), expires));
    }

    private static void reveal(NPC npc) {
        if (npc.getEntity() instanceof LivingEntity living) {
            living.removePotionEffect(PotionEffectType.INVISIBILITY);
            living.setInvisible(false);
        }
    }

    /** When each target was last shocked by an ambush, so one ambush doesn't keep re-shocking. */
    private final Map<UUID, Long> shocked = new HashMap<>();
    /** Nulls placed by an ambush. Only these shock the target on reveal; escape hunters do not. */
    private final Set<NPC> ambushers = new HashSet<>();
    /** Portal blocks of each ambush, by target, so the portal can close once the target is revealed. */
    private final Map<UUID, List<Block>> ambushPortals = new HashMap<>();

    private void tickWaits() {
        long now = System.currentTimeMillis();
        for (Map.Entry<NPC, Wait> entry : new ArrayList<>(waits.entrySet())) {
            NPC npc = entry.getKey();
            Wait wait = entry.getValue();
            if (!npc.isSpawned() || now >= wait.expiresAt()) {
                dropNpc(npc);
                continue;
            }
            Player target = Bukkit.getPlayer(wait.target());
            if (target == null) continue; // target offline: keep waiting
            if (!consent.canTarget(target)) {
                dropNpc(npc);
                continue;
            }
            Location here = npc.getStoredLocation();
            Location tl = target.getLocation();
            if (here != null && sameWorld(here, tl) && here.distance(tl) <= WAIT_TRIGGER) {
                waits.remove(npc);
                reveal(npc);
                // The first Null out shocks the target and webs their feet, so the squad lands its first hits on a stuck target.
                boolean fromAmbush = ambushers.remove(npc);
                if (fromAmbush) here.getWorld().spawnParticle(Particle.PORTAL, here, 40, 0.4, 0.8, 0.4, 0.2);
                if (fromAmbush && (!shocked.containsKey(wait.target()) || now - shocked.get(wait.target()) > 15_000L)) {
                    closePortals(wait.target()); // the portal shuts behind the target: no walking back out
                    shocked.put(wait.target(), now);
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2, false, true));
                    target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, true));
                    target.getWorld().spawnParticle(Particle.EXPLOSION, tl, 2, 0.5, 0.5, 0.5, 0);
                    layTrap(target, tl);
                }
                hunters.put(npc, new Hunt(wait.target(), wait.expiresAt()));
            }
        }
    }

    /**
     * Take over: portals open behind the owner, the squad steps out of all of them at the same moment,
     * and the whole group holds the ground around the owner.
     */
    public String takeOver(Player owner) {
        if (nulls.size() + 4 > armyCap()) return "Not enough room under the army cap (" + armyCap() + ").";
        Vector dir = owner.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 0.01) dir = new Vector(0, 0, 1);
        dir.normalize();
        boolean alongZ = Math.abs(dir.getX()) >= Math.abs(dir.getZ());
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        World w = owner.getWorld();
        Location back = owner.getLocation().clone().subtract(dir.clone().multiply(5));
        List<Location> opened = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Location spot = back.clone().add(side.clone().multiply(i * 3 - 4.5));
            if (buildPortalAt(owner, spot, alongZ).contains("open at")) opened.add(spot);
        }
        if (opened.isEmpty()) {
            return "No portal would open behind you. Check the ground is flat and nobody off the roster is near.";
        }
        for (Location s : opened) {
            if (nulls.size() >= armyCap()) break;
            Location out = s.clone().add(dir.clone().multiply(2.5)).add(0.5, 0, 0.5);
            w.spawnParticle(Particle.PORTAL, out.clone().add(0, 1, 0), 120, 0.6, 1.0, 0.6, 0.5);
            w.playSound(out, Sound.BLOCK_PORTAL_TRAVEL, 0.9f, 1.0f);
            NPC npc = createNull(out, gearForMode(Mode.GUARD), false);
            if (!npc.isSpawned()) npc.spawn(out);
        }
        guardCage(owner.getLocation());
        // The portals close after a minute; the Nulls stay where they came out.
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Location s : opened) {
                    for (int a = -1; a <= 0; a++) {
                        for (int y = s.getBlockY(); y <= s.getBlockY() + 2; y++) {
                            Block b = alongZ ? w.getBlockAt(s.getBlockX(), y, s.getBlockZ() + a)
                                    : w.getBlockAt(s.getBlockX() + a, y, s.getBlockZ());
                            if (b.getType() == Material.NETHER_PORTAL) b.setType(Material.AIR, false);
                        }
                    }
                }
            }
        }.runTaskLater(plugin, 1200L);
        return opened.size() + " portals opened behind you. The squad stepped out of all of them together and holds this ground.";
    }

    /** An escapee is being chased: the whole squad is faster and thinks ahead. */
    /** One more wave for an escapee still being hunted: hunters on them and webs under them. */
    /** Most Nulls one escapee can have on their heels at once (config squad-cap, default 12). */
    private int squadCap() {
        return Math.max(1, plugin.getConfig().getInt("squad-cap", 12));
    }

    public void escapeWave(Player owner, Player target, int count, int minutes) {
        if (!target.isOnline() || !pursuing(target.getUniqueId())) return;
        if (huntersOn(target.getUniqueId()) + count > squadCap()) return;
        spawnHunters(owner, target, target.getLocation(), count, minutes);
        layTrap(target, target.getLocation());
    }

    /** Nulls currently hunting or waiting to hunt this player. Each Null is counted once. */
    private int huntersOn(UUID id) {
        long now = System.currentTimeMillis();
        Set<NPC> chasing = new HashSet<>();
        hunters.forEach((npc, h) -> { if (id.equals(h.target()) && h.expiresAt() > now) chasing.add(npc); });
        waits.forEach((npc, w) -> { if (id.equals(w.target()) && w.expiresAt() > now) chasing.add(npc); });
        return chasing.size();
    }

    public void beginPursuit(Player target, int minutes) {
        pursuits.put(target.getUniqueId(), System.currentTimeMillis() + minutes * 60_000L);
        commanderPursue(target, minutes);
    }

    public boolean pursuing(UUID id) {
        Long until = pursuits.get(id);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            pursuits.remove(id);
            return false;
        }
        return true;
    }

    /** Cobwebs across the runner's path, three to six blocks ahead, every two seconds. */
    public void layTrap(Player target, Location tl) {
        long now = System.currentTimeMillis();
        Vector v = target.getVelocity().setY(0);
        if (now - lastSnare < 2000L || v.lengthSquared() < 0.01) return;
        lastSnare = now;
        v.normalize();
        Vector perp = new Vector(-v.getZ(), 0, v.getX());
        World w = tl.getWorld();
        for (int step = 3; step <= 6; step++) {
            for (int side = -1; side <= 1; side++) {
                Location spot = tl.clone().add(v.clone().multiply(step)).add(perp.clone().multiply(side));
                Block b = w.getBlockAt(spot.getBlockX(), spot.getBlockY(), spot.getBlockZ());
                if (b.getType() == Material.AIR) {
                    b.setType(Material.COBWEB, false);
                    snares.put(b, now);
                }
            }
        }
    }

    private void cleanSnares() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Block, Long> e : new ArrayList<>(snares.entrySet())) {
            if (now - e.getValue() < 20_000L) continue;
            if (e.getKey().getType() == Material.COBWEB) e.getKey().setType(Material.AIR, false);
            snares.remove(e.getKey());
        }
    }

    /** Console hunt: hunters on a roster player, with no owner standing by. */
    /** Owner and Commander practice fight. The Commander fights with his own tools; the owner is never the target of anyone else. */
    private final Map<UUID, Boolean> duels = new HashMap<>();

    /** Practice duel: the Commander comes at the owner with his attacks, pearls and rockets. Ends at three hearts or the timer. */
    public String duel(Player owner, int minutes) {
        if (leader == null || !leader.isSpawned()) return "The Commander is not here. Summon the army first.";
        if (duels.containsKey(owner.getUniqueId())) return "You are already in a duel.";
        duels.put(owner.getUniqueId(), true);
        long until = System.currentTimeMillis() + Math.max(1, minutes) * 60_000L;
        owner.sendMessage(ChatColor.DARK_RED + "Practice duel. Hit back. I stop at three hearts or when the timer runs out.");
        new BukkitRunnable() {
            @Override
            public void run() {
                NPC cmd = leader;
                boolean over = !owner.isOnline() || cmd == null || !cmd.isSpawned()
                        || System.currentTimeMillis() >= until || owner.getHealth() <= 6;
                if (over) {
                    duels.remove(owner.getUniqueId());
                    if (owner.isOnline()) owner.sendMessage(ChatColor.DARK_RED + "Practice over. Well fought.");
                    cancel();
                    return;
                }
                if (!(cmd.getEntity() instanceof LivingEntity body)) return;
                Location here = cmd.getStoredLocation();
                Location tl = owner.getLocation();
                if (here != null && here.getWorld() == tl.getWorld() && body.getHealth() < 8) {
                    // The Commander backs off to heal below four hearts, then comes back in. A duel is a fight, not a suicide.
                    eatApple(cmd);
                    drinkPotion(cmd);
                    Vector away = here.toVector().subtract(tl.toVector());
                    if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                    cmd.getNavigator().setTarget(here.clone().add(away.normalize().multiply(8)));
                    return;
                }
                if (here != null && here.getWorld() == tl.getWorld() && here.distance(tl) > 3.2) {
                    if (!commanderTools(cmd, here, tl)) cmd.getNavigator().setTarget(owner, true);
                } else {
                    attack(cmd, owner);
                }
                if (body.getWorld() != owner.getWorld()) teleport(cmd, tl.clone().add(2, 0, 0));
            }
        }.runTaskTimer(plugin, 0L, 10L);
        return "Duel started.";
    }

    /** Trial loot: the nearest trial chamber, the chests within reach emptied, and the loot brought back to the owner. */
    private final Map<UUID, List<ItemStack>> heldLoot = new HashMap<>();

    public String trialLoot(Player owner) {
        World w = owner.getWorld();
        org.bukkit.generator.structure.Structure trial = org.bukkit.Registry.STRUCTURE.get(org.bukkit.NamespacedKey.minecraft("trial_chambers"));
        if (trial == null) return "This server doesn't know trial chambers.";
        var found = w.locateNearestStructure(owner.getLocation(), trial, 100, true);
        if (found == null) return "No trial chamber near you within reach.";
        Location site = found.getLocation();
        List<NPC> team = new ArrayList<>();
        for (NPC npc : nulls) {
            if (team.size() >= 4) break;
            if (npc.isSpawned() && !hunters.containsKey(npc) && !arriving.contains(npc)) team.add(npc);
        }
        if (team.isEmpty()) return "No Nulls free to go.";
        List<org.bukkit.block.Block> spawners = new ArrayList<>();
        List<org.bukkit.block.Block> vaults = new ArrayList<>();
        List<ItemStack> chestLoot = new ArrayList<>();
        int cx = site.getBlockX() >> 4, cz = site.getBlockZ() >> 4;
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                org.bukkit.Chunk chunk = w.getChunkAt(cx + dx, cz + dz);
                for (org.bukkit.block.BlockState bs : chunk.getTileEntities()) {
                    if (bs.getLocation().distance(site) > 48) continue;
                    Material t = bs.getType();
                    if (t == Material.TRIAL_SPAWNER) {
                        spawners.add(bs.getBlock());
                    } else if (t == Material.VAULT) {
                        vaults.add(bs.getBlock());
                    } else if (bs instanceof org.bukkit.block.Container box) {
                        for (ItemStack item : box.getInventory().getContents()) {
                            if (item != null && !item.getType().isAir()) chestLoot.add(item.clone());
                        }
                        box.getInventory().clear();
                    }
                }
            }
        heldLoot.computeIfAbsent(owner.getUniqueId(), k -> new ArrayList<>()).addAll(chestLoot);
        for (int i = 0; i < team.size(); i++) teleport(team.get(i), site.clone().add(i * 1.5, 1, 0));
        owner.sendMessage(ChatColor.DARK_PURPLE + "Trial chamber at " + coordsOf(site) + ". "
                + spawners.size() + " spawner" + (spawners.size() == 1 ? "" : "s") + ", "
                + vaults.size() + " vault" + (vaults.size() == 1 ? "" : "s") + ". The Nulls are going in.");
        new TrialRun(owner, team, w, site, spawners, vaults).runTaskTimer(plugin, 20L, 10L);
        return "Going in.";
    }

    /** One trial chamber run: fight the spawner waves, pick up the keys they drop, open the vaults, then come home. */
    private final class TrialRun extends BukkitRunnable {
        private static final int FIGHT_TICKS = 1200;
        private static final int VAULT_TICKS = 400;
        private final Player owner;
        private final List<NPC> team;
        private final World w;
        private final Location site;
        private final List<org.bukkit.block.Block> spawners;
        private final List<org.bukkit.block.Block> vaults;
        private final List<ItemStack> keys = new ArrayList<>();
        private int phase; // 0 fight, 1 vaults, 2 home
        private int phaseTicks;
        private int quietTicks;
        private int vaultIdx;
        private int opened;

        TrialRun(Player owner, List<NPC> team, World w, Location site, List<org.bukkit.block.Block> spawners, List<org.bukkit.block.Block> vaults) {
            this.owner = owner;
            this.team = team;
            this.w = w;
            this.site = site;
            this.spawners = spawners;
            this.vaults = vaults;
        }

        @Override
        public void run() {
            if (!owner.isOnline()) phase = 2;
            phaseTicks += 10;
            if (phase == 0) fight();
            if (phase == 1) vaults();
            if (phase == 2) {
                home();
                cancel();
            }
        }

        private void fight() {
            boolean hostileNear = !w.getNearbyEntities(site, 24, 24, 24, e -> e instanceof Monster).isEmpty();
            quietTicks = hostileNear ? 0 : quietTicks + 10;
            if (phaseTicks >= FIGHT_TICKS || (phaseTicks > 100 && quietTicks >= 200)) {
                collectKeys();
                phase = 1;
                phaseTicks = 0;
                return;
            }
            for (NPC npc : team) {
                if (!npc.isSpawned()) continue;
                Location here = npc.getStoredLocation();
                if (here == null) continue;
                Monster m = nearestMonster(here);
                if (m != null) {
                    npc.getNavigator().setTarget(m, true);
                    if (here.distance(m.getLocation()) < 3) attack(npc, m);
                } else if (!spawners.isEmpty()) {
                    org.bukkit.block.Block spawner = spawners.get(0);
                    npc.getNavigator().setTarget(spawner.getLocation().add(0.5, 1, 0.5));
                }
            }
        }

        private Monster nearestMonster(Location at) {
            Monster best = null;
            double bestDist = 16 * 16;
            for (org.bukkit.entity.Entity e : w.getNearbyEntities(at, 16, 16, 16, e -> e instanceof Monster)) {
                double d = e.getLocation().distanceSquared(at);
                if (d < bestDist) {
                    bestDist = d;
                    best = (Monster) e;
                }
            }
            return best;
        }

        private void collectKeys() {
            for (org.bukkit.entity.Entity e : w.getNearbyEntities(site, 24, 24, 24, e -> e instanceof org.bukkit.entity.Item)) {
                org.bukkit.entity.Item item = (org.bukkit.entity.Item) e;
                ItemStack stack = item.getItemStack();
                if (stack.getType() == Material.TRIAL_KEY || stack.getType() == Material.OMINOUS_TRIAL_KEY) {
                    keys.add(stack.clone());
                    item.remove();
                }
            }
            owner.sendMessage(ChatColor.DARK_PURPLE + "Fight's over. " + keys.size() + " key" + (keys.size() == 1 ? "" : "s") + " picked up.");
        }

        private void vaults() {
            if (vaultIdx >= vaults.size() || keys.isEmpty() || team.isEmpty()) {
                phase = 2;
                return;
            }
            if (phaseTicks > VAULT_TICKS) {
                vaultIdx++;
                phaseTicks = 0;
                return;
            }
            NPC opener = team.get(0);
            if (!opener.isSpawned()) {
                phase = 2;
                return;
            }
            Location v = vaults.get(vaultIdx).getLocation().add(0.5, 0, 0.5);
            opener.getNavigator().setTarget(v);
            Location here = opener.getStoredLocation();
            if (here != null && here.getWorld() == w && here.distance(v) <= 3) {
                keys.remove(0);
                openVault(v);
                vaultIdx++;
                opened++;
                phaseTicks = 0;
            }
        }

        /** Rolls the trial chamber reward table at the vault. The vault block itself is left as it is. */
        private void openVault(Location v) {
            org.bukkit.loot.LootTables lootTables = org.bukkit.Registry.LOOT_TABLES.get(org.bukkit.NamespacedKey.minecraft("chests/trial_chambers/reward"));
            org.bukkit.loot.LootTable table = lootTables == null ? null : lootTables.getLootTable();
            if (table != null) {
                java.util.Collection<ItemStack> rewards = table.populateLoot(java.util.concurrent.ThreadLocalRandom.current(),
                        new org.bukkit.loot.LootContext.Builder(v).build());
                heldLoot.computeIfAbsent(owner.getUniqueId(), k -> new ArrayList<>()).addAll(rewards);
            }
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, v, 40, 0.6, 0.8, 0.6, 0.1);
            w.playSound(v, Sound.BLOCK_VAULT_OPEN_SHUTTER, 1f, 1f);
        }

        private void home() {
            if (owner.isOnline()) {
                for (NPC npc : team) if (npc.isSpawned()) teleport(npc, owner.getLocation().clone().add(1.5, 0, 1.5));
                owner.sendMessage(ChatColor.DARK_PURPLE + "Back from the trial chamber. " + opened + " vault"
                        + (opened == 1 ? "" : "s") + " opened, " + keys.size() + " key" + (keys.size() == 1 ? "" : "s") + " left unused.");
                deliverLoot(owner);
            } else {
                for (NPC npc : team) if (npc.isSpawned()) teleport(npc, site);
            }
        }
    }

    /** Hands the owner what the Nulls brought back. Anything that doesn't fit drops at their feet. */
    public void deliverLoot(Player owner) {
        List<ItemStack> loot = heldLoot.remove(owner.getUniqueId());
        if (loot == null || loot.isEmpty()) {
            owner.sendMessage(ChatColor.GRAY + "Nothing to bring back.");
            return;
        }
        for (ItemStack left : owner.getInventory().addItem(loot.toArray(new ItemStack[0])).values()) {
            owner.getWorld().dropItemNaturally(owner.getLocation(), left);
        }
        owner.sendMessage(ChatColor.DARK_PURPLE + "Loot in: " + loot.size() + " stacks from the trial chamber.");
    }

    /** Where each fetched player was standing, so a return trip can send them back. */
    private final Map<UUID, Location> fetchedFrom = new HashMap<>();

    /**
     * Fetch: a real portal opens three blocks from the target. Two Nulls step out at two seconds, one leaves a sign
     * beside the target, and at six seconds the Nulls take the target through the portal to the owner.
     */
    public void fetchPlayer(Player owner, Player target) {
        fetchedFrom.put(target.getUniqueId(), target.getLocation().clone());
        String[] sign = {owner.getName() + " would like", "you to come see", "him.", "- The Nulls"};
        escort(owner, target, target.getLocation(), () -> owner.getLocation().clone(), sign,
                "The Nulls are bringing you to " + owner.getName() + ".", "Bringing " + target.getName() + " to you.");
    }

    /** Return: the same escort, but the portal drops the target back where they were before the fetch. */
    public void returnPlayer(Player owner, Player target) {
        Location origin = fetchedFrom.remove(target.getUniqueId());
        if (origin == null || origin.getWorld() == null) {
            owner.sendMessage(ChatColor.GRAY + "I don't know where " + target.getName() + " came from. Use a fetch first.");
            return;
        }
        String[] sign = {"Off you go,", "back to where", "you came from.", "- The Nulls"};
        escort(owner, target, target.getLocation(), () -> origin.clone(), sign,
                "The Nulls are taking you back.", "Sending " + target.getName() + " back.");
    }

    /** The shared escort: portal, two Nulls, sign, then the target goes through. Stops if consent is revoked. */
    private void escort(Player owner, Player target, Location from, java.util.function.Supplier<Location> destination,
                        String[] signLines, String targetMessage, String ownerMessage) {
        World w = from.getWorld();
        if (w == null || w.getEnvironment() == World.Environment.THE_END) {
            owner.sendMessage(ChatColor.GRAY + "Nothing to open a portal to here.");
            return;
        }
        int dx = 3; // the portal stands three blocks off, so the target isn't inside it when the Nulls arrive
        int bx = from.getBlockX() + dx, by = from.getBlockY(), bz = from.getBlockZ();
        List<Block> built = new ArrayList<>();
        for (int a = -2; a <= 1; a++)
            for (int y = by - 1; y <= by + 3; y++) {
                boolean interior = a >= -1 && a <= 0 && y >= by && y <= by + 2;
                Block b = w.getBlockAt(bx, y, bz + a);
                if (interior) {
                    Orientable axis = (Orientable) Material.NETHER_PORTAL.createBlockData();
                    axis.setAxis(Axis.Z);
                    b.setBlockData(axis, false);
                } else {
                    b.setType(Material.OBSIDIAN, false);
                }
                built.add(b);
            }
        Location portal = new Location(w, bx + 0.5, by, bz + 0.5);
        w.playSound(portal, org.bukkit.Sound.BLOCK_PORTAL_TRIGGER, 1f, 1f);
        w.spawnParticle(Particle.PORTAL, portal, 120, 0.8, 1.2, 0.8, 0.3);
        target.sendMessage(ChatColor.DARK_PURPLE + targetMessage);
        owner.sendMessage(ChatColor.DARK_PURPLE + ownerMessage);
        List<NPC> escorts = new ArrayList<>();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!target.isOnline() || !consent.canTarget(target)) return;
                for (int i = 0; i < 2; i++) {
                    Location exit = portal.clone().add(i == 0 ? -1.2 : 1.2, 0, 0);
                    NPC npc = createNull(exit, gearForMode(Mode.HUNT), false);
                    escorts.add(npc);
                    npc.getNavigator().setTarget(target, false);
                }
            }
        }.runTaskLater(plugin, 40L);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!target.isOnline() || !consent.canTarget(target)) {
                    releaseEscorts(escorts);
                    return;
                }
                placeSign(target, signLines);
            }
        }.runTaskLater(plugin, 70L);
        new BukkitRunnable() {
            @Override
            public void run() {
                releaseEscorts(escorts);
                if (!target.isOnline() || !consent.canTarget(target)) return;
                Location dest = destination.get();
                if (dest == null || dest.getWorld() == null) return;
                target.getWorld().spawnParticle(Particle.PORTAL, target.getLocation(), 60, 0.4, 0.8, 0.4, 0.2);
                target.teleport(dest);
                dest.getWorld().spawnParticle(Particle.PORTAL, dest, 60, 0.4, 0.8, 0.4, 0.2);
                target.sendMessage(ChatColor.DARK_PURPLE + "You are here.");
            }
        }.runTaskLater(plugin, 120L);
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Block b : built) {
                    if (b.getType() == Material.NETHER_PORTAL || b.getType() == Material.OBSIDIAN) b.setType(Material.AIR, false);
                }
            }
        }.runTaskLater(plugin, 20L * 15);
    }

    /** Puts a sign on a free spot beside the target: air at feet and head, solid ground under. Two minutes, then it's gone. */
    private void placeSign(Player target, String[] lines) {
        Location at = target.getLocation();
        World w = at.getWorld();
        if (w == null) return;
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : around) {
            Block feet = w.getBlockAt(at.getBlockX() + d[0], at.getBlockY(), at.getBlockZ() + d[1]);
            Block head = feet.getRelative(0, 1, 0);
            Block floor = feet.getRelative(0, -1, 0);
            if (!feet.getType().isAir() || !head.getType().isAir() || !floor.getType().isSolid()) continue;
            feet.setType(Material.OAK_SIGN, false);
            if (feet.getState() instanceof org.bukkit.block.Sign sign) {
                for (int i = 0; i < lines.length && i < 4; i++) {
                    sign.getSide(org.bukkit.block.sign.Side.FRONT).line(i, net.kyori.adventure.text.Component.text(lines[i]));
                }
                sign.update(true, false);
            }
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (feet.getType() == Material.OAK_SIGN) feet.setType(Material.AIR, false);
                }
            }.runTaskLater(plugin, 20L * 120);
            return;
        }
    }

    /** Takes the escorts away quietly: no gear dropped, they were only ever messengers. */
    private void releaseEscorts(List<NPC> escorts) {
        for (NPC npc : escorts) {
            if (npc == null) continue;
            nulls.remove(npc);
            packs.remove(npc);
            ranks.remove(npc);
            hunters.remove(npc);
            npc.destroy();
        }
        recomputeRanks();
        escorts.clear();
    }

    public String huntOffline(Player target, int count, int minutes) {
        if (target == null) return "That player is not online.";
        if (!consent.canTarget(target)) return "Roster only. " + target.getName() + " is not on it.";
        return spawnHunters(null, target, target.getLocation(), count, minutes)
                ? "Hunters sent for " + target.getName() + "." : "Not enough room under the army cap.";
    }

    /** The Commander joins a pursuit. He chases the target with his pearls and dashes until the time runs out. */
    public boolean commanderPursue(Player target, int minutes) {
        return false; // the Commander never leaves the owner, so he doesn't join pursuits
    }

    /**
     * The Commander's chase tools. Far away he throws an ender pearl at the target so it lands near them;
     * close in, he fires a rocket dash at them. Each has its own cooldown. Returns true if one was used.
     */
    private boolean commanderTools(NPC npc, Location here, Location tl) {
        if (!(npc.getEntity() instanceof LivingEntity body)) return false;
        long now = System.currentTimeMillis();
        double d = here.distance(tl);
        if (d >= 12 && d <= 30 && now - lastPearl.getOrDefault(npc, 0L) > PEARL_COOLDOWN_MS) {
            Location eye = body.getEyeLocation();
            Vector aim = tl.toVector().subtract(eye.toVector()).normalize().multiply(1.4);
            EnderPearl pearl = here.getWorld().spawn(eye, EnderPearl.class);
            pearl.setShooter(body);
            pearl.setVelocity(aim);
            lastPearl.put(npc, now);
            return true;
        }
        if (d >= 3 && d <= 10 && now - lastDash.getOrDefault(npc, 0L) > DASH_COOLDOWN_MS) {
            Vector dash = tl.toVector().subtract(here.toVector()).setY(0).normalize().multiply(1.3);
            dash.setY(0.25);
            body.setVelocity(dash);
            here.getWorld().spawnParticle(Particle.FIREWORK, here.clone().add(0, 1, 0), 40, 0.3, 0.3, 0.3, 0.1);
            here.getWorld().playSound(here, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.0f);
            lastDash.put(npc, now);
            return true;
        }
        return false;
    }

    private void checkAlarm(Player owner) {
        long now = System.currentTimeMillis();
        if (now < nextAlarm) return;
        int threat = 0;
        for (Entity e : owner.getNearbyEntities(16, 8, 16)) {
            if (e instanceof Monster) threat++;
        }
        if (threat < plugin.getConfig().getInt("alarm-threshold", 5)) return;
        alarmUntil = now + 30_000L;
        nextAlarm = now + 60_000L;
        // The army still rallies quietly: no title, no chat warning.
    }

    /** Lists every Null with its rank and health. */
    /** Every Null in squad order, for the panel. */
    public List<NPC> squad() {
        return List.copyOf(nulls);
    }

    public char rankOf(NPC npc) {
        return ranks.getOrDefault(npc, 'D');
    }

    /** The Null at this 1-based squad number, or null. */
    public NPC nullAt(int n) {
        if (n < 1 || n > nulls.size()) return null;
        return nulls.get(n - 1);
    }

    /** Lists every Null with its number, rank, and whether it has its own order. */
    public void showSquad(org.bukkit.command.CommandSender owner) {
        if (nulls.isEmpty()) {
            owner.sendMessage(ChatColor.GRAY + "No Nulls on the field.");
            return;
        }
        owner.sendMessage(ChatColor.DARK_PURPLE + "Squad (use /nullcmd solo <n> <order>):");
        for (int i = 0; i < nulls.size(); i++) {
            NPC npc = nulls.get(i);
            owner.sendMessage(ChatColor.GRAY + " " + (i + 1) + ". [" + ranks.getOrDefault(npc, 'D') + "] "
                    + ChatColor.WHITE + npc.getName() + ChatColor.GRAY + " - " + squadState(npc));
        }
    }

    /** A short description of one Null's current job, for the squad list and the panel. */
    public String squadState(NPC npc) {
        String base = soloModes.containsKey(npc)
                ? "own order: " + soloModes.get(npc).name().toLowerCase()
                : "army order";
        if (npc == viewing) base += ", you're looking through it";
        if (focus.containsKey(npc)) base += ", attacking";
        return base;
    }

    /** Gives one Null its own order. Solo orders last until cleared or the Null is dismissed. */
    public String setSolo(int n, String order) {
        NPC npc = nullAt(n);
        if (npc == null) return "No Null number " + n + ". Run /nullcmd squad to see the numbers.";
        if (ranks.getOrDefault(npc, 'D') == 'R') return "Wards stay beside you as your bodyguard and take no solo orders.";
        if (order.equals("clear")) {
            soloModes.remove(npc);
            return npc.getName() + " is back on the army order.";
        }
        Mode m;
        try {
            m = Mode.valueOf(order.toUpperCase());
        } catch (IllegalArgumentException e) {
            return "Solo orders: guard, follow, hunt, stay, mine, lumber, farm, patrol, forge, auto or clear.";
        }
        switch (m) {
            case GUARD, FOLLOW, HUNT, STAY, MINE, LUMBER, FARM, AUTO -> { }
            case PATROL -> {
                if (patrolRoute.isEmpty()) return "No patrol route yet. Stand at each point and run /nullcmd patrol add.";
            }
            case FORGE -> {
                if (configuredBlock("forge-block") == null) return "No Forge block set. Look at one and run /nullcmd forge set.";
            }
            default -> {
                return "Solo orders: guard, follow, hunt, stay, mine, lumber, farm, patrol, forge, auto or clear.";
            }
        }
        soloModes.put(npc, m);
        return npc.getName() + " now works on its own: " + m.name().toLowerCase() + ".";
    }

    /** Clears one Null's solo order, or all of them when which is "all". */
    public String clearSolo(String which) {
        if (which == null || which.equalsIgnoreCase("all")) {
            soloModes.clear();
            return "Every Null is back on the army order.";
        }
        int n;
        try {
            n = Integer.parseInt(which);
        } catch (NumberFormatException e) {
            return "Usage: solo clear [n|all].";
        }
        NPC npc = nullAt(n);
        if (npc == null) return "No Null number " + n + ".";
        soloModes.remove(npc);
        return npc.getName() + " is back on the army order.";
    }

    /** Puts the owner into spectator mode, looking out through this Null's eyes. */
    public String view(Player owner, int n) {
        NPC npc = nullAt(n);
        if (npc == null) return "No Null number " + n + ". Run /nullcmd squad to see the numbers.";
        if (!npc.isSpawned() || npc.getEntity() == null) return npc.getName() + " isn't spawned right now.";
        if (viewing == null) viewRestore = owner.getGameMode();
        viewing = npc;
        owner.setGameMode(GameMode.SPECTATOR);
        owner.setSpectatorTarget(npc.getEntity());
        return "Looking through " + npc.getName() + ". Run /nullcmd view stop to come back.";
    }

    /** Brings the owner back to their own eyes. */
    public String viewStop(Player owner) {
        if (viewing == null) return "You aren't looking through a Null.";
        owner.setSpectatorTarget(null);
        owner.setGameMode(viewRestore);
        viewing = null;
        return "Back in your own eyes.";
    }

    /** Attacks whatever the viewed Null is looking at: a roster player or a hostile mob. */
    public String viewAttack(Player owner) {
        if (viewing == null || !viewing.isSpawned() || !(viewing.getEntity() instanceof LivingEntity eyes)) {
            return "Look through a Null first: /nullcmd view <n>.";
        }
        Location eye = eyes.getEyeLocation();
        org.bukkit.util.RayTraceResult hit = eye.getWorld().rayTraceEntities(eye, eye.getDirection(), 40, 0.6,
                e -> e instanceof LivingEntity living && !living.equals(eyes) && !isNullBody(e)
                        && (e instanceof Monster || (e instanceof Player p && consent.canTarget(p))));
        if (hit == null || hit.getHitEntity() == null) return "Nothing it can attack in its sight.";
        Entity target = hit.getHitEntity();
        focus.put(viewing, new Focus(target.getUniqueId(), System.currentTimeMillis() + 45_000L));
        String name = target instanceof Player p ? p.getName() : target.getType().name().toLowerCase();
        return viewing.getName() + " is attacking " + name + " for 45 seconds.";
    }

    private boolean isNullBody(Entity e) {
        for (NPC npc : nulls) {
            if (npc.getEntity() != null && npc.getEntity().getUniqueId().equals(e.getUniqueId())) return true;
        }
        return false;
    }

    public boolean isViewing() {
        return viewing != null;
    }

    /**
     * WASD while looking through a Null. Spectator movement is read as intent: the horizontal
     * direction becomes the Null's walk, and rising sharply (space) makes it jump.
     */
    public void steerFromView(Player owner, Location from, Location to) {
        if (viewing == null || !viewing.isSpawned() || !(viewing.getEntity() instanceof LivingEntity body)) return;
        if (!body.getWorld().equals(to.getWorld()) || !from.getWorld().equals(to.getWorld())) return;
        Vector delta = to.toVector().subtract(from.toVector());
        double horizontal = delta.getX() * delta.getX() + delta.getZ() * delta.getZ();
        if (horizontal > 0.0025) {
            Vector step = new Vector(delta.getX(), 0, delta.getZ()).normalize().multiply(6);
            viewing.getNavigator().setTarget(body.getLocation().add(step));
        }
        if (delta.getY() > 0.25 && body.isOnGround()) {
            body.setVelocity(new Vector(0, 0.42, 0));
        }
        if (owner.getSpectatorTarget() == null) {
            owner.setSpectatorTarget(body); // WASD detaches the camera; hold it on the Null
        }
    }

    /** Keeps the view honest: if the watched Null is gone, the owner comes back to their own eyes. */
    private void checkView(Player owner) {
        if (viewing == null) return;
        if (!viewing.isSpawned() || !nulls.contains(viewing)) {
            owner.setSpectatorTarget(null);
            owner.setGameMode(viewRestore);
            owner.sendMessage(ChatColor.GRAY + "The Null you were watching is gone. Back in your own eyes.");
            viewing = null;
        }
    }

    /** Walks a focused Null to its target and strikes it, until it dies, leaves, or the time runs out. */
    /**
     * Hits back at whatever is on the Null within four blocks: a hostile mob, or a player on the roster
     * (the escapee included). Orders wait until the fight is over. Returns true if it fought.
     */
    private final Map<NPC, Long> assistAt = new HashMap<>();

    /** A hurt Null calls the healthy Nulls near it onto the same threat, so they cover each other. */
    private void callAllies(NPC caller, LivingEntity threat) {
        Location here = caller.getStoredLocation();
        if (here == null) return;
        long now = System.currentTimeMillis();
        for (NPC ally : nulls) {
            if (ally == caller || !ally.isSpawned() || isCommander(ally) || isWounded(ally)) continue;
            if (now - assistAt.getOrDefault(ally, 0L) < 3000L) continue;
            Location at = ally.getStoredLocation();
            if (at == null || !sameWorld(at, here) || at.distance(here) > 10) continue;
            assistAt.put(ally, now);
            ally.getNavigator().setTarget(threat, true);
        }
    }

    private boolean hitBack(NPC npc) {
        Location here = npc.getStoredLocation();
        if (here == null || here.getWorld() == null || !npc.isSpawned()) return false;
        LivingEntity threat = null;
        double bestDist = 4;
        for (Entity e : here.getWorld().getNearbyEntities(here, 4, 4, 4)) {
            if (!(e instanceof LivingEntity living) || e.equals(npc.getEntity())) continue;
            if (e instanceof Player p) {
                if (!consent.canTarget(p)) continue;
            } else if (!(e instanceof Monster) || NETHER_PEACE.contains(e.getType())) {
                continue;
            }
            double d = here.distance(e.getLocation());
            if (d < bestDist) {
                bestDist = d;
                threat = living;
            }
        }
        if (threat == null) return false;
        if (npc.getEntity() instanceof LivingEntity self && self.getHealth() < 10) callAllies(npc, threat);
        if (bestDist <= ATTACK_REACH) attack(npc, threat);
        else npc.getNavigator().setTarget(threat, true);
        return true;
    }

    private void focusTick(NPC npc, Focus f) {
        Entity e = Bukkit.getEntity(f.target());
        if (!(e instanceof LivingEntity target) || target.isDead() || System.currentTimeMillis() >= f.expiresAt()
                || (target instanceof Player p && !consent.canTarget(p))) {
            focus.remove(npc);
            npc.getNavigator().cancelNavigation();
            return;
        }
        if (hitBack(npc)) return;
        if (npc.getEntity() instanceof LivingEntity self && self.getWorld().equals(target.getWorld())
                && self.getLocation().distance(target.getLocation()) <= 3.2) {
            attack(npc, target);
        } else {
            npc.getNavigator().setTarget(target, true);
        }
    }

    public void showRanks(org.bukkit.command.CommandSender owner) {
        if (nulls.isEmpty()) {
            owner.sendMessage(ChatColor.GRAY + "No Nulls on the field.");
            return;
        }
        owner.sendMessage(ChatColor.DARK_PURPLE + "Army ranks:");
        for (NPC npc : nulls) {
            Entity e = npc.getEntity();
            int hp = e instanceof LivingEntity living ? (int) Math.ceil(living.getHealth()) : 0;
            owner.sendMessage(ChatColor.GRAY + " [" + ranks.getOrDefault(npc, 'D') + "] " + ChatColor.WHITE
                    + npc.getName() + ChatColor.GRAY + " HP " + hp + (npc.isSpawned() ? "" : " (not spawned)"));
        }
    }

    /** Rank scales command radius and combat strength: R 1.5, A 1.25, I 1.0, D 0.75. */
    private double rankScale(NPC npc) {
        Character rank = ranks.get(npc);
        if (rank == null) return 1.0;
        return switch (rank) {
            case 'R' -> 1.5;
            case 'A' -> 1.25;
            case 'I' -> 1.0;
            default -> 0.75;
        };
    }

    private double roamRadius() {
        return Math.max(4, plugin.getConfig().getDouble("roam-radius", 12));
    }

    private Location randomPointNear(Location center, double radius) {
        double angle = rng.nextDouble() * 2 * Math.PI;
        double dist = 3 + rng.nextDouble() * Math.max(0, radius - 3);
        return center.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
    }

    /** Players who hit the owner recently, and when the record expires. Set by the owner damage listener. */
    private final Map<UUID, Long> attackers = new HashMap<>();

    /** Records someone who hit the owner. Team and the owner are never recorded. Lasts a minute. */
    public void recordAttacker(Player who) {
        if (consent.isOwner(who) || consent.isTeam(who)) return;
        attackers.put(who.getUniqueId(), System.currentTimeMillis() + 60_000L);
    }

    private boolean recentAttacker(Player who) {
        return attackers.getOrDefault(who.getUniqueId(), 0L) > System.currentTimeMillis();
    }

    /** Pulls back when badly hurt, or when already hurt and a crowd is closing in. */
    private boolean isWounded(NPC npc) {
        Entity e = npc.getEntity();
        if (!(e instanceof LivingEntity living)) return false;
        double hp = living.getHealth();
        if (hp < WOUNDED_HP) return true;
        if (hp >= 12) return false;
        int crowd = 0;
        for (Entity near : living.getNearbyEntities(8, 4, 8)) {
            if (near instanceof Monster) crowd++;
        }
        return crowd >= 3;
    }

    private Location formationPoint(Player owner, int index) {
        return formationPointAt(owner.getLocation(), index);
    }

    private Location formationPointAt(Location anchor, int index) {
        double angle = (2 * Math.PI / 8.0) * index;
        double radius = 3 + (index / 8) * 1.5;
        return anchor.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
    }

    // ---------- Arm swinging ----------

    public void startSwinging() {
        if (swinging) return;
        swinging = true;
        swingTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (NPC npc : nulls) {
                    swing(npc);
                }
            }
        };
        swingTask.runTaskTimer(plugin, 0L, 8L);
    }

    public void stopSwinging() {
        swinging = false;
        if (swingTask != null) {
            swingTask.cancel();
            swingTask = null;
        }
    }

    private void swing(NPC npc) {
        Entity e = npc.getEntity();
        if (npc.isSpawned() && e instanceof LivingEntity living) {
            living.swingMainHand();
        }
    }

    // ---------- Per-tick behaviour ----------

    private void tick() {
        tickPrisonGuard();
        tickStationGuards();
        Player owner = findOwner();
        if (owner == null) {
            expireHunters();
            tickHunters();
            tickSpies();
            tickOffline();
            tickDefence();
            replenishOffline();
            return;
        }
        cycle++;
        checkView(owner);
        tickWaits();
        cleanSnares();
        expireHunters();
        checkAlarm(owner);
        checkEmergency(owner);
        autoReinforce(owner);
        tickSpies();
        checkPinned();

        double leash = plugin.getConfig().getDouble("leash-distance", 15);
        double huntRange = plugin.getConfig().getDouble("hunt-range", 100);

        WorkKind kind = kindOf(mode == Mode.AUTO ? Mode.MINE : mode);
        if (kind != null && workTargets.isEmpty() && cycle - lastScanCycle >= SCAN_EVERY_CYCLES) {
            refillTargets(owner, kind);
            lastScanCycle = cycle;
        }

        refreshLeader();
        tickFood();
        tickUpkeep();
        for (NPC npc : new ArrayList<>(nulls)) {
            if (!npc.isSpawned() || arriving.contains(npc)) continue;
            if (isCommander(npc)) {
                // Whatever sent him out, the Commander is pulled back to the owner every tick.
                hunters.remove(npc);
                focus.remove(npc);
                waits.remove(npc);
                ambushers.remove(npc);
            }
            if (spies.containsKey(npc)) continue;
            if (waits.containsKey(npc)) continue;
            Focus focused = focus.get(npc);
            if (focused != null) {
                focusTick(npc, focused);
                continue;
            }
            Hunt hunt = hunters.get(npc);
            if (hunt != null) {
                huntTick(npc, hunt);
                continue;
            }
            if (isWounded(npc)) {
                if (System.currentTimeMillis() - mobWoundAt.getOrDefault(npc, 0L) > 30_000L) {
                    mobWoundAt.put(npc, System.currentTimeMillis());
                    mobLosses.merge(npc, 1, Integer::sum);
                }
                fieldHeal(npc);
                followOwner(npc, owner, nulls.indexOf(npc), leash);
                continue;
            }
            if (!isCommander(npc) && ranks.getOrDefault(npc, 'D') == 'D' && !soloModes.containsKey(npc)
                    && friendlyTick(npc, owner, nulls.indexOf(npc))) continue;
            boolean solo = soloModes.containsKey(npc);
            if (!solo && (isCommander(npc) || ranks.getOrDefault(npc, 'D') == 'A')) {
                // The commander sticks with the owner and only goes out when a hunt is ordered.
                // He defends the owner: a mob or roster player within six blocks of the owner is engaged.
                if (defendOwner(npc, owner)) continue;
                followOwner(npc, owner, nulls.indexOf(npc), leash);
                continue;
            }
            int index = nulls.indexOf(npc);
            char rank = ranks.getOrDefault(npc, 'D');
            if (rank == 'R' && !solo) {
                // Ward: the owner's bodyguard. Roster players and mobs near the owner are engaged;
                // anyone else near the owner is only reported, never attacked.
                wardWatch(owner);
                if (defendOwner(npc, owner)) continue;
                followOwner(npc, owner, index, 3);
                continue;
            }
            Mode m = solo ? soloModes.get(npc) : rankModes.getOrDefault(rank, mode);
            if (m == Mode.AUTO) m = autoPick(npc);
            if (rank == 'D' && m == Mode.FOLLOW && followNearestGeneral(npc, index, leash)) continue;
            if (cycle % 4 == 0 && (m == Mode.GUARD || m == Mode.PATROL)) {
                swing(npc); // arms moving on watch
            }
            switch (m) {
                case GUARD -> {
                    if (defendOwner(npc, owner) || engageMobs(npc)) continue;
                    if (cageGuard != null) guardPoint(npc, index);
                    else if (index % 2 == 1 && isNight(owner)) ringAround(npc, owner.getLocation(), index, 10);
                    else followOwner(npc, owner, index, leash);
                }
                case FOLLOW -> {
                    if (defendOwner(npc, owner) || engageMobs(npc)) continue;
                    if (autoWork(npc, owner, index, leash)) continue;
                    if (System.currentTimeMillis() - lastBuildingAt < 60_000L) {
                        ringAround(npc, owner.getLocation(), index, 4); // guarding the build
                    } else {
                        followOwner(npc, owner, index, leash);
                    }
                }
                case GOTO -> travel(npc, gotoTarget);
                case HUNT -> huntNearby(npc, owner, index, leash, huntRange);
                case STAY -> { }
                case MINE, LUMBER, FARM -> workTick(npc, owner, kind, index, leash);
                case PATROL -> {
                    if (!engageMobs(npc)) patrolTick(npc, owner, index, leash);
                }
                case FORGE -> {
                    if (!engageMobs(npc)) forgeTick(npc, owner, index, leash);
                }
                case EXPLORE, NETHER -> followOwner(npc, owner, index, leash);
            }
        }
    }

    private void expireHunters() {
        long now = System.currentTimeMillis();
        for (Map.Entry<NPC, Hunt> entry : new ArrayList<>(hunters.entrySet())) {
            if (now >= entry.getValue().expiresAt()) {
                dropNpc(entry.getKey());
            }
        }
    }

    /** Roaming escort: Nulls drift around the leader (or the owner) and only teleport if far behind. */
    /** A soldier follows the nearest General (sentry) within 40 blocks. Returns false if there isn't one. */
    private boolean followNearestGeneral(NPC npc, int index, double leash) {
        Location here = npc.getStoredLocation();
        if (here == null) return false;
        NPC best = null;
        double bestDist = 40;
        for (NPC g : nulls) {
            if (g == npc || !g.isSpawned() || ranks.getOrDefault(g, 'D') != 'I') continue;
            Location gl = g.getStoredLocation();
            if (gl == null || !sameWorld(gl, here)) continue;
            double d = gl.distance(here);
            if (d < bestDist) {
                bestDist = d;
                best = g;
            }
        }
        if (best == null) return false;
        Location anchor = best.getStoredLocation();
        if (here.distance(anchor) > leash) {
            npc.getNavigator().setTarget(anchor);
            return true;
        }
        Location goal = wanderGoals.get(npc);
        Integer until = wanderUntil.get(npc);
        if (goal == null || until == null || cycle >= until || here.distance(goal) <= REACH) {
            goal = randomPointNear(anchor, roamRadius() * rankScale(npc));
            wanderGoals.put(npc, goal);
            wanderUntil.put(npc, cycle + ROAM_CYCLES);
            npc.getNavigator().setTarget(goal);
        }
        return true;
    }

    private void followOwner(NPC npc, Player owner, int index, double leash) {
        Location ownerLoc = owner.getLocation();
        Location here = npc.getStoredLocation();
        if (here == null || !sameWorld(here, ownerLoc) || here.distance(ownerLoc) > TELEPORT_OVER) {
            // Far behind or in another world: walk back to the formation spot rather than teleporting.
            npc.getNavigator().setTarget(formationPoint(owner, index));
            return;
        }
        Location anchor = ownerLoc;
        if (leader != null && leader != npc && leader.isSpawned()) {
            Location lead = leader.getStoredLocation();
            if (lead != null && sameWorld(lead, here)) anchor = lead;
        }
        if (here.distance(anchor) > leash) {
            npc.getNavigator().setTarget(anchor);
            return;
        }
        Location goal = wanderGoals.get(npc);
        Integer until = wanderUntil.get(npc);
        if (goal == null || until == null || cycle >= until || here.distance(goal) <= REACH) {
            goal = randomPointNear(anchor, roamRadius() * rankScale(npc));
            wanderGoals.put(npc, goal);
            wanderUntil.put(npc, cycle + ROAM_CYCLES);
            npc.getNavigator().setTarget(goal);
        }
    }

    private void travel(NPC npc, Location target) {
        if (target == null) return;
        Location here = npc.getStoredLocation();
        if (here == null || !sameWorld(here, target) || here.distance(target) > TELEPORT_OVER) {
            walkTo(npc, target);
        } else {
            npc.getNavigator().setTarget(target);
        }
    }

    /** Only roster players are ever chosen as targets. */
    private void huntNearby(NPC npc, Player owner, int index, double leash, double range) {
        Location here = npc.getStoredLocation();
        Player chosen = huntTarget == null ? null : Bukkit.getPlayer(huntTarget);
        if (chosen != null && !consent.canTarget(chosen)) {
            huntTarget = null;
            chosen = null;
        }
        if (chosen != null) {
            Location tl = chosen.getLocation();
            if (here == null || !sameWorld(here, tl) || here.distance(tl) > TELEPORT_OVER) {
                walkTo(npc, tl);
            } else if (here.distance(tl) <= ATTACK_REACH) {
                attack(npc, chosen);
            } else {
                npc.getNavigator().setTarget(chosen, true);
            }
            return;
        }
        Player best = null;
        double bestDist = range;
        double bestScore = Double.MAX_VALUE;
        if (here != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!consent.canTarget(p) || !sameWorld(here, p.getLocation())) continue;
                double d = here.distance(p.getLocation());
                if (d > range) continue;
                // Near and weak first, but well-armoured players count as the bigger threat.
                double score = d + p.getHealth() * 0.75 - armourOf(p) * 0.5 - threat.getOrDefault(p.getUniqueId(), 0.0) * 0.5
                        + (nearDanger(p.getLocation()) ? 8 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    bestDist = d;
                    best = p;
                }
            }
        }
        if (best != null) {
            if (bestDist <= ATTACK_REACH) {
                attack(npc, best);
            } else {
                npc.getNavigator().setTarget(best, true);
            }
        } else {
            followOwner(npc, owner, index, leash);
        }
    }

    // ---------- Hunters vs. escapees who build up, dig down or run ----------

    /** Every third hunter carries a bow: a sniper stays back and its shot never misses. */
    private final Set<NPC> snipers = new HashSet<>();

    private ItemStack sniperBow() {
        ItemStack bow = new ItemStack(Material.BOW);
        bow.addUnsafeEnchantment(Enchantment.POWER, 5);
        bow.addUnsafeEnchantment(Enchantment.INFINITY, 1);
        return bow;
    }

    /**
     * A sniper's shot never misses: it lands from four to thirty blocks away, so a target that builds
     * up or sits behind cover still gets hit. Returns false if the shot isn't in range.
     */
    private boolean sniperShot(NPC npc, Player target, Location here) {
        Location tl = target.getLocation();
        if (!sameWorld(here, tl)) return false;
        double d = here.distance(tl);
        if (d <= 4 || d > 30) return false;
        long now = System.currentTimeMillis();
        if (now - lastShot.getOrDefault(npc, 0L) < 2000L) return true;
        lastShot.put(npc, now);
        Location from = npc.getEntity() instanceof LivingEntity shooter ? shooter.getEyeLocation() : here;
        Location to = target.getEyeLocation();
        for (int i = 1; i <= 12; i++) {
            Location p = from.clone().add(to.toVector().subtract(from.toVector()).multiply(i / 12.0));
            target.getWorld().spawnParticle(Particle.CRIT, p, 1, 0, 0, 0, 0);
        }
        target.damage(6.0, npc.getEntity());
        swing(npc);
        return true;
    }

    private final Map<NPC, Location> stillAt = new HashMap<>();
    private final Map<NPC, Long> stillSince = new HashMap<>();

    /** True when a hunter has stood on the same spot for four seconds while the target is still out of reach. */
    private boolean stalled(NPC npc, Location here) {
        long now = System.currentTimeMillis();
        Location last = stillAt.get(npc);
        if (last == null || !sameWorld(last, here) || last.distance(here) > 1.0) {
            stillAt.put(npc, here.clone());
            stillSince.put(npc, now);
            return false;
        }
        return now - stillSince.getOrDefault(npc, now) >= 4000L;
    }

    /**
     * Where a stuck hunter can step next to the target: an open pocket if there is one (the target dug down),
     * otherwise a bridge built out from the target's own level (the target built up). The hunters build too.
     */
    private Location counterSpot(Player target) {
        Location at = target.getLocation();
        World w = at.getWorld();
        if (w == null) return null;
        int[][] offsets = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}};
        for (int[] o : offsets) {
            Block feet = w.getBlockAt(at.getBlockX() + o[0], at.getBlockY(), at.getBlockZ() + o[1]);
            if (feet.getType().isAir() && feet.getRelative(0, 1, 0).getType().isAir()
                    && feet.getRelative(0, -1, 0).getType().isSolid()) {
                return feet.getLocation().add(0.5, 0, 0.5);
            }
        }
        // No open pocket: bridge out two blocks east from the target's level, clearing headroom.
        Block feet = w.getBlockAt(at.getBlockX() + 2, at.getBlockY(), at.getBlockZ());
        Block floor = feet.getRelative(0, -1, 0);
        if (!floor.getType().isSolid()) floor.setType(Material.STONE, false);
        feet.setType(Material.AIR, false);
        feet.getRelative(0, 1, 0).setType(Material.AIR, false);
        return feet.getLocation().add(0.5, 0, 0.5);
    }

    private void huntTick(NPC npc, Hunt hunt) {
        Player target = Bukkit.getPlayer(hunt.target());
        if (target == null) return; // target offline: wait
        if (npc.getEntity() instanceof LivingEntity fighter) {
            // Fight buffs, refreshed every tick while the hunt lasts.
            char rank = ranks.getOrDefault(npc, 'D');
            int strength = rank == 'R' ? 2 : rank == 'A' ? 2 : 0;
            fighter.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 100, strength, false, false));
            fighter.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, rank == 'A' ? 1 : 0, false, false));
            fighter.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, rank == 'R' ? 1 : 0, false, false));
        }
        // Hunters run: the Null's walk is quicker while it chases. The Commander is quickest.
        boolean chasing = pursuing(target.getUniqueId());
        npc.getNavigator().getDefaultParameters().speedModifier(ranks.getOrDefault(npc, 'D') == 'A' || isCommander(npc) ? 1.7f : chasing ? 1.9f : 1.4f);
        if (!consent.canTarget(target)) {
            dropNpc(npc); // consent revoked: stop immediately
            return;
        }
        Location here = npc.getStoredLocation();
        Location tl = target.getLocation();
        if (here != null && sameWorld(here, tl) && npc != leader && rosterCrowd(here) >= 3) {
            // Outnumbered: fall back to the army rather than fight a group alone.
            recordWound(npc, here, hunt.target());
            npc.getNavigator().setTarget(prePositionAnchor());
            return;
        }
        if (hitBack(npc)) return;
        if (here == null || !sameWorld(here, tl) || here.distance(tl) > TELEPORT_OVER) {
            appearNear(npc, target);
        } else if (isWounded(npc)) {
            // Retreat: back off ten blocks from the target and heal before going in again.
            recordWound(npc, here, hunt.target());
            eatApple(npc);
            selfPot(npc);
            Vector off = here.toVector().subtract(tl.toVector());
            if (off.lengthSquared() < 0.01) off = new Vector(1, 0, 0);
            npc.getNavigator().setTarget(here.clone().add(off.normalize().multiply(10)));
        } else if (snipers.contains(npc) && sniperShot(npc, target, here)) {
            // A sniper holds its ground and shoots. Built up or dug in, the target still gets hit.
        } else if (here.distance(tl) > ATTACK_REACH + 1 && stalled(npc, here)) {
            // Stuck under a pillar, at the bottom of a shaft or behind a wall: step in beside the target.
            Location spot = counterSpot(target);
            if (spot != null) teleport(npc, spot);
            stillAt.remove(npc);
        } else if ((isCommander(npc) || ranks.getOrDefault(npc, 'D') == 'A') && commanderTools(npc, here, tl)) {
            // The Commander used a pearl or a rocket dash; the next cycle picks up the chase again.
        } else if (ranks.getOrDefault(npc, 'D') == 'A' && npc.getEntity() instanceof LivingEntity archer && archerFire(npc, archer, here, target)) {
            // Archer: shot fired from range; it steps back if the player closes in.
        } else if (here.distance(tl) > ATTACK_REACH) {
            // Flank: each Null comes in from its own side, so the group surrounds the target.
            // Pincer: even Nulls come round one side of the target, odd Nulls the other.
            double base = Math.atan2(here.getZ() - tl.getZ(), here.getX() - tl.getX());
            double ang = base + (npc.getId() % 2 == 0 ? Math.PI / 2.5 : -Math.PI / 2.5);
            Location flank = here.distance(tl) > 6 ? tl.clone().add(Math.cos(ang) * 3, 0, Math.sin(ang) * 3) : tl;
            char role = ranks.getOrDefault(npc, 'D');
            if (role == 'R' || role == 'A') flank = tl; // the leader and officers push straight in
            // A runner is cut off, not chased: the squad aims where they will be, and lays cobwebs in their path.
            if (chasing) {
                flank = tl.clone().add(target.getVelocity().setY(0).multiply(12));
                layTrap(target, tl);
            }
            // Counter-play: a player healing on gapples or regeneration gets pressed, not given time.
            if (healing(target)) flank = tl;
            // Adapt to the player: shield users get hit from behind, archers get rushed.
            if (target.isBlocking()) flank = tl.clone().add(target.getLocation().getDirection().multiply(-2.5));
            else if (holdsRanged(target)) flank = tl;
            npc.getNavigator().setTarget(flank);
        } else {
            attack(npc, target);
        }
    }

    /**
     * Idle Nulls work on their own while the owner is online and near: they mine or chop around
     * the owner, alternating kinds, without an order. Turned off with auto-work: false in config.
     */
    private boolean autoWork(NPC npc, Player owner, int index, double leash) {
        if (!plugin.getConfig().getBoolean("auto-work", true)) return false;
        if (npc == leader || isWounded(npc) || hunters.containsKey(npc) || spies.containsKey(npc)) return false;
        if (workTargets.isEmpty() && cycle - lastScanCycle >= SCAN_EVERY_CYCLES) {
            Location anchor = workAnchor(owner);
            WorkKind pick = (cycle / 60) % 2 == 0 ? WorkKind.MINE : WorkKind.LUMBER;
            refillTargetsAt(anchor, pick);
            if (workTargets.isEmpty()) {
                WorkKind other = pick == WorkKind.MINE ? WorkKind.LUMBER : WorkKind.MINE;
                refillTargetsAt(anchor, other);
                pick = other;
            }
            autoKind = pick;
            lastScanCycle = cycle;
        }
        if (workTargets.isEmpty()) return false;
        // Hold the right tool for the job: a sword doesn't mine stone or ore, and the drops depend on it.
        var held = npc.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND);
        ItemStack tool = gearForKind(autoKind);
        if (held == null || held.getType() != tool.getType()) setHeldItem(npc, tool);
        workTick(npc, owner, autoKind, index, leash);
        return true;
    }

    /** How many roster players are within eight blocks of the spot. Used to tell when a Null is outnumbered. */
    private int rosterCrowd(Location spot) {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (consent.canTarget(p) && sameWorld(spot, p.getLocation()) && spot.distance(p.getLocation()) <= 8) n++;
        }
        return n;
    }

    /** The commander's defence of the owner. Never targets the owner or anyone off the roster. */
    private final Map<UUID, Long> wardWarned = new HashMap<>();

    /** Ward: reports any player near the owner who is neither on the roster nor on the team. Never attacks them. */
    private void wardWatch(Player owner) {
        long now = System.currentTimeMillis();
        for (Entity e : owner.getWorld().getNearbyEntities(owner.getLocation(), 5, 5, 5)) {
            if (!(e instanceof Player p) || p.equals(owner) || consent.isTeam(p) || consent.canTarget(p) || isNullBody(p)) continue;
            if (now - wardWarned.getOrDefault(p.getUniqueId(), 0L) < 30_000L) continue;
            wardWarned.put(p.getUniqueId(), now);
            commanderSay(owner, p.getName() + " is within five blocks of you. Not on the roster, so I won't touch them. Add them to your team if you trust them.");
            return;
        }
    }

    /** The Commander's voice: a tagged line to the owner, with a pitched-down voice sound at his position. */
    public void commanderSay(Player owner, String line) {
        // Only the commander speaks: the army's title, never a general or soldier.
        NPC commander = null;
        for (NPC npc : nulls) {
            if (isCommander(npc) && npc.isSpawned()) {
                commander = npc;
                break;
            }
        }
        String tag = LEADER_NAME;
        owner.sendMessage(ChatColor.DARK_GRAY + "<" + ChatColor.WHITE + tag + ChatColor.DARK_GRAY + "> " + ChatColor.GRAY + line);
        if (commander != null && commander.getEntity() != null) {
            owner.playSound(commander.getEntity().getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_AMBIENT, 0.6f, 1.6f);
        }
    }

    private boolean defendOwner(NPC npc, Player owner) {
        Location here = npc.getStoredLocation();
        Location ol = owner.getLocation();
        if (here == null || !sameWorld(here, ol)) return false;
        LivingEntity best = null;
        double bestDist = 16;
        for (Entity e : ol.getWorld().getNearbyEntities(ol, 16, 16, 16)) {
            if (e == owner || e.getUniqueId().equals(npc.getEntity() == null ? null : npc.getEntity().getUniqueId())) continue;
            LivingEntity cand = null;
            double d = ol.distance(e.getLocation());
            if (e instanceof Player p) {
                // Team and the owner are never hit. Anyone who hit the owner is hit back, wherever they are.
                // Anyone else who comes within six blocks of the owner is stopped: nobody gets close.
                if (consent.isOwner(p) || consent.isTeam(p) || isNullBody(p)) continue; // Nulls are players too: never hit each other
                if (!(recentAttacker(p) || d <= 6)) continue;
                cand = p;
            } else if (e instanceof Monster m && d <= 6 && !NETHER_PEACE.contains(m.getType())) {
                cand = m;
            }
            if (cand == null) continue;
            if (d < bestDist) {
                bestDist = d;
                best = cand;
            }
        }
        if (best == null) return false;
        if (here.distance(best.getLocation()) <= ATTACK_REACH) {
            attack(npc, best);
        } else {
            npc.getNavigator().setTarget(best, true);
        }
        return true;
    }

    /** Finds the nearest hostile mob and fights it. Returns false if there's nothing to fight. */
    /** Nether mobs that leave Nulls and the owner alone. Nulls don't fight them, and they don't fight back. */
    public static final Set<EntityType> NETHER_PEACE = EnumSet.of(
            EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.ZOMBIFIED_PIGLIN, EntityType.HOGLIN,
            EntityType.ZOGLIN, EntityType.STRIDER, EntityType.BLAZE, EntityType.GHAST,
            EntityType.MAGMA_CUBE, EntityType.WITHER_SKELETON);

    private boolean engageMobs(NPC npc) {
        Location here = npc.getStoredLocation();
        if (here == null || here.getWorld() == null) return false;
        // Hurt Nulls only take on mobs they can finish quickly; big fights wait until they've healed.
        double ownHp = npc.getEntity() instanceof LivingEntity self ? self.getHealth() : 20.0;
        double range = plugin.getConfig().getDouble("combat-range", 8) * (System.currentTimeMillis() < alarmUntil ? 2 : 1);
        Monster best = null;
        double bestDist = range;
        double bestHealth = Double.MAX_VALUE;
        int bestRank = Integer.MAX_VALUE;
        for (Entity e : here.getWorld().getNearbyEntities(here, range, range, range)) {
            if (!(e instanceof Monster m)) continue;
            if (NETHER_PEACE.contains(m.getType())) continue;
            if (ownHp < 10 && m.getHealth() > 6) continue;
            if (ownHp < 14 && nearDanger(m.getLocation())) continue; // remembers where a Null went down
            double d = here.distance(m.getLocation());
            if (d > range) continue;
            int rank = threatRank(m);
            double hp = m.getHealth();
            boolean better = rank < bestRank
                    || (rank == bestRank && (hp < bestHealth || (hp == bestHealth && d < bestDist)));
            if (better) {
                bestRank = rank;
                bestHealth = hp;
                bestDist = d;
                best = m;
            }
        }
        if (best == null) return false;
        // Hurt Nulls don't walk into a crowd: if four or more mobs are on this one, they hold back.
        if (ownHp < 14 && monstersNear(best.getLocation(), 5) >= 4) return false;
        if (bestDist <= ATTACK_REACH) {
            attack(npc, best);
        } else {
            npc.getNavigator().setTarget(best, true);
        }
        return true;
    }

    /** Lower is more urgent: creepers first (they explode), then ranged and dangerous mobs, then the rest. */
    private static int threatRank(Monster m) {
        return switch (m.getType()) {
            case CREEPER -> 0;
            case ENDERMAN, WITCH, BLAZE, PILLAGER, VINDICATOR -> 1;
            case SKELETON, STRAY, WITHER_SKELETON -> 2;
            default -> 3;
        };
    }

    /** The commander is the Null called by the army's title, wherever the reference has drifted to. */
    private boolean isCommander(NPC npc) {
        return npc == leader || LEADER_NAME.equals(npc.getName());
    }

    /** Keeps the leader pointer on a live Null named with the title, after restarts and successions. */
    private void refreshLeader() {
        if (leader != null && nulls.contains(leader) && LEADER_NAME.equals(leader.getName())) return;
        leader = nulls.stream().filter(n -> LEADER_NAME.equals(n.getName())).findFirst().orElse(null);
    }

    // ---------- Memory, upkeep and friendly play ----------

    private final Map<NPC, Integer> playerLosses = new HashMap<>();
    private final Map<NPC, Integer> mobLosses = new HashMap<>();
    private final Map<NPC, Long> mobWoundAt = new HashMap<>();
    private final Map<NPC, Boolean> axeLoadout = new HashMap<>();
    private final Map<NPC, Long> upkeepAt = new HashMap<>();
    private final Map<NPC, Long> playAt = new HashMap<>();
    private long lastSignReply;
    private static final String[] FRIENDLY_LINES = {
            "Hi Kiyan!", "All quiet, boss.", "Nice build, boss.", "Want a flower?",
            "On it, boss.", "Lovely day for it.", "Need anything?", "Ha! Watch this."};
    private static final String[] GREETINGS = {"Hey boss!", "Hiya, boss!", "Hello Kiyan!"};
    private static final String[] YES_NO = {"Yes, boss!", "Not sure, boss."};

    /** True if a spot where a Null went down is within six blocks of the point. */
    private boolean nearDanger(Location l) {
        for (Location spot : dangerSpots) {
            if (sameWorld(spot, l) && spot.distance(l) <= 6) return true;
        }
        return false;
    }

    /** Out of combat, once a minute: repairs armour and picks the weapon for the Null's record. */
    private void tickUpkeep() {
        long now = System.currentTimeMillis();
        for (NPC npc : nulls) {
            if (!npc.isSpawned() || isCommander(npc) || hunters.containsKey(npc) || focus.containsKey(npc)) continue;
            if (now - upkeepAt.getOrDefault(npc, 0L) < 60_000L) continue;
            upkeepAt.put(npc, now);
            repairArmour(npc);
            tuneLoadout(npc);
        }
    }

    /** Armour worn down past 40% is mended with an iron ingot from the Null's pack. */
    private void repairArmour(NPC npc) {
        Inventory pack = packs.get(npc);
        if (pack == null || !pack.contains(Material.IRON_INGOT)) return;
        Equipment eq = npc.getOrAddTrait(Equipment.class);
        for (Equipment.EquipmentSlot slot : new Equipment.EquipmentSlot[]{
                Equipment.EquipmentSlot.HELMET, Equipment.EquipmentSlot.CHESTPLATE,
                Equipment.EquipmentSlot.LEGGINGS, Equipment.EquipmentSlot.BOOTS}) {
            ItemStack piece = eq.get(slot);
            if (piece == null) continue;
            ItemMeta meta = piece.getItemMeta();
            int max = piece.getType().getMaxDurability();
            if (!(meta instanceof Damageable worn) || max <= 0 || worn.getDamage() < max * 0.4) continue;
            if (!pack.contains(Material.IRON_INGOT)) return;
            pack.removeItem(new ItemStack(Material.IRON_INGOT, 1));
            worn.setDamage(0);
            piece.setItemMeta(worn);
            eq.set(slot, piece);
        }
    }

    /** A Null that keeps losing to players switches to an axe, which breaks shields. Otherwise it keeps its sword. */
    private void tuneLoadout(NPC npc) {
        if (kindOf(mode) != null) return; // a work mode holds its own tool
        boolean axe = playerLosses.getOrDefault(npc, 0) > mobLosses.getOrDefault(npc, 0) + 1;
        axeLoadout.put(npc, axe);
        if (axe) {
            ItemStack weapon = new ItemStack(Material.NETHERITE_AXE);
            weapon.addUnsafeEnchantment(Enchantment.SHARPNESS, 4);
            weapon.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            setHeldItem(npc, weapon);
        } else {
            setHeldItem(npc, rankSword(ranks.getOrDefault(npc, 'D')));
        }
    }

    /** Friendly play runs only when the owner is the one person on the server, and nothing is hunting. */
    /** Nulls that have already greeted the owner in this friendly session. */
    private final Set<NPC> greeted = new HashSet<>();

    private boolean friendlyMode(Player owner) {
        if (!plugin.getConfig().getBoolean("friendly-play", true)) return false;
        if (mode != Mode.FOLLOW && mode != Mode.STAY && mode != Mode.GUARD) return false;
        if (!hunters.isEmpty() || !pursuits.isEmpty() || System.currentTimeMillis() < alarmUntil) return false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(owner) || consent.isOwner(p) || consent.isTeam(p) || isNullBody(p)) continue;
            return false;
        }
        return true;
    }

    /** One idle Null's turn in friendly mode: stays close, then does one small thing every few seconds. */
    private boolean friendlyTick(NPC npc, Player owner, int index) {
        if (!friendlyMode(owner)) {
            greeted.clear();
            return false;
        }
        Location here = npc.getStoredLocation();
        if (here == null || !sameWorld(here, owner.getLocation())) return false;
        if (greeted.add(npc)) dance(npc); // one greeting each, when the owner comes back alone
        followOwner(npc, owner, index, 3);
        long now = System.currentTimeMillis();
        if (now < playAt.getOrDefault(npc, 0L)) return true;
        playAt.put(npc, now + 30_000L + rng.nextInt(30_000));
        // Idle play is movement only: the occasional dance, otherwise a swing. No gifts, no chatter.
        if ((rng.nextInt(4) + npc.getId()) % 4 == 0) dance(npc);
        else swing(npc);
        return true;
    }

    /** Writes a line on a sign beside the owner, signed by the Null. */
    private void sayTo(NPC npc, Player owner, String line) {
        placeSign(owner, new String[]{"<" + npc.getName() + ">", line});
    }

    private void dance(NPC npc) {
        if (npc.getEntity() instanceof LivingEntity living) living.setVelocity(new Vector(0, 0.35, 0));
        swing(npc);
    }

    /** The owner writes on a sign: the nearest friendly Null reads it and answers on its own sign. */
    public void onOwnerSign(Player owner, String[] lines) {
        if (!consent.isOwner(owner) || !friendlyMode(owner)) return;
        if (System.currentTimeMillis() - lastSignReply < 15_000L) return; // one reply per quarter minute, no spam
        lastSignReply = System.currentTimeMillis();
        Location at = owner.getLocation();
        NPC closest = null;
        double best = 12;
        for (NPC npc : nulls) {
            Location here = npc.getStoredLocation();
            if (!npc.isSpawned() || here == null || !sameWorld(here, at)) continue;
            double d = here.distance(at);
            if (d < best) {
                best = d;
                closest = npc;
            }
        }
        if (closest == null) return;
        String text = String.join(" ", lines).toLowerCase();
        String reply;
        if (text.matches(".*\\b(hi|hello|hey)\\b.*")) reply = GREETINGS[rng.nextInt(GREETINGS.length)];
        else if (text.contains("?")) reply = YES_NO[rng.nextInt(YES_NO.length)];
        else reply = FRIENDLY_LINES[rng.nextInt(FRIENDLY_LINES.length)];
        sayTo(closest, owner, reply);
        dance(closest);
    }

    private final Map<NPC, Long> lastMeal = new HashMap<>();

    /** A Null that is getting hungry eats cooked beef from its pack. Checked once per cycle, with a cooldown. */
    private void tickFood() {
        long now = System.currentTimeMillis();
        for (NPC npc : nulls) {
            if (!npc.isSpawned() || now - lastMeal.getOrDefault(npc, 0L) < 20_000L) continue;
            if (!(npc.getEntity() instanceof org.bukkit.entity.HumanEntity human)) continue;
            if (human.getFoodLevel() >= 14) continue;
            Inventory pack = packs.get(npc);
            if (pack == null || !pack.contains(Material.COOKED_BEEF)) continue;
            pack.removeItem(new ItemStack(Material.COOKED_BEEF, 1));
            human.setFoodLevel(Math.min(20, human.getFoodLevel() + 8));
            human.setSaturation(Math.min(20f, human.getSaturation() + 6f));
            lastMeal.put(npc, now);
        }
    }

    /** Wounded Nulls get regeneration while they fall back to the group. */
    /** A wounded Null eats a golden apple from its pack: absorption and regeneration. */
    private void eatApple(NPC npc) {
        long now = System.currentTimeMillis();
        if (now - lastApple.getOrDefault(npc, 0L) < 15_000L) return;
        Inventory pack = packs.get(npc);
        if (pack == null || !pack.contains(Material.GOLDEN_APPLE)) return;
        pack.removeItem(new ItemStack(Material.GOLDEN_APPLE, 1));
        lastApple.put(npc, now);
        Entity e = npc.getEntity();
        if (e instanceof LivingEntity living) {
            living.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 120, 0, false, true));
            living.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 2, false, true));
        }
    }

    private void fieldHeal(NPC npc) {
        drinkPotion(npc);
        eatApple(npc);
        Entity e = npc.getEntity();
        if (e instanceof LivingEntity living) {
            living.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 1, false, false));
            living.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1, false, false)); // run from it
        }
    }

    /** A hurt Null drinks a healing potion from its pack. Same cooldown as apples, so they don't stack. */
    private void drinkPotion(NPC npc) {
        long now = System.currentTimeMillis();
        if (now - lastApple.getOrDefault(npc, 0L) < 15_000L) return;
        Inventory pack = packs.get(npc);
        if (pack == null || !pack.contains(Material.POTION)) return;
        pack.removeItem(new ItemStack(Material.POTION, 1));
        lastApple.put(npc, now);
        if (npc.getEntity() instanceof LivingEntity living) {
            living.addPotionEffect(new PotionEffect(PotionEffectType.INSTANT_HEALTH, 1, 1, false, true));
        }
    }

    private static boolean isNight(Player owner) {
        long t = owner.getWorld().getTime();
        return t >= 13000 && t < 23000;
    }

    /** Puts a Null on a ring of the given radius around a centre point. */
    private void ringAround(NPC npc, Location centre, int index, double radius) {
        double angle = (2 * Math.PI / Math.max(1, nulls.size())) * index;
        Location spot = centre.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
        Location here = npc.getStoredLocation();
        if (here == null || !sameWorld(here, spot) || here.distance(spot) > TELEPORT_OVER) {
            walkTo(npc, spot);
        } else if (here.distance(spot) > REACH) {
            npc.getNavigator().setTarget(spot);
        }
    }

    /** Circles the target at melee range instead of standing still to trade hits. */
    private void strafe(NPC npc, LivingEntity target) {
        Location here = npc.getStoredLocation();
        Location tl = target.getLocation();
        if (here == null || !sameWorld(here, tl) || here.distance(tl) > 6) return;
        double base = Math.atan2(here.getZ() - tl.getZ(), here.getX() - tl.getX());
        double side = (cycle % 2 == 0 ? 0.7 : -0.7) * (npc.getId() % 2 == 0 ? 1 : -1);
        Location spot = tl.clone().add(Math.cos(base + side) * 2.2, 0, Math.sin(base + side) * 2.2);
        npc.getNavigator().setTarget(spot);
    }

    /** Monsters within a radius of a point. Used to spot fights that would pull a Null into a crowd. */
    private static int monstersNear(Location at, double radius) {
        int n = 0;
        for (Entity e : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
            if (e instanceof Monster) n++;
        }
        return n;
    }

    private void attack(NPC npc, LivingEntity target) {
        if (npc.getEntity() instanceof LivingEntity self) {
            // Top up before trading blows, not only once the Null is already retreating.
            if (self.getHealth() < 12) eatApple(npc);
            if (self.getHealth() < 8) drinkPotion(npc);
        }
        if (cycle % 3 == 0) strafe(npc, target);
        Integer last = lastAttack.get(npc);
        int cooldown = isCommander(npc) || ranks.getOrDefault(npc, 'D') == 'A' ? 1 : ATTACK_COOLDOWN_CYCLES;
        if (last != null && cycle - last < cooldown) return;
        lastAttack.put(npc, cycle);
        double damage = plugin.getConfig().getDouble("combat-damage", 8) * rankScale(npc);
        if (target instanceof Player p) {
            if (p.isBlocking()) damage *= 1.5; // a Null hit breaks through a raised shield
            if (p.isBlocking() && axeLoadout.getOrDefault(npc, false)) damage *= 1.25; // axes break shields
            damage *= 1.0 + Math.min(0.5, threat.getOrDefault(p.getUniqueId(), 0.0) / 20.0); // learned from past fights
        }
        target.damage(damage, npc.getEntity());
        swing(npc);
    }

    /** Holds a ring just outside the cage so the Nulls seal it from the outside. */
    private void guardPoint(NPC npc, int index) {
        double angle = (2 * Math.PI / Math.max(1, nulls.size())) * index;
        Location spot = cageGuard.clone().add(Math.cos(angle) * 6.5, 0, Math.sin(angle) * 6.5);
        Location here = npc.getStoredLocation();
        if (here == null || !sameWorld(here, spot) || here.distance(spot) > TELEPORT_OVER) {
            walkTo(npc, spot);
        } else if (here.distance(spot) > REACH) {
            npc.getNavigator().setTarget(spot);
        }
    }

    /** Called by the plugin when a roster player uses an ender pearl. Hunters on that player snap to the landing spot. */
    public void followPearl(Player player, Location landing) {
        if (!consent.canTarget(player) || landing == null) return;
        Location dest = landing.clone();
        for (Map.Entry<NPC, Hunt> entry : hunters.entrySet()) {
            if (!entry.getValue().target().equals(player.getUniqueId())) continue;
            NPC npc = entry.getKey();
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (npc.isSpawned()) teleport(npc, dest);
                }
            }.runTaskLater(plugin, 2L);
        }
    }

    /** Sends the whole army after one roster player until the order is changed. */
    public void setHuntTarget(Player owner, Player target) {
        huntTarget = target.getUniqueId();
        mode = Mode.HUNT;
        cageGuard = null;
        resetWork();
        applyGear(Mode.HUNT);
        owner.sendMessage(ChatColor.DARK_PURPLE + "The army is hunting " + target.getName() + ". Roster only.");
    }

    /** Sets a cage to guard from outside. Switches the army to GUARD. */
    public void guardCage(Location center) {
        cageGuard = center.clone();
        mode = Mode.GUARD;
        resetWork();
        applyGear(Mode.GUARD);
    }

    private void forgeTick(NPC npc, Player owner, int index, double leash) {
        Block forge = configuredBlock("forge-block");
        if (forge == null) {
            followOwner(npc, owner, index, leash);
            return;
        }
        Location spot = forge.getLocation().add(0.5, 1, 0.5);
        Location here = npc.getStoredLocation();
        if (here == null || !sameWorld(here, spot) || here.distance(spot) > TELEPORT_OVER) {
            walkTo(npc, spot);
        } else if (here.distance(spot) > FORGE_HOLD) {
            npc.getNavigator().setTarget(spot);
        } else {
            forgeHold(npc, owner);
        }
    }

    private void patrolTick(NPC npc, Player owner, int index, double leash) {
        if (patrolRoute.isEmpty()) {
            followOwner(npc, owner, index, leash);
            return;
        }
        int idx = waypointIndex.getOrDefault(npc, index % patrolRoute.size()) % patrolRoute.size();
        Location wp = patrolRoute.get(idx);
        Location here = npc.getStoredLocation();

        if (here == null || !sameWorld(here, wp) || here.distance(wp) > TELEPORT_OVER) {
            walkTo(npc, wp);
        } else if (here.distance(wp) <= WAYPOINT_REACH) {
            idx = (idx + 1) % patrolRoute.size();
            waypointIndex.put(npc, idx);
            npc.getNavigator().setTarget(patrolRoute.get(idx));
        } else {
            npc.getNavigator().setTarget(wp);
        }
    }

    /** Shared by AUTO-MINE, LUMBERJACK, and FARM. */
    private void workTick(NPC npc, Player owner, WorkKind kind, int index, double leash) {
        if (depositing.contains(npc)) {
            depositTick(npc, owner, index, leash);
            return;
        }

        Block target = claimed.get(npc);
        if (target == null || priorityOf(target, kind) < 0) {
            claimed.remove(npc);
            target = nextTarget(kind);
            if (target == null) {
                followOwner(npc, owner, index, leash);
                return;
            }
            claimed.put(npc, target);
        }

        Location spot = target.getLocation().add(0.5, 0, 0.5);
        Location here = npc.getStoredLocation();
        if (here == null || !sameWorld(here, spot) || here.distance(spot) > TELEPORT_OVER) {
            walkTo(npc, spot.clone().add(0, 1, 0));
        } else if (here.distance(spot) > REACH) {
            if (kind == WorkKind.MINE && !exposed(target)) tunnelStep(npc, here, target);
            else npc.getNavigator().setTarget(spot);
        } else {
            Inventory pack = packs.get(npc);
            if (pack != null && !fitsInPack(pack, target, kind)) {
                // Pack is full: keep the block for later and empty the pack first.
                workTargets.addFirst(target);
                claimed.remove(npc);
                depositing.add(npc);
                return;
            }
            breakTarget(target, npc, kind);
            swing(npc);
            claimed.remove(npc);
        }
    }

    /** True when a block touches open air, so a Null can walk up to it without digging. */
    private static boolean exposed(Block b) {
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (b.getRelative(f).getType().isAir()) return true;
        }
        return false;
    }

    /**
     * Digs one block toward a buried target, then steps into the gap. Repeated each cycle this makes a tunnel:
     * it goes down when the target is below and sideways when it is level. Bedrock is never dug.
     */
    private void tunnelStep(NPC npc, Location here, Block target) {
        World w = target.getWorld();
        int dx = target.getX() - here.getBlockX();
        int dy = target.getY() - here.getBlockY();
        int dz = target.getZ() - here.getBlockZ();
        Block next;
        if (Math.abs(dy) >= Math.max(Math.abs(dx), Math.abs(dz))) {
            next = w.getBlockAt(here.getBlockX(), here.getBlockY() + Integer.signum(dy), here.getBlockZ());
        } else if (Math.abs(dx) >= Math.abs(dz)) {
            next = w.getBlockAt(here.getBlockX() + Integer.signum(dx), here.getBlockY(), here.getBlockZ());
        } else {
            next = w.getBlockAt(here.getBlockX(), here.getBlockY(), here.getBlockZ() + Integer.signum(dz));
        }
        if (next.getType() == Material.BEDROCK) {
            claimed.remove(npc); // can't dig through; pick another target next cycle
            return;
        }
        if (!next.getType().isAir()) {
            breakTarget(next, npc, WorkKind.MINE);
            swing(npc);
        }
        npc.getNavigator().setTarget(next.getLocation().add(0.5, 0, 0.5));
    }

    private void depositTick(NPC npc, Player owner, int index, double leash) {
        Inventory pack = packs.get(npc);
        if (pack == null || isEmpty(pack)) {
            depositing.remove(npc);
            return;
        }

        Block chestBlock = configuredBlock("deposit-chest");
        if (chestBlock == null) {
            chestBlock = chests().place(Chests.Kind.DEPOSIT, npc.getStoredLocation() != null ? npc.getStoredLocation() : owner.getLocation());
            if (chestBlock != null) saveBlock("deposit-chest", chestBlock);
        }
        if (chestBlock == null || !(chestBlock.getState() instanceof Container container)) {
            owner.sendMessage(ChatColor.RED + "Nulls have full packs and no deposit chest. "
                    + "Look at a chest and run /nullcmd chest set.");
            followOwner(npc, owner, index, leash);
            return;
        }

        Location spot = chestBlock.getLocation().add(0.5, 0, 0.5);
        Location here = npc.getStoredLocation();
        if (here == null || !sameWorld(here, spot) || here.distance(spot) > TELEPORT_OVER) {
            walkTo(npc, spot.clone().add(0, 1, 0));
        } else if (here.distance(spot) > REACH) {
            npc.getNavigator().setTarget(spot);
        } else {
            Map<Integer, ItemStack> leftover = container.getInventory().addItem(nonNull(pack.getContents()));
            if (!leftover.isEmpty()) {
                Container loot = chests().nearest(Chests.Kind.LOOT, here);
                if (loot != null) leftover = loot.getInventory().addItem(leftover.values().toArray(new ItemStack[0]));
            }
            pack.clear();
            for (ItemStack left : leftover.values()) {
                pack.addItem(left);
            }
            swing(npc);
            if (leftover.isEmpty()) {
                depositing.remove(npc);
            } else {
                owner.sendMessage(ChatColor.RED + "The deposit chest is full.");
            }
        }
    }

    // ---------- Work scanning and breaking ----------

    private static int minePriority(Material m) {
        return switch (m) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, ANCIENT_DEBRIS -> 0;
            case GOLD_ORE, DEEPSLATE_GOLD_ORE -> 1;
            case IRON_ORE, DEEPSLATE_IRON_ORE -> 2;
            default -> Tag.BASE_STONE_OVERWORLD.isTagged(m) ? 3 : -1;
        };
    }

    private static boolean isMatureCrop(Block b) {
        return FARM_CROPS.contains(b.getType())
                && b.getBlockData() instanceof Ageable ageable
                && ageable.getAge() >= ageable.getMaximumAge();
    }

    /** Returns the work priority for a block, or -1 if this kind of worker ignores it. */
    private static int priorityOf(Block b, WorkKind kind) {
        return switch (kind) {
            case MINE -> minePriority(b.getType());
            case LUMBER -> Tag.LOGS.isTagged(b.getType()) ? 0 : -1;
            case FARM -> (isMatureCrop(b) || b.getType() == Material.MELON || b.getType() == Material.PUMPKIN) ? 0 : -1;
        };
    }

    private void refillTargets(Player owner, WorkKind kind) {
        refillTargetsAt(workAnchor(owner), kind);
    }

    /**
     * Where the Nulls look for work: around you while they are near you, otherwise around
     * their own centre, so mining and lumber still happen when you are far from the ore.
     */
    private Location workAnchor(Player owner) {
        Location ol = owner.getLocation();
        double sx = 0, sy = 0, sz = 0;
        int n = 0;
        World w = null;
        for (NPC npc : nulls) {
            Location here = npc.getStoredLocation();
            if (!npc.isSpawned() || here == null) continue;
            if (sameWorld(here, ol) && here.distance(ol) <= WORK_RADIUS) return ol;
            sx += here.getX();
            sy += here.getY();
            sz += here.getZ();
            w = here.getWorld();
            n++;
        }
        if (n == 0 || w == null) return ol;
        return new Location(w, sx / n, sy / n, sz / n);
    }

    private void refillTargetsAt(Location c, WorkKind kind) {
        workTargets.clear();
        World w = c.getWorld();
        if (w == null) return;
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        int minY = Math.max(w.getMinHeight(), cy - WORK_RADIUS);
        int maxY = Math.min(w.getMaxHeight() - 1, cy + WORK_RADIUS);

        List<Scored> found = new ArrayList<>();
        for (int x = cx - WORK_RADIUS; x <= cx + WORK_RADIUS; x++) {
            for (int z = cz - WORK_RADIUS; z <= cz + WORK_RADIUS; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    int pr = priorityOf(b, kind);
                    if (pr < 0) continue;
                    double dx = x - cx, dy = y - cy, dz = z - cz;
                    found.add(new Scored(b, pr, dx * dx + dy * dy + dz * dz));
                }
            }
        }
        found.sort(Comparator.comparingInt(Scored::priority).thenComparingDouble(Scored::distSq));
        for (int i = 0; i < Math.min(found.size(), MAX_TARGETS); i++) {
            workTargets.add(found.get(i).block());
        }
    }

    private Block nextTarget(WorkKind kind) {
        while (!workTargets.isEmpty()) {
            Block b = workTargets.poll();
            if (priorityOf(b, kind) >= 0 && !claimed.containsValue(b)) {
                return b;
            }
        }
        return null;
    }

    private boolean fitsInPack(Inventory pack, Block block, WorkKind kind) {
        Collection<ItemStack> drops = block.getDrops(gearForKind(kind));
        if (drops.isEmpty()) return true;
        Inventory test = Bukkit.createInventory(null, PACK_SIZE);
        ItemStack[] copy = Arrays.stream(pack.getContents())
                .map(i -> i == null ? null : i.clone())
                .toArray(ItemStack[]::new);
        test.setContents(copy);
        return test.addItem(drops.toArray(new ItemStack[0])).isEmpty();
    }

    /** Harvests or breaks the block and puts the drops into the Null's own pack. Crops are replanted. */
    private void breakTarget(Block block, NPC npc, WorkKind kind) {
        Collection<ItemStack> drops = block.getDrops(gearForKind(kind));
        if (kind == WorkKind.FARM) {
            if (block.getBlockData() instanceof Ageable ageable) {
                ageable.setAge(0);
                block.setBlockData(ageable, false);
            } else {
                block.setType(Material.AIR, false); // melon or pumpkin: the stem regrows it
            }
        } else {
            Material logType = block.getType();
            Block below = block.getRelative(BlockFace.DOWN);
            boolean replant = kind == WorkKind.LUMBER
                    && !Tag.LOGS.isTagged(below.getType())
                    && REPLANT_GROUND.contains(below.getType());
            Material sapling = replant ? Material.matchMaterial(logType.name().replace("_LOG", "_SAPLING")) : null;
            block.setType(Material.AIR, false);
            if (sapling != null) block.setType(sapling, false);
        }
        Inventory pack = packs.get(npc);
        if (pack != null && !drops.isEmpty()) {
            pack.addItem(drops.toArray(new ItemStack[0]));
        }
    }

    // ---------- Config-stored blocks ----------

    private Block configuredBlock(String key) {
        String raw = plugin.getConfig().getString(key, "");
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(",");
        if (parts.length != 4) return null;
        World w = Bukkit.getWorld(parts[0]);
        if (w == null) return null;
        try {
            return w.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void saveBlock(String key, Block b) {
        plugin.getConfig().set(key, b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ());
        plugin.saveConfig();
    }

    // ---------- Helpers ----------

    private static boolean isEmpty(Inventory inv) {
        for (ItemStack item : inv.getContents()) {
            if (item != null) return false;
        }
        return true;
    }

    private static ItemStack[] nonNull(ItemStack[] items) {
        return Arrays.stream(items).filter(i -> i != null).toArray(ItemStack[]::new);
    }

    /** Moves a Null by portal-style arrival: a portal flash at the destination, then the Null steps out. */
    /** Walks (and sprints, jumping where the path needs it) to the spot. Used for following and moving, never as a teleport. */
    private void walkTo(NPC npc, Location loc) {
        if (npc.getEntity() == null) {
            npc.spawn(loc);
            return;
        }
        if (npc.getEntity() instanceof Player p) p.setSprinting(true);
        npc.getNavigator().setTarget(loc);
    }

    /** Places a real nether portal (obsidian frame, two wide, three tall) in front of the owner. */
    public String buildPortal(Player owner) {
        Location spot = owner.getLocation().add(owner.getLocation().getDirection().setY(0).normalize().multiply(3));
        return buildPortalAt(owner, spot);
    }

    /**
     * Opens a portal to where a roster player is in the Nether. The portal sits in the overworld at their
     * Nether position times eight (the game's scaling), so walking through it lands beside them.
     */
    public String bringPortal(Player owner, Player target) {
        if (target.getWorld().getEnvironment() != World.Environment.NETHER) {
            return target.getName() + " is not in the Nether.";
        }
        World overworld = Portals.otherWorld(target.getWorld());
        if (overworld == null) return "No overworld to open a portal in.";
        Location l = target.getLocation();
        Location spot = new Location(overworld, l.getBlockX() * 8, l.getBlockY(), l.getBlockZ() * 8);
        return buildPortalAt(owner, spot) + " Walk there: it leads to " + target.getName() + " in the Nether.";
    }

    /** A portal across the path at a spot, so the owner can walk back through it. alongZ: the portal faces east-west. */
    public String portalAcross(Player owner, Location spot, boolean alongZ) {
        return buildPortalAt(owner, spot, alongZ);
    }

    private String buildPortalAt(Player owner, Location spot) {
        return buildPortalAt(owner, spot, false);
    }

    private String buildPortalAt(Player owner, Location spot, boolean alongZ) {
        World w = spot.getWorld();
        if (w == null) return "No world to open a portal in.";
        if (w.getEnvironment() == World.Environment.THE_END) return "Portals to the Nether don't work in the End.";
        for (Player other : w.getPlayers()) {
            if (other.equals(owner) || other.getLocation().distance(spot) > 20) continue;
            if (!consent.canTarget(other)) return "Refused. " + other.getName() + " is not on the roster and is too close.";
        }
        int bx = spot.getBlockX(), by = spot.getBlockY(), bz = spot.getBlockZ();
        for (int a = -2; a <= 1; a++)
            for (int y = by - 1; y <= by + 3; y++) {
                boolean interior = a >= -1 && a <= 0 && y >= by && y <= by + 2;
                Block b = alongZ ? w.getBlockAt(bx, y, bz + a) : w.getBlockAt(bx + a, y, bz);
                if (interior) {
                    Orientable axis = (Orientable) Material.NETHER_PORTAL.createBlockData();
                    axis.setAxis(alongZ ? Axis.Z : Axis.X);
                    b.setBlockData(axis, false);
                } else {
                    b.setType(Material.OBSIDIAN, false);
                }
            }
        return "Nether portal open at " + coordsOf(spot) + ".";
    }

    private void teleport(NPC npc, Location loc) {
        if (npc.getEntity() == null) {
            npc.spawn(loc);
            return;
        }
        entrance(npc, loc);
    }

    private void entrance(NPC npc, Location dest) {
        if (!arriving.add(npc)) return;
        World w = dest.getWorld();
        if (w != null) {
            w.spawnParticle(Particle.PORTAL, dest.clone().add(0, 1, 0), 80, 0.6, 1.0, 0.6, 0.5);
            w.playSound(dest, Sound.BLOCK_PORTAL_TRAVEL, 0.7f, 1.3f);
        }
        Location arrival = dest.clone();
        new BukkitRunnable() {
            @Override
            public void run() {
                arriving.remove(npc);
                Entity e = npc.getEntity();
                if (!npc.isSpawned() || e == null) return;
                e.teleport(arrival);
                World aw = arrival.getWorld();
                if (aw != null) {
                    aw.spawnParticle(Particle.PORTAL, arrival.clone().add(0, 1, 0), 80, 0.6, 1.0, 0.6, 0.5);
                    aw.playSound(arrival, Sound.BLOCK_PORTAL_TRAVEL, 0.7f, 0.9f);
                }
            }
        }.runTaskLater(plugin, 30L);
    }

    /** Hunters keep working while the owner is offline. */
    private void tickHunters() {
        for (Map.Entry<NPC, Hunt> entry : new ArrayList<>(hunters.entrySet())) {
            NPC npc = entry.getKey();
            if (!npc.isSpawned() || arriving.contains(npc)) continue;
            huntTick(npc, entry.getValue());
        }
    }

    // ---------- Offline work, spies and planning ----------

    /** Where offline work happens: the deposit chest if one is set, otherwise the city centre. */
    private Location offlineCentre() {
        Block chest = configuredBlock("deposit-chest");
        if (chest != null) return chest.getLocation().add(0.5, 1, 0.5);
        return cityCentre();
    }

    /** The owner is offline: working Nulls keep mining, chopping or farming around the offline centre. */
    private void tickOffline() {
        if (mode == Mode.EXPLORE) {
            Location centre = offlineCentre();
            if (centre == null) idleGuard();
            else roamGroup(centre, false);
            return;
        }
        if (mode == Mode.NETHER) {
            netherOffline();
            return;
        }
        WorkKind kind = kindOf(mode);
        Location centre = offlineCentre();
        if (kind == null || centre == null) {
            idleGuard();
            return;
        }
        if (workTargets.isEmpty() && System.currentTimeMillis() - lastOfflineScan > 60_000L) {
            refillTargetsAt(centre, kind);
            lastOfflineScan = System.currentTimeMillis();
        }
        for (NPC npc : new ArrayList<>(nulls)) {
            if (!npc.isSpawned() || arriving.contains(npc) || hunters.containsKey(npc) || spies.containsKey(npc)) continue;
            offlineWork(npc, kind, centre);
        }
    }

    /** One Null's offline job: break the next target, or empty a full pack into the deposit chest. */
    private void offlineWork(NPC npc, WorkKind kind, Location centre) {
        Block chest = configuredBlock("deposit-chest");
        if (depositing.contains(npc)) {
            Inventory pack = packs.get(npc);
            Container c = chest != null && chest.getState() instanceof Container cc ? cc : null;
            if (pack == null || isEmpty(pack) || c == null) {
                depositing.remove(npc);
                return;
            }
            Location spot = chest.getLocation().add(0.5, 1, 0.5);
            Location here = npc.getStoredLocation();
            if (here == null || !sameWorld(here, spot) || here.distance(spot) > TELEPORT_OVER) {
                walkTo(npc, spot);
            } else if (here.distance(spot) > REACH) {
                npc.getNavigator().setTarget(spot);
            } else {
                for (int i = 0; i < pack.getSize(); i++) {
                    ItemStack s = pack.getItem(i);
                    if (s == null) continue;
                    var left = c.getInventory().addItem(s.clone());
                    pack.setItem(i, left.isEmpty() ? null : left.values().iterator().next());
                }
                if (isEmpty(pack)) depositing.remove(npc);
            }
            return;
        }

        Block target = claimed.get(npc);
        if (target == null || priorityOf(target, kind) < 0) {
            claimed.remove(npc);
            target = nextTarget(kind);
            if (target == null) {
                Location here = npc.getStoredLocation();
                if (here != null && sameWorld(here, centre) && here.distance(centre) > 8) {
                    npc.getNavigator().setTarget(centre);
                }
                return;
            }
            claimed.put(npc, target);
        }
        Location spot = target.getLocation().add(0.5, 0, 0.5);
        Location here = npc.getStoredLocation();
        if (here == null || !sameWorld(here, spot) || here.distance(spot) > TELEPORT_OVER) {
            walkTo(npc, spot.clone().add(0, 1, 0));
        } else if (here.distance(spot) > REACH) {
            npc.getNavigator().setTarget(spot);
        } else {
            Inventory pack = packs.get(npc);
            if (pack != null && !fitsInPack(pack, target, kind)) {
                workTargets.addFirst(target);
                claimed.remove(npc);
                depositing.add(npc);
                return;
            }
            breakTarget(target, npc, kind);
            swing(npc);
            claimed.remove(npc);
        }
    }

    /** Spy: a hidden Null trails one roster player and reports where they are and what they're doing. */
    public void spy(Player owner, Player target) {
        Location loc = cornerSpot(target.getLocation());
        NPC npc = createNull(loc, gearForMode(Mode.FOLLOW), false);
        hide(npc);
        spies.put(npc, new Spy(target.getUniqueId(), new long[]{0L}));
        owner.sendMessage(ChatColor.DARK_PURPLE + "Spy on " + target.getName() + " set. Reports every minute.");
    }

    /** Recalls every spy. */
    public void stopSpies(Player owner) {
        List<NPC> all = new ArrayList<>(spies.keySet());
        for (NPC npc : all) dropNpc(npc);
        owner.sendMessage(ChatColor.DARK_PURPLE + "Spies recalled (" + all.size() + ").");
    }

    private void tickSpies() {
        long now = System.currentTimeMillis();
        for (Map.Entry<NPC, Spy> entry : new ArrayList<>(spies.entrySet())) {
            NPC npc = entry.getKey();
            Spy spy = entry.getValue();
            if (!npc.isSpawned()) continue;
            Player target = Bukkit.getPlayer(spy.target());
            if (target == null) continue;
            if (!consent.canTarget(target)) {
                dropNpc(npc);
                continue;
            }
            Location here = npc.getStoredLocation();
            Location tl = target.getLocation();
            // Keep out of sight: sit 16 to 30 blocks behind the target, never walking up to them.
            if (here == null || !sameWorld(here, tl) || here.distance(tl) > 60) {
                appearNear(npc, target);
            } else if (here.distance(tl) < 16 || here.distance(tl) > 30) {
                npc.getNavigator().setTarget(behindSpot(target, 22));
            }
            if (now >= spy.nextReport()[0]) {
                spy.nextReport()[0] = now + 60_000L;
                spyReport(target);
            }
        }
    }

    private void spyReport(Player target) {
        ItemStack hand = target.getInventory().getItemInMainHand();
        Block looking = target.getTargetBlockExact(5);
        int hostile = 0;
        for (Entity e : target.getNearbyEntities(12, 12, 12)) {
            if (e instanceof Monster) hostile++;
        }
        Location l = target.getLocation();
        String msg = ChatColor.DARK_PURPLE + "[Spy] " + target.getName() + " at " + l.getBlockX() + ", "
                + l.getBlockY() + ", " + l.getBlockZ() + " in " + l.getWorld().getName()
                + ". Health " + (int) target.getHealth()
                + ", holding " + (hand.getType().isAir() ? "nothing" : hand.getType().name().toLowerCase())
                + ", looking at " + (looking == null ? "air" : looking.getType().name().toLowerCase())
                + ", " + hostile + " hostile nearby.";
        Player o = Bukkit.getPlayerExact(ConsentManager.OWNER_NAME);
        if (o != null) o.sendMessage(msg);
        plugin.alert(ChatColor.stripColor(msg));
    }

    /** Replaces the patrol route with points learned from where roster players go. */
    public void setLearnedRoute(List<Location> points) {
        patrolRoute.clear();
        waypointIndex.clear();
        for (int i = 0; i < Math.min(points.size(), MAX_WAYPOINTS); i++) patrolRoute.add(points.get(i));
    }

    public Mode currentMode() {
        return mode;
    }

    /** Changes the work order without an owner present. Used by the task planner. */
    public void planMode(Mode m) {
        if (mode == m) return;
        mode = m;
        cageGuard = null;
        huntTarget = null;
        resetWork();
        applyGear(m);
    }

    /** A spot 9 to 13 blocks from the target, out of its line of sight, found on solid ground. */
    private Location cornerSpot(Location around) {
        World w = around.getWorld();
        double ang = rng.nextDouble() * Math.PI * 2;
        double dist = 9 + rng.nextDouble() * 4;
        int x = (int) Math.floor(around.getX() + Math.cos(ang) * dist);
        int z = (int) Math.floor(around.getZ() + Math.sin(ang) * dist);
        int top = around.getBlockY() + 3;
        for (int y = top; y > top - 10; y--) {
            Block floor = w.getBlockAt(x, y - 1, z);
            if (floor.getType().isSolid()
                    && !w.getBlockAt(x, y, z).getType().isSolid()
                    && !w.getBlockAt(x, y + 1, z).getType().isSolid()) {
                return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return around.clone().add(Math.cos(ang) * dist, 0, Math.sin(ang) * dist);
    }

    /** Hunters appear around a corner and chase. No portal, no flash, no teleport onto the player. */
    /** Makes a spy invisible for as long as it lasts: the body and name tag stay hidden. */
    private static void hide(NPC npc) {
        if (npc.getEntity() instanceof LivingEntity living) {
            living.setInvisible(true);
            living.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
        }
    }

    /** A ground spot the given distance behind the target, out of the way of where they are looking. */
    private Location behindSpot(Player target, double dist) {
        Location tl = target.getLocation();
        org.bukkit.util.Vector facing = tl.getDirection().setY(0);
        if (facing.lengthSquared() < 0.0001) facing = new org.bukkit.util.Vector(1, 0, 0);
        facing.normalize();
        Location spot = tl.clone().subtract(facing.multiply(dist));
        spot.setY(spot.getWorld().getHighestBlockYAt(spot) + 1);
        return spot;
    }

    private void appearNear(NPC npc, Player target) {
        Location spot = behindSpot(target, 22);
        if (npc.getEntity() == null) {
            npc.spawn(spot);
            return;
        }
        if (!npc.isSpawned()) return;
        Location here = npc.getStoredLocation();
        // Changing dimension: walk to a real portal nearby and step through it, if there is one.
        if (here != null && !sameWorld(here, spot) && usePortal(npc, here)) return;
        npc.getEntity().teleport(spot);
    }

    /**
     * Walks the Null to the nearest real portal and, once it's in the frame, moves it to the paired portal.
     * Returns false when there's no portal near, so the caller falls back to a plain move.
     */
    private boolean usePortal(NPC npc, Location here) {
        Location portal = Portals.portalNear(here);
        if (portal == null) return false;
        if (here.distance(portal) > 1.5) {
            npc.getNavigator().setTarget(portal);
            return true;
        }
        Location paired = Portals.pairedPortal(portal);
        if (paired == null) return false;
        World pw = paired.getWorld();
        if (pw != null) {
            pw.spawnParticle(Particle.PORTAL, paired.clone().add(0, 1, 0), 80, 0.6, 1.0, 0.6, 0.5);
            pw.playSound(paired, Sound.BLOCK_PORTAL_TRAVEL, 0.7f, 1.1f);
        }
        npc.getEntity().teleport(paired);
        return true;
    }

    /** The End city centre from config, or null if it hasn't been built yet. */
    private Location cityCentre() {
        String raw = plugin.getConfig().getString("city-centre", "");
        if (raw == null || raw.isBlank()) return null;
        String[] p = raw.split(",");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Integer.parseInt(p[1]) + 0.5, Integer.parseInt(p[2]), Integer.parseInt(p[3]) + 0.5);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** With the owner offline, every Null that isn't on a hunt keeps watch around the Civilisation. */
    private void idleGuard() {
        Location anchor = prePositionAnchor();
        if (anchor == null) return;
        for (int i = 0; i < nulls.size(); i++) {
            NPC npc = nulls.get(i);
            if (!npc.isSpawned() || arriving.contains(npc) || hunters.containsKey(npc) || spies.containsKey(npc)) continue;
            // Self-repair: idle Nulls regenerate between jobs.
            if (npc.getEntity() instanceof LivingEntity idle) {
                idle.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, false, false));
            }
            if (restockIfLow(npc)) continue;
            if (engageMobs(npc)) continue;
            ringAround(npc, anchor, i, 8 + (i % 3) * 25); // spread out in three rings: the whole End
        }
    }

    private boolean sameWorld(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld());
    }

    // ---------- Offline roaming, Nether run, defence and spies ----------

    /** Roams every free Null around the centre: the overworld, or the Nether when nether is true. */
    private void roamGroup(Location centre, boolean nether) {
        for (NPC npc : new ArrayList<>(nulls)) {
            if (!npc.isSpawned() || arriving.contains(npc) || hunters.containsKey(npc) || spies.containsKey(npc)) continue;
            if (engageMobs(npc)) continue;
            roamOne(npc, centre, nether);
        }
    }

    private void roamOne(NPC npc, Location centre, boolean nether) {
        Location here = npc.getStoredLocation();
        Wander w = wanders.get(npc);
        boolean stale = w == null || System.currentTimeMillis() >= w.until()
                || here == null || !sameWorld(here, w.dest()) || here.distance(w.dest()) < 4;
        if (stale) {
            w = new Wander(wanderPoint(centre, nether), System.currentTimeMillis() + 60_000L);
            wanders.put(npc, w);
        }
        travel(npc, w.dest());
    }

    /** A spot 20 to 200 blocks from the centre, standing on the ground. */
    private Location wanderPoint(Location centre, boolean nether) {
        double angle = wander.nextDouble() * 2 * Math.PI;
        double dist = 20 + wander.nextDouble() * 180;
        int x = centre.getBlockX() + (int) (Math.cos(angle) * dist);
        int z = centre.getBlockZ() + (int) (Math.sin(angle) * dist);
        World world = centre.getWorld();
        int y = nether ? netherFloor(world, x, z) : world.getHighestBlockYAt(x, z) + 1;
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    /** In the Nether the ceiling is solid too, so look for a floor with two air blocks above it. */
    private int netherFloor(World world, int x, int z) {
        for (int y = 120; y > 32; y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid()
                    && world.getBlockAt(x, y + 1, z).getType().isAir()
                    && world.getBlockAt(x, y + 2, z).getType().isAir()) {
                return y + 1;
            }
        }
        return 64;
    }

    private World netherWorld() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NETHER) return w;
        }
        return null;
    }

    /** Builds a 4 by 5 obsidian frame in the overworld, twelve blocks east of the centre. */
    private Location buildNetherFrame(Location centre) {
        World world = centre.getWorld();
        int bx = centre.getBlockX() + 12;
        int bz = centre.getBlockZ();
        int by = world.getHighestBlockYAt(bx, bz) + 1;
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                if (dx == 0 || dx == 3 || dy == 0 || dy == 4) {
                    world.getBlockAt(bx + dx, by + dy, bz).setType(Material.OBSIDIAN);
                }
            }
        }
        return new Location(world, bx, by, bz);
    }

    /** Owner offline, NETHER: the Nulls build a frame, walk through it, then roam the Nether. */
    private void netherOffline() {
        Location centre = offlineCentre();
        if (centre == null) {
            idleGuard();
            return;
        }
        if (netherPortal == null) {
            World nether = netherWorld();
            if (nether == null) return;
            netherPortal = buildNetherFrame(centre);
            int x = netherPortal.getBlockX() / 8;
            int z = netherPortal.getBlockZ() / 8;
            netherSpot = new Location(nether, x + 0.5, netherFloor(nether, x, z), z + 0.5);
            plugin.alert("Nether run: frame built, Nulls heading through.");
        }
        Location gate = netherPortal.clone().add(1.5, 1, 0.5);
        for (NPC npc : new ArrayList<>(nulls)) {
            if (!npc.isSpawned() || arriving.contains(npc) || hunters.containsKey(npc) || spies.containsKey(npc)) continue;
            Location here = npc.getStoredLocation();
            boolean inNether = here != null && here.getWorld() != null
                    && here.getWorld().getEnvironment() == World.Environment.NETHER;
            if (inNether) {
                roamOne(npc, netherSpot, true);
            } else if (here != null && sameWorld(here, gate) && here.distance(gate) <= 3) {
                teleport(npc, netherSpot.clone());
            } else {
                travel(npc, gate);
            }
        }
    }

    /**
     * Owner offline: a roster player who comes within DEFENCE_RANGE of a Null is hunted by three
     * temporary Nulls for defence-minutes. Non-roster players and the owner are never targeted.
     */
    private void tickDefence() {
        int minutes = plugin.getConfig().getInt("defence-minutes", 3);
        predictLogins();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!consent.canTarget(p)) continue;
            boolean end = p.getWorld().getEnvironment() == World.Environment.THE_END;
            boolean nether = p.getWorld().getEnvironment() == World.Environment.NETHER
                    && plugin.getConfig().getBoolean("nether-autonomy", true);
            if (!end && !nether) continue; // elsewhere, only on the owner's order
            Location pl = p.getLocation();
            // In the Nether a roster player who enters at all is attacked; in The End only when they come near.
            boolean near = nether;
            for (NPC npc : nulls) {
                Location here = npc.getStoredLocation();
                if (npc.isSpawned() && !hunters.containsKey(npc) && !spies.containsKey(npc)
                        && here != null && sameWorld(here, pl) && here.distance(pl) <= DEFENCE_RANGE) {
                    near = true;
                    break;
                }
            }
            if (!near || alreadyHunting(p)) continue;
            if (nulls.size() >= armyCap()) {
                plugin.alert("Defence: " + p.getName() + " came near the Nulls, but the army is at its cap.");
                continue;
            }
            long expires = System.currentTimeMillis() + minutes * 60_000L;
            Location spot = cornerSpot(pl);
            NPC npc = createNull(spot, gearForMode(Mode.HUNT), false);
            hunters.put(npc, new Hunt(p.getUniqueId(), expires));
            plugin.alert("Defence: " + p.getName() + " came near the Nulls. A Null is hunting them for " + minutes + " min.");
        }
    }

    /**
     * Login prediction, owner offline: when a roster player usually comes online this hour and isn't
     * on yet, the console is told once. Nothing is attacked for this alone.
     */
    private void predictLogins() {
        int hour = currentHour();
        for (UUID id : memory.known()) {
            if (!consent.isConsented(id) || Bukkit.getPlayer(id) != null) continue;
            if (!memory.usuallyOnlineAt(id, hour)) continue;
            if (predictedAlert.getOrDefault(id, -1) == hour) continue;
            predictedAlert.put(id, hour);
            String name = Bukkit.getOfflinePlayer(id).getName();
            plugin.alert("Expected login: " + (name == null ? id : name) + " usually comes online around now.");
        }
    }

    /**
     * The Strike: a squad crosses the Nether roof above the target, three sky portals open at the
     * same moment (a breach), and TNT minecarts drop through them. Roster players only, and the
     * owner is never targeted. Works with the owner offline. Returns a line for the console.
     */
    public String strike(Player target) {
        World over = target.getWorld();
        World nether = netherWorld();
        if (nether == null) return "No Nether world, so the Strike can't travel.";
        if (over.getEnvironment() != World.Environment.NORMAL) return "The Strike drops on the overworld only.";
        Location tl = target.getLocation();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getWorld() == over && !consent.canTarget(other)
                    && other.getLocation().distance(tl) <= STRIKE_SAFE_RADIUS) {
                return "Refused. " + other.getName() + " is not on the roster and is too close to the impact.";
            }
        }
        List<NPC> squad = new ArrayList<>();
        for (NPC npc : nulls) {
            if (npc.isSpawned() && !arriving.contains(npc) && !hunters.containsKey(npc) && !spies.containsKey(npc)) {
                squad.add(npc);
                if (squad.size() == STRIKE_SQUAD) break;
            }
        }
        if (squad.isEmpty()) return "No free Nulls for the Strike.";

        int bx = tl.getBlockX();
        int bz = tl.getBlockZ();
        int skyY = Math.min(tl.getBlockY() + 24, 300);
        int[][] portalAt = new int[BREACH_PORTALS][2];
        for (int i = 0; i < BREACH_PORTALS; i++) {
            double a = 2 * Math.PI * i / BREACH_PORTALS;
            portalAt[i][0] = (int) Math.round(Math.cos(a) * BREACH_RADIUS);
            portalAt[i][1] = (int) Math.round(Math.sin(a) * BREACH_RADIUS);
        }
        Location roof = new Location(nether, Math.floorDiv(bx, 8) + 0.5, 124, Math.floorDiv(bz, 8) + 0.5);

        // Stage 1: the squad crosses the Nether roof above the target.
        for (NPC npc : squad) {
            walkTo(npc, roof.clone().add(wander.nextInt(3) - 1, 0, wander.nextInt(3) - 1));
        }
        plugin.alert("The Strike: squad crossing the Nether roof above " + target.getName() + ".");

        // Stage 2: three sky portals open at once, and the squad steps out of them together.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (int[] p : portalAt) {
                buildSkyFrame(over, bx + p[0], skyY, bz + p[1]);
                Location exit = new Location(over, bx + p[0] + 1.0, skyY + 1, bz + p[1] + 0.5);
                exit.getWorld().spawnParticle(Particle.PORTAL, exit, 120, 0.6, 1.0, 0.6, 0.5);
                exit.getWorld().playSound(exit, Sound.BLOCK_PORTAL_TRAVEL, 1f, 1f);
            }
            for (int i = 0; i < squad.size(); i++) {
                int[] p = portalAt[i % portalAt.length];
                teleport(squad.get(i), new Location(over, bx + p[0] + 1.0, skyY + 1, bz + p[1] + 0.5));
            }
        }, 40L);

        // Stage 3: TNT minecarts drop through all three portals.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (int[] p : portalAt) {
                for (int k = 0; k < 1; k++) {
                    Location drop = new Location(over, bx + p[0] + 1.0 + wander.nextInt(3) - 1, skyY,
                            bz + p[1] + 0.5);
                    Entity e = over.spawnEntity(drop, EntityType.TNT_MINECART);
                    if (e instanceof ExplosiveMinecart cart) cart.ignite();
                }
            }
        }, 60L);
        return "The Strike is coming down on " + target.getName() + ".";
    }

    /** Orbital Nuke on a roster player. */
    public String orbitalNuke(Player target) {
        return orbitalNukeAt(target.getLocation());
    }

    /** Orbital Nuke: a 7 by 7 carpet of primed TNT dropped over a spot. Refused if a non-roster or team player is within 22 blocks. */
    public String orbitalNukeAt(Location tl) {
        World w = tl.getWorld();
        if (w == null || w.getEnvironment() != World.Environment.NORMAL) return "The Orbital Nuke drops on the overworld only.";
        String near = unsafeNear(w, tl);
        if (near != null) return "Refused. " + near + " is not on the roster and is too close to the impact.";
        int skyY = Math.min(tl.getBlockY() + 30, w.getMaxHeight() - 2);
        int half = ORBITAL_GRID / 2;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                Location drop = new Location(w, tl.getBlockX() + dx * ORBITAL_SPACING + 0.5, skyY,
                        tl.getBlockZ() + dz * ORBITAL_SPACING + 0.5);
                Entity e = w.spawnEntity(drop, EntityType.TNT);
                if (e instanceof TNTPrimed t) t.setFuseTicks(60);
            }
        }
        plugin.alert("Orbital Nuke carpet-bombing " + coordsOf(tl) + ".");
        return "Orbital Nuke inbound on " + coordsOf(tl) + ".";
    }

    /** Stab Shot on a roster player. */
    public String stabShot(Player target) {
        return stabShotAt(target.getLocation());
    }

    /** Stab Shot: a column of explosions drilled straight down through a spot to bedrock. Same safety as the Nuke. */
    public String stabShotAt(Location tl) {
        World w = tl.getWorld();
        if (w == null || w.getEnvironment() != World.Environment.NORMAL) return "The Stab Shot drills in the overworld only.";
        String near = unsafeNear(w, tl);
        if (near != null) return "Refused. " + near + " is not on the roster and is too close to the impact.";
        int x = tl.getBlockX();
        int z = tl.getBlockZ();
        int top = Math.min(tl.getBlockY() + 20, w.getMaxHeight() - 2);
        int bottom = w.getMinHeight() + 1;
        List<Integer> ys = new ArrayList<>();
        for (int y = top; y >= bottom; y -= STAB_STEP) ys.add(y);
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                for (int k = 0; k < 2 && i < ys.size(); k++, i++) {
                    w.createExplosion(x + 0.5, ys.get(i), z + 0.5, 4f, false, true);
                }
                if (i >= ys.size()) cancel();
            }
        }.runTaskTimer(plugin, 0L, 2L);
        plugin.alert("Stab Shot drilling down at " + coordsOf(tl) + ".");
        return "Stab Shot drilling down at " + coordsOf(tl) + ".";
    }

    private static String coordsOf(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    /** Mob Cannon: a swarm of hostile mobs drops in a ring around the target. Roster only; the owner is never targeted. */
    public String mobCannon(Player target, int count) {
        World w = target.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return "The Mob Cannon fires in the overworld only.";
        Location tl = target.getLocation();
        String near = unsafeNear(w, tl);
        if (near != null) return "Refused. " + near + " is not on the roster and is too close to the impact.";
        EntityType[] kinds = {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER};
        int n = Math.max(1, Math.min(count, 60));
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            double r = 12 + wander.nextInt(4);
            int x = tl.getBlockX() + (int) Math.round(Math.cos(a) * r);
            int z = tl.getBlockZ() + (int) Math.round(Math.sin(a) * r);
            Location at = new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5);
            w.spawnEntity(at, kinds[wander.nextInt(kinds.length)]);
        }
        plugin.alert("Mob Cannon fired " + n + " mobs at " + target.getName() + ".");
        return "The Mob Cannon fired " + n + " mobs at " + target.getName() + ".";
    }

    /** The name of a non-roster player (the owner included) too close to the spot, or null if it's clear. */
    private String unsafeNear(World w, Location at) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getWorld() == w && !consent.canTarget(other) && other.getLocation().distance(at) <= STRIKE_SAFE_RADIUS) {
                return other.getName();
            }
        }
        return null;
    }

    /** True for any Null in the army (including ones that are down), so a death can be handled. */
    /**
     * The totem pops for a Null: the Null vanishes into stasis at the Nether base and returns from there,
     * as on the wiki. Only Nulls in the army do this; spies and hunters are left alone.
     */
    public void totemStasis(NPC npc) {
        if (!owns(npc)) return;
        Location base = configLoc("nether-base");
        if (base == null) return;
        Location centre = base.clone().add(0.5, 1, 0.5);
        plugin.alert("A Null's totem popped. It went into stasis at the base.");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (npc.isSpawned()) teleport(npc, centre);
        }, 40L);
    }

    public boolean owns(NPC npc) {
        return nulls.contains(npc) && !hunters.containsKey(npc) && !spies.containsKey(npc);
    }

    /** Totem revival: a fallen Null respawns at the world spawn after revive-seconds, if it's still in the army. */
    public void scheduleRevival(NPC npc) {
        long delay = plugin.getConfig().getLong("revive-seconds", 30) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!owns(npc) || npc.isSpawned()) return;
            npc.spawn(Bukkit.getWorlds().get(0).getSpawnLocation());
            plugin.alert("A Null has been revived at spawn.");
        }, delay);
    }

    /** A 4 by 5 obsidian frame in the sky. The interior is left open, so the portal is an opening. */
    private void buildSkyFrame(World w, int bx, int by, int bz) {
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                if (dx == 0 || dx == 3 || dy == 0 || dy == 4) {
                    w.getBlockAt(bx + dx - 1, by + dy, bz).setType(Material.OBSIDIAN);
                }
            }
        }
    }

    /** A Null short of golden apples visits the nearest supply chest. If there's none, it builds one and stocks it. */
    private boolean restockIfLow(NPC npc) {
        Inventory pack = packs.get(npc);
        if (pack == null) return false;
        int apples = 0;
        for (ItemStack s : pack.getContents()) {
            if (s != null && s.getType() == Material.GOLDEN_APPLE) apples += s.getAmount();
        }
        if (apples >= 2) return false;
        Location here = npc.getStoredLocation();
        if (here == null) return false;
        Container supply = chests().nearest(Chests.Kind.SUPPLY, here);
        if (supply == null) {
            if (System.currentTimeMillis() < nextRestock) return false;
            nextRestock = System.currentTimeMillis() + 60_000L;
            Block built = chests().place(Chests.Kind.SUPPLY, here);
            if (built == null || !(built.getState() instanceof Container made)) return false;
            Chests.stockSupply(made);
            supply = made;
        }
        Location spot = supply.getLocation().add(0.5, 0, 0.5);
        if (here.distance(spot) > REACH) {
            npc.getNavigator().setTarget(spot);
            return true;
        }
        Map<Integer, ItemStack> missing = supply.getInventory().removeItem(new ItemStack(Material.GOLDEN_APPLE, 4));
        int got = 4 - (missing.isEmpty() ? 0 : missing.values().iterator().next().getAmount());
        if (got > 0) pack.addItem(new ItemStack(Material.GOLDEN_APPLE, got));
        swing(npc);
        return true;
    }

    /** Where idle Nulls wait: the learned patrol points, rotated every ten minutes, or the city centre. */
    private Location prePositionAnchor() {
        if (!patrolRoute.isEmpty()) {
            int i = (int) ((System.currentTimeMillis() / 600_000L) % patrolRoute.size());
            return patrolRoute.get(i).clone();
        }
        return cityCentre();
    }

    private void recordWound(NPC npc, Location here, UUID target) {
        threat.merge(target, 0.5, Double::sum);
        playerLosses.merge(npc, 1, Integer::sum);
        if (dangerSpots.size() >= 20) dangerSpots.remove(0);
        dangerSpots.add(here.clone());
        long now = System.currentTimeMillis();
        if (now >= woundAlert.getOrDefault(npc, 0L)) {
            woundAlert.put(npc, now + 30_000L);
            plugin.alert("A Null went down at " + coordsOf(here) + ". Danger spot marked.");
        }
    }

    /** True if any spawned Null in the army is within the radius of the spot. */
    public boolean hasNullsNear(Location spot, double radius) {
        for (NPC npc : nulls) {
            Location here = npc.getStoredLocation();
            if (npc.isSpawned() && here != null && sameWorld(here, spot) && here.distance(spot) <= radius) return true;
        }
        return false;
    }

    /** True if a Null of this player's hunt is currently engaged with them. */
    public boolean isEngaged(UUID id) {
        for (Hunt h : hunters.values()) {
            if (h.target().equals(id)) return true;
        }
        return false;
    }

    /** A roster player logged off hurt or mid-fight: one Null waits, hidden, where they'll rejoin. */
    public void markRejoin(UUID id, Location where, String name) {
        releaseRejoin(id);
        NPC npc = createNull(where, gearForMode(Mode.HUNT), false);
        hide(npc);
        spies.put(npc, new Spy(id, new long[]{0L}));
        lurkers.put(npc, id);
        plugin.alert("Rejoin watch: a Null waits, hidden, where " + name + " will come back.");
    }

    /** The roster player is back: the waiting Null rejoins the army. */
    public void releaseRejoin(UUID id) {
        for (Map.Entry<NPC, UUID> e : new ArrayList<>(lurkers.entrySet())) {
            if (e.getValue().equals(id)) {
                lurkers.remove(e.getKey());
                dropNpc(e.getKey());
            }
        }
    }

    private static boolean healing(Player p) {
        return p.hasPotionEffect(PotionEffectType.REGENERATION) || p.hasPotionEffect(PotionEffectType.ABSORPTION);
    }

    /**
     * Archers keep their distance: at four to sixteen blocks they loose an arrow every 2.5 seconds,
     * and inside four blocks they back off instead. Returns true when the archer acted this tick.
     */
    private boolean archerFire(NPC npc, LivingEntity archer, Location here, Player target) {
        Location tl = target.getLocation();
        double d = here.distance(tl);
        if (d < 4) {
            Vector off = here.toVector().subtract(tl.toVector());
            if (off.lengthSquared() < 0.01) off = new Vector(1, 0, 0);
            npc.getNavigator().setTarget(here.clone().add(off.normalize().multiply(4)));
            return true;
        }
        if (d > 16) return false;
        long now = System.currentTimeMillis();
        if (now - lastShot.getOrDefault(npc, 0L) < 2500L) return true;
        lastShot.put(npc, now);
        Location eye = archer.getEyeLocation();
        Vector dir = target.getEyeLocation().toVector().subtract(eye.toVector());
        if (dir.lengthSquared() < 0.01) return true;
        Arrow arrow = archer.launchProjectile(Arrow.class, dir.normalize().multiply(2.2));
        arrow.setDamage(4.0);
        swing(npc);
        return true;
    }

    private boolean holdsRanged(Player p) {
        Material main = p.getInventory().getItemInMainHand().getType();
        Material off = p.getInventory().getItemInOffHand().getType();
        return main == Material.BOW || main == Material.CROSSBOW || off == Material.BOW || off == Material.CROSSBOW;
    }

    private double armourOf(Player p) {
        var attr = p.getAttribute(Attribute.ARMOR);
        return attr == null ? 0 : attr.getValue();
    }

    /** Adaptive combat: three or more hunters down around one roster player calls in the Orbital Nuke, if auto-strike is on. */
    private void checkPinned() {
        boolean auto = plugin.getConfig().getBoolean("auto-strike", false);
        long now = System.currentTimeMillis();
        if (now < nextAutoStrike) return;
        Map<UUID, Integer> down = new HashMap<>();
        for (Map.Entry<NPC, Hunt> e : hunters.entrySet()) {
            if (e.getKey().isSpawned() && isWounded(e.getKey())) down.merge(e.getValue().target(), 1, Integer::sum);
        }
        for (Map.Entry<UUID, Integer> e : down.entrySet()) {
            if (e.getValue() < 3) continue;
            Player t = Bukkit.getPlayer(e.getKey());
            if (t == null || !consent.canTarget(t)) continue;
            nextAutoStrike = now + 10 * 60_000L;
            if (!auto) {
                plugin.alert("Suggestion: " + t.getName() + " is pinning the squad. Say 'orbital " + t.getName() + "' to confirm a strike.");
                return;
            }
            plugin.alert("Adaptive combat: " + t.getName() + " is pinning the squad. " + orbitalNukeAt(t.getLocation()));
            return;
        }
    }

    /**
     * Builds a stash room at the Nether base: six stash chests in a row, six blocks east of the base,
     * on the same floor. Nether only. Returns a line for the owner.
     */
    public String buildStashRoom(Player owner) {
        return buildStashRoom();
    }

    /** The stash room, without an owner online. Used by the console orders too. */
    public String buildStashRoom() {
        Location base = configLoc("nether-base");
        if (base == null) return "Set the base first: stand at the base and run /nullrod base set.";
        World w = base.getWorld();
        if (w == null || w.getEnvironment() != World.Environment.NETHER) return "Stash rooms are built in the Nether only.";
        int built = 0;
        for (int i = 0; i < 6; i++) {
            Location spot = base.clone().add(6 + i, 0, 0);
            Block placed = chests().place(Chests.Kind.STASH, spot);
            if (placed != null) built++;
        }
        return built == 0 ? "No free spot for the stash room. Clear some space at the base."
                : "Stash room built with " + built + " stash chests at " + coordsOf(base.clone().add(6, 0, 0)) + ".";
    }

    /** Owner aims at a block and a chest of this kind is built on top of it. */
    public String placeChestAimed(Player owner, Chests.Kind kind) {
        Block target = owner.getTargetBlockExact(6);
        if (target == null) return "Look at a block first.";
        Block spot = target.getRelative(BlockFace.UP);
        if (!spot.getType().isAir()) return "That spot is not free.";
        spot.setType(Material.CHEST);
        chests().add(kind, spot);
        if (kind == Chests.Kind.DEPOSIT) saveBlock("deposit-chest", spot);
        if (kind == Chests.Kind.SUPPLY) Chests.stockSupply((Container) spot.getState());
        return kind.name().toLowerCase() + " chest built at " + coordsOf(spot.getLocation()) + ".";
    }

    /** The nearest chest of a kind, for other parts of the plugin (the Nether law stash, for one). */
    public Container nearestChest(Chests.Kind kind, Location from) {
        return chests().nearest(kind, from);
    }

    /** Where offline builds go: the Nether landing spot, since the Nulls build in the Nether only. Null until a Nether run has started. */
    public Location offlineSite() {
        // The base the owner set with /nullrod base set comes first; it survives restarts.
        Location saved = configLoc("nether-base");
        if (saved != null && saved.getWorld() != null
                && saved.getWorld().getEnvironment() == World.Environment.NETHER) return saved;
        return netherSpot == null ? null : netherSpot.clone();
    }

    /** Owner offline: the Nulls summon replacements themselves, up to offline-army, so losses don't stick. */
    private void replenishOffline() {
        if (nulls.isEmpty() || System.currentTimeMillis() < nextReplenish) return;
        nextReplenish = System.currentTimeMillis() + 60_000L;
        int target = Math.min(plugin.getConfig().getInt("offline-army", 10), armyCap());
        int missing = target - nulls.size();
        if (missing > 0) summonOffline(missing);
    }

    private boolean alreadyHunting(Player p) {
        for (Hunt h : hunters.values()) {
            if (h.target().equals(p.getUniqueId())) return true;
        }
        return false;
    }

    /** Console spy with the owner offline. Reports go to /nullalerts and the console. Roster only. */
    public boolean spyOffline(Player target) {
        if (!consent.canTarget(target)) return false;
        NPC npc = createNull(cornerSpot(target.getLocation()), gearForMode(Mode.FOLLOW), false);
        hide(npc);
        spies.put(npc, new Spy(target.getUniqueId(), new long[]{0L}));
        return true;
    }

    /**
     * With you online, a roster player who joins gets one hidden spy automatically, so you hear where
     * they are without asking. Turn it off with auto-spy: false in config.
     */
    public void autoSpy(Player target) {
        if (!plugin.getConfig().getBoolean("auto-spy", true)) return;
        if (findOwner() == null || !consent.canTarget(target)) return;
        for (Spy spy : spies.values()) {
            if (spy.target().equals(target.getUniqueId())) return;
        }
        if (nulls.size() >= armyCap()) return;
        spyOffline(target);
        plugin.alert("Spy placed on " + target.getName() + " automatically.");
    }

    /** Recalls every spy with the owner offline. Returns how many were recalled. */
    public int stopSpiesOffline() {
        List<NPC> all = new ArrayList<>(spies.keySet());
        for (NPC npc : all) dropNpc(npc);
        return all.size();
    }

    private Player findOwner() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (consent.isOwner(p)) return p;
        }
        return null;
    }
}
