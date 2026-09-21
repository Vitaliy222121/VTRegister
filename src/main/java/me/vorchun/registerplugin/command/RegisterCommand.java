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
import me.vorchun.registerplugin.service.PasswordValidator;
import me.vorchun.registerplugin.service.ReminderService;
import me.vorchun.registerplugin.service.SessionManager;
import me.vorchun.registerplugin.service.TeleportService;
import me.vorchun.registerplugin.util.Compat;

/**
 * /register (/reg) — регистрация.
 * В защищённом режиме пароль вводится следующим сообщением в чат;
 * в незащищённом — команда перехватывается AuthListener и исполняется внутри плагина.
 */
public final class RegisterCommand implements CommandExecutor {

    private final RegisterPlugin plugin;
    private final AccountStore accountStore;
    private final SessionManager sessionManager;
    private final AuthTimeoutService timeoutService;
    private final ReminderService reminderService;
    private final LoginAttemptService loginAttemptService;
    private final MessageService messages;
    private final TeleportService teleportService;

    public RegisterCommand(RegisterPlugin plugin, AccountStore accountStore, SessionManager sessionManager,
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
        if (sessionManager.isLoggedIn(player.getUniqueId())) {
            Compat.clearAuthDarkness(player);
            messages.send(player, "already_logged_in");
            return true;
        }
        if (accountStore.isRegistered(player.getUniqueId())) {
            messages.send(player, "already_registered");
            return true;
        }
        // С аргументами сюда попадать не должны: AuthListener перехватывает такие
        // команды и исполняет их сам, чтобы пароль не попал в лог ядра.
        if (args.length >= 1) {
            messages.send(player, "password_in_command_blocked");
            return true;
        }
        messages.send(player, "enter_password_chat_register");
        return true;
    }

    /**
     * Регистрация по уже полученному паролю (из чата или из перехваченной команды).
     * Хеширование выполняется в фоне внутри AuthService.
     */
    public boolean performRegistration(Player player, String password) {
        AuthService auth = plugin.getAuthService();
        if (auth == null) {
            return true;
        }
        auth.register(player, password, (result, validation) -> {
            if (result == AuthService.Result.OK) {
                timeoutService.stop(player);
                reminderService.stop(player);
                loginAttemptService.reset(player.getUniqueId());
                Compat.updateCommands(player);
                Compat.clearAuthDarkness(player);
                if (plugin.getAuthListener() != null) {
                    plugin.getAuthListener().revealPlayer(player);
                }
                if (plugin.getSpawnService() != null) {
                    plugin.getSpawnService().teleportAfterLogin(player, true);
                } else {
                    teleportService.teleportToLobby(player);
                }
                messages.send(player, "register_success");
                return;
            }
            switch (validation) {
                case TOO_SHORT:
                    messages.send(player, "password_too_short");
                    break;
                case TOO_LONG:
                    messages.send(player, "password_too_long");
                    break;
                case INVALID_WHITESPACE:
                case INVALID_NULL:
                    messages.send(player, "password_invalid");
                    break;
                case TOO_WEAK:
                    messages.send(player, "password_too_weak");
                    break;
                default:
                    messages.send(player, "already_registered");
            }
        });
        return true;
    }

    /** Валидация пароля (используется тестами и совместимостью). */
    public PasswordValidator.ValidationResult validate(String password) {
        return plugin.validatePassword(password);
    }

    private static final int P7 = -530440465;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x1008) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1008) == P7;
    }
}
