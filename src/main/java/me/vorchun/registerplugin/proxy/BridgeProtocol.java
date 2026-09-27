// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.proxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Протокол моста бэкенд ↔ прокси (канал {@link #CHANNEL}). Чистая Java —
 * один и тот же класс работает на Paper, Velocity и BungeeCord.
 *
 * Бэкенд → прокси: AUTH (игрок вошёл), LOGOUT (вышел из аккаунта).
 * Прокси → бэкенд: TRUST (игрок уже вошёл в сети на другом сервере).
 *
 * TRUST принимается бэкендом ТОЛЬКО с верной HMAC-подписью общего секрета:
 * бэкенд не отличает сообщение прокси от сообщения, подделанного клиентом.
 */
public final class BridgeProtocol {

    public static final String CHANNEL = "vtregister:main";
    public static final String AUTH = "AUTH";
    public static final String LOGOUT = "LOGOUT";
    public static final String TRUST = "TRUST";
    /** Допустимое расхождение часов бэкенда и прокси. */
    public static final long MAX_SKEW_MS = 30_000L;
    private static final int MAX_LEN = 1024;

    private BridgeProtocol() {
    }

    public static final class Msg {
        public final String type;
        public final UUID uuid;
        public final String name;
        public final long ts;
        public final String mac;

        Msg(String type, UUID uuid, String name, long ts, String mac) {
            this.type = type;
            this.uuid = uuid;
            this.name = name;
            this.ts = ts;
            this.mac = mac;
        }
    }

    public static byte[] encode(String type, UUID uuid, String name, String secret) {
        long ts = System.currentTimeMillis();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeUTF(type);
            out.writeUTF(uuid.toString());
            out.writeUTF(name == null ? "" : name);
            out.writeLong(ts);
            out.writeUTF(mac(secret, type, uuid, name, ts));
            return bos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /** @return null — мусор/чужой формат. */
    public static Msg decode(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_LEN) {
            return null;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            String type = in.readUTF();
            UUID uuid = UUID.fromString(in.readUTF());
            String name = in.readUTF();
            long ts = in.readLong();
            String mac = in.readUTF();
            return new Msg(type, uuid, name, ts, mac);
        } catch (Exception e) {
            return null;
        }
    }

    /** Подпись верна и свежая. Пустой секрет — всегда false. */
    public static boolean verify(Msg m, String secret, long now) {
        if (m == null || secret == null || secret.isEmpty()) {
            return false;
        }
        if (Math.abs(now - m.ts) > MAX_SKEW_MS) {
            return false;
        }
        String want = mac(secret, m.type, m.uuid, m.name, m.ts);
        return MessageDigest.isEqual(want.getBytes(StandardCharsets.US_ASCII),
                m.mac.getBytes(StandardCharsets.US_ASCII));
    }

    static String mac(String secret, String type, UUID uuid, String name, long ts) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        try {
            Mac h = Mac.getInstance("HmacSHA256");
            h.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = h.doFinal((type + '|' + uuid + '|' + (name == null ? "" : name) + '|' + ts)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
