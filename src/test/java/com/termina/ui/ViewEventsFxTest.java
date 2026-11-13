package com.termina.ui;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import com.termina.config.Settings;
import com.termina.testing.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terminal view under a hand that does everything at once: moves over the text with and without
 * the link modifier, leaves and comes back, selects a word and a line and clicks the selection away,
 * presses the pane-drag chord, opens and closes the find bar, holds a modifier down and lets go, and does it
 * again while a full-screen program has the mouse and asks for every motion. The capture runs check
 * what each gesture yields; this checks the view takes them all in a row without failing, in a real
 * window on a real shell.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Windows shell path has never run; see AGENTS.md")
class ViewEventsFxTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
        Platform.setImplicitExit(false);
    }

    @Test
    void aHandThatDoesEverythingInARow() throws Exception {
        Settings settings = new Settings(dir.resolve("settings.json"));
        settings.load();
        settings.setUpdateCheck(false);
        settings.setShowStatusBar(true);
        settings.setNotifications(false);
        WindowManager windows = onFx(() -> new WindowManager(settings));
        TerminalWindow window = onFx(() -> windows.openWindow(null));
        onFx(() -> windows.openWindow(window));
        waitFor(() -> started(window), "the shell");
        TerminalView view = onFx(window::activeTerminal);
        onFx(() -> {
            view.getSession().sendString("printf 'one two three\\nhttps://example.com/x\\n'\r");
            return null;
        });
        // The line as printed, not as typed: the echo of the command has it inside quotes.
        waitFor(() -> hasLine(view, "https://example.com/x"), "the printed lines");

        for (boolean programHasTheMouse : new boolean[] {false, true}) {
            if (programHasTheMouse) {
                onFx(() -> {
                    // Any-motion reporting with SGR coordinates, as a full-screen program asks.
                    // And a word after them, put together by printf so the echo does not have it:
                    // the emulator reads in order, so the word on screen means the modes are set.
                    view.getSession().sendString(ABANDON_LINE);
                    view.getSession().sendString("printf '\\033[?1049h\\033[?1003h\\033[?1006hmouse-%s' on\r");
                    return null;
                });
                waitFor(() -> screen(view).contains("mouse-on"), "the program to take the mouse");
            }
            onFx(() -> {
                for (int x = 5; x < 300; x += 37) {
                    Event.fireEvent(view, mouse(MouseEvent.MOUSE_MOVED, x, 12, 0, false, false));
                    Event.fireEvent(view, mouse(MouseEvent.MOUSE_MOVED, x, 30, 0, true, false));
                }
                Event.fireEvent(
                        view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.CONTROL, false, true, false, false));
                Event.fireEvent(
                        view, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.CONTROL, false, false, false, false));
                Event.fireEvent(view, mouse(MouseEvent.MOUSE_EXITED, -5, -5, 0, false, false));
                Event.fireEvent(view, mouse(MouseEvent.MOUSE_ENTERED, 20, 12, 0, false, false));
                // A word, a line, then a plain click that clears the selection.
                Event.fireEvent(view, mouse(MouseEvent.MOUSE_PRESSED, 20, 12, 2, false, false));
                Event.fireEvent(view, mouse(MouseEvent.MOUSE_RELEASED, 20, 12, 2, false, false));
                Event.fireEvent(view, mouse(MouseEvent.MOUSE_PRESSED, 20, 12, 3, false, false));
                Event.fireEvent(view, mouse(MouseEvent.MOUSE_RELEASED, 20, 12, 3, false, false));
                Event.fireEvent(view, mouse(MouseEvent.MOUSE_PRESSED, 60, 40, 1, false, false));
                Event.fireEvent(view, mouse(MouseEvent.MOUSE_RELEASED, 60, 40, 1, false, false));
                // A drag detected with nothing armed, which the view leaves alone. Never with the chord
                // armed: JavaFX starts a drag-and-drop only from a DRAG_DETECTED the scene itself
                // dispatched, and refuses a synthetic one outright.
                Event.fireEvent(view, mouse(MouseEvent.DRAG_DETECTED, 90, 60, 1, false, false));
                // The pane-drag chord, pressed and released.
                Event.fireEvent(view, mouse(MouseEvent.MOUSE_PRESSED, 30, 30, 1, true, true));
                // Not dragged with it held: a moved chord press starts a real drag-and-drop, which a
                // synthetic event cannot. The capture runs drag panes through the real path.

                Event.fireEvent(view, mouse(MouseEvent.MOUSE_RELEASED, 90, 60, 1, true, true));
                // The find bar, opened, typed into, and closed with Escape.
                view.showSearch();
                view.findNext(true);
                view.findNext(false);
                // In the bar's own field: Enter, Shift+Enter, then Escape closes it.
                javafx.scene.Node field = view.getScene().getFocusOwner();
                if (field != null) {
                    Event.fireEvent(
                            field,
                            new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                    Event.fireEvent(
                            field,
                            new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, true, false, false, false));
                    Event.fireEvent(
                            field,
                            new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                }
                Event.fireEvent(
                        view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                view.requestFocus();
                return null;
            });
        }
        // Keys, at the view: plain and with Alt as Meta, then under the kitty keyboard protocol.
        for (String mode : new String[] {"", "printf '\\033[>1u'\r"}) {
            if (!mode.isEmpty()) {
                onFx(() -> {
                    view.getSession().sendString(ABANDON_LINE);
                    view.getSession().sendString(mode);
                    return null;
                });
                waitFor(() -> kittyFlags(view) != 0, "the kitty keyboard flags to be set");
            }
            onFx(() -> {
                view.setAltIsMeta(true);
                for (KeyCode code :
                        new KeyCode[] {KeyCode.A, KeyCode.UP, KeyCode.F5, KeyCode.ENTER, KeyCode.BACK_SPACE, KeyCode.TAB
                        }) {
                    Event.fireEvent(view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
                    Event.fireEvent(view, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, true, true, true, false));
                    Event.fireEvent(
                            view, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false));
                }
                Event.fireEvent(
                        view, new KeyEvent(KeyEvent.KEY_TYPED, "x", "", KeyCode.UNDEFINED, false, false, false, false));
                Event.fireEvent(
                        view, new KeyEvent(KeyEvent.KEY_TYPED, "é", "", KeyCode.UNDEFINED, false, false, true, false));
                view.setAltIsMeta(false);
                return null;
            });
        }

        // Prompt marks, then a resize: the marks follow their lines through the reflow. Back on
        // the main screen first, and the mouse given back: marks are not kept on the other one.
        onFx(() -> {
            view.getSession().sendString(ABANDON_LINE);
            view.getSession()
                    .sendString(
                            "printf '\\033[<u\\033[?1003l\\033[?1049l\\033]133;A\\007$ \\033]133;B\\007\\n\\033]133;C\\007out\\n\\033]133;D;0\\007'\r");
            return null;
        });
        waitFor(() -> hasLine(view, "out") && marked(view), "the marked prompt and its output");
        int columns = columns(view);
        onFx(() -> {
            window.stage().setWidth(window.stage().getWidth() - 160);
            return null;
        });
        waitFor(() -> columns(view) < columns, "the narrower grid to reach the shell");
        onFx(() -> {
            window.stage().setWidth(window.stage().getWidth() + 160);
            return null;
        });
        waitFor(() -> columns(view) == columns, "the grid to be as wide as it was");
        assertTrue(onFx(() -> view.isStarted()), "the view is still running");
    }

    /** A mouse event at x,y; {@code chord} holds Ctrl+Alt+Shift (the pane drag), {@code link} Ctrl. */
    private static MouseEvent mouse(
            javafx.event.EventType<MouseEvent> type, double x, double y, int clicks, boolean link, boolean chord) {
        boolean pressed = type == MouseEvent.MOUSE_PRESSED
                || type == MouseEvent.MOUSE_DRAGGED
                || type == MouseEvent.DRAG_DETECTED;
        return new MouseEvent(
                type,
                x,
                y,
                x,
                y,
                clicks > 0 ? MouseButton.PRIMARY : MouseButton.NONE,
                clicks,
                chord,
                link || chord,
                chord,
                false,
                pressed,
                false,
                false,
                true,
                false,
                true,
                null);
    }

    /**
     * Ctrl+C, before each command typed after the keys and clicks: they leave the shell's line
     * editor mid-chord (an Escape waiting for its letter, function keys it spelled out), and a
     * command typed onto that is not the command. Found when the fixed waits here became waits for
     * what each command does: the mouse modes had never been set.
     */
    private static final String ABANDON_LINE = "\u0003";

    private static String screen(TerminalView view) {
        try {
            return onFx(view::screenText);
        } catch (Exception e) {
            return "";
        }
    }

    /** Whether a row on screen is exactly {@code line}. */
    private static boolean hasLine(TerminalView view, String line) {
        return screen(view).lines().anyMatch(row -> row.strip().equals(line));
    }

    private static int kittyFlags(TerminalView view) {
        try {
            return onFx(view::keyboardFlagsForCapture);
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean marked(TerminalView view) {
        try {
            return onFx(view::hasPromptMarks);
        } catch (Exception e) {
            return false;
        }
    }

    private static int columns(TerminalView view) {
        try {
            return onFx(() -> view.getSession().getTextBuffer().getWidth());
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean started(TerminalWindow window) {
        try {
            return onFx(() -> {
                TerminalView view = window.activeTerminal();
                return view != null && view.isStarted();
            });
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Await.frame();
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }
}
