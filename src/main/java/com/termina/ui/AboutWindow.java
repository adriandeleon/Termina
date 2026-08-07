package com.termina.ui;

import static com.termina.i18n.Messages.tr;

import com.termina.AppInfo;
import com.termina.config.Settings;
import com.termina.update.ReleaseInfo;
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
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/** About: what this build is, where it came from, and whether something newer exists. */
public final class AboutWindow {

    private final Stage stage = new Stage();
    private final VBox updateRow = new VBox(4);
    private final Consumer<String> openLink;
    private final Settings settings;

    private ReleaseInfo update;

    public AboutWindow(Settings settings, Consumer<String> openLink) {
        this.settings = settings;
        this.openLink = openLink;
        build();
    }

    /** @param update the newer release, or null when there is none */
    public void setUpdate(ReleaseInfo update) {
        this.update = update;
        refreshUpdateRow();
    }

    public void show(Window owner) {
        if (owner != null && stage.getOwner() == null) {
            stage.initOwner(owner);
            stage.initModality(Modality.NONE);
        }
        refreshUpdateRow();
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
            return;
        }
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
        details.getChildren().addAll(
                new Label(AppInfo.COPYRIGHT),
                new Label(AppInfo.LICENSE));

        Hyperlink homepage = new Hyperlink(AppInfo.HOMEPAGE);
        homepage.setOnAction(e -> openLink.accept(AppInfo.HOMEPAGE));

        Label settingsPath = new Label(tr("about.settings", settings.file()));
        settingsPath.getStyleClass().add("about-path");
        settingsPath.setWrapText(true);

        Label credits = new Label(
                tr("about.credits"));
        credits.getStyleClass().add("about-credits");
        credits.setWrapText(true);

        updateRow.getStyleClass().add("about-update");

        Button close = new Button(tr("about.close"));
        close.setOnAction(e -> stage.hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(spacer, close);
        footer.setAlignment(Pos.CENTER_RIGHT);

        VBox titles = new VBox(2, name, version);
        heading.getChildren().add(titles);

        VBox root = new VBox(10, heading, details, homepage, updateRow, settingsPath, credits, footer);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.TOP_LEFT);
        VBox.setMargin(footer, new Insets(8, 0, 0, 0));

        Scene scene = new Scene(root, 460, 380);
        var css = AboutWindow.class.getResource("/com/termina/styles/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        Fonts.installUiFont(scene);
        stage.setScene(scene);

        refreshUpdateRow();
    }

    private void refreshUpdateRow() {
        updateRow.getChildren().clear();
        if (update == null) {
            Label upToDate = new Label(tr("about.upToDate"));
            upToDate.getStyleClass().add("about-uptodate");
            updateRow.getChildren().add(upToDate);
            return;
        }
        Label headline = new Label(tr("about.updateHeadline", update.version()));
        headline.getStyleClass().add("about-update-headline");
        Hyperlink download = new Hyperlink(tr("about.openReleasePage"));
        download.setOnAction(e -> {
            String url = update.url() == null || update.url().isBlank()
                    ? AppInfo.RELEASES_PAGE
                    : update.url();
            openLink.accept(url);
        });
        updateRow.getChildren().addAll(headline, download);
    }
}
