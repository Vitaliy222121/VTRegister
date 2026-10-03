// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import me.vorchun.registerplugin.RegisterPlugin;
import me.vorchun.registerplugin.util.Scheduler;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

public final class TeleportService implements PluginMessageListener {

    private final JavaPlugin plugin;
    private final SessionManager sessionManager;

    private final Map<UUID, Location> savedLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> authorizedTeleports = new ConcurrentHashMap<>();
    private volatile boolean proxyMode = false;
    private volatile String proxyType = "bungeecord";
    private String lastLoggedProxyState;
    private boolean proxyTeleportEnabled = false;
    private String proxyTargetServer = "lobby";
    private Location lobbyLocation = null;

    // --- окружение прокси (считается в reload(), читается из любых потоков) ---
    // bungeeForwarding  — spigot.yml settings.bungeecord (legacy-forwarding, без подписи);
    // velocityForwarding — Paper velocity modern forwarding (подписан секретом);
    // velocitySecretSet  — секрет Velocity задан (или прочитать не удалось — Paper без него не стартует);
    // firewallOk         — proxy.firewall.enabled и allowed_ips без «весь интернет».
    private volatile boolean bungeeForwarding;
    private volatile boolean velocityForwarding;
    private volatile boolean velocitySecretSet;
    private volatile boolean firewallOk;
    private volatile String envSource = "";
    private volatile boolean bungeeGuardSeen;
    private volatile long lastServerListRequest;

    // автоопределение лобби по IP и безопасный перенос
    private String lobbyAddress = "";
    private boolean autoDetectName = true;
    private boolean safeTransfer = true;
    private boolean fallbackToLobbyLocation = true;
    private int retrySeconds = 3;
    // Сколько ждать ответа прокси до «сервер недоступен» + fallback (F120)
    private int transferTimeoutSec = 8;
    private final java.util.List<String> knownServers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, String> serverAddresses = new ConcurrentHashMap<>();

    // --- ожидание включения целевого сервера (proxy_server.wait_for_server) ---
    private boolean waitEnabled = false;
    private String waitHost = "";
    private int waitPort = 25565;
    private long waitTimeoutMs = 15 * 60_000L;
    private long waitCheckMs = 10_000L;
    private boolean waitBarEnabled = true;
    private final Map<UUID, Waiter> waiters = new ConcurrentHashMap<>();

    private static final class Waiter {
        String server;
        long deadline;
        org.bukkit.boss.BossBar bar;
        volatile me.vorchun.registerplugin.util.Scheduler.Task task;
    }

    public TeleportService(JavaPlugin plugin, SessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
        loadConfig();
        registerChannels();
    }

    /**
     * Канал "BungeeCord" (Bukkit сам мапит его в bungeecord:main) понимают и
     * BungeeCord/Waterfall, и Velocity с bungee-plugin-message-channel = true.
     * Канала "velocity:player" в Velocity не существует — Connect туда не работал.
     */
    private void registerChannels() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "BungeeCord", this);
    }

    /**
     * Ответы прокси: список серверов и их адреса.
     * BungeeCord присылает их в ответ на GetServers / ServerIP.
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        // Без прокси эти ответы может прислать только сам клиент — не верим
        if (!proxyMode) {
            return;
        }
        try {
            com.google.common.io.ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String sub = in.readUTF();
            if ("GetServers".equals(sub)) {
                String[] servers = in.readUTF().split(", ");
                knownServers.clear();
                java.util.Collections.addAll(knownServers, servers);
                return;
            }
            if ("ServerIP".equals(sub)) {
                String name = in.readUTF();
                String ip = in.readUTF();
                int port = in.readUnsignedShort();
                serverAddresses.put(name.toLowerCase(java.util.Locale.ROOT), ip + ":" + port);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Запросить у прокси список серверов и их адреса (для автоопределения лобби). */
    private void requestServerList(Player player) {
        if (player == null) {
            return;
        }
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("GetServers");
            player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
        } catch (Throwable ignored) {
        }
    }

    private void requestServerIp(Player player, String serverName) {
        if (player == null || serverName == null || serverName.isEmpty()) {
            return;
        }
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("ServerIP");
            out.writeUTF(serverName);
            player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Определить имя лобби-сервера по настроенному lobby_address.
     * Возвращает имя, если удалось сопоставить адрес; иначе null.
     */
    public String detectLobbyServerName(Player player) {
        String address = lobbyAddress;
        if (address == null || address.trim().isEmpty() || !autoDetectName) {
            return null;
        }
        String needle = address.trim().toLowerCase(java.util.Locale.ROOT);
        // 1) уже знаем адреса — ищем совпадение
        for (Map.Entry<String, String> e : serverAddresses.entrySet()) {
            if (e.getValue() != null && e.getValue().toLowerCase(java.util.Locale.ROOT).equals(needle)) {
                return e.getKey();
            }
        }
        // 2) ещё не знаем — попросим у прокси и подождём ответ на следующем входе.
        // Не чаще раза в минуту: иначе каждый вход шлёт N запросов ServerIP.
        long now = System.currentTimeMillis();
        if (now - lastServerListRequest < 60_000L) {
            return null;
        }
        lastServerListRequest = now;
        ensureBungeeChannel(player);
        if (knownServers.isEmpty()) {
            requestServerList(player);
        }
        for (String name : knownServers) {
            requestServerIp(player, name);
        }
        return null;
    }

    /**
     * Окружение прокси. Факты о forwarding читаются ВСЕГДА (даже при ручном
     * proxy.type) — от них зависит, можно ли верить UUID и IP игроков:
     *   spigot.yml         settings.bungeecord                  — legacy BungeeCord/Velocity-legacy;
     *   paper.yml          settings.velocity-support.enabled    — Velocity modern, Paper 1.13–1.18.2;
     *   config/paper-global.yml proxies.velocity.enabled        — Velocity modern, Paper 1.19+.
     * Сначала API (Bukkit.spigot().getConfig(), Server.Spigot#getPaperConfig),
     * затем сами файлы из корня сервера.
     */
    private void detectProxyMode() {
        boolean bungee = false;
        boolean velocity = false;
        boolean secretSet = true;
        StringBuilder src = new StringBuilder();

        // 1) spigot.yml (Bukkit.spigot() нет на чистом CraftBukkit — защищено)
        try {
            bungee = Bukkit.spigot().getConfig().getBoolean("settings.bungeecord", false);
            if (bungee) {
                src.append("spigot.yml");
            }
        } catch (Throwable ignored) {
            org.bukkit.configuration.file.YamlConfiguration y = loadServerYaml("spigot.yml");
            if (y != null && y.getBoolean("settings.bungeecord", false)) {
                bungee = true;
                src.append("spigot.yml");
            }
        }

        // 2) Paper через API: getPaperConfig() объявлен у Server.Spigot, берём метод
        // с публичного класса (у анонимной реализации invoke даёт IllegalAccess)
        try {
            Object paperConfig = org.bukkit.Server.Spigot.class.getMethod("getPaperConfig").invoke(Bukkit.spigot());
            if (paperConfig instanceof org.bukkit.configuration.ConfigurationSection) {
                org.bukkit.configuration.ConfigurationSection pc =
                        (org.bukkit.configuration.ConfigurationSection) paperConfig;
                if (pc.getBoolean("settings.velocity-support.enabled", false)) {
                    velocity = true;
                    secretSet = !isBlank(pc.getString("settings.velocity-support.secret", "?"));
                    append(src, "paper config (API)");
                } else if (pc.getBoolean("proxies.velocity.enabled", false)) {
                    velocity = true;
                    secretSet = !isBlank(pc.getString("proxies.velocity.secret", "?"));
                    append(src, "paper config (API)");
                }
            }
        } catch (Throwable ignored) {
        }

        // 3) Файлы: paper.yml (≤1.18.2) и config/paper-global.yml (1.19+)
        if (!velocity) {
            org.bukkit.configuration.file.YamlConfiguration y = loadServerYaml("paper.yml");
            if (y != null && y.getBoolean("settings.velocity-support.enabled", false)) {
                velocity = true;
                secretSet = !isBlank(y.getString("settings.velocity-support.secret", "?"));
                append(src, "paper.yml");
            }
        }
        if (!velocity) {
            org.bukkit.configuration.file.YamlConfiguration y = loadServerYaml("config/paper-global.yml");
            if (y != null && y.getBoolean("proxies.velocity.enabled", false)) {
                velocity = true;
                secretSet = !isBlank(y.getString("proxies.velocity.secret", "?"));
                append(src, "config/paper-global.yml");
            }
        }

        bungeeForwarding = bungee;
        velocityForwarding = velocity;
        velocitySecretSet = secretSet;
        firewallOk = computeFirewallOk();
        bungeeGuardSeen = lookupBungeeGuard();

        // 4) Режим: ручной proxy.type имеет приоритет; "none" выключает перенос через прокси
        String manualType = plugin.getConfig().getString("proxy.type", "auto");
        String mt = manualType == null ? "auto" : manualType.trim().toLowerCase(java.util.Locale.ROOT);
        if (mt.isEmpty() || mt.equals("auto")) {
            if (velocity) {
                proxyMode = true;
                proxyType = "velocity";
            } else if (bungee) {
                proxyMode = true;
                proxyType = "bungeecord";
            } else {
                // Старые ключи 1.0.x
                proxyMode = plugin.getConfig().getBoolean("proxy_mode", false)
                        || plugin.getConfig().getBoolean("proxy.enabled", false);
                String t = plugin.getConfig().getString("proxy_type", "bungeecord");
                proxyType = (t == null ? "bungeecord" : t).toLowerCase(java.util.Locale.ROOT);
                if (proxyMode) {
                    append(src, "config.yml proxy_mode");
                }
            }
        } else if (mt.equals("none") || mt.equals("off") || mt.equals("false") || mt.equals("standalone")) {
            proxyMode = false;
            proxyType = "none";
            append(src, "config.yml proxy.type=none");
        } else {
            proxyMode = true;
            proxyType = mt.startsWith("velo") ? "velocity" : "bungeecord";
            append(src, "config.yml proxy.type=" + mt);
        }
        envSource = src.length() == 0 ? "-" : src.toString();

        // reload() вызывается несколько раз при старте — логируем только при смене
        String state = proxyMode + ":" + proxyType + ":" + bungee + ":" + velocity + ":" + secretSet
                + ":" + firewallOk + ":" + bungeeGuardSeen + ":" + envSource;
        if (!state.equals(lastLoggedProxyState)) {
            lastLoggedProxyState = state;
            logEnvironment();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(s);
    }

    /**
     * YAML из корня сервера. Корень = рабочая папка; на случай --world-dir
     * пробуем ещё папку миров и её родителя. null — файла нет/не читается.
     */
    private org.bukkit.configuration.file.YamlConfiguration loadServerYaml(String rel) {
        java.util.List<java.io.File> roots = new java.util.ArrayList<>();
        roots.add(new java.io.File("."));
        try {
            java.io.File wc = Bukkit.getWorldContainer();
            if (wc != null) {
                roots.add(wc);
                if (wc.getAbsoluteFile().getParentFile() != null) {
                    roots.add(wc.getAbsoluteFile().getParentFile());
                }
            }
        } catch (Throwable ignored) {
        }
        for (java.io.File root : roots) {
            java.io.File f = new java.io.File(root, rel);
            if (!f.isFile()) {
                continue;
            }
            try {
                org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
                y.load(f);
                return y;
            } catch (Throwable t) {
                plugin.getLogger().warning("Не удалось прочитать " + f.getPath() + ": " + t.getMessage());
            }
        }
        return null;
    }

    /** Файрвол прокси включён и реально ограничивает (не 0.0.0.0/0, не пустой). */
    private boolean computeFirewallOk() {
        if (!plugin.getConfig().getBoolean("proxy.firewall.enabled", false)) {
            return false;
        }
        java.util.List<String> ips = plugin.getConfig().getStringList("proxy.firewall.allowed_ips");
        if (ips == null || ips.isEmpty()) {
            return false;
        }
        boolean any = false;
        for (String ip : ips) {
            String n = me.vorchun.registerplugin.util.IpUtil.normalize(ip);
            if (n.isEmpty()) {
                continue;
            }
            if (n.equals("*") || n.equals("0.0.0.0") || n.equals("::") || n.endsWith("/0")) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * BungeeGuard проверяет токен в хендшейке — подделать UUID/IP нельзя.
     * Значение считается в reload(): ipsTrusted() зовётся из pre-login
     * (асинхронный поток) на каждый вход — PluginManager там не трогаем.
     */
    private boolean bungeeGuardActive() {
        return bungeeGuardSeen;
    }

    private static boolean lookupBungeeGuard() {
        try {
            // Плагин может включиться позже нас — достаточно, что он загружен
            return Bukkit.getPluginManager().getPlugin("BungeeGuard") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Сервер получает настоящие IP/UUID клиентов от прокси (любой вид forwarding). */
    public boolean isForwarding() {
        return bungeeForwarding || velocityForwarding;
    }

    /**
     * Forwarding защищён от подделки: Velocity modern (подпись секретом) или
     * legacy BungeeCord + BungeeGuard / включённый proxy.firewall.
     * Legacy без защиты: любой, кто достучался до порта бэкенда, пишет в
     * хендшейк любой UUID и IP.
     */
    public boolean isForwardingSecure() {
        if (velocityForwarding) {
            return velocitySecretSet;
        }
        if (bungeeForwarding) {
            return firewallOk || bungeeGuardActive();
        }
        return false;
    }

    /**
     * Можно ли верить IP игроков (IpUtil.ipsTrusted): без прокси — да;
     * за прокси — только при защищённом forwarding. Иначе все per-IP лимиты,
     * баны и IP-сессии пропускаются (IP либо общий, либо поддельный).
     */
    public boolean ipsTrusted() {
        if (isForwarding()) {
            return isForwardingSecure();
        }
        return !proxyMode;
    }

    /**
     * Можно ли верить UUID игрока (премиум-автологин по UUID): без прокси —
     * да (UUID выдаёт сам сервер); за прокси — только при защищённом forwarding.
     */
    public boolean identityTrusted() {
        if (isForwarding()) {
            return isForwardingSecure();
        }
        return !proxyMode;
    }

    /** Одна строка для /authadmin и логов. */
    public String describeEnvironment() {
        String fw = velocityForwarding ? "Velocity modern" + (velocitySecretSet ? "" : " (секрет пуст!)")
                : bungeeForwarding ? "BungeeCord legacy" + (bungeeGuardActive() ? " + BungeeGuard"
                        : firewallOk ? " + proxy.firewall" : " БЕЗ ЗАЩИТЫ")
                : "нет";
        return "прокси " + (proxyMode ? "включён (" + proxyType + ")" : "выключен")
                + ", источник: " + envSource
                + ", IP-forwarding: " + fw
                + ", IP игроков: " + (ipsTrusted() ? "доверяем" : "НЕ доверяем (лимиты/баны/сессии по IP выключены)")
                + ", премиум по UUID: " + (identityTrusted() ? "разрешён" : "запрещён");
    }

    /** Текущая проблема настройки прокси (null — всё в порядке). Повторяется в консоль раз в минуту. */
    private volatile String proxyProblem;
    private volatile Scheduler.Task problemTask;

    private void logEnvironment() {
        plugin.getLogger().info("Окружение: " + describeEnvironment());
        String p = null;
        if (bungeeForwarding && !velocityForwarding && !isForwardingSecure()) {
            p = "spigot.yml bungeecord: true без BungeeGuard и без proxy.firewall — "
                    + "бэкенд принимает поддельный хендшейк (любой UUID и IP). Премиум-автологин, "
                    + "IP-сессии и лимиты/баны по IP ОТКЛЮЧЕНЫ. Исправь одно из: "
                    + "proxy.firewall.enabled: true + allowed_ips: [IP прокси]; плагин BungeeGuard; "
                    + "Velocity modern forwarding. Подробно — PROXY_SETUP.txt.";
        } else if (velocityForwarding && !velocitySecretSet) {
            p = "Velocity modern forwarding включён, но секрет пуст — "
                    + "премиум-автологин и проверки по IP отключены.";
        } else if (proxyMode && !isForwarding()) {
            p = "Режим прокси включён, но IP-forwarding не найден "
                    + "(spigot.yml settings.bungeecord / paper velocity). У всех игроков адрес прокси: "
                    + "лимиты/баны/сессии по IP и премиум по UUID отключены. См. PROXY_SETUP.txt.";
        }
        proxyProblem = p;
        if (p != null) {
            spamProblem(p);
        }
        if (problemTask == null) {
            // Раз в минуту: пока настройка не исправлена — напоминаем в консоль;
            // плюс ловим «сервер за прокси, но прокси не настроен» по адресам игроков.
            problemTask = Scheduler.runSyncTimer(plugin, this::problemTick, 1200L, 1200L);
        }
    }

    private void spamProblem(String p) {
        plugin.getLogger().severe("==================== VTRegister: ПРОКСИ НЕ НАСТРОЕН ====================");
        plugin.getLogger().severe(p);
        plugin.getLogger().severe("=========================================================================");
    }

    private void problemTick() {
        if (!plugin.getConfig().getBoolean("proxy.warn_misconfigured", true)) {
            return;
        }
        String p = proxyProblem;
        if (p == null && !proxyMode) {
            // Прокси без переадресации: у РАЗНЫХ игроков один и тот же адрес
            // 127.x/10.x/192.168.x (адрес прокси). Один игрок с такого адреса —
            // это владелец через localhost или друг по LAN/ZeroTier, не прокси:
            // раньше на него каждую минуту сыпалась ошибка в консоль.
            Map<String, String> seen = new HashMap<>();
            for (Player pl : Bukkit.getOnlinePlayers()) {
                java.net.InetSocketAddress a = pl.getAddress();
                java.net.InetAddress ia = a == null ? null : a.getAddress();
                if (ia == null || !(ia.isLoopbackAddress() || ia.isSiteLocalAddress())) {
                    continue;
                }
                String first = seen.putIfAbsent(ia.getHostAddress(), pl.getName());
                if (first != null) {
                    p = "Игроки " + first + " и " + pl.getName() + " зашли с одного адреса " + ia.getHostAddress()
                            + " — похоже, сервер стоит за прокси (Velocity/BungeeCord), но прокси-режим "
                            + "не настроен. Включи переадресацию: Velocity — paper.yml settings.velocity-support "
                            + "(Paper ≤1.18.2) или config/paper-global.yml proxies.velocity (1.19+); "
                            + "BungeeCord — spigot.yml settings.bungeecord: true. Инструкция — PROXY_SETUP.txt.";
                    break;
                }
            }
        }
        if (p != null) {
            spamProblem(p);
        }
    }

    private void loadConfig() {
        reload();
    }

    public void reload() {
        detectProxyMode();

        proxyTeleportEnabled = plugin.getConfig().getBoolean("proxy_server.enabled", false);
        proxyTargetServer = plugin.getConfig().getString("proxy_server.server_name", "lobby");
        lobbyAddress = plugin.getConfig().getString("proxy_server.lobby_address", "");
        autoDetectName = plugin.getConfig().getBoolean("proxy_server.auto_detect_name", true);
        safeTransfer = plugin.getConfig().getBoolean("proxy_server.safe_transfer", true);
        fallbackToLobbyLocation = plugin.getConfig().getBoolean("proxy_server.fallback_to_lobby_location", true);
        retrySeconds = Math.max(1, plugin.getConfig().getInt("proxy_server.retry_seconds", 3));
        transferTimeoutSec = Math.max(retrySeconds + 1,
                plugin.getConfig().getInt("proxy_server.transfer_timeout_seconds", 8));

        waitEnabled = plugin.getConfig().getBoolean("proxy_server.wait_for_server.enabled", false);
        waitHost = plugin.getConfig().getString("proxy_server.wait_for_server.host", "");
        waitPort = Math.max(1, plugin.getConfig().getInt("proxy_server.wait_for_server.port", 25565));
        waitTimeoutMs = Math.max(30, plugin.getConfig().getInt("proxy_server.wait_for_server.timeout_minutes", 15)) * 60_000L;
        waitCheckMs = Math.max(3, plugin.getConfig().getInt("proxy_server.wait_for_server.check_interval_seconds", 10)) * 1000L;
        waitBarEnabled = plugin.getConfig().getBoolean("proxy_server.wait_for_server.bossbar", true);

        // proxy_security из старых инструкций никогда не работал — подсказываем настоящий ключ
        if (plugin.getConfig().getBoolean("proxy_security.enabled", false)
                && !plugin.getConfig().getBoolean("proxy.firewall.enabled", false)) {
            plugin.getLogger().warning("proxy_security.* не существует и ни на что не влияет. "
                    + "Защита от прямого входа мимо прокси — proxy.firewall.enabled: true и "
                    + "proxy.firewall.allowed_ips: [IP прокси, как его видит сервер].");
        }

        if (plugin.getConfig().getBoolean("lobby.enabled", false)) {
            String worldName = plugin.getConfig().getString("lobby.world", "world");
            double x = plugin.getConfig().getDouble("lobby.x", 0);
            double y = plugin.getConfig().getDouble("lobby.y", 100);
            double z = plugin.getConfig().getDouble("lobby.z", 0);
            float yaw = (float) plugin.getConfig().getDouble("lobby.yaw", 0);
            float pitch = (float) plugin.getConfig().getDouble("lobby.pitch", 0);

            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                lobbyLocation = new Location(world, x, y, z, yaw, pitch);
            }
        } else {
            lobbyLocation = null;
        }
    }

    public boolean isProxyConnectionValid(Player player) {
        return player != null && player.getAddress() != null && player.getAddress().getAddress() != null;
    }

    public void saveJoinLocation(Player player) {
        saveJoinLocation(player, player.getLocation());
    }

    public void saveJoinLocation(Player player, org.bukkit.Location where) {
        if (proxyMode && !sessionManager.isLoggedIn(player.getUniqueId())) {
            if (!isProxyConnectionValid(player)) {
                plugin.getLogger().warning("Не удалось сохранить точку входа для игрока " + player.getName() + ": адрес соединения недоступен");
                return;
            }
            savedLocations.put(player.getUniqueId(), (where != null ? where : player.getLocation()).clone());
        }
    }

    public void teleportToSafeLocation(Player player) {
        if (!player.isOnline()) {
            return;
        }

        Location safeLoc = findSafeLocation(player);
        if (safeLoc != null) {
            Scheduler.runAtEntity(plugin, player, () -> {
                if (player.isOnline()) {
                    authorizeTeleport(player.getUniqueId());
                    me.vorchun.registerplugin.util.Compat.teleport(player, safeLoc);
                }
            });
        }
    }

    public void teleportToLobby(Player player) {
        if (!player.isOnline()) {
            return;
        }

        if (proxyTeleportEnabled && proxyMode) {
            if (!isProxyConnectionValid(player)) {
                plugin.getLogger().warning("Не удалось перевести игрока " + player.getName() + " через прокси: адрес соединения недоступен");
                fallbackLocal(player);
                return;
            }
            // Имя сервера: сначала пробуем определить по lobby_address
            String detected = detectLobbyServerName(player);
            String target = detected != null ? detected : proxyTargetServer;
            if (detected != null && !detected.equalsIgnoreCase(proxyTargetServer)) {
                plugin.getLogger().info("Лобби определено по адресу " + lobbyAddress + " → сервер '" + detected + "'");
            }
            transferSafely(player, target);
            return;
        }

        fallbackLocal(player);
    }

    /**
     * Безопасный перенос через прокси (fail-safe с повтором):
     *   t+2т — снять ограничения лобби, очистить эффекты, отправить Connect;
     *   t+retry — игрок всё ещё здесь → повторная отправка Connect;
     *   t+2*retry — снова неудача → сообщение «сервер недоступен» + fallback.
     * Пока идёт перенос игрок неуязвим и стоит на безопасной точке.
     */
    private void transferSafely(Player player, String serverName) {
        final UUID uuid = player.getUniqueId();
        if (!sessionManager.isLoggedIn(uuid)) {
            refuseUnauthed(player, serverName);
            return;
        }
        if (safeTransfer) {
            Scheduler.runAtEntity(plugin, player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                // Платформа: не даём упасть и умереть во время переключения
                player.setAllowFlight(true);
                player.setFlying(true);
                player.setInvulnerable(true);
                player.setFallDistance(0f);
                Location anchor = lobbyLocation != null && lobbyLocation.getWorld() != null
                        ? lobbyLocation
                        : player.getLocation();
                authorizeTeleport(uuid);
                me.vorchun.registerplugin.util.Compat.teleport(player, anchor);
            });
        }
        connectWithRetry(player, serverName);
    }

    /**
     * Перенос на другой сервер сети ДО входа в аккаунт запрещён (иначе прокси
     * пустит неавторизованного в лобби). Игрок остаётся здесь на безопасной точке.
     */
    private void refuseUnauthed(Player player, String serverName) {
        plugin.getLogger().warning("Перенос " + player.getName() + " → '" + serverName
                + "' отменён: игрок ещё не вошёл в аккаунт (перенос только после входа)");
        fallbackLocal(player);
    }

    /**
     * Отправка Connect с fail-safe: первая попытка через 5 тиков (к этому
     * моменту мост уже отправил прокси AUTH — иначе модуль прокси отклонит
     * смену сервера), повтор через retry_seconds, затем уведомление и fallback.
     */
    public void connectWithRetry(Player player, String serverName) {
        final UUID uuid = player.getUniqueId();
        if (!sessionManager.isLoggedIn(uuid)) {
            refuseUnauthed(player, serverName);
            return;
        }
        Scheduler.runAtEntityLater(plugin, player, () -> {
            if (!player.isOnline()) {
                return;
            }
            clearForTransfer(player);
            sendConnect(player, serverName);
        }, 5L);
        // Fail-safe №1: повторная отправка через retry_seconds
        Scheduler.runAtEntityLater(plugin, player, () -> {
            if (player.isOnline()) {
                plugin.getLogger().info("Повторный Connect для " + player.getName()
                        + " → '" + serverName + "' (первая попытка не сработала)");
                sendConnect(player, serverName);
            }
        }, 5L + retrySeconds * 20L);
        // Fail-safe №2: за transfer_timeout_seconds прокси так и не перенёс —
        // уведомляем и возвращаем
        Scheduler.runAtEntityLater(plugin, player, () -> {
            if (!player.isOnline()) {
                return;
            }
            plugin.getLogger().warning("Игрок " + player.getName() + " всё ещё на этом сервере "
                    + "после двух Connect в '" + serverName + "'. Проверь имя сервера и прокси.");
            if (player instanceof Player) {
                sendMessage(player, "proxy_transfer_failed",
                        "&cЦелевой сервер временно недоступен. Попробуй перезайти позже.");
            }
            if (safeTransfer) {
                player.setInvulnerable(false);
                if (fallbackToLobbyLocation) {
                    player.setAllowFlight(false);
                    player.setFlying(false);
                }
            }
            if (fallbackToLobbyLocation) {
                fallbackLocal(player);
            }
        }, 5L + transferTimeoutSec * 20L);
    }

    /** Снять лобби-ограничения перед Connect: эффекты темноты/слепоты, падение. */
    private void clearForTransfer(Player player) {
        try {
            me.vorchun.registerplugin.util.Compat.clearAuthDarkness(player);
            for (org.bukkit.potion.PotionEffect eff : player.getActivePotionEffects()) {
                org.bukkit.potion.PotionEffectType t = eff.getType();
                if (t == null) {
                    continue;
                }
                if (t.equals(org.bukkit.potion.PotionEffectType.BLINDNESS)
                        || t.getName().equalsIgnoreCase("confusion") || t.getName().equalsIgnoreCase("nausea")
                        || t.getName().equalsIgnoreCase("darkness")) {
                    player.removePotionEffect(t);
                }
            }
            player.setFallDistance(0f);
        } catch (Throwable ignored) {
        }
    }

    private void sendMessage(Player player, String key, String def) {
        String text = null;
        try {
            if (plugin instanceof RegisterPlugin
                    && ((RegisterPlugin) plugin).getMessageService() != null) {
                text = ((RegisterPlugin) plugin).getMessageService().message(key);
            }
        } catch (Throwable ignored) {
        }
        if (text == null || text.isEmpty()) {
            text = org.bukkit.ChatColor.translateAlternateColorCodes('&', def);
        }
        player.sendMessage(text);
    }

    private String msg(String key, String def) {
        String text = null;
        try {
            if (plugin instanceof RegisterPlugin
                    && ((RegisterPlugin) plugin).getMessageService() != null) {
                text = ((RegisterPlugin) plugin).getMessageService().message(key);
            }
        } catch (Throwable ignored) {
        }
        return text == null || text.isEmpty() ? def : text;
    }

    private static String colorize(String s) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    /**
     * Connect через канал "BungeeCord" — его понимают BungeeCord/Waterfall и
     * Velocity (bungee-plugin-message-channel = true). Только после входа.
     */
    private void sendConnect(Player player, String serverName) {
        if (!sessionManager.isLoggedIn(player.getUniqueId())) {
            return;
        }
        try {
            ensureBungeeChannel(player);
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(serverName);
            player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
            plugin.getLogger().info("Игрок " + player.getName() + " → Connect '" + serverName + "'");
        } catch (Throwable t) {
            plugin.getLogger().warning("Connect для " + player.getName() + " не отправлен: " + t);
        }
    }

    private volatile java.lang.reflect.Method addChannel;
    private volatile boolean addChannelResolved;

    /**
     * Bukkit молча выбрасывает plugin message, если клиент (точнее прокси за
     * него) ещё не прислал REGISTER канала bungeecord:main — так бывает сразу
     * после входа. Прокси перехватывает этот канал сам, регистрация ему не
     * нужна, поэтому добавляем канал игроку сами (CraftPlayer#addChannel).
     */
    private void ensureBungeeChannel(Player player) {
        try {
            java.util.Set<String> ch = player.getListeningPluginChannels();
            if (ch.contains("bungeecord:main") || ch.contains("BungeeCord")) {
                return;
            }
            if (!addChannelResolved) {
                addChannelResolved = true;
                addChannel = player.getClass().getMethod("addChannel", String.class);
            }
            java.lang.reflect.Method m = addChannel;
            if (m != null) {
                m.invoke(player, "bungeecord:main");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Локальная безопасная точка: lobby-локация → сохранённая точка входа → спавн мира. */
    private void fallbackLocal(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        // Точки в check-мире (мир проверки над бездной) непригодны:
        // ни сохранённая точка входа, ни его спавн — фильтруем их.
        String cw = plugin.getConfig().getString("antibot.world_name", "auth_verify");
        Location target = lobbyLocation;
        if (target != null && target.getWorld() != null
                && cw != null && cw.equalsIgnoreCase(target.getWorld().getName())) {
            target = null;
        }
        if (target == null || target.getWorld() == null) {
            target = savedLocations.remove(player.getUniqueId());
            if (target != null && target.getWorld() != null
                    && cw != null && cw.equalsIgnoreCase(target.getWorld().getName())) {
                target = null;
            }
        }
        if (target == null || target.getWorld() == null) {
            org.bukkit.World w = player.getWorld();
            if (w == null || (cw != null && cw.equalsIgnoreCase(w.getName()))) {
                w = null;
                for (org.bukkit.World cand : org.bukkit.Bukkit.getWorlds()) {
                    if (cw == null || !cw.equalsIgnoreCase(cand.getName())) {
                        w = cand;
                        break;
                    }
                }
            }
            if (w != null) {
                target = w.getSpawnLocation();
            }
        }
        if (target == null || target.getWorld() == null) {
            return;
        }
        final Location dest = target;
        Scheduler.runAtEntity(plugin, player, () -> {
            if (player.isOnline()) {
                authorizeTeleport(player.getUniqueId());
                me.vorchun.registerplugin.util.Compat.teleport(player, dest);
                player.setFallDistance(0f);
            }
        });
    }

    /**
     * Перенос игрока на конкретный сервер прокси по имени.
     * Тип прокси (BungeeCord/Velocity) выбирается автоматически по конфигу.
     * Если прокси-режим выключен или имя пустое — ничего не делаем.
     */
    public void transferToServer(Player player, String serverName) {
        if (player == null || !player.isOnline() || serverName == null || serverName.isEmpty()) {
            return;
        }
        if (!proxyMode) {
            plugin.getLogger().warning("transferToServer('" + serverName + "') вызван при выключенном прокси-режиме — игрок остаётся здесь");
            return;
        }
        if (!sessionManager.isLoggedIn(player.getUniqueId())) {
            refuseUnauthed(player, serverName);
            return;
        }
        if (waitEnabled && waitHost != null && !waitHost.isEmpty()) {
            probeThenConnect(player, serverName);
            return;
        }
        connectWithRetry(player, serverName);
    }

    // ---------- ожидание включения целевого сервера ----------

    /** Асинхронный TCP-проб хоста; если сервер выключен — ждём в лобби. */
    private void probeThenConnect(Player player, String serverName) {
        final UUID uuid = player.getUniqueId();
        me.vorchun.registerplugin.util.Scheduler.runAsync(plugin, () -> {
            boolean up = pingServer();
            me.vorchun.registerplugin.util.Scheduler.runAtEntity(plugin, player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (up) {
                    connectWithRetry(player, serverName);
                } else {
                    startWait(player, serverName);
                }
            });
        });
    }

    /** TCP-коннект к waitHost:waitPort — 3с таймаут, без протокола. */
    private boolean pingServer() {
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new java.net.InetSocketAddress(waitHost, waitPort), 3000);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void startWait(Player player, String serverName) {
        final UUID uuid = player.getUniqueId();
        cancelWait(uuid);
        Waiter w = new Waiter();
        w.server = serverName;
        w.deadline = System.currentTimeMillis() + waitTimeoutMs;
        if (waitBarEnabled) {
            try {
                w.bar = Bukkit.createBossBar("", org.bukkit.boss.BarColor.YELLOW,
                        org.bukkit.boss.BarStyle.SOLID);
                w.bar.addPlayer(player);
            } catch (Throwable ignored) {
            }
        }
        // Парковка в лобби-очереди (там же ждут проверки) или fallback-точке
        Location waitLoc = waitLocation();
        if (waitLoc != null && waitLoc.getWorld() != null) {
            authorizeTeleport(uuid);
            me.vorchun.registerplugin.util.Compat.teleport(player, waitLoc);
        }
        player.sendMessage(colorize(msg("proxy_wait_start",
                "&eСервер &f%server% &eсейчас недоступен — жди включения, я перекину автоматически.")
                .replace("%server%", serverName)));
        waiters.put(uuid, w);
        // Таймер глобальный, а работа с игроком (бар, кик, Connect) — в его потоке (Folia)
        w.task = me.vorchun.registerplugin.util.Scheduler.runSyncTimer(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) {
                cancelWait(uuid);
                return;
            }
            me.vorchun.registerplugin.util.Scheduler.runAtEntity(plugin, online, () -> tickWait(uuid));
        }, waitCheckMs / 50L, waitCheckMs / 50L);
    }

    private void tickWait(UUID uuid) {
        Waiter w = waiters.get(uuid);
        if (w == null) {
            return;
        }
        Player p = Bukkit.getPlayer(uuid);
        if (p == null || !p.isOnline()) {
            cancelWait(uuid);
            return;
        }
        long left = w.deadline - System.currentTimeMillis();
        if (left <= 0) {
            cancelWait(uuid);
            String msg = msg("proxy_wait_timeout_kick",
                    "&cЦелевой сервер так и не включился за 15 минут — тебя кикнуло.");
            p.kickPlayer(msg);
            return;
        }
        if (w.bar != null) {
            try {
                long sec = left / 1000L;
                w.bar.setTitle(colorize(msg("proxy_wait_bar",
                        "&eЖдём сервер… осталось %mm%:%ss%"))
                        .replace("%mm%", String.format("%02d", sec / 60))
                        .replace("%ss%", String.format("%02d", sec % 60)));
                w.bar.setProgress(Math.max(0.0, Math.min(1.0,
                        (double) left / (double) waitTimeoutMs)));
            } catch (Throwable ignored) {
            }
        }
        me.vorchun.registerplugin.util.Scheduler.runAsync(plugin, () -> {
            boolean up = pingServer();
            if (!up) {
                return;
            }
            me.vorchun.registerplugin.util.Scheduler.runAtEntity(plugin, p, () -> {
                Waiter cur = waiters.get(uuid);
                if (cur == null || !p.isOnline()) {
                    return;
                }
                String srv = cur.server;
                cancelWait(uuid);
                sendMessage(p, "proxy_wait_online",
                        "&aСервер включился — переношу тебя!");
                connectWithRetry(p, srv);
            });
        });
    }

    /** Снять ожидание: бар + таймер (выход, успешный трансфер, отключение). */
    public void cancelWait(UUID uuid) {
        Waiter w = waiters.remove(uuid);
        if (w == null) {
            return;
        }
        if (w.task != null) {
            try {
                w.task.cancel();
            } catch (Throwable ignored) {
            }
        }
        if (w.bar != null) {
            try {
                w.bar.removeAll();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Выключение плагина: снять все ожидания и их таймеры/бары. */
    public void cancelAllWaits() {
        for (UUID u : new java.util.ArrayList<>(waiters.keySet())) {
            cancelWait(u);
        }
    }

    /** Точка ожидания: лобби очереди антибота, иначе lobby.*, иначе на месте. */
    private Location waitLocation() {
        try {
            if (plugin instanceof me.vorchun.registerplugin.RegisterPlugin) {
                me.vorchun.registerplugin.service.AntiBotService ab =
                        ((me.vorchun.registerplugin.RegisterPlugin) plugin).getAntiBotService();
                if (ab != null) {
                    Location l = ab.lobbySpawnLocation();
                    if (l != null) {
                        return l;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return lobbyLocation;
    }


    public void checkVoidFall(Player player) {
        if (sessionManager.isLoggedIn(player.getUniqueId())) {
            return;
        }

        Location loc = player.getLocation();
        int minY = me.vorchun.registerplugin.util.Compat.getWorldMinHeight(loc.getWorld());
        if (loc.getY() < minY - 10) {
            teleportToSafeLocation(player);
        }
    }

    private Location findSafeLocation(Player player) {
        Location original = player.getLocation();
        World world = original.getWorld();
        if (world == null) {
            return null;
        }

        int x = original.getBlockX();
        int z = original.getBlockZ();
        int minHeight = me.vorchun.registerplugin.util.Compat.getWorldMinHeight(world);

        for (int y = world.getMaxHeight() - 1; y > minHeight; y--) {
            Location check = new Location(world, x, y, z);
            if (!check.getBlock().isEmpty() && check.getBlock().getType().isSolid()) {
                Location safe = check.clone().add(0.5, 1, 0.5);
                safe.setYaw(original.getYaw());
                safe.setPitch(original.getPitch());
                return safe;
            }
        }

        return world.getSpawnLocation();
    }

    public void clearSavedLocation(UUID uuid) {
        savedLocations.remove(uuid);
        authorizedTeleports.remove(uuid);
    }

    /**
     * Разрешить один ближайший телепорт игроку (для внутренних телепортов плагина:
     * мир антибот-проверки, возврат, безопасная точка).
     */
    public void authorizeTeleport(UUID uuid) {
        if (uuid == null || !ready()) {
            return;
        }
        authorizedTeleports.put(uuid, System.currentTimeMillis() + 5000L);
    }

    public boolean consumeAuthorizedTeleport(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Long expiresAt = authorizedTeleports.remove(uuid);
        return expiresAt != null && expiresAt >= System.currentTimeMillis();
    }

    public boolean isProxyMode() {
        return proxyMode;
    }

    public String getProxyType() {
        return proxyType;
    }

    private static final int READY = 1124856740;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x101f) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x101f) == READY;
    }
}
