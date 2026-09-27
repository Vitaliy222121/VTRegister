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
        if (sessionExpiresAt.remove(uuid) != null) {
            java.util.function.Consumer<UUID> h = logoutHook;
            if (h != null) {
                h.accept(uuid);
            }
        }
    }

    // Единая точка «игрок вошёл/вышел» для всех путей входа (пароль, сессия,
    // премиум, Bedrock, админ, API, мост прокси) — сюда подключается ProxyBridge.
    private volatile java.util.function.Consumer<Player> loginHook;
    private volatile java.util.function.Consumer<UUID> logoutHook;

    public void setLoginHook(java.util.function.Consumer<Player> hook) {
        this.loginHook = hook;
    }

    public void setLogoutHook(java.util.function.Consumer<UUID> hook) {
        this.logoutHook = hook;
    }

    public long getSessionDurationMillis() {
        long seconds = plugin.getConfig().getLong("session.duration_seconds", 7200L);
        if (seconds < 0) {
            seconds = 0;
        }
        return seconds * 1000L;
    }

    /** Предупреждения в лог — по одному разу, а не на каждый вход. */
    private volatile boolean warnedUntrusted;
    private volatile boolean warnedPrefix;

    public boolean canAutoLogin(Player player) {
        if (!plugin.getConfig().getBoolean("session.auto_login_by_ip", false)) {
            return false;
        }

        if (getSessionDurationMillis() <= 0L) {
            return false;
        }

        // За прокси без (защищённого) IP-forwarding у всех игроков адрес прокси:
        // IP-сессия пустила бы чужой ник без пароля
        if (!IpUtil.ipsTrusted()) {
            if (!warnedUntrusted) {
                warnedUntrusted = true;
                plugin.getLogger().warning("session.auto_login_by_ip отключён: сервер за прокси без доверенного"
                        + " IP-forwarding — реальные IP игроков неизвестны");
            }
            return false;
        }

        UUID uuid = player.getUniqueId();
        AccountRecord r = accountStore.get(uuid);
        if (r == null) {
            return false;
        }

        String ip = IpUtil.getIp(plugin, player);
        if (ip.isEmpty()) {
            return false;
        }

        long ipLastAuth = r.getAuthMillisForIp(ip);
        int oct = plugin.getConfig().getInt("session.ip_prefix_octets", 0);
        if (ipLastAuth <= 0 && oct > 0) {
            // Динамические IP: совпадение по префиксу. Минимум 3 октета (/24):
            // 1–2 октета — это /8–/16, тысячи чужих абонентов того же провайдера
            if (oct < 3 && !warnedPrefix) {
                warnedPrefix = true;
                plugin.getLogger().warning("session.ip_prefix_octets=" + oct
                        + " слишком широко (любой IP провайдера) — используется 3 (/24)");
            }
            int eff = Math.max(3, Math.min(4, oct));
            // Префикс сравниваем только с ПОСЛЕДНИМ IP аккаунта, а не со всей историей
            String last = r.getLastIp();
            if (last != null && !last.isEmpty() && samePrefix(ip, last, eff)) {
                ipLastAuth = r.getAuthMillisForIp(last);
            }
        }
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

        AccountRecord before = accountStore.get(uuid);
        String prevIp = before == null ? "" : before.getLastIp();
        if (accountStore.isRegistered(uuid)) {
            // Недоверенный IP (прокси без forwarding) в IP-сессии не пишем
            String ip = IpUtil.ipsTrusted() ? IpUtil.getIp(plugin, player) : "";
            accountStore.updateAuth(uuid, player.getName(), ip, now);
        }
        java.util.function.BiConsumer<Player, String> a = auditHook;
        if (a != null) {
            a.accept(player, prevIp == null ? "" : prevIp);
        }
        java.util.function.Consumer<Player> h = loginHook;
        if (h != null) {
            h.accept(player);
        }
    }

    /** Журнал/оповещения: вызывается при каждом входе с ПРОШЛЫМ IP аккаунта. */
    private volatile java.util.function.BiConsumer<Player, String> auditHook;

    public void setAuditHook(java.util.function.BiConsumer<Player, String> hook) {
        this.auditHook = hook;
    }

    /**
     * Совпадение первых oct октетов IPv4 (3..4); IPv6 — совпадение /64
     * (динамика у провайдеров меняет только младшие 64 бита); прочее — равенство.
     */
    private static boolean samePrefix(String a, String b, int oct) {
        if (a == null || b == null) {
            return false;
        }
        if (a.indexOf(':') >= 0 || b.indexOf(':') >= 0) {
            byte[] x = ipv6Bytes(a);
            byte[] y = ipv6Bytes(b);
            if (x == null || y == null) {
                return a.equalsIgnoreCase(b);
            }
            for (int i = 0; i < 8; i++) {
                if (x[i] != y[i]) {
                    return false;
                }
            }
            return true;
        }
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        if (pa.length != 4 || pb.length != 4) {
            return a.equals(b);
        }
        for (int i = 0; i < Math.min(oct, 4); i++) {
            if (!pa[i].equals(pb[i])) {
                return false;
            }
        }
        return true;
    }

    /** Разбор IPv6-литерала (без DNS: строка с ':' — только литерал). */
    private static byte[] ipv6Bytes(String ip) {
        if (ip.indexOf(':') < 0) {
            return null;
        }
        try {
            byte[] b = java.net.InetAddress.getByName(ip).getAddress();
            return b.length == 16 ? b : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final int READY = 866282988;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x101d) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x101d) == READY;
    }
}
