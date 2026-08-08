package com.sampong.tambo.tui.features;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * tambo's own optional configuration, read once at startup from
 * {@code <config-dir>/tambo.properties} where {@code <config-dir>} is
 * {@code $TAMBO_CONFIG_DIR} or {@code ~/.config/tambo}.
 * <p>
 * Everything is optional: a missing or unreadable file yields the built-in
 * {@link Theme#defaults() defaults} and no key overrides, so tambo runs
 * unchanged out of the box. Three sections are honoured:
 * <ul>
 *   <li>{@code theme.*} — palette colours (see {@link Theme})</li>
 *   <li>{@code keys.*} — navigation binding overrides merged onto the standard
 *       set, e.g. {@code keys.moveUp = Up, k, w}</li>
 *   <li>{@code ui.backend} — which TamboUI terminal backend to render with
 *       ({@code jline3} or {@code panama}), settable in-app via {@code B}
 *       (Advanced panel); see {@link #applyBackendPreference()}</li>
 * </ul>
 */
@Slf4j
@Component
public class TamboConfig {

    /** tambo.properties key for {@link #backend()}. */
    private static final String BACKEND_KEY = "ui.backend";
    /** Must match a {@code dev.tamboui.terminal.BackendProvider#name()} on the classpath. */
    public static final String BACKEND_JLINE3 = "jline3";
    public static final String BACKEND_PANAMA = "panama";
    private static final Set<String> VALID_BACKENDS = Set.of(BACKEND_JLINE3, BACKEND_PANAMA);

    private final Theme theme;
    private final Properties properties;

    public TamboConfig() {
        this.properties = load();
        this.theme = Theme.fromProperties(properties);
        applyBackendPreference();
    }

    public Theme theme() {
        return theme;
    }

    /** The configured TamboUI terminal backend name, defaulting to {@value #BACKEND_JLINE3}. */
    public String backend() {
        String configured = properties.getProperty(BACKEND_KEY);
        return configured != null && VALID_BACKENDS.contains(configured) ? configured : BACKEND_JLINE3;
    }

    /**
     * Persists a new {@link #backend()} choice to {@code tambo.properties}. Takes effect on
     * the next launch — TamboUI picks its backend once at startup (see
     * {@link #applyBackendPreference()}), so a running session can't hot-swap it.
     */
    public synchronized void setBackend(String backend) {
        if (!VALID_BACKENDS.contains(backend)) {
            throw new IllegalArgumentException("Unknown UI backend: " + backend);
        }
        properties.setProperty(BACKEND_KEY, backend);
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "tambo configuration");
            }
        } catch (IOException e) {
            log.warn("Could not save {}: {}", file, e.getMessage());
        }
    }

    /**
     * Selects which TamboUI backend {@code dev.tamboui.terminal.BackendFactory} creates, by
     * setting the {@code tamboui.backend} system property it reads. Only applies our
     * {@link #backend()} preference when neither that property nor {@code TAMBOUI_BACKEND} is
     * already set externally, so an explicit deployment-level override always wins over the
     * persisted in-app choice.
     */
    private void applyBackendPreference() {
        if (System.getProperty("tamboui.backend") == null && System.getenv("TAMBOUI_BACKEND") == null) {
            System.setProperty("tamboui.backend", backend());
        }
    }

    /**
     * Navigation binding overrides as a {@code BindingSets} overlay string
     * (one {@code action = keys} line per {@code keys.*} property), or empty when none.
     */
    public String keyOverlay() {
        StringBuilder overlay = new StringBuilder();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("keys.")) {
                String action = name.substring("keys.".length());
                String value = properties.getProperty(name);
                if (!action.isBlank() && value != null && !value.isBlank()) {
                    overlay.append(action).append(" = ").append(value.strip()).append('\n');
                }
            }
        }
        return overlay.toString();
    }

    private static Properties load() {
        Properties props = new Properties();
        Path file = configFile();
        if (!Files.isRegularFile(file)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
            log.debug("Loaded tambo config from {}", file);
        } catch (IOException e) {
            log.warn("Could not read {}: {}", file, e.getMessage());
        }
        return props;
    }

    private static Path configFile() {
        String dir = System.getenv("TAMBO_CONFIG_DIR");
        Path base = dir != null && !dir.isBlank()
                ? Path.of(dir)
                : Path.of(System.getProperty("user.home"), ".config", "tambo");
        return base.resolve("tambo.properties");
    }
}
