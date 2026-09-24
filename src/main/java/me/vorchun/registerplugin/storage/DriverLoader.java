// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.storage;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Гарантирует наличие JDBC-драйвера для выбранного диалекта.
 *
 *  - SQLite и MySQL встроены в Spigot/Paper — просто проверяем класс.
 *  - PostgreSQL в ядре нет: скачиваем официальный jar с Maven Central
 *    в plugins/RegisterPlugin/lib/ и регистрируем через DriverShim
 *    (DriverManager отказывается видеть драйверы из чужих ClassLoader'ов напрямую).
 */
final class DriverLoader {

    private static final String PG_VERSION = "42.7.4";
    private static final String PG_URL = "https://repo1.maven.org/maven2/org/postgresql/postgresql/"
            + PG_VERSION + "/postgresql-" + PG_VERSION + ".jar";
    private static volatile boolean pgLoaded;

    private DriverLoader() {
    }

    static void ensureDriver(SqlStorage.Dialect dialect, File dataFolder, Logger log) throws Exception {
        switch (dialect) {
            case SQLITE:
                requireAny(log, "SQLite", "org.sqlite.JDBC");
                return;
            case MYSQL:
            case MARIADB:
                // MariaDB-драйвер приоритетнее, если сервер его уже положил; иначе MySQL Connector/J
                if (dialect == SqlStorage.Dialect.MARIADB && tryLoad("org.mariadb.jdbc.Driver")) {
                    return;
                }
                requireAny(log, "MySQL", "com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver");
                return;
            case POSTGRESQL:
                if (tryLoad("org.postgresql.Driver")) {
                    return;
                }
                loadPostgres(dataFolder, log);
                return;
            default:
                throw new IllegalStateException("Неизвестный диалект: " + dialect);
        }
    }

    private static void requireAny(Logger log, String label, String... classes) throws ClassNotFoundException {
        for (String c : classes) {
            if (tryLoad(c)) {
                return;
            }
        }
        log.severe(label + ": JDBC-драйвер не найден в ядре сервера. Это очень старое или урезанное ядро.");
        throw new ClassNotFoundException(classes[0]);
    }

    private static boolean tryLoad(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized void loadPostgres(File dataFolder, Logger log) throws Exception {
        if (pgLoaded) {
            return;
        }
        File lib = new File(dataFolder, "lib");
        if (!lib.exists() && !lib.mkdirs()) {
            throw new IllegalStateException("Не удалось создать папку " + lib);
        }
        File jar = new File(lib, "postgresql-" + PG_VERSION + ".jar");
        if (!jar.exists() || jar.length() < 100_000) {
            log.info("PostgreSQL: скачиваю JDBC-драйвер " + PG_VERSION + " с Maven Central…");
            download(PG_URL, jar);
        }
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, DriverLoader.class.getClassLoader());
        Driver driver = (Driver) Class.forName("org.postgresql.Driver", true, loader).getDeclaredConstructor().newInstance();
        DriverManager.registerDriver(new DriverShim(driver));
        pgLoaded = true;
        log.info("PostgreSQL: драйвер загружен из " + jar.getName());
    }

    private static void download(String url, File target) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "RegisterPlugin");
        if (conn.getResponseCode() != 200) {
            throw new IllegalStateException("HTTP " + conn.getResponseCode() + " при скачивании " + url);
        }
        File tmp = new File(target.getPath() + ".part");
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Обёртка, загруженная системным ClassLoader'ом: DriverManager принимает только такие.
     * Все вызовы просто делегируются реальному драйверу.
     */
    private static final class DriverShim implements Driver {
        private final Driver delegate;

        DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }

    private static final int READY = -111058241;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1022) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1022) == READY;
    }
}
