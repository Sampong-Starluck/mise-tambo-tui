package com.sampong.tambo.mise.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.jspecify.annotations.Nullable;

/**
 * A task entry as reported by {@code mise tasks ls -J}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MiseTask(
        String name,
        @Nullable List<String> aliases,
        @Nullable String description,
        @Nullable String source,
        @Nullable List<String> depends,
        @Nullable List<String> run
) {

    public String aliasSummary() {
        return (aliases == null || aliases.isEmpty()) ? "-" : String.join(", ", aliases);
    }

    public String dependsSummary() {
        return (depends == null || depends.isEmpty()) ? "-" : String.join(", ", depends);
    }

    public String runSummary() {
        return (run == null || run.isEmpty()) ? "-" : String.join("  &&  ", run);
    }
}
