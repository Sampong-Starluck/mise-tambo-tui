package com.sampong.tambo.mise.model;

/**
 * A single tool version entry as reported by {@code mise ls -J}.
 */
public record ToolVersion(
        String tool,
        String version,
        String requestedVersion,
        String installPath,
        String sourceType,
        String sourcePath,
        boolean installed,
        boolean active
) {

    /** Returns the {@code tool@version} identifier mise expects on the command line. */
    public String label() {
        return tool + "@" + version;
    }
}
