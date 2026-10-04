// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.net.ssl.SSLSocketFactory;

import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.util.Scheduler;

/**
 * Отправка писем через SMTP (SSL или STARTTLS) без внешних библиотек.
 *
 * Используется для:
 *  - подтверждения адреса при /email set;
 *  - восстановления пароля /recover (код на почту).
 *
 * Коды живут ограниченное время, ограничены по числу попыток,
 * письма уходят только в фоновом потоке.
 */
public final class MailService {

    private static final class Code {
        final String value;
        final long expiresAt;
        final String purpose; // "verify" | "recover"
        int attempts;

        Code(String value, long expiresAt, String purpose) {
            this.value = value;
            this.expiresAt = expiresAt;
            this.purpose = purpose;
        }
    }

    private final JavaPlugin plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Code> codes = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile String host;
    private volatile int port;
    private volatile String user;
    private volatile String password;
    private volatile String from;
    private volatile boolean ssl;
    private volatile boolean startTls;
    private volatile int codeMinutes;
    private volatile int maxPerMinute;
    /** Отметки отправлений за последнюю минуту (лимит SMTP-флуда). */
    private final java.util.Queue<Long> sentAt = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public MailService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("email.enabled", false);
        host = plugin.getConfig().getString("email.smtp.host", "smtp.example.com");
        port = plugin.getConfig().getInt("email.smtp.port", 465);
        user = plugin.getConfig().getString("email.smtp.user", "");
        password = plugin.getConfig().getString("email.smtp.password", "");
        from = plugin.getConfig().getString("email.smtp.from", user);
        ssl = plugin.getConfig().getBoolean("email.smtp.ssl", true);
        startTls = plugin.getConfig().getBoolean("email.smtp.starttls", false);
        applyProvider(plugin.getConfig().getString("email.provider", "custom"));
        // Адрес-заглушка из примера конфига — письма с него отклоняются
        if ((from == null || from.trim().isEmpty() || from.endsWith("example.com"))
                && user != null && user.indexOf('@') > 0) {
            from = user;
        }
        // Порт 587 — всегда STARTTLS (а не SSL сразу): частая ошибка настройки
        if (port == 587) {
            startTls = true;
        }
        if (startTls) {
            ssl = false;
        }
        codeMinutes = Math.max(1, plugin.getConfig().getInt("email.code_minutes", 10));
        maxPerMinute = Math.max(0, plugin.getConfig().getInt("limits.max_emails_per_minute", 5));
    }

    /**
     * Готовые настройки популярных почт (email.provider): host/port/SSL
     * подставляются сами. Эти сервисы принимают письма только от адреса,
     * под которым вошли, поэтому from = user.
     */
    private void applyProvider(String name) {
        String p = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        String h;
        String domain;
        switch (p) {
            case "gmail":
            case "google":
                h = "smtp.gmail.com";
                domain = "gmail.com";
                break;
            case "yandex":
            case "яндекс":
            case "ya":
                h = "smtp.yandex.ru";
                domain = "yandex.ru";
                break;
            case "mailru":
            case "mail.ru":
            case "mail":
                h = "smtp.mail.ru";
                domain = "mail.ru";
                break;
            default:
                return; // custom — всё вручную
        }
        host = h;
        port = 465;
        ssl = true;
        startTls = false;
        if (user != null && !user.trim().isEmpty()) {
            user = user.trim();
            if (user.indexOf('@') < 0) {
                user = user + "@" + domain; // логин без домена
            }
            from = user;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ---------- коды ----------

    public void sendCode(UUID uuid, String email, String purpose, String playerName, Consumer<Boolean> result) {
        if (!enabled) {
            result.accept(false);
            return;
        }
        // Чистим просроченные коды, чтобы мапа не росла
        long now = System.currentTimeMillis();
        codes.entrySet().removeIf(e -> now > e.getValue().expiresAt);
        // Лимит писем в минуту — защита SMTP-аккаунта от флуда
        if (maxPerMinute > 0) {
            long cutoff = now - 60_000L;
            Long head;
            while ((head = sentAt.peek()) != null && head < cutoff) {
                sentAt.poll();
            }
            if (sentAt.size() >= maxPerMinute) {
                plugin.getLogger().warning("MailService: лимит писем в минуту исчерпан (" + maxPerMinute + ")");
                result.accept(false);
                return;
            }
            sentAt.offer(now);
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        codes.put(uuid, new Code(code, System.currentTimeMillis() + codeMinutes * 60_000L, purpose));
        String subject = "verify".equals(purpose)
                ? "Подтверждение почты" : "Восстановление пароля";
        String body = "Привет, " + playerName + "!\n\n"
                + "Код: " + code + "\n"
                + "Код действует " + codeMinutes + " мин.\n\n"
                + "Если вы не запрашивали код — просто игнорируйте письмо.";
        Scheduler.runAsync(plugin, () -> {
            boolean ok = send(email, subject, body);
            Scheduler.runSync(plugin, () -> result.accept(ok));
        });
    }

    /**
     * @return 0 — код верный, 1 — неверный, 2 — истёк/нет кода, 3 — попытки исчерпаны
     */
    public int checkCode(UUID uuid, String input, String purpose) {
        Code c = codes.get(uuid);
        if (c == null || !c.purpose.equals(purpose)) {
            return 2;
        }
        if (System.currentTimeMillis() > c.expiresAt) {
            codes.remove(uuid);
            return 2;
        }
        if (input != null && c.value.equals(input.trim())) {
            codes.remove(uuid);
            return 0;
        }
        if (++c.attempts >= 5) {
            codes.remove(uuid);
            return 3;
        }
        return 1;
    }

    public void clearCode(UUID uuid) {
        codes.remove(uuid);
    }

    /** Выход игрока: код привязки почты снимаем, код восстановления живёт до истечения. */
    public void clearVerifyCode(UUID uuid) {
        codes.computeIfPresent(uuid, (k, c) -> "recover".equals(c.purpose) ? c : null);
    }

    public int codeMinutes() {
        return codeMinutes;
    }

    private volatile String lastError = "";

    /**
     * Проверочное письмо для админа (/authadmin testmail): результат — null
     * при успехе или текст ошибки SMTP (неверный пароль, порт, сертификат…).
     */
    public void sendTest(String to, Consumer<String> result) {
        if (!enabled) {
            result.accept("email.enabled: false — включи почту в config.yml");
            return;
        }
        Scheduler.runAsync(plugin, () -> {
            lastError = "";
            boolean ok = send(to, "VTRegister: проверка почты",
                    "Если ты читаешь это письмо — почта VTRegister настроена правильно.");
            String err = ok ? null : (lastError.isEmpty() ? "неизвестная ошибка" : lastError);
            Scheduler.runSync(plugin, () -> result.accept(err));
        });
    }

    // ---------- SMTP ----------

    private boolean send(String to, String subject, String body) {
        Socket socket = null;
        try {
            if (ssl) {
                socket = SSLSocketFactory.getDefault().createSocket();
                verifyHost((javax.net.ssl.SSLSocket) socket);
                socket.connect(new java.net.InetSocketAddress(host, port), 15_000);
                ((javax.net.ssl.SSLSocket) socket).startHandshake();
            } else {
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(host, port), 15_000);
            }
            socket.setSoTimeout(20_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);

            expect(in, "220");
            send(out, "EHLO vtregister");
            expect(in, "250");
            if (startTls && !ssl) {
                send(out, "STARTTLS");
                expect(in, "220");
                socket = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true);
                verifyHost((javax.net.ssl.SSLSocket) socket);
                ((javax.net.ssl.SSLSocket) socket).startHandshake();
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                send(out, "EHLO vtregister");
                expect(in, "250");
            }
            if (user != null && !user.isEmpty()) {
                send(out, "AUTH LOGIN");
                expect(in, "334");
                send(out, Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8)));
                expect(in, "334");
                send(out, Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
                expect(in, "235");
            }
            send(out, "MAIL FROM:<" + from + ">");
            expect(in, "250");
            send(out, "RCPT TO:<" + to + ">");
            expect(in, "250");
            send(out, "DATA");
            expect(in, "354");
            String data = "Date: " + new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z",
                    java.util.Locale.US).format(new java.util.Date()) + "\r\n"
                    + "From: " + from + "\r\n"
                    + "To: " + to + "\r\n"
                    + "Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=\r\n"
                    + "MIME-Version: 1.0\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n"
                    + "Content-Transfer-Encoding: 8bit\r\n\r\n"
                    + body.replace("\n", "\r\n").replace("\r\n.", "\r\n..")
                    + "\r\n.";
            send(out, data);
            expect(in, "250");
            send(out, "QUIT");
            return true;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            plugin.getLogger().warning("SMTP: не удалось отправить письмо: " + t.getMessage());
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void send(Writer out, String line) throws Exception {
        out.write(line + "\r\n");
        out.flush();
    }

    /**
     * Читает ответ сервера ЦЕЛИКОМ: многострочный ответ ("250-...", ..., "250 ...")
     * заканчивается строкой с пробелом после кода. Раньше чтение останавливалось
     * на первой строке, хвост EHLO доставался следующей команде — и AUTH на
     * Gmail/Яндексе/Mail.ru падал: письма не уходили вообще.
     */
    private void expect(BufferedReader in, String prefix) throws Exception {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.length() < 3) {
                continue;
            }
            boolean last = line.length() == 3 || line.charAt(3) != '-';
            if (!last) {
                continue;
            }
            if (line.startsWith(prefix)) {
                return;
            }
            throw new IllegalStateException("SMTP: " + line);
        }
        throw new IllegalStateException("SMTP: соединение закрыто");
    }

    /** Сертификат должен быть выдан именно этому серверу (защита от подмены). */
    private static void verifyHost(javax.net.ssl.SSLSocket s) {
        javax.net.ssl.SSLParameters p = s.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        s.setSSLParameters(p);
    }

    private static final int READY = -1251988160;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1017) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1017) == READY;
    }
}
