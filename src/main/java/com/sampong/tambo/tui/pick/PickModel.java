package com.sampong.tambo.tui.pick;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo.tui.components.VersionRows;
import com.sampong.tambo.tui.features.Fuzzy;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import lombok.NonNull;

/**
 * The state of one {@code pick} session: which versions of one tool exist, the query the user has
 * typed, and the cursor. A pure model with no rendering or process work, so the filtering, ordering
 * and key mapping can be tested without a terminal.
 * <p>
 * The row list is {@link VersionRows}' list, so {@code pick} orders versions exactly as the full
 * TUI's Versions column does: installed first with the active one leading, then what is available
 * newest first. An empty query shows that list as-is; a non-empty query is fuzzy-ranked instead,
 * because once the user is typing they want the best match, not the installed group.
 * <p>
 * The cursor is kept on a visible row: typing or deleting puts it back on the best match, and
 * moving past either end stops at the edge rather than wrapping, the way fzf and Television do.
 */
public final class PickModel {

    /** What Enter, Ctrl+G and Alt+I each ask for. */
    public enum Action { USE_HERE, USE_GLOBAL, INSTALL_ONLY }

    /**
     * One selectable version.
     *
     * @param version   the version a command is given, exactly as the backend reports it
     * @param installed whether it is already on disk
     * @param active    whether it is the version in effect for the current directory
     * @param global    whether the user-wide config is what pins it
     * @param latest    whether it is the newest version the backend's outdated check reported
     */
    public record Row(String version, boolean installed, boolean active, boolean global, boolean latest) {

        /**
         * The dim word after the version: what the user most needs to know about it. Joined with
         * a middle dot so a row can say "installed · current · latest" without a legend.
         */
        public String note() {
            List<String> parts = new ArrayList<>();
            if (installed) {
                parts.add("installed");
            }
            if (active) {
                parts.add("current");
            } else if (global) {
                parts.add("global");
            }
            if (latest) {
                parts.add("latest");
            }
            return String.join(" · ", parts);
        }
    }

    private final String backendName;
    private final String tool;
    private final List<SdkVersion> sdks;
    private final List<Row> all;
    private final @Nullable String latest;
    private final StringBuilder query = new StringBuilder();
    private int cursor;
    private int firstVisible;

    /**
     * @param backendName the backend's name, shown in the "will run" line ({@code mise})
     * @param tool        the tool being picked a version of ({@code node})
     * @param sdks        the backend's installed/configured SDK list; only entries named {@code tool} are used
     * @param releases    the versions the backend can install, newest first
     * @param latest      the newest version the outdated check reported for {@code tool}, or null
     */
    public PickModel(@NonNull String backendName, @NonNull String tool, @NonNull List<SdkVersion> sdks,
                     @NonNull List<SdkRelease> releases, @Nullable String latest) {
        this.backendName = backendName;
        this.tool = tool;
        this.sdks = sdks.stream().filter(sdk -> sdk.name().equals(tool)).toList();
        this.latest = latest;
        this.all = VersionRows.build(tool, this.sdks, releases, latest).stream()
                .filter(row -> row.kind() == VersionRows.Kind.VERSION)
                .map(row -> new Row(row.version(), row.installed(), row.active(),
                        "global".equals(row.tag()), row.version().equals(latest)))
                .toList();
    }

    // ==================== Queries ====================

    public String backendName() {
        return backendName;
    }

    public String tool() {
        return tool;
    }

    public String query() {
        return query.toString();
    }

    /** The newest version the outdated check reported, or null when there is none. */
    public @Nullable String latest() {
        return latest;
    }

    /** The rows the query leaves, in display order. Empty query: the full list as built. */
    public List<Row> visible() {
        String q = query.toString();
        if (q.isBlank()) {
            return all;
        }
        return Fuzzy.filter(q, all, Row::version, null);
    }

    /** The row under the cursor, or null when the query leaves nothing. */
    public @Nullable Row selected() {
        List<Row> rows = visible();
        return rows.isEmpty() ? null : rows.get(clamp(cursor, rows.size()));
    }

    /** {@code "6 / 412"}: how many rows the query leaves, out of every version the tool has. */
    public String counter() {
        return visible().size() + " / " + all.size();
    }

    /**
     * The config file that pins {@code row}, when one does, for the detail pane. Null when the
     * version is merely installed or not installed at all.
     */
    public @Nullable String pinnedPath(Row row) {
        return sdks.stream()
                .filter(sdk -> sdk.hasVersion() && sdk.version().equals(row.version()) && sdk.pinned())
                .map(SdkVersion::sourcePath)
                .findFirst()
                .orElse(null);
    }

    /**
     * The first row to draw so the cursor stays inside a window of {@code rows} lines. Stored and
     * moved only as far as needed, so the list scrolls one line at a time instead of re-centring
     * on every keypress.
     */
    public int firstVisible(int rows) {
        int size = visible().size();
        int cursorNow = clamp(cursor, size);
        if (cursorNow < firstVisible) {
            firstVisible = cursorNow;
        } else if (rows > 0 && cursorNow >= firstVisible + rows) {
            firstVisible = cursorNow - rows + 1;
        }
        firstVisible = Math.clamp(firstVisible, 0, Math.max(0, size - Math.max(rows, 0)));
        return firstVisible;
    }

    // ==================== Edits ====================

    /** Appends a typed character and puts the cursor back on the best match. */
    public void type(char c) {
        query.append(c);
        cursor = 0;
    }

    /** Removes the last typed character, if any, and puts the cursor back on the best match. */
    public void backspace() {
        if (!query.isEmpty()) {
            query.setLength(query.length() - 1);
        }
        cursor = 0;
    }

    /** Moves the cursor up one row, stopping at the top. */
    public void up() {
        cursor = clamp(cursor - 1, visible().size());
    }

    /** Moves the cursor down one row, stopping at the bottom. */
    public void down() {
        cursor = clamp(cursor + 1, visible().size());
    }

    /**
     * The cursor index held inside {@code [0, size)}. The cursor is clamped on read as well as on
     * move, because a query that narrows the list can leave it past the end.
     */
    private static int clamp(int index, int size) {
        return size <= 0 ? 0 : Math.clamp(index, 0, size - 1);
    }

    // ==================== Keys ====================

    /**
     * The action a keypress asks for, or null when it is not one of the three pick actions.
     * Enter is "use here", which is what the mockup's footer leads with.
     */
    public static @Nullable Action actionFor(KeyEvent key) {
        if (key.code() == KeyCode.ENTER) {
            return Action.USE_HERE;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('g')) {
            return Action.USE_GLOBAL;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('i')) {
            return Action.INSTALL_ONLY;
        }
        return null;
    }
}
