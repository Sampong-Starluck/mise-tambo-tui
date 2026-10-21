package com.sampong.tambo.testsupport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Captured CLI output under {@code src/test/resources/fixtures}, recorded from real mise 2026.9 /
 * vfox 1.0.11 runs with their output piped, exactly as tambo runs them. Kept byte-exact
 * ({@code .gitattributes} marks them {@code -text}): the bare {@code \r} redraws and ANSI
 * escapes are precisely what the parsers have to cope with.
 */
public final class Fixtures {

    /** Same pattern {@code CliProcessRunner} strips. */
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[;\\d]*[ -/]*[@-~]");

    private Fixtures() {
    }

    public static Path path(String name) {
        URL url = Fixtures.class.getResource("/fixtures/" + name);
        if (url == null) {
            throw new IllegalArgumentException("no fixture " + name);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The whole fixture as text, ANSI and all — what a non-streaming {@code run} hands a parser. */
    public static String text(String name) {
        try (InputStream in = open(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The fixture as {@code CliProcessRunner.runStreaming} delivers it to a line consumer:
     * split by {@link BufferedReader#readLine()} (so a bare {@code \r} ends a line), ANSI
     * stripped, trailing whitespace trimmed, blank lines kept.
     */
    public static List<String> streamedLines(String name) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(ANSI.matcher(line).replaceAll("").stripTrailing());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return lines;
    }

    private static InputStream open(String name) {
        InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name);
        if (in == null) {
            throw new IllegalArgumentException("no fixture " + name);
        }
        return in;
    }
}
