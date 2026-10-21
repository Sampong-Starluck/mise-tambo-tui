package com.sampong.tambo.tui.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import com.sampong.tambo.tui.commands.CommandLineCompleter.Suggestion;

class CommandLineCompleterTest {

    private static final List<String> TOOLS = List.of("node", "nodejs", "go", "python", "terraform");

    private final CommandLineCompleter completer = new CommandLineCompleter(
            "mise",
            CommandLineCompleter.DEFAULT_SUBCOMMANDS,
            tool -> tool.equals("node") ? List.of("22.11.0", "20.18.0", "22.1.0") : List.of(),
            () -> TOOLS);

    @Test
    void firstWordCompletesToSubcommands() {
        assertThat(completer.suggest("ins")).extracting(Suggestion::replacement).containsExactly("install");
    }

    @Test
    void emptyInputOffersSubcommandsCappedAtEight() {
        assertThat(completer.suggest("")).hasSize(8);
        assertThat(completer.suggest("")).extracting(Suggestion::replacement)
                .doesNotHaveDuplicates()
                .allMatch(CommandLineCompleter.DEFAULT_SUBCOMMANDS::contains);
    }

    @Test
    void laterWordCompletesToToolNames() {
        assertThat(completer.suggest("install no")).extracting(Suggestion::replacement)
                .containsExactly("node", "nodejs");
    }

    @Test
    void trailingSpaceOffersAllToolNames() {
        assertThat(completer.suggest("use ")).extracting(Suggestion::replacement)
                .containsExactlyElementsOf(TOOLS);
    }

    @Test
    void afterAtCompletesVersionsOfThatTool() {
        assertThat(completer.suggest("install node@22"))
                .extracting(Suggestion::replacement, Suggestion::display)
                .containsExactly(
                        Tuple.tuple("node@22.11.0", "22.11.0"),
                        Tuple.tuple("node@22.1.0", "22.1.0"));
    }

    @Test
    void bareAtListsEveryVersion() {
        assertThat(completer.suggest("install node@")).hasSize(3);
    }

    @Test
    void toolWithNoKnownVersionsGetsNothing() {
        assertThat(completer.suggest("install go@")).isEmpty();
    }

    @Test
    void flagsGetNoSuggestions() {
        assertThat(completer.suggest("install -")).isEmpty();
        assertThat(completer.suggest("install --")).isEmpty();
    }

    @Test
    void unmatchedWordGetsNothing() {
        assertThat(completer.suggest("zzz")).isEmpty();
    }

    @Test
    void applyReplacesOnlyTheCurrentToolToken() {
        assertThat(completer.apply("install go nod", new Suggestion("nodejs", "nodejs", "")))
                .isEqualTo("install go nodejs ");
    }

    @Test
    void applyCompletesSubcommandWithTrailingSpace() {
        assertThat(completer.apply("ins", new Suggestion("install", "install", "")))
                .isEqualTo("install ");
    }

    @Test
    void applyVersionLeavesNoTrailingSpace() {
        assertThat(completer.apply("install node@2", new Suggestion("node@22.11.0", "22.11.0", "")))
                .isEqualTo("install node@22.11.0");
    }

    @Test
    void applyAfterTrailingSpaceAppendsInsteadOfReplacing() {
        assertThat(completer.apply("install ", new Suggestion("go", "go", "")))
                .isEqualTo("install go ");
    }

    @Test
    void effectForUseInProjectWritesMiseToml() {
        assertThat(completer.effect("use node@22")).isEqualTo("writes mise.toml");
    }

    @Test
    void effectForUseGlobalWritesGlobalConfig() {
        assertThat(completer.effect("use -g node@22")).isEqualTo("writes global config");
        assertThat(completer.effect("use --global node@22")).isEqualTo("writes global config");
    }

    @Test
    void effectForInstallDownloads() {
        assertThat(completer.effect("install node@22")).isEqualTo("downloads and installs");
    }

    @Test
    void effectForRemovalsSaysSo() {
        assertThat(completer.effect("uninstall node@22")).isEqualTo("removes installed files");
        assertThat(completer.effect("prune")).isEqualTo("removes installed files");
    }

    @Test
    void effectIsEmptyForReadOnlyCommands() {
        assertThat(completer.effect("ls")).isEmpty();
        assertThat(completer.effect("")).isEmpty();
    }

    @Test
    void tokensSplitOnWhitespaceAndIgnoreBlanks() {
        assertThat(CommandLineCompleter.tokens("  install   node@22  ")).containsExactly("install", "node@22");
        assertThat(CommandLineCompleter.tokens("")).isEmpty();
        assertThat(CommandLineCompleter.tokens("   ")).isEmpty();
    }

    @Test
    void previewPrefixesBackendName() {
        assertThat(completer.preview("  install node  ")).isEqualTo("mise install node");
    }
}
