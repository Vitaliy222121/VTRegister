// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.storage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import me.vorchun.registerplugin.service.AccountRecord;

/**
 * Файловое хранилище accounts.yml. Подходит для небольших серверов (до ~2000 аккаунтов).
 * Вся база держится в памяти; запись на диск — атомарная (через временный файл).
 *
 * Формат совместим с версиями 1.0.x–1.1.x: старые поля читаются как есть,
 * новые (registeredAt, totp, email) появляются по мере сохранения.
 */
public final class YamlStorage implements AccountStorage {

    private final File file;
    private final Logger log;
    private final Map<UUID, AccountRecord> records = new HashMap<>();
    private final Object lock = new Object();

    public YamlStorage(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "accounts.yml");
        this.log = log;
    }

    @Override
    public String name() {
        return "YAML";
    }

    /**
     * Файл успешно прочитан. Пока false, запись на диск запрещена: иначе
     * первый же flush() затёр бы нечитаемый accounts.yml пустой базой.
     */
    private volatile boolean loaded;
    /** mtime битого файла, с которого уже снята .broken-копия. */
    private long brokenBackedUp = -1L;

    @Override
    public void open() throws java.io.IOException {
        synchronized (lock) {
            records.clear();
            loaded = false;
            if (!file.exists()) {
                loaded = true;
                return;
            }
            YamlConfiguration cfg = me.vorchun.registerplugin.util.ConfigMerger
                    .loadYamlTolerant(file, null);
            if (cfg == null) {
                // Файл безнадёжно битый: уводим его в .broken-бэкап, чтобы
                // первая же запись не затёрла исходник — админ восстановит руками.
                // Повторные open() (ретрай AccountStore раз в 10 с) копию не плодят.
                if (brokenBackedUp == file.lastModified()) {
                    throw new java.io.IOException("accounts.yml не читается — исправь файл или восстанови из бэкапа");
                }
                brokenBackedUp = file.lastModified();
                try {
                    java.io.File bak = new java.io.File(file.getParentFile(),
                            "accounts.broken-" + System.currentTimeMillis() + ".yml");
                    java.nio.file.Files.copy(file.toPath(), bak.toPath());
                    log.warning("accounts.yml не читается — копия сохранена в " + bak.getName());
                } catch (Throwable t) {
                    log.warning("accounts.yml не читается и не копируется: " + t.getMessage());
                }
                // Не работаем на пустой базе: любой ник можно было бы зарегистрировать заново
                throw new java.io.IOException("accounts.yml не читается — исправь файл или восстанови из бэкапа");
            }
            ConfigurationSection root = cfg.getConfigurationSection("accounts");
            if (root == null) {
                loaded = true;
                return;
            }
            for (String key : root.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                String hash = s.getString("hash");
                if (hash == null || hash.isEmpty()) {
                    continue;
                }
                Map<String, Long> ipAuth = new HashMap<>();
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
                AccountRecord r = new AccountRecord(uuid,
                        s.getString("name", ""),
                        hash,
                        s.getLong("registeredAt", 0L),
                        s.getString("registeredIp", ""),
                        s.getString("lastIp", ""),
                        s.getLong("lastAuth", 0L),
                        ipAuth,
                        s.getString("totp", null),
                        s.getString("email", null),
                        s.getBoolean("emailVerified", false));
                records.put(uuid, r);
            }
            loaded = true;
            log.info("YAML: загружено аккаунтов: " + records.size());
        }
    }

    @Override
    public void close() {
        if (loaded) {
            try {
                flush();
            } catch (java.io.IOException e) {
                log.severe("Не удалось сохранить accounts.yml: " + e.getMessage());
            }
        }
    }

    @Override
    public AccountRecord load(UUID uuid) {
        if (!ready()) {
            return null;
        }
        synchronized (lock) {
            return records.get(uuid);
        }
    }

    @Override
    public AccountRecord loadByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        synchronized (lock) {
            for (AccountRecord r : records.values()) {
                if (r.getName().equalsIgnoreCase(name)) {
                    return r;
                }
            }
        }
        return null;
    }

    @Override
    public void save(AccountRecord record) throws java.io.IOException, DuplicateAccountException {
        saveBatch(java.util.Collections.singletonList(record));
    }

    /**
     * Пакетная запись: один flush на весь пакет (в разы меньше дисковых операций).
     * Новая регистрация поверх уже существующего UUID не записывается (конфликт).
     */
    @Override
    public void saveBatch(Collection<AccountRecord> batch) throws java.io.IOException, DuplicateAccountException {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        requireLoaded();
        List<AccountRecord> conflicts = null;
        synchronized (lock) {
            for (AccountRecord r : batch) {
                if (r.isNew()) {
                    AccountRecord cur = records.get(r.getUuid());
                    if (cur != null && cur != r) {
                        if (conflicts == null) {
                            conflicts = new ArrayList<>();
                        }
                        conflicts.add(r);
                        continue;
                    }
                    r.clearNew();
                }
                records.put(r.getUuid(), r);
            }
        }
        flush();
        if (conflicts != null) {
            throw new DuplicateAccountException(conflicts);
        }
    }

    @Override
    public void delete(UUID uuid) throws java.io.IOException {
        requireLoaded();
        synchronized (lock) {
            records.remove(uuid);
        }
        flush();
    }

    @Override
    public void deleteBatch(java.util.Collection<UUID> ids) throws java.io.IOException {
        requireLoaded();
        if (ids == null || ids.isEmpty()) {
            return;
        }
        synchronized (lock) {
            for (UUID u : ids) {
                records.remove(u);
            }
        }
        flush();
    }

    private void requireLoaded() throws java.io.IOException {
        if (!loaded) {
            throw new java.io.IOException("accounts.yml не загружен — запись запрещена");
        }
    }

    @Override
    public int count() {
        synchronized (lock) {
            return records.size();
        }
    }

    @Override
    public int countByRegisteredIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return 0;
        }
        int n = 0;
        synchronized (lock) {
            for (AccountRecord r : records.values()) {
                if (ip.equals(r.getRegisteredIp()) || ip.equals(r.getLastIp())) {
                    n++;
                }
            }
        }
        return n;
    }

    @Override
    public Collection<AccountRecord> list(int offset, int limit) {
        List<AccountRecord> all;
        synchronized (lock) {
            all = new ArrayList<>(records.values());
        }
        all.sort((a, b) -> a.getName().toLowerCase(Locale.ROOT).compareTo(b.getName().toLowerCase(Locale.ROOT)));
        int from = Math.max(0, Math.min(offset, all.size()));
        int to = Math.max(from, Math.min(all.size(), from + Math.max(0, limit)));
        return new ArrayList<>(all.subList(from, to));
    }

    @Override
    public Collection<AccountRecord> loadAll() {
        synchronized (lock) {
            return new ArrayList<>(records.values());
        }
    }

    /**
     * Полная перезапись файла. Пишем во временный файл и атомарно подменяем —
     * при падении сервера в момент записи старый accounts.yml останется целым.
     * Ошибка диска пробрасывается — AccountStore повторит запись в следующий цикл.
     */
    private void flush() throws java.io.IOException {
        YamlConfiguration cfg = new YamlConfiguration();
        ConfigurationSection root = cfg.createSection("accounts");
        synchronized (lock) {
            for (AccountRecord r : records.values()) {
                ConfigurationSection s = root.createSection(r.getUuid().toString());
                s.set("name", r.getName());
                s.set("hash", r.getPasswordHash());
                s.set("registeredAt", r.getRegisteredAt());
                s.set("registeredIp", r.getRegisteredIp());
                s.set("lastIp", r.getLastIp());
                s.set("lastAuth", r.getLastAuthMillis());
                for (Map.Entry<String, Long> e : r.getIpAuthMillis().entrySet()) {
                    s.set("ipAuth." + encodeIp(e.getKey()), e.getValue());
                }
                if (r.hasTotp()) {
                    s.set("totp", r.getTotpSecret());
                }
                if (r.getEmail() != null) {
                    s.set("email", r.getEmail());
                    s.set("emailVerified", r.isEmailVerified());
                }
                r.clearDirty();
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            File tmp = new File(file.getPath() + ".tmp");
            cfg.save(tmp);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            throw e;
        } catch (Exception e) {
            throw new java.io.IOException(e.getMessage(), e);
        }
    }

    // IP как ключ YAML небезопасен (точки, двоеточия) — кодируем base64url
    private static String encodeIp(String ip) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ip.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeIp(String key) {
        try {
            return new String(Base64.getUrlDecoder().decode(key), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return key; // очень старый формат — ключ был «сырым» IP
        }
    }

    private static final int READY = 1448549717;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1024) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1024) == READY;
    }
}
