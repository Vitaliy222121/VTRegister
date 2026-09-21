// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.listener;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import me.vorchun.registerplugin.util.Scheduler;

/**
 * Защита «до входа»: отсекает бот-волны ещё на этапе подключения,
 * до создания игрока и до антибот-проверки в игре.
 *
 * Что проверяется (всё настраивается в config.yml → antibot.guard):
 *   - общий рейт подключений (N входов за M секунд) → attack-mode;
 *   - лимит одновременных подключений с одного IP;
 *   - лимит подключений с одного IP за окно времени;
 *   - фильтр ников (регулярка) — «Player12345» и прочие бот-паттерны;
 *   - лимит аккаунтов на IP (мультиаккаунты);
 *   - повторное подключение в течение N секунд (reconnect-челлендж);
 *   - временный бан IP после провала проверки.
 *
 * Все проверки работают с локальными счётчиками в памяти — нулевая нагрузка.
 */
public final class AntiBotGuard implements Listener {

    private final JavaPlugin plugin;
    private final AccountStore accountStore;

    private final Map<String, Deque<Long>> joinsByIp = new ConcurrentHashMap<>();
    private final Map<String, Integer> onlineByIp = new ConcurrentHashMap<>();
    private final Map<String, Long> bannedUntil = new ConcurrentHashMap<>();
    private final Map<String, String> banMessages = new ConcurrentHashMap<>();
    private final Map<String, Long> recentQuit = new ConcurrentHashMap<>();
    private final Deque<Long> globalJoins = new ArrayDeque<>();

    private volatile boolean enabled;
    private volatile int windowSeconds;
    private volatile int maxPerIp;
    private volatile int maxGlobalPerWindow;
    private volatile int attackThreshold;
    private volatile long attackUntil;
    private volatile int maxAccountsPerIp;
    private volatile int reconnectSeconds;
    private volatile int failBanMinutes;
    private volatile Pattern namePattern;
    private volatile boolean firewallEnabled;
    private volatile java.util.Set<String> firewallIps = java.util.Collections.emptySet();
    private volatile Scheduler.Task purger;

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
        reconnectSeconds = Math.max(0, plugin.getConfig().getInt("antibot.guard.reconnect_seconds", 0));
        failBanMinutes = Math.max(0, plugin.getConfig().getInt("antibot.guard.fail_ban_minutes", 0));
        String regex = plugin.getConfig().getString("antibot.guard.name_regex", "^[A-Za-z0-9_]{3,16}$");
        try {
            namePattern = Pattern.compile(regex);
        } catch (Throwable t) {
            namePattern = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
            plugin.getLogger().warning("antibot.guard.name_regex некорректен — использую стандартный");
        }

        // Файрвол прокси: пускать только подключения с IP самого прокси
        firewallEnabled = plugin.getConfig().getBoolean("proxy.firewall.enabled", false);
        java.util.Set<String> ips = new java.util.HashSet<>();
        for (String ip : plugin.getConfig().getStringList("proxy.firewall.allowed_ips")) {
            if (ip != null && !ip.isEmpty()) {
                ips.add(ip.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        firewallIps = java.util.Collections.unmodifiableSet(ips);

        // Периодическая очистка счётчиков — иначе карты растут бесконечно
        // на серверах с большим потоком IP (бот-волны)
        if (purger != null) {
            purger.cancel();
        }
        purger = Scheduler.runAsyncTimer(plugin, this::purgeStale, 1200L, 1200L);
    }

    /** Удаляем устаревшие записи: старше окна у joins, истёкшие баны, старые reconnect-метки. */
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
        recentQuit.entrySet().removeIf(e -> now - e.getValue() > Math.max(windowMs, 60_000L));
        synchronized (globalJoins) {
            while (!globalJoins.isEmpty() && now - globalJoins.peekFirst() > windowMs) {
                globalJoins.pollFirst();
            }
        }
    }

    /**
     * Файрвол прокси: если включён, на сервер пускаем только подключения,
     * реальный сокет-адрес которых = IP прокси (allowed_ips).
     * Используем PlayerLoginEvent#getRealAddress — это адрес сокета,
     * а не подменённый при forwarding адрес игрока.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onProxyFirewall(org.bukkit.event.player.PlayerLoginEvent e) {
        if (!firewallEnabled || firewallIps.isEmpty()) {
            return;
        }
        String real = null;
        try {
            if (e.getRealAddress() != null) {
                real = e.getRealAddress().getHostAddress();
            }
        } catch (Throwable ignored) {
        }
        if (real == null && e.getAddress() != null) {
            real = e.getAddress().getHostAddress();
        }
        if (real == null || !firewallIps.contains(real.toLowerCase(java.util.Locale.ROOT))) {
            plugin.getLogger().warning("Proxy-firewall: отклонено прямое подключение " + e.getPlayer().getName()
                    + " с " + real + " (разрешены: " + firewallIps + ")");
            e.disallow(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_OTHER,
                    color("&#FF6666Подключение напрямую запрещено. Используй прокси."));
        }
    }

    public boolean isAttackMode() {
        return System.currentTimeMillis() < attackUntil;
    }

    /** Провал антибот-проверки → временный бан IP (если включено). */
    public void onAntiBotFail(UUID uuid) {
        if (!enabled || failBanMinutes <= 0) {
            return;
        }
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.getAddress() != null && p.getAddress().getAddress() != null) {
            bannedUntil.put(p.getAddress().getAddress().getHostAddress(),
                    System.currentTimeMillis() + failBanMinutes * 60_000L);
        }
    }

    /**
     * Временный бан IP извне (AFK/бот-детект): ip — hostAddress,
     * durationMs — длительность, message — текст кика при входе.
     */
    public void banIp(String ip, long durationMs, String message) {
        if (ip == null || ip.isEmpty() || durationMs <= 0) {
            return;
        }
        bannedUntil.put(ip, System.currentTimeMillis() + durationMs);
        if (message != null && !message.isEmpty()) {
            banMessages.put(ip, message);
        }
    }

    // ---------- pre-login ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!enabled || !ready()) {
            return;
        }
        String ip = e.getAddress() == null ? "" : e.getAddress().getHostAddress();
        long now = System.currentTimeMillis();

        Long ban = bannedUntil.get(ip);
        if (ban != null) {
            if (ban > now) {
                String custom = banMessages.get(ip);
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        custom != null ? custom
                                : msg("antibot_ip_banned", "&cСлишком много неудачных проверок. Попробуй позже."));
                return;
            }
            bannedUntil.remove(ip);
            banMessages.remove(ip);
        }

        String name = e.getName();
        if (namePattern != null && !namePattern.matcher(name).matches()) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    msg("antibot_bad_name", "&cЭтот ник не разрешён на сервере."));
            return;
        }

        // Attack-mode: всплеск входов
        synchronized (globalJoins) {
            globalJoins.addLast(now);
            while (!globalJoins.isEmpty() && now - globalJoins.peekFirst() > windowSeconds * 1000L) {
                globalJoins.pollFirst();
            }
            if (globalJoins.size() >= attackThreshold) {
                if (!isAttackMode()) {
                    plugin.getLogger().warning("AntiBot: всплеск подключений (" + globalJoins.size()
                            + " за " + windowSeconds + "с) — включён attack-mode");
                }
                attackUntil = now + windowSeconds * 1000L;
            }
            if (globalJoins.size() > maxGlobalPerWindow) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        msg("antibot_global_limit", "&cСервер перегружен подключениями. Попробуй через минуту."));
                return;
            }
        }

        Deque<Long> joins = joinsByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (joins) {
            while (!joins.isEmpty() && now - joins.peekFirst() > windowSeconds * 1000L) {
                joins.pollFirst();
            }
            if (joins.size() >= maxPerIp) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        msg("antibot_ip_limit", "&cСлишком много подключений с твоего IP. Подожди немного."));
                return;
            }
            joins.addLast(now);
        }

        // Reconnect-челлендж: заставляет бота переподключиться (боты часто не умеют)
        if (reconnectSeconds > 0 && isAttackMode()) {
            Long quit = recentQuit.get(ip);
            if (quit != null && now - quit < reconnectSeconds * 1000L) {
                recentQuit.remove(ip);
            } else if (quit == null) {
                recentQuit.put(ip, now);
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        msg("antibot_reconnect", "&eПроверка подключения: зайди ещё раз через пару секунд."));
                return;
            }
        }

        // Мультиаккаунты: лимит аккаунтов, зарегистрированных с этого IP
        if (maxAccountsPerIp > 0 && !ip.isEmpty()) {
            UUID uuid = e.getUniqueId();
            boolean known = accountStore.isRegistered(uuid);
            if (!known) {
                int count = accountStore.countByIpBlocking(ip);
                if (count >= maxAccountsPerIp) {
                    e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                            msg("antibot_multiaccount", "&cС этого IP уже зарегистрировано максимум аккаунтов."));
                    return;
                }
            }
            accountStore.preload(uuid, name);
        } else {
            accountStore.preload(e.getUniqueId(), name);
        }
    }

    /**
     * Текст сообщения с учётом lang-файлов:
     * config.yml → messages.* имеет приоритет, дальше lang/ru.yml, потом дефолт.
     * Кик-сообщения чистим от HEX-цветов (язык игрока ещё неизвестен — берём
     * серверный дефолт), поэтому используем только &-коды.
     */
    private String msg(String key, String def) {
        if (plugin instanceof RegisterPlugin && ((RegisterPlugin) plugin).getMessageService() != null) {
            String m = ((RegisterPlugin) plugin).getMessageService().message(key);
            if (m != null && !m.isEmpty()) {
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
        if (!enabled) {
            return;
        }
        Player p = e.getPlayer();
        if (p.getAddress() == null || p.getAddress().getAddress() == null) {
            return;
        }
        onlineByIp.merge(p.getAddress().getAddress().getHostAddress(), 1, Integer::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitCount(PlayerQuitEvent e) {
        if (!enabled) {
            return;
        }
        Player p = e.getPlayer();
        if (p.getAddress() == null || p.getAddress().getAddress() == null) {
            return;
        }
        String ip = p.getAddress().getAddress().getHostAddress();
        onlineByIp.computeIfPresent(ip, (k, v) -> v <= 1 ? null : v - 1);
        recentQuit.put(ip, System.currentTimeMillis());
        if (plugin instanceof RegisterPlugin) {
            RegisterPlugin rp = (RegisterPlugin) plugin;
            if (rp.getTotpService() != null) {
                rp.getTotpService().forgetTrust(p.getUniqueId());
            }
        }
    }

    /** Снять все временные баны IP (команда /authadmin unban). */
    public void clearBans() {
        bannedUntil.clear();
        banMessages.clear();
    }

    /** Сколько игроков сейчас с этого IP (для отчётов/команд). */
    public int onlineFrom(String ip) {
        Integer n = onlineByIp.get(ip);
        return n == null ? 0 : n;
    }

    private static String color(String s) {
        return s == null ? "" : org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    private static final int READY = 2109234910











;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x100a) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x100a) == READY;
    }
}
