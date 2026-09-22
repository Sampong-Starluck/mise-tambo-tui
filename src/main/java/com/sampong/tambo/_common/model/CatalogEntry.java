package com.sampong.tambo._common.model;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One entry in a backend's catalog of installable SDKs — mise's registry, vfox's plugin list.
 * <p>
 * Carries no JSON annotations: mise deserializes its {@code registry -J} output into a wire DTO
 * and maps it here, the same way it already did for {@code ls -J}, so this shape stays the
 * shared one rather than mise's wire format wearing a neutral name.
 *
 * @param name        the short name the CLI accepts, e.g. {@code node}
 * @param backends    the install methods behind it ({@code core}, {@code asdf}, {@code npm}, …);
 *                    empty for vfox, which has one plugin mechanism and so nothing to report
 * @param description one line about the SDK, when the backend offers one
 * @param aliases     other names that resolve to the same SDK
 */
public record CatalogEntry(
        String name,
        List<String> backends,
        @Nullable String description,
        List<String> aliases
) {

    /** An entry known only by name — all vfox entries, and any mise entry with a sparse record. */
    public static CatalogEntry of(String name, @Nullable String description) {
        return new CatalogEntry(name, List.of(), description, List.of());
    }

    public String backendSummary() {
        return backends.isEmpty() ? "-" : String.join(", ", backends);
    }

    public String aliasSummary() {
        return aliases.isEmpty() ? "-" : String.join(", ", aliases);
    }

    /** The text the fuzzy finder matches against beyond the name itself. */
    public String searchableDetail() {
        return (description == null ? "" : description) + " " + backendSummary();
    }
}
