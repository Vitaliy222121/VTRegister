// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Запись аккаунта. Один объект на игрока, живёт в кэше AccountStore.
 * Все изменения помечают запись «грязной» — хранилище само решит, когда сбросить на диск/в БД.
 */
public final class AccountRecord {

    private final UUID uuid;
    private String name;
    private String passwordHash;
    private long registeredAt;
    private String registeredIp;
    private String lastIp;
    private long lastAuthMillis;
    private final Map<String, Long> ipAuthMillis = new HashMap<>();

    private String totpSecret;      // null — 2FA выключена
    private String email;           // null — почта не привязана
    private boolean emailVerified;

    private volatile boolean dirty;

    public AccountRecord(UUID uuid, String name, String passwordHash, String ip, long now) {
        this.uuid = uuid;
        this.name = name == null ? "" : name;
        this.passwordHash = passwordHash;
        this.registeredAt = now;
        this.registeredIp = ip == null ? "" : ip;
        this.lastIp = this.registeredIp;
        this.lastAuthMillis = now;
        if (!this.lastIp.isEmpty() && now > 0) {
            ipAuthMillis.put(this.lastIp, now);
        }
    }

    /** Полный конструктор для загрузки из хранилища. */
    public AccountRecord(UUID uuid, String name, String passwordHash, long registeredAt, String registeredIp,
                         String lastIp, long lastAuthMillis, Map<String, Long> ipAuth,
                         String totpSecret, String email, boolean emailVerified) {
        this.uuid = uuid;
        this.name = name == null ? "" : name;
        this.passwordHash = passwordHash;
        this.registeredAt = registeredAt;
        this.registeredIp = registeredIp == null ? "" : registeredIp;
        this.lastIp = lastIp == null ? "" : lastIp;
        this.lastAuthMillis = lastAuthMillis;
        if (ipAuth != null) {
            ipAuth.forEach((k, v) -> {
                if (k != null && !k.isEmpty() && v != null && v > 0) {
                    ipAuthMillis.put(k, v);
                }
            });
        }
        this.totpSecret = emptyToNull(totpSecret);
        this.email = emptyToNull(email);
        this.emailVerified = emailVerified;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    // ---------- идентификация ----------

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.trim().isEmpty() && !name.equals(this.name)) {
            this.name = name;
            dirty = true;
        }
    }

    // ---------- пароль ----------

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        dirty = true;
    }

    // ---------- регистрация / сессии по IP ----------

    public long getRegisteredAt() {
        return registeredAt;
    }

    public String getRegisteredIp() {
        return registeredIp;
    }

    public String getLastIp() {
        return lastIp;
    }

    public long getLastAuthMillis() {
        return lastAuthMillis;
    }

    public Map<String, Long> getIpAuthMillis() {
        return Collections.unmodifiableMap(ipAuthMillis);
    }

    public long getAuthMillisForIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return 0L;
        }
        Long v = ipAuthMillis.get(ip);
        return v == null ? 0L : v;
    }

    public void updateAuthForIp(String ip, long authMillis) {
        if (authMillis <= 0) {
            return;
        }
        this.lastAuthMillis = authMillis;
        if (ip != null && !ip.isEmpty()) {
            ipAuthMillis.put(ip, authMillis);
            this.lastIp = ip;
        }
        dirty = true;
    }

    /** Оставить только N самых свежих IP не старше maxAgeMillis (0 = без ограничения по времени). */
    public void pruneIpAuth(long now, long maxAgeMillis, int maxIps) {
        if (maxAgeMillis > 0) {
            ipAuthMillis.entrySet().removeIf(e -> now - e.getValue() > maxAgeMillis);
        }
        while (ipAuthMillis.size() > Math.max(1, maxIps)) {
            String oldest = null;
            long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : ipAuthMillis.entrySet()) {
                if (e.getValue() < oldestTs) {
                    oldestTs = e.getValue();
                    oldest = e.getKey();
                }
            }
            if (oldest == null) {
                break;
            }
            ipAuthMillis.remove(oldest);
        }
        dirty = true;
    }

    public void clearAuthIps() {
        ipAuthMillis.clear();
        this.lastAuthMillis = 0L;
        dirty = true;
    }

    // ---------- 2FA ----------

    public String getTotpSecret() {
        return totpSecret;
    }

    public boolean hasTotp() {
        return totpSecret != null && !totpSecret.isEmpty();
    }

    public void setTotpSecret(String totpSecret) {
        this.totpSecret = emptyToNull(totpSecret);
        dirty = true;
    }

    // ---------- email ----------

    public String getEmail() {
        return email;
    }

    public boolean isEmailVerified() {
        return emailVerified && email != null;
    }

    public void setEmail(String email, boolean verified) {
        this.email = emptyToNull(email);
        this.emailVerified = verified && this.email != null;
        dirty = true;
    }

    // ---------- грязный флаг ----------

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        dirty = true;
    }

    public void clearDirty() {
        dirty = false;
    }

    private static final int READY = -1596029182;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x100c) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x100c) == READY;
    }
}
