package com.sampong.tambo.tui.components;

import dev.tamboui.style.Color;

import com.sampong.tambo._common.model.SdkVersion;

/**
 * The one-glyph state every tool row leads with — mason.nvim's status column. Four states, each
 * with its own shape as well as its own colour, so the column reads the same on a monochrome
 * terminal or to a colour-blind eye: {@code ✓} installed, {@code ➜} being worked on, {@code ✗}
 * asked for by a config but not on disk, {@code ○} registered with no version at all.
 * <p>
 * "Newer version available" is deliberately not a state here: an installed tool can also be
 * outdated, so the {@code ↑} rides after the version instead of replacing the {@code ✓}.
 */
public enum ToolStatus {

    INSTALLED("✓", Color.GREEN),
    BUSY("➜", Color.CYAN),
    MISSING("✗", Color.RED),
    NO_VERSION("○", Color.DARK_GRAY);

    /** The legend the Tools panel hangs off its bottom border. */
    public static final String LEGEND = " ✓ ok  ➜ busy  ✗ missing  ↑ newer  * marked ";

    private final String glyph;
    private final Color color;

    ToolStatus(String glyph, Color color) {
        this.glyph = glyph;
        this.color = color;
    }

    public String glyph() {
        return glyph;
    }

    public Color color() {
        return color;
    }

    /** The state of {@code sdk}, given whether any operation is currently running on it. */
    public static ToolStatus of(SdkVersion sdk, boolean busy) {
        if (busy) {
            return BUSY;
        }
        if (!sdk.hasVersion()) {
            return NO_VERSION;
        }
        return sdk.installed() ? INSTALLED : MISSING;
    }
}
