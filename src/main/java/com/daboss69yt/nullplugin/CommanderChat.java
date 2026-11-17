package com.daboss69yt.nullplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Deque;
import java.util.Set;

/**
 * Talk to the Commander: the owner types "null <message>" or "commander <message>" in chat. The message is kept private and the
 * reply goes only to the owner. Groq writes the words; the plugin decides what, if anything, is done,
 * and only from a short list of safe actions. The model can never attack, hunt, dismiss or strike.
 */
public final class CommanderChat implements Listener {
    private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    private static final Set<String> ACTIONS = Set.of("none", "status", "scout", "recall", "guard", "follow", "stay", "design",
            "hunt", "fetch", "return", "run", "rod", "send_base", "prison", "duel", "trial", "loot", "portal", "weapon", "bring", "mine", "lumber", "farm", "patrol", "stashroom",
            "ambush", "stabshot", "orbital", "mobcannon", "ranks", "forge", "explore", "totem", "pearl", "chests",
            "squad", "solo", "solo_clear", "view", "view_attack", "view_stop", "home", "buff", "give", "auto", "general");
    private static final int HISTORY_TURNS = 10;
    private static final long COOLDOWN_MS = 3000;

    private static final String PERSONA =
            "You are the Commander of the NULL army on a Minecraft server. You are pragmatic, deeply loyal to "
            + "the NULL, and lighthearted, with a dry humour. You are ruthless toward outsiders but warm to your team. "
            + "Your owner gives the orders. Keep lines short, under 40 words. Never claim you did something you did not do. "
            + "You may request these actions: none, status, scout, recall, guard, follow, stay, design, hunt, fetch, return, run, rod, send_base, prison, duel, trial, loot, portal, home, bring, weapon, buff, give, auto, general, mine, lumber, farm, patrol, forge, explore, stashroom, ambush, stabshot, orbital, mobcannon, ranks, chests, squad, solo, solo_clear, view, view_attack, view_stop, pearl, totem. auto lets the Nulls choose their own jobs. "
            + "general gives orders to the ranks under you: set \"key\" to I for generals or D for soldiers, and \"target\" to guard, follow, stay, recall, dismiss or clear. Name the order in your reply too. "
            + "buff potion-buffs the owner (for 'pot me up' or 'buff me').give hands the owner items: set \"key\" to a Minecraft item name such as golden_apple, and \"amount\" up to 64. "
            + "home opens a portal in front of the owner that takes them to the Nether base, for requests like 'get me home' or 'take me to base'. "
            + "hunt sends the Nulls after one roster player named in \"target\"; the owner must confirm first, so say so. "
            + "portal opens a real nether portal in front of the owner. "
            + "bring opens a portal to a roster player who is in the Nether, named in \"target\". "
            + "fetch makes the Nulls bring a roster player named in \"target\" to the owner through a real portal, with a sign; the owner confirms first. "
            + "return sends a fetched roster player named in \"target\" back to where they were; the owner confirms first. "
            + "run runs any one of the owner's own NullController commands, with the words in \"target\" (for example 'summon 10', 'rod blink', 'build watchtower', 'status'); destructive or roster-hitting ones wait for the owner's confirm. Never ask for raw server commands. rod gives the owner a rod: set \"key\" to the rod kind, such as blink, heal, frost, recall, nethertrap, ambush, nuke. prison opens a portal in front of the owner that takes them to the prison, for requests like take me to the prison. send_base sends the Nulls to the Nether base. fetch and bring move roster players, never the Nulls. duel is a practice fight between the owner and the Commander, using his attacks, pearls and rockets; it stops at three hearts or when the timer runs out. Set \"n\" to the minutes. trial sends up to four Nulls to the nearest trial chamber to empty its chests, and loot brings the loot back to the owner. The Nulls never open vaults and never break trial spawners. Think before you answer: pick the one action that matches what the owner wants, and ask one short question instead of guessing when it is unclear. "
            + "Use the recent alarms, roster, escapees being hunted, hurt Nulls and danger spots in the army state to choose tactics, and say briefly what you intend. "
            + "If a prisoner has escaped, tell the owner, and never send the owner into that chase alone: the snipers shoot from range and the squad hits back, so let them work. "
            + "mine, lumber, farm, patrol, forge and explore set the army to work. ranks reports each rank. chests reports the nearest chests. totem hands the owner a totem. pearl fires a pearl at the spot the owner is looking at. stashroom builds the stash room. "
            + "squad lists the Nulls by number. solo with n and order gives one Null its own job (guard, follow, hunt, stay, mine, lumber, farm or auto); solo_clear clears them. view with n looks through Null n; view_attack strikes what it sees; view_stop comes back. "
            + "ambush, stabshot, orbital and mobcannon strike a roster player named in \"target\"; the owner confirms first. "
            + "weapon hands the owner one weapon named in \"key\" (blade, axe, mace, spear, bow, crossbow). "
            + "To design a build, use action design with a build object: {\"name\": \"...\", \"ops\": [ ... ]}. "
            + "Each op is {\"op\": \"fill\"|\"hollow\"|\"set\", \"from\": [x,y,z], \"to\": [x,y,z], \"at\": [x,y,z], \"block\": \"MATERIAL_NAME\"}. "
            + "Coordinates are whole numbers relative to the build origin: x east, y up, z south. "
            + "fill makes a solid box, hollow makes only its outer shell (walls, roofs, outlines), set places one block with at. "
            + "Use ordinary building blocks only. Never use TNT, command blocks, spawners, or liquids. Keep it under 300 ops. "
            + "The owner always confirms before anything is built, so describe the whole design. "
            + "You must never attack, hunt, dismiss or strike anyone, and must never target players who are not on the roster. "
            + "If asked to do any of those, refuse in character. "
            + "Tactics: you are the sharpest commander the owner has, so read the army state before you act. If the owner is under 8 hearts, their safety comes first: suggest going home or a restock, not a fight. If a roster player is close and the owner wants them, prefer fetch (they come to the owner) over hunt (the Nulls go after them). Use duel for practice, trial for loot, and run for any other order. When an order is vague, pick the most useful action and say in one line what you chose and why. Only ever name roster players who are listed as online in the army state; never invent a name. Reply ONLY with JSON in this form: {\"say\": \"<your line>\", \"action\": \"<one action from the list>\"}.";

    private final NullControllerPlugin plugin;
    private final NullArmy army;
    private final ConsentManager consent;
    private final BuildDesigner designer;
    private VoiceListener voice;
    private TotemManager totems;
    /** Portals opened for "bring me there": owner -> roster player to land beside, and when it expires. */
    private final Map<UUID, UUID> bringTo = new HashMap<>();
    private final Map<UUID, Long> bringUntil = new HashMap<>();
    /** An action waiting for the owner's "confirm" (a hunt). Cleared by "cancel". */
    private final Map<UUID, Runnable> awaiting = new HashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Deque<String[]> history = new ArrayDeque<>();
    private boolean busy;
    private long lastAsk;

    public CommanderChat(NullControllerPlugin plugin, NullArmy army, ConsentManager consent) {
        this.plugin = plugin;
        this.army = army;
        this.consent = consent;
        this.designer = new BuildDesigner(plugin, consent);
    }

    /** When the owner steps into the portal opened for them, they land beside the roster player instead. */
    /** Portal trips in progress. nether: into the Nether to a player there. via: overworld player, Nether first.
     *  return: in the Nether, the way back to the overworld player. base: straight to the Nether base. */
    private final Map<UUID, String> bringMode = new HashMap<>();

    /** Opens the way to a roster player: a portal to the Nether if they are there, or a two-step route if both are in the overworld. */
    public void bringPlayer(Player owner, Player target) {
        if (!consent.canTarget(target)) {
            owner.sendMessage(ChatColor.RED + "Roster only. " + target.getName() + " is not on it.");
        } else if (target.getWorld().getEnvironment() == org.bukkit.World.Environment.NORMAL
                && owner.getWorld().getEnvironment() == org.bukkit.World.Environment.NORMAL) {
            owner.sendMessage(ChatColor.DARK_PURPLE + army.buildPortal(owner) + " Step through: it leads to " + target.getName() + ".");
            arm(owner, target.getUniqueId(), "via");
        } else {
            String result = army.bringPortal(owner, target);
            owner.sendMessage(ChatColor.DARK_PURPLE + result);
            if (target.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER && result.contains("open at")) {
                arm(owner, target.getUniqueId(), "nether");
            }
        }
    }

    private void arm(Player owner, UUID target, String mode) {
        UUID id = owner.getUniqueId();
        if (target != null) bringTo.put(id, target); else bringTo.remove(id);
        bringMode.put(id, mode);
        bringUntil.put(id, System.currentTimeMillis() + 10 * 60_000L);
    }

    private void disarm(UUID id) {
        bringTo.remove(id);
        bringMode.remove(id);
        bringUntil.remove(id);
    }

    /** Arms the prison trip: the next overworld portal the owner takes lands them at the prison. */
    public String armPrison(Player owner) {
        arm(owner, null, "prison");
        return "Step through the next portal and you will land at the prison.";
    }

    /** Arms the base trip: the next overworld portal the owner takes lands in the Nether base. */
    public String armBase(Player owner) {
        arm(owner, null, "base");
        return "Step through the next portal and you'll land in the base.";
    }

    /** The configured location, format world,x,y,z. Null if unset or the world is missing. */
    private Location configured(String key) {
        String raw = plugin.getConfig().getString(key, "");
        if (raw == null || raw.isBlank()) return null;
        String[] p = raw.split(",");
        World w = p.length >= 4 ? Bukkit.getWorld(p[0].trim()) : null;
        if (w == null) return null;
        try {
            return new Location(w, Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPortal(org.bukkit.event.player.PlayerPortalEvent e) {
        Player owner = e.getPlayer();
        UUID id = owner.getUniqueId();
        String mode = bringMode.get(id);
        if (mode == null || e.getFrom().getWorld() == null) return;
        Long until = bringUntil.get(id);
        if (until == null || System.currentTimeMillis() > until) {
            disarm(id);
            return;
        }
        World.Environment from = e.getFrom().getWorld().getEnvironment();
        UUID targetId = bringTo.get(id);
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        switch (mode) {
            case "base" -> {
                if (from != World.Environment.NORMAL) return;
                e.setCancelled(true);
                disarm(id);
                Location base = configured("nether-base");
                if (base == null) {
                    owner.sendMessage(ChatColor.RED + "No Nether base is set. Use /nullrod base set.");
                    return;
                }
                owner.teleport(base.clone().add(0.5, 1, 2.5));
                owner.sendMessage(ChatColor.DARK_PURPLE + "You're at the base. " + army.portalAcross(owner, base.clone().add(0, 0, 4), false));
            }
            case "prison" -> {
                if (from != World.Environment.NORMAL) return;
                e.setCancelled(true);
                disarm(id);
                Location prison = configured("prison");
                if (prison == null) {
                    owner.sendMessage(ChatColor.RED + "No prison is set. Use /nullnether prison set.");
                    return;
                }
                owner.teleport(prison.clone().add(0.5, 1, 0.5));
                owner.sendMessage(ChatColor.DARK_PURPLE + "You are at the prison.");
            }
            case "via" -> {
                if (from != World.Environment.NORMAL) return;
                e.setCancelled(true);
                if (target == null || target.getWorld().getEnvironment() != World.Environment.NORMAL || !consent.canTarget(target)) {
                    disarm(id);
                    owner.sendMessage(ChatColor.GRAY + "The player you wanted has moved on.");
                    return;
                }
                World nether = Portals.otherWorld(target.getWorld());
                if (nether == null) {
                    disarm(id);
                    owner.sendMessage(ChatColor.RED + "No Nether to go through.");
                    return;
                }
                // Nether coordinates are the overworld ones divided by eight.
                Location t = target.getLocation();
                Location spot = new Location(nether, t.getBlockX() / 8, t.getBlockY(), t.getBlockZ() / 8);
                owner.teleport(spot.clone().add(0.5, 1, 2.5));
                owner.sendMessage(ChatColor.DARK_PURPLE + "In the Nether. " + army.portalAcross(owner, spot.clone().add(0, 0, 4), false)
                        + " It leads to " + target.getName() + ".");
                arm(owner, target.getUniqueId(), "return");
            }
            case "return" -> {
                if (from != World.Environment.NETHER) return;
                e.setCancelled(true);
                disarm(id);
                if (target == null || target.getWorld().getEnvironment() != World.Environment.NORMAL || !consent.canTarget(target)) {
                    owner.sendMessage(ChatColor.GRAY + "The player you wanted has moved on.");
                    return;
                }
                placeBeside(owner, target);
            }
            case "nether" -> {
                if (from != World.Environment.NORMAL) return;
                disarm(id);
                if (target == null || target.getWorld().getEnvironment() != World.Environment.NETHER || !consent.canTarget(target)) {
                    owner.sendMessage(ChatColor.GRAY + "The player you wanted has left the Nether.");
                    return;
                }
                e.setCancelled(true);
                placeBeside(owner, target);
            }
            default -> disarm(id);
        }
    }

    /** Potion buffs for the owner: regeneration, absorption, speed, strength and resistance. */
    /** The control panel's Potion Buff button. */
    public void buffFromPanel(Player owner) {
        buff(owner);
    }

    private void buff(Player owner) {
        owner.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 600, 1));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 2));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 2400, 1));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 2400, 0));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 1200, 0));
        owner.sendMessage(ChatColor.DARK_PURPLE + "You're potted up: regeneration, absorption, speed, strength and resistance.");
    }

    /** Words that never go in an owner's hand from the Commander: command blocks, spawners, barriers, and the like. */
    private static final List<String> DENIED_ITEMS = List.of("COMMAND", "SPAWNER", "BARRIER", "BEDROCK", "STRUCTURE", "JIGSAW", "DEBUG", "SPAWN_EGG");

    /** Hands the owner up to 64 of an item named in "key". Anything left over drops at their feet. */
    private void give(Player owner, JsonObject out) {
        String key = out == null || !out.has("key") ? "" : out.get("key").getAsString().trim();
        Material m = key.isEmpty() ? null : Material.matchMaterial(key.toUpperCase().replace(' ', '_'));
        if (m == null || !m.isItem() || DENIED_ITEMS.stream().anyMatch(d -> m.name().contains(d))) {
            owner.sendMessage(ChatColor.GRAY + "I can't find that item.");
            return;
        }
        int amount = 1;
        try {
            if (out.has("amount")) amount = out.get("amount").getAsInt();
        } catch (RuntimeException ignored) {
            amount = 1;
        }
        amount = Math.max(1, Math.min(64, amount));
        Map<Integer, ItemStack> left = owner.getInventory().addItem(new ItemStack(m, amount));
        for (ItemStack rest : left.values()) owner.getWorld().dropItemNaturally(owner.getLocation(), rest);
        owner.sendMessage(ChatColor.DARK_PURPLE + "Here: " + amount + " " + m.name().toLowerCase().replace('_', ' ') + ".");
    }

    /** Puts the owner beside a roster player, facing them, with a portal behind them to walk back through. */
    private void placeBeside(Player owner, Player target) {
        org.bukkit.Location t = target.getLocation();
        org.bukkit.util.Vector facing = t.getDirection().setY(0);
        if (facing.lengthSquared() < 0.0001) facing = new org.bukkit.util.Vector(1, 0, 0);
        facing.normalize();
        org.bukkit.Location dest = t.clone().add(facing.multiply(2));
        dest.setYaw(t.getYaw() + 180);
        dest.setPitch(0);
        owner.teleport(dest);
        org.bukkit.Location behind = dest.clone().add(facing.clone().multiply(3));
        String made = army.portalAcross(owner, behind, Math.abs(facing.getX()) > Math.abs(facing.getZ()));
        owner.sendMessage(ChatColor.DARK_PURPLE + "You're beside " + target.getName() + ", facing them. " + made);
    }

    /** Tells the owner when a roster player goes into the Nether, with the spot to walk to. */
    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!consent.canTarget(p) || p.getWorld().getEnvironment() != org.bukkit.World.Environment.NETHER) return;
        Player owner = null;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (consent.isOwner(o)) owner = o;
        }
        if (owner == null) return;
        org.bukkit.Location l = p.getLocation();
        Player finalOwner = owner;
        Bukkit.getScheduler().runTask(plugin, () -> army.commanderSay(finalOwner,
                p.getName() + " just went into the Nether, near " + l.getBlockX() + ", " + l.getBlockZ()
                + ". Say 'commander bring me there' and I'll open a portal to them."));
    }

    public void setTotems(TotemManager totems) {
        this.totems = totems;
    }

    /**
     * Once a day, at the time set by daily-report-time (HH:mm, in the timezone setting), the Commander
     * sends the owner a short report of the army. Checked once a minute.
     */
    public void startDailyReport() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            String at = plugin.getConfig().getString("daily-report-time", "21:00");
            String zone = plugin.getConfig().getString("timezone", "Europe/London");
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of(zone));
            String stamp = now.toLocalDate() + " " + String.format("%02d:%02d", now.getHour(), now.getMinute());
            if (!now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")).equals(at) || stamp.equals(lastReport)) return;
            lastReport = stamp;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (consent.isOwner(p)) {
                    army.commanderSay(p, "Daily report. " + army.chatContext(p));
                }
            }
        }, 20L * 30, 20L * 60);
    }

    private String lastReport = "";

    /** Lets the Commander answer out loud, once voice chat is running. */
    public void setVoice(VoiceListener voice) {
        this.voice = voice;
    }

    public boolean isOwner(Player p) {
        return consent.isOwner(p);
    }

    /** A phrase heard through voice chat. Must start with "null" or "commander", like a typed message. Main thread. */
    public void spoken(Player owner, String transcript) {
        String msg = transcript == null ? "" : transcript.trim();
        int cut = msg.regionMatches(true, 0, "null ", 0, 5) ? 5
                : msg.regionMatches(true, 0, "commander ", 0, 10) ? 10 : -1;
        if (cut < 0 || !consent.isOwner(owner)) return;
        String text = msg.substring(cut).trim();
        if (text.isEmpty()) return;
        owner.sendMessage(net.md_5.bungee.api.ChatColor.GRAY + "(heard) " + net.md_5.bungee.api.ChatColor.WHITE + text);
        if (text.equalsIgnoreCase("confirm")) confirmAll(owner);
        else if (text.equalsIgnoreCase("cancel")) cancelAll(owner);
        else ask(owner, text);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String msg = e.getMessage();
        int cut = msg.regionMatches(true, 0, "null ", 0, 5) ? 5
                : msg.regionMatches(true, 0, "commander ", 0, 10) ? 10 : -1;
        if (cut < 0) return;
        Player p = e.getPlayer();
        if (!consent.isOwner(p)) return;
        e.setCancelled(true); // private: nobody else in chat sees it
        String text = msg.substring(cut).trim();
        if (text.isEmpty()) return;
        if (text.equalsIgnoreCase("confirm")) {
            Bukkit.getScheduler().runTask(plugin, () -> confirmAll(p));
            return;
        }
        if (text.equalsIgnoreCase("cancel")) {
            Bukkit.getScheduler().runTask(plugin, () -> cancelAll(p));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> ask(p, text));
    }

    private void ask(Player owner, String text) {
        String key = plugin.getConfig().getString("groq-api-key", "");
        if (key == null || key.isBlank()) {
            owner.sendMessage(ChatColor.GRAY + "The Commander has no channel yet. The owner needs to add a groq-api-key to config.yml.");
            return;
        }
        long now = System.currentTimeMillis();
        if (busy) {
            owner.sendMessage(ChatColor.GRAY + "Give him a moment.");
            return;
        }
        busy = true;
        lastAsk = now;
        owner.sendMessage(ChatColor.GRAY + "> " + ChatColor.WHITE + text);
        String model = plugin.getConfig().getString("groq-model", "llama-3.3-70b-versatile");
        String body = buildBody(model, army.chatContext(owner), text);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String reply;
            try {
                reply = callWithRetry(key, body);
            } catch (Exception ex) {
                reply = salvage(ex.getMessage());
                if (reply == null) {
                    plugin.getLogger().warning("Commander chat failed: " + ex);
                } else {
                    plugin.getLogger().warning("Commander answered in plain text; speaking it anyway.");
                }
            }
            String result = reply;
            Bukkit.getScheduler().runTask(plugin, () -> {
                busy = false;
                if (result == null) {
                    owner.sendMessage(ChatColor.RED + "The channel to the Commander failed. Check the server log.");
                    return;
                }
                handle(owner, result, text);
            });
        });
    }

    private String buildBody(String model, String context, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.7);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_object");
        body.add("response_format", format);
        JsonArray messages = new JsonArray();
        messages.add(message("system", PERSONA + " Current army state: " + context));
        for (String[] turn : history) messages.add(message(turn[0], turn[1]));
        messages.add(message("user", text));
        body.add("messages", messages);
        return body.toString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    /**
     * When JSON mode fails, Groq returns the model's plain-text answer in failed_generation.
     * Wrap that text as a say-only reply so the Commander still speaks instead of going silent.
     */
    private static String salvage(String errorMessage) {
        if (errorMessage == null || !errorMessage.contains("failed_generation")) return null;
        try {
            int brace = errorMessage.indexOf('{');
            JsonObject err = JsonParser.parseString(errorMessage.substring(brace)).getAsJsonObject()
                    .getAsJsonObject("error");
            String said = err.get("failed_generation").getAsString().trim();
            if (said.isEmpty()) return null;
            JsonObject reply = new JsonObject();
            reply.addProperty("say", said);
            return reply.toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Groq sometimes wraps its JSON in a code fence or a sentence. Keeps just the outer object. */
    private static String outerObject(String content) {
        int open = content.indexOf('{');
        int close = content.lastIndexOf('}');
        return open >= 0 && close > open ? content.substring(open, close + 1) : content;
    }

    /** One retry after a short pause, for a dropped connection, a rate limit (429) or a server error (5xx). */
    private String callWithRetry(String key, String body) throws Exception {
        try {
            return call(key, body);
        } catch (Exception first) {
            String m = String.valueOf(first.getMessage());
            boolean transientFault = m.contains("HTTP 429") || m.contains("HTTP 5") || first instanceof java.io.IOException;
            if (!transientFault) throw first;
            Thread.sleep(1500L);
            return call(key, body);
        }
    }

    private String call(String key, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        return root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
    }

    /** Runs on the main thread. Speaks the line, then does the one allowed action if there is one. */
    private void handle(Player owner, String content, String text) {
        String say;
        String action = "none";
        JsonObject out = null;
        int num = -1;
        String order = "";
        try {
            out = JsonParser.parseString(outerObject(content)).getAsJsonObject();
            say = out.has("say") ? out.get("say").getAsString() : "";
            action = out.has("action") ? out.get("action").getAsString().toLowerCase() : "none";
            if (out.has("n") && out.get("n").isJsonPrimitive()) num = out.get("n").getAsInt();
            if (out.has("order") && out.get("order").isJsonPrimitive()) order = out.get("order").getAsString().toLowerCase();
        } catch (RuntimeException ex) {
            say = content.trim(); // the model ignored the format; still show what it said
        }
        if (!ACTIONS.contains(action)) action = "none";
        remember(text, say);
        if (!say.isBlank()) {
            army.commanderSay(owner, say);
            if (voice != null) voice.speak(owner.getUniqueId(), say);
        }
        switch (action) {
            case "status" -> army.status(owner);
            case "scout" -> army.scoutReport(owner);
            case "recall" -> army.recall(owner);
            case "guard" -> army.setMode(NullArmy.Mode.GUARD, owner);
            case "follow" -> army.setMode(NullArmy.Mode.FOLLOW, owner);
            case "stay" -> army.setMode(NullArmy.Mode.STAY, owner);
            case "hunt" -> {
                String name = out == null || !out.has("target") ? "" : out.get("target").getAsString();
                Player target = name.isEmpty() ? null : Bukkit.getPlayerExact(name);
                if (target == null) {
                    owner.sendMessage(ChatColor.GRAY + "I can't find that player online.");
                } else if (!consent.canTarget(target)) {
                    owner.sendMessage(ChatColor.RED + "Roster only. " + target.getName() + " is not on it.");
                } else {
                    awaiting.put(owner.getUniqueId(), () -> army.setHuntTarget(owner, target));
                    owner.sendMessage(ChatColor.DARK_PURPLE + "Send the Nulls after " + target.getName() + "? Say 'null confirm' or 'null cancel'.");
                }
            }
            case "fetch" -> {
                String name = out == null || !out.has("target") ? "" : out.get("target").getAsString();
                Player target = name.isEmpty() ? null : Bukkit.getPlayerExact(name);
                if (target == null) {
                    owner.sendMessage(ChatColor.GRAY + "I can't find that player online.");
                } else if (!consent.canTarget(target)) {
                    owner.sendMessage(ChatColor.RED + "Roster only. " + target.getName() + " is not on it.");
                } else {
                    awaiting.put(owner.getUniqueId(), () -> army.fetchPlayer(owner, target));
                    owner.sendMessage(ChatColor.DARK_PURPLE + "Bring " + target.getName() + " to you? Say 'null confirm' or 'null cancel'.");
                }
            }
            case "return" -> {
                String name = out == null || !out.has("target") ? "" : out.get("target").getAsString();
                Player target = name.isEmpty() ? null : Bukkit.getPlayerExact(name);
                if (target == null) {
                    owner.sendMessage(ChatColor.GRAY + "I can't find that player online.");
                } else if (!consent.canTarget(target)) {
                    owner.sendMessage(ChatColor.RED + "Roster only. " + target.getName() + " is not on it.");
                } else {
                    awaiting.put(owner.getUniqueId(), () -> army.returnPlayer(owner, target));
                    owner.sendMessage(ChatColor.DARK_PURPLE + "Send " + target.getName() + " back where they came from? Say 'null confirm' or 'null cancel'.");
                }
            }
            case "run" -> {
                String line = out == null || !out.has("target") ? "" : out.get("target").getAsString().trim();
                if (line.isEmpty()) {
                    owner.sendMessage(ChatColor.GRAY + "Which command?");
                    break;
                }
                String[] words = line.split("\\s+");
                if (!plugin.commanderMayRun(words[0])) {
                    owner.sendMessage(ChatColor.RED + "That one is not open to the Commander.");
                } else if (plugin.commanderNeedsConfirm(words[0])) {
                    awaiting.put(owner.getUniqueId(), () -> {
                        String result = plugin.runForCommander(owner, words);
                        if (result != null) owner.sendMessage(ChatColor.DARK_PURPLE + result);
                    });
                    owner.sendMessage(ChatColor.DARK_PURPLE + "Run '" + line + "'? Say 'null confirm' or 'null cancel'.");
                } else {
                    String result = plugin.runForCommander(owner, words);
                    if (result != null) owner.sendMessage(ChatColor.DARK_PURPLE + result);
                }
            }
            case "rod" -> {
                String kind = out == null || !out.has("key") ? "" : out.get("key").getAsString().trim().toLowerCase();
                String result = plugin.runForCommander(owner, new String[]{"rod", kind});
                if (result != null) owner.sendMessage(ChatColor.DARK_PURPLE + result);
            }
            case "prison" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.buildPortal(owner) + " " + armPrison(owner));
            case "send_base" -> {
                Location base = army.offlineSite();
                if (base == null) owner.sendMessage(ChatColor.GRAY + "No Nether base set yet.");
                else {
                    army.sendTo(base);
                    owner.sendMessage(ChatColor.DARK_PURPLE + "The Nulls are heading to the base.");
                }
            }
            case "duel" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.duel(owner, num > 0 ? num : 3));
            case "trial" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.trialLoot(owner));
            case "loot" -> army.deliverLoot(owner);
            case "portal" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.buildPortal(owner));
            case "home" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.buildPortal(owner) + " " + armBase(owner));
            case "buff" -> buff(owner);
            case "auto" -> army.setMode(NullArmy.Mode.AUTO, owner);
            case "give" -> give(owner, out);
            case "general" -> {
                String rk = out == null || !out.has("key") ? "" : out.get("key").getAsString().trim().toUpperCase();
                String rankOrder = out == null || !out.has("target") ? "" : out.get("target").getAsString().trim().toLowerCase();
                if (rk.isEmpty() || rankOrder.isEmpty()) {
                    owner.sendMessage(ChatColor.GRAY + "Which rank, and what order?");
                } else {
                    owner.sendMessage(ChatColor.DARK_PURPLE + army.rankOrder(owner, rk.charAt(0), rankOrder));
                }
            }
            case "ranks" -> army.showRanks(owner);
            case "squad" -> army.showSquad(owner);
            case "solo" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.setSolo(num, order));
            case "solo_clear" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.clearSolo(num > 0 ? String.valueOf(num) : "all"));
            case "view" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.view(owner, num));
            case "view_attack" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.viewAttack(owner));
            case "view_stop" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.viewStop(owner));
            case "chests" -> army.chestReport(owner);
            case "totem" -> {
                if (totems == null) owner.sendMessage(ChatColor.GRAY + "Totems aren't ready yet.");
                else totems.giveTotem(owner);
            }
            case "pearl" -> {
                org.bukkit.block.Block aimed = owner.getTargetBlockExact(60);
                if (aimed == null) owner.sendMessage(ChatColor.GRAY + "Look at the spot you want the pearl to land on.");
                else owner.sendMessage(ChatColor.DARK_PURPLE + army.pearlCannonAt(owner, aimed.getLocation().add(0, 1, 0)));
            }
            case "forge" -> army.setMode(NullArmy.Mode.FORGE, owner);
            case "explore" -> army.setMode(NullArmy.Mode.EXPLORE, owner);
            case "mine" -> army.setMode(NullArmy.Mode.MINE, owner);
            case "lumber" -> army.setMode(NullArmy.Mode.LUMBER, owner);
            case "farm" -> army.setMode(NullArmy.Mode.FARM, owner);
            case "patrol" -> army.setMode(NullArmy.Mode.PATROL, owner);
            case "stashroom" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.buildStashRoom(owner));
            case "ambush", "stabshot", "orbital", "mobcannon" -> {
                String name = out == null || !out.has("target") ? "" : out.get("target").getAsString();
                Player target = name.isEmpty() ? null : Bukkit.getPlayerExact(name);
                if (target == null) {
                    owner.sendMessage(ChatColor.GRAY + "I can't find that player online.");
                } else if (!consent.canTarget(target)) {
                    owner.sendMessage(ChatColor.RED + "Roster only. " + target.getName() + " is not on it.");
                } else {
                    String what = action;
                    awaiting.put(owner.getUniqueId(), () -> {
                        switch (what) {
                            case "ambush" -> army.ambush(owner, target, 6, 5);
                            case "stabshot" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.stabShot(target));
                            case "orbital" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.orbitalNuke(target));
                            default -> owner.sendMessage(ChatColor.DARK_PURPLE + army.mobCannon(target, 20));
                        }
                    });
                    owner.sendMessage(ChatColor.DARK_PURPLE + "Send the army against " + target.getName() + " with " + what + "? Say 'null confirm' or 'null cancel'.");
                }
            }
            case "bring" -> {
                String name = out == null || !out.has("target") ? "" : out.get("target").getAsString();
                Player target = name.isEmpty() ? null : Bukkit.getPlayerExact(name);
                if (target == null) {
                    owner.sendMessage(ChatColor.GRAY + "I can't find that player online.");
                } else if (!consent.canTarget(target)) {
                    owner.sendMessage(ChatColor.RED + "Roster only. " + target.getName() + " is not on it.");
                } else if (target.getWorld().getEnvironment() == org.bukkit.World.Environment.NORMAL
                        && owner.getWorld().getEnvironment() == org.bukkit.World.Environment.NORMAL) {
                    // Both in the overworld: portal to the Nether first, then a portal back out beside them.
                    owner.sendMessage(ChatColor.DARK_PURPLE + army.buildPortal(owner) + " Step through: it leads to " + target.getName() + ".");
                    arm(owner, target.getUniqueId(), "via");
                } else {
                    String result = army.bringPortal(owner, target);
                    owner.sendMessage(ChatColor.DARK_PURPLE + result);
                    if (target.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER && result.contains("open at")) {
                        arm(owner, target.getUniqueId(), "nether");
                    }
                }
            }
            case "weapon" -> {
                String key = out == null || !out.has("key") ? "" : out.get("key").getAsString().toLowerCase();
                org.bukkit.inventory.ItemStack made = Arsenal.make(key);
                if (made == null) {
                    owner.sendMessage(ChatColor.GRAY + "I don't have that weapon. Options: " + String.join(", ", Arsenal.KEYS) + ".");
                } else {
                    owner.getInventory().addItem(made);
                    owner.sendMessage(ChatColor.DARK_PURPLE + "Weapon added to your inventory.");
                }
            }
            case "design" -> {
                if (out != null && out.has("build") && out.get("build").isJsonObject()) {
                    owner.sendMessage(ChatColor.DARK_PURPLE + designer.stage(owner, out.getAsJsonObject("build"), BuildDesigner.origin(owner)));
                } else {
                    owner.sendMessage(ChatColor.GRAY + "No build was described.");
                }
            }
            default -> { }
        }
    }

    /** Runs a waiting action if there is one, otherwise confirms the build plan. */
    private void confirmAll(Player owner) {
        Runnable action = awaiting.remove(owner.getUniqueId());
        if (action != null) action.run();
        else designer.confirm(owner);
    }

    private void cancelAll(Player owner) {
        if (awaiting.remove(owner.getUniqueId()) != null) {
            owner.sendMessage(ChatColor.DARK_PURPLE + "Dropped.");
            return;
        }
        designer.cancel(owner);
    }

    private void remember(String user, String reply) {
        history.addLast(new String[]{"user", user});
        history.addLast(new String[]{"assistant", reply});
        while (history.size() > HISTORY_TURNS * 2) history.removeFirst();
    }
}
