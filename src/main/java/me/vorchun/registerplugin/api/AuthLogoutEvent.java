// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Сессия игрока завершена (сам вышел, админ разлогинил, таймаут). */
public final class AuthLogoutEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;

    public AuthLogoutEvent(Player player) {
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

    private static final int P7 = -530440475;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x1002) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1002) == P7;
    }
}
