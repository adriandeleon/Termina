package com.termina.ui;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.termina.config.ConfigArchive;
import com.termina.config.ConfigArchive.Part;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Import Configuration dialog (SET-12, Pauta 2.0.2). */
@Tag("fx")
class ConfigTransferTest {

    @BeforeAll
    static void startToolkit() {
        System.setProperty("glass.platform", "Headless");
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // Another test in the same JVM got there first, which is the same outcome.
        }
    }

    @Test
    void theDialogOffersOnlyThePartsTheZipHoldsAllTickedAndWillNotImportNothing() throws Exception {
        ConfigArchive.Scan scan = new ConfigArchive.Scan(
                Map.of("theme", "dracula", "font.size", "14"), Map.of("a.properties", new byte[] {1}));

        List<Object> seen = onFx(() -> {
            ConfigTransfer.PartsDialog dialog = ConfigTransfer.PartsDialog.of(Path.of("/home/me/export.zip"), scan);
            var go = dialog.alert().getDialogPane().lookupButton(dialog.go());
            boolean enabledAtFirst = !go.isDisable();
            EnumSet<Part> offered = EnumSet.copyOf(dialog.boxes().keySet());
            String preferencesLabel = dialog.boxes().get(Part.PREFERENCES).getText();
            dialog.boxes().get(Part.THEMES).setSelected(false);
            var afterOne = dialog.ticked();
            dialog.boxes().values().forEach(box -> box.setSelected(false));
            boolean disabledWithNone = go.isDisable();
            boolean danger = go.getStyleClass().contains(atlantafx.base.theme.Styles.DANGER);
            return List.of(
                    enabledAtFirst,
                    offered,
                    preferencesLabel,
                    afterOne,
                    disabledWithNone,
                    danger,
                    dialog.alert().getHeaderText());
        });

        assertTrue((Boolean) seen.get(0), "everything is ticked to start with");
        assertEquals(EnumSet.of(Part.PREFERENCES, Part.THEMES), seen.get(1));
        assertTrue(((String) seen.get(2)).contains("2"), "a part says how much it holds: " + seen.get(2));
        assertEquals(EnumSet.of(Part.PREFERENCES), seen.get(3));
        assertTrue((Boolean) seen.get(4), "Import is unavailable with nothing ticked");
        assertTrue((Boolean) seen.get(5), "Import replaces things, so it is the dangerous button");
        assertTrue(((String) seen.get(6)).contains("export.zip"), String.valueOf(seen.get(6)));
    }

    @Test
    void aRefusalReadsAsAReasonInEveryCase() {
        for (ConfigArchive.Refused.Reason reason : ConfigArchive.Refused.Reason.values()) {
            String text = ConfigTransfer.refusal(new ConfigArchive.Refused(reason, "detail"));
            assertFalse(text.isBlank(), reason.name());
            assertFalse(text.startsWith("config.refused."), "a missing string: " + reason);
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    @Test
    void anExportReadsBackThroughTheImportWithOnlyTheTickedParts(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        java.nio.file.Path home = dir.resolve("home");
        java.nio.file.Files.createDirectories(home);
        com.termina.config.Settings settings = new com.termina.config.Settings(dir.resolve("config/settings.json"));
        settings.load();
        settings.setThemeId("editora-light");
        settings.replaceKeyBindings(Map.of("newTab", "Shortcut+Shift+Y"));
        settings.flush();
        ConfigTransfer transfer = new ConfigTransfer(settings);
        java.util.List<String> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
        Reporter report = (severity, message) -> reports.add(severity + " " + message);

        String was = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            onFx(() -> {
                transfer.export(report);
                return null;
            });
            waitFor(() -> !reports.isEmpty());
        } finally {
            System.setProperty("user.home", was);
        }
        assertTrue(reports.get(0).startsWith("INFO"), reports.toString());
        java.nio.file.Path zip;
        try (var files = java.nio.file.Files.list(home)) {
            zip = files.filter(f -> f.toString().endsWith(".zip")).findFirst().orElseThrow();
        }

        // Changed here since the export; the import brings the bindings back and leaves the theme.
        settings.setThemeId("editora-dark");
        settings.replaceKeyBindings(Map.of("newTab", "Shortcut+Shift+U"));
        onFx(() -> {
            transfer.importZip(zip, report, scan -> java.util.EnumSet.of(Part.KEY_BINDINGS));
            return null;
        });
        waitFor(() -> reports.size() >= 2);
        assertTrue(reports.get(1).startsWith("INFO"), reports.toString());
        assertEquals("Shortcut+Shift+Y", settings.values().get("keys.newTab"));
        assertEquals("editora-dark", settings.themeId());

        // Not a zip: refused, with the reason.
        java.nio.file.Path notZip = dir.resolve("notes.zip");
        java.nio.file.Files.writeString(notZip, "text");
        onFx(() -> {
            transfer.importZip(notZip, report, scan -> java.util.EnumSet.allOf(Part.class));
            return null;
        });
        waitFor(() -> reports.size() >= 3);
        assertTrue(reports.get(2).startsWith("ERROR"), reports.toString());
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            Thread.sleep(50);
        }
    }
}
