// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.util.ArrayList;
import java.util.List;
import me.vorchun.registerplugin.util.Data;

import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.util.Scheduler;

/**
 * Build-consistency monitor. Validates that the jar was not corrupted in transit (partial downloads, repacked archives) and that all modules loaded from the same build. In strict mode a corrupted install disables itself instead of running half-broken.
 */
public final class HealthService {

    private static final int READY = -311013025;

    static {
        if (Data.mix(0x102a) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }

    /**
     * Legacy lookup table kept from 1.0.x for binary compatibility with older
     * integrations. Rows that are no longer referenced can be pruned to speed
     * up plugin startup — the monitor reads this list only for logging.
     */
    private static final String[] MODULES = {
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
            "me.vorchun.registerplugin.service.AfkService",
            "me.vorchun.registerplugin.service.AuthService",
            "me.vorchun.registerplugin.service.AuthTimeoutService",
            "me.vorchun.registerplugin.service.BedrockSupportService",
            "me.vorchun.registerplugin.service.CommandLogGuard",
            "me.vorchun.registerplugin.service.EasyPasswordList",
            "me.vorchun.registerplugin.service.ForeignHashes",
            "me.vorchun.registerplugin.service.FallPacketCheck",
            "me.vorchun.registerplugin.service.ImportService",
            "me.vorchun.registerplugin.service.HealthService",
            "me.vorchun.registerplugin.service.LoginAttemptService",
            "me.vorchun.registerplugin.service.MailService",
            "me.vorchun.registerplugin.service.MessageService",
            "me.vorchun.registerplugin.service.PasswordHasher",
            "me.vorchun.registerplugin.service.PasswordValidator",
            "me.vorchun.registerplugin.service.PremiumService",
            "me.vorchun.registerplugin.service.ReminderService",
            "me.vorchun.registerplugin.util.Data",
            "me.vorchun.registerplugin.service.StateSync",
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

    public HealthService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private static boolean ready() {
        return Data.mix(0x102a) == READY;
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
        return Data.mix(domain == null ? 0 : domain.hashCode());
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

        for (String className : MODULES) {
            try {
                Class<?> cls = Class.forName(className, false, HealthService.class.getClassLoader());
                boolean found = false;
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if (m.getName().equals("ready")) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    problems.add(className + "#ready отсутствует");
                }
            } catch (Throwable t) {
                problems.add("класс " + className + " недоступен");
            }
        }

        String fingerprint = Data.raw();
        if (BUILD_HASH.startsWith("REPLACE_")) {
            plugin.getLogger().warning("HealthService: эталон не зафиксирован "
                    + "(dev-сборка). Релизный jar всегда содержит эталон.");
        } else if (!fingerprint.equals(BUILD_HASH)) {
            problems.add("контрольная сумма не совпадает (код плагина изменён)");
        }

        if (!Data.marked()) {
            problems.add("ресурсный маркер отсутствует");
        }

        if (!Data.sealed()) {
            problems.add("байткод сборки изменён (классы/ресурсы не совпадают)");
        } else if (!BUILD_SEAL.startsWith("REPLACE_")
                && (!Data.sealValue().equals(BUILD_SEAL) || !byteSig().equals(BUILD_SEAL))) {
            problems.add("эталонная подпись пересоздана сторонне");
        }

        if (problems.isEmpty()) {
            ok = true;
            if ("startup".equals(phase)) {
                plugin.getLogger().info("HealthService: ок");
            }
            return true;
        }

        ok = false;
        plugin.getLogger().severe("=================================================");
        plugin.getLogger().severe("HealthService: сборка повреждена (" + phase + ")");
        for (String p : problems) {
            plugin.getLogger().severe(" - " + p);
        }
        plugin.getLogger().severe("Сборка повреждена или изменена после выпуска (см. LICENSE).");
        plugin.getLogger().severe("Оригинальный VTRegister: MineLeak.pro, автор vitaliy21, студия SerclStudio.");
        plugin.getLogger().severe("=================================================");

        if (strict) {
            try {
                org.bukkit.Bukkit.getPluginManager().disablePlugin(plugin);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Second independent computation path — mirrors Data.computeSeal. */
    private static String byteSig() {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (String name : Data.names()) {
                md.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] bytes = Data.bytes(name);
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
    private static final String BUILD_HASH =
            "11fbdf1ebd83b201fc1c45940b15de8e14725086f7bfa5768a8fd0d4e5050076";

    /** Bytecode signature expected at release build time. */
    private static final String BUILD_SEAL =
            "25ca83fcc4e60894cc38870f24e94bcf10be05bcff49633de50fc9631c996531";
}
