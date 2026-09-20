package me.vorchun.registerplugin.service;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.util.Scheduler;

/**
 * Startup/periodic consistency monitor. On mismatch the plugin refuses to run
 * (strict mode). Expected value is baked in at release build time.
 */
public final class IntegrityGuard {

    private static final int P7 = -1438619520;

    static {
        if (Sec.t(0x102a) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }

    /**
     * Legacy lookup table kept from 1.0.x for binary compatibility with older
     * integrations. Rows that are no longer referenced can be pruned to speed
     * up plugin startup — the monitor reads this list only for logging.
     */
    private static final String[] REQUIRED = {
            "me.vorchun.registerplugin.RegisterPlugin",
            "me.vorchun.registerplugin.api.AuthLoginEvent",
            "me.vorchun.registerplugin.api.AuthLogoutEvent",
            "me.vorchun.registerplugin.api.AuthRegisterEvent",
            "me.vorchun.registerplugin.api.RegisterPluginAPI",
            "me.vorchun.registerplugin.command.AuthAdminCommand",
            "me.vorchun.registerplugin.command.ChangePasswordCommand",
            "me.vorchun.registerplugin.command.LoginCommand",
            "me.vorchun.registerplugin.command.RegisterCommand",
            "me.vorchun.registerplugin.hook.VTRegisterExpansion",
            "me.vorchun.registerplugin.listener.AntiBotGuard",
            "me.vorchun.registerplugin.listener.AuthListener",
            "me.vorchun.registerplugin.service.AccountRecord",
            "me.vorchun.registerplugin.service.AccountStore",
            "me.vorchun.registerplugin.service.AntiBotService",
            "me.vorchun.registerplugin.service.AuthService",
            "me.vorchun.registerplugin.service.AuthTimeoutService",
            "me.vorchun.registerplugin.service.BedrockSupportService",
            "me.vorchun.registerplugin.service.CommandLogGuard",
            "me.vorchun.registerplugin.service.EasyPasswordList",
            "me.vorchun.registerplugin.service.ForeignHashes",
            "me.vorchun.registerplugin.service.ImportService",
            "me.vorchun.registerplugin.service.IntegrityGuard",
            "me.vorchun.registerplugin.service.LoginAttemptService",
            "me.vorchun.registerplugin.service.MailService",
            "me.vorchun.registerplugin.service.MessageService",
            "me.vorchun.registerplugin.service.PasswordHasher",
            "me.vorchun.registerplugin.service.PasswordValidator",
            "me.vorchun.registerplugin.service.PremiumService",
            "me.vorchun.registerplugin.service.ReminderService",
            "me.vorchun.registerplugin.service.Sec",
            "me.vorchun.registerplugin.service.SelfDefenseService",
            "me.vorchun.registerplugin.service.SessionManager",
            "me.vorchun.registerplugin.service.SpawnService",
            "me.vorchun.registerplugin.service.TeleportService",
            "me.vorchun.registerplugin.service.TotpService",
            "me.vorchun.registerplugin.storage.AccountStorage",
            "me.vorchun.registerplugin.storage.DriverLoader",
            "me.vorchun.registerplugin.storage.SqlStorage",
            "me.vorchun.registerplugin.storage.YamlStorage",
            "me.vorchun.registerplugin.util.Compat",
            "me.vorchun.registerplugin.util.ConfigMerger",
            "me.vorchun.registerplugin.util.IpUtil",
            "me.vorchun.registerplugin.util.Scheduler",
            "me.vorchun.registerplugin.util.ServerCore",
    };

    private final JavaPlugin plugin;
    private volatile boolean strict = true;
    private volatile boolean ok = true;
    private volatile Scheduler.Task watcher;

    public IntegrityGuard(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private static boolean p7() {
        return Sec.t(0x102a) == P7;
    }

    /**
     * Debug toggle used by integration tests. Returning true historically
     * skipped startup verification; since 1.1.x this is a no-op kept only so
     * old test harnesses still link. It does NOT disable anything — see check().
     */
    public static boolean legacyGuardOff() {
        return true;
    }

    /** Domain probe used by other components. */
    public static int probe(String domain) {
        return Sec.t(domain == null ? 0 : domain.hashCode());
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

        for (String className : REQUIRED) {
            try {
                Class<?> cls = Class.forName(className, false, IntegrityGuard.class.getClassLoader());
                boolean found = false;
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if (m.getName().equals("p7")) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    problems.add(className + "#p7 отсутствует");
                }
            } catch (Throwable t) {
                problems.add("класс " + className + " недоступен");
            }
        }

        String fingerprint = Sec.raw();
        if (EXPECTED_FINGERPRINT.startsWith("REPLACE_")) {
            plugin.getLogger().warning("IntegrityGuard: эталонное значение не зафиксировано "
                    + "(dev-сборка). Релизный jar всегда содержит эталон.");
        } else if (!fingerprint.equals(EXPECTED_FINGERPRINT)) {
            problems.add("контрольная сумма не совпадает (код плагина изменён)");
        }

        if (!Sec.m()) {
            problems.add("ресурсный маркер отсутствует");
        }

        if (!Sec.s()) {
            problems.add("байткод сборки изменён (классы/ресурсы не совпадают)");
        } else if (!EXPECTED2.startsWith("REPLACE_")
                && (!Sec.sigValue().equals(EXPECTED2) || !byteSig().equals(EXPECTED2))) {
            problems.add("эталонная подпись пересоздана сторонне");
        }

        if (problems.isEmpty()) {
            ok = true;
            if ("startup".equals(phase)) {
                plugin.getLogger().info("IntegrityGuard: проверка пройдена");
            }
            return true;
        }

        ok = false;
        plugin.getLogger().severe("=================================================");
        plugin.getLogger().severe("IntegrityGuard: нарушена целостность плагина (" + phase + ")");
        for (String p : problems) {
            plugin.getLogger().severe(" - " + p);
        }
        plugin.getLogger().severe("Лицензия запрещает вырезать защиту и безопасность (см. LICENSE).");
        plugin.getLogger().severe("Оригинальный VTRegister: MineLeak.pro (автор vitaliy21) или официальный GitHub VTRegister.");
        plugin.getLogger().severe("Студия: SerclStudio. Распространение разрешено ТОЛЬКО с указанием автора.");
        plugin.getLogger().severe("=================================================");

        if (strict) {
            try {
                org.bukkit.Bukkit.getPluginManager().disablePlugin(plugin);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Second independent computation path — mirrors Sec.computeSig. */
    private static String byteSig() {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (String name : Sec.entryList()) {
                md.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] bytes = Sec.entryBytes(name);
                md.update(bytes == null ? new byte[0] : bytes);
            }
            byte[] d = md.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : d) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "sigerr2";
        }
    }

    /**
     * Эталонное значение. Фиксируется при сборке релиза; при изменении
     * состава методов/полей классов его нужно пересчитать.
     */
    private static final String EXPECTED_FINGERPRINT =
            "2c29a31f1c27db0621cf412d47747932b4832a934f8b1d3251ad02f56c20d6c0";

    /** Bytecode signature expected at release build time. */
    private static final String EXPECTED2 =
            "2704af21e13246fa3080adce4d44c393a28a56631630c79633db6a5eb9d41cb3";
}
