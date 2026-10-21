package com.sampong.tambo.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class TamboCommandTest {

    private static TamboCommand parse(String... args) {
        TamboCommand command = new TamboCommand();
        new CommandLine(command).parseArgs(args);
        return command;
    }

    @Test
    void pickWithAToolNamesTheTool() {
        TamboCommand command = parse("pick", "node");

        assertThat(command.pickTool()).isEqualTo("node");
        assertThat(command.validationError()).isNull();
    }

    @Test
    void noArgumentsLaunchTheFullTui() {
        TamboCommand command = parse();

        assertThat(command.pickTool()).isNull();
        assertThat(command.validationError()).isNull();
    }

    @Test
    void pickWithoutAToolIsRejected() {
        TamboCommand command = parse("pick");

        assertThat(command.pickTool()).isNull();
        assertThat(command.validationError()).contains("tool name");
    }

    @Test
    void anUnknownWordIsRejectedRatherThanIgnored() {
        TamboCommand command = parse("pck", "node");

        assertThat(command.pickTool()).isNull();
        assertThat(command.validationError()).contains("pck");
    }

    @Test
    void optionsStillParseAroundThePickWords() {
        TamboCommand command = parse("--backend", "vfox", "pick", "node");

        assertThat(command.backend()).isEqualTo(TamboCommand.Backend.vfox);
        assertThat(command.pickTool()).isEqualTo("node");
    }

    @Test
    void moreThanTwoWordsIsRejected() {
        TamboCommand command = parse("pick", "node", "22");

        assertThat(command.pickTool()).isNull();
        assertThat(command.validationError()).contains("too many arguments");
    }
}
