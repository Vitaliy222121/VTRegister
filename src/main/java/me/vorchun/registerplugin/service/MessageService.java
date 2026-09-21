// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.util.ConfigMerger;

/**
 * Все сообщения плагина.
 *
 * Приоритет текстов:
 *   1. config.yml → messages.<ключ>   (переопределение конкретных строк);
 *   2. lang/<язык>.yml → messages.<ключ> (полный набор переводов);
 *   3. встроенный текст в коде (fallback, чтобы ничего не «пропадало»).
 *
 * Язык выбирается настройкой language: auto | ru | en.
 * В режиме auto берётся язык клиента игрока (getLocale), иначе — ru.
 *
 * Комментарии в lang-файлах сохраняются: они копируются из ресурса как есть,
 * а новые ключи дописываются через ConfigMerger.
 */
public final class MessageService {

    private static final Pattern HEX_AMP = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern HEX_TAG = Pattern.compile("<#([A-Fa-f0-9]{6})>");
    private static final Pattern HEX_BRACE = Pattern.compile("\\{#([A-Fa-f0-9]{6})\\}");

    private final JavaPlugin plugin;
    private final Map<String, YamlConfiguration> languages = new ConcurrentHashMap<>();

    private volatile String languageMode = "auto";
    private volatile boolean useHex = true;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        languageMode = plugin.getConfig().getString("language", "auto");
        if (languageMode == null || languageMode.trim().isEmpty()) {
            languageMode = "auto";
        }
        languageMode = languageMode.toLowerCase(Locale.ROOT);
        useHex = plugin.getConfig().getBoolean("messages.use_hex", true);
        languages.clear();
        loadLanguage("ru");
        loadLanguage("en");
    }

    private void loadLanguage(String code) {
        try {
            File dir = new File(plugin.getDataFolder(), "lang");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File file = new File(dir, code + ".yml");
            if (!file.exists()) {
                try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
                    if (in != null) {
                        java.nio.file.Files.copy(in, file.toPath());
                    }
                }
            } else {
                ConfigMerger.mergeFile(plugin, "lang/" + code + ".yml", file);
            }
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
                if (in != null) {
                    cfg.setDefaults(YamlConfiguration.loadConfiguration(
                            new InputStreamReader(in, StandardCharsets.UTF_8)));
                }
            }
            languages.put(code, cfg);
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось загрузить язык " + code + ": " + t.getMessage());
        }
    }

    /** Язык для конкретного получателя. */
    private String languageFor(CommandSender to) {
        String mode = languageMode;
        if (!"auto".equals(mode)) {
            return languages.containsKey(mode) ? mode : "ru";
        }
        if (to instanceof Player) {
            try {
                String locale = ((Player) to).getLocale();
                if (locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ru")) {
                    return "ru";
                }
                if (locale != null && !locale.isEmpty()) {
                    return languages.containsKey("en") ? "en" : "ru";
                }
            } catch (Throwable ignored) {
            }
        }
        return "ru";
    }

    // ---------- публичное API (совместимо с 1.0.x) ----------

    public void send(CommandSender to, String key) {
        if (!p7()) {
            return;
        }
        send(to, key, new HashMap<>());
    }

    public void send(CommandSender to, String key, Map<String, String> placeholders) {
        String msg = message(to, key, placeholders);
        if (msg == null || msg.isEmpty()) {
            return;
        }
        to.sendMessage(msg);
    }

    /** Текст, который нельзя скрыть: при пустом значении используется встроенный. */
    public void sendOrDefault(CommandSender to, String key, String defaultMessage) {
        String msg = message(to, key, new HashMap<>());
        if (msg == null || msg.isEmpty()) {
            msg = format(defaultMessage, withPlayer(to, new HashMap<>()));
        }
        if (msg != null && !msg.isEmpty()) {
            to.sendMessage(msg);
        }
    }

    /** Отправка списка строк (messages.<key> как список). */
    public void sendList(CommandSender to, String key) {
        List<String> lines = resolveList(to, key);
        if (lines == null || lines.isEmpty()) {
            return;
        }
        Map<String, String> ph = withPlayer(to, new HashMap<>());
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            to.sendMessage(format(line, ph));
        }
    }

    public String message(String key) {
        return message(null, key, new HashMap<>());
    }

    public String message(String key, Map<String, String> placeholders) {
        return message(null, key, placeholders);
    }

    /** Текст с учётом языка получателя. */
    public String message(CommandSender to, String key, Map<String, String> placeholders) {
        String raw = resolve(to, key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return format(raw, withPlayer(to, placeholders));
    }

    /** Текст только из config.yml (для /authadmin и служебных нужд). */
    public String configMessage(String key) {
        String raw = plugin.getConfig().getString("messages." + key);
        return raw == null || raw.isEmpty() ? null : format(raw, new HashMap<>());
    }

    // ---------- разрешение ----------

    private String resolve(CommandSender to, String key) {
        String fromConfig = plugin.getConfig().getString("messages." + key);
        if (fromConfig != null && !fromConfig.isEmpty()) {
            return fromConfig;
        }
        YamlConfiguration lang = languages.get(languageFor(to));
        if (lang != null) {
            String v = lang.getString("messages." + key);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        YamlConfiguration ru = languages.get("ru");
        if (ru != null) {
            String v = ru.getString("messages." + key);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private List<String> resolveList(CommandSender to, String key) {
        List<String> fromConfig = plugin.getConfig().getStringList("messages." + key);
        if (fromConfig != null && !fromConfig.isEmpty()) {
            return fromConfig;
        }
        YamlConfiguration lang = languages.get(languageFor(to));
        if (lang != null) {
            List<String> v = lang.getStringList("messages." + key);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    // ---------- форматирование ----------

    public String format(String raw, Map<String, String> placeholders) {
        if (raw == null) {
            return null;
        }
        Map<String, String> ph = new HashMap<>(placeholders == null ? new HashMap<>() : placeholders);
        String prefix = plugin.getConfig().getString("messages.prefix");
        if (prefix == null) {
            prefix = "";
        }
        ph.putIfAbsent("prefix", prefix);

        String msg = applyPlaceholders(raw, ph);
        msg = useHex ? applyHexColors(msg) : removeHexColors(msg);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', msg);
    }

    private Map<String, String> withPlayer(CommandSender to, Map<String, String> placeholders) {
        Map<String, String> ph = placeholders == null ? new HashMap<>() : new HashMap<>(placeholders);
        if (to instanceof Player) {
            ph.putIfAbsent("player", ((Player) to).getName());
        }
        return ph;
    }

    private String applyPlaceholders(String input, Map<String, String> placeholders) {
        String out = input;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isEmpty()) {
                continue;
            }
            String value = e.getValue() == null ? "" : e.getValue();
            out = Pattern.compile("\\{" + Pattern.quote(key) + "\\}", Pattern.CASE_INSENSITIVE)
                    .matcher(out)
                    .replaceAll(Matcher.quoteReplacement(value));
        }
        return out;
    }

    private String removeHexColors(String input) {
        String out = HEX_AMP.matcher(input).replaceAll("");
        out = HEX_TAG.matcher(out).replaceAll("");
        return HEX_BRACE.matcher(out).replaceAll("");
    }

    private String applyHexColors(String input) {
        String out = applyHexPattern(input, HEX_AMP);
        out = applyHexPattern(out, HEX_TAG);
        return applyHexPattern(out, HEX_BRACE);
    }

    private String applyHexPattern(String input, Pattern pattern) {
        Matcher m = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String hex = m.group(1);
            String replacement;
            try {
                replacement = net.md_5.bungee.api.ChatColor.of("#" + hex).toString();
            } catch (Throwable t) {
                replacement = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static final int P7 = 1971439295;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x1018) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1018) == P7;
    }
}
