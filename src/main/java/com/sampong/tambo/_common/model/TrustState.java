package com.sampong.tambo._common.model;

/**
 * The trust state of one config directory. Only produced by backends that support
 * {@link BackendFeature#TRUST}.
 *
 * @param path    the config directory the backend reported on
 * @param trusted whether it is allowed to be parsed
 */
public record TrustState(String path, boolean trusted) {
}
