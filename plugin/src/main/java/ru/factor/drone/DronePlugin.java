package ru.factor.drone;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

public class DronePlugin extends JavaPlugin {

    public static NamespacedKey KEY_ITEM;      // метка предмета
    public static NamespacedKey KEY_ENTITY;    // метка сущности
    public static NamespacedKey KEY_OWNER;     // владелец дрона

    private DroneManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        KEY_ITEM   = new NamespacedKey(this, "kamikaze_drone");
        KEY_ENTITY = new NamespacedKey(this, "drone_entity");
        KEY_OWNER  = new NamespacedKey(this, "drone_owner");

        manager = new DroneManager(this);

        getServer().getPluginManager().registerEvents(new DroneListener(this, manager), this);
        DroneCommand cmd = new DroneCommand(this, manager);
        if (getCommand("drone") != null) {
            getCommand("drone").setExecutor(cmd);
            getCommand("drone").setTabCompleter(cmd);
        }

        registerRecipe();
        manager.start();

        getLogger().info("Дроны-камикадзе запущены. Скорость: "
                + getConfig().getDouble("flight.speed-blocks-per-second", 8.0) + " бл/с");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
        Bukkit.removeRecipe(KEY_ITEM);
    }

    public DroneManager manager() { return manager; }

    // ─────────────────────────────────────────────────────── рецепт

    public void registerRecipe() {
        Bukkit.removeRecipe(KEY_ITEM);
        if (!getConfig().getBoolean("recipe.enabled", true)) return;

        String[] rows = {
                getConfig().getString("recipe.row1", ""),
                getConfig().getString("recipe.row2", ""),
                getConfig().getString("recipe.row3", "")
        };

        // Раскладываем материалы по буквам: одинаковый материал — одна буква
        Map<Material, Character> letters = new HashMap<>();
        Map<Character, Material> back = new HashMap<>();
        char next = 'a';
        String[] shape = new String[3];

        for (int r = 0; r < 3; r++) {
            String[] cells = rows[r].split(",");
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < 3; c++) {
                String raw = c < cells.length ? cells[c].trim().toUpperCase() : "AIR";
                if (raw.isEmpty()) raw = "AIR";
                Material m = Material.matchMaterial(raw);
                if (m == null) {
                    getLogger().warning("Неизвестный предмет в рецепте: " + raw + " — клетка оставлена пустой.");
                    m = Material.AIR;
                }
                if (m == Material.AIR) {
                    line.append(' ');
                } else {
                    Character ch = letters.get(m);
                    if (ch == null) {
                        ch = next++;
                        letters.put(m, ch);
                        back.put(ch, m);
                    }
                    line.append(ch);
                }
            }
            shape[r] = line.toString();
        }

        if (back.isEmpty()) {
            getLogger().warning("Рецепт пустой — крафт отключён.");
            return;
        }

        ItemStack result = DroneItem.create(this,
                Math.max(1, getConfig().getInt("recipe.amount", 1)));

        ShapedRecipe recipe = new ShapedRecipe(KEY_ITEM, result);
        recipe.shape(shape);
        back.forEach(recipe::setIngredient);
        Bukkit.addRecipe(recipe);

        getLogger().info("Рецепт зарегистрирован: " + String.join(" / ", rows));
    }

    public String msg(String path, String... kv) {
        String s = getConfig().getString("messages." + path, path);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s.replace('&', '\u00A7');
    }
}
