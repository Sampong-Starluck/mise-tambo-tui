package com.sampong.tambo.mise.implement;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.sampong.tambo._common.model.InstallProgress;
import com.sampong.tambo._common.model.InstallProgress.Phase;
import com.sampong.tambo._common.service.ProgressParser;

/**
 * Reads the status report mise prints when its output is a pipe rather than a terminal. With no
 * TTY there is no live bar; instead, every ~3 s it reprints one line per tool in flight, columns
 * separated by two or more spaces:
 * <pre>
 *   node@22.11.0  downloading  6.0s  17.4/34.2 MB · 3.4 MB/s
 *   node@22.11.0  extracting  12.0s
 * </pre>
 * preceded by an aggregate {@code mise ███░░░ 0/1 · 6.0s} line, which is noise once the UI draws
 * its own bar. The sizes and rate together give the bar a speed to advance at between those
 * sparse snapshots.
 * <p>
 * Calibrated against mise 2026.9 on Windows.
 */
final class MiseProgressParser implements ProgressParser {

    static final MiseProgressParser INSTANCE = new MiseProgressParser();

    private static final Pattern COLUMNS = Pattern.compile("\\s{2,}");
    private static final Pattern TOOL = Pattern.compile("\\S+@\\S+");
    private static final Pattern PHASE_WORD = Pattern.compile("[A-Za-z][A-Za-z ]*");
    private static final Pattern ELAPSED = Pattern.compile("\\d[\\dhms. ]*s");
    private static final Pattern TRANSFER = Pattern.compile(
            "^(?<done>[\\d.]+)\\s*(?<doneUnit>[kKMGT]?i?B)?\\s*/\\s*(?<total>[\\d.]+)\\s*(?<totalUnit>[kKMGT]?i?B)");
    private static final Pattern SPEED = Pattern.compile("(?<amount>[\\d.]+)\\s*(?<unit>[kKMGT]?i?B)/s");
    /** {@code mise ████░░░░ 0/1 · 3.0s} — block elements, then a done/total count. */
    private static final Pattern AGGREGATE_BAR = Pattern.compile("^mise\\s+[\\u2580-\\u259F]+\\s+\\d+/\\d+");

    private MiseProgressParser() {
    }

    @Override
    public @Nullable InstallProgress parse(String line) {
        String[] columns = COLUMNS.split(line.strip());
        if (columns.length < 3
                || !TOOL.matcher(columns[0]).matches()
                || !PHASE_WORD.matcher(columns[1]).matches()
                || !ELAPSED.matcher(columns[2]).matches()) {
            return null;
        }
        String tool = columns[0];
        Phase phase = phaseOf(columns[1]);
        String rest = columns.length > 3 ? columns[3] : "";
        if (rest.isEmpty()) {
            return InstallProgress.of(tool, phase);
        }

        Matcher speedMatch = SPEED.matcher(rest);
        String speed = speedMatch.find() ? speedMatch.group() : null;
        Matcher transfer = TRANSFER.matcher(rest);
        if (!transfer.find()) {
            return new InstallProgress(tool, phase, null, null, null, speed, null);
        }

        String totalUnit = transfer.group("totalUnit");
        String doneUnit = transfer.group("doneUnit") != null ? transfer.group("doneUnit") : totalUnit;
        double done = bytes(transfer.group("done"), doneUnit);
        double total = bytes(transfer.group("total"), totalUnit);
        if (!(total > 0)) {
            return new InstallProgress(tool, phase, null, null, null, speed, null);
        }
        Double rate = speed != null
                ? bytes(speedMatch.group("amount"), speedMatch.group("unit")) / total
                : null;
        return new InstallProgress(tool, phase, Math.min(1.0, done / total), rate,
                transfer.group().strip(), speed, null);
    }

    @Override
    public boolean isNoise(String line) {
        return AGGREGATE_BAR.matcher(line.strip()).find();
    }

    private static Phase phaseOf(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.contains("download")) {
            return Phase.DOWNLOADING;
        }
        if (w.contains("extract") || w.contains("unpack") || w.contains("unzip")) {
            return Phase.EXTRACTING;
        }
        if (w.contains("verif") || w.contains("checksum")) {
            return Phase.VERIFYING;
        }
        if (w.contains("resolv") || w.contains("prepar")) {
            return Phase.PREPARING;
        }
        return Phase.INSTALLING;
    }

    /** {@code 17.4} + {@code MB} → bytes. Decimal and binary prefixes both appear in the wild. */
    private static double bytes(String amount, String unit) {
        double value;
        try {
            value = Double.parseDouble(amount);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        boolean binary = unit.contains("i");
        double base = binary ? 1024 : 1000;
        return switch (Character.toUpperCase(unit.charAt(0))) {
            case 'K' -> value * base;
            case 'M' -> value * base * base;
            case 'G' -> value * base * base * base;
            case 'T' -> value * base * base * base * base;
            default -> value;
        };
    }
}
