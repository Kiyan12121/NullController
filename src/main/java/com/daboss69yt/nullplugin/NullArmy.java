package com.daboss69yt.nullplugin;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.GameModeTrait;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
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
import org.bukkit.entity.Monster;
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

    public enum Mode { GUARD, FOLLOW, HUNT, GOTO, STAY, MINE, LUMBER, FARM, PATROL, FORGE }

    enum WorkKind { MINE, LUMBER, FARM }

    static final int HARD_CAP = 200;
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
    private final Set<NPC> depositing = new HashSet<>();

    private final Deque<Block> workTargets = new ArrayDeque<>();
    private final Map<NPC, Block> claimed = new HashMap<>();
    private int cycle;
    private int lastScanCycle = -SCAN_EVERY_CYCLES;

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
    private long nextAlarm;

    private boolean swinging;
    private BukkitRunnable swingTask;

    public NullArmy(NullControllerPlugin plugin, ConsentManager consent) {
        this.plugin = plugin;
        this.consent = consent;
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

    /** Spawns a timed hunting group against one roster player. The caller checks consent first. */
    public boolean spawnHunters(Player owner, Player target, Location center, int count, int minutes) {
        if (nulls.size() + count > armyCap()) {
            owner.sendMessage(ChatColor.RED + "Not enough room under the army cap (" + armyCap() + ").");
            return false;
        }
        long expires = System.currentTimeMillis() + minutes * 60_000L;
        for (int i = 0; i < count; i++) {
            Location loc = center.clone().add(rng.nextInt(5) - 2, 0, rng.nextInt(5) - 2);
            NPC npc = createNull(loc, gearForMode(Mode.HUNT));
            hunters.put(npc, new Hunt(target.getUniqueId(), expires));
        }
        return true;
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
        createNull(formationPoint(owner, nulls.size()), gearForMode(mode));
    }

    private NPC createNull(Location loc, ItemStack gear) {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, randomName());
        npc.setProtected(false); // Citizens NPCs are invulnerable by default; Nulls must be hittable
        npc.getOrAddTrait(GameModeTrait.class).setGameMode(GameMode.SURVIVAL); // creative players can't be hurt
        setHeldItem(npc, gear);
        equipArmour(npc);
        npc.spawn(loc);
        applySkin(npc);
        nulls.add(npc);
        if (leader == null) leader = npc;
        recomputeRanks();
        packs.put(npc, Bukkit.createInventory(null, PACK_SIZE, "Null Pack"));
        return npc;
    }

    /** Black skin from the configured URL when set, otherwise the skin name from config. Needs the NPC spawned. */
    private void applySkin(NPC npc) {
        String url = plugin.getConfig().getString("null-skin-url", "");
        if (url != null && !url.isBlank()) {
            var console = Bukkit.getConsoleSender();
            CitizensAPI.getDefaultNPCSelector().select(console, npc);
            Bukkit.dispatchCommand(console, "npc skin --url " + url);
            CitizensAPI.getDefaultNPCSelector().deselect(console);
        } else {
            npc.getOrAddTrait(SkinTrait.class).setSkinName(plugin.getConfig().getString("null-skin", "NULL"));
        }
    }

    private void applyGear(Mode m) {
        ItemStack gear = gearForMode(m);
        for (NPC npc : nulls) {
            setHeldItem(npc, gear);
        }
    }

    /** Every Null wears full enchanted netherite armour. */
    private void equipArmour(NPC npc) {
        Equipment eq = npc.getOrAddTrait(Equipment.class);
        eq.set(Equipment.EquipmentSlot.HELMET, enchantedNetherite(Material.NETHERITE_HELMET));
        eq.set(Equipment.EquipmentSlot.CHESTPLATE, enchantedNetherite(Material.NETHERITE_CHESTPLATE));
        eq.set(Equipment.EquipmentSlot.LEGGINGS, enchantedNetherite(Material.NETHERITE_LEGGINGS));
        eq.set(Equipment.EquipmentSlot.BOOTS, enchantedNetherite(Material.NETHERITE_BOOTS));
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
        return kind == null ? new ItemStack(Material.NETHERITE_SWORD) : gearForKind(kind);
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
        for (int i = 0; i < nulls.size(); i++) {
            char rank = i == 0 ? 'R' : i <= 2 ? 'A' : i <= 6 ? 'I' : 'D';
            Character promoted = promotions.get(nulls.get(i));
            if (promoted != null && rankLevel(promoted) > rankLevel(rank)) rank = promoted;
            ranks.put(nulls.get(i), rank);
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
        owner.sendTitle(ChatColor.DARK_RED + "ALARM", ChatColor.GRAY + "" + threat + " hostiles. Rally.", 5, 40, 10);
        owner.sendMessage(ChatColor.DARK_RED + "[NULL] Alarm: " + threat + " hostiles near you. The army is rallying.");
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
        double angle = (2 * Math.PI / 8.0) * index;
        double radius = 3 + (index / 8) * 1.5;
        return owner.getLocation().clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
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
        Player owner = findOwner();
        if (owner == null) {
            idleGuard();
            return;
        }
        cycle++;
        expireHunters();
        checkAlarm(owner);

        double leash = plugin.getConfig().getDouble("leash-distance", 15);
        double huntRange = plugin.getConfig().getDouble("hunt-range", 100);

        WorkKind kind = kindOf(mode);
        if (kind != null && workTargets.isEmpty() && cycle - lastScanCycle >= SCAN_EVERY_CYCLES) {
            refillTargets(owner, kind);
            lastScanCycle = cycle;
        }

        for (NPC npc : new ArrayList<>(nulls)) {
            if (!npc.isSpawned() || arriving.contains(npc)) continue;
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
                    if (!engageMobs(npc)) followOwner(npc, owner, index, leash);
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
            teleport(npc, formationPoint(owner, index));
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
                double score = d + p.getHealth() * 0.75; // near and weak first
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
        if (!consent.canTarget(target)) {
            dropNpc(npc); // consent revoked: stop immediately
            return;
        }
        Location here = npc.getStoredLocation();
        Location tl = target.getLocation();
        if (here == null || !sameWorld(here, tl) || here.distance(tl) > TELEPORT_OVER) {
            teleport(npc, tl);
        } else if (here.distance(tl) > ATTACK_REACH) {
            npc.getNavigator().setTarget(tl);
        } else {
            attack(npc, target);
        }
    }

    /** Finds the nearest hostile mob and fights it. Returns false if there's nothing to fight. */
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
    private void fieldHeal(NPC npc) {
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
        workTargets.clear();
        World w = owner.getWorld();
        Location c = owner.getLocation();
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
        Location anchor = cityCentre();
        if (anchor == null) return;
        for (int i = 0; i < nulls.size(); i++) {
            NPC npc = nulls.get(i);
            if (!npc.isSpawned() || arriving.contains(npc) || hunters.containsKey(npc)) continue;
            if (engageMobs(npc)) continue;
            ringAround(npc, anchor, i, 8 + (i % 3) * 25); // spread out in three rings: the whole End
        }
    }

    private boolean sameWorld(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld());
    }

    private Player findOwner() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (consent.isOwner(p)) return p;
        }
        return null;
    }
}
