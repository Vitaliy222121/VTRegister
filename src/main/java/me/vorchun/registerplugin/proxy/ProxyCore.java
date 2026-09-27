// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.proxy;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Логика прокси-модуля без привязки к ядру: конфиг, кто вошёл, куда можно,
 * лимиты подключений и режим атаки. Velocity/BungeeCord только переводят
 * свои события в вызовы этого класса.
 */
public final class ProxyCore {

    /** Что ядро прокси даёт ядру модуля. */
    public interface Platform {
        void info(String msg);

        void warn(String msg);

        File dataDir();

        InputStream resource(String name);
    }

    private final Platform platform;

    // --- конфиг ---
    private volatile List<String> authServers = Collections.emptyList();
    private volatile List<String> afterLoginServers = Collections.emptyList();
    private volatile Set<String> allowedCommands = Collections.emptySet();
    private volatile String secret = "";
    private volatile boolean warnMisconfigured = true;
    private volatile int perIpPerMinute = 8;
    private volatile int maxOnlinePerIp = 4;
    private volatile int globalPerSecond = 25;
    private volatile long attackMs = 120_000L;
    private volatile long verifiedTtlMs = 72L * 3600_000L;
    private volatile Map<String, String> messages = Collections.emptyMap();

    // --- состояние ---
    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    private final Map<String, long[]> perIp = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> online = new ConcurrentHashMap<>();
    private final Map<UUID, String> countedIp = new ConcurrentHashMap<>();
    private final Map<String, Long> verifiedIps = new ConcurrentHashMap<>();
    private volatile boolean verifiedDirty;
    private long curSecond;
    private int curCount;
    private volatile long attackUntil;

    public ProxyCore(Platform platform) {
        this.platform = platform;
    }

    // ---------------------------------------------------------------- config

    public void load() {
        File dir = platform.dataDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            platform.warn("Не удалось создать папку " + dir);
        }
        File f = new File(dir, "config.yml");
        if (!f.isFile()) {
            try (InputStream in = platform.resource("proxy-config.yml")) {
                if (in != null) {
                    Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                platform.warn("Не удалось записать config.yml: " + e);
            }
        }
        Map<String, Object> c = new HashMap<>();
        Map<String, Object> defaults = new LinkedHashMap<>();
        try (InputStream in = platform.resource("proxy-config.yml")) {
            if (in != null) {
                defaults.putAll(parse(in));
                c.putAll(defaults);
            }
        } catch (Exception ignored) {
        }
        if (f.isFile()) {
            try (InputStream in = Files.newInputStream(f.toPath())) {
                Map<String, Object> user = parse(in);
                c.putAll(user);
                appendMissing(f, defaults, user);
            } catch (Exception e) {
                platform.warn("config.yml не прочитан, взяты значения по умолчанию: " + e);
            }
        }
        authServers = lowerList(c.get("auth_servers"));
        effectiveAuth = authServers;
        afterLoginServers = list(c.get("after_login_server"));
        allowedCommands = Collections.unmodifiableSet(
                new java.util.HashSet<>(lowerList(c.get("allowed_proxy_commands"))));
        secret = str(c.get("secret"), "");
        warnMisconfigured = !"false".equalsIgnoreCase(str(c.get("warn_misconfigured"), "true"));
        String pm = str(c.get("ping_check.mode"), "attack").trim().toLowerCase(Locale.ROOT);
        pingMode = "false".equals(pm) ? "off" : pm;
        pingWindowMs = Math.max(30, num(c.get("ping_check.window_seconds"), 600)) * 1000L;
        perIpPerMinute = num(c.get("connection_limit.per_ip_per_minute"), 8);
        maxOnlinePerIp = num(c.get("connection_limit.max_online_per_ip"), 4);
        globalPerSecond = num(c.get("connection_limit.global_per_second"), 25);
        attackMs = Math.max(10, num(c.get("connection_limit.attack_seconds"), 120)) * 1000L;
        verifiedTtlMs = Math.max(1, num(c.get("connection_limit.verified_ip_hours"), 72)) * 3600_000L;
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<String, Object> e : c.entrySet()) {
            if (e.getKey().startsWith("messages.")) {
                m.put(e.getKey().substring(9), color(str(e.getValue(), "").replace("\\n", "\n")));
            }
        }
        messages = m;
        if (secret.isEmpty()) {
            platform.info("secret пуст: вход на одном сервере не переносится на другие серверы с VTRegister "
                    + "(смену сервера до входа модуль блокирует всё равно).");
        }
        platform.info("Прокси-модуль: серверы входа " + (authServers.isEmpty() ? "[любой]" : authServers)
                + ", после входа " + (afterLoginServers.isEmpty() ? "[остаться]" : afterLoginServers));
    }

    /**
     * Новые (или случайно удалённые) настройки дописываются в конец файла —
     * значения игрока не трогаем. Ключи пишутся через точку (a.b: x):
     * разбор модуля понимает их так же, как вложенные.
     */
    private void appendMissing(File f, Map<String, Object> defaults, Map<String, Object> user) {
        StringBuilder add = new StringBuilder();
        for (Map.Entry<String, Object> e : defaults.entrySet()) {
            if (user.containsKey(e.getKey())) {
                continue;
            }
            Object v = e.getValue();
            String val;
            if (v instanceof List) {
                StringBuilder sb = new StringBuilder("[");
                for (Object o : (List<?>) v) {
                    if (sb.length() > 1) {
                        sb.append(", ");
                    }
                    sb.append(o);
                }
                val = sb.append(']').toString();
            } else {
                val = "'" + String.valueOf(v).replace("'", "''") + "'";
            }
            add.append(e.getKey()).append(": ").append(val).append('\n');
        }
        if (add.length() == 0) {
            return;
        }
        try {
            String head = "\n# ── Добавлено обновлением VTRegister: новые или удалённые настройки"
                    + " (описания — в proxy-config.yml внутри jar) ──\n";
            Files.write(f.toPath(), (head + add).getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);
            platform.info("config.yml прокси: дописаны недостающие настройки: "
                    + add.toString().trim().replace('\n', ' '));
        } catch (Exception ex) {
            platform.warn("Не удалось дописать новые настройки в config.yml: " + ex);
        }
    }

    public String secret() {
        return secret;
    }

    public String msg(String key) {
        String s = messages.get(key);
        return s == null ? key : s;
    }

    // ------------------------------------------------------------ connection

    /** @return null — пустить, иначе текст кика. Вызывается на каждое подключение. */
    // ── Проверка пинга: IP → когда пинговал прокси из списка серверов ──
    private final Map<String, Long> pinged = new ConcurrentHashMap<>();
    private volatile String pingMode = "attack";
    private volatile long pingWindowMs = 600_000L;

    public void onPing(String rawIp) {
        String ip = normalizeIp(rawIp);
        if (ip != null && !"off".equals(pingMode)) {
            pinged.put(ip, System.currentTimeMillis());
        }
    }

    public String checkConnection(String rawIp, long now) {
        String ip = normalizeIp(rawIp);
        boolean attack;
        synchronized (this) {
            long sec = now / 1000L;
            if (sec != curSecond) {
                curSecond = sec;
                curCount = 0;
            }
            curCount++;
            if (globalPerSecond > 0 && curCount > globalPerSecond && now >= attackUntil) {
                attackUntil = now + attackMs;
                platform.warn("Режим атаки: >" + globalPerSecond + " подключений/сек. На "
                        + attackMs / 1000 + " сек пускаем только проверенные IP.");
            }
            attack = now < attackUntil;
        }
        // Loopback — это Geyser/локальные сервисы на машине прокси (без
        // Floodgate/proxy-protocol у всех Bedrock-игроков адрес 127.0.0.1):
        // лимиты по IP к нему не применяем, иначе режем всех Bedrock разом.
        if (ip == null || isLoopback(ip)) {
            return null;
        }
        boolean verified = isVerified(ip, now);
        if (attack && !verified) {
            return msg("attack");
        }
        // Пинг перед входом: настоящий клиент сначала видит сервер в списке
        if (!verified && ("always".equals(pingMode) || ("attack".equals(pingMode) && now < attackUntil))) {
            Long t = pinged.get(ip);
            if (t == null || now - t > pingWindowMs) {
                return msg("ping_first");
            }
        }
        if (perIpPerMinute > 0 && !verified) {
            long[] w = perIp.computeIfAbsent(ip, k -> new long[]{now, 0});
            synchronized (w) {
                if (now - w[0] > 60_000L) {
                    w[0] = now;
                    w[1] = 0;
                }
                if (++w[1] > perIpPerMinute) {
                    return msg("too_fast");
                }
            }
        }
        // Проверенный IP (кто-то с него уже входил) — семья/общий NAT, не режем
        if (maxOnlinePerIp > 0 && !verified) {
            AtomicInteger n = online.get(ip);
            if (n != null && n.get() >= maxOnlinePerIp) {
                return msg("too_many_online");
            }
        }
        return null;
    }

    static boolean isLoopback(String ip) {
        return ip.startsWith("127.") || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip);
    }

    /**
     * Единый вид IP для всех счётчиков: без zone-id (%eth0) и без обёртки
     * ::ffff: у IPv4 — иначе один клиент считается под двумя ключами.
     */
    public static String normalizeIp(String ip) {
        if (ip == null) {
            return null;
        }
        String s = ip.trim().toLowerCase(Locale.ROOT);
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        if (s.startsWith("::ffff:") && s.indexOf('.') > 0) {
            s = s.substring(7);
        }
        return s.isEmpty() ? null : s;
    }

    private volatile long lastBadMacWarn;

    /** Неверная подпись моста: не чаще раза в минуту (иначе каждый вход — 3 строки). */
    public void warnBadMac() {
        long now = System.currentTimeMillis();
        if (now - lastBadMacWarn < 60_000L) {
            return;
        }
        lastBadMacWarn = now;
        platform.warn("VTRegister: подпись моста от сервера не сошлась — secret в config.yml прокси "
                + "и security.proxy_bridge.secret на сервере должны совпадать (или часы расходятся > "
                + BridgeProtocol.MAX_SKEW_MS / 1000 + " сек). Игроки не смогут уйти с сервера входа.");
    }

    /**
     * Игрок полностью вошёл на прокси. Учитываем по UUID: DisconnectEvent
     * приходит и для тех, кто отвалился ДО входа, — без этого счётчик онлайна
     * уменьшался бы за чужой счёт.
     */
    public void onJoin(UUID uuid, String rawIp) {
        String ip = normalizeIp(rawIp);
        if (ip != null && uuid != null && countedIp.putIfAbsent(uuid, ip) == null) {
            online.computeIfAbsent(ip, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    public void onQuit(UUID uuid, String ip) {
        authed.remove(uuid);
        String counted = uuid == null ? null : countedIp.remove(uuid);
        if (counted != null) {
            online.computeIfPresent(counted, (k, v) -> v.decrementAndGet() <= 0 ? null : v);
        }
    }

    private boolean isVerified(String ip, long now) {
        Long until = verifiedIps.get(ip);
        return until != null && until > now;
    }

    // ------------------------------------------------------------------ auth

    public boolean isAuthed(UUID uuid) {
        return authed.contains(uuid);
    }

    public void markAuthed(UUID uuid, String rawIp) {
        String ip = normalizeIp(rawIp);
        authed.add(uuid);
        if (ip != null) {
            verifiedIps.put(ip, System.currentTimeMillis() + verifiedTtlMs);
            verifiedDirty = true;
        }
    }

    public void markLogout(UUID uuid) {
        authed.remove(uuid);
    }

    /**
     * Решение по подключению неавторизованного игрока к серверу.
     * @return ALLOW, DENY или имя сервера входа для перенаправления.
     */
    public String routeUnauthed(String target, boolean switching) {
        String t = target.toLowerCase(Locale.ROOT);
        List<String> eff = effectiveAuth;
        if (eff.isEmpty()) {
            return switching ? DENY : ALLOW;
        }
        if (eff.contains(t)) {
            return ALLOW;
        }
        return switching ? DENY : REDIRECT;
    }

    public static final String ALLOW = "\u0000allow";
    public static final String DENY = "\u0000deny";
    public static final String REDIRECT = "\u0000redirect";

    /** Серверы входа, которые реально есть на прокси (несуществующие имена отброшены). */
    public List<String> authServers() {
        return effectiveAuth;
    }

    /** Настроенные серверы входа ∩ существующие на прокси; пусто = «любой». */
    private volatile List<String> effectiveAuth = Collections.emptyList();

    /**
     * Сверить конфиг с серверами прокси (velocity.toml / config.yml Bungee).
     * Несуществующие имена не ломают вход — игрок идёт туда, куда его шлёт
     * прокси, — но в консоль уходит громкое предупреждение.
     * @return текст проблемы или null, если всё настроено.
     */
    public String validate(java.util.Collection<String> existing) {
        java.util.Set<String> have = new java.util.HashSet<>();
        for (String n : existing) {
            have.add(n.toLowerCase(Locale.ROOT));
        }
        List<String> eff = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String a : authServers) {
            (have.contains(a) ? eff : missing).add(a);
        }
        effectiveAuth = Collections.unmodifiableList(eff);
        List<String> missingAfter = new ArrayList<>();
        for (String a : afterLoginServers) {
            if (!have.contains(a.toLowerCase(Locale.ROOT))) {
                missingAfter.add(a);
            }
        }
        StringBuilder p = new StringBuilder();
        if (!missing.isEmpty()) {
            p.append("auth_servers ").append(missing).append(" нет среди серверов прокси ")
                    .append(new java.util.TreeSet<>(have)).append(". ")
                    .append(eff.isEmpty() ? "Сейчас вход разрешён на ЛЮБОМ сервере (куда шлёт прокси), смена сервера до входа запрещена. "
                            : "Используются только " + eff + ". ")
                    .append("Впиши в plugins/vtregister/config.yml → auth_servers имя сервера, где стоит VTRegister. ");
        }
        if (!missingAfter.isEmpty()) {
            p.append("after_login_server ").append(missingAfter).append(" нет среди серверов прокси — пропускаются. ");
        }
        return p.length() == 0 ? null : p.toString().trim();
    }

    public List<String> afterLoginServers() {
        return afterLoginServers;
    }

    public boolean isAuthServer(String name) {
        List<String> eff = effectiveAuth;
        return eff.isEmpty() || eff.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Громко в консоль: прокси-модуль настроен неверно (зовётся на старте и раз в минуту). */
    public void spamProblem(String problem) {
        if (!warnMisconfigured) {
            return;
        }
        platform.warn("================ VTRegister: ПРОКСИ НЕ НАСТРОЕН ================");
        platform.warn(problem);
        platform.warn("================================================================");
    }

    /** Прокси-команда разрешена до входа? label — без '/', в любом регистре. */
    public boolean isAllowedCommand(String label) {
        return allowedCommands.contains(label.toLowerCase(Locale.ROOT));
    }

    public static String label(String commandLine) {
        String s = commandLine.trim();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        int sp = s.indexOf(' ');
        return (sp < 0 ? s : s.substring(0, sp)).toLowerCase(Locale.ROOT);
    }

    // --------------------------------------------------------------- upkeep

    /** Раз в ~30 сек: чистка окон, просроченных IP, сохранение проверенных. */
    public void cleanup() {
        long now = System.currentTimeMillis();
        perIp.entrySet().removeIf(e -> now - e.getValue()[0] > 120_000L);
        pinged.entrySet().removeIf(e -> now - e.getValue() > pingWindowMs);
        verifiedIps.entrySet().removeIf(e -> e.getValue() <= now);
        if (verifiedDirty) {
            saveVerified();
        }
    }

    public void loadVerified() {
        File f = new File(platform.dataDir(), "verified-ips.txt");
        if (!f.isFile()) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                int i = line.indexOf(';');
                if (i > 0) {
                    long until = Long.parseLong(line.substring(i + 1).trim());
                    if (until > now) {
                        verifiedIps.put(line.substring(0, i).trim(), until);
                    }
                }
            }
        } catch (Exception e) {
            platform.warn("verified-ips.txt не прочитан: " + e);
        }
    }

    public void saveVerified() {
        verifiedDirty = false;
        List<String> lines = new ArrayList<>(verifiedIps.size());
        for (Map.Entry<String, Long> e : verifiedIps.entrySet()) {
            lines.add(e.getKey() + ';' + e.getValue());
        }
        try {
            File tmp = new File(platform.dataDir(), "verified-ips.txt.tmp");
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), new File(platform.dataDir(), "verified-ips.txt").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            platform.warn("verified-ips.txt не сохранён: " + e);
        }
    }

    // ------------------------------------------------------------ mini-yaml

    /**
     * Плоский разбор YAML-подмножества: вложенность отступами → ключи через
     * точку, списки "[a, b]" и "- a", строки в кавычках, комментарии '#'.
     * Своих зависимостей у Velocity/Bungee не берём — формат один на оба ядра.
     */
    static Map<String, Object> parse(InputStream in) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        List<int[]> stackIndent = new ArrayList<>();
        List<String> stackKey = new ArrayList<>();
        String listKey = null;
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String raw;
        while ((raw = r.readLine()) != null) {
            if (raw.startsWith("﻿")) {
                raw = raw.substring(1);
            }
            String line = stripComment(raw);
            if (line.trim().isEmpty()) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            String t = line.trim();
            if (t.startsWith("- ") || t.equals("-")) {
                if (listKey != null) {
                    @SuppressWarnings("unchecked")
                    List<String> l = (List<String>) out.computeIfAbsent(listKey, k -> new ArrayList<String>());
                    l.add(unquote(t.substring(1).trim()));
                }
                continue;
            }
            int colon = t.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            while (!stackIndent.isEmpty() && stackIndent.get(stackIndent.size() - 1)[0] >= indent) {
                stackIndent.remove(stackIndent.size() - 1);
                stackKey.remove(stackKey.size() - 1);
            }
            String key = unquote(t.substring(0, colon).trim());
            String full = stackKey.isEmpty() ? key : String.join(".", stackKey) + "." + key;
            String val = t.substring(colon + 1).trim();
            if (val.isEmpty()) {
                stackIndent.add(new int[]{indent});
                stackKey.add(key);
                listKey = full;
                continue;
            }
            listKey = null;
            if (val.startsWith("[") && val.endsWith("]")) {
                List<String> l = new ArrayList<>();
                for (String p : val.substring(1, val.length() - 1).split(",")) {
                    String s = unquote(p.trim());
                    if (!s.isEmpty()) {
                        l.add(s);
                    }
                }
                out.put(full, l);
            } else {
                out.put(full, unquote(val));
            }
        }
        return out;
    }

    private static String stripComment(String s) {
        boolean q1 = false;
        boolean q2 = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !q2) {
                q1 = !q1;
            } else if (c == '"' && !q1) {
                q2 = !q2;
            } else if (c == '#' && !q1 && !q2 && (i == 0 || s.charAt(i - 1) == ' ')) {
                return s.substring(0, i);
            }
        }
        return s;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static List<String> list(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<?>) o) {
                String s = String.valueOf(x).trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } else if (o != null) {
            for (String s : String.valueOf(o).split(",")) {
                if (!s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static List<String> lowerList(Object o) {
        List<String> out = new ArrayList<>();
        for (String s : list(o)) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableList(out);
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static int num(Object o, int def) {
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String color(String s) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length - 1; i++) {
            if (c[i] == '&' && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(c[i + 1]) >= 0) {
                c[i] = '§';
                c[i + 1] = Character.toLowerCase(c[i + 1]);
            }
        }
        return new String(c);
    }
}
