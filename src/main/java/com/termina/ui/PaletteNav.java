package com.termina.ui;

import java.util.function.Predicate;

import javafx.scene.control.ListView;
import javafx.scene.input.KeyEvent;

/**
 * The palette's keys applied to JavaFX: the event read as a token, and a navigation applied to the
 * list. Every decision is {@link PaletteKeys}', which imports no JavaFX type (QUAL-05, Pauta
 * 2.0.2); this only reads and writes the controls. In Editora both halves are one class,
 * {@code ui.PickerKeys}.
 */
final class PaletteNav {

    private PaletteNav() {}

    /**
     * The action for a key press. {@code queryField} says whether a text field has the focus: then
     * plain Home/End stay with its caret. {@code moves} is the movement chords in force.
     */
    static PaletteKeys.Action action(KeyEvent e, boolean queryField, java.util.Map<String, PaletteKeys.Action> moves) {
        return PaletteKeys.resolve(KeyChord.of(e), queryField, moves);
    }

    /** How many rows a page moves: one less than the list shows, so a row of context remains. */
    static int page(ListView<?> list) {
        double cell = list.getFixedCellSize();
        if (cell > 0 && list.getHeight() > cell) return Math.max(1, (int) (list.getHeight() / cell) - 1);
        return PaletteKeys.DEFAULT_PAGE;
    }

    /**
     * Applies a navigation action to {@code list} ({@link PaletteKeys#landing}). Returns false —
     * leaving the event for someone else — only when {@code action} is not navigation.
     */
    static <T> boolean navigate(ListView<T> list, PaletteKeys.Action action, Predicate<? super T> selectable) {
        if (!PaletteKeys.isNavigation(action)) return false;
        int idx = PaletteKeys.landing(
                list.getItems(), list.getSelectionModel().getSelectedIndex(), action, page(list), selectable);
        if (idx >= 0) {
            list.getSelectionModel().select(idx);
            list.scrollTo(idx);
            // The keyboard stays in the text field, so nothing else tells a screen reader the
            // highlighted row moved (ACC-08, Pauta 6.0.1).
            list.notifyAccessibleAttributeChanged(javafx.scene.AccessibleAttribute.FOCUS_ITEM);
        }
        return true;
    }
}
