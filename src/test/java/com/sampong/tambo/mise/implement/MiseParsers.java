package com.sampong.tambo.mise.implement;

import com.sampong.tambo._common.service.ProgressParser;

/** Exposes the package-private mise progress parser to tests in other packages. */
public final class MiseParsers {

    private MiseParsers() {
    }

    public static ProgressParser progress() {
        return MiseProgressParser.INSTANCE;
    }
}
