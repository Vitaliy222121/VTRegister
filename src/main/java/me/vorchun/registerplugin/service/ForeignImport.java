// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Чтение аккаунтов из баз ДРУГИХ плагинов входа: AuthMe (в т.ч. старые форки
 * и файловый auths.db), nLogin и его предшественник OpeNLogin.
 *
 * Настройки источника берутся из его config.yml (тип базы, таблица, имена
 * колонок, алгоритм хеша); чего нет — определяется по колонкам таблицы.
 * Хеш приводится к формату, который умеет проверять {@link ForeignHashes}:
 * игрок входит СТАРЫМ паролем, после первого входа он перехешируется в
 * Argon2id. Хеши, которые проверить нельзя, НЕ импортируются (иначе игрок
 * получил бы аккаунт, в который невозможно войти) — они считаются в отчёте.
 */
final class ForeignImport {

    /** Одна запись для сохранения. */
    static final class Row {
        String name;
        String hash;
        String ip = "";
        long lastLogin;
    }

    interface Sink {
        void accept(Row row);
    }

    private static final String[] NAME_COLS = {"realname", "last_name", "lastname", "username", "name",
            "nickname", "player", "playername", "user", "nick"};
    private static final String[] PASS_COLS = {"password", "hash", "hashed_password", "pass", "passwd", "pwd"};
    private static final String[] SALT_COLS = {"salt"};
    private static final String[] IP_COLS = {"last_ip", "lastip", "ip", "address", "last_address", "ipaddress", "reg_ip"};
    private static final String[] LAST_COLS = {"lastlogin", "last_login", "last_seen", "lastseen", "logindate",
            "login_date", "last_join"};

    private final JavaPlugin plugin;
    private final Sink sink;
    int read;
    int unsupported;
    int failed;
    final Map<String, Integer> unsupportedKinds = new LinkedHashMap<>();

    ForeignImport(JavaPlugin plugin, Sink sink) {
        this.plugin = plugin;
        this.sink = sink;
    }

    // ---------- AuthMe и старые форки ----------

    /** plugins/AuthMe: SQLite/MySQL/MariaDB/PostgreSQL по его config.yml + файловый auths.db. */
    void authMe(File dir, List<String> sources) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        YamlConfiguration cfg = yaml(new File(dir, "config.yml"));
        String algo = str(cfg, "settings.security.passwordHash", "");
        String backend = str(cfg, "DataSource.backend", "").toUpperCase(Locale.ROOT);
        File flat = new File(dir, "auths.db");
        if (flat.isFile() && !isSqliteFile(flat)) {
            // Очень старые версии и форки: текстовый файл name:hash:ip:lastlogin:…
            sources.add("AuthMe (файл auths.db)");
            authMeFlat(flat, algo);
        }
        String table = ident(str(cfg, "DataSource.mySQLTablename", "authme"), "authme");
        Map<String, String> cols = new LinkedHashMap<>();
        cols.put("name", str(cfg, "DataSource.mySQLColumnName", "username"));
        cols.put("real", str(cfg, "DataSource.mySQLRealName", "realname"));
        cols.put("pass", str(cfg, "DataSource.mySQLColumnPassword", "password"));
        cols.put("salt", str(cfg, "DataSource.mySQLColumnSalt", ""));
        cols.put("ip", str(cfg, "DataSource.mySQLColumnIp", "ip"));
        cols.put("last", str(cfg, "DataSource.mySQLColumnLastLogin", "lastlogin"));
        if (backend.startsWith("MYSQL") || backend.startsWith("MARIADB") || backend.startsWith("POSTGRE")) {
            String dialect = backend.startsWith("POSTGRE") ? "postgresql" : backend.startsWith("MARIADB") ? "mariadb" : "mysql";
            try (Connection c = remote(dialect, str(cfg, "DataSource.mySQLHost", "127.0.0.1"),
                    str(cfg, "DataSource.mySQLPort", dialect.equals("postgresql") ? "5432" : "3306"),
                    str(cfg, "DataSource.mySQLDatabase", "authme"),
                    str(cfg, "DataSource.mySQLUsername", "root"), str(cfg, "DataSource.mySQLPassword", ""))) {
                sources.add("AuthMe (" + dialect + ")");
                table(c, table, cols, algo);
            } catch (Throwable t) {
                failed++;
                plugin.getLogger().warning("Импорт AuthMe (" + dialect + "): " + t.getMessage());
            }
            return;
        }
        File db = new File(dir, str(cfg, "DataSource.mySQLDatabase", "authme") + ".db");
        if (!db.isFile()) {
            db = new File(dir, "authme.db");
        }
        if (!db.isFile() || !isSqliteFile(db)) {
            return;
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath())) {
            sources.add("AuthMe (SQLite " + db.getName() + ")");
            table(c, table, cols, algo);
        } catch (Throwable t) {
            failed++;
            plugin.getLogger().warning("Импорт AuthMe (" + db.getName() + "): " + t.getMessage());
        }
    }

    private void authMeFlat(File f, String algo) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(Files.newInputStream(f.toPath()),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split(":", -1);
                if (p.length < 2) {
                    failed++;
                    continue;
                }
                Row r = new Row();
                r.name = p[0];
                r.ip = p.length > 2 ? p[2] : "";
                try {
                    r.lastLogin = p.length > 3 ? Long.parseLong(p[3]) : 0L;
                } catch (NumberFormatException e) {
                    r.lastLogin = 0L;
                }
                emit(r, p[1], "", algo);
            }
        } catch (Throwable t) {
            failed++;
            plugin.getLogger().warning("Импорт AuthMe (auths.db): " + t.getMessage());
        }
    }

    // ---------- nLogin / OpeNLogin ----------

    /** plugins/nLogin (или OpeNLogin): MySQL из его config.yml + все SQLite-файлы в папке. */
    void nLogin(File dir, String label, List<String> sources) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        YamlConfiguration cfg = yaml(new File(dir, "config.yml"));
        String algo = first(cfg, "", "security.encryption", "encryption", "settings.encryption", "hashing.algorithm");
        String type = first(cfg, "", "database.type", "database.driver", "database.storage", "storage.type")
                .toUpperCase(Locale.ROOT);
        if (type.contains("MYSQL") || type.contains("MARIADB") || type.contains("POSTGRE")) {
            String dialect = type.contains("POSTGRE") ? "postgresql" : type.contains("MARIADB") ? "mariadb" : "mysql";
            String host = first(cfg, "127.0.0.1", "database.mysql.host", "database.host", "database.address", "mysql.host");
            String port = first(cfg, dialect.equals("postgresql") ? "5432" : "3306",
                    "database.mysql.port", "database.port", "mysql.port");
            if (host.contains(":") && !host.startsWith("[")) {
                port = host.substring(host.indexOf(':') + 1);
                host = host.substring(0, host.indexOf(':'));
            }
            String db = first(cfg, "nlogin", "database.mysql.database", "database.database", "database.name", "mysql.database");
            String user = first(cfg, "root", "database.mysql.username", "database.mysql.user", "database.username",
                    "database.user", "mysql.username");
            String pass = first(cfg, "", "database.mysql.password", "database.password", "mysql.password");
            String table = ident(first(cfg, "", "database.table-name", "database.table", "database.mysql.table"), "");
            try (Connection c = remote(dialect, host, port, db, user, pass)) {
                sources.add(label + " (" + dialect + ")");
                if (!table.isEmpty()) {
                    table(c, table, null, algo);
                } else {
                    for (String t : tables(c)) {
                        if (looksLikeAccounts(c, t)) {
                            table(c, t, null, algo);
                        }
                    }
                }
            } catch (Throwable t) {
                failed++;
                plugin.getLogger().warning("Импорт " + label + " (" + dialect + "): " + t.getMessage());
            }
        }
        List<File> dbs = new ArrayList<>();
        collectSqlite(dir, dbs, 2);
        for (File f : dbs) {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath())) {
                boolean any = false;
                for (String t : tables(c)) {
                    if (looksLikeAccounts(c, t)) {
                        if (!any) {
                            sources.add(label + " (SQLite " + f.getName() + ")");
                            any = true;
                        }
                        table(c, t, null, algo);
                    }
                }
            } catch (Throwable t) {
                failed++;
                plugin.getLogger().warning("Импорт " + label + " (" + f.getName() + "): " + t.getMessage());
            }
        }
        File[] h2 = dir.listFiles((d, n) -> n.endsWith(".mv.db") || n.endsWith(".h2.db"));
        if (h2 != null && h2.length > 0 && dbs.isEmpty()) {
            plugin.getLogger().warning("Импорт " + label + ": база в формате H2 (" + h2[0].getName()
                    + ") — переключи " + label + " на SQLite/MySQL или выгрузи аккаунты в import.yml");
        }
    }

    // ---------- чтение таблицы ----------

    /**
     * @param cols явные имена колонок (AuthMe: из его config.yml) или null —
     *             определить по названиям
     */
    private void table(Connection c, String table, Map<String, String> cols, String algo) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + quote(c, table))) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> have = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                have.add(md.getColumnLabel(i));
            }
            String nameCol = pick(have, cols == null ? null : cols.get("name"), NAME_COLS);
            String realCol = cols == null ? null : find(have, cols.get("real"));
            String passCol = pick(have, cols == null ? null : cols.get("pass"), PASS_COLS);
            String saltCol = pick(have, cols == null ? null : cols.get("salt"), SALT_COLS);
            String ipCol = pick(have, cols == null ? null : cols.get("ip"), IP_COLS);
            String lastCol = pick(have, cols == null ? null : cols.get("last"), LAST_COLS);
            if (nameCol == null || passCol == null) {
                plugin.getLogger().warning("Импорт: в таблице " + table + " нет колонок ника/пароля — пропущена");
                return;
            }
            while (rs.next()) {
                Row r = new Row();
                String name = rs.getString(nameCol);
                if (realCol != null) {
                    // AuthMe: username в нижнем регистре, offline-UUID считается от ника
                    // С УЧЁТОМ регистра — берём realname, если это тот же ник
                    String real = rs.getString(realCol);
                    if (real != null && !real.isEmpty() && (name == null || real.equalsIgnoreCase(name))) {
                        name = real;
                    }
                }
                r.name = name;
                r.ip = ipCol == null ? "" : nz(rs.getString(ipCol));
                r.lastLogin = lastCol == null ? 0L : millis(rs, lastCol);
                emit(r, rs.getString(passCol), saltCol == null ? "" : nz(rs.getString(saltCol)), algo);
            }
        }
    }

    private void emit(Row r, String rawHash, String salt, String algo) {
        read++;
        if (r.name == null || r.name.trim().isEmpty() || r.name.length() > 16 || rawHash == null || rawHash.trim().isEmpty()) {
            failed++;
            return;
        }
        r.name = r.name.trim();
        String h = normalize(rawHash.trim(), salt, algo);
        if (h == null) {
            unsupported++;
            unsupportedKinds.merge(kind(rawHash.trim(), algo), 1, Integer::sum);
            return;
        }
        r.hash = h;
        sink.accept(r);
    }

    // ---------- хеши ----------

    /**
     * Привести хеш к виду, который проверяет {@link ForeignHashes}. Хеши без
     * префикса (hex) помечаются алгоритмом источника: $AM$SHA1$$…, иначе
     * 32 hex MD5 и двойной MD5 не различить. Открытый текст (PLAINTEXT старых
     * баз) сразу хешируется нашим Argon2id. null — проверить нельзя.
     */
    static String normalize(String raw, String salt, String algo) {
        String a = algo == null ? "" : algo.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (a.equals("PLAINTEXT") || a.equals("PLAIN")) {
            return PasswordHasher.hash(raw);
        }
        if (raw.startsWith("$SHA$") || raw.startsWith("$SHA256$") || raw.startsWith("$SHA512$") || raw.startsWith("$MD5$")
                || raw.startsWith("$BCRYPT$") || raw.startsWith("$2a$") || raw.startsWith("$2b$") || raw.startsWith("$2y$")
                || raw.startsWith("$argon2")) {
            return ForeignHashes.isSupported(raw) ? raw : null;
        }
        if (raw.startsWith("pbkdf2_")) {
            // AuthMe PBKDF2 / PBKDF2DJANGO: pbkdf2_sha256$итерации$соль$хеш
            return raw.split("\\$").length == 4 ? "$AM$" + (a.contains("DJANGO") ? "PBKDF2DJANGO" : "PBKDF2") + "$" + raw : null;
        }
        String s = salt == null ? "" : salt;
        String tag;
        switch (a) {
            case "MD5": tag = "MD5"; break;
            case "SHA1": tag = "SHA1"; break;
            case "SHA256": tag = "SHA256"; break;
            case "SHA512": tag = "SHA512"; break;
            case "DOUBLEMD5": tag = "DOUBLEMD5"; break;
            case "SALTED2MD5": tag = "SALTED2MD5"; break;
            case "SALTEDSHA512": tag = "SALTEDSHA512"; break;
            case "":
                // Алгоритм неизвестен — по длине hex (самые частые варианты)
                tag = hexLen(raw) == 32 ? "MD5" : hexLen(raw) == 40 ? "SHA1" : hexLen(raw) == 64 ? "SHA256"
                        : hexLen(raw) == 128 ? "SHA512" : null;
                break;
            default:
                tag = null; // WHIRLPOOL, XAUTH, форумные (IPB, MyBB, phpBB…) — не поддерживаем
        }
        int len = hexLen(raw);
        if (tag != null && len > 0 && !a.isEmpty() && len != hexLenOf(tag)) {
            // «Наследный» хеш старого алгоритма (AuthMe legacyHashes: MD5 при
            // текущем SHA256 и т.п.) — длина выдаёт настоящий алгоритм
            tag = len == 32 ? "MD5" : len == 40 ? "SHA1" : len == 64 ? "SHA256" : len == 128 ? "SHA512" : null;
        }
        if (tag == null || len == 0) {
            return null;
        }
        if ((tag.equals("SALTED2MD5") || tag.equals("SALTEDSHA512")) && s.isEmpty()) {
            return null;
        }
        if (s.indexOf('$') >= 0) {
            return null;
        }
        return "$AM$" + tag + "$" + s + "$" + raw.toLowerCase(Locale.ROOT);
    }

    private static String kind(String raw, String algo) {
        if (algo != null && !algo.trim().isEmpty()) {
            return algo.trim().toUpperCase(Locale.ROOT);
        }
        if (raw.startsWith("$")) {
            int e = raw.indexOf('$', 1);
            return e > 1 ? raw.substring(0, e + 1) : "?";
        }
        return "len=" + raw.length();
    }

    /** Длина hex-хеша у алгоритма. */
    private static int hexLenOf(String tag) {
        switch (tag) {
            case "SHA1": return 40;
            case "SHA256": return 64;
            case "SHA512":
            case "SALTEDSHA512": return 128;
            default: return 32; // MD5, DOUBLEMD5, SALTED2MD5
        }
    }

    private static int hexLen(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return 0;
            }
        }
        return s.length();
    }

    // ---------- JDBC и конфиги ----------

    private Connection remote(String dialect, String host, String port, String db, String user, String pass) throws Exception {
        me.vorchun.registerplugin.storage.DriverLoader.ensure(dialect, plugin.getDataFolder(), plugin.getLogger());
        String url = dialect.equals("postgresql")
                ? "jdbc:postgresql://" + host + ":" + port + "/" + db + "?connectTimeout=10&socketTimeout=60"
                : "jdbc:mysql://" + host + ":" + port + "/" + db
                        + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectTimeout=10000&socketTimeout=60000";
        return DriverManager.getConnection(url, user, pass);
    }

    private static List<String> tables(Connection c) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String t = rs.getString("TABLE_NAME");
                if (t != null && !t.toLowerCase(Locale.ROOT).startsWith("sqlite_")) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** Таблица аккаунтов: есть колонка ника и колонка пароля. */
    private static boolean looksLikeAccounts(Connection c, String table) {
        if (ident(table, "").isEmpty()) {
            return false;
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + quote(c, table) + " WHERE 1=0")) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> have = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                have.add(md.getColumnLabel(i));
            }
            return pick(have, null, NAME_COLS) != null && pick(have, null, PASS_COLS) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String pick(List<String> have, String preferred, String[] candidates) {
        String p = find(have, preferred);
        if (p != null) {
            return p;
        }
        for (String cand : candidates) {
            String f = find(have, cand);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    private static String find(List<String> have, String want) {
        if (want == null || want.isEmpty()) {
            return null;
        }
        for (String h : have) {
            if (h.equalsIgnoreCase(want)) {
                return h;
            }
        }
        return null;
    }

    /** Время последнего входа: число (мс или сек) или дата. */
    private static long millis(ResultSet rs, String col) {
        try {
            Object o = rs.getObject(col);
            if (o instanceof Number) {
                long v = ((Number) o).longValue();
                return v > 0 && v < 100_000_000_000L ? v * 1000L : Math.max(0L, v);
            }
            if (o instanceof java.util.Date) {
                return ((java.util.Date) o).getTime();
            }
            if (o != null) {
                java.sql.Timestamp ts = rs.getTimestamp(col);
                return ts == null ? 0L : ts.getTime();
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    /** Имя таблицы только из букв/цифр/_ — значение приходит из чужого конфига. */
    private static String ident(String s, String def) {
        if (s == null || s.isEmpty() || !s.matches("[A-Za-z0-9_]{1,64}")) {
            return def;
        }
        return s;
    }

    private static String quote(Connection c, String table) throws Exception {
        String q = c.getMetaData().getIdentifierQuoteString();
        q = q == null || q.trim().isEmpty() ? "" : q.trim();
        return q + table + q;
    }

    private static void collectSqlite(File dir, List<File> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory() && depth > 0) {
                collectSqlite(f, out, depth - 1);
            } else if (f.isFile() && (f.getName().endsWith(".db") || f.getName().endsWith(".sqlite"))
                    && !f.getName().endsWith(".mv.db") && isSqliteFile(f)) {
                out.add(f);
            }
        }
    }

    /** Файл SQLite начинается с «SQLite format 3». */
    private static boolean isSqliteFile(File f) {
        try (java.io.InputStream in = Files.newInputStream(f.toPath())) {
            byte[] head = new byte[15];
            return in.read(head) == 15 && new String(head, StandardCharsets.US_ASCII).equals("SQLite format 3");
        } catch (Throwable t) {
            return false;
        }
    }

    private static YamlConfiguration yaml(File f) {
        return f.isFile() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
    }

    private static String str(YamlConfiguration cfg, String key, String def) {
        Object v = cfg.get(key);
        return v == null ? def : String.valueOf(v).trim();
    }

    private static String first(YamlConfiguration cfg, String def, String... keys) {
        for (String k : keys) {
            Object v = cfg.get(k);
            if (v != null && !String.valueOf(v).trim().isEmpty() && !(v instanceof org.bukkit.configuration.ConfigurationSection)) {
                return String.valueOf(v).trim();
            }
        }
        return def;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
