package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.fill;
import static dev.tamboui.toolkit.Toolkit.length;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import dev.tamboui.layout.Constraint;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The panel stack down the left-hand side, and the rules that govern it: which panels exist for
 * the active backend, the number key each answers to, what {@code Tab} cycles through, and how
 * tall each one is.
 * <p>
 * This is lazygit's shape — a column of small, always-visible, numbered panels beside one large
 * pane showing whatever the selected row is — and it replaces a tab bar that showed one surface
 * at a time. Tabs hid four of the five panels to give the fifth the full width, which is the
 * opposite of the trade this app wants: the whole point of the stack is that an install running
 * in Tools stays visible while the user reads Tasks.
 * <p>
 * There is no selection state here. The focused panel <em>is</em> the selected one, read from
 * {@link UiContext#focusedId()}, so the two can never disagree — which they could while a tab
 * index and a focus id were tracked separately.
 * <p>
 * A panel the backend cannot serve is not removed: it stays in the stack, collapsed to its title
 * bar and marked {@code n/a}, so the number keys mean the same thing under both backends and a
 * missing feature reads as "vfox has no task runner" rather than as a panel that failed to load.
 * Selecting one shows the backend's own explanation in the main pane.
 */
@RequiredArgsConstructor
public final class SidePanels {

    /** Rows a collapsed panel occupies: its two border rows, the title riding on the top one. */
    public static final int COLLAPSED_HEIGHT = 2;
    /**
     * Below this sidebar height every panel but the expanded one collapses to its title bar —
     * lazygit's accordion. Above it there is room to show a few rows of each, which is the more
     * useful default: the point of the stack is peripheral vision.
     */
    private static final int ACCORDION_HEIGHT = 30;
    /** How much more of the sidebar the expanded panel gets than its neighbours. */
    private static final int EXPANDED_WEIGHT = 3;
    private static final int RESTING_WEIGHT = 1;

    /**
     * The panels in the sidebar, top to bottom — which is also the order of the number keys. The
     * command log is absent because it sits under the main pane rather than in this stack (see
     * {@link #cycle}); it keeps key {@code 5} all the same.
     */
    public enum Side {
        STATUS("Status", PanelIds.STATUS, null, '1'),
        TOOLS("Tools", PanelIds.TOOLS, null, '2'),
        ENV("Env", PanelIds.ENV, BackendFeature.ENV, '3'),
        TASKS("Tasks", PanelIds.TASKS, BackendFeature.TASKS, '4'),
        ADVANCED("Advanced", PanelIds.ADVANCED, null, '6');

        private final String title;
        private final String panelId;
        /** The feature this panel needs, or null when every backend can serve it. */
        private final @Nullable BackendFeature feature;
        private final char key;

        Side(String title, String panelId, @Nullable BackendFeature feature, char key) {
            this.title = title;
            this.panelId = panelId;
            this.feature = feature;
            this.key = key;
        }

        public String title() {
            return title;
        }

        public String panelId() {
            return panelId;
        }

        public @Nullable BackendFeature feature() {
            return feature;
        }

        /** The number key that jumps to this panel. */
        public char key() {
            return key;
        }
    }

    @NonNull
    private final UiContext ctx;
    /**
     * Rows the Status panel's content currently needs. Status is the one panel with a fixed,
     * short body, so it is sized to its content instead of taking a share of the sidebar — a
     * weight would stretch six rows of text over a third of the screen.
     */
    @NonNull
    private final IntSupplier statusRows;

    /** The last panel focus genuinely resolved to — what the main pane keeps showing. */
    private Side lastSide = Side.TOOLS;

    /** The panels on offer: everything, minus Advanced until {@code V} turns it on. */
    public List<Side> visible() {
        List<Side> sides = new ArrayList<>();
        for (Side side : Side.values()) {
            if (side != Side.ADVANCED || ctx.state().advancedFeatures()) {
                sides.add(side);
            }
        }
        return sides;
    }

    /** True when the active backend can actually serve {@code side}. */
    public boolean available(Side side) {
        return side.feature() == null || ctx.supports(side.feature());
    }

    /** The backend's own sentence on why it cannot serve {@code side}. */
    public String unsupportedReason(Side side) {
        BackendFeature feature = side.feature();
        return feature == null
                ? ctx.backend().name() + " does not support this."
                : ctx.backend().unsupportedReason(feature);
    }

    /**
     * The side panel the main pane is following. Normally the focused one — but focus also
     * lands on things that are not side panels at all (the command log, a filter box, a modal's
     * input), and blanking the main pane every time it did would defeat the point of the shape:
     * you read the detail of a tool <em>while</em> scrolling the log of its install. So anything
     * off the stack leaves this on whatever panel it last resolved to.
     */
    public Side focused() {
        String id = ctx.focusedId();
        Side side = id == null ? null : switch (id) {
            case PanelIds.STATUS -> Side.STATUS;
            case PanelIds.TOOLS, PanelIds.TOOLS_FILTER -> Side.TOOLS;
            case PanelIds.ENV, PanelIds.ENV_FILTER -> Side.ENV;
            case PanelIds.TASKS, PanelIds.TASKS_FILTER -> Side.TASKS;
            case PanelIds.ADVANCED -> Side.ADVANCED;
            default -> null;
        };
        if (side != null) {
            lastSide = side;
        }
        return lastSide;
    }

    /** True when {@code side} is the panel actually holding focus, not merely the one being shown. */
    public boolean isFocused(Side side) {
        return side.panelId().equals(ctx.focusedId());
    }

    /** True while the command log under the main pane holds focus rather than any side panel. */
    public boolean logFocused() {
        return PanelIds.LOG.equals(ctx.focusedId());
    }

    public void focus(Side side) {
        ctx.focus(side.panelId());
    }

    /**
     * Moves focus one panel along, wrapping. The ring is the visible sidebar panels plus the
     * command log, so {@code Tab} eventually reaches everything focusable — and it skips the
     * panels this backend cannot serve, which have nothing to focus.
     */
    public void cycle(int delta) {
        List<String> ring = new ArrayList<>();
        for (Side side : visible()) {
            if (available(side)) {
                ring.add(side.panelId());
            }
        }
        ring.add(PanelIds.LOG);
        String current = ctx.focusedId();
        int at = current == null ? -1 : ring.indexOf(current);
        if (at < 0) {
            // Focus is on a filter box, or on nothing yet — resume from the owning panel.
            at = Math.max(0, ring.indexOf(focused().panelId()));
        }
        ctx.focus(ring.get(Math.floorMod(at + delta, ring.size())));
    }

    /**
     * The panel's border title: {@code [2] Tools (12)}, or {@code [3] Env — n/a} when the
     * backend cannot serve it. The number is part of the title so the stack doubles as the
     * shortcut legend, which is what lazygit's panel jumps look like.
     */
    public static String title(Side side, String detail) {
        return " [" + side.key() + "] " + side.title() + (detail.isEmpty() ? "" : " " + detail) + " ";
    }

    /** The title of a panel this backend cannot serve — the stack keeps its slot and its number. */
    public static String unavailableTitle(Side side) {
        return " [" + side.key() + "] " + side.title() + " — n/a ";
    }

    /**
     * The scroll/selection indicator lazygit and ratatui both hang off the bottom border of the
     * focused block: {@code 3 of 12}. Empty for an empty or single-entry panel, where a counter
     * would only be noise.
     */
    public static String positionLabel(int index, int size) {
        return size <= 1 ? "" : " " + (index + 1) + " of " + size + " ";
    }

    /**
     * How tall {@code side} renders in a sidebar of {@code sidebarHeight} rows. Everything the
     * backend cannot serve, and — on a short terminal — everything except the expanded panel,
     * collapses to its title bar; the rest share what is left, with the expanded panel taking
     * the larger share.
     */
    public Constraint constraint(Side side, int sidebarHeight) {
        if (!available(side)) {
            return length(COLLAPSED_HEIGHT);
        }
        boolean expanded = side == expanded();
        if (side == Side.STATUS) {
            // Status is worth six rows on a terminal with room to spare and not on one without:
            // it is the panel you read once and then stop looking at, so on a short terminal it
            // collapses like the rest and gives those rows to whatever is being worked in.
            return sidebarHeight < ACCORDION_HEIGHT && !isFocused(side)
                    ? length(COLLAPSED_HEIGHT)
                    : length(statusRows.getAsInt() + COLLAPSED_HEIGHT);
        }
        if (sidebarHeight < ACCORDION_HEIGHT) {
            return expanded ? fill(1) : length(COLLAPSED_HEIGHT);
        }
        return fill(expanded ? EXPANDED_WEIGHT : RESTING_WEIGHT);
    }

    /**
     * The panel that soaks up whatever room the others do not need. Normally the focused one —
     * but Status is sized to its content and never grows, and an unavailable panel has nothing
     * to show, so in both cases the room goes to Tools, the list this app is mostly about.
     */
    private Side expanded() {
        Side side = focused();
        if (side == Side.STATUS || !available(side)) {
            return Side.TOOLS;
        }
        return side;
    }
}
