// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.util.Scheduler;

/**
 * Двухфакторная аутентификация (TOTP, RFC 6238) — Google Authenticator,
 * Authy, Aegis и любые совместимые приложения.
 *
 * Сценарий:
 *   1. игрок вводит верный пароль;
 *   2. если у аккаунта включена 2FA, вход приостанавливается и игроку
 *      предлагается ввести 6-значный код (/2fa 123456);
 *   3. только после верного кода вызывается AuthService.completeLogin().
 *
 * Реализация без внешних библиотек: HMAC-SHA1 + Base32.
 */
public final class TotpService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int CODE_DIGITS = 6;
    private static final long PERIOD_SECONDS = 30L;

    private static final class Challenge {
        final String password;      // пароль уже проверен — держим, чтобы завершить вход
        final boolean fromChat;
        final long expiresAt;
        final int attempts;

        Challenge(String password, boolean fromChat, long expiresAt, int attempts) {
            this.password = password;
            this.fromChat = fromChat;
            this.expiresAt = expiresAt;
            this.attempts = attempts;
        }
    }

    private final JavaPlugin plugin;
    private final AccountStore accountStore;
    private final SecureRandom random = new SecureRandom();

    private final Map<UUID, Challenge> challenges = new ConcurrentHashMap<>();
    private final Map<UUID, Long> trustedUntil = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile int timeoutSeconds = 60;
    private volatile int maxAttempts = 4;

    public TotpService(JavaPlugin plugin, AccountStore accountStore) {
        this.plugin = plugin;
        this.accountStore = accountStore;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("twofactor.enabled", true);
        timeoutSeconds = Math.max(15, plugin.getConfig().getInt("twofactor.timeout_seconds", 60));
        maxAttempts = Math.max(1, plugin.getConfig().getInt("twofactor.max_attempts", 4));
        if (!enabled) {
            challenges.clear();
            trustedUntil.clear();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ---------- проверка кода ----------

    /** Совпадает ли введённый код с секретом (окно ±1 период — компенсация часов). */
    public boolean check(String secret, String input) {
        if (secret == null || input == null || !ready()) {
            return false;
        }
        String code = input.trim().replace(" ", "");
        if (code.length() != CODE_DIGITS) {
            return false;
        }
        byte[] key;
        try {
            key = base32Decode(secret);
        } catch (Throwable t) {
            return false;
        }
        long counter = System.currentTimeMillis() / 1000L / PERIOD_SECONDS;
        for (long offset = -1; offset <= 1; offset++) {
            String expected = generate(key, counter + offset);
            if (constantTimeEquals(expected, code)) {
                return true;
            }
        }
        return false;
    }

    private static String generate(byte[] key, long counter) {
        try {
            byte[] data = new byte[8];
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (counter & 0xFF);
                counter >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------- секреты ----------

    /** Новый секрет (16 символов base32 = 80 бит) для /2fa setup. */
    public String generateSecret() {
        byte[] raw = new byte[10];
        random.nextBytes(raw);
        return base32Encode(raw);
    }

    /** Ссылка для QR-кода: otpauth://totp/… (её же можно вставить в приложение вручную). */
    public String buildUrl(String playerName, String secret) {
        String issuer = plugin.getConfig().getString("twofactor.issuer", "Minecraft");
        try {
            String enc = java.net.URLEncoder.encode(issuer + ":" + playerName, "UTF-8");
            return "otpauth://totp/" + enc + "?secret=" + secret + "&issuer=" + issuer + "&digits=6&period=30";
        } catch (Throwable t) {
            return "otpauth://totp/" + playerName + "?secret=" + secret;
        }
    }

    /** Включить 2FA: игрок должен подтвердить кодом из приложения. */
    public void enable(Player player, String secret) {
        AccountRecord r = accountStore.get(player.getUniqueId());
        if (r != null) {
            r.setTotpSecret(secret);
            accountStore.markDirty(r);
        }
    }

    public void disable(Player player) {
        AccountRecord r = accountStore.get(player.getUniqueId());
        if (r != null) {
            r.setTotpSecret(null);
            accountStore.markDirty(r);
        }
        trustedUntil.remove(player.getUniqueId());
    }

    // ---------- челлендж входа ----------

    /** Пароль верный, но нужен код 2FA. */
    public void beginChallenge(Player player, String password, boolean fromChat) {
        UUID uuid = player.getUniqueId();
        challenges.put(uuid, new Challenge(password, fromChat,
                System.currentTimeMillis() + timeoutSeconds * 1000L, 0));
        me.vorchun.registerplugin.service.MessageService ms = messages();
        if (ms != null) {
            ms.send(player, "twofa_enter_code");
        }
    }

    public boolean hasChallenge(UUID uuid) {
        Challenge c = challenges.get(uuid);
        if (c == null) {
            return false;
        }
        if (System.currentTimeMillis() > c.expiresAt) {
            challenges.remove(uuid);
            return false;
        }
        return true;
    }

    /**
     * Игрок ввёл код. При успехе завершает вход (пароль уже проверен).
     * @return 0 — успех, 1 — неверный код, 2 — попытки исчерпаны/таймаут
     */
    public int submit(Player player, String code, AuthService authService) {
        UUID uuid = player.getUniqueId();
        Challenge c = challenges.get(uuid);
        if (c == null) {
            return 2;
        }
        if (System.currentTimeMillis() > c.expiresAt) {
            challenges.remove(uuid);
            return 2;
        }
        AccountRecord r = accountStore.get(uuid);
        if (r == null || !r.hasTotp()) {
            challenges.remove(uuid);
            authService.completeLogin(player, c.password);
            return 0;
        }
        if (check(r.getTotpSecret(), code)) {
            challenges.remove(uuid);
            trust(player);
            authService.completeLogin(player, c.password);
            return 0;
        }
        int attempts = c.attempts + 1;
        if (attempts >= maxAttempts) {
            challenges.remove(uuid);
            return 2;
        }
        challenges.put(uuid, new Challenge(c.password, c.fromChat, c.expiresAt, attempts));
        return 1;
    }

    public void cancelChallenge(UUID uuid) {
        challenges.remove(uuid);
    }

    // ---------- «доверенное устройство» (до выхода с сервера) ----------

    public boolean isTrusted(Player player) {
        Long until = trustedUntil.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public void trust(Player player) {
        int minutes = Math.max(0, plugin.getConfig().getInt("twofactor.trust_minutes", 0));
        if (minutes > 0) {
            trustedUntil.put(player.getUniqueId(),
                    System.currentTimeMillis() + minutes * 60_000L);
        }
    }

    public void forgetTrust(UUID uuid) {
        trustedUntil.remove(uuid);
    }

    private MessageService messages() {
        if (plugin instanceof me.vorchun.registerplugin.RegisterPlugin) {
            return ((me.vorchun.registerplugin.RegisterPlugin) plugin).getMessageService();
        }
        return null;
    }

    // ---------- base32 ----------

    private static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String s) {
        String in = s.trim().replace("=", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        int buffer = 0;
        int bitsLeft = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < in.length(); i++) {
            int v = BASE32.indexOf(in.charAt(i));
            if (v < 0) {
                continue;
            }
            buffer = (buffer << 5) | v;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    private static final int READY = 811582389;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1020) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1020) == READY;
    }
}
