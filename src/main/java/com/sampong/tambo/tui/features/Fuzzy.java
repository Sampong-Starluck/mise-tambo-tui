package com.sampong.tambo.tui.features;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

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
    public static int score(@Nullable String query, @Nullable String candidate) {
        if (query == null || query.isEmpty()) {
            return 0;
        }
        if (candidate == null || candidate.isEmpty()) {
            return -1;
        }
        String q = query.toLowerCase(Locale.ROOT);
        String c = candidate.toLowerCase(Locale.ROOT);
        int score = subsequenceScore(q, c);
        if (score < 0) {
            return -1;
        }
        // Mild penalty for long candidates so tight matches float to the top.
        return Math.max(1, score - (c.length() - q.length()) / 4);
    }

    /**
     * Walks {@code c} looking for the characters of {@code q} in order. Returns the raw score of
     * the match, or {@code -1} when {@code q} is not a subsequence of {@code c}.
     */
    private static int subsequenceScore(String q, String c) {
        int qi = 0;
        int score = 0;
        int streak = 0;
        for (int ci = 0; ci < c.length() && qi < q.length(); ci++) {
            if (c.charAt(ci) != q.charAt(qi)) {
                streak = 0;
                continue;
            }
            streak++;
            score += 1 + streak;                           // reward consecutive runs
            if (startsWord(c, ci)) {
                score += 4;                                // reward word-boundary hits
            }
            qi++;
        }
        return qi < q.length() ? -1 : score;
    }

    private static boolean startsWord(String c, int i) {
        return i == 0 || isSeparator(c.charAt(i - 1));
    }

    private static boolean isSeparator(char ch) {
        return ch == '-' || ch == '_' || ch == '.' || ch == ' ' || ch == '/' || ch == ':' || ch == '@';
    }

    /**
     * Filters and ranks {@code items} by fuzzy-matching the query against a primary
     * key, falling back to a (lower-weighted) secondary key. Order is preserved for
     * the empty query, and stable within equal scores otherwise.
     */
    public static <T> List<T> filter(@Nullable String query,
                                     List<T> items,
                                     Function<T, String> primary,
                                     @Nullable Function<T, String> secondary) {
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
