package com.sampong.tambo.tui.components;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import com.sampong.tambo.tui.features.BackendActions;
import com.sampong.tambo.tui.features.Theme;
import com.sampong.tambo.tui.state.Job;
import com.sampong.tambo.tui.state.JobTracker;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;
import com.sampong.tambo.tui.state.UiState;

import org.jspecify.annotations.Nullable;

class JobsViewTest {

    /** Records focus moves and the cancel requests the view makes; everything else is unused here. */
    private static final class StubContext implements UiContext {

        private final UiState state = new UiState();
        private @Nullable String focused = PanelIds.TOOLS;
        private final List<String> focusCalls = new ArrayList<>();

        @Override
        public UiState state() {
            return state;
        }

        @Override
        public BackendActions actions() {
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
        public @Nullable String focusedId() {
            return focused;
        }

        @Override
        public void focus(String id) {
            focused = id;
            focusCalls.add(id);
        }

        @Override
        public void clearFocus() {
            focused = null;
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

    private final StubContext ctx = new StubContext();
    private final JobTracker jobs = new JobTracker(() -> 1_000_000_000_000L);
    private final List<Job> cancelRequests = new ArrayList<>();
    private final JobsView view = new JobsView(ctx, jobs, cancelRequests::add, List::of);

    private static KeyEvent ch(char c) {
        return KeyEvent.ofChar(c);
    }

    private static KeyEvent key(KeyCode code) {
        return KeyEvent.ofKey(code);
    }

    @Test
    void openClearsFocusAndCloseRestoresIt() {
        view.open();
        assertThat(view.isOpen()).isTrue();
        assertThat(ctx.focusedId()).isNull();

        assertThat(view.handleKey(key(KeyCode.ESCAPE))).isTrue();

        assertThat(view.isOpen()).isFalse();
        assertThat(ctx.focusedId()).isEqualTo(PanelIds.TOOLS);
    }

    @Test
    void wAlsoClosesTheView() {
        view.open();

        assertThat(view.handleKey(ch('w'))).isTrue();
        assertThat(view.isOpen()).isFalse();
    }

    @Test
    void cCancelsTheSelectedActiveJob() {
        Job queued = jobs.submit("install node", "$ mise install node", "node", null);
        view.open();

        assertThat(view.handleKey(ch('c'))).isTrue();
        assertThat(cancelRequests).containsExactly(queued);
    }

    @Test
    void cOnAFinishedJobIsConsumedButCancelsNothing() {
        Job done = jobs.submit("install node", "$ mise install node", "node", null);
        jobs.finish(done.id(), true, null, null);
        view.open();

        assertThat(view.handleKey(ch('c'))).isTrue();
        assertThat(cancelRequests).isEmpty();
    }

    @Test
    void arrowsMoveTheSelectionSoTheCursorFollowsTheJob() {
        Job running = jobs.submit("running", "$ a", "a", null);
        jobs.start(running.id());
        Job queued = jobs.submit("queued", "$ b", "b", null);
        view.open();

        // The running job sorts first, so the cursor starts there; Down moves to the queued one.
        view.handleKey(key(KeyCode.DOWN));
        view.handleKey(ch('c'));

        assertThat(cancelRequests).containsExactly(queued);
    }

    @Test
    void rRetriesAFailedJobByDismissingItThenRunningTheRetry() {
        AtomicInteger retried = new AtomicInteger();
        Job failed = jobs.submit("install node", "$ mise install node", "node", retried::incrementAndGet);
        jobs.finish(failed.id(), false, null, "download failed");
        view.open();

        assertThat(view.handleKey(ch('r'))).isTrue();

        assertThat(retried.get()).isEqualTo(1);
        assertThat(jobs.get(failed.id())).isNull();
    }

    @Test
    void rWithoutAFailedJobWithRetryFallsThroughToTheGlobalRefresh() {
        Job failedNoRetry = jobs.submit("install go", "$ mise install go", "go", null);
        jobs.finish(failedNoRetry.id(), false, null, "boom");
        view.open();

        assertThat(view.handleKey(ch('r'))).isFalse();
        assertThat(jobs.get(failedNoRetry.id())).isSameAs(failedNoRetry);
    }

    @Test
    void xDismissesAFinishedJobButNotAnActiveOne() {
        Job active = jobs.submit("a", "$ a", "a", null);
        jobs.start(active.id());
        Job done = jobs.submit("b", "$ b", "b", null);
        jobs.finish(done.id(), true, null, null);
        view.open();

        // The running job is selected first; x must leave it alone.
        view.handleKey(ch('x'));
        assertThat(jobs.get(active.id())).as("the running job stays").isSameAs(active);
        assertThat(jobs.get(done.id())).isSameAs(done);

        view.handleKey(key(KeyCode.DOWN));
        view.handleKey(ch('x'));
        assertThat(jobs.get(done.id())).isNull();
    }

    @Test
    void lClosesTheViewAndFocusesTheLog() {
        jobs.submit("install node", "$ mise install node", "node", null);
        view.open();

        assertThat(view.handleKey(ch('l'))).isTrue();

        assertThat(view.isOpen()).isFalse();
        assertThat(ctx.focusedId()).isEqualTo(PanelIds.LOG);
    }

    @Test
    void globalKeysPassThroughUnhandled() {
        view.open();

        assertThat(view.handleKey(ch('z'))).isFalse();
        assertThat(view.handleKey(ch('Z'))).isFalse();
        assertThat(view.handleKey(ch('q'))).isFalse();
        assertThat(view.isOpen()).isTrue();
    }

    @Test
    void buildRendersEmptyAndPopulatedStatesWithoutError() {
        view.open();
        assertThat(view.build()).isNotNull();

        Job running = jobs.submit("install node", "$ mise install node", "node", null);
        jobs.start(running.id());
        jobs.output(running.id(), "ERROR something odd");
        Job failed = jobs.submit("install go", "$ mise install go", "go", () -> {
        });
        jobs.finish(failed.id(), false, null, "download failed");
        jobs.submit("install python", "$ mise install python", "python", null);

        assertThat(view.build()).isNotNull();
    }

    @Test
    void footerHintNamesTheViewsKeys() {
        assertThat(view.footerHint()).isEqualTo("JOBS  r retry  c cancel job  l log  z undo last change  Esc back");
    }

    @Test
    void headerSummaryOmitsZeroCountsAndIsEmptyWhenIdle() {
        assertThat(JobsView.headerSummary(jobs)).isEmpty();

        jobs.submit("a", "$ a", "a", null);
        jobs.submit("b", "$ b", "b", null);
        assertThat(JobsView.headerSummary(jobs)).isEqualTo("2 queued");

        Job running1 = jobs.submit("c", "$ c", "c", null);
        Job running2 = jobs.submit("d", "$ d", "d", null);
        jobs.start(running1.id());
        jobs.start(running2.id());
        Job failed = jobs.submit("e", "$ e", "e", null);
        jobs.finish(failed.id(), false, null, "x");

        // The spinner glyph leads and changes frame to frame; the counts are what must hold.
        assertThat(JobsView.headerSummary(jobs)).endsWith("2 running · 2 queued · ✗ 1 failed");
    }
}
