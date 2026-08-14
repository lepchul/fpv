package ru.factor.drone;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DroneCommand implements CommandExecutor, TabCompleter {

    private final DronePlugin plugin;
    private final DroneManager manager;

    public DroneCommand(DronePlugin plugin, DroneManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("§e/drone give [ник] [кол-во] §7— выдать дрон");
            s.sendMessage("§e/drone stop §7— убрать свои дроны без взрыва");
            s.sendMessage("§e/drone boom §7— подорвать свои дроны прямо сейчас");
            s.sendMessage("§e/drone reload §7— перечитать конфиг и рецепт");
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "give" -> {
                if (!s.hasPermission("drone.admin")) { s.sendMessage("§cНет прав."); return true; }
                Player target = a.length > 1 ? Bukkit.getPlayerExact(a[1])
                        : (s instanceof Player p ? p : null);
                if (target == null) { s.sendMessage("§cИгрок не найден."); return true; }
                int n = 1;
                if (a.length > 2) {
                    try { n = Math.max(1, Math.min(64, Integer.parseInt(a[2]))); }
                    catch (NumberFormatException ignored) { }
                }
                target.getInventory().addItem(DroneItem.create(plugin, n));
                s.sendMessage("§aВыдано " + n + " шт. игроку " + target.getName());
            }
            case "boom" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cТолько в игре."); return true; }
                List<Drone> list = manager.flyingOf(p.getUniqueId());
                if (list.isEmpty()) { p.sendMessage("§7Нет дронов в воздухе."); return true; }
                for (Drone d : list) { manager.remove(d.id); d.detonate(null); }
                p.sendMessage("§cПодорвано дронов: " + list.size());
            }
            case "stop" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cТолько в игре."); return true; }
                List<Drone> list = manager.flyingOf(p.getUniqueId());
                if (list.isEmpty()) { p.sendMessage("§7Нет дронов в воздухе."); return true; }
                for (Drone d : list) { manager.remove(d.id); d.fizzle(); }
                p.sendMessage("§aОстановлено дронов: " + list.size());
            }
            case "reload" -> {
                if (!s.hasPermission("drone.admin")) { s.sendMessage("§cНет прав."); return true; }
                plugin.reloadConfig();
                plugin.registerRecipe();
                s.sendMessage("§aКонфиг и рецепт перечитаны.");
            }
            default -> s.sendMessage("§cНеизвестная команда.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 1) return Arrays.asList("give", "stop", "boom", "reload");
        if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        return List.of();
    }
}
