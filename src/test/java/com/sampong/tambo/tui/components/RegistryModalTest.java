package com.sampong.tambo.tui.components;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.sampong.tambo.tui.components.RegistryModal.Where;

/** The Add SDK wizard's previews: what each choice will run, and what it says it will change. */
class RegistryModalTest {

    @Test
    void aBackendThatPinsOnInstallRunsOneCommandPerChoice() {
        assertThat(RegistryModal.plan("mise", true, "python@3.12.7", Where.PROJECT))
                .containsExactly("mise use python@3.12.7");
        assertThat(RegistryModal.plan("mise", true, "python@3.12.7", Where.GLOBAL))
                .containsExactly("mise use -g python@3.12.7");
        assertThat(RegistryModal.plan("mise", true, "python@3.12.7", Where.INSTALL_ONLY))
                .containsExactly("mise install python@3.12.7");
    }

    @Test
    void aBackendThatCannotPinOnInstallPreviewsBothCommands() {
        assertThat(RegistryModal.plan("vfox", false, "nodejs@22.11.0", Where.PROJECT))
                .containsExactly("vfox install nodejs@22.11.0", "vfox use -p nodejs@22.11.0");
        assertThat(RegistryModal.plan("vfox", false, "nodejs@22.11.0", Where.GLOBAL))
                .containsExactly("vfox install nodejs@22.11.0", "vfox use -g nodejs@22.11.0");
        assertThat(RegistryModal.plan("vfox", false, "nodejs@22.11.0", Where.INSTALL_ONLY))
                .containsExactly("vfox install nodejs@22.11.0");
    }

    @Test
    void eachChoiceNamesWhatItWrites() {
        assertThat(RegistryModal.consequence(Where.PROJECT, "mise.toml", null, "mise"))
                .startsWith("writes ./mise.toml");
        assertThat(RegistryModal.consequence(Where.GLOBAL, "mise.toml", Path.of("/etc/mise/config.toml"), "mise"))
                .startsWith("writes /etc/mise/config.toml");
        assertThat(RegistryModal.consequence(Where.GLOBAL, ".vfox.toml", null, "vfox"))
                .startsWith("sets vfox's global default");
        assertThat(RegistryModal.consequence(Where.INSTALL_ONLY, "mise.toml", null, "mise"))
                .contains("doesn't pin");
    }
}
