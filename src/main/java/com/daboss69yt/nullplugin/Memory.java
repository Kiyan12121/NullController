package com.daboss69yt.nullplugin;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Nulls' memory, kept in memory.yml in the plugin folder so it survives restarts.
 * Holds how hard each roster player has hit back (threat) and when each usually logs in.
 */
public final class Memory {
    private final File file;
    /** Learned per player: how hard the Nulls have been hit by them. */
    private final Map<UUID, Double> threat = new HashMap<>();
    /** Per player: login counts for each hour of the day (0-23). */
    private final Map<UUID, int[]> logins = new HashMap<>();

    public Memory(File folder) {
        this.file = new File(folder, "memory.yml");
        load();
    }

    /** The live threat map. The army reads and updates it directly. */
    public Map<UUID, Double> threat() {
        return threat;
    }

    /** Every player the Nulls have learned anything about. */
    public java.util.Set<UUID> known() {
        java.util.Set<UUID> all = new java.util.HashSet<>(logins.keySet());
        all.addAll(threat.keySet());
        return all;
    }

    public void recordLogin(UUID id, int hour) {
        logins.computeIfAbsent(id, k -> new int[24])[hour % 24]++;
        save();
    }

    /** True if this hour is one this player usually logs in at: at least three logins, and this hour is their top one or tied for it. */
    public boolean usuallyOnlineAt(UUID id, int hour) {
        int[] counts = logins.get(id);
        if (counts == null) return false;
        int total = 0;
        int max = 0;
        for (int c : counts) {
            total += c;
            max = Math.max(max, c);
        }
        return total >= 3 && counts[hour % 24] == max && max > 0;
    }

    /** Writes everything learned so far. Called on shutdown and whenever a new login is recorded. */
    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : threat.entrySet()) {
            yml.set("threat." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, int[]> e : logins.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (int c : e.getValue()) sb.append(c).append(',');
            yml.set("logins." + e.getKey(), sb.toString());
        }
        try {
            file.getParentFile().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            // Memory is best-effort: a failed save only costs what was learned since the last one.
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        var t = yml.getConfigurationSection("threat");
        if (t != null) {
            for (String key : t.getKeys(false)) {
                try {
                    threat.put(UUID.fromString(key), t.getDouble(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        var l = yml.getConfigurationSection("logins");
        if (l != null) {
            for (String key : l.getKeys(false)) {
                try {
                    String[] parts = l.getString(key, "").split(",");
                    int[] counts = new int[24];
                    for (int i = 0; i < Math.min(24, parts.length); i++) {
                        counts[i] = Integer.parseInt(parts[i].trim());
                    }
                    logins.put(UUID.fromString(key), counts);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }
}
