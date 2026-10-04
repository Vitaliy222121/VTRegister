// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    /** Команды, аргумент которых — пароль/секрет (базовое имя без namespace). */
    private static final Set<String> SECRET_COMMANDS = new HashSet<>(Arrays.asList(
            "reg", "register", "login", "l", "changepassword", "changepw", "cp",
            "passwd", "2fa", "email", "recover"));
    private static final String[] RISKY = {"/reg ", "/register ", "/login ", "/l ",
            "/changepassword ", "/changepw ", "/cp ", "/passwd ", "/authadmin setpw ",
            "/2fa ", "/email ", "/recover "};

    private final JavaPlugin plugin;

    private volatile String mode = "warn";     // fix | warn | off
    private volatile boolean log4jEnabled = true;
    private volatile boolean filterInstalled;
    private volatile boolean warned;
    // Установленный фильтр и логгер — чтобы снять их при выключении плагина
    // (иначе после /reload копятся фильтры со ссылкой на старый ClassLoader)
    private volatile Object installedFilter;
    private volatile Object installedOn;

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

            // Методы ищем ОДИН раз: фильтр вызывается на каждую строку лога.
            // У LogEvent нет getFormattedMessage() — текст берётся из Message
            // (раньше здесь был NoSuchMethodException, и фильтр не вставал нигде).
            final Class<?> messageClass = Class.forName("org.apache.logging.log4j.message.Message");
            final Method getMessage = logEventClass.getMethod("getMessage");
            final Method getFormatted = messageClass.getMethod("getFormattedMessage");
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if ("filter".equals(name) && args != null && args.length > 0 && args[0] != null) {
                    String message = null;
                    try {
                        if (logEventClass.isInstance(args[0])) {
                            // filter(LogEvent) — фильтр уровня LoggerConfig
                            Object m = getMessage.invoke(args[0]);
                            message = m == null ? null : (String) getFormatted.invoke(m);
                        } else if (args.length > 3 && args[3] != null) {
                            // filter(Logger, Level, Marker, msg, ...) — текст и параметры
                            Object m = args[3];
                            StringBuilder sb = new StringBuilder(messageClass.isInstance(m)
                                    ? String.valueOf(getFormatted.invoke(m)) : String.valueOf(m));
                            for (int i = 4; i < args.length; i++) {
                                Object a = args[i];
                                if (a instanceof Object[]) {
                                    for (Object o : (Object[]) a) {
                                        sb.append(' ').append(o);
                                    }
                                } else if (a != null && !(a instanceof Throwable)) {
                                    sb.append(' ').append(a);
                                }
                            }
                            message = sb.toString();
                        }
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
            installedFilter = filter;
            installedOn = log4jLogger;
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
        for (String prefix : RISKY) {
            if (lower.contains(prefix)) {
                return true;
            }
        }
        return isSecretCommand(lower);
    }

    /**
     * Namespaced-варианты из таб-комплита: «/vtregister:login пароль»,
     * «/registerplugin:reg …». Берём команду после маркера лога, срезаем
     * '/', namespace до последнего ':' и сверяем базу; пароль — только
     * если после команды есть аргумент.
     */
    private static boolean isSecretCommand(String lower) {
        int mark = lower.indexOf("command:"); // первое вхождение: пароль сам может содержать «command:»
        String cmd = mark >= 0 ? lower.substring(mark + "command:".length()) : lower;
        int slash = cmd.indexOf('/');
        if (slash < 0) {
            return false;
        }
        cmd = cmd.substring(slash + 1).trim();
        int sp = cmd.indexOf(' ');
        if (sp < 0) {
            return false; // без аргумента пароля нет
        }
        String base = cmd.substring(0, sp);
        int colon = base.lastIndexOf(':');
        if (colon >= 0) {
            base = base.substring(colon + 1);
        }
        String rest = cmd.substring(sp + 1).trim();
        if (rest.isEmpty()) {
            return false;
        }
        if (SECRET_COMMANDS.contains(base)) {
            return true;
        }
        return "authadmin".equals(base) && rest.startsWith("setpw");
    }

    /** Снять log4j-фильтр (onDisable). Без log4j2 — тихо выходим. */
    public void uninstall() {
        Object filter = installedFilter;
        Object logger = installedOn;
        installedFilter = null;
        installedOn = null;
        filterInstalled = false;
        if (filter == null || logger == null) {
            return;
        }
        try {
            Class<?> filterClass = Class.forName("org.apache.logging.log4j.core.Filter");
            Class<?> coreLoggerClass = Class.forName("org.apache.logging.log4j.core.Logger");
            try {
                coreLoggerClass.getMethod("removeFilter", filterClass).invoke(logger, filter);
            } catch (NoSuchMethodException old) {
                // Старый log4j (ядра 1.13–1.16): у Logger нет removeFilter —
                // фильтр висит на его LoggerConfig (так его ставит addFilter)
                Object cfg = coreLoggerClass.getMethod("get").invoke(logger);
                cfg.getClass().getMethod("removeFilter", filterClass).invoke(cfg, filter);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("CommandLogGuard: не удалось снять log4j-фильтр: " + t);
        }
    }

    public boolean isFilterInstalled() {
        return filterInstalled;
    }

    public String getMode() {
        return mode;
    }

    private static final int READY = -1251988155;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1012) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1012) == READY;
    }
}
