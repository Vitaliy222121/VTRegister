// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import me.vorchun.registerplugin.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;

/**
 * Watchdog слушателей и команд: при внешнем снятии восстанавливает регистрацию,
 * при повторном вмешательстве выполняет действие из конфига (restart/stop).
 */
public final class StateSync implements Listener {

    private final JavaPlugin plugin;
    private final Runnable integrityFix;

    private volatile boolean enabled;
    private volatile String action = "stop";
    private volatile boolean integrityCheck;
    private volatile int checkIntervalSeconds = 10;
    private volatile boolean shuttingDown;
    private volatile long lastStopCommandAt;

    private me.vorchun.registerplugin.util.Scheduler.Task watchdog;
    private int tamperStrikes;

    /**
     * @param integrityFix колбэк, восстанавливающий команды и слушатели плагина
     */
    public StateSync(JavaPlugin plugin, Runnable integrityFix) {
        this.plugin = plugin;
        this.integrityFix = integrityFix;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("selfdefense.enabled", false);
        String a = plugin.getConfig().getString("selfdefense.action", "stop");
        action = (a != null && a.equalsIgnoreCase("restart")) ? "restart" : "stop";
        integrityCheck = plugin.getConfig().getBoolean("selfdefense.integrity_check", true);
        int sec = plugin.getConfig().getInt("selfdefense.check_interval_seconds", 10);
        checkIntervalSeconds = Math.max(3, sec);
        tamperStrikes = 0;

        stopWatchdog();
        if (enabled && integrityCheck && plugin.isEnabled()) {
            watchdog = Scheduler.runSyncTimer(plugin, this::checkIntegrity,
                    checkIntervalSeconds * 20L, checkIntervalSeconds * 20L);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Вызывается из onDisable — помечает штатное выключение,
     * чтобы не сработала реакция на отключение при остановке сервера.
     */
    public void markShutdown() {
        shuttingDown = true;
    }

    private void stopWatchdog() {
        if (watchdog != null) {
            try {
                watchdog.cancel();
            } catch (Throwable ignored) {
            }
            watchdog = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent e) {
        Plugin target = e.getPlugin();
        if (target == null || !target.getName().equalsIgnoreCase(plugin.getName())) {
            return;
        }
        if (!enabled || shuttingDown || isServerStopping()) {
            return;
        }

        plugin.getLogger().severe("=================================================");
        plugin.getLogger().severe("ОБНАРУЖЕНО ВЫКЛЮЧЕНИЕ RegisterPlugin СТОРОННИМ ПЛАГИНОМ!");
        plugin.getLogger().severe("Действие по конфигу selfdefense.action: " + action);
        plugin.getLogger().severe("=================================================");

        performAction();
    }

    /**
     * Отслеживаем команды остановки сервера, чтобы отличить штатный стоп
     * от выключения плагина сторонним плагином.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerCommand(org.bukkit.event.server.ServerCommandEvent e) {
        trackStopCommand(e.getCommand());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerStopCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        trackStopCommand(msg != null && msg.startsWith("/") ? msg.substring(1) : msg);
    }

    private void trackStopCommand(String cmd) {
        if (cmd == null) {
            return;
        }
        String base = cmd.trim().toLowerCase(java.util.Locale.ROOT);
        int sp = base.indexOf(' ');
        if (sp >= 0) {
            base = base.substring(0, sp);
        }
        int colon = base.indexOf(':');
        if (colon >= 0 && colon + 1 < base.length()) {
            base = base.substring(colon + 1);
        }
        if (base.equals("stop") || base.equals("restart") || base.equals("reload") || base.equals("rl")
                || base.equals("shutdown") || base.equals("end")) {
            lastStopCommandAt = System.currentTimeMillis();
        }
    }

    /**
     * Проверка, что сервер не находится в процессе штатной остановки.
     */
    private boolean isServerStopping() {
        // Недавно вводили stop/restart — считаем выключение штатным
        if (System.currentTimeMillis() - lastStopCommandAt < 15_000L) {
            return true;
        }

        // Paper: Server#isStopping()
        try {
            Method m = Bukkit.getServer().getClass().getMethod("isStopping");
            Object r = m.invoke(Bukkit.getServer());
            if (r instanceof Boolean && (Boolean) r) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        // CraftBukkit/NMS: MinecraftServer.isRunning
        try {
            Method getServer = Bukkit.getServer().getClass().getMethod("getServer");
            Object nms = getServer.invoke(Bukkit.getServer());
            Method isRunning = nms.getClass().getMethod("isRunning");
            Object r = isRunning.invoke(nms);
            if (r instanceof Boolean && !(Boolean) r) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void performAction() {
        try {
            if ("restart".equals(action)) {
                if (!tryRestart()) {
                    plugin.getLogger().warning("Restart API недоступно — выполняю stop");
                    Bukkit.shutdown();
                }
            } else {
                Bukkit.shutdown();
            }
        } catch (Throwable t) {
            try {
                Bukkit.shutdown();
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean tryRestart() {
        // Paper: Server#restart()
        try {
            Method m = Bukkit.getServer().getClass().getMethod("restart");
            m.invoke(Bukkit.getServer());
            return true;
        } catch (Throwable ignored) {
        }
        // Bukkit.spigot() может отсутствовать на чистом CraftBukkit
        try {
            Object spigot = Bukkit.getServer().getClass().getMethod("spigot").invoke(Bukkit.getServer());
            Method restart = spigot.getClass().getMethod("restart");
            restart.invoke(spigot);
            return true;
        } catch (Throwable ignored) {
        }
        // команда restart/restart-script у части ядер
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "restart");
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Watchdog: восстановление слушателей/команд, страйки при повторном вмешательстве. */
    private void checkIntegrity() {
        if (!plugin.isEnabled() || shuttingDown) {
            return;
        }

        boolean tampered = !ready();

        try {
            if (HandlerList.getRegisteredListeners(plugin).isEmpty()) {
                tampered = true;
            }
        } catch (Throwable ignored) {
        }

        try {
            if (plugin instanceof me.vorchun.registerplugin.RegisterPlugin
                    && !((me.vorchun.registerplugin.RegisterPlugin) plugin).commandsSynced()) {
                tampered = true;
            }
        } catch (Throwable ignored) {
        }

        if (!tampered) {
            tamperStrikes = 0;
            return;
        }

        tamperStrikes++;
        plugin.getLogger().warning("StateSync: обнаружено вмешательство в команды/слушатели плагина (удара " + tamperStrikes + ")");

        if (integrityFix != null) {
            try {
                integrityFix.run();
                plugin.getLogger().info("StateSync: целостность восстановлена");
            } catch (Throwable t) {
                plugin.getLogger().warning("StateSync: не удалось восстановить целостность: " + t.getMessage());
            }
        }

        int maxStrikes = plugin.getConfig().getInt("selfdefense.max_tamper_strikes", 3);
        if (tamperStrikes >= Math.max(1, maxStrikes)) {
            plugin.getLogger().severe("StateSync: повторные вмешательства — выполняю действие: " + action);
            performAction();
        }
    }

    private static final int READY = -779908233;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x101c) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x101c) == READY;
    }
}
