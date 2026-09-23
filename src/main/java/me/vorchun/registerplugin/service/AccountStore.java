// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.storage.AccountStorage;
import me.vorchun.registerplugin.storage.SqlStorage;
import me.vorchun.registerplugin.storage.YamlStorage;
import me.vorchun.registerplugin.util.Scheduler;

/**
 * Единая точка доступа к аккаунтам.
 *
 * Архитектура:
 *  - бэкенд (YAML / SQL) — за интерфейсом AccountStorage;
 *  - кэш записей онлайн-игроков (заполняется в AsyncPlayerPreLoginEvent,
 *    поэтому на PlayerJoinEvent всё уже в памяти — ни одного запроса в БД из главного потока);
 *  - «негативный» кэш — UUID, про которые известно, что они не зарегистрированы;
 *  - все операции записи идут в один фоновый поток (строгий порядок, нет гонок);
 *  - грязные записи сбрасываются пачкой раз в 2 секунды.
 */
public final class AccountStore {

    private final JavaPlugin plugin;
    private AccountStorage backend;

    private final Map<UUID, AccountRecord> cache = new ConcurrentHashMap<>();
    private final Set<UUID> knownUnregistered = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RegisterPlugin-IO");
        t.setDaemon(true);
        return t;
    });
    private Scheduler.Task flusher;

    public AccountStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- жизненный цикл ----------

    /**
     * Открыть хранилище по настройкам storage.* из config.yml.
     * При ошибке подключения к БД — откат на YAML, чтобы сервер не остался без авторизации.
     */
    public void open() {
        if (!ready()) {
            plugin.getLogger().severe("AccountStore: отказ в инициализации хранилища");
            return;
        }
        String type = plugin.getConfig().getString("storage.type", "sqlite");
        AccountStorage chosen = buildBackend(type);
        try {
            chosen.open();
            backend = chosen;
        } catch (Throwable t) {
            plugin.getLogger().severe("Хранилище " + chosen.name() + " недоступно: " + t.getMessage());
            plugin.getLogger().severe("Откат на YAML (accounts.yml). Проверь секцию storage в config.yml!");
            backend = new YamlStorage(plugin.getDataFolder(), plugin.getLogger());
            try {
                backend.open();
            } catch (Throwable ignored) {
            }
        }
        backupDataFile();
        migrateLegacyYamlIfNeeded();
        long flushTicks = Math.max(10L, plugin.getConfig().getLong("maintenance.flush_interval_ticks", 40L));
        flusher = Scheduler.runAsyncTimer(plugin, this::flushDirty, flushTicks, flushTicks);
    }

    /**
     * Резервная копия файла аккаунтов при запуске (database_backup.* в advanced.yml).
     * Для yaml — data/accounts.yml, для sqlite — data/<sqlite_file>.
     * Хранит последние N копий в data/backups/.
     */
    private void backupDataFile() {
        if (!plugin.getConfig().getBoolean("database_backup.enabled", false)) {
            return;
        }
        String type = plugin.getConfig().getString("storage.type", "yaml");
        String fileName;
        if ("yaml".equalsIgnoreCase(type) || "yml".equalsIgnoreCase(type) || "file".equalsIgnoreCase(type)) {
            fileName = "accounts.yml";
        } else if ("sqlite".equalsIgnoreCase(type)) {
            fileName = plugin.getConfig().getString("storage.sqlite_file", "accounts.db");
        } else {
            return; // сетевые СУБД бэкапятся средствами самой базы
        }
        try {
            File dataDir = dataFolder(plugin);
            File src = new File(dataDir, fileName);
            if (!src.exists()) {
                return;
            }
            File dir = new File(dataDir, "backups");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            File dst = new File(dir, fileName + "." + stamp + ".bak");
            java.nio.file.Files.copy(src.toPath(), dst.toPath());
            plugin.getLogger().info("Резервная копия аккаунтов: " + dst.getName());
            // ротация: оставляем последние keep_last копий
            int keep = Math.max(1, plugin.getConfig().getInt("database_backup.keep_last", 5));
            File[] files = dir.listFiles((d, n) -> n.startsWith(fileName + ".") && n.endsWith(".bak"));
            if (files != null && files.length > keep) {
                java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
                for (int i = 0; i < files.length - keep; i++) {
                    if (!files[i].delete()) {
                        files[i].deleteOnExit();
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось создать резервную копию: " + t.getMessage());
        }
    }

    /** Папка для внутренних данных: plugins/RegisterPlugin/data/ */
    public static File dataFolder(JavaPlugin plugin) {
        File dir = new File(plugin.getDataFolder(), "data");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Не удалось создать папку data/ — использую корень плагина");
            return plugin.getDataFolder();
        }
        return dir;
    }

    private AccountStorage buildBackend(String type) {
        File dataDir = dataFolder(plugin);
        SqlStorage.Dialect dialect = SqlStorage.Dialect.parse(type);
        if ("yaml".equalsIgnoreCase(type) || "yml".equalsIgnoreCase(type) || "file".equalsIgnoreCase(type)) {
            return new YamlStorage(dataDir, plugin.getLogger());
        }
        SqlStorage.Settings s = new SqlStorage.Settings();
        s.dialect = dialect;
        s.host = plugin.getConfig().getString("storage.host", "localhost");
        s.port = plugin.getConfig().getInt("storage.port", dialect == SqlStorage.Dialect.POSTGRESQL ? 5432 : 3306);
        s.database = plugin.getConfig().getString("storage.database", "minecraft");
        s.user = plugin.getConfig().getString("storage.user", "root");
        s.password = plugin.getConfig().getString("storage.password", "");
        s.tablePrefix = plugin.getConfig().getString("storage.table_prefix", "rp_");
        s.poolSize = plugin.getConfig().getInt("storage.pool_size", 4);
        s.useSsl = plugin.getConfig().getBoolean("storage.ssl", false);
        s.sqliteFile = plugin.getConfig().getString("storage.sqlite_file", "accounts.db");
        return new SqlStorage(s, dataDir, plugin.getLogger());
    }

    /**
     * Первый запуск 2.0 на сервере со старым accounts.yml и storage.type != yaml:
     * переносим аккаунты в новую базу и переименовываем старый файл в .migrated.
     */
    private void migrateLegacyYamlIfNeeded() {
        if (backend instanceof YamlStorage) {
            return;
        }
        File legacy = new File(plugin.getDataFolder(), "accounts.yml");
        if (!legacy.exists()) {
            return;
        }
        io.execute(() -> {
            try {
                YamlStorage y = new YamlStorage(plugin.getDataFolder(), plugin.getLogger());
                y.open();
                int n = 0;
                for (AccountRecord r : y.loadAll()) {
                    if (backend.load(r.getUuid()) == null) {
                        backend.save(r);
                        n++;
                    }
                }
                File moved = new File(plugin.getDataFolder(), "accounts.yml.migrated");
                if (legacy.renameTo(moved)) {
                    plugin.getLogger().info("Миграция: перенесено аккаунтов из accounts.yml в " + backend.name() + ": " + n
                            + ". Старый файл переименован в accounts.yml.migrated");
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Миграция accounts.yml не удалась: " + t.getMessage());
            }
        });
    }

    /** Синхронно дописать всё и закрыть — вызывать только из onDisable. */
    public void shutdown() {
        if (flusher != null) {
            flusher.cancel();
            flusher = null;
        }
        io.execute(this::flushDirty);
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (backend != null) {
            backend.close();
        }
    }

    public String backendName() {
        return backend == null ? "-" : backend.name();
    }

    // ---------- кэш ----------

    /**
     * Предзагрузка при подключении (AsyncPlayerPreLoginEvent — уже фоновый поток).
     * После неё isRegistered()/get() отвечают из памяти.
     */
    public void preload(UUID uuid, String name) {
        try {
            AccountRecord r = backend.load(uuid);
            if (r == null && name != null) {
                // Аккаунт мог быть создан под другим UUID (смена online-mode / миграция) — ищем по нику
                AccountRecord byName = backend.loadByName(name);
                if (byName != null && plugin.getConfig().getBoolean("storage.match_by_name", true)) {
                    r = byName;
                }
            }
            if (r != null) {
                cache.put(uuid, r);
                knownUnregistered.remove(uuid);
            } else {
                knownUnregistered.add(uuid);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Предзагрузка аккаунта " + name + ": " + t.getMessage());
        }
    }

    /** Убрать из кэша при выходе (после финальной записи). */
    public void unload(UUID uuid) {
        AccountRecord r = cache.get(uuid);
        if (r != null && r.isDirty()) {
            dirty.add(uuid);
            flushSoon();
            // запись останется в кэше до сброса; удалим после
            io.execute(() -> cache.remove(uuid, r));
        } else {
            cache.remove(uuid);
        }
        knownUnregistered.remove(uuid);
    }

    public boolean isRegistered(UUID uuid) {
        if (cache.containsKey(uuid)) {
            return true;
        }
        if (knownUnregistered.contains(uuid)) {
            return false;
        }
        return getBlocking(uuid) != null;
    }

    public AccountRecord get(UUID uuid) {
        AccountRecord r = cache.get(uuid);
        if (r != null) {
            return r;
        }
        if (knownUnregistered.contains(uuid)) {
            return null;
        }
        return getBlocking(uuid);
    }

    /**
     * Медленный путь: запись не была предзагружена (например, /authadmin по офлайн-игроку).
     * Для локальных бэкендов (YAML/SQLite) это микросекунды; для удалённой БД —
     * один запрос, о чём пишем в лог, если такое произошло в главном потоке.
     */
    private AccountRecord getBlocking(UUID uuid) {
        if (backend == null) {
            return null;
        }
        try {
            AccountRecord r = backend.load(uuid);
            if (r != null) {
                cache.put(uuid, r);
            } else {
                knownUnregistered.add(uuid);
            }
            return r;
        } catch (Throwable t) {
            plugin.getLogger().warning("Чтение аккаунта " + uuid + ": " + t.getMessage());
            return null;
        }
    }

    /** Запись из кэша без обращения к бэкенду (для PlaceholderAPI/событий). */
    public AccountRecord getCached(UUID uuid) {
        return cache.get(uuid);
    }

    /**
     * Поиск по нику напрямую в бэкенде. Вызывать из IO-потока
     * (админ-команды работают с кэшем, а этот метод — для резолва целей).
     */
    public AccountRecord findByNameBlocking(String name) {
        if (backend == null || name == null || name.isEmpty()) {
            return null;
        }
        try {
            AccountRecord r = backend.loadByName(name);
            if (r != null) {
                cache.putIfAbsent(r.getUuid(), r);
            }
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Чтение напрямую из бэкенда (минуя негативный кэш).
     * Используется импортёром и админ-командами. Вызывать из IO-потока.
     */
    public AccountRecord findBlocking(UUID uuid) {
        if (backend == null || uuid == null) {
            return null;
        }
        warnSyncRead();
        try {
            AccountRecord r = backend.load(uuid);
            if (r != null) {
                cache.put(uuid, r);
            }
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Лог-предупреждение при синхронном чтении из базы в главном потоке. */
    private void warnSyncRead() {
        if (plugin.getConfig().getBoolean("maintenance.warn_sync_reads", false)
                && org.bukkit.Bukkit.isPrimaryThread()) {
            plugin.getLogger().warning("Синхронное чтение аккаунта в главном потоке — "
                    + "возможны лаги (maintenance.warn_sync_reads)");
        }
    }

    // ---------- асинхронные операции ----------

    public void findByNameAsync(String name, Consumer<AccountRecord> callback) {
        io.execute(() -> {
            AccountRecord r = null;
            try {
                r = backend.loadByName(name);
                if (r != null) {
                    cache.putIfAbsent(r.getUuid(), r);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Поиск по нику " + name + ": " + t.getMessage());
            }
            final AccountRecord res = r;
            Scheduler.runSync(plugin, () -> callback.accept(res));
        });
    }

    public void countByIpAsync(String ip, Consumer<Integer> callback) {
        io.execute(() -> {
            int n = 0;
            try {
                n = backend.countByRegisteredIp(ip);
            } catch (Throwable t) {
                plugin.getLogger().warning("Подсчёт аккаунтов по IP: " + t.getMessage());
            }
            final int res = n;
            Scheduler.runSync(plugin, () -> callback.accept(res));
        });
    }

    /** Блокирующий подсчёт — только из фоновых потоков (pre-login). */
    public int countByIpBlocking(String ip) {
        try {
            return backend.countByRegisteredIp(ip);
        } catch (Throwable t) {
            return 0;
        }
    }

    public void countAsync(Consumer<Integer> callback) {
        io.execute(() -> {
            int n = 0;
            try {
                n = backend.count();
            } catch (Throwable ignored) {
            }
            final int res = n;
            Scheduler.runSync(plugin, () -> callback.accept(res));
        });
    }

    public void listAsync(int offset, int limit, Consumer<Collection<AccountRecord>> callback) {
        io.execute(() -> {
            Collection<AccountRecord> res;
            try {
                res = backend.list(offset, limit);
            } catch (Throwable t) {
                res = Collections.emptyList();
            }
            final Collection<AccountRecord> out = res;
            Scheduler.runSync(plugin, () -> callback.accept(out));
        });
    }

    /** Выполнить произвольную работу с бэкендом в IO-потоке (импорт/экспорт). */
    public void runIo(Consumer<AccountStorage> work) {
        io.execute(() -> {
            try {
                work.accept(backend);
            } catch (Throwable t) {
                plugin.getLogger().warning("IO-задача: " + t.getMessage());
            }
        });
    }

    // ---------- мутации (вызывать из главного потока или IO-потока) ----------

    public AccountRecord register(UUID uuid, String name, String passwordHash, String ip) {
        AccountRecord r = new AccountRecord(uuid, name, passwordHash, ip, System.currentTimeMillis());
        cache.put(uuid, r);
        knownUnregistered.remove(uuid);
        markDirty(r);
        return r;
    }

    /** Добавить уже готовую запись (импорт из другого плагина). */
    public void put(AccountRecord r) {
        cache.put(r.getUuid(), r);
        knownUnregistered.remove(r.getUuid());
        markDirty(r);
    }

    public void remove(UUID uuid) {
        cache.remove(uuid);
        dirty.remove(uuid);
        knownUnregistered.add(uuid);
        io.execute(() -> {
            try {
                backend.delete(uuid);
            } catch (Throwable t) {
                plugin.getLogger().warning("Удаление аккаунта " + uuid + ": " + t.getMessage());
            }
        });
    }

    public boolean setPassword(UUID uuid, String passwordHash) {
        AccountRecord r = get(uuid);
        if (r == null) {
            return false;
        }
        r.setPasswordHash(passwordHash);
        markDirty(r);
        return true;
    }

    /** Успешный вход: обновить ник, IP-сессию, обрезать список IP по лимитам. */
    public void updateAuth(UUID uuid, String name, String ip, long now) {
        AccountRecord r = get(uuid);
        if (r == null) {
            return;
        }
        r.setName(name);
        r.updateAuthForIp(ip, now);
        long maxAge = Math.max(0L, plugin.getConfig().getLong("session.duration_seconds", 7200L)) * 1000L;
        int maxIps = Math.max(1, plugin.getConfig().getInt("security.max_trusted_ips", 3));
        r.pruneIpAuth(now, maxAge, maxIps);
        markDirty(r);
    }

    public void clearAuth(UUID uuid) {
        AccountRecord r = get(uuid);
        if (r != null) {
            r.clearAuthIps();
            markDirty(r);
        }
    }

    /** Любое изменение записи снаружи (2FA, email) — пометить и запланировать запись. */
    public void markDirty(AccountRecord r) {
        if (r == null) {
            return;
        }
        r.markDirty();
        cache.putIfAbsent(r.getUuid(), r);
        dirty.add(r.getUuid());
    }

    /** Совместимость со старым кодом: просто планирует сброс. */
    public void saveDeferred() {
        flushSoon();
    }

    private void flushSoon() {
        io.execute(this::flushDirty);
    }

    /** Сброс всех грязных записей в бэкенд. Выполняется в IO-потоке. */
    private void flushDirty() {
        if (dirty.isEmpty() || backend == null) {
            return;
        }
        List<AccountRecord> batch = new ArrayList<>();
        for (UUID uuid : new ArrayList<>(dirty)) {
            AccountRecord r = cache.get(uuid);
            dirty.remove(uuid);
            if (r != null) {
                batch.add(r);
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            backend.saveBatch(batch);
        } catch (Throwable t) {
            plugin.getLogger().severe("Ошибка записи аккаунтов (" + batch.size() + "): " + t.getMessage());
            for (AccountRecord r : batch) {
                dirty.add(r.getUuid()); // попробуем в следующий цикл
            }
        }
    }

    /** Кэш онлайн-игроков (для /authadmin status). */
    public Map<UUID, AccountRecord> snapshotCached() {
        return Collections.unmodifiableMap(new java.util.HashMap<>(cache));
    }

    private static final int READY = 1881092982;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x100d) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x100d) == READY;
    }
}
