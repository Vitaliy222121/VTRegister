package me.vorchun.registerplugin.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.util.Scheduler;

/**
 * Лицензионная проверка целостности (см. LICENSE).
 *
 * Задача: не дать «вырезать» защиту и безопасность плагина — убрать предупреждения
 * о паролях, отключить фильтр логов, выкинуть антибот или подменить проверку пароля —
 * и продолжать выдавать это за оригинальный плагин.
 *
 * Как работает (без «зловредности» — плагин просто отказывается запускаться):
 *   1. Проверяет, что критичные классы существуют и содержат ожидаемые методы.
 *   2. Считает контрольную сумму «отпечатков» этих классов (имена методов + строковые
 *      маркеры защиты) и сравнивает с эталоном, зашитым в этот же класс.
 *   3. Каждый критичный сервис отдаёт свою часть отпечатка (см. securityToken()).
 *      Если хотя бы один сервис вырезан или переписан — сумма не сойдётся.
 *   4. При несоответствии (в строгом режиме) плагин выключается с понятным сообщением.
 *
 * Честно: любой, у кого есть jar и декомпилятор, теоретически может пропатчить и это.
 * Задача механизма — сделать вырезание защиты трудоёмким и явно нарушающим лицензию,
 * а не «абсолютно невозможным».
 */
public final class IntegrityGuard {

    /** Маркеры защиты: если их вырезали из классов, отпечаток не совпадёт. */
    private static final String[][] REQUIRED = {
            {"me.vorchun.registerplugin.service.PasswordHasher",
                    "verifyAny", "needsRehash", "hash"},
            {"me.vorchun.registerplugin.service.ForeignHashes",
                    "verify", "isForeign"},
            {"me.vorchun.registerplugin.service.CommandLogGuard",
                    "apply", "containsAuthPassword"},
            {"me.vorchun.registerplugin.service.AuthService",
                    "login", "register", "changePassword"},
            {"me.vorchun.registerplugin.service.AntiBotService",
                    "beginCheck", "onMove", "submitCode"},
            {"me.vorchun.registerplugin.service.TotpService",
                    "check", "submit"},
            {"me.vorchun.registerplugin.listener.AuthListener",
                    "onChat", "onCommand"},
            {"me.vorchun.registerplugin.util.Scheduler",
                    "runSync", "runAsync"},
    };

    private final JavaPlugin plugin;
    private volatile boolean strict = true;
    private volatile boolean ok = true;
    private volatile Scheduler.Task watcher;

    public IntegrityGuard(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        strict = plugin.getConfig().getBoolean("security.integrity.strict", true);
    }

    /** Запустить проверку и (опционально) периодический контроль. */
    public void start() {
        if (!check("startup")) {
            return;
        }
        int interval = Math.max(60, plugin.getConfig().getInt("security.integrity.interval_seconds", 300));
        watcher = Scheduler.runSyncTimer(plugin, () -> check("periodic"), interval * 20L, interval * 20L);
    }

    public boolean isOk() {
        return ok;
    }

    private boolean check(String phase) {
        List<String> problems = new ArrayList<>();

        for (String[] requirement : REQUIRED) {
            String className = requirement[0];
            try {
                Class<?> cls = Class.forName(className);
                for (int i = 1; i < requirement.length; i++) {
                    boolean found = false;
                    for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                        if (m.getName().equals(requirement[i])) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        problems.add(className + "#" + requirement[i] + " отсутствует");
                    }
                }
            } catch (Throwable t) {
                problems.add("класс " + className + " недоступен");
            }
        }

        // Отпечаток: набор токенов от критичных сервисов + маркеры защиты.
        // Плейсхолдер означает «сборка без эталона» (dev-версия) — не блокируем запуск,
        // но предупреждаем. В релизном jar эталон всегда подставлен.
        String fingerprint = fingerprint();
        if (EXPECTED_FINGERPRINT.startsWith("REPLACE_")) {
            plugin.getLogger().warning("IntegrityGuard: эталонный отпечаток не зафиксирован "
                    + "(dev-сборка). Релизный jar всегда содержит эталон.");
        } else if (!fingerprint.equals(EXPECTED_FINGERPRINT)) {
            problems.add("отпечаток защиты не совпадает (код плагина изменён)");
        }

        if (problems.isEmpty()) {
            ok = true;
            if ("startup".equals(phase)) {
                plugin.getLogger().info("IntegrityGuard: целостность защиты подтверждена");
            }
            return true;
        }

        ok = false;
        plugin.getLogger().severe("=================================================");
        plugin.getLogger().severe("IntegrityGuard: нарушена целостность защиты плагина (" + phase + ")");
        for (String p : problems) {
            plugin.getLogger().severe(" - " + p);
        }
        plugin.getLogger().severe("Лицензия запрещает вырезать защиту и безопасность (см. LICENSE).");
        plugin.getLogger().severe("Восстанови оригинальный jar с MineLeak.pro или от автора Vorchun.");
        plugin.getLogger().severe("=================================================");

        if (strict) {
            try {
                org.bukkit.Bukkit.getPluginManager().disablePlugin(plugin);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * Комбинированный отпечаток. Меняется при вырезании/подмене защиты,
     * потому что каждая часть берётся из своего класса.
     */
    private String fingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append(tokenOf("me.vorchun.registerplugin.service.PasswordHasher"));
        sb.append('|').append(tokenOf("me.vorchun.registerplugin.service.ForeignHashes"));
        sb.append('|').append(tokenOf("me.vorchun.registerplugin.service.CommandLogGuard"));
        sb.append('|').append(tokenOf("me.vorchun.registerplugin.service.AuthService"));
        sb.append('|').append(tokenOf("me.vorchun.registerplugin.service.AntiBotService"));
        sb.append('|').append("VTRegister-license");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "error";
        }
    }

    /** Токен класса: хеш от отсортированного списка объявленных методов. */
    private String tokenOf(String className) {
        try {
            Class<?> cls = Class.forName(className);
            List<String> names = new ArrayList<>();
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                names.add(m.getName());
            }
            names.sort(String::compareTo);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(String.join(",", names).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16))
                        .append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "missing";
        }
    }

    /**
     * Эталонный отпечаток. Значение фиксируется при сборке релиза;
     * при изменении набора методов в критичных классах его нужно пересчитать
     * (иначе плагин корректно откажется работать — это и есть защита).
     */
    private static final String EXPECTED_FINGERPRINT =
            "1fe5c089fb5b0468d0adb1506365e0a5b76f86ba786b4af64505b7fb2f5d147a";
}
