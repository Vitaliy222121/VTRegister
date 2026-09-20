package me.vorchun.registerplugin.service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

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

    private static final int P7 = 1831525535;

    static {
        if (Sec.t(0x1A2B) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }

    private PasswordHasher() {
    }

    private static boolean p7() {
        return Sec.t(0x1A2B) == P7;
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
    }

    public static String hash(String password) {
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
        if (!p7() || stored == null || stored.isEmpty()) {
            return false;
        }
        if (stored.startsWith("pbkdf2_")) {
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
