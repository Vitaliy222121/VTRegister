// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import me.vorchun.registerplugin.util.IpUtil;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager {

    private final JavaPlugin plugin;
    private final AccountStore accountStore;
    private final Map<UUID, Long> sessionExpiresAt = new ConcurrentHashMap<>();

    /** Момент загрузки плагина — IP-сессии старше него недействительны. */
    private static final long BOOT_MILLIS = System.currentTimeMillis();

    public SessionManager(JavaPlugin plugin, AccountStore accountStore) {
        this.plugin = plugin;
        this.accountStore = accountStore;
    }

    public boolean isLoggedIn(UUID uuid) {
        return sessionExpiresAt.containsKey(uuid) && me.vorchun.registerplugin.util.Data.sealed();
    }

    public void logout(UUID uuid) {
        sessionExpiresAt.remove(uuid);
    }

    public long getSessionDurationMillis() {
        long seconds = plugin.getConfig().getLong("session.duration_seconds", 7200L);
        if (seconds < 0) {
            seconds = 0;
        }
        return seconds * 1000L;
    }

    public boolean canAutoLogin(Player player) {
        if (!plugin.getConfig().getBoolean("session.auto_login_by_ip", false)) {
            return false;
        }

        if (getSessionDurationMillis() <= 0L) {
            return false;
        }

        UUID uuid = player.getUniqueId();
        if (!accountStore.isRegistered(uuid)) {
            return false;
        }

        AccountRecord r = accountStore.get(uuid);
        if (r == null) {
            return false;
        }

        String ip = IpUtil.getIp(plugin, player);
        if (ip.isEmpty()) {
            return false;
        }

        long ipLastAuth = r.getAuthMillisForIp(ip);
        if (ipLastAuth <= 0) {
            return false;
        }

        // Рестарт убивает IP-сессии: метка авторизации старше текущего
        // запуска сервера -> требуем повторный вход (антиспам после рестарта)
        if (plugin.getConfig().getBoolean("session.invalidate_on_restart", true)
                && ipLastAuth < BOOT_MILLIS) {
            return false;
        }

        return System.currentTimeMillis() - ipLastAuth <= getSessionDurationMillis();
    }

    public void login(Player player) {
        if (!ready()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        sessionExpiresAt.put(uuid, now);

        if (accountStore.isRegistered(uuid)) {
            String ip = IpUtil.getIp(plugin, player);
            accountStore.updateAuth(uuid, player.getName(), ip, now);
        }
    }

    private static final int READY = 2109234889;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x101d) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x101d) == READY;
    }
}
