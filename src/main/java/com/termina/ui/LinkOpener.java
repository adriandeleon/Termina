package com.termina.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.termina.link.LinkActions;
import com.termina.link.OpenCommand;

/**
 * Opens what was clicked.
 *
 * <p>URLs go to JavaFX's own {@code HostServices}, which is already wired up for the release-page
 * link. Files go to the configured command when there is one, and to the desktop's opener when
 * there is not — {@code open}, {@code xdg-open} or {@code start}, the same thing that happens on a
 * double-click in the file manager.
 */
final class LinkOpener implements LinkActions {

    /**
     * Launching is off the FX thread even though nothing waits for the result.
     *
     * <p>{@code ProcessBuilder.start()} forks, and a fork can block for as long as the OS wants —
     * on a loaded machine, or with a large heap to copy, long enough to drop frames. There is no
     * result to come back for; the thread exists so the click returns immediately.
     */
    private static final Executor LAUNCHER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "link-opener");
        thread.setDaemon(true);
        return thread;
    });

    private final Consumer<String> openUrl;
    private final Supplier<String> fileCommand;
    private final Consumer<String> onError;

    /**
     * @param openUrl the desktop's URL opener, supplied by App from HostServices
     * @param fileCommand the configured command template, read at each click so a settings change
     *     applies to the next one without any re-wiring
     * @param onError how to say that opening failed, which is otherwise silent — a click that does
     *     nothing is indistinguishable from a click that missed
     */
    LinkOpener(Consumer<String> openUrl, Supplier<String> fileCommand, Consumer<String> onError) {
        this.openUrl = openUrl;
        this.fileCommand = fileCommand;
        this.onError = onError;
    }

    @Override
    public void openUrl(String url) {
        if (url == null || url.isBlank()) return;
        openUrl.accept(url);
    }

    @Override
    public void openFile(Path file, int line, int column) {
        if (file == null) return;
        List<String> argv = OpenCommand.forTemplate(fileCommand.get(), file, line, column);
        if (argv.isEmpty()) {
            // No command configured, so the position has nowhere to go: `open` and `xdg-open` take
            // a file and nothing else. The file still opens, at the top.
            argv = OpenCommand.systemOpen(System.getProperty("os.name", ""), file.toString());
        }
        launch(argv);
    }

    private void launch(List<String> argv) {
        if (argv.isEmpty()) return;
        LAUNCHER.execute(() -> {
            try {
                new ProcessBuilder(argv)
                        // An undrained pipe is this codebase's recurring way to wedge a child. We
                        // never read either stream, so neither may be one.
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .start();
                // Deliberately not waited for. The opener hands off to another application and
                // exits, or is the application; either way its exit code says nothing about
                // whether the user got their file.
            } catch (IOException | RuntimeException e) {
                if (onError != null) onError.accept(argv.get(0));
            }
        });
    }
}
