// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.util.Scheduler;

/**
 * Когда игрок последний раз прошёл проверку на бота. Файл
 * data/antibot-passed.txt («uuid время»), переживает рестарт: по сроку
 * antibot.recheck_hours зарегистрированные проходят проверку снова.
 * Хранятся только ещё не истёкшие отметки — файл маленький.
 */
final class AntiBotPasses {

    /** Срок хранения при выключенной перепроверке (recheck_hours: 0). */
    private static final long KEEP_WHEN_OFF_MS = 7L * 24 * 3600_000L;

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Long> at = new ConcurrentHashMap<>();
    private final AtomicBoolean saveQueued = new AtomicBoolean();
    private volatile long ttlMs;
    private volatile boolean loaded;

    AntiBotPasses(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/antibot-passed.txt");
    }

    /** Срок действия проверки (0 — бессрочно); при первом вызове читает файл. */
    void configure(long ttlMs) {
        this.ttlMs = Math.max(0L, ttlMs);
        if (loaded) {
            return;
        }
        loaded = true;
        if (!file.exists()) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                int sp = line.indexOf(' ');
                if (sp <= 0) {
                    continue;
                }
                try {
                    UUID u = UUID.fromString(line.substring(0, sp).trim());
                    long t = Long.parseLong(line.substring(sp + 1).trim());
                    if (t <= now && now - t < keepMs()) {
                        at.put(u, t);
                    }
                } catch (IllegalArgumentException ignored) {
                    // битая строка — пропускаем
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: data/antibot-passed.txt не прочитан: " + t);
        }
    }

    /** Проверка пройдена сейчас. */
    void mark(UUID uuid) {
        if (uuid == null) {
            return;
        }
        at.put(uuid, System.currentTimeMillis());
        saveSoon();
    }

    /**
     * Истёк ли срок прошлой проверки. Нет отметки (не проходил после
     * установки 1.1.6 / срок вышел и запись вычищена) — истёк.
     * recheck_hours: 0 — никогда.
     */
    boolean expired(UUID uuid) {
        long ttl = ttlMs;
        if (ttl <= 0L || uuid == null) {
            return false;
        }
        Long t = at.get(uuid);
        return t == null || System.currentTimeMillis() - t >= ttl;
    }

    private long keepMs() {
        long ttl = ttlMs;
        return ttl > 0L ? ttl : KEEP_WHEN_OFF_MS;
    }

    private void saveSoon() {
        if (saveQueued.compareAndSet(false, true)) {
            try {
                Scheduler.runAsync(plugin, () -> {
                    saveQueued.set(false);
                    saveNow();
                });
            } catch (Throwable t) {
                // шедулер уже не принимает задачи (выключение) — сохранит shutdown
                saveQueued.set(false);
            }
        }
    }

    /** Записать файл сейчас (выключение плагина или фоновая задача). */
    synchronized void saveNow() {
        long now = System.currentTimeMillis();
        long keep = keepMs();
        at.entrySet().removeIf(e -> now - e.getValue() >= keep);
        List<String> lines = new ArrayList<>(at.size());
        for (Map.Entry<UUID, Long> e : at.entrySet()) {
            lines.add(e.getKey() + " " + e.getValue());
        }
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return;
            }
            File tmp = new File(dir, "antibot-passed.txt.tmp");
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: data/antibot-passed.txt не сохранён: " + t);
        }
    }
}
