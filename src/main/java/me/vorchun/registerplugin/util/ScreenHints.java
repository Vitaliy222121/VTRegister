// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * Подсказки по центру экрана (config.yml → screen_hints): что игроку делать
 * прямо сейчас. Общие настройки и показ для авторизации, этапов антибота,
 * отсчёта перед проверкой и «Готово!».
 *
 * Повтор: надпись показывается снова каждые refresh_seconds, пока шаг не
 * выполнен (не больше max_repeats раз, 0 — без ограничения). Плавное
 * появление — только в первый раз: повтор без fade_in не «мигает».
 */
public final class ScreenHints {

    private static volatile boolean enabled = true;
    private static volatile boolean auth = true;
    private static volatile boolean antibot = true;
    private static volatile boolean prepare = true;
    private static volatile boolean done = true;
    private static volatile int stayTicks = 60;
    private static volatile long refreshMs = 3000L;
    private static volatile int maxRepeats = 0;
    private static volatile int fadeIn = 8;
    private static volatile int fadeOut = 10;

    /** Какая подсказка у игрока на экране: ключ, время показа, сколько раз показана. */
    private static final Map<UUID, Object[]> shown = new ConcurrentHashMap<>();

    private ScreenHints() {
    }

    public static void load(FileConfiguration c) {
        enabled = c.getBoolean("screen_hints.enabled", true);
        auth = c.getBoolean("screen_hints.auth", true);
        antibot = c.getBoolean("screen_hints.antibot", true);
        prepare = c.getBoolean("screen_hints.prepare", true);
        done = c.getBoolean("screen_hints.done", true);
        stayTicks = clamp(c.getInt("screen_hints.stay_seconds", 3), 1, 30) * 20;
        int refresh = c.getInt("screen_hints.refresh_seconds", 3);
        refreshMs = refresh <= 0 ? Long.MAX_VALUE : clamp(refresh, 1, 60) * 1000L;
        maxRepeats = clamp(c.getInt("screen_hints.max_repeats", 0), 0, 1000);
        fadeIn = clamp(c.getInt("screen_hints.fade_in_ticks", 8), 0, 100);
        fadeOut = clamp(c.getInt("screen_hints.fade_out_ticks", 10), 0, 100);
    }

    public static boolean auth() {
        return enabled && auth;
    }

    public static boolean antibot() {
        return enabled && antibot;
    }

    public static boolean prepare() {
        return enabled && prepare;
    }

    public static boolean done() {
        return enabled && done;
    }

    /**
     * Показать подсказку с ключом key, если пора: новая подсказка — сразу (с
     * плавным появлением), та же — раз в refresh_seconds, не больше max_repeats.
     * Надпись держится не меньше интервала повтора — без пропадания между показами.
     */
    public static void show(Player p, String key, String title, String subtitle) {
        if (p == null || title == null || title.isEmpty()) {
            return;
        }
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        Object[] s = shown.get(u);
        boolean same = s != null && key.equals(s[0]);
        if (same) {
            int count = (Integer) s[2];
            if (now - (Long) s[1] < refreshMs || (maxRepeats > 0 && count >= maxRepeats)) {
                return;
            }
            s[1] = now;
            s[2] = count + 1;
        } else {
            shown.put(u, new Object[]{key, now, 1});
        }
        int stay = refreshMs == Long.MAX_VALUE ? stayTicks
                : (int) Math.max(stayTicks, Math.min(1200L, refreshMs / 50L + 10L));
        if (maxRepeats > 0 && same && (Integer) shown.get(u)[2] >= maxRepeats) {
            stay = stayTicks; // последний показ — обычная длительность
        }
        try {
            p.sendTitle(title, subtitle == null ? "" : subtitle, same ? 0 : fadeIn, stay, fadeOut);
        } catch (Throwable ignored) {
        }
    }

    /** Разовая надпись (отсчёт, «Готово!») — без учёта повторов. */
    public static void once(Player p, String title, String subtitle, int stay) {
        if (p == null || title == null || title.isEmpty()) {
            return;
        }
        shown.remove(p.getUniqueId());
        try {
            p.sendTitle(title, subtitle == null ? "" : subtitle, 0, stay <= 0 ? stayTicks : stay, fadeOut);
        } catch (Throwable ignored) {
        }
    }

    public static void forget(UUID u) {
        if (u != null) {
            shown.remove(u);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
