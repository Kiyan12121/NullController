package com.daboss69yt.nullplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.command.Command;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Nether is owned. Roster players who take loot out of it get, in order:
 * strike 1 a warning on screen, strike 2 an escort trail back to the owner, strike 3 hunted down
 * (everything taken from the Nether drops on death), strike 4 arrested: the Nulls take the whole
 * inventory and armour, and the prisoner mines in the prison for the rest of the time. Ancient debris
 * is always taken by the Nulls. Escape is leaving the Nether or the prison radius. Roster only.
 */
public final class NetherLaw implements Listener, CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("status", "prison", "arrest", "release", "clear", "collect");
    private static final List<String> DEFAULT_BLOCKED = List.of(
            "home", "homes", "sethome", "tp", "tpa", "tpahere", "tpaccept", "tpo", "tpohere",
            "spawn", "warp", "back", "ec", "enderchest");
    private static final List<InventoryType> CONTAINERS =
            List.of(InventoryType.CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX);

    /** Per-player state. Only ever held for roster players. */
    static final class Record {
        String name = "";
        int strikes;
        boolean prisoner;
        boolean stripped;
        long lastStrike;
        long lastRation;
        long lastEscape;
        int debris;
        int mined;
        final Map<String, Integer> ledger = new LinkedHashMap<>(); // loot taken from the Nether, by material
        final List<ItemStack> stash = new ArrayList<>();           // taken by the Nulls, collected by the owner
    }

    private final NullControllerPlugin plugin;
    private final ConsentManager consent;
    private final NullArmy army;
    private final File file;
    private final Map<UUID, Record> records = new HashMap<>();
    private final Map<UUID, Map<Material, Integer>> snapshots = new HashMap<>();
    private final Map<UUID, BukkitTask> escorts = new HashMap<>();

    public NetherLaw(NullControllerPlugin plugin, ConsentManager consent, NullArmy army) {
        this.plugin = plugin;
        this.consent = consent;
        this.army = army;
        plugin.getDataFolder().mkdirs();
        this.file = new File(plugin.getDataFolder(), "nether.yml");
        load();
    }

    /** Starts the prison watch: rations and escape checks. */
    public void start() {
        new BukkitRunnable() {
            @Override
            public void run() {
                watchPrisoners();
            }
        }.runTaskTimer(plugin, 40L, 40L);
    }

    // ---------- Config ----------

    private long warnCooldownMs() {
        return plugin.getConfig().getLong("nether-warning-minutes", 10) * 60_000L;
    }

    private long huntMinutes() {
        return plugin.getConfig().getLong("nether-hunt-minutes", 5);
    }

    private long rationMs() {
        return plugin.getConfig().getLong("prison-food-minutes", 5) * 60_000L;
    }

    private double prisonRadius() {
        return plugin.getConfig().getDouble("prison-radius", 25);
    }

    private List<String> blockedCommands() {
        List<String> raw = plugin.getConfig().getStringList("prisoner-blocked-commands");
        return raw.isEmpty() ? DEFAULT_BLOCKED : raw;
    }

    /** A "world,x,y,z" location from config, or null if unset or unreadable. */
    private Location configLoc(String key) {
        String raw = plugin.getConfig().getString(key, "");
        if (raw == null || raw.isBlank()) return null;
        String[] p = raw.split(",");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0].trim());
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean inPrison(Location loc) {
        Location centre = configLoc("prison");
        if (centre == null || loc.getWorld() == null || !loc.getWorld().equals(centre.getWorld())) return false;
        return loc.distance(centre) <= prisonRadius();
    }

    // ---------- Persistence ----------

    private Record record(UUID id, String name) {
        Record r = records.computeIfAbsent(id, k -> new Record());
        if (name != null && !name.isEmpty()) r.name = name;
        return r;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        var root = yml.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                var s = root.getConfigurationSection(key);
                if (s == null) continue;
                Record r = record(id, s.getString("name", ""));
                r.strikes = s.getInt("strikes");
                r.prisoner = s.getBoolean("prisoner");
                r.stripped = s.getBoolean("stripped");
                r.lastStrike = s.getLong("last-strike");
                r.lastRation = s.getLong("last-ration");
                r.lastEscape = s.getLong("last-escape");
                r.debris = s.getInt("debris");
                r.mined = s.getInt("mined");
                var led = s.getConfigurationSection("ledger");
                if (led != null) for (String m : led.getKeys(false)) r.ledger.put(m, led.getInt(m));
                List<?> raw = s.getList("stash");
                if (raw != null) for (Object o : raw) if (o instanceof ItemStack is) r.stash.add(is);
            } catch (IllegalArgumentException ignored) {
                // skip malformed keys
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Record> e : records.entrySet()) {
            String base = "players." + e.getKey();
            Record r = e.getValue();
            yml.set(base + ".name", r.name);
            yml.set(base + ".strikes", r.strikes);
            yml.set(base + ".prisoner", r.prisoner);
            yml.set(base + ".stripped", r.stripped);
            yml.set(base + ".last-strike", r.lastStrike);
            yml.set(base + ".last-ration", r.lastRation);
            yml.set(base + ".last-escape", r.lastEscape);
            yml.set(base + ".debris", r.debris);
            yml.set(base + ".mined", r.mined);
            for (Map.Entry<String, Integer> l : r.ledger.entrySet()) {
                yml.set(base + ".ledger." + l.getKey(), l.getValue());
            }
            yml.set(base + ".stash", r.stash);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not save nether.yml: " + ex.getMessage());
        }
    }

    // ---------- Loot tracking ----------

    private void addLoot(Record r, Material m, int amount) {
        if (amount <= 0) return;
        r.ledger.merge(m.name(), amount, Integer::sum);
    }

    private void addStash(Record r, ItemStack s) {
        ItemStack add = s.clone();
        for (ItemStack ex : r.stash) {
            if (ex.isSimilar(add) && ex.getAmount() + add.getAmount() <= ex.getMaxStackSize()) {
                ex.setAmount(ex.getAmount() + add.getAmount());
                return;
            }
        }
        r.stash.add(add);
    }

    /** The ledger as stacks no bigger than a full stack. */
    private List<ItemStack> ledgerStacks(Record r) {
        List<ItemStack> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : r.ledger.entrySet()) {
            Material m = Material.matchMaterial(e.getKey());
            if (m == null) continue;
            int left = e.getValue();
            while (left > 0) {
                int n = Math.min(left, m.getMaxStackSize());
                out.add(new ItemStack(m, n));
                left -= n;
            }
        }
        return out;
    }

    private boolean isOffender(Player p) {
        return consent.canTarget(p);
    }

    private static Map<Material, Integer> count(Player p) {
        Map<Material, Integer> m = new HashMap<>();
        for (ItemStack s : p.getInventory().getContents()) {
            if (s != null && !s.getType().isAir()) m.merge(s.getType(), s.getAmount(), Integer::sum);
        }
        return m;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        Player p = event.getPlayer();
        if (!isOffender(p)) return;
        Record r = record(p.getUniqueId(), p.getName());
        if (r.prisoner) return;
        if (p.getWorld().getEnvironment() != World.Environment.NETHER) return;
        ItemStack stack = event.getItem().getItemStack();
        addLoot(r, stack.getType(), stack.getAmount());
        offence(p, r);
    }

    /** Chests, barrels and shulker boxes in the Nether: whatever a roster player gains while one is open is loot. */
    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        if (!isOffender(p) || p.getWorld().getEnvironment() != World.Environment.NETHER) return;
        if (!CONTAINERS.contains(event.getInventory().getType())) return;
        if (record(p.getUniqueId(), p.getName()).prisoner) return;
        snapshots.put(p.getUniqueId(), count(p));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        Map<Material, Integer> before = snapshots.remove(p.getUniqueId());
        if (before == null || !isOffender(p)) return;
        Record r = record(p.getUniqueId(), p.getName());
        Map<Material, Integer> after = count(p);
        boolean gained = false;
        for (Map.Entry<Material, Integer> e : after.entrySet()) {
            int gain = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (gain > 0) {
                addLoot(r, e.getKey(), gain);
                gained = true;
            }
        }
        if (gained) offence(p, r);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (!isOffender(p)) return;
        Record r = record(p.getUniqueId(), p.getName());
        Block block = event.getBlock();

        if (block.getType() == Material.ANCIENT_DEBRIS) {
            // The Nulls always take ancient debris. It never reaches the player.
            event.setDropItems(false);
            storeDebris(r);
            r.debris++;
            nullPickup(block);
            p.sendMessage(ChatColor.GRAY + "A Null takes the ancient debris."); 
            notifyOwner(ChatColor.DARK_PURPLE + "[Nether] " + p.getName() + " dug up ancient debris ("
                    + r.debris + " total). Stashed for you.");
            if (!r.prisoner && p.getWorld().getEnvironment() == World.Environment.NETHER) offence(p, r);
            save();
            return;
        }

        if (r.prisoner) {
            if (inPrison(block.getLocation())) {
                r.mined++;
                if (r.mined % 25 == 0) save();
            }
            return;
        }
        if (p.getWorld().getEnvironment() != World.Environment.NETHER) return;
        for (ItemStack drop : block.getDrops(p.getInventory().getItemInMainHand(), p)) {
            addLoot(r, drop.getType(), drop.getAmount());
        }
        offence(p, r);
    }

    /** Debris goes into the deposit chest when one is set, otherwise to the owner's stash. */
    private void storeDebris(Record r) {
        ItemStack debris = new ItemStack(Material.ANCIENT_DEBRIS);
        Container c = depositChest();
        if (c != null && c.getInventory().addItem(debris).isEmpty()) return;
        addStash(r, debris);
    }

    private Container depositChest() {
        String raw = plugin.getConfig().getString("deposit-chest", "");
        if (raw == null || raw.isBlank()) return null;
        String[] p = raw.split(",");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0].trim());
        if (w == null) return null;
        try {
            Block b = w.getBlockAt(Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
            return b.getState() instanceof Container c ? c : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * The debris drops where it was dug, with a pickup delay long enough that no player can grab it.
     * A Null then collects it: the item vanishes with a pickup flash and sound.
     */
    private void nullPickup(Block block) {
        org.bukkit.Location spot = block.getLocation().add(0.5, 0.5, 0.5);
        org.bukkit.entity.Item item = block.getWorld().dropItem(spot, new ItemStack(Material.ANCIENT_DEBRIS));
        item.setPickupDelay(Integer.MAX_VALUE);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (item.isValid()) {
                item.remove();
                spot.getWorld().spawnParticle(Particle.ENCHANT, spot, 30, 0.4, 0.4, 0.4, 0.5);
                spot.getWorld().playSound(spot, Sound.ENTITY_ITEM_PICKUP, 1f, 0.8f);
            }
        }, 30L);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        Record r = records.get(p.getUniqueId());
        if (r == null || r.ledger.isEmpty()) return;
        // Without keep-inventory the whole inventory is already in the drops. With it, the loot
        // comes out of the inventory and goes into the drops, so every bit of it still falls.
        if (event.getKeepInventory()) {
            for (ItemStack s : ledgerStacks(r)) {
                p.getInventory().removeItem(s.clone());
                event.getDrops().add(s);
            }
        }
        r.ledger.clear();
        save();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        snapshots.remove(event.getPlayer().getUniqueId());
        BukkitTask t = escorts.remove(event.getPlayer().getUniqueId());
        if (t != null) t.cancel();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Record r = records.get(p.getUniqueId());
        if (r == null || !r.prisoner || r.stripped) return;
        // Arrested while offline: the Nulls take the gear as soon as the prisoner logs in.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !r.stripped) {
                strip(p, r);
                warn(p, ChatColor.DARK_RED + "PRISONER", "Go to the prison and mine.");
            }
        }, 20L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        Record r = records.get(p.getUniqueId());
        if (r == null || !r.prisoner || configLoc("prison") == null) return;
        if (!inPrison(p.getLocation())) escape(p, r);
    }

    // ---------- Strikes ----------

    private void offence(Player p, Record r) {
        long now = System.currentTimeMillis();
        if (now - r.lastStrike < warnCooldownMs()) return; // one strike per visit, not per block
        r.lastStrike = now;
        r.strikes++;
        save();
        notifyOwner(ChatColor.DARK_PURPLE + "[Nether] " + p.getName() + " took loot. Strike " + r.strikes + ".");

        // First offence: one warning and the loot they took is taken back. Second: the Nulls kill them.
        // Third and after: prison.
        switch (r.strikes) {
            case 1 -> {
                warn(p, ChatColor.RED + "WARNING", "This Nether is owned. What you took is being taken back.");
                confiscate(p, r);
            }
            case 2 -> {
                warn(p, ChatColor.DARK_RED + "THE NULLS KILL YOU", "You took from the Nether again.");
                notifyOwner(ChatColor.DARK_RED + "[Nether] " + p.getName() + " was killed by the Nulls.");
                p.setHealth(0.0);
            }
            default -> arrest(p, r);
        }
    }

    /** Takes back the loot in the ledger from the player's inventory and puts it in the stash. */
    private void confiscate(Player p, Record r) {
        for (Map.Entry<String, Integer> e : new ArrayList<>(r.ledger.entrySet())) {
            Material m = Material.matchMaterial(e.getKey());
            if (m == null || e.getValue() <= 0) continue;
            Map<Integer, ItemStack> left = p.getInventory().removeItem(new ItemStack(m, e.getValue()));
            int leftover = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            int taken = e.getValue() - leftover;
            if (taken > 0) addStash(r, new ItemStack(m, taken));
        }
        r.ledger.clear();
        save();
        notifyOwner(ChatColor.DARK_PURPLE + "[Nether] Loot taken back from " + p.getName() + " and stashed.");
    }

    /** Plays the warning sound and writes the line to chat. The Nulls don't use big screen text. */
    private void warn(Player p, String title, String subtitle) {
        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
        p.sendMessage(title + ChatColor.GRAY + " " + subtitle);
    }

    /**
     * Strike 2: a red particle trail runs from the player towards the Commander, with the distance
     * shown on the action bar. It ends when they're within six blocks, or after three minutes.
     */
    private void escort(Player p, Location target) {
        BukkitTask old = escorts.remove(p.getUniqueId());
        if (old != null) old.cancel();
        if (target == null) {
            p.sendMessage(ChatColor.RED + "The Commander's location is unknown. Find the Commander.");
            return;
        }
        final int[] elapsed = {0};
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                elapsed[0] += 10;
                if (!p.isOnline()) {
                    escorts.remove(p.getUniqueId());
                    cancel();
                    return;
                }
                if (elapsed[0] > 3600) {
                    p.sendMessage(ChatColor.RED + "You did not come to me.");
                    escorts.remove(p.getUniqueId());
                    cancel();
                    return;
                }
                Location here = p.getLocation();
                if (here.getWorld() == null || !here.getWorld().equals(target.getWorld())) {
                    p.sendActionBar(Component.text("Wrong world. Come to me.", NamedTextColor.RED));
                    return;
                }
                double dist = here.distance(target);
                if (dist <= 6) {
                    p.sendActionBar(Component.text("Good. Stay close.", NamedTextColor.GREEN));
                    escorts.remove(p.getUniqueId());
                    cancel();
                    return;
                }
                p.sendActionBar(Component.text("Come to me: " + (int) dist + " blocks", NamedTextColor.RED));
                Vector dir = target.toVector().subtract(here.toVector()).normalize();
                for (int i = 1; i <= 15; i++) {
                    Location dot = here.clone().add(dir.clone().multiply(i * 2.0)).add(0, 1, 0);
                    if (dot.distance(here) > dist) break;
                    here.getWorld().spawnParticle(Particle.DUST, dot, 3, 0.1, 0.1, 0.1, 0,
                            new Particle.DustOptions(Color.RED, 1.2f));
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
        escorts.put(p.getUniqueId(), task);
    }

    /** The Commander: the owner if they're online, otherwise the city centre, otherwise the prison. */
    private Location commanderTarget() {
        Player o = owner();
        if (o != null) return o.getLocation();
        Location city = configLoc("city-centre");
        return city != null ? city : configLoc("prison");
    }

    /** Strips the prisoner: everything including armour goes to the stash, and a wooden pickaxe is issued. */
    private void strip(Player p, Record r) {
        Container stashChest = army.nearestChest(Chests.Kind.STASH, p.getLocation());
        for (ItemStack s : p.getInventory().getContents()) {
            if (s == null || s.getType().isAir()) continue;
            if (stashChest == null) {
                addStash(r, s);
                continue;
            }
            var left = stashChest.getInventory().addItem(s);
            for (ItemStack l : left.values()) addStash(r, l);
        }
        p.getInventory().clear();
        p.getInventory().addItem(new ItemStack(Material.DIAMOND_PICKAXE)); // the lowest tool that mines ancient debris
        r.ledger.clear();
        r.stripped = true;
        save();
    }

    private void arrest(Player p, Record r) {
        Location prison = configLoc("prison");
        if (prison == null) {
            notifyOwner(ChatColor.RED + "[Nether] No prison set. Use /nullnether prison set in the Nether.");
            if (p != null) {
                warn(p, ChatColor.DARK_RED + "NO PRISON YET", "The Nulls are coming instead.");
                army.spawnHunters(owner(), p, p.getLocation(), 3, (int) huntMinutes());
            }
            return;
        }
        r.prisoner = true;
        r.stripped = false;
        save();
        if (p != null) {
            strip(p, r);
            warn(p, ChatColor.DARK_RED + "PRISONER", "Go to the prison at " + coords(prison) + " and mine.");
        }
        notifyOwner(ChatColor.DARK_PURPLE + "[Prison] " + (p == null ? "A roster player" : p.getName())
                + " is now a prisoner. Gear stashed.");
    }

    private void escape(Player p, Record r) {
        long now = System.currentTimeMillis();
        if (now - r.lastEscape < 60_000L) return;
        r.lastEscape = now;
        warn(p, ChatColor.DARK_RED + "ESCAPE", "The Nulls are on you.");
        notifyOwner(ChatColor.DARK_RED + "[Prison] " + p.getName() + " escaped. Hunters sent.");
        army.spawnHunters(owner(), p, p.getLocation(), 3, (int) huntMinutes());
        save();
    }

    // ---------- Commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player owner = plugin.ownerFor(sender);
        if (owner == null || !consent.isOwner(owner)) {
            sender.sendMessage(ChatColor.RED + "Access denied.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(owner);
            case "prison" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("set")) {
                    Location l = owner.getLocation();
                    plugin.getConfig().set("prison", l.getWorld().getName() + "," + l.getBlockX() + ","
                            + l.getBlockY() + "," + l.getBlockZ());
                    plugin.saveConfig();
                    owner.sendMessage(ChatColor.DARK_PURPLE + "Prison set at " + coords(l) + " in "
                            + l.getWorld().getName() + ".");
                } else {
                    owner.sendMessage(ChatColor.GRAY + "Usage: /nullnether prison set");
                }
            }
            case "arrest", "release", "clear", "collect" -> {
                if (args.length < 2) {
                    owner.sendMessage(ChatColor.GRAY + "Usage: /nullnether " + sub + " <player>");
                    return true;
                }
                UUID id = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
                if (!consent.isConsented(id)) {
                    owner.sendMessage(ChatColor.RED + args[1] + " is not on the roster.");
                    return true;
                }
                Record r = record(id, args[1]);
                switch (sub) {
                    case "arrest" -> {
                        arrest(Bukkit.getPlayer(id), r);
                        owner.sendMessage(ChatColor.DARK_PURPLE + args[1] + " arrested.");
                    }
                    case "release" -> {
                        r.prisoner = false;
                        r.strikes = 0;
                        save();
                        owner.sendMessage(ChatColor.DARK_PURPLE + args[1] + " released. Clean slate. Gear is still stashed.");
                    }
                    case "collect" -> collect(owner, r, args[1]);
                    default -> {
                        r.strikes = 0;
                        r.ledger.clear();
                        save();
                        owner.sendMessage(ChatColor.DARK_PURPLE + args[1] + " cleared.");
                    }
                }
            }
            default -> owner.sendMessage(ChatColor.GRAY + "Usage: /nullnether status | prison set | arrest|release|clear|collect <player>");
        }
        return true;
    }

    /** Hands the stash to the owner. Anything that doesn't fit drops at the owner's feet. */
    private void collect(Player owner, Record r, String name) {
        if (r.stash.isEmpty()) {
            owner.sendMessage(ChatColor.GRAY + "Nothing stashed for " + name + ".");
            return;
        }
        int items = 0;
        for (ItemStack s : r.stash) {
            items += s.getAmount();
            for (ItemStack left : owner.getInventory().addItem(s.clone()).values()) {
                owner.getWorld().dropItemNaturally(owner.getLocation(), left);
            }
        }
        r.stash.clear();
        save();
        owner.sendMessage(ChatColor.DARK_PURPLE + "Collected " + items + " items from " + name + ".");
    }

    private void status(Player owner) {
        owner.sendMessage(ChatColor.DARK_PURPLE + "--- Nether law ---");
        Location prison = configLoc("prison");
        owner.sendMessage(ChatColor.GRAY + "Prison: " + (prison == null ? "not set" : coords(prison)));
        if (records.isEmpty()) {
            owner.sendMessage(ChatColor.GRAY + "No roster player has been logged yet.");
            return;
        }
        for (Map.Entry<UUID, Record> e : records.entrySet()) {
            Record r = e.getValue();
            int loot = r.ledger.values().stream().mapToInt(Integer::intValue).sum();
            owner.sendMessage(ChatColor.WHITE + (r.name.isEmpty() ? e.getKey().toString() : r.name)
                    + ChatColor.GRAY + " strikes " + r.strikes
                    + (r.prisoner ? ChatColor.DARK_RED + " PRISONER" : "")
                    + ChatColor.GRAY + " loot held " + loot
                    + " stashed " + r.stash.size() + " stacks"
                    + (r.prisoner ? " mined " + r.mined + " debris " + r.debris : ""));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && !args[0].equalsIgnoreCase("prison") && !args[0].equalsIgnoreCase("status")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }

    // ---------- Prisoner rules ----------

    @EventHandler(ignoreCancelled = true)
    public void onCommandUse(PlayerCommandPreprocessEvent event) {
        Record r = records.get(event.getPlayer().getUniqueId());
        if (r == null || !r.prisoner) return;
        String root = event.getMessage().substring(1).split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);
        if (blockedCommands().contains(root)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.DARK_RED + "Prisoners do not get that command.");
        }
    }

    /** Rations every few minutes, and escape checks for prisoners who are online. */
    private void watchPrisoners() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Record> e : records.entrySet()) {
            Record r = e.getValue();
            if (!r.prisoner || !r.stripped) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;

            if (now - r.lastRation >= rationMs()) {
                r.lastRation = now;
                p.setFoodLevel(20);
                p.setSaturation(6f);
                for (ItemStack left : p.getInventory().addItem(new ItemStack(Material.BREAD, 4)).values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), left);
                }
                p.sendMessage(ChatColor.GRAY + "Rations issued.");
                save();
            }
            if (configLoc("prison") != null && !inPrison(p.getLocation())) escape(p, r);
        }
    }

    // ---------- Helpers ----------

    private Player owner() {
        return Bukkit.getPlayerExact(ConsentManager.OWNER_NAME);
    }

    private void notifyOwner(String message) {
        Player o = owner();
        if (o != null) o.sendMessage(message);
        plugin.alert(ChatColor.stripColor(message));
        plugin.getLogger().info(ChatColor.stripColor(message));
    }

    private static String coords(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }
}
