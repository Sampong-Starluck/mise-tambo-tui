package com.sampong.tambo.mise.model;

import org.jspecify.annotations.Nullable;

/**
 * A single tool version entry as reported by {@code mise ls -J}.
 */
public record ToolVersion(
        String tool,
        String version,
        @Nullable String requestedVersion,
        @Nullable String installPath,
        @Nullable String sourceType,
        @Nullable String sourcePath,
        boolean installed,
        boolean active
) {

    /**
     * False for a vfox plugin that has been registered ({@code vfox add}) but has no version
     * installed yet — vfox lists such a plugin, so it gets a row, but there is no
     * {@code tool@version} to install, uninstall or pin until a version is chosen.
     */
    public boolean hasVersion() {
        return !version.isBlank();
    }

    /**
     * Returns the {@code tool@version} identifier mise expects on the command line, or the
     * bare tool name when no version is installed yet (see {@link #hasVersion()}) — never a
     * dangling {@code "tool@"}, which is neither valid on a command line nor readable in a log.
     */
    public String label() {
        return hasVersion() ? tool + "@" + version : tool;
    }
}
