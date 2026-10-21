package com.sampong.tambo.tui.features;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Turns two versions of a {@code mise.toml} into a short, human list of what changed in its
 * {@code [tools]} table — the text the undo history and the Jobs view show.
 * <p>
 * This is a deliberately naive reader, not a TOML parser. It only needs to cover what mise
 * writes for tool entries: bare or quoted keys, a string, an array of strings, or an inline
 * table with a {@code version}. Anything it cannot read is skipped rather than failing, because
 * a wrong line in a history label is far less harmful than an exception in the render path.
 * Comparing the full text of the files is still done by callers, so a {@code [settings]} edit is
 * never lost — it just shows up as {@code "edited"}.
 */
public final class ToolsDiff {

    private static final String MINUS = "\u2212";
    private static final String ARROW = " \u2192 ";

    /** A quoted string. Possessive, for the same reason as {@code TomlSyntax}: no per-char recursion. */
    private static final Pattern STRING = Pattern.compile(
            "\"((?:[^\"\\\\]++|\\\\.)*+)\"|'([^']*+)'");

    /** The {@code version} key inside an inline table such as {@code { version = "21", os = [...] }}. */
    private static final Pattern INLINE_VERSION = Pattern.compile(
            "\\bversion\\s*=\\s*(?:\"((?:[^\"\\\\]++|\\\\.)*+)\"|'([^']*+)'|([^,}\\s]++))");

    /** A bare (unquoted) key, which TOML limits to these characters. */
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_.\\-]++");

    private ToolsDiff() {
    }

    /**
     * The per-tool changes between two versions of a config, in name order.
     * <p>
     * Each entry is one of {@code "node 22.9.0 → 22.11.0"} (version changed), {@code "+ maven 3.9.9"}
     * (added) or {@code "− gradle 8.10"} (removed). Either side may be {@code null} when the file
     * did not exist, which makes every tool in the other side an addition or removal.
     */
    public static List<String> changes(@Nullable String beforeToml, @Nullable String afterToml) {
        Map<String, String> before = parseTools(beforeToml);
        Map<String, String> after = parseTools(afterToml);

        Set<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());

        List<String> out = new ArrayList<>();
        for (String name : names) {
            boolean wasThere = before.containsKey(name);
            boolean isThere = after.containsKey(name);
            if (wasThere && isThere) {
                String was = before.get(name);
                String now = after.get(name);
                if (!was.equals(now)) {
                    out.add(describe(name, was) + ARROW + (now.isEmpty() ? "(no version)" : now));
                }
            } else if (isThere) {
                out.add("+ " + describe(name, after.get(name)));
            } else {
                out.add(MINUS + " " + describe(name, before.get(name)));
            }
        }
        return out;
    }

    /**
     * A one-line label for a config save: the {@link #changes} joined with {@code "; "}.
     * <p>
     * Falls back to {@code "edited"} when the files differ but {@code [tools]} is the same, so a
     * {@code [settings]} edit still gets a label. Returns {@code ""} when the files are identical.
     */
    public static String summary(@Nullable String before, @Nullable String after) {
        if (Objects.equals(before, after)) {
            return "";
        }
        List<String> changes = changes(before, after);
        return changes.isEmpty() ? "edited" : String.join("; ", changes);
    }

    private static String describe(String name, String version) {
        return version.isEmpty() ? name : name + " " + version;
    }

    /** Name → version text for every entry in {@code [tools]}; empty when the file is null or has none. */
    private static Map<String, String> parseTools(@Nullable String toml) {
        Map<String, String> tools = new TreeMap<>();
        if (toml == null) {
            return tools;
        }
        String[] lines = toml.split("\\R", -1);
        boolean inTools = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                // Any header ends the previous table; only the exact [tools] header starts ours.
                inTools = stripComment(line).replaceAll("\\s", "").equals("[tools]");
                continue;
            }
            if (!inTools) {
                continue;
            }

            Entry entry = keyOf(line);
            if (entry == null) {
                continue;
            }
            // A multi-line array or inline table: keep appending lines until its brackets close.
            String value = entry.rest();
            while (open(value) && i + 1 < lines.length) {
                value = value + "\n" + lines[++i];
            }
            tools.put(entry.name(), versionOf(value));
        }
        return tools;
    }

    /** A parsed {@code key = value} line: the key without quotes, and the raw text after the {@code =}. */
    private record Entry(String name, String rest) {
    }

    private static @Nullable Entry keyOf(String line) {
        String name;
        String after;
        if (line.startsWith("\"") || line.startsWith("'")) {
            char quote = line.charAt(0);
            int close = line.indexOf(quote, 1);
            if (close < 0) {
                return null;
            }
            name = line.substring(1, close);
            after = line.substring(close + 1).trim();
        } else {
            Matcher m = BARE_KEY.matcher(line);
            if (!m.lookingAt()) {
                return null;
            }
            name = m.group();
            after = line.substring(m.end()).trim();
        }
        if (!after.startsWith("=")) {
            return null;
        }
        return new Entry(name, after.substring(1).trim());
    }

    /** The version text of a raw value: a string's content, an array's items joined, or an inline table's {@code version}. */
    private static String versionOf(String raw) {
        String value = stripComment(raw).trim();
        if (value.startsWith("[")) {
            List<String> items = new ArrayList<>();
            Matcher m = STRING.matcher(value);
            while (m.find()) {
                items.add(firstNonNull(m.group(1), m.group(2)));
            }
            return String.join(", ", items);
        }
        if (value.startsWith("{")) {
            Matcher m = INLINE_VERSION.matcher(value);
            if (!m.find()) {
                return "";
            }
            return firstNonNull(m.group(1), m.group(2), m.group(3));
        }
        Matcher m = STRING.matcher(value);
        if (m.lookingAt()) {
            return firstNonNull(m.group(1), m.group(2));
        }
        return value;
    }

    private static String firstNonNull(@Nullable String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return "";
    }

    /** Whether a value still has an unclosed {@code [} or {@code {} outside quotes and comments. */
    private static boolean open(String value) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                // Basic strings cannot span lines, so a newline also ends a quote we failed to close.
                if (c == '\n') {
                    quote = 0;
                } else if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                while (i + 1 < value.length() && value.charAt(i + 1) != '\n') {
                    i++;
                }
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            }
        }
        return depth > 0;
    }

    /** Drops a trailing {@code # comment}, leaving {@code #} inside quotes alone. Keeps newlines. */
    private static String stripComment(String text) {
        StringBuilder out = new StringBuilder(text.length());
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\n') {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < text.length()) {
                    out.append(c).append(text.charAt(++i));
                    continue;
                } else if (c == quote) {
                    quote = 0;
                }
                out.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                out.append(c);
            } else if (c == '#') {
                while (i + 1 < text.length() && text.charAt(i + 1) != '\n') {
                    i++;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
