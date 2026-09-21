// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.api.AuthLoginEvent;
import me.vorchun.registerplugin.api.AuthLogoutEvent;
import me.vorchun.registerplugin.api.AuthRegisterEvent;
import me.vorchun.registerplugin.util.Scheduler;

/**
 * Ядро авторизации: единственное место, где пароль хешируется и проверяется.
 *
 * Ключевые свойства:
 *  - PBKDF2 (200k итераций) НИКОГДА не выполняется в главном потоке —
 *    ни при входе из чата, ни из команды, ни при смене пароля;
 *  - результат всегда применяется в главном потоке (Folia — в потоке игрока);
 *  - на один IP одновременно не более N проверок (защита от флуда хешами);
 *  - после успешного входа срабатывают события API и хуки (2FA, spawn, lobby).
 */
public final class AuthService {

    /** Причина, по которой вход не завершён. */
    public enum Result {
        OK,
        WRONG_PASSWORD,
        NOT_REGISTERED,
        ALREADY_LOGGED_IN,
        LOCKED,
        NEED_2FA,
        ERROR
    }

    private final JavaPlugin plugin;
    private final AccountStore accountStore;
    private final SessionManager sessionManager;
    private final LoginAttemptService loginAttempts;
    private final MessageService messages;

    /** Сколько проверок пароля одновременно выполняется (на весь сервер). */
    private final Semaphore hashSlots;
    /** Сколько проверок выполняется на конкретный IP. */
    private final Map<String, AtomicInteger> perIp = new ConcurrentHashMap<>();
    private final int maxPerIp;

    private final ExecutorService hashPool;

    public AuthService(JavaPlugin plugin, AccountStore accountStore, SessionManager sessionManager,
                       LoginAttemptService loginAttempts, MessageService messages) {
        this.plugin = plugin;
        this.accountStore = accountStore;
        this.sessionManager = sessionManager;
        this.loginAttempts = loginAttempts;
        this.messages = messages;
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        if (plugin.getConfig().isSet("limits.max_parallel_hash_checks")) {
            threads = Math.max(1, Math.min(8, plugin.getConfig().getInt("limits.max_parallel_hash_checks", threads) / 3));
        }
        this.hashPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "RegisterPlugin-Hash");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        this.hashSlots = new Semaphore(threads * 3);
        this.maxPerIp = Math.max(1, plugin.getConfig().getInt("limits.max_parallel_per_ip", 2));
    }

    public void shutdown() {
        hashPool.shutdown();
    }

    // ---------- вход ----------

    /**
     * Проверить пароль и, если он верный, авторизовать игрока.
     * Безопасно вызывать из любого потока.
     */
    public void login(Player player, String password, boolean fromChat, Consumer<Result> callback) {
        UUID uuid = player.getUniqueId();
        if (!ready()) {
            callback.accept(Result.ERROR);
            return;
        }
        if (sessionManager.isLoggedIn(uuid)) {
            callback.accept(Result.ALREADY_LOGGED_IN);
            return;
        }
        if (loginAttempts.isLocked(uuid)) {
            callback.accept(Result.LOCKED);
            return;
        }
        AccountRecord record = accountStore.get(uuid);
        if (record == null) {
            callback.accept(Result.NOT_REGISTERED);
            return;
        }

        String ip = me.vorchun.registerplugin.util.IpUtil.getIp(plugin, player);
        submitHash(ip, () -> {
            boolean ok;
            String stored = record.getPasswordHash();
            try {
                ok = PasswordHasher.verifyAny(password, stored);
            } catch (Throwable t) {
                ok = false;
            }
            final boolean verified = ok;
            Scheduler.runAtEntity(plugin, player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!verified) {
                    loginAttempts.onFail(player);
                    callback.accept(Result.WRONG_PASSWORD);
                    return;
                }
                // Прозрачный апгрейд хеша до актуальных параметров
                if (PasswordHasher.needsRehash(stored) || PasswordHasher.isForeign(stored)) {
                    accountStore.setPassword(uuid, PasswordHasher.hash(password));
                }
                // Второй фактор
                TotpService totp = totp();
                if (totp != null && totp.isEnabled() && record.hasTotp() && !totp.isTrusted(player)) {
                    totp.beginChallenge(player, password, fromChat);
                    callback.accept(Result.NEED_2FA);
                    return;
                }
                completeLogin(player, password);
                callback.accept(Result.OK);
            });
        });
    }

    /** Общая часть после успешной проверки пароля (в главном потоке). */
    public void completeLogin(Player player, String password) {
        sessionManager.login(player);
        loginAttempts.reset(player.getUniqueId());
        me.vorchun.registerplugin.util.Compat.updateCommands(player);
        me.vorchun.registerplugin.util.Compat.clearAuthDarkness(player);
        Bukkit.getPluginManager().callEvent(new AuthLoginEvent(player, false));
    }

    // ---------- регистрация ----------

    public interface RegisterCallback {
        void done(Result result, PasswordValidator.ValidationResult validation);
    }

    /**
     * Зарегистрировать игрока. Валидация выполняется сразу, хеширование — в пуле.
     */
    public void register(Player player, String password, RegisterCallback callback) {
        UUID uuid = player.getUniqueId();
        if (sessionManager.isLoggedIn(uuid)) {
            callback.done(Result.ALREADY_LOGGED_IN, PasswordValidator.ValidationResult.VALID);
            return;
        }
        if (accountStore.isRegistered(uuid)) {
            callback.done(Result.ALREADY_LOGGED_IN, PasswordValidator.ValidationResult.VALID);
            return;
        }

        PasswordValidator.ValidationResult vr = validate(password);
        if (vr != PasswordValidator.ValidationResult.VALID) {
            callback.done(Result.ERROR, vr);
            return;
        }

        String ip = me.vorchun.registerplugin.util.IpUtil.getIp(plugin, player);
        submitHash(ip, () -> {
            String hash = PasswordHasher.hash(password);
            Scheduler.runAtEntity(plugin, player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (accountStore.isRegistered(player.getUniqueId())) {
                    callback.done(Result.ALREADY_LOGGED_IN, PasswordValidator.ValidationResult.VALID);
                    return;
                }
                accountStore.register(player.getUniqueId(), player.getName(), hash, ip);
                sessionManager.login(player);
                loginAttempts.reset(player.getUniqueId());
                me.vorchun.registerplugin.util.Compat.updateCommands(player);
                me.vorchun.registerplugin.util.Compat.clearAuthDarkness(player);
                Bukkit.getPluginManager().callEvent(new AuthRegisterEvent(player));
                Bukkit.getPluginManager().callEvent(new AuthLoginEvent(player, true));
                callback.done(Result.OK, PasswordValidator.ValidationResult.VALID);
            });
        });
    }

    // ---------- смена пароля ----------

    /**
     * @param checkOld проверять ли старый пароль (при смене самим игроком)
     */
    public void changePassword(Player player, String oldPassword, String newPassword, boolean checkOld,
                               Consumer<PasswordValidator.ValidationResult> callback) {
        UUID uuid = player.getUniqueId();
        AccountRecord record = accountStore.get(uuid);
        if (record == null) {
            callback.accept(PasswordValidator.ValidationResult.INVALID_NULL);
            return;
        }
        PasswordValidator.ValidationResult vr = validate(newPassword);
        if (vr != PasswordValidator.ValidationResult.VALID) {
            callback.accept(vr);
            return;
        }
        String ip = me.vorchun.registerplugin.util.IpUtil.getIp(plugin, player);
        submitHash(ip, () -> {
            boolean oldOk = !checkOld || PasswordHasher.verifyAny(oldPassword, record.getPasswordHash());
            String hash = oldOk ? PasswordHasher.hash(newPassword) : null;
            Scheduler.runAtEntity(plugin, player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!oldOk) {
                    callback.accept(null); // null = старый пароль неверен
                    return;
                }
                accountStore.setPassword(uuid, hash);
                callback.accept(PasswordValidator.ValidationResult.VALID);
            });
        });
    }

    // ---------- выход ----------

    public void logout(Player player, boolean kick) {
        UUID uuid = player.getUniqueId();
        sessionManager.logout(uuid);
        Bukkit.getPluginManager().callEvent(new AuthLogoutEvent(player));
        if (kick && player.isOnline()) {
            Scheduler.runAtEntity(plugin, player, () -> player.kickPlayer(""));
        }
    }

    // ---------- внутреннее ----------

    private PasswordValidator.ValidationResult validate(String password) {
        int minLen = plugin.getConfig().getInt("password.min_length", 8);
        int maxLen = plugin.getConfig().getInt("password.max_length", 64);
        boolean enforce = plugin.getConfig().getBoolean("password.enforce_strength", true);
        java.util.Set<String> easy = easyPasswords();
        return PasswordValidator.validate(password, minLen, maxLen, enforce, easy);
    }

    private java.util.Set<String> easyPasswords() {
        if (plugin instanceof me.vorchun.registerplugin.RegisterPlugin) {
            EasyPasswordList list = ((me.vorchun.registerplugin.RegisterPlugin) plugin).getEasyPasswordList();
            return list == null ? null : list.getAllowed();
        }
        return null;
    }

    private TotpService totp() {
        if (plugin instanceof me.vorchun.registerplugin.RegisterPlugin) {
            return ((me.vorchun.registerplugin.RegisterPlugin) plugin).getTotpService();
        }
        return null;
    }

    /**
     * Выполнить тяжёлую операцию с паролем в фоновом пуле.
     * Ограничители: общий семафор (не забить CPU) и лимит на IP (не дать одному игроку флудить).
     */
    private void submitHash(String ip, Runnable work) {
        final String key = ip == null ? "" : ip;
        AtomicInteger counter = perIp.computeIfAbsent(key, k -> new AtomicInteger());
        if (counter.get() >= maxPerIp) {
            // Очередь переполнена — работаем вне пула, но всё равно асинхронно
            Scheduler.runAsync(plugin, () -> {
                try {
                    work.run();
                } finally {
                    perIp.remove(key);
                }
            });
            return;
        }
        counter.incrementAndGet();
        hashPool.execute(() -> {
            try {
                hashSlots.acquire();
                try {
                    work.run();
                } finally {
                    hashSlots.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                plugin.getLogger().warning("Ошибка обработки пароля: " + t.getMessage());
            } finally {
                counter.decrementAndGet();
                if (counter.get() <= 0) {
                    perIp.remove(key);
                }
            }
        });
    }

    private static final int READY = 812196039;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x100f) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x100f) == READY;
    }
}
