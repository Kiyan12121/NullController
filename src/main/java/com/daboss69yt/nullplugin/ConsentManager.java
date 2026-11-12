package com.daboss69yt.nullplugin;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for who the Nulls may act upon.
 * Every player-targeting action must call canTarget() first.
 */
public final class ConsentManager {

    public static final String OWNER_NAME = "daboss69yt";
    private static final String CONFIG_KEY = "consented-players";

    private final NullControllerPlugin plugin;
    private final Set<UUID> consented = ConcurrentHashMap.newKeySet();

    public ConsentManager(NullControllerPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Re-reads config.yml. Called on enable and by /nullreload. */
    public void reload() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        List<String> raw = cfg.getStringList(CONFIG_KEY);

        Set<UUID> fresh = new HashSet<>();
        for (String entry : raw) {
            try {
                fresh.add(UUID.fromString(entry.trim()));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping invalid UUID in consent roster: " + entry);
            }
        }
        consented.clear();
        consented.addAll(fresh);
    }

    public boolean isOwner(Player player) {
        return player.getName().equalsIgnoreCase(OWNER_NAME);
    }

    /** The only gate for hunting, caging, ambushing, or any other player-targeting action. */
    public boolean canTarget(Player target) {
        if (isOwner(target)) return false;
        return consented.contains(target.getUniqueId());
    }

    /** Roster check by UUID, for players who are offline. */
    public boolean isConsented(UUID id) {
        return consented.contains(id);
    }

    /** Adds a player to the roster and writes it to config.yml. */
    public boolean grant(Player player) {
        if (!consented.add(player.getUniqueId())) {
            return false;
        }
        List<String> list = consented.stream().map(UUID::toString).sorted().toList();
        plugin.getConfig().set(CONFIG_KEY, list);
        plugin.saveConfig();
        return true;
    }
}
