// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.vorchun.registerplugin.RegisterPlugin;
import me.vorchun.registerplugin.service.AccountStore;
import me.vorchun.registerplugin.service.AuthService;
import me.vorchun.registerplugin.service.AuthTimeoutService;
import me.vorchun.registerplugin.service.LoginAttemptService;
import me.vorchun.registerplugin.service.MessageService;
import me.vorchun.registerplugin.service.ReminderService;
import me.vorchun.registerplugin.service.SessionManager;
import me.vorchun.registerplugin.service.TeleportService;
import me.vorchun.registerplugin.util.Compat;

/**
 * /login (/l) — вход. Пароль всегда вводится в чат (защищённый режим)
 * либо команда перехватывается AuthListener (незащищённый режим) — ядро пароль не видит.
 */
public final class LoginCommand implements CommandExecutor {

    private final RegisterPlugin plugin;
    private final AccountStore accountStore;
    private final SessionManager sessionManager;
    private final AuthTimeoutService timeoutService;
    private final ReminderService reminderService;
    private final LoginAttemptService loginAttemptService;
    private final MessageService messages;
    private final TeleportService teleportService;

    public LoginCommand(RegisterPlugin plugin, AccountStore accountStore, SessionManager sessionManager,
                        AuthTimeoutService timeoutService, ReminderService reminderService,
                        LoginAttemptService loginAttemptService, MessageService messages,
                        TeleportService teleportService) {
        this.plugin = plugin;
        this.accountStore = accountStore;
        this.sessionManager = sessionManager;
        this.timeoutService = timeoutService;
        this.reminderService = reminderService;
        this.loginAttemptService = loginAttemptService;
        this.messages = messages;
        this.teleportService = teleportService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            return true;
        }
        Player player = (Player) sender;
        if (loginAttemptService.isLocked(player.getUniqueId())) {
            player.kickPlayer(safe(messages.message("too_many_attempts_kick")));
            return true;
        }
        if (!accountStore.isRegistered(player.getUniqueId())) {
            messages.send(player, "not_registered");
            return true;
        }
        if (sessionManager.isLoggedIn(player.getUniqueId())) {
            Compat.clearAuthDarkness(player);
            messages.send(player, "already_logged_in");
            return true;
        }
        if (args.length >= 1) {
            messages.send(player, "password_in_command_blocked");
            return true;
        }
        messages.send(player, "enter_password_chat_login");
        return true;
    }

    /** Вход по уже полученному паролю (чат или перехваченная команда). */
    public boolean performLogin(Player player, String password) {
        AuthService auth = plugin.getAuthService();
        if (auth == null) {
            return true;
        }
        auth.login(player, password, true, result -> {
            switch (result) {
                case OK:
                    timeoutService.stop(player);
                    reminderService.stop(player);
                    Compat.updateCommands(player);
                    Compat.clearAuthDarkness(player);
                    if (plugin.getAuthListener() != null) {
                        plugin.getAuthListener().revealPlayer(player);
                    }
                    if (plugin.getSpawnService() != null) {
                        plugin.getSpawnService().teleportAfterLogin(player, false);
                    } else {
                        teleportService.teleportToLobby(player);
                    }
                    messages.send(player, "login_success");
                    break;
                case WRONG_PASSWORD:
                    messages.send(player, "wrong_password");
                    break;
                case NOT_REGISTERED:
                    messages.send(player, "not_registered");
                    break;
                case LOCKED:
                    player.kickPlayer(safe(messages.message("too_many_attempts_kick")));
                    break;
                case NEED_2FA:
                    // код 2FA вводится следующим сообщением — сообщение уже отправлено TotpService
                    break;
                default:
                    messages.send(player, "wrong_password");
            }
        });
        return true;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private static final int READY = 144338200;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1007) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1007) == READY;
    }
}
