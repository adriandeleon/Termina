package com.termina.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.termina.AppInfo;
import com.termina.Os;
import com.termina.config.Settings;
import com.termina.link.CommandPath;
import com.termina.process.ChildPath;
import com.termina.process.CommandOutput;
import com.termina.pty.ShellLauncher;
import com.termina.ui.Doctor.Check;
import org.jspecify.annotations.Nullable;

import static com.termina.i18n.Messages.tr;

/**
 * The Doctor (DIAG-07, Pauta 6.0.1): one row for each program Termina starts and each place it
 * writes, with a status in words, and under a failed one what to install and where from.
 *
 * <p>A window beside the terminals, like About. The checks start programs and may ask the login
 * shell for its PATH, so they run on a worker and the window says it is checking meanwhile
 * (PERF-01). Its two buttons do what the {@code help.doctor} and {@code help.copyDoctor} commands
 * do (KBD-01).
 */
final class DoctorWindow {

    private final Stage stage = new Stage();
    private final Consumer<String> openLink;
    private final Supplier<List<Check>> checks;
    private final VBox list = new VBox(10);
    private final Label summary = new Label();
    private final Button again = new Button(tr("doctor.again"));
    private final Button copy = new Button(tr("doctor.copy"));

    /** The rows last shown; null until the first check has finished. */
    private @Nullable List<Check> rows;

    /** Counts the checks started, so a slow one that is overtaken does not overwrite a newer answer. */
    private int started;

    /** The Doctor for this machine and these settings. */
    DoctorWindow(Consumer<String> openLink, Settings settings) {
        this(openLink, () -> checkThisMachine(settings));
    }

    /**
     * @param checks runs the checks; called on a worker, never the FX thread
     */
    DoctorWindow(Consumer<String> openLink, Supplier<List<Check>> checks) {
        this.openLink = openLink;
        this.checks = checks;

        Label title = new Label(tr("doctor.title"));
        title.getStyleClass().add("about-name");
        Label intro = new Label(tr("doctor.intro", AppInfo.NAME));
        intro.getStyleClass().add("about-version");
        intro.setWrapText(true);
        summary.getStyleClass().add("about-details");
        summary.setWrapText(true);

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("pauta-list");
        scroll.setAccessibleText(tr("doctor.title"));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        again.setOnAction(e -> check());
        copy.setOnAction(e -> copyAsText());
        Button close = new Button(tr("doctor.close"));
        close.setDefaultButton(true);
        close.setOnAction(e -> stage.hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, again, copy, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12, new VBox(4, title, intro), summary, scroll, buttons);
        content.setPadding(new Insets(18));
        Scene scene = UiZoom.scene(content, 640, 520);
        scene.getStylesheets().add(UiZoom.sheet("app.css"));
        Fonts.installUiFont(scene);
        SecondaryWindows.closeOnEscape(stage, scene);
        stage.setTitle(tr("doctor.title"));
        stage.setScene(scene);
        Icons.applyTo(stage);
    }

    /** Shows it beside {@code from} and checks again: what the command does each time it runs. */
    void show(Window from) {
        if (!stage.isShowing()) SecondaryWindows.placeNear(stage, from);
        stage.show();
        stage.toFront();
        check();
    }

    /** Runs the checks on a worker and shows the rows when they are in. */
    void check() {
        int mine = ++started;
        again.setDisable(true);
        copy.setDisable(true);
        copy.setText(tr("doctor.copy"));
        summary.setText(tr("doctor.checking"));
        Thread worker = new Thread(
                () -> {
                    List<Check> found = checks.get();
                    Platform.runLater(() -> {
                        if (mine == started) showRows(found);
                    });
                },
                "termina-doctor");
        worker.setDaemon(true);
        worker.start();
    }

    private void showRows(List<Check> found) {
        rows = found;
        list.getChildren().clear();
        for (Check check : found) list.getChildren().add(row(check));
        summary.setText(summaryOf(found));
        again.setDisable(false);
        copy.setDisable(false);
    }

    /** "Everything Termina relies on is there." or how many rows need a look, worded for the count. */
    static String summaryOf(List<Check> rows) {
        long failures = Doctor.failures(rows);
        return failures == 0 ? tr("doctor.summary.ok", AppInfo.NAME) : tr("doctor.summary.failed", failures);
    }

    /** The status in words, the row's name and what was found; under a failed row, the fix and its link. */
    private Node row(Check check) {
        Label status = new Label(tr(check.status().key()));
        status.getStyleClass()
                .addAll("doctor-status", "doctor-" + check.status().name().toLowerCase(java.util.Locale.ROOT));
        status.setMinWidth(Region.USE_PREF_SIZE);
        Label name = new Label(tr(check.nameKey()));
        name.getStyleClass().add("doctor-name");
        name.setMinWidth(Region.USE_PREF_SIZE);
        Label detail = new Label(check.detail());
        detail.getStyleClass().add("doctor-detail");
        detail.setWrapText(true);
        HBox.setHgrow(detail, Priority.ALWAYS);
        HBox head = new HBox(10, status, name, detail);
        head.setAlignment(Pos.TOP_LEFT);
        VBox row = new VBox(2, head);
        row.getStyleClass().add("doctor-row");
        String fixKey = check.fixKey();
        if (fixKey != null) {
            Label fix = new Label(tr(fixKey));
            fix.setWrapText(true);
            fix.getStyleClass().add("doctor-fix");
            row.getChildren().add(fix);
            String link = check.link();
            if (link != null) {
                Hyperlink get = new Hyperlink(link);
                get.getStyleClass().add("doctor-link");
                get.setOnAction(e -> openLink.accept(link));
                row.getChildren().add(get);
            }
        }
        return row;
    }

    /** Whether there are rows to copy: not before the first check has finished. */
    boolean hasRows() {
        return rows != null;
    }

    /** The Copy as text button, and the {@code help.copyDoctor} command: the rows shown, as plain text. */
    void copyAsText() {
        List<Check> shown = rows;
        if (shown == null) return;
        var content = new javafx.scene.input.ClipboardContent();
        content.putString(text(shown));
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        copy.setText(tr("doctor.copied"));
    }

    /** The report: the app and the system, then a line for each row, the fix under a failed one. */
    static String text(List<Check> rows) {
        return AppInfo.NAME + " " + AppInfo.VERSION + " — " + Os.description() + "\n\n"
                + Doctor.asText(rows, key -> tr(key));
    }

    /** The checks against this machine. Starts programs: never on the FX thread. */
    static List<Check> checkThisMachine(Settings settings) {
        String started = ShellLauncher.shellCommand(settings.shell()).getFirst();
        return Doctor.run(Os.current(), new Machine(), settings.shell().strip(), started, settings.directory());
    }

    /** How long the Doctor waits for the login shell to say its PATH before going on without it. */
    private static final Duration LOGIN_SHELL_WAIT = Duration.ofSeconds(8);

    /** The real answers. */
    private static final class Machine implements Doctor.Probes {
        /**
         * Where the system keeps its own first, which is where most installs are and costs
         * nothing; the login shell's PATH only for what is not there, and for no longer than
         * {@link #LOGIN_SHELL_WAIT}. Asking starts the user's shell, and a profile that hangs
         * would otherwise leave the window saying it is checking for good.
         */
        @Override
        public @Nullable Path userTool(String program) {
            Path system = SystemTools.find(program);
            if (system != null) return system;
            List<Path> directories = loginShellDirectories();
            return directories == null ? null : CommandPath.resolve(program, directories, Files::isExecutable);
        }

        private static @Nullable List<Path> loginShellDirectories() {
            java.util.concurrent.CompletableFuture<List<Path>> asked = new java.util.concurrent.CompletableFuture<>();
            Thread asker = new Thread(
                    () -> {
                        try {
                            asked.complete(LoginShellPath.directories());
                        } catch (RuntimeException failed) {
                            asked.completeExceptionally(failed);
                        }
                    },
                    "termina-doctor-path");
            asker.setDaemon(true);
            asker.start();
            try {
                return asked.get(LOGIN_SHELL_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                return null;
            }
        }

        @Override
        public @Nullable Path systemTool(String program) {
            return SystemTools.find(program);
        }

        @Override
        public @Nullable String firstLine(List<String> argv) {
            try {
                return CommandOutput.read(ChildPath.apply(new ProcessBuilder(argv)), Duration.ofSeconds(3))
                        .map(bytes -> new String(bytes, StandardCharsets.UTF_8)
                                .lines()
                                .findFirst()
                                .orElse(""))
                        .orElse(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public boolean isExecutable(Path file) {
            return Files.isExecutable(file);
        }

        @Override
        public boolean isWritableDirectory(Path directory) {
            return Files.isDirectory(directory) && Files.isWritable(directory);
        }

        @Override
        public @Nullable String env(String name) {
            return System.getenv(name);
        }
    }

    Scene scene() {
        return stage.getScene();
    }

    VBox listForTest() {
        return list;
    }

    Label summaryForTest() {
        return summary;
    }
}
