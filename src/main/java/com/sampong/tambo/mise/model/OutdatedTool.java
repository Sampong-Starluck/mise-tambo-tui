package com.sampong.tambo.mise.model;

import org.jspecify.annotations.Nullable;

/**
 * One entry of {@code mise outdated -J}: a tool that has a newer version than
 * the one currently installed/active.
 *
 * @param tool    the tool short name (e.g. {@code node})
 * @param current the version currently in use, or null when unknown
 * @param latest  the newer version mise reports as available
 */
public record OutdatedTool(
        String tool,
        @Nullable String current,
        @Nullable String latest
) {
}
