// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.util.Scheduler;

/**
 * Премиум-автологин: если сервер работает в online-mode (или за прокси с
 * ЗАЩИЩЁННЫМ forwarding: Velocity modern, BungeeGuard или proxy.firewall),
 * UUID игрока совпадает с UUID его лицензионного аккаунта. Тогда пароль
 * вводить не нужно. Без защиты UUID подделывается — вход запрещён.
 *
 * Как проверяем:
 *  1. берём UUID игрока, как его видит сервер;
 *  2. спрашиваем у Mojang UUID по нику (api.mojang.com);
 *  3. если совпало — игрок лицензионный, авторизуем без пароля.
 *
 * Результаты кэшируются (ник → UUID, TTL из конфига), запросы уходят
 * только асинхронно и с ограничением частоты.
 */
public final class PremiumService {

    private static final String API = "https://api.mojang.com/users/profiles/minecraft/";

    private static final class Entry {
        final UUID uuid;
        final long expiresAt;

        Entry(UUID uuid, long expiresAt) {
            this.uuid = uuid;
            this.expiresAt = expiresAt;
        }
    }

    private final JavaPlugin plugin;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    /** Отрицательный ответ Mojang (ника нет в лицензии) — тоже кэшируем. */
    private static final UUID NOT_PREMIUM = new UUID(0L, 0L);
    /** Ник Mojang: 1–16 символов [A-Za-z0-9_]; остальное (Bedrock «.Ник») в API не шлём. */
    private static final java.util.regex.Pattern MOJANG_NAME = java.util.regex.Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private final Object rateLock = new Object();

    private volatile boolean enabled;
    private volatile boolean onlyFirstJoin;
    private volatile long cacheMinutes;
    private volatile long lastRequestAt;
    private volatile long minIntervalMs;
    // Можно ли верить UUID игрока (TeleportService#identityTrusted, ставит RegisterPlugin)
    private volatile java.util.function.BooleanSupplier identityTrusted = () -> true;
    private volatile boolean untrustedWarned;

    public PremiumService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("premium.enabled", false);
        onlyFirstJoin = plugin.getConfig().getBoolean("premium.only_first_join", false);
        cacheMinutes = Math.max(1, plugin.getConfig().getInt("premium.cache_minutes", 60));
        minIntervalMs = Math.max(0, plugin.getConfig().getInt("premium.min_interval_ms", 250));
        untrustedWarned = false;
        if (enabled && !identityOk()) {
            warnUntrusted();
        }
    }

    /**
     * Источник доверия к UUID. За прокси с legacy-forwarding без защиты
     * UUID приходит из хендшейка и подделывается — тогда премиум-вход
     * запрещён (fail-closed), игрок вводит пароль.
     */
    public void setIdentityTrust(java.util.function.BooleanSupplier src) {
        identityTrusted = src == null ? () -> true : src;
    }

    private boolean identityOk() {
        try {
            return identityTrusted.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    private void warnUntrusted() {
        if (untrustedWarned) {
            return;
        }
        untrustedWarned = true;
        plugin.getLogger().severe("premium.enabled: true, но UUID игроков нельзя доверять "
                + "(прокси с legacy BungeeCord-forwarding без BungeeGuard/proxy.firewall или без forwarding) — "
                + "премиум-автологин отключён, все вводят пароль. Настройка — PROXY_SETUP.txt.");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Асинхронно проверить, лицензионный ли игрок.
     * Колбэк вызывается в потоке игрока; true = можно авторизовать без пароля.
     */
    public void check(Player player, Consumer<Boolean> callback) {
        if (!enabled || !ready()) {
            callback.accept(false);
            return;
        }
        if (!identityOk()) {
            warnUntrusted();
            callback.accept(false);
            return;
        }
        String name = player.getName();
        UUID serverUuid = player.getUniqueId();
        if (name == null || !MOJANG_NAME.matcher(name).matches()) {
            callback.accept(false);
            return;
        }
        String key = name.toLowerCase(java.util.Locale.ROOT);

        // Периодически вычищаем просроченные записи, чтобы кэш не рос бесконечно
        if (cache.size() > 1000) {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(e -> e.getValue().expiresAt < now);
        }

        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            callback.accept(!NOT_PREMIUM.equals(cached.uuid) && cached.uuid.equals(serverUuid));
            return;
        }

        Scheduler.runAsync(plugin, () -> {
            UUID premium = fetchUuid(name);
            if (premium != null) {
                cache.put(key, new Entry(premium, System.currentTimeMillis() + cacheMinutes * 60_000L));
            }
            boolean match = premium != null && !NOT_PREMIUM.equals(premium) && premium.equals(serverUuid);
            Scheduler.runAtEntity(plugin, player, () -> callback.accept(match));
        });
    }

    /**
     * Синхронный запрос к Mojang — вызывать только из фонового потока.
     * @return UUID лицензии, NOT_PREMIUM — ника нет в лицензии, null — ошибка (не кэшируем).
     */
    private UUID fetchUuid(String name) {
        try {
            // Пауза между запросами общая для всех потоков пула
            synchronized (rateLock) {
                long now = System.currentTimeMillis();
                long wait = minIntervalMs - (now - lastRequestAt);
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                lastRequestAt = System.currentTimeMillis();
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(API
                    + java.net.URLEncoder.encode(name, "UTF-8")).openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);
            conn.setRequestProperty("User-Agent", "RegisterPlugin/VTRegister");
            int code = conn.getResponseCode();
            if (code == 204 || code == 404) {
                return NOT_PREMIUM; // игрока нет в лицензии
            }
            if (code != 200) {
                plugin.getLogger().warning("Premium: Mojang API вернул HTTP " + code);
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            String id = extractId(sb.toString());
            if (id == null) {
                return null;
            }
            return UUID.fromString(id.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                    "$1-$2-$3-$4-$5"));
        } catch (Throwable t) {
            plugin.getLogger().warning("Premium: ошибка запроса к Mojang: " + t.getMessage());
            return null;
        }
    }

    /** Простейший разбор {"id":"...","name":"..."} без JSON-библиотек. */
    private static String extractId(String json) {
        int idx = json.indexOf("\"id\"");
        if (idx < 0) {
            return null;
        }
        int start = json.indexOf('"', json.indexOf(':', idx) + 1);
        if (start < 0) {
            return null;
        }
        int end = json.indexOf('"', start + 1);
        if (end < 0) {
            return null;
        }
        return json.substring(start + 1, end);
    }

    public boolean isOnlyFirstJoin() {
        return onlyFirstJoin;
    }

    /** Убрать из кэша (например, при /authadmin reload). */
    public void clearCache() {
        cache.clear();
    }

    /** Сколько лицензионных игроков распознано — для /authadmin status. */
    public int cachedCount() {
        return cache.size();
    }

    /** Заглушка для тестов/без Bukkit. */
    public static UUID offlineUuid(String name) {
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    private static final int READY = 967612775;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x101a) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x101a) == READY;
    }
}
