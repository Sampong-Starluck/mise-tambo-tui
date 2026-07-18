package com.sampong.tambo.mise.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single installable tool entry as reported by {@code mise registry -J}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistryEntry(
        @JsonProperty("short") String shortName,
        List<String> backends,
        String description,
        List<String> aliases
) {

    public String backendSummary() {
        return (backends == null || backends.isEmpty()) ? "-" : String.join(", ", backends);
    }

    public String aliasSummary() {
        return (aliases == null || aliases.isEmpty()) ? "-" : String.join(", ", aliases);
    }
}
