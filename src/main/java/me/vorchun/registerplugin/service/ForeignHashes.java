// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Проверка паролей по хешам ДРУГИХ плагинов — нужна только для миграции
 * (AuthMe, LoginSecurity, nLogin и т.п.). После первого успешного входа
 * AuthService перехеширует пароль в Argon2id, и этот код больше не используется.
 *
 * Поддерживаемые форматы:
 *   $SHA$salt$hash        — AuthMe SHA-256
 *   $SHA512$salt$hash     — AuthMe SHA-512
 *   $MD5$salt$hash        — AuthMe MD5
 *   $BCRYPT$hash, $2a$…   — BCrypt (AuthMe / LoginSecurity / nLogin)
 *   32 hex                — MD5 без соли (очень старые базы)
 *   64 hex                — SHA-256 без соли (nLogin legacy)
 *   40 / 128 hex          — SHA-1 / SHA-512 без соли
 *   $SHA256$соль$хеш      — nLogin
 *   $argon2i$ / $argon2d$ / $argon2id$ — Argon2 (nLogin, AuthMe, LibreLogin), v= необязателен
 *   pbkdf2_sha256$…       — AuthMe PBKDF2 / PBKDF2DJANGO
 *   $AM$<алгоритм>$соль$хеш — помечено при импорте AuthMe: MD5, SHA1, SHA256,
 *                           SHA512, DOUBLEMD5, SALTED2MD5, SALTEDSHA512, PBKDF2
 */
public final class ForeignHashes {

    private ForeignHashes() {
    }

    /** Похоже ли, что это не наш формат (pbkdf2_sha*$iters$salt$hash). */
    public static boolean isForeign(String stored) {
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        // Наш формат — pbkdf2_* и $argon2id$; $argon2i$/$argon2d$ (nLogin, AuthMe) — чужие
        return !stored.startsWith("pbkdf2_") && !stored.startsWith("$argon2id$");
    }

    /** Умеем ли проверять такой хеш. */
    public static boolean isSupported(String stored) {
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        String s = stored.trim();
        if (s.startsWith("$SHA$") || s.startsWith("$SHA256$") || s.startsWith("$SHA512$") || s.startsWith("$MD5$")) {
            return s.split("\\$").length == 4;
        }
        if (s.startsWith("$BCRYPT$") || s.startsWith("$2a$") || s.startsWith("$2b$") || s.startsWith("$2y$")) {
            return true;
        }
        if (s.startsWith("$argon2i$") || s.startsWith("$argon2d$") || s.startsWith("$argon2id$")) {
            return parseArgon(s) != null;
        }
        if (s.startsWith("$AM$")) {
            String[] p = s.split("\\$", 4);
            return p.length == 4 && AM_TAGS.contains(p[2]);
        }
        return isHex(s, 32) || isHex(s, 40) || isHex(s, 64) || isHex(s, 128);
    }

    /** Алгоритмы AuthMe, помеченные при импорте: $AM$<алгоритм>$<соль>$<хеш>. */
    private static final java.util.Set<String> AM_TAGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "MD5", "SHA1", "SHA256", "SHA512", "DOUBLEMD5", "SALTED2MD5", "SALTEDSHA512", "PBKDF2", "PBKDF2DJANGO"));

    /** $AM$…: алгоритм источника известен из его конфига (hex без префикса иначе не различить). */
    private static boolean verifyTagged(String password, String s) throws Exception {
        String[] p = s.split("\\$", 4); // ["", "AM", tag, остаток]
        if (p.length != 4) {
            return false;
        }
        String tag = p[2];
        if (tag.equals("PBKDF2") || tag.equals("PBKDF2DJANGO")) {
            return pbkdf2Foreign(password, p[3], tag.equals("PBKDF2DJANGO"));
        }
        int cut = p[3].indexOf('$');
        if (cut < 0) {
            return false;
        }
        String salt = p[3].substring(0, cut);
        String hash = p[3].substring(cut + 1);
        switch (tag) {
            case "MD5": return constantTimeEquals(md5Hex(password), hash);
            case "SHA1": return constantTimeEquals(hexDigest("SHA-1", password), hash);
            case "SHA256": return constantTimeEquals(sha256Hex(password), hash);
            case "SHA512": return constantTimeEquals(hexDigest("SHA-512", password), hash);
            case "DOUBLEMD5": return constantTimeEquals(hex(md5(md5Hex(password).getBytes(StandardCharsets.UTF_8))), hash);
            case "SALTED2MD5": return constantTimeEquals(hex(md5((md5Hex(password) + salt).getBytes(StandardCharsets.UTF_8))), hash);
            case "SALTEDSHA512": return constantTimeEquals(hexDigest("SHA-512", password + salt), hash);
            default: return false;
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 из AuthMe: pbkdf2_sha256$итерации$соль$хеш. Варианты
     * кодирования у AuthMe/форков/Django разные — проверяем все известные
     * (пароль тот же, подобрать хеш другим вариантом нельзя).
     */
    private static boolean pbkdf2Foreign(String password, String stored, boolean django) {
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].startsWith("pbkdf2_sha")) {
            return false;
        }
        String alg = p[0].equals("pbkdf2_sha512") ? "PBKDF2WithHmacSHA512"
                : p[0].equals("pbkdf2_sha1") ? "PBKDF2WithHmacSHA1" : "PBKDF2WithHmacSHA256";
        int it;
        try {
            it = Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (it < 1 || it > 2_000_000) {
            return false;
        }
        java.util.List<byte[]> salts = new java.util.ArrayList<>();
        salts.add(p[2].getBytes(StandardCharsets.UTF_8));
        if (!django && isHex(p[2], p[2].length()) && p[2].length() % 2 == 0) {
            salts.add(unhex(p[2]));
        }
        java.util.List<byte[]> expected = new java.util.ArrayList<>();
        if (isHex(p[3], p[3].length()) && p[3].length() % 2 == 0) {
            expected.add(unhex(p[3]));
        }
        try {
            expected.add(java.util.Base64.getDecoder().decode(p[3]));
        } catch (IllegalArgumentException ignored) {
        }
        boolean ok = false;
        for (byte[] salt : salts) {
            for (byte[] exp : expected) {
                if (exp.length < 16 || exp.length > 128) {
                    continue;
                }
                try {
                    javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance(alg);
                    byte[] got = f.generateSecret(new javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, it,
                            exp.length * 8)).getEncoded();
                    ok |= java.security.MessageDigest.isEqual(got, exp);
                } catch (Throwable ignored) {
                }
            }
        }
        return ok;
    }

    /** Argon2 i/d/id в формате PHC ($argon2i$v=19$m=…,t=…,p=…$соль$хеш; v может отсутствовать). */
    private static Object[] parseArgon(String s) {
        String[] parts = s.split("\\$");
        // ["", type, (v=..), params, salt, hash]
        int i = 2;
        int version = org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_10;
        if (parts.length == 6 && parts[2].startsWith("v=")) {
            try {
                version = Integer.parseInt(parts[2].substring(2)) >= 19
                        ? org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13
                        : org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_10;
            } catch (NumberFormatException e) {
                return null;
            }
            i = 3;
        } else if (parts.length != 5) {
            return null;
        }
        int type = "argon2i".equals(parts[1]) ? org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_i
                : "argon2d".equals(parts[1]) ? org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_d
                : "argon2id".equals(parts[1]) ? org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id : -1;
        if (type < 0) {
            return null;
        }
        int m = 0, t = 0, par = 0;
        for (String kv : parts[i].split(",")) {
            String[] x = kv.split("=", 2);
            if (x.length != 2) {
                return null;
            }
            try {
                int v = Integer.parseInt(x[1]);
                if ("m".equals(x[0])) {
                    m = v;
                } else if ("t".equals(x[0])) {
                    t = v;
                } else if ("p".equals(x[0])) {
                    par = v;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // Границы: битая/чужая строка с m=4 ГБ не должна уронить сервер OOM
        if (m < 8 || m > 262_144 || t < 1 || t > 64 || par < 1 || par > 16) {
            return null;
        }
        try {
            byte[] salt = java.util.Base64.getDecoder().decode(pad64(parts[i + 1]));
            byte[] hash = java.util.Base64.getDecoder().decode(pad64(parts[i + 2]));
            if (salt.length < 4 || hash.length < 4 || hash.length > 256) {
                return null;
            }
            return new Object[]{type, version, m, t, par, salt, hash};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean argonForeign(String password, String s) {
        Object[] a = parseArgon(s);
        if (a == null) {
            return false;
        }
        byte[] expected = (byte[]) a[6];
        byte[] actual = new byte[expected.length];
        org.bouncycastle.crypto.generators.Argon2BytesGenerator g = new org.bouncycastle.crypto.generators.Argon2BytesGenerator();
        g.init(new org.bouncycastle.crypto.params.Argon2Parameters.Builder((Integer) a[0])
                .withVersion((Integer) a[1])
                .withMemoryAsKB((Integer) a[2])
                .withIterations((Integer) a[3])
                .withParallelism((Integer) a[4])
                .withSalt((byte[]) a[5])
                .build());
        g.generateBytes(password.getBytes(StandardCharsets.UTF_8), actual);
        return java.security.MessageDigest.isEqual(actual, expected);
    }

    private static String pad64(String s) {
        int pad = (4 - s.length() % 4) % 4;
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < pad; i++) {
            sb.append('=');
        }
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hexDigest(String algorithm, String s) throws Exception {
        return hex(digest(algorithm, s.getBytes(StandardCharsets.UTF_8)));
    }

    public static boolean verify(String password, String stored) {
        if (password == null || stored == null || !ready()) {
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
            if (s.startsWith("$SHA512$") || s.startsWith("$SHA256$")) {
                // AuthMe / nLogin: основной вариант sha(sha(пароль) + соль); у форков
                // встречаются sha(пароль + соль) и sha(соль + пароль) — проверяем все
                String[] parts = s.split("\\$");
                if (parts.length != 4) {
                    return false;
                }
                String alg = s.startsWith("$SHA512$") ? "SHA-512" : "SHA-256";
                String salt = parts[2];
                return constantTimeEquals(authMe(password, salt, alg), parts[3])
                        | constantTimeEquals(hexDigest(alg, password + salt), parts[3])
                        | constantTimeEquals(hexDigest(alg, salt + password), parts[3]);
            }
            if (s.startsWith("$AM$")) {
                return verifyTagged(password, s);
            }
            if (s.startsWith("$argon2i$") || s.startsWith("$argon2d$") || s.startsWith("$argon2id$")) {
                return argonForeign(password, s);
            }
            if (s.startsWith("pbkdf2_")) {
                return pbkdf2Foreign(password, s, false) || pbkdf2Foreign(password, s, true);
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
            if (isHex(s, 40)) {
                return constantTimeEquals(hexDigest("SHA-1", password), s.toLowerCase(java.util.Locale.ROOT));
            }
            if (isHex(s, 128)) {
                return constantTimeEquals(hexDigest("SHA-512", password), s.toLowerCase(java.util.Locale.ROOT));
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

    private static final int READY = 1124856751;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1014) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1014) == READY;
    }
}
