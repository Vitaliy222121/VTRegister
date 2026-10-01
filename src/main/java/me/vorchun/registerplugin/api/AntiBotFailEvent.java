// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.api;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Игрок не прошёл проверку на бота и будет кикнут. proven=true — доказанный
 * бот (неверная физика, флуд, робот-ответы, обманка): засчитывается в баны
 * по IP. proven=false — таймаут (лаг, AFK): только кик. Главный поток.
 */
public final class AntiBotFailEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID uuid;
    private final Player player;
    private final String reason;
    private final boolean proven;

    public AntiBotFailEvent(UUID uuid, Player player, String reason, boolean proven) {
        this.uuid = uuid;
        this.player = player;
        this.reason = reason == null ? "" : reason;
        this.proven = proven;
    }

    public UUID getUniqueId() {
        return uuid;
    }

    /** null, если игрок уже вышел. */
    public Player getPlayer() {
        return player;
    }

    /** Текст кика (как его увидит игрок, с цветами). */
    public String getReason() {
        return reason;
    }

    /** true — доказанный бот (идёт в баны по IP), false — таймаут. */
    public boolean isProven() {
        return proven;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
