package com.sampong.tambo.mise;

import java.util.List;
import java.util.Map;

import com.sampong.tambo._common.model.BackendInfo;
import com.sampong.tambo._common.model.CatalogEntry;
import com.sampong.tambo._common.model.OutdatedSdk;
import com.sampong.tambo._common.model.ProjectTask;
import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo._common.model.TrustState;

/**
 * Read-only queries against {@code mise}: turns raw CLI output (mostly {@code -J} JSON) into the
 * shared backend-neutral model in {@code _common.model}. Never mutates any mise state — mutating
 * operations live in {@link MiseToolService} and {@link MiseMaintenanceService}.
 * <p>
 * Nothing here returns a mise-shaped type any more. mise's JSON is deserialized into wire DTOs
 * private to the implementation and mapped across, so this service and
 * {@link com.sampong.tambo.vfox.VfoxSdkBackend} hand the UI the same records — which is what
 * lets the UI stop asking which backend it is talking to.
 */
public interface MiseQueryService {

    /** True when the app was launched with {@code --offline}: no query here may touch the network. */
    boolean offline();

    /**
     * Installed/configured SDK versions via {@code mise ls -J}. In offline mode, excludes
     * configured-but-not-yet-installed entries — nothing offline can install them anyway.
     */
    List<SdkVersion> listSdks();

    /**
     * SDKs with a newer version available, via {@code mise outdated -J}. Empty when everything
     * is current, the command is unavailable, or the app is offline (checking needs the network).
     */
    List<OutdatedSdk> listOutdated();

    List<ProjectTask> listTasks();

    List<CatalogEntry> listCatalog();

    Map<String, String> listEnv();

    /**
     * The installable versions of an SDK via {@code mise ls-remote <sdk>}, newest first, always
     * led by mise's synthetic {@code latest} entry. Versions already on disk are marked as such
     * from what {@code mise ls -J} reports.
     */
    List<SdkRelease> listReleases(String sdk);

    /** Version plus the health summary parsed out of {@code mise doctor}'s plain-text output. */
    BackendInfo info();

    /**
     * The trust state of every config directory {@code mise trust --show} reports for the
     * working directory and its parents. Empty when mise is unavailable or unparseable.
     */
    List<TrustState> trustStatus();
}
