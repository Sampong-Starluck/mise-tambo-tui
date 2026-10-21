package com.sampong.tambo.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.concurrent.Executors;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.sampong.tambo.cli.TamboCommand;
import com.sampong.tambo.mise.implement.MiseSdkBackend;
import com.sampong.tambo.tui.MiseTuiApp;
import com.sampong.tambo.tui.features.WindowsConsoleMouse;
import com.sampong.tambo.tui.pick.PickApp;
import com.sampong.tambo.vfox.VfoxSdkBackend;

/** Spring wiring for the app: the JSON mapper, the background task executor, and the app runner. */
@Configuration
public class AppConfig {

    /**
     * Jackson mapper for parsing {@code mise ... -J} output. Backs off automatically
     * if Spring Boot's Jackson autoconfiguration already provides one.
     * <p>
     * Jackson 3 turned {@code FAIL_ON_NULL_FOR_PRIMITIVES} on by default; it is switched back
     * off so a {@code null} for one of the DTOs' {@code boolean} flags keeps reading as
     * {@code false}, as it did under Jackson 2, instead of failing the whole listing.
     */
    @Bean
    @ConditionalOnMissingBean
    ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
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

    /**
     * Starts the TUI once the context is up, or the inline version picker for {@code pick <tool>}
     * instead. Switchable off ({@code tambo.tui.enabled=false}) so a test can boot the whole context
     * to check its wiring without taking over the terminal; there is no reason to set it otherwise.
     * <p>
     * A pick exits the process with its own code rather than returning: the picker is a one-shot
     * command, and {@code System.exit} is what the rest of {@code main} would have reached anyway.
     */
    @Bean
    @ConditionalOnProperty(name = "tambo.tui.enabled", havingValue = "true", matchIfMissing = true)
    CommandLineRunner miseTui(MiseTuiApp app, TamboCommand command,
                              MiseSdkBackend miseSdkBackend, VfoxSdkBackend vfoxSdkBackend) {
        return args -> {
            // conhost's QuickEdit mode eats mouse input; clear it while the TUI runs.
            Integer previousConsoleMode = WindowsConsoleMouse.disableQuickEdit();
            Integer pickExitCode = null;
            try {
                String pickTool = command.pickTool();
                if (pickTool != null) {
                    pickExitCode = PickApp.pick(pickTool,
                            PickApp.backendFor(command, miseSdkBackend, vfoxSdkBackend));
                } else {
                    app.run();
                }
            } finally {
                WindowsConsoleMouse.restore(previousConsoleMode);
            }
            if (pickExitCode != null) {
                System.exit(pickExitCode);
            }
        };
    }
}
