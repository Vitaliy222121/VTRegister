package me.vorchun.registerplugin.util;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;

public final class IpUtil {

    private IpUtil() {
    }

    public static String getIp(Player player) {
        return getIp(null, player);
    }

    public static String getIp(JavaPlugin plugin, Player player) {
        InetSocketAddress addr = player.getAddress();
        if (addr == null || addr.getAddress() == null) {
            return "";
        }

        String ip = addr.getAddress().getHostAddress();
        if (ip == null) {
            return "";
        }

        int zone = ip.indexOf('%');
        if (zone >= 0) {
            ip = ip.substring(0, zone);
        }

        if (plugin != null) {
            List<String> proxyIps = plugin.getConfig().getStringList("proxy.known_proxy_ips");
            if (proxyIps != null && !proxyIps.isEmpty()) {
                String nip = ip.toLowerCase(Locale.ROOT);
                for (String s : proxyIps) {
                    if (s == null || s.isEmpty()) {
                        continue;
                    }
                    if (nip.equals(s.trim().toLowerCase(Locale.ROOT))) {
                        return "";
                    }
                }
            }
        }

        return ip;
    }
}
