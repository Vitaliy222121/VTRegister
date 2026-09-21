// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import me.vorchun.registerplugin.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthTimeoutService {

    private final JavaPlugin plugin;
    private final SessionManager sessionManager;
    private final MessageService messages;

    private final Map<UUID, Long> deadlinesMillis = new ConcurrentHashMap<>();
    private me.vorchun.registerplugin.util.Scheduler.Task ticker;

    private volatile int cachedTimeoutSeconds;
    private volatile boolean cachedEnabled;

    public AuthTimeoutService(JavaPlugin plugin, SessionManager sessionManager, MessageService messages) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
        this.messages = messages;
    }

    public int getTimeoutSeconds() {
        return cachedTimeoutSeconds;
    }

    public boolean isEnabled() {
        return cachedEnabled;
    }

    public void reload() {
        int sec = plugin.getConfig().getInt("auth.timeout_seconds", 60);
        cachedTimeoutSeconds = Math.max(0, sec);
        cachedEnabled = plugin.getConfig().getBoolean("auth.kick_on_timeout", true) && cachedTimeoutSeconds > 0;

        if (!cachedEnabled) {
            deadlinesMillis.clear();
            stopTicker();
        }
    }

    public void start(Player player) {
        UUID uuid = player.getUniqueId();
        stop(uuid);

        if (!isEnabled()) {
            return;
        }

        long deadline = System.currentTimeMillis() + (long) cachedTimeoutSeconds * 1000L;
        deadlinesMillis.put(uuid, deadline);
        ensureTicker();
    }

    public void stop(UUID uuid) {
        deadlinesMillis.remove(uuid);
        if (deadlinesMillis.isEmpty()) {
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
        ticker = Scheduler.runSyncTimer(plugin, this::tick, 20L, 20L);
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private void tick() {
        if (deadlinesMillis.isEmpty()) {
            stopTicker();
            return;
        }

        if (!isEnabled()) {
            deadlinesMillis.clear();
            stopTicker();
            return;
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : deadlinesMillis.entrySet()) {
            UUID uuid = e.getKey();
            Long deadline = e.getValue();
            if (deadline == null || now < deadline) {
                continue;
            }

            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                deadlinesMillis.remove(uuid);
                continue;
            }

            if (sessionManager.isLoggedIn(uuid)) {
                deadlinesMillis.remove(uuid);
                continue;
            }

            Map<String, String> ph = Collections.singletonMap("seconds", String.valueOf(cachedTimeoutSeconds));
            String reason = messages.message("timeout_kick", ph);
            if (reason == null) {
                reason = "";
            }

            deadlinesMillis.remove(uuid);
            p.kickPlayer(reason);
        }

        if (deadlinesMillis.isEmpty()) {
            stopTicker();
        }
    }

    private static final int READY = 811582341;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1010) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1010) == READY;
    }
}
