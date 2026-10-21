package com.sampong.tambo._common.service;

import org.jspecify.annotations.Nullable;

import com.sampong.tambo._common.model.InstallProgress;

/**
 * Recognises a backend's install-progress lines among everything else its CLI streams.
 * <p>
 * Neither mise nor vfox offers a machine-readable progress channel, so this reads the same
 * human-oriented output the user would see in a terminal. That output is not a stable
 * interface: a line this fails to recognise simply falls through as ordinary output — logged
 * and shown verbatim, exactly as before progress parsing existed — so a format change in a new
 * CLI release degrades the animation rather than breaking the install.
 * <p>
 * Called on the process reader thread, once per streamed line; implementations must be
 * stateless.
 */
@FunctionalInterface
public interface ProgressParser {

    /** For backends whose output has no recognisable progress. */
    ProgressParser NONE = line -> null;

    /**
     * The progress {@code line} reports, or null when it is ordinary output. {@code line} has
     * already had ANSI escapes stripped.
     */
    @Nullable InstallProgress parse(String line);

    /**
     * True for lines that are neither progress worth tracking nor output worth logging — a
     * backend's own aggregate bar, say, which the UI's animation already replaces.
     */
    default boolean isNoise(String line) {
        return false;
    }
}
