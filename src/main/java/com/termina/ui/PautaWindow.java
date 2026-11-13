package com.termina.ui;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.termina.AppInfo;
import com.termina.PautaConformance;
import com.termina.PautaConformance.Conformance;
import com.termina.PautaConformance.Entry;
import com.termina.PautaConformance.Kind;

import static com.termina.i18n.Messages.tr;

/**
 * The Pauta window (PWIN-01 to PWIN-08, Pauta 6.0.1): the edition Termina implements, how fully it
 * conforms, and every rule it does not meet, each with its reason and its issue or decision.
 *
 * <p>The app template's window of the same name, as a window beside the terminals like About. It
 * reads {@code pauta.json} and {@code AppInfo} alone and takes the conformance from {@link
 * PautaConformance} (PWIN-03): no edition, conformance or rule ID is written here. Its links and
 * its Copy button run what their commands run ({@code help.pautaRules}, {@code help.pautaDocs},
 * {@code help.copyPauta}), so the palette reaches each of them.
 */
final class PautaWindow {

    /** Where the user guide's conformance page is, until the site serves versioned docs (PWIN-07). */
    static final String DOCS_PAGE = AppInfo.DOCUMENTATION + "pauta/";

    private final Stage stage = new Stage();
    private final List<Entry> exceptions;
    private final Consumer<String> openLink;
    private final VBox list = new VBox(2);
    private final Button copy = new Button(tr("pauta.copy"));

    PautaWindow(Consumer<String> openLink) {
        this(openLink, PautaConformance.exceptions());
    }

    PautaWindow(Consumer<String> openLink, List<Entry> exceptions) {
        this.openLink = openLink;
        this.exceptions = PautaConformance.sorted(exceptions);
        Conformance conformance = PautaConformance.of(exceptions);

        Label edition = new Label(tr("pauta.edition", AppInfo.PAUTA_EDITION));
        edition.getStyleClass().add("about-name");
        Label pill = new Label(conformanceName(conformance));
        pill.getStyleClass().addAll("pauta-conformance", conformance.key());
        Label meaning = new Label(meaning(conformance, exceptions));
        meaning.getStyleClass().add("about-version");
        meaning.setWrapText(true);
        HBox head = new HBox(16, mark(), new VBox(4, edition, new HBox(pill), meaning));
        head.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12, head);
        String counts = counts(exceptions);
        if (!counts.isEmpty()) {
            Label countsLabel = new Label(counts);
            countsLabel.getStyleClass().add("about-details");
            content.getChildren().add(countsLabel);
        }
        fillList();
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("pauta-list");
        // The list is the window's content: named for a screen reader, and it takes the height.
        scroll.setAccessibleText(tr("pauta.title"));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        content.getChildren().add(scroll);

        Hyperlink rules = new Hyperlink(tr("pauta.rules", AppInfo.PAUTA_EDITION));
        rules.setOnAction(e -> openLink.accept(AppInfo.pautaUrl()));
        Hyperlink docs = new Hyperlink(tr("pauta.docs"));
        docs.setOnAction(e -> openLink.accept(DOCS_PAGE));
        copy.setOnAction(e -> copyAsText());
        Button close = new Button(tr("pauta.close"));
        close.setDefaultButton(true);
        close.setOnAction(e -> stage.hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, copy, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().addAll(new VBox(2, rules, docs), buttons);
        content.setPadding(new Insets(18));

        Scene scene = new Scene(content, 640, 560);
        var css = PautaWindow.class.getResource("/com/termina/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        Fonts.installUiFont(scene);
        SecondaryWindows.closeOnEscape(stage, scene);
        stage.setTitle(tr("pauta.title"));
        stage.setScene(scene);
        Icons.applyTo(stage);
    }

    /** One group header per kind present, then its rows (PWIN-05's order); one line when there are none. */
    private void fillList() {
        if (exceptions.isEmpty()) {
            Label none = new Label(tr("pauta.none", AppInfo.NAME, AppInfo.PAUTA_EDITION));
            none.setWrapText(true);
            list.getChildren().add(none);
            return;
        }
        Kind group = null;
        for (Entry entry : exceptions) {
            if (entry.kind() != group) {
                group = entry.kind();
                Label header = new Label(tr("pauta.kind." + group.key()));
                header.getStyleClass().add("pauta-group");
                list.getChildren().add(header);
            }
            list.getChildren().add(row(entry));
        }
    }

    /** The rule's ID linked to its address in this edition, its level, the reason and the entry's link. */
    private Node row(Entry entry) {
        Hyperlink id = new Hyperlink(entry.rule());
        id.getStyleClass().add("pauta-rule");
        id.setOnAction(e -> openLink.accept(entry.url()));
        id.setMinWidth(84);
        Label level = new Label(entry.level());
        level.getStyleClass().add("pauta-level");
        level.setMinWidth(56);
        TextFlow reason = new TextFlow(new Text(entry.reason()));
        reason.getStyleClass().add("pauta-reason");
        String target = entry.link();
        if (target != null) {
            Hyperlink link = new Hyperlink(linkText(target));
            link.setOnAction(e -> openLink.accept(target));
            reason.getChildren().addAll(new Text(" "), link);
        }
        HBox.setHgrow(reason, Priority.ALWAYS);
        HBox row = new HBox(8, id, level, reason);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("pauta-row");
        return row;
    }

    private static String linkText(String link) {
        String issue = PautaConformance.issueNumber(link);
        if (issue != null) return issue;
        String decision = PautaConformance.decisionNumber(link);
        if (decision != null) return tr("pauta.decision", decision);
        return tr("pauta.link");
    }

    private static Node mark() {
        // Loaded in the background: opening the window reads no file on the FX thread (PERF-01).
        java.net.URL url = PautaWindow.class.getResource("/com/termina/icons/pauta-128.png");
        if (url == null) return new Label();
        ImageView view = new ImageView(new Image(url.toExternalForm(), 72, 72, true, true, true));
        view.setFitWidth(72);
        view.setFitHeight(72);
        return view;
    }

    /** Complete, Core or Partial, in the active language. */
    static String conformanceName(Conformance conformance) {
        return tr("pauta.conformance." + conformance.key());
    }

    /** The sentence under the pill: what the conformance means for this app. */
    static String meaning(Conformance conformance, List<Entry> exceptions) {
        return switch (conformance) {
            case COMPLETE -> tr("pauta.conformance.complete.desc", AppInfo.NAME);
            case CORE ->
                tr("pauta.conformance.core.desc", AppInfo.NAME, PautaConformance.pending(exceptions, "SHOULD"));
            case PARTIAL ->
                tr("pauta.conformance.partial.desc", AppInfo.NAME, PautaConformance.pending(exceptions, "MUST"));
        };
    }

    /** "3 pending · 1 deviation · 1 not applicable"; a kind with none is left out, and no exceptions is empty. */
    static String counts(List<Entry> exceptions) {
        StringJoiner line = new StringJoiner(" · ");
        for (Map.Entry<Kind, Integer> count :
                PautaConformance.counts(exceptions).entrySet()) {
            // One of a kind has its own string: several languages inflect the word that follows.
            String key = "pauta.count." + count.getKey().key() + (count.getValue() == 1 ? ".one" : "");
            line.add(tr(key, count.getValue()));
        }
        return line.toString();
    }

    /** Copy as text (PWIN-06): the edition, the conformance and the list as Markdown, one line per exception. */
    static String markdown(List<Entry> exceptions) {
        Conformance conformance = PautaConformance.of(exceptions);
        StringBuilder text = new StringBuilder();
        text.append("# ")
                .append(tr("pauta.edition", AppInfo.PAUTA_EDITION))
                .append(": ")
                .append(conformanceName(conformance))
                .append("\n\n")
                .append(meaning(conformance, exceptions))
                .append('\n');
        if (exceptions.isEmpty()) {
            text.append('\n')
                    .append(tr("pauta.none", AppInfo.NAME, AppInfo.PAUTA_EDITION))
                    .append('\n');
        }
        Kind group = null;
        for (Entry entry : PautaConformance.sorted(exceptions)) {
            if (entry.kind() != group) {
                group = entry.kind();
                text.append("\n## ").append(tr("pauta.kind." + group.key())).append("\n\n");
            }
            text.append("- [")
                    .append(entry.rule())
                    .append("](")
                    .append(entry.url())
                    .append(") ")
                    .append(entry.level())
                    .append(": ")
                    .append(entry.reason());
            String link = entry.link();
            if (link != null) {
                text.append(" [")
                        .append(linkText(link))
                        .append("](")
                        .append(link)
                        .append(')');
            }
            text.append('\n');
        }
        text.append('\n').append(AppInfo.pautaUrl()).append('\n');
        return text.toString();
    }

    /** Puts the app's own list on the clipboard as Markdown; what {@code help.copyPauta} does without the window. */
    static void copyMarkdown() {
        var content = new javafx.scene.input.ClipboardContent();
        content.putString(markdown(PautaConformance.exceptions()));
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    /** The Copy as text button: the list this window shows, and the button says so; the window stays open. */
    void copyAsText() {
        var content = new javafx.scene.input.ClipboardContent();
        content.putString(markdown(exceptions));
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        copy.setText(tr("pauta.copied"));
    }

    /** Shows it beside {@code from}, the Copy button back to its own label. */
    void show(Window from) {
        copy.setText(tr("pauta.copy"));
        if (!stage.isShowing()) SecondaryWindows.placeNear(stage, from);
        stage.show();
        stage.toFront();
    }

    Scene scene() {
        return stage.getScene();
    }

    VBox listForTest() {
        return list;
    }

    Button copyButtonForTest() {
        return copy;
    }
}
