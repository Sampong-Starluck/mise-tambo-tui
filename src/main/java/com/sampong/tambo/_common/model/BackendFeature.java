package com.sampong.tambo._common.model;

/**
 * A capability a version-manager backend may or may not have. Neither backend is a superset of
 * the other — mise has tasks, env and a doctor report that vfox has no concept of, while vfox
 * has a standalone plugin registry that mise folds into its tool registry instead — so the UI
 * asks {@link com.sampong.tambo._common.service.SdkVersionBackend#supports} rather than testing
 * "is this vfox?" anywhere.
 * <p>
 * Adding a feature here is what makes it appear in the UI: every surface that depends on one
 * renders either the feature or the backend's {@code unsupportedReason} for it, so nothing
 * silently disappears the way whole panels used to under vfox.
 */
public enum BackendFeature {

    /** A task runner: listing and running project tasks. mise only. */
    TASKS,
    /** Reporting the environment variables the backend would export. mise only. */
    ENV,
    /** A health report about the backend's own installation. mise only. */
    DOCTOR,
    /** Marking a project config as trusted before it is parsed. mise only. */
    TRUST,
    /** Deleting tool versions the backend no longer considers in use. mise only. */
    PRUNE,
    /** Knowing which installed versions are outdated, and upgrading them. mise only. */
    UPGRADE,
    /** Updating the backend's own binary in place. Both, by different commands. */
    SELF_UPDATE,
    /** Registering a plugin on its own, without installing a version of it. vfox only. */
    PLUGIN_REGISTRY,
    /** Pinning a version globally as well as per project. Both. */
    GLOBAL_SCOPE,
    /** Re-parsing the active config to surface a syntax error. mise only. */
    CONFIG_VALIDATE,
    /**
     * Installing everything the project config declares in a single command
     * ({@code mise install}). mise only: vfox has no bulk form, so the auto-install flow
     * installs its tools one at a time there instead.
     */
    BULK_INSTALL,
    /** A user-level config file the app can open in its own editor. mise only. */
    GLOBAL_CONFIG,
    /**
     * Whether choosing a version can install <em>and</em> pin it in one step. mise's
     * {@code use} does both; vfox's {@code use} only switches scope and errors on a version
     * that is not installed yet, so there pinning stays a separate action afterwards. This is
     * what decides whether the version browser offers a project/global choice at all.
     */
    PIN_ON_INSTALL;

    /**
     * A short phrase naming the capability in the user's terms, for the backend report the
     * Status panel opens in the main pane. Spelled out here rather than derived from
     * {@link #name()} so it reads as a sentence fragment ("run project tasks") instead of as a
     * constant with the underscores taken out.
     */
    public String label() {
        return switch (this) {
            case TASKS -> "run project tasks";
            case ENV -> "report exported env vars";
            case DOCTOR -> "report its own health";
            case TRUST -> "trust a project config";
            case PRUNE -> "prune unused versions";
            case UPGRADE -> "find and apply upgrades";
            case SELF_UPDATE -> "update its own binary";
            case PLUGIN_REGISTRY -> "register plugins separately";
            case GLOBAL_SCOPE -> "pin a version globally";
            case CONFIG_VALIDATE -> "re-check the config for errors";
            case BULK_INSTALL -> "install everything the project config declares at once";
            case GLOBAL_CONFIG -> "edit a user-level config";
            case PIN_ON_INSTALL -> "install and pin in one step";
        };
    }
}
