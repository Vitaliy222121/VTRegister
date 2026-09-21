package me.vorchun.registerplugin.command;

import me.vorchun.registerplugin.service.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /vtregister — справка по командам плагина и быстрые действия.
 * TAB показывает доступные подкоманды.
 */
public final class VtRegisterCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList("help", "status", "cmds");
    private final MessageService messages;

    public VtRegisterCommand(MessageService messages) {
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "status":
                if (sender.hasPermission("registerplugin.admin")) {
                    org.bukkit.Bukkit.dispatchCommand(sender, "authadmin status");
                } else {
                    sender.sendMessage(color("&cНет доступа."));
                }
                return true;
            case "cmds":
            case "help":
            default:
                sendHelp(sender);
                return true;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(color("&6&m======&r &eVTRegister &7— команды &6&m======"));
        sender.sendMessage(color("&e/register &7(рег) — регистрация, пароль вводится в чат"));
        sender.sendMessage(color("&e/login &7(л) — вход, пароль вводится в чат"));
        sender.sendMessage(color("&e/changepassword &7(сменпароль) — смена своего пароля"));
        sender.sendMessage(color("&e/2fa on|off|код — двухфакторная защита аккаунта"));
        sender.sendMessage(color("&e/email <адрес> — привязка почты, /recover — восстановление"));
        if (sender.hasPermission("registerplugin.admin")) {
            sender.sendMessage(color("&6&m——&r &cАдмин &6&m——"));
            sender.sendMessage(color("&c/authadmin status|list|info <ник> — состояние и аккаунты"));
            sender.sendMessage(color("&c/authadmin reset|unregister|setpw|logout|forcelogin <ник>"));
            sender.sendMessage(color("&c/authadmin setspawn <prelogin|postlogin|firstjoin>"));
            sender.sendMessage(color("&c/authadmin pvpkit — GUI-редактор набора в PvP-сундуке"));
            sender.sendMessage(color("&c/authadmin lobby — телепорт в лобби-очередь"));
            sender.sendMessage(color("&c/authadmin import|reload|unban <ip>"));
        }
        sender.sendMessage(color("&6&m================================"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String pref = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : SUBS) {
                if (s.startsWith(pref)) {
                    out.add(s);
                }
            }
            return out;
        }
        return java.util.Collections.emptyList();
    }

    private String color(String s) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }
}
