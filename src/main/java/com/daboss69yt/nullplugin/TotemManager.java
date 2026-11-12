package com.daboss69yt.nullplugin;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.UUID;

/**
 * The Totem of NULL. Owner-bound: each use spends a charge and pulses hostile mobs and roster players.
 * Succession mints a new totem and makes every older one inert. Running out of charges breaks the army.
 */
public final class TotemManager {

    private static final double RADIUS = 8.0;

    private final NullControllerPlugin plugin;
    private final ConsentManager consent;
    private final NullArmy army;
    private final NamespacedKey idKey;
    private final NamespacedKey chargesKey;

    public TotemManager(NullControllerPlugin plugin, ConsentManager consent, NullArmy army) {
        this.plugin = plugin;
        this.consent = consent;
        this.army = army;
        this.idKey = new NamespacedKey(plugin, "totem-id");
        this.chargesKey = new NamespacedKey(plugin, "totem-charges");
    }

    /** Gives the owner the active totem. Creates the first one if none exists yet. */
    public void giveTotem(Player owner) {
        String id = plugin.getConfig().getString("active-totem-id", "");
        if (id == null || id.isBlank()) {
            id = mintId();
        }
        owner.getInventory().addItem(build(id, charges()));
        owner.sendMessage(ChatColor.DARK_PURPLE + "Totem of NULL issued. Charges: " + charges() + ".");
    }

    /** Mints a new active totem. Older totems stop working. */
    public void succession(Player owner) {
        String id = mintId();
        owner.getInventory().addItem(build(id, charges()));
        owner.sendMessage(ChatColor.DARK_PURPLE + "Succession complete. New Totem of NULL issued. "
                + "Every earlier totem is now inert.");
    }

    public boolean isTotem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(idKey, PersistentDataType.STRING);
    }

    /** Right-click handler. Spends one charge; at zero the army breaks. */
    public void use(Player player, ItemStack item) {
        if (!consent.isOwner(player)) {
            player.sendMessage(ChatColor.RED + "The Totem of NULL does not respond to you.");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String id = pdc.get(idKey, PersistentDataType.STRING);
        String active = plugin.getConfig().getString("active-totem-id", "");
        if (id == null || !id.equals(active)) {
            player.sendMessage(ChatColor.GRAY + "This totem is inert. A succession replaced it.");
            return;
        }

        Integer stored = pdc.get(chargesKey, PersistentDataType.INTEGER);
        int remaining = (stored == null ? 1 : stored) - 1;
        pulse(player.getLocation());

        if (remaining <= 0) {
            player.getInventory().setItemInMainHand(null);
            army.breakArmy(player);
            return;
        }

        pdc.set(chargesKey, PersistentDataType.INTEGER, remaining);
        meta.setLore(loreFor(remaining));
        item.setItemMeta(meta);
        player.getInventory().setItemInMainHand(item);
        player.sendMessage(ChatColor.DARK_PURPLE + "Totem pulse. Charges left: " + remaining + ".");
    }

    /** Hostile mobs and roster players in range are knocked back, blinded, and slowed. The owner and Nulls never are. */
    private void pulse(Location center) {
        World w = center.getWorld();
        if (w == null) return;
        w.spawnParticle(Particle.SONIC_BOOM, center.clone().add(0, 1, 0), 1);
        for (Entity e : w.getNearbyEntities(center, RADIUS, RADIUS, RADIUS)) {
            if (!(e instanceof LivingEntity living)) continue;
            if (e instanceof Player p) {
                if (!consent.canTarget(p)) continue;
            } else if (!(e instanceof Monster)) {
                continue;
            }
            Vector away = e.getLocation().toVector().subtract(center.toVector());
            if (away.lengthSquared() > 0.01) {
                living.setVelocity(away.normalize().multiply(1.2).setY(0.5));
            }
            living.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0));
            living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1));
        }
    }

    private ItemStack build(String id, int charges) {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Totem of NULL");
        meta.setLore(loreFor(charges));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(idKey, PersistentDataType.STRING, id);
        pdc.set(chargesKey, PersistentDataType.INTEGER, charges);
        item.setItemMeta(meta);
        return item;
    }

    private List<String> loreFor(int charges) {
        return List.of(ChatColor.GRAY + "Owner-bound command totem.",
                ChatColor.GRAY + "Charges: " + charges);
    }

    private String mintId() {
        String id = UUID.randomUUID().toString();
        plugin.getConfig().set("active-totem-id", id);
        plugin.saveConfig();
        return id;
    }

    private int charges() {
        return Math.max(1, plugin.getConfig().getInt("totem-charges", 3));
    }
}
