package com.sampong.tambo.tui.features;

import java.util.Properties;

import dev.tamboui.style.Color;

/**
 * The colour palette panels render from. Loaded from the user's optional
 * {@code tambo.properties} (see {@link TamboConfig}); every value falls back to
 * the built-in default, so an absent or partial file changes nothing.
 * <p>
 * Recognised keys: {@code theme.accent}, {@code theme.focus}, {@code theme.idle}.
 * Values are colour names ({@code cyan}, {@code green}, {@code dark-gray}, …) or
 * {@code #rrggbb} hex.
 */
public final class Theme {

    /** Highlights, selected rows, titles. */
    private final Color accent;
    /** Border of the focused panel. */
    private final Color focus;
    /** Border of unfocused panels. */
    private final Color idle;

    public Theme(Color accent, Color focus, Color idle) {
        this.accent = accent;
        this.focus = focus;
        this.idle = idle;
    }

    /** The built-in palette — identical to the colours hard-coded before theming. */
    public static Theme defaults() {
        return new Theme(Color.CYAN, Color.GREEN, Color.DARK_GRAY);
    }

    /** Builds a theme from properties, using {@link #defaults()} for anything missing. */
    public static Theme fromProperties(Properties props) {
        Theme d = defaults();
        return new Theme(
                parseColor(props.getProperty("theme.accent"), d.accent),
                parseColor(props.getProperty("theme.focus"), d.focus),
                parseColor(props.getProperty("theme.idle"), d.idle));
    }

    public Color accent() {
        return accent;
    }

    public Color focus() {
        return focus;
    }

    public Color idle() {
        return idle;
    }

    /** Parses a colour name or {@code #rrggbb} hex; returns {@code fallback} when unset or unrecognised. */
    static Color parseColor(String value, Color fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String v = value.strip().toLowerCase();
        if (v.startsWith("#") && v.length() == 7) {
            try {
                return Color.rgb(
                        Integer.parseInt(v.substring(1, 3), 16),
                        Integer.parseInt(v.substring(3, 5), 16),
                        Integer.parseInt(v.substring(5, 7), 16));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return switch (v) {
            case "red" -> Color.RED;
            case "green" -> Color.GREEN;
            case "yellow" -> Color.YELLOW;
            case "blue" -> Color.BLUE;
            case "magenta" -> Color.MAGENTA;
            case "cyan" -> Color.CYAN;
            case "white" -> Color.WHITE;
            case "gray", "grey" -> Color.GRAY;
            case "dark-gray", "dark-grey" -> Color.DARK_GRAY;
            case "light-red" -> Color.LIGHT_RED;
            case "light-green" -> Color.LIGHT_GREEN;
            case "light-yellow" -> Color.LIGHT_YELLOW;
            case "light-blue" -> Color.LIGHT_BLUE;
            case "light-magenta" -> Color.LIGHT_MAGENTA;
            case "light-cyan" -> Color.LIGHT_CYAN;
            default -> fallback;
        };
    }
}
