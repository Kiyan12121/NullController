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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Owner-only rods, cast like fishing rods. Every rod that hurts a player only ever picks a roster
 * player, and every rod that drops something on the ground refuses if a non-roster player is close.
 *
 *  Ambush    portals open around the roster player and Nulls step out
 *  Stasis    a hooked roster player is pulled to the Nether base and frozen
 *  TNT       four TNT drop from the sky onto the landing spot
 *  Summon    the whole army walks to the hook and guards it
 *  Lightning a bolt strikes the roster player nearest the hook
 *  Launch    the nearest roster player is thrown into the air
 *  Wither    the nearest roster player gets wither, slowness and blindness
 *  Gravity   roster players within eight blocks of the hook are pulled in
 *  Orbital   eight lightning bolts walk over the landing spot
 *  Nuke      three big explosions on the landing spot
 *  Orbital Nuke  a 7 by 7 carpet of TNT on the aim spot
 *  Stab Shot     a column of explosions drilled down to bedrock at the aim spot
 */
public final class Rods implements Listener, CommandExecutor, TabCompleter {

    static final String AMBUSH = ChatColor.DARK_RED + "Ambush Rod";
    static final String STASIS = ChatColor.AQUA + "Stasis Rod";
    static final String TNT = ChatColor.GOLD + "TNT Rod";
    static final String ORBITAL_NUKE = ChatColor.DARK_GRAY + "Orbital Nuke Rod";
    static final String STAB_SHOT = ChatColor.AQUA + "Stab Shot Rod";
    static final String SUMMON = ChatColor.GREEN + "Summon Rod";
    static final String LIGHTNING = ChatColor.YELLOW + "Lightning Rod";
    static final String LAUNCH = ChatColor.LIGHT_PURPLE + "Launch Rod";
    static final String WITHER = ChatColor.DARK_GRAY + "Wither Rod";
    static final String GRAVITY = ChatColor.DARK_AQUA + "Gravity Rod";
    static final String ORBITAL = ChatColor.BLUE + "Orbital Rod";
    static final String NUKE = ChatColor.RED + "Nuke Rod";

    static final String MOB_PREFIX = ChatColor.GREEN + "Mob Rod: ";
    private static final double TNT_SAFE_RADIUS = 12;
    private static final Map<String, String> NAME_BY_KIND = Map.ofEntries(
            Map.entry("ambush", AMBUSH), Map.entry("stasis", STASIS), Map.entry("tnt", TNT), Map.entry("orbitalnuke", ORBITAL_NUKE), Map.entry("stabshot", STAB_SHOT),
            Map.entry("summon", SUMMON), Map.entry("lightning", LIGHTNING), Map.entry("launch", LAUNCH),
            Map.entry("wither", WITHER), Map.entry("gravity", GRAVITY), Map.entry("orbital", ORBITAL),
            Map.entry("nuke", NUKE));
    private static final Map<String, String> KIND_BY_NAME = new HashMap<>();
    static {
        NAME_BY_KIND.forEach((k, v) -> KIND_BY_NAME.put(v, k));
    }

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
        Player owner = plugin.ownerFor(sender);
        if (owner == null || !consent.isOwner(owner)) {
            sender.sendMessage(ChatColor.RED + "Access denied.");
            return true;
        }
        if (args.length == 0) {
            owner.sendMessage(ChatColor.GRAY + "Usage: /nullrod <" + String.join(" | ", NAME_BY_KIND.keySet()) + "> | base set");
            return true;
        }
        String kind = args[0].toLowerCase(Locale.ROOT);
        if (kind.equals("base")) {
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
            return true;
        }
        if (kind.equals("mob")) {
            mobRod(owner, args);
            return true;
        }
        String name = NAME_BY_KIND.get(kind);
        if (name == null) {
            owner.sendMessage(ChatColor.RED + "Unknown rod: " + args[0]);
            return true;
        }
        give(owner, name, lore(kind));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> all = new java.util.ArrayList<>(NAME_BY_KIND.keySet());
            all.add("base");
            return all.stream().filter(k -> k.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("base")) return List.of("set");
        return List.of();
    }

    private static String lore(String kind) {
        return switch (kind) {
            case "ambush" -> "Portals open around a roster player. Nulls step out.";
            case "stasis" -> "Hook a roster player: they are pulled to the Nether base and frozen.";
            case "tnt" -> "Land the hook: portal flash, then TNT drops from the sky.";
            case "orbitalnuke" -> "Aim at a spot: a 7 by 7 carpet of TNT drops on it.";
            case "stabshot" -> "Aim at a spot: a column of explosions drills down to bedrock.";
            case "summon" -> "Land the hook: the whole army walks there and guards it.";
            case "lightning" -> "Land near a roster player: a bolt strikes them.";
            case "launch" -> "Hook near a roster player: they are thrown into the air.";
            case "wither" -> "Land near a roster player: wither, slowness and blindness.";
            case "gravity" -> "Land the hook: roster players within 8 blocks are pulled in.";
            case "orbital" -> "Land the hook: eight lightning bolts walk over the spot.";
            case "nuke" -> "Land the hook: three big explosions on the spot.";
            default -> "";
        };
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
        String display = meta.getDisplayName();
        if (display.startsWith(MOB_PREFIX)) {
            mobCast(p, display.substring(MOB_PREFIX.length()), event.getHook().getLocation());
            return;
        }
        String kind = KIND_BY_NAME.get(display);
        if (kind == null) return;

        Location hook = event.getHook().getLocation();
        switch (kind) {
            case "ambush" -> ambush(p, hook);
            case "stasis" -> stasis(p, event);
            case "tnt" -> tnt(p, hook);
            case "summon" -> {
                army.goTo(hook, p);
                p.sendMessage(ChatColor.DARK_PURPLE + "The army is walking to " + coords(hook) + ".");
            }
            case "lightning" -> strikeRoster(p, hook, 6, false);
            case "launch" -> strikeRoster(p, hook, 6, true);
            case "wither" -> wither(p, hook);
            case "gravity" -> gravity(p, hook);
            case "orbital" -> orbital(p, hook);
            case "nuke" -> nuke(p, hook);
            case "orbitalnuke" -> p.sendMessage(ChatColor.DARK_PURPLE + army.orbitalNukeAt(hook));
            case "stabshot" -> p.sendMessage(ChatColor.DARK_PURPLE + army.stabShotAt(hook));
            default -> { }
        }
    }

    /** /nullrod mob <TYPE>: a rod that summons that mob where it lands. */
    private void mobRod(Player owner, String[] args) {
        if (args.length < 2) {
            owner.sendMessage(ChatColor.GRAY + "Usage: /nullrod mob <ENTITY_TYPE>, e.g. ZOMBIE, WARDEN, CREEPER");
            return;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            owner.sendMessage(ChatColor.RED + "No such mob: " + args[1]);
            return;
        }
        if (!type.isAlive()) {
            owner.sendMessage(ChatColor.RED + type.name() + " is not a mob.");
            return;
        }
        give(owner, MOB_PREFIX + type.name(), "Land it to summon a " + type.name().toLowerCase(Locale.ROOT) + ".");
    }

    private void mobCast(Player owner, String typeName, Location hook) {
        EntityType type;
        try {
            type = EntityType.valueOf(typeName);
        } catch (IllegalArgumentException ex) {
            owner.sendMessage(ChatColor.RED + "This rod has no mob set.");
            return;
        }
        if (hook.getWorld() == null || !safe(owner, hook)) return;
        hook.getWorld().spawnEntity(hook, type);
        owner.sendMessage(ChatColor.DARK_PURPLE + "A " + type.name().toLowerCase(Locale.ROOT) + " appears at " + coords(hook) + ".");
    }

    private void ambush(Player owner, Location hook) {
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

    private void tnt(Player owner, Location land) {
        World w = land.getWorld();
        if (w == null || !safe(owner, land)) return;
        portalFlash(land);
        Location base = configLoc("nether-base");
        if (base != null) portalFlash(base);
        for (int i = 0; i < 4; i++) {
            Location drop = land.clone().add(rng.nextInt(7) - 3, 25, rng.nextInt(7) - 3);
            Entity e = w.spawnEntity(drop, EntityType.TNT);
            if (e instanceof TNTPrimed t) t.setFuseTicks(60);
        }
        owner.sendMessage(ChatColor.DARK_PURPLE + "TNT dropping at " + coords(land) + ".");
    }

    /** Hits the roster player nearest the hook with lightning, or throws them up if launch is set. */
    private void strikeRoster(Player owner, Location hook, double radius, boolean launch) {
        Player target = nearestRoster(hook, radius);
        if (target == null) {
            owner.sendMessage(ChatColor.GRAY + "No roster player near the hook.");
            return;
        }
        if (launch) {
            target.setVelocity(new Vector(0, 1.8, 0));
            owner.sendMessage(ChatColor.DARK_PURPLE + target.getName() + " is thrown.");
        } else {
            target.getWorld().strikeLightning(target.getLocation());
            owner.sendMessage(ChatColor.DARK_PURPLE + "Lightning on " + target.getName() + ".");
        }
    }

    private void wither(Player owner, Location hook) {
        Player target = nearestRoster(hook, 6);
        if (target == null) {
            owner.sendMessage(ChatColor.GRAY + "No roster player near the hook.");
            return;
        }
        target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 100, 1, false, true));
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 2, false, true));
        target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, true));
        owner.sendMessage(ChatColor.DARK_PURPLE + "Wither on " + target.getName() + ".");
    }

    private void gravity(Player owner, Location hook) {
        int pulled = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!consent.canTarget(p) || p.getWorld() != hook.getWorld()) continue;
            if (p.getLocation().distance(hook) > 8) continue;
            p.setVelocity(hook.toVector().subtract(p.getLocation().toVector()).normalize().multiply(1.0));
            pulled++;
        }
        owner.sendMessage(ChatColor.DARK_PURPLE + "Gravity pulled " + pulled + " roster player(s).");
    }

    private void orbital(Player owner, Location land) {
        World w = land.getWorld();
        if (w == null || !safe(owner, land)) return;
        new BukkitRunnable() {
            int n = 0;

            @Override
            public void run() {
                if (n++ >= 8) {
                    cancel();
                    return;
                }
                w.strikeLightning(land.clone().add(rng.nextInt(13) - 6, 0, rng.nextInt(13) - 6));
            }
        }.runTaskTimer(plugin, 0L, 15L);
        owner.sendMessage(ChatColor.DARK_PURPLE + "Orbital strike on " + coords(land) + ".");
    }

    private void nuke(Player owner, Location land) {
        World w = land.getWorld();
        if (w == null || !safe(owner, land)) return;
        new BukkitRunnable() {
            int n = 0;

            @Override
            public void run() {
                if (n++ >= 3) {
                    cancel();
                    return;
                }
                portalFlash(land);
                w.createExplosion(land, 6f, false, true);
            }
        }.runTaskTimer(plugin, 0L, 20L);
        owner.sendMessage(ChatColor.DARK_PURPLE + "Nuke on " + coords(land) + ".");
    }

    /** Refuses if a non-roster, non-owner player is within the safe radius of the impact. */
    private boolean safe(Player owner, Location land) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getWorld() == land.getWorld() && !consent.canTarget(other)
                    && other.getLocation().distance(land) <= TNT_SAFE_RADIUS) {
                owner.sendMessage(ChatColor.RED + "Refused. " + other.getName()
                        + (consent.isTeam(other) ? " is on the team" : " is not on the roster")
                        + " and is too close to the impact.");
                return false;
            }
        }
        return true;
    }

    /**
     * The Strike, console-side: four TNT drop from the sky onto the spot. Works with the owner
     * offline. Refuses if anyone who is not on the roster (the owner included) is too close.
     */
    public String strikeAt(Location land) {
        World w = land.getWorld();
        if (w == null) return "No world there.";
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getWorld() == w && !consent.canTarget(other)
                    && other.getLocation().distance(land) <= TNT_SAFE_RADIUS) {
                return "Refused. " + other.getName() + " is not on the roster and is too close to the impact.";
            }
        }
        portalFlash(land);
        for (int i = 0; i < 4; i++) {
            Location drop = land.clone().add(rng.nextInt(7) - 3, 25, rng.nextInt(7) - 3);
            Entity e = w.spawnEntity(drop, EntityType.TNT);
            if (e instanceof TNTPrimed t) t.setFuseTicks(60);
        }
        return "TNT dropping at " + coords(land) + ".";
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

    private static String coords(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
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
