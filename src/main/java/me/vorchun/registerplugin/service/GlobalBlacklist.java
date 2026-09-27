// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.sun.net.httpserver.HttpServer;

import me.vorchun.registerplugin.util.Scheduler;

/**
 * Единый чёрный список между серверами (global_blacklist, по умолчанию выкл).
 *
 * Каждый сервер по желанию ОТДАЁТ свой список (встроенный HTTP, доступ по
 * токену) и ЗАБИРАЕТ списки партнёров. IP/ник из чужого списка не пускается
 * на сервер — бот или читер, забаненный на одном сервере, не заходит на
 * остальные. Отдаются только: IP с баном антибота от ступени share_from_tier
 * (или навсегда) и, по share_server_bans, баны самого сервера (/ban, /ban-ip).
 */
public final class GlobalBlacklist {

    private static final Pattern IP = Pattern.compile("^[0-9a-fA-F:.]{3,45}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.*]{1,32}$");
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private static final class Entry {
        final long until;
        final String source;

        Entry(long until, String source) {
            this.until = until;
            this.source = source;
        }
    }

    private static final class Peer {
        final String url;
        final String token;

        Peer(String url, String token) {
            this.url = url;
            this.token = token;
        }
    }

    private final JavaPlugin plugin;
    private final IntFunction<List<String>> localBans;

    private volatile boolean enabled;
    private volatile String serverName = "server";
    private volatile int shareFromTier = 3;
    private volatile boolean shareServerBans = true;
    private volatile boolean blockNames = true;
    private volatile boolean ignorePrivate = true;
    private volatile int maxPerPeer = 50_000;
    private volatile List<Peer> peers = Collections.emptyList();

    private volatile Map<String, Entry> remoteIps = Collections.emptyMap();
    private volatile Map<String, Entry> remoteNames = Collections.emptyMap();
    private volatile byte[] published = new byte[0];

    private volatile HttpServer http;
    private volatile String token = "";
    private volatile Scheduler.Task pullTask;
    private volatile Scheduler.Task snapTask;
    private final Map<String, Long> lastRequest = new ConcurrentHashMap<>();
    private final Map<String, Long> peerErrorLogged = new ConcurrentHashMap<>();

    /** localBans(minTier) → строки «ip;доКогда» (AntiBotGuard#exportBans). */
    public GlobalBlacklist(JavaPlugin plugin, IntFunction<List<String>> localBans) {
        this.plugin = plugin;
        this.localBans = localBans;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void reload() {
        stop();
        enabled = plugin.getConfig().getBoolean("global_blacklist.enabled", false);
        if (!enabled) {
            remoteIps = Collections.emptyMap();
            remoteNames = Collections.emptyMap();
            return;
        }
        serverName = plugin.getConfig().getString("global_blacklist.server_name", "server");
        shareFromTier = Math.max(1, plugin.getConfig().getInt("global_blacklist.share_from_tier", 3));
        shareServerBans = plugin.getConfig().getBoolean("global_blacklist.share_server_bans", true);
        blockNames = plugin.getConfig().getBoolean("global_blacklist.block_names", true);
        ignorePrivate = plugin.getConfig().getBoolean("global_blacklist.ignore_private_ips", true);
        maxPerPeer = Math.max(100, plugin.getConfig().getInt("global_blacklist.max_entries_per_peer", 50_000));
        List<Peer> ps = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("global_blacklist.peers")) {
            Object u = m.get("url");
            Object t = m.get("token");
            if (u != null && !String.valueOf(u).trim().isEmpty()) {
                ps.add(new Peer(String.valueOf(u).trim(), t == null ? "" : String.valueOf(t).trim()));
            }
        }
        peers = Collections.unmodifiableList(ps);
        int pull = Math.max(15, plugin.getConfig().getInt("global_blacklist.pull_interval_seconds", 60));

        // Снимок своих банов — на главном потоке (API банов Bukkit), раз в период
        snapTask = Scheduler.runSyncTimer(plugin, this::snapshot, 20L, pull * 20L);
        if (plugin.getConfig().getBoolean("global_blacklist.publish.enabled", false)) {
            startHttp(plugin.getConfig().getString("global_blacklist.publish.bind", "0.0.0.0"),
                    plugin.getConfig().getInt("global_blacklist.publish.port", 8765));
        }
        if (!peers.isEmpty()) {
            pullTask = Scheduler.runAsyncTimer(plugin, this::pullAll, 60L, pull * 20L);
        }
        plugin.getLogger().info("Единый чёрный список: включён, партнёров " + peers.size()
                + (http != null ? ", список отдаётся на порту " + http.getAddress().getPort() : ""));
    }

    public void stop() {
        Scheduler.Task t = pullTask;
        if (t != null) {
            t.cancel();
            pullTask = null;
        }
        t = snapTask;
        if (t != null) {
            t.cancel();
            snapTask = null;
        }
        HttpServer h = http;
        if (h != null) {
            try {
                h.stop(0);
            } catch (Throwable ignored) {
            }
            http = null;
        }
    }

    /** @return источник (имя сервера-партнёра) или null — не в списке. */
    public String check(String ip, String name) {
        if (!enabled) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (ip != null) {
            Entry e = remoteIps.get(ip.toLowerCase(Locale.ROOT));
            if (e != null && (e.until < 0 || e.until > now)) {
                return e.source;
            }
        }
        if (blockNames && name != null) {
            Entry e = remoteNames.get(name.toLowerCase(Locale.ROOT));
            if (e != null && (e.until < 0 || e.until > now)) {
                return e.source;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ publish

    private void snapshot() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("#vtregister-blacklist v1 server=").append(clean(serverName)).append('\n');
        for (String line : localBans.apply(shareFromTier)) {
            int i = line.indexOf(';');
            if (i > 0) {
                sb.append("ip;").append(line, 0, i).append(';').append(line.substring(i + 1)).append('\n');
            }
        }
        if (shareServerBans) {
            try {
                for (BanEntry b : Bukkit.getBanList(BanList.Type.IP).getBanEntries()) {
                    appendBan(sb, "ip", b);
                }
                for (BanEntry b : Bukkit.getBanList(BanList.Type.NAME).getBanEntries()) {
                    appendBan(sb, "name", b);
                }
            } catch (Throwable ignored) {
            }
        }
        published = sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendBan(StringBuilder sb, String type, BanEntry b) {
        String target = b.getTarget();
        if (target == null || target.isEmpty()) {
            return;
        }
        long until = b.getExpiration() == null ? -1 : b.getExpiration().getTime();
        if (until >= 0 && until <= System.currentTimeMillis()) {
            return;
        }
        sb.append(type).append(';').append(target.toLowerCase(Locale.ROOT)).append(';').append(until).append('\n');
    }

    private void startHttp(String bind, int port) {
        token = resolveToken();
        try {
            HttpServer h = HttpServer.create(new InetSocketAddress(bind, port), 16);
            h.createContext("/vtregister/blacklist", ex -> {
                try {
                    String remote = ex.getRemoteAddress() == null ? "?"
                            : ex.getRemoteAddress().getAddress().getHostAddress();
                    long now = System.currentTimeMillis();
                    Long last = lastRequest.put(remote, now);
                    String got = ex.getRequestHeaders().getFirst("X-VTR-Token");
                    int code;
                    byte[] body;
                    if (last != null && now - last < 2000L) {
                        code = 429;
                        body = "slow down".getBytes(StandardCharsets.UTF_8);
                    } else if (got == null || !MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8),
                            token.getBytes(StandardCharsets.UTF_8))) {
                        code = 403;
                        body = "forbidden".getBytes(StandardCharsets.UTF_8);
                    } else {
                        code = 200;
                        body = published;
                    }
                    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    ex.sendResponseHeaders(code, body.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(body);
                    }
                } finally {
                    ex.close();
                }
            });
            h.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread th = new Thread(r, "VTRegister-Blacklist-HTTP");
                th.setDaemon(true);
                return th;
            }));
            h.start();
            http = h;
            if (lastRequest.size() > 10_000) {
                lastRequest.clear();
            }
        } catch (Throwable t) {
            plugin.getLogger().severe("Единый чёрный список: порт " + port + " не открыт (" + t
                    + "). Список партнёрам не отдаётся.");
        }
    }

    /** Токен: из конфига, иначе сгенерированный один раз в data/blacklist-token.txt. */
    private String resolveToken() {
        String t = plugin.getConfig().getString("global_blacklist.publish.token", "");
        if (t != null && !t.trim().isEmpty()) {
            return t.trim();
        }
        File f = new File(plugin.getDataFolder(), "data/blacklist-token.txt");
        try {
            if (f.isFile()) {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
            byte[] rnd = new byte[24];
            new SecureRandom().nextBytes(rnd);
            StringBuilder sb = new StringBuilder();
            for (byte b : rnd) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().warning("Единый чёрный список: токен сгенерирован — передай его партнёрам: "
                    + sb + " (файл data/blacklist-token.txt)");
            return sb.toString();
        } catch (Throwable e) {
            return Long.toHexString(new SecureRandom().nextLong());
        }
    }

    // --------------------------------------------------------------- pull

    private void pullAll() {
        Map<String, Entry> ips = new HashMap<>();
        Map<String, Entry> names = new HashMap<>();
        for (Peer p : peers) {
            try {
                pull(p, ips, names);
            } catch (Throwable t) {
                long now = System.currentTimeMillis();
                Long last = peerErrorLogged.get(p.url);
                if (last == null || now - last > 600_000L) {
                    peerErrorLogged.put(p.url, now);
                    plugin.getLogger().warning("Единый чёрный список: партнёр " + p.url + " недоступен: " + t);
                }
            }
        }
        remoteIps = ips;
        remoteNames = names;
    }

    private void pull(Peer p, Map<String, Entry> ips, Map<String, Entry> names) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(p.url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setRequestProperty("X-VTR-Token", p.token);
        c.setRequestProperty("User-Agent", "VTRegister-Blacklist");
        int code = c.getResponseCode();
        if (code != 200) {
            throw new IllegalStateException("HTTP " + code + (code == 403 ? " (неверный токен)" : ""));
        }
        byte[] body;
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
                if (bos.size() > MAX_BODY) {
                    throw new IllegalStateException("слишком большой список");
                }
            }
            body = bos.toByteArray();
        }
        String source = hostOf(p.url);
        int count = 0;
        for (String line : new String(body, StandardCharsets.UTF_8).split("\n")) {
            line = line.trim();
            if (line.startsWith("#vtregister-blacklist")) {
                int i = line.indexOf("server=");
                if (i > 0) {
                    source = clean(line.substring(i + 7));
                }
                continue;
            }
            String[] parts = line.split(";");
            if (parts.length < 3 || ++count > maxPerPeer) {
                continue;
            }
            long until;
            try {
                until = Long.parseLong(parts[2].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String target = parts[1].trim().toLowerCase(Locale.ROOT);
            if ("ip".equals(parts[0]) && IP.matcher(target).matches() && !(ignorePrivate && isPrivate(target))) {
                ips.put(target, new Entry(until, source));
            } else if ("name".equals(parts[0]) && NAME.matcher(target).matches()) {
                names.put(target, new Entry(until, source));
            }
        }
    }

    private static boolean isPrivate(String ip) {
        try {
            InetAddress a = InetAddress.getByName(ip);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress();
        } catch (Throwable t) {
            return true;
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable t) {
            return "partner";
        }
    }

    private static String clean(String s) {
        String c = s == null ? "" : s.replaceAll("[^A-Za-z0-9_.\\- ]", "").trim();
        return c.isEmpty() ? "partner" : (c.length() > 32 ? c.substring(0, 32) : c);
    }

    /** Для /authadmin: сколько записей получено от партнёров. */
    public String status() {
        return enabled ? ("включён: партнёров " + peers.size() + ", IP " + remoteIps.size()
                + ", ников " + remoteNames.size() + (http != null ? ", отдаём на порту "
                + http.getAddress().getPort() : ", не отдаём")) : "выключен";
    }

}
