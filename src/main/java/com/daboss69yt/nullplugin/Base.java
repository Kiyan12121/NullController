package com.daboss69yt.nullplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Dispenser;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.block.data.Lightable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Machines at the Nether base, anchored on the block set with /nullrod base set.
 *  turret       three dispensers loaded with arrows fire at roster players who come close
 *  trap         a 3x3 floor drops away under a roster player standing on the centre, then closes again
 *  alarm        a ring of redstone lamps lights and a bell rings when a roster player is near
 *  drill        mines the 3x3 shaft under the base, one layer every two seconds
 *  debrisdrill mines all ancient debris within twelve blocks, once a minute
 * The drill and the debris drill put what they dig into the deposit chest, or drop it at the base.
 * Nothing fires or opens while a non-roster player is close.
 */
public final class Base implements CommandExecutor, TabCompleter {

    private static final List<String> KINDS = List.of("turret", "trap", "alarm", "drill", "debrisdrill");
    private static final double QUIET_RADIUS = 12;

    private final NullControllerPlugin plugin;
    private final ConsentManager consent;
    private boolean alarmOn;
    private boolean trapBusy;

    public Base(NullControllerPlugin plugin, ConsentManager consent) {
        this.plugin = plugin;
        this.consent = consent;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::debrisDrill, 200L, 1200L);
    }

    // ---------- Commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player owner) || !consent.isOwner(owner)) {
            sender.sendMessage(ChatColor.RED + "Access denied.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("status")) {
            owner.sendMessage(ChatColor.DARK_PURPLE + "Base machines: " + (active().isEmpty() ? "none" : String.join(", ", active())));
            return true;
        }
        if (args.length < 2 || !KINDS.contains(args[1].toLowerCase(Locale.ROOT))) {
            owner.sendMessage(ChatColor.GRAY + "Usage: /nullbase build|clear <" + String.join("|", KINDS) + "> or /nullbase status");
            return true;
        }
        String kind = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "build" -> build(owner, kind);
            case "clear" -> {
                List<String> list = active();
                list.remove(kind);
                plugin.getConfig().set("base-machines", list);
                plugin.saveConfig();
                owner.sendMessage(ChatColor.DARK_PURPLE + kind + " switched off.");
            }
            default -> owner.sendMessage(ChatColor.GRAY + "Usage: /nullbase build|clear <kind> or /nullbase status");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("build", "clear", "status");
        if (args.length == 2) return KINDS;
        return List.of();
    }

    private void build(Player owner, String kind) {
        Location a = anchor();
        if (a == null) {
            owner.sendMessage(ChatColor.RED + "Set the base first: /nullrod base set");
            return;
        }
        World w = a.getWorld();
        int ax = a.getBlockX(), ay = a.getBlockY(), az = a.getBlockZ();
        switch (kind) {
            case "turret" -> {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = w.getBlockAt(ax + 3, ay + 1, az + dz);
                    b.setBlockData(org.bukkit.Bukkit.createBlockData("minecraft:dispenser[facing=west]"));
                    Dispenser d = (Dispenser) b.getState();
                    d.getInventory().addItem(new ItemStack(Material.ARROW, 64));
                    d.update();
                }
            }
            case "trap" -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        w.getBlockAt(ax + dx, ay - 1, az + dz).setType(Material.POLISHED_BLACKSTONE_BRICKS);
                    }
                }
            }
            case "alarm" -> {
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) == 4) {
                            w.getBlockAt(ax + dx, ay + 1, az + dz).setType(Material.REDSTONE_LAMP);
                        }
                    }
                }
                w.getBlockAt(ax, ay + 2, az).setType(Material.BELL);
            }
            case "craft" -> w.getBlockAt(ax - 3, ay + 1, az).setBlockData(
                    Bukkit.createBlockData("minecraft:furnace[facing=east]"));
            default -> { }
        }
        List<String> list = active();
        if (!list.contains(kind)) list.add(kind);
        plugin.getConfig().set("base-machines", list);
        plugin.saveConfig();
        owner.sendMessage(ChatColor.DARK_PURPLE + kind + " built and switched on.");
    }

    private List<String> active() {
        return new ArrayList<>(plugin.getConfig().getStringList("base-machines"));
    }

    // ---------- Running ----------

    private void tick() {
        Location a = anchor();
        if (a == null || a.getWorld() == null) return;
        boolean roster = rosterNear(a, QUIET_RADIUS);
        boolean intruder = intruderNear(a, QUIET_RADIUS);

        if (active().contains("alarm")) alarm(a, roster);
        if (active().contains("turret") && roster && !intruder) turret(a);
        if (active().contains("trap") && !intruder) trap(a);
        if (active().contains("drill")) drillLayer(a);
        if (active().contains("craft")) craft(a);
    }

    private void turret(Location a) {
        World w = a.getWorld();
        for (int dz = -1; dz <= 1; dz++) {
            Block b = w.getBlockAt(a.getBlockX() + 3, a.getBlockY() + 1, a.getBlockZ() + dz);
            if (b.getType() == Material.DISPENSER && b.getState() instanceof Dispenser d) {
                d.dispense();
            }
        }
    }

    private void alarm(Location a, boolean roster) {
        World w = a.getWorld();
        if (roster && !alarmOn) {
            alarmOn = true;
            Bukkit.getScheduler().runTask(plugin, () -> { });
            w.playSound(a, Sound.BLOCK_BELL_USE, 2f, 0.8f);
            Player o = Bukkit.getPlayerExact(ConsentManager.OWNER_NAME);
            if (o != null) o.sendMessage(ChatColor.DARK_RED + "[Base] Roster player at the base.");
            plugin.alert("Roster player at the base.");
        } else if (!roster) {
            alarmOn = false;
        }
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 4) continue;
                Block b = w.getBlockAt(a.getBlockX() + dx, a.getBlockY() + 1, a.getBlockZ() + dz);
                if (b.getType() != Material.REDSTONE_LAMP) continue;
                Lightable l = (Lightable) b.getBlockData();
                if (l.isLit() != alarmOn) {
                    l.setLit(alarmOn);
                    b.setBlockData(l);
                }
            }
        }
    }

    private void trap(Location a) {
        if (trapBusy) return;
        boolean triggered = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!consent.canTarget(p) || p.getWorld() != a.getWorld()) continue;
            Location l = p.getLocation();
            if (Math.abs(l.getX() - (a.getBlockX() + 0.5)) <= 1.2
                    && Math.abs(l.getZ() - (a.getBlockZ() + 0.5)) <= 1.2
                    && Math.abs(l.getY() - a.getBlockY()) <= 2) {
                triggered = true;
                p.sendMessage(ChatColor.DARK_RED + "The floor gives way.");
            }
        }
        if (!triggered) return;
        World w = a.getWorld();
        List<Block> floor = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block b = w.getBlockAt(a.getBlockX() + dx, a.getBlockY() - 1, a.getBlockZ() + dz);
                if (b.getType() != Material.AIR) {
                    floor.add(b);
                    b.setType(Material.AIR);
                }
            }
        }
        trapBusy = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Block b : floor) b.setType(Material.POLISHED_BLACKSTONE_BRICKS);
            trapBusy = false;
        }, 100L);
    }

    /** Mines one layer of the 3x3 shaft under the base. Drops go to the deposit chest, or to the ground. */
    private void drillLayer(Location a) {
        World w = a.getWorld();
        int bottom = a.getBlockY() - 40;
        for (int y = a.getBlockY() - 2; y >= bottom; y--) {
            boolean any = false;
            List<ItemStack> drops = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = w.getBlockAt(a.getBlockX() + dx, y, a.getBlockZ() + dz);
                    if (b.getType() == Material.AIR || b.getType() == Material.BEDROCK) continue;
                    any = true;
                    drops.addAll(b.getDrops(new ItemStack(Material.NETHERITE_PICKAXE)));
                    b.setType(Material.AIR);
                }
            }
            if (any) {
                deposit(a, drops);
                return;
            }
        }
    }

    /** Every twelve blocks around the base, every minute: ancient debris is dug up and deposited. */
    private void debrisDrill() {
        if (!active().contains("debrisdrill")) return;
        Location a = anchor();
        if (a == null || a.getWorld() == null) return;
        World w = a.getWorld();
        List<ItemStack> drops = new ArrayList<>();
        int taken = 0;
        int r = 12;
        for (int dx = -r; dx <= r && taken < 60; dx++) {
            for (int dy = -r; dy <= r && taken < 60; dy++) {
                for (int dz = -r; dz <= r && taken < 60; dz++) {
                    Block b = w.getBlockAt(a.getBlockX() + dx, a.getBlockY() + dy, a.getBlockZ() + dz);
                    if (b.getType() != Material.ANCIENT_DEBRIS) continue;
                    drops.addAll(b.getDrops(new ItemStack(Material.NETHERITE_PICKAXE)));
                    b.setType(Material.AIR);
                    taken++;
                }
            }
        }
        if (!drops.isEmpty()) {
            deposit(a, drops);
            Player o = Bukkit.getPlayerExact(ConsentManager.OWNER_NAME);
            if (o != null) o.sendMessage(ChatColor.DARK_PURPLE + "[Base] Debris drill took " + taken + " ancient debris.");
        }
    }

    private void deposit(Location a, List<ItemStack> items) {
        Container c = depositChest();
        for (ItemStack item : items) {
            if (c != null) {
                for (ItemStack left : c.getInventory().addItem(item).values()) {
                    a.getWorld().dropItemNaturally(a, left);
                }
            } else {
                a.getWorld().dropItemNaturally(a, item);
            }
        }
    }

    /**
     * Crafting line: the furnace three blocks west of the base takes up to eight ancient debris from the
     * deposit chest, smelts it into netherite scrap, and puts the scrap back in the chest. Coal is kept topped up.
     */
    private void craft(Location a) {
        World w = a.getWorld();
        Block fb = w.getBlockAt(a.getBlockX() - 3, a.getBlockY() + 1, a.getBlockZ());
        if (fb.getType() != Material.FURNACE || !(fb.getState() instanceof Furnace f)) return;
        Container c = depositChest();
        if (c == null) return;
        FurnaceInventory fi = f.getInventory();
        if (fi.getFuel() == null || fi.getFuel().getType() == Material.AIR) {
            fi.setFuel(new ItemStack(Material.COAL, 8));
        }
        ItemStack input = fi.getSmelting();
        if (input == null || input.getType() == Material.AIR) {
            int moved = takeFrom(c, Material.ANCIENT_DEBRIS, 8);
            if (moved > 0) fi.setSmelting(new ItemStack(Material.ANCIENT_DEBRIS, moved));
        }
        ItemStack out = fi.getResult();
        if (out != null && out.getType() != Material.AIR) {
            for (ItemStack left : c.getInventory().addItem(out.clone()).values()) {
                w.dropItemNaturally(a, left);
            }
            fi.setResult(null);
        }
        f.update();
    }

    /** Takes up to max of one material out of a container. Returns how many were taken. */
    private static int takeFrom(Container c, Material m, int max) {
        int have = 0;
        for (ItemStack s : c.getInventory().getContents()) {
            if (s != null && s.getType() == m) have += s.getAmount();
        }
        int n = Math.min(have, max);
        if (n > 0) c.getInventory().removeItem(new ItemStack(m, n));
        return n;
    }

    // ---------- Helpers ----------

    private boolean rosterNear(Location a, double radius) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (consent.canTarget(p) && p.getWorld() == a.getWorld() && p.getLocation().distance(a) <= radius) return true;
        }
        return false;
    }

    private boolean intruderNear(Location a, double radius) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!consent.canTarget(p) && !consent.isOwner(p) && p.getWorld() == a.getWorld()
                    && p.getLocation().distance(a) <= radius) return true;
        }
        return false;
    }

    private Location anchor() {
        String raw = plugin.getConfig().getString("nether-base", "");
        if (raw == null || raw.isBlank()) return null;
        String[] p = raw.split(",");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0].trim());
        if (w == null) return null;
        try {
            return new Location(w, Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
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
}
