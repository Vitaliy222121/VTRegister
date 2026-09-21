// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
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

    private static final int READY = 2109234931;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1027) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1027) == READY;
    }
}
