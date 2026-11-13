package com.termina.ui;

import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.termina.AppInfo;
import com.termina.report.ProblemReport;

import static com.termina.i18n.Messages.tr;

/**
 * Help → Report a Problem (DIAG-06, Pauta 2.0.2). Shows the redacted report, editable, so the user
 * reads what would be made public before it is; copies it, and opens a new GitHub issue with the
 * bug form's two fields filled in. Nothing is sent from here: the browser shows the issue, and the
 * user submits it or not.
 */
final class ReportProblemWindow {

    private final Stage stage = new Stage();
    private final TextArea environment = new TextArea();
    private final TextArea report = new TextArea();
    private final Label status = new Label();
    private final Consumer<String> openLink;

    ReportProblemWindow(Consumer<String> openLink) {
        this.openLink = openLink;
        Label intro = new Label(tr("report.intro"));
        intro.setWrapText(true);
        environment.setPrefRowCount(5);
        environment.getStyleClass().add("debug-log-text");
        report.getStyleClass().add("debug-log-text");
        VBox.setVgrow(report, Priority.ALWAYS);
        status.setWrapText(true);
        status.getStyleClass().add("settings-row-description");

        Button copy = new Button(tr("report.copy"));
        copy.setOnAction(e -> copy());
        Button open = new Button(tr("report.open"));
        open.setDefaultButton(true);
        open.setOnAction(e -> openIssue());
        Button close = new Button(tr("debuglog.close"));
        close.setOnAction(e -> stage.hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, copy, spacer, close, open);
        buttons.setAlignment(Pos.CENTER_LEFT);

        // Each field's label names it for a screen reader too (ACC-01).
        Label environmentLabel = new Label(tr("report.environment"));
        environmentLabel.setLabelFor(environment);
        Label reportLabel = new Label(tr("report.report"));
        reportLabel.setLabelFor(report);
        VBox root = new VBox(8, intro, environmentLabel, environment, reportLabel, report, status, buttons);
        root.setPadding(new Insets(14));
        Scene scene = new Scene(root, 760, 560);
        var css = ReportProblemWindow.class.getResource("/com/termina/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        Fonts.installUiFont(scene);
        SecondaryWindows.closeOnEscape(stage, scene);
        stage.setTitle(tr("report.title"));
        stage.setScene(scene);
        Icons.applyTo(stage);
    }

    /** Fills it with a freshly built report and shows it beside {@code from}. */
    void show(Window from, ProblemReport built) {
        environment.setText(built.environment());
        report.setText(built.report());
        status.setText("");
        if (!stage.isShowing()) SecondaryWindows.placeNear(stage, from);
        stage.show();
        stage.toFront();
    }

    /** What the user left in the two fields, which is what goes. */
    private ProblemReport edited() {
        return new ProblemReport(environment.getText(), report.getText());
    }

    private void copyToClipboard() {
        ClipboardContent content = new ClipboardContent();
        content.putString(edited().text());
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Copies both fields as they stand; what {@code report.copy} and the Copy button do. */
    void copy() {
        copyToClipboard();
        status.setText(tr("report.copied"));
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    /**
     * Opens a new issue with the two fields filled in, the report on the clipboard as well; what
     * {@code report.open} and the window's default button do.
     */
    void openIssue() {
        copyToClipboard();
        ProblemReport.Link link = edited().link(AppInfo.GITHUB_REPO, tr("report.pasteHint"));
        openLink.accept(link.url());
        status.setText(link.pasteReport() ? tr("report.paste") : tr("report.opened"));
    }

    javafx.scene.Scene scene() {
        return stage.getScene();
    }

    /** What the window would send, for the capture hook. */
    String contentForCapture() {
        return edited().text();
    }
}
