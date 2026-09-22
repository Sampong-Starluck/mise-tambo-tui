package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textInput;

import java.util.*;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;

import com.sampong.tambo._common.model.BackendFeature;
import com.sampong.tambo._common.model.CatalogEntry;
import com.sampong.tambo._common.model.SdkRelease;
import com.sampong.tambo._common.model.SdkVersion;
import com.sampong.tambo.tui.features.Fuzzy;
import com.sampong.tambo.tui.state.Lazy;
import com.sampong.tambo.tui.state.PanelIds;
import com.sampong.tambo.tui.state.UiContext;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The "Add SDK" modal: step 1 fuzzy-finds a tool by typing into a real input box, step 2
 * fuzzy-finds the version the same way. Esc steps back.
 * <p>
 * Both steps are the same for either backend; what differs is declared, not branched on.
 * A backend with its own plugin registry (BackendFeature.PLUGIN_REGISTRY) already has a
 * separate flow for registering plugins, so step 1 here is scoped to SDKs already added and
 * this modal means "install another version of one you have"; a backend without one browses
 * its whole catalog, since that is the only way in. Step 2 then either installs and pins in
 * one go (BackendFeature.PIN_ON_INSTALL, with Ctrl+G choosing the scope) or installs only,
 * leaving pinning to the Tools panel.
 * <p>
 * Owns all of its own state — the rest of the app only asks {@link #isOpen()}.
 */
@RequiredArgsConstructor
public final class RegistryModal {

    private static final int VISIBLE_ROWS = 12;
    private static final int WIDTH = 72;

    private enum Step { TOOL, VERSION }

    @NonNull
    private final UiContext ctx;

    @Getter
    private boolean open;
    private Step step = Step.TOOL;
    private final TextInputState search = new TextInputState();
    private String lastQuery = "";
    private int index;
    private @Nullable CatalogEntry tool;
    private List<SdkRelease> releases = List.of();
    private boolean versionsLoading;
    private boolean installGlobal;
    private @Nullable String preOpenFocus;

    public void open() {
        // The catalog (mise's ~200 KB of JSON, or vfox's `available` listing) is fetched here
        // on first open rather than at startup — a session that never adds an SDK never pays for
        // it at all. Reopening after a failed fetch is the user asking to try again; a loaded
        // catalog is reused, since nothing done locally changes what is installable.
        ctx.state().catalogLazy().retryIfFailed();
        ctx.actions().ensureCatalog();
        preOpenFocus = ctx.focusedId();
        open = true;
        step = Step.TOOL;
        search.clear();
        lastQuery = "";
        index = 0;
        tool = null;
        releases = List.of();
        versionsLoading = false;
        installGlobal = false;
        ctx.focus(PanelIds.MODAL_INPUT);
    }

    public void close() {
        open = false;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    /** The context-sensitive hint line the footer shows while the modal is open. */
    public String footerHint() {
        if (step == Step.TOOL) {
            return "type to fuzzy find   ↑/↓ select   enter choose " + (scopedToAdded() ? "plugin" : "sdk")
                    + "   esc close";
        }
        return "type to fuzzy find   ↑/↓ select   enter install"
                + (pinsOnInstall() ? "   ctrl+g local/global" : "") + "   esc back";
    }

    /**
     * True when step 1 lists only SDKs already added rather than the whole catalog — the case
     * for a backend whose catalog is reached through its own plugin-registry flow instead.
     */
    private boolean scopedToAdded() {
        return ctx.supports(BackendFeature.PLUGIN_REGISTRY);
    }

    /** True when choosing a version can pin it as well as install it. */
    private boolean pinsOnInstall() {
        return ctx.supports(BackendFeature.PIN_ON_INSTALL);
    }

    // ==================== Rendering ====================

    public Element build() {
        String query = search.text();
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            index = 0;
        }

        List<Element> content = new ArrayList<>();
        if (step == Step.TOOL) {
            buildToolStep(content, query);
        } else {
            buildVersionStep(content, query);
        }

        content.add(text(""));
        content.add(text(step == Step.TOOL
                ? "enter choose " + (scopedToAdded() ? "plugin" : "SDK") + "   esc close"
                : "enter install" + (pinsOnInstall() ? "   ctrl+g toggle local/global" : "") + "   esc back")
                .dim());

        String title = scopedToAdded()
                ? "Add SDK — added plugins (" + addedSdks().size() + ")"
                : "Add SDK — catalog (" + ctx.state().catalog().size() + ")";
        return dialog(title, content.toArray(new Element[0]))
                .rounded().borderColor(Color.CYAN).width(WIDTH);
    }

    private void buildToolStep(List<Element> content, String query) {
        List<CatalogEntry> matches = fuzzySdks(query);
        index = Ui.clamp(index, matches.size());

        content.add(searchInputRow("Search SDK", scopedToAdded()
                ? "type to fuzzy find an added plugin"
                : "type to fuzzy find, e.g. \"node\" or \"jdk\""));
        content.add(text(""));
        if (scopedToAdded()) {
            if (addedSdks().isEmpty()) {
                content.add(text("No plugins added yet — press P to add one from the catalog").dim());
            } else if (matches.isEmpty()) {
                content.add(text("No plugin matches \"" + query + "\"").dim());
            } else {
                addWindowedRows(content, matches.size(), i -> toolRow(matches, i));
            }
        } else if (ctx.state().catalog().isEmpty()) {
            Lazy<List<CatalogEntry>> catalog = ctx.state().catalogLazy();
            content.add(text(catalog.everLoaded() || catalog.failed()
                    ? "Catalog unavailable"
                    : "Loading catalog…").dim());
        } else if (matches.isEmpty()) {
            content.add(text("No SDK matches \"" + query + "\"").dim());
        } else {
            addWindowedRows(content, matches.size(), i -> toolRow(matches, i));
        }
    }

    private Element toolRow(List<CatalogEntry> matches, int i) {
        CatalogEntry e = matches.get(i);
        boolean sel = i == index;
        return row(
                text(sel ? "> " : "  ").fg(Color.CYAN).bold(),
                sel ? text(e.name()).bold().cyan() : text(e.name()).bold(),
                spacer(),
                text(Ui.truncate(Ui.nullToDash(e.description()), 40) + " ").dim()
        );
    }

    private void buildVersionStep(List<Element> content, String query) {
        List<SdkRelease> matches = matchingReleases(query);
        index = Ui.clamp(index, matches.size());

        assert tool != null;
        if (!pinsOnInstall()) {
            // Selecting a version here only installs it (see confirmVersion) — this backend's
            // install carries no scope, so there is nothing to toggle.
            content.add(row(
                    text("Plugin ").dim(),
                    text(tool.name()).bold().cyan()
            ));
        } else {
            content.add(row(
                    text("SDK ").dim(),
                    text(tool.name()).bold().cyan(),
                    spacer(),
                    text("target: ").dim(),
                    installGlobal ? text("global (ctrl+g)").yellow() : text("this directory (ctrl+g)").green()
            ));
        }
        content.add(searchInputRow("Search version", "type to fuzzy find a version"));
        content.add(text(""));
        if (versionsLoading) {
            content.add(text("Fetching versions of " + tool.name() + " from "
                    + ctx.backend().name() + "…").dim());
        } else if (matches.isEmpty()) {
            content.add(text("No version matches \"" + query + "\"").dim());
        } else {
            addWindowedRows(content, matches.size(), i -> {
                SdkRelease r = matches.get(i);
                boolean sel = i == index;
                String label = r.latest() ? r.version() + "  (newest)" : r.version();
                return row(
                        text(sel ? "> " : "  ").fg(Color.CYAN).bold(),
                        sel ? text(label).bold().cyan() : text(label),
                        spacer(),
                        r.installed() ? text("installed ").fg(Color.GREEN).dim() : text("")
                );
            });
        }
    }

    /** Fuzzy-matches the fetched releases by version string. */
    private List<SdkRelease> matchingReleases(String query) {
        return Fuzzy.filter(query, releases, SdkRelease::version, null);
    }

    /** The typed input box shared by both steps; owns all modal key handling. */
    private Element searchInputRow(String label, String placeholder) {
        return row(
                text(label + " ").dim().constraint(length(15)),
                textInput(search)
                        .placeholder(placeholder)
                        .placeholderColor(Color.DARK_GRAY)
                        .id(PanelIds.MODAL_INPUT)
                        .focusable(true)
                        .onKeyEvent(this::handleKey)
        );
    }

    /** Renders a window of VISIBLE_ROWS rows that follows the selection. */
    private void addWindowedRows(List<Element> content, int total, IntFunction<Element> rowAt) {
        int start = Math.max(0, index - VISIBLE_ROWS + 1);
        int end = Math.min(total, start + VISIBLE_ROWS);
        for (int i = start; i < end; i++) {
            content.add(rowAt.apply(i));
        }
        int hidden = total - (end - start);
        content.add(hidden > 0 ? text("… " + hidden + " more (keep typing to narrow)").dim() : text(""));
    }

    private List<CatalogEntry> fuzzySdks(String query) {
        // Match on the name first, then fall back to the description and install methods, so
        // typing one of those (e.g. "cargo", "npm", "ubi") narrows the list too. A backend that
        // reports no install methods contributes a harmless "-" to that second field.
        List<CatalogEntry> source = scopedToAdded() ? addedSdks() : ctx.state().catalog();
        return Fuzzy.filter(query, source, CatalogEntry::name, CatalogEntry::searchableDetail);
    }

    /**
     * The SDKs the user already has, derived from the installed listing rather than the full
     * catalog. Enriches each with the catalog's description when one is available (the catalog
     * may not have loaded yet, or the SDK may not be in it at all — either way that is
     * cosmetic, so a bare entry is a fine fallback).
     */
    private List<CatalogEntry> addedSdks() {
        Map<String, CatalogEntry> byName = ctx.state().catalog().stream()
                .collect(Collectors.toMap(e -> e.name().toLowerCase(Locale.ROOT), e -> e, (a, b) -> a));
        return ctx.state().sdks().stream()
                .map(SdkVersion::name)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(name -> byName.getOrDefault(name.toLowerCase(Locale.ROOT),
                        CatalogEntry.of(name, null)))
                .toList();
    }

    // ==================== Key handling ====================

    /**
     * Handles the keys the input box itself doesn't consume: list navigation,
     * Enter (choose/install), Ctrl+G (local/global), and Escape (back/close).
     * Everything else is swallowed so no global shortcut fires under the modal.
     */
    private EventResult handleKey(KeyEvent event) {
        int total = step == Step.TOOL
                ? fuzzySdks(search.text()).size()
                : matchingReleases(search.text()).size();

        if (event.isCancel()) {
            if (step == Step.VERSION) {
                backToToolStep();
            } else {
                close();
            }
            return EventResult.HANDLED;
        }
        if (event.isConfirm()) {
            confirm();
            return EventResult.HANDLED;
        }
        if (event.code() == KeyCode.UP) {
            index = Ui.clamp(index - 1, total);
            return EventResult.HANDLED;
        }
        if (event.code() == KeyCode.DOWN) {
            index = Ui.clamp(index + 1, total);
            return EventResult.HANDLED;
        }
        if (event.code() == KeyCode.PAGE_UP) {
            index = Ui.clamp(index - VISIBLE_ROWS, total);
            return EventResult.HANDLED;
        }
        if (event.code() == KeyCode.PAGE_DOWN) {
            index = Ui.clamp(index + VISIBLE_ROWS, total);
            return EventResult.HANDLED;
        }
        if (event.hasCtrl() && event.isCharIgnoreCase('g')) {
            installGlobal = !installGlobal;
            return EventResult.HANDLED;
        }
        // Swallow everything else (Tab, stray chars with modifiers, …) — the modal is modal.
        return EventResult.HANDLED;
    }

    private void backToToolStep() {
        step = Step.TOOL;
        search.clear();
        lastQuery = "";
        index = 0;
        releases = List.of();
        versionsLoading = false;
    }

    private void confirm() {
        if (step == Step.TOOL) {
            confirmTool();
        } else {
            confirmVersion();
        }
    }

    private void confirmTool() {
        List<CatalogEntry> matches = fuzzySdks(search.text());
        if (matches.isEmpty()) {
            return;
        }
        enterVersionStep(matches.get(Ui.clamp(index, matches.size())));
    }

    /**
     * Opens straight at the version step for an already-registered SDK, skipping the
     * plugin-picking step — the Tools panel's "install" action for a plugin that has no
     * version installed yet. The catalog is still fetched, since {@link #build()} reads it,
     * but the entry is synthesised from the name so this works even for a plugin added by
     * {@code --source} that the catalog has never heard of.
     */
    public void openAtVersion(String toolName) {
        open();
        enterVersionStep(CatalogEntry.of(toolName, null));
    }

    private void enterVersionStep(CatalogEntry entry) {
        tool = entry;
        step = Step.VERSION;
        search.clear();
        lastQuery = "";
        index = 0;
        releases = List.of();
        versionsLoading = true;

        String toolName = tool.name();
        ctx.actions().fetchReleases(toolName, fetched -> {
            // Ignore stale responses if the user already left the version step.
            if (open && step == Step.VERSION && tool != null && toolName.equals(tool.name())) {
                releases = fetched;
                versionsLoading = false;
            }
        });
    }

    private void confirmVersion() {
        if (versionsLoading) {
            return;
        }
        List<SdkRelease> matches = matchingReleases(search.text());
        if (matches.isEmpty()) {
            return;
        }
        String version = matches.get(Ui.clamp(index, matches.size())).version();
        assert tool != null;
        String shortName = tool.name();
        close();
        if (pinsOnInstall()) {
            ctx.actions().useSdk(shortName + "@" + version, installGlobal);
        } else {
            // This backend installs without pinning — pinning (project/global "use") stays a
            // separate step in the Tools panel (u / g), same as any other install.
            ctx.actions().installSdk(new SdkVersion(shortName, version, null, null, null, null, false, false));
        }
    }
}
