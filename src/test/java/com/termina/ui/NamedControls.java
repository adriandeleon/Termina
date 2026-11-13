package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Cell;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Control;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;

/**
 * Finds the controls a screen reader would announce as nothing (ACC-01, Pauta 6.0.1): one a person
 * can reach with Tab or the mouse that has no text of its own, no accessible text and no label
 * pointing at it; a list cell that reads as nothing; a menu row with no title.
 *
 * <p>A test helper, shared by the sweep of every window and by {@link DialogAnswers}, which looks
 * at each dialog as it answers it.
 */
final class NamedControls {

    private NamedControls() {}

    /** What is unnamed under {@code root}, each as a line that says which control and where. */
    static List<String> unnamed(Node root) {
        List<String> out = new ArrayList<>();
        List<Label> labels = new ArrayList<>();
        collectLabels(root, labels);
        walk(root, labels, out);
        return out;
    }

    /** How many controls and cells under {@code root} were looked at: a sweep that saw none proves nothing. */
    static int checked(Node root) {
        int[] count = {0};
        count(root, count);
        return count[0];
    }

    /** The rows of a menu that read as nothing, submenus included. */
    static List<String> unnamedRows(List<? extends MenuItem> items) {
        List<String> out = new ArrayList<>();
        for (MenuItem item : items) {
            if (item instanceof SeparatorMenuItem) continue;
            if (item instanceof CustomMenuItem custom) {
                Node content = custom.getContent();
                // JavaFX names such a row from its content's accessible text.
                if (content == null || blank(content.getAccessibleText())) {
                    out.add("menu row with no accessible text: " + describe(content));
                }
                if (content != null) out.addAll(unnamed(content));
            } else if (blank(item.getText())
                    && (item.getGraphic() == null || blank(item.getGraphic().getAccessibleText()))) {
                // A row titled by its graphic, a submenu drawn like the rows around it, is named
                // from that graphic's accessible text.
                out.add("menu row with no title: " + item + " graphic=" + describe(item.getGraphic()));
            }
            if (item instanceof Menu menu) {
                // A submenu filled as it shows, New Tab With, holds a placeholder until then: the
                // rows a person would see are the ones it has after its showing handler has run.
                javafx.event.Event.fireEvent(menu, new javafx.event.Event(Menu.ON_SHOWING));
                out.addAll(unnamedRows(menu.getItems()));
            }
        }
        return out;
    }

    /**
     * The rows of a menu that carry no glyph (MENU-14, Pauta 6.0.1): every row does but a check or
     * radio row, whose mark has the icon column. A plain row's glyph is its graphic; a row drawn
     * from its own nodes has one somewhere in its content, in the icon column.
     */
    static List<String> rowsWithoutGlyph(List<? extends MenuItem> items) {
        List<String> out = new ArrayList<>();
        for (MenuItem item : items) {
            if (item instanceof SeparatorMenuItem || !item.isVisible()) continue;
            if (item instanceof javafx.scene.control.CheckMenuItem
                    || item instanceof javafx.scene.control.RadioMenuItem) continue;
            Node drawn = item instanceof CustomMenuItem custom ? custom.getContent() : item.getGraphic();
            if (drawn == null || !hasShape(drawn)) {
                String title = MenuRows.isRow(item) ? MenuRows.titleOf(item) : item.getText();
                out.add("menu row with no glyph: " + title + " " + item.getUserData());
            }
            if (item instanceof Menu menu) {
                javafx.event.Event.fireEvent(menu, new javafx.event.Event(Menu.ON_SHOWING));
                out.addAll(rowsWithoutGlyph(menu.getItems()));
            }
        }
        return out;
    }

    /** The same, for the right-click menu of every control under {@code root}. */
    static List<String> menusWithoutGlyph(Node root) {
        List<String> out = new ArrayList<>();
        if (root instanceof Control control && control.getContextMenu() != null) {
            javafx.scene.control.ContextMenu menu = control.getContextMenu();
            out.addAll(rowsWithoutGlyph(menu.getItems()));
        }
        for (Node child : children(root)) out.addAll(menusWithoutGlyph(child));
        return out;
    }

    /** Whether something is drawn under {@code node}: a path with geometry, or an image. */
    private static boolean hasShape(Node node) {
        if (node instanceof javafx.scene.shape.SVGPath path) {
            return path.getBoundsInLocal().getWidth() > 0
                    && path.getBoundsInLocal().getHeight() > 0;
        }
        if (node instanceof javafx.scene.image.ImageView) return true;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) if (hasShape(child)) return true;
        }
        return false;
    }

    /** The unnamed rows of every right-click menu a control under {@code root} has been given. */
    static List<String> unnamedMenus(Node root) {
        List<String> out = new ArrayList<>();
        if (root instanceof Control control && control.getContextMenu() != null) {
            out.addAll(unnamedRows(control.getContextMenu().getItems()));
        }
        for (Node child : children(root)) out.addAll(unnamedMenus(child));
        return out;
    }

    private static void collectLabels(Node node, List<Label> labels) {
        if (node instanceof Label label && label.getLabelFor() != null) labels.add(label);
        for (Node child : children(node)) collectLabels(child, labels);
    }

    private static void count(Node node, int[] count) {
        if (isCell(node) || isReachable(node)) count[0]++;
        if (isLeaf(node)) return;
        for (Node child : children(node)) count(child, count);
    }

    private static void walk(Node node, List<Label> labels, List<String> out) {
        if (!node.isVisible()) return;
        if (isCell(node)) {
            Cell<?> cell = (Cell<?>) node;
            if (!cell.isEmpty() && blank(cell.getText()) && blank(cell.getAccessibleText()) && !hasText(cell)) {
                out.add("cell that reads as nothing: " + describe(cell));
            }
        } else if (isReachable(node)) {
            Control control = (Control) node;
            boolean named = !blank(control.getAccessibleText())
                    || (control instanceof Labeled labeled && hasLetters(labeled.getText()))
                    || labels.stream().anyMatch(label -> label.getLabelFor() == control && hasLetters(label.getText()));
            if (!named) out.add("no name: " + describe(control));
        }
        if (isLeaf(node)) return;
        for (Node child : children(node)) walk(child, labels, out);
    }

    /** A control a person can focus or click, in the Tab order or out of it. */
    private static boolean isReachable(Node node) {
        if (node instanceof ScrollBar) {
            // The terminal's own bar is the app's; one inside a list or a scroll pane is its skin's.
            return node.getStyleClass().contains("terminal-scroll-bar");
        }
        return node instanceof ButtonBase
                || node instanceof TextInputControl
                || node instanceof ComboBoxBase
                || node instanceof ChoiceBox
                || node instanceof Spinner
                || node instanceof Slider
                || node instanceof ListView;
    }

    private static boolean isCell(Node node) {
        return node instanceof Cell;
    }

    /** A control whose children are its skin's: the field inside a spinner, the arrow of a combo box. */
    private static boolean isLeaf(Node node) {
        return node instanceof ButtonBase
                || node instanceof TextInputControl
                || node instanceof ComboBoxBase
                || node instanceof ChoiceBox
                || node instanceof Spinner
                || node instanceof Slider
                || node instanceof ScrollBar;
    }

    private static List<Node> children(Node node) {
        List<Node> out = new ArrayList<>();
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) out.add(scroll.getContent());
        if (node instanceof Parent parent) out.addAll(parent.getChildrenUnmodifiable());
        return out;
    }

    /** Whether anything under a cell is text a reader could read. */
    private static boolean hasText(Node node) {
        for (Node child : children(node)) {
            if (child instanceof Labeled labeled && hasLetters(labeled.getText())) return true;
            if (child instanceof javafx.scene.text.Text text && hasLetters(text.getText())) return true;
            if (!blank(child.getAccessibleText())) return true;
            if (hasText(child)) return true;
        }
        return false;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /** A symbol alone, the zoom row's "−", is read as itself or not at all: not a name. */
    private static boolean hasLetters(String text) {
        return text != null && text.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private static String describe(Node node) {
        if (node == null) return "null";
        StringBuilder path = new StringBuilder(node.getClass().getSimpleName() + node.getStyleClass());
        if (node instanceof TextInputControl field && !blank(field.getPromptText())) {
            path.append(" prompt=\"").append(field.getPromptText()).append('"');
        }
        Node parent = node.getParent();
        for (int up = 0; parent != null && up < 3; up++, parent = parent.getParent()) {
            path.append(" < ").append(parent.getClass().getSimpleName()).append(parent.getStyleClass());
        }
        return path.toString();
    }
}
