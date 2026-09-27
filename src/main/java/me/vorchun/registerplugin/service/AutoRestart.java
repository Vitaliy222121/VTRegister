// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.util.Scheduler;

/**
 * Автоматический перезапуск сервера (advanced.yml → auto_restart).
 * mode: time — в заданное время суток в выбранном часовом поясе раз в
 * every_days дней (по умолчанию 00:00 МСК каждые 48 ч); mode: uptime — через
 * uptime_hours часов работы. Игроков предупреждают по warn_seconds.
 */
public final class AutoRestart {

    private final JavaPlugin plugin;
    private volatile Scheduler.Task task;
    private volatile long restartAt;
    private volatile List<Integer> warns = new ArrayList<>();
    private volatile int lastWarned = Integer.MAX_VALUE;
    private volatile boolean firing;

    public AutoRestart(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        stop();
        if (!plugin.getConfig().getBoolean("auto_restart.enabled", true)) {
            restartAt = 0;
            return;
        }
        List<Integer> w = new ArrayList<>(plugin.getConfig().getIntegerList("auto_restart.warn_seconds"));
        if (w.isEmpty()) {
            w.add(300);
            w.add(60);
            w.add(10);
        }
        w.sort((a, b) -> b - a);
        warns = w;
        lastWarned = Integer.MAX_VALUE;
        restartAt = computeNext();
        if (restartAt <= 0) {
            return;
        }
        plugin.getLogger().info("Автоперезапуск: " + describe());
        task = Scheduler.runSyncTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        Scheduler.Task t = task;
        if (t != null) {
            t.cancel();
            task = null;
        }
    }

    /** Когда следующий перезапуск (для /authadmin status и лога). */
    public String describe() {
        if (restartAt <= 0) {
            return "выключен";
        }
        ZonedDateTime z = Instant.ofEpochMilli(restartAt).atZone(zone());
        return String.format("%02d.%02d %02d:%02d (%s), через %d ч %d мин",
                z.getDayOfMonth(), z.getMonthValue(), z.getHour(), z.getMinute(), zone().getId(),
                (restartAt - System.currentTimeMillis()) / 3_600_000L,
                ((restartAt - System.currentTimeMillis()) / 60_000L) % 60);
    }

    private ZoneId zone() {
        String tz = plugin.getConfig().getString("auto_restart.timezone", "Europe/Moscow");
        if (tz == null || tz.trim().isEmpty() || "server".equalsIgnoreCase(tz.trim())) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(tz.trim());
        } catch (Exception e) {
            plugin.getLogger().warning("auto_restart.timezone: неизвестный пояс '" + tz
                    + "' — беру часовой пояс сервера");
            return ZoneId.systemDefault();
        }
    }

    private long computeNext() {
        long now = System.currentTimeMillis();
        long started;
        try {
            started = ManagementFactory.getRuntimeMXBean().getStartTime();
        } catch (Throwable t) {
            started = now;
        }
        String mode = plugin.getConfig().getString("auto_restart.mode", "time").trim().toLowerCase(java.util.Locale.ROOT);
        if ("uptime".equals(mode)) {
            long hours = Math.max(1, plugin.getConfig().getInt("auto_restart.uptime_hours", 48));
            long at = started + hours * 3_600_000L;
            return Math.max(at, now + 60_000L);
        }
        LocalTime time;
        try {
            time = LocalTime.parse(plugin.getConfig().getString("auto_restart.time", "00:00").trim());
        } catch (Exception e) {
            plugin.getLogger().warning("auto_restart.time: нужен формат ЧЧ:ММ — беру 00:00");
            time = LocalTime.MIDNIGHT;
        }
        int days = Math.max(1, plugin.getConfig().getInt("auto_restart.every_days", 2));
        // Раз в N дней: ближайшее «время» не раньше, чем через (N сутки − 12 ч)
        // после запуска сервера — так при every_days: 2 перезапуск идёт
        // примерно каждые 48 часов, всегда в заданное время.
        long minUptime = Math.max(1, days * 24L - 12L) * 3_600_000L;
        ZoneId zone = zone();
        ZonedDateTime c = ZonedDateTime.now(zone).with(time).withSecond(0).withNano(0);
        for (int i = 0; i < 400; i++) {
            long ms = c.toInstant().toEpochMilli();
            if (ms > now + 60_000L && ms - started >= minUptime) {
                return ms;
            }
            c = c.plusDays(1);
        }
        return 0;
    }

    private void tick() {
        if (firing || restartAt <= 0) {
            return;
        }
        long left = (restartAt - System.currentTimeMillis() + 999) / 1000L;
        for (int w : warns) {
            if (left <= w && w < lastWarned) {
                lastWarned = w;
                broadcast(plugin.getConfig().getString("auto_restart.warn_message",
                        "&c⚠ Перезапуск сервера через &f{time}&c.").replace("{time}", human(w)));
                break;
            }
        }
        if (left <= 0) {
            firing = true;
            String kick = color(plugin.getConfig().getString("auto_restart.kick_message",
                    "&eСервер перезапускается. Зайди через минуту!"));
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                try {
                    p.kickPlayer(kick);
                } catch (Throwable ignored) {
                }
            }
            String cmd = plugin.getConfig().getString("auto_restart.command", "restart").trim();
            plugin.getLogger().warning("Автоперезапуск: выполняю «" + cmd + "»");
            Scheduler.runSyncLater(plugin, () -> {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.isEmpty() ? "stop" : cmd);
                } catch (Throwable t) {
                    Bukkit.shutdown();
                }
            }, 20L);
        }
    }

    private static String human(int s) {
        if (s >= 60) {
            int m = s / 60;
            return m + " мин" + (s % 60 > 0 ? " " + (s % 60) + " сек" : "");
        }
        return s + " сек";
    }

    private void broadcast(String msg) {
        String c = color(msg);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(c);
        }
        plugin.getLogger().info(ChatColor.stripColor(c));
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
