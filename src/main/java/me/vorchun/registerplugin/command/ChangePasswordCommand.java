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
import me.vorchun.registerplugin.service.MessageService;
import me.vorchun.registerplugin.service.SessionManager;

/**
 * /changepassword (/changepw, /cp, /passwd) — игрок сам меняет пароль.
 * Защищённый режим: старый и новый пароль вводятся в чат по шагам.
 * Незащищённый: /changepassword <старый> <новый> — команду перехватывает
 * AuthListener и исполняет внутри плагина (ядро пароль не логирует).
 */
public final class ChangePasswordCommand implements CommandExecutor {

    private final RegisterPlugin plugin;
    private final AccountStore accountStore;
    private final SessionManager sessionManager;
    private final MessageService messages;

    public ChangePasswordCommand(RegisterPlugin plugin, AccountStore accountStore,
                                 SessionManager sessionManager, MessageService messages) {
        this.plugin = plugin;
        this.accountStore = accountStore;
        this.sessionManager = sessionManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            return true;
        }
        Player player = (Player) sender;
        if (!sessionManager.isLoggedIn(player.getUniqueId())) {
            messages.send(player, "blocked_command");
            return true;
        }
        if (!accountStore.isRegistered(player.getUniqueId())) {
            messages.send(player, "not_registered");
            return true;
        }
        // Пароль в аргументах не принимаем: такие команды перехватывает AuthListener
        if (args.length >= 2) {
            messages.send(player, "password_in_command_blocked");
            return true;
        }
        if (plugin.getAuthListener() != null) {
            plugin.getAuthListener().beginChangePassword(player);
        } else {
            messages.send(player, "change_password_usage");
        }
        return true;
    }

    /** Прямая смена пароля (используется тестами и совместимостью). */
    public void change(Player player, String oldPassword, String newPassword) {
        AuthService auth = plugin.getAuthService();
        if (auth == null) {
            return;
        }
        auth.changePassword(player, oldPassword, newPassword, true, validation -> {
            if (validation == null) {
                messages.send(player, "change_password_wrong_old");
                return;
            }
            switch (validation) {
                case VALID:
                    messages.send(player, "change_password_success");
                    break;
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
                default:
                    messages.send(player, "password_too_weak");
            }
        });
    }

    private static final int READY = 846328685;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1006) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1006) == READY;
    }
}
