// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.util.IpUtil;

/**
 * Журнал входов (security.login_log) и оповещения о входе админа с нового IP
 * в Discord / Telegram (security.admin_alerts). Всё пишется и отправляется
 * в отдельном фоновом потоке — вход игрока не ждёт ни диска, ни сети.
 */
public final class SecurityAudit {

    private final JavaPlugin plugin;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VTRegister-Audit");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean logEnabled = true;
    private volatile int keepDays = 30;
    private volatile boolean alertsEnabled;
    private volatile String adminPermission = "registerplugin.admin";
    private volatile boolean alertOnFirstLogin;
    private volatile String discordWebhook = "";
    private volatile String telegramToken = "";
    private volatile String telegramChat = "";
    private volatile String serverName = "server";

    public SecurityAudit(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        logEnabled = plugin.getConfig().getBoolean("security.login_log.enabled", true);
        keepDays = Math.max(1, plugin.getConfig().getInt("security.login_log.keep_days", 30));
        alertsEnabled = plugin.getConfig().getBoolean("security.admin_alerts.enabled", false);
        adminPermission = plugin.getConfig().getString("security.admin_alerts.permission", "registerplugin.admin");
        alertOnFirstLogin = plugin.getConfig().getBoolean("security.admin_alerts.on_first_login", false);
        discordWebhook = plugin.getConfig().getString("security.admin_alerts.discord_webhook", "").trim();
        telegramToken = plugin.getConfig().getString("security.admin_alerts.telegram_bot_token", "").trim();
        telegramChat = plugin.getConfig().getString("security.admin_alerts.telegram_chat_id", "").trim();
        serverName = plugin.getConfig().getString("security.admin_alerts.server_name", "server");
        io.execute(this::cleanupOld);
    }

    public void shutdown() {
        io.shutdown();
    }

    /** Успешный вход (любой путь). prevIp — прошлый IP аккаунта до этого входа. */
    public void onLogin(Player p, String prevIp) {
        String ip = IpUtil.getIp(plugin, p);
        write("OK    " + p.getName() + " " + p.getUniqueId() + " ip=" + ip
                + (prevIp != null && !prevIp.isEmpty() && !prevIp.equals(ip) ? " (прошлый " + prevIp + ")" : ""));
        if (!alertsEnabled || !isAdmin(p) || ip.isEmpty()) {
            return;
        }
        boolean newIp = prevIp == null || prevIp.isEmpty() ? alertOnFirstLogin : !prevIp.equals(ip);
        if (newIp) {
            String text = "⚠ [" + serverName + "] Вход админа " + p.getName() + " с НОВОГО IP " + ip
                    + (prevIp == null || prevIp.isEmpty() ? "" : " (раньше " + prevIp + ")");
            io.execute(() -> sendAlerts(text));
        }
    }

    /** Неудачная попытка (неверный пароль / код 2FA). */
    public void onFail(Player p, String reason) {
        write("FAIL  " + p.getName() + " " + p.getUniqueId() + " ip=" + IpUtil.getIp(plugin, p) + " " + reason);
    }

    public boolean isAdmin(Player p) {
        return p.isOp() || (adminPermission != null && !adminPermission.isEmpty() && p.hasPermission(adminPermission));
    }

    // ------------------------------------------------------------------

    private File dir() {
        return new File(plugin.getDataFolder(), "logs/logins");
    }

    private void write(String line) {
        if (!logEnabled) {
            return;
        }
        final String stamped = new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + line + "\n";
        final String day = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        io.execute(() -> {
            try {
                File d = dir();
                d.mkdirs();
                Files.write(new File(d, day + ".log").toPath(), stamped.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
            }
        });
    }

    private void cleanupOld() {
        File[] files = dir().listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - keepDays * 86_400_000L;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".log") && f.lastModified() < cutoff) {
                f.delete();
            }
        }
    }

    private void sendAlerts(String text) {
        if (!discordWebhook.isEmpty()) {
            post(discordWebhook, "application/json",
                    "{\"content\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        }
        if (!telegramToken.isEmpty() && !telegramChat.isEmpty()) {
            try {
                post("https://api.telegram.org/bot" + telegramToken + "/sendMessage",
                        "application/x-www-form-urlencoded",
                        "chat_id=" + URLEncoder.encode(telegramChat, "UTF-8")
                                + "&text=" + URLEncoder.encode(text, "UTF-8"));
            } catch (Throwable ignored) {
            }
        }
    }

    private void post(String url, String type, String body) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", type + "; charset=utf-8");
            c.setRequestProperty("User-Agent", "VTRegister");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code >= 300) {
                plugin.getLogger().warning("Оповещение админам не отправлено: HTTP " + code
                        + " (" + (url.contains("telegram") ? "Telegram" : "Discord") + ")");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Оповещение админам не отправлено: " + t.getMessage());
        }
    }
}
