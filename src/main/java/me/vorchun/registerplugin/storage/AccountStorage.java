// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.storage;

import java.util.Collection;
import java.util.UUID;

import me.vorchun.registerplugin.service.AccountRecord;

/**
 * Бэкенд хранения аккаунтов. Все методы блокирующие и потокобезопасные —
 * AccountStore вызывает их из фонового потока, никогда из главного.
 *
 * Реализации: YamlStorage (accounts.yml), SqlStorage (SQLite / MySQL / MariaDB / PostgreSQL).
 */
public interface AccountStorage {

    /** Человекочитаемое имя бэкенда для логов: "SQLite", "MySQL", "YAML". */
    String name();

    /** Открыть соединение / прочитать файл. Бросает исключение, если хранилище недоступно. */
    void open() throws Exception;

    void close();

    /** Загрузить запись по UUID или null. */
    AccountRecord load(UUID uuid) throws Exception;

    /** Найти запись по нику (без учёта регистра) или null. */
    AccountRecord loadByName(String name) throws Exception;

    /** Вставить или обновить запись целиком. */
    void save(AccountRecord record) throws Exception;

    /**
     * Пакетная запись. Для YAML — один flush на весь пакет вместо N перезаписей файла.
     * По умолчанию просто вызывает save() по очереди.
     */
    default void saveBatch(java.util.Collection<AccountRecord> records) throws Exception {
        for (AccountRecord r : records) {
            save(r);
        }
    }

    void delete(UUID uuid) throws Exception;

    /** Общее число аккаунтов. */
    int count() throws Exception;

    /** Сколько аккаунтов зарегистрировано с этого IP (по registeredIp). */
    int countByRegisteredIp(String ip) throws Exception;

    /**
     * Постраничный список для /authadmin list (отсортирован по нику).
     * @param offset с какой записи, @param limit сколько
     */
    Collection<AccountRecord> list(int offset, int limit) throws Exception;

    /** Все записи — для экспорта/миграции. На больших базах вызывать только из консоли. */
    Collection<AccountRecord> loadAll() throws Exception;

    int P7 = 1616536819;
    static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1021) == P7;
    }
}
