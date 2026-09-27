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

        List<String> originalLines = new ArrayList<>(userLines);

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
        if ("config.yml".equals(resourceName) && dropObsolete(userLines)) {
            upgraded = true;
        }
        if (changedInner || upgraded) {
            // Никогда не пишем YAML, который потом не распарсится: битый
            // конфиг молча сбросился бы на дефолты (storage/БД владельца).
            if (yamlParses(String.join("\n", userLines))) {
                rewriteFile(target, userLines);
            } else {
                plugin.getLogger().warning("ConfigMerger: " + target.getName()
                        + " — слияние дало бы нечитаемый YAML (необычные отступы?), файл не тронут. "
                        + "Недостающие ключи работают со значениями по умолчанию.");
                userLines.clear();
                userLines.addAll(originalLines);
                changedInner = false;
                upgraded = false;
            }
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
            if (yamlParses(String.join("\n", userLines) + "\n" + tail)) {
                appendToFile(target, tail.toString());
            } else {
                plugin.getLogger().warning("ConfigMerger: " + target.getName()
                        + " — дописывание новых секций дало бы нечитаемый YAML, файл не тронут");
                tail.setLength(0);
            }
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
            int ind = sectionIndent(userLines, start, end);
            if (ind <= 0) {
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
                    String uk = keyAt(userLines.get(i), ind);
                    if (uk == null || !uk.equals(dk)) {
                        continue;
                    }
                    if (!placeholdersOf(userLines.get(i)).containsAll(need)) {
                        userLines.set(i, reindent(defLine, ind));
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

        // Фактический отступ секции у пользователя (2, 4, …). Раньше
        // считался ровно 2 — при 4 пробелах все ключи «терялись» и
        // дописывались дубликатами с чужим отступом (YAML не парсился).
        int ind = sectionIndent(userLines, start, end);
        if (ind < 0) {
            return false; // секция — список или скаляр: ключи не вставить
        }
        if (ind == 0) {
            String top = userLines.get(start);
            String after = top.substring(top.indexOf(':') + 1).trim();
            if (!after.isEmpty() && !after.startsWith("#")) {
                return false; // «key: value» / «key: {}» — не секция
            }
            ind = 2;
        }

        // Недостающие ключи ЛЮБОЙ глубины (вместе с их #-описаниями):
        // раньше восстанавливался только 2-й уровень, удалённый
        // antibot.bans.permanent или global_blacklist.publish.port не возвращался.
        List<Ins> ins = new ArrayList<>();
        mergeSection(userLines, start, end, ind, ind,
                defSection.lines.subList(1, defSection.lines.size()), 2, ins);
        if (ins.isEmpty()) {
            return false;
        }
        // Снизу вверх; при равной позиции — сначала родитель, потом вложенная секция
        ins.sort((a, b) -> a.at != b.at ? Integer.compare(b.at, a.at) : Integer.compare(b.seq, a.seq));
        for (Ins x : ins) {
            userLines.addAll(x.at, x.lines);
        }
        return true;
    }

    private static final class Ins {
        final int at;
        final int seq;
        final List<String> lines;

        Ins(int at, int seq, List<String> lines) {
            this.at = at;
            this.seq = seq;
            this.lines = lines;
        }
    }

    /**
     * Рекурсивно дописать недостающие ключи в секцию пользователя.
     * uStart — строка ключа секции, uEnd — её конец (не включая),
     * childInd — отступ детей у пользователя, step — шаг отступа файла,
     * defContent — содержимое секции в дефолте, defInd — отступ детей там.
     */
    private static void mergeSection(List<String> user, int uStart, int uEnd, int childInd, int step,
                                     List<String> defContent, int defInd, List<Ins> out) {
        java.util.Map<String, int[]> have = new java.util.LinkedHashMap<>();
        for (int i = uStart + 1; i < uEnd; i++) {
            String k = keyAt(user.get(i), childInd);
            if (k == null) {
                continue;
            }
            int j = i + 1;
            while (j < uEnd) {
                String l = user.get(j);
                if (!isCommentOrBlank(l) && indentOf(l) <= childInd) {
                    break;
                }
                j++;
            }
            have.put(k, new int[]{i, j});
        }
        List<String> add = new ArrayList<>();
        for (Block b : splitBlocks(defContent, defInd)) {
            int[] r = have.get(b.key);
            if (r == null) {
                if (add.isEmpty()) {
                    add.add(spaces(childInd) + "# -- добавлено обновлением VTRegister --");
                }
                for (String l : b.header) {
                    add.add(reindent(l, step));
                }
                for (String l : b.lines) {
                    add.add(reindent(l, step));
                }
                continue;
            }
            String keyLine = user.get(r[0]);
            String after = keyLine.substring(keyLine.indexOf(':') + 1).trim();
            if ((after.isEmpty() || after.startsWith("#")) && b.lines.size() > 1) {
                int ci = sectionIndent(user, r[0], r[1]);
                if (ci < 0) {
                    continue; // у пользователя там список — не трогаем
                }
                if (ci == 0) {
                    ci = childInd + step;
                }
                mergeSection(user, r[0], r[1], ci, step, b.lines.subList(1, b.lines.size()), defInd + 2, out);
            }
        }
        if (!add.isEmpty()) {
            int at = uEnd;
            // вставляем после последней значимой строки секции, а не после
            // комментариев, которые уже относятся к следующему ключу
            while (at - 1 > uStart && isCommentOrBlank(user.get(at - 1))) {
                at--;
            }
            out.add(new Ins(at, out.size(), add));
        }
    }

    /** Ключи, которые больше не действуют: удаляются вместе со своими # описаниями. */
    private static final String[][] OBSOLETE = {
            {"twofactor", "require_for_admins"}, // → twofactor.force_admins (по умолчанию выкл)
            {"afk", "track_authed"}              // → afk.kick_after_login
    };

    static boolean dropObsolete(List<String> lines) {
        boolean changed = false;
        for (String[] k : OBSOLETE) {
            int sec = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (k[0].equals(keyAt(lines.get(i), 0))) {
                    sec = i;
                    break;
                }
            }
            if (sec < 0) {
                continue;
            }
            for (int i = sec + 1; i < lines.size(); i++) {
                String l = lines.get(i);
                if (isCommentOrBlank(l)) {
                    continue;
                }
                int ind = indentOf(l);
                if (ind == 0) {
                    break; // следующая секция
                }
                if (!k[1].equals(keyAt(l, ind))) {
                    continue;
                }
                int j = i + 1;
                while (j < lines.size() && isCommentOrBlank(lines.get(j))) {
                    j++;
                }
                if (j < lines.size() && indentOf(lines.get(j)) > ind) {
                    break; // вложенная секция — не трогаем
                }
                int from = i;
                while (from - 1 > sec && lines.get(from - 1).trim().startsWith("#")
                        && indentOf(lines.get(from - 1)) == ind) {
                    from--;
                }
                for (int r = i; r >= from; r--) {
                    lines.remove(r);
                }
                changed = true;
                break;
            }
        }
        return changed;
    }

    private static int indentOf(String l) {
        int n = 0;
        while (n < l.length() && l.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static String spaces(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * То же слияние в памяти (для тестов): шаг 1 — недостающие ключи внутри
     * секций, шаг 2 — недостающие секции в конец. Возвращает новый текст.
     */
    static List<String> mergeLines(List<String> defLines, List<String> userLines) {
        List<String> user = new ArrayList<>(userLines);
        List<Block> defBlocks = splitBlocks(defLines, 0);
        Set<String> top = new LinkedHashSet<>();
        for (String line : user) {
            String k = keyAt(line, 0);
            if (k != null) {
                top.add(k);
            }
        }
        for (Block def : defBlocks) {
            if (top.contains(def.key)) {
                mergeSecondLevel(user, def);
            }
        }
        for (Block def : defBlocks) {
            if (!top.contains(def.key)) {
                user.addAll(def.header);
                user.addAll(def.lines);
            }
        }
        return user;
    }

    /**
     * Отступ первой значимой строки секции (start, end).
     * 0 = у секции нет содержимого; -1 = содержимое — список/мусор.
     */
    private static int sectionIndent(List<String> lines, int start, int end) {
        for (int i = start + 1; i < end; i++) {
            String l = lines.get(i);
            if (isCommentOrBlank(l)) {
                continue;
            }
            int n = 0;
            while (n < l.length() && l.charAt(n) == ' ') {
                n++;
            }
            if (n == 0 || n >= l.length() || l.charAt(n) == '-' || l.charAt(n) == '\t') {
                return -1;
            }
            return n;
        }
        return 0;
    }

    /**
     * Переиндентировать строку дефолта (шаг 2 пробела) под шаг ind:
     * уровень вложенности сохраняется, строка встаёт в отступы пользователя.
     */
    private static String reindent(String line, int ind) {
        if (ind == 2 || line == null) {
            return line;
        }
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        if (n == 0 || n == line.length()) {
            return line;
        }
        int nn = (n / 2) * ind + (n % 2);
        StringBuilder sb = new StringBuilder(nn + line.length() - n);
        for (int i = 0; i < nn; i++) {
            sb.append(' ');
        }
        return sb.append(line, n, line.length()).toString();
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
        if (text != null && yamlParses(text)) {
            String rep = repairMojibake(text);
            if (!rep.equals(text) && yamlParses(rep)) {
                writeUtf8(f, rep);
                plugin.getLogger().warning("ConfigMerger: " + f.getName()
                    + " содержал битую кодировку/символы — исправлен и пересохранён в UTF-8");
            }
            return;
        }
        if (text != null) {
            for (String candidate : candidates(text)) {
                if (yamlParses(candidate)) {
                    writeUtf8(f, candidate);
                    plugin.getLogger().warning("ConfigMerger: " + f.getName()
                        + " содержал битую кодировку/символы — исправлен и пересохранён в UTF-8");
                    return;
                }
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
                String s = decodeStrict(raw, StandardCharsets.UTF_8);
                if (!s.isEmpty() && s.charAt(0) == 0xFEFF) {
                    s = s.substring(1);
                    writeUtf8(f, s);
                }
                return s;
            } catch (Throwable bad) {
                String s = decodeStrict(raw, java.nio.charset.Charset.forName("windows-1251"));
                writeUtf8(f, s);
                if (plugin != null) {
                    plugin.getLogger().warning("ConfigMerger: " + f.getName()
                        + " содержал битую кодировку/символы — исправлен и пересохранён в UTF-8");
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
     * Обратный mojibake-ремонт построчно: строки, где UTF-8 был прочитан
     * как windows-1252 (Поп, ) или windows-1251 (РџРѕРї  кириллица
     * в диапазоне U+0400+), кодируются обратно в байты и декодируются
     * как UTF-8. Настоящая кириллица не проходит проверку (байты-лиды
     * без trail-байтов) и остаётся нетронутой.
     */
    private static String repairMojibake(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        boolean touched = false;
        for (int li = 0; li < lines.length; li++) {
            String ln = lines[li];
            if (li > 0) {
                out.append('\n');
            }
            int hi = 0;
            for (int i = 0; i < ln.length(); i++) {
                if (ln.charAt(i) >= 0x80) {
                    hi++;
                }
            }
            if (hi < 2) {
                out.append(ln);
                continue;
            }
            String fixed = null;
            // latin-1: каждый символ <= 0xFF -> байт (реальный коррупт
            // репозитория шёл именно этим путём, включая C1-символы)
            boolean allLow = true;
            for (int i = 0; i < ln.length(); i++) {
                if (ln.charAt(i) > 0xFF) {
                    allLow = false;
                    break;
                }
            }
            if (allLow) {
                byte[] bs = new byte[ln.length()];
                for (int i = 0; i < ln.length(); i++) {
                    bs[i] = (byte) ln.charAt(i);
                }
                try {
                    fixed = decodeStrict(bs, StandardCharsets.UTF_8);
                } catch (Throwable t) {
                    fixed = null;
                }
            }
            if (fixed == null) {
                fixed = tryRepair(ln, "windows-1252");
            }
            if (fixed == null) {
                fixed = tryRepair(ln, "windows-1251");
            }
            if (fixed != null) {
                out.append(fixed);
                touched = true;
            } else {
                out.append(ln);
            }
        }
        return touched ? out.toString() : text;
    }

    /** Перекодировать строку: charset -> байты -> UTF-8. null = не удалось. */
    private static String tryRepair(String line, String charset) {
        try {
            java.nio.ByteBuffer bb = java.nio.charset.Charset.forName(charset)
                    .newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(line));
            byte[] bs = new byte[bb.remaining()];
            bb.get(bs);
            return decodeStrict(bs, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
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
            // Mojibake внутри валидного YAML тоже чиним: если ремонт дал
            // другой текст и он парсится — предпочитаем отремонтированный.
            String rep = repairMojibake(text);
            if (!rep.equals(text)) {
                try {
                    org.bukkit.configuration.file.YamlConfiguration c2 =
                            new org.bukkit.configuration.file.YamlConfiguration();
                    c2.loadFromString(rep);
                    writeUtf8(f, rep);
                    return c2;
                } catch (Throwable ignored) {
                }
            }
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

    private static final int READY = 866282967;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1026) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1026) == READY;
    }
}
