package com.sampong.tambo._common.model;

import org.jspecify.annotations.Nullable;

/**
 * One installed or configured version of an SDK, as both backends report it.
 * <p>
 * Backend-neutral by design: mise maps {@code mise ls -J} into this and vfox maps its parsed
 * {@code vfox list} tree into the same record, so no panel has to know which one produced it.
 * Fields a backend cannot answer are null rather than invented — vfox has no notion of a
 * requested version range or of which config file pinned a version, and renders as "-" for
 * those, which is the honest answer rather than a guess.
 *
 * @param name      the SDK's short name, lowercased ({@code node}, {@code nodejs}, {@code go})
 * @param version   the concrete installed version, or empty when the SDK is registered but has
 *                  no version yet — see {@link #hasVersion()}
 * @param requested the version range the config asked for, when the backend tracks one
 * @param installPath where the version lives on disk, when the backend reports it
 * @param sourceType  what kind of config pinned it ({@code mise.toml}, {@code global}, …)
 * @param sourcePath  the config file that pinned it, when there is one
 * @param installed   whether the version is actually present on disk
 * @param active      whether this is the version currently in effect for the project
 */
public record SdkVersion(
        String name,
        String version,
        @Nullable String requested,
        @Nullable String installPath,
        @Nullable String sourceType,
        @Nullable String sourcePath,
        boolean installed,
        boolean active
) {

    /**
     * False for an SDK that has been registered but has no version installed yet — the state a
     * freshly added vfox plugin is in. Such an SDK still gets a row, but there is no
     * {@code name@version} to install, uninstall or pin until a version is chosen.
     */
    public boolean hasVersion() {
        return !version.isBlank();
    }

    /**
     * The {@code name@version} identifier both CLIs expect as an argument, or the bare name
     * when no version is installed yet (see {@link #hasVersion()}) — never a dangling
     * {@code "name@"}, which is neither valid on a command line nor readable in a log.
     */
    public String label() {
        return hasVersion() ? name + "@" + version : name;
    }

    /** True when this version was pinned by a config file rather than merely being installed. */
    public boolean pinned() {
        return sourcePath != null && !sourcePath.isBlank();
    }
}
