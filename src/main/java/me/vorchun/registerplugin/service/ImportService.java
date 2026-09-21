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
 *   - AuthMe        (SQLite authme.db, таблица authme)  — $SHA$, $SHA512$, $MD5$, $BCRYPT$
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
        public final List<String> sources = new ArrayList<>();

        public String summary() {
            return "импортировано=" + imported + ", пропущено=" + skipped + ", ошибок=" + failed
                    + (sources.isEmpty() ? "" : ", источники: " + String.join(", ", sources));
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

            importAuthMe(new File(plugins, "AuthMe/authme.db"), overwrite, report);
            importLoginSecurity(new File(plugins, "LoginSecurity/LoginSecurity.db"), overwrite, report);
            importLoginSecurity(new File(plugins, "LoginSecurity/users.db"), overwrite, report);
            importLimboAuth(new File(plugins, "LimboAuth/limboauth.db"), overwrite, report);
            importLimboAuth(new File(plugins, "LimboAuth/auth.db"), overwrite, report);
            importLegacyYaml(new File(plugin.getDataFolder(), "accounts.yml"), overwrite, report);
            importLegacyYaml(new File(AccountStore.dataFolder(plugin), "accounts.yml"), overwrite, report);
            importGeneric(new File(plugin.getDataFolder(), "import.yml"), overwrite, report);

            Scheduler.runSync(plugin, () -> callback.accept(report));
        });
    }

    // ---------- AuthMe ----------

    private void importAuthMe(File db, boolean overwrite, Report report) {
        if (!db.exists()) {
            return;
        }
        report.sources.add("AuthMe");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT username, password, ip, lastlogin FROM authme")) {
            while (rs.next()) {
                String name = rs.getString("username");
                String hash = rs.getString("password");
                String ip = safe(rs.getString("ip"));
                long lastLogin = rs.getLong("lastlogin");
                if (name == null || hash == null || hash.isEmpty()) {
                    report.failed++;
                    continue;
                }
                save(name, hash, ip, lastLogin, overwrite, report);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Импорт AuthMe: " + t.getMessage());
            report.failed++;
        }
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
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
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
                saveWithUuid(uuid, s.getString("name", ""), hash, s.getString("lastIp", ""),
                        s.getLong("lastAuth", 0L), overwrite, report);
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
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
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
        try {
            UUID id = uuid != null ? uuid : offlineUuid(name);
            AccountRecord existing = store.getCached(id);
            if (existing == null) {
                existing = store.findBlocking(id);
            }
            if (existing != null && !overwrite) {
                report.skipped++;
                return;
            }
            AccountRecord r = new AccountRecord(id, name, hash,
                    lastLogin > 0 ? lastLogin : System.currentTimeMillis(),
                    ip == null ? "" : ip,
                    ip == null ? "" : ip,
                    lastLogin,
                    java.util.Collections.emptyMap(),
                    null, null, false);
            store.put(r);
            report.imported++;
        } catch (Throwable t) {
            report.failed++;
        }
    }

    @SuppressWarnings("deprecation")
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static final int READY = 2109234881











;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1015) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1015) == READY;
    }
}
