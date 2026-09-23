package com.sampong.tambo._common.model;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One tool the project config declares, matched against what is actually installed.
 * <p>
 * Backend-neutral like every other record here: the {@code [tools]} table reads the same in
 * {@code mise.toml} and {@code .vfox.toml}, and both backends report installed versions as
 * {@link SdkVersion}, so the comparison that produces these steps
 * ({@code com.sampong.tambo._common.service.AutoInstallPlanner}) never has to know which CLI
 * is behind it.
 * <p>
 * The three statuses are what the auto-install flow branches on, and they exist because
 * "the config asks for a version you do not have" is not one situation but two: a version
 * close enough to what is on disk that reusing it is a reasonable answer, and one that is not.
 * Only {@link Status#SIMILAR} needs the user, which is why it carries both versions.
 *
 * @param sdk              the tool's short name, as the config spells it
 * @param requestedVersion the version string the config declares, verbatim (it may be a range
 *                         like {@code "21"} or an alias like {@code "latest"})
 * @param status           what has to happen for the project to match the config
 * @param installedVersion the installed version this matched, or null for {@link Status#MISSING};
 *                         for {@link Status#SUBSTITUTE}, the recommended stand-in
 * @param candidates       for {@link Status#SUBSTITUTE}, every installed version of the tool,
 *                         closest to the config's first; empty for every other status
 */
public record AutoInstallStep(
        String sdk,
        String requestedVersion,
        Status status,
        @Nullable String installedVersion,
        List<String> candidates
) {

    public AutoInstallStep {
        candidates = List.copyOf(candidates);
    }

    public enum Status {
        /**
         * An installed version already answers the config, so there is nothing to do at all —
         * not even a pin. The config is itself the pin under both backends, and re-pinning
         * would rewrite it with the concrete version that happened to match: a config asking
         * for {@code ["20", "18"]} or {@code "latest"} would come back as {@code "20.11.1"},
         * silently trading a range the project chose for the one build on this machine.
         */
        SATISFIED,
        /**
         * Nothing matches exactly, but a version of the same major release is installed
         * (Java 25.0.3 on disk against a config asking for 25.0.4). Reusing it saves a
         * download and is usually what the user wants, but it is not what the config says —
         * so this is the one status that stops and asks.
         */
        SIMILAR,
        /**
         * Offline only: the config's version is not on disk and cannot be fetched, but other
         * versions of the tool are. {@link #candidates()} lists them closest-first and
         * {@link #installedVersion()} is the recommendation; the user picks one to pin in the
         * config's place, or skips the tool. Offline this replaces {@link #SIMILAR} as well,
         * since its "download what the config says" answer is not available.
         */
        SUBSTITUTE,
        /**
         * Nothing close enough is installed; the config's version has to be fetched. Offline,
         * with no version of the tool on disk at all, this is a tool that cannot be set up.
         */
        MISSING
    }

    public static AutoInstallStep satisfied(String sdk, String requested, String installed) {
        return new AutoInstallStep(sdk, requested, Status.SATISFIED, installed, List.of());
    }

    public static AutoInstallStep similar(String sdk, String requested, String installed) {
        return new AutoInstallStep(sdk, requested, Status.SIMILAR, installed, List.of());
    }

    public static AutoInstallStep missing(String sdk, String requested) {
        return new AutoInstallStep(sdk, requested, Status.MISSING, null, List.of());
    }

    /** @param ranked installed versions of the tool, closest to {@code requested} first; never empty */
    public static AutoInstallStep substitute(String sdk, String requested, List<String> ranked) {
        return new AutoInstallStep(sdk, requested, Status.SUBSTITUTE, ranked.getFirst(), ranked);
    }

    /** The {@code name@version} the config asks for, as both CLIs accept it. */
    public String requestedLabel() {
        return sdk + "@" + requestedVersion;
    }

    /**
     * The installed near-match. Only defined for {@link Status#SATISFIED},
     * {@link Status#SIMILAR} and {@link Status#SUBSTITUTE}, where the planner guarantees a match — a call on a
     * {@link Status#MISSING} step is a bug in the caller's branching rather than a state to
     * render, so it fails rather than inventing a version.
     */
    public String requireInstalledVersion() {
        return Objects.requireNonNull(installedVersion,
                () -> "no installed version matched " + requestedLabel());
    }
}
