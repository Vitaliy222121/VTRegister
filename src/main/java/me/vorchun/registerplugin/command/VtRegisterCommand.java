package me.vorchun.registerplugin.command;

import me.vorchun.registerplugin.RegisterPlugin;
import me.vorchun.registerplugin.service.MessageService;
import me.vorchun.registerplugin.util.ConfigMerger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /vtregister — справка по командам плагина и быстрые действия.
 * TAB показывает доступные подкоманды.
 */
public final class VtRegisterCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList("help", "status", "reload", "reset", "cmds");
    private static final List<String> RESET_WHAT = Arrays.asList("config", "advanced", "lang", "all");
    /** Файлы языков из jar: свои языки владельца сброс не трогает. */
    private static final List<String> LANG_FILES = Arrays.asList("lang/ru.yml", "lang/en.yml");
    private static final long CONFIRM_MS = 30_000L;

    private final RegisterPlugin plugin;
    private final MessageService messages;
    /** Кто и что собрался сбросить → до какого времени ждём confirm. */
    private final Map<String, Long> pendingReset = new ConcurrentHashMap<>();

    public VtRegisterCommand(RegisterPlugin plugin, MessageService messages) {
        this.plugin = plugin;
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
                    messages.send(sender, "admin_no_permission");
                }
                return true;
            case "reload":
                if (sender.hasPermission("registerplugin.admin")) {
                    org.bukkit.Bukkit.dispatchCommand(sender, "authadmin reload");
                } else {
                    messages.send(sender, "admin_no_permission");
                }
                return true;
            case "reset":
            case "defaults":
                if (!sender.hasPermission("registerplugin.admin")) {
                    messages.send(sender, "admin_no_permission");
                    return true;
                }
                reset(sender, label, args);
                return true;
            case "cmds":
            case "help":
            default:
                sendHelp(sender);
                return true;
        }
    }

    /**
     * /vtregister reset <config|advanced|lang|all> [confirm] — вернуть файлы
     * настроек к значениям по умолчанию. Сначала просит подтверждение, перед
     * сбросом копирует текущие файлы в backups/reset-<время>/. «Перец» паролей
     * (security.password_pepper) и подключение к базе (storage) сохраняются:
     * без них игроки не вошли бы со своими паролями / пропали бы аккаунты.
     */
    private void reset(CommandSender sender, String label, String[] args) {
        String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (!RESET_WHAT.contains(what)) {
            messages.send(sender, "admin_defaults_usage", ph("cmd", "/" + label));
            return;
        }
        List<String> files = filesFor(what);
        String key = sender.getName() + "|" + what;
        boolean confirm = args.length > 2 && "confirm".equalsIgnoreCase(args[2]);
        Long until = pendingReset.get(key);
        if (!confirm || until == null || until < System.currentTimeMillis()) {
            pendingReset.put(key, System.currentTimeMillis() + CONFIRM_MS);
            Map<String, String> p = ph("files", String.join(", ", files));
            p.put("cmd", "/" + label + " reset " + what + " confirm");
            messages.send(sender, "admin_defaults_confirm", p);
            return;
        }
        pendingReset.remove(key);
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date());
        File backup = new File(plugin.getDataFolder(), "backups/reset-" + stamp);
        String backupShown = "plugins/" + plugin.getDataFolder().getName() + "/backups/reset-" + stamp;
        List<String> done = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (String f : files) {
            String[] keep = "config.yml".equals(f) ? new String[]{"security.password_pepper"}
                    : "advanced.yml".equals(f) ? new String[]{"storage.*"} : new String[0];
            try {
                List<String> k = ConfigMerger.resetToDefaults(plugin, f, new File(plugin.getDataFolder(), f), backup, keep);
                if (k == null) {
                    continue; // такого файла в jar нет
                }
                done.add(f);
                kept.addAll(k);
            } catch (Throwable t) {
                Map<String, String> p = ph("file", f);
                p.put("error", String.valueOf(t.getMessage()));
                p.put("backup", backupShown);
                messages.send(sender, "admin_defaults_failed", p);
                plugin.getLogger().warning("reset " + f + ": " + t);
                break;
            }
        }
        if (done.isEmpty()) {
            return;
        }
        plugin.getLogger().warning(sender.getName() + " сбросил настройки к значениям по умолчанию: " + String.join(", ", done)
                + (kept.isEmpty() ? "" : " (сохранены: " + String.join(", ", kept) + ")") + "; копия — " + backupShown);
        // Применить: тот же путь, что /vtregister reload (тексты, антибот, лимиты)
        plugin.reloadAndMergeConfig();
        plugin.reloadAll();
        Map<String, String> p = ph("files", String.join(", ", done));
        p.put("backup", backupShown);
        p.put("kept", kept.isEmpty() ? "-" : String.join(", ", kept));
        messages.send(sender, "admin_defaults_done", p);
    }

    private static List<String> filesFor(String what) {
        switch (what) {
            case "config":
                return Arrays.asList("config.yml");
            case "advanced":
                return Arrays.asList("advanced.yml");
            case "lang":
                return LANG_FILES;
            default:
                List<String> all = new ArrayList<>(Arrays.asList("config.yml", "advanced.yml"));
                all.addAll(LANG_FILES);
                return all;
        }
    }

    private static Map<String, String> ph(String k, String v) {
        Map<String, String> m = new HashMap<>();
        m.put(k, v);
        return m;
    }

    private static final List<String> HELP_PLAYER = Arrays.asList(
            "help_header", "help_register", "help_login", "help_changepassword", "help_2fa", "help_email");
    private static final List<String> HELP_ADMIN = Arrays.asList(
            "help_admin_header", "help_admin_reload", "help_admin_reset", "help_admin_status",
            "help_admin_accounts", "help_admin_spawn", "help_admin_misc");

    /** Справка — тексты в lang (help_*), на языке игрока. */
    private void sendHelp(CommandSender sender) {
        if (messages.message(sender, "help_header", new HashMap<>()) == null) {
            // lang без справки (ключи не дописались) — хотя бы главное
            sender.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    "&e/register &7(/reg), &e/login &7(/l), &e/changepassword &7(/cp)"));
            return;
        }
        for (String k : HELP_PLAYER) {
            messages.send(sender, k);
        }
        if (sender.hasPermission("registerplugin.admin")) {
            for (String k : HELP_ADMIN) {
                messages.send(sender, k);
            }
        }
        messages.send(sender, "help_footer");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> from;
        if (args.length == 1) {
            from = SUBS;
        } else if (args.length == 2 && ("reset".equalsIgnoreCase(args[0]) || "defaults".equalsIgnoreCase(args[0]))
                && sender.hasPermission("registerplugin.admin")) {
            from = RESET_WHAT;
        } else if (args.length == 3 && ("reset".equalsIgnoreCase(args[0]) || "defaults".equalsIgnoreCase(args[0]))
                && sender.hasPermission("registerplugin.admin")) {
            from = java.util.Collections.singletonList("confirm");
        } else {
            return java.util.Collections.emptyList();
        }
        String pref = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : from) {
            if (s.startsWith(pref)) {
                out.add(s);
            }
        }
        return out;
    }
}
