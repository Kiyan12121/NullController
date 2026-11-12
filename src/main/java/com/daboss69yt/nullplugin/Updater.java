package com.daboss69yt.nullplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Checks the latest GitHub release. If it's newer than the running plugin, the jar is downloaded into
 * the server's plugins/update folder, which Purpur installs on the next restart. Nothing is installed
 * while the server is running, and the download is checked to be a NullController jar first.
 */
public final class Updater implements CommandExecutor {

    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
    private static final Pattern JAR = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.jar)\"");

    private final NullControllerPlugin plugin;
    private final ConsentManager consent;

    public Updater(NullControllerPlugin plugin, ConsentManager consent) {
        this.plugin = plugin;
        this.consent = consent;
    }

    /** Checks at start-up, then every six hours. */
    public void start() {
        if (!plugin.getConfig().getBoolean("auto-update", true)) return;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> check(false), 20L * 30, 20L * 60 * 60 * 6);
    }

    /** Runs on an async thread. Messages the owner on the main thread. */
    public void check(boolean manual) {
        try {
            String repo = plugin.getConfig().getString("update-repo", "Kiyan12121/NullController");
            String json = get("https://api.github.com/repos/" + repo + "/releases/latest");
            Matcher tag = TAG.matcher(json);
            Matcher jar = JAR.matcher(json);
            if (!tag.find() || !jar.find()) {
                say(manual, ChatColor.GRAY + "No release found to update from.");
                return;
            }
            String remote = tag.group(1);
            String current = plugin.getDescription().getVersion();
            if (compare(remote, current) <= 0) {
                say(manual, ChatColor.GRAY + "NullController is up to date (" + current + ").");
                return;
            }

            File dir = new File(plugin.getDataFolder().getParentFile(), "update");
            dir.mkdirs();
            File part = new File(dir, "NullController.jar.part");
            download(jar.group(1), part);
            if (!isNullControllerJar(part)) {
                Files.deleteIfExists(part.toPath());
                say(manual, ChatColor.RED + "Update " + remote + " was rejected: not a NullController jar.");
                plugin.getLogger().warning("Downloaded update failed the jar check. Not installed.");
                return;
            }
            Files.move(part.toPath(), new File(dir, "NullController.jar").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            say(true, ChatColor.DARK_PURPLE + "NullController " + remote + " downloaded. It installs on the next restart.");
        } catch (Exception ex) {
            plugin.getLogger().warning("Update check failed: " + ex.getMessage());
            say(manual, ChatColor.RED + "Update check failed: " + ex.getMessage());
        }
    }

    /** /nullupdate: owner only. Checks now. */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p) || !consent.isOwner(p)) {
            sender.sendMessage(ChatColor.RED + "Access denied.");
            return true;
        }
        sender.sendMessage(ChatColor.GRAY + "Checking for an update...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> check(true));
        return true;
    }

    private void say(boolean toOwner, String message) {
        if (!toOwner) {
            plugin.getLogger().info(ChatColor.stripColor(message));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player o = Bukkit.getPlayerExact(ConsentManager.OWNER_NAME);
            if (o != null) o.sendMessage(message);
            plugin.getLogger().info(ChatColor.stripColor(message));
        });
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "NullController-Updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "NullController-Updater");
        try (InputStream in = c.getInputStream()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            c.disconnect();
        }
    }

    /** A real NullController jar has a plugin.yml that names it. */
    private static boolean isNullControllerJar(File f) {
        try (ZipFile z = new ZipFile(f)) {
            var entry = z.getEntry("plugin.yml");
            if (entry == null) return false;
            String yml = new String(z.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            return yml.contains("name: NullController");
        } catch (Exception ex) {
            return false;
        }
    }

    /** Compares dotted versions like 5.2.0. Returns >0 if a is newer than b. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0;
            int q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
