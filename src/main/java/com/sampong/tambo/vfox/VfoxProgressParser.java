package com.sampong.tambo.vfox;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import com.sampong.tambo._common.model.InstallProgress;
import com.sampong.tambo._common.model.InstallProgress.Phase;
import com.sampong.tambo._common.service.ProgressParser;

/**
 * Reads vfox's install output. vfox draws its download bar even into a pipe, redrawing it in
 * place with carriage returns, which the process reader splits into one line per frame:
 * <pre>
 * Preinstalling nodejs@22.11.0...
 * Downloading...   7% [=>                ] (4.7 MB/s) [0s:6s]
 * Verifying checksum 9053…
 * Unpacking C:\…\node-v22.11.0-win-x64.zip...
 * </pre>
 * The bracketed pair is elapsed:remaining. Frames arrive many times a second, so unlike mise's
 * there is no need to extrapolate between them.
 * <p>
 * Calibrated against vfox 1.0.11 on Windows.
 */
@NullMarked
final class VfoxProgressParser implements ProgressParser {

    static final VfoxProgressParser INSTANCE = new VfoxProgressParser();

    private static final Pattern DOWNLOAD = Pattern.compile(
            "^Downloading\\.{3}\\s+(?<pct>\\d{1,3})%\\s*\\[[^\\]]*\\]\\s*\\((?<speed>[^)]*)\\)"
                    + "(?:\\s*\\[[^\\]:]*:(?<eta>[^\\]]*)\\])?");

    private VfoxProgressParser() {
    }

    @Override
    public @Nullable InstallProgress parse(String line) {
        String l = line.strip();
        Matcher download = DOWNLOAD.matcher(l);
        if (download.find()) {
            double fraction = Math.min(100, Integer.parseInt(download.group("pct"))) / 100.0;
            String speed = download.group("speed").strip();
            String eta = download.group("eta");
            return new InstallProgress(null, Phase.DOWNLOADING, fraction, null, null,
                    speed.isEmpty() ? null : speed, eta == null || eta.isBlank() ? null : eta.strip());
        }
        if (l.startsWith("Preinstalling ")) {
            return InstallProgress.of(null, Phase.PREPARING);
        }
        if (l.startsWith("Verifying checksum")) {
            return InstallProgress.of(null, Phase.VERIFYING);
        }
        if (l.startsWith("Unpacking ")) {
            return InstallProgress.of(null, Phase.EXTRACTING);
        }
        return null;
    }
}
