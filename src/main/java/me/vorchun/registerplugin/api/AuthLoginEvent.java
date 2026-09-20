package me.vorchun.registerplugin.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Игрок успешно вошёл (после проверки пароля/2FA/премиум-автологина). */
public final class AuthLoginEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final boolean firstTime;

    public AuthLoginEvent(Player player, boolean firstTime) {
        this.player = player;
        this.firstTime = firstTime;
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
}
