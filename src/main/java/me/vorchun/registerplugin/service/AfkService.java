// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.listener.AntiBotGuard;
import me.vorchun.registerplugin.util.Compat;
import me.vorchun.registerplugin.util.IpUtil;
import me.vorchun.registerplugin.util.Scheduler;

/**
 * Двухуровневая AFK-защита + детект макросов для неавторизованных игроков.
 *
 * Уровень 1 (спавн): не двигался первые grace секунд → обратный отсчёт
 * в чат и actionbar, по истечении timeout — кик.
 * Уровень 2 (очередь): нет полезной активности queue_idle секунд → кик.
 *
 * Анти-макро:
 *   - прыжок на месте (|dx|,|dz| < move_delta) НЕ сбрасывает AFK-таймер;
 *   - линейная камера: за окно в camera_window тиков дельты yaw/pitch с
 *     почти нулевым стандартным отклонением → страйк; strikes → кик.
 *     Для Bedrock (Floodgate) допуск смягчается на bedrock_multiplier.
 *
 * Осадной режим (siege): если afk-киков за окно >= threshold — на
 * duration секунд вход лимитируется (max_pending неавторизованных),
 * лишние заходы кикаются с вежливым сообщением.
 *
 * Параллельно сервис ведёт BossBar #2 (AFK-предупреждение) и BossBar #3
 * (ротация инфо-сообщений из конфига) для неавторизованных игроков.
 * BossBar #1 (прогресс очереди/этапа) живёт в AntiBotService.
 */
public final class AfkService implements Listener {

    private static final int RING_CAP = 64;
    private static final float LOOK_EPS = 0.05f;

    private final JavaPlugin plugin;
    private final SessionManager sessions;
    private final AntiBotService antiBot;
    private final BedrockSupportService bedrock;
    private final MessageService messages;
    private volatile AntiBotGuard guard;

    private final Map<UUID, Tr> tracked = new ConcurrentHashMap<>();
    private final Deque<Long> recentKicks = new ArrayDeque<>();
    private volatile long siegeUntil;
    private volatile Scheduler.Task ticker;
    private int infoIndex;
    private int tickCount;

    // --- конфиг ---
    private volatile boolean enabled;
    private volatile long graceMs, timeoutMs, queueIdleMs, ipBanMs;
    private volatile boolean ipBanOnKick;
    private volatile int ipBanStrikes;
    private final Map<String, long[]> ipStrikes = new ConcurrentHashMap<>();
    private volatile double moveDeltaSq, camEps, camMinMean, bedrockMult;
    private volatile int camWindow, camStrikes;
    private volatile boolean siegeEnabled;
    private volatile int siegeThreshold, siegeMaxPending;
    private volatile long siegeWindowMs, siegeDurationMs;
    private volatile boolean infoEnabled;
    private volatile int infoIntervalS;
    private volatile BarColor infoColor;
    private volatile List<String> infoMsgs = new ArrayList<>();
    private volatile boolean afkBarEnabled;
    private volatile boolean specEnabled;
    private volatile long specTimeoutMs;

    private static final class Tr {
        final UUID uuid;
        volatile long lastActive;
        final float[] dYaw;
        final float[] dPitch;
        int bufIdx, bufCount, strikes;
        boolean bedrock;
        volatile boolean spectating;
        volatile long specSince;
        volatile org.bukkit.GameMode prevMode;
        BossBar afkBar, infoBar;

        Tr(UUID uuid, int window, boolean bedrock) {
            this.uuid = uuid;
            dYaw = new float[Math.min(window, RING_CAP)];
            dPitch = new float[Math.min(window, RING_CAP)];
            lastActive = System.currentTimeMillis();
            this.bedrock = bedrock;
        }
    }

    public AfkService(JavaPlugin plugin, SessionManager sessions, AntiBotService antiBot,
                      BedrockSupportService bedrock, MessageService messages) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.antiBot = antiBot;
        this.bedrock = bedrock;
        this.messages = messages;
        reload();
    }

    public void setGuard(AntiBotGuard guard) {
        this.guard = guard;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("afk.enabled", true);
        graceMs = Math.max(1, plugin.getConfig().getInt("afk.initial_grace_seconds", 5)) * 1000L;
        timeoutMs = Math.max(graceMs / 1000 + 1,
                plugin.getConfig().getInt("afk.initial_timeout_seconds", 15)) * 1000L;
        queueIdleMs = Math.max(30, plugin.getConfig().getInt("afk.queue_idle_seconds", 300)) * 1000L;
        double md = Math.max(0.05, plugin.getConfig().getDouble("afk.move_block_delta", 0.2));
        moveDeltaSq = md * md;
        camWindow = Math.max(8, Math.min(RING_CAP, plugin.getConfig().getInt("afk.camera_window", 20)));
        camEps = Math.max(0.0001, plugin.getConfig().getDouble("afk.camera_stddev_max", 0.01));
        camMinMean = Math.max(0.1, plugin.getConfig().getDouble("afk.camera_min_mean_delta", 1.0));
        camStrikes = Math.max(1, plugin.getConfig().getInt("afk.camera_strikes", 2));
        bedrockMult = Math.max(1.0, plugin.getConfig().getDouble("afk.bedrock_multiplier", 3.0));
        ipBanMs = Math.max(0, plugin.getConfig().getInt("afk.ip_ban_minutes", 15)) * 60_000L;
        ipBanOnKick = plugin.getConfig().getBoolean("afk.ip_ban_on_kick", true);
        ipBanStrikes = Math.max(1, plugin.getConfig().getInt("afk.ip_ban_strikes", 2));
        specEnabled = plugin.getConfig().getBoolean("afk.spectator_grace.enabled", true);
        specTimeoutMs = Math.max(10, plugin.getConfig().getInt("afk.spectator_grace.timeout_seconds", 60)) * 1000L;
        afkBarEnabled = plugin.getConfig().getBoolean("afk.bossbar", true);

        siegeEnabled = plugin.getConfig().getBoolean("afk.siege.enabled", true);
        siegeThreshold = Math.max(2, plugin.getConfig().getInt("afk.siege.kicks_threshold", 8));
        siegeWindowMs = Math.max(10, plugin.getConfig().getInt("afk.siege.window_seconds", 60)) * 1000L;
        siegeDurationMs = Math.max(10, plugin.getConfig().getInt("afk.siege.duration_seconds", 180)) * 1000L;
        siegeMaxPending = Math.max(1, plugin.getConfig().getInt("afk.siege.max_pending", 8));

        infoEnabled = plugin.getConfig().getBoolean("info_bar.enabled", true);
        infoIntervalS = Math.max(2, plugin.getConfig().getInt("info_bar.interval_seconds", 8));
        infoColor = barColor(plugin.getConfig().getString("info_bar.color", "PURPLE"));
        List<String> ms = plugin.getConfig().getStringList("info_bar.messages");
        List<String> clean = new ArrayList<>();
        for (String m : ms) {
            if (m != null && !m.trim().isEmpty()) {
                clean.add(m);
            }
        }
        infoMsgs = clean;

        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        if (enabled) {
            ticker = Scheduler.runSyncTimer(plugin, this::tick, 20L, 20L);
        }
    }

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Map.Entry<UUID, Tr> e : tracked.entrySet()) {
            Tr t = e.getValue();
            if (t.spectating) {
                Player p = Bukkit.getPlayer(e.getKey());
                org.bukkit.GameMode back = t.prevMode != null
                        ? t.prevMode : org.bukkit.GameMode.SURVIVAL;
                if (p != null && p.isOnline()
                        && p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                    try {
                        p.setGameMode(back);
                    } catch (Throwable ignored) {
                    }
                }
            }
            removeBars(t);
        }
        tracked.clear();
        ipStrikes.clear();
    }

    // ---------- вход/выход ----------

    public void onJoin(Player p) {
        if (!enabled || p == null || sessions.isLoggedIn(p.getUniqueId())) {
            return;
        }
        // Осадной режим: вход лимитирован — лишних вежливо кикаем
        if (isSiege() && tracked.size() >= siegeMaxPending) {
            String msg = msg("afk_siege_kick",
                    "&eИзвините, нас возможно атакуют боты — мы защищаемся. "
                            + "Если вы не бот, извините =( перезайдите!");
            Scheduler.runAtEntityLater(plugin, p, () -> {
                if (p.isOnline()) {
                    p.kickPlayer(msg);
                }
            }, 2L);
            return;
        }
        boolean br = bedrock != null && bedrock.isBedrockPlayer(p);
        tracked.put(p.getUniqueId(), new Tr(p.getUniqueId(), camWindow, br));
    }

    public void onQuit(UUID uuid) {
        Tr t = tracked.remove(uuid);
        if (t != null) {
            if (t.spectating) {
                Player p = Bukkit.getPlayer(uuid);
                org.bukkit.GameMode back = t.prevMode != null ? t.prevMode : org.bukkit.GameMode.SURVIVAL;
                if (p != null && p.isOnline()) {
                    Scheduler.runAtEntity(plugin, p, () -> {
                        if (p.isOnline() && p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                            p.setGameMode(back);
                        }
                    });
                }
            }
            removeBars(t);
        }
    }

    /** Игрок авторизовался — AFK-слежение и бары больше не нужны. */
    public void onLogin(UUID uuid) {
        onQuit(uuid);
    }

    /** Полезная активность (чат в очереди и т.п.) — сбрасывает AFK-таймер. */
    public void onActivity(UUID uuid) {
        Tr t = tracked.get(uuid);
        if (t != null) {
            t.lastActive = System.currentTimeMillis();
        }
    }

    // ---------- движение ----------

    /**
     * Вызывается из AuthListener.onMove для неавторизованных.
     * Реальное горизонтальное смещение сбрасывает AFK; дельты камеры
     * складываются в кольцевой буфер для детекта идеально-линейного аима.
     */
    public void onMove(PlayerMoveEvent e) {
        if (!enabled || e.getTo() == null) {
            return;
        }
        Player p = e.getPlayer();
        Tr t = tracked.get(p.getUniqueId());
        if (t == null) {
            return;
        }
        // На этапах проверки свои таймауты и свой детект камеры — не мешаем
        if (antiBot != null && antiBot.isChecking(p.getUniqueId())) {
            t.lastActive = System.currentTimeMillis();
            return;
        }
        double dx = e.getTo().getX() - e.getFrom().getX();
        double dz = e.getTo().getZ() - e.getFrom().getZ();
        if (dx * dx + dz * dz >= moveDeltaSq) {
            t.lastActive = System.currentTimeMillis();
        }
        if (t.spectating) {
            float sdy = wrapAngle(e.getTo().getYaw() - e.getFrom().getYaw());
            float sdp = e.getTo().getPitch() - e.getFrom().getPitch();
            double sdx = e.getTo().getX() - e.getFrom().getX();
            double sdz = e.getTo().getZ() - e.getFrom().getZ();
            if (Math.abs(sdy) + Math.abs(sdp) > LOOK_EPS || sdx * sdx + sdz * sdz >= moveDeltaSq) {
                exitSpectate(p, t);
            }
            return;
        }
        // Линейный аим: собираем дельты поворота; прыжок на месте сюда не попадает
        float dy = wrapAngle(e.getTo().getYaw() - e.getFrom().getYaw());
        float dp = e.getTo().getPitch() - e.getFrom().getPitch();
        if (Math.abs(dy) + Math.abs(dp) > LOOK_EPS) {
            pushLook(t, dy, dp);
        }
    }

    private void pushLook(Tr t, float dy, float dp) {
        int cap = t.dYaw.length;
        t.dYaw[t.bufIdx] = dy;
        t.dPitch[t.bufIdx] = dp;
        t.bufIdx = (t.bufIdx + 1) % cap;
        if (t.bufCount < cap) {
            t.bufCount++;
        }
        if (t.bufCount == cap) {
            analyzeLook(t);
            t.bufCount = 0;
        }
    }

    /** Окно заполнилось: считаем среднее и stddev |dYaw|+|dPitch| за тик. */
    private void analyzeLook(Tr t) {
        int n = t.dYaw.length;
        double sum = 0, sumSq = 0;
        for (int i = 0; i < n; i++) {
            double m = Math.abs(t.dYaw[i]) + Math.abs(t.dPitch[i]);
            sum += m;
            sumSq += m * m;
        }
        double mean = sum / n;
        double stddev = Math.sqrt(Math.max(0.0, sumSq / n - mean * mean));
        double eps = t.bedrock ? camEps * bedrockMult : camEps;
        if (mean >= camMinMean && stddev <= eps) {
            t.strikes++;
            int maxStrikes = t.bedrock ? camStrikes * 2 : camStrikes;
            if (t.strikes >= maxStrikes) {
                tracked.remove(t.uuid);
                removeBars(t);
                Player p = Bukkit.getPlayer(t.uuid);
                if (p != null && p.isOnline()) {
                    kick(p, msg("afk_bot_kick",
                            "&cЗафиксировано бот-поведение камеры."), true);
                }
            }
        } else if (t.strikes > 0) {
            t.strikes--;
        }
    }

    // ---------- тикер (1 раз/сек) ----------

    private void tick() {
        long now = System.currentTimeMillis();
        boolean rotate = infoEnabled && !infoMsgs.isEmpty()
                && (++tickCount % infoIntervalS == 0);
        if (rotate) {
            infoIndex = (infoIndex + 1) % infoMsgs.size();
        }
        for (Map.Entry<UUID, Tr> e : tracked.entrySet()) {
            UUID uuid = e.getKey();
            Tr t = e.getValue();
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline() || sessions.isLoggedIn(uuid)) {
                tracked.remove(uuid);
                if (t.spectating && p != null) {
                    exitSpectate(p, t);
                } else {
                    removeBars(t);
                }
                continue;
            }
            final Player fp = p;
            Scheduler.runAtEntity(plugin, fp, () -> updateInfoBar(fp, t));
            boolean inCheck = antiBot != null && antiBot.isChecking(uuid);
            if (inCheck) {
                // Проверка началась прямо из spectator-грейса — возвращаем
                // исходный режим, иначе этапы в GM3 не пройти.
                if (t.spectating) {
                    exitSpectate(p, t);
                }
                t.lastActive = now;
                hideAfkBar(t);
                continue;
            }
            boolean inQueue = antiBot != null
                    && (antiBot.isBusy(uuid) || antiBot.isInQueueLobby(uuid));
            long idle = now - t.lastActive;
            if (inQueue) {
                // Режим очереди-лобби: вместо мгновенного кика переводим в
                // spectator с боссбаром — живой игрок шевельнёт камерой и
                // вернётся, бот без активности отлетит по второму таймауту.
                if (t.spectating) {
                    if (t.afkBar != null) {
                        try {
                            double left = Math.max(0.0,
                                    1.0 - (double) (now - t.specSince) / (double) specTimeoutMs);
                            t.afkBar.setProgress(left);
                            t.afkBar.setTitle(msg("afk_spectator_bar",
                                    "&eНаблюдатель: шевели камерой, иначе кик")
                                    + " &7(" + (Math.max(0L,
                                    (specTimeoutMs - (now - t.specSince)) / 1000L)) + "s)");
                        } catch (Throwable ignored) {
                        }
                    }
                    if (now - t.specSince >= specTimeoutMs) {
                        tracked.remove(uuid);
                        kick(p, msg("afk_queue_kick",
                                "&cAFK в очереди: ты не проявлял активность слишком долго."), false);
                    }
                    continue;
                }
                if (idle >= queueIdleMs) {
                    if (specEnabled) {
                        enterSpectate(p, t);
                        continue;
                    }
                    tracked.remove(uuid);
                    kick(p, msg("afk_queue_kick",
                            "&cAFK в очереди: ты не проявлял активность слишком долго."), false);
                }
                continue;
            }
            if (idle < graceMs) {
                hideAfkBar(t);
                continue;
            }
            long remainMs = timeoutMs - idle;
            if (remainMs <= 0) {
                tracked.remove(uuid);
                kick(p, msg("afk_kick", "&cТы слишком долго не двигался."), false);
                continue;
            }
            int remain = (int) ((remainMs + 999) / 1000);
            Scheduler.runAtEntity(plugin, p, () -> {
                if (p.isOnline()) {
                    sendCountdown(p, t, remain);
                }
            });
        }
        // Осадной режим: много afk-киков за окно → лимит входа
        if (siegeEnabled) {
            synchronized (recentKicks) {
                while (!recentKicks.isEmpty() && now - recentKicks.peekFirst() > siegeWindowMs) {
                    recentKicks.pollFirst();
                }
                if (recentKicks.size() >= siegeThreshold && !isSiege()) {
                    siegeUntil = now + siegeDurationMs;
                    plugin.getLogger().warning("AFK-siege: " + recentKicks.size()
                            + " киков за " + (siegeWindowMs / 1000) + "с — лимит входа на "
                            + (siegeDurationMs / 1000) + "с (макс. " + siegeMaxPending + " ожидающих)");
                }
            }
        }
    }

    private void sendCountdown(Player p, Tr t, int secondsLeft) {
        String text = msg("afk_countdown",
                "{prefix}&#FFAA00Двигайтесь! Кик через &#FF5555{seconds} сек")
                .replace("{seconds}", String.valueOf(secondsLeft));
        p.sendMessage(text);
        Compat.sendActionBar(p,
                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(text));
        if (afkBarEnabled) {
            if (t.afkBar == null) {
                try {
                    t.afkBar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID);
                    t.afkBar.addPlayer(p);
                } catch (Throwable ignored) {
                }
            }
            if (t.afkBar != null) {
                try {
                    String title = msg("afk_bar_title", "&cДвигайтесь! Кик через {seconds} сек")
                            .replace("{seconds}", String.valueOf(secondsLeft));
                    t.afkBar.setTitle(title);
                    int total = (int) Math.max(1, (timeoutMs - graceMs) / 1000);
                    t.afkBar.setProgress(Math.max(0.0, Math.min(1.0, secondsLeft / (double) total)));
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void updateInfoBar(Player p, Tr t) {
        if (!infoEnabled || infoMsgs.isEmpty()) {
            if (t.infoBar != null) {
                try {
                    t.infoBar.removeAll();
                } catch (Throwable ignored) {
                }
                t.infoBar = null;
            }
            return;
        }
        if (t.infoBar == null) {
            try {
                t.infoBar = Bukkit.createBossBar("", infoColor, BarStyle.SOLID);
                t.infoBar.setProgress(1.0);
                t.infoBar.addPlayer(p);
            } catch (Throwable ignored) {
                return;
            }
        }
        try {
            t.infoBar.setTitle(color(infoMsgs.get(Math.min(infoIndex, infoMsgs.size() - 1))));
        } catch (Throwable ignored) {
        }
    }

    private void hideAfkBar(Tr t) {
        if (t.afkBar != null) {
            try {
                t.afkBar.removeAll();
            } catch (Throwable ignored) {
            }
            t.afkBar = null;
        }
    }

    private void removeBars(Tr t) {
        hideAfkBar(t);
        if (t.infoBar != null) {
            try {
                t.infoBar.removeAll();
            } catch (Throwable ignored) {
            }
            t.infoBar = null;
        }
    }

    // ---------- кики / баны ----------

    private void kick(Player p, String message, boolean bot) {
        long now = System.currentTimeMillis();
        synchronized (recentKicks) {
            recentKicks.addLast(now);
        }
        String ip = IpUtil.getIp(plugin, p);
        // Бан по IP: подтверждённый бот (макрос/линейный аим) — сразу;
        // обычный AFK — только со 2-го нарушения (afk.ip_ban_strikes).
        if (ip != null && !ip.isEmpty() && ipBanOnKick && ipBanMs > 0 && guard != null) {
            int strikes = bot ? ipBanStrikes : (int) bumpIpStrikes(ip);
            if (strikes >= ipBanStrikes) {
                guard.banIp(ip, ipBanMs, msg("afk_ip_ban",
                        "&cНаша система зафиксировала очень подозрительное поведение, "
                                + "к сожалению возвращайтесь позже."));
            }
        }
        removeBarsQuiet(p.getUniqueId());
        Scheduler.runAtEntity(plugin, p, () -> {
            if (p.isOnline()) {
                p.kickPlayer(message);
            }
        });
    }

    private long bumpIpStrikes(String ip) {
        long now = System.currentTimeMillis();
        long[] st = ipStrikes.computeIfAbsent(ip, k -> new long[2]);
        if (now - st[1] > Math.max(queueIdleMs, timeoutMs) * 4L) {
            st[0] = 0;
        }
        st[0]++;
        st[1] = now;
        if (ipStrikes.size() > 512) {
            ipStrikes.entrySet().removeIf(e -> now - e.getValue()[1] > 3_600_000L);
        }
        return st[0];
    }

    /** Перевод AFK-игрока очереди-лобби в spectator: бар + таймер. */
    private void enterSpectate(Player p, Tr t) {
        t.spectating = true;
        t.specSince = System.currentTimeMillis();
        Scheduler.runAtEntity(plugin, p, () -> {
            if (!p.isOnline()) {
                return;
            }
            t.prevMode = p.getGameMode();
            p.setGameMode(org.bukkit.GameMode.SPECTATOR);
            p.sendMessage(msg("afk_spectator_msg",
                    "&eТы переведён в режим наблюдателя. Двигай камерой или летай, чтобы вернуться!"));
            if (afkBarEnabled) {
                if (t.afkBar == null) {
                    try {
                        t.afkBar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID);
                        t.afkBar.addPlayer(p);
                    } catch (Throwable ignored) {
                    }
                }
                if (t.afkBar != null) {
                    try {
                        t.afkBar.setTitle(msg("afk_spectator_bar",
                                "&eНаблюдатель: шевели камерой, иначе кик"));
                        t.afkBar.setProgress(1.0);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /** Игрок проявил активность в spectator — возвращаем в очередь. */
    private void exitSpectate(Player p, Tr t) {
        t.spectating = false;
        t.lastActive = System.currentTimeMillis();
        hideAfkBar(t);
        org.bukkit.GameMode back = t.prevMode != null ? t.prevMode : org.bukkit.GameMode.SURVIVAL;
        Scheduler.runAtEntity(plugin, p, () -> {
            if (p.isOnline() && p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                p.setGameMode(back);
            }
        });
    }

    /** В spectator-грейсе сейчас? (для AuthListener.onMove) */
    public boolean isSpectating(UUID uuid) {
        Tr t = tracked.get(uuid);
        return t != null && t.spectating;
    }

    private void removeBarsQuiet(UUID uuid) {
        Tr t = tracked.get(uuid);
        if (t != null) {
            removeBars(t);
        }
    }

    public boolean isSiege() {
        return siegeEnabled && System.currentTimeMillis() < siegeUntil;
    }

    /** Сколько игроков сейчас под наблюдением (для отладки/команд). */
    public int trackedCount() {
        return tracked.size();
    }

    // ---------- утилиты ----------

    private String msg(String key, String def) {
        String m = messages != null ? messages.message(key) : null;
        if (m == null || m.isEmpty()) {
            m = def;
        }
        return color(m);
    }

    private static String color(String s) {
        return s == null ? "" : org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    private static BarColor barColor(String name) {
        try {
            return BarColor.valueOf(name == null ? "PURPLE" : name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable t) {
            return BarColor.PURPLE;
        }
    }

    private static float wrapAngle(float a) {
        a %= 360.0f;
        if (a >= 180.0f) {
            a -= 360.0f;
        } else if (a < -180.0f) {
            a += 360.0f;
        }
        return a;
    }

    private static final int READY = -1596029149

    ;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x102d) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x102d) == READY;
    }
}
