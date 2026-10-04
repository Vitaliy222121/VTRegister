// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.util.Scheduler;

/**
 * Миграция аккаунтов из других плагинов — «входной билет» для серверов,
 * которые уже работают на AuthMe/LoginSecurity.
 *
 * Поддерживается:
 *   - AuthMe и старые форки — SQLite/MySQL/MariaDB/PostgreSQL по его config.yml
 *                   (свои таблица и колонки, алгоритм хеша) и файловый auths.db
 *   - nLogin, OpeNLogin — SQLite-файлы в папке плагина и MySQL из его config.yml
 *   - LoginSecurity (SQLite LoginSecurity.db, LS_players) — BCrypt
 *   - accounts.yml  (наши версии 1.0.x–1.1.x)
 *   - import.yml    (универсальный формат: name + password + необязательные ip/lastlogin)
 *
 * Импортированные хеши НЕ пересчитываются: игрок входит своим старым паролем,
 * а плагин после первого успешного входа сам перехеширует его в PBKDF2.
 */
public final class ImportService {

    public static final class Report {
        public int imported;
        public int skipped;
        public int failed;
        /** Хеши, которые нельзя проверить (WHIRLPOOL, форумные…) — не импортированы. */
        public int unsupported;
        public final java.util.Map<String, Integer> unsupportedKinds = new java.util.LinkedHashMap<>();
        public final List<String> sources = new ArrayList<>();

        public String summary() {
            return "импортировано=" + imported + ", пропущено=" + skipped + ", ошибок=" + failed
                    + (unsupported > 0 ? ", неподдерживаемый хеш=" + unsupported + " " + unsupportedKinds : "")
                    + (sources.isEmpty() ? " (источники не найдены: AuthMe, nLogin, OpeNLogin, LoginSecurity, LimboAuth, import.yml)"
                        : ", источники: " + String.join(", ", sources));
        }
    }

    private final JavaPlugin plugin;
    private final AccountStore store;

    public ImportService(JavaPlugin plugin, AccountStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /** Импортировать всё, что найдено на диске. Колбэк — в главном потоке. */
    public void importAll(boolean overwrite, Consumer<Report> callback) {
        Scheduler.runAsync(plugin, () -> {
            Report report = new Report();
            File plugins = plugin.getDataFolder().getParentFile();

            // AuthMe (и форки), nLogin, OpeNLogin — настройки из их config.yml
            ForeignImport fi = new ForeignImport(plugin, row -> save(row.name, row.hash, row.ip, row.lastLogin, overwrite, report));
            fi.authMe(new File(plugins, "AuthMe"), report.sources);
            fi.nLogin(new File(plugins, "nLogin"), "nLogin", report.sources);
            fi.nLogin(new File(plugins, "OpeNLogin"), "OpeNLogin", report.sources);
            report.failed += fi.failed;
            report.unsupported += fi.unsupported;
            fi.unsupportedKinds.forEach((k, v) -> report.unsupportedKinds.merge(k, v, Integer::sum));
            if (fi.unsupported > 0) {
                plugin.getLogger().warning("Импорт: " + fi.unsupported + " аккаунтов с хешем, который нельзя проверить "
                        + fi.unsupportedKinds + ", не перенесены — эти игроки зарегистрируются заново");
            }
            importLoginSecurity(new File(plugins, "LoginSecurity/LoginSecurity.db"), overwrite, report);
            importLoginSecurity(new File(plugins, "LoginSecurity/users.db"), overwrite, report);
            importLimboAuth(new File(plugins, "LimboAuth/limboauth.db"), overwrite, report);
            importLimboAuth(new File(plugins, "LimboAuth/auth.db"), overwrite, report);
            importLegacyYaml(new File(plugin.getDataFolder(), "accounts.yml"), overwrite, report);
            // data/accounts.yml — рабочая база YAML-бэкенда: импортировать её в саму себя
            // бессмысленно и опасно. На SQL это может быть устаревший файл —
            // только добавляем отсутствующие аккаунты, никогда не перезаписываем
            if (!"YAML".equals(store.backendName())) {
                importLegacyYaml(new File(AccountStore.dataFolder(plugin), "accounts.yml"), false, report);
            }
            importGeneric(new File(plugin.getDataFolder(), "import.yml"), overwrite, report);

            Scheduler.runSync(plugin, () -> callback.accept(report));
        });
    }

    // ---------- LoginSecurity ----------

    private void importLoginSecurity(File db, boolean overwrite, Report report) {
        if (!db.exists()) {
            return;
        }
        if (!report.sources.contains("LoginSecurity")) {
            report.sources.add("LoginSecurity");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath())) {
            // UUID есть — используем его напрямую (точное совпадение с аккаунтом игрока)
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, last_name, password, ip, last_login FROM LS_players")) {
                while (rs.next()) {
                    String uuid = rs.getString("uuid");
                    String name = rs.getString("last_name");
                    String hash = rs.getString("password");
                    if (name == null || hash == null || hash.isEmpty()) {
                        report.failed++;
                        continue;
                    }
                    UUID parsed = null;
                    if (uuid != null && !uuid.isEmpty()) {
                        try {
                            parsed = UUID.fromString(uuid);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    saveWithUuid(parsed, name, hash, safe(rs.getString("ip")), rs.getLong("last_login"), overwrite, report);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Импорт LoginSecurity: " + t.getMessage());
        }
    }

    // ---------- LimboAuth ----------
    // LimboAuth по умолчанию хранит базу в H2 — её читать JDBC-SQLite нельзя;
    // но при database-type: SQLITE таблица AUTH читается напрямую.
    // Схема: NICKNAME, HASH (BCrypt $2a$), IP, LOGINDATE (+ TOTPTOKEN, UUID...).
    private void importLimboAuth(File db, boolean overwrite, Report report) {
        if (!db.exists()) {
            return;
        }
        report.sources.add("LimboAuth");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT NICKNAME, HASH, IP, LOGINDATE FROM AUTH")) {
            while (rs.next()) {
                String name = rs.getString("NICKNAME");
                String hash = rs.getString("HASH");
                if (name == null || hash == null || hash.isEmpty()) {
                    report.failed++;
                    continue;
                }
                save(name, hash, safe(rs.getString("IP")), rs.getLong("LOGINDATE"), overwrite, report);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Импорт LimboAuth (" + db.getName() + "): " + t.getMessage()
                    + " — если база в формате H2, конвертируй её в SQLite или используй import.yml");
        }
    }

    // ---------- наш старый accounts.yml ----------

    private void importLegacyYaml(File file, boolean overwrite, Report report) {
        if (!file.exists()) {
            return;
        }
        if (!report.sources.contains("accounts.yml")) {
            report.sources.add("accounts.yml");
        }
        try {
            YamlConfiguration cfg = me.vorchun.registerplugin.util.ConfigMerger
                    .loadYamlTolerant(file, plugin);
            if (cfg == null) {
                return;
            }
            ConfigurationSection root = cfg.getConfigurationSection("accounts");
            if (root == null) {
                return;
            }
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String hash = s.getString("hash");
                if (hash == null || hash.isEmpty()) {
                    continue;
                }
                // Переносим запись целиком (2FA, почта, IP-сессии), как YamlStorage.open —
                // иначе импорт молча выключил бы 2FA и отвязал почту
                java.util.Map<String, Long> ipAuth = new java.util.HashMap<>();
                ConfigurationSection ips = s.getConfigurationSection("ipAuth");
                if (ips != null) {
                    for (String ipKey : ips.getKeys(false)) {
                        String ip = decodeIp(ipKey);
                        long ts = ips.getLong(ipKey, 0L);
                        if (ip != null && !ip.isEmpty() && ts > 0) {
                            ipAuth.put(ip, ts);
                        }
                    }
                }
                long lastAuth = s.getLong("lastAuth", 0L);
                long registeredAt = s.getLong("registeredAt", 0L);
                AccountRecord full = new AccountRecord(uuid, s.getString("name", ""), hash,
                        registeredAt > 0 ? registeredAt : (lastAuth > 0 ? lastAuth : System.currentTimeMillis()),
                        s.getString("registeredIp", ""), s.getString("lastIp", ""), lastAuth, ipAuth,
                        s.getString("totp", null), s.getString("email", null), s.getBoolean("emailVerified", false));
                saveRecord(full, overwrite, report);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Импорт accounts.yml: " + t.getMessage());
        }
    }

    // ---------- универсальный import.yml ----------

    private void importGeneric(File file, boolean overwrite, Report report) {
        if (!file.exists()) {
            return;
        }
        report.sources.add("import.yml");
        try {
            YamlConfiguration cfg = me.vorchun.registerplugin.util.ConfigMerger
                    .loadYamlTolerant(file, plugin);
            if (cfg == null) {
                return;
            }
            ConfigurationSection root = cfg.getConfigurationSection("players");
            if (root == null) {
                plugin.getLogger().warning("import.yml: нужна секция players (ник -> password/ip/uuid)");
                return;
            }
            for (String name : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(name);
                String hash = s != null ? s.getString("password") : root.getString(name);
                if (hash == null || hash.isEmpty()) {
                    report.failed++;
                    continue;
                }
                String ip = s != null ? safe(s.getString("ip")) : "";
                long last = s != null ? s.getLong("lastlogin", 0L) : 0L;
                UUID uuid = null;
                if (s != null && s.getString("uuid") != null) {
                    try {
                        uuid = UUID.fromString(s.getString("uuid"));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                saveWithUuid(uuid, name, hash, ip, last, overwrite, report);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Импорт import.yml: " + t.getMessage());
        }
    }

    // ---------- общее ----------

    private void save(String name, String hash, String ip, long lastLogin, boolean overwrite, Report report) {
        saveWithUuid(null, name, hash, ip, lastLogin, overwrite, report);
    }

    /**
     * Сохранить запись. UUID берём из источника, иначе считаем offline-UUID по нику
     * (лицензионные игроки потом найдут аккаунт по нику — AccountStore ищет по name).
     */
    private void saveWithUuid(UUID uuid, String name, String hash, String ip, long lastLogin,
                              boolean overwrite, Report report) {
        UUID id = uuid != null ? uuid : offlineUuid(name);
        AccountRecord r = new AccountRecord(id, name, hash,
                lastLogin > 0 ? lastLogin : System.currentTimeMillis(),
                ip == null ? "" : ip,
                ip == null ? "" : ip,
                lastLogin,
                java.util.Collections.emptyMap(),
                null, null, false);
        saveRecord(r, overwrite, report);
    }

    /**
     * Новая запись — добавляем целиком. Существующая при --overwrite — меняем
     * ТОЛЬКО хеш пароля: 2FA, почта и IP-сессии аккаунта остаются.
     * Ошибка чтения базы = ошибка импорта (не «аккаунта нет»).
     */
    private void saveRecord(AccountRecord r, boolean overwrite, Report report) {
        try {
            AccountRecord existing = store.findBlockingStrict(r.getUuid());
            if (existing != null) {
                if (!overwrite) {
                    report.skipped++;
                    return;
                }
                existing.setPasswordHash(r.getPasswordHash());
                store.markDirty(existing);
                report.imported++;
                return;
            }
            store.put(r);
            report.imported++;
        } catch (Throwable t) {
            report.failed++;
        }
    }

    // IP как ключ YAML закодирован base64url (см. YamlStorage)
    private static String decodeIp(String key) {
        try {
            return new String(java.util.Base64.getUrlDecoder().decode(key), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return key; // очень старый формат — ключ был «сырым» IP
        }
    }

    @SuppressWarnings("deprecation")
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static final int READY = -1251988158;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1015) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1015) == READY;
    }
}
