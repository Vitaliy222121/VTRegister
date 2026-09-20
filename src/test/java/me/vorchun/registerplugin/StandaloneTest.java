// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin;

import me.vorchun.registerplugin.service.PasswordHasher;
import me.vorchun.registerplugin.service.PasswordValidator;
import me.vorchun.registerplugin.service.PasswordValidator.ValidationResult;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Standalone-тест чисто-Java частей плагина.
 * Запуск без сервера: javac + java с classpath на собранный jar.
 */
public final class StandaloneTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("=== VTRegister standalone tests ===");

        testPasswordHasher();
        testPasswordValidator();
        testVersionParsing();
        testForeignHashes();
        testTotp();
        testTotpServiceBase32();

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testPasswordHasher() {
        System.out.println("--- PasswordHasher ---");

        String hash = PasswordHasher.hash("TestPassword123");
        check("hash format 4 parts", hash.split("\\$").length == 4);
        check("hash algo pbkdf2_sha512", hash.startsWith("pbkdf2_sha512$"));

        check("verify correct", PasswordHasher.verify("TestPassword123", hash));
        check("verify wrong", !PasswordHasher.verify("WrongPassword", hash));
        check("verify empty", !PasswordHasher.verify("", hash));
        check("verify null stored", !PasswordHasher.verify("x", null));
        check("verify garbage stored", !PasswordHasher.verify("x", "not_a_hash"));
        check("verify 3 parts", !PasswordHasher.verify("x", "a$b$c"));

        // tampered iterations (too low)
        check("verify low iters rejected",
                !PasswordHasher.verify("x", "pbkdf2_sha512$10$" + "AAAA$" + "BBBB"));
        // different salts produce different hashes
        String h2 = PasswordHasher.hash("TestPassword123");
        check("salt randomness", !hash.equals(h2));
        // needsRehash on good hash = false
        check("needsRehash good", !PasswordHasher.needsRehash(hash));
        // legacy algo needs rehash
        check("needsRehash legacy",
                PasswordHasher.needsRehash("pbkdf2_sha1$50000$" +
                        java.util.Base64.getEncoder().encodeToString(new byte[32]) + "$" +
                        java.util.Base64.getEncoder().encodeToString(new byte[64])));
    }

    private static void testPasswordValidator() {
        System.out.println("--- PasswordValidator ---");

        check("valid", PasswordValidator.validate("GoodPass1", 8, 64) == ValidationResult.VALID);
        check("null", PasswordValidator.validate(null, 8, 64) == ValidationResult.INVALID_NULL);
        check("empty", PasswordValidator.validate("", 8, 64) == ValidationResult.TOO_SHORT);
        check("short", PasswordValidator.validate("ab1", 8, 64) == ValidationResult.TOO_SHORT);
        check("long", PasswordValidator.validate(repeat("aB1", 30), 8, 64) == ValidationResult.TOO_LONG);
        check("whitespace", PasswordValidator.validate(" pass1234", 8, 64) == ValidationResult.INVALID_WHITESPACE);
        check("same chars weak", PasswordValidator.validate("aaaaaaaa", 8, 64) == ValidationResult.TOO_WEAK);
        check("only letters weak", PasswordValidator.validate("abcdefgh", 8, 64) == ValidationResult.TOO_WEAK);
        check("letters+digits ok", PasswordValidator.validate("abcd1234", 8, 64) == ValidationResult.VALID);

        // enforce_strength = false → слабый пароль проходит
        check("enforce off weak ok",
                PasswordValidator.validate("abcdefgh", 8, 64, false, null) == ValidationResult.VALID);
        // easy passwords list bypasses strength+min length
        Set<String> easy = new HashSet<>(Collections.singletonList("qwe"));
        check("easy list bypass",
                PasswordValidator.validate("qwe", 8, 64, true, easy) == ValidationResult.VALID);
        check("easy list no bypass other",
                PasswordValidator.validate("qwe2", 8, 64, true, easy) == ValidationResult.TOO_SHORT);
        // easy list doesn't bypass whitespace
        Set<String> easy2 = new HashSet<>(Collections.singletonList(" q"));
        check("easy list whitespace still bad",
                PasswordValidator.validate(" q", 8, 64, true, easy2) == ValidationResult.INVALID_WHITESPACE);
    }

    /**
     * Реплика логики Compat.parseVersion/isVersionAtLeast для проверки
     * на версиях 1.16.5, 1.21.x и новых 26.x
     */
    private static void testVersionParsing() {
        System.out.println("--- Version parsing (replica) ---");

        int[] v1165 = parseVersion("1.16.5");
        check("1.16.5 supported", atLeast(v1165, 1, 16, 5));
        check("1.16.5 <1.19", !atLeast(v1165, 1, 19, 0));

        int[] v12111 = parseVersion("1.21.11");
        check("1.21.11 supported", atLeast(v12111, 1, 16, 5));
        check("1.21.11 >=1.19", atLeast(v12111, 1, 19, 0));

        int[] v262 = parseVersion("26.2");
        check("26.2 supported", atLeast(v262, 1, 16, 5));
        check("26.2 >=1.19 (new scheme)", atLeast(v262, 1, 19, 0));

        int[] v263 = parseVersion("26.3");
        check("26.3 supported", atLeast(v263, 1, 16, 5));

        // версия без патча "1.20"
        int[] v120 = parseVersion("1.20");
        check("1.20 supported", atLeast(v120, 1, 16, 5));

        // старая версия 1.15 — не поддерживается
        int[] v115 = parseVersion("1.15.2");
        check("1.15.2 NOT supported", !atLeast(v115, 1, 16, 5));
    }

    /** Импортированные хеши (AuthMe / LoginSecurity / nLogin). */
    private static void testForeignHashes() {
        System.out.println("--- ForeignHashes (миграция) ---");

        // AuthMe SHA-256: $SHA$salt$hash, hash = sha256(sha256(pw) + salt)
        String salt = "a1b2c3d4";
        String inner = sha256Hex("SecretPass1");
        String outer = sha256Hex(inner + salt);
        String authMe = "$SHA$" + salt + "$" + outer;
        check("authme sha256 verify", me.vorchun.registerplugin.service.ForeignHashes.verify("SecretPass1", authMe));
        check("authme sha256 wrong pw", !me.vorchun.registerplugin.service.ForeignHashes.verify("OtherPass", authMe));

        // MD5 без соли (очень старые базы)
        check("legacy md5 verify", me.vorchun.registerplugin.service.ForeignHashes.verify("abc", md5Hex("abc")));
        check("legacy md5 wrong", !me.vorchun.registerplugin.service.ForeignHashes.verify("abd", md5Hex("abc")));

        // SHA-256 без соли (nLogin legacy)
        check("legacy sha256 verify", me.vorchun.registerplugin.service.ForeignHashes.verify("abc", sha256Hex("abc")));

        // Определение «чужого» и «своего» формата
        check("isForeign true", me.vorchun.registerplugin.service.ForeignHashes.isForeign(authMe));
        check("isForeign false", !me.vorchun.registerplugin.service.ForeignHashes.isForeign(
                PasswordHasher.hash("x")));
        check("verifyAny pbkdf2", PasswordHasher.verifyAny("Hello123", PasswordHasher.hash("Hello123")));
        check("verifyAny authme", PasswordHasher.verifyAny("SecretPass1", authMe));
    }

    /** TOTP: сверка с тест-вектором RFC 6238 (секрет «12345678901234567890»). */
    private static void testTotp() {
        System.out.println("--- TOTP (RFC 6238) ---");
        // Базовая проверка формата и стабильности кода
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"; // base32("12345678901234567890")
        check("totp code length", totpCode(secret, 59L).length() == 6);
        check("totp same window equal", totpCode(secret, 59L).equals(totpCode(secret, 60L - 1L)));
        check("totp different window differs", !totpCode(secret, 59L).equals(totpCode(secret, 120L)));
        check("totp matches RFC vector 59s", totpCode(secret, 59L).equals("287082"));
    }

    private static void testTotpServiceBase32() {
        System.out.println("--- TOTP base32/секреты ---");
        try {
            java.lang.reflect.Method encode = Class.forName("me.vorchun.registerplugin.service.TotpService")
                    .getDeclaredMethod("base32Encode", byte[].class);
            encode.setAccessible(true);
            java.lang.reflect.Method decode = Class.forName("me.vorchun.registerplugin.service.TotpService")
                    .getDeclaredMethod("base32Decode", String.class);
            decode.setAccessible(true);
            byte[] data = "12345678901234567890".getBytes("UTF-8");
            String encoded = (String) encode.invoke(null, (Object) data);
            byte[] back = (byte[]) decode.invoke(null, encoded);
            check("base32 roundtrip", java.util.Arrays.equals(data, back));
            check("base32 known value", encoded.equals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"));
        } catch (Throwable t) {
            check("base32 tests", false);
        }
    }

    private static String totpCode(String base32Secret, long epochSeconds) {
        try {
            Class<?> cls = Class.forName("me.vorchun.registerplugin.service.TotpService");
            java.lang.reflect.Method decode = cls.getDeclaredMethod("base32Decode", String.class);
            decode.setAccessible(true);
            byte[] key = (byte[]) decode.invoke(null, base32Secret);
            java.lang.reflect.Method generate = cls.getDeclaredMethod("generate", byte[].class, long.class);
            generate.setAccessible(true);
            long counter = epochSeconds / 30L;
            return (String) generate.invoke(null, key, counter);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return hex(md.digest(s.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }

    private static String md5Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            return hex(md.digest(s.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static int[] parseVersion(String version) {
        try {
            String v = version.split("-")[0];
            String[] parts = v.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return new int[]{major, minor, patch};
        } catch (Exception e) {
            return new int[]{1, 16, 5};
        }
    }

    private static boolean atLeast(int[] v, int major, int minor, int patch) {
        if (v[0] > major) return true;
        if (v[0] < major) return false;
        if (v[1] > minor) return true;
        if (v[1] < minor) return false;
        return v[2] >= patch;
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }
}
