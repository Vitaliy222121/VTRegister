// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.listener.AntiBotGuard;
import me.vorchun.registerplugin.util.Compat;
import me.vorchun.registerplugin.util.IpUtil;
import me.vorchun.registerplugin.util.Scheduler;

/**
 * Двухуровневая AFK-защита + детект макросов для неавторизованных игроков.
 *
 * Уровень 1 (спавн): не двигался первые grace секунд → обратный отсчёт
 * в чат и actionbar, по истечении timeout — кик. Действует только пока
 * игрок ничего не сделал: первый шаг, сообщение в чат (пароль!) или
 * команда снимают уровень 1 — дальше входу отмеряет только auth.timeout.
 * Уровень 2 (очередь): нет полезной активности queue_idle секунд → кик.
 *
 * Анти-макро:
 *   - прыжок на месте (|dx|,|dz| < move_delta) НЕ сбрасывает AFK-таймер;
 *   - линейная камера: за окно в camera_window тиков дельты yaw/pitch с
 *     почти нулевым стандартным отклонением → страйк; strikes → кик.
 *     Для Bedrock (Floodgate) допуск смягчается на bedrock_multiplier.
 *
 * Авторизованные (afk.track_authed, по умолчанию выкл.): активность —
 * любое движение (включая транспорт), поворот камеры, чат, команды,
 * взаимодействия, ломание/установка блоков, клики в инвентаре.
 *
 * Осадной режим (siege): если afk-киков за окно >= threshold — на
 * duration секунд вход лимитируется (max_pending НЕАВТОРИЗОВАННЫХ),
 * лишние заходы кикаются с вежливым сообщением.
 *
 * Параллельно сервис ведёт BossBar #2 (AFK-предупреждение) и BossBar #3
 * (ротация инфо-сообщений из конфига) для неавторизованных игроков.
 * BossBar #1 (прогресс очереди/этапа) живёт в AntiBotService.
 */
public final class AfkService implements Listener {

    private static final int RING_CAP = 64;
    private static final float LOOK_EPS = 0.05f;
    /** Любой горизонтальный сдвиг (не дрожание float) = игрок «взялся за дело». */
    private static final double ENGAGE_SQ = 1.0e-4;
    /** Любое движение авторизованного — активность. */
    private static final double ANY_MOVE_SQ = 1.0e-6;
    private static final String BYPASS_PERM = "vtregister.afk.bypass";

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

    /**
     * Кто сейчас в AFK-spectator и какой режим вернуть. Дублируется в
     * data/afk-spectators.yml: после краша/кика игрок не останется в GM3.
     */
    private final Map<UUID, GameMode> specStore = new ConcurrentHashMap<>();
    private final Object specFileLock = new Object();

    // --- конфиг ---
    private volatile boolean enabled;
    private volatile boolean trackAuthed;
    private volatile long authedIdleMs = 900_000L;
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
        /** Игрок уже проявил себя (шаг/чат/команда) — уровень 1 не действует. */
        volatile boolean engaged;
        final float[] dYaw;
        final float[] dPitch;
        int bufIdx, bufCount, strikes;
        int lastInfoIdx = -1;
        boolean bedrock;
        volatile boolean spectating;
        volatile long specSince;
        volatile GameMode prevMode;
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
        loadSpecStore();
        reload();
        // Свои события активности (чат/команды/блоки/транспорт) — сами,
        // без правок в AuthListener. unregisterAll — идемпотентность.
        HandlerList.unregisterAll(this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void setGuard(AntiBotGuard guard) {
        this.guard = guard;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("afk.enabled", true);
        trackAuthed = plugin.getConfig().getBoolean("afk.kick_after_login", false);
        authedIdleMs = Math.max(60, plugin.getConfig().getInt("afk.authed_idle_seconds", 900)) * 1000L;
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
        ipBanOnKick = plugin.getConfig().getBoolean("afk.ip_ban_on_kick", false);
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
                if (p != null && p.isOnline()) {
                    restoreModeNow(p, t);
                }
            }
            removeBars(t);
        }
        tracked.clear();
        ipStrikes.clear();
        // игроки, которых не удалось вернуть (оффлайн), остаются в файле —
        // режим вернётся при следующем входе
        writeSpecStore();
    }

    // ---------- вход/выход ----------

    public void onJoin(Player p) {
        if (p == null) {
            return;
        }
        // Остался в AFK-spectator после краша/кика — вернуть режим
        GameMode saved = specStore.remove(p.getUniqueId());
        if (saved != null) {
            try {
                if (p.getGameMode() == GameMode.SPECTATOR) {
                    p.setGameMode(saved);
                }
            } catch (Throwable ignored) {
            }
            saveSpecStoreAsync();
        }
        if (!enabled || sessions.isLoggedIn(p.getUniqueId())) {
            return;
        }
        // Осадной режим: вход лимитирован — лишних вежливо кикаем.
        // Считаем только НЕавторизованных: залогиненные (track_authed)
        // не «ожидающие» и не должны закрывать вход новичкам.
        // Свой игрок (аккаунт есть, IP привычный) — почти наверняка человек:
        // бот его пароля не знает, а он сам войдёт за секунды. Не выкидываем.
        if (isSiege() && pendingCount() >= siegeMaxPending && !isReturningPlayer(p)) {
            String msg = msg(p, "afk_siege_kick",
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

    /** Зарегистрирован и заходит с привычного IP (только кэш — без похода в базу). */
    private boolean isReturningPlayer(Player p) {
        try {
            if (!(plugin instanceof me.vorchun.registerplugin.RegisterPlugin)) {
                return false;
            }
            AccountStore store = ((me.vorchun.registerplugin.RegisterPlugin) plugin).getAccountStore();
            return store != null && store.isKnownIpCached(p.getUniqueId(),
                    me.vorchun.registerplugin.util.IpUtil.getIp(plugin, p));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Сколько неавторизованных сейчас под наблюдением (для осады). */
    private int pendingCount() {
        int n = 0;
        for (UUID u : tracked.keySet()) {
            if (!sessions.isLoggedIn(u)) {
                n++;
            }
        }
        return n;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Выход игрока (PlayerQuitEvent, поток игрока). Режим из spectator
     * возвращаем СИНХРОННО: playerdata сохраняется сразу после события,
     * отложенная задача уже не успела бы.
     */
    public void onQuit(UUID uuid) {
        Tr t = tracked.remove(uuid);
        if (t != null) {
            if (t.spectating) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    restoreModeNow(p, t);
                }
            }
            removeBars(t);
        }
    }

    /** Игрок авторизовался — слежение продолжается при afk.track_authed. */
    public void onLogin(UUID uuid) {
        if (!trackAuthed) {
            Tr t = tracked.remove(uuid);
            if (t != null) {
                Player p = Bukkit.getPlayer(uuid);
                if (t.spectating && p != null && p.isOnline()) {
                    exitSpectate(p, t);
                } else {
                    removeBars(t);
                }
            }
            return;
        }
        Tr t = tracked.get(uuid);
        if (t == null && enabled) {
            // вошёл уже авторизованным (сессия до onJoin) — лимит
            // authed_idle действует на всех авторизованных одинаково
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                boolean br = bedrock != null && bedrock.isBedrockPlayer(p);
                tracked.putIfAbsent(uuid, new Tr(uuid, camWindow, br));
            }
            return;
        }
        if (t != null) {
            t.lastActive = System.currentTimeMillis();
            t.engaged = true;
            if (t.spectating) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    exitSpectate(p, t);
                }
            }
        }
    }

    /** Полезная активность (чат, команда, ввод пароля) — сбрасывает AFK-таймер. */
    public void onActivity(UUID uuid) {
        Tr t = tracked.get(uuid);
        if (t != null) {
            t.lastActive = System.currentTimeMillis();
            t.engaged = true;
        }
    }

    /** Активность, которая считается только у авторизованных (блоки, клики, транспорт). */
    private void onAuthedActivity(Entity who) {
        if (!trackAuthed || !(who instanceof Player)) {
            return;
        }
        UUID u = who.getUniqueId();
        Tr t = tracked.get(u);
        if (t != null && sessions.isLoggedIn(u)) {
            t.lastActive = System.currentTimeMillis();
        }
    }

    // ---------- события активности ----------
    // MONITOR + ignoreCancelled=false: чат/команды неавторизованных
    // AuthListener отменяет (пароль), но это всё равно живой ввод.

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChatActivity(AsyncPlayerChatEvent e) {
        if (enabled) {
            onActivity(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommandActivity(PlayerCommandPreprocessEvent e) {
        if (enabled) {
            onActivity(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakActivity(BlockBreakEvent e) {
        onAuthedActivity(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlaceActivity(BlockPlaceEvent e) {
        onAuthedActivity(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractActivity(PlayerInteractEvent e) {
        onAuthedActivity(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntityActivity(PlayerInteractEntityEvent e) {
        onAuthedActivity(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryActivity(InventoryClickEvent e) {
        onAuthedActivity(e.getWhoClicked());
    }

    /** Пассажиру PlayerMoveEvent не приходит — лодка/вагонетка/лошадь. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleActivity(VehicleMoveEvent e) {
        if (!trackAuthed || tracked.isEmpty()) {
            return;
        }
        for (Entity passenger : e.getVehicle().getPassengers()) {
            onAuthedActivity(passenger);
        }
    }

    // ---------- движение ----------

    /**
     * Вызывается из AuthListener.onMove (неавторизованные; авторизованные —
     * при afk.track_authed). Реальное горизонтальное смещение сбрасывает
     * AFK; дельты камеры складываются в кольцевой буфер для детекта
     * идеально-линейного аима.
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
        float yawD = wrapAngle(e.getTo().getYaw() - e.getFrom().getYaw());
        float pitchD = e.getTo().getPitch() - e.getFrom().getPitch();
        boolean looked = Math.abs(yawD) + Math.abs(pitchD) > LOOK_EPS;
        double horiz = dx * dx + dz * dz;
        // Авторизованный игрок уже доказал, что не бот: активность — любое
        // движение (присед, вода, паутина дают < 0.2 за событие) и поворот.
        // Линейный аим у него не анализируем.
        if (sessions.isLoggedIn(p.getUniqueId())) {
            double dyv = e.getTo().getY() - e.getFrom().getY();
            if (looked || horiz + dyv * dyv > ANY_MOVE_SQ) {
                t.lastActive = System.currentTimeMillis();
            }
            return;
        }
        if (horiz > ENGAGE_SQ) {
            t.engaged = true;
        }
        if (horiz >= moveDeltaSq) {
            t.lastActive = System.currentTimeMillis();
        }
        // В очереди (боссбар) игрок заморожен на месте — камера для него
        // единственная активность. Раньше поворот не считался, и живого
        // игрока, ждущего очереди, через queue_idle уводило в наблюдатели и кикало.
        if (looked && Math.abs(yawD) + Math.abs(pitchD) > 1.0f && antiBot != null
                && (antiBot.isBusy(p.getUniqueId()) || antiBot.isInQueueLobby(p.getUniqueId()))) {
            t.lastActive = System.currentTimeMillis();
        }
        if (t.spectating) {
            if (looked || horiz >= moveDeltaSq) {
                t.lastActive = System.currentTimeMillis();
                exitSpectate(p, t);
            }
            return;
        }
        // Линейный аим: собираем дельты поворота; прыжок на месте сюда не попадает.
        // Bedrock (тач/геймпад через Geyser) не анализируем вовсе. Движение
        // без поворота рвёт серию: геймпад-игрок отпустил стик — это человек.
        if (t.bedrock) {
            return;
        }
        if (looked) {
            pushLook(t, yawD, pitchD);
        } else {
            t.bufCount = 0;
            t.strikes = 0;
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
            // Длинная серия подряд (>= 3 окон): геймпад со стиком тоже даёт
            // ровные дельты, но не секундами без единой паузы.
            int maxStrikes = Math.max(3, t.bedrock ? camStrikes * 2 : camStrikes);
            if (t.strikes >= maxStrikes) {
                tracked.remove(t.uuid);
                removeBars(t);
                Player p = Bukkit.getPlayer(t.uuid);
                if (p != null && p.isOnline()) {
                    // эвристика — без мгновенного IP-бана (обычный страйк)
                    kick(p, t, msg(p, "afk_bot_kick",
                            "&cЗафиксировано бот-поведение камеры."), false);
                }
            }
        } else {
            t.strikes = 0; // серия прервана
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
            if (p == null || !p.isOnline()
                    || (!trackAuthed && sessions.isLoggedIn(uuid))) {
                tracked.remove(uuid);
                if (t.spectating && p != null) {
                    exitSpectate(p, t);
                } else {
                    removeBars(t);
                }
                continue;
            }
            final Player fp = p;
            boolean authed = sessions.isLoggedIn(uuid);
            if (!authed) {
                // Folia — в потоке игрока; на обычном ядре мы уже в главном
                // потоке: без лишней задачи в планировщике на игрока в секунду
                if (me.vorchun.registerplugin.util.ServerCore.isFolia()) {
                    Scheduler.runAtEntity(plugin, fp, () -> updateInfoBar(fp, t));
                } else {
                    updateInfoBar(fp, t);
                }
            }
            boolean inCheck = antiBot != null && antiBot.isChecking(uuid);
            if (inCheck) {
                // Проверка началась прямо из spectator-грейса — возвращаем
                // исходный режим, иначе этапы в GM3 не пройти.
                if (t.spectating) {
                    exitSpectate(p, t);
                }
                t.lastActive = now;
                // Игрок на этапах проверки — не «ничего не делал»: прошёл
                // физику/камеру и читает «/reg» — кикать его за 15 сек
                // без шага незачем (бота отсекла проверка, дальше лимит —
                // auth.timeout_seconds). Провал проверки кикает сам антибот.
                t.engaged = true;
                hideAfkBar(t);
                continue;
            }
            boolean inQueue = antiBot != null
                    && (antiBot.isBusy(uuid) || antiBot.isInQueueLobby(uuid));
            long idle = now - t.lastActive;
            if (authed) {
                if (t.infoBar != null || t.afkBar != null) {
                    Scheduler.runAtEntity(plugin, fp, () -> removeBars(t));
                }
                // Залогиненный игрок: отдельный мягкий лимит — spectator
                // и queue-грас не применяются, просто кик по authed_idle.
                if (idle >= authedIdleMs && !p.hasPermission(BYPASS_PERM)) {
                    tracked.remove(uuid);
                    kick(p, t, msg(p, "afk_kick",
                            "&cТы слишком долго не двигался."), false);
                }
                continue;
            }
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
                            t.afkBar.setTitle(msg(p, "afk_spectator_bar",
                                    "&eНаблюдатель: шевели камерой, иначе кик")
                                    + " &7(" + (Math.max(0L,
                                    (specTimeoutMs - (now - t.specSince)) / 1000L)) + "s)");
                        } catch (Throwable ignored) {
                        }
                    }
                    if (now - t.specSince >= specTimeoutMs) {
                        tracked.remove(uuid);
                        // kick() сам вернёт режим до kickPlayer — иначе
                        // playerdata сохранится с SPECTATOR
                        kick(p, t, msg(p, "afk_queue_kick",
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
                    kick(p, t, msg(p, "afk_queue_kick",
                            "&cAFK в очереди: ты не проявлял активность слишком долго."), false);
                }
                continue;
            }
            // Уровень 1 только для тех, кто ещё НИЧЕГО не сделал: игрок,
            // который печатает пароль или уже шагнул, ограничен лишь
            // auth.timeout_seconds (без кика и IP-страйков отсюда).
            if (t.engaged || idle < graceMs) {
                hideAfkBar(t);
                continue;
            }
            long remainMs = timeoutMs - idle;
            if (remainMs <= 0) {
                tracked.remove(uuid);
                kick(p, t, msg(p, "afk_kick", "&cТы слишком долго не двигался."), false);
                continue;
            }
            int remain = (int) ((remainMs + 999) / 1000);
            Scheduler.runAtEntity(plugin, p, () -> {
                if (p.isOnline() && !t.engaged) {
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
        String text = msg(p, "afk_countdown",
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
                    String title = msg(p, "afk_bar_title", "&cДвигайтесь! Кик через {seconds} сек")
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
                t.lastInfoIdx = -1;
            }
            return;
        }
        if (t.infoBar == null) {
            try {
                t.infoBar = Bukkit.createBossBar("", infoColor, BarStyle.SOLID);
                t.infoBar.setProgress(1.0);
                t.infoBar.addPlayer(p);
                // новый бар пустой — титул обязан выставиться на этом же тике
                t.lastInfoIdx = -1;
            } catch (Throwable ignored) {
                return;
            }
        }
        try {
            int idx = Math.min(infoIndex, infoMsgs.size() - 1);
            if (t.lastInfoIdx != idx) {
                t.lastInfoIdx = idx;
                t.infoBar.setTitle(color(infoMsgs.get(idx)));
            }
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
        t.lastInfoIdx = -1;
    }

    // ---------- кики / баны ----------

    private void kick(Player p, Tr t, String message, boolean bot) {
        long now = System.currentTimeMillis();
        boolean authed = sessions != null
                && sessions.isLoggedIn(p.getUniqueId());
        synchronized (recentKicks) {
            if (!authed) {
                recentKicks.addLast(now);
            }
        }
        String ip = IpUtil.getIp(plugin, p);
        // Бан по IP: подтверждённый бот (макрос/линейный аим) — сразу;
        // обычный AFK — только со 2-го нарушения (afk.ip_ban_strikes).
        // Без доверенных IP (прокси без forwarding) у всех один адрес —
        // бан ударил бы по всем, поэтому не баним.
        if (ip != null && !ip.isEmpty() && ipBanOnKick && ipBanMs > 0 && guard != null
                && !authed && IpUtil.ipsTrusted()) {
            int strikes = bot ? ipBanStrikes : (int) bumpIpStrikes(ip);
            if (strikes >= ipBanStrikes) {
                guard.banIp(ip, ipBanMs, msg(p, "afk_ip_ban",
                        "&cНаша система зафиксировала очень подозрительное поведение, "
                                + "к сожалению возвращайтесь позже."));
            }
        }
        if (t != null) {
            removeBars(t);
        } else {
            removeBarsQuiet(p.getUniqueId());
        }
        Scheduler.runAtEntity(plugin, p, () -> {
            if (p.isOnline()) {
                // AFK-spectator: сначала вернуть режим, потом кик —
                // иначе игрок сохранится и вернётся в GM3
                if (t != null && t.spectating) {
                    restoreModeNow(p, t);
                }
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
            if (!p.isOnline() || !t.spectating) {
                return;
            }
            GameMode cur = p.getGameMode();
            t.prevMode = cur == GameMode.SPECTATOR ? GameMode.SURVIVAL : cur;
            // сначала запись «вернуть режим», потом сам GM3 — краш между
            // ними не оставит игрока в spectator без записи
            specStore.put(p.getUniqueId(), t.prevMode);
            saveSpecStoreAsync();
            p.setGameMode(GameMode.SPECTATOR);
            p.sendMessage(msg(p, "afk_spectator_msg",
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
                        t.afkBar.setTitle(msg(p, "afk_spectator_bar",
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
        GameMode back = t.prevMode != null ? t.prevMode : GameMode.SURVIVAL;
        Scheduler.runAtEntity(plugin, p, () -> {
            if (p.isOnline() && p.getGameMode() == GameMode.SPECTATOR) {
                p.setGameMode(back);
            }
            if (specStore.remove(p.getUniqueId()) != null) {
                saveSpecStoreAsync();
            }
        });
    }

    /** Вернуть режим прямо сейчас (вызывать в потоке игрока). */
    private void restoreModeNow(Player p, Tr t) {
        t.spectating = false;
        GameMode back = t.prevMode != null ? t.prevMode : GameMode.SURVIVAL;
        try {
            if (p.getGameMode() == GameMode.SPECTATOR) {
                p.setGameMode(back);
            }
            specStore.remove(p.getUniqueId());
        } catch (Throwable ignored) {
            // режим не вернули — запись в файле остаётся, вернём при входе
            return;
        }
        saveSpecStoreAsync();
    }

    /** В spectator-грейсе сейчас? (для AuthListener.onMove) */
    public boolean isSpectating(UUID uuid) {
        Tr t = tracked.get(uuid);
        return t != null && t.spectating;
    }

    /**
     * Режим, который был ДО AFK-spectator (null, если игрок не в нём).
     * AntiBotService.preparePlayer должен сохранять его, а не SPECTATOR.
     */
    public GameMode spectatorPrevMode(UUID uuid) {
        Tr t = tracked.get(uuid);
        if (t != null && t.spectating) {
            return t.prevMode != null ? t.prevMode : GameMode.SURVIVAL;
        }
        return specStore.get(uuid);
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

    // ---------- файл AFK-spectator ----------

    private File specFile() {
        return new File(AccountStore.dataFolder(plugin), "afk-spectators.yml");
    }

    private void loadSpecStore() {
        try {
            File f = specFile();
            if (!f.exists()) {
                return;
            }
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            for (String k : y.getKeys(false)) {
                try {
                    specStore.put(UUID.fromString(k), GameMode.valueOf(y.getString(k, "SURVIVAL")));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("AFK: не удалось прочитать afk-spectators.yml: " + t.getMessage());
        }
    }

    private void saveSpecStoreAsync() {
        try {
            Scheduler.runAsync(plugin, this::writeSpecStore);
        } catch (Throwable t) {
            // плагин выключается — пишем сразу
            writeSpecStore();
        }
    }

    /** Пишет ТЕКУЩЕЕ состояние: поздняя запись всегда актуальна. */
    private void writeSpecStore() {
        synchronized (specFileLock) {
            try {
                File f = specFile();
                if (specStore.isEmpty()) {
                    if (f.exists() && !f.delete()) {
                        new YamlConfiguration().save(f);
                    }
                    return;
                }
                YamlConfiguration y = new YamlConfiguration();
                for (Map.Entry<UUID, GameMode> e : specStore.entrySet()) {
                    y.set(e.getKey().toString(), e.getValue().name());
                }
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                y.save(f);
            } catch (Throwable t) {
                plugin.getLogger().warning("AFK: не удалось записать afk-spectators.yml: " + t.getMessage());
            }
        }
    }

    // ---------- утилиты ----------

    /** Текст на языке игрока (language: auto). */
    private String msg(Player p, String key, String def) {
        String m = messages != null
                ? messages.message(p, key, Collections.<String, String>emptyMap()) : null;
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

    private static final int READY = -1251988102;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x102d) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x102d) == READY;
    }
}
