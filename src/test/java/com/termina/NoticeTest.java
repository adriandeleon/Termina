package com.termina;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REPO-04 (Pauta 2.0.2): NOTICE says what Termina is licensed under and credits everything it ships,
 * and both it and LICENSE go inside what is shipped.
 */
class NoticeTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path)).replace("\r\n", "\n");
    }

    @Test
    void noticeNamesTheAppItsCopyrightAndItsLicence() throws IOException {
        String notice = read("NOTICE");
        assertTrue(notice.startsWith("Termina\n"), "NOTICE opens with the app's name");
        assertTrue(notice.contains("Copyright 2026 Adrian De Leon"));
        assertTrue(notice.contains("Licensed under the MIT License"));
    }

    @Test
    void everyDependencyThatShipsIsCredited() throws IOException {
        String pom = read("pom.xml");
        // The project's own dependencies: the <dependencies> after </dependencyManagement>, before the build.
        String own = pom.substring(pom.indexOf("</dependencyManagement>"), pom.indexOf("<build>"));
        String notice = read("NOTICE").toLowerCase(Locale.ROOT);
        Matcher dependency = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL)
                .matcher(own);
        List<String> uncredited = new ArrayList<>();
        int shipped = 0;
        while (dependency.find()) {
            String block = dependency.group(1);
            if (block.contains("<scope>test</scope>") || block.contains("<scope>provided</scope>")) continue;
            Matcher artifact =
                    Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(block);
            if (!artifact.find()) continue;
            String id = artifact.group(1).toLowerCase(Locale.ROOT);
            shipped++;
            // Credited by its artifact id, or by the project's name, which is its first part.
            String project = id.contains("-") ? id.substring(0, id.indexOf('-')) : id;
            boolean credited = notice.contains(id)
                    || notice.contains(project)
                    || (project.equals("javafx") && notice.contains("openjfx"));
            if (!credited) uncredited.add(id);
        }
        assertTrue(shipped > 5, "found too few dependencies; is the pom still shaped as this test expects?");
        assertEquals(List.of(), uncredited, "a shipped dependency NOTICE does not mention");
    }

    @Test
    void licenseAndNoticeArePackaged() throws IOException {
        String pom = read("pom.xml");
        int resource = pom.indexOf("<targetPath>META-INF</targetPath>");
        assertTrue(resource > 0, "the jar no longer carries META-INF/LICENSE and NOTICE");
        String block = pom.substring(resource, pom.indexOf("</resource>", resource));
        assertTrue(block.contains("<include>LICENSE</include>") && block.contains("<include>NOTICE</include>"));
        // The app image: the dist build hands both to scripts/add-notices.sh.
        int notices = pom.indexOf("scripts/add-notices.sh</argument>");
        assertTrue(notices > 0, "the dist build no longer adds the notices to the app image");
        String arguments = pom.substring(notices, pom.indexOf("</arguments>", notices));
        assertTrue(
                arguments.contains("/LICENSE</argument>") && arguments.contains("/NOTICE</argument>"),
                "the app image gets both too");
    }
}
