package com.termina;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository's documents keep the shape Pauta 2.0.2 asks of them (REPO-02, REPO-05, REPO-06,
 * REPO-07, REPO-09, DOCS-12, DOCS-17): the files exist, the agent guide has its three operational
 * sections, and the changelog stays in Keep a Changelog form with a bold summary on every entry.
 */
class RepositoryDocsTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path)).replace("\r\n", "\n");
    }

    @Test
    void theContributorDocumentsExist() {
        List<String> missing = new ArrayList<>();
        for (String path : List.of(
                "CONTRIBUTING.md",
                "docs/README.md",
                "docs/architecture.md",
                "docs/conventions.md",
                "docs/testing.md",
                "docs/gotchas.md",
                "docs/release.md",
                "docs/building-and-packaging.md",
                "docs/decisions/README.md",
                ".github/ISSUE_TEMPLATE/bug_report.yml")) {
            if (!Files.isRegularFile(Path.of(path))) missing.add(path);
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void theAgentGuideHasCommandsWorktreesAndAChecklistAndClaudeMdPointsToIt() throws IOException {
        String agents = read("AGENTS.md");
        for (String heading : List.of("## Essential commands", "## Worktrees", "## Change checklist")) {
            assertTrue(agents.contains("\n" + heading), "AGENTS.md has no " + heading);
        }
        assertTrue(agents.contains("the code wins"), "AGENTS.md must say the code wins");
        assertTrue(read("docs/README.md").contains("the code wins"), "docs/README.md must say the code wins");
        assertTrue(read("CLAUDE.md").contains("@AGENTS.md"), "CLAUDE.md must point to AGENTS.md");
    }

    @Test
    void theBugFormHasTheFieldsAReportFills() throws IOException {
        String form = read(".github/ISSUE_TEMPLATE/bug_report.yml");
        assertTrue(form.contains("id: environment"));
        assertTrue(form.contains("id: report"));
    }

    @Test
    void theChangelogIsKeepAChangelogWithABoldSummaryOnEveryEntry() throws IOException {
        String changelog = read("CHANGELOG.md");
        assertTrue(changelog.contains("keepachangelog.com/en/1.1.0"), "name Keep a Changelog 1.1.0");
        assertTrue(changelog.contains("semver.org"), "name Semantic Versioning");
        String firstVersion =
                changelog.lines().filter(l -> l.startsWith("## ")).findFirst().orElseThrow();
        assertEquals("## [Unreleased]", firstVersion);

        Set<String> types = Set.of("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security");
        List<String> wrong = new ArrayList<>();
        for (String line : changelog.lines().toList()) {
            if (line.startsWith("## ")
                    && !line.equals("## [Unreleased]")
                    && !line.matches("## \\[\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+)?] - \\d{4}-\\d{2}-\\d{2}")) {
                wrong.add(line);
            }
            if (line.startsWith("### ") && !types.contains(line.substring(4))) wrong.add(line);
            if (line.startsWith("- ") && !line.startsWith("- **")) wrong.add(line);
        }
        assertEquals(List.of(), wrong);
    }
}
