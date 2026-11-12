package com.daboss69yt.nullplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.entity.Entity;
import net.citizensnpcs.api.CitizensAPI;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.block.data.Openable;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NullControllerPlugin extends JavaPlugin implements Listener, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("summon", "dismiss", "recall", "guard", "follow",
            "hunt", "stay", "goto", "status", "ranks", "mine", "lumber", "farm", "forge", "chest", "patrol",
            "build", "fortify", "cage", "invade", "ambush", "totem", "succession", "event", "remote", "help");
    private static final List<String> BUILDS = List.of("citadel", "bridge", "watchtower", "barracks",
            "forgegate", "spire", "city", "metropolis", "collector", "smelter", "door", "melonfarm", "cancel", "undo", "preview");
    private static final List<String> EVENTS = List.of("forge", "capital", "end", "clash");
    private static final String REMOTE_NAME = ChatColor.DARK_PURPLE + "Null Remote";

    private static final String MENU_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "The Null: Control";

    private ConsentManager consent;
    private NullArmy army;
    private Builders builders;
    private TotemManager totems;
    private NetherLaw netherLaw;
    private final List<String> alerts = new ArrayList<>();
    private final Map<Integer, String[]> menuActions = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (getServer().getPluginManager().getPlugin("Citizens") == null) {
            getLogger().severe("Citizens is required but was not found. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        consent = new ConsentManager(this);
        army = new NullArmy(this, consent);
        army.startTicker();
        builders = new Builders(this, army);
        long minutes = Math.max(1, getConfig().getInt("auto-fortify-minutes", 3));
        getServer().getScheduler().runTaskTimer(this, () -> {
            Player o = Bukkit.getPlayerExact(ConsentManager.OWNER_NAME);
            if (o != null) builders.autoFortify(o);
        }, 20L * 60, 20L * 60 * minutes);
        totems = new TotemManager(this, consent, army);
        netherLaw = new NetherLaw(this, consent, army);
        netherLaw.start();
        Updater updater = new Updater(this, consent);
        updater.start();
        getCommand("nullupdate").setExecutor(updater);
        Rods rods = new Rods(this, consent, army);
        getServer().getPluginManager().registerEvents(rods, this);
        getCommand("nullrod").setExecutor(rods);
        getCommand("nullrod").setTabCompleter(rods);
        Base base = new Base(this, consent);
        base.start();
        getCommand("nullbase").setExecutor(base);
        getCommand("nullbase").setTabCompleter(base);
        getCommand("nullalerts").setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player p) || !consent.isOwner(p)) {
                sender.sendMessage(ChatColor.RED + "Access denied.");
                return true;
            }
            synchronized (alerts) {
                if (alerts.isEmpty()) p.sendMessage(ChatColor.GRAY + "No alerts yet.");
                for (String a : alerts) p.sendMessage(ChatColor.DARK_PURPLE + "- " + a);
            }
            return true;
        });
        getServer().getPluginManager().registerEvents(netherLaw, this);
        getCommand("nullnether").setExecutor(netherLaw);
        getCommand("nullnether").setTabCompleter(netherLaw);
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("nullcmd").setTabCompleter(this);
        getServer().getScheduler().runTaskTimer(this, this::sweepCity, 100L, 40L);
        getLogger().info("NullController 3.0.0 online. Consent roster loaded.");
    }

    @Override
    public void onDisable() {
        if (netherLaw != null) netherLaw.save();
        if (army != null) army.shutdown();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "bottestverify" -> { return handleAgree(sender, args); }
            case "nullreload" -> { return handleReload(sender); }
            case "nullui" -> { return handleUi(sender); }
            case "nullcmd" -> { return handleCmd(sender, args); }
            default -> { return false; }
        }
    }

    // ---------- Commands ----------

    private boolean handleAgree(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can agree to the rules.");
            return true;
        }
        if (consent.grant(player)) {
            player.sendMessage(ChatColor.DARK_PURPLE + "Verified. Welcome to the roster.");
        } else {
            player.sendMessage(ChatColor.GRAY + "Verified. You are already on the roster.");
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!isOwner(sender)) {
            sender.sendMessage(ChatColor.RED + "Access denied.");
            return true;
        }
        consent.reload();
        sender.sendMessage(ChatColor.DARK_PURPLE + "Consent roster reloaded.");
        return true;
    }

    private boolean handleUi(CommandSender sender) {
        if (!isOwner(sender)) {
            sender.sendMessage(ChatColor.RED + "Access denied.");
            return true;
        }
        ((Player) sender).openInventory(buildMenu());
        return true;
    }

    private boolean handleCmd(CommandSender sender, String[] args) {
        if (!isOwner(sender)) {
            sender.sendMessage(ChatColor.RED + "Access denied.");
            return true;
        }
        Player owner = (Player) sender;
        if (args.length == 0) {
            owner.sendMessage(ChatColor.GRAY + "Usage: /nullcmd help  (lists every order)");
            return true;
        }
        dispatch(owner, args);
        return true;
    }

    /** Shared by /nullcmd and the GUI so both do exactly the same thing. */
    private void dispatch(Player owner, String[] args) {
        switch (args[0].toLowerCase()) {
            case "summon" -> army.summon(owner, args.length > 1 ? parseInt(args[1], 5) : 5);
            case "dismiss" -> army.dismissAll(owner);
            case "recall" -> army.recall(owner);
            case "guard" -> army.setMode(NullArmy.Mode.GUARD, owner);
            case "follow" -> army.setMode(NullArmy.Mode.FOLLOW, owner);
            case "hunt" -> handleHunt(owner, args);
            case "stay" -> army.setMode(NullArmy.Mode.STAY, owner);
            case "status" -> army.status(owner);
            case "ranks" -> army.showRanks(owner);
            case "help" -> sendHelp(owner);
            case "goto" -> handleGoto(owner, args);
            case "mine" -> army.setMode(NullArmy.Mode.MINE, owner);
            case "lumber" -> army.setMode(NullArmy.Mode.LUMBER, owner);
            case "farm" -> army.setMode(NullArmy.Mode.FARM, owner);
            case "forge" -> handleForge(owner, args);
            case "chest" -> handleChest(owner, args);
            case "patrol" -> handlePatrol(owner, args);
            case "build" -> handleBuild(owner, args);
            case "fortify" -> builders.fortify(owner);
            case "cage" -> handleCage(owner, args);
            case "invade" -> handleInvade(owner, args);
            case "ambush" -> handleAmbush(owner, args);
            case "totem" -> totems.giveTotem(owner);
            case "cmd" -> owner.performCommand(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            case "succession" -> totems.succession(owner);
            case "event" -> handleEvent(owner, args);
            case "remote" -> handleRemote(owner, args);
            default -> owner.sendMessage(ChatColor.RED + "Unknown order: " + args[0]);
        }
    }

    private void handleGoto(Player owner, String[] args) {
        if (args.length < 4) {
            owner.sendMessage(ChatColor.GRAY + "Usage: /nullcmd goto <x> <y> <z> [world]");
            return;
        }
        try {
            double x = Double.parseDouble(args[1]);
            double y = Double.parseDouble(args[2]);
            double z = Double.parseDouble(args[3]);
            World world = args.length >= 5 ? Bukkit.getWorld(args[4]) : owner.getWorld();
            if (world == null) {
                owner.sendMessage(ChatColor.RED + "No world named " + args[4] + ".");
                return;
            }
            army.goTo(new Location(world, x, y, z), owner);
        } catch (NumberFormatException ex) {
            owner.sendMessage(ChatColor.RED + "Coordinates must be numbers.");
        }
    }

    private void handleForge(Player owner, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("set")) {
            Block target = owner.getTargetBlockExact(6);
            if (target == null || target.getType() == Material.AIR) {
                owner.sendMessage(ChatColor.RED + "Look at the Forge block within 6 blocks and try again.");
                return;
            }
            army.setForge(target, owner);
            return;
        }
        army.setMode(NullArmy.Mode.FORGE, owner);
    }

    private void handleChest(Player owner, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "set";
        if (!sub.equals("set")) {
            owner.sendMessage(ChatColor.GRAY + "Usage: /nullcmd chest set  (while looking at a chest)");
            return;
        }
        Block target = owner.getTargetBlockExact(6);
        if (target == null) {
            owner.sendMessage(ChatColor.RED + "Look at a chest within 6 blocks, then run /nullcmd chest set.");
            return;
        }
        army.setDepositChest(target, owner);
    }

    private void handlePatrol(Player owner, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "start";
        switch (sub) {
            case "add" -> army.patrolAdd(owner);
            case "clear" -> army.patrolClear(owner);
            case "start" -> army.setMode(NullArmy.Mode.PATROL, owner);
            default -> owner.sendMessage(ChatColor.GRAY + "Usage: /nullcmd patrol <add|clear|start>");
        }
    }

    private void handleBuild(Player owner, String[] args) {
        if (args.length < 2) {
            owner.sendMessage(ChatColor.GRAY + "Usage: /nullcmd build <citadel|bridge|watchtower|barracks|forgegate|spire|cancel>");
        } else if (args[1].equalsIgnoreCase("cancel")) {
            builders.cancel(owner);
        } else if (args[1].equalsIgnoreCase("undo")) {
            builders.undo(owner);
        } else if (args[1].equalsIgnoreCase("preview")) {
            if (args.length < 3) {
                owner.sendMessage(ChatColor.GRAY + "Usage: /nullcmd build preview <type>");
            } else {
                builders.preview(owner, args[2].toLowerCase());
            }
        } else {
            builders.build(owner, args[1].toLowerCase());
        }
    }

    /** Bedrock Cage: roster players only. */
    private void handleCage(Player owner, String[] args) {
        Player target = requireConsentingTarget(owner, args, 1, "cage");
        if (target == null) return;
        builders.startBuild(owner, "Bedrock Cage", Structures.cage(target.getLocation()));
        title(owner, "BEDROCK CAGE", "Sealing the target");
        army.guardCage(target.getLocation());
    }

    /** Roster players' ender pearls pull their hunters after them. */
    @EventHandler
    public void onRosterPearl(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        army.followPearl(event.getPlayer(), event.getTo());
    }

    /** Invasion: a timed hunt on one roster player, from about 40 blocks away. */
    private void handleInvade(Player owner, String[] args) {
        Player target = requireConsentingTarget(owner, args, 1, "invade");
        if (target == null) return;
        int size = getConfig().getInt("invasion-size", 6);
        int minutes = getConfig().getInt("invasion-minutes", 10);
        Location start = target.getLocation().clone().add(40, 0, 0);
        if (army.spawnHunters(owner, target, start, size, minutes)) {
            owner.sendMessage(ChatColor.DARK_PURPLE + "Invasion of " + target.getName() + " begins. "
                    + size + " Nulls for " + minutes + " minutes.");
        }
    }

    private Player requireConsentingTarget(Player owner, String[] args, int index, String verb) {
        if (args.length <= index) {
            owner.sendMessage(ChatColor.GRAY + "Usage: /nullcmd " + verb + " <player>");
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[index]);
        if (target == null) {
            owner.sendMessage(ChatColor.RED + "That player isn't online.");
            return null;
        }
        if (!consent.canTarget(target)) {
            owner.sendMessage(ChatColor.RED + target.getName() + " isn't on the consent roster, so the Nulls can't target them.");
            return null;
        }
        return target;
    }

    private void handleAmbush(Player owner, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "status";
        boolean current = getConfig().getBoolean("end-ambush", false);
        boolean next;
        switch (sub) {
            case "on" -> next = true;
            case "off" -> next = false;
            case "toggle" -> next = !current;
            default -> {
                owner.sendMessage(ChatColor.GRAY + "End ambush is " + (current ? "ON" : "OFF")
                        + ". Use /nullcmd ambush <on|off>.");
                return;
            }
        }
        getConfig().set("end-ambush", next);
        saveConfig();
        owner.sendMessage(ChatColor.DARK_PURPLE + "End ambush " + (next ? "enabled" : "disabled") + ".");
    }

    /** Lore events. Each one summons a squad and sets it up for the scene. There's no lightning in any of them. */
    private void handleEvent(Player owner, String[] args) {
        String ev = args.length > 1 ? args[1].toLowerCase() : "";
        switch (ev) {
            case "forge" -> {
                title(owner, "FORGE AWAKENING", "The squad mobilises");
                if (army.setMode(NullArmy.Mode.FORGE, owner)) army.summon(owner, 5);
            }
            case "capital" -> {
                title(owner, "CAPITAL DEFENSE", "Watchtower rising");
                army.setMode(NullArmy.Mode.GUARD, owner);
                army.summon(owner, 8);
                builders.build(owner, "watchtower");
            }
            case "end" -> {
                title(owner, "END COMMAND POST", "Barracks going up");
                if (owner.getWorld().getEnvironment() != World.Environment.THE_END) {
                    owner.sendMessage(ChatColor.RED + "The End Command Post can only be set up in The End.");
                    return;
                }
                army.setMode(NullArmy.Mode.GUARD, owner);
                army.summon(owner, 5);
                builders.build(owner, "barracks");
            }
            case "clash" -> {
                title(owner, "FACTION CLASH", "Hostiles will be engaged");
                army.setMode(NullArmy.Mode.GUARD, owner);
                army.summon(owner, 8);
                owner.sendMessage(ChatColor.GRAY + "Faction Clash: the squad will engage hostile mobs. "
                        + "Nulls don't fight each other.");
            }
            default -> owner.sendMessage(ChatColor.GRAY + "Usage: /nullcmd event <forge|capital|end|clash>");
        }
    }

    /** /nullcmd hunt [player|any]: hunt one roster player, or whoever on the roster is nearest. */
    private void handleHunt(Player owner, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("any")) {
            army.setMode(NullArmy.Mode.HUNT, owner);
            return;
        }
        Player target = requireConsentingTarget(owner, args, 1, "hunt");
        if (target == null) return;
        army.setHuntTarget(owner, target);
    }

    /** Keeps Endermen and dropped chorus fruit out of the Civilisation's area. */
    private void sweepCity() {
        String raw = getConfig().getString("city-centre", "");
        if (raw == null || raw.isBlank()) return;
        String[] p = raw.split(",");
        if (p.length != 4) return;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return;
        int r = getConfig().getInt("sweep-radius", 120);
        try {
            Location c = new Location(w, Integer.parseInt(p[1]) + 0.5, Integer.parseInt(p[2]), Integer.parseInt(p[3]) + 0.5);
            for (org.bukkit.entity.Entity e : w.getNearbyEntities(c, r, 80, r)) {
                if (e instanceof org.bukkit.entity.Enderman) {
                    e.remove();
                } else if (e instanceof org.bukkit.entity.Item item
                        && item.getItemStack().getType() == Material.CHORUS_FRUIT) {
                    item.remove();
                }
            }
        } catch (NumberFormatException ex) {
            getLogger().warning("city-centre in config.yml is not valid; clear it to stop the sweep.");
        }
    }

    /** /nullcmd remote gives the Null Remote rod. /nullcmd remote link registers the iron door you're looking at. */
    private void handleRemote(Player owner, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("link")) {
            linkDoor(owner);
            return;
        }
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = rod.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(REMOTE_NAME);
            meta.setLore(List.of(ChatColor.GRAY + "Cast the hook onto a linked door to open or close it."));
            rod.setItemMeta(meta);
        }
        owner.getInventory().addItem(rod);
        owner.sendMessage(ChatColor.DARK_PURPLE + "Null Remote issued. Link doors with /nullcmd remote link while looking at them.");
    }

    private void linkDoor(Player owner) {
        Block target = owner.getTargetBlockExact(6);
        if (target == null || !(target.getBlockData() instanceof Openable)) {
            owner.sendMessage(ChatColor.RED + "Look at a door within 6 blocks and try again.");
            return;
        }
        String entry = target.getWorld().getName() + "," + target.getX() + "," + target.getY() + "," + target.getZ();
        List<String> doors = new ArrayList<>(getConfig().getStringList("remote-doors"));
        if (!doors.contains(entry)) doors.add(entry);
        getConfig().set("remote-doors", doors);
        saveConfig();
        owner.sendMessage(ChatColor.DARK_PURPLE + "Door linked to the Null Remote.");
    }

    /** Casting a Null Remote onto a linked door (within 4 blocks of it) toggles the door open or closed. */
    @EventHandler
    public void onRemoteCast(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.IN_GROUND) return;
        Player p = event.getPlayer();
        if (!consent.isOwner(p)) return;
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getType() != Material.FISHING_ROD || held.getItemMeta() == null
                || !REMOTE_NAME.equals(held.getItemMeta().getDisplayName())) return;

        Location hook = event.getHook().getLocation();
        for (String raw : getConfig().getStringList("remote-doors")) {
            String[] parts = raw.split(",");
            if (parts.length != 4) continue;
            World w = Bukkit.getWorld(parts[0]);
            if (w == null || !w.equals(hook.getWorld())) continue;
            try {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                if (new Location(w, x + 0.5, y, z + 0.5).distance(hook) > 4) continue;
                boolean open = toggleDoor(w, x, y, z);
                p.sendMessage(ChatColor.DARK_PURPLE + "Door " + (open ? "opened" : "closed") + " by Null Remote.");
            } catch (NumberFormatException ex) {
                getLogger().warning("Bad remote-doors entry in config.yml: " + raw);
            }
        }
    }

    /** Flips both halves of a door. Returns true if it is now open. */
    private boolean toggleDoor(World w, int x, int y, int z) {
        Block base = w.getBlockAt(x, y, z);
        if (!(base.getBlockData() instanceof Openable first)) return false;
        boolean next = !first.isOpen();
        for (int dy = -1; dy <= 1; dy++) {
            Block b = w.getBlockAt(x, y + dy, z);
            if (b.getBlockData() instanceof Openable op) {
                op.setOpen(next);
                b.setBlockData(op, false);
            }
        }
        return next;
    }

    private void title(Player p, String main, String sub) {
        p.sendTitle(ChatColor.DARK_PURPLE + main, ChatColor.GRAY + sub, 10, 60, 20);
    }

    private void sendHelp(Player owner) {
        owner.sendMessage(ChatColor.DARK_PURPLE + "=== NullController 4.0 ===");
        owner.sendMessage(ChatColor.GRAY + "Army: " + ChatColor.WHITE + "summon [n], dismiss, recall, status, ranks");
        owner.sendMessage(ChatColor.GRAY + "Modes: " + ChatColor.WHITE + "guard, follow, hunt [player|any], stay, goto <x> <y> <z>, mine, lumber, farm, forge, patrol");
        owner.sendMessage(ChatColor.GRAY + "Builds: " + ChatColor.WHITE + "build <citadel|bridge|watchtower|barracks|forgegate|spire|cancel|undo|preview <type>>, fortify");
        owner.sendMessage(ChatColor.GRAY + "Roster only: " + ChatColor.WHITE + "cage <player>, invade <player>, ambush on|off");
        owner.sendMessage(ChatColor.GRAY + "Totem: " + ChatColor.WHITE + "totem, succession");
        owner.sendMessage(ChatColor.GRAY + "Machines: " + ChatColor.WHITE + "build collector|smelter|door|melonfarm; remote [link]");
        owner.sendMessage(ChatColor.GRAY + "Events: " + ChatColor.WHITE + "event <forge|capital|end|clash>");
        owner.sendMessage(ChatColor.GRAY + "Or open " + ChatColor.WHITE + "/nullui" + ChatColor.GRAY + " for the panel.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!isOwner(sender)) return List.of();
        if (args.length == 1) return filter(SUBCOMMANDS, args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("build")) return filter(BUILDS, args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("event")) return filter(EVENTS, args[1]);
        return List.of();
    }

    private static List<String> filter(List<String> options, String typed) {
        String t = typed.toLowerCase();
        return options.stream().filter(o -> o.startsWith(t)).toList();
    }

    private int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean isOwner(CommandSender sender) {
        return sender instanceof Player p && consent.isOwner(p);
    }

    // ---------- GUI ----------

    private Inventory buildMenu() {
        menuActions.clear();
        MenuHolder holder = new MenuHolder();
        Inventory inv = getServer().createInventory(holder, 54, MENU_TITLE);
        holder.bind(inv);

        button(inv, 10, Material.WITHER_SKELETON_SKULL, "Summon 5 Nulls", "Adds a squad around you", "summon", "5");
        button(inv, 11, Material.PLAYER_HEAD, "Summon 10 Nulls", "Adds a larger squad", "summon", "10");
        button(inv, 12, Material.BARRIER, "Dismiss All", "Removes every Null", "dismiss");
        button(inv, 13, Material.ENDER_PEARL, "Recall", "Brings the army to you", "recall");
        button(inv, 14, Material.BOOK, "Status", "Army count, mode, and destinations", "status");
        button(inv, 15, Material.MAP, "Add Patrol Point", "Adds your position to the route", "patrol", "add");
        button(inv, 16, Material.PAPER, "Clear Patrol", "Empties the patrol route", "patrol", "clear");
        button(inv, 17, Material.ENDER_CHEST, "Deposit Chest", "Look at a chest, run /nullcmd chest set", "status");

        button(inv, 19, Material.SHIELD, "GUARD Mode", "Form a perimeter, fight mobs near you", "guard");
        button(inv, 20, Material.LEAD, "FOLLOW Mode", "Walk with you", "follow");
        button(inv, 21, Material.NETHERITE_SWORD, "HUNT Mode", "Pursue roster players only", "hunt");
        button(inv, 22, Material.IRON_BLOCK, "STAY Mode", "Hold position", "stay");
        button(inv, 23, Material.DIAMOND_PICKAXE, "AUTO-MINE Mode", "Ores and stone within 16 blocks", "mine");
        button(inv, 24, Material.NETHERITE_AXE, "LUMBERJACK Mode", "Chops logs within 16 blocks", "lumber");
        button(inv, 25, Material.COMPASS, "PATROL Mode", "Walks the saved route", "patrol", "start");

        button(inv, 28, Material.OBSIDIAN, "Build Void Citadel", "17x17 fortress, 22 tall", "build", "citadel");
        button(inv, 29, Material.NETHERITE_BLOCK, "Build Bridge", "5 wide, 50 long, faces you", "build", "bridge");
        button(inv, 30, Material.DEEPSLATE_BRICKS, "Build Watchtower", "Tall lookout with beacon", "build", "watchtower");
        button(inv, 31, Material.CHEST, "Build Barracks", "Sleeping and armory block", "build", "barracks");
        button(inv, 32, Material.LODESTONE, "Build Forge Gate", "Gate around a Forge block", "build", "forgegate");
        button(inv, 33, Material.BEACON, "Build Beacon Spire", "Territory marker spire", "build", "spire");
        button(inv, 34, Material.BARRIER, "Cancel Build", "Stops the current build", "build", "cancel");
        button(inv, 35, Material.END_STONE_BRICKS, "Build Null Civilisation", "End only: walled city, your Hall, Null quarters", "build", "city");

        button(inv, 37, Material.WHEAT, "FARM Mode", "Harvests and replants mature crops", "farm");
        button(inv, 38, Material.IRON_PICKAXE, "Fortify", "Re-places missing blocks from the last build", "fortify");
        button(inv, 39, Material.LODESTONE, "FORGE DUTY", "Hold near the Forge (set with /nullcmd forge set)", "forge");
        button(inv, 40, Material.RED_NETHER_BRICKS, "End Ambush: toggle", "Two hunters for roster players entering The End", "ambush", "toggle");
        button(inv, 41, Material.TOTEM_OF_UNDYING, "Get Totem of NULL", "Issues your active totem", "totem");
        button(inv, 42, Material.NETHER_STAR, "Succession", "Mints a new totem and voids the old ones", "succession");
        button(inv, 43, Material.SPYGLASS, "Ranks", "Every Null's rank, health and state", "ranks");
        button(inv, 44, Material.WRITABLE_BOOK, "Help", "Lists every command", "help");

        // One-click rods along the top row, and the Nether base machines and panels along the bottom.
        button(inv, 0, Material.CRYING_OBSIDIAN, "Ambush Rod", "Portals open around a roster player", "cmd", "nullrod", "ambush");
        button(inv, 1, Material.BLUE_ICE, "Stasis Rod", "Pull a roster player to the base and freeze them", "cmd", "nullrod", "stasis");
        button(inv, 2, Material.TNT, "TNT Rod", "TNT from the sky onto the landing spot", "cmd", "nullrod", "tnt");
        button(inv, 3, Material.BELL, "Summon Rod", "The army walks to the hook", "cmd", "nullrod", "summon");
        button(inv, 4, Material.LIGHTNING_ROD, "Lightning Rod", "Bolt on the nearest roster player", "cmd", "nullrod", "lightning");
        button(inv, 5, Material.FEATHER, "Launch Rod", "Throws the nearest roster player up", "cmd", "nullrod", "launch");
        button(inv, 6, Material.WITHER_ROSE, "Wither Rod", "Wither, slowness and blindness", "cmd", "nullrod", "wither");
        button(inv, 7, Material.ENDER_EYE, "Gravity Rod", "Pulls roster players in", "cmd", "nullrod", "gravity");
        button(inv, 8, Material.BEACON, "Orbital Rod", "Eight lightning bolts over the spot", "cmd", "nullrod", "orbital");
        button(inv, 26, Material.DRAGON_BREATH, "Nuke Rod", "Three big explosions on the spot", "cmd", "nullrod", "nuke");
        button(inv, 9, Material.TURTLE_HELMET, "Base: Turret", "Arrow turret at the base", "cmd", "nullbase", "build", "turret");
        button(inv, 18, Material.IRON_BARS, "Base: Trap", "Floor that drops under roster players", "cmd", "nullbase", "build", "trap");
        button(inv, 36, Material.REDSTONE_LAMP, "Base: Alarm", "Lamps and bell when roster players come", "cmd", "nullbase", "build", "alarm");
        button(inv, 45, Material.IRON_PICKAXE, "Base: Drill", "Mines the shaft under the base", "cmd", "nullbase", "build", "drill");
        button(inv, 50, Material.ANCIENT_DEBRIS, "Base: Debris Drill", "Digs up debris within 12 blocks", "cmd", "nullbase", "build", "debrisdrill");
        button(inv, 51, Material.FURNACE, "Base: Crafting Line", "Smelts debris into scrap from the chest", "cmd", "nullbase", "build", "craft");
        button(inv, 52, Material.OBSERVER, "Alerts", "The last 30 alarms and finds", "cmd", "nullalerts");
        button(inv, 53, Material.NETHERITE_INGOT, "Nether Law", "Strikes, prisoners and the prison", "cmd", "nullnether", "status");

        button(inv, 46, Material.CRIMSON_FUNGUS, "Event: Forge Awakening", "Squad of 5 mobilises at the Forge", "event", "forge");
        button(inv, 47, Material.GOLD_BLOCK, "Event: Capital Defense", "Squad of 8 guards, watchtower built", "event", "capital");
        button(inv, 48, Material.END_STONE, "Event: End Command Post", "Squad of 5 and barracks, End only", "event", "end");
        button(inv, 49, Material.NETHERITE_SWORD, "Event: Faction Clash", "Squad of 8 engages mobs", "event", "clash");

        return inv;
    }

    private void button(Inventory inv, int slot, Material mat, String name, String lore, String... action) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.DARK_PURPLE + name);
            meta.setLore(List.of(ChatColor.GRAY + lore));
            item.setItemMeta(meta);
        }
        inv.setItem(slot, item);
        menuActions.put(slot, action);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player p) || !consent.isOwner(p)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        String[] action = menuActions.get(slot);
        if (action != null) {
            dispatch(p, action);
        }
    }

    /** Keeps the last 30 alerts for /nullalerts and the control panel. */
    public void alert(String message) {
        synchronized (alerts) {
            alerts.add(message);
            while (alerts.size() > 30) alerts.remove(0);
        }
    }

    // ---------- Nether peace ----------

    /** Nether mobs never target the owner or the Nulls. */
    @EventHandler
    public void onNetherAggro(EntityTargetLivingEntityEvent event) {
        if (!NullArmy.NETHER_PEACE.contains(event.getEntity().getType())) return;
        Entity target = event.getTarget();
        if (target == null) return;
        boolean protectedTarget = (target instanceof Player p && consent.isOwner(p))
                || CitizensAPI.getNPCRegistry().isNPC(target);
        if (protectedTarget) event.setCancelled(true);
    }

    // ---------- End ambush ----------

    /** When a roster player arrives in The End, two hunters appear for them if the ambush is on. */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (!getConfig().getBoolean("end-ambush", false)) return;
        Player p = event.getPlayer();
        if (!consent.canTarget(p)) return;
        if (p.getWorld().getEnvironment() != World.Environment.THE_END) return;

        Player owner = Bukkit.getPlayerExact(ConsentManager.OWNER_NAME);
        if (owner == null) return;
        int minutes = getConfig().getInt("ambush-minutes", 3);
        army.spawnHunters(owner, p, p.getLocation(), 2, minutes);
        owner.sendMessage(ChatColor.DARK_PURPLE + "End ambush sent after " + p.getName() + ".");
    }

    // ---------- Totem right-click ----------

    @EventHandler
    public void onTotemUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (!totems.isTotem(item)) return;
        event.setCancelled(true);
        totems.use(event.getPlayer(), item);
    }

    /** Marks our GUI so click handling can recognise it. */
    public static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;

        void bind(Inventory inv) {
            this.inventory = inv;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
