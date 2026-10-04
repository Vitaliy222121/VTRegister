// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.service.AccountRecord;
import me.vorchun.registerplugin.service.AccountStore;
import me.vorchun.registerplugin.service.MessageService;
import me.vorchun.registerplugin.service.TotpService;
import me.vorchun.registerplugin.util.Scheduler;

/**
 * Обязательная 2FA для админов (twofactor.force_admins, по умолчанию выкл). Админ без
 * привязанной 2FA после входа может только настроить её (/2fa ...):
 * остальные команды и чат заблокированы, пока код не подтверждён. Не успел
 * за admin_setup_seconds — кик (зайдёт и настроит снова).
 * Угон админского аккаунта в offline-mode — самое опасное, что может быть.
 */
public final class AdminTwoFactorGuard implements Listener {

    private final JavaPlugin plugin;
    private final AccountStore accounts;
    private final TotpService totp;
    private final MessageService messages;
    /** UUID → до какого времени нужно включить 2FA. */
    private final Map<UUID, Long> pending = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile String permission = "registerplugin.admin";
    private volatile long setupMs = 300_000L;
    private volatile Scheduler.Task timer;

    public AdminTwoFactorGuard(JavaPlugin plugin, AccountStore accounts, TotpService totp, MessageService messages) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.totp = totp;
        this.messages = messages;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("twofactor.force_admins", false);
        permission = plugin.getConfig().getString("twofactor.admin_permission", "registerplugin.admin");
        int setupSec = plugin.getConfig().getInt("twofactor.admin_setup_seconds", 300);
        setupMs = setupSec <= 0 ? 0L : Math.max(60, setupSec) * 1000L; // 0 = не кикать
        if (timer == null) {
            timer = Scheduler.runSyncTimer(plugin, this::tick, 100L, 100L);
        }
        if (!enabled) {
            pending.clear();
        }
    }

    /** Вызывается после любого успешного входа. */
    public void onLogin(Player p) {
        if (!enabled || totp == null || !totp.isEnabled() || !isAdmin(p)) {
            return;
        }
        AccountRecord r = accounts.get(p.getUniqueId());
        if (r == null || r.hasTotp()) {
            return;
        }
        pending.put(p.getUniqueId(), System.currentTimeMillis() + setupMs);
        messages.sendOrDefault(p, "twofa_admin_required",
                "{prefix}&#FF6666У админ-аккаунта обязательна двухфакторка. Включи её: &#FFD700/2fa on"
                        + "&#FF6666 — до этого команды и чат недоступны.");
    }

    private boolean isAdmin(Player p) {
        return p.isOp() || (permission != null && !permission.isEmpty() && p.hasPermission(permission));
    }

    private boolean blocked(Player p) {
        Long until = pending.get(p.getUniqueId());
        if (until == null) {
            return false;
        }
        AccountRecord r = accounts.get(p.getUniqueId());
        if (r != null && r.hasTotp()) {
            pending.remove(p.getUniqueId());
            return false;
        }
        return true;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : pending.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) {
                pending.remove(e.getKey());
                continue;
            }
            AccountRecord r = accounts.get(e.getKey());
            if (r != null && r.hasTotp()) {
                pending.remove(e.getKey());
                messages.sendOrDefault(p, "twofa_admin_done",
                        "{prefix}&#A0FFA0Двухфакторка включена — все команды снова доступны.");
            } else if (setupMs > 0 && now > e.getValue()) {
                pending.remove(e.getKey());
                p.kickPlayer(messages.format(messages.message(p, "twofa_admin_timeout", new java.util.HashMap<>()), new java.util.HashMap<>()));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (pending.isEmpty() || !blocked(e.getPlayer())) {
            return;
        }
        String m = e.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (m.startsWith("/2fa") || m.startsWith("/vtregister:2fa")) {
            return;
        }
        e.setCancelled(true);
        messages.sendOrDefault(e.getPlayer(), "twofa_admin_required",
                "{prefix}&#FF6666Сначала включи двухфакторку: &#FFD700/2fa on");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!pending.isEmpty() && pending.containsKey(e.getPlayer().getUniqueId())) {
            // 6-значный код 2FA из чата пропускаем — его обработает AuthListener
            if (e.getMessage().trim().matches("\\d{6}")) {
                return;
            }
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }
}
