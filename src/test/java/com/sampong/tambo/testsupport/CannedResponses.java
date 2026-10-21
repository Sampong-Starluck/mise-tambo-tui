package com.sampong.tambo.testsupport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.sampong.tambo._common.model.CliResult;

/**
 * The scripted half of {@link FakeMiseCli} / {@link FakeVfoxCli}: canned output per argument
 * list, plus a record of every call made. An unscripted call fails loudly in the result, so a
 * test never passes by accident on a command it did not expect.
 */
public final class CannedResponses {

    private final Map<List<String>, CliResult> responses = new HashMap<>();
    private final List<List<String>> calls = new CopyOnWriteArrayList<>();

    /** Scripts a successful run of {@code args} that prints {@code stdout}. */
    public void ok(String stdout, String... args) {
        responses.put(List.of(args), new CliResult(0, stdout, ""));
    }

    public void respond(CliResult result, String... args) {
        responses.put(List.of(args), result);
    }

    public CliResult answer(List<String> args) {
        calls.add(List.copyOf(args));
        return responses.getOrDefault(List.copyOf(args), new CliResult(127, "", "unscripted call: " + args));
    }

    public List<List<String>> calls() {
        return calls;
    }

    /**
     * Answers a streaming call: feeds the canned stdout to {@code onLine} the way
     * {@code CliProcessRunner.runStreaming} does — split on {@code \n}, {@code \r\n} and bare
     * {@code \r}, ANSI stripped, trailing whitespace trimmed — then returns the result.
     */
    public CliResult stream(List<String> args, Consumer<String> onLine) {
        CliResult result = answer(args);
        result.stdout().lines()
                .map(line -> ANSI.matcher(line).replaceAll("").stripTrailing())
                .forEach(onLine);
        return result;
    }

    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[;\\d]*[ -/]*[@-~]");
}
