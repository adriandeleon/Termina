package com.termina.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

/**
 * Names an icon-only control: its tooltip and its accessible text, from one string (ICON-21,
 * TBAR-01, KBD-17, Pauta 2.0.2).
 *
 * <p>A tooltip is for a pointer; a screen reader reads the accessible text and nothing else, and a
 * button whose only content is a shape or a symbol has no name for it to read. Setting both from
 * the same value keeps them from drifting apart, including when the text changes with a rebound
 * chord.
 */
final class IconNames {

    /** How long a pointer rests before the name shows, the same everywhere in the chrome. */
    static final Duration SHOW_DELAY = Duration.millis(400);

    private IconNames() {}

    /** Names {@code node} with a text that may change; returns the tooltip it installed. */
    static Tooltip name(Node node, ObservableValue<String> text) {
        Tooltip tip = new Tooltip();
        tip.textProperty().bind(text);
        tip.setShowDelay(SHOW_DELAY);
        if (node instanceof Control control) {
            control.setTooltip(tip);
        } else {
            // A skin's own node — a tab's close button — is not a Control and has no tooltip slot.
            Tooltip.install(node, tip);
        }
        node.accessibleTextProperty().bind(text);
        return tip;
    }

    /**
     * A command's tooltip: its title and the chord it is on now, "Zoom In (Ctrl+Shift+=)", or the
     * title alone when it has none (TBAR-03, Pauta 2.0.2). The chord is whatever the keymap holds,
     * never written into the string.
     */
    static String withChord(String title, javafx.scene.input.@org.jspecify.annotations.Nullable KeyCombination chord) {
        return chord == null ? title : com.termina.i18n.Messages.tr("tooltip.withChord", title, chord.getDisplayText());
    }

    /** Names {@code node} with a fixed text. */
    static Tooltip name(Node node, String text) {
        return name(node, new SimpleStringProperty(text));
    }
}
