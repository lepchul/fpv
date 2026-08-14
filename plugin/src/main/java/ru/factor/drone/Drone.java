package ru.factor.drone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;

/** Один дрон: висит на месте, пока не задан курс, потом идёт на цель. */
public class Drone {

    public enum State { IDLE, CLIMBING, FLYING, DEAD }

    public final UUID id;
    public final Phantom entity;
    public final UUID owner;

    public State state = State.IDLE;
    public Location target;
    public Location launchPoint;
    public int ticksFlown = 0;

    private final DronePlugin plugin;
    private int soundTimer = 0;
    private int hudTimer = 0;
    private double climbTo = 0;

    public Drone(DronePlugin plugin, Phantom entity, UUID owner) {
        this.plugin = plugin;
        this.entity = entity;
        this.owner = owner;
        this.id = entity.getUniqueId();
    }

    public boolean alive() {
        return state != State.DEAD && entity.isValid() && !entity.isDead();
    }

    public boolean flying() {
        return state == State.FLYING || state == State.CLIMBING;
    }

    public void launch(Location target) {
        this.target = target;
        this.launchPoint = entity.getLocation().clone();
        this.ticksFlown = 0;

        boolean climb = plugin.getConfig().getBoolean("flight.climb-first", true);
        double climbH = plugin.getConfig().getDouble("flight.climb-height", 12);
        double ceiling = entity.getWorld().getMaxHeight() - 4;

        this.climbTo = Math.min(ceiling,
                Math.max(launchPoint.getY() + climbH, target.getY() + 3));
        this.state = (climb && entity.getLocation().getY() < climbTo - 1)
                ? State.CLIMBING : State.FLYING;

        World w = entity.getWorld();
        w.playSound(entity.getLocation(),
                Cfg.sound(plugin, "effects.launch-sound", Sound.ENTITY_BEE_LOOP_AGGRESSIVE), 1.2f, 0.7f);

        warnNearby();
    }

    /** Предупреждение игрокам поблизости — дрон слышно на подлёте. */
    private void warnNearby() {
        double r = plugin.getConfig().getDouble("protection.warn-radius", 60);
        if (r <= 0) return;
        Location at = entity.getLocation();
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getUniqueId().equals(owner)) continue;
            if (p.getLocation().distanceSquared(at) <= r * r) {
                p.sendMessage(plugin.msg("incoming"));
                p.playSound(p.getLocation(), Sound.ENTITY_BEE_LOOP_AGGRESSIVE, 0.7f, 0.6f);
            }
        }
    }

    /** Шаг полёта. false — дрон убирается из реестра. */
    public boolean tick() {
        if (!alive()) return false;

        if (entity.getFireTicks() > 0) entity.setFireTicks(0);
        if (!flying()) return true;

        double speed = plugin.getConfig().getDouble("flight.speed-blocks-per-second", 8.0) / 20.0;
        int maxTicks = plugin.getConfig().getInt("flight.max-seconds", 60) * 20;
        double maxRange = plugin.getConfig().getDouble("flight.max-range", 400);

        ticksFlown++;

        if (ticksFlown > maxTicks) {
            detonate(plugin.msg("lost-battery"));
            return false;
        }
        if (launchPoint != null && sameWorld() && entity.getLocation().distance(launchPoint) > maxRange) {
            detonate(plugin.msg("lost-range"));
            return false;
        }

        Location cur = entity.getLocation();
        Vector dir;
        double dist;

        if (state == State.CLIMBING) {
            // Подъём вертикально, чтобы не врезаться в рельеф на старте
            if (cur.getY() >= climbTo - 0.5) {
                state = State.FLYING;
                return true;
            }
            dir = new Vector(0, 1, 0);
            dist = target.distance(cur);
        } else {
            Vector diff = target.toVector().subtract(cur.toVector());
            dist = diff.length();
            if (dist <= speed + 0.5) {
                entity.teleport(target);
                detonate(null);
                return false;
            }
            dir = diff.normalize();
        }

        Location next = cur.clone().add(dir.clone().multiply(speed));
        next.setDirection(dir);

        if (next.getBlock().getType().isSolid()) {
            detonate(null);
            return false;
        }
        if (next.getBlock().isLiquid() && plugin.getConfig().getBoolean("flight.water-kills", true)) {
            fizzle();
            return false;
        }

        entity.teleport(next);
        effects(next);
        hud(dist);
        return true;
    }

    private boolean sameWorld() {
        return launchPoint != null && launchPoint.getWorld() != null
                && launchPoint.getWorld().equals(entity.getWorld());
    }

    private void effects(Location at) {
        World w = at.getWorld();
        if (w == null) return;

        int count = plugin.getConfig().getInt("effects.trail-count", 2);
        if (count > 0) {
            w.spawnParticle(Cfg.particle(plugin, "effects.trail-particle", Particle.SMOKE_NORMAL),
                    at, count, 0.05, 0.05, 0.05, 0.01);
        }

        int interval = Math.max(1, plugin.getConfig().getInt("effects.engine-interval-ticks", 6));
        if (++soundTimer >= interval) {
            soundTimer = 0;
            w.playSound(at,
                    Cfg.sound(plugin, "effects.engine-sound", Sound.ENTITY_BEE_LOOP_AGGRESSIVE),
                    (float) plugin.getConfig().getDouble("effects.engine-volume", 0.8),
                    (float) plugin.getConfig().getDouble("effects.engine-pitch", 1.6));
        }
    }

    private void hud(double dist) {
        if (!plugin.getConfig().getBoolean("hud.enabled", true)) return;
        int every = Math.max(1, plugin.getConfig().getInt("hud.update-ticks", 2));
        if (++hudTimer < every) return;
        hudTimer = 0;

        Player op = Bukkit.getPlayer(owner);
        if (op != null && op.isOnline()) {
            op.sendActionBar(plugin.msg("hud",
                    "dist", String.valueOf((int) dist),
                    "sec", String.valueOf(ticksFlown / 20)));
        }
    }

    /** Взрыв в текущей точке. */
    public void detonate(String reason) {
        if (state == State.DEAD) return;
        Location at = entity.getLocation().clone();
        state = State.DEAD;

        float power = (float) plugin.getConfig().getDouble("explosion.power", 4.0);
        boolean fire = plugin.getConfig().getBoolean("explosion.set-fire", false);
        boolean blocks = plugin.getConfig().getBoolean("explosion.break-blocks", true);

        World w = at.getWorld();
        if (w != null && plugin.getConfig().getStringList("protection.no-grief-worlds")
                .contains(w.getName())) {
            blocks = false;
        }

        entity.remove();
        plugin.manager().markBlast(at, owner);       // для настроек урона по игрокам
        if (w != null) w.createExplosion(at, power, fire, blocks);

        Player op = Bukkit.getPlayer(owner);
        if (op != null && op.isOnline()) {
            op.sendActionBar("");
            op.sendMessage(reason != null ? reason : plugin.msg("hit",
                    "x", String.valueOf(at.getBlockX()),
                    "y", String.valueOf(at.getBlockY()),
                    "z", String.valueOf(at.getBlockZ())));
        }
    }

    /** Сбит или упал в воду — без взрыва. */
    public void fizzle() {
        if (state == State.DEAD) return;
        Location at = entity.getLocation().clone();
        state = State.DEAD;
        entity.remove();

        World w = at.getWorld();
        if (w != null) {
            w.spawnParticle(Cfg.particle(plugin, "effects.fizzle-particle", Particle.SMOKE_LARGE),
                    at, 25, 0.4, 0.4, 0.4, 0.05);
            w.playSound(at, Cfg.sound(plugin, "effects.fizzle-sound",
                    Sound.ENTITY_GENERIC_EXTINGUISH_FIRE), 1f, 1f);
        }
        Player op = Bukkit.getPlayer(owner);
        if (op != null && op.isOnline()) {
            op.sendActionBar("");
            op.sendMessage(plugin.msg("shot-down"));
        }
    }
}
