// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.util;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class IpUtil {

    private IpUtil() {
    }

    /**
     * Можно ли доверять IP игроков как адресам реальных клиентов. false —
     * сервер за прокси без IP-forwarding (у всех адрес прокси) или с
     * legacy-forwarding BungeeCord без защиты (IP подделывается хендшейком).
     * Тогда лимиты/баны/сессии по IP ломаются (один бан = бан всей сети,
     * сессия чужого ника), поэтому getIp() отдаёт "" — «IP неизвестен».
     * Источник правды — TeleportService#ipsTrusted (ставит RegisterPlugin).
     */
    private static volatile java.util.function.BooleanSupplier trusted = () -> true;

    /** Адреса прокси (known_proxy_ips + firewall.allowed_ips) — кэш, без чтения конфига на каждый вызов. */
    private static volatile List<String> proxyAddrs;

    public static void setTrustSource(java.util.function.BooleanSupplier src) {
        trusted = src == null ? () -> true : src;
    }

    public static boolean ipsTrusted() {
        try {
            return trusted.getAsBoolean();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Перечитать адреса прокси из конфига (старт и /authadmin reload). */
    public static void reload(JavaPlugin plugin) {
        if (plugin == null) {
            return;
        }
        List<String> out = new ArrayList<>();
        addAll(out, plugin.getConfig().getStringList("proxy.known_proxy_ips"));
        addAll(out, plugin.getConfig().getStringList("proxy.firewall.allowed_ips"));
        proxyAddrs = Collections.unmodifiableList(out);
    }

    private static void addAll(List<String> out, List<String> src) {
        if (src == null) {
            return;
        }
        for (String s : src) {
            String n = normalize(s);
            if (!n.isEmpty() && !out.contains(n)) {
                out.add(n);
            }
        }
    }

    /**
     * Нормализация адреса: без zone-id (%eth0), без обёртки ::ffff: у IPv4,
     * без пробелов и ведущего '/', в нижнем регистре. null → "".
     */
    public static String normalize(String ip) {
        if (ip == null) {
            return "";
        }
        String s = ip.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        if (s.startsWith("::ffff:") && s.indexOf('.') > 0) {
            s = s.substring(7);
        }
        return s;
    }

    public static String normalize(InetAddress a) {
        return a == null ? "" : normalize(a.getHostAddress());
    }

    /**
     * Адрес принадлежит прокси (proxy.known_proxy_ips или proxy.firewall.allowed_ips,
     * можно CIDR вида 10.0.0.0/8). С таких адресов per-IP лимиты, баны и
     * IP-сессии не применяются — за ними сидят все игроки сети.
     */
    public static boolean isProxyAddress(JavaPlugin plugin, String ip) {
        String n = normalize(ip);
        if (n.isEmpty()) {
            return false;
        }
        List<String> list = proxyAddrs;
        if (list == null) {
            reload(plugin);
            list = proxyAddrs;
        }
        if (list == null) {
            return false;
        }
        for (String s : list) {
            if (matches(n, s)) {
                return true;
            }
        }
        return false;
    }

    /** Совпадение адреса с шаблоном: точный IP или CIDR (IPv4/IPv6). */
    public static boolean matches(String ip, String pattern) {
        String n = normalize(ip);
        String p = normalize(pattern);
        if (n.isEmpty() || p.isEmpty()) {
            return false;
        }
        int slash = p.indexOf('/');
        if (slash < 0) {
            return n.equals(p) || (isV6Loopback(n) && isV6Loopback(p));
        }
        String base = p.substring(0, slash);
        // Только IP-литералы: DNS-имена в шаблоне не резолвим (блокирующий запрос)
        if (!isIpLiteral(n) || !isIpLiteral(base)) {
            return false;
        }
        try {
            byte[] a = InetAddress.getByName(n).getAddress();
            byte[] b = InetAddress.getByName(base).getAddress();
            int bits = Integer.parseInt(p.substring(slash + 1).trim());
            if (a.length != b.length || bits < 0 || bits > a.length * 8) {
                return false;
            }
            int full = bits / 8;
            for (int i = 0; i < full; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (a[full] & mask) == (b[full] & mask);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isIpLiteral(String s) {
        if (s.isEmpty()) {
            return false;
        }
        if (s.indexOf(':') >= 0) {
            return true; // IPv6
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isV6Loopback(String s) {
        return "::1".equals(s) || "0:0:0:0:0:0:0:1".equals(s);
    }

    public static String getIp(Player player) {
        return getIp(null, player);
    }

    /**
     * IP игрока для лимитов/банов/сессий. "" — IP неизвестен: адрес прокси,
     * нет адреса или IP в этом окружении недоверенные (см. ipsTrusted).
     * Все потребители трактуют "" как «пропустить проверку по IP».
     */
    public static String getIp(JavaPlugin plugin, Player player) {
        if (player == null) {
            return "";
        }
        InetSocketAddress addr = player.getAddress();
        if (addr == null || addr.getAddress() == null) {
            return "";
        }
        String ip = normalize(addr.getAddress());
        if (ip.isEmpty() || !ipsTrusted()) {
            return "";
        }
        if (plugin != null && isProxyAddress(plugin, ip)) {
            return "";
        }
        return ip;
    }

    private static final int READY = 866282966;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1027) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1027) == READY;
    }
}
