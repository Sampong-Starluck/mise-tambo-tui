package com.sampong.tambo._common.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Reads the {@code [tools]} table out of a project config — {@code mise.toml} or
 * {@code .vfox.toml}, which declare tools identically — into a plain {@code name -> version}
 * map in file order.
 * <p>
 * Deliberately not a TOML parser, and deliberately no TOML library on the classpath for one
 * table in one file. It understands the shapes a tool version is actually written in:
 * <pre>
 *   [tools]
 *   java   = "oracle-graalvm-25.0.3"    a plain string
 *   node   = ["20", "18"]               a list of versions — the first is the one in effect
 *   python = { version = "3.12" }       an inline table with options beside the version
 *
 *   [tools.ruby]                        mise's per-tool table, same thing spelled out
 *   version = "3.3"
 * </pre>
 * Anything else in the file is skipped rather than rejected: this runs against a config the
 * backend itself owns and will parse properly on the next command, so guessing wrong about an
 * exotic line must cost a missing row in the plan, never a failed read. In particular a value
 * spread over several lines (an array or inline table broken across newlines) is not
 * reassembled — the tool is simply left out of the plan.
 */
public final class ProjectToolsReader {

    private static final String TOOLS_TABLE = "tools";
    private static final String VERSION_KEY = "version";

    private ProjectToolsReader() {
    }

    /**
     * The tools {@code configFile} declares, in the order it declares them. An unreadable or
     * absent file reads as "declares nothing" — the caller's own message about an empty plan
     * is more useful than an exception it would only have to catch and turn into one.
     */
    public static Map<String, String> read(Path configFile) {
        List<String> lines;
        try {
            lines = Files.readAllLines(configFile);
        } catch (IOException e) {
            return Map.of();
        }
        Map<String, String> tools = new LinkedHashMap<>();
        boolean inTools = false;
        // Non-null while inside a [tools.<name>] table, naming the tool its keys describe.
        String nestedTool = null;

        for (String rawLine : lines) {
            String line = stripComment(rawLine).strip();
            if (line.startsWith("[")) {
                String header = tableName(line);
                inTools = TOOLS_TABLE.equals(header);
                nestedTool = nestedToolName(header);
            } else if (!line.isEmpty() && (inTools || nestedTool != null)) {
                readEntry(tools, line, nestedTool);
            }
        }
        return tools;
    }

    /**
     * One {@code key = value} line inside a tools table. {@code nestedTool} names the tool when
     * the line is in its own {@code [tools.<name>]} table, and is null directly under
     * {@code [tools]}. A line without a key is skipped.
     */
    private static void readEntry(Map<String, String> tools, String line, @Nullable String nestedTool) {
        int equals = line.indexOf('=');
        if (equals <= 0) {
            return;
        }
        String key = unquote(line.substring(0, equals).strip());
        String value = line.substring(equals + 1).strip();
        if (nestedTool == null) {
            putIfUsable(tools, key, parseVersion(value));
        } else if (key.equals(VERSION_KEY)) {
            // Inside [tools.<name>] every key but `version` is an option for that tool.
            putIfUsable(tools, nestedTool, unquote(value));
        }
    }

    private static void putIfUsable(Map<String, String> tools, String name, @Nullable String version) {
        if (!name.isEmpty() && version != null && !version.isEmpty()) {
            tools.put(name, version);
        }
    }

    /** {@code "[tools.java]"} → {@code "tools.java"}; anything unterminated → the raw text. */
    private static String tableName(String line) {
        int close = line.indexOf(']');
        return close < 0 ? line.substring(1).strip() : line.substring(1, close).strip();
    }

    /** {@code "tools.java"} → {@code "java"}, for mise's per-tool table form; null otherwise. */
    private static @Nullable String nestedToolName(String header) {
        String prefix = TOOLS_TABLE + ".";
        if (!header.startsWith(prefix)) {
            return null;
        }
        String name = unquote(header.substring(prefix.length()).strip());
        return name.isEmpty() ? null : name;
    }

    /**
     * The version out of a value, whichever of the three inline shapes it takes. Returns null
     * when the value is none of them — a multi-line array, say, whose first line is a bare
     * {@code [}.
     */
    private static @Nullable String parseVersion(String value) {
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("[")) {
            // A list of versions: the first is the one in effect, the rest are fallbacks.
            int close = value.indexOf(']');
            String first = (close < 0 ? value.substring(1) : value.substring(1, close)).strip();
            int comma = first.indexOf(',');
            return unquote((comma < 0 ? first : first.substring(0, comma)).strip());
        }
        if (value.startsWith("{")) {
            // An inline table; only its `version` key is a version, the rest are options.
            int close = value.lastIndexOf('}');
            String body = close < 0 ? value.substring(1) : value.substring(1, close);
            for (String field : body.split(",")) {
                int equals = field.indexOf('=');
                if (equals > 0 && unquote(field.substring(0, equals).strip()).equals(VERSION_KEY)) {
                    return unquote(field.substring(equals + 1).strip());
                }
            }
            return null;
        }
        return unquote(value);
    }

    /**
     * Drops a trailing {@code #} comment, ignoring one inside a quoted string — a version can
     * legitimately contain {@code #} (a {@code ref:} or URL-ish value), and cutting the line
     * there would silently truncate it.
     */
    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** Removes one matching pair of surrounding single or double quotes, if present. */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
