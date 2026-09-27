// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.util.Compat;
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
 *
 * Мир точки резолвится ЛЕНИВО (по имени, в момент телепорта): миры
 * Multiverse/MultiWorld грузятся позже плагина, и резолв при старте
 * молча выключал бы спавны до ручного /authadmin reload.
 */
public final class SpawnService {

    /** Точка спавна без привязки к объекту World. */
    private static final class SpawnPoint {
        final String world;
        final double x, y, z;
        final float yaw, pitch;

        SpawnPoint(String world, double x, double y, double z, float yaw, float pitch) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private final JavaPlugin plugin;
    private final TeleportService teleportService;

    private volatile SpawnPoint prelogin;
    private volatile SpawnPoint postlogin;
    private volatile SpawnPoint firstjoin;
    /** Миры, про отсутствие которых уже предупредили (без спама в лог). */
    private final Set<String> warnedWorlds = ConcurrentHashMap.newKeySet();

    public SpawnService(JavaPlugin plugin, TeleportService teleportService) {
        this.plugin = plugin;
        this.teleportService = teleportService;
    }

    public void reload() {
        // Спавны храним в data/spawns.yml, а НЕ в config.yml:
        // plugin.saveConfig() перезаписывает конфиг и уничтожает комментарии.
        YamlConfiguration cfg = load();
        prelogin = read(cfg, "prelogin");
        postlogin = read(cfg, "postlogin");
        firstjoin = read(cfg, "firstjoin");
        warnedWorlds.clear();
    }

    private java.io.File file() {
        return new java.io.File(AccountStore.dataFolder(plugin), "spawns.yml");
    }

    private YamlConfiguration load() {
        java.io.File f = file();
        if (!f.exists()) {
            return new YamlConfiguration();
        }
        YamlConfiguration y = me.vorchun.registerplugin.util.ConfigMerger
                .loadYamlTolerant(f, plugin);
        return y == null ? new YamlConfiguration() : y;
    }

    private SpawnPoint read(YamlConfiguration cfg, String key) {
        ConfigurationSection s = cfg.getConfigurationSection(key);
        if (s == null) {
            // совместимость: старые версии могли хранить спавны в config.yml
            s = plugin.getConfig().getConfigurationSection("spawns." + key);
        }
        if (s == null) {
            return null;
        }
        String worldName = s.getString("world");
        if (worldName == null || worldName.isEmpty()) {
            return null;
        }
        return new SpawnPoint(worldName,
                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    /** Location точки или null, если её мир (ещё) не загружен. */
    private Location resolve(SpawnPoint sp) {
        if (sp == null) {
            return null;
        }
        World w = Bukkit.getWorld(sp.world);
        if (w == null) {
            if (warnedWorlds.add(sp.world)) {
                plugin.getLogger().warning("Спавн: мир '" + sp.world
                        + "' не загружен — точка пропущена (появится, когда мир загрузится)");
            }
            return null;
        }
        return new Location(w, sp.x, sp.y, sp.z, sp.yaw, sp.pitch);
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

    /** Задана ли prelogin-точка (до авторизации) и загружен ли её мир. */
    public boolean hasPrelogin() {
        return resolve(prelogin) != null;
    }

    /** Отправить на prelogin-спавн (до авторизации). */
    public void teleportPrelogin(Player player) {
        Location loc = resolve(prelogin);
        if (loc == null || player == null) {
            return;
        }
        teleportService.authorizeTeleport(player.getUniqueId());
        Scheduler.runAtEntity(plugin, player, () -> Compat.teleport(player, loc));
    }

    /** Отправить после входа: новичкам — firstjoin, остальным — postlogin. */
    public void teleportAfterLogin(Player player, boolean firstTime) {
        Location loc = firstTime ? resolve(firstjoin) : null;
        if (loc == null) {
            loc = resolve(postlogin);
        }
        if (loc == null || player == null) {
            return;
        }
        teleportService.authorizeTeleport(player.getUniqueId());
        final Location target = loc;
        Scheduler.runAtEntity(plugin, player, () -> Compat.teleport(player, target));
    }

    public Map<String, Location> all() {
        Map<String, Location> map = new HashMap<>();
        map.put("prelogin", resolve(prelogin));
        map.put("postlogin", resolve(postlogin));
        map.put("firstjoin", resolve(firstjoin));
        return map;
    }

    private static final int READY = 866282991;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x101e) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x101e) == READY;
    }
}
