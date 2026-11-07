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
        copy.setOnAction(e -> {
            copyToClipboard();
            status.setText(tr("report.copied"));
        });
        Button open = new Button(tr("report.open"));
        open.setDefaultButton(true);
        open.setOnAction(e -> openIssue());
        Button close = new Button(tr("debuglog.close"));
        close.setOnAction(e -> stage.hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, copy, spacer, close, open);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(
                8,
                intro,
                new Label(tr("report.environment")),
                environment,
                new Label(tr("report.report")),
                report,
                status,
                buttons);
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

    private void openIssue() {
        copyToClipboard();
        ProblemReport.Link link = edited().link(AppInfo.GITHUB_REPO);
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
