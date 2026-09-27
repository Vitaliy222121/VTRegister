// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import me.vorchun.registerplugin.service.AccountRecord;

/**
 * SQL-хранилище на чистом JDBC (без HikariCP — не тянем зависимости).
 *
 *  - SQLite:     драйвер встроен в Spigot/Paper; одно соединение + блокировка.
 *  - MySQL:      драйвер встроен в Spigot/Paper; небольшой пул соединений.
 *  - MariaDB:    работает через MySQL-драйвер (совместим по протоколу).
 *  - PostgreSQL: драйвер докачивается автоматически в plugins/RegisterPlugin/lib/.
 *
 * Все запросы — простые PreparedStatement, схема создаётся при первом старте.
 */
public final class SqlStorage implements AccountStorage {

    public enum Dialect {
        SQLITE, MYSQL, MARIADB, POSTGRESQL;

        public static Dialect parse(String s) {
            if (s == null) {
                return SQLITE;
            }
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "mysql": return MYSQL;
                case "mariadb": return MARIADB;
                case "postgres":
                case "postgresql":
                case "pgsql": return POSTGRESQL;
                default: return SQLITE;
            }
        }
    }

    /** Параметры подключения из config.yml → storage.* */
    public static final class Settings {
        public Dialect dialect = Dialect.SQLITE;
        public String host = "localhost";
        public int port = 3306;
        public String database = "minecraft";
        public String user = "root";
        public String password = "";
        public String tablePrefix = "rp_";
        public int poolSize = 4;
        public boolean useSsl = false;
        public String sqliteFile = "accounts.db";
    }

    private final Settings settings;
    private final File dataFolder;
    private final Logger log;
    private final String table;

    /**
     * Пул: слоты считает семафор, а простаивающие соединения лежат в очереди.
     * Слот возвращается ВСЕГДА (и при ошибке пересоздания соединения), поэтому
     * краткое падение БД не «съедает» пул навсегда — после подъёма базы
     * следующий acquire просто откроет новое соединение.
     */
    private volatile Semaphore permits;
    private final ConcurrentLinkedQueue<Connection> idle = new ConcurrentLinkedQueue<>();
    private volatile int poolSize = 1;
    private final Object sqliteLock = new Object();
    private volatile boolean open;

    /** Таймауты сети (сек): зависшая БД не должна вешать IO-поток и главный поток. */
    private static final int CONNECT_TIMEOUT_SEC = 5;
    private static final int SOCKET_TIMEOUT_SEC = 15;

    public SqlStorage(Settings settings, File dataFolder, Logger log) {
        this.settings = settings;
        this.dataFolder = dataFolder;
        this.log = log;
        String prefix = settings.tablePrefix == null ? "" : settings.tablePrefix.replaceAll("[^A-Za-z0-9_]", "");
        this.table = prefix + "accounts";
    }

    @Override
    public String name() {
        switch (settings.dialect) {
            case MYSQL: return "MySQL";
            case MARIADB: return "MariaDB";
            case POSTGRESQL: return "PostgreSQL";
            default: return "SQLite";
        }
    }

    // ---------- подключение ----------

    @Override
    public void open() throws Exception {
        // Повторный open() (ретрай после недоступной БД) — начинаем с чистого пула
        close();
        DriverLoader.ensureDriver(settings.dialect, dataFolder, log);
        if (settings.dialect != Dialect.SQLITE) {
            DriverManager.setLoginTimeout(CONNECT_TIMEOUT_SEC);
        }
        int size = settings.dialect == Dialect.SQLITE ? 1 : Math.max(1, Math.min(16, settings.poolSize));
        poolSize = size;
        // Одно соединение сразу: проверяем, что база доступна; остальные — по требованию
        Connection first = newConnection();
        idle.offer(first);
        permits = new Semaphore(size, true);
        open = true;
        try {
            createSchema();
        } catch (SQLException e) {
            close();
            throw e;
        }
        log.info(name() + ": подключение установлено, таблица " + table);
    }

    @Override
    public void close() {
        open = false;
        Connection c;
        while ((c = idle.poll()) != null) {
            closeQuietly(c);
        }
    }

    private static void closeQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (SQLException ignored) {
        }
    }

    private Connection newConnection() throws SQLException {
        switch (settings.dialect) {
            case SQLITE: {
                File f = new File(dataFolder, settings.sqliteFile);
                Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
                try (Statement st = c.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA synchronous=NORMAL");
                }
                return c;
            }
            case POSTGRESQL: {
                // Таймауты pgJDBC — в секундах; без них зависшая сеть держит поток часами
                String url = "jdbc:postgresql://" + settings.host + ":" + settings.port + "/" + settings.database
                        + "?connectTimeout=" + CONNECT_TIMEOUT_SEC + "&socketTimeout=" + SOCKET_TIMEOUT_SEC
                        + "&tcpKeepAlive=true"
                        + (settings.useSsl ? "&ssl=true&sslmode=require" : "");
                return DriverManager.getConnection(url, settings.user, settings.password);
            }
            default: {
                // Таймауты Connector/J — в миллисекундах
                String url = "jdbc:mysql://" + settings.host + ":" + settings.port + "/" + settings.database
                        + "?useSSL=" + settings.useSsl + "&allowPublicKeyRetrieval=true&characterEncoding=utf8"
                        + "&autoReconnect=true&serverTimezone=UTC"
                        + "&connectTimeout=" + (CONNECT_TIMEOUT_SEC * 1000)
                        + "&socketTimeout=" + (SOCKET_TIMEOUT_SEC * 1000)
                        + "&tcpKeepAlive=true";
                return DriverManager.getConnection(url, settings.user, settings.password);
            }
        }
    }

    /**
     * Взять соединение: сначала слот семафора, потом простаивающее соединение
     * (мёртвое — закрыть) или новое. При любой ошибке слот возвращается.
     * В главном потоке ждём слот недолго — лучше ошибка, чем фриз сервера.
     */
    private Connection acquire() throws SQLException {
        Semaphore p = permits;
        if (!open || p == null) {
            throw new SQLException("Хранилище закрыто");
        }
        long waitMs = onPrimaryThread() ? 2000L : 10_000L;
        try {
            if (!p.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
                throw new SQLException("Нет свободных соединений (pool_size=" + poolSize + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Прервано ожидание соединения");
        }
        try {
            Connection c;
            while ((c = idle.poll()) != null) {
                boolean alive;
                try {
                    alive = !c.isClosed() && c.isValid(2);
                } catch (SQLException e) {
                    alive = false;
                }
                if (alive) {
                    return c;
                }
                closeQuietly(c);
            }
            return newConnection();
        } catch (SQLException | RuntimeException e) {
            p.release();
            throw e;
        }
    }

    private void release(Connection c) {
        if (c == null) {
            return;
        }
        Semaphore p = permits;
        boolean alive;
        try {
            alive = open && !c.isClosed();
        } catch (SQLException e) {
            alive = false;
        }
        if (alive) {
            idle.offer(c);
        } else {
            closeQuietly(c);
        }
        if (p != null) {
            p.release();
        }
    }

    private static boolean onPrimaryThread() {
        try {
            return org.bukkit.Bukkit.isPrimaryThread();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- схема ----------

    private void createSchema() throws SQLException {
        String boolType = settings.dialect == Dialect.POSTGRESQL ? "BOOLEAN" : "TINYINT";
        String bigint = settings.dialect == Dialect.SQLITE ? "INTEGER" : "BIGINT";
        String sql = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "uuid VARCHAR(36) PRIMARY KEY,"
                + "name VARCHAR(32) NOT NULL,"
                + "name_lower VARCHAR(32) NOT NULL,"
                + "password VARCHAR(512) NOT NULL,"
                + "registered_at " + bigint + " NOT NULL DEFAULT 0,"
                + "registered_ip VARCHAR(64) NOT NULL DEFAULT '',"
                + "last_ip VARCHAR(64) NOT NULL DEFAULT '',"
                + "last_auth " + bigint + " NOT NULL DEFAULT 0,"
                + "ip_auth TEXT,"
                + "totp VARCHAR(128),"
                + "email VARCHAR(255),"
                + "email_verified " + boolType + " NOT NULL DEFAULT " + (settings.dialect == Dialect.POSTGRESQL ? "FALSE" : "0")
                + ")";
        Connection c = acquire();
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
            // Индексы: поиск по нику (каждый пре-логин) и по IP (лимит мультиаккаунтов)
            ensureIndex(c, st, "idx_" + table + "_name", "name_lower");
            ensureIndex(c, st, "idx_" + table + "_regip", "registered_ip");
            ensureIndex(c, st, "idx_" + table + "_lastip", "last_ip");
        } finally {
            release(c);
        }
    }

    /**
     * MySQL/MariaDB (включая 8.0) не понимают CREATE INDEX IF NOT EXISTS —
     * проверяем наличие индекса через метаданные и создаём обычным CREATE INDEX.
     * SQLite и PostgreSQL IF NOT EXISTS поддерживают.
     */
    private void ensureIndex(Connection c, Statement st, String index, String column) {
        if (settings.dialect == Dialect.MYSQL || settings.dialect == Dialect.MARIADB) {
            try (ResultSet rs = c.getMetaData().getIndexInfo(c.getCatalog(), null, table, false, false)) {
                while (rs.next()) {
                    if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                        return;
                    }
                }
            } catch (SQLException e) {
                log.warning(name() + ": не удалось прочитать индексы " + table + ": " + e.getMessage());
            }
            tryExec(st, "CREATE INDEX " + index + " ON " + table + " (" + column + ")");
            return;
        }
        tryExec(st, "CREATE INDEX IF NOT EXISTS " + index + " ON " + table + " (" + column + ")");
    }

    private void tryExec(Statement st, String sql) {
        try {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            // Индекс уже есть: MySQL 1061 (ER_DUP_KEYNAME), PostgreSQL 42P07 — это не ошибка
            if (e.getErrorCode() != 1061 && !"42P07".equals(e.getSQLState())) {
                log.warning(name() + ": " + e.getMessage());
            }
        }
    }

    // ---------- чтение ----------

    private static final String COLS = "uuid,name,password,registered_at,registered_ip,last_ip,last_auth,ip_auth,totp,email,email_verified";

    @Override
    public AccountRecord load(UUID uuid) throws SQLException {
        if (!ready()) {
            return null;
        }
        return queryOne("SELECT " + COLS + " FROM " + table + " WHERE uuid=?", uuid.toString());
    }

    @Override
    public AccountRecord loadByName(String name) throws SQLException {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return queryOne("SELECT " + COLS + " FROM " + table + " WHERE name_lower=?", name.toLowerCase(Locale.ROOT));
    }

    private AccountRecord queryOne(String sql, String param) throws SQLException {
        Connection c = acquire();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } finally {
            release(c);
        }
    }

    private AccountRecord read(ResultSet rs) throws SQLException {
        UUID uuid;
        try {
            uuid = UUID.fromString(rs.getString("uuid"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new AccountRecord(uuid,
                rs.getString("name"),
                rs.getString("password"),
                rs.getLong("registered_at"),
                rs.getString("registered_ip"),
                rs.getString("last_ip"),
                rs.getLong("last_auth"),
                parseIpAuth(rs.getString("ip_auth")),
                rs.getString("totp"),
                rs.getString("email"),
                rs.getBoolean("email_verified"));
    }

    @Override
    public int count() throws SQLException {
        return queryInt("SELECT COUNT(*) FROM " + table, null);
    }

    @Override
    public int countByRegisteredIp(String ip) throws SQLException {
        if (ip == null || ip.isEmpty()) {
            return 0;
        }
        return queryInt("SELECT COUNT(*) FROM " + table + " WHERE registered_ip=? OR last_ip=?", ip);
    }

    private int queryInt(String sql, String param) throws SQLException {
        Connection c = acquire();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (param != null) {
                ps.setString(1, param);
                if (sql.indexOf('?') != sql.lastIndexOf('?')) {
                    ps.setString(2, param);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } finally {
            release(c);
        }
    }

    @Override
    public Collection<AccountRecord> list(int offset, int limit) throws SQLException {
        List<AccountRecord> out = new ArrayList<>();
        Connection c = acquire();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLS + " FROM " + table + " ORDER BY name_lower LIMIT ? OFFSET ?")) {
            ps.setInt(1, Math.max(0, limit));
            ps.setInt(2, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AccountRecord r = read(rs);
                    if (r != null) {
                        out.add(r);
                    }
                }
            }
        } finally {
            release(c);
        }
        return out;
    }

    @Override
    public Collection<AccountRecord> loadAll() throws SQLException {
        List<AccountRecord> out = new ArrayList<>();
        Connection c = acquire();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT " + COLS + " FROM " + table)) {
            while (rs.next()) {
                AccountRecord r = read(rs);
                if (r != null) {
                    out.add(r);
                }
            }
        } finally {
            release(c);
        }
        return out;
    }

    // ---------- запись ----------

    /**
     * UPDATE, а если строки не было — INSERT. Портабельно между всеми диалектами
     * (ON CONFLICT / ON DUPLICATE KEY у каждого свой синтаксис).
     */
    @Override
    public void saveBatch(java.util.Collection<AccountRecord> records) throws SQLException, DuplicateAccountException {
        if (records == null || records.isEmpty()) {
            return;
        }
        // Новые регистрации — по одной, только INSERT и вне общей транзакции
        // (на PostgreSQL ошибка ключа ломает всю транзакцию пакета)
        List<AccountRecord> existing = new ArrayList<>(records.size());
        List<AccountRecord> conflicts = null;
        for (AccountRecord r : records) {
            if (!r.isNew()) {
                existing.add(r);
                continue;
            }
            try {
                save(r);
            } catch (DuplicateAccountException e) {
                if (conflicts == null) {
                    conflicts = new ArrayList<>();
                }
                conflicts.addAll(e.getConflicts());
            }
        }
        if (existing.size() == 1) {
            save(existing.get(0));
        } else if (!existing.isEmpty()) {
            // Пакетная запись в ОДНОЙ транзакции: меньше round-trip, меньше fsync, быстрее
            Connection c = acquire();
            try {
                synchronized (sqliteLock) {
                    boolean prevAuto = c.getAutoCommit();
                    c.setAutoCommit(false);
                    try {
                        for (AccountRecord r : existing) {
                            saveOne(c, r);
                        }
                        c.commit();
                        for (AccountRecord r : existing) {
                            r.clearDirty();
                        }
                    } catch (SQLException e) {
                        try {
                            c.rollback();
                        } catch (SQLException ignored) {
                        }
                        throw e;
                    } finally {
                        c.setAutoCommit(prevAuto);
                    }
                }
            } finally {
                release(c);
            }
        }
        if (conflicts != null) {
            throw new DuplicateAccountException(conflicts);
        }
    }

    @Override
    public void save(AccountRecord r) throws SQLException, DuplicateAccountException {
        Connection c = acquire();
        try {
            if (r.isNew()) {
                try {
                    synchronized (sqliteLock) {
                        insertOne(c, r);
                    }
                } catch (SQLException e) {
                    if (isDuplicateKey(e)) {
                        throw new DuplicateAccountException(java.util.Collections.singletonList(r));
                    }
                    throw e;
                }
                r.clearNew();
            } else {
                synchronized (sqliteLock) {
                    saveOne(c, r);
                }
            }
            r.clearDirty();
        } finally {
            release(c);
        }
    }

    /** Нарушение первичного ключа: SQLState 23xxx (MySQL/PostgreSQL), код 19 SQLITE_CONSTRAINT. */
    private static boolean isDuplicateKey(SQLException e) {
        if (e instanceof java.sql.SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        if (e.getErrorCode() == 19 || e.getErrorCode() == 1555 || e.getErrorCode() == 2067) {
            return true;
        }
        String m = e.getMessage();
        return m != null && m.toLowerCase(Locale.ROOT).contains("constraint");
    }

    private void insertOne(Connection c, AccountRecord r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table
                + " (name,name_lower,password,registered_at,registered_ip,last_ip,last_auth,ip_auth,totp,email,email_verified,uuid)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            bind(ps, r);
            ps.setString(12, r.getUuid().toString());
            ps.executeUpdate();
        }
    }

    private void saveOne(Connection c, AccountRecord r) throws SQLException {
        int updated;
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET name=?,name_lower=?,password=?,"
                + "registered_at=?,registered_ip=?,last_ip=?,last_auth=?,ip_auth=?,totp=?,email=?,email_verified=? WHERE uuid=?")) {
            bind(ps, r);
            ps.setString(12, r.getUuid().toString());
            updated = ps.executeUpdate();
        }
        if (updated == 0) {
            insertOne(c, r);
        }
    }

    private void bind(PreparedStatement ps, AccountRecord r) throws SQLException {
        ps.setString(1, r.getName());
        ps.setString(2, r.getName().toLowerCase(Locale.ROOT));
        ps.setString(3, r.getPasswordHash());
        ps.setLong(4, r.getRegisteredAt());
        ps.setString(5, r.getRegisteredIp());
        ps.setString(6, r.getLastIp());
        ps.setLong(7, r.getLastAuthMillis());
        ps.setString(8, serializeIpAuth(r.getIpAuthMillis()));
        ps.setString(9, r.getTotpSecret());
        ps.setString(10, r.getEmail());
        ps.setBoolean(11, r.isEmailVerified());
    }

    @Override
    public void delete(UUID uuid) throws SQLException {
        Connection c = acquire();
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE uuid=?")) {
            synchronized (sqliteLock) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        } finally {
            release(c);
        }
    }

    // ---------- сериализация карты IP → время ----------

    private static String serializeIpAuth(Map<String, Long> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static Map<String, Long> parseIpAuth(String s) {
        Map<String, Long> map = new HashMap<>();
        if (s == null || s.isEmpty()) {
            return map;
        }
        for (String part : s.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                map.put(part.substring(0, eq), Long.parseLong(part.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return map;
    }

    private static final int READY = 967612766;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1023) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1023) == READY;
    }
}
