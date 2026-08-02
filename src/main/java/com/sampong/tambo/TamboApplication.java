package com.sampong.tambo;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

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

    static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(TamboApplication.class, args)));
    }
}
