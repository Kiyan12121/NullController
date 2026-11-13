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
import java.util.Deque;
import java.util.Set;

/**
 * Talk to the Commander: the owner types "null <message>" or "commander <message>" in chat. The message is kept private and the
 * reply goes only to the owner. Groq writes the words; the plugin decides what, if anything, is done,
 * and only from a short list of safe actions. The model can never attack, hunt, dismiss or strike.
 */
public final class CommanderChat implements Listener {
    private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    private static final Set<String> ACTIONS = Set.of("none", "status", "scout", "recall", "guard", "follow", "stay", "design");
    private static final int HISTORY_TURNS = 6;
    private static final long COOLDOWN_MS = 3000;

    private static final String PERSONA =
            "You are the Commander of the NULL army on a Minecraft server. You are pragmatic, deeply loyal to "
            + "the NULL, and lighthearted, with a dry humour. You are ruthless toward outsiders but warm to your team. "
            + "Your owner gives the orders. Keep lines short, under 40 words. Never claim you did something you did not do. "
            + "You may only request these actions: none, status, scout, recall, guard, follow, stay, design. "
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
            Bukkit.getScheduler().runTask(plugin, () -> designer.confirm(p));
            return;
        }
        if (text.equalsIgnoreCase("cancel")) {
            Bukkit.getScheduler().runTask(plugin, () -> designer.cancel(p));
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
                plugin.getLogger().warning("Commander chat failed: " + ex);
                reply = null;
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
        if (!say.isBlank()) army.commanderSay(owner, say);
        switch (action) {
            case "status" -> army.status(owner);
            case "scout" -> army.scoutReport(owner);
            case "recall" -> army.recall(owner);
            case "guard" -> army.setMode(NullArmy.Mode.GUARD, owner);
            case "follow" -> army.setMode(NullArmy.Mode.FOLLOW, owner);
            case "stay" -> army.setMode(NullArmy.Mode.STAY, owner);
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

    private void remember(String user, String reply) {
        history.addLast(new String[]{"user", user});
        history.addLast(new String[]{"assistant", reply});
        while (history.size() > HISTORY_TURNS * 2) history.removeFirst();
    }
}
