// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.listener;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

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
}
