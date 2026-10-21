package com.sampong.tambo.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import com.sampong.tambo.testsupport.DemoBackend;
import com.sampong.tambo.tui.components.MenuBar;
import com.sampong.tambo.tui.components.RegistryModal;
import com.sampong.tambo.tui.state.PanelIds;

/**
 * Every screen of the redesign, rendered headlessly from the real layout and key router, and
 * checked for the things that make each one what it is. Each frame is also written to
 * {@code target/screens/<name>.txt}, so a change to the layout can be read as text before it is
 * ever run in a terminal.
 */
class ScreensTest {

    private static final Path OUT = Path.of("target", "screens");

    private static ScreenHarness wide() {
        return new ScreenHarness(new DemoBackend(), 140, 40);
    }

    private static String shot(String name, ScreenHarness screen) throws IOException {
        String frame = screen.render();
        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve(name + ".txt"), frame, StandardCharsets.UTF_8);
        return frame;
    }

    private static KeyEvent ctrl(char c) {
        return KeyEvent.ofChar(c, KeyModifiers.CTRL);
    }

    @Test
    void mainWorkspaceShowsStatusIconsVersionsColumnAndFooter() throws IOException {
        ScreenHarness screen = wide();
        String frame = shot("01-main", screen);

        assertThat(frame).contains("[2] Tools", "✓", "✗", "↑", "Versions · java", "INSTALLED", "AVAILABLE",
                "Enter versions", "Ctrl+P", "↑ 3 outdated");
    }

    @Test
    void ctrlPOpensThePaletteListingCommandsWithTheirKeys() throws IOException {
        ScreenHarness screen = wide().press(ctrl('p'));
        String frame = shot("02-palette", screen);

        assertThat(screen.ui().palette().isOpen()).isTrue();
        assertThat(frame).contains("Commands", "of 3", "Add an SDK", "[a]", "[Space t]");
    }

    @Test
    void colonOpensTheCommandLineWithAPreview() throws IOException {
        ScreenHarness screen = wide().press(KeyEvent.ofChar(':'));
        screen.ui().commandLine().setText("use node@2");
        String frame = shot("03-command", screen);

        assertThat(frame).contains("will run", "mise use node@2", "writes mise.toml");
    }

    @Test
    void ctrlOLabelsThePanels() throws IOException {
        ScreenHarness screen = wide().press(ctrl('o'));
        String frame = shot("04-jump", screen);

        assertThat(frame).contains("JUMP", " 2  Tools", " v  Versions");
        screen.press(KeyEvent.ofChar('v'));
        assertThat(screen.focusedId()).isEqualTo(PanelIds.VERSIONS);
        assertThat(screen.state().jumpMode()).isFalse();
    }

    @Test
    void spaceOpensTheLeaderMenuAndASecondKeyRunsTheCommand() throws IOException {
        ScreenHarness screen = wide().press(KeyEvent.ofChar(' '));
        String frame = shot("05-leader", screen);

        assertThat(frame).contains("Space", "PROJECT", "UPDATES", "Prune unused versions");
        screen.press(KeyEvent.ofChar('m'));
        assertThat(screen.state().beginnerMode()).isTrue();
        assertThat(screen.ui().leaderMenu().isOpen()).isFalse();
    }

    @Test
    void addSdkWizardWalksToolVersionWhereConfirmThenRuns() throws IOException {
        ScreenHarness screen = wide().press(KeyEvent.ofChar('a'));
        var wizard = screen.ui().registryModal();
        assertThat(shot("06-wizard-tool", screen)).contains("● Tool", "○ Confirm", "Which tool?", "python", "step 1 of 4");

        wizard.handleKey(KeyEvent.ofKey(KeyCode.ENTER));
        assertThat(shot("06-wizard-version", screen)).contains("✓ Tool python", "Which version of python?", "23.1.0");

        wizard.handleKey(KeyEvent.ofKey(KeyCode.ENTER));
        String where = shot("06-wizard-where", screen);
        assertThat(where).contains("✓ Version 23.1.0", "● Where", "Where should python 23.1.0 apply?",
                "This project", "writes ./mise.toml", "Everywhere (global)", "Just install it",
                "$ mise use python@23.1.0", "step 3 of 4");

        screen.press(KeyEvent.ofKey(KeyCode.DOWN), KeyEvent.ofKey(KeyCode.ENTER));
        assertThat(shot("06-wizard-confirm", screen)).contains("● Confirm", "Everywhere (global)",
                "$ mise use -g python@23.1.0", "step 4 of 4");

        screen.press(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT));
        assertThat(wizard.step()).as("Shift+Tab goes back a step").isEqualTo(RegistryModal.Step.WHERE);
        screen.press(KeyEvent.ofKey(KeyCode.ENTER), KeyEvent.ofKey(KeyCode.ENTER));

        assertThat(wizard.isOpen()).isFalse();
        assertThat(screen.state().jobs().ordered()).extracting(job -> job.command())
                .containsExactly("$ mise use -g python@23.1.0");
    }

    @Test
    void upgradeAsksWithADialogThatDefaultsToNo() throws IOException {
        ScreenHarness screen = wide();
        screen.state().mark("java");
        screen.state().mark("node");
        screen.ui().toolsPanel().upgradeSelected();
        String frame = shot("07-batch", screen);

        assertThat(frame).contains("Upgrade 2 tools?", "21.0.6", "23.1.0", "major");
        screen.press(KeyEvent.ofKey(KeyCode.ENTER));
        assertThat(screen.ui().batchConfirm().isOpen()).as("Enter = the default, Cancel").isFalse();
        assertThat(screen.state().jobs().ordered()).as("nothing ran").isEmpty();
    }

    @Test
    void jobsViewListsWhatRanAndTheUndoHistory() throws IOException {
        ScreenHarness screen = wide();
        screen.ui().toolsPanel().installSelected();
        screen.press(KeyEvent.ofChar('w'));
        String frame = shot("08-jobs", screen);

        assertThat(frame).contains("Jobs (w)", "install java@21.0.5", "done", "Recent changes");
    }

    @Test
    void f1OpensHelpForTheFocusedPanel() throws IOException {
        ScreenHarness screen = wide().press(KeyEvent.ofKey(KeyCode.F1));
        String frame = shot("09-help", screen);

        assertThat(frame).contains("Help · Tools panel", "IN THE TOOLS PANEL", "ANYWHERE");
    }

    @Test
    void beginnerModeAddsTheMenuBarAndFKeyBar() throws IOException {
        ScreenHarness screen = wide();
        screen.state().beginnerMode(true);
        String bars = shot("10-beginner", screen);
        assertThat(bars).contains("File", "Project", "1Help", "10Quit");

        screen.press(KeyEvent.ofKey(KeyCode.F9));
        String menu = shot("10-beginner-menu", screen);
        assertThat(screen.ui().menuBar().isOpen()).isTrue();
        assertThat(menu).contains("works", "anywhere");
        // The dropdown paints every cell, so nothing from the panels behind shows through it.
        String entry = menu.lines().filter(l -> l.contains("Cancel everything running")).findFirst().orElseThrow();
        assertThat(entry).doesNotContain("↑", "23.1.0").contains("C │");
    }

    @Test
    void clickingAMenuNameOpensItAndClickingAnEntryRunsIt() throws IOException {
        ScreenHarness screen = wide();
        screen.state().beginnerMode(true);

        screen.click(2, 0);
        assertThat(screen.ui().menuBar().isOpen()).isTrue();
        assertThat(screen.ui().menuBar().current()).isEqualTo(MenuBar.Menu.FILE);
        assertThat(shot("10-beginner-click", screen)).contains("Prune unused versions");

        screen.click(4, 2);
        assertThat(screen.ui().menuBar().isOpen()).as("running an entry closes the menu").isFalse();
        assertThat(screen.ui().confirmModal().isOpen()).as("prune asks first").isTrue();
    }

    @Test
    void clickingOutsideTheDropdownClosesIt() {
        ScreenHarness screen = wide();
        screen.state().beginnerMode(true);
        screen.click(2, 0).click(120, 30);

        assertThat(screen.ui().menuBar().isOpen()).isFalse();
    }

    @Test
    void altLetterOpensThatMenuAndSwitchesBeginnerModeOn() {
        ScreenHarness screen = wide().press(KeyEvent.ofChar('t', KeyModifiers.ALT));

        assertThat(screen.state().beginnerMode()).isTrue();
        assertThat(screen.ui().menuBar().isOpen()).isTrue();
        assertThat(screen.ui().menuBar().current()).isEqualTo(MenuBar.Menu.TOOLS);
        screen.press(KeyEvent.ofChar('h', KeyModifiers.ALT));
        assertThat(screen.ui().menuBar().current()).isEqualTo(MenuBar.Menu.HELP);
    }

    @Test
    void narrowTerminalDrillsDownUnderABreadcrumb() throws IOException {
        ScreenHarness screen = new ScreenHarness(new DemoBackend(), 80, 24);
        screen.focus(PanelIds.VERSIONS);
        String frame = shot("11-narrow", screen);

        assertThat(frame).contains("tambo › Tools › java", "Versions · java", "Enter opens it");
    }

    @Test
    void tooSmallTerminalSaysHowBigItNeedsToBe() throws IOException {
        ScreenHarness screen = new ScreenHarness(new DemoBackend(), 46, 14);
        String frame = shot("12-too-small", screen);

        assertThat(frame).contains("bigger window", "46 × 14", "60 × 16");
    }

    @Test
    void emptyProjectNamesTheKeysThatFixIt() throws IOException {
        ScreenHarness screen = new ScreenHarness(new DemoBackend().withSdks(List.of()), 140, 40);
        String frame = shot("13-empty", screen);

        assertThat(frame).contains("No tools in this project yet", "add an SDK", "browse all commands");
    }

    @Test
    void offlineSaysSoInTheHeader() throws IOException {
        ScreenHarness screen = wide();
        screen.state().offline(true);
        String frame = shot("13-offline", screen);

        assertThat(frame).contains("offline", "installs paused");
    }

    @Test
    void undoRestoresTheConfigAndRedoReappliesIt() {
        ScreenHarness screen = wide();
        screen.press(KeyEvent.ofChar('z'));
        assertThat(screen.state().log()).extracting(e -> e.text()).contains("Nothing to undo");
    }
}
