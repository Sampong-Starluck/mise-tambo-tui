package com.sampong.tambo.cli;

/** The result of running a version-manager CLI subcommand (mise, vfox, ...). */
public record CliResult(int exitCode, String stdout, String stderr) {

    public boolean ok() {
        return exitCode == 0;
    }

    /** The first non-blank line of stderr, falling back to stdout, for compact log lines. */
    public String summaryLine() {
        String source = !stderr.isBlank() ? stderr : stdout;
        for (String line : source.split("\n")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }
}
