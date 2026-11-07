package com.termina;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Pauta edition is one number (ABOUT-08, Pauta 2.0.2): {@code pauta.version} in the pom,
 * filtered into the build, and named again by AGENTS.md and the README. Moving editions changes
 * all of them in one change; this fails the build when one is left behind.
 */
class AppInfoTest {

    private static String pomEdition() throws IOException {
        Matcher m = Pattern.compile("<pauta\\.version>([^<]+)</pauta\\.version>")
                .matcher(Files.readString(Path.of("pom.xml")));
        assertTrue(m.find(), "pom.xml names no pauta.version");
        return m.group(1).trim();
    }

    @Test
    void theBuildCarriesThePomsEdition() throws IOException {
        assertEquals(pomEdition(), AppInfo.PAUTA_EDITION);
        assertEquals("https://pauta-project.dev/" + pomEdition() + "/", AppInfo.pautaUrl());
        assertFalse(AppInfo.pautaUrl().contains("latest"));
    }

    @Test
    void agentsAndReadmeNameTheSameEdition() throws IOException {
        String edition = pomEdition();
        String agents = Files.readString(Path.of("AGENTS.md"));
        assertTrue(agents.contains("built to Pauta " + edition), "AGENTS.md does not name Pauta " + edition);
        assertTrue(agents.contains("https://pauta-project.dev/" + edition + "/llms.txt"));
        String readme = Files.readString(Path.of("README.md"));
        assertTrue(readme.contains("Pauta-" + edition), "README badge does not name Pauta " + edition);
        assertTrue(readme.contains("https://pauta-project.dev/" + edition + "/"));
    }

    @Test
    void detailsOpenWithNameAndVersionAndCarryTheEdition() {
        List<String> details = AppInfo.details();
        // The first line is what anything parsing --version reads.
        assertEquals(AppInfo.NAME + " " + AppInfo.VERSION, details.getFirst());
        assertTrue(details.contains("Pauta: " + AppInfo.PAUTA_EDITION));
        assertTrue(details.stream().anyMatch(l -> l.startsWith("Java: ")));
        assertTrue(details.stream().anyMatch(l -> l.startsWith("JavaFX: ")));
    }
}
