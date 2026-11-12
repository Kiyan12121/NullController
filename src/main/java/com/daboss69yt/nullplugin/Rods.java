package com.daboss69yt.nullplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Owner-only rods, cast like fishing rods:
 * Ambush Rod: portals open around the roster player nearest the hook and Nulls step out.
 * Stasis Rod: a hooked roster player is pulled to the Nether base and frozen there.
 * TNT Rod: a landing spot gets a portal flash and four TNT drop out of the sky onto it.
 * Every rod refuses to act if a non-roster player is close, and only the owner can use them.
 */
public final class Rods implements Listener, CommandExecutor, TabCompleter {

    static final String AMBUSH = ChatColor.DARK_RED + "Ambush Rod";
    static final String STASIS = ChatColor.AQUA + "Stasis Rod";
    static final String TNT = ChatColor.GOLD + "TNT Rod";
    private static final double TNT_SAFE_RADIUS = 12;
    private static final List<String> KINDS = List.of("ambush", "stasis", "tnt", "base");

    private final NullControllerPlugin plugin;
    private final ConsentManager consent;
    private final NullArmy army;
    private final Random rng = new Random();

    public Rods(NullControllerPlugin plugin, ConsentManager consent, NullArmy army) {
        this.plugin = plugin;
        this.consent = consent;
        this.army = army;
    }

    // ---------- Commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player owner) || !consent.isOwner(owner)) {
            sender.sendMessage(ChatColor.RED + "Access denied.");
            return true;
        }
        if (args.length == 0) {
            owner.sendMessage(ChatColor.GRAY + "Usage: /nullrod ambush | stasis | tnt | base set");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "ambush" -> give(owner, AMBUSH, "Portals open around a roster player. Nulls step out.");
            case "stasis" -> give(owner, STASIS, "Hook a roster player: they are pulled to the Nether base and frozen.");
            case "tnt" -> give(owner, TNT, "Land the hook: portal flash, then TNT drops from the sky.");
            case "base" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("set")) {
                    var l = owner.getLocation();
                    plugin.getConfig().set("nether-base", l.getWorld().getName() + "," + l.getBlockX() + ","
                            + l.getBlockY() + "," + l.getBlockZ());
                    plugin.saveConfig();
                    owner.sendMessage(ChatColor.DARK_PURPLE + "Nether base set at " + l.getBlockX() + ", "
                            + l.getBlockY() + ", " + l.getBlockZ() + ".");
                } else {
                    owner.sendMessage(ChatColor.GRAY + "Usage: /nullrod base set");
                }
            }
            default -> owner.sendMessage(ChatColor.GRAY + "Usage: /nullrod ambush | stasis | tnt | base set");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return KINDS.stream().filter(k -> k.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("base")) return List.of("set");
        return List.of();
    }

    private void give(Player owner, String name, String lore) {
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = rod.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(ChatColor.GRAY + lore));
        rod.setItemMeta(meta);
        owner.getInventory().addItem(rod).values().forEach(
                left -> owner.getWorld().dropItemNaturally(owner.getLocation(), left));
        owner.sendMessage(ChatColor.DARK_PURPLE + name + ChatColor.GRAY + " given. " + lore);
    }

    // ---------- Casting ----------

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        Player p = event.getPlayer();
        if (!consent.isOwner(p)) return;
        if (event.getState() != PlayerFishEvent.State.IN_GROUND
                && event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY) return;
        ItemStack held = p.getInventory().getItemInMainHand();
        ItemMeta meta = held.getItemMeta();
        if (held.getType() != Material.FISHING_ROD || meta == null || meta.getDisplayName() == null) return;
        String name = meta.getDisplayName();

        if (name.equals(AMBUSH)) ambush(p, event);
        else if (name.equals(STASIS)) stasis(p, event);
        else if (name.equals(TNT)) tnt(p, event);
    }

    private void ambush(Player owner, PlayerFishEvent event) {
        Location hook = event.getHook().getLocation();
        Player target = nearestRoster(hook, 6);
        if (target == null) {
            owner.sendMessage(ChatColor.GRAY + "No roster player near the hook.");
            return;
        }
        army.ambush(owner, target, 3, (int) plugin.getConfig().getLong("ambush-minutes", 3));
        owner.sendMessage(ChatColor.DARK_PURPLE + "Portals open around " + target.getName() + ".");
    }

    private void stasis(Player owner, PlayerFishEvent event) {
        if (!(event.getCaught() instanceof Player target)) return;
        if (!consent.canTarget(target)) {
            owner.sendMessage(ChatColor.RED + "Roster only. " + target.getName() + " is not on it.");
            return;
        }
        Location base = configLoc("nether-base");
        if (base == null || base.getWorld() == null || !base.getWorld().equals(target.getWorld())) {
            owner.sendMessage(ChatColor.GRAY + "Set the base in this world first: /nullrod base set");
            return;
        }
        Vector pull = base.toVector().subtract(target.getLocation().toVector()).normalize().multiply(1.6).setY(0.4);
        target.setVelocity(pull);
        target.sendTitle(ChatColor.AQUA + "STASIS", ChatColor.GRAY + "You are held at the base.", 10, 60, 20);
        target.playSound(target.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.7f, 0.6f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (target.isOnline()) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 20, 255, false, false));
            }
        }, 15L);
        owner.sendMessage(ChatColor.DARK_PURPLE + target.getName() + " is pulled to the base and frozen.");
    }

    private void tnt(Player owner, PlayerFishEvent event) {
        Location land = event.getHook().getLocation();
        World w = land.getWorld();
        if (w == null) return;
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getWorld().equals(w) && !consent.isOwner(other) && !consent.canTarget(other)
                    && other.getLocation().distance(land) <= TNT_SAFE_RADIUS) {
                owner.sendMessage(ChatColor.RED + "Refused. " + other.getName()
                        + " is not on the roster and is too close to the impact.");
                return;
            }
        }
        portalFlash(land);
        Location base = configLoc("nether-base");
        if (base != null) portalFlash(base);
        for (int i = 0; i < 4; i++) {
            Location drop = land.clone().add(rng.nextInt(7) - 3, 25, rng.nextInt(7) - 3);
            Entity e = w.spawnEntity(drop, EntityType.TNT);
            if (e instanceof TNTPrimed t) t.setFuseTicks(60);
        }
        owner.sendMessage(ChatColor.DARK_PURPLE + "TNT dropping at " + land.getBlockX() + ", "
                + land.getBlockY() + ", " + land.getBlockZ() + ".");
    }

    private void portalFlash(Location loc) {
        World w = loc.getWorld();
        if (w == null) return;
        w.spawnParticle(Particle.PORTAL, loc.clone().add(0, 1, 0), 120, 0.6, 1.0, 0.6, 0.5);
        w.playSound(loc, Sound.BLOCK_PORTAL_TRAVEL, 1f, 0.8f);
    }

    private Player nearestRoster(Location at, double radius) {
        Player best = null;
        double bestDist = radius;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!consent.canTarget(p) || p.getWorld() != at.getWorld()) continue;
            double d = p.getLocation().distance(at);
            if (d <= bestDist) {
                best = p;
                bestDist = d;
            }
        }
        return best;
    }

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
}
