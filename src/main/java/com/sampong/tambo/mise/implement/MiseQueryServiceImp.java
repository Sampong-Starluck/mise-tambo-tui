package com.sampong.tambo.mise.implement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sampong.tambo._common.base.Concurrently;
import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo._common.model.CatalogEntry;
import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo._common.model.OutdatedSdk;
import com.sampong.tambo._common.model.ProjectTask;
import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.model.TrustState;
import com.sampong.tambo.mise.MiseCli;
import com.sampong.tambo.mise.MiseQueryService;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Read-only queries against {@code mise}, mapped into the shared backend-neutral model.
 * <p>
 * mise's JSON never leaves this class in its own shape: each command deserializes into a wire
 * DTO below and is mapped to a {@code _common.model} record, so mise's field names
 * ({@code requested_version}, {@code short}) stay an implementation detail of the mise adapter
 * rather than leaking into the type the UI renders.
 */
@Service
@RequiredArgsConstructor
// Native image: these types are only ever reached through Jackson reflection,
// so Spring AOT must be told to keep their constructors/accessors.
@RegisterReflectionForBinding({MiseQueryServiceImp.RawVersion.class, MiseQueryServiceImp.RawSource.class,
        MiseQueryServiceImp.RawOutdated.class, MiseQueryServiceImp.RawTask.class,
        MiseQueryServiceImp.RawCatalogEntry.class})
public class MiseQueryServiceImp implements MiseQueryService {

    @NonNull
    private final MiseCli cli;
    @NonNull
    private final ObjectMapper mapper;
    /** Used to overlap the two subprocess calls {@link #listReleases} needs. */
    @Qualifier("miseTaskExecutor")
    @NonNull
    private final AsyncTaskExecutor executor;

    @Override
    public boolean offline() {
        return cli.offline();
    }

    @Override
    public List<SdkVersion> listSdks() {
        CliResult result = cli.run(List.of("ls", "-J"));
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        try {
            Map<String, List<RawVersion>> raw = mapper.readValue(
                    result.stdout(), new TypeReference<Map<String, List<RawVersion>>>() {
                    });
            if (raw == null) {
                return List.of();
            }
            List<SdkVersion> sdks = new ArrayList<>();
            for (Map.Entry<String, List<RawVersion>> entry : raw.entrySet()) {
                List<RawVersion> versions = entry.getValue();
                if (versions == null) {
                    continue;
                }
                for (RawVersion v : versions) {
                    if (cli.offline() && !v.installed) {
                        continue;
                    }
                    sdks.add(new SdkVersion(
                            entry.getKey(),
                            v.version != null ? v.version : "unknown",
                            v.requestedVersion,
                            v.installPath,
                            v.source != null ? v.source.type : null,
                            v.source != null ? v.source.path : null,
                            v.installed,
                            v.active));
                }
            }
            sdks.sort(Comparator.comparing(SdkVersion::name).thenComparing(SdkVersion::version));
            return sdks;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<OutdatedSdk> listOutdated() {
        if (cli.offline()) {
            return List.of();
        }
        CliResult result = cli.run(List.of("outdated", "-J"), Duration.ofSeconds(30));
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        try {
            Map<String, RawOutdated> raw = mapper.readValue(
                    result.stdout(), new TypeReference<Map<String, RawOutdated>>() {
                    });
            if (raw == null) {
                return List.of();
            }
            List<OutdatedSdk> outdated = new ArrayList<>();
            for (Map.Entry<String, RawOutdated> entry : raw.entrySet()) {
                RawOutdated v = entry.getValue();
                if (v == null) {
                    continue;
                }
                // `name` is usually present; fall back to the map key.
                outdated.add(new OutdatedSdk(v.name != null ? v.name : entry.getKey(), v.current, v.latest));
            }
            return outdated;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<ProjectTask> listTasks() {
        CliResult result = cli.run(List.of("tasks", "ls", "-J"));
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        try {
            List<RawTask> raw = mapper.readValue(result.stdout(), new TypeReference<List<RawTask>>() {
            });
            if (raw == null) {
                return List.of();
            }
            List<ProjectTask> tasks = new ArrayList<>();
            for (RawTask t : raw) {
                if (t == null || t.name == null) {
                    continue;
                }
                tasks.add(new ProjectTask(t.name, orEmpty(t.aliases), t.description, t.source,
                        orEmpty(t.depends), orEmpty(t.run)));
            }
            return tasks;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<CatalogEntry> listCatalog() {
        CliResult result = cli.run(List.of("registry", "-J"), Duration.ofSeconds(30));
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        try {
            List<RawCatalogEntry> raw = mapper.readValue(
                    result.stdout(), new TypeReference<List<RawCatalogEntry>>() {
                    });
            if (raw == null) {
                return List.of();
            }
            List<CatalogEntry> entries = new ArrayList<>();
            for (RawCatalogEntry e : raw) {
                if (e == null || e.shortName == null) {
                    continue;
                }
                entries.add(new CatalogEntry(e.shortName, orEmpty(e.backends), e.description, orEmpty(e.aliases)));
            }
            return entries;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public Map<String, String> listEnv() {
        CliResult result = cli.run(List.of("env", "-J"));
        if (!result.ok() || result.stdout().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> raw = mapper.readValue(result.stdout(), new TypeReference<Map<String, String>>() {
            });
            return raw != null ? new TreeMap<>(raw) : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    @Override
    public List<SdkRelease> listReleases(@NonNull String sdk) {
        // Two independent calls: what is already on disk (so the browser can mark it, the way
        // vfox's `search all` reports natively) and what is installable. The second is the
        // network one and dominates, so running them together costs no more than it alone.
        return Concurrently.both(executor,
                this::installedVersions,
                () -> cli.run(List.of("ls-remote", sdk), Duration.ofSeconds(30)),
                (installedBySdk, remote) -> toReleases(installedBySdk.getOrDefault(sdk.toLowerCase(), Set.of()), remote));
    }

    /** Installed versions per SDK short name, lowercased, for marking the version browser. */
    private Map<String, Set<String>> installedVersions() {
        Map<String, Set<String>> installed = new HashMap<>();
        for (SdkVersion v : listSdks()) {
            if (v.installed()) {
                installed.computeIfAbsent(v.name().toLowerCase(), k -> new HashSet<>()).add(v.version());
            }
        }
        return installed;
    }

    private static List<SdkRelease> toReleases(Set<String> installed, CliResult result) {
        List<SdkRelease> releases = new ArrayList<>();
        releases.add(SdkRelease.latestAlias());
        if (result.ok() && !result.stdout().isBlank()) {
            List<String> parsed = new ArrayList<>();
            for (String line : result.stdout().split("\n")) {
                if (!line.isBlank()) {
                    parsed.add(line.strip());
                }
            }
            Collections.reverse(parsed); // ls-remote lists oldest first
            for (String version : parsed) {
                releases.add(new SdkRelease(version, installed.contains(version), false));
            }
        }
        return releases;
    }

    @Override
    public BackendInfo info() {
        CliResult result = cli.run(List.of("doctor"));
        String version = "unknown";
        // `mise activate` exports MISE_SHELL / __MISE_DIFF into the launching
        // shell, and this process inherits them — the most reliable signal on
        // Windows, where doctor prints no "activated:" line at all.
        boolean activated = activatedFromEnvironment();
        boolean shimsOnPath = false;
        int configFiles = 0;
        if (result.ok() || !result.stdout().isBlank()) {
            boolean inConfigFiles = false;
            boolean inShell = false;
            for (String rawLine : result.stdout().split("\n")) {
                String line = rawLine.strip();
                if (inShell) {
                    // First indented line under "shell:" — "(unknown)" means the
                    // launching shell never ran `mise activate`.
                    inShell = false;
                    if (rawLine.startsWith(" ") && !line.isBlank() && !line.startsWith("(unknown")) {
                        activated = true;
                    }
                }
                if (line.startsWith("version:")) {
                    version = line.substring("version:".length()).strip();
                } else if (line.startsWith("activated:")) {
                    activated = activated
                            || line.substring("activated:".length()).strip().equalsIgnoreCase("yes");
                } else if (line.startsWith("MISE_SHELL=")) {
                    activated = true;
                } else if (line.equals("shell:")) {
                    inShell = true;
                } else if (line.startsWith("shims_on_path:")) {
                    shimsOnPath = line.substring("shims_on_path:".length()).strip().equalsIgnoreCase("yes");
                } else if (line.startsWith("config_files:")) {
                    inConfigFiles = true;
                } else if (inConfigFiles) {
                    if (rawLine.isBlank() || !rawLine.startsWith(" ")) {
                        inConfigFiles = false;
                    } else {
                        configFiles++;
                    }
                }
            }
        }
        return BackendInfo.health("mise", version, activated, shimsOnPath, configFiles);
    }

    private static boolean activatedFromEnvironment() {
        for (String var : new String[]{"MISE_SHELL", "__MISE_DIFF", "__MISE_SESSION"}) {
            String value = System.getenv(var);
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<TrustState> trustStatus() {
        CliResult result = cli.run(List.of("trust", "--show"));
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        List<TrustState> statuses = new ArrayList<>();
        for (String line : result.stdout().split("\n")) {
            // "<path>: trusted|untrusted" — split at the LAST colon, since Windows
            // paths contain one of their own ("C:\...").
            int sep = line.lastIndexOf(':');
            if (sep < 0) {
                continue;
            }
            String path = line.substring(0, sep).strip();
            String status = line.substring(sep + 1).strip().toLowerCase();
            if (path.isEmpty()) {
                continue;
            }
            switch (status) {
                case "trusted" -> statuses.add(new TrustState(path, true));
                case "untrusted", "ignored" -> statuses.add(new TrustState(path, false));
                default -> {
                    // not a trust line (e.g. a warning) — skip it
                }
            }
        }
        return statuses;
    }

    /** mise omits empty arrays rather than sending {@code []}, so every list field can arrive null. */
    private static List<String> orEmpty(@Nullable List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    // ==================== JSON DTOs ====================

    // Package-private (not private): ECJ rejects private nested types referenced
    // from the @RegisterReflectionForBinding annotation on the outer class.
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class RawVersion {
        public @Nullable String version;
        @JsonProperty("requested_version")
        public @Nullable String requestedVersion;
        @JsonProperty("install_path")
        public @Nullable String installPath;
        public @Nullable RawSource source;
        public boolean installed;
        public boolean active;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class RawSource {
        public @Nullable String type;
        public @Nullable String path;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class RawOutdated {
        public @Nullable String name;
        public @Nullable String current;
        public @Nullable String latest;
        @JsonProperty("requested")
        public @Nullable String requested;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class RawTask {
        public @Nullable String name;
        public @Nullable List<String> aliases;
        public @Nullable String description;
        public @Nullable String source;
        public @Nullable List<String> depends;
        public @Nullable List<String> run;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class RawCatalogEntry {
        @JsonProperty("short")
        public @Nullable String shortName;
        public @Nullable List<String> backends;
        public @Nullable String description;
        public @Nullable List<String> aliases;
    }
}
