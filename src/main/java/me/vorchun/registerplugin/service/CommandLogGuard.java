// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Защита от попадания пароля в консоль и логи сервера.
 *
 * Важный факт (проверено по исходникам CraftBukkit/Paper):
 * строка «<ник> issued server command: /reg пароль» пишется ядром ДО события
 * PlayerCommandPreprocessEvent и управляется настройкой spigot.yml → commands.log.
 * Плагин не может отменить эту запись событием — поэтому здесь два механизма:
 *
 *  1. spigot.yml: если commands.log = true, плагин (по настройке) сам ставит false
 *     и пишет в консоль, что нужен перезапуск. Режимы: fix | warn | off.
 *  2. log4j2-фильтр (опционально): перехватывает сообщения лога и полностью
 *     отбрасывает строки с нашими auth-командами и аргументом-паролем.
 *     Работает на Paper/Purpur и большинстве современных ядер (log4j2 в classpath).
 *
 * Дополнительно: наши команды всё равно перехватываются на LOWEST и исполняются
 * внутри плагина, поэтому на ядрах с commands.log=false пароль не появляется
 * нигде — ни у других плагинов, ни в консоли.
 */
public final class CommandLogGuard {

    private final JavaPlugin plugin;

    private volatile String mode = "warn";     // fix | warn | off
    private volatile boolean log4jEnabled = true;
    private volatile boolean filterInstalled;
    private volatile boolean warned;

    public CommandLogGuard(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        mode = plugin.getConfig().getString("security.command_logging", "warn");
        if (mode == null) {
            mode = "warn";
        }
        mode = mode.toLowerCase(Locale.ROOT);
        log4jEnabled = plugin.getConfig().getBoolean("security.log4j_filter", true);
    }

    /** Вызывается один раз при старте (после reload). */
    public void apply() {
        if (!"off".equals(mode)) {
            handleSpigotYml();
        }
        if (log4jEnabled && !filterInstalled) {
            installLog4jFilter();
        }
    }

    // ---------- spigot.yml ----------

    private void handleSpigotYml() {
        try {
            // spigot.yml лежит в корне сервера: сначала пробуем рабочий каталог,
            // затем каталог миров (обычно это и есть корень сервера)
            File spigotYml = new File("spigot.yml");
            if (!spigotYml.exists()) {
                spigotYml = new File(plugin.getServer().getWorldContainer(), "spigot.yml");
            }
            if (!spigotYml.exists()) {
                return; // ядро без spigot.yml (например, чистый CraftBukkit или Folia)
            }
            List<String> lines = Files.readAllLines(spigotYml.toPath(), StandardCharsets.UTF_8);
            int commandsIdx = -1;
            int logIdx = -1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("commands:")) {
                    commandsIdx = i;
                    continue;
                }
                if (commandsIdx >= 0 && i > commandsIdx && !line.isEmpty() && line.charAt(0) != ' ') {
                    break; // секция commands закончилась
                }
                if (commandsIdx >= 0 && line.trim().startsWith("log:")) {
                    logIdx = i;
                    break;
                }
            }
            if (logIdx < 0) {
                return; // нет ключа — ядро по умолчанию не логирует или структура иная
            }
            String value = lines.get(logIdx).trim();
            if (value.contains("false")) {
                return; // уже выключено — идеально
            }
            if ("fix".equals(mode)) {
                lines.set(logIdx, lines.get(logIdx).replaceAll("log:\\s*true", "log: false"));
                Files.write(spigotYml.toPath(), lines, StandardCharsets.UTF_8);
                plugin.getLogger().warning("=================================================");
                plugin.getLogger().warning("spigot.yml: commands.log = false (пароли в командах больше не пишутся в логи).");
                plugin.getLogger().warning("Настройка применится после ПЕРЕЗАПУСКА сервера.");
                plugin.getLogger().warning("=================================================");
            } else if (!warned) {
                warned = true;
                plugin.getLogger().warning("=================================================");
                plugin.getLogger().warning("ВНИМАНИЕ: в spigot.yml стоит commands.log: true.");
                plugin.getLogger().warning("Ядро пишет в консоль все команды игроков, включая пароли.");
                plugin.getLogger().warning("Поставь commands.log: false или security.command_logging: fix");
                plugin.getLogger().warning("=================================================");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("CommandLogGuard: не удалось проверить spigot.yml: " + t.getMessage());
        }
    }

    // ---------- log4j2 ----------

    /**
     * Ставит фильтр на корневой логгер: строки с нашими auth-командами отбрасываются.
     * Всё на рефлексии — если log4j2 недоступен, тихо выходим.
     */
    private void installLog4jFilter() {
        try {
            Class<?> filterClass = Class.forName("org.apache.logging.log4j.core.Filter");
            Class<?> logEventClass = Class.forName("org.apache.logging.log4j.core.LogEvent");
            Class<?> resultClass = Class.forName("org.apache.logging.log4j.core.Filter$Result");
            Object deny = Enum.valueOf(resultClass.asSubclass(Enum.class), "DENY");
            Object neutral = Enum.valueOf(resultClass.asSubclass(Enum.class), "NEUTRAL");

            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if ("filter".equals(name) && args != null && args.length > 0 && args[0] != null) {
                    Object event = args[0];
                    String message = null;
                    try {
                        Method getFormatted = logEventClass.getMethod("getFormattedMessage");
                        message = (String) getFormatted.invoke(event);
                    } catch (Throwable ignored) {
                    }
                    if (message != null && containsAuthPassword(message)) {
                        return deny;
                    }
                    return neutral;
                }
                if ("getState".equals(name) || "getOnMatch".equals(name) || "getOnMismatch".equals(name)) {
                    return neutral;
                }
                if ("isStarted".equals(name)) {
                    return Boolean.TRUE;
                }
                if ("start".equals(name) || "stop".equals(name) || "initialize".equals(name)) {
                    return null;
                }
                if ("toString".equals(name)) {
                    return "VTRegister-LogGuard";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == (args == null || args.length == 0 ? null : args[0]);
                }
                return null;
            };

            Object filter = Proxy.newProxyInstance(filterClass.getClassLoader(),
                    new Class<?>[]{filterClass}, handler);

            Object rootLogger = org.bukkit.Bukkit.getLogger();
            Object log4jLogger = null;
            try {
                Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager");
                log4jLogger = logManager.getMethod("getRootLogger").invoke(null);
            } catch (Throwable ignored) {
            }
            if (log4jLogger == null) {
                return;
            }
            Class<?> coreLoggerClass = Class.forName("org.apache.logging.log4j.core.Logger");
            if (!coreLoggerClass.isInstance(log4jLogger)) {
                return;
            }
            Method addFilter = coreLoggerClass.getMethod("addFilter", filterClass);
            addFilter.invoke(log4jLogger, filter);
            filterInstalled = true;
            plugin.getLogger().info("CommandLogGuard: log4j-фильтр установлен — пароли из команд не попадут в логи.");
        } catch (Throwable t) {
            // Не критично: на старых ядрах log4j может отсутствовать
            if (log4jEnabled) {
                plugin.getLogger().info("CommandLogGuard: log4j2-фильтр недоступен (" + t.getClass().getSimpleName() + ")");
            }
        }
    }

    /**
     * Строка похожа на «ник issued server command: /reg ПАРОЛЬ»?
     * Проверяем и с префиксом "issued server command", и просто на команду с аргументом,
     * чтобы не пропустить переименованные ядра.
     */
    private boolean containsAuthPassword(String message) {
        if (!ready()) {
            return true;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        boolean isCommandLog = lower.contains("issued server command")
                || lower.contains("issued command")
                || lower.contains("command executed");
        if (!isCommandLog) {
            return false;
        }
        String[] risky = {"/reg ", "/register ", "/login ", "/l ", "/changepassword ", "/changepw ",
                "/cp ", "/passwd ", "/authadmin setpw ", "/2fa ", "/email ", "/recover "};
        for (String prefix : risky) {
            if (lower.contains(prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean isFilterInstalled() {
        return filterInstalled;
    }

    public String getMode() {
        return mode;
    }

    private static final int READY = -111058289;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1012) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1012) == READY;
    }
}
