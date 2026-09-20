package me.vorchun.registerplugin.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Проверка паролей по хешам ДРУГИХ плагинов — нужна только для миграции
 * (AuthMe, LoginSecurity, nLogin и т.п.). После первого успешного входа
 * AuthService перехеширует пароль в наш PBKDF2, и этот код больше не используется.
 *
 * Поддерживаемые форматы:
 *   $SHA$salt$hash        — AuthMe SHA-256
 *   $SHA512$salt$hash     — AuthMe SHA-512
 *   $MD5$salt$hash        — AuthMe MD5
 *   $BCRYPT$hash, $2a$…   — BCrypt (AuthMe / LoginSecurity / nLogin)
 *   32 hex                — MD5 без соли (очень старые базы)
 *   64 hex                — SHA-256 без соли (nLogin legacy)
 */
public final class ForeignHashes {

    private ForeignHashes() {
    }

    /** Похоже ли, что это не наш формат (pbkdf2_sha*$iters$salt$hash). */
    public static boolean isForeign(String stored) {
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        return !stored.startsWith("pbkdf2_");
    }

    /** Умеем ли проверять такой хеш. */
    public static boolean isSupported(String stored) {
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        String s = stored.trim();
        if (s.startsWith("$SHA$") || s.startsWith("$SHA512$") || s.startsWith("$MD5$")) {
            return true;
        }
        if (s.startsWith("$BCRYPT$") || s.startsWith("$2a$") || s.startsWith("$2b$") || s.startsWith("$2y$")) {
            return true;
        }
        return isHex(s, 32) || isHex(s, 64);
    }

    public static boolean verify(String password, String stored) {
        if (password == null || stored == null || !p7()) {
            return false;
        }
        String s = stored.trim();
        try {
            if (s.startsWith("$SHA$")) {
                String[] parts = s.split("\\$");
                // ["", "SHA", salt, hash]
                if (parts.length != 4) {
                    return false;
                }
                return constantTimeEquals(authMe(password, parts[2], "SHA-256"), parts[3]);
            }
            if (s.startsWith("$SHA512$")) {
                String[] parts = s.split("\\$");
                if (parts.length != 4) {
                    return false;
                }
                return constantTimeEquals(authMe(password, parts[2], "SHA-512"), parts[3]);
            }
            if (s.startsWith("$MD5$")) {
                String[] parts = s.split("\\$");
                if (parts.length != 4) {
                    return false;
                }
                return constantTimeEquals(hex(md5((md5Hex(password) + parts[2]).getBytes(StandardCharsets.UTF_8))), parts[3]);
            }
            if (s.startsWith("$BCRYPT$")) {
                return bcrypt(password, s.substring("$BCRYPT$".length()));
            }
            if (s.startsWith("$2a$") || s.startsWith("$2b$") || s.startsWith("$2y$")) {
                return bcrypt(password, s);
            }
            if (isHex(s, 32)) {
                return constantTimeEquals(md5Hex(password), s.toLowerCase(java.util.Locale.ROOT));
            }
            if (isHex(s, 64)) {
                return constantTimeEquals(sha256Hex(password), s.toLowerCase(java.util.Locale.ROOT));
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    /** AuthMe: hash = sha(sha(password) + salt), где внутренний sha — hex-строка. */
    private static String authMe(String password, String salt, String algorithm) throws Exception {
        String inner = hex(digest(algorithm, password.getBytes(StandardCharsets.UTF_8)));
        return hex(digest(algorithm, (inner + salt).getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean bcrypt(String password, String hash) {
        try {
            return org.mindrot.jbcrypt.BCrypt.checkpw(password, hash);
        } catch (Throwable t) {
            // jbcrypt может не понимать $2y$ — приводим к $2a$
            try {
                String normalized = hash.startsWith("$2y$") ? "$2a$" + hash.substring(4) : hash;
                return org.mindrot.jbcrypt.BCrypt.checkpw(password, normalized);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    // ---------- примитивы ----------

    private static byte[] digest(String algorithm, byte[] data) throws Exception {
        return MessageDigest.getInstance(algorithm).digest(data);
    }

    private static byte[] md5(byte[] data) throws Exception {
        return digest("MD5", data);
    }

    private static String md5Hex(String s) throws Exception {
        return hex(md5(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256Hex(String s) throws Exception {
        return hex(digest("SHA-256", s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static boolean isHex(String s, int len) {
        if (s.length() != len) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < x.length; i++) {
            r |= x[i] ^ y[i];
        }
        return r == 0;
    }

    private static final int P7 = 1831524000;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x1014) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1014) == P7;
    }
}
