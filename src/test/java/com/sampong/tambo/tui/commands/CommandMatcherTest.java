package com.sampong.tambo.tui.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CommandMatcherTest {

    private static Command command(String id, String title, Command.Group group) {
        return new Command(id, title, group, "", null, false, () -> { });
    }

    private final Command upgrade = command("tools.upgrade", "Upgrade all outdated (3)", Command.Group.UPDATES);
    private final Command install = command("tools.install", "Install node 22", Command.Group.TOOLS);
    private final Command prune = command("cleanup.prune", "Prune unused versions", Command.Group.CLEANUP);
    private final Command doctor = command("app.doctor", "Run doctor", Command.Group.APP);

    @Test
    void emptyQueryKeepsGivenOrderWithNoHighlights() {
        List<Command> commands = List.of(prune, upgrade, install);

        List<CommandMatcher.Match> matches = CommandMatcher.rank("", commands);

        assertThat(matches).extracting(m -> m.command().id())
                .containsExactly("cleanup.prune", "tools.upgrade", "tools.install");
        assertThat(matches).allSatisfy(m -> assertThat(m.highlight()).isEmpty());
    }

    @Test
    void blankQueryIsTreatedAsEmpty() {
        assertThat(CommandMatcher.rank("   ", List.of(upgrade, install)))
                .extracting(m -> m.command().id())
                .containsExactly("tools.upgrade", "tools.install");
    }

    @Test
    void upgRanksUpgradeFirst() {
        List<CommandMatcher.Match> matches = CommandMatcher.rank("upg", List.of(install, upgrade));

        assertThat(matches).extracting(m -> m.command().id()).containsExactly("tools.upgrade");
    }

    @Test
    void tighterTitleRanksAboveLongerTitleWithTheSameMatch() {
        Command longer = upgrade;
        Command shorter = command("tools.upgrade-short", "Upgrade", Command.Group.UPDATES);

        List<CommandMatcher.Match> matches = CommandMatcher.rank("upg", List.of(longer, shorter));

        assertThat(matches).extracting(m -> m.command().id())
                .containsExactly("tools.upgrade-short", "tools.upgrade");
    }

    @Test
    void nonMatchingCommandsAreDropped() {
        List<CommandMatcher.Match> matches = CommandMatcher.rank("zzq", List.of(upgrade, install, doctor));

        assertThat(matches).isEmpty();
    }

    @Test
    void highlightIndicesPointAtTheMatchedTitleCharacters() {
        // "Upgrade" starts with U, p, g at 0, 1, 2 — the match is case-insensitive.
        List<CommandMatcher.Match> matches = CommandMatcher.rank("UPG", List.of(upgrade));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).highlight()).containsExactly(0, 1, 2);
    }

    @Test
    void highlightSkipsGapsBetweenMatchedCharacters() {
        // "Install node 22": the query i-n-d lands on I (0), n (1) and the d of "node" (10),
        // skipping everything in between.
        List<CommandMatcher.Match> matches = CommandMatcher.rank("ind", List.of(install));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).highlight()).containsExactly(0, 1, 10);
    }

    @Test
    void groupLabelOnlyMatchIsKeptWithNoHighlight() {
        // "clean" is in no title here, only in the Clean-up group label of prune.
        List<CommandMatcher.Match> matches = CommandMatcher.rank("clean", List.of(doctor, prune));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).command()).isSameAs(prune);
        assertThat(matches.get(0).highlight()).isEmpty();
    }

    @Test
    void titleMatchOutranksGroupOnlyMatchWhateverTheOrder() {
        // "Zebra" is only found through its TOOLS group label; "Tools overview" matches in the title.
        Command toolsGroupOnly = command("x.group", "Zebra", Command.Group.TOOLS);
        Command titleHit = command("x.title", "Tools overview", Command.Group.APP);

        List<CommandMatcher.Match> matches = CommandMatcher.rank("tools", List.of(toolsGroupOnly, titleHit));

        assertThat(matches).extracting(m -> m.command().id()).containsExactly("x.title", "x.group");
    }

    @Test
    void tiesKeepTheCallersOrder() {
        Command first = command("a", "Alpha bravo", Command.Group.APP);
        Command second = command("b", "Alpha bravo", Command.Group.APP);

        List<CommandMatcher.Match> matches = CommandMatcher.rank("alp", List.of(first, second));

        assertThat(matches).extracting(m -> m.command().id()).containsExactly("a", "b");
    }
}
