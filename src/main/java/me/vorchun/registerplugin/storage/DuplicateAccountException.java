// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import me.vorchun.registerplugin.service.AccountRecord;

/**
 * Новая запись (регистрация) не вставлена: строка с таким UUID уже есть в базе.
 * Значит, при регистрации аккаунт не был прочитан (сбой БД) — перезаписывать
 * чужую строку нельзя. Остальные записи пакета при этом сохранены.
 */
public final class DuplicateAccountException extends Exception {

    private static final long serialVersionUID = 1L;

    private final List<AccountRecord> conflicts;

    public DuplicateAccountException(Collection<AccountRecord> conflicts) {
        super("Аккаунт уже существует в базе: " + conflicts.size());
        this.conflicts = new ArrayList<>(conflicts);
    }

    public List<AccountRecord> getConflicts() {
        return conflicts;
    }
}
