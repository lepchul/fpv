package ru.factor.drone;

import org.bukkit.Particle;
import org.bukkit.Sound;

/** Разбор значений из конфига: неверное имя не роняет плагин, а пишет в лог. */
public final class Cfg {

    private Cfg() {}

    public static Particle particle(DronePlugin plugin, String path, Particle fallback) {
        String raw = plugin.getConfig().getString(path, "");
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Particle.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Неизвестная частица в " + path + ": " + raw
                    + " — использую " + fallback);
            return fallback;
        }
    }

    public static Sound sound(DronePlugin plugin, String path, Sound fallback) {
        String raw = plugin.getConfig().getString(path, "");
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Sound.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Неизвестный звук в " + path + ": " + raw
                    + " — использую " + fallback);
            return fallback;
        }
    }
}
