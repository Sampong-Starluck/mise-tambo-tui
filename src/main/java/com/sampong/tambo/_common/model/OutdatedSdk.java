package com.sampong.tambo._common.model;

import org.jspecify.annotations.Nullable;

/**
 * An installed SDK that has a newer version available. Only produced by backends that support
 * {@link BackendFeature#UPGRADE}.
 *
 * @param name    the SDK short name
 * @param current the version currently in use, or null when the backend does not say
 * @param latest  the newer version available
 */
public record OutdatedSdk(String name, @Nullable String current, @Nullable String latest) {
}
