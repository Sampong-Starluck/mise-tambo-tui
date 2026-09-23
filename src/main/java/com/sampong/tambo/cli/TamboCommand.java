package com.sampong.tambo.cli;

import org.jspecify.annotations.Nullable;

import dev.tamboui.picocli.TuiMixin;

import com.sampong.tambo._common.util.AppVersion;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

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

    @Option(names = "--advanced-features",
            description = "Start the session with the Advanced panel already unlocked, same as pressing V.")
    private boolean advancedFeatures;

    @Option(names = "--auto-install",
            description = "On startup, apply the project's mise.toml/.vfox.toml: install what is "
                    + "missing and ask which version to use where an installed one is close but not "
                    + "equal. Same as pressing I.")
    private boolean autoInstall;

    /** TamboUI's TUI flags (--no-alt-screen, --show-cursor, --mouse, --tick-rate, --poll-timeout). */
    @Mixin
    private final TuiMixin tuiOptions = new TuiMixin();

    public @Nullable Backend backend() {
        return backend;
    }

    public boolean offline() {
        return offline;
    }

    public boolean advancedFeatures() {
        return advancedFeatures;
    }

    public boolean autoInstall() {
        return autoInstall;
    }

    public TuiMixin tuiOptions() {
        return tuiOptions;
    }

    static final class ManifestVersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] { "mise-tambo " + AppVersion.get() };
        }
    }
}
