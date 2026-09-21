// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import me.vorchun.registerplugin.util.Scheduler;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ReminderService {

    private final JavaPlugin plugin;
    private final AccountStore accountStore;
    private final SessionManager sessionManager;
    private final MessageService messages;

    private final Map<UUID, Long> nextSendAtMillis = new ConcurrentHashMap<>();
    private me.vorchun.registerplugin.util.Scheduler.Task ticker;

    private volatile boolean cachedEnabled;
    private volatile boolean cachedSendChat;
    private volatile int cachedIntervalSeconds;
    private volatile BaseComponent[] cachedLoginComponents;
    private volatile BaseComponent[] cachedRegisterComponents;
    private volatile String cachedLoginChat;
    private volatile String cachedRegisterChat;

    public ReminderService(JavaPlugin plugin, AccountStore accountStore, SessionManager sessionManager, MessageService messages) {
        this.plugin = plugin;
        this.accountStore = accountStore;
        this.sessionManager = sessionManager;
        this.messages = messages;
    }

    public boolean isEnabled() {
        return cachedEnabled;
    }

    public int getIntervalSeconds() {
        return cachedIntervalSeconds;
    }

    public void reload() {
        cachedEnabled = plugin.getConfig().getBoolean("auth.reminder.enabled", true);
        cachedSendChat = plugin.getConfig().getBoolean("auth.reminder.send_chat", true);
        int sec = plugin.getConfig().getInt("auth.reminder.interval_seconds", 3);
        cachedIntervalSeconds = Math.max(1, sec);

        String loginMsg = messages.message("reminder_login_actionbar");
        String registerMsg = messages.message("reminder_register_actionbar");

        cachedLoginChat = messages.message("reminder_login_chat");
        cachedRegisterChat = messages.message("reminder_register_chat");

        cachedLoginComponents = (loginMsg == null || loginMsg.isEmpty()) ? null : TextComponent.fromLegacyText(loginMsg);
        cachedRegisterComponents = (registerMsg == null || registerMsg.isEmpty()) ? null : TextComponent.fromLegacyText(registerMsg);

        if (!cachedEnabled) {
            nextSendAtMillis.clear();
            stopTicker();
        }
    }

    public void start(Player player) {
        UUID uuid = player.getUniqueId();
        stop(uuid);

        if (!isEnabled()) {
            return;
        }

        long nextAt = System.currentTimeMillis();
        nextSendAtMillis.put(uuid, nextAt);
        ensureTicker();
    }

    public void stop(UUID uuid) {
        nextSendAtMillis.remove(uuid);
        if (nextSendAtMillis.isEmpty()) {
            stopTicker();
        }
    }

    public void stop(Player player) {
        stop(player.getUniqueId());
    }

    private void ensureTicker() {
        if (ticker != null) {
            return;
        }
        ticker = Scheduler.runSyncTimer(plugin, this::tick, 1L, 20L);
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private void tick() {
        if (nextSendAtMillis.isEmpty()) {
            stopTicker();
            return;
        }

        if (!isEnabled()) {
            nextSendAtMillis.clear();
            stopTicker();
            return;
        }

        long now = System.currentTimeMillis();
        long intervalMillis = (long) cachedIntervalSeconds * 1000L;

        BaseComponent[] loginComponents = cachedLoginComponents;
        BaseComponent[] registerComponents = cachedRegisterComponents;
        boolean sendChat = cachedSendChat;
        String loginChat = cachedLoginChat;
        String registerChat = cachedRegisterChat;

        for (Map.Entry<UUID, Long> e : nextSendAtMillis.entrySet()) {
            UUID uuid = e.getKey();
            Long nextAt = e.getValue();
            if (nextAt == null || now < nextAt) {
                continue;
            }

            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                nextSendAtMillis.remove(uuid);
                continue;
            }

            if (sessionManager.isLoggedIn(uuid)) {
                nextSendAtMillis.remove(uuid);
                continue;
            }

            BaseComponent[] components = accountStore.isRegistered(uuid) ? loginComponents : registerComponents;
            if (components != null && components.length > 0) {
                me.vorchun.registerplugin.util.Compat.sendActionBar(p, components);
            }

            if (sendChat) {
                String chatMsg = accountStore.isRegistered(uuid) ? loginChat : registerChat;
                if (chatMsg != null && !chatMsg.isEmpty()) {
                    p.sendMessage(chatMsg);
                }
            }

            nextSendAtMillis.put(uuid, now + intervalMillis);
        }

        if (nextSendAtMillis.isEmpty()) {
            stopTicker();
        }
    }

    private static final int READY = 144338180;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x101b) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x101b) == READY;
    }
}
