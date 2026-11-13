package com.termina;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-16 (Pauta 6.0.1): {@code main} starts the config read on a thread of its own before the
 * toolkit, and the app's {@code init} takes that result. Tagged {@code fx} only because the read
 * warms classes of the interface, which other tests in this run want loaded with a toolkit.
 */
@Tag("fx")
class AppReadAheadFxTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
        Platform.setImplicitExit(false);
    }

    @Test
    void theReadMainStartedIsWhatInitTakesAndOnlyOnce() throws Exception {
        Path config = Files.createDirectories(dir.resolve("config"));
        Files.writeString(
                config.resolve("settings.json"),
                "{\n  \"schemaVersion\" : 1,\n  \"settings\" : {\n    \"updates.check\" : false\n  }\n}\n");

        App.readAhead(List.of("--config-dir", config.toString()));
        App.Config read = App.takeReadAhead();
        assertNotNull(read);
        assertEquals(config, read.settings().directory());
        assertFalse(read.settings().updateCheck(), "the file's value, so the file was read");
        assertEquals(config.toString(), read.cli().configDir());

        assertNull(App.takeReadAhead(), "taken once: a second init reads for itself");
    }

    @Test
    void aReadThatFailedIsNoResultSoInitReadsAgain() {
        App.readAhead(null); // no arguments at all: the read throws on its own thread
        assertNull(App.takeReadAhead());
    }

    /** The rule's own check, on the source: main starts the read before launch, and only it reads. */
    @Test
    void mainStartsTheReadBeforeTheToolkitAndStartReadsNoFile() throws Exception {
        String main = Files.readString(Path.of("src/main/java/com/termina/Main.java"));
        int read = main.indexOf("App.readAhead(");
        int launch = main.indexOf("Application.launch(");
        assertTrue(read > 0 && launch > read, "readAhead comes before launch in main");
        assertFalse(main.contains("extends Application"), "the entry point is not the Application subclass");

        String app = Files.readString(Path.of("src/main/java/com/termina/App.java"));
        assertEquals(1, app.split("settings\\.load\\(\\)", -1).length - 1, "one place loads the settings");
        int load = app.indexOf("settings.load()");
        int readConfig = app.indexOf("static Config readConfig(");
        int finish = app.indexOf("private static Prepared finish(");
        assertTrue(readConfig > 0 && readConfig < load && load < finish, "and it is the config read");
        assertTrue(app.contains("\"termina-config\""), "on a named thread of its own");
    }
}
