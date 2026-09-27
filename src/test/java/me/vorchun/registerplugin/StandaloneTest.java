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
        testProxyBridge();
        testConfigMerge();
        testCidr();
        testPhase13();
        testAirFont();

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testPasswordHasher() {
        System.out.println("--- PasswordHasher ---");

        String hash = PasswordHasher.hash("TestPassword123");
        // Основной алгоритм — Argon2id ($argon2id$v=19$m=..,t=..,p=..$salt$hash),
        // при недоступности BouncyCastle — откат на pbkdf2_* (4 части)
        boolean argon = hash.startsWith("$argon2id$");
        check("hash format argon2id/pbkdf2",
                argon ? hash.split("\\$").length == 6 : hash.split("\\$").length == 4);
        check("hash algo argon2id", argon || hash.startsWith("pbkdf2_sha512$"));

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

    /** Прокси-модуль: протокол моста, разбор конфига, маршрут, лимиты. */
    private static void testProxyBridge() {
        System.out.println("--- Proxy bridge ---");
        java.util.UUID u = java.util.UUID.randomUUID();
        byte[] b = me.vorchun.registerplugin.proxy.BridgeProtocol.encode("TRUST", u, "Steve", "s3cret");
        me.vorchun.registerplugin.proxy.BridgeProtocol.Msg m = me.vorchun.registerplugin.proxy.BridgeProtocol.decode(b);
        long now = System.currentTimeMillis();
        check("bridge roundtrip", m != null && u.equals(m.uuid) && "Steve".equals(m.name));
        check("bridge hmac ok", me.vorchun.registerplugin.proxy.BridgeProtocol.verify(m, "s3cret", now));
        check("bridge wrong secret", !me.vorchun.registerplugin.proxy.BridgeProtocol.verify(m, "other", now));
        check("bridge empty secret rejects", !me.vorchun.registerplugin.proxy.BridgeProtocol.verify(m, "", now));
        check("bridge stale rejected", !me.vorchun.registerplugin.proxy.BridgeProtocol.verify(m, "s3cret", now + 60_000L));
        byte[] forged = me.vorchun.registerplugin.proxy.BridgeProtocol.encode("TRUST", u, "Steve", "");
        check("bridge unsigned rejected", !me.vorchun.registerplugin.proxy.BridgeProtocol.verify(
                me.vorchun.registerplugin.proxy.BridgeProtocol.decode(forged), "s3cret", now));
        check("bridge garbage", me.vorchun.registerplugin.proxy.BridgeProtocol.decode(new byte[]{1, 2, 3}) == null);
        final java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "vtr-proxy-test-" + now);
        me.vorchun.registerplugin.proxy.ProxyCore core = new me.vorchun.registerplugin.proxy.ProxyCore(
                new me.vorchun.registerplugin.proxy.ProxyCore.Platform() {
                    public void info(String s) { }
                    public void warn(String s) { }
                    public java.io.File dataDir() { return dir; }
                    public java.io.InputStream resource(String n) { return StandaloneTest.class.getResourceAsStream("/" + n); }
                });
        core.load();
        check("proxy cfg auth_servers", core.authServers().equals(java.util.Collections.singletonList("auth")));
        check("proxy cfg messages", core.msg("switch_denied").startsWith("§c"));
        check("route initial redirect", core.routeUnauthed("survival", false) == me.vorchun.registerplugin.proxy.ProxyCore.REDIRECT);
        check("route switch deny", core.routeUnauthed("survival", true) == me.vorchun.registerplugin.proxy.ProxyCore.DENY);
        check("route auth allow", core.routeUnauthed("AUTH", true) == me.vorchun.registerplugin.proxy.ProxyCore.ALLOW);
        check("label parse", "server".equals(me.vorchun.registerplugin.proxy.ProxyCore.label("/Server lobby")));
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (core.checkConnection("10.0.0.1", now + i) == null) allowed++;
        }
        check("per-ip limit 8/min", allowed == 8);
        String k = null;
        for (int i = 0; i < 40 && k == null; i++) {
            k = core.checkConnection("10.1.0." + i, now + 5000);
        }
        check("attack mode triggers", k != null);
        core.markAuthed(u, "10.2.0.1");
        check("verified ip passes attack", core.checkConnection("10.2.0.1", now + 5000) == null);
        check("new ip blocked in attack", core.checkConnection("10.3.0.1", now + 6000) != null);
    }

    /** Восстановление yml: удалённые ключи любой глубины возвращаются с #-описаниями. */
    @SuppressWarnings("unchecked")
    private static void testConfigMerge() {
        System.out.println("--- Config merge ---");
        try {
            java.util.List<String> def = java.util.Arrays.asList(
                    "antibot:",
                    "  enabled: true",
                    "  # описание банов",
                    "  bans:",
                    "    mode: auto",
                    "    # навсегда (по умолчанию выкл)",
                    "    permanent: false",
                    "  stages:",
                    "    fall: true",
                    "# верхний ключ",
                    "top2: 1");
            java.util.List<String> user = java.util.Arrays.asList(
                    "antibot:",
                    "    enabled: false",
                    "    bans:",
                    "        mode: manual",
                    "# комментарий следующей секции",
                    "other: 5");
            java.lang.reflect.Method m = Class.forName("me.vorchun.registerplugin.util.ConfigMerger")
                    .getDeclaredMethod("mergeLines", java.util.List.class, java.util.List.class);
            m.setAccessible(true);
            java.util.List<String> out = (java.util.List<String>) m.invoke(null, def, user);
            String text = String.join("\n", out);
            java.util.Map<String, Object> y = (java.util.Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(text);
            java.util.Map<String, Object> ab = (java.util.Map<String, Object>) y.get("antibot");
            java.util.Map<String, Object> bans = (java.util.Map<String, Object>) ab.get("bans");
            check("merge keeps user value", Boolean.FALSE.equals(ab.get("enabled")) && "manual".equals(bans.get("mode")));
            check("merge restores depth-3 key", Boolean.FALSE.equals(bans.get("permanent")));
            check("merge restores depth-2 section", ab.get("stages") instanceof java.util.Map);
            check("merge restores top key", Integer.valueOf(1).equals(y.get("top2")));
            check("merge keeps # descriptions", text.contains("# навсегда (по умолчанию выкл)"));
            check("merge uses user indent", text.contains("\n        permanent: false"));
            check("merge before next comment", text.indexOf("permanent") < text.indexOf("# комментарий следующей"));
        } catch (Throwable t) {
            check("config merge ran (" + t + ")", false);
        }
    }

    /** Фаза 13: устаревшие ключи, цвета 1.13, материалы, QR 2FA. */
    @SuppressWarnings("unchecked")
    private static void testPhase13() {
        System.out.println("--- phase 13 ---");
        try {
            java.util.List<String> cfg = new java.util.ArrayList<>(java.util.Arrays.asList(
                    "twofactor:",
                    "  enabled: true",
                    "  # Обязательная 2FA для админов: старое описание",
                    "  # вторая строка описания",
                    "  require_for_admins: true",
                    "  admin_permission: \"registerplugin.admin\"",
                    "afk:",
                    "  track_authed: true",
                    "  kick_after_login: false"));
            java.lang.reflect.Method d = Class.forName("me.vorchun.registerplugin.util.ConfigMerger")
                    .getDeclaredMethod("dropObsolete", java.util.List.class);
            d.setAccessible(true);
            boolean changed = (Boolean) d.invoke(null, cfg);
            String text = String.join("\n", cfg);
            check("obsolete keys dropped", changed && !text.contains("require_for_admins") && !text.contains("track_authed"));
            check("obsolete key comments dropped", !text.contains("старое описание") && !text.contains("вторая строка"));
            check("other keys kept", text.contains("admin_permission") && text.contains("kick_after_login")
                    && text.contains("  enabled: true"));
            check("obsolete drop idempotent", !(Boolean) d.invoke(null, cfg));
        } catch (Throwable t) {
            check("obsolete drop ran (" + t + ")", false);
        }
        try {
            java.lang.reflect.Method n = Class.forName("me.vorchun.registerplugin.util.LegacyColor")
                    .getDeclaredMethod("nearest", String.class);
            n.setAccessible(true);
            check("hex→legacy red", "§c".equals(n.invoke(null, "#FF5555")));
            check("hex→legacy dark green", "§2".equals(n.invoke(null, "#00AA00")));
            check("hex→legacy gold", "§6".equals(n.invoke(null, "#FFB000")));
        } catch (Throwable t) {
            check("legacy color ran (" + t + ")", false);
        }
        try {
            java.lang.reflect.Method e = Class.forName("me.vorchun.registerplugin.service.TwoFactorQr")
                    .getDeclaredMethod("encode", String.class);
            e.setAccessible(true);
            boolean[][] img = (boolean[][]) e.invoke(null,
                    "otpauth://totp/Minecraft:Vorchun_Long_Name?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Minecraft");
            int black = 0;
            for (boolean[] col : img) {
                for (boolean b : col) {
                    black += b ? 1 : 0;
                }
            }
            check("qr 128x128", img != null && img.length == 128 && img[0].length == 128);
            check("qr has modules", black > 2000 && black < 14000);
            check("qr quiet zone", !img[0][0] && !img[127][127]);
        } catch (Throwable t) {
            check("qr encode ran (" + t + ")", false);
        }
    }

    /** Список подсетей хостингов/VPN: слияние диапазонов и двоичный поиск. */
    private static void testCidr() {
        System.out.println("--- CIDR list ---");
        me.vorchun.registerplugin.util.CidrList l = me.vorchun.registerplugin.util.CidrList.parse(
                java.util.Arrays.asList("# comment", "10.0.0.0/8", "192.168.1.0/24", "192.168.1.128/25",
                        "1.2.3.4", "garbage", "5.6.7.0/33", "0.0.0.0/0"));
        check("cidr merged size", l.size() == 3);
        check("cidr contains /8", l.contains("10.200.3.4"));
        check("cidr contains /24", l.contains("192.168.1.200"));
        check("cidr contains single", l.contains("1.2.3.4"));
        check("cidr not neighbour", !l.contains("1.2.3.5") && !l.contains("192.168.2.1"));
        check("cidr /0 ignored", !l.contains("8.8.8.8"));
        check("cidr ipv6 not matched", !l.contains("::1"));
    }

    /** Шрифт капчи в воздухе: у каждого символа есть глиф, все глифы разные. */
    @SuppressWarnings("unchecked")
    private static void testAirFont() {
        System.out.println("--- Air captcha font ---");
        try {
            String chars = me.vorchun.registerplugin.util.BlockFont.CHARS;
            java.util.Map<Character, String[]> font = new java.util.HashMap<>();
            for (char ch : chars.toCharArray()) {
                font.put(ch, me.vorchun.registerplugin.util.BlockFont.glyph(ch));
            }
            boolean all = true;
            java.util.Set<String> shapes = new java.util.HashSet<>();
            for (char ch : chars.toCharArray()) {
                String[] g = font.get(ch);
                if (g == null || g.length != 5) {
                    all = false;
                    continue;
                }
                shapes.add(String.join("|", g));
            }
            check("air font covers all chars", all);
            check("air font glyphs unique", shapes.size() == chars.length());
        } catch (Throwable t) {
            check("air font test ran (" + t + ")", false);
        }
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
