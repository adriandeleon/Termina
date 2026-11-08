package com.termina.ui;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import com.termina.config.ConfigArchive;
import com.termina.config.ConfigArchive.Part;
import com.termina.config.Settings;
import org.jspecify.annotations.Nullable;

import static com.termina.i18n.Messages.tr;

/**
 * Export Configuration and Import Configuration (SET-12, Pauta 2.0.2): the Settings window's two
 * buttons and the palette's two commands, which share this.
 *
 * <p>Every file is read and written on a worker; the FX thread asks, shows and applies. The export
 * goes to the home folder under a timestamped name, as Editora's does, and says where. The import
 * reads the zip first, offers only the parts it holds, copies the current configuration to
 * {@code backups/} before replacing anything, and gives the preferences to {@link
 * Settings#replaceStored}, so every window re-applies at once.
 */
final class ConfigTransfer {

    private final Settings settings;

    ConfigTransfer(Settings settings) {
        this.settings = settings;
    }

    /** Writes {@code termina-config-<stamp>.zip} into the home folder and reports where it went. */
    void export(Reporter report) {
        Path home = Path.of(System.getProperty("user.home"));
        Path zip = home.resolve(ConfigArchive.fileName(LocalDateTime.now()));
        background("termina-config-export", () -> {
            try {
                settings.flush();
                ConfigArchive.export(settings.directory(), zip);
                Platform.runLater(() -> report.info(tr("config.export.done", zip)));
            } catch (Exception e) {
                Platform.runLater(() -> report.error(tr("config.export.failed", reason(e))));
            }
        });
    }

    /** Asks for a zip, offers its parts, and imports the ones left ticked. */
    void importFrom(Window owner, Reporter report) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("config.import.title"));
        chooser.setInitialDirectory(new java.io.File(System.getProperty("user.home")));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(tr("config.import.filter"), "*.zip"));
        java.io.File chosen = chooser.showOpenDialog(owner);
        if (chosen == null) return;
        Path zip = chosen.toPath();
        importZip(zip, report, scan -> askParts(owner, zip, scan));
    }

    /**
     * Reads {@code zip} off the FX thread and, if it passes, asks {@code choose} on the FX thread
     * which parts to take; nothing chosen, nothing imported. The file chooser is skipped, for the
     * development capture, which cannot drive a native one.
     */
    void importZip(Path zip, Reporter report, java.util.function.Function<ConfigArchive.Scan, Set<Part>> choose) {
        // A second import while the first zip is still being read replaces it (PERF-03): the dialog
        // that opens is for the zip chosen last.
        scans.submit(
                () -> {
                    try {
                        return new ScanResult(ConfigArchive.scan(zip), null);
                    } catch (ConfigArchive.Refused e) {
                        return new ScanResult(null, tr("config.import.refused", zip.getFileName(), refusal(e)));
                    } catch (Exception e) {
                        return new ScanResult(null, tr("config.import.failed", reason(e)));
                    }
                },
                result -> {
                    ConfigArchive.Scan scan = result.scan();
                    String error = result.error();
                    if (scan == null) {
                        report.error(error == null ? tr("config.import.failed", zip) : error);
                        return;
                    }
                    Set<Part> ticked = choose.apply(scan);
                    if (!ticked.isEmpty()) apply(scan, ticked, report);
                });
    }

    /** A zip read, or why it could not be. */
    private record ScanResult(
            ConfigArchive.@Nullable Scan scan, @Nullable String error) {}

    private final com.termina.work.LatestOnly scans =
            new com.termina.work.LatestOnly(task -> background("termina-config-scan", task), Platform::runLater);

    private void apply(ConfigArchive.Scan scan, Set<Part> ticked, Reporter report) {
        background("termina-config-import", () -> {
            Path backup;
            try {
                settings.flush();
                backup = ConfigArchive.backup(settings.directory(), LocalDateTime.now());
                ConfigArchive.applyFiles(scan, ticked, settings.directory());
                if (ticked.contains(Part.THEMES)) Themes.reload();
            } catch (Exception e) {
                Platform.runLater(() -> report.error(tr("config.import.failed", reason(e))));
                return;
            }
            Platform.runLater(() -> {
                settings.replaceStored(ConfigArchive.merge(settings.values(), scan.settings(), ticked));
                report.info(tr("config.import.done", partNames(ticked), backup));
            });
        });
    }

    /**
     * The dialog: one box per part the zip holds, all ticked, and an Import button that is the
     * dangerous one, since it replaces what is ticked. Returns what was left ticked, or nothing for
     * Cancel.
     */
    private static Set<Part> askParts(Window owner, Path zip, ConfigArchive.Scan scan) {
        PartsDialog dialog = PartsDialog.of(zip, scan);
        dialog.alert().initOwner(owner);
        if (dialog.alert().showAndWait().orElse(ButtonType.CANCEL) != dialog.go()) return Set.of();
        return dialog.ticked();
    }

    /** The import dialog, built apart from showing it so a test can look inside. */
    record PartsDialog(Alert alert, ButtonType go, Map<Part, CheckBox> boxes) {

        static PartsDialog of(Path zip, ConfigArchive.Scan scan) {
            Map<Part, CheckBox> boxes = new EnumMap<>(Part.class);
            List<Node> rows = new ArrayList<>();
            for (Part part : scan.parts()) {
                CheckBox box = new CheckBox(partLabel(part, scan.count(part)));
                box.setSelected(true);
                boxes.put(part, box);
                rows.add(box);
            }
            Label note = new Label(tr("config.import.note"));
            note.setWrapText(true);
            note.setMaxWidth(420);
            rows.add(note);
            VBox content = new VBox(8);
            content.getChildren().setAll(rows);

            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(tr("config.import.title"));
            alert.setHeaderText(tr("config.import.header", String.valueOf(zip.getFileName())));
            alert.getDialogPane().setContent(content);
            ButtonType go = new ButtonType(tr("config.import.action"), ButtonBar.ButtonData.OK_DONE);
            ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(go, cancel);
            if (alert.getDialogPane().lookupButton(go) instanceof Button button) {
                button.getStyleClass().add(atlantafx.base.theme.Styles.DANGER);
                Runnable refresh =
                        () -> button.setDisable(boxes.values().stream().noneMatch(CheckBox::isSelected));
                boxes.values().forEach(box -> box.selectedProperty().addListener((o, was, is) -> refresh.run()));
            }
            Confirm.style(alert.getDialogPane());
            return new PartsDialog(alert, go, boxes);
        }

        Set<Part> ticked() {
            Set<Part> ticked = EnumSet.noneOf(Part.class);
            boxes.forEach((part, box) -> {
                if (box.isSelected()) ticked.add(part);
            });
            return ticked;
        }
    }

    static String partLabel(Part part, int count) {
        return tr("config.part." + part.name(), count);
    }

    static String partNames(Set<Part> parts) {
        List<String> names = new ArrayList<>();
        for (Part part : parts) names.add(tr("config.partName." + part.name()));
        return String.join(", ", names);
    }

    /** Why a zip was refused; the two reasons that need no detail are plain text, not a format. */
    static String refusal(ConfigArchive.Refused e) {
        String key = "config.refused." + e.reason().name();
        return switch (e.reason()) {
            case NOT_A_ZIP, NO_SETTINGS -> tr(key);
            default -> tr(key, String.valueOf(e.getMessage()));
        };
    }

    private static String reason(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void background(String name, Runnable work) {
        Thread worker = new Thread(work, name);
        worker.setDaemon(true);
        worker.start();
    }
}
