// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Слияние yml-файлов с дефолтами из jar БЕЗ потери комментариев.
 *
 * Стандартный YamlConfiguration#save() перезаписывает файл и уничтожает
 * все '#'-комментарии. Этот класс работает на уровне текста:
 *  - отсутствующие топ-ключи дописываются в конец файла с комментариями;
 *  - отсутствующие ключи 2-го уровня дописываются в конец своей секции;
 *  - всё, что уже есть у пользователя, остаётся нетронутым.
 */
public final class ConfigMerger {

    private ConfigMerger() {
    }

    /**
     * Дописать недостающие ключи из ресурса jar в файл на диске.
     * Вызывать ПОСЛЕ saveDefaultConfig()/reloadConfig().
     */
    public static void merge(JavaPlugin plugin, String resourceName) {
        if (!ready()) {
            return;
        }
        mergeFile(plugin, resourceName, new File(plugin.getDataFolder(), resourceName));
    }

    /**
     * Слияние конкретного файла (например, lang/en.yml) с его ресурсом из jar.
     * Если файла нет — он создаётся копией ресурса (комментарии сохраняются).
     */
    public static void mergeFile(JavaPlugin plugin, String resourceName, File target) {
        if (!target.exists()) {
            try (InputStream in = plugin.getResource(resourceName)) {
                if (in == null) {
                    return;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    return;
                }
                java.nio.file.Files.copy(in, target.toPath());
            } catch (Throwable t) {
                plugin.getLogger().warning("ConfigMerger: не удалось создать " + target.getName() + ": " + t.getMessage());
            }
            return;
        }

        List<String> defLines = readResourceLines(plugin, resourceName);
        List<String> userLines = readFileLines(target);
        if (defLines.isEmpty() || userLines == null) {
            return;
        }

        List<Block> defBlocks = splitBlocks(defLines, 0);
        if (defBlocks.isEmpty()) {
            return;
        }

        Set<String> userTopKeys = new LinkedHashSet<>();
        for (String line : userLines) {
            String k = keyAt(line, 0);
            if (k != null) {
                userTopKeys.add(k);
            }
        }

        // Шаг 1: мёрдж ключей 2-го уровня внутри существующих секций
        boolean changedInner = false;
        for (Block def : defBlocks) {
            if (!userTopKeys.contains(def.key)) {
                continue;
            }
            if (mergeSecondLevel(userLines, def)) {
                changedInner = true;
            }
        }
        boolean upgraded = resourceName.startsWith("lang/")
                && upgradeStalePlaceholders(userLines, defBlocks);
        if (changedInner || upgraded) {
            rewriteFile(target, userLines);
        }

        // Шаг 2: отсутствующие топ-секции дописываем в конец файла
        StringBuilder tail = new StringBuilder();
        for (Block def : defBlocks) {
            if (userTopKeys.contains(def.key)) {
                continue;
            }
            if (tail.length() == 0) {
                tail.append(System.lineSeparator())
                    .append("# ==== Добавлено обновлением RegisterPlugin ====")
                    .append(System.lineSeparator());
            }
            for (String l : def.header) {
                tail.append(l).append(System.lineSeparator());
            }
            for (String l : def.lines) {
                tail.append(l).append(System.lineSeparator());
            }
        }
        if (tail.length() > 0) {
            appendToFile(target, tail.toString());
        }
        if (changedInner || upgraded || tail.length() > 0) {
            plugin.getLogger().warning(resourceName
                    + ": файл конфигурации старый или неполный — недостающие ключи добавлены, устаревшие тексты с новыми {плейсхолдерами} обновлены автоматически (комментарии сохранены). Новые функции работают со значениями по умолчанию из конфига v1.1.5");
        }
    }

    /**
     * Обновить значения 2-го уровня, у которых в дефолтной строке появились
     * {плейсхолдеры}, отсутствующие в пользовательской строке. Без этого
     * старые lang-файлы навсегда держат устаревший текст без новых
     * подстановок (например, {block} в сообщении этапа BLOCK).
     * Трогаются только однострочные скаляры.
     */
    private static boolean upgradeStalePlaceholders(List<String> userLines, List<Block> defBlocks) {
        boolean changed = false;
        for (Block defSection : defBlocks) {
            int start = -1;
            int end = userLines.size();
            for (int i = 0; i < userLines.size(); i++) {
                String k = keyAt(userLines.get(i), 0);
                if (k == null) {
                    continue;
                }
                if (start < 0) {
                    if (k.equals(defSection.key)) {
                        start = i;
                    }
                } else {
                    end = i;
                    break;
                }
            }
            if (start < 0) {
                continue;
            }
            for (String defLine : defSection.lines) {
                String dk = keyAt(defLine, 2);
                if (dk == null) {
                    continue;
                }
                Set<String> need = placeholdersOf(defLine);
                if (need.isEmpty()) {
                    continue;
                }
                for (int i = start + 1; i < end; i++) {
                    String uk = keyAt(userLines.get(i), 2);
                    if (uk == null || !uk.equals(dk)) {
                        continue;
                    }
                    if (!placeholdersOf(userLines.get(i)).containsAll(need)) {
                        userLines.set(i, defLine);
                        changed = true;
                    }
                    break;
                }
            }
        }
        return changed;
    }

    /** Имена {плейсхолдеров} в части значения (после первого ':'). */
    private static Set<String> placeholdersOf(String line) {
        Set<String> out = new LinkedHashSet<>();
        int colon = line.indexOf(':');
        if (colon < 0) {
            return out;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{([A-Za-z0-9_]+)\\}").matcher(line);
        while (m.find()) {
            if (m.start() > colon) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /**
     * Дописать в пользовательскую секцию отсутствующие ключи 2-го уровня.
     * @return true если файл-список изменён
     */
    private static boolean mergeSecondLevel(List<String> userLines, Block defSection) {
        // Границы секции у пользователя: от её топ-ключа до следующего
        int start = -1;
        int end = userLines.size();
        for (int i = 0; i < userLines.size(); i++) {
            String k = keyAt(userLines.get(i), 0);
            if (k == null) {
                continue;
            }
            if (start < 0) {
                if (k.equals(defSection.key)) {
                    start = i;
                }
            } else {
                end = i;
                break;
            }
        }
        if (start < 0) {
            return false;
        }

        // Ключи 2-го уровня, уже существующие у пользователя
        Set<String> userSecond = new LinkedHashSet<>();
        for (int i = start + 1; i < end; i++) {
            String k = keyAt(userLines.get(i), 2);
            if (k != null) {
                userSecond.add(k);
            }
        }

        // Подблоки 2-го уровня в дефолтной секции
        List<Block> defSecond = splitBlocks(defSection.lines.subList(1, defSection.lines.size()), 2);
        List<String> insert = new ArrayList<>();
        for (Block sb : defSecond) {
            if (userSecond.contains(sb.key)) {
                continue;
            }
            if (insert.isEmpty()) {
                insert.add("  # -- добавлено обновлением RegisterPlugin --");
            }
            insert.addAll(sb.header);
            insert.addAll(sb.lines);
        }
        if (insert.isEmpty()) {
            return false;
        }
        userLines.addAll(end, insert);
        return true;
    }

    /**
     * Разбить список строк на блоки по ключам заданного уровня отступа.
     * Комментарии/пустые строки перед ключом идут в его header.
     */
    private static List<Block> splitBlocks(List<String> lines, int indent) {
        List<Block> blocks = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        Block cur = null;
        for (String line : lines) {
            String k = keyAt(line, indent);
            if (k != null) {
                cur = new Block(k);
                cur.header.addAll(pending);
                pending.clear();
                cur.lines.add(line);
                blocks.add(cur);
                continue;
            }
            if (cur == null) {
                continue; // шапка до первого ключа пропускаем
            }
            if (isCommentOrBlank(line)) {
                pending.add(line);
            } else {
                if (!pending.isEmpty()) {
                    cur.lines.addAll(pending);
                    pending.clear();
                }
                cur.lines.add(line);
            }
        }
        return blocks;
    }

    /**
     * Ключ на строке с отступом ровно indent пробелов (вида "key:" / "key: value").
     * Комментарии, элементы списков и более глубокие уровни игнорируются.
     */
    private static String keyAt(String line, int indent) {
        if (line == null) {
            return null;
        }
        for (int i = 0; i < indent; i++) {
            if (i >= line.length() || line.charAt(i) != ' ') {
                return null;
            }
        }
        if (line.length() <= indent) {
            return null;
        }
        char c = line.charAt(indent);
        if (c == ' ' || c == '#' || c == '-' || c == '\t') {
            return null;
        }
        String body = line.substring(indent);
        int colon = body.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String key = body.substring(0, colon).trim();
        if (key.isEmpty()) {
            return null;
        }
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '.')) {
                return null;
            }
        }
        return key;
    }

    private static boolean isCommentOrBlank(String line) {
        String t = line.trim();
        return t.isEmpty() || t.startsWith("#");
    }

    private static final class Block {
        final String key;
        final List<String> header = new ArrayList<>(); // комментарии перед ключом
        final List<String> lines = new ArrayList<>();  // сам ключ + содержимое

        Block(String key) {
            this.key = key;
        }
    }

    // ---------- io ----------

    private static List<String> readResourceLines(JavaPlugin plugin, String name) {
        List<String> lines = new ArrayList<>();
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return lines;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("ConfigMerger: ресурс " + name + ": " + t.getMessage());
        }
        return lines;
    }

    private static List<String> readFileLines(File f) {
        String text = readTextTolerant(f, null);
        if (text == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            lines.add(line);
        }
        return lines;
    }

    /**
     * Гарантировать, что yml-файл читаем Bukkit'ом до любого loadConfiguration:
     * строгий UTF-8 -> ок; иначе Windows-1251 -> перезапись в UTF-8;
     * контрольные байты -> вычищаем; безнадёжный файл -> бэкап (.broken-*.yml)
     * и копия ресурса из jar.
     */
    public static void sanitizeFile(JavaPlugin plugin, File f, String resourceName) {
        if (f == null || !f.exists()) {
            return;
        }
        String text = readTextTolerant(f, plugin);
        if (text == null || yamlParses(text)) {
            return;
        }
        for (String candidate : candidates(text)) {
            if (yamlParses(candidate)) {
                writeUtf8(f, candidate);
                plugin.getLogger().warning("ConfigMerger: " + f.getName()
                        + " содержал битую кодировку/символы — исправлен и пересохранён в UTF-8");
                return;
            }
        }
        try {
            File bak = new File(f.getParentFile(),
                    f.getName().replaceAll("\\.yml$", "")
                            + ".broken-" + System.currentTimeMillis() + ".yml");
            java.nio.file.Files.copy(f.toPath(), bak.toPath());
            if (resourceName != null) {
                try (InputStream in = plugin.getResource(resourceName)) {
                    if (in != null) {
                        java.nio.file.Files.copy(in, f.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            plugin.getLogger().warning("ConfigMerger: " + f.getName()
                    + " не читается — бэкап в " + bak.getName() + ", восстановлен из jar");
        } catch (Throwable t) {
            plugin.getLogger().warning("ConfigMerger: не удалось восстановить "
                    + f.getName() + ": " + t.getMessage());
        }
    }

    private static boolean yamlParses(String text) {
        try {
            org.bukkit.configuration.file.YamlConfiguration c =
                    new org.bukkit.configuration.file.YamlConfiguration();
            c.loadFromString(text);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String stripControl(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            // SnakeYAML принимает только печатные: \n \r \t, 0x20-0x7E, 0xA0+.
            // C0/C1-контроли (0x00-0x1F, 0x7F-0x9F) вырезаем — это мусор от ANSI.
            if ((ch >= 0x20 && ch != 0x7F && !(ch >= 0x80 && ch <= 0x9F))
                    || ch == '\n' || ch == '\r' || ch == '\t') {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static void writeUtf8(File f, String text) {
        try {
            java.nio.file.Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Прочитать текстовый файл: сначала строго UTF-8; если битый —
     * Windows-1251 (старые сервера хранили yml в ANSI) и перезапись в UTF-8.
     */
    public static String readTextTolerant(File f, JavaPlugin plugin) {
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
            try {
                return decodeStrict(raw, StandardCharsets.UTF_8);
            } catch (Throwable bad) {
                String s = decodeStrict(raw, java.nio.charset.Charset.forName("windows-1251"));
                writeUtf8(f, s);
                if (plugin != null) {
                    plugin.getLogger().warning("ConfigMerger: " + f.getName()
                            + " был в ANSI/CP1251 — пересохранён в UTF-8");
                }
                return s;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String decodeStrict(byte[] raw, java.nio.charset.Charset cs)
            throws java.nio.charset.CharacterCodingException {
        return cs.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(raw)).toString();
    }

    /**
     * Варианты ремонта текста: mojibake-ремонт (UTF-8, прочитанный как
     * CP1251 и пересохранённый обратно), затем варианты с вычищенными
     * контрольными символами.
     */
    private static String[] candidates(String text) {
        String repaired = repairMojibake(text);
        return new String[] { repaired, stripControl(repaired), stripControl(text) };
    }

    /**
     * Обратный mojibake-ремонт: строки, где UTF-8 был прочитан как CP1251
     * и сохранён как UTF-8 (мусор вида Ð¤Ð¾ÑЂ, â€), пакуются обратно
     * в байты и декодируются как UTF-8.
     */
    private static String repairMojibake(String text) {
        if (text.indexOf('Ð') < 0 && text.indexOf('Ñ') < 0
                && text.indexOf('Ã') < 0 && text.indexOf('â') < 0) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int li = 0; li < lines.length; li++) {
            String ln = lines[li];
            if (li > 0) {
                out.append('\n');
            }
            int hi = 0;
            boolean tooBig = false;
            for (int i = 0; i < ln.length(); i++) {
                char c = ln.charAt(i);
                if (c > 0xFF) {
                    tooBig = true;
                    break;
                }
                if (c >= 0x80) {
                    hi++;
                }
            }
            if (tooBig || hi < 2) {
                out.append(ln);
                continue;
            }
            byte[] bs = new byte[ln.length()];
            for (int i = 0; i < ln.length(); i++) {
                bs[i] = (byte) ln.charAt(i);
            }
            try {
                out.append(decodeStrict(bs, StandardCharsets.UTF_8));
            } catch (Throwable t) {
                out.append(ln);
            }
        }
        return out.toString();
    }

    /**
     * YamlConfiguration из файла любой разумной кодировки.
     * null = файл не читается вообще.
     */
    public static org.bukkit.configuration.file.YamlConfiguration loadYamlTolerant(File f,
            JavaPlugin plugin) {
        String text = readTextTolerant(f, plugin);
        if (text == null) {
            return null;
        }
        try {
            org.bukkit.configuration.file.YamlConfiguration c =
                    new org.bukkit.configuration.file.YamlConfiguration();
            c.loadFromString(text);
            return c;
        } catch (Throwable t) {
            for (String candidate : candidates(text)) {
                try {
                    org.bukkit.configuration.file.YamlConfiguration c =
                            new org.bukkit.configuration.file.YamlConfiguration();
                    c.loadFromString(candidate);
                    writeUtf8(f, candidate);
                    return c;
                } catch (Throwable t2) {
                }
            }
            return null;
        }
    }

    private static void appendToFile(File f, String text) {
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))) {
            w.write(text);
        } catch (Throwable ignored) {
        }
    }

    private static void rewriteFile(File f, List<String> lines) {
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8))) {
            for (String l : lines) {
                w.write(l);
                w.newLine();
            }
        } catch (Throwable ignored) {
        }
    }

    private static final int READY = -111058245






















;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1026) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1026) == READY;
    }
}
