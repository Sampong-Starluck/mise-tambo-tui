package com.sampong.tambo._common.model;

/**
 * What the app knows about the version manager it is driving: enough to fill the header badge
 * and the Status surface for either backend.
 * <p>
 * mise fills every field from its {@code doctor} report. vfox fills {@code name} and
 * {@code version} from {@code vfox -v} and leaves the rest at their defaults, which the UI
 * renders as "not reported" rather than as "no" — a backend that cannot answer a question is
 * not the same as one answering no, and showing "not activated" for a backend with no such
 * concept was actively misleading.
 *
 * @param name            the backend's own name, {@code mise} or {@code vfox}
 * @param version         its version string, or {@code unknown} before the probe answers
 * @param activated       whether shell activation is in effect; only meaningful with
 *                        {@link #reportsHealth()}
 * @param shimsOnPath     whether the backend's shim directory is on PATH
 * @param configFileCount how many config files the backend is reading
 * @param reportsHealth   whether the three fields above carry an answer at all
 */
public record BackendInfo(
        String name,
        String version,
        boolean activated,
        boolean shimsOnPath,
        int configFileCount,
        boolean reportsHealth
) {

    /** Before any probe has answered. */
    public static BackendInfo unknown(String name) {
        return new BackendInfo(name, "unknown", false, false, 0, false);
    }

    /** A backend that reports only its version, with no health surface behind it. */
    public static BackendInfo versionOnly(String name, String version) {
        return new BackendInfo(name, version, false, false, 0, false);
    }

    /** A full health report, from a backend that has one. */
    public static BackendInfo health(String name, String version, boolean activated,
                                     boolean shimsOnPath, int configFileCount) {
        return new BackendInfo(name, version, activated, shimsOnPath, configFileCount, true);
    }
}
