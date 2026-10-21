package com.sampong.tambo.config;

import tools.jackson.databind.ObjectMapper;

/**
 * Hands tests the exact {@link ObjectMapper} the app wires up, so JSON-parsing tests run with the
 * production configuration rather than a copy of it that could drift.
 */
public final class TestMappers {

    private TestMappers() {
    }

    public static ObjectMapper appMapper() {
        return new AppConfig().objectMapper();
    }
}
