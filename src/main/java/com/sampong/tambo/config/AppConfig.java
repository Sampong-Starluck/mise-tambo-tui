package com.sampong.tambo.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

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
     * Virtual-thread executor for background {@code mise} subprocess calls: each
     * blocking CLI invocation gets a cheap virtual thread instead of pinning a
     * pooled platform thread.
     */
    @Bean
    AsyncTaskExecutor miseTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("mise-");
        executor.setVirtualThreads(true);
        return executor;
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
