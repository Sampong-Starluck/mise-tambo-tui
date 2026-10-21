package com.sampong.tambo.tui.components;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import com.sampong.tambo._common.service.SdkVersionBackend;
import com.sampong.tambo.tui.features.BackendActions;
import com.sampong.tambo.tui.features.Theme;
import com.sampong.tambo.tui.state.UiContext;
import com.sampong.tambo.tui.state.UiState;
import com.sampong.tambo.tui.components.BatchConfirmModal.Item;

class BatchConfirmModalTest {

    private static final List<Item> BATCH = List.of(
            new Item("node", "22.11.0", "23.1.0", "project"),
            new Item("go", "1.22.0", "1.22.4", "global"));

    private final StubContext ctx = new StubContext();
    private final BatchConfirmModal modal = new BatchConfirmModal(ctx);
    private final List<String> ran = new ArrayList<>();

    private void openBatch() {
        modal.open("Upgrade 2 tools?", BATCH, "Old versions stay installed until you prune them.",
                () -> ran.add("upgraded"));
    }

    @Test
    void openClearsFocusAndCloseRestoresIt() {
        ctx.focused = "panel-tools";
        openBatch();

        assertThat(modal.isOpen()).isTrue();
        assertThat(ctx.focused).isNull();

        modal.close();
        assertThat(modal.isOpen()).isFalse();
        assertThat(ctx.focused).isEqualTo("panel-tools");
    }

    @Test
    void enterCancelsBecauseNoIsTheDefault() {
        openBatch();

        assertThat(modal.handleKey(KeyEvent.ofKey(KeyCode.ENTER))).isTrue();

        assertThat(modal.isOpen()).isFalse();
        assertThat(ran).isEmpty();
    }

    @Test
    void escCancels() {
        openBatch();

        modal.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertThat(modal.isOpen()).isFalse();
        assertThat(ran).isEmpty();
    }

    @Test
    void nCancels() {
        openBatch();

        modal.handleKey(KeyEvent.ofChar('n'));

        assertThat(modal.isOpen()).isFalse();
        assertThat(ran).isEmpty();
    }

    @Test
    void yConfirmsAndRuns() {
        openBatch();

        modal.handleKey(KeyEvent.ofChar('y'));

        assertThat(modal.isOpen()).isFalse();
        assertThat(ran).containsExactly("upgraded");
    }

    @Test
    void uppercaseYAlsoConfirms() {
        openBatch();

        modal.handleKey(KeyEvent.ofChar('Y'));

        assertThat(ran).containsExactly("upgraded");
    }

    @Test
    void otherKeysAreSwallowedAndDialogStaysOpen() {
        openBatch();

        assertThat(modal.handleKey(KeyEvent.ofChar('x'))).isTrue();
        assertThat(modal.handleKey(KeyEvent.ofKey(KeyCode.DOWN))).isTrue();

        assertThat(modal.isOpen()).isTrue();
        assertThat(ran).isEmpty();
    }

    @Test
    void buildRendersWithoutATerminal() {
        openBatch();

        assertThatNoException().isThrownBy(modal::build);
        assertThat(modal.footerHint()).contains("y confirm").contains("Enter");
    }

    @Test
    void majorChangeIsFlaggedAcrossAMajorBoundary() {
        assertThat(BatchConfirmModal.majorChange("22.11.0", "23.1.0")).isTrue();
    }

    @Test
    void minorOrPatchChangeIsNotAMajorChange() {
        assertThat(BatchConfirmModal.majorChange("1.9.8", "1.10.2")).isFalse();
        assertThat(BatchConfirmModal.majorChange("22.11.0", "22.12.0")).isFalse();
    }

    @Test
    void leadingPrefixDoesNotHideTheMajor() {
        assertThat(BatchConfirmModal.majorChange("v20.1.0", "v21.0.0")).isTrue();
    }

    @Test
    void rowsWithoutVersionsAreNeverMajorChanges() {
        assertThat(BatchConfirmModal.majorChange("", "")).isFalse();
        assertThat(BatchConfirmModal.majorChange("22.11.0", "")).isFalse();
        assertThat(BatchConfirmModal.majorChange("latest", "23.1.0")).isFalse();
    }

    /** A UiContext that records focus and nothing else; the modal under test needs no more. */
    private static final class StubContext implements UiContext {

        String focused;

        @Override
        public UiState state() {
            throw new UnsupportedOperationException();
        }

        @Override
        public BackendActions actions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SdkVersionBackend backend() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Theme theme() {
            return Theme.defaults();
        }

        @Override
        public String uiBackend() {
            return "jline3";
        }

        @Override
        public String focusedId() {
            return focused;
        }

        @Override
        public void focus(String id) {
            focused = id;
        }

        @Override
        public void clearFocus() {
            focused = null;
        }

        @Override
        public boolean noModalOpen() {
            return focused == null;
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
