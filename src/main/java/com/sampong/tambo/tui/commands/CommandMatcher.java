package com.sampong.tambo.tui.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.sampong.tambo.tui.features.Fuzzy;

import lombok.NonNull;

/**
 * Ranks {@link Command}s against what the user typed into the Ctrl+P palette. Pure logic — no
 * rendering — so the ordering and the highlighted characters can be tested without a terminal.
 * <p>
 * A title match always outranks a group-label-only match: someone typing {@code clean} wants the
 * commands whose names say clean, and only then the ones that merely sit under the Clean-up
 * heading. Within each tier the better fuzzy score comes first, and ties keep the order the
 * commands were given in, so the list does not reshuffle between keystrokes that do not change
 * the ranking.
 */
public final class CommandMatcher {

    private CommandMatcher() {
    }

    /**
     * A command that matched.
     *
     * @param command   the matched command
     * @param highlight indices into {@code command.title()} of the characters the query matched,
     *                  in order; empty when only the group label matched
     */
    public record Match(Command command, List<Integer> highlight) {
    }

    /** Title hits sort before group-label-only hits. */
    private static final int TITLE_TIER = 0;
    private static final int GROUP_TIER = 1;

    private record Scored(Match match, int tier, int score) {
    }

    /**
     * Returns the commands that match {@code query}, best first. A blank query returns every
     * command in the given order with no highlights, since there is nothing to rank against.
     */
    public static List<Match> rank(@NonNull String query, @NonNull List<Command> commands) {
        String q = query.strip();
        if (q.isEmpty()) {
            return commands.stream().map(c -> new Match(c, List.of())).toList();
        }

        List<Scored> scored = new ArrayList<>();
        for (Command c : commands) {
            int titleScore = Fuzzy.score(q, c.title());
            if (titleScore > 0) {
                scored.add(new Scored(new Match(c, highlight(q, c.title())), TITLE_TIER, titleScore));
                continue;
            }
            int groupScore = Fuzzy.score(q, c.group().label());
            if (groupScore > 0) {
                scored.add(new Scored(new Match(c, List.of()), GROUP_TIER, groupScore));
            }
        }
        // List.sort is stable, so equal tier and score keep the caller's order.
        scored.sort(Comparator.comparingInt(Scored::tier)
                .thenComparing(Comparator.comparingInt(Scored::score).reversed()));

        List<Match> out = new ArrayList<>(scored.size());
        for (Scored s : scored) {
            out.add(s.match());
        }
        return out;
    }

    /**
     * The title positions the query lands on, found greedily left to right the same way
     * {@link Fuzzy#score} walks the candidate, so the highlight agrees with why the title
     * matched. Compared per character rather than on a lowercased copy so the indices always
     * line up with the title as printed.
     */
    static List<Integer> highlight(String query, String title) {
        List<Integer> hits = new ArrayList<>();
        int qi = 0;
        for (int ti = 0; ti < title.length() && qi < query.length(); ti++) {
            if (Character.toLowerCase(title.charAt(ti)) == Character.toLowerCase(query.charAt(qi))) {
                hits.add(ti);
                qi++;
            }
        }
        return hits;
    }
}
