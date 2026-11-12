package com.termina.ui;

import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.termina.AppInfo;
import com.termina.config.Settings;
import com.termina.update.ReleaseInfo;
import com.termina.update.UpdateService;
import org.jspecify.annotations.Nullable;

import static com.termina.i18n.Messages.tr;

/** About: what this build is, where it came from, and whether something newer exists. */
public final class AboutWindow {

    private final Stage stage = new Stage();
    private final VBox updateRow = new VBox(4);
    private final Button copy = new Button();
    private final Consumer<String> openLink;
    private final Settings settings;

    private @Nullable ReleaseInfo update;
    private UpdateService.@Nullable Outcome answer;

    public AboutWindow(Settings settings, Consumer<String> openLink) {
        this.settings = settings;
        this.openLink = openLink;
        build();
    }

    /**
     * @param update the newer release, or null when none is known
     * @param answer what the last update check this session answered, or null when none has
     */
    public void setUpdate(@Nullable ReleaseInfo update, UpdateService.@Nullable Outcome answer) {
        this.update = update;
        this.answer = answer;
        refreshUpdateRow();
    }

    /** What the update row may say. */
    enum UpdateLine {
        AVAILABLE,
        UP_TO_DATE,
        CHECK_FAILED,
        NOT_CHECKED,
        CHECKS_OFF
    }

    /**
     * Which line the update row shows. "Up to date" is a claim, and only an answer from this
     * session earns it: the stored check time is stamped <em>before</em> the request so that an
     * offline launch does not retry every time, so it says nothing about whether one answered.
     */
    static UpdateLine updateLine(
            @Nullable ReleaseInfo update, UpdateService.@Nullable Outcome answer, boolean checksOn) {
        if (update != null) return UpdateLine.AVAILABLE;
        if (answer == null) return checksOn ? UpdateLine.NOT_CHECKED : UpdateLine.CHECKS_OFF;
        if (answer.error() != null) return UpdateLine.CHECK_FAILED;
        return UpdateLine.UP_TO_DATE;
    }

    /**
     * Shows the window near {@code from}, without being owned by it.
     *
     * <p>An owned stage closes with its owner, and an owner can be set only once — so the first
     * version died with whichever terminal window happened to open it first, while others were
     * still up. Unowned, it outlives any one window; it is placed beside the one that asked, which
     * is the part of ownership that was wanted.
     */
    public void show(Window from) {
        refreshUpdateRow();
        copy.setText(tr("about.copyDetails"));
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
            return;
        }
        SecondaryWindows.placeNear(stage, from);
        stage.show();
    }

    public Scene scene() {
        return stage.getScene();
    }

    private void build() {
        stage.setTitle(tr("about.title", AppInfo.NAME));
        stage.setResizable(false);

        javafx.scene.image.ImageView logo = Icons.logo(72);
        Label name = new Label(AppInfo.NAME);
        name.getStyleClass().add("about-name");
        HBox heading = new HBox(14);
        heading.setAlignment(Pos.CENTER_LEFT);
        if (logo != null) heading.getChildren().add(logo);

        // The snapshot suffix is kept rather than tidied away: it is what tells you at a glance
        // that a build came off a working tree rather than a release.
        Label version = new Label(tr("about.version", AppInfo.VERSION));
        version.getStyleClass().add("about-version");

        VBox details = new VBox(3);
        details.getStyleClass().add("about-details");
        if (!AppInfo.BUILD_TIME.isBlank()) {
            details.getChildren().add(new Label(tr("about.built", AppInfo.BUILD_TIME)));
        }
        if (!AppInfo.COMMIT.isBlank()) details.getChildren().add(new Label(tr("about.commit", AppInfo.COMMIT)));
        details.getChildren().add(new Label(tr("about.runtime", AppInfo.javaVersion(), AppInfo.javaFxVersion())));
        details.getChildren().addAll(new Label(AppInfo.COPYRIGHT), new Label(AppInfo.LICENSE));

        Hyperlink homepage = new Hyperlink(AppInfo.HOMEPAGE);
        homepage.setOnAction(e -> openLink.accept(AppInfo.HOMEPAGE));
        // Always there, whatever the update check said: the repository's releases page is where
        // its source is too (ABOUT-02, Pauta 6.0.1). The link in the update row is the new
        // version's, and shows only when there is one.
        Hyperlink source = new Hyperlink(tr("about.source"));
        source.setOnAction(e -> openLink.accept(AppInfo.RELEASES_PAGE));
        // A flow, not a row: three links are wider than the window in most languages, and a row
        // would cut each short with an ellipsis.
        javafx.scene.layout.FlowPane links = new javafx.scene.layout.FlowPane(4, 0, homepage, source);
        links.setAlignment(Pos.CENTER_LEFT);
        // The numbered edition's page, never latest/: a newer edition may hold rules this build
        // does not meet. Absent from an unfiltered build rather than linking nowhere.
        if (!AppInfo.PAUTA_EDITION.isBlank()) {
            Hyperlink pauta = new Hyperlink(tr("about.pauta", AppInfo.PAUTA_EDITION));
            pauta.setOnAction(e -> openLink.accept(AppInfo.pautaUrl()));
            links.getChildren().add(pauta);
        }

        // Everything --version prints, for a bug report. In the content rather than the button
        // bar, and the window stays open: copying is a step on the way to somewhere else.
        copy.setText(tr("about.copyDetails"));
        copy.setOnAction(e -> {
            var content = new javafx.scene.input.ClipboardContent();
            content.putString(String.join("\n", AppInfo.details()) + "\n");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            copy.setText(tr("about.copied"));
        });

        Label settingsPath = new Label(tr("about.settings", settings.file()));
        settingsPath.getStyleClass().add("about-path");
        settingsPath.setWrapText(true);

        Label credits = new Label(tr("about.credits"));
        credits.getStyleClass().add("about-credits");
        credits.setWrapText(true);

        updateRow.getStyleClass().add("about-update");

        Button close = new Button(tr("about.close"));
        close.setOnAction(e -> stage.hide());
        close.setDefaultButton(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, copy, spacer, close);
        footer.setAlignment(Pos.CENTER_RIGHT);

        VBox titles = new VBox(2, name, version);
        heading.getChildren().add(titles);

        VBox root = new VBox(10, heading, details, links, updateRow, settingsPath, credits, footer);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.TOP_LEFT);
        VBox.setMargin(footer, new Insets(8, 0, 0, 0));

        Scene scene = new Scene(root, 480, 440);
        var css = AboutWindow.class.getResource("/com/termina/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        Fonts.installUiFont(scene);
        SecondaryWindows.closeOnEscape(stage, scene);
        stage.setScene(scene);
        Icons.applyTo(stage);

        refreshUpdateRow();
    }

    private void refreshUpdateRow() {
        updateRow.getChildren().clear();
        ReleaseInfo update = this.update;
        if (update == null) {
            UpdateLine line = updateLine(null, answer, settings.updateCheck());
            Label status = new Label(
                    switch (line) {
                        case UP_TO_DATE, AVAILABLE -> tr("about.upToDate");
                        case CHECK_FAILED -> tr("about.checkFailed");
                        case NOT_CHECKED -> tr("about.notChecked");
                        case CHECKS_OFF -> tr("about.checksOff");
                    });
            status.getStyleClass().add(line == UpdateLine.UP_TO_DATE ? "about-uptodate" : "about-details");
            updateRow.getChildren().add(status);
        } else {
            Label headline = new Label(tr("about.updateHeadline", update.version()));
            headline.getStyleClass().add("about-update-headline");
            Hyperlink download = new Hyperlink(tr("about.openReleasePage"));
            download.setOnAction(e -> {
                String url = update.url() == null || update.url().isBlank() ? AppInfo.RELEASES_PAGE : update.url();
                openLink.accept(url);
            });
            updateRow.getChildren().addAll(headline, download);
        }
        // When the last check ran, so "up to date" can be read as "as of when". Only beside an
        // answer from this session: under "not checked" a date reads as the check that was not.
        String checked = answer == null ? null : lastChecked(settings.lastUpdateCheck());
        if (checked != null) {
            Label when = new Label(tr("about.lastChecked", checked));
            when.getStyleClass().add("about-details");
            updateRow.getChildren().add(when);
        }
    }

    /** The last check as a local date and time in the UI's locale, or null when there was none. */
    static @Nullable String lastChecked(long epochMs) {
        if (epochMs <= 0) return null;
        try {
            return java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                    .format(java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
