// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.util.Scheduler;

/**
 * Система спавнов:
 *   - prelogin  — куда поставить игрока ДО авторизации (безопасная зона);
 *   - postlogin — куда телепортировать ПОСЛЕ успешного входа;
 *   - firstjoin — куда отправить игрока, который только что зарегистрировался.
 *
 * Точки задаются командой /authadmin setspawn <prelogin|postlogin|firstjoin>
 * и хранятся в data/spawns.yml. Любой пункт можно оставить пустым — тогда
 * используется поведение «как раньше» (игрок остаётся там, где стоял).
 */
public final class SpawnService {

    private final JavaPlugin plugin;
    private final TeleportService teleportService;

    private volatile Location prelogin;
    private volatile Location postlogin;
    private volatile Location firstjoin;

    public SpawnService(JavaPlugin plugin, TeleportService teleportService) {
        this.plugin = plugin;
        this.teleportService = teleportService;
    }

    public void reload() {
        // Спавны храним в data/spawns.yml, а НЕ в config.yml:
        // plugin.saveConfig() перезаписывает конфиг и уничтожает комментарии.
        prelogin = read("prelogin");
        postlogin = read("postlogin");
        firstjoin = read("firstjoin");
    }

    private java.io.File file() {
        return new java.io.File(AccountStore.dataFolder(plugin), "spawns.yml");
    }

    private YamlConfiguration load() {
        java.io.File f = file();
        if (!f.exists()) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    private Location read(String key) {
        YamlConfiguration cfg = load();
        ConfigurationSection s = cfg.getConfigurationSection(key);
        if (s == null) {
            // совместимость: старые версии могли хранить спавны в config.yml
            s = plugin.getConfig().getConfigurationSection("spawns." + key);
        }
        if (s == null) {
            return null;
        }
        String worldName = s.getString("world");
        if (worldName == null) {
            return null;
        }
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            return null;
        }
        return new Location(w,
                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    public void save(String kind, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        YamlConfiguration cfg = load();
        cfg.set(kind + ".world", loc.getWorld().getName());
        cfg.set(kind + ".x", loc.getX());
        cfg.set(kind + ".y", loc.getY());
        cfg.set(kind + ".z", loc.getZ());
        cfg.set(kind + ".yaw", loc.getYaw());
        cfg.set(kind + ".pitch", loc.getPitch());
        try {
            java.io.File f = file();
            if (f.getParentFile() != null && !f.getParentFile().exists()) {
                f.getParentFile().mkdirs();
            }
            cfg.save(f);
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Не удалось сохранить spawns.yml: " + e.getMessage());
        }
        reload();
    }

    /** Отправить на prelogin-спавн (до авторизации). */
    /** Задана ли prelogin-точка (до авторизации). */
    public boolean hasPrelogin() {
        return prelogin != null;
    }

    public void teleportPrelogin(Player player) {
        Location loc = prelogin;
        if (loc == null || player == null) {
            return;
        }
        teleportService.authorizeTeleport(player.getUniqueId());
        Scheduler.runAtEntity(plugin, player, () -> player.teleport(loc));
    }

    /** Отправить после входа: новичкам — firstjoin, остальным — postlogin. */
    public void teleportAfterLogin(Player player, boolean firstTime) {
        Location loc = firstTime && firstjoin != null ? firstjoin : postlogin;
        if (loc == null || player == null) {
            return;
        }
        teleportService.authorizeTeleport(player.getUniqueId());
        Scheduler.runAtEntity(plugin, player, () -> player.teleport(loc));
    }

    public Map<String, Location> all() {
        Map<String, Location> map = new HashMap<>();
        map.put("prelogin", prelogin);
        map.put("postlogin", postlogin);
        map.put("firstjoin", firstjoin);
        return map;
    }

    private static final int READY = 2109234890;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x101e) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x101e) == READY;
    }
}
