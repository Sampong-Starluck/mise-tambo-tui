package com.sampong.tambo._common.util;

/**
 * tambo's own version, as {@code --version} and the TUI header show it.
 * <p>
 * Read from the jar manifest's {@code Implementation-Version}, which Spring Boot's repackaging
 * fills in from the pom's {@code <version>} — so there is one place to bump it. A run that is
 * not from the packaged jar (an IDE launch, {@code spring-boot:run} over {@code target/classes})
 * has no manifest to read, and reports {@value #DEVELOPMENT} instead.
 */
public final class AppVersion {

    /** Shown when no manifest version is available. */
    public static final String DEVELOPMENT = "development";

    private static final String VERSION = resolve();

    private AppVersion() {
    }

    /** The version string, or {@value #DEVELOPMENT} outside a packaged build. */
    public static String get() {
        return VERSION;
    }

    private static String resolve() {
        String version = AppVersion.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? DEVELOPMENT : version;
    }
}
