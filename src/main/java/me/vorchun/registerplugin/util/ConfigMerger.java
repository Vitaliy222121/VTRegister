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
        if (!p7()) {
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
        if (changedInner) {
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
        if (changedInner || tail.length() > 0) {
            plugin.getLogger().info(resourceName + ": добавлены новые ключи (комментарии сохранены)");
        }
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
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
        } catch (Throwable t) {
            return null;
        }
        return lines;
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

    private static final int P7 = 983397651;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x1026) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1026) == P7;
    }
}
