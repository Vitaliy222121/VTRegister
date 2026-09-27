// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.listener;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.RegisterPlugin;
import me.vorchun.registerplugin.service.AccountStore;
import me.vorchun.registerplugin.service.BedrockSupportService;
import me.vorchun.registerplugin.service.SessionManager;
import me.vorchun.registerplugin.util.IpUtil;
import me.vorchun.registerplugin.util.Scheduler;

/**
 * Защита «до входа»: отсекает бот-волны ещё на этапе подключения,
 * до создания игрока и до антибот-проверки в игре.
 *
 * Что проверяется (всё настраивается в config.yml → antibot.guard):
 *   - общий рейт подключений (N входов за M секунд) → attack-mode;
 *   - лимит одновременно онлайн с одного IP (только новые аккаунты);
 *   - лимит подключений с одного IP за окно времени;
 *   - фильтр ников (регулярка) — «Player12345» и прочие бот-паттерны;
 *     Bedrock-ники Floodgate («.Steve») пропускаются;
 *   - лимит аккаунтов на IP (мультиаккаунты);
 *   - повторное подключение в течение N секунд (reconnect-челлендж);
 *   - временный бан IP после провала проверки (fail_ban_minutes) и после
 *     серии провалов за час (repeat_fail_ban_minutes).
 * Плюс вне antibot.guard: одна сессия на ник (security.single_session)
 * и предзагрузка аккаунта из БД в фоновом потоке.
 *
 * Все проверки по IP пропускаются, если IP — адрес прокси или IP в этом
 * окружении недоверенные (IpUtil.ipsTrusted): иначе один бан/лимит
 * закрыл бы сервер всем игрокам сети.
 *
 * Все проверки работают с локальными счётчиками в памяти — нулевая нагрузка.
 */
public final class AntiBotGuard implements Listener {

    private final JavaPlugin plugin;
    private final AccountStore accountStore;

    private final Map<String, Deque<Long>> joinsByIp = new ConcurrentHashMap<>();
    private final Map<String, Integer> onlineByIp = new ConcurrentHashMap<>();
    /** Под каким IP игрок учтён в onlineByIp — выход вычитает ровно то, что прибавил вход. */
    private final Map<UUID, String> countedIp = new ConcurrentHashMap<>();
    private final Map<String, Long> bannedUntil = new ConcurrentHashMap<>();
    private final Map<String, String> banMessages = new ConcurrentHashMap<>();
    /** Reconnect-челлендж: когда IP получил просьбу переподключиться. */
    private final Map<String, Long> challengeIssued = new ConcurrentHashMap<>();
    /** Последний IP вышедшего игрока: провал проверки приходит уже после кика. */
    private final Map<UUID, LastIp> lastIpByUuid = new ConcurrentHashMap<>();
    /** Провалы проверки по IP за час: {начало окна, число} — прогрессивный бан. */
    private final Map<String, long[]> failsByIp = new ConcurrentHashMap<>();
    /** Бан IP за серию провалов — действует только на незарегистрированных. */
    private final Map<String, Long> repeatBanned = new ConcurrentHashMap<>();
    /** Ник (нижний регистр) → UUID онлайн-игрока: одна сессия на ник. */
    private final Map<String, UUID> onlineByName = new ConcurrentHashMap<>();
    /** Принятые входы (глобальный лимит). */
    private final Deque<Long> globalJoins = new ArrayDeque<>();
    /** Все попытки входа (детект всплеска → attack-mode). */
    private final Deque<Long> attempts = new ArrayDeque<>();

    private static final class LastIp {
        final String ip;
        final long at;

        LastIp(String ip, long at) {
            this.ip = ip;
            this.at = at;
        }
    }

    private volatile boolean enabled;
    private volatile int windowSeconds;
    private volatile int maxPerIp;
    private volatile int maxGlobalPerWindow;
    private volatile int attackThreshold;
    private volatile long attackUntil;
    private volatile int maxAccountsPerIp;
    private volatile int maxOnlinePerIp;
    private volatile int reconnectSeconds;
    private volatile int failBanMinutes;
    private volatile int repeatFailBanMinutes;
    private volatile Pattern namePattern;
    private volatile boolean singleSession;
    private volatile boolean firewallEnabled;
    private volatile java.util.List<String> firewallIps = java.util.Collections.emptyList();
    private volatile Scheduler.Task purger;
    private volatile long lastFirewallLog;
    private final AtomicInteger firewallDenied = new AtomicInteger();
    private volatile boolean forwardingBrokenWarned;

    // ── IP хостингов / дата-центров / VPN (antibot.datacenter_block) ──
    private volatile boolean dcEnabled;
    private volatile boolean dcAllPlayers;
    private volatile String dcUrl = "";
    private volatile long dcRefreshMs = 24L * 3600_000L;
    private volatile me.vorchun.registerplugin.util.CidrList dcList = me.vorchun.registerplugin.util.CidrList.EMPTY;
    private volatile long dcLoadedAt;
    private final java.util.concurrent.atomic.AtomicBoolean dcLoading = new java.util.concurrent.atomic.AtomicBoolean();

    private java.io.File dcCache() {
        return new java.io.File(plugin.getDataFolder(), "data/datacenter-ipv4.txt");
    }

    /** Загрузить список: кэш с диска сразу, свежий из сети — если устарел. В фоне. */
    private void refreshDatacenterList(boolean force) {
        if (!dcEnabled || !dcLoading.compareAndSet(false, true)) {
            return;
        }
        Scheduler.runAsync(plugin, () -> {
            try {
                java.io.File cache = dcCache();
                if (dcList.size() == 0 && cache.isFile()) {
                    dcList = me.vorchun.registerplugin.util.CidrList.parse(java.nio.file.Files.readAllLines(
                            cache.toPath(), java.nio.charset.StandardCharsets.UTF_8));
                    dcLoadedAt = cache.lastModified();
                }
                if (!force && System.currentTimeMillis() - dcLoadedAt < dcRefreshMs) {
                    return;
                }
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(dcUrl).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(30_000);
                c.setRequestProperty("User-Agent", "VTRegister");
                if (c.getResponseCode() != 200) {
                    throw new IllegalStateException("HTTP " + c.getResponseCode());
                }
                java.util.List<String> lines = new java.util.ArrayList<>();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                        c.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null && lines.size() < 2_000_000) {
                        lines.add(l);
                    }
                }
                me.vorchun.registerplugin.util.CidrList fresh = me.vorchun.registerplugin.util.CidrList.parse(lines);
                if (fresh.size() > 0) {
                    dcList = fresh;
                    dcLoadedAt = System.currentTimeMillis();
                    cache.getParentFile().mkdirs();
                    java.nio.file.Files.write(cache.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
                    plugin.getLogger().info("AntiBot: список IP хостингов/VPN обновлён — " + fresh.size() + " диапазонов");
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("AntiBot: список IP хостингов/VPN не скачан (" + t.getMessage()
                        + ") — работает прошлый" + (dcList.size() > 0 ? "" : " (пустой)"));
            } finally {
                dcLoading.set(false);
            }
        });
    }

    // ── Проверка пинга (antibot.ping_check) ──
    /** IP → когда сервер был пингнут из списка серверов. */
    private final Map<String, Long> pinged = new ConcurrentHashMap<>();
    private volatile String pingMode = "attack";
    private volatile long pingWindowMs = 600_000L;
    private volatile java.util.function.BooleanSupplier proxyMode = () -> false;

    public void setProxyModeSource(java.util.function.BooleanSupplier s) {
        this.proxyMode = s == null ? () -> false : s;
    }

    /** Пинг из списка серверов: настоящий клиент делает его перед входом, бот — обычно нет. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPing(org.bukkit.event.server.ServerListPingEvent e) {
        if (!"off".equals(pingMode) && e.getAddress() != null) {
            pinged.put(IpUtil.normalize(e.getAddress()), System.currentTimeMillis());
        }
    }

    /** Нужно ли требовать пинг перед входом этого подключения. */
    private boolean pingRequired(String ip, UUID uuid, long now) {
        String mode = pingMode;
        if ("off".equals(mode) || proxyMode.getAsBoolean()) {
            return false; // за прокси пинги видит прокси, а не сервер — там своя проверка
        }
        if ("attack".equals(mode) && !isAttackMode()) {
            return false;
        }
        // Bedrock (Floodgate) пингует через Geyser — UUID вида 00000000-0000-0000-…
        if (uuid.getMostSignificantBits() == 0L || isLoopback(ip) || isRegistered(uuid)) {
            return false;
        }
        Long t = pinged.get(ip);
        return t == null || now - t > pingWindowMs;
    }

    // ── Лимит подключений (antibot.connection_limit) ──
    private volatile boolean rateEnabled;
    private volatile int ratePerSecond;
    private volatile int ratePerMinute;
    private volatile int rateIpPerSecond;
    private volatile int rateIpPerMinute;
    private volatile boolean rateRegisteredBypass;
    /** {номер секунды, счётчик, номер минуты, счётчик} — весь сервер. */
    private final long[] rateGlobal = new long[4];
    /** То же на один IP. */
    private final Map<String, long[]> rateIp = new ConcurrentHashMap<>();

    // ── Баны по ступеням (antibot.bans) ──
    private volatile boolean bansAuto;
    private volatile int[] banTiers = {1, 5, 15};
    private volatile boolean banPermanent;
    private volatile int banFreeFails = 2;
    private volatile long strikeForgetMs = 24L * 3600_000L;
    /** IP → {ступень, когда был последний провал}. */
    private final Map<String, long[]> strikes = new ConcurrentHashMap<>();
    /** Бан навсегда хранится как Long.MAX_VALUE. */
    public static final long PERMANENT = Long.MAX_VALUE;
    private volatile boolean bansLoaded;
    private volatile me.vorchun.registerplugin.service.GlobalBlacklist global;

    public void setGlobalBlacklist(me.vorchun.registerplugin.service.GlobalBlacklist g) {
        this.global = g;
    }

    public AntiBotGuard(JavaPlugin plugin, AccountStore accountStore) {
        this.plugin = plugin;
        this.accountStore = accountStore;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("antibot.guard.enabled", true);
        windowSeconds = Math.max(5, plugin.getConfig().getInt("antibot.guard.window_seconds", 60));
        maxPerIp = Math.max(1, plugin.getConfig().getInt("antibot.guard.max_joins_per_ip", 5));
        maxGlobalPerWindow = Math.max(1, plugin.getConfig().getInt("antibot.guard.max_joins_global", 60));
        attackThreshold = Math.max(1, plugin.getConfig().getInt("antibot.guard.attack_mode_threshold", 30));
        maxAccountsPerIp = Math.max(0, plugin.getConfig().getInt("antibot.guard.max_accounts_per_ip", 0));
        maxOnlinePerIp = Math.max(0, plugin.getConfig().getInt("antibot.guard.max_online_per_ip", 4));
        reconnectSeconds = Math.max(0, plugin.getConfig().getInt("antibot.guard.reconnect_seconds", 0));
        failBanMinutes = Math.max(0, plugin.getConfig().getInt("antibot.guard.fail_ban_minutes", 0));
        repeatFailBanMinutes = Math.max(0, plugin.getConfig().getInt("antibot.guard.repeat_fail_ban_minutes", 5));
        dcEnabled = plugin.getConfig().getBoolean("antibot.datacenter_block.enabled", false);
        dcAllPlayers = "all".equalsIgnoreCase(plugin.getConfig().getString("antibot.datacenter_block.mode", "new_players").trim());
        dcUrl = plugin.getConfig().getString("antibot.datacenter_block.url",
                "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/datacenter/ipv4.txt");
        dcRefreshMs = Math.max(1, plugin.getConfig().getInt("antibot.datacenter_block.refresh_hours", 24)) * 3600_000L;
        refreshDatacenterList(false);
        String pm = plugin.getConfig().getString("antibot.ping_check.mode", "attack").trim().toLowerCase(Locale.ROOT);
        pingMode = "false".equals(pm) ? "off" : pm;
        pingWindowMs = Math.max(30, plugin.getConfig().getInt("antibot.ping_check.window_seconds", 600)) * 1000L;
        rateEnabled = plugin.getConfig().getBoolean("antibot.connection_limit.enabled", true);
        ratePerSecond = Math.max(0, plugin.getConfig().getInt("antibot.connection_limit.per_second", 10));
        ratePerMinute = Math.max(0, plugin.getConfig().getInt("antibot.connection_limit.per_minute", 150));
        rateIpPerSecond = Math.max(0, plugin.getConfig().getInt("antibot.connection_limit.per_ip_per_second", 2));
        rateIpPerMinute = Math.max(0, plugin.getConfig().getInt("antibot.connection_limit.per_ip_per_minute", 10));
        rateRegisteredBypass = plugin.getConfig().getBoolean("antibot.connection_limit.registered_bypass", true);

        bansAuto = "auto".equalsIgnoreCase(plugin.getConfig().getString("antibot.bans.mode", "auto").trim());
        java.util.List<Integer> tl = plugin.getConfig().getIntegerList("antibot.bans.tiers_minutes");
        java.util.List<Integer> tiers = new java.util.ArrayList<>();
        if (tl != null) {
            for (Integer t : tl) {
                if (t != null && t > 0) {
                    tiers.add(t);
                }
            }
        }
        if (tiers.isEmpty()) {
            tiers = java.util.Arrays.asList(1, 5, 15);
        }
        int[] ta = new int[tiers.size()];
        for (int i = 0; i < ta.length; i++) {
            ta[i] = tiers.get(i);
        }
        banTiers = ta;
        banPermanent = plugin.getConfig().getBoolean("antibot.bans.permanent", false);
        banFreeFails = Math.max(0, plugin.getConfig().getInt("antibot.bans.free_fails", 2));
        strikeForgetMs = Math.max(1, plugin.getConfig().getInt("antibot.bans.forget_after_hours", 24)) * 3600_000L;
        if (!bansLoaded) {
            bansLoaded = true;
            loadBans();
        }

        singleSession = plugin.getConfig().getBoolean("security.single_session", true);
        String regex = plugin.getConfig().getString("antibot.guard.name_regex", "^[A-Za-z0-9_]{3,16}$");
        try {
            namePattern = Pattern.compile(regex);
        } catch (Throwable t) {
            namePattern = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
            plugin.getLogger().warning("antibot.guard.name_regex некорректен — использую стандартный");
        }

        // Файрвол прокси: пускать только подключения с IP самого прокси (точный IP или CIDR)
        firewallEnabled = plugin.getConfig().getBoolean("proxy.firewall.enabled", false);
        java.util.List<String> ips = new java.util.ArrayList<>();
        for (String ip : plugin.getConfig().getStringList("proxy.firewall.allowed_ips")) {
            String n = IpUtil.normalize(ip);
            if (!n.isEmpty()) {
                ips.add(n);
            }
        }
        firewallIps = java.util.Collections.unmodifiableList(ips);
        if (firewallEnabled && firewallIps.isEmpty()) {
            plugin.getLogger().warning("proxy.firewall.enabled: true, но allowed_ips пуст — файрвол НЕ работает");
        }

        // После /reload плагина игроки уже онлайн — восстанавливаем учёт ников
        for (Player p : Bukkit.getOnlinePlayers()) {
            onlineByName.put(p.getName().toLowerCase(Locale.ROOT), p.getUniqueId());
        }

        // Периодическая очистка счётчиков — иначе карты растут бесконечно
        // на серверах с большим потоком IP (бот-волны)
        if (purger != null) {
            purger.cancel();
        }
        purger = Scheduler.runAsyncTimer(plugin, this::purgeStale, 1200L, 1200L);
    }

    /** Удаляем устаревшие записи: старше окна у joins, истёкшие баны, старые метки. */
    private void purgeStale() {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;

        joinsByIp.entrySet().removeIf(e -> {
            Deque<Long> d = e.getValue();
            synchronized (d) {
                while (!d.isEmpty() && now - d.peekFirst() > windowMs) {
                    d.pollFirst();
                }
                return d.isEmpty();
            }
        });
        bannedUntil.entrySet().removeIf(e -> {
            if (now > e.getValue()) {
                banMessages.remove(e.getKey());
                return true;
            }
            return false;
        });
        long challengeMs = Math.max(reconnectSeconds * 1000L, 60_000L);
        challengeIssued.entrySet().removeIf(e -> now - e.getValue() > challengeMs);
        lastIpByUuid.entrySet().removeIf(e -> now - e.getValue().at > 120_000L);
        failsByIp.entrySet().removeIf(e -> now - e.getValue()[0] > FAIL_WINDOW_MS);
        repeatBanned.entrySet().removeIf(e -> now > e.getValue());
        pinged.entrySet().removeIf(e -> now - e.getValue() > pingWindowMs);
        if (dcEnabled && now - dcLoadedAt > dcRefreshMs) {
            refreshDatacenterList(false);
        }
        long curMin = now / 60_000L;
        rateIp.entrySet().removeIf(e -> e.getValue()[2] < curMin - 1);
        strikes.entrySet().removeIf(e -> now - e.getValue()[1] > strikeForgetMs && !bannedUntil.containsKey(e.getKey()));
        trim(globalJoins, now, windowMs);
        trim(attempts, now, windowMs);
    }

    private static void trim(Deque<Long> d, long now, long windowMs) {
        synchronized (d) {
            while (!d.isEmpty() && now - d.peekFirst() > windowMs) {
                d.pollFirst();
            }
        }
    }

    /**
     * Файрвол прокси: если включён, на сервер пускаем только подключения,
     * реальный сокет-адрес которых = IP прокси (allowed_ips, можно CIDR).
     * Используем PlayerLoginEvent#getRealAddress — это адрес сокета,
     * а не подменённый при forwarding адрес игрока.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onProxyFirewall(org.bukkit.event.player.PlayerLoginEvent e) {
        if (!firewallEnabled || firewallIps.isEmpty()) {
            return;
        }
        String real = "";
        try {
            real = IpUtil.normalize(e.getRealAddress());
        } catch (Throwable ignored) {
        }
        if (real.isEmpty()) {
            real = IpUtil.normalize(e.getAddress());
        }
        boolean allowed = false;
        for (String ip : firewallIps) {
            if (IpUtil.matches(real, ip)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            // Под атакой не заливаем лог: одна строка раз в 10 секунд со счётчиком
            int n = firewallDenied.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - lastFirewallLog > 10_000L) {
                lastFirewallLog = now;
                firewallDenied.set(0);
                plugin.getLogger().warning("Proxy-firewall: отклонено прямое подключение " + e.getPlayer().getName()
                        + " с " + real + (n > 1 ? " (и ещё " + (n - 1) + " за 10с)" : "")
                        + " (разрешены: " + firewallIps + ")");
            }
            e.disallow(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_OTHER,
                    color("&cПодключение напрямую запрещено. Используй адрес сервера (прокси)."));
            return;
        }
        // Вход через прокси, а адрес игрока = адрес прокси → IP-forwarding не работает
        String shown = IpUtil.normalize(e.getAddress());
        if (!forwardingBrokenWarned && shown.equals(real) && !isLoopback(real)) {
            forwardingBrokenWarned = true;
            plugin.getLogger().severe("IP-forwarding не работает: игрок " + e.getPlayer().getName()
                    + " пришёл с адресом прокси " + real + ". Лимиты/баны/сессии по IP для адресов прокси "
                    + "пропускаются. Включи forwarding (Velocity modern / BungeeCord ip_forward) — см. PROXY_SETUP.txt.");
        }
    }

    private static boolean isLoopback(String ip) {
        return ip.startsWith("127.") || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip);
    }

    public boolean isAttackMode() {
        return System.currentTimeMillis() < attackUntil;
    }

    /**
     * Провал антибот-проверки → временный бан IP (если включено).
     * AntiBotService кикает ДО этого вызова, поэтому игрока уже может не быть
     * онлайн — тогда берём последний IP, запомненный при выходе.
     */
    public void onAntiBotFail(UUID uuid) {
        if (!enabled || uuid == null) {
            return;
        }
        String ip = "";
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            ip = IpUtil.getIp(plugin, p);
        }
        if (ip.isEmpty()) {
            LastIp last = lastIpByUuid.get(uuid);
            if (last != null && System.currentTimeMillis() - last.at < 120_000L) {
                ip = last.ip;
            }
        }
        registerFail(ip);
    }

    /** Провал проверки с уже известным IP (вызывающий снял его до кика). */
    public void onAntiBotFail(String ip) {
        if (!enabled) {
            return;
        }
        registerFail(ip);
    }

    /** Окно подсчёта повторных провалов и сколько провалов дают бан. */
    private static final long FAIL_WINDOW_MS = 3600_000L;
    private static final int REPEAT_FAILS = 3;

    /**
     * Провал с IP: fail_ban_minutes — бан сразу; независимо от него
     * REPEAT_FAILS провалов за час — бан на repeat_fail_ban_minutes
     * (иначе бот перебирает пазл бесконечно, перезаходя).
     */
    private void registerFail(String ip) {
        String n = IpUtil.normalize(ip);
        if (n.isEmpty() || !perIpApplicable(n)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (bansAuto) {
            // AUTO: 1-й провал — первая ступень (1 мин), 2-й — вторая (5 мин),
            // 3-й — третья (15 мин), дальше — навсегда (если bans.permanent)
            // или снова последняя ступень. Без провалов forget_after_hours — с нуля.
            long[] st = strikes.compute(n, (k, v) -> {
                if (v == null || now - v[1] > strikeForgetMs) {
                    v = new long[]{0, now};
                }
                v[0]++;
                v[1] = now;
                return v;
            });
            int fails = (int) st[0];
            if (fails <= banFreeFails) {
                // Первые free_fails доказанных провалов — только кик: человек мог
                // ошибиться. Факт записан, бан — с (free_fails+1)-го провала.
                plugin.getLogger().info("AntiBot: IP " + n + " — доказанный провал " + fails
                        + "/" + banFreeFails + " без бана (только кик)");
                saveBansAsync();
                return;
            }
            int level = fails - banFreeFails;
            int[] t = banTiers;
            long dur = level > t.length
                    ? (banPermanent ? PERMANENT : t[t.length - 1] * 60_000L)
                    : t[level - 1] * 60_000L;
            banIp(n, dur, null);
            plugin.getLogger().info("AntiBot: бан IP " + n + " — " + fails + "-й доказанный провал, ступень " + level + ", "
                    + (dur == PERMANENT ? "навсегда" : (dur / 60_000L) + " мин"));
            saveBansAsync();
            return;
        }
        if (failBanMinutes > 0) {
            banIp(n, failBanMinutes * 60_000L, null);
        }
        if (repeatFailBanMinutes > 0) {
            long[] w = failsByIp.computeIfAbsent(n, k -> new long[]{now, 0});
            synchronized (w) {
                if (now - w[0] > FAIL_WINDOW_MS) {
                    w[0] = now;
                    w[1] = 0;
                }
                if (++w[1] >= REPEAT_FAILS) {
                    // Только для новых аккаунтов: за общим NAT/CGNAT зарегистрированные
                    // соседи бота входить не должны перестать
                    repeatBanned.put(n, now + repeatFailBanMinutes * 60_000L);
                }
            }
        }
    }

    /**
     * Временный бан IP извне (AFK/бот-детект): ip — hostAddress,
     * durationMs — длительность, message — текст кика при входе.
     * Адреса прокси и недоверенные IP не баним — это был бы бан всей сети.
     */
    public void banIp(String ip, long durationMs, String message) {
        String n = IpUtil.normalize(ip);
        if (n.isEmpty() || durationMs <= 0 || !perIpApplicable(n)) {
            return;
        }
        bannedUntil.put(n, durationMs == PERMANENT ? PERMANENT : System.currentTimeMillis() + durationMs);
        if (message != null && !message.isEmpty()) {
            banMessages.put(n, message);
        } else {
            banMessages.remove(n);
        }
    }

    /** Можно ли применять к адресу per-IP лимиты/баны. */
    private boolean perIpApplicable(String ip) {
        return !ip.isEmpty() && IpUtil.ipsTrusted() && !IpUtil.isProxyAddress(plugin, ip);
    }

    // ---------- pre-login ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        // Одна сессия на ник — защита аккаунта, работает и без antibot.guard
        if (singleSession && checkSingleSession(e)) {
            return;
        }
        if (!enabled || !ready()) {
            return;
        }
        String ip = IpUtil.normalize(e.getAddress());
        boolean perIp = perIpApplicable(ip);
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        UUID uuid = e.getUniqueId();

        if (perIp) {
            Long ban = bannedUntil.get(ip);
            if (ban != null) {
                if (ban > now) {
                    String custom = banMessages.get(ip);
                    e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                            custom != null ? custom
                                    : msg("antibot_ip_banned_time",
                                    "&cПроверка на бота не пройдена. Вход закрыт: &f{time}")
                                    .replace("{time}", timeLeft(ban, now)));
                    return;
                }
                bannedUntil.remove(ip);
                banMessages.remove(ip);
            }
            Long rb = repeatBanned.get(ip);
            if (rb != null) {
                if (rb <= now) {
                    repeatBanned.remove(ip);
                } else if (!isRegistered(uuid)) {
                    e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                            msg("antibot_ip_banned", "&cСлишком много неудачных проверок. Попробуй позже."));
                    return;
                }
            }
        }

        me.vorchun.registerplugin.service.GlobalBlacklist gb = global;
        if (gb != null && gb.isEnabled()) {
            String src = gb.check(perIp ? ip : null, e.getName());
            if (src != null) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        msg("global_blacklisted", "&cТы в едином чёрном списке серверов (&f{source}&c).")
                                .replace("{source}", src));
                return;
            }
        }

        if (perIp && dcEnabled && dcList.contains(ip) && (dcAllPlayers || !isRegistered(uuid))) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    msg("antibot_datacenter", "&cВход с адресов хостингов и VPN запрещён. Отключи VPN и зайди снова."));
            return;
        }

        if (perIp && pingRequired(ip, uuid, now)) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    msg("antibot_ping_first", "&eДобавь сервер в список серверов, обнови список и зайди снова."));
            return;
        }

        if (rateEnabled && !(rateRegisteredBypass && isRegistered(uuid))) {
            if (!rateAllow(perIp ? ip : null, now)) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        msg("antibot_rate_limit", "&cСлишком много подключений. Подожди пару секунд и зайди снова."));
                return;
            }
        }

        String name = e.getName();
        if (namePattern != null && !nameAllowed(uuid, name)) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    msg("antibot_bad_name", "&cЭтот ник не разрешён на сервере."));
            return;
        }

        // Attack-mode: всплеск попыток входа (считаем все, включая отклонённые ниже)
        synchronized (attempts) {
            attempts.addLast(now);
            while (!attempts.isEmpty() && now - attempts.peekFirst() > windowMs) {
                attempts.pollFirst();
            }
            if (attempts.size() >= attackThreshold) {
                if (!isAttackMode()) {
                    plugin.getLogger().warning("AntiBot: всплеск подключений (" + attempts.size()
                            + " за " + windowSeconds + "с) — включён attack-mode");
                }
                attackUntil = now + windowMs;
            }
        }

        if (perIp) {
            Deque<Long> joins = joinsByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
            synchronized (joins) {
                while (!joins.isEmpty() && now - joins.peekFirst() > windowMs) {
                    joins.pollFirst();
                }
                if (joins.size() >= maxPerIp) {
                    e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                            msg("antibot_ip_limit", "&cСлишком много подключений с твоего IP. Подожди немного."));
                    return;
                }
                joins.addLast(now);
            }
        }

        // Глобальный лимит: считаем только ПРИНЯТЫЕ входы (иначе один IP,
        // долбящий в per-IP лимит, забивает окно всем) и своих не режем.
        boolean globalFull;
        synchronized (globalJoins) {
            while (!globalJoins.isEmpty() && now - globalJoins.peekFirst() > windowMs) {
                globalJoins.pollFirst();
            }
            globalFull = globalJoins.size() >= maxGlobalPerWindow;
        }
        if (globalFull && !isRegistered(uuid)) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    msg("antibot_global_limit", "&cСервер перегружен подключениями. Попробуй через минуту."));
            return;
        }

        // Reconnect-челлендж: заставляет бота переподключиться (боты часто не умеют).
        // Пропуск — только повторный вход в окне [1с; reconnect_seconds) после выдачи.
        if (perIp && reconnectSeconds > 0 && isAttackMode()) {
            Long issued = challengeIssued.get(ip);
            if (issued != null && now - issued >= 1000L && now - issued < reconnectSeconds * 1000L) {
                challengeIssued.remove(ip);
            } else {
                challengeIssued.put(ip, now);
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        msg("antibot_reconnect", "&eПроверка подключения: зайди ещё раз через пару секунд."));
                return;
            }
        }

        // Одновременно онлайн с IP: ограничиваем только новые аккаунты
        if (perIp && maxOnlinePerIp > 0 && onlineFrom(ip) >= maxOnlinePerIp && !isRegistered(uuid)) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    msg("antibot_ip_online_limit", "&cС твоего IP уже играет слишком много аккаунтов."));
            return;
        }

        // Мультиаккаунты: лимит аккаунтов, зарегистрированных с этого IP
        if (perIp && maxAccountsPerIp > 0 && !isRegistered(uuid)) {
            int count = accountStore.countByIpBlocking(ip);
            if (count >= maxAccountsPerIp) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        msg("antibot_multiaccount", "&cС этого IP уже зарегистрировано максимум аккаунтов."));
                return;
            }
        }

        synchronized (globalJoins) {
            globalJoins.addLast(now);
        }
    }

    /**
     * Одна сессия (D8): ник уже онлайн И вошёл в аккаунт → отказываем НОВОМУ
     * подключению, а не выкидываем играющего (в offline-mode иначе любой
     * выбивает игрока, зайдя под его ником). В online-mode личность
     * подтверждает Mojang — там решает ванилла.
     * @return true — вход отклонён.
     */
    private boolean checkSingleSession(AsyncPlayerPreLoginEvent e) {
        if (e.getName() == null || Bukkit.getOnlineMode()) {
            return false;
        }
        UUID online = onlineByName.get(e.getName().toLowerCase(Locale.ROOT));
        if (online == null || !(plugin instanceof RegisterPlugin)) {
            return false;
        }
        SessionManager sm = ((RegisterPlugin) plugin).getSessionManager();
        if (sm == null || !sm.isLoggedIn(online)) {
            return false;
        }
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                msg("already_online", "&cИгрок с таким ником уже на сервере."));
        return true;
    }

    /**
     * Фильтр ников с учётом Bedrock (D9): Floodgate-игроков (по UUID/API) не
     * фильтруем вовсе (у gamertag свои правила), а префикс Floodgate («.»,
     * «*» или username-prefix из конфига Floodgate) срезаем перед регуляркой.
     */
    private boolean nameAllowed(UUID uuid, String name) {
        if (name == null) {
            return false;
        }
        Pattern p = namePattern;
        if (p.matcher(name).matches()) {
            return true;
        }
        BedrockSupportService bs = plugin instanceof RegisterPlugin
                ? ((RegisterPlugin) plugin).getBedrockSupportService() : null;
        if (bs != null) {
            if (bs.looksLikeFloodgate(uuid)) {
                return true;
            }
            String stripped = bs.stripFloodgatePrefix(name);
            return !stripped.equals(name) && p.matcher(stripped).matches();
        }
        if (name.length() > 1 && (name.charAt(0) == '.' || name.charAt(0) == '*')) {
            return p.matcher(name.substring(1)).matches();
        }
        return false;
    }

    /** Зарегистрирован ли аккаунт (фоновый поток pre-login — чтение из БД допустимо). */
    private boolean isRegistered(UUID uuid) {
        try {
            return accountStore.isRegistered(uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    // Предзагрузку аккаунта на пре-логине делает AccountStore.Lifecycle (MONITOR)
    // при любом antibot.guard.enabled — второй запрос к БД здесь не нужен.

    /**
     * Текст сообщения с учётом lang-файлов:
     * config.yml → messages.* имеет приоритет, дальше lang/ru.yml, потом дефолт.
     * Кик-сообщения чистим от HEX-цветов (язык игрока ещё неизвестен — берём
     * серверный дефолт), поэтому используем только &-коды.
     */
    private String msg(String key, String def) {
        if (plugin instanceof RegisterPlugin && ((RegisterPlugin) plugin).getMessageService() != null) {
            String m = ((RegisterPlugin) plugin).getMessageService().message(key);
            if (m != null && !m.isEmpty() && !m.equals(key)) {
                return stripPrefix(m);
            }
        }
        return color(def);
    }

    private static String stripPrefix(String formatted) {
        // Для киков префикс не нужен
        return formatted;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoinCount(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        onlineByName.put(p.getName().toLowerCase(Locale.ROOT), p.getUniqueId());
        if (!enabled) {
            return;
        }
        String ip = IpUtil.getIp(plugin, p);
        if (ip.isEmpty()) {
            return;
        }
        String prev = countedIp.put(p.getUniqueId(), ip);
        if (prev != null) {
            onlineByIp.computeIfPresent(prev, (k, v) -> v <= 1 ? null : v - 1);
        }
        onlineByIp.merge(ip, 1, Integer::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitCount(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        onlineByName.remove(p.getName().toLowerCase(Locale.ROOT), uuid);
        String counted = countedIp.remove(uuid);
        if (counted != null) {
            onlineByIp.computeIfPresent(counted, (k, v) -> v <= 1 ? null : v - 1);
        }
        String ip = counted != null ? counted : IpUtil.getIp(plugin, p);
        if (!ip.isEmpty()) {
            lastIpByUuid.put(uuid, new LastIp(ip, System.currentTimeMillis()));
        }
        // Доверие 2FA сбрасываем всегда, независимо от antibot.guard
        if (plugin instanceof RegisterPlugin) {
            RegisterPlugin rp = (RegisterPlugin) plugin;
            if (rp.getTotpService() != null) {
                rp.getTotpService().forgetTrust(uuid);
            }
        }
    }

    /** Снять все временные баны IP (команда /authadmin unban). */
    public void clearBans() {
        bannedUntil.clear();
        banMessages.clear();
        failsByIp.clear();
        repeatBanned.clear();
        strikes.clear();
        saveBansAsync();
    }

    /**
     * Лимит подключений: сначала на IP (отказ не тратит общий лимит), потом
     * на весь сервер. Окна — текущая секунда и текущая минута.
     */
    private boolean rateAllow(String ip, long now) {
        long sec = now / 1000L;
        long min = now / 60_000L;
        if (ip != null && (rateIpPerSecond > 0 || rateIpPerMinute > 0)) {
            long[] w = rateIp.computeIfAbsent(ip, k -> new long[4]);
            synchronized (w) {
                if (w[0] != sec) {
                    w[0] = sec;
                    w[1] = 0;
                }
                if (w[2] != min) {
                    w[2] = min;
                    w[3] = 0;
                }
                if ((rateIpPerSecond > 0 && w[1] >= rateIpPerSecond)
                        || (rateIpPerMinute > 0 && w[3] >= rateIpPerMinute)) {
                    return false;
                }
                w[1]++;
                w[3]++;
            }
        }
        synchronized (rateGlobal) {
            if (rateGlobal[0] != sec) {
                rateGlobal[0] = sec;
                rateGlobal[1] = 0;
            }
            if (rateGlobal[2] != min) {
                rateGlobal[2] = min;
                rateGlobal[3] = 0;
            }
            if ((ratePerSecond > 0 && rateGlobal[1] >= ratePerSecond)
                    || (ratePerMinute > 0 && rateGlobal[3] >= ratePerMinute)) {
                return false;
            }
            rateGlobal[1]++;
            rateGlobal[3]++;
        }
        return true;
    }

    private String timeLeft(long until, long now) {
        if (until == PERMANENT) {
            return msg("time_forever", "навсегда");
        }
        long s = Math.max(1, (until - now + 999) / 1000L);
        return s >= 60 ? (s / 60) + " мин " + (s % 60) + " сек" : s + " сек";
    }

    /**
     * Баны, которые можно отдать в единый чёрный список: ступень не ниже
     * minLevel или навсегда. Формат строки: ip;доКогда(мс, -1 = навсегда).
     */
    public java.util.List<String> exportBans(int minLevel) {
        java.util.List<String> out = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> en : bannedUntil.entrySet()) {
            long until = en.getValue();
            if (until <= now) {
                continue;
            }
            long[] st = strikes.get(en.getKey());
            if (until == PERMANENT || (st != null && st[0] >= minLevel)) {
                out.add(en.getKey() + ";" + (until == PERMANENT ? -1 : until));
            }
        }
        return out;
    }

    // ── хранение банов: data/ip-bans.txt — «ip|доКогда|ступень|последнийПровал» ──

    private java.io.File bansFile() {
        return new java.io.File(plugin.getDataFolder(), "data/ip-bans.txt");
    }

    private void loadBans() {
        java.io.File f = bansFile();
        if (!f.isFile()) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            for (String line : java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String[] p = line.trim().split("\\|");
                if (p.length < 4 || p[0].isEmpty()) {
                    continue;
                }
                long until = Long.parseLong(p[1]);
                long level = Long.parseLong(p[2]);
                long last = Long.parseLong(p[3]);
                if (until == PERMANENT || until > now) {
                    bannedUntil.put(p[0], until);
                }
                if (level > 0 && now - last <= strikeForgetMs) {
                    strikes.put(p[0], new long[]{level, last});
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: data/ip-bans.txt не прочитан: " + t);
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean saveQueued = new java.util.concurrent.atomic.AtomicBoolean();

    /** Сохранить баны и ступени в фоне (несколько вызовов подряд — одна запись). */
    public void saveBansAsync() {
        if (!saveQueued.compareAndSet(false, true)) {
            return;
        }
        Scheduler.runAsync(plugin, () -> {
            saveQueued.set(false);
            saveBansNow();
        });
    }

    public void saveBansNow() {
        long now = System.currentTimeMillis();
        java.util.Set<String> ips = new java.util.HashSet<>(bannedUntil.keySet());
        ips.addAll(strikes.keySet());
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String ip : ips) {
            Long until = bannedUntil.get(ip);
            long[] st = strikes.get(ip);
            long u = until == null ? 0 : until;
            if ((u == 0 || (u != PERMANENT && u <= now)) && (st == null || now - st[1] > strikeForgetMs)) {
                continue;
            }
            lines.add(ip + "|" + u + "|" + (st == null ? 0 : st[0]) + "|" + (st == null ? 0 : st[1]));
        }
        try {
            java.io.File f = bansFile();
            f.getParentFile().mkdirs();
            java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: data/ip-bans.txt не сохранён: " + t);
        }
    }

    /** Сколько игроков сейчас с этого IP (для отчётов/команд). */
    public int onlineFrom(String ip) {
        Integer n = onlineByIp.get(IpUtil.normalize(ip));
        return n == null ? 0 : n;
    }

    private static String color(String s) {
        return s == null ? "" : org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    private static final int READY = 967612791;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x100a) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x100a) == READY;
    }
}
