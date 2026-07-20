package com.sampong.tambo;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.sampong.tambo.tui.MiseTuiApp;
import com.sampong.tambo.tui.WindowsConsoleMouse;

@SpringBootApplication
@ImportRuntimeHints(TamboApplication.TamboUiResourceHints.class)
public class TamboApplication {

    /**
     * Native image: TamboUI loads its built-in key-binding sets
     * (dev/tamboui/tui/bindings/*.properties) from the classpath at runtime,
     * and the TamboUI jars ship no resource metadata for them.
     */
    static class TamboUiResourceHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            hints.resources().registerPattern("dev/tamboui/tui/bindings/*.properties");
        }
    }

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(TamboApplication.class, args)));
    }

    /**
     * Jackson mapper for parsing {@code mise ... -J} output. Backs off automatically
     * if Spring Boot's Jackson auto-configuration already provides one.
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
