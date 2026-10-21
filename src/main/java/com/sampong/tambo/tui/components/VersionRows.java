package com.sampong.tambo.tui.components;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;

import org.jspecify.annotations.Nullable;

/**
 * The rows of the Versions column: one tool's installed versions, then the versions it could
 * still be moved to. A pure model with no rendering, so the grouping, ordering and tagging can be
 * tested without a terminal, and so {@link VersionsPanel} and anything that reads its selection
 * see the same list.
 * <p>
 * Installed comes first and the active version leads it: that is the answer to "what am I
 * running", and the one the user most often wants to act on. Available follows in the order the
 * backend gave it, which is newest first. Headers and notes are rows too, so the table can show
 * them, but {@link #selectable} skips them — the cursor never lands on a label.
 * <p>
 * Version ordering is numeric-aware rather than lexical: {@code 22.9.0} sorts below
 * {@code 22.11.0}, which a plain string compare gets backwards.
 */
public final class VersionRows {

    /** Shown in place of the available list while the backend's release listing is in flight. */
    /** Tag of a version a config pins that is not on disk. */
    static final String MISSING = "missing";
    static final String LOADING = "loading versions…";
    /** Shown when the release listing came back with nothing the user does not already have. */
    static final String NONE_FOUND = "no other versions found";

    /** The tag the newest available release carries. */
    private static final String LATEST = "latest";
    private static final String PROJECT = "project";
    private static final String GLOBAL = "global";

    /** Separators between version parts: {@code 22.11.0}, {@code 1.0.0-rc1}, {@code 1.0.0+build}. */
    private static final Pattern SEPARATOR = Pattern.compile("[.\\-+_]");

    /**
     * Active first, then the rest by version descending. Two sorts folded into one comparator so
     * the installed group can be a single pass over an already-ordered list.
     */
    private static final Comparator<SdkVersion> INSTALLED_ORDER = (a, b) -> {
        if (a.active() != b.active()) {
            return a.active() ? -1 : 1;
        }
        return compareVersions(b.version(), a.version());
    };

    private VersionRows() {
    }

    /** What a row is: a section label, a version the user can act on, or a status line. */
    public enum Kind { HEADER, VERSION, NOTE }

    /**
     * One line of the Versions column.
     *
     * @param kind      whether this is a label, a selectable version, or a status note
     * @param text      what the row displays — the version itself for a VERSION row
     * @param version   the version a command should be given; empty for headers and notes
     * @param tag       the dim word at the end of the row: {@code project}, {@code global},
     *                  {@code latest}, or empty
     * @param active    whether this is the version in effect for the project
     * @param installed whether this version is already on disk
     * @param newer     whether this is the backend's latest and is newer than the active version
     */
    public record VersionRow(Kind kind, String text, String version, String tag,
                             boolean active, boolean installed, boolean newer) {
    }

    /**
     * The rows for {@code tool}, built from the whole SDK state list and the releases the backend
     * listed for it.
     *
     * @param sdks     every installed or configured SDK; only entries named {@code tool} are used
     * @param releases the listing from {@code ls-remote}, or null while it is still loading
     * @param latest   the newer version the outdated check reported for this tool, or null
     */
    public static List<VersionRow> build(String tool, List<SdkVersion> sdks,
                                         @Nullable List<SdkRelease> releases, @Nullable String latest) {
        // The same version can be reported by more than one config source. Sorting first and
        // keeping the first entry per version means the active one wins when there is one.
        Map<String, SdkVersion> installed = new LinkedHashMap<>();
        sdks.stream()
                .filter(sdk -> sdk.name().equals(tool) && sdk.hasVersion())
                .sorted(INSTALLED_ORDER)
                .forEach(sdk -> installed.putIfAbsent(sdk.version(), sdk));

        String activeVersion = installed.values().stream()
                .filter(SdkVersion::active)
                .map(SdkVersion::version)
                .findFirst()
                .orElse(null);

        List<VersionRow> rows = new ArrayList<>();
        rows.add(header("INSTALLED"));
        for (SdkVersion sdk : installed.values()) {
            boolean newer = !sdk.active() && isNewer(sdk.version(), latest, activeVersion);
            // A version a config asks for but the disk does not have stays in this group — it is
            // this project's version all the same — tagged so it cannot pass for installed.
            rows.add(new VersionRow(Kind.VERSION, sdk.version(), sdk.version(),
                    sdk.installed() ? installedTag(sdk) : MISSING, sdk.active(), sdk.installed(), newer));
        }

        if (releases == null) {
            rows.add(note(LOADING));
            return rows;
        }

        // The "latest" alias is a pointer, not a release, so listing it would show the same
        // version twice. Anything already installed is in the section above.
        List<SdkRelease> available = releases.stream()
                .filter(release -> !release.latest() && !release.installed()
                        && !installed.containsKey(release.version()))
                .toList();
        if (available.isEmpty()) {
            rows.add(note(NONE_FOUND));
            return rows;
        }

        rows.add(header("AVAILABLE · " + available.size()));
        for (SdkRelease release : available) {
            String version = release.version();
            String tag = version.equals(latest) ? LATEST : "";
            rows.add(new VersionRow(Kind.VERSION, version, version, tag, false, false,
                    isNewer(version, latest, activeVersion)));
        }
        return rows;
    }

    /** The indexes of the rows the cursor may land on — every VERSION row, never a label. */
    public static List<Integer> selectable(List<VersionRow> rows) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).kind() == Kind.VERSION) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    static VersionRow note(String text) {
        return new VersionRow(Kind.NOTE, text, "", "", false, false, false);
    }

    private static VersionRow header(String text) {
        return new VersionRow(Kind.HEADER, text, "", "", false, false, false);
    }

    /**
     * {@code global} when the config that pinned it is the user-wide one, else {@code project}
     * for an active or config-pinned version. Global is checked first: a version set globally is
     * active everywhere, and tagging it "project" would claim a pin the project does not have.
     */
    private static String installedTag(SdkVersion sdk) {
        if (isGlobal(sdk)) {
            return GLOBAL;
        }
        boolean projectSource = sdk.sourceType() != null
                && sdk.sourceType().toLowerCase(Locale.ROOT).contains(PROJECT);
        return sdk.active() || sdk.pinned() || projectSource ? PROJECT : "";
    }

    private static boolean isGlobal(SdkVersion sdk) {
        String type = sdk.sourceType();
        if (type != null && type.toLowerCase(Locale.ROOT).contains(GLOBAL)) {
            return true;
        }
        String path = sdk.sourcePath();
        return path != null && path.replace('\\', '/').contains(".config/mise");
    }

    /** True when {@code version} is the outdated check's latest and is newer than what is active. */
    private static boolean isNewer(String version, @Nullable String latest, @Nullable String activeVersion) {
        return latest != null && latest.equals(version)
                && (activeVersion == null || compareVersions(version, activeVersion) > 0);
    }

    /**
     * Compares two version strings part by part, numerically where both parts are numbers.
     * A leading {@code v} is ignored. When one version has extra parts, a numeric extra makes it
     * the newer ({@code 22.0.1} beats {@code 22.0}), and a textual one such as {@code rc1} makes
     * it the older (a pre-release sorts before the release it leads up to).
     */
    static int compareVersions(String a, String b) {
        String[] left = tokens(a);
        String[] right = tokens(b);
        int common = Math.min(left.length, right.length);
        for (int i = 0; i < common; i++) {
            int c = compareToken(left[i], right[i]);
            if (c != 0) {
                return c;
            }
        }
        if (left.length == right.length) {
            return 0;
        }
        boolean leftLonger = left.length > right.length;
        String extra = (leftLonger ? left : right)[common];
        int sign = leftLonger ? 1 : -1;
        return isNumeric(extra) ? sign : -sign;
    }

    private static int compareToken(String x, String y) {
        if (isNumeric(x) && isNumeric(y)) {
            // BigInteger rather than parseInt: a build number can outgrow an int and the
            // comparison should still be right, not wrap around.
            return new BigInteger(x).compareTo(new BigInteger(y));
        }
        return x.compareTo(y);
    }

    private static String[] tokens(String version) {
        String v = version.strip();
        if (v.length() > 1 && (v.charAt(0) == 'v' || v.charAt(0) == 'V') && Character.isDigit(v.charAt(1))) {
            v = v.substring(1);
        }
        return Arrays.stream(SEPARATOR.split(v))
                .filter(token -> !token.isEmpty())
                .toArray(String[]::new);
    }

    private static boolean isNumeric(String token) {
        return !token.isEmpty() && token.chars().allMatch(Character::isDigit);
    }
}
