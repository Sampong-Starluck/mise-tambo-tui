package com.sampong.tambo.tui;

/** A single line in the command log panel. */
public record LogEntry(LogLevel level, String text) {
}
