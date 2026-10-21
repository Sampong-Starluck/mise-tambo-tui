package com.sampong.tambo.testsupport;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in version-manager binary for process tests: launched as a real subprocess through
 * the running JVM's own {@code java} executable, so tests exercise genuine pipes, exit codes,
 * timeouts and kills on every OS without needing mise or vfox installed.
 * <p>
 * Usage: {@code FakeCli <mode> [args]}:
 * <ul>
 *   <li>{@code cat <file>} — copies the file's raw bytes to stdout</li>
 *   <li>{@code exit <code> <stdout> <stderr>} — prints both and exits with {@code code}</li>
 *   <li>{@code sleep <millis>} — prints {@code started} then sleeps</li>
 * </ul>
 */
public final class FakeCli {

    private FakeCli() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "cat" -> {
                System.out.write(Files.readAllBytes(Path.of(args[1])));
                System.out.flush();
            }
            case "exit" -> {
                System.out.print(args[2]);
                System.err.print(args[3]);
                System.out.flush();
                System.err.flush();
                System.exit(Integer.parseInt(args[1]));
            }
            case "sleep" -> {
                System.out.println("started");
                System.out.flush();
                Thread.sleep(Long.parseLong(args[1]));
            }
            default -> throw new IllegalArgumentException("unknown mode " + args[0]);
        }
    }

    /** The running JVM's {@code java} executable — the "binary" a runner under test launches. */
    public static String javaBinary() {
        return ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    /** Arguments that make {@link #javaBinary()} run this class in {@code mode}. */
    public static List<String> args(String mode, String... rest) {
        List<String> args = new ArrayList<>(List.of("-cp", classpath(), FakeCli.class.getName(), mode));
        args.addAll(List.of(rest));
        return args;
    }

    /** Starts this class as a bare process, for tests that need a {@link Process} handle. */
    public static Process start(String mode, String... rest) throws IOException {
        List<String> command = new ArrayList<>(List.of(javaBinary()));
        command.addAll(args(mode, rest));
        return new ProcessBuilder(command).start();
    }

    private static String classpath() {
        try {
            return Path.of(FakeCli.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
