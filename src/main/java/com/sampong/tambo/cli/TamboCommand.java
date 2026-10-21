package com.sampong.tambo.cli;

import org.jspecify.annotations.Nullable;

import dev.tamboui.picocli.TuiMixin;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * tambo's command-line surface: defines the app's launch flags. {@link com.sampong.tambo.TamboApplication#main}
 * parses and validates an instance of this class with picocli, then registers it as a Spring bean
 * so {@code MiseCli}, {@code AppLifecycle}, and {@code MiseTuiApp} can inject it directly instead
 * of re-parsing raw {@code ApplicationArguments}.
 * <p>
 * Deliberately just an options holder, not a picocli {@code Callable}/{@code Runnable} command:
 * Spring Boot's AOT/native-image processing invokes {@code main} reflectively and relies on
 * {@link org.springframework.boot.SpringApplication#run} throwing an internal signalling
 * exception straight out of {@code main} uncaught. Routing the actual {@code SpringApplication.run}
 * call through picocli's {@code execute()} would have it caught and swallowed by picocli's own
 * exception handling instead, breaking that contract — so {@code main} calls
 * {@code SpringApplication.run} directly, only using picocli to parse/validate first.
 */
@Command(name = "mise-tambo", mixinStandardHelpOptions = true,
        versionProvider = TamboCommand.ManifestVersionProvider.class,
        description = "A lazygit-style terminal UI for mise and vfox.")
@Component
public final class TamboCommand {

    public enum Backend { mise, vfox }

    @Option(names = "--backend",
            description = "Force the version-manager backend (${COMPLETION-CANDIDATES}); "
                    + "otherwise detected from mise.toml/.vfox.toml in the current directory, "
                    + "or picked interactively on first run.")
    private @Nullable Backend backend;

    @Option(names = "--offline",
            description = "Skip anything that needs the network (install, use, self-update, Add SDK); "
                    + "shows only already-installed tools.")
    private boolean offline;

    /**
     * Accepted so existing scripts and aliases keep working, but it no longer does anything: the
     * actions it used to unlock are always available from the Space menu and the Ctrl+P palette.
     */
    @Option(names = "--advanced-features", hidden = true,
            description = "No longer needed: every action is in the Space menu and the Ctrl+P palette.")
    private boolean advancedFeatures;

    /** TamboUI's TUI flags (--no-alt-screen, --show-cursor, --mouse, --tick-rate, --poll-timeout). */
    @Mixin
    private final TuiMixin tuiOptions = new TuiMixin();

    /**
     * Positional words. The only shape that means anything is {@code pick <tool>}, which opens the
     * inline version picker instead of the full TUI; no words launches the TUI as before. Kept as
     * plain words rather than sub-commands because {@code TamboApplication#main} boots Spring
     * directly, not through picocli's {@code execute()}.
     */
    @Parameters(arity = "0..2", paramLabel = "pick <tool>",
            description = "Open the inline version picker for one tool, e.g. `mise-tambo pick node`.")
    private String[] words = new String[0];

    public @Nullable Backend backend() {
        return backend;
    }

    public boolean offline() {
        return offline;
    }

    public boolean advancedFeatures() {
        return advancedFeatures;
    }

    public TuiMixin tuiOptions() {
        return tuiOptions;
    }

    /**
     * The tool named by {@code pick <tool>}, or null when this run is not a pick. Only the exact
     * two-word form counts: {@code pick} alone is a usage error (see {@link #validationError()}).
     */
    public @Nullable String pickTool() {
        return words.length == 2 && "pick".equals(words[0]) ? words[1] : null;
    }

    /**
     * Why the positional words are unusable, or null when they are fine. Checked by
     * {@code TamboApplication#main} after picocli parsing, so a typo such as {@code mise-tambo pck}
     * fails loudly instead of silently launching the full TUI.
     */
    public @Nullable String validationError() {
        // picocli does not enforce arity 0..2 on an array field, so the cap is checked here.
        if (words.length > 2) {
            return "too many arguments; the only command is: mise-tambo pick <tool>";
        }
        if (words.length == 0 || pickTool() != null) {
            return null;
        }
        if ("pick".equals(words[0])) {
            return "pick needs a tool name, e.g. mise-tambo pick node";
        }
        return "unknown argument '" + words[0] + "'; the only command is: mise-tambo pick <tool>";
    }

    static final class ManifestVersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = TamboCommand.class.getPackage().getImplementationVersion();
            return new String[] { "mise-tambo " + (version != null ? version : "development") };
        }
    }
}
