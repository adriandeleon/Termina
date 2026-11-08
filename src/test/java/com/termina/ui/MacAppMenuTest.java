package com.termina.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** About in the macOS application menu (ABOUT-01, Pauta 2.0.2). */
class MacAppMenuTest {

    private static final String EXPORT = "--add-exports=javafx.graphics/com.sun.glass.ui=com.termina";
    private static final String OPENS = "--add-opens=javafx.graphics/com.sun.glass.ui.mac=com.termina";

    @Test
    void onlyMacOSHasAnApplicationMenu() {
        assertTrue(MacAppMenu.shouldInstall("Mac OS X"));
        assertFalse(MacAppMenu.shouldInstall("Linux"));
        assertFalse(MacAppMenu.shouldInstall("Windows 11"));
    }

    @Test
    void elsewhereItDoesNothingAndDoesNotThrow() {
        if (MacAppMenu.shouldInstall(System.getProperty("os.name", ""))) return;
        assertFalse(MacAppMenu.addAbout("About Termina", () -> {}));
    }

    @Test
    void everyLauncherCarriesTheExportAndOpensTheMacPackageOnlyOnMacOS() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        // javafx:run and the image's launcher both take the export and the per-OS property.
        assertEquals(
                2,
                count(pom, "<option>" + EXPORT + "</option>") + count(pom, "<javaOption>" + EXPORT + "</javaOption>"));
        assertEquals(2, count(pom, ">${glass.mac.opens}<"));
        assertEquals(1, count(pom, "<glass.mac.opens>" + OPENS + "</glass.mac.opens>"), "set by os-mac only");
        assertEquals(1, count(pom, "<glass.mac.opens>" + EXPORT + "</glass.mac.opens>"), "the harmless default");

        for (String script : new String[] {"scripts/dev-run.sh", "scripts/aot-train.sh"}) {
            String text = Files.readString(Path.of(script));
            assertTrue(text.contains(EXPORT), script);
            assertTrue(text.contains(OPENS), script);
            assertTrue(text.contains("Darwin"), script + " opens the mac package on macOS only");
        }
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }
}
