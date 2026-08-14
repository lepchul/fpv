package ru.factor.drone;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class DroneListener implements Listener {

    private final DronePlugin plugin;
    private final DroneManager manager;

    public DroneListener(DronePlugin plugin, DroneManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ────────────────────────────────────────────── установка дрона

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;

        ItemStack item = e.getItem();
        if (!DroneItem.isDrone(item)) return;

        e.setCancelled(true);
        Player p = e.getPlayer();

        if (!p.hasPermission("drone.use")) {
            p.sendMessage(plugin.msg("no-permission"));
            return;
        }

        int limit = plugin.getConfig().getInt("drone.max-per-player", 3);
        if (manager.countOf(p.getUniqueId()) >= limit) {
            p.sendMessage(plugin.msg("too-many", "n", String.valueOf(limit)));
            return;
        }

        Block block = e.getClickedBlock();
        if (block == null) return;
        Location at = block.getRelative(e.getBlockFace()).getLocation().add(0.5, 0.2, 0.5);
        if (at.getWorld() == null) return;

        if (plugin.getConfig().getStringList("protection.worlds-blacklist").contains(at.getWorld().getName())) {
            p.sendMessage(plugin.msg("world-blocked"));
            return;
        }

        manager.spawn(p, at);

        if (p.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
        at.getWorld().playSound(at, Cfg.sound(plugin, "effects.place-sound", Sound.BLOCK_BEACON_ACTIVATE), 0.6f, 1.8f);
        p.sendMessage(plugin.msg("placed"));
    }

    // ──────────────────────────────────────────────── открытие меню

    @EventHandler(ignoreCancelled = true)
    public void onClickDrone(PlayerInteractAtEntityEvent e) {
        Entity ent = e.getRightClicked();
        if (!manager.isDrone(ent)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player p = e.getPlayer();
        Drone d = manager.byEntity(ent);
        if (d == null) return;

        if (d.flying()) {
            p.sendMessage(plugin.msg("already-flying"));
            return;
        }
        if (!canControl(p, d)) {
            p.sendMessage(plugin.msg("not-yours"));
            return;
        }
        new DroneMenu(plugin, d).open(p);
    }

    private boolean canControl(Player p, Drone d) {
        if (p.hasPermission("drone.admin")) return true;
        return d.owner.equals(p.getUniqueId());
    }

    // ─────────────────────────────────────────────── клики в меню

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof DroneMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Drone d = menu.drone();
        if (d == null || !d.alive()) {
            p.closeInventory();
            p.sendMessage(plugin.msg("drone-gone"));
            return;
        }

        switch (e.getRawSlot()) {
            case DroneMenu.SLOT_COORDS -> {
                p.closeInventory();
                int cd = manager.cooldownLeft(p.getUniqueId());
                if (cd > 0) {
                    p.sendMessage(plugin.msg("cooldown", "sec", String.valueOf(cd)));
                    return;
                }
                manager.awaitCoords(p, d);
                p.sendMessage(plugin.msg("ask-coords"));
                p.sendMessage(plugin.msg("ask-coords-hint",
                        "x", String.valueOf(p.getLocation().getBlockX()),
                        "y", String.valueOf(p.getLocation().getBlockY()),
                        "z", String.valueOf(p.getLocation().getBlockZ())));
            }
            case DroneMenu.SLOT_LOOK -> {
                p.closeInventory();
                if (!plugin.getConfig().getBoolean("flight.allow-look-launch", true)) return;
                var block = p.getTargetBlockExact(
                        (int) plugin.getConfig().getDouble("flight.max-range", 400));
                if (block == null) {
                    p.sendMessage(plugin.msg("no-target-look"));
                    return;
                }
                Location t = block.getLocation().add(0.5, 1.0, 0.5);
                if (validate(p, d, t)) {
                    d.launch(t);
                    manager.startCooldown(p.getUniqueId());
                    p.sendMessage(plugin.msg("launched-look"));
                }
            }
            case DroneMenu.SLOT_PICKUP -> {
                p.closeInventory();
                d.entity.remove();
                d.state = Drone.State.DEAD;
                manager.remove(d.id);
                p.getInventory().addItem(DroneItem.create(plugin, 1));
                p.sendMessage(plugin.msg("picked-up"));
            }
            default -> { }
        }
    }

    // ───────────────────────────────────────────── ввод координат

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!manager.isAwaiting(p)) return;
        e.setCancelled(true);

        String text = e.getMessage().trim();
        if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) {
            manager.cancelAwait(p);
            p.sendMessage(plugin.msg("cancelled"));
            return;
        }

        String[] parts = text.split("[\\s,;]+");
        if (parts.length < 3) {
            p.sendMessage(plugin.msg("bad-coords"));
            return;
        }

        final double x, y, z;
        try {
            x = Double.parseDouble(parts[0].replace(',', '.'));
            y = Double.parseDouble(parts[1].replace(',', '.'));
            z = Double.parseDouble(parts[2].replace(',', '.'));
        } catch (NumberFormatException ex) {
            p.sendMessage(plugin.msg("bad-coords"));
            return;
        }

        // Из асинхронного чата возвращаемся в основной поток
        Bukkit.getScheduler().runTask(plugin, () -> {
            Drone d = manager.awaited(p);
            manager.cancelAwait(p);
            if (d == null || !d.alive()) {
                p.sendMessage(plugin.msg("drone-gone"));
                return;
            }

            Location target = new Location(d.entity.getWorld(), x + 0.5, y, z + 0.5);
            if (!validate(p, d, target)) return;
            double dist = d.entity.getLocation().distance(target);

            d.launch(target);
            manager.startCooldown(p.getUniqueId());
            p.sendMessage(plugin.msg("launched",
                    "x", String.valueOf((int) x), "y", String.valueOf((int) y),
                    "z", String.valueOf((int) z), "sec",
                    String.valueOf((int) (dist / plugin.getConfig()
                            .getDouble("flight.speed-blocks-per-second", 8.0)))));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        manager.cancelAwait(e.getPlayer());
    }

    // ─────────────────────────────────────────────── урон по дрону

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!manager.isDrone(e.getEntity())) return;
        Drone d = manager.byEntity(e.getEntity());
        if (d == null) return;

        Entity damager = e.getDamager();
        boolean byArrow = damager instanceof AbstractArrow
                || (damager instanceof Projectile pr && pr.getShooter() instanceof Player);

        if (!plugin.getConfig().getBoolean("drone.can-be-shot-by-anyone", true)) {
            boolean fromOwner = damager instanceof Projectile pr2
                    && pr2.getShooter() instanceof Player sh && sh.getUniqueId().equals(d.owner);
            if (!fromOwner) { e.setCancelled(true); return; }
        }

        if (byArrow && plugin.getConfig().getBoolean("drone.arrow-instant-kill", true)) {
            e.setCancelled(true);
            manager.remove(d.id);
            if (plugin.getConfig().getBoolean("drone.explode-when-shot", false)) d.detonate(null);
            else d.fizzle();

            if (damager instanceof Projectile pr && pr.getShooter() instanceof Player shooter) {
                shooter.sendMessage(plugin.msg("you-shot-down"));
            }
            return;
        }

        // Обычный урон: если добили — уничтожаем сами
        if (d.entity.getHealth() - e.getFinalDamage() <= 0) {
            e.setCancelled(true);
            manager.remove(d.id);
            d.fizzle();
        }
    }

    /** Дрон уничтожен любым другим способом — не оставляем висеть. */
    @EventHandler(ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent e) {
        if (!manager.isDrone(e.getEntity())) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                || e.getCause() == EntityDamageEvent.DamageCause.FIRE) {
            e.setCancelled(true);   // фантомы горят на солнце
        }
    }

    @EventHandler
    public void onCombust(EntityCombustEvent e) {
        if (manager.isDrone(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        if (manager.isDrone(e.getEntity())) e.setCancelled(true);
    }

    // ────────────────────────────────────────── проверка цели

    private boolean validate(Player p, Drone d, Location target) {
        var world = d.entity.getWorld();
        if (target.getY() < world.getMinHeight() || target.getY() > world.getMaxHeight()) {
            p.sendMessage(plugin.msg("bad-height"));
            return false;
        }

        double range = plugin.getConfig().getDouble("flight.max-range", 400);
        double dist = d.entity.getLocation().distance(target);
        if (dist > range) {
            p.sendMessage(plugin.msg("too-far",
                    "dist", String.valueOf((int) dist), "max", String.valueOf((int) range)));
            return false;
        }

        double prot = plugin.getConfig().getDouble("protection.spawn-radius", 0);
        if (prot > 0 && target.distanceSquared(world.getSpawnLocation()) <= prot * prot) {
            p.sendMessage(plugin.msg("spawn-protected"));
            return false;
        }

        int cd = manager.cooldownLeft(p.getUniqueId());
        if (cd > 0) {
            p.sendMessage(plugin.msg("cooldown", "sec", String.valueOf(cd)));
            return false;
        }
        return true;
    }

    // ─────────────────────────── настройки урона от взрыва дрона

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlastDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        var cause = e.getCause();
        if (cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                && cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return;

        var blast = manager.recentBlast(victim.getLocation());
        if (blast == null) return;

        if (!plugin.getConfig().getBoolean("explosion.damage-players", true)) {
            e.setCancelled(true);
            return;
        }
        if (!plugin.getConfig().getBoolean("explosion.damage-owner", true)
                && victim.getUniqueId().equals(blast.owner())) {
            e.setCancelled(true);
        }
    }
}
