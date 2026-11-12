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
    private static final String ALLY_KEY = "allies";

    private final NullControllerPlugin plugin;
    private final Set<UUID> consented = ConcurrentHashMap.newKeySet();
    /** The team: allies the Null weapons must never hit. The owner is always on the team too. */
    private final Set<UUID> allies = ConcurrentHashMap.newKeySet();

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

        allies.clear();
        for (String entry : cfg.getStringList(ALLY_KEY)) {
            try {
                allies.add(UUID.fromString(entry.trim()));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping invalid UUID in team: " + entry);
            }
        }
    }

    /** Team means the owner or an ally. Null weapons never hit a team member. */
    public boolean isTeam(Player player) {
        return isOwner(player) || allies.contains(player.getUniqueId());
    }

    public Set<UUID> allies() {
        return Set.copyOf(allies);
    }

    public boolean addAlly(UUID id) {
        if (!allies.add(id)) return false;
        persistAllies();
        return true;
    }

    public boolean removeAlly(UUID id) {
        if (!allies.remove(id)) return false;
        persistAllies();
        return true;
    }

    private void persistAllies() {
        List<String> list = allies.stream().map(UUID::toString).sorted().toList();
        plugin.getConfig().set(ALLY_KEY, list);
        plugin.saveConfig();
    }

    public boolean isOwner(Player player) {
        return player.getName().equalsIgnoreCase(OWNER_NAME);
    }

    /** The only gate for hunting, caging, ambushing, or any other player-targeting action. */
    public boolean canTarget(Player target) {
        if (isTeam(target)) return false; // the owner and allies are never targeted, even if on the roster
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
