package com.sampong.tambo._common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileWriterTest {

    @TempDir
    Path dir;

    @Test
    void createsANewFile() throws IOException {
        Path file = dir.resolve("mise.toml");

        AtomicFileWriter.writeString(file, "[tools]\n");

        assertThat(Files.readString(file)).isEqualTo("[tools]\n");
    }

    @Test
    void replacesExistingContentWholesale() throws IOException {
        Path file = dir.resolve("mise.toml");
        Files.writeString(file, "[tools]\nnode = \"22\"\npython = \"3.14\"\n");

        AtomicFileWriter.writeString(file, "[tools]\nnode = \"24\"\n");

        assertThat(Files.readString(file)).isEqualTo("[tools]\nnode = \"24\"\n");
    }

    @Test
    void createsMissingParentDirectories() throws IOException {
        Path file = dir.resolve(".config/mise/config.toml");

        AtomicFileWriter.writeString(file, "[settings]\n");

        assertThat(file).exists();
    }

    @Test
    void leavesNoTempFileBehind() throws IOException {
        Path file = dir.resolve("mise.toml");
        AtomicFileWriter.writeString(file, "a");
        AtomicFileWriter.writeString(file, "b");

        try (var files = Files.list(dir)) {
            assertThat(files).containsExactly(file);
        }
    }

    @Test
    void writesThroughASymlinkInsteadOfReplacingIt() throws IOException {
        Path real = dir.resolve("dotfiles/config.toml");
        Files.createDirectories(real.getParent());
        Files.writeString(real, "old");
        Path link = dir.resolve("config.toml");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symlinks unavailable here (Windows without Developer Mode): " + e);
        }

        AtomicFileWriter.writeString(link, "new");

        assertThat(Files.isSymbolicLink(link)).as("link survives").isTrue();
        assertThat(real).hasContent("new");
    }

    @Test
    void keepsTheOriginalPosixPermissions() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX only");
        Path file = dir.resolve("mise.toml");
        Files.writeString(file, "old");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        AtomicFileWriter.writeString(file, "new");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-r--r--");
    }
}
