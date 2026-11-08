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
 * The repository's documents keep the shape Pauta 2.0.2 asks of them (REPO-01, REPO-02, REPO-05,
 * REPO-06, REPO-07, REPO-09, DOCS-12, DOCS-14, DOCS-17, DOCS-19): the files exist, the README is a
 * landing page that defers to the website, the long catalogues sit under {@code docs/reference/},
 * the agent guide has its three operational sections, every relative link resolves, and the
 * changelog stays in Keep a Changelog form with a bold summary on every entry.
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
                "docs/reference/README.md",
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

    @Test
    void theReadmeIsALandingPageThatDefersToTheWebsite() throws IOException {
        // REPO-01 / DOCS-19: about 200 lines, its fixed parts, and the website for everything else.
        String readme = read("README.md");
        long lines = readme.lines().count();
        assertTrue(lines <= 250, "README.md is " + lines + " lines; the catalogue goes to docs/reference/features.md");
        for (String heading : List.of(
                "## Highlights",
                "## Install",
                "## Build from source",
                "## Command line",
                "## Configuration",
                "## License")) {
            assertTrue(readme.contains("\n" + heading + "\n"), "README.md has no " + heading);
        }
        assertTrue(readme.contains("https://termina-project.dev/docs/"), "README.md links the website's docs");
    }

    @Test
    void theShortDocumentsPointIntoDocsAndTheCataloguesAreInReference() throws IOException {
        // DOCS-14: AGENTS.md and CONTRIBUTING.md link into docs/; nothing long sits beside the guides.
        assertTrue(read("AGENTS.md").contains("docs/"), "AGENTS.md points into docs/");
        assertTrue(read("CONTRIBUTING.md").contains("docs/"), "CONTRIBUTING.md points into docs/");
        List<String> stray = new ArrayList<>();
        try (var files = Files.list(Path.of("docs"))) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.matches("(review|pauta-audit)-.*\\.md")) stray.add(name);
            }
        }
        assertEquals(List.of(), stray, "reviews and audits belong in docs/reference/");
    }

    @Test
    void everyRelativeLinkInTheDocumentsResolves() throws IOException {
        // A moved page breaks its links silently; GitHub shows a 404 only to whoever clicks.
        java.util.regex.Pattern link = java.util.regex.Pattern.compile("\\]\\(([^)\\s]+)\\)");
        List<String> broken = new ArrayList<>();
        List<Path> documents = new ArrayList<>(List.of(
                Path.of("README.md"), Path.of("AGENTS.md"), Path.of("CONTRIBUTING.md"), Path.of("CHANGELOG.md")));
        try (var walk = Files.walk(Path.of("docs"))) {
            walk.filter(p -> p.toString().endsWith(".md")).forEach(documents::add);
        }
        for (Path document : documents) {
            // Code is quoted, not linked: fenced blocks and inline spans are left out.
            String text =
                    read(document.toString()).replaceAll("(?s)```.*?```", "").replaceAll("`[^`\\n]*`", "");
            var matcher = link.matcher(text);
            while (matcher.find()) {
                String target = matcher.group(1);
                if (target.matches("^[a-z]+:.*") || target.startsWith("#")) continue;
                String file = target.replaceAll("#.*$", "");
                Path base = document.getParent() == null ? Path.of("") : document.getParent();
                if (!Files.exists(base.resolve(file).normalize())) broken.add(document + " -> " + target);
            }
        }
        assertEquals(List.of(), broken);
    }
}
