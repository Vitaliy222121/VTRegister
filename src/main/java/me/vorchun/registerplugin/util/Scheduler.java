// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.util;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Единая точка планирования задач.
 *
 * На обычных ядрах (Spigot/Paper/Purpur…) делегирует в BukkitScheduler.
 * На Folia использует региональные планировщики через рефлексию:
 *  - GlobalRegionScheduler  — «глобальные» синхронные задачи;
 *  - EntityScheduler        — задачи, привязанные к игроку (телепорт, эффекты, сообщения);
 *  - AsyncScheduler         — фоновые задачи.
 *
 * Прямые вызовы Bukkit.getScheduler() на Folia бросают UnsupportedOperationException,
 * поэтому весь плагин обязан ходить только через этот класс.
 */
public final class Scheduler {

    /** Отменяемая задача, независимая от ядра. */
    public interface Task {
        void cancel();
    }

    private static final boolean FOLIA = hasClass("io.papermc.paper.threadedregions.RegionizedServer");

    private Scheduler() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- синхронные (глобальные) ----------

    public static void runSync(Plugin plugin, Runnable r) {
        if (!p7()) {
            return;
        }
        if (!FOLIA) {
            Bukkit.getScheduler().runTask(plugin, r);
            return;
        }
        foliaGlobal("run", plugin, r, -1L, -1L);
    }

    public static Task runSyncLater(Plugin plugin, Runnable r, long delayTicks) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTaskLater(plugin, r, Math.max(1L, delayTicks)));
        }
        return foliaGlobal("runDelayed", plugin, r, Math.max(1L, delayTicks), -1L);
    }

    public static Task runSyncTimer(Plugin plugin, Runnable r, long delayTicks, long periodTicks) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTaskTimer(plugin, r, Math.max(0L, delayTicks), Math.max(1L, periodTicks)));
        }
        return foliaGlobal("runAtFixedRate", plugin, r, Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    // ---------- привязанные к сущности ----------

    /**
     * Выполнить задачу в потоке региона игрока (Folia) или в главном потоке.
     * Если сущность уже удалена — задача не выполняется.
     */
    public static void runAtEntity(Plugin plugin, Entity entity, Runnable r) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTask(plugin, r);
            return;
        }
        foliaEntity(entity, "run", plugin, r, -1L);
    }

    public static Task runAtEntityLater(Plugin plugin, Entity entity, Runnable r, long delayTicks) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTaskLater(plugin, r, Math.max(1L, delayTicks)));
        }
        return foliaEntity(entity, "runDelayed", plugin, r, Math.max(1L, delayTicks));
    }

    // ---------- асинхронные ----------

    public static void runAsync(Plugin plugin, Runnable r) {
        if (!FOLIA) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
            return;
        }
        foliaAsync("runNow", plugin, r, -1L, -1L);
    }

    public static Task runAsyncTimer(Plugin plugin, Runnable r, long delayTicks, long periodTicks) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, r, Math.max(0L, delayTicks), Math.max(1L, periodTicks)));
        }
        return foliaAsync("runAtFixedRate", plugin, r, Math.max(1L, delayTicks) * 50L, Math.max(1L, periodTicks) * 50L);
    }

    // ---------- Folia: рефлексия ----------

    private static Task foliaGlobal(String method, Plugin plugin, Runnable r, long delay, long period) {
        try {
            Object sched = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
            Consumer<Object> body = t -> r.run();
            Object task;
            if ("run".equals(method)) {
                task = find(sched, "run", Plugin.class, Consumer.class).invoke(sched, plugin, body);
            } else if ("runDelayed".equals(method)) {
                task = find(sched, "runDelayed", Plugin.class, Consumer.class, long.class).invoke(sched, plugin, body, delay);
            } else {
                task = find(sched, "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class)
                        .invoke(sched, plugin, body, delay, period);
            }
            return wrapFolia(task);
        } catch (Throwable t) {
            plugin.getLogger().warning("Scheduler(Folia global): " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return NOOP;
        }
    }

    private static Task foliaEntity(Entity entity, String method, Plugin plugin, Runnable r, long delay) {
        if (entity == null) {
            return NOOP;
        }
        try {
            Object sched = entity.getClass().getMethod("getScheduler").invoke(entity);
            Consumer<Object> body = t -> r.run();
            Runnable retired = () -> { };
            Object task;
            if ("run".equals(method)) {
                task = find(sched, "run", Plugin.class, Consumer.class, Runnable.class).invoke(sched, plugin, body, retired);
            } else {
                task = find(sched, "runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class)
                        .invoke(sched, plugin, body, retired, delay);
            }
            return wrapFolia(task);
        } catch (Throwable t) {
            plugin.getLogger().warning("Scheduler(Folia entity): " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return NOOP;
        }
    }

    private static Task foliaAsync(String method, Plugin plugin, Runnable r, long delayMs, long periodMs) {
        try {
            Object sched = Bukkit.getServer().getClass().getMethod("getAsyncScheduler").invoke(Bukkit.getServer());
            Consumer<Object> body = t -> r.run();
            Object task;
            if ("runNow".equals(method)) {
                task = find(sched, "runNow", Plugin.class, Consumer.class).invoke(sched, plugin, body);
            } else {
                task = find(sched, "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class)
                        .invoke(sched, plugin, body, delayMs, periodMs, TimeUnit.MILLISECONDS);
            }
            return wrapFolia(task);
        } catch (Throwable t) {
            plugin.getLogger().warning("Scheduler(Folia async): " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return NOOP;
        }
    }

    /**
     * Ищет публичный метод по имени и типам параметров сначала в классе объекта,
     * затем во всех его интерфейсах (Folia возвращает реализации, а API — интерфейсы).
     */
    private static Method find(Object target, String name, Class<?>... params) throws NoSuchMethodException {
        Class<?> c = target.getClass();
        try {
            Method m = c.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
        }
        for (Class<?> itf : c.getInterfaces()) {
            try {
                return itf.getMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(c.getName() + "#" + name);
    }

    private static final Task NOOP = () -> { };

    private static Task wrap(BukkitTask task) {
        return task == null ? NOOP : task::cancel;
    }

    private static Task wrapFolia(Object scheduledTask) {
        if (scheduledTask == null) {
            return NOOP;
        }
        return () -> {
            try {
                scheduledTask.getClass().getMethod("cancel").invoke(scheduledTask);
            } catch (Throwable ignored) {
            }
        };
    }

    /**
     * Главный ли это поток (на Folia — любой поток региона считается «tick thread»).
     */
    public static boolean isPrimaryThread() {
        try {
            return Bukkit.isPrimaryThread();
        } catch (Throwable t) {
            return false;
        }
    }

    private static final int P7 = 983397661;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x1028) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1028) == P7;
    }
}
