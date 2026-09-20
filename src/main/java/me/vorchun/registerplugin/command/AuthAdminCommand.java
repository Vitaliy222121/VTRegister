package me.vorchun.registerplugin.command;

import me.vorchun.registerplugin.util.Scheduler;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.RegisterPlugin;
import me.vorchun.registerplugin.service.AccountRecord;
import me.vorchun.registerplugin.service.AccountStore;
import me.vorchun.registerplugin.service.MessageService;
import me.vorchun.registerplugin.service.PasswordHasher;
import me.vorchun.registerplugin.service.PasswordValidator;
import me.vorchun.registerplugin.service.SessionManager;

/**
 * /authadmin — администрирование авторизации.
 *
 * Подкоманды:
 *   reload                    — перечитать конфиг (комментарии сохраняются)
 *   status                    — состояние плагина и хранилища
 *   info <ник>                — информация об аккаунте
 *   list [страница]           — список аккаунтов
 *   reset <ник>               — сбросить пароль (аккаунт остаётся)
 *   unregister <ник>          — удалить аккаунт полностью
 *   setpw <ник>               — задать новый пароль (ввод в чат, без логов)
 *   logout <ник>              — разлогинить игрока
 *   forcelogin <ник>          — авторизовать без пароля
 *   setspawn <prelogin|postlogin|firstjoin> — точка спавна
 *   import [--overwrite]      — импорт из AuthMe/LoginSecurity/accounts.yml
 *   unban <ip>                — снять временный бан IP антибота
 */
public final class AuthAdminCommand implements CommandExecutor, Listener {

    private final JavaPlugin plugin;
    private final AccountStore accountStore;
    private final SessionManager sessionManager;
    private final MessageService messages;

    private final Map<UUID, UUID> pendingSetpwTarget = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, me.vorchun.registerplugin.util.Scheduler.Task> pendingSetpwTimeout = new java.util.concurrent.ConcurrentHashMap<>();

    public AuthAdminCommand(JavaPlugin plugin, AccountStore accountStore, SessionManager sessionManager, MessageService messages) {
        this.plugin = plugin;
        this.accountStore = accountStore;
        this.sessionManager = sessionManager;
        this.messages = messages;
    }

    // ---------- ввод пароля в чат ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAdminChat(AsyncPlayerChatEvent e) {
        Player admin = e.getPlayer();
        if (!admin.hasPermission("registerplugin.admin")) {
            return;
        }
        UUID targetUuid = pendingSetpwTarget.get(admin.getUniqueId());
        if (targetUuid == null) {
            return;
        }
        String input = e.getMessage();
        e.setCancelled(true);
        e.setMessage("");
        e.getRecipients().clear();
        Scheduler.runSync(plugin, () -> handleAdminPasswordInput(admin.getUniqueId(), targetUuid, input));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdminQuit(PlayerQuitEvent e) {
        clearPendingSetPassword(e.getPlayer().getUniqueId());
    }

    // ---------- команда ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("registerplugin.admin")) {
            messages.send(sender, "admin_no_permission");
            return true;
        }
        if (args.length < 1) {
            messages.send(sender, "admin_usage");
            return true;
        }

        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "reload":
                if (plugin instanceof RegisterPlugin) {
                    RegisterPlugin rp = (RegisterPlugin) plugin;
                    rp.reloadAndMergeConfig();
                    rp.reloadAll();
                } else {
                    plugin.reloadConfig();
                }
                audit(sender, "reload", null);
                messages.send(sender, "admin_reload_done");
                return true;

            case "status":
            case "stats":
                status(sender);
                return true;

            case "info":
                return requireTarget(sender, args, this::info);


            case "list":
                list(sender, args);
                return true;

            case "reset":
                return requireTarget(sender, args, (s, target) -> {
                    UUID uuid = target.getUniqueId();
                    accountStore.remove(uuid);
                    sessionManager.logout(uuid);
                    beginAuthentication(uuid);
                    audit(sender, "reset", target);
                    Map<String, String> ph = new HashMap<>();
                    ph.put("target", safeName(target));
                    messages.send(sender, "admin_reset_done", ph);
                });

            case "unregister":
                return requireTarget(sender, args, (s, target) -> {
                    UUID uuid = target.getUniqueId();
                    accountStore.remove(uuid);
                    sessionManager.logout(uuid);
                    audit(sender, "unregister", target);
                    Map<String, String> ph = new HashMap<>();
                    ph.put("target", safeName(target));
                    messages.send(sender, "admin_unregister_done", ph);
                });

            case "setpw":
                return setPassword(sender, args);

            case "logout":
                return requireTarget(sender, args, (s, target) -> {
                    UUID uuid = target.getUniqueId();
                    sessionManager.logout(uuid);
                    accountStore.clearAuth(uuid);
                    beginAuthentication(uuid);
                    audit(sender, "logout", target);
                    Map<String, String> ph = new HashMap<>();
                    ph.put("target", safeName(target));
                    messages.send(sender, "admin_logout_done", ph);
                });

            case "forcelogin":
                return requireTarget(sender, args, (s, target) -> {
                    Player online = Bukkit.getPlayer(target.getUniqueId());
                    if (online == null || !online.isOnline()) {
                        messages.send(sender, "admin_player_offline");
                        return;
                    }
                    sessionManager.login(online);
                    cleanupAfterLogin(online);
                    audit(sender, "forcelogin", target);
                    Map<String, String> ph = new HashMap<>();
                    ph.put("target", safeName(target));
                    messages.send(sender, "admin_forcelogin_done", ph);
                });

            case "setspawn":
                return setSpawn(sender, args);

            case "import":
                return importAccounts(sender, args);

            case "unban":
                return unban(sender, args);

            default:
                messages.send(sender, "admin_usage");
                return true;
        }
    }

    // ---------- подкоманды ----------

    private interface TargetAction {
        void run(CommandSender sender, OfflinePlayer target);
    }

    private boolean requireTarget(CommandSender sender, String[] args, TargetAction action) {
        if (args.length < 2) {
            messages.send(sender, "admin_usage");
            return true;
        }
        OfflinePlayer target = resolveTarget(args[1]);
        if (target == null) {
            messages.send(sender, "admin_player_not_found");
            return true;
        }
        action.run(sender, target);
        return true;
    }

    private void status(CommandSender sender) {
        RegisterPlugin rp = plugin instanceof RegisterPlugin ? (RegisterPlugin) plugin : null;
        Map<String, String> ph = new HashMap<>();
        ph.put("version", plugin.getDescription().getVersion());
        int onlineLogged = 0;
        int onlineWaiting = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (sessionManager.isLoggedIn(p.getUniqueId())) {
                onlineLogged++;
            } else {
                onlineWaiting++;
            }
        }
        ph.put("total", String.valueOf(accountStore.snapshotCached().size()));
        ph.put("logged", String.valueOf(onlineLogged));
        ph.put("waiting", String.valueOf(onlineWaiting));
        ph.put("mode", rp != null && rp.getAuthListener() != null && rp.getAuthListener().isSecureMode() ? "secure" : "insecure");
        messages.send(sender, "admin_status", ph);

        if (rp != null) {
            sender.sendMessage(messages.format("&#7F7F7FХранилище: &#FFFFFF" + accountStore.backendName()
                    + " &#7F7F7F| Ядро: &#FFFFFF" + me.vorchun.registerplugin.util.ServerCore.get().displayName
                    + " &#7F7F7F| Антибот: &#FFFFFF" + (rp.getAntiBotService() != null && rp.getAntiBotService().isEnabled() ? "вкл" : "выкл")
                    + (rp.getAntiBotGuard() != null && rp.getAntiBotGuard().isAttackMode() ? " &#FF6666(ATTACK MODE)" : ""), new HashMap<>()));
            if (rp.getCommandLogGuard() != null) {
                sender.sendMessage(messages.format("&#7F7F7FЗащита логов: &#FFFFFF" + rp.getCommandLogGuard().getMode()
                        + (rp.getCommandLogGuard().isFilterInstalled() ? " &#A0FFA0(log4j-фильтр активен)" : ""), new HashMap<>()));
            }
        }
    }

    private void info(CommandSender sender, OfflinePlayer target) {
        AccountRecord r = accountStore.get(target.getUniqueId());
        Map<String, String> ph = new HashMap<>();
        ph.put("target", safeName(target));
        ph.put("registered", String.valueOf(r != null));
        ph.put("ip", r == null || r.getLastIp() == null ? "" : r.getLastIp());
        ph.put("lastauth", r == null ? "0" : String.valueOf(r.getLastAuthMillis()));
        messages.send(sender, "admin_info", ph);
        if (r != null) {
            sender.sendMessage(messages.format("&#7F7F7F2FA: &#FFFFFF" + (r.hasTotp() ? "вкл" : "выкл")
                    + " &#7F7F7F| Почта: &#FFFFFF" + (r.isEmailVerified() ? "подтверждена" : "нет")
                    + " &#7F7F7F| Регистрация: &#FFFFFF" + r.getRegisteredAt()
                    + " &#7F7F7F| IP регистрации: &#FFFFFF" + r.getRegisteredIp(), new HashMap<>()));
        }
    }

    private void list(CommandSender sender, String[] args) {
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        final int requestedPage = page;
        final int perPage = 10;
        accountStore.countAsync(total -> {
            int pages = Math.max(1, (total + perPage - 1) / perPage);
            int current = Math.min(requestedPage, pages);
            Map<String, String> ph = new HashMap<>();
            ph.put("page", String.valueOf(current));
            ph.put("pages", String.valueOf(pages));
            ph.put("total", String.valueOf(total));
            messages.send(sender, "admin_list_header", ph);
            accountStore.listAsync((current - 1) * perPage, perPage, records -> {
                for (AccountRecord r : records) {
                    Map<String, String> row = new HashMap<>();
                    row.put("name", r.getName().isEmpty() ? r.getUuid().toString() : r.getName());
                    row.put("ip", r.getLastIp() == null ? "" : r.getLastIp());
                    Player online = Bukkit.getPlayer(r.getUuid());
                    row.put("online", online != null && online.isOnline() ? "online" : "offline");
                    messages.send(sender, "admin_list_row", row);
                }
            });
        });
    }

    private boolean setPassword(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "admin_usage");
            return true;
        }
        if (!(sender instanceof Player)) {
            messages.send(sender, "admin_usage");
            return true;
        }
        Player admin = (Player) sender;
        OfflinePlayer target = resolveTarget(args[1]);
        if (target == null) {
            messages.send(sender, "admin_player_not_found");
            return true;
        }
        pendingSetpwTarget.put(admin.getUniqueId(), target.getUniqueId());
        me.vorchun.registerplugin.util.Scheduler.Task prev = pendingSetpwTimeout.remove(admin.getUniqueId());
        if (prev != null) {
            prev.cancel();
        }
        int sec = Math.max(5, Math.min(300, plugin.getConfig().getInt("security.admin_setpw_timeout_seconds", 30)));
        pendingSetpwTimeout.put(admin.getUniqueId(), Scheduler.runSyncLater(plugin, () -> {
            pendingSetpwTimeout.remove(admin.getUniqueId());
            if (pendingSetpwTarget.remove(admin.getUniqueId()) != null && admin.isOnline()) {
                messages.send(admin, "admin_setpw_timeout");
            }
        }, sec * 20L));

        Map<String, String> ph = new HashMap<>();
        ph.put("target", safeName(target));
        messages.send(admin, "admin_setpw_prompt", ph);
        return true;
    }

    private boolean setSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            messages.send(sender, "admin_usage");
            return true;
        }
        if (args.length < 2) {
            messages.send(sender, "admin_setspawn_usage");
            return true;
        }
        String kind = args[1].toLowerCase(java.util.Locale.ROOT);
        if (!kind.equals("prelogin") && !kind.equals("postlogin") && !kind.equals("firstjoin")) {
            messages.send(sender, "admin_setspawn_usage");
            return true;
        }
        RegisterPlugin rp = plugin instanceof RegisterPlugin ? (RegisterPlugin) plugin : null;
        if (rp == null || rp.getSpawnService() == null) {
            return true;
        }
        rp.getSpawnService().save(kind, ((Player) sender).getLocation());
        Map<String, String> ph = new HashMap<>();
        ph.put("kind", kind);
        messages.send(sender, "admin_setspawn_done", ph);
        return true;
    }

    private boolean importAccounts(CommandSender sender, String[] args) {
        RegisterPlugin rp = plugin instanceof RegisterPlugin ? (RegisterPlugin) plugin : null;
        if (rp == null || rp.getImportService() == null) {
            return true;
        }
        boolean overwrite = args.length >= 2 && args[1].equalsIgnoreCase("--overwrite");
        messages.send(sender, "admin_import_started");
        rp.getImportService().importAll(overwrite, report -> {
            sender.sendMessage(messages.format("&#A0FFA0Импорт завершён: &#FFFFFF" + report.summary(), new HashMap<>()));
            audit(sender, "import", null);
        });
        return true;
    }

    private boolean unban(CommandSender sender, String[] args) {
        // Временные баны антибота живут в памяти; снимаем перезагрузкой стража
        RegisterPlugin rp = plugin instanceof RegisterPlugin ? (RegisterPlugin) plugin : null;
        if (rp != null && rp.getAntiBotGuard() != null) {
            rp.getAntiBotGuard().clearBans();
            messages.send(sender, "admin_unban_done");
        }
        return true;
    }

    // ---------- ввод пароля администратором ----------

    private void handleAdminPasswordInput(UUID adminUuid, UUID targetUuid, String input) {
        UUID currentTarget = pendingSetpwTarget.get(adminUuid);
        if (currentTarget == null || !currentTarget.equals(targetUuid)) {
            return;
        }
        Player admin = Bukkit.getPlayer(adminUuid);
        String raw = input == null ? "" : input;
        String trimmed = raw.trim();

        if (trimmed.equalsIgnoreCase("cancel") || trimmed.equalsIgnoreCase("отмена") || trimmed.equalsIgnoreCase("отменить")) {
            clearPendingSetPassword(adminUuid);
            if (admin != null) {
                messages.send(admin, "admin_setpw_cancelled");
            }
            return;
        }
        if (!accountStore.isRegistered(targetUuid)) {
            clearPendingSetPassword(adminUuid);
            if (admin != null) {
                messages.send(admin, "admin_player_not_found");
            }
            return;
        }

        PasswordValidator.ValidationResult result = plugin instanceof RegisterPlugin
                ? ((RegisterPlugin) plugin).validatePassword(raw)
                : PasswordValidator.validate(raw, 8, 64);
        if (admin != null) {
            switch (result) {
                case TOO_SHORT:
                    messages.send(admin, "admin_setpw_too_short");
                    return;
                case TOO_LONG:
                    messages.send(admin, "admin_setpw_too_long");
                    return;
                case INVALID_WHITESPACE:
                case INVALID_NULL:
                    messages.send(admin, "admin_setpw_invalid");
                    return;
                case TOO_WEAK:
                    messages.send(admin, "admin_setpw_too_weak");
                    return;
                default:
                    break;
            }
        } else if (result != PasswordValidator.ValidationResult.VALID) {
            return;
        }

        clearPendingSetPassword(adminUuid);
        // Хеширование — в фоне, чтобы не фризить сервер
        me.vorchun.registerplugin.util.Scheduler.runAsync(plugin, () -> {
            String newHash = PasswordHasher.hash(raw);
            me.vorchun.registerplugin.util.Scheduler.runSync(plugin, () -> {
                if (!accountStore.setPassword(targetUuid, newHash)) {
                    if (admin != null) {
                        messages.send(admin, "admin_player_not_found");
                    }
                    return;
                }
                sessionManager.logout(targetUuid);
                accountStore.clearAuth(targetUuid);
                beginAuthentication(targetUuid);
                if (admin != null) {
                    audit(admin, "setpw", Bukkit.getOfflinePlayer(targetUuid));
                    Map<String, String> ph = new HashMap<>();
                    ph.put("target", safeName(Bukkit.getOfflinePlayer(targetUuid)));
                    messages.send(admin, "admin_setpw_done", ph);
                }
            });
        });
    }

    private void clearPendingSetPassword(UUID adminUuid) {
        pendingSetpwTarget.remove(adminUuid);
        me.vorchun.registerplugin.util.Scheduler.Task t = pendingSetpwTimeout.remove(adminUuid);
        if (t != null) {
            t.cancel();
        }
    }

    // ---------- вспомогательное ----------

    private void cleanupAfterLogin(Player online) {
        RegisterPlugin rp = plugin instanceof RegisterPlugin ? (RegisterPlugin) plugin : null;
        if (rp == null) {
            return;
        }
        if (rp.getAuthListener() != null) {
            rp.getAuthListener().clearPasswordWaiting(online.getUniqueId());
            rp.getAuthListener().revealPlayer(online);
        }
        if (rp.getAuthTimeoutService() != null) {
            rp.getAuthTimeoutService().stop(online);
        }
        if (rp.getReminderService() != null) {
            rp.getReminderService().stop(online);
        }
        if (rp.getAntiBotService() != null) {
            rp.getAntiBotService().cancelCheck(online.getUniqueId(), false);
        }
        me.vorchun.registerplugin.util.Compat.updateCommands(online);
        me.vorchun.registerplugin.util.Compat.clearAuthDarkness(online);
    }

    private void beginAuthentication(UUID targetUuid) {
        if (!(plugin instanceof RegisterPlugin)) {
            return;
        }
        Player target = Bukkit.getPlayer(targetUuid);
        if (target != null && target.isOnline()) {
            ((RegisterPlugin) plugin).beginAuthentication(target);
        }
    }

    /**
     * Поиск аккаунта. ВАЖНО: сначала смотрим свою базу и онлайн-игроков,
     * и только потом Bukkit.getOfflinePlayer (он может ходить в Mojang API
     * и подвешивать главный поток на online-mode серверах).
     */
    private OfflinePlayer resolveTarget(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String trimmed = name.trim();
        Player online = Bukkit.getPlayerExact(trimmed);
        if (online != null) {
            return accountStore.isRegistered(online.getUniqueId()) ? online : null;
        }
        AccountRecord byName = accountStore.findByNameBlocking(trimmed);
        if (byName == null) {
            return null;
        }
        Player byUuid = Bukkit.getPlayer(byName.getUuid());
        return byUuid != null ? byUuid : Bukkit.getOfflinePlayer(byName.getUuid());
    }

    private void audit(CommandSender sender, String action, OfflinePlayer target) {
        plugin.getLogger().info("Аудит authadmin: actor=" + (sender == null ? "консоль" : sender.getName())
                + ", action=" + action + ", target=" + (target == null ? "-" : safeName(target)));
    }

    private String safeName(OfflinePlayer p) {
        if (p == null) {
            return "";
        }
        String name = p.getName();
        if (name != null) {
            return name;
        }
        AccountRecord r = accountStore.getCached(p.getUniqueId());
        return r != null && !r.getName().isEmpty() ? r.getName() : p.getUniqueId().toString();
    }

    private static final int P7 = -1438619473;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x1005) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1005) == P7;
    }
}
