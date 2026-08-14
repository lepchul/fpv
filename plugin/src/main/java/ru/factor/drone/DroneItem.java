package ru.factor.drone;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** Предмет помечен в PersistentDataContainer, поэтому подделать его наковальней нельзя. */
public final class DroneItem {

    private DroneItem() {}

    public static ItemStack create(DronePlugin plugin, int amount) {
        Material mat = Material.matchMaterial(
                plugin.getConfig().getString("item.material", "PHANTOM_MEMBRANE").toUpperCase());
        if (mat == null || mat == Material.AIR) mat = Material.PHANTOM_MEMBRANE;

        ItemStack item = new ItemStack(mat, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(plugin.getConfig()
                .getString("item.name", "&b&lДрон Камикадзе").replace('&', '\u00A7'));

        List<String> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("item.lore")) {
            lore.add(line.replace('&', '\u00A7'));
        }
        if (!lore.isEmpty()) meta.setLore(lore);

        if (plugin.getConfig().getBoolean("item.glow", true)) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.DURABILITY, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        meta.getPersistentDataContainer().set(DronePlugin.KEY_ITEM, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isDrone(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(DronePlugin.KEY_ITEM, PersistentDataType.BYTE);
    }
}
