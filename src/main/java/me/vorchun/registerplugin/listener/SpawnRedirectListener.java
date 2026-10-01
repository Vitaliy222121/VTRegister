// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.listener;

import java.lang.reflect.Method;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Спавн неавторизованного игрока сразу на платформе входа. Отдельный класс:
 * если событие когда-нибудь уберут из API, не сломается регистрация
 * остальных обработчиков AuthListener.
 */
public final class SpawnRedirectListener implements Listener {

    private final AuthListener auth;

    public SpawnRedirectListener(AuthListener auth) {
        this.auth = auth;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(org.spigotmc.event.player.PlayerSpawnLocationEvent e) {
        Location to = auth.redirectSpawn(e.getPlayer(), e.getSpawnLocation());
        if (to != null) {
            e.setSpawnLocation(to);
        }
    }

    /**
     * Paper 1.21.9+: PlayerSpawnLocationEvent устарел — подписка на него
     * заставляет ядро создавать игрока раньше времени и печатает
     * предупреждение владельцу. Там есть AsyncPlayerSpawnLocationEvent
     * (фаза configuration, не главный поток) — подписываемся на него через
     * рефлексию: плагин собирается под API 1.16.5.
     * @return true — подписка на новое событие есть, старое не нужно
     */
    public static boolean registerAsync(Plugin plugin, AuthListener auth) {
        try {
            Class<?> ec = Class.forName("io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent");
            final Class<? extends Event> type = ec.asSubclass(Event.class);
            final Method getConnection = ec.getMethod("getConnection");
            final Method getProfile = getConnection.getReturnType().getMethod("getProfile");
            final Method getId = getProfile.getReturnType().getMethod("getId");
            final Method getSpawn = ec.getMethod("getSpawnLocation");
            final Method setSpawn = ec.getMethod("setSpawnLocation", Location.class);
            Bukkit.getPluginManager().registerEvent(type, new Listener() { }, EventPriority.HIGHEST, (l, ev) -> {
                if (!type.isInstance(ev)) {
                    return;
                }
                try {
                    Object profile = getProfile.invoke(getConnection.invoke(ev));
                    Location to = auth.redirectSpawnAsync((UUID) getId.invoke(profile), (Location) getSpawn.invoke(ev));
                    if (to != null) {
                        setSpawn.invoke(ev, to);
                    }
                } catch (Throwable ignored) {
                    // обычный маршрут (телепорт на платформу после входа) остаётся
                }
            }, plugin, false);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
