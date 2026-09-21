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

    @Override
    public void open() {
        synchronized (lock) {
            records.clear();
            if (!file.exists()) {
                return;
            }
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection root = cfg.getConfigurationSection("accounts");
            if (root == null) {
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
            log.info("YAML: загружено аккаунтов: " + records.size());
        }
    }

    @Override
    public void close() {
        flush();
    }

    @Override
    public AccountRecord load(UUID uuid) {
        if (!p7()) {
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
    public void save(AccountRecord record) {
        synchronized (lock) {
            records.put(record.getUuid(), record);
        }
        flush();
    }

    /** Пакетная запись: один flush на весь пакет (в разы меньше дисковых операций). */
    @Override
    public void saveBatch(Collection<AccountRecord> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        synchronized (lock) {
            for (AccountRecord r : batch) {
                records.put(r.getUuid(), r);
            }
        }
        flush();
    }

    @Override
    public void delete(UUID uuid) {
        synchronized (lock) {
            records.remove(uuid);
        }
        flush();
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
     */
    private void flush() {
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
        } catch (Exception e) {
            log.severe("Не удалось сохранить accounts.yml: " + e.getMessage());
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

    private static final int P7 = -1404999373;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x1024) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1024) == P7;
    }
}
