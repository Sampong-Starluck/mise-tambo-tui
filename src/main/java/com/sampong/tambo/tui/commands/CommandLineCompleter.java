package com.sampong.tambo.tui.commands;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Completion and preview logic for the {@code :} command line, which lets the user type a raw
 * mise command and have it run here. Pure on purpose: it knows nothing about rendering or keys,
 * so the bar can ask it for suggestions, the text a choice would produce, and a one-line account
 * of what the command will do, and tests can check each without a terminal.
 * <p>
 * The completer only ever looks at the token under the cursor (the last one, or an empty token
 * after a trailing space), so a multi-word command completes the word being typed and leaves the
 * rest of the line alone. Versions come from a function rather than a field so the backend decides
 * how they are fetched and cached; this class only filters what it is handed.
 */
@RequiredArgsConstructor
public final class CommandLineCompleter {

    /**
     * The mise subcommands offered for the first word. A fixed list rather than asking mise,
     * because {@code mise --help} is slow to parse and the set changes rarely.
     */
    public static final List<String> DEFAULT_SUBCOMMANDS = List.of(
            "install", "use", "uninstall", "upgrade", "outdated", "ls", "ls-remote", "unuse", "prune",
            "doctor", "trust", "self-update", "cache", "run", "where", "latest", "plugins");

    /** Keeps the popup short enough to sit above the bar without scrolling. */
    private static final int MAX_SUGGESTIONS = 8;

    /**
     * One completion candidate.
     *
     * @param replacement the whole token the current word becomes, e.g. {@code node@22.11.0}
     * @param display     what the popup shows, e.g. {@code 22.11.0}
     * @param note        a dim annotation shown beside the display, or empty
     */
    public record Suggestion(String replacement, String display, String note) {
    }

    @NonNull
    private final String backendName;
    @NonNull
    private final List<String> subcommands;
    @NonNull
    private final Function<String, List<String>> versionsOf;
    @NonNull
    private final Supplier<List<String>> toolNames;

    /**
     * Candidates for the word under the cursor, at most {@value #MAX_SUGGESTIONS}.
     * <ul>
     *   <li>the first word completes to a subcommand;</li>
     *   <li>a word containing {@code @} completes to a version of the tool before it;</li>
     *   <li>a flag gets nothing, since there is no list of flags to offer;</li>
     *   <li>any other later word completes to a tool name.</li>
     * </ul>
     */
    public List<Suggestion> suggest(String input) {
        boolean trailingSpace = input.isEmpty() || Character.isWhitespace(input.charAt(input.length() - 1));
        List<String> words = tokens(input);
        String current = trailingSpace ? "" : words.get(words.size() - 1);
        int index = trailingSpace ? words.size() : words.size() - 1;

        if (index == 0) {
            return subcommands.stream()
                    .filter(s -> s.startsWith(current))
                    .limit(MAX_SUGGESTIONS)
                    .map(s -> new Suggestion(s, s, ""))
                    .toList();
        }

        int at = current.indexOf('@');
        if (at >= 0) {
            String tool = current.substring(0, at);
            String prefix = current.substring(at + 1);
            if (tool.isEmpty()) {
                return List.of();
            }
            return versionsOf.apply(tool).stream()
                    .filter(v -> v.startsWith(prefix))
                    .distinct()
                    .limit(MAX_SUGGESTIONS)
                    .map(v -> new Suggestion(tool + "@" + v, v, ""))
                    .toList();
        }

        if (current.startsWith("-")) {
            return List.of();
        }
        return toolNames.get().stream()
                .filter(t -> t.startsWith(current))
                .distinct()
                .limit(MAX_SUGGESTIONS)
                .map(t -> new Suggestion(t, t, ""))
                .toList();
    }

    /**
     * The input with only its current word replaced by the chosen candidate. Subcommands and tool
     * names get a trailing space so the user can keep typing the next word; a versioned token does
     * not, since a version is normally the last word of a command.
     */
    public String apply(String input, Suggestion s) {
        int start = input.length();
        while (start > 0 && !Character.isWhitespace(input.charAt(start - 1))) {
            start--;
        }
        String tail = s.replacement().contains("@") ? "" : " ";
        return input.substring(0, start) + s.replacement() + tail;
    }

    /** Splits a command line into its words; blank input yields no words. */
    public static List<String> tokens(String input) {
        String stripped = input.strip();
        return stripped.isEmpty() ? List.of() : List.of(stripped.split("\\s+"));
    }

    /** The command as it will be shown before running, prefixed with the backend's name. */
    public String preview(String input) {
        return backendName + " " + input.strip();
    }

    /**
     * What the command does to the machine, in a few words, so a user about to run {@code use}
     * or {@code prune} knows whether it touches the project or the global config, or deletes
     * something. Empty for commands with no side effect worth naming.
     */
    public String effect(String input) {
        List<String> words = tokens(input);
        if (words.isEmpty()) {
            return "";
        }
        return switch (words.get(0)) {
            case "use" -> words.stream().anyMatch(w -> w.equals("-g") || w.equals("--global"))
                    ? "writes global config"
                    : "writes mise.toml";
            case "install" -> "downloads and installs";
            case "uninstall", "prune" -> "removes installed files";
            default -> "";
        };
    }
}
