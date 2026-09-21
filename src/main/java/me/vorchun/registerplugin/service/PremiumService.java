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
 * корректным IP-forwarding), UUID игрока совпадает с UUID его лицензионного
 * аккаунта. Тогда пароль вводить не нужно.
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

    private volatile boolean enabled;
    private volatile boolean onlyFirstJoin;
    private volatile long cacheMinutes;
    private volatile long lastRequestAt;
    private volatile long minIntervalMs;

    public PremiumService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("premium.enabled", false);
        onlyFirstJoin = plugin.getConfig().getBoolean("premium.only_first_join", false);
        cacheMinutes = Math.max(1, plugin.getConfig().getInt("premium.cache_minutes", 60));
        minIntervalMs = Math.max(0, plugin.getConfig().getInt("premium.min_interval_ms", 250));
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Асинхронно проверить, лицензионный ли игрок.
     * Колбэк вызывается в главном потоке; true = можно авторизовать без пароля.
     */
    public void check(Player player, Consumer<Boolean> callback) {
        if (!enabled || !p7()) {
            callback.accept(false);
            return;
        }
        String name = player.getName();
        UUID serverUuid = player.getUniqueId();

        // Периодически вычищаем просроченные записи, чтобы кэш не рос бесконечно
        if (cache.size() > 1000) {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(e -> e.getValue().expiresAt < now);
        }

        Entry cached = cache.get(name.toLowerCase(java.util.Locale.ROOT));
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            callback.accept(cached.uuid.equals(serverUuid));
            return;
        }

        Scheduler.runAsync(plugin, () -> {
            UUID premium = fetchUuid(name);
            if (premium != null) {
                cache.put(name.toLowerCase(java.util.Locale.ROOT),
                        new Entry(premium, System.currentTimeMillis() + cacheMinutes * 60_000L));
            }
            boolean match = premium != null && premium.equals(serverUuid);
            Scheduler.runSync(plugin, () -> callback.accept(match));
        });
    }

    /** Синхронный запрос к Mojang — вызывать только из фонового потока. */
    private UUID fetchUuid(String name) {
        try {
            long now = System.currentTimeMillis();
            long wait = minIntervalMs - (now - lastRequestAt);
            if (wait > 0) {
                Thread.sleep(wait);
            }
            lastRequestAt = System.currentTimeMillis();

            HttpURLConnection conn = (HttpURLConnection) new URL(API + name).openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);
            conn.setRequestProperty("User-Agent", "RegisterPlugin/VTRegister");
            int code = conn.getResponseCode();
            if (code == 204 || code == 404) {
                return null; // игрока нет в лицензии
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

    private static final int P7 = 1068906301;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x101a) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x101a) == P7;
    }
}
