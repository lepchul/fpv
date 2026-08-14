package ru.factor.drone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DroneManager {

    private final DronePlugin plugin;
    private final Map<UUID, Drone> drones = new HashMap<>();
    /** Игроки, от которых ждём координаты в чат. */
    private final Map<UUID, UUID> awaitingCoords = new HashMap<>();
    /** Кулдаун запусков: игрок -> время следующего разрешённого запуска. */
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** Недавние взрывы: нужны, чтобы разрулить настройки урона по игрокам. */
    private final List<Blast> blasts = new ArrayList<>();

    public record Blast(Location at, UUID owner, long time) {}
    private BukkitTask task;

    public DroneManager(DronePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        if (plugin.getConfig().getBoolean("drone.remove-flying-on-stop", true)) {
            for (Drone d : new ArrayList<>(drones.values())) {
                if (d.flying() && d.entity.isValid()) d.entity.remove();
            }
        }
        drones.clear();
    }

    private void tickAll() {
        Iterator<Map.Entry<UUID, Drone>> it = drones.entrySet().iterator();
        while (it.hasNext()) {
            Drone d = it.next().getValue();
            try {
                if (!d.tick()) it.remove();
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка в тике дрона: " + e.getMessage());
                it.remove();
            }
        }
    }

    // ─────────────────────────────────────────────────── спавн

    public Drone spawn(Player owner, Location at) {
        String name = plugin.getConfig()
                .getString("item.entity-name", "&c☠ Дрон Камикадзе").replace('&', '\u00A7');

        Phantom ph = at.getWorld().spawn(at, Phantom.class, p -> {
            p.setAI(false);
            p.setGravity(false);
            p.setSilent(true);
            p.setCollidable(false);
            p.setPersistent(true);
            p.setSize(0);
            p.setCustomName(name);
            p.setCustomNameVisible(plugin.getConfig().getBoolean("item.show-name-always", true));
            p.setRemoveWhenFarAway(false);
            double hp = plugin.getConfig().getDouble("drone.health", 6.0);
            var attr = p.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (attr != null) attr.setBaseValue(hp);
            p.setHealth(Math.min(hp, attr == null ? hp : attr.getValue()));
            p.getPersistentDataContainer().set(DronePlugin.KEY_ENTITY, PersistentDataType.BYTE, (byte) 1);
            p.getPersistentDataContainer().set(DronePlugin.KEY_OWNER, PersistentDataType.STRING,
                    owner.getUniqueId().toString());
        });

        Drone d = new Drone(plugin, ph, owner.getUniqueId());
        drones.put(d.id, d);
        return d;
    }

    /** Дрон по сущности. Если после рестарта пропал из памяти — восстанавливаем по метке. */
    public Drone byEntity(Entity e) {
        if (!(e instanceof Phantom ph)) return null;
        Drone d = drones.get(e.getUniqueId());
        if (d != null) return d;

        if (!ph.getPersistentDataContainer().has(DronePlugin.KEY_ENTITY, PersistentDataType.BYTE)) return null;
        String raw = ph.getPersistentDataContainer()
                .get(DronePlugin.KEY_OWNER, PersistentDataType.STRING);
        UUID owner;
        try {
            owner = raw == null ? new UUID(0, 0) : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            owner = new UUID(0, 0);
        }
        Drone restored = new Drone(plugin, ph, owner);
        drones.put(restored.id, restored);
        return restored;
    }

    public boolean isDrone(Entity e) {
        return e instanceof Phantom p && p.getPersistentDataContainer()
                .has(DronePlugin.KEY_ENTITY, PersistentDataType.BYTE);
    }

    public void remove(UUID id) { drones.remove(id); }

    public int countOf(UUID owner) {
        int n = 0;
        for (Drone d : drones.values()) if (owner.equals(d.owner) && d.alive()) n++;
        return n;
    }

    public List<Drone> flyingOf(UUID owner) {
        List<Drone> out = new ArrayList<>();
        for (Drone d : drones.values()) {
            if (owner.equals(d.owner) && d.flying()) out.add(d);
        }
        return out;
    }

    // ─────────────────────────────────────────── ожидание координат

    public void awaitCoords(Player p, Drone d) { awaitingCoords.put(p.getUniqueId(), d.id); }
    public void cancelAwait(Player p)          { awaitingCoords.remove(p.getUniqueId()); }
    public boolean isAwaiting(Player p)        { return awaitingCoords.containsKey(p.getUniqueId()); }

    // ─────────────────────────────────────────────── кулдаун

    /** Осталось секунд перезарядки, 0 — можно запускать. */
    public int cooldownLeft(UUID player) {
        Long until = cooldowns.get(player);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    public void startCooldown(UUID player) {
        int sec = plugin.getConfig().getInt("drone.cooldown-seconds", 10);
        if (sec > 0) cooldowns.put(player, System.currentTimeMillis() + sec * 1000L);
    }

    // ─────────────────────────────────────────────── взрывы

    public void markBlast(Location at, UUID owner) {
        long now = System.currentTimeMillis();
        blasts.removeIf(b -> now - b.time() > 1000);
        blasts.add(new Blast(at.clone(), owner, now));
    }

    /** Взрыв дрона рядом с этой точкой за последние полсекунды. */
    public Blast recentBlast(Location at) {
        long now = System.currentTimeMillis();
        for (Blast b : blasts) {
            if (now - b.time() > 500) continue;
            if (b.at().getWorld() == null || !b.at().getWorld().equals(at.getWorld())) continue;
            double r = plugin.getConfig().getDouble("explosion.power", 4.0) * 2.5;
            if (b.at().distanceSquared(at) <= r * r) return b;
        }
        return null;
    }

    public Drone awaited(Player p) {
        UUID id = awaitingCoords.get(p.getUniqueId());
        return id == null ? null : drones.get(id);
    }
}
