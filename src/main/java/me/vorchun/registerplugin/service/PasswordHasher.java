// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import me.vorchun.registerplugin.util.Data;

import org.bukkit.plugin.java.JavaPlugin;

public final class PasswordHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MIN_ITERATIONS = 50_000;
    private static final int MAX_ITERATIONS = 2_000_000;
    private static final int MIN_SALT_BYTES = 16;
    private static final int MAX_SALT_BYTES = 64;
    private static final int MIN_HASH_BYTES = 32;
    private static final int MAX_HASH_BYTES = 128;

    private static volatile int configuredIterations = 200_000;
    private static volatile String configuredPepper = "";
    // Argon2id (основной алгоритм): память в KiB, итерации, параллелизм
    private static volatile String configuredAlgo = "argon2id";
    private static volatile int argonMemoryKiB = 65_536;
    private static volatile int argonIterations = 3;
    private static volatile int argonParallelism = 1;
    private static volatile int argonHashLen = 32;

    private static final int READY = 144336692











;

    static {
        if (Data.mix(0x1A2B) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }

    private PasswordHasher() {
    }

    private static boolean ready() {
        return Data.mix(0x1A2B) == READY;
    }

    private static char[] applyPepper(String password) {
        String p = configuredPepper;
        if (p == null || p.isEmpty()) {
            return password == null ? new char[0] : password.toCharArray();
        }
        String base = password == null ? "" : password;
        return (base + p).toCharArray();
    }

    public static void reload(JavaPlugin plugin) {
        if (plugin == null) {
            return;
        }
        int it = plugin.getConfig().getInt("security.pbkdf2_iterations", configuredIterations);
        if (it < MIN_ITERATIONS) {
            it = MIN_ITERATIONS;
        }
        if (it > MAX_ITERATIONS) {
            it = MAX_ITERATIONS;
        }
        configuredIterations = it;

        String pepper = plugin.getConfig().getString("security.password_pepper", "");
        configuredPepper = pepper == null ? "" : pepper;

        configuredAlgo = plugin.getConfig().getString("security.hash_algorithm", "argon2id")
                .trim().toLowerCase(java.util.Locale.ROOT);
        argonMemoryKiB = clamp(plugin.getConfig().getInt("security.argon2_memory_kib", 65_536), 8_192, 262_144);
        argonIterations = clamp(plugin.getConfig().getInt("security.argon2_iterations", 3), 1, 10);
        argonParallelism = clamp(plugin.getConfig().getInt("security.argon2_parallelism", 1), 1, 4);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static String hash(String password) {
        if ("argon2id".equals(configuredAlgo)) {
            try {
                return argonHash(password);
            } catch (Throwable t) {
                // BouncyCastle недоступен/сломан — откат на PBKDF2 (не теряем вход)
            }
        }
        return pbkdf2Hash(password);
    }

    // ---------- Argon2id ----------

    /** PHC-формат: $argon2id$v=19$m=65536,t=3,p=1$<b64 salt>$<b64 hash> */
    private static String argonHash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] out = new byte[argonHashLen];
        char[] secret = applyPepper(password);
        byte[] pw = toUtf8(secret);
        try {
            org.bouncycastle.crypto.generators.Argon2BytesGenerator g =
                    new org.bouncycastle.crypto.generators.Argon2BytesGenerator();
            g.init(new org.bouncycastle.crypto.params.Argon2Parameters.Builder(
                    org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
                    .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(argonMemoryKiB)
                    .withIterations(argonIterations)
                    .withParallelism(argonParallelism)
                    .withSalt(salt)
                    .build());
            g.generateBytes(pw, out);
        } finally {
            Arrays.fill(secret, '\0');
            Arrays.fill(pw, (byte) 0);
        }
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return "$argon2id$v=19$m=" + argonMemoryKiB + ",t=" + argonIterations
                + ",p=" + argonParallelism + "$" + b64.encodeToString(salt)
                + "$" + b64.encodeToString(out);
    }

    private static boolean argonVerify(String password, String stored) {
        // $argon2id$v=19$m=...,t=...,p=...$salt$hash
        String[] parts = stored.split("\\$");
        if (parts.length != 6 || !"argon2id".equals(parts[1])) {
            return false;
        }
        int mem = 0, it = 0, par = 0;
        for (String kv : parts[3].split(",")) {
            String[] p = kv.split("=", 2);
            if (p.length != 2) {
                return false;
            }
            try {
                int v = Integer.parseInt(p[1]);
                if ("m".equals(p[0])) {
                    mem = v;
                } else if ("t".equals(p[0])) {
                    it = v;
                } else if ("p".equals(p[0])) {
                    par = v;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (mem <= 0 || it <= 0 || par <= 0) {
            return false;
        }
        byte[] salt, expected;
        try {
            Base64.Decoder dec = Base64.getDecoder();
            // PHC пишет base64 без padding — восстанавливаем при декоде
            salt = dec.decode(pad64(parts[4]));
            expected = dec.decode(pad64(parts[5]));
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] actual = new byte[expected.length];
        char[] secret = applyPepper(password);
        byte[] pw = toUtf8(secret);
        try {
            org.bouncycastle.crypto.generators.Argon2BytesGenerator g =
                    new org.bouncycastle.crypto.generators.Argon2BytesGenerator();
            g.init(new org.bouncycastle.crypto.params.Argon2Parameters.Builder(
                    org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
                    .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(mem)
                    .withIterations(it)
                    .withParallelism(par)
                    .withSalt(salt)
                    .build());
            g.generateBytes(pw, actual);
        } finally {
            Arrays.fill(secret, '\0');
            Arrays.fill(pw, (byte) 0);
        }
        return constantTimeEquals(actual, expected);
    }

    private static String pad64(String s) {
        int pad = (4 - s.length() % 4) % 4;
        if (pad == 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < pad; i++) {
            sb.append('=');
        }
        return sb.toString();
    }

    private static byte[] toUtf8(char[] chars) {
        java.nio.ByteBuffer bb = java.nio.charset.StandardCharsets.UTF_8
                .encode(java.nio.CharBuffer.wrap(chars));
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        return out;
    }

    private static String pbkdf2Hash(String password) {
        int iterations = configuredIterations;
        byte[] salt = new byte[32];
        RANDOM.nextBytes(salt);
        Pbkdf2Algo algo = bestAvailableAlgo();
        char[] secret = applyPepper(password);
        byte[] hash;
        try {
            hash = pbkdf2(algo, secret, salt, iterations, 64);
        } finally {
            Arrays.fill(secret, '\0');
        }

        return algo.id + "$" + iterations + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean verify(String password, String stored) {
        if (stored == null) {
            return false;
        }

        if (stored.startsWith("$argon2id$")) {
            try {
                return argonVerify(password, stored);
            } catch (Throwable t) {
                return false;
            }
        }

        String[] parts = stored.split("\\$");

        Pbkdf2Algo algo;
        int iterations;
        byte[] salt;
        byte[] expected;

        if (parts.length == 4) {
            algo = Pbkdf2Algo.fromId(parts[0]);
            if (algo == null) {
                if (!"pbkdf2".equalsIgnoreCase(parts[0])) {
                    return false;
                }
                algo = Pbkdf2Algo.SHA256;
            }

            try {
                iterations = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return false;
            }

            if (!isValidIterations(iterations)) {
                return false;
            }

            try {
                salt = Base64.getDecoder().decode(parts[2]);
                expected = Base64.getDecoder().decode(parts[3]);
            } catch (IllegalArgumentException e) {
                return false;
            }

            if (!isValidSalt(salt) || !isValidHash(expected)) {
                return false;
            }

            char[] secret = applyPepper(password);
            byte[] actual;
            try {
                actual = pbkdf2(algo, secret, salt, iterations, expected.length);
            } finally {
                Arrays.fill(secret, '\0');
            }
            return constantTimeEquals(actual, expected);
        }

        return false;
    }

    /**
     * Проверка пароля по «своему» ИЛИ импортированному хешу.
     * Импортированные хеши (AuthMe/LoginSecurity/nLogin) поддерживаются только
     * для бесшовной миграции: после первого входа AuthService перехеширует пароль в PBKDF2.
     */
    public static boolean verifyAny(String password, String stored) {
        if (!ready() || !Data.sealed() || stored == null || stored.isEmpty()) {
            return false;
        }
        if (stored.startsWith("pbkdf2_") || stored.startsWith("$argon2")) {
            return verify(password, stored);
        }
        return ForeignHashes.verify(password, stored);
    }

    /** Импортированный (не наш) хеш? */
    public static boolean isForeign(String stored) {
        return ForeignHashes.isForeign(stored);
    }

    public static boolean needsRehash(String stored) {
        if (stored == null || stored.isEmpty()) {
            return false;
        }

        // Argon2id — наш актуальный формат: перехешируем при смене параметров
        if (stored.startsWith("$argon2id$")) {
            if (!"argon2id".equals(configuredAlgo)) {
                return false;
            }
            String want = "m=" + argonMemoryKiB + ",t=" + argonIterations + ",p=" + argonParallelism;
            return !stored.contains(want);
        }
        // PBKDF2-хеш под алгоритмом argon2id — апгрейд при первом входе
        if ("argon2id".equals(configuredAlgo)) {
            return stored.startsWith("pbkdf2_") || stored.startsWith("pbkdf2$");
        }

        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return false;
        }

        Pbkdf2Algo algo = Pbkdf2Algo.fromId(parts[0]);
        if (algo == null) {
            if (!"pbkdf2".equalsIgnoreCase(parts[0])) {
                return false;
            }
            algo = Pbkdf2Algo.SHA256;
        }

        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }

        if (!isValidIterations(iterations)) {
            return true;
        }

        byte[] salt;
        byte[] hash;
        try {
            salt = Base64.getDecoder().decode(parts[2]);
            hash = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }

        Pbkdf2Algo best = bestAvailableAlgo();
        if (algo != best) {
            return true;
        }
        if (iterations < configuredIterations) {
            return true;
        }
        if (!isValidSalt(salt)) {
            return true;
        }
        return !isValidHash(hash);
    }

    private static boolean isValidIterations(int iterations) {
        return iterations >= MIN_ITERATIONS && iterations <= MAX_ITERATIONS;
    }

    private static boolean isValidSalt(byte[] salt) {
        return salt != null && salt.length >= MIN_SALT_BYTES && salt.length <= MAX_SALT_BYTES;
    }

    private static boolean isValidHash(byte[] hash) {
        return hash != null && hash.length >= MIN_HASH_BYTES && hash.length <= MAX_HASH_BYTES;
    }

    private enum Pbkdf2Algo {
        SHA512("pbkdf2_sha512", "PBKDF2WithHmacSHA512"),
        SHA256("pbkdf2_sha256", "PBKDF2WithHmacSHA256"),
        SHA1("pbkdf2_sha1", "PBKDF2WithHmacSHA1");

        final String id;
        final String jce;

        Pbkdf2Algo(String id, String jce) {
            this.id = id;
            this.jce = jce;
        }

        static Pbkdf2Algo fromId(String id) {
            if (id == null) {
                return null;
            }
            for (Pbkdf2Algo a : values()) {
                if (a.id.equalsIgnoreCase(id)) {
                    return a;
                }
            }
            return null;
        }
    }

    private static Pbkdf2Algo bestAvailableAlgo() {
        try {
            SecretKeyFactory.getInstance(Pbkdf2Algo.SHA512.jce);
            return Pbkdf2Algo.SHA512;
        } catch (Exception ignored) {
        }

        try {
            SecretKeyFactory.getInstance(Pbkdf2Algo.SHA256.jce);
            return Pbkdf2Algo.SHA256;
        } catch (Exception ignored) {
        }

        return Pbkdf2Algo.SHA1;
    }

    private static byte[] pbkdf2(Pbkdf2Algo algo, char[] password, byte[] salt, int iterations, int keyLenBytes) {
        PBEKeySpec spec = null;
        try {
            spec = new PBEKeySpec(password, salt, iterations, keyLenBytes * 8);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(algo.jce);
            return skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось вычислить хэш пароля", e);
        } finally {
            if (spec != null) {
                try {
                    spec.clearPassword();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= (a[i] ^ b[i]);
        }
        return result == 0;
    }
}
