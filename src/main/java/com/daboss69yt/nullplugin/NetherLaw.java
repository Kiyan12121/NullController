package com.daboss69yt.nullplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Nether is owned. Roster players who take loot out of it get, in order:
 * strike 1 a warning, strike 2 told to come to the owner, strike 3 hunted down (and everything they took
 * from the Nether drops on death), strike 4 arrested and put to work in the prison. Escapees are hunted.
 * Only roster players are ever touched. Works while the owner is offline.
 */
public final class NetherLaw implements Listener, CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("status", "prison", "arrest", "release", "clear");
    private static final List<String> DEFAULT_BLOCKED = List.of(
            "home", "homes", "sethome", "tp", "tpa", "tpahere", "tpaccept", "tpo", "tpohere",
            "spawn", "warp", "back", "ec", "enderchest");

    /** Per-player state. Only ever held for roster players. */
    static final class Record {
        String name = "";
        int strikes;
        boolean prisoner;
        long lastStrike;
        long lastRation;
        long lastEscape;
        int debris;
        int mined;
        final Map<String, Integer> ledger = new LinkedHashMap<>(); // loot taken from the Nether, by material
    }

    private final NullControllerPlugin plugin;
    private final ConsentManager consent;
    private final NullArmy army;
    private final File file;
    private final Map<UUID, Record> records = new HashMap<>();

    public NetherLaw(NullControllerPlugin plugin, ConsentManager consent, NullArmy army) {
        this.plugin = plugin;
        this.consent = consent;
        this.army = army;
        plugin.getDataFolder().mkdirs();
        this.file = new File(plugin.getDataFolder(), "nether.yml");
        load();
    }

    /** Starts the prison watch: rations, escape checks. Runs every two seconds. */
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

    /** The prison centre set with /nullnether prison set, or null if none yet. */
    private Location prisonCentre() {
        String raw = plugin.getConfig().getString("prison", "");
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
        Location centre = prisonCentre();
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
                r.lastStrike = s.getLong("last-strike");
                r.lastRation = s.getLong("last-ration");
                r.lastEscape = s.getLong("last-escape");
                r.debris = s.getInt("debris");
                r.mined = s.getInt("mined");
                var led = s.getConfigurationSection("ledger");
                if (led != null) for (String m : led.getKeys(false)) r.ledger.put(m, led.getInt(m));
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
            yml.set(base + ".last-strike", r.lastStrike);
            yml.set(base + ".last-ration", r.lastRation);
            yml.set(base + ".last-escape", r.lastEscape);
            yml.set(base + ".debris", r.debris);
            yml.set(base + ".mined", r.mined);
            for (Map.Entry<String, Integer> l : r.ledger.entrySet()) {
                yml.set(base + ".ledger." + l.getKey(), l.getValue());
            }
        }
        try {
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

    /** Gives the ledger back out as stacks no bigger than a full stack. */
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

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (!isOffender(p)) return;
        Record r = record(p.getUniqueId(), p.getName());
        Block block = event.getBlock();

        if (r.prisoner) {
            if (!inPrison(block.getLocation())) return;
            r.mined++;
            if (block.getType() == Material.ANCIENT_DEBRIS) {
                r.debris++;
                notifyOwner(ChatColor.DARK_PURPLE + "[Prison] " + p.getName() + " found ancient debris ("
                        + r.debris + " total).");
            }
            if (r.mined % 25 == 0) save();
            return;
        }
        if (p.getWorld().getEnvironment() != World.Environment.NETHER) return;
        for (ItemStack drop : block.getDrops(p.getInventory().getItemInMainHand(), p)) {
            addLoot(r, drop.getType(), drop.getAmount());
        }
        offence(p, r);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        Record r = records.get(p.getUniqueId());
        if (r == null || r.ledger.isEmpty()) return;
        // Without keep-inventory the whole inventory is already in the drops. With it, the loot
        // is pulled out of the inventory and added to the drops, so every bit of it still falls.
        if (event.getKeepInventory()) {
            for (ItemStack s : ledgerStacks(r)) {
                p.getInventory().removeItem(s.clone());
                event.getDrops().add(s);
            }
        }
        r.ledger.clear();
        save();
    }

    // ---------- Strikes ----------

    private void offence(Player p, Record r) {
        long now = System.currentTimeMillis();
        if (now - r.lastStrike < warnCooldownMs()) return; // one strike per visit, not per block
        r.lastStrike = now;
        r.strikes++;
        save();
        notifyOwner(ChatColor.DARK_PURPLE + "[Nether] " + p.getName() + " took loot. Strike " + r.strikes + ".");

        switch (r.strikes) {
            case 1 -> p.sendMessage(ChatColor.RED + "This Nether is owned. Put back what you took. Warning 1 of 3.");
            case 2 -> p.sendMessage(ChatColor.RED + "Come to me. The Commander is at " + commanderCoords() + ".");
            case 3 -> {
                p.sendMessage(ChatColor.DARK_RED + "The Nulls are coming for you. Everything you took will drop.");
                army.spawnHunters(owner(), p, p.getLocation(), 3, (int) huntMinutes());
            }
            default -> arrest(p, r);
        }
    }

    /** Arrests a roster player. p may be null when the player is offline (owner command). */
    private void arrest(Player p, Record r) {
        Location prison = prisonCentre();
        if (prison == null) {
            notifyOwner(ChatColor.RED + "[Nether] No prison set. Use /nullnether prison set.");
            if (p != null) {
                p.sendMessage(ChatColor.DARK_RED + "The prison is not built yet. The Nulls are coming instead.");
                army.spawnHunters(owner(), p, p.getLocation(), 3, (int) huntMinutes());
            }
            return;
        }
        r.prisoner = true;
        // Confiscated loot goes to the prison floor for the owner to collect.
        for (ItemStack s : ledgerStacks(r)) {
            prison.getWorld().dropItemNaturally(prison, s);
        }
        r.ledger.clear();
        save();
        if (p != null) {
            p.sendMessage(ChatColor.DARK_RED + "You are a prisoner. Report to the prison at "
                    + coords(prison) + " and mine. Everything you find is logged.");
        }
        notifyOwner(ChatColor.DARK_PURPLE + "[Prison] " + p.getName() + " is now a prisoner.");
    }

    /** Owner command: arrest, release, clear. Every roster player's state lives in nether.yml. */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player owner) || !consent.isOwner(owner)) {
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
                    owner.sendMessage(ChatColor.DARK_PURPLE + "Prison set at " + coords(l) + ".");
                } else {
                    owner.sendMessage(ChatColor.GRAY + "Usage: /nullnether prison set");
                }
            }
            case "arrest", "release", "clear" -> {
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
                if (sub.equals("arrest")) {
                    arrest(Bukkit.getPlayer(id), r);
                    owner.sendMessage(ChatColor.DARK_PURPLE + args[1] + " arrested.");
                } else if (sub.equals("release")) {
                    r.prisoner = false;
                    r.strikes = 0;
                    save();
                    owner.sendMessage(ChatColor.DARK_PURPLE + args[1] + " released. Clean slate.");
                } else {
                    r.strikes = 0;
                    r.ledger.clear();
                    save();
                    owner.sendMessage(ChatColor.DARK_PURPLE + args[1] + " cleared.");
                }
            }
            default -> owner.sendMessage(ChatColor.GRAY + "Usage: /nullnether status | prison set | arrest|release|clear <player>");
        }
        return true;
    }

    private void status(Player owner) {
        owner.sendMessage(ChatColor.DARK_PURPLE + "--- Nether law ---");
        owner.sendMessage(ChatColor.GRAY + "Prison: " + (prisonCentre() == null ? "not set" : coords(prisonCentre())));
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
                    + (r.prisoner ? " mined " + r.mined + " debris " + r.debris : ""));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && (args[0].equalsIgnoreCase("arrest") || args[0].equalsIgnoreCase("release")
                || args[0].equalsIgnoreCase("clear"))) {
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

    /** Rations every few minutes, and hunts any prisoner who has left the prison. */
    private void watchPrisoners() {
        Location prison = prisonCentre();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Record> e : records.entrySet()) {
            Record r = e.getValue();
            if (!r.prisoner) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;

            if (now - r.lastRation >= rationMs()) {
                r.lastRation = now;
                p.setFoodLevel(20);
                p.setSaturation(6f);
                for (ItemStack leftover : p.getInventory().addItem(new ItemStack(Material.BREAD, 4)).values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), leftover);
                }
                p.sendMessage(ChatColor.GRAY + "Rations issued.");
                save();
            }

            if (prison == null) continue;
            boolean outside = !inPrison(p.getLocation());
            if (outside && now - r.lastEscape >= 60_000L) {
                r.lastEscape = now;
                p.sendMessage(ChatColor.DARK_RED + "You left the prison. The Nulls are on you.");
                notifyOwner(ChatColor.DARK_RED + "[Prison] " + p.getName() + " escaped. Hunters sent.");
                army.spawnHunters(owner(), p, p.getLocation(), 3, (int) huntMinutes());
                save();
            }
        }
    }

    // ---------- Helpers ----------

    private Player owner() {
        return Bukkit.getPlayerExact(ConsentManager.OWNER_NAME);
    }

    private void notifyOwner(String message) {
        Player o = owner();
        if (o != null) o.sendMessage(message);
        plugin.getLogger().info(ChatColor.stripColor(message));
    }

    private String commanderCoords() {
        Player o = owner();
        if (o != null) return coords(o.getLocation());
        Location c = prisonCentre();
        return c == null ? "unknown" : coords(c);
    }

    private static String coords(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }
}
