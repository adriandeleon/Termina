package com.termina.ui;

import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.control.CheckBox;
import javafx.scene.input.KeyEvent;

import atlantafx.base.controls.ToggleSwitch;

/**
 * The Settings on/off control (KBD-18, Pauta 6.0.1): an AtlantaFX {@link ToggleSwitch} that a
 * keyboard and a screen reader can use. The stock switch only reacts to a mouse release and reports
 * the accessible role {@code PARENT}, so a row built from it could be tabbed to but never flipped,
 * and was announced as an anonymous group. This one takes focus, toggles on Space and Enter,
 * reports {@code TOGGLE_BUTTON} with its selected state, and fires through the accessibility API.
 * Editora's class of the same name.
 */
final class SettingSwitch extends ToggleSwitch {

    SettingSwitch() {
        setAccessibleRole(AccessibleRole.TOGGLE_BUTTON);
        setFocusTraversable(true);
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            boolean modified = e.isShortcutDown() || e.isAltDown() || e.isControlDown() || e.isMetaDown();
            if (SwitchKeys.toggles(e.getCode(), modified)) {
                toggle();
                e.consume();
            }
        });
        selectedProperty().addListener((o, was, now) -> notifyAccessibleAttributeChanged(AccessibleAttribute.SELECTED));
    }

    /**
     * A switch that is a <em>view</em> of {@code check}: every Settings on/off value lives on a
     * {@link CheckBox} (its listeners, the reload, the palette's toggles), so the switch binds to
     * its selected and disabled state while the checkbox itself stays out of the scene graph.
     */
    static SettingSwitch boundTo(CheckBox check) {
        SettingSwitch sw = new SettingSwitch();
        if (check.getAccessibleText() != null) sw.setAccessibleText(check.getAccessibleText());
        sw.selectedProperty().bindBidirectional(check.selectedProperty());
        sw.disableProperty().bind(check.disableProperty());
        return sw;
    }

    /** Flips it, as a click, Space or a screen reader's press does; nothing while it is greyed. */
    void toggle() {
        if (!isDisabled()) setSelected(!isSelected());
    }

    @Override
    public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        if (attribute == AccessibleAttribute.SELECTED) return isSelected();
        return super.queryAccessibleAttribute(attribute, parameters);
    }

    @Override
    public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
        if (action == AccessibleAction.FIRE) toggle();
        else super.executeAccessibleAction(action, parameters);
    }
}
