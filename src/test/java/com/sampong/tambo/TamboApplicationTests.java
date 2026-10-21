package com.sampong.tambo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.AsyncTaskExecutor;

import com.sampong.tambo._common.base.CancelRegistry;
import com.sampong.tambo.mise.implement.MiseSdkBackend;
import com.sampong.tambo.tui.MiseTuiApp;
import com.sampong.tambo.vfox.VfoxSdkBackend;

import tools.jackson.databind.ObjectMapper;

/**
 * Boots the whole Spring context — eagerly, so every bean is actually constructed rather than
 * left to lazy initialization — to catch wiring mistakes. The TUI runner is switched off: it
 * would take over the terminal, which a test run does not have. Logging goes under
 * {@code target/} rather than the user's real {@code ~/.config/tambo}.
 */
@SpringBootTest(properties = {
        "tambo.tui.enabled=false",
        "spring.main.lazy-initialization=false",
        "logging.file.name=target/test-logs/tambo.log",
})
class TamboApplicationTests {

    @Autowired
    ApplicationContext context;

    @Test
    void everyBeanWiresUp() {
        assertThat(context.getBean(MiseTuiApp.class)).isNotNull();
        assertThat(context.getBean(MiseSdkBackend.class).name()).isEqualTo("mise");
        assertThat(context.getBean(VfoxSdkBackend.class).name()).isEqualTo("vfox");
        assertThat(context.getBean(CancelRegistry.class)).isNotNull();
    }

    @Test
    void theAppUsesItsOwnJackson3Mapper() {
        assertThat(context.getBeansOfType(ObjectMapper.class)).hasSize(1);
    }

    /** Injection by type relies on there being exactly one executor (see AppConfig). */
    @Test
    void thereIsExactlyOneTaskExecutor() {
        assertThat(context.getBeansOfType(AsyncTaskExecutor.class)).containsOnlyKeys("miseTaskExecutor");
    }

    @Test
    void theTuiRunnerStaysOffInTests() {
        assertThat(context.getBeansOfType(CommandLineRunner.class)).isEmpty();
    }
}
