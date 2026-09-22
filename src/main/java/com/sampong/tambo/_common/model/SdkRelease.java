package com.sampong.tambo._common.model;

/**
 * One installable version of an SDK, as offered by the version browser.
 * <p>
 * Replaces the bare {@code List<String>} the backends used to return: vfox's
 * {@code search … all} already marks which versions are installed, and mise can be told the
 * same from what it has on disk, so carrying that alongside the version string lets the
 * browser mark them without the modal cross-referencing installed SDKs itself.
 *
 * @param version   the version token the CLI expects, exactly as it must be passed
 * @param installed whether this version is already on disk
 * @param latest    whether this is the backend's synthetic "newest" alias rather than a
 *                  concrete version — mise offers {@code latest}, vfox does not
 */
public record SdkRelease(String version, boolean installed, boolean latest) {

    public static SdkRelease of(String version) {
        return new SdkRelease(version, false, false);
    }

    public static SdkRelease installed(String version) {
        return new SdkRelease(version, true, false);
    }

    /** mise's synthetic {@code latest} entry, always offered first in its version list. */
    public static SdkRelease latestAlias() {
        return new SdkRelease("latest", false, true);
    }
}
