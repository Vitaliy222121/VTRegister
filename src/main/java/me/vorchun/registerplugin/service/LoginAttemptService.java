// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import me.vorchun.registerplugin.util.IpUtil;
import me.vorchun.registerplugin.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Лимит неверных паролей. Ключ блокировки — пара «аккаунт + IP» (F54):
 * чужой с другого IP, вводя неверные пароли, не запирает владельцу вход.
 * Отдельно — счётчик на IP по всем аккаунтам (перебор разных ников с одного
 * адреса), порог в IP_FACTOR раз выше. IP неизвестен ("" — прокси без
 * forwarding / недоверенные IP) — ключ только аккаунт, как раньше.
 */
public final class LoginAttemptService {

    private static final class State {
        int fails;
        long lockedUntilMillis;
        long lastFailMillis;
    }

    /** Порог per-IP счётчика = max_login_attempts * IP_FACTOR. */
    private static final int IP_FACTOR = 3;
    /** Неверные пароли старше окна забываются (минимум 10 минут). */
    private static final long MIN_WINDOW_MS = 600_000L;
    private static final int PURGE_ABOVE = 2048;

    private final JavaPlugin plugin;
    private final MessageService messages;

    private final Map<String, State> states = new ConcurrentHashMap<>();

    private volatile int cachedMaxAttempts;
    private volatile int cachedLockSeconds;

    public LoginAttemptService(JavaPlugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public void reload() {
        int max = plugin.getConfig().getInt("security.max_login_attempts", 5);
        cachedMaxAttempts = Math.max(1, max);

        int lock = plugin.getConfig().getInt("security.lock_seconds", 300);
        cachedLockSeconds = Math.max(0, lock);
    }

    public int getMaxAttempts() {
        return cachedMaxAttempts;
    }

    public int getLockSeconds() {
        return cachedLockSeconds;
    }

    private static String pairKey(UUID uuid, String ip) {
        return uuid + "|" + (ip == null ? "" : ip);
    }

    private static String ipKey(String ip) {
        return "ip:" + ip;
    }

    private String ipOf(Player player) {
        String ip = IpUtil.getIp(plugin, player);
        return ip == null ? "" : ip;
    }

    /** Заблокирован ли вход этому игроку: по паре аккаунт+IP и по самому IP. */
    public boolean isLocked(Player player) {
        if (!ready()) {
            return true;
        }
        String ip = ipOf(player);
        if (lockedKey(pairKey(player.getUniqueId(), ip))) {
            return true;
        }
        return !ip.isEmpty() && lockedKey(ipKey(ip));
    }

    /** Совместимость: только ключ аккаунта без IP (IP неизвестен). */
    public boolean isLocked(UUID uuid) {
        if (!ready()) {
            return true;
        }
        return lockedKey(pairKey(uuid, ""));
    }

    private boolean lockedKey(String key) {
        State s = states.get(key);
        if (s == null) {
            return false;
        }
        // onFail зовут и из async-потока чата (код 2FA) — состояние под замком
        synchronized (s) {
            if (s.lockedUntilMillis <= 0) {
                return false;
            }
            if (System.currentTimeMillis() >= s.lockedUntilMillis) {
                s.lockedUntilMillis = 0;
                s.fails = 0;
                return false;
            }
            return true;
        }
    }

    /** Успешный вход: сбросить все пары этого аккаунта (IP-счётчик не трогаем). */
    public void reset(UUID uuid) {
        String prefix = uuid + "|";
        states.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void onFail(Player player) {
        if (plugin instanceof me.vorchun.registerplugin.RegisterPlugin) {
            SecurityAudit audit = ((me.vorchun.registerplugin.RegisterPlugin) plugin).getSecurityAudit();
            if (audit != null) {
                audit.onFail(player, "неверный пароль или код");
            }
        }
        UUID uuid = player.getUniqueId();
        String ip = ipOf(player);
        long now = System.currentTimeMillis();
        purgeIfLarge(now);
        boolean kick = count(pairKey(uuid, ip), getMaxAttempts(), now);
        if (!ip.isEmpty()) {
            kick |= count(ipKey(ip), getMaxAttempts() * IP_FACTOR, now);
        }

        if (kick) {
            String reason = messages.message("too_many_attempts_kick");
            // Кик — в потоке игрока (Folia), на Paper это главный поток
            Scheduler.runAtEntity(plugin, player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.kickPlayer(reason == null ? "" : reason);
            });
        }
    }

    /** +1 неверный пароль по ключу; true — порог достигнут (лок и кик). */
    private boolean count(String key, int max, long now) {
        State s = states.computeIfAbsent(key, k -> new State());
        synchronized (s) {
            if (s.lastFailMillis > 0 && now - s.lastFailMillis > windowMs() && s.lockedUntilMillis <= 0) {
                s.fails = 0;
            }
            s.lastFailMillis = now;
            s.fails++;
            if (s.fails < max) {
                return false;
            }
            int lockSeconds = getLockSeconds();
            if (lockSeconds > 0) {
                s.lockedUntilMillis = now + (long) lockSeconds * 1000L;
            }
            return true;
        }
    }

    private long windowMs() {
        return Math.max(MIN_WINDOW_MS, getLockSeconds() * 1000L);
    }

    /** Боты с новыми ник+IP не должны раздувать карту: выбрасываем остывшие. */
    private void purgeIfLarge(long now) {
        if (states.size() < PURGE_ABOVE) {
            return;
        }
        long window = windowMs();
        states.values().removeIf(s -> {
            synchronized (s) {
                return s.lockedUntilMillis < now && now - s.lastFailMillis > window;
            }
        });
    }

    private static final int READY = 866282983;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1016) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1016) == READY;
    }
}
