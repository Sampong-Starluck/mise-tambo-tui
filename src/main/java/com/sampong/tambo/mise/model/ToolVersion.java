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

    /** Returns the {@code tool@version} identifier mise expects on the command line. */
    public String label() {
        return tool + "@" + version;
    }
}
