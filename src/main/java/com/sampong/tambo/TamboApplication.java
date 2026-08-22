package com.sampong.tambo;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

import com.sampong.tambo.cli.TamboCommand;

import picocli.CommandLine;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.UnmatchedArgumentException;

@SpringBootApplication
@ImportRuntimeHints(TamboApplication.TamboUiResourceHints.class)
public class TamboApplication {

    /**
     * Native image: TamboUI loads its built-in key-binding sets
     * (dev/tamboui/tui/bindings/*.properties) from the classpath at runtime,
     * and the TamboUI jars ship no resource metadata for them.
     */
    static class TamboUiResourceHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
            hints.resources().registerPattern("dev/tamboui/tui/bindings/*.properties");
            // Spring's own AOT-generated reflection entry for this class carries a
            // typeReached(TamboApplication) runtime condition, but this app's own
            // package is initialized at build time under the `native` profile, so
            // that runtime transition never fires and the conditional entry never
            // activates ("AOT initializer ... could not be found" at native-image
            // startup). Register it unconditionally as a belt-and-suspenders fix.
            hints.reflection().registerType(
                    TypeReference.of(TamboApplication.class.getName() + "__ApplicationContextInitializer"),
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        }
    }

    /**
     * Parses and validates {@code args} with picocli first (giving {@code --help}/{@code --version}
     * and validated errors on bad input), then boots Spring directly rather than through picocli's
     * {@code execute()} — see {@link TamboCommand}'s class javadoc for why that distinction matters
     * for the native-image AOT build.
     */
    static void main(String[] args) {
        TamboCommand command = new TamboCommand();
        CommandLine cli = new CommandLine(command).setCaseInsensitiveEnumValuesAllowed(true);

        ParseResult parseResult;
        try {
            parseResult = cli.parseArgs(args);
        } catch (ParameterException ex) {
            cli.getErr().println(ex.getMessage());
            if (!UnmatchedArgumentException.printSuggestions(ex, cli.getErr())) {
                ex.getCommandLine().usage(cli.getErr());
            }
            System.exit(cli.getCommandSpec().exitCodeOnInvalidInput());
            return;
        }
        if (CommandLine.printHelpIfRequested(parseResult)) {
            System.exit(CommandLine.ExitCode.OK);
            return;
        }

        SpringApplication app = new SpringApplication(TamboApplication.class);
        app.addInitializers(ctx -> ctx.getBeanFactory().registerSingleton("tamboCommand", command));
        System.exit(SpringApplication.exit(app.run()));
    }
}
