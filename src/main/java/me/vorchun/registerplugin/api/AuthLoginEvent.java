// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Игрок вошёл — любым способом: пароль, регистрация, IP-сессия, премиум,
 * Bedrock, 2FA, API/forcelogin. Приходит один раз на вход, в главном потоке
 * (на Folia — в потоке игрока), после снятия всех ограничений авторизации.
 */
public final class AuthLoginEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final boolean firstTime;
    private final String method;

    public AuthLoginEvent(Player player, boolean firstTime) {
        this(player, firstTime, firstTime ? "register" : "password");
    }

    /**
     * @param method способ входа: "password", "register", "session" (IP-сессия),
     *               "premium", "bedrock", "other" (2FA-код, API, forcelogin)
     */
    public AuthLoginEvent(Player player, boolean firstTime, String method) {
        this.player = player;
        this.firstTime = firstTime;
        this.method = method == null ? "other" : method;
    }

    /** Способ входа: password, register, session, premium, bedrock, other. */
    public String getMethod() {
        return method;
    }

    public Player getPlayer() {
        return player;
    }

    /** true — это была регистрация (первый вход). */
    public boolean isFirstTime() {
        return firstTime;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    private static final int READY = 1448549744;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1001) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1001) == READY;
    }
}
