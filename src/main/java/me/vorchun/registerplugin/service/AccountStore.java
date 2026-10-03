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

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.storage.AccountStorage;
import me.vorchun.registerplugin.storage.DuplicateAccountException;
import me.vorchun.registerplugin.storage.SqlStorage;
import me.vorchun.registerplugin.storage.YamlStorage;
import me.vorchun.registerplugin.util.Scheduler;

/**
 * Единая точка доступа к аккаунтам.
 *
 * Архитектура:
 *  - бэкенд (YAML / SQL) — за интерфейсом AccountStorage;
 *  - кэш записей онлайн-игроков (заполняется в AsyncPlayerPreLoginEvent собственным
 *    слушателем — всегда, независимо от антибота; поэтому на PlayerJoinEvent всё
 *    уже в памяти — ни одного запроса в БД из главного потока); выход — выгрузка;
 *  - «негативный» кэш — UUID, про которые известно, что они не зарегистрированы;
 *  - ошибка чтения — это «не знаем», а НЕ «не зарегистрирован»: такой игрок
 *    не может зарегистрироваться (иначе перезапишет чужой аккаунт);
 *  - хранилище недоступно на старте — вход закрыт, фоновый повтор подключения
 *    (никакого тихого отката на пустой YAML);
 *  - все операции записи и удаления идут в один фоновый поток (строгий порядок, нет гонок);
 *  - грязные записи сбрасываются пачкой раз в 2 секунды.
 */
public final class AccountStore {

    private final JavaPlugin plugin;
    private volatile AccountStorage backend;

    private final Map<UUID, AccountRecord> cache = new ConcurrentHashMap<>();
    private final Set<UUID> knownUnregistered = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    /** UUID, чтение которых из базы упало: регистрировать нельзя, вход закрыт. */
    private final Set<UUID> loadFailed = ConcurrentHashMap.newKeySet();
    /** Игроки на сервере (после PlayerJoinEvent) — их запись в кэше авторитетна. */
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    /** Вышли, но запись ещё не сброшена — выгрузим после flush. */
    private final Set<UUID> pendingUnload = ConcurrentHashMap.newKeySet();
    /** Когда запись последний раз читалась из базы (дедупликация preload). */
    private final Map<UUID, Long> loadedAt = new ConcurrentHashMap<>();

    /** Хранилище не открылось — вход закрыт до успешного повтора. */
    private volatile boolean storageDown;
    /** После ошибки чтения главный поток не ходит в базу до этого момента. */
    private volatile long readBackoffUntil;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RegisterPlugin-IO");
        t.setDaemon(true);
        return t;
    });
    private Scheduler.Task flusher;
    private Scheduler.Task reopener;
    private Scheduler.Task purger;
    private final Lifecycle lifecycle = new Lifecycle();
    private boolean lifecycleRegistered;

    public AccountStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- жизненный цикл ----------

    /**
     * Открыть хранилище по настройкам storage.* из config.yml.
     * При ошибке подключения вход на сервер закрывается (storageDown) и раз
     * в 10 секунд делается повтор — пустая «запасная» база опаснее простоя:
     * на ней любой ник можно было бы зарегистрировать заново.
     */
    public void open() {
        if (!ready()) {
            plugin.getLogger().severe("AccountStore: отказ в инициализации хранилища");
            return;
        }
        String type = plugin.getConfig().getString("storage.type", "sqlite");
        AccountStorage chosen = buildBackend(type);
        backend = chosen;
        try {
            chosen.open();
            storageDown = false;
        } catch (Throwable t) {
            storageDown = true;
            plugin.getLogger().severe("Хранилище " + chosen.name() + " недоступно: " + t.getMessage());
            plugin.getLogger().severe("Вход на сервер закрыт, пока хранилище не станет доступно (повтор каждые 10 с)."
                    + " Проверь секцию storage в config.yml!");
        }
        backupDataFile();
        if (!storageDown) {
            migrateLegacyYamlIfNeeded();
            migrateYamlBackendIfNeeded();
        }
        long flushTicks = Math.max(10L, plugin.getConfig().getLong("maintenance.flush_interval_ticks", 40L));
        // Таймер только ставит задачу в io: запись и удаление идут строго по очереди
        flusher = Scheduler.runAsyncTimer(plugin, () -> submitIo(this::flushDirty), flushTicks, flushTicks);
        reopener = Scheduler.runAsyncTimer(plugin, () -> {
            if (storageDown) {
                submitIo(this::tryReopen);
            }
        }, 200L, 200L);
        schedulePurge();
        if (!lifecycleRegistered) {
            lifecycleRegistered = true;
            Bukkit.getPluginManager().registerEvents(lifecycle, plugin);
        }
        // /reload с игроками на сервере: они уже «на сервере», хоть join и не пришёл
        for (Player p : Bukkit.getOnlinePlayers()) {
            active.add(p.getUniqueId());
        }
    }

    /** Повтор подключения к недоступному хранилищу (IO-поток). */
    private void tryReopen() {
        AccountStorage b = backend;
        if (!storageDown || b == null) {
            return;
        }
        try {
            b.open();
            storageDown = false;
            loadFailed.clear();
            readBackoffUntil = 0L;
            plugin.getLogger().info("Хранилище " + b.name() + " снова доступно — вход открыт");
            migrateLegacyYamlIfNeeded();
            migrateYamlBackendIfNeeded();
        } catch (Throwable ignored) {
            // причина уже в логе со старта; не спамим раз в 10 секунд
        }
    }

    private void submitIo(Runnable r) {
        try {
            io.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // io уже остановлен (выключение плагина)
        }
    }

    /** Хранилище недоступно целиком (вход закрыт). */
    public boolean isStorageDown() {
        return storageDown;
    }

    /**
     * Про этот UUID ничего нельзя утверждать: хранилище недоступно или чтение
     * упало. Регистрация и вход в таком состоянии запрещены.
     */
    public boolean isUnavailable(UUID uuid) {
        return storageDown || (uuid != null && loadFailed.contains(uuid));
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
        s.sqliteBusyMs = plugin.getConfig().getInt("storage.sqlite_busy_timeout_ms", 5000);
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

    /**
     * Смена storage.type с yaml на sqlite/mysql/…: новая база пуста, а аккаунты
     * лежат в data/accounts.yml — без переноса все игроки стали бы
     * «незарегистрированными». Переносим, ТОЛЬКО если новая база пустая
     * (повторно не дублируем), пачками; старый файл не удаляем —
     * переименовываем в accounts.yml.migrated.
     */
    private void migrateYamlBackendIfNeeded() {
        if (backend instanceof YamlStorage) {
            return;
        }
        File dir = dataFolder(plugin);
        File yaml = new File(dir, "accounts.yml");
        if (!yaml.exists()) {
            return;
        }
        io.execute(() -> {
            try {
                if (backend.count() > 0) {
                    plugin.getLogger().warning("В " + backend.name() + " уже есть аккаунты — data/accounts.yml не переношу "
                            + "автоматически. Добавить недостающие: /authadmin import");
                    return;
                }
                YamlStorage y = new YamlStorage(dir, plugin.getLogger());
                y.open();
                java.util.List<AccountRecord> batch = new java.util.ArrayList<>();
                int n = 0;
                for (AccountRecord r : y.loadAll()) {
                    batch.add(r);
                    if (batch.size() >= 500) {
                        backend.saveBatch(batch);
                        n += batch.size();
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    backend.saveBatch(batch);
                    n += batch.size();
                }
                y.close();
                knownUnregistered.clear(); // до переноса эти UUID числились «без аккаунта»
                File moved = new File(dir, "accounts.yml.migrated");
                boolean renamed = yaml.renameTo(moved);
                plugin.getLogger().info("Смена хранилища: перенесено аккаунтов из data/accounts.yml в "
                        + backend.name() + ": " + n + (renamed ? ". Старый файл — data/accounts.yml.migrated" : ""));
            } catch (Throwable t) {
                plugin.getLogger().warning("Перенос data/accounts.yml в " + backend.name() + " не удался: " + t.getMessage()
                        + " — выполни /authadmin import");
            }
        });
    }

    /** Синхронно дописать всё и закрыть — вызывать только из onDisable. */
    public void shutdown() {
        if (flusher != null) {
            flusher.cancel();
            flusher = null;
        }
        if (reopener != null) {
            reopener.cancel();
            reopener = null;
        }
        if (purger != null) {
            purger.cancel();
            purger = null;
        }
        submitIo(this::flushDirty);
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
        pendingUnload.remove(uuid);
        AccountStorage b = backend;
        if (b == null || storageDown) {
            loadFailed.add(uuid);
            return;
        }
        try {
            AccountRecord r = b.load(uuid);
            if (r == null && name != null) {
                // Аккаунт мог быть создан под другим UUID (смена online-mode / миграция) — ищем по нику
                AccountRecord byName = b.loadByName(name);
                if (byName != null && plugin.getConfig().getBoolean("storage.match_by_name", true)) {
                    r = byName;
                }
            }
            loadFailed.remove(uuid);
            loadedAt.put(uuid, System.currentTimeMillis());
            if (r != null) {
                putLoaded(uuid, r);
                knownUnregistered.remove(uuid);
            } else if (!keepCached(cache.get(uuid), uuid)) {
                cache.remove(uuid);
                knownUnregistered.add(uuid);
            }
        } catch (Throwable t) {
            loadFailed.add(uuid);
            plugin.getLogger().warning("Предзагрузка аккаунта " + name + ": " + t.getMessage());
        }
    }

    /**
     * Положить прочитанную из базы запись в кэш, не затирая несохранённые
     * изменения (игрок перезашёл раньше очередного flush) и запись игрока,
     * который сейчас на сервере.
     */
    private void putLoaded(UUID uuid, AccountRecord fresh) {
        cache.compute(uuid, (k, old) -> keepCached(old, uuid) ? old : fresh);
    }

    private boolean keepCached(AccountRecord old, UUID uuid) {
        return old != null && (old.isDirty() || old.isNew() || dirty.contains(old.getUuid()) || active.contains(uuid));
    }

    /** Свежая ли предзагрузка (дедупликация: AntiBotGuard уже мог загрузить запись). */
    private boolean recentlyLoaded(UUID uuid) {
        Long at = loadedAt.get(uuid);
        return at != null && System.currentTimeMillis() - at < 5000L
                && !loadFailed.contains(uuid)
                && (cache.containsKey(uuid) || knownUnregistered.contains(uuid));
    }

    /** Убрать из кэша при выходе (несохранённая запись — после финальной записи). */
    public void unload(UUID uuid) {
        active.remove(uuid);
        knownUnregistered.remove(uuid);
        loadFailed.remove(uuid);
        loadedAt.remove(uuid);
        AccountRecord r = cache.get(uuid);
        if (r == null) {
            return;
        }
        if (r.isDirty() || r.isNew() || dirty.contains(r.getUuid())) {
            // запись останется в кэше до сброса; выгрузим в flushDirty
            dirty.add(r.getUuid());
            pendingUnload.add(uuid);
            flushSoon();
        } else {
            dropCached(uuid, r);
        }
    }

    private void dropCached(UUID uuid, AccountRecord r) {
        cache.remove(uuid, r);
        if (!r.getUuid().equals(uuid)) {
            cache.remove(r.getUuid(), r);
        }
    }

    public boolean isRegistered(UUID uuid) {
        if (cache.containsKey(uuid)) {
            return true;
        }
        if (knownUnregistered.contains(uuid)) {
            return false;
        }
        if (getBlocking(uuid) != null) {
            return true;
        }
        // Ошибка чтения — «не знаем», а не «нет аккаунта»: регистрацию не даём
        return isUnavailable(uuid);
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
     * Ошибка чтения помечает UUID как «недоступен» (isUnavailable), а не «не зарегистрирован».
     */
    private AccountRecord getBlocking(UUID uuid) {
        AccountStorage b = backend;
        if (b == null || uuid == null) {
            return null;
        }
        if (storageDown) {
            loadFailed.add(uuid);
            return null;
        }
        boolean primary = Bukkit.isPrimaryThread();
        if (primary && System.currentTimeMillis() < readBackoffUntil) {
            // База только что не ответила — не фризим главный поток повторами
            loadFailed.add(uuid);
            return null;
        }
        warnSyncRead();
        try {
            AccountRecord r = b.load(uuid);
            loadFailed.remove(uuid);
            loadedAt.put(uuid, System.currentTimeMillis());
            if (r != null) {
                putLoaded(uuid, r);
                return cache.getOrDefault(uuid, r);
            }
            knownUnregistered.add(uuid);
            return null;
        } catch (Throwable t) {
            loadFailed.add(uuid);
            readBackoffUntil = System.currentTimeMillis() + 5000L;
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
                cacheLookup(r);
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
                putLoaded(uuid, r);
            }
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Прочитать с признаком ошибки (для импорта): null — записи нет,
     * исключение — база не ответила (не путать с «нет записи»).
     */
    public AccountRecord findBlockingStrict(UUID uuid) throws Exception {
        AccountRecord cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        AccountStorage b = backend;
        if (b == null || storageDown) {
            throw new IllegalStateException("хранилище недоступно");
        }
        AccountRecord r = b.load(uuid);
        if (r != null) {
            putLoaded(uuid, r);
            return cache.getOrDefault(uuid, r);
        }
        return null;
    }

    /**
     * Запись офлайн-игрока, найденная по нику (админ-команды, API): в кэш — с
     * меткой загрузки, чтобы purgeAbandoned выгрузил её, а не держал до рестарта.
     */
    private void cacheLookup(AccountRecord r) {
        if (cache.putIfAbsent(r.getUuid(), r) == null) {
            loadedAt.putIfAbsent(r.getUuid(), System.currentTimeMillis());
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
                    cacheLookup(r);
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

    /**
     * Подсчёт для лимита при регистрации: в IO-потоке ПОСЛЕ сброса
     * несохранённых записей — только что созданные аккаунты тоже в счёте.
     * Блокирующий — только из фоновых потоков. Сбой — 0 (вход не ломаем).
     */
    public int countByIpFlushed(String ip) {
        try {
            return io.submit(() -> {
                flushDirty();
                return backend == null ? 0 : backend.countByRegisteredIp(ip);
            }).get(10, TimeUnit.SECONDS);
        } catch (Throwable t) {
            return 0;
        }
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

    /**
     * Автоочистка (advanced.yml → purge, по умолчанию ВЫКЛ): удаляет аккаунты без
     * входа purge.inactive_days дней. Не трогает игроков онлайн, аккаунты с почтой
     * или 2FA (по настройке) и записи без даты. Первый проход — через 10 минут
     * после старта, дальше раз в check_hours; всё в IO-потоке, главный не ждёт.
     */
    private void schedulePurge() {
        if (purger != null) {
            purger.cancel();
            purger = null;
        }
        if (!plugin.getConfig().getBoolean("purge.enabled", false)) {
            return;
        }
        long hours = Math.max(1, plugin.getConfig().getInt("purge.check_hours", 24));
        purger = Scheduler.runAsyncTimer(plugin, () -> submitIo(this::purgeInactive), 12_000L, hours * 72_000L);
    }

    private void purgeInactive() {
        AccountStorage b = backend;
        if (storageDown || b == null) {
            return;
        }
        int days = Math.max(30, plugin.getConfig().getInt("purge.inactive_days", 180));
        boolean keepEmail = plugin.getConfig().getBoolean("purge.keep_with_email", true);
        boolean keep2fa = plugin.getConfig().getBoolean("purge.keep_with_2fa", true);
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        java.util.List<UUID> victims = new java.util.ArrayList<>();
        try {
            for (int offset = 0; ; offset += 1000) {
                Collection<AccountRecord> page = b.list(offset, 1000);
                if (page == null || page.isEmpty()) {
                    break;
                }
                for (AccountRecord r : page) {
                    long last = r.getLastAuthMillis() > 0 ? r.getLastAuthMillis() : r.getRegisteredAt();
                    if (last <= 0 || last >= cutoff || active.contains(r.getUuid()) || cache.containsKey(r.getUuid())) {
                        continue;
                    }
                    if (keepEmail && r.getEmail() != null && !r.getEmail().isEmpty()) {
                        continue;
                    }
                    if (keep2fa && r.getTotpSecret() != null && !r.getTotpSecret().isEmpty()) {
                        continue;
                    }
                    victims.add(r.getUuid());
                }
                if (page.size() < 1000) {
                    break;
                }
            }
            b.deleteBatch(victims);
            if (!victims.isEmpty()) {
                plugin.getLogger().info("Автоочистка: удалено аккаунтов без входа больше " + days + " дн.: " + victims.size());
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Автоочистка аккаунтов: " + t.getMessage());
        }
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
        // Только INSERT: если строка с этим UUID в базе уже есть — это чужой
        // аккаунт, который не удалось прочитать; перезаписывать его нельзя
        r.markNew();
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
        AccountRecord r = cache.remove(uuid);
        // Запись могла быть найдена по нику (match_by_name) и лежать в базе под другим UUID
        final UUID alt = r != null && !r.getUuid().equals(uuid) ? r.getUuid() : null;
        dirty.remove(uuid);
        pendingUnload.remove(uuid);
        loadFailed.remove(uuid);
        knownUnregistered.add(uuid);
        if (alt != null) {
            cache.remove(alt);
            dirty.remove(alt);
            knownUnregistered.add(alt);
        }
        submitIo(() -> {
            try {
                backend.delete(uuid);
                if (alt != null) {
                    backend.delete(alt);
                }
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
        submitIo(this::flushDirty);
    }

    /** Сброс всех грязных записей в бэкенд. Выполняется ТОЛЬКО в IO-потоке. */
    private void flushDirty() {
        if (backend == null || storageDown) {
            return;
        }
        if (!dirty.isEmpty()) {
            List<AccountRecord> batch = new ArrayList<>();
            for (UUID uuid : new ArrayList<>(dirty)) {
                AccountRecord r = cache.get(uuid);
                dirty.remove(uuid);
                if (r != null) {
                    batch.add(r);
                }
            }
            if (!batch.isEmpty()) {
                try {
                    backend.saveBatch(batch);
                } catch (DuplicateAccountException e) {
                    onRegisterConflict(e.getConflicts());
                } catch (Throwable t) {
                    plugin.getLogger().severe("Ошибка записи аккаунтов (" + batch.size() + "): " + t.getMessage());
                    for (AccountRecord r : batch) {
                        dirty.add(r.getUuid()); // попробуем в следующий цикл
                    }
                }
            }
        }
        // Вышедшие игроки: запись сохранена — выгружаем из кэша
        if (!pendingUnload.isEmpty()) {
            for (UUID uuid : new ArrayList<>(pendingUnload)) {
                AccountRecord r = cache.get(uuid);
                if (active.contains(uuid)) {
                    pendingUnload.remove(uuid);
                } else if (r == null) {
                    pendingUnload.remove(uuid);
                } else if (!r.isDirty() && !r.isNew() && !dirty.contains(r.getUuid())) {
                    pendingUnload.remove(uuid);
                    dropCached(uuid, r);
                }
            }
        }
        purgeAbandoned();
    }

    private long lastPurge;

    /**
     * Отключились между пре-логином и входом (боты, обрыв) — PlayerQuitEvent не
     * приходит, и предзагруженные записи висели бы в памяти до рестарта.
     * Раз в минуту чистим то, что загружено давно и так и не зашло.
     */
    private void purgeAbandoned() {
        long now = System.currentTimeMillis();
        if (now - lastPurge < 60_000L) {
            return;
        }
        lastPurge = now;
        for (Map.Entry<UUID, Long> en : loadedAt.entrySet()) {
            UUID uuid = en.getKey();
            if (now - en.getValue() < 300_000L || active.contains(uuid)) {
                continue;
            }
            loadedAt.remove(uuid, en.getValue());
            knownUnregistered.remove(uuid);
            loadFailed.remove(uuid);
            AccountRecord r = cache.get(uuid);
            if (r != null && !r.isDirty() && !r.isNew() && !dirty.contains(r.getUuid())) {
                dropCached(uuid, r);
            }
        }
    }

    /**
     * Регистрация не записана: строка с этим UUID в базе уже есть (аккаунт не
     * прочитался из-за сбоя, и игроку предложили регистрацию). Чужую строку НЕ
     * трогаем, запись из кэша убираем, игрока отключаем.
     */
    private void onRegisterConflict(List<AccountRecord> conflicts) {
        for (AccountRecord r : conflicts) {
            UUID id = r.getUuid();
            plugin.getLogger().severe("Регистрация " + r.getName() + " (" + id + ") отклонена: аккаунт уже есть в базе."
                    + " Вероятен сбой чтения БД — существующая запись не тронута.");
            dirty.remove(id);
            cache.remove(id, r);
            knownUnregistered.remove(id);
            loadFailed.add(id);
            Scheduler.runSync(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    String reason = storageKickMessage();
                    Scheduler.runAtEntity(plugin, p, () -> p.kickPlayer(reason));
                }
            });
        }
    }

    /** Текст отказа при недоступном хранилище (lang: storage_unavailable). */
    private String storageKickMessage() {
        if (plugin instanceof me.vorchun.registerplugin.RegisterPlugin) {
            MessageService ms = ((me.vorchun.registerplugin.RegisterPlugin) plugin).getMessageService();
            if (ms != null) {
                String m = ms.message("storage_unavailable");
                if (m != null && !m.isEmpty()) {
                    return m;
                }
            }
        }
        return "§cХранилище аккаунтов временно недоступно. Попробуй зайти через минуту.";
    }

    /**
     * Собственный слушатель хранилища: предзагрузка на КАЖДОМ пре-логине
     * (не зависит от antibot.guard), отказ во входе при сбое чтения и выгрузка
     * кэша при выходе.
     */
    public final class Lifecycle implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPreLogin(AsyncPlayerPreLoginEvent e) {
            if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                return;
            }
            UUID uuid = e.getUniqueId();
            if (!storageDown && !recentlyLoaded(uuid)) {
                preload(uuid, e.getName());
            }
            if (isUnavailable(uuid)) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, storageKickMessage());
            }
        }

        /** Вход отклонён после пре-логина (вайтлист, бан, дубль сессии) — не держим запись. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onLoginDenied(PlayerLoginEvent e) {
            if (e.getResult() == PlayerLoginEvent.Result.ALLOWED) {
                return;
            }
            UUID uuid = e.getPlayer().getUniqueId();
            if (!active.contains(uuid)) {
                unload(uuid);
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onJoin(PlayerJoinEvent e) {
            active.add(e.getPlayer().getUniqueId());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent e) {
            unload(e.getPlayer().getUniqueId());
        }
    }

    /** Размер кэша без копирования (плейсхолдеры, /authadmin status). */
    public int cachedCount() {
        return cache.size();
    }

    /** Кэш онлайн-игроков (для /authadmin status). */
    public Map<UUID, AccountRecord> snapshotCached() {
        return Collections.unmodifiableMap(new java.util.HashMap<>(cache));
    }

    private static final int READY = 1124856758;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x100d) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x100d) == READY;
    }
}
