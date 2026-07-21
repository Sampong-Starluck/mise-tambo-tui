package com.sampong.tambo.tui.features;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * tambo's own optional configuration, read once at startup from
 * {@code <config-dir>/tambo.properties} where {@code <config-dir>} is
 * {@code $TAMBO_CONFIG_DIR} or {@code ~/.config/tambo}.
 * <p>
 * Everything is optional: a missing or unreadable file yields the built-in
 * {@link Theme#defaults() defaults} and no key overrides, so tambo runs
 * unchanged out of the box. Two sections are honoured:
 * <ul>
 *   <li>{@code theme.*} — palette colours (see {@link Theme})</li>
 *   <li>{@code keys.*} — navigation binding overrides merged onto the standard
 *       set, e.g. {@code keys.moveUp = Up, k, w}</li>
 * </ul>
 */
@Slf4j
@Component
public class TamboConfig {

    private final Theme theme;
    private final Properties properties;

    public TamboConfig() {
        this.properties = load();
        this.theme = Theme.fromProperties(properties);
    }

    public Theme theme() {
        return theme;
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
