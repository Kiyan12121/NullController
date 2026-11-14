package com.daboss69yt.nullplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import net.citizensnpcs.api.event.NPCDeathEvent;
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
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.entity.Entity;
import net.citizensnpcs.api.CitizensAPI;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class NullControllerPlugin extends JavaPlugin implements Listener, TabCompleter {
    private Rods rods;

    private static final List<String> SUBCOMMANDS = List.of("summon", "dismiss", "recall", "guard", "follow",
            "hunt", "stay", "goto", "status", "ranks", "mine", "lumber", "farm", "forge", "chest", "patrol", "stashroom", "autowork", "autospy",
            "build", "fortify", "cage", "invade", "ambush", "totem", "succession", "event", "remote", "team", "orbital", "stabshot", "chests", "pearl", "scout", "rank", "portal", "weapon", "arm", "help");
    private static final List<String> BUILDS = List.of("citadel", "bridge", "watchtower", "barracks",
            "forgegate", "spire", "city", "metropolis", "collector", "smelter", "door", "melonfarm", "prison", "cancel", "undo", "preview");
    private static final List<String> EVENTS = List.of("forge", "capital", "end", "clash");
    private static final String REMOTE_NAME = ChatColor.DARK_PURPLE + "Null Remote";

    private static final String MENU_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "The Null: Control";

    private ConsentManager consent;
    private NullArmy army;
    private CommanderChat commanderChat;
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
        getServer().getScheduler().runTaskTimer(this, () -> {
            Player o = Bukkit.getPlayerExact(ConsentManager.OWNER_NAME);
            if (o != null) builders.repairUnderAttack(o);
        }, 20L * 10, 20L * 10); // every ten seconds: repairs happen while the Nulls are still there
        totems = new TotemManager(this, consent, army);
        netherLaw = new NetherLaw(this, consent, army);
        netherLaw.setPrisonBuilder(site -> builders.buildOffline(site, "prison", msg -> alert(msg)));
        netherLaw.start();
        Updater updater = new Updater(this, consent);
        updater.start();
        getCommand("nullupdate").setExecutor(updater);
        rods = new Rods(this, consent, army);
        getServer().getPluginManager().registerEvents(rods, this);
        getCommand("nullrod").setExecutor(rods);
        getCommand("nullrod").setTabCompleter(rods);
        Base base = new Base(this, consent, army);
        base.start();
        getCommand("nullbase").setExecutor(base);
        getCommand("nullbase").setTabCompleter(base);
        getCommand("nullalerts").setExecutor((sender, cmd, label, args) -> {
            Player p = ownerFor(sender);
            if (p == null || !consent.isOwner(p)) {
                sender.sendMessage(ChatColor.RED + "Access denied.");
                return true;
            }
            synchronized (alerts) {
                if (alerts.isEmpty()) p.sendMessage(ChatColor.GRAY + "No alerts yet.");
                for (String a : alerts) p.sendMessage(ChatColor.DARK_PURPLE + "- " + a);
            }
            return true;
        });
        getCommand("nullspy").setExecutor((sender, cmd, label, args) -> {
            Player p = ownerFor(sender);
            if (p == null || !consent.isOwner(p)) {
                sender.sendMessage(ChatColor.RED + "Access denied, or the owner is offline.");
                return true;
            }
            if (args.length == 0 || args[0].equalsIgnoreCase("stop")) {
                army.stopSpies(p);
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                p.sendMessage(ChatColor.RED + "That player is not online.");
                return true;
            }
            if (!consent.canTarget(target)) {
                p.sendMessage(ChatColor.RED + "Roster only. " + target.getName() + " is not on it.");
                return true;
            }
            army.spy(p, target);
            return true;
        });
        getServer().getPluginManager().registerEvents(netherLaw, this);
        commanderChat = new CommanderChat(this, army, consent);
        commanderChat.setTotems(totems);
        commanderChat.startDailyReport();
        getServer().getPluginManager().registerEvents(commanderChat, this);
        registerVoice(0);
        getCommand("nullnether").setExecutor(netherLaw);
        getCommand("nullnether").setTabCompleter(netherLaw);
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("nullcmd").setTabCompleter(this);
        getServer().getScheduler().runTaskTimer(this, this::sweepCity, 100L, 40L);
        getLogger().info("NullController 3.0.0 online. Consent roster loaded.");
    }

    private boolean voiceRegistered;

    /**
     * Simple Voice Chat publishes its service after NullController has already enabled, so the lookup
     * can come back empty on the first try. Retry on the scheduler until it appears, then give up loudly.
     */
    private void registerVoice(int attempt) {
        if (voiceRegistered) return;
        if (getServer().getPluginManager().getPlugin("voicechat") == null) {
            getLogger().info("Simple Voice Chat is not installed: the Commander cannot hear you.");
            return;
        }
        var svc = getServer().getServicesManager().load(de.maxhenkel.voicechat.api.BukkitVoicechatService.class);
        if (svc != null) {
            VoiceListener listener = new VoiceListener(this, commanderChat);
            commanderChat.setVoice(listener);
            svc.registerPlugin(listener);
            voiceRegistered = true;
            getLogger().info("Simple Voice Chat found: the Commander can hear you.");
            return;
        }
        if (attempt >= 40) {
            getLogger().warning("Simple Voice Chat service never appeared; the Commander cannot hear you.");
            return;
        }
        getServer().getScheduler().runTaskLater(this, () -> registerVoice(attempt + 1), 20L);
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
        if (!(sender instanceof ConsoleCommandSender) && !isOwner(sender)) {
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
        if (sender instanceof Player pl) {
            pl.openInventory(buildMenu());
        } else {
            sender.sendMessage(ChatColor.GRAY + "The panel opens in game. Run /nullui as the owner.");
        }
        return true;
    }

    /** Console orders while the owner is offline: only what needs no player. */
    private boolean offlineOrder(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(OFFLINE_HELP);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "summon" -> {
                int requested = args.length > 1 ? parseInt(args[1], 5) : 5;
                int queued = army.summonOffline(requested);
                sender.sendMessage(queued < 0 ? "The army is regrouping. Try again shortly."
                        : queued == 0 ? "Army cap reached. Raise max-nulls in config.yml."
                        : "Summoning " + queued + " Nulls at the army's position.");
            }
            case "build" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: build <citadel|bridge|watchtower|barracks|forgegate|spire|collector|smelter|door|melonfarm|prison>");
                } else {
                    Location site = army.offlineSite();
                    if (site == null) {
                        sender.sendMessage("Run /nullcmd nether first. The Nulls build in the Nether only.");
                    } else {
                        builders.buildOffline(site, args[1].toLowerCase(), msg -> sender.sendMessage(msg));
                    }
                }
            }
            case "strike" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: strike <roster player>");
                } else {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) sender.sendMessage("That player is not online.");
                    else if (!consent.canTarget(target)) sender.sendMessage("Roster only. " + target.getName() + " is not on it.");
                    else sender.sendMessage(army.strike(target));
                }
            }
            case "mobcannon" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: mobcannon <roster player> [count]");
                } else {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    int count = args.length > 2 ? parseInt(args[2], 30) : 30;
                    if (target == null) sender.sendMessage("That player is not online.");
                    else if (!consent.canTarget(target)) sender.sendMessage("Roster only. " + target.getName() + " is not on it.");
                    else sender.sendMessage(army.mobCannon(target, count));
                }
            }
            case "team" -> teamOrder(sender, args);
            case "orbital" -> targetedOrder(sender, args, army::orbitalNuke);
            case "stabshot" -> targetedOrder(sender, args, army::stabShot);
            case "explore" -> offlineMode(sender, NullArmy.Mode.EXPLORE);
            case "nether" -> offlineMode(sender, NullArmy.Mode.NETHER);
            case "spy" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("stop")) {
                    sender.sendMessage("Spies recalled (" + army.stopSpiesOffline() + ").");
                } else if (args.length < 2) {
                    sender.sendMessage("Usage: spy <player>  or  spy stop");
                } else {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) sender.sendMessage("That player is not online.");
                    else if (!army.spyOffline(target)) sender.sendMessage("Roster only. " + target.getName() + " is not on it.");
                    else sender.sendMessage("Spy on " + target.getName() + " set. Reports go to /nullalerts.");
                }
            }
            case "dismiss" -> sender.sendMessage("Dismissed " + army.dismissOffline() + " Nulls.");
            case "guard" -> offlineMode(sender, NullArmy.Mode.GUARD);
            case "stay" -> offlineMode(sender, NullArmy.Mode.STAY);
            case "mine" -> offlineMode(sender, NullArmy.Mode.MINE);
            case "lumber" -> offlineMode(sender, NullArmy.Mode.LUMBER);
            case "farm" -> offlineMode(sender, NullArmy.Mode.FARM);
            case "status" -> sender.sendMessage(army.statusOffline());
            case "ambush" -> {
                // "ambush <player>" opens a portal ambush on a roster player, with you offline. "on|off|toggle" flips the Nether ambush.
                if (args.length > 1 && !List.of("on", "off", "toggle").contains(args[1].toLowerCase(Locale.ROOT))) {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "That player is not online.");
                        return true;
                    }
                    if (!consent.canTarget(target)) {
                        sender.sendMessage(ChatColor.RED + "Refused. " + target.getName() + " is not on the roster.");
                        return true;
                    }
                    army.ambush(null, target, 3, (int) getConfig().getLong("ambush-minutes", 3));
                    sender.sendMessage(ChatColor.DARK_PURPLE + "Ambush opened on " + target.getName() + ".");
                    return true;
                }
                String sub = args.length > 1 ? args[1].toLowerCase() : "toggle";
                boolean current = getConfig().getBoolean("nether-ambush", false);
                boolean next = switch (sub) {
                    case "on" -> true;
                    case "off" -> false;
                    default -> !current;
                };
                getConfig().set("nether-ambush", next);
                saveConfig();
                sender.sendMessage("Nether ambush " + (next ? "enabled" : "disabled") + ".");
            }
            case "stashroom" -> sender.sendMessage(army.buildStashRoom());
            case "autowork" -> sender.sendMessage("Auto work " + toggleKey(args, "auto-work", true) + ".");
            case "autospy" -> sender.sendMessage("Auto spy " + toggleKey(args, "auto-spy", true) + ".");
            default -> sender.sendMessage(OFFLINE_HELP);
        }
        return true;
    }

    /** on | off | toggle for a boolean config key. Returns the new state as enabled or disabled. */
    private String toggleKey(String[] args, String key, boolean fallback) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "toggle";
        boolean current = getConfig().getBoolean(key, fallback);
        boolean next = switch (sub) {
            case "on" -> true;
            case "off" -> false;
            default -> !current;
        };
        getConfig().set(key, next);
        saveConfig();
        return next ? "enabled" : "disabled";
    }

    private static final String OFFLINE_HELP = ChatColor.GRAY + "Owner is offline. Console orders: "
            + "summon [n], build <kind> (Nether only), chests place <kind> (look at a block), strike <player> (the Breach), orbital <player> (Nuke), stabshot <player>, mobcannon <player> [n], team list|add|remove <player>, dismiss, guard, stay, mine, lumber, farm, explore, nether, spy <player>|stop, status, ambush on|off|toggle, stashroom, autowork on|off|toggle, autospy on|off|toggle";

    private void offlineMode(CommandSender sender, NullArmy.Mode mode) {
        sender.sendMessage(army.setModeOffline(mode)
                ? "Army mode: " + mode.name()
                : "That mode can't run yet (patrol route or forge block not set).");
    }

    private boolean handleCmd(CommandSender sender, String[] args) {
        if (sender instanceof ConsoleCommandSender && ownerFor(sender) == null) {
            return offlineOrder(sender, args);
        }
        if (!isOwner(sender)) {
            sender.sendMessage(ChatColor.RED + "Access denied, or the owner is offline.");
            return true;
        }
        Player owner = ownerFor(sender);
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
            case "team" -> teamOrder(owner, args);
            case "chests" -> handleChests(owner, args);
            case "stashroom" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.buildStashRoom(owner));
            case "autowork" -> owner.sendMessage(ChatColor.DARK_PURPLE + "Auto work " + toggleKey(args, "auto-work", true) + ".");
            case "autospy" -> owner.sendMessage(ChatColor.DARK_PURPLE + "Auto spy " + toggleKey(args, "auto-spy", true) + ".");
            case "orbital" -> targetedOrder(owner, args, army::orbitalNuke);
            case "scout" -> army.scoutReport(owner);
            case "portal" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.buildPortal(owner));
            case "weapon" -> {
                org.bukkit.inventory.ItemStack made = args.length < 2 ? null : Arsenal.make(args[1].toLowerCase());
                if (made == null) {
                    owner.sendMessage(ChatColor.GRAY + "Usage: weapon <" + String.join("|", Arsenal.KEYS) + ">");
                } else {
                    owner.getInventory().addItem(made);
                    owner.sendMessage(ChatColor.DARK_PURPLE + "Weapon added to your inventory.");
                }
            }
            case "arm" -> {
                if (args.length < 3 || args[1].isEmpty()) {
                    owner.sendMessage(ChatColor.GRAY + "Usage: arm <R|A|I|D> <" + String.join("|", Arsenal.KEYS) + ">");
                } else {
                    owner.sendMessage(ChatColor.DARK_PURPLE + army.armRank(Character.toUpperCase(args[1].charAt(0)), args[2].toLowerCase()));
                }
            }
            case "rank" -> {
                if (args.length < 3 || args[1].isEmpty()) {
                    owner.sendMessage(ChatColor.GRAY + "Usage: rank <R|A|I|D> <recall|dismiss|guard|follow|stay|clear>");
                } else {
                    owner.sendMessage(ChatColor.DARK_PURPLE + army.rankOrder(owner, Character.toUpperCase(args[1].charAt(0)), args[2].toLowerCase()));
                }
            }
            case "pearl" -> {
                Block aimed = owner.getTargetBlockExact(60);
                owner.sendMessage(aimed == null ? ChatColor.RED + "Look at a spot to aim the pearl cannon."
                        : ChatColor.DARK_PURPLE + army.pearlCannonAt(owner, aimed.getLocation().add(0, 1, 0)));
            }
            case "stabshot" -> targetedOrder(owner, args, army::stabShot);
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

    /** chests place <deposit|supply|stash|loot>: builds a chest on the block you're looking at. */
    private void handleChests(Player owner, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("place")) {
            owner.sendMessage(ChatColor.GRAY + "Usage: chests place <deposit|supply|stash|loot>  (look at the block to build on)");
            return;
        }
        Chests.Kind kind;
        try {
            kind = Chests.Kind.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            owner.sendMessage(ChatColor.RED + "Unknown chest kind. Use deposit, supply, stash or loot.");
            return;
        }
        owner.sendMessage(ChatColor.DARK_PURPLE + army.placeChestAimed(owner, kind));
    }

    /** Runs a strike on a roster player named in args[1]. Works for the owner online or the console. */
    private void targetedOrder(CommandSender sender, String[] args, java.util.function.Function<Player, String> action) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.GRAY + "Usage: " + args[0] + " <roster player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) sender.sendMessage(ChatColor.RED + "That player is not online.");
        else if (!consent.canTarget(target)) sender.sendMessage(ChatColor.RED + "Roster only. " + target.getName() + " is not on it.");
        else sender.sendMessage(action.apply(target));
    }

    /** team list | add <player> | remove <player>. Team members are never hit by Null weapons. */
    private void teamOrder(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "list";
        if (sub.equals("list")) {
            List<String> names = new java.util.ArrayList<>();
            for (UUID id : consent.allies()) {
                var off = Bukkit.getOfflinePlayer(id);
                names.add(off.getName() != null ? off.getName() : id.toString());
            }
            sender.sendMessage(ChatColor.GRAY + "Team: " + ChatColor.WHITE + ConsentManager.OWNER_NAME
                    + " (owner)" + (names.isEmpty() ? "" : ", " + String.join(", ", names)));
            return;
        }
        if ((sub.equals("add") || sub.equals("remove")) && args.length > 2) {
            String name = args[2];
            Player online = Bukkit.getPlayerExact(name);
            var off = Bukkit.getOfflinePlayer(name);
            if (online == null && !off.hasPlayedBefore()) {
                sender.sendMessage(ChatColor.RED + "Unknown player. They need to have joined the server once.");
                return;
            }
            UUID id = online != null ? online.getUniqueId() : off.getUniqueId();
            boolean changed = sub.equals("add") ? consent.addAlly(id) : consent.removeAlly(id);
            sender.sendMessage(changed
                    ? ChatColor.DARK_PURPLE + name + (sub.equals("add") ? " joined the team." : " left the team.")
                    : ChatColor.GRAY + "No change.");
            return;
        }
        sender.sendMessage(ChatColor.GRAY + "Usage: team list | team add <player> | team remove <player>");
    }

    private void handleAmbush(Player owner, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "status";
        boolean current = getConfig().getBoolean("nether-ambush", false);
        boolean next;
        switch (sub) {
            case "on" -> next = true;
            case "off" -> next = false;
            case "toggle" -> next = !current;
            default -> {
                owner.sendMessage(ChatColor.GRAY + "Nether ambush is " + (current ? "ON" : "OFF")
                        + ". Use /nullcmd ambush <on|off>.");
                return;
            }
        }
        getConfig().set("nether-ambush", next);
        saveConfig();
        owner.sendMessage(ChatColor.DARK_PURPLE + "Nether ambush " + (next ? "enabled" : "disabled") + ".");
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
        // Nulls don't use big screen text. Their messages go to chat.
        p.sendMessage(ChatColor.DARK_PURPLE + main + ChatColor.GRAY + (sub.isEmpty() ? "" : " " + sub));
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

    /** The player behind a command: the sender, or the owner when the console runs it. Null if the owner is offline. */
    public Player ownerFor(CommandSender sender) {
        if (sender instanceof Player p) return p;
        if (sender instanceof ConsoleCommandSender) return Bukkit.getPlayerExact(ConsentManager.OWNER_NAME);
        return null;
    }

    private boolean isOwner(CommandSender sender) {
        Player p = ownerFor(sender);
        return p != null && consent.isOwner(p);
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
        button(inv, 17, Material.BLAZE_ROD, "Mark Rod", "Look at a roster player: the Nulls hit them harder", "cmd", "nullrod", "mark");

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
        button(inv, 40, Material.RED_NETHER_BRICKS, "Nether Ambush: toggle", "Two hunters for roster players entering the Nether", "ambush", "toggle");
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
        button(inv, 27, Material.GLOWSTONE, "Flare Rod", "Lights roster players within 8 blocks", "cmd", "nullrod", "flare");
        button(inv, 46, Material.DEEPSLATE_BRICKS, "Build Stash Room", "Six stash chests at the Nether base", "cmd", "nullcmd", "stashroom");
        button(inv, 47, Material.OBSIDIAN, "Build Prison", "Walled cell block at this spot, Nether only", "build", "prison");
        button(inv, 48, Material.CLOCK, "Auto Work: toggle", "Idle Nulls mine and chop near you", "cmd", "nullcmd", "autowork", "toggle");
        button(inv, 49, Material.ENDER_EYE, "Auto Spy: toggle", "A hidden spy on roster players who join", "cmd", "nullcmd", "autospy", "toggle");

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

    // ---------- Nether ambush ----------

    /** When a roster player arrives in the Nether, two hunters appear for them if the ambush is on. */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (!getConfig().getBoolean("nether-ambush", false)) return;
        Player p = event.getPlayer();
        if (!consent.canTarget(p)) return;
        if (p.getWorld().getEnvironment() != World.Environment.NETHER) return;

        Player owner = Bukkit.getPlayerExact(ConsentManager.OWNER_NAME);
        if (owner == null) return;
        int minutes = getConfig().getInt("ambush-minutes", 3);
        army.spawnHunters(owner, p, p.getLocation(), 2, minutes);
        owner.sendMessage(ChatColor.DARK_PURPLE + "Nether ambush sent after " + p.getName() + ".");
    }

    // ---------- Rejoin watch ----------

    /** A roster player who logs off hurt or mid-fight leaves one hidden Null waiting where they'll come back. */
    @EventHandler
    public void onRosterQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (!consent.canTarget(p)) return;
        if (p.getHealth() < 8 || army.isEngaged(p.getUniqueId())) {
            army.markRejoin(p.getUniqueId(), p.getLocation(), p.getName());
        }
    }

    /**
     * Minecraft doesn't pass the H key to plugins, so the owner's F key opens the control panel
     * instead. F normally swaps the hand items, so that swap is cancelled for the owner.
     */
    @EventHandler
    public void onPanelKey(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        if (!consent.isOwner(p)) return;
        event.setCancelled(true);
        p.openInventory(buildMenu());
    }

    @EventHandler
    public void onOwnerPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        army.noteBuilding(event.getPlayer());
    }

    @EventHandler
    public void onRosterJoin(PlayerJoinEvent event) {
        army.releaseRejoin(event.getPlayer().getUniqueId());
        army.recordLogin(event.getPlayer().getUniqueId());
        army.autoSpy(event.getPlayer());
    }

    // ---------- Totem revival ----------

    /** A Null's totem popped: it goes into stasis at the Nether base instead of dying. */
    @EventHandler
    public void onTotemPop(org.bukkit.event.entity.EntityResurrectEvent event) {
        var npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getNPC(event.getEntity());
        if (npc != null) army.totemStasis(npc);
    }

    @EventHandler
    public void onNullDeath(NPCDeathEvent event) {
        if (army.owns(event.getNPC())) army.scheduleRevival(event.getNPC());
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
