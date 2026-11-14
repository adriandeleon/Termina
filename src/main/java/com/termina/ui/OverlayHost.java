package com.termina.ui;

import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import org.jspecify.annotations.Nullable;

/**
 * The layer over a window that its cards show in: the Command Palette, the Message Log and every
 * {@link QuickPick}. After Editora's {@code ui.OverlayHost}.
 *
 * <p>It holds one card at a time (QOPEN-11, Pauta 6.0.1). Showing a card takes down whatever card
 * was up, and the keyboard goes back, when the last one closes, to the node that had it before the
 * first opened. So "is a card up" is one question with one answer, which the window's focus guard
 * and its key filter both ask; before this each card kept its own backdrop and its own memory of
 * the focus, and a new card had three places to be added to.
 *
 * <p>A card is a node in the scene and not a window of its own. A second Stage would take OS focus
 * from the terminal, and getting it back is the thing that has to work.
 */
final class OverlayHost {

    /** What is behind a card: its style class, and what a press on it is called for a screen reader. */
    record Scrim(String styleClass, String accessibleText) {}

    private final StackPane layer;
    private final Runnable focusFallback;

    /**
     * What is behind the card. A press on it closes the card, so to a screen reader it is a button
     * that does (ACC-02, Pauta 6.0.1), and its press runs what a click runs.
     */
    private final Region backdrop = new Region() {
        @Override
        public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
            if (action == AccessibleAction.FIRE) hide();
            else super.executeAccessibleAction(action, parameters);
        }
    };

    private @Nullable Node card;
    private @Nullable Runnable onHidden;

    /** The node that had the keyboard before the first card opened, or null. */
    private @Nullable Node focusBefore;

    /**
     * @param layer the pane over the whole window that the backdrop and the card are added to
     * @param focusFallback gives the keyboard to the terminal, when the node that had it is gone or
     *     cannot take it
     */
    OverlayHost(StackPane layer, Runnable focusFallback) {
        this.layer = layer;
        this.focusFallback = focusFallback;
        backdrop.setAccessibleRole(AccessibleRole.BUTTON);
        // Dismiss on PRESS and consume it: a click is press and release, so consuming the press
        // means the gesture that closes the card cannot also reach the terminal behind it.
        backdrop.setOnMousePressed(e -> {
            hide();
            e.consume();
        });
    }

    /**
     * Shows {@code card} over the window, in place of any card that was up. The caller gives its
     * card the keyboard afterwards.
     *
     * @param onHidden runs when this card comes down, however it does: closed, or replaced
     */
    void show(Node card, Scrim scrim, Runnable onHidden) {
        // A card that replaces another inherits where the keyboard was before the first: the
        // card it replaces is about to leave the scene, and is nowhere to go back to.
        Node before = this.card == null ? focusOwner() : focusBefore;
        if (this.card != null) remove();
        focusBefore = before;
        this.card = card;
        this.onHidden = onHidden;
        backdrop.getStyleClass().setAll(scrim.styleClass());
        backdrop.setAccessibleText(scrim.accessibleText());
        layer.getChildren().addAll(backdrop, card);
    }

    /**
     * Takes the card down and gives the keyboard back: to the node that had it, a find bar's field
     * included, or to the terminal when that node is gone or cannot take it. Left where it is if
     * something outside the card took it meanwhile.
     */
    void hide() {
        Node shown = card;
        if (shown == null) return;
        Node now = focusOwner();
        boolean ours = now == null || isUnder(now, shown);
        Node before = focusBefore;
        focusBefore = null;
        remove();
        if (ours && before != null && returnsTo(before, layer.getScene())) {
            before.requestFocus();
        } else if (ours) {
            focusFallback.run();
        }
    }

    /** Takes {@code card} down if it is the one showing; nothing otherwise. */
    void hide(Node card) {
        if (this.card == card) hide();
    }

    /** Whether any card is up, and so entitled to the keyboard. */
    boolean isShowing() {
        return card != null;
    }

    boolean isShowing(Node card) {
        return this.card == card;
    }

    /** The scene the layer is in, or null before it is in one. */
    @Nullable
    Scene scene() {
        return layer.getScene();
    }

    private @Nullable Node focusOwner() {
        Scene scene = layer.getScene();
        return scene == null ? null : scene.getFocusOwner();
    }

    private void remove() {
        Node shown = card;
        Runnable hidden = onHidden;
        card = null;
        onHidden = null;
        if (shown != null) layer.getChildren().removeAll(backdrop, shown);
        if (hidden != null) hidden.run();
    }

    /**
     * Whether closing gives the keyboard back to {@code before}: it is still in this scene, shown,
     * enabled and able to take focus. Otherwise the terminal has it.
     */
    static boolean returnsTo(@Nullable Node before, @Nullable Scene scene) {
        if (before == null || scene == null || before.getScene() != scene) return false;
        if (!before.isFocusTraversable() && !(before instanceof TextInputControl)) {
            // The terminal itself is not focus-traversable and takes focus by request: it is the
            // fallback, and reaching it through the fallback keeps one way of focusing it.
            return false;
        }
        for (Node node = before; node != null; node = node.getParent()) {
            if (!node.isVisible() || node.isDisabled()) return false;
        }
        return true;
    }

    private static boolean isUnder(Node node, Node ancestor) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at == ancestor) return true;
        }
        return false;
    }
}
