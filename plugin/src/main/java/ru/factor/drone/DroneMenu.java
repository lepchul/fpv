package ru.factor.drone;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Меню, которое открывается по клику на стоящий дрон. */
public class DroneMenu implements InventoryHolder {

    public static final int SLOT_COORDS = 2;
    public static final int SLOT_INFO   = 4;
    public static final int SLOT_LOOK   = 6;
    public static final int SLOT_PICKUP = 8;

    private final Drone drone;
    private final Inventory inv;

    public DroneMenu(DronePlugin plugin, Drone drone) {
        this.drone = drone;
        this.inv = org.bukkit.Bukkit.createInventory(this, 9, color(
                plugin.getConfig().getString("menu.title", "&8Пульт управления")));

        inv.setItem(SLOT_COORDS, icon(Material.COMPASS,
                color(plugin.getConfig().getString("menu.coords-name", "&a&lЗадать координаты")),
                Arrays.asList("§7Дрон пойдёт в указанную точку", "§7и сдетонирует при попадании.")));

        List<String> info = new ArrayList<>();
        info.add("§7Скорость: §f" + plugin.getConfig().getDouble("flight.speed-blocks-per-second", 8.0) + " бл/с");
        info.add("§7Дальность: §f" + (int) plugin.getConfig().getDouble("flight.max-range", 400) + " блоков");
        info.add("§7Батарея: §f" + plugin.getConfig().getInt("flight.max-seconds", 60) + " сек");
        info.add("§7Мощность: §f" + plugin.getConfig().getDouble("explosion.power", 4.0));
        info.add("");
        info.add("§cДрон можно сбить стрелой.");
        inv.setItem(SLOT_INFO, icon(Material.PAPER,
                color(plugin.getConfig().getString("menu.info-name", "&e&lИнформация")), info));

        if (plugin.getConfig().getBoolean("flight.allow-look-launch", true)) {
            inv.setItem(SLOT_LOOK, icon(Material.SPYGLASS,
                    color(plugin.getConfig().getString("menu.look-name", "&b&lЛететь по взгляду")),
                    Arrays.asList("§7Цель — блок, на который", "§7вы сейчас смотрите.")));
        }

        inv.setItem(SLOT_PICKUP, icon(Material.HOPPER,
                color(plugin.getConfig().getString("menu.pickup-name", "&6&lЗабрать дрон")),
                Arrays.asList("§7Вернуть предмет в инвентарь.")));

        Material fill = Material.matchMaterial(
                plugin.getConfig().getString("menu.filler", "GRAY_STAINED_GLASS_PANE").toUpperCase());
        if (fill != null && fill != Material.AIR) {
            ItemStack filler = icon(fill, " ", null);
            for (int i = 0; i < 9; i++) if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    private static String color(String s) {
        return s == null ? "" : s.replace('&', '\u00A7');
    }

    private ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    public Drone drone() { return drone; }

    public void open(Player p) { p.openInventory(inv); }

    @Override
    public Inventory getInventory() { return inv; }
}
