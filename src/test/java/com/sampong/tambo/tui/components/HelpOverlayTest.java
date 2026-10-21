package com.sampong.tambo.tui.components;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sampong.tambo.tui.components.HelpOverlay.Entry;
import com.sampong.tambo.tui.components.HelpOverlay.Topic;
import com.sampong.tambo.tui.features.BackendActions;
import com.sampong.tambo.tui.features.Theme;
import com.sampong.tambo.tui.state.UiContext;
import com.sampong.tambo.tui.state.UiState;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

class HelpOverlayTest {

    private final Ctx ctx = new Ctx();
    private final HelpOverlay overlay = new HelpOverlay(ctx, () -> 40, () -> 100);

    /** A small reference shaped like the real one: headings, key rows, a flag and a note. */
    private static List<Entry> sampleDoc() {
        return List.of(
                Entry.heading("IN THE TOOLS PANEL"),
                Entry.keyRow("i", "Install the selected tool"),
                Entry.keyRow("Esc", "Clear the marks and the filter"),
                Entry.heading("ANYWHERE"),
                Entry.keyRow("1-5", "Jump to a panel"),
                Entry.keyRow("Tab / Shift+Tab", "Next / previous panel"),
                Entry.note("Jump prose that is not a key row"),
                Entry.heading("LAUNCH FLAGS"),
                Entry.flagRow("--offline", "Installed tools only")
        );
    }

    @Test
    void searchKeepsMatchingRowsAndDropsEmptySections() {
        List<Entry> kept = HelpOverlay.matching(sampleDoc(), "jump");

        assertThat(kept).containsExactly(
                Entry.heading("ANYWHERE"),
                Entry.keyRow("1-5", "Jump to a panel")
        );
    }

    @Test
    void searchIsCaseInsensitiveAndMatchesTheKeyAsWellAsTheDescription() {
        assertThat(HelpOverlay.matching(sampleDoc(), "SHIFT"))
                .containsExactly(Entry.heading("ANYWHERE"), Entry.keyRow("Tab / Shift+Tab", "Next / previous panel"));

        assertThat(HelpOverlay.matching(sampleDoc(), "esc"))
                .containsExactly(Entry.heading("IN THE TOOLS PANEL"), Entry.keyRow("Esc", "Clear the marks and the filter"));
    }

    @Test
    void searchDropsProseAndKeepsFlagsThatMatch() {
        assertThat(HelpOverlay.matching(sampleDoc(), "offline"))
                .containsExactly(Entry.heading("LAUNCH FLAGS"), Entry.flagRow("--offline", "Installed tools only"));
    }

    @Test
    void searchWithNoMatchesLeavesNothing() {
        assertThat(HelpOverlay.matching(sampleDoc(), "zzz")).isEmpty();
    }

    @Test
    void blankQueryKeepsTheWholeDocument() {
        assertThat(HelpOverlay.matching(sampleDoc(), "")).isEqualTo(sampleDoc());
        assertThat(HelpOverlay.matching(sampleDoc(), "   ")).isEqualTo(sampleDoc());
    }

    @Test
    void openOnAPanelStartsOnThatPanel() {
        overlay.open(Topic.LOG);

        assertThat(overlay.isOpen()).isTrue();
        assertThat(overlay.topic()).isEqualTo(Topic.LOG);
        assertThat(ctx.focusCleared).isTrue();
    }

    @Test
    void plainOpenShowsTools() {
        overlay.open();

        assertThat(overlay.topic()).isEqualTo(Topic.TOOLS);
    }

    @Test
    void tabCyclesTopicsForwardAndWraps() {
        overlay.open(Topic.TOOLS);

        overlay.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        assertThat(overlay.topic()).isEqualTo(Topic.VERSIONS);

        overlay.open(Topic.JOBS);
        overlay.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        assertThat(overlay.topic()).as("wraps after the last topic").isEqualTo(Topic.STATUS);
    }

    @Test
    void shiftTabCyclesTopicsBackwardAndWraps() {
        overlay.open(Topic.TOOLS);

        overlay.handleKey(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT));
        assertThat(overlay.topic()).isEqualTo(Topic.STATUS);

        overlay.handleKey(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT));
        assertThat(overlay.topic()).as("wraps before the first topic").isEqualTo(Topic.JOBS);
    }

    @Test
    void slashSearchTypingDoesNotCloseOnQ() {
        overlay.open(Topic.TOOLS);

        overlay.handleKey(KeyEvent.ofChar('/'));
        overlay.handleKey(KeyEvent.ofChar('q'));

        assertThat(overlay.isOpen()).as("q is part of the query while searching").isTrue();
    }

    @Test
    void escClearsTheSearchBeforeItClosesTheOverlay() {
        overlay.open(Topic.TOOLS);
        overlay.handleKey(KeyEvent.ofChar('/'));
        overlay.handleKey(KeyEvent.ofChar('a'));

        overlay.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertThat(overlay.isOpen()).as("first esc clears the search").isTrue();

        overlay.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertThat(overlay.isOpen()).as("second esc closes").isFalse();
        assertThat(ctx.focused).as("focus restored on close").isEqualTo("tools");
    }

    @Test
    void f1ClosesTheOverlay() {
        overlay.open(Topic.ENV);

        overlay.handleKey(KeyEvent.ofKey(KeyCode.F1));

        assertThat(overlay.isOpen()).isFalse();
    }

    /** Only the focus calls matter here; everything else is unreachable from the overlay's key handling. */
    private static final class Ctx implements UiContext {

        String focused = "tools";
        boolean focusCleared;

        @Override
        public @org.jspecify.annotations.Nullable String focusedId() {
            return focused;
        }

        @Override
        public void focus(String id) {
            focused = id;
        }

        @Override
        public void clearFocus() {
            focused = null;
            focusCleared = true;
        }

        @Override
        public UiState state() {
            throw new UnsupportedOperationException();
        }

        @Override
        public BackendActions actions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Theme theme() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String uiBackend() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean noModalOpen() {
            return true;
        }

        @Override
        public void confirm(String message, Runnable onConfirm) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void promptTaskArgs(String taskName, String initialArgs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void promptVersionFor(String tool) {
            throw new UnsupportedOperationException();
        }
    }
}
