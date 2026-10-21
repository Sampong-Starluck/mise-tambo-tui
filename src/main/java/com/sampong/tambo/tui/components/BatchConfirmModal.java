package com.sampong.tambo.tui.components;

import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spacer;
import static dev.tamboui.toolkit.Toolkit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

import com.sampong.tambo.tui.state.UiContext;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A confirmation for a batch of changes (upgrade three tools, uninstall two), listing each row
 * before it runs. The default answer is No: Enter, Esc and {@code n} all cancel, and only an
 * explicit {@code y} runs the batch. A batch is easy to trigger by accident from the marked-row
 * keys, so the safe answer is the one a stray Enter gives.
 * <p>
 * A row whose major version changes is flagged in red, since a major bump is the one upgrade a
 * user most often wants to look at before it lands.
 * <p>
 * Owns all of its own state — the rest of the app only asks {@link #isOpen()}. Like
 * {@link ConfirmModal} it has no focusable input, so it consumes keys through the global handler.
 */
@RequiredArgsConstructor
public final class BatchConfirmModal implements Modal {

    private static final int WIDTH = 76;
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\D*(\\d+)");

    /**
     * One line of the batch. {@code from} and {@code to} are versions; both are empty for an
     * operation with no version change (an uninstall), in which case the row shows the tool and
     * {@code where} only.
     */
    public record Item(String tool, String from, String to, String where) {
    }

    @NonNull
    private final UiContext ctx;

    @Getter
    private boolean open;
    private String title = "";
    private List<Item> items = List.of();
    private String note = "";
    private @Nullable Runnable onConfirm;
    private @Nullable String preOpenFocus;

    /**
     * Opens the dialog. {@code title} starts with the verb, which labels the confirm button
     * ("Upgrade 3 tools?" gives "y Upgrade 3"). {@code onConfirm} runs only on an explicit {@code y}.
     */
    public void open(@NonNull String title, @NonNull List<Item> items, @NonNull String note,
                     @NonNull Runnable onConfirm) {
        this.title = title;
        this.items = List.copyOf(items);
        this.note = note;
        this.onConfirm = onConfirm;
        this.preOpenFocus = ctx.focusedId();
        this.open = true;
        // No input to focus — clear focus so no panel handles keys underneath.
        ctx.clearFocus();
    }

    public void close() {
        open = false;
        onConfirm = null;
        if (preOpenFocus != null) {
            ctx.focus(preOpenFocus);
        }
    }

    /** The context-sensitive hint line the footer shows while the dialog is open. */
    public String footerHint() {
        return "y confirm   N / Enter / Esc cancel";
    }

    /**
     * Handles the answer keys. Returns true when the event was consumed; {@code MiseTuiApp}'s
     * global handler calls this while the dialog is open. Anything other than {@code y} closes
     * without running, and keys that are neither are swallowed so nothing behind the dialog fires.
     */
    public boolean handleKey(KeyEvent key) {
        if (key.isChar('y') || key.isChar('Y')) {
            Runnable action = onConfirm;
            close();
            if (action != null) {
                action.run();
            }
            return true;
        }
        if (key.isChar('n') || key.isChar('N') || key.isConfirm() || key.isCancel()) {
            close();
            return true;
        }
        return true; // modal: swallow everything else
    }

    public Element build() {
        int toolWidth = items.stream().mapToInt(i -> i.tool().length()).max().orElse(0);
        int fromWidth = items.stream().mapToInt(i -> i.from().length()).max().orElse(0);

        List<Element> content = new ArrayList<>();
        for (Item item : items) {
            content.add(itemRow(item, toolWidth, fromWidth));
        }
        content.add(text(""));
        if (!note.isBlank()) {
            for (String line : note.split("\n")) {
                content.add(text(line).dim());
            }
            content.add(text(""));
        }
        content.add(buttons());

        return dialog(title, content.toArray(new Element[0]))
                .rounded().borderColor(Color.YELLOW).width(WIDTH);
    }

    /** True when the two versions have different major numbers; false when either has none. */
    static boolean majorChange(String from, String to) {
        Integer a = majorOf(from);
        Integer b = majorOf(to);
        return a != null && b != null && !a.equals(b);
    }

    /** The first number in a version string: {@code 22} for {@code 22.11.0} or {@code v22.1}. */
    private static @Nullable Integer majorOf(String version) {
        Matcher m = LEADING_NUMBER.matcher(version.strip());
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.valueOf(m.group(1));
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /** One aligned row: tool, then from → to, then where, then a red major-change flag if any. */
    private static Element itemRow(Item item, int toolWidth, int fromWidth) {
        List<Element> cells = new ArrayList<>();
        cells.add(text(pad(item.tool(), toolWidth) + "  ").bold());
        if (!item.to().isBlank()) {
            cells.add(text(pad(item.from(), fromWidth) + " → ").dim());
            cells.add(text(item.to()).bold().yellow());
        } else if (!item.from().isBlank()) {
            cells.add(text(item.from()).dim());
        }
        if (!item.where().isBlank()) {
            cells.add(text("  " + item.where()).dim());
        }
        if (majorChange(item.from(), item.to())) {
            cells.add(text("  · major " + majorOf(item.from()) + " → " + majorOf(item.to())).red());
        }
        return row(cells.toArray(new Element[0]));
    }

    /**
     * The right-aligned button row. The confirm button is named after the title's first word;
     * Cancel is drawn reversed because it is the answer Enter gives.
     */
    private Element buttons() {
        String verb = title.isBlank() ? "Confirm" : title.strip().split("\\s+")[0];
        return row(
                spacer(),
                text(" y " + verb + " " + items.size() + " ").bold().yellow(),
                text("  "),
                text("[ N Cancel ]").reversed().bold()
        );
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
