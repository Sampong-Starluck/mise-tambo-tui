package com.sampong.tambo._common.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Replaces a file's contents all-or-nothing, for the config files the editor saves.
 * <p>
 * {@link Files#writeString} truncates the target before writing, so a failure partway through —
 * disk full, the process killed — leaves {@code mise.toml} empty or half-written. Writing a
 * sibling temp file and renaming it over the target means the file is only ever the old
 * contents or the new.
 */
public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    public static void writeString(Path file, String content) throws IOException {
        // Write through a symlink rather than over it: dotfile managers commonly symlink
        // ~/.config/mise/config.toml, and renaming onto the link would replace it with a
        // regular file and silently detach it from the managed copy.
        Path target = Files.exists(file) ? file.toRealPath() : file.toAbsolutePath();
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = Files.createTempFile(dir, "." + target.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temp, content);
            copyPermissions(target, temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Carries the original file's POSIX mode over to the replacement — the temp file is created
     * {@code 0600}, which would otherwise quietly tighten a {@code 0644} config. A no-op on
     * Windows and for a file that does not exist yet.
     */
    private static void copyPermissions(Path from, Path to) throws IOException {
        if (!Files.exists(from)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system.
        }
    }
}
