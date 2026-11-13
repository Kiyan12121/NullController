package com.daboss69yt.nullplugin;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

/** The Null arsenal: a short list of enchanted weapons, the same items handed to you or equipped on a rank. */
public final class Arsenal {
    private Arsenal() {
    }

    /** Weapon keys the owner can use, and what they are. */
    public static final List<String> KEYS = List.of("blade", "axe", "mace", "spear", "bow", "crossbow");

    /** Builds one weapon by key, or returns null if the key is not in the arsenal. */
    public static ItemStack make(String key) {
        return switch (key) {
            case "blade" -> enchant(new ItemStack(Material.NETHERITE_SWORD),
                    Map.of(Enchantment.SHARPNESS, 5, Enchantment.FIRE_ASPECT, 2, Enchantment.UNBREAKING, 3), "Null Blade");
            case "axe" -> enchant(new ItemStack(Material.NETHERITE_AXE),
                    Map.of(Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3), "Null Axe");
            case "mace" -> enchant(new ItemStack(Material.MACE),
                    Map.of(Enchantment.UNBREAKING, 3), "Null Mace");
            case "spear" -> enchant(spearItem(),
                    Map.of(Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3), "Null Spear");
            case "bow" -> enchant(new ItemStack(Material.BOW),
                    Map.of(Enchantment.POWER, 5, Enchantment.INFINITY, 1, Enchantment.UNBREAKING, 3), "Null Bow");
            case "crossbow" -> enchant(new ItemStack(Material.CROSSBOW),
                    Map.of(Enchantment.MULTISHOT, 1, Enchantment.QUICK_CHARGE, 3, Enchantment.UNBREAKING, 3), "Null Crossbow");
            default -> null;
        };
    }

    /** The netherite spear on servers that have it, otherwise the closest thing: a trident. */
    private static ItemStack spearItem() {
        Material spear = Material.matchMaterial("NETHERITE_SPEAR");
        return new ItemStack(spear != null ? spear : Material.TRIDENT);
    }

    private static ItemStack enchant(ItemStack item, Map<Enchantment, Integer> levels, String name) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§5" + name);
            for (Map.Entry<Enchantment, Integer> e : levels.entrySet()) {
                meta.addEnchant(e.getKey(), e.getValue(), true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
