package com.sampong.tambo.tui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * A small fzf-style fuzzy matcher: the query must appear as a subsequence of the
 * candidate; consecutive matches and matches at word boundaries score higher.
 */
public final class Fuzzy {

    private Fuzzy() {
    }

    /**
     * Scores {@code query} against {@code candidate}.
     *
     * @return a positive score when the query is a subsequence of the candidate
     *         (higher is better), or {@code -1} when it does not match at all.
     *         The empty query matches everything with score {@code 0}.
     */
    public static int score(String query, String candidate) {
        if (query == null || query.isEmpty()) {
            return 0;
        }
        if (candidate == null || candidate.isEmpty()) {
            return -1;
        }
        String q = query.toLowerCase(Locale.ROOT);
        String c = candidate.toLowerCase(Locale.ROOT);

        int qi = 0;
        int score = 0;
        int streak = 0;
        for (int ci = 0; ci < c.length() && qi < q.length(); ci++) {
            if (c.charAt(ci) == q.charAt(qi)) {
                streak++;
                score += 1 + streak;                       // reward consecutive runs
                if (ci == 0 || isSeparator(c.charAt(ci - 1))) {
                    score += 4;                            // reward word-boundary hits
                }
                qi++;
            } else {
                streak = 0;
            }
        }
        if (qi < q.length()) {
            return -1;
        }
        // Mild penalty for long candidates so tight matches float to the top.
        score -= (c.length() - q.length()) / 4;
        return Math.max(1, score);
    }

    private static boolean isSeparator(char ch) {
        return ch == '-' || ch == '_' || ch == '.' || ch == ' ' || ch == '/' || ch == ':' || ch == '@';
    }

    /**
     * Filters and ranks {@code items} by fuzzy-matching the query against a primary
     * key, falling back to a (lower-weighted) secondary key. Order is preserved for
     * the empty query, and stable within equal scores otherwise.
     */
    public static <T> List<T> filter(String query,
                                     List<T> items,
                                     Function<T, String> primary,
                                     Function<T, String> secondary) {
        if (query == null || query.isBlank()) {
            return items;
        }
        record Scored<T>(T item, int score) {
        }
        List<Scored<T>> scored = new ArrayList<>();
        for (T item : items) {
            int s = score(query, primary.apply(item));
            if (s > 0) {
                s += 1000; // primary-key hits always outrank secondary-only hits
            } else if (secondary != null) {
                s = score(query, secondary.apply(item));
            }
            if (s > 0) {
                scored.add(new Scored<>(item, s));
            }
        }
        scored.sort(Comparator.comparingInt((Scored<T> s) -> s.score()).reversed());
        List<T> out = new ArrayList<>(scored.size());
        for (Scored<T> s : scored) {
            out.add(s.item());
        }
        return out;
    }
}
