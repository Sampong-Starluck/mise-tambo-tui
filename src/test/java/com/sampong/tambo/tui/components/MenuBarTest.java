package com.sampong.tambo.tui.components;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.sampong.tambo.tui.commands.Command;
import com.sampong.tambo.tui.components.MenuBar.Menu;
import com.sampong.tambo.tui.features.BackendActions;
import com.sampong.tambo.tui.features.Theme;
import com.sampong.tambo.tui.state.UiContext;
import com.sampong.tambo.tui.state.UiState;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

class MenuBarTest {

    private final Ctx ctx = new Ctx();
    private final MenuBar bar = new MenuBar(ctx);

    private static Command command(String id, Command.Group group, Runnable action) {
        return new Command(id, id, group, "", null, false, action);
    }

    @Test
    void groupsMapToTheMenuNamedAfterThem() {
        assertThat(MenuBar.menuFor(Command.Group.TOOLS)).isEqualTo(Menu.TOOLS);
        assertThat(MenuBar.menuFor(Command.Group.UPDATES)).isEqualTo(Menu.TOOLS);
        assertThat(MenuBar.menuFor(Command.Group.PROJECT)).isEqualTo(Menu.PROJECT);
        assertThat(MenuBar.menuFor(Command.Group.CLEANUP)).isEqualTo(Menu.FILE);
        assertThat(MenuBar.menuFor(Command.Group.VIEW)).isEqualTo(Menu.VIEW);
        assertThat(MenuBar.menuFor(Command.Group.APP)).isEqualTo(Menu.FILE);
    }

    @Test
    void openingTakesFocusAndStartsOnTools() {
        ctx.focused = "tools";

        bar.open(List.of());

        assertThat(bar.isOpen()).isTrue();
        assertThat(ctx.focused).isNull();
        assertThat(bar.dropdownColumn()).as("Tools sits after File").isEqualTo("File".length() + 2);
    }

    @Test
    void leftAndRightWrapAroundTheBar() {
        bar.open(List.of());

        bar.handleKey(KeyEvent.ofKey(KeyCode.LEFT));
        assertThat(bar.dropdownColumn()).as("File is first").isZero();

        bar.handleKey(KeyEvent.ofKey(KeyCode.LEFT));
        assertThat(bar.dropdownColumn()).as("left from File wraps to Help, the last menu").isPositive();

        bar.handleKey(KeyEvent.ofKey(KeyCode.RIGHT));
        assertThat(bar.dropdownColumn()).as("right from Help wraps to File").isZero();
    }

    @Test
    void enterRunsTheSelectedCommandAndCloses() {
        AtomicBoolean first = new AtomicBoolean();
        AtomicBoolean second = new AtomicBoolean();
        bar.open(List.of(
                command("tools.install", Command.Group.TOOLS, () -> first.set(true)),
                command("tools.upgrade", Command.Group.UPDATES, () -> second.set(true))
        ));

        bar.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
        bar.handleKey(KeyEvent.ofKey(KeyCode.ENTER));

        assertThat(second).as("the second item ran").isTrue();
        assertThat(first).as("the first did not").isFalse();
        assertThat(bar.isOpen()).isFalse();
        assertThat(ctx.focused).as("focus restored").isNull();
    }

    @Test
    void downWrapsAndOnlyCommandsOfTheOpenMenuAreRun() {
        AtomicInteger ran = new AtomicInteger();
        List<Command> commands = List.of(
                command("tools.install", Command.Group.TOOLS, () -> ran.addAndGet(1)),
                command("file.prune", Command.Group.CLEANUP, () -> ran.addAndGet(100))
        );

        bar.open(commands);
        bar.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
        bar.handleKey(KeyEvent.ofKey(KeyCode.ENTER));
        assertThat(ran.get()).as("down from the only Tools item wraps back to it").isEqualTo(1);

        bar.open(commands);
        bar.handleKey(KeyEvent.ofKey(KeyCode.LEFT));
        bar.handleKey(KeyEvent.ofKey(KeyCode.ENTER));
        assertThat(ran.get()).as("File lists the clean-up command").isEqualTo(101);
    }

    @Test
    void enterOnTheHelpMenuRunsKeyboardHelp() {
        AtomicBoolean helpOpened = new AtomicBoolean();
        bar.onKeyboardHelp(() -> helpOpened.set(true));
        bar.open(List.of());

        bar.handleKey(KeyEvent.ofKey(KeyCode.LEFT));
        bar.handleKey(KeyEvent.ofKey(KeyCode.LEFT));
        bar.handleKey(KeyEvent.ofKey(KeyCode.ENTER));

        assertThat(helpOpened).isTrue();
        assertThat(bar.isOpen()).isFalse();
    }

    @Test
    void escClosesWithoutRunning() {
        AtomicBoolean ran = new AtomicBoolean();
        bar.open(List.of(command("tools.install", Command.Group.TOOLS, () -> ran.set(true))));

        bar.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertThat(bar.isOpen()).isFalse();
        assertThat(ran).isFalse();
    }

    @Test
    void f9ClosesToo() {
        bar.open(List.of());

        bar.handleKey(KeyEvent.ofKey(KeyCode.F9));

        assertThat(bar.isOpen()).isFalse();
    }

    @Test
    void whileOpenEveryKeyIsSwallowed() {
        bar.open(List.of());

        assertThat(bar.handleKey(KeyEvent.ofChar('x'))).isTrue();
        assertThat(bar.isOpen()).isTrue();
    }

    @Test
    void fKeyBarMapsF1ToF10ToActionNames() {
        List<KeyCode> keys = List.of(KeyCode.F1, KeyCode.F2, KeyCode.F3, KeyCode.F4, KeyCode.F5,
                KeyCode.F6, KeyCode.F7, KeyCode.F8, KeyCode.F9, KeyCode.F10);
        List<String> actions = new ArrayList<>();
        for (KeyCode code : keys) {
            actions.add(FKeyBar.actionFor(code));
        }

        assertThat(actions).containsExactly("help", "versions", "install", "use", "global",
                "upgrade", "add", "remove", "menu", "quit");
    }

    @Test
    void fKeyBarIgnoresOtherKeys() {
        assertThat(FKeyBar.actionFor(KeyCode.ENTER)).isNull();
        assertThat(FKeyBar.actionFor(KeyCode.CHAR)).isNull();
        assertThat(FKeyBar.actionFor(KeyCode.F11)).isNull();
    }

    @Test
    void fKeyBarBuildsAtNarrowAndWideWidths() {
        assertThat(FKeyBar.build(80)).isNotNull();
        assertThat(FKeyBar.build(4)).as("narrower than one cell per key").isNotNull();
    }

    /** Tracks focus so the tests can check the bar hands it back; the rest is unused by the bar. */
    private static final class Ctx implements UiContext {

        String focused;

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
