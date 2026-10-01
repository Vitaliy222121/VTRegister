// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Игрок прошёл проверку на бота (или выпущен из неё без провала — например,
 * антибот выключили на лету). Дальше он вводит /reg или /login.
 * Главный поток (на Folia — поток игрока).
 */
public final class AntiBotPassEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;

    public AntiBotPassEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
