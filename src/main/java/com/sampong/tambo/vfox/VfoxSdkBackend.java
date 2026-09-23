package com.sampong.tambo.vfox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import com.sampong.tambo._common.base.Concurrently;
import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo._common.model.CatalogEntry;
import com.sampong.tambo._common.model.CliResult;
import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.service.SdkVersionBackend;

import lombok.NonNull;

/**
 * Implements {@link SdkVersionBackend} against the {@code vfox} CLI. vfox has no JSON output for
 * any command, so every query here parses plain text and maps it into the same shared model mise
 * produces from its {@code -J} JSON — that mapping is the whole point of this class, and it is
 * why no panel has to know which backend it is rendering.
 * <p>
 * The parsers are written defensively (unrecognized lines are skipped rather than throwing) since
 * the exact formatting was derived by reading vfox's Go source rather than by running the binary
 * — expect this to need a short calibration pass against a real install.
 * <p>
 * vfox supports only {@link BackendFeature#SELF_UPDATE}, {@link BackendFeature#PLUGIN_REGISTRY}
 * and {@link BackendFeature#GLOBAL_SCOPE}; everything else falls through to the interface's
 * unsupported defaults, which is what the UI renders as an explanation rather than an empty panel.
 */
@Service
public class VfoxSdkBackend implements SdkVersionBackend {

    /** vfox's actual project-scope config filename — dot-prefixed, per vfox's own convention. */
    public static final String PROJECT_CONFIG_FILE = ".vfox.toml";

    private static final Set<BackendFeature> FEATURES = EnumSet.of(
            BackendFeature.SELF_UPDATE, BackendFeature.PLUGIN_REGISTRY, BackendFeature.GLOBAL_SCOPE);

    /** vfox requires a plugin be registered before a version of it can be installed. */
    private static final Duration ADD_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CURRENT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration USE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration UNINSTALL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration UNUSE_TIMEOUT = Duration.ofSeconds(30);
    /** Same as {@link #UNINSTALL_TIMEOUT}: removing a plugin also deletes all of its installed versions. */
    private static final Duration REMOVE_PLUGIN_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration AVAILABLE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);
    /** Longer than {@link #ADD_TIMEOUT}: a user-supplied {@code --source} may be a git clone. */
    private static final Duration ADD_PLUGIN_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SELF_UPDATE_TIMEOUT = Duration.ofMinutes(5);

    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[;\\d]*[ -/]*[@-~]");
    /** Leading pterm tree-drawing characters (├─┬ └── │   etc.) plus whitespace. */
    private static final Pattern TREE_PREFIX = Pattern.compile("^[\\s│├└┬─]+");
    /** A bare version token, e.g. "21.5.0" or "v21.5.0" — distinguishes version rows from tool-name rows. */
    private static final Pattern VERSION_LIKE = Pattern.compile("^v?\\d+(\\.\\d+)*([\\-+.].*)?$");

    @NonNull
    private final VfoxCli cli;
    /** Used to overlap the two subprocess calls {@link #listSdks()} needs. */
    @NonNull
    private final AsyncTaskExecutor executor;

    public VfoxSdkBackend(@NonNull VfoxCli cli, @NonNull @Qualifier("miseTaskExecutor") AsyncTaskExecutor executor) {
        this.cli = cli;
        this.executor = executor;
    }

    // ==================== Identity ====================

    @Override
    @NullMarked
    public String name() {
        return "vfox";
    }

    @Override
    @NullMarked
    public String projectConfigFileName() {
        return PROJECT_CONFIG_FILE;
    }

    @Override
    @NullMarked
    public Set<BackendFeature> features() {
        return FEATURES;
    }

    /**
     * Written to be read by a user in an empty panel, so each one says what vfox does instead
     * rather than only what it lacks.
     */
    @Override
    @NullMarked
    public String unsupportedReason(BackendFeature feature) {
        return switch (feature) {
            case TASKS -> "vfox has no task runner. Project tasks are a mise feature — "
                    + "run tambo in a directory with a mise.toml to use them.";
            case ENV -> "vfox exports its environment through shell hooks rather than reporting it, "
                    + "so there is nothing to list here. Press A to set those hooks up.";
            case DOCTOR -> "vfox has no health-check command. The version above is what "
                    + "`vfox -v` reports.";
            case TRUST -> "vfox parses .vfox.toml without a trust step, so there is nothing "
                    + "to approve.";
            case PRUNE -> "vfox has no prune command. Remove versions you no longer want "
                    + "individually with x.";
            case UPGRADE -> "vfox does not report which SDKs are outdated. Install a newer "
                    + "version with a, then switch to it with u.";
            case CONFIG_VALIDATE -> "vfox does not expose a config parse check.";
            case BULK_INSTALL -> "vfox has no command that installs a whole .vfox.toml at once, "
                    + "so auto-install applies its tools one at a time instead.";
            case GLOBAL_CONFIG -> "vfox keeps its global state in its own data directory rather "
                    + "than an editable config file. Use g to set a global default instead.";
            default -> "vfox does not support this.";
        };
    }

    /** {@code vfox remove} deletes every installed version of the SDK along with the plugin. */
    @Override
    @NullMarked
    public String removePluginWarning(String plugin) {
        return "Remove plugin " + plugin + " and ALL its installed versions?";
    }

    /**
     * {@code vfox -v} — e.g. {@code "vfox version 1.0.11"}, trimmed to the version number. vfox
     * has no health report behind it, hence {@link BackendInfo#versionOnly}: the Status surface
     * shows "not reported" for activation and shims rather than a misleading "no".
     */
    @Override
    @NullMarked
    public BackendInfo info() {
        CliResult result = cli.run(List.of("-v"), VERSION_TIMEOUT);
        if (!result.ok() || result.stdout().isBlank()) {
            return BackendInfo.unknown("vfox");
        }
        String line = clean(result.stdout().strip());
        String prefix = "vfox version ";
        String version = line.regionMatches(true, 0, prefix, 0, prefix.length())
                ? line.substring(prefix.length()).strip()
                : line;
        return BackendInfo.versionOnly("vfox", version);
    }

    // ==================== Queries ====================

    @Override
    @NullMarked
    public List<SdkVersion> listSdks() {
        // `vfox list`'s tree marks the active version inline (unconfirmed exact wording), so
        // that's kept as a fallback signal, but `vfox current` is a purpose-built, far more
        // reliable source of truth for which version is active per tool. The two are
        // independent, so they run together rather than one after the other — this query used
        // to cost two sequential process launches for no reason.
        return Concurrently.both(executor,
                () -> cli.run(List.of("list"), LIST_TIMEOUT),
                this::currentVersions,
                this::parseSdkTree);
    }

    /** Turns `vfox list`'s tree into SDK rows, using `vfox current` to mark the active version. */
    private List<SdkVersion> parseSdkTree(CliResult result, Map<String, String> current) {
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        List<SdkVersion> sdks = new ArrayList<>();
        String currentSdk = null;
        boolean sawVersion = false;
        for (String rawLine : result.stdout().split("\n")) {
            String line = clean(rawLine);
            if (line.isEmpty() || line.equalsIgnoreCase("All installed sdk versions")) {
                continue;
            }
            String token = line.replaceAll("(?i)<[—-]+\\s*current.*$", "").strip();
            if (!VERSION_LIKE.matcher(token).matches()) {
                // Not a version, so the next SDK's node — close off the previous one first.
                addIfVersionless(sdks, currentSdk, sawVersion);
                currentSdk = token.toLowerCase();
                sawVersion = false;
            } else if (currentSdk != null) {
                sdks.add(versionRow(currentSdk, token, line, current));
                sawVersion = true;
            }
        }
        addIfVersionless(sdks, currentSdk, sawVersion);
        return sdks;
    }

    /**
     * One installed version under {@code sdk}. It is active when {@code vfox current} names it,
     * or when the tree line itself carries the {@code <— current} marker.
     */
    private static SdkVersion versionRow(String sdk, String token, String line, Map<String, String> current) {
        String version = token.startsWith("v") ? token.substring(1) : token;
        boolean active = version.equals(current.get(sdk)) || line.toLowerCase().contains("current");
        return new SdkVersion(sdk, version, null, null, null, null, true, active);
    }

    /**
     * {@code vfox add <plugin>} registers a plugin without installing any version, and
     * {@code vfox list} then prints it as a childless node ({@code ├──cmake} rather than
     * {@code ├─┬cmake}). Without this, the SDK name is simply overwritten by the next one and
     * the plugin vanishes from the Tools panel — which is every freshly added plugin, since
     * {@code add} never installs a version. Emitted with an empty version and
     * {@code installed=false}, the state {@link SdkVersion#hasVersion()} exists to describe.
     */
    private static void addIfVersionless(List<SdkVersion> sdks, @Nullable String sdk, boolean sawVersion) {
        if (sdk != null && !sawVersion) {
            sdks.add(new SdkVersion(sdk, "", null, null, null, null, false, false));
        }
    }

    /** {@code vfox current} (no args): the active version of every installed SDK, name -> version. */
    private Map<String, String> currentVersions() {
        CliResult result = cli.run(List.of("current"), CURRENT_TIMEOUT);
        if (!result.ok() || result.stdout().isBlank()) {
            return Map.of();
        }
        Map<String, String> current = new HashMap<>();
        for (String rawLine : result.stdout().split("\n")) {
            String line = clean(rawLine);
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue; // header or a "not set" line rather than a "<sdk> <version>" row
            }
            String sdk = parts[0].toLowerCase();
            String version = parts[parts.length - 1];
            current.put(sdk, version.startsWith("v") ? version.substring(1) : version);
        }
        return current;
    }

    @Override
    @NullMarked
    public List<SdkRelease> listReleases(String sdk) {
        // Plain `vfox search <sdk>` only returns a short recent-versions page; "all" is
        // required to get the full list the fuzzy-find version step is meant to browse.
        CliResult result = cli.run(List.of("search", sdk, "all"), SEARCH_TIMEOUT);
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        List<SdkRelease> releases = new ArrayList<>();
        for (String rawLine : result.stdout().split("\n")) {
            String line = clean(rawLine);
            if (!line.startsWith("-")) {
                continue; // header ("Available versions:") or anything unexpected
            }
            // Each row is "- <version> [<trailing annotations>]", e.g.
            // "24.15.0 (LTS) [npm 11.12.1] (installed)" for Node.js — everything from the
            // first space on is decoration, not part of the version token the CLI expects,
            // except that the decoration is also where vfox states what is already on disk.
            String rest = line.substring(1).strip();
            int spaceIndex = rest.indexOf(' ');
            String version = spaceIndex < 0 ? rest : rest.substring(0, spaceIndex);
            if (!version.isEmpty()) {
                boolean installed = rest.toLowerCase().contains("(installed)");
                releases.add(new SdkRelease(version, installed, false));
            }
        }
        return releases;
    }

    /**
     * {@code vfox available} prints a header ("AVAILABLE PLUGINS"), one
     * {@code  <name> <✓/✗> <homepage>} row per plugin, and a footer hint line — none of it
     * JSON. The ✓/✗ column marks whether the plugin is in vfox's official registry.
     */
    @Override
    @NullMarked
    public List<CatalogEntry> listCatalog() {
        CliResult result = cli.run(List.of("available"), AVAILABLE_TIMEOUT);
        if (!result.ok() || result.stdout().isBlank()) {
            return List.of();
        }
        List<CatalogEntry> entries = new ArrayList<>();
        for (String rawLine : result.stdout().split("\n")) {
            CatalogEntry entry = catalogEntry(clean(rawLine));
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /** One {@code <name> <✓/✗> <homepage>} row, or null for the header, the footer, or a blank line. */
    private static @Nullable CatalogEntry catalogEntry(String line) {
        if (line.isEmpty() || line.equalsIgnoreCase("AVAILABLE PLUGINS")
                || line.toLowerCase().startsWith("use ")) {
            return null; // header or the "Use 'vfox add <plugin>' to install" footer
        }
        String[] parts = line.split("\\s+", 3);
        if (parts.length < 2) {
            return null;
        }
        String origin = parts[1].contains("✓") ? "official" : "community";
        String homepage = parts.length > 2 ? parts[2].strip() : "";
        return CatalogEntry.of(parts[0], homepage.isEmpty() ? origin : origin + " — " + homepage);
    }

    // ==================== SDK operations ====================

    @Override
    @NullMarked
    public CliResult install(String sdkAtVersion, Consumer<String> onLine, String cancelKey) {
        // Registering an already-added plugin is a benign no-op/error; only the install
        // that follows determines success.
        cli.run(List.of("add", sdkName(sdkAtVersion)), ADD_TIMEOUT);
        return cli.runStreaming(List.of("install", "-y", sdkAtVersion), INSTALL_TIMEOUT, onLine, cancelKey);
    }

    @Override
    @NullMarked
    public CliResult uninstall(String sdkAtVersion) {
        return cli.run(List.of("uninstall", sdkAtVersion), UNINSTALL_TIMEOUT);
    }

    /**
     * {@code vfox unuse} takes a bare SDK name (no {@code @version}) and requires an explicit
     * scope flag, unlike mise's {@code unuse} which auto-detects where a tool is pinned.
     * Project scope only — global unpinning stays a manual {@code vfox unuse -g} for now.
     */
    @Override
    @NullMarked
    public CliResult remove(String sdkAtVersion) {
        return cli.run(List.of("unuse", "-p", sdkName(sdkAtVersion)), UNUSE_TIMEOUT);
    }

    /**
     * {@code vfox remove} prompts for confirmation, so {@code -y} is required with no TTY; it
     * goes before the name, where urfave/cli reliably parses flags (by default it stops at the
     * first positional argument). vfox deletes every installed version of the SDK along with
     * the plugin.
     */
    @Override
    @NullMarked
    public CliResult removePlugin(String plugin) {
        return cli.run(List.of("remove", "-y", plugin), REMOVE_PLUGIN_TIMEOUT);
    }

    /**
     * Unlike mise's {@code use} (which installs {@code sdk@version} if needed and pins it in
     * one step), vfox's {@code use} only switches scope — it errors if the version isn't
     * installed yet. So this registers the plugin and installs the version first, same as
     * {@link #install}, before running the actual scope switch.
     */
    @Override
    @NullMarked
    public CliResult use(String sdkAtVersion, boolean global, Consumer<String> onLine, String cancelKey) {
        cli.run(List.of("add", sdkName(sdkAtVersion)), ADD_TIMEOUT);
        CliResult installResult = cli.runStreaming(List.of("install", "-y", sdkAtVersion), INSTALL_TIMEOUT, onLine, cancelKey);
        if (!installResult.ok()) {
            return installResult;
        }
        List<String> args = List.of("use", global ? "-g" : "-p", sdkAtVersion);
        return cli.runStreaming(args, USE_TIMEOUT, onLine, cancelKey);
    }

    /**
     * Bare {@code vfox use -p|-g <sdk>@<version>} — the scope switch on its own, without the
     * {@code add}/{@code install} pair {@link #use} runs first. That is exactly what vfox's
     * {@code use} is natively (it errors on a version that is not installed yet), so this is
     * the one place the adapter does not have to make up for vfox lacking mise's install-and-pin.
     */
    @Override
    @NullMarked
    public CliResult pin(String sdkAtVersion, boolean global) {
        return cli.run(List.of("use", global ? "-g" : "-p", sdkAtVersion), USE_TIMEOUT);
    }

    /**
     * Registers a plugin standalone, without installing any version — {@code args} is passed
     * straight through to {@code vfox add} (e.g. {@code ["nodejs"]} or
     * {@code ["myplugin", "--alias", "foo", "--source", "https://…"]}), mirroring vfox's own
     * CLI syntax exactly rather than parsing flags ourselves.
     */
    @Override
    @NullMarked
    public CliResult registerPlugin(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("add");
        command.addAll(args);
        return cli.run(command, ADD_PLUGIN_TIMEOUT);
    }

    // ==================== Backend maintenance ====================

    /** {@code vfox upgrade} — updates the vfox binary itself, vfox's {@code mise self-update}. */
    @Override
    @NullMarked
    public CliResult selfUpdate(Consumer<String> onLine) {
        return cli.runStreaming(List.of("upgrade"), SELF_UPDATE_TIMEOUT, onLine, "vfox-self-update");
    }

    private static String sdkName(String sdkAtVersion) {
        return sdkAtVersion.contains("@") ? sdkAtVersion.substring(0, sdkAtVersion.indexOf('@')) : sdkAtVersion;
    }

    private static String clean(String rawLine) {
        String noAnsi = ANSI.matcher(rawLine).replaceAll("");
        return TREE_PREFIX.matcher(noAnsi).replaceFirst("").stripTrailing();
    }
}
