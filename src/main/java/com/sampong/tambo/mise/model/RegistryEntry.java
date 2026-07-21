package com.sampong.tambo.mise.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.jspecify.annotations.Nullable;

/**
 * A single installable tool entry as reported by {@code mise registry -J}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistryEntry(
        @JsonProperty("short") String shortName,
        @Nullable List<String> backends,
        @Nullable String description,
        @Nullable List<String> aliases
) {

    public String backendSummary() {
        return (backends == null || backends.isEmpty()) ? "-" : String.join(", ", backends);
    }

    public String aliasSummary() {
        return (aliases == null || aliases.isEmpty()) ? "-" : String.join(", ", aliases);
    }
}
