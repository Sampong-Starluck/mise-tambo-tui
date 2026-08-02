package com.sampong.tambo.sdk;

import java.util.List;
import java.util.function.Consumer;

import com.sampong.tambo.cli.CliResult;
import com.sampong.tambo.mise.model.RegistryEntry;
import com.sampong.tambo.mise.model.ToolVersion;

/**
 * The narrow slice of SDK version management ({@code mise}'s tool install/use/list/registry)
 * that {@link com.sampong.tambo.vfox.VfoxSdkBackend} can also implement as a lighter
 * alternative. Everything else in the app (tasks, doctor, trust, prune, upgrade, self-update,
 * env, config editing) has no vfox equivalent and stays wired directly to the mise services.
 */
public interface SdkVersionBackend {

    /** Short backend name ("mise" or "vfox") used in command-log lines. */
    String name();

    /** Installed/configured tool versions. */
    List<ToolVersion> listTools();

    /** Installable versions of a tool, newest first. */
    List<String> listRemoteVersions(String tool);

    /** Every SDK/plugin this backend can install, for the "Add SDK" registry browser. */
    List<RegistryEntry> listAvailable();

    /** Installs {@code tool@version}, streaming progress through {@code onLine}. */
    CliResult install(String toolAtVersion, Consumer<String> onLine, String cancelKey);

    /** Uninstalls an installed {@code tool@version}. */
    CliResult uninstall(String toolAtVersion);

    /**
     * Unpins {@code tool[@version]} from whichever scope (project or global) currently
     * declares it, without necessarily deleting the installed version.
     */
    CliResult remove(String toolAtVersion);

    /**
     * Pins {@code tool@version} at project scope (installing it if needed), or global scope
     * when {@code global} is true. Streams progress through {@code onLine}.
     */
    CliResult use(String toolAtVersion, boolean global, Consumer<String> onLine, String cancelKey);
}
