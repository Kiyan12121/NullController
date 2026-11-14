package com.daboss69yt.nullplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
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
            "hunt", "portal", "weapon", "bring", "mine", "lumber", "farm", "patrol", "stashroom",
            "ambush", "stabshot", "orbital", "mobcannon", "ranks", "forge", "explore", "totem", "pearl", "chests");
    private static final int HISTORY_TURNS = 6;
    private static final long COOLDOWN_MS = 3000;

    private static final String PERSONA =
            "You are the Commander of the NULL army on a Minecraft server. You are pragmatic, deeply loyal to "
            + "the NULL, and lighthearted, with a dry humour. You are ruthless toward outsiders but warm to your team. "
            + "Your owner gives the orders. Keep lines short, under 40 words. Never claim you did something you did not do. "
            + "You may only request these actions: none, status, scout, recall, guard, follow, stay, design, hunt, portal, weapon. "
            + "hunt sends the Nulls after one roster player named in \"target\"; the owner must confirm first, so say so. "
            + "portal opens a real nether portal in front of the owner. "
            + "bring opens a portal to a roster player who is in the Nether, named in \"target\". "
            + "mine, lumber, farm, patrol, forge and explore set the army to work. ranks reports each rank. chests reports the nearest chests. totem hands the owner a totem. pearl fires a pearl at the spot the owner is looking at. stashroom builds the stash room. "
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
            + "Reply ONLY with JSON in this form: {\"say\": \"<your line>\", \"action\": \"<one action from the list>\"}.";

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
    @EventHandler(priority = EventPriority.HIGH)
    public void onPortal(org.bukkit.event.player.PlayerPortalEvent e) {
        Player owner = e.getPlayer();
        UUID targetId = bringTo.get(owner.getUniqueId());
        if (targetId == null || e.getFrom().getWorld() == null) return;
        Long until = bringUntil.get(owner.getUniqueId());
        if (until == null || System.currentTimeMillis() > until) {
            bringTo.remove(owner.getUniqueId());
            return;
        }
        if (e.getFrom().getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL) return;
        Player target = Bukkit.getPlayer(targetId);
        bringTo.remove(owner.getUniqueId());
        bringUntil.remove(owner.getUniqueId());
        if (target == null || target.getWorld().getEnvironment() != org.bukkit.World.Environment.NETHER
                || !consent.canTarget(target)) {
            owner.sendMessage(ChatColor.GRAY + "The player you wanted has left the Nether.");
            return;
        }
        e.setCancelled(true);
        org.bukkit.Location t = target.getLocation();
        org.bukkit.util.Vector facing = t.getDirection().setY(0);
        if (facing.lengthSquared() < 0.0001) facing = new org.bukkit.util.Vector(1, 0, 0);
        facing.normalize();
        org.bukkit.Location dest = t.clone().add(facing.multiply(2));
        dest.setYaw(t.getYaw() + 180);
        dest.setPitch(0);
        owner.teleport(dest);
        // A portal across the path, three blocks behind the owner, so they can walk back.
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
        if (busy || now - lastAsk < COOLDOWN_MS) {
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
                reply = call(key, body);
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
        try {
            out = JsonParser.parseString(content).getAsJsonObject();
            say = out.has("say") ? out.get("say").getAsString() : "";
            action = out.has("action") ? out.get("action").getAsString().toLowerCase() : "none";
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
            case "portal" -> owner.sendMessage(ChatColor.DARK_PURPLE + army.buildPortal(owner));
            case "ranks" -> army.showRanks(owner);
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
                } else {
                    boolean inNether = target.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER;
                    String result = army.bringPortal(owner, target);
                    owner.sendMessage(ChatColor.DARK_PURPLE + result);
                    if (inNether && result.contains("open at")) {
                        bringTo.put(owner.getUniqueId(), target.getUniqueId());
                        bringUntil.put(owner.getUniqueId(), System.currentTimeMillis() + 10 * 60_000L);
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
