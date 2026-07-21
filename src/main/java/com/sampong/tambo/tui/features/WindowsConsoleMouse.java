package com.sampong.tambo.tui.features;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import org.jspecify.annotations.Nullable;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns off the Windows console's QuickEdit mode for the duration of the TUI.
 * <p>
 * In a classic conhost window (a plain {@code cmd} console), QuickEdit is on by
 * default and captures every mouse event for its text-selection feature — the
 * VT mouse-reporting sequences TamboUI enables never reach the application, so
 * clicking and wheel-scrolling silently do nothing. TamboUI's own
 * {@code WindowsTerminal} does not clear the flag, so we do it here before the
 * TUI starts. Windows Terminal and other modern hosts are unaffected either way.
 * <p>
 * Everything is best-effort: on a non-Windows OS, without a real console
 * (piped stdin), or on any FFM failure, calls are silent no-ops.
 */
@Slf4j
public final class WindowsConsoleMouse {

    private static final int STD_INPUT_HANDLE = -10;
    private static final int ENABLE_QUICK_EDIT_MODE = 0x0040;
    private static final int ENABLE_EXTENDED_FLAGS = 0x0080;

    private WindowsConsoleMouse() {
    }

    /**
     * Clears QuickEdit on the console input handle. Returns the previous console
     * mode so {@link #restore(Integer)} can put it back on exit, or null when
     * nothing was changed.
     */
    public static @Nullable Integer disableQuickEdit() {
        if (notWindows()) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            Kernel32 k32 = new Kernel32(arena);
            MemorySegment handle = k32.stdInputHandle();
            if (handle == null) {
                return null;
            }
            MemorySegment modeBuf = arena.allocate(ValueLayout.JAVA_INT);
            if ((int) k32.getConsoleMode.invoke(handle, modeBuf) == 0) {
                return null; // no console attached (piped/redirected stdin)
            }
            int mode = modeBuf.get(ValueLayout.JAVA_INT, 0);
            int newMode = (mode | ENABLE_EXTENDED_FLAGS) & ~ENABLE_QUICK_EDIT_MODE;
            if (newMode == mode || (int) k32.setConsoleMode.invoke(handle, newMode) == 0) {
                return null;
            }
            return mode;
        } catch (Throwable t) {
            log.debug("QuickEdit mode unavailable in this console: {}", t.getMessage());
            return null; // mouse simply stays unavailable in this console
        }
    }

    /** Restores a console mode previously returned by {@link #disableQuickEdit()}. */
    public static void restore(@Nullable Integer previousMode) {
        if (previousMode == null || notWindows()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            Kernel32 k32 = new Kernel32(arena);
            MemorySegment handle = k32.stdInputHandle();
            if (handle != null) {
                k32.setConsoleMode.invoke(handle, previousMode.intValue());
            }
        } catch (Throwable t) {
            log.debug("Failed to restore previous console mode: {}", t.getMessage());
        }
    }

    private static boolean notWindows() {
        return !System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** The three kernel32 calls we need, bound for the lifetime of one Arena. */
    private static final class Kernel32 {

        final MethodHandle getStdHandle;
        final MethodHandle getConsoleMode;
        final MethodHandle setConsoleMode;

        Kernel32(Arena arena) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            getStdHandle = linker.downcallHandle(kernel32.findOrThrow("GetStdHandle"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            getConsoleMode = linker.downcallHandle(kernel32.findOrThrow("GetConsoleMode"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            setConsoleMode = linker.downcallHandle(kernel32.findOrThrow("SetConsoleMode"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        }

        /** The console input handle, or null when invalid. */
        @Nullable MemorySegment stdInputHandle() throws Throwable {
            MemorySegment handle = (MemorySegment) getStdHandle.invoke(STD_INPUT_HANDLE);
            // INVALID_HANDLE_VALUE is -1; a null segment is address 0.
            return handle == null || handle.address() == 0 || handle.address() == -1 ? null : handle;
        }
    }
}
