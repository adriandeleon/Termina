package com.termina.ui;

import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
import javafx.scene.control.Label;

/**
 * A label that does something when clicked, and says so (ACC-02, Pauta 6.0.1): a screen reader is
 * told it is a button, and its press runs what a click runs. A plain {@link Label} with a click
 * handler is announced as text, and nothing says it can be pressed.
 */
final class PressLabel extends Label {

    private final Runnable action;

    PressLabel(Runnable action) {
        this.action = action;
        setAccessibleRole(AccessibleRole.BUTTON);
        setCursor(Cursor.HAND);
        setOnMouseClicked(e -> action.run());
    }

    @Override
    public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
        if (action == AccessibleAction.FIRE) this.action.run();
        else super.executeAccessibleAction(action, parameters);
    }
}
