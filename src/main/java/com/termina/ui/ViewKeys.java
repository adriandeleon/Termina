package com.termina.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * The keys the view answers itself, ahead of the encoder.
 *
 * <p>Shift+PageUp and friends move through the scrollback and Shift+Insert pastes, on every
 * terminal a Linux or Windows user has used; Ctrl+Insert copies for the same reason. Left to the
 * encoder they reach the shell as {@code CSI 5;2~} and the like, which nothing at a prompt wants.
 *
 * <p>The alternate screen is the exception for the scrolling four: a full-screen program owns its
 * viewport, there is no scrollback behind it, and {@code less} binds Shift+PageUp itself. There they
 * pass through to the encoder. Paste and copy are not contested and work everywhere.
 *
 * <p>Pure because this is the whole of the rule, and a rule about which chord goes where is the
 * kind that is wrong silently: a stolen key is just a key that seems not to work.
 */
final class ViewKeys {

    private ViewKeys() {}

    enum Action {
        NONE,
        PAGE_UP,
        PAGE_DOWN,
        TOP,
        BOTTOM,
        PASTE,
        COPY
    }

    static Action of(KeyEvent e, boolean alternateScreen) {
        return of(e.getCode(), e.isShiftDown(), e.isControlDown(), e.isAltDown(), e.isMetaDown(), alternateScreen);
    }

    static Action of(KeyCode code, boolean shift, boolean control, boolean alt, boolean meta, boolean alternateScreen) {
        if (alt || meta) return Action.NONE;
        if (code == KeyCode.INSERT) {
            if (shift && !control) return Action.PASTE;
            if (control && !shift) return Action.COPY;
            return Action.NONE;
        }
        if (!shift || control || alternateScreen) return Action.NONE;
        return switch (code) {
            case PAGE_UP -> Action.PAGE_UP;
            case PAGE_DOWN -> Action.PAGE_DOWN;
            case HOME -> Action.TOP;
            case END -> Action.BOTTOM;
            default -> Action.NONE;
        };
    }

    /**
     * The scroll origin after a scrolling action, clamped to the history that exists.
     *
     * <p>A page is the screen's height: the row that was at the top is the one just below the
     * bottom afterwards, which is how every pager and terminal defines it.
     */
    static int scrolledOrigin(Action action, int origin, int rows, int historyLines) {
        int next = switch (action) {
            case PAGE_UP -> origin - rows;
            case PAGE_DOWN -> origin + rows;
            case TOP -> -historyLines;
            case BOTTOM -> 0;
            default -> origin;
        };
        return Math.max(-historyLines, Math.min(0, next));
    }
}
