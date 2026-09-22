package com.sampong.tambo.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.sampong.tambo.tui.MiseTuiApp;
import com.sampong.tambo.tui.features.WindowsConsoleMouse;

/** Spring wiring for the app: the JSON mapper, the background task executor, and the app runner. */
@Configuration
public class AppConfig {

    /**
     * Jackson mapper for parsing {@code mise ... -J} output. Backs off automatically
     * if Spring Boot's Jackson autoconfiguration already provides one.
     */
    @Bean
    @ConditionalOnMissingBean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Virtual-thread executor for every background subprocess call: each blocking CLI
     * invocation, and each of the two stream readers it needs, gets a cheap virtual thread
     * instead of pinning a pooled platform thread.
     * <p>
     * {@link Executors#newVirtualThreadPerTaskExecutor()} rather than a
     * {@code SimpleAsyncTaskExecutor} with virtual threads switched on: the work here is
     * entirely blocking I/O on subprocess pipes, so there is nothing for that class's
     * concurrency-limit gate and lifecycle bookkeeping to do, and the JDK executor is the
     * direct expression of one virtual thread per blocking call.
     * <p>
     * Deliberately unbounded. A concurrency cap would only queue subprocess launches behind
     * each other, which is the exact latency this exists to avoid — and the real limit is the
     * user, who can start a handful of installs at most.
     * <p>
     * Being an {@code Executor} bean, this also suppresses Spring Boot's auto-configured
     * {@code applicationTaskExecutor} (which backs off on {@code @ConditionalOnMissingBean}),
     * so there is exactly one executor in the context and injection by type is unambiguous.
     */
    @Bean
    AsyncTaskExecutor miseTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }

    @Bean
    CommandLineRunner miseTui(MiseTuiApp app) {
        return args -> {
            // conhost's QuickEdit mode eats mouse input; clear it while the TUI runs.
            Integer previousConsoleMode = WindowsConsoleMouse.disableQuickEdit();
            try {
                app.run();
            } finally {
                WindowsConsoleMouse.restore(previousConsoleMode);
            }
        };
    }
}
