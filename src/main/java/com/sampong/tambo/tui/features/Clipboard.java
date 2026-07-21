package com.sampong.tambo.tui.features;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Copies text to the system clipboard via the terminal's OSC 52 escape sequence.
 * <p>
 * Chosen over AWT's {@code Toolkit} clipboard because it works headlessly, over
 * SSH, and under GraalVM native-image (where AWT is unavailable) — the escape is
 * interpreted by the terminal emulator itself. Supported by Windows Terminal,
 * iTerm2, kitty, WezTerm and most modern emulators.
 */
public final class Clipboard {

    /** ESC ] 52 ; c ; &lt;base64&gt; BEL — the OSC 52 "set clipboard" sequence. */
    private static final String OSC52_PREFIX = "]52;c;";
    private static final String BEL = "";

    private Clipboard() {
    }

    /** Writes {@code text} to the clipboard; a no-op visually if the terminal ignores OSC 52. */
    public static void copy(String text) {
        String encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        System.out.print(OSC52_PREFIX + encoded + BEL);
        System.out.flush();
    }
}
