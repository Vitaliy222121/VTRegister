// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.api.AuthLogoutEvent;
import me.vorchun.registerplugin.api.AuthRegisterEvent;
import me.vorchun.registerplugin.util.IpUtil;
import me.vorchun.registerplugin.util.Scheduler;

/**
 * Ядро авторизации: единственное место, где пароль хешируется и проверяется.
 *
 * Ключевые свойства:
 *  - Argon2id / PBKDF2 НИКОГДА не выполняются в главном потоке —
 *    ни при входе из чата, ни из команды, ни при смене пароля, ни при апгрейде хеша;
 *  - результат всегда применяется в главном потоке (Folia — в потоке игрока);
 *  - параллельных хешей не больше потоков пула, очередь ограничена; на один IP
 *    одновременно не более N операций — лишнее получает отказ «подожди»
 *    (никакого «перелива» в безлимитный пул: Argon2 по 64 МБ = OOM);
 *  - сбой хранилища / запрет антибота — отказ с сообщением, колбэк не вызывается;
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

    /** Сколько операций с паролем сейчас в работе/очереди на ключ (IP или UUID). */
    private final Map<String, Integer> perKey = new ConcurrentHashMap<>();
    private final int maxPerIp;

    /**
     * Фиксированный пул: одновременно не больше threads хешей (память Argon2
     * ограничена), очередь конечна — переполнение = отказ, а не новый поток.
     */
    private final ThreadPoolExecutor hashPool;

    /** Разрешена ли регистрация (антибот: игрок прошёл проверку). null — всегда да. */
    private volatile Predicate<UUID> registerGate;

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
        this.hashPool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threads * 16), r -> {
            Thread t = new Thread(r, "RegisterPlugin-Hash");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.maxPerIp = Math.max(1, plugin.getConfig().getInt("limits.max_parallel_per_ip", 2));
    }

    public void shutdown() {
        hashPool.shutdown();
    }

    /**
     * Фильтр регистрации (защита в глубину, D1): RegisterPlugin подключает
     * antiBotService::mayRegister. Пока фильтр говорит «нет» — регистрация
     * отклоняется с сообщением antibot_wait_register.
     */
    public void setRegisterGate(Predicate<UUID> gate) {
        this.registerGate = gate;
    }

    private boolean registerAllowed(UUID uuid) {
        Predicate<UUID> g = registerGate;
        if (g == null) {
            return true;
        }
        try {
            return g.test(uuid);
        } catch (Throwable t) {
            // Сбой антибота не должен открывать регистрацию ботам
            return false;
        }
    }

    /** Отказ без вызова колбэка: сообщение шлём сами (в потоке игрока). */
    private void refuse(Player player, String key) {
        if (Bukkit.isPrimaryThread()) {
            if (player.isOnline()) {
                messages.send(player, key);
            }
            return;
        }
        Scheduler.runAtEntity(plugin, player, () -> {
            if (player.isOnline()) {
                messages.send(player, key);
            }
        });
    }

    // ---------- вход ----------

    /**
     * Проверить пароль и, если он верный, авторизовать игрока.
     * Безопасно вызывать из любого потока. При сбое хранилища или перегрузке
     * пула игрок получает сообщение, а колбэк не вызывается.
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
        if (loginAttempts.isLocked(player)) {
            callback.accept(Result.LOCKED);
            return;
        }
        AccountRecord record = accountStore.get(uuid);
        if (record == null) {
            if (accountStore.isUnavailable(uuid)) {
                refuse(player, "storage_unavailable");
                return;
            }
            callback.accept(Result.NOT_REGISTERED);
            return;
        }

        final String stored = record.getPasswordHash();
        boolean queued = submitHash(hashKey(player), () -> {
            boolean ok;
            try {
                ok = PasswordHasher.verifyAny(password, stored);
            } catch (Throwable t) {
                ok = false;
            }
            // Прозрачный апгрейд хеша до актуальных параметров — тоже здесь, в пуле
            // (Argon2 64 МБ в главном потоке = фриз на сотни мс на каждый вход)
            String upgraded = null;
            if (ok) {
                try {
                    if (PasswordHasher.needsRehash(stored) || PasswordHasher.isForeign(stored)) {
                        upgraded = PasswordHasher.hash(password);
                    }
                } catch (Throwable ignored) {
                    // апгрейд не обязателен: войти можно и со старым хешем
                }
            }
            final boolean verified = ok;
            final String newHash = upgraded;
            Scheduler.runAtEntity(plugin, player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!verified) {
                    loginAttempts.onFail(player);
                    callback.accept(Result.WRONG_PASSWORD);
                    return;
                }
                // Хеш не сменился, пока шла проверка (смена пароля/сброс) — только тогда апгрейд
                if (newHash != null && stored.equals(record.getPasswordHash())) {
                    accountStore.setPassword(uuid, newHash);
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
        if (!queued) {
            refuse(player, "auth_busy");
        }
    }

    /**
     * Общая часть после успешной проверки пароля. Выполняется в главном потоке
     * (потоке игрока на Folia): вызов из async-потока (например, код 2FA из чата)
     * переносится туда — синхронное AuthLoginEvent из async бросает исключение.
     */
    public void completeLogin(Player player, String password) {
        if (!Bukkit.isPrimaryThread()) {
            Scheduler.runAtEntity(plugin, player, () -> {
                if (player.isOnline()) {
                    completeLogin(player, password);
                }
            });
            return;
        }
        sessionManager.login(player);
        loginAttempts.reset(player.getUniqueId());
        me.vorchun.registerplugin.util.Compat.updateCommands(player);
        me.vorchun.registerplugin.util.Compat.clearAuthDarkness(player);
        // AuthLoginEvent зовёт общий финализатор входа (AuthListener.afterLoginSuccess):
        // так событие приходит при ЛЮБОМ входе (сессия, премиум, Bedrock, API) и один раз
    }

    // ---------- регистрация ----------

    public interface RegisterCallback {
        void done(Result result, PasswordValidator.ValidationResult validation);
    }

    /**
     * Зарегистрировать игрока. Валидация выполняется сразу, хеширование — в пуле.
     * Отказ без вызова колбэка (с сообщением игроку): антибот ещё не пропустил
     * (antibot_wait_register), хранилище недоступно, пул перегружен.
     */
    public void register(Player player, String password, RegisterCallback callback) {
        UUID uuid = player.getUniqueId();
        if (sessionManager.isLoggedIn(uuid)) {
            callback.done(Result.ALREADY_LOGGED_IN, PasswordValidator.ValidationResult.VALID);
            return;
        }
        if (!registerAllowed(uuid)) {
            refuse(player, "antibot_wait_register");
            return;
        }
        // Сначала «недоступно»: при сбое чтения isRegistered отвечает true,
        // и игрок получил бы ложное «уже зарегистрирован»
        if (accountStore.get(uuid) == null && accountStore.isUnavailable(uuid)) {
            refuse(player, "storage_unavailable");
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

        // За прокси без forwarding у всех адрес прокси — такой IP в аккаунт не пишем
        String ip = IpUtil.ipsTrusted() ? IpUtil.getIp(plugin, player) : "";
        // Лимит аккаунтов на IP: одна регистрация с IP за раз, подсчёт — после
        // сброса несохранённых, иначе пачка ботов с IP проскочила бы лимит
        final int ipMax = ipLimit(ip);
        final Long ipStamp = ipMax > 0 ? reserveIp(ip) : null;
        if (ipMax > 0 && ipStamp == null) {
            refuse(player, "auth_busy");
            return;
        }
        boolean queued = submitHash(hashKey(player), () -> {
            String hash = PasswordHasher.hash(password);
            final int have = ipMax > 0 ? accountStore.countByIpFlushed(ip) : 0;
            Scheduler.runAtEntity(plugin, player, () -> {
                try {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (sessionManager.isLoggedIn(uuid)) {
                        callback.done(Result.ALREADY_LOGGED_IN, PasswordValidator.ValidationResult.VALID);
                        return;
                    }
                    if (!registerAllowed(uuid)) {
                        refuse(player, "antibot_wait_register");
                        return;
                    }
                    if (accountStore.get(uuid) == null && accountStore.isUnavailable(uuid)) {
                        refuse(player, "storage_unavailable");
                        return;
                    }
                    if (accountStore.isRegistered(uuid)) {
                        callback.done(Result.ALREADY_LOGGED_IN, PasswordValidator.ValidationResult.VALID);
                        return;
                    }
                    if (ipMax > 0 && have >= ipMax) {
                        Map<String, String> ph = new java.util.HashMap<>();
                        ph.put("max", String.valueOf(ipMax));
                        messages.send(player, "register_ip_limit", ph);
                        return;
                    }
                    accountStore.register(player.getUniqueId(), player.getName(), hash, ip);
                    sessionManager.login(player);
                    loginAttempts.reset(player.getUniqueId());
                    me.vorchun.registerplugin.util.Compat.updateCommands(player);
                    me.vorchun.registerplugin.util.Compat.clearAuthDarkness(player);
                    Bukkit.getPluginManager().callEvent(new AuthRegisterEvent(player));
                    callback.done(Result.OK, PasswordValidator.ValidationResult.VALID);
                } finally {
                    if (ipStamp != null) {
                        ipRegistering.remove(ip, ipStamp);
                    }
                }
            });
        });
        if (!queued) {
            if (ipStamp != null) {
                ipRegistering.remove(ip, ipStamp);
            }
            refuse(player, "auth_busy");
        }
    }

    /** Регистрации в работе по IP → время начала (лимит аккаунтов на IP). */
    private final Map<String, Long> ipRegistering = new ConcurrentHashMap<>();

    /** ip_limit для адреса; 0 — не ограничиваем (выключен, IP неизвестен, адрес прокси). */
    private int ipLimit(String ip) {
        if (ip == null || ip.isEmpty() || !plugin.getConfig().getBoolean("ip_limit.enabled", true)
                || IpUtil.isProxyAddress(plugin, ip)) {
            return 0;
        }
        return Math.max(0, plugin.getConfig().getInt("ip_limit.max_accounts", 3));
    }

    /**
     * Занять IP на время регистрации; null — с этого IP регистрация уже идёт.
     * Метка старше 30 с считается зависшей (игрок вышел, задача потерялась).
     */
    private Long reserveIp(String ip) {
        long now = System.currentTimeMillis();
        Long[] got = {null};
        ipRegistering.compute(ip, (k, since) -> {
            if (since == null || now - since > 30_000L) {
                got[0] = now;
                return now;
            }
            return since;
        });
        return got[0];
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
            if (accountStore.isUnavailable(uuid)) {
                refuse(player, "storage_unavailable");
                return;
            }
            callback.accept(PasswordValidator.ValidationResult.INVALID_NULL);
            return;
        }
        PasswordValidator.ValidationResult vr = validate(newPassword);
        if (vr != PasswordValidator.ValidationResult.VALID) {
            callback.accept(vr);
            return;
        }
        boolean queued = submitHash(hashKey(player), () -> {
            boolean oldOk;
            try {
                oldOk = !checkOld || PasswordHasher.verifyAny(oldPassword, record.getPasswordHash());
            } catch (Throwable t) {
                oldOk = false;
            }
            final boolean verified = oldOk;
            String hash = oldOk ? PasswordHasher.hash(newPassword) : null;
            Scheduler.runAtEntity(plugin, player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!verified) {
                    callback.accept(null); // null = старый пароль неверен
                    return;
                }
                accountStore.setPassword(uuid, hash);
                // Новый пароль = отзыв доверия: IP-сессии и «доверенное устройство» 2FA
                // (иначе угонщик со своего IP продолжал бы входить без пароля)
                accountStore.clearAuth(uuid);
                TotpService totp = totp();
                if (totp != null) {
                    totp.forgetTrust(uuid);
                }
                // Сам игрок сейчас в игре — его текущий IP остаётся доверенным
                if (sessionManager.isLoggedIn(uuid)) {
                    accountStore.updateAuth(uuid, player.getName(),
                            IpUtil.ipsTrusted() ? IpUtil.getIp(plugin, player) : "",
                            System.currentTimeMillis());
                }
                callback.accept(PasswordValidator.ValidationResult.VALID);
            });
        });
        if (!queued) {
            refuse(player, "auth_busy");
        }
    }

    // ---------- выход ----------

    public void logout(Player player, boolean kick) {
        if (!Bukkit.isPrimaryThread()) {
            // AuthLogoutEvent синхронное — из async-потока (API) переносим в поток игрока
            Scheduler.runAtEntity(plugin, player, () -> logout(player, kick));
            return;
        }
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
     * Ключ лимита «на источник»: IP игрока, а если IP недоверенный (прокси без
     * forwarding — у всех адрес прокси) или пустой (known_proxy_ips) — UUID,
     * иначе все игроки за прокси делили бы один счётчик.
     */
    private String hashKey(Player player) {
        String ip = IpUtil.ipsTrusted() ? IpUtil.getIp(plugin, player) : "";
        return ip == null || ip.isEmpty() ? "u:" + player.getUniqueId() : ip;
    }

    /**
     * Выполнить тяжёлую операцию с паролем в фоновом пуле.
     * Ограничители: фиксированный пул с конечной очередью (не забить CPU и память)
     * и лимит на ключ (не дать одному источнику флудить). Превышение — отказ:
     * @return false — работа не принята (игроку нужно сказать «подожди»)
     */
    /**
     * API: проверить пароль аккаунта в пуле хеширования. done — в главном потоке;
     * false — неверный пароль, нет аккаунта или пул занят.
     */
    public void verifyAsync(UUID uuid, String password, Consumer<Boolean> done) {
        if (uuid == null || password == null || done == null) {
            if (done != null) {
                done.accept(false);
            }
            return;
        }
        boolean queued = submitHash("api:" + uuid, () -> {
            AccountRecord r = accountStore.get(uuid);
            boolean ok = r != null && r.getPasswordHash() != null
                    && PasswordHasher.verifyAny(password, r.getPasswordHash());
            Scheduler.runSync(plugin, () -> done.accept(ok));
        });
        if (!queued) {
            Scheduler.runSync(plugin, () -> done.accept(false));
        }
    }

    /** API: задать пароль без старого (как /authadmin setpw). done — в главном потоке. */
    public void setPasswordAsync(UUID uuid, String password, Consumer<Boolean> done) {
        if (uuid == null || password == null || password.isEmpty()) {
            if (done != null) {
                done.accept(false);
            }
            return;
        }
        boolean queued = submitHash("api:" + uuid, () -> {
            String hash = PasswordHasher.hash(password);
            boolean ok = accountStore.setPassword(uuid, hash);
            if (done != null) {
                Scheduler.runSync(plugin, () -> done.accept(ok));
            }
        });
        if (!queued && done != null) {
            Scheduler.runSync(plugin, () -> done.accept(false));
        }
    }

    private boolean submitHash(String key, Runnable work) {
        boolean[] entered = {false};
        perKey.compute(key, (k, n) -> {
            int cur = n == null ? 0 : n;
            if (cur >= maxPerIp) {
                return n;
            }
            entered[0] = true;
            return cur + 1;
        });
        if (!entered[0]) {
            return false;
        }
        try {
            hashPool.execute(() -> {
                try {
                    work.run();
                } catch (Throwable t) {
                    plugin.getLogger().warning("Ошибка обработки пароля: " + t.getMessage());
                } finally {
                    leaveKey(key);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            leaveKey(key);
            return false;
        }
    }

    private void leaveKey(String key) {
        perKey.computeIfPresent(key, (k, n) -> n <= 1 ? null : n - 1);
    }

    private static final int READY = 1124856756;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x100f) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x100f) == READY;
    }
}
