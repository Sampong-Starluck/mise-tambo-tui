package com.sampong.tambo._common.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.sampong.tambo._common.model.AutoInstallStep;
import com.sampong.tambo._common.model.SdkVersion;

/**
 * Compares what the project config declares against what is installed, producing one
 * {@link AutoInstallStep} per declared tool.
 * <p>
 * Pure and backend-neutral: it takes the parsed {@code [tools]} table and the backend's own
 * {@code listSdks()} answer and returns a plan. Nothing here runs a command or touches the
 * filesystem, so the rule that decides "close enough to ask about" is one readable function
 * rather than something spread across the mise and vfox adapters.
 * <p>
 * The rule itself, in order:
 * <ol>
 *   <li><b>Satisfied</b> — an installed version answers the request: the same string, or the
 *       request read as a prefix range ({@code "21"} is answered by {@code 21.0.5}), or an
 *       alias with no version number in it at all ({@code latest}, {@code lts}, {@code system}),
 *       which nothing local can contradict.</li>
 *   <li><b>Similar</b> — no exact answer, but an installed version shares the request's
 *       <em>vendor prefix and major release</em>: {@code 25.0.3} against a config asking for
 *       {@code 25.0.4}, or {@code oracle-graalvm-25.0.3} against {@code oracle-graalvm-25.0.4}.
 *       The prefix has to match too, so a Temurin JDK is never offered as a near-miss for a
 *       GraalVM one — same number, materially different build. The newest such version wins.</li>
 *   <li><b>Missing</b> — neither; the config's version has to be fetched.</li>
 * </ol>
 * Offline, nothing can be fetched, so everything past rule 1 collapses into one question. Any
 * tool with <em>some</em> version on disk becomes a <b>substitute</b> step listing all of them,
 * ranked by {@link #closeness} to the request, with the closest recommended; only a tool with
 * nothing on disk at all stays missing.
 * <p>
 * Major release is the granularity on purpose. It is the line almost every SDK draws for
 * "compatible", it is coarse enough that a patch or minor bump still offers the user a choice
 * (which is the whole point), and it is narrow enough that Java 21 is never offered in answer
 * to a config asking for Java 25.
 */
public final class AutoInstallPlanner {

    private AutoInstallPlanner() {
    }

    /**
     * One step per entry in {@code requested}, in the config's own order.
     *
     * @param requested the {@code [tools]} table, as
     *                  {@link com.sampong.tambo._common.util.ProjectToolsReader} read it
     * @param installed everything the backend reports; entries that are not actually on disk
     *                  are ignored, since the point of the comparison is what can be reused
     *                  without a download
     * @param offline   true when nothing can be downloaded; see the class notes
     */
    public static List<AutoInstallStep> plan(Map<String, String> requested, List<SdkVersion> installed,
                                             boolean offline) {
        List<AutoInstallStep> steps = new ArrayList<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            steps.add(planOne(entry.getKey(), entry.getValue(), onDisk(entry.getKey(), installed), offline));
        }
        return steps;
    }

    private static AutoInstallStep planOne(String sdk, String version, List<SdkVersion> candidates,
                                           boolean offline) {
        // Where several installed versions answer the request (a range like "20" matched by
        // both 20.9.0 and 20.11.1), report the one actually in effect — it is the honest
        // answer to "which version is this config getting".
        SdkVersion exact = candidates.stream()
                .filter(v -> satisfies(version, v.version()))
                .max(Comparator.comparing(SdkVersion::active))
                .orElse(null);
        if (exact != null) {
            return AutoInstallStep.satisfied(sdk, version, exact.version());
        }
        if (offline) {
            List<String> ranked = candidates.stream()
                    .map(SdkVersion::version)
                    .distinct()
                    .sorted(closeness(version))
                    .toList();
            return ranked.isEmpty()
                    ? AutoInstallStep.missing(sdk, version)
                    : AutoInstallStep.substitute(sdk, version, ranked);
        }
        SdkVersion near = candidates.stream()
                .filter(v -> similar(version, v.version()))
                .max((a, b) -> compareVersions(a.version(), b.version()))
                .orElse(null);
        return near != null
                ? AutoInstallStep.similar(sdk, version, near.version())
                : AutoInstallStep.missing(sdk, version);
    }

    /** The versions of {@code sdk} that are genuinely present on disk and have a version at all. */
    private static List<SdkVersion> onDisk(String sdk, List<SdkVersion> installed) {
        return installed.stream()
                .filter(v -> v.name().equalsIgnoreCase(sdk) && v.installed() && v.hasVersion())
                .toList();
    }

    // ==================== Version matching ====================

    /** True when {@code installed} is an acceptable answer to a config asking for {@code requested}. */
    static boolean satisfies(String requested, String installed) {
        if (requested.equalsIgnoreCase(installed)) {
            return true;
        }
        Version want = Version.parse(requested);
        Version have = Version.parse(installed);
        if (want.numbers().isEmpty()) {
            // "latest", "lts", "system" — no number to compare, and resolving what they point
            // at needs the network. Anything installed is taken as an answer rather than
            // scheduling a download the user never asked for.
            return true;
        }
        return want.prefix().equalsIgnoreCase(have.prefix()) && startsWith(have.numbers(), want.numbers());
    }

    /** True when {@code installed} is the same vendor and major release as {@code requested}. */
    static boolean similar(String requested, String installed) {
        Version want = Version.parse(requested);
        Version have = Version.parse(installed);
        if (want.numbers().isEmpty() || have.numbers().isEmpty()) {
            return false;
        }
        return want.prefix().equalsIgnoreCase(have.prefix())
                && want.numbers().getFirst().equals(have.numbers().getFirst());
    }

    /**
     * Orders installed versions closest-to-{@code requested} first, for the offline substitute.
     * In order of weight:
     * <ol>
     *   <li>the same vendor prefix — a Temurin JDK is a worse stand-in for GraalVM than an older
     *       GraalVM is;</li>
     *   <li>more leading numbers in common — {@code 25.0.1} beats {@code 25.1.0} for a request of
     *       {@code 25.0.4}, and anything on major 25 beats anything that is not;</li>
     *   <li>the smaller gap at the first number that differs — for {@code 21}, {@code 22} beats
     *       {@code 17};</li>
     *   <li>then the newer version, so an equal gap either side ({@code 18} and {@code 22} for
     *       {@code 20}) resolves forward rather than backward.</li>
     * </ol>
     */
    static Comparator<String> closeness(String requested) {
        Version want = Version.parse(requested);
        Comparator<String> byPrefix = Comparator.comparing(
                (String v) -> !Version.parse(v).prefix().equalsIgnoreCase(want.prefix()));
        Comparator<String> byShared = Comparator.comparingInt(
                (String v) -> -sharedLeading(want.numbers(), Version.parse(v).numbers()));
        Comparator<String> byGap = Comparator.comparingInt((String v) -> {
            List<Integer> have = Version.parse(v).numbers();
            int i = sharedLeading(want.numbers(), have);
            return Math.abs(at(want.numbers(), i) - at(have, i));
        });
        Comparator<String> newestFirst = (a, b) -> compareVersions(b, a);
        return byPrefix.thenComparing(byShared).thenComparing(byGap).thenComparing(newestFirst);
    }

    /** How many leading numbers the two versions have in common. */
    private static int sharedLeading(List<Integer> a, List<Integer> b) {
        int i = 0;
        while (i < a.size() && i < b.size() && a.get(i).equals(b.get(i))) {
            i++;
        }
        return i;
    }

    /** Newest-wins ordering over two installed versions, comparing number by number. */
    private static int compareVersions(String a, String b) {
        List<Integer> left = Version.parse(a).numbers();
        List<Integer> right = Version.parse(b).numbers();
        for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
            int cmp = Integer.compare(at(left, i), at(right, i));
            if (cmp != 0) {
                return cmp;
            }
        }
        // Numerically equal (e.g. "21.0" and "21.0.0") — fall back to the text so this stays a
        // total order rather than picking arbitrarily between them.
        return a.compareTo(b);
    }

    private static int at(List<Integer> numbers, int index) {
        return index < numbers.size() ? numbers.get(index) : 0;
    }

    private static boolean startsWith(List<Integer> full, List<Integer> prefix) {
        if (prefix.size() > full.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!full.get(i).equals(prefix.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A version string split into the part that identifies the build and the part that orders
     * it: {@code "oracle-graalvm-25.0.3"} becomes prefix {@code "oracle-graalvm"} and numbers
     * {@code [25, 0, 3]}; {@code "25.0.4"} becomes an empty prefix and the same shape of
     * numbers; {@code "latest"} becomes prefix {@code "latest"} and no numbers at all, which is
     * how the rules above recognise an alias without having to list them.
     */
    private record Version(String prefix, List<Integer> numbers) {

        static Version parse(String raw) {
            String text = raw.strip();
            int firstDigit = -1;
            for (int i = 0; i < text.length(); i++) {
                if (Character.isDigit(text.charAt(i))) {
                    firstDigit = i;
                    break;
                }
            }
            if (firstDigit < 0) {
                return new Version(text, List.of());
            }
            String prefix = trimSeparators(text.substring(0, firstDigit));
            // A leading "v" is decoration on the number, not part of a vendor name.
            if (prefix.equalsIgnoreCase("v")) {
                prefix = "";
            }
            return new Version(prefix, numbersIn(text.substring(firstDigit)));
        }

        private static String trimSeparators(String prefix) {
            int end = prefix.length();
            while (end > 0 && "-_.@".indexOf(prefix.charAt(end - 1)) >= 0) {
                end--;
            }
            return prefix.substring(0, end);
        }

        /**
         * The dot-separated leading integers of a version tail, stopping at the first segment
         * that does not start with a digit — so {@code "17.0.9+9.1"} reads as
         * {@code [17, 0, 9]} and a build qualifier never shifts the comparison.
         */
        private static List<Integer> numbersIn(String tail) {
            List<Integer> numbers = new ArrayList<>();
            for (String segment : tail.split("\\.")) {
                Integer value = leadingInt(segment);
                if (value == null) {
                    break;
                }
                numbers.add(value);
            }
            return List.copyOf(numbers);
        }

        private static @Nullable Integer leadingInt(String segment) {
            int end = 0;
            while (end < segment.length() && Character.isDigit(segment.charAt(end))) {
                end++;
            }
            if (end == 0) {
                return null;
            }
            try {
                return Integer.valueOf(segment.substring(0, end));
            } catch (NumberFormatException e) {
                return null; // a run of digits too long for an int — not a version we can order
            }
        }
    }
}
