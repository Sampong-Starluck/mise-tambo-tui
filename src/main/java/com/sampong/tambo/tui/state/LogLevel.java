package com.sampong.tambo.tui.state;

/** Severity/category of a {@link LogEntry} shown in the command log panel. */
public enum LogLevel {
    /** A command that was invoked, echoed before it runs. */
    CMD,
    /** Informational message (refresh started, app started, etc). */
    INFO,
    /** A command completed successfully. */
    OK,
    /** A command failed. */
    ERROR
}
