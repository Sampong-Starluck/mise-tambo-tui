package com.sampong.tambo._common.model;

import org.jspecify.annotations.Nullable;

/**
 * One reading of how far an install has got, in a shape both backends' output reduces to.
 * <p>
 * mise and vfox report a download nothing alike — mise prints a status snapshot every few
 * seconds ({@code node@22.11.0  downloading  6.0s  17.4/34.2 MB · 3.4 MB/s}), vfox redraws a
 * percentage bar in place with carriage returns. Each backend's
 * {@link com.sampong.tambo._common.service.ProgressParser} turns its own lines into this, so the
 * UI draws one animation without knowing which CLI produced the numbers.
 *
 * @param tool              which {@code tool@version} the reading is about, when the line names
 *                          one — mise does, which is what lets {@code upgrade} of several tools
 *                          keep one bar per tool; null when the line is implicitly about the
 *                          single SDK the operation was started for (vfox)
 * @param phase             what the install is doing right now
 * @param fraction          completion in {@code [0, 1]}, or null when the phase has no measurable
 *                          extent (unpacking, verifying) or the backend did not say
 * @param fractionPerSecond how fast {@code fraction} is rising, when the backend reports both a
 *                          total size and a transfer rate — lets the UI keep the bar moving between
 *                          backends' sparse snapshots instead of jumping every few seconds
 * @param transferred       e.g. {@code 17.4/34.2 MB}, as the backend printed it
 * @param speed             e.g. {@code 3.4 MB/s}, as the backend printed it
 * @param eta               e.g. {@code 6s}, as the backend printed it
 */
public record InstallProgress(@Nullable String tool, Phase phase, @Nullable Double fraction,
                              @Nullable Double fractionPerSecond, @Nullable String transferred,
                              @Nullable String speed, @Nullable String eta) {

    /** Only {@link #phase} known — the indeterminate steps either side of the download. */
    public static InstallProgress of(@Nullable String tool, Phase phase) {
        return new InstallProgress(tool, phase, null, null, null, null, null);
    }

    /** The stages an install passes through, in the order they normally happen. */
    public enum Phase {
        PREPARING("preparing"),
        DOWNLOADING("downloading"),
        VERIFYING("verifying"),
        EXTRACTING("extracting"),
        INSTALLING("installing");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        /** Lower-case word for the UI, e.g. {@code downloading}. */
        public String label() {
            return label;
        }
    }
}
