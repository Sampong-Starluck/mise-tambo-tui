package com.sampong.tambo.tui.components;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import com.sampong.tambo.tui.commands.Command;
import com.sampong.tambo.tui.commands.CommandMatcher;
import com.sampong.tambo.tui.features.BackendActions;
import com.sampong.tambo.tui.features.Theme;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;
import com.sampong.tambo.tui.state.UiState;

class CommandPaletteTest {

    /** Records focus changes; the palette only ever touches focus and the context's theme. */
    private static final class StubContext implements UiContext {
        private String focused;

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
    private final List<String> commandLines = new ArrayList<>();
    private final CommandPalette palette = new CommandPalette(ctx, commandLines::add);
    private final List<String> ran = new ArrayList<>();

    private Command command(String id, String title) {
        return new Command(id, title, Command.Group.UPDATES, "U", null, false, () -> ran.add(id));
    }

    private List<Command> commands() {
        return List.of(
                command("tools.install", "Install node 22"),
                command("tools.upgrade", "Upgrade all outdated (3)"),
                command("app.doctor", "Run doctor"));
    }

    private static KeyEvent down() {
        return KeyEvent.ofKey(KeyCode.DOWN);
    }

    private static KeyEvent up() {
        return KeyEvent.ofKey(KeyCode.UP);
    }

    private static KeyEvent enter() {
        return KeyEvent.ofKey(KeyCode.ENTER);
    }

    @Test
    void openFocusesTheInputAndRemembersThePreviousFocus() {
        ctx.focus("panel-tools");

        palette.open(commands());

        assertThat(palette.isOpen()).isTrue();
        assertThat(ctx.focusedId()).isEqualTo(PanelIds.PALETTE_INPUT);
        assertThat(palette.query()).isEmpty();
        assertThat(palette.selected().id()).isEqualTo("tools.install");
    }

    @Test
    void typingNarrowsTheListAndSelectsTheBestMatch() {
        palette.open(commands());

        palette.type("upg");

        assertThat(palette.query()).isEqualTo("upg");
        assertThat(palette.selected().id()).isEqualTo("tools.upgrade");
    }

    @Test
    void arrowKeysMoveAndClampAtBothEnds() {
        palette.open(commands());

        palette.handleKey(up());
        assertThat(palette.selected().id()).as("cannot go above the first row").isEqualTo("tools.install");

        palette.handleKey(down());
        palette.handleKey(down());
        palette.handleKey(down());
        palette.handleKey(down());
        assertThat(palette.selected().id()).as("clamped at the last row").isEqualTo("app.doctor");
    }

    @Test
    void changingTheQueryResetsTheSelectionToTheTop() {
        palette.open(commands());
        palette.handleKey(down());
        assertThat(palette.selected().id()).isNotEqualTo(CommandMatcher.rank("o", commands()).get(0).command().id());

        palette.type("o");

        assertThat(palette.selected().id())
                .isEqualTo(CommandMatcher.rank("o", commands()).get(0).command().id());
    }

    @Test
    void enterClosesThenRunsTheSelectedCommand() {
        ctx.focus("panel-tools");
        palette.open(commands());
        palette.type("doc");

        palette.handleKey(enter());

        assertThat(ran).containsExactly("app.doctor");
        assertThat(palette.isOpen()).isFalse();
        assertThat(ctx.focusedId()).isEqualTo("panel-tools");
    }

    @Test
    void enterWithNoMatchStaysOpenAndRunsNothing() {
        palette.open(commands());
        palette.type("zzzz");

        palette.handleKey(enter());

        assertThat(ran).isEmpty();
        assertThat(palette.isOpen()).isTrue();
        assertThat(palette.selected()).isNull();
    }

    @Test
    void escapeClosesWithoutRunning() {
        ctx.focus("panel-tools");
        palette.open(commands());

        palette.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertThat(ran).isEmpty();
        assertThat(palette.isOpen()).isFalse();
        assertThat(ctx.focusedId()).isEqualTo("panel-tools");
    }

    @Test
    void colonOnAnEmptyQueryClosesAndOpensTheCommandLine() {
        palette.open(commands());

        palette.handleKey(KeyEvent.ofChar(':'));

        assertThat(palette.isOpen()).isFalse();
        assertThat(commandLines).containsExactly("");
        assertThat(ran).isEmpty();
    }

    @Test
    void colonPrefixedQueryHandsTheRestToTheCommandLine() {
        palette.open(commands());

        palette.type(":tools upgrade");

        assertThat(palette.isOpen()).isFalse();
        assertThat(commandLines).containsExactly("tools upgrade");
    }

    @Test
    void colonInsideAQueryDoesNotRoute() {
        palette.open(commands());

        palette.type("up:x");

        assertThat(palette.isOpen()).isTrue();
        assertThat(commandLines).isEmpty();
    }

    @Test
    void buildRendersWithAndWithoutMatches() {
        palette.open(commands());
        assertThat(palette.build()).isNotNull();

        palette.type("zzzz");
        assertThat(palette.build()).isNotNull();
        assertThat(palette.footerHint()).isEqualTo("↑↓ move  Enter run  Esc close");
    }
}
