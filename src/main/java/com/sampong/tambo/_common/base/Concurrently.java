package com.sampong.tambo._common.base;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.springframework.core.task.AsyncTaskExecutor;

/**
 * Runs two independent CLI calls at the same time and combines their answers.
 * <p>
 * Several queries need two subprocesses whose results are only combined at the end — vfox has to
 * ask {@code list} what is installed and {@code current} which version is active, and mise has to
 * ask {@code ls} what is on disk and {@code ls-remote} what could be. Run one after the other,
 * such a query costs the sum of two process launches; run together on the virtual-thread
 * executor the rest of the app already uses, it costs the slower of the two, which for anything
 * touching the network is the network call alone.
 * <p>
 * This is deliberately {@link CompletableFuture} rather than structured concurrency: JEP 505's
 * {@code StructuredTaskScope} is the natural fit and reads better, but it is still a preview API
 * in Java 25 and would need {@code --enable-preview} on both the compiler and the runtime — and
 * on the native-image build. Revisit when it finalizes.
 */
public final class Concurrently {

    private Concurrently() {
    }

    /**
     * Runs both suppliers on {@code executor} and combines them once both have answered.
     * <p>
     * Exceptions are unwrapped from the {@link CompletionException} the future would otherwise
     * wrap them in, so a failure surfaces as the {@link RuntimeException} the caller's own
     * error handling already expects rather than as a wrapper around it.
     */
    public static <A, B, R> R both(AsyncTaskExecutor executor, Supplier<A> first, Supplier<B> second,
                                   BiFunction<A, B, R> combine) {
        CompletableFuture<A> firstFuture = CompletableFuture.supplyAsync(first, executor);
        CompletableFuture<B> secondFuture = CompletableFuture.supplyAsync(second, executor);
        try {
            return combine.apply(firstFuture.join(), secondFuture.join());
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
