package com.sampong.tambo.tui.pick;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo.tui.pick.PickModel.Action;
import com.sampong.tambo.tui.pick.PickModel.Row;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

class PickModelTest {

    /** An installed node version; {@code source} is the config that pins it, or null for none. */
    private static SdkVersion installed(String version, boolean active, String sourceType, String source) {
        return new SdkVersion("node", version, null, null, sourceType, source, true, active);
    }

    private static List<SdkRelease> releases(String... versions) {
        List<SdkRelease> out = new ArrayList<>();
        for (String v : versions) {
            out.add(SdkRelease.of(v));
        }
        return out;
    }

    /**
     * The mockup's shape in miniature: 22.11.0 active here (pinned by the project), 22.9.0 installed,
     * 20.18.0 installed globally, and 22.12.0 / 23.1.0 available, 23.1.0 being the latest.
     */
    private static PickModel sample() {
        List<SdkVersion> sdks = List.of(
                installed("22.9.0", false, "mise.toml", null),
                installed("22.11.0", true, "mise.toml", "/home/dev/shop-api/mise.toml"),
                installed("20.18.0", false, "global", "/home/dev/.config/mise/config.toml"));
        return new PickModel("mise", "node", sdks,
                releases("23.1.0", "22.12.0", "22.11.0", "22.9.0", "20.18.0"), "23.1.0");
    }

    private static List<String> versions(PickModel model) {
        return model.visible().stream().map(Row::version).toList();
    }

    @Test
    void emptyQueryShowsInstalledFirstWithTheActiveVersionLeading() {
        PickModel model = sample();

        assertThat(versions(model)).containsExactly("22.11.0", "22.9.0", "20.18.0", "23.1.0", "22.12.0");
        assertThat(model.counter()).isEqualTo("5 / 5");
    }

    @Test
    void notesDescribeInstalledCurrentGlobalAndLatest() {
        PickModel model = sample();
        List<Row> rows = model.visible();

        assertThat(rows.get(0).note()).isEqualTo("installed · current");
        assertThat(rows.get(1).note()).isEqualTo("installed");
        assertThat(rows.get(2).note()).isEqualTo("installed · global");
        assertThat(rows.get(3).note()).isEqualTo("latest");
        assertThat(rows.get(4).note()).isEmpty();
    }

    @Test
    void noteJoinsInstalledAndLatestWhenBothApply() {
        assertThat(new Row("23.1.0", true, false, false, true).note()).isEqualTo("installed · latest");
        assertThat(new Row("23.1.0", true, true, false, true).note()).isEqualTo("installed · current · latest");
    }

    @Test
    void typingFiltersByFuzzyMatchAndUpdatesTheCounter() {
        PickModel model = sample();

        model.type('2');
        model.type('2');

        assertThat(versions(model)).containsExactlyInAnyOrder("22.11.0", "22.9.0", "22.12.0");
        assertThat(model.counter()).isEqualTo("3 / 5");
        assertThat(model.query()).isEqualTo("22");
    }

    @Test
    void backspaceWidensTheFilterAgain() {
        PickModel model = sample();
        model.type('z');
        assertThat(model.counter()).isEqualTo("0 / 5");

        model.backspace();

        assertThat(model.query()).isEmpty();
        assertThat(model.counter()).isEqualTo("5 / 5");
        model.backspace();
        assertThat(model.query()).as("backspace on an empty query").isEmpty();
    }

    @Test
    void typingResetsTheCursorToTheBestMatch() {
        PickModel model = sample();
        model.down();
        model.down();

        model.type('2');

        assertThat(model.selected()).isNotNull();
        assertThat(model.selected().version()).isEqualTo(model.visible().get(0).version());
    }

    @Test
    void cursorMovesAndStopsAtBothEnds() {
        PickModel model = sample();

        model.up();
        assertThat(model.selected().version()).isEqualTo("22.11.0");

        for (int i = 0; i < 10; i++) {
            model.down();
        }
        assertThat(model.selected().version()).isEqualTo("22.12.0");

        for (int i = 0; i < 10; i++) {
            model.up();
        }
        assertThat(model.selected().version()).isEqualTo("22.11.0");
    }

    @Test
    void selectionIsNullAndCounterZeroWhenQueryMatchesNothing() {
        PickModel model = sample();

        model.type('z');

        assertThat(model.visible()).isEmpty();
        assertThat(model.selected()).isNull();
        assertThat(model.counter()).isEqualTo("0 / 5");
    }

    @Test
    void emptyToolHasNoRowsAndNoSelection() {
        PickModel model = new PickModel("mise", "node", List.of(), List.of(), null);

        assertThat(model.visible()).isEmpty();
        assertThat(model.selected()).isNull();
        assertThat(model.counter()).isEqualTo("0 / 0");
    }

    @Test
    void onlyTheNamedToolIsConsidered() {
        List<SdkVersion> sdks = List.of(
                new SdkVersion("go", "1.22.0", null, null, null, null, true, true));
        PickModel model = new PickModel("mise", "node", sdks, releases("22.11.0"), null);

        assertThat(versions(model)).containsExactly("22.11.0");
    }

    @Test
    void pinnedPathIsReportedOnlyForAVersionAConfigPins() {
        PickModel model = sample();

        assertThat(model.pinnedPath(model.visible().get(0))).isEqualTo("/home/dev/shop-api/mise.toml");
        assertThat(model.pinnedPath(model.visible().get(1))).isNull();
    }

    @Test
    void firstVisibleScrollsOnlyAsFarAsTheCursorNeeds() {
        List<SdkRelease> many = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            many.add(SdkRelease.of("1.0." + i));
        }
        PickModel model = new PickModel("mise", "node", List.of(), many, null);

        assertThat(model.firstVisible(7)).isZero();
        for (int i = 0; i < 9; i++) {
            model.down();
        }
        assertThat(model.firstVisible(7)).as("cursor on row 9 of a 7-row window").isEqualTo(3);
        for (int i = 0; i < 9; i++) {
            model.up();
        }
        assertThat(model.firstVisible(7)).isZero();
    }

    @Test
    void enterIsUseHere() {
        assertThat(PickModel.actionFor(KeyEvent.ofKey(KeyCode.ENTER))).isEqualTo(Action.USE_HERE);
    }

    @Test
    void ctrlGIsUseGlobally() {
        assertThat(PickModel.actionFor(KeyEvent.ofChar('g', KeyModifiers.CTRL))).isEqualTo(Action.USE_GLOBAL);
    }

    @Test
    void altIIsInstallOnly() {
        assertThat(PickModel.actionFor(KeyEvent.ofChar('i', KeyModifiers.ALT))).isEqualTo(Action.INSTALL_ONLY);
    }

    @Test
    void ordinaryKeysAreNotActions() {
        assertThat(PickModel.actionFor(KeyEvent.ofChar('g'))).isNull();
        assertThat(PickModel.actionFor(KeyEvent.ofChar('i'))).isNull();
        assertThat(PickModel.actionFor(KeyEvent.ofChar('i', KeyModifiers.CTRL))).isNull();
        assertThat(PickModel.actionFor(KeyEvent.ofKey(KeyCode.ESCAPE))).isNull();
    }
}
