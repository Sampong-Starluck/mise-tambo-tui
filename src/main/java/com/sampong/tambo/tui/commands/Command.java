package com.sampong.tambo.tui.commands;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * One thing the user can do, described once so every surface that offers it agrees on its name
 * and its key: the Ctrl+P palette lists it with {@link #key} as a chip, the Space menu shows it
 * under {@link #group} when it has a {@link #leaderKey}, and the beginner menu bar puts it in the
 * menu named by its group.
 *
 * @param id        stable identifier, e.g. {@code "tools.upgrade"}; never shown
 * @param title     what the palette and menus print, e.g. {@code "Upgrade all outdated (3)"}
 * @param group     the heading it is listed under
 * @param key       the direct key that runs it without any menu, as printed in a key chip
 *                  ({@code "U"}, {@code "Ctrl+O"}, {@code "Space u"}); empty when it has none
 * @param leaderKey the second key after Space that runs it from the Space menu, or null when the
 *                  Space menu does not list it
 * @param asks      true when running it opens a confirmation first — marked ⚠ in the Space menu
 * @param action    what it does; runs on the render thread
 */
public record Command(
        @NonNull String id,
        @NonNull String title,
        @NonNull Group group,
        @NonNull String key,
        @Nullable Character leaderKey,
        boolean asks,
        @NonNull Runnable action) {

    /** Headings commands are grouped under, in the order the Space menu and menu bar show them. */
    public enum Group {
        TOOLS("Tools"),
        PROJECT("Project"),
        UPDATES("Updates"),
        CLEANUP("Clean-up"),
        VIEW("View"),
        APP("App");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Runs the command. */
    public void run() {
        action.run();
    }
}
