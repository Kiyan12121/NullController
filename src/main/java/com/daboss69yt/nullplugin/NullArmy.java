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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
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

    public enum Mode { GUARD, FOLLOW, HUNT, GOTO, STAY, MINE, LUMBER, FARM, PATROL, FORGE, EXPLORE, NETHER }

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
    private static final double WOUNDED_HP = 6.0;
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
    private final Map<NPC, Integer> lastAttack = new HashMap<>();
    private final Map<NPC, Location> wanderGoals = new HashMap<>();
    private final Map<NPC, Integer> wanderUntil = new HashMap<>();
    private NPC leader;
    private final Map<NPC, Character> ranks = new HashMap<>();
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
        removeAll();
        memory.save();
    }

    // ---------- Commands ----------

    public void summon(Player owner, int requested) {
        long now = System.currentTimeMillis();
        if (now < cooldownUntil) {
            long minutesLeft = (cooldownUntil - now) / 60_000L + 1;
            owner.sendMessage(ChatColor.RED + "The army is regrouping. Available again in about " + minutesLeft + " min.");
            return;
        }
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
        if (System.currentTimeMillis() < cooldownUntil) return -1;
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
        int count = nulls.size();
        removeAll();
        owner.sendMessage(ChatColor.DARK_PURPLE + "Dismissed " + count + " Nulls.");
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
            Location loc = cornerSpot(target.getLocation());
            NPC npc = createNull(loc, gearForMode(Mode.HUNT), false);
            hunters.put(npc, new Hunt(target.getUniqueId(), expires));
        }
        return true;
    }

    /** Ambush: portals open around one roster player, and Nulls step out of them after a moment. */
    public void ambush(Player owner, Player target, int count, int minutes) {
        if (nulls.size() + count > armyCap()) {
            if (owner != null) owner.sendMessage(ChatColor.RED + "Not enough room under the army cap (" + armyCap() + ").");
            return;
        }
        long expires = System.currentTimeMillis() + minutes * 60_000L;
        for (int i = 0; i < count; i++) {
            Location spot = cornerSpot(target.getLocation());
            World w = spot.getWorld();
            if (w == null) continue;
            w.spawnParticle(Particle.PORTAL, spot.clone().add(0, 1, 0), 120, 0.6, 1.0, 0.6, 0.5);
            w.playSound(spot, Sound.BLOCK_PORTAL_TRAVEL, 0.8f, 1.2f);
            new BukkitRunnable() {
                @Override
                public void run() {
                    NPC npc = createNull(spot, gearForMode(Mode.HUNT), false);
                    hunters.put(npc, new Hunt(target.getUniqueId(), expires));
                    w.spawnParticle(Particle.PORTAL, spot.clone().add(0, 1, 0), 60, 0.4, 1.0, 0.4, 0.3);
                }
            }.runTaskLater(plugin, 30L);
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
        npc.setProtected(false); // Citizens NPCs are invulnerable by default; Nulls must be hittable
        npc.getOrAddTrait(GameModeTrait.class).setGameMode(GameMode.SURVIVAL); // creative players can't be hurt
        setHeldItem(npc, gear);
        equipArmour(npc);
        npc.spawn(loc);
        applySkin(npc);
        nulls.add(npc);
        if (becomesLeader) leader = npc;
        recomputeRanks();
        Inventory pack = Bukkit.createInventory(null, PACK_SIZE, "Null Pack");
        pack.addItem(new ItemStack(Material.GOLDEN_APPLE, 4), new ItemStack(Material.COOKED_BEEF, 16));
        packs.put(npc, pack);
        return npc;
    }

    /** Black skin from the configured URL when set, otherwise the skin name from config. Needs the NPC spawned. */
    private void applySkin(NPC npc) {
        String url = plugin.getConfig().getString("null-skin-url", "");
        if (url != null && !url.isBlank()) {
            setSkinUrl(npc, url);
            // Skin lookups fail when many Nulls spawn at once (rate limits), leaving some on the default
            // skin. Re-apply a few times so every Null ends up with the black skin. Re-applying is harmless.
            for (long delay : new long[]{60L, 200L, 600L}) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (npc.isSpawned()) setSkinUrl(npc, url);
                }, delay);
            }
        } else {
            npc.getOrAddTrait(SkinTrait.class).setSkinName(plugin.getConfig().getString("null-skin", "NULL"));
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
            setHeldItem(npc, gear);
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
        String trimMaterial = switch (rank) {
            case 'R' -> "netherite";
            case 'A' -> "gold";
            case 'I' -> "iron";
            default -> "copper";
        };
        Equipment eq = npc.getOrAddTrait(Equipment.class);
        eq.set(Equipment.EquipmentSlot.HELMET, rankPiece(Material.NETHERITE_HELMET, protection, pattern, trimMaterial));
        if (npc == leader) {
            eq.set(Equipment.EquipmentSlot.OFF_HAND, new ItemStack(Material.TOTEM_OF_UNDYING));
            ItemStack elytra = new ItemStack(Material.ELYTRA);
            elytra.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            eq.set(Equipment.EquipmentSlot.CHESTPLATE, elytra);
        } else {
            eq.set(Equipment.EquipmentSlot.CHESTPLATE, rankPiece(Material.NETHERITE_CHESTPLATE, protection, pattern, trimMaterial));
        }
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
    public void showRanks(Player owner) {
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

    private boolean isWounded(NPC npc) {
        Entity e = npc.getEntity();
        return e instanceof LivingEntity living && living.getHealth() < WOUNDED_HP;
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
        expireHunters();
        checkAlarm(owner);
        tickSpies();
        checkPinned();

        double leash = plugin.getConfig().getDouble("leash-distance", 15);
        double huntRange = plugin.getConfig().getDouble("hunt-range", 100);

        WorkKind kind = kindOf(mode);
        if (kind != null && workTargets.isEmpty() && cycle - lastScanCycle >= SCAN_EVERY_CYCLES) {
            refillTargets(owner, kind);
            lastScanCycle = cycle;
        }

        for (NPC npc : new ArrayList<>(nulls)) {
            if (!npc.isSpawned() || arriving.contains(npc)) continue;
            if (spies.containsKey(npc)) continue;
            Hunt hunt = hunters.get(npc);
            if (hunt != null) {
                huntTick(npc, hunt);
                continue;
            }
            if (isWounded(npc)) {
                fieldHeal(npc);
                followOwner(npc, owner, nulls.indexOf(npc), leash);
                continue;
            }
            if (npc == leader) {
                // The commander sticks with the owner and only goes out when a hunt is ordered.
                // He defends the owner: a mob or roster player within six blocks of the owner is engaged.
                if (defendOwner(npc, owner)) continue;
                followOwner(npc, owner, nulls.indexOf(npc), leash);
                continue;
            }
            int index = nulls.indexOf(npc);
            if (cycle % 4 == 0 && (mode == Mode.GUARD || mode == Mode.PATROL)) {
                swing(npc); // arms moving on watch
            }
            switch (mode) {
                case GUARD -> {
                    if (engageMobs(npc)) continue;
                    if (cageGuard != null) guardPoint(npc, index);
                    else if (index % 2 == 1 && isNight(owner)) ringAround(npc, owner.getLocation(), index, 10);
                    else followOwner(npc, owner, index, leash);
                }
                case FOLLOW -> {
                    if (engageMobs(npc)) continue;
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
            teleport(npc, target);
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
                teleport(npc, tl);
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
                double score = d + p.getHealth() * 0.75 - armourOf(p) * 0.5 - threat.getOrDefault(p.getUniqueId(), 0.0) * 0.5;
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

    private void huntTick(NPC npc, Hunt hunt) {
        Player target = Bukkit.getPlayer(hunt.target());
        if (target == null) return; // target offline: wait
        if (npc.getEntity() instanceof LivingEntity fighter) {
            // Fight buffs, refreshed every tick while the hunt lasts.
            char rank = ranks.getOrDefault(npc, 'D');
            int strength = rank == 'R' ? 2 : rank == 'A' ? 1 : 0;
            fighter.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 100, strength, false, false));
            fighter.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0, false, false));
            fighter.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, rank == 'R' ? 1 : 0, false, false));
        }
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
    private boolean defendOwner(NPC npc, Player owner) {
        Location here = npc.getStoredLocation();
        Location ol = owner.getLocation();
        if (here == null || !sameWorld(here, ol)) return false;
        LivingEntity best = null;
        double bestDist = 6;
        for (Entity e : ol.getWorld().getNearbyEntities(ol, 6, 6, 6)) {
            if (e == owner || e.getUniqueId().equals(npc.getEntity() == null ? null : npc.getEntity().getUniqueId())) continue;
            LivingEntity cand = null;
            if (e instanceof Player p && consent.canTarget(p)) cand = p;
            else if (e instanceof Monster m && !NETHER_PEACE.contains(m.getType())) cand = m;
            if (cand == null) continue;
            double d = ol.distance(cand.getLocation());
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
        double range = plugin.getConfig().getDouble("combat-range", 8) * (System.currentTimeMillis() < alarmUntil ? 2 : 1);
        Monster best = null;
        double bestDist = range;
        double bestHealth = Double.MAX_VALUE;
        int bestRank = Integer.MAX_VALUE;
        for (Entity e : here.getWorld().getNearbyEntities(here, range, range, range)) {
            if (!(e instanceof Monster m)) continue;
            if (NETHER_PEACE.contains(m.getType())) continue;
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
        eatApple(npc);
        Entity e = npc.getEntity();
        if (e instanceof LivingEntity living) {
            living.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 1, false, false));
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
            teleport(npc, spot);
        } else if (here.distance(spot) > REACH) {
            npc.getNavigator().setTarget(spot);
        }
    }

    private void attack(NPC npc, LivingEntity target) {
        Integer last = lastAttack.get(npc);
        if (last != null && cycle - last < ATTACK_COOLDOWN_CYCLES) return;
        lastAttack.put(npc, cycle);
        double damage = plugin.getConfig().getDouble("combat-damage", 8) * rankScale(npc);
        if (target instanceof Player p) {
            if (p.isBlocking()) damage *= 1.5; // a Null hit breaks through a raised shield
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
            teleport(npc, spot);
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
            teleport(npc, spot);
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
            teleport(npc, wp);
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
            teleport(npc, spot.clone().add(0, 1, 0));
        } else if (here.distance(spot) > REACH) {
            npc.getNavigator().setTarget(spot);
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
            teleport(npc, spot.clone().add(0, 1, 0));
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
                teleport(npc, spot);
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
            teleport(npc, spot.clone().add(0, 1, 0));
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
        if (npc.getEntity() instanceof LivingEntity living) living.setInvisible(true);
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
            if (here == null || !sameWorld(here, tl) || here.distance(tl) > 40) {
                appearNear(npc, target);
            } else if (here.distance(tl) > 14) {
                npc.getNavigator().setTarget(tl);
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
    private void appearNear(NPC npc, Player target) {
        Location spot = cornerSpot(target.getLocation());
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
            teleport(npc, roof.clone().add(wander.nextInt(3) - 1, 0, wander.nextInt(3) - 1));
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
        if (npc.getEntity() instanceof LivingEntity living) living.setInvisible(true);
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
        if (npc.getEntity() instanceof LivingEntity living) living.setInvisible(true);
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
