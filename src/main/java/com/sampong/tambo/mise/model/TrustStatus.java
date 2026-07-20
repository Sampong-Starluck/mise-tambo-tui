package com.sampong.tambo.mise.model;

/**
 * The trust state of one config-file directory as reported by
 * {@code mise trust --show} (one line per directory: {@code <path>: trusted|untrusted}).
 */
public record TrustStatus(String path, boolean trusted) {
}
