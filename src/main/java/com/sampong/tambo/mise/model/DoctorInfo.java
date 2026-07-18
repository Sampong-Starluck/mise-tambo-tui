package com.sampong.tambo.mise.model;

/**
 * A small summary of {@code mise doctor} / {@code mise version}, parsed from plain text
 * since those commands don't offer a {@code --json} output.
 */
public record DoctorInfo(String version, boolean activated, boolean shimsOnPath, int configFileCount) {

    public static DoctorInfo unknown() {
        return new DoctorInfo("unknown", false, false, 0);
    }
}
