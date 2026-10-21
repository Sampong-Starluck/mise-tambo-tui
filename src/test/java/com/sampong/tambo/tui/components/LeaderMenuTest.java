package com.sampong.tambo.tui.components;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import com.sampong.tambo.tui.commands.Command;
import com.sampong.tambo.tui.features.Theme;
import com.sampong.tambo.tui.state.UiContext;

class LeaderMenuTest {

    /** Records what the menu did to focus; the rest of the context is never touched by the menu. */
    private static final class StubContext implements UiContext {
        private String focused;

        @Override
        public com.sampong.tambo.tui.state.UiState state() {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.sampong.tambo.tui.features.BackendActions actions() {
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
    private final LeaderMenu menu = new LeaderMenu(ctx);
    private final List<String> ran = new ArrayList<>();

    private Command leader(String id, char leaderKey) {
        return new Command(id, id, Command.Group.TOOLS, "", leaderKey, false, () -> ran.add(id));
    }

    private List<Command> commands() {
        return List.of(
                leader("upgrade", 'u'),
                leader("check", 'C'),
                leader("clear", 'c'),
                new Command("palette-only", "Palette only", Command.Group.APP, "Ctrl+P", null, false,
                        () -> ran.add("palette-only")));
    }

    @Test
    void leaderKeyRunsTheMatchingCommandAndCloses() {
        ctx.focus("panel-tools");
        menu.open(commands());

        menu.handleKey(KeyEvent.ofChar('u'));

        assertThat(ran).containsExactly("upgrade");
        assertThat(menu.isOpen()).isFalse();
        assertThat(ctx.focusedId()).as("focus restored to the panel that had it").isEqualTo("panel-tools");
    }

    @Test
    void leaderKeysAreCaseSensitive() {
        menu.open(commands());
        menu.handleKey(KeyEvent.ofChar('C'));
        assertThat(ran).containsExactly("check");

        menu.open(commands());
        menu.handleKey(KeyEvent.ofChar('c'));
        assertThat(ran).containsExactly("check", "clear");
    }

    @Test
    void unknownKeyIsSwallowedAndMenuStaysOpen() {
        menu.open(commands());

        boolean consumed = menu.handleKey(KeyEvent.ofChar('z'));

        assertThat(consumed).isTrue();
        assertThat(ran).isEmpty();
        assertThat(menu.isOpen()).isTrue();
    }

    @Test
    void escapeClosesWithoutRunningAnything() {
        menu.open(commands());

        menu.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertThat(ran).isEmpty();
        assertThat(menu.isOpen()).isFalse();
    }

    @Test
    void backspaceStepsOutWithoutRunningAnything() {
        menu.open(commands());

        menu.handleKey(KeyEvent.ofKey(KeyCode.BACKSPACE));

        assertThat(ran).isEmpty();
        assertThat(menu.isOpen()).isFalse();
    }

    @Test
    void commandsWithoutALeaderKeyAreIgnored() {
        menu.open(List.of(new Command("palette-only", "Palette only", Command.Group.APP, "Ctrl+P", null,
                false, () -> ran.add("palette-only"))));

        // No character can select it, so every key leaves the menu open with nothing run.
        for (char c : new char[] {'p', 'a', 'l', 'x'}) {
            menu.handleKey(KeyEvent.ofChar(c));
        }

        assertThat(ran).isEmpty();
        assertThat(menu.isOpen()).isTrue();
    }

    @Test
    void buildRendersWithoutError() {
        menu.open(commands());

        assertThat(menu.build()).isNotNull();
        assertThat(menu.footerHint()).contains("SPACE");
    }
}
