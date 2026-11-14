package com.termina;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Pauta 2.0.2 release rules that live in the workflows, which nothing else runs before a tag:
 * pinned actions (REL-22), read-only defaults (REL-24), a timeout on every job (REL-26), and the
 * steps a release must not lose — the download check before publishing, both attestations
 * (REL-07, REL-18, QUAL-15). Dependabot keeps the pins moving (REL-23).
 */
class WorkflowRulesTest {

    private static final List<Path> WORKFLOWS =
            List.of(Path.of(".github/workflows/ci.yml"), Path.of(".github/workflows/release.yml"));

    private static String read(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }

    @Test
    void everyActionIsPinnedToACommitWithItsVersionBeside() throws IOException {
        Pattern uses = Pattern.compile("^\\s*(?:- )?uses:\\s*(\\S+)(.*)$", Pattern.MULTILINE);
        Pattern pinned = Pattern.compile("[^@\\s]+@[0-9a-f]{40}");
        Pattern comment = Pattern.compile("\\s*# v\\d+\\.\\d+\\.\\d+\\s*");
        List<String> loose = new ArrayList<>();
        for (Path workflow : WORKFLOWS) {
            Matcher m = uses.matcher(read(workflow));
            while (m.find()) {
                if (!pinned.matcher(m.group(1)).matches()
                        || !comment.matcher(m.group(2)).matches()) {
                    loose.add(workflow.getFileName() + ": " + m.group().trim());
                }
            }
        }
        assertEquals(List.of(), loose);
    }

    @Test
    void everyWorkflowStartsReadOnly() throws IOException {
        for (Path workflow : WORKFLOWS) {
            assertTrue(
                    read(workflow).contains("\npermissions:\n  contents: read\n"),
                    workflow + " does not start from contents: read");
        }
    }

    @Test
    void everyJobHasATimeout() throws IOException {
        List<String> without = new ArrayList<>();
        for (Path workflow : WORKFLOWS) {
            String text = read(workflow);
            String jobs = text.substring(text.indexOf("\njobs:\n"));
            // A job is a key at two spaces under jobs:; its block runs to the next such key.
            Matcher job = Pattern.compile("\n  ([A-Za-z0-9_-]+):\n").matcher(jobs);
            List<int[]> starts = new ArrayList<>();
            List<String> names = new ArrayList<>();
            while (job.find()) {
                starts.add(new int[] {job.start()});
                names.add(job.group(1));
            }
            for (int i = 0; i < starts.size(); i++) {
                int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : jobs.length();
                String block = jobs.substring(starts.get(i)[0], end);
                if (!block.contains("\n    timeout-minutes:"))
                    without.add(workflow.getFileName() + ": " + names.get(i));
            }
            assertTrue(!names.isEmpty(), "found no jobs in " + workflow);
        }
        assertEquals(List.of(), without);
    }

    @Test
    void ciCancelsOnlySupersededPullRequestRuns() throws IOException {
        assertTrue(read(Path.of(".github/workflows/ci.yml"))
                .contains("cancel-in-progress: ${{ github.event_name == 'pull_request' }}"));
        assertTrue(read(Path.of(".github/workflows/release.yml")).contains("cancel-in-progress: false"));
    }

    @Test
    void aReleaseChecksItsDownloadsThenAttestsThemThenPublishes() throws IOException {
        String release = read(Path.of(".github/workflows/release.yml"));
        int check = release.indexOf("scripts/check-release-assets.sh");
        int attest = release.indexOf("subject-path: 'artifacts/*'");
        int publish = release.indexOf("- name: Publish release");
        int checksums = release.indexOf("subject-path: 'out/jreleaser/checksums/checksums_sha256.txt'");
        assertTrue(check > 0, "the release-asset check is gone");
        assertTrue(attest > check, "the downloads must be checked before they are attested");
        assertTrue(publish > attest, "the downloads must be attested before they are published");
        assertTrue(checksums > publish, "the checksums file JReleaser writes is attested after it exists");
    }

    @Test
    void theMacAppIsCheckedForOurTeamThenNotarizedThenLaunched() throws IOException {
        // REL-15 (Pauta 2.0.2): team 8JKSF797L8, notarytool, staple, spctl, and then the notarized
        // app itself is launched once — after the ticket, not only before the dmg was made.
        String release = read(Path.of(".github/workflows/release.yml"));
        int team = release.indexOf("^TeamIdentifier=8JKSF797L8$");
        int notarize = release.indexOf("xcrun notarytool submit");
        int staple = release.indexOf("xcrun stapler staple");
        int assess = release.indexOf("spctl --assess --type execute");
        int launch = release.indexOf("scripts/smoke-test.sh \"$app/Contents/MacOS/Termina\"");
        assertTrue(team > 0, "the team check is gone");
        assertTrue(notarize > team, "the team is checked before anything goes to Apple");
        assertTrue(staple > notarize && assess > staple, "notarize, staple, then assess");
        assertTrue(launch > assess, "the notarized app is launched after it is assessed");
    }

    @Test
    void aReleasesNotesAreItsChangelogSectionWrittenBeforePublishing() throws IOException {
        // DOCS-05, REL-04 (Pauta 2.0.2): what a user reads on a release page was written for users.
        String release = read(Path.of(".github/workflows/release.yml"));
        int written = release.indexOf(
                "scripts/release-notes.sh \"${{ needs.build.outputs.version }}\" > out/release-notes.md");
        assertTrue(written > 0, "the publish job no longer writes the notes from CHANGELOG.md");
        assertTrue(written < release.indexOf("- name: Publish release"), "the notes are written before publishing");
        assertTrue(read(Path.of("jreleaser.yml")).contains("external: out/release-notes.md"));
    }

    @Test
    void dependabotUpdatesActionsAndMavenWeekly() throws IOException {
        String config = read(Path.of(".github/dependabot.yml"));
        for (String ecosystem : List.of("github-actions", "maven")) {
            int at = config.indexOf("package-ecosystem: " + ecosystem);
            assertTrue(at >= 0, ecosystem + " is not updated");
            int next = config.indexOf("package-ecosystem:", at + 1);
            String block = config.substring(at, next < 0 ? config.length() : next);
            assertTrue(block.contains("interval: weekly"), ecosystem + " is not weekly");
        }
    }

    @Test
    void theWholeBuildIsGatedAtTheRulesCoverage() throws IOException {
        // PLAT-12 (Pauta 2.0.2): a BUNDLE rule at 0.90 lines and methods, checked at verify.
        String pom = read(Path.of("pom.xml"));
        assertTrue(pom.contains("<coverage.bundle.line>0.90</coverage.bundle.line>"), "line floor");
        assertTrue(pom.contains("<coverage.bundle.method>0.90</coverage.bundle.method>"), "method floor");
        assertTrue(pom.contains("<element>BUNDLE</element>"), "a whole-build rule");
        assertTrue(pom.contains("<minimum>${coverage.bundle.line}</minimum>"), "the rule reads the floor");
    }

    @Test
    void everyJavaSetupCachesMavensRepositoryAndTheReleaseHasItsListeningStep() throws IOException {
        // REL-25 (Pauta 6.0.1): each setup-java step has cache: maven.
        for (String workflow : List.of(".github/workflows/ci.yml", ".github/workflows/release.yml")) {
            String text = read(Path.of(workflow));
            java.util.regex.Matcher step = java.util.regex.Pattern.compile(
                            "uses: actions/setup-java@[^\\n]*\\n((?:[ \\t]+[^\\n-][^\\n]*\\n)+)")
                    .matcher(text);
            int steps = 0;
            while (step.find()) {
                steps++;
                assertTrue(step.group(1).contains("cache: maven"), workflow + ": a setup-java step without the cache");
            }
            assertTrue(steps >= 2, workflow + ": the setup-java steps were read: " + steps);
        }
        // ACC-07: the screen-reader pass is a step of its own in the release guide.
        String release = read(Path.of("docs/release.md"));
        assertTrue(
                release.contains("Narrator") && release.contains("VoiceOver"),
                "the release guide names both screen readers");
    }

    @Test
    void theTestingGuideQuotesTheFloorsTheBuildEnforces() throws IOException {
        // QUAL-04 (Pauta 6.0.1): a guide that quotes the floors is held to the pom's.
        String pom = read(Path.of("pom.xml"));
        String guide = read(Path.of("docs/testing.md"));
        java.util.regex.Matcher quoted = java.util.regex.Pattern.compile(
                        "(\\d\\.\\d\\d) lines and (\\d\\.\\d\\d) methods")
                .matcher(guide);
        assertTrue(quoted.find(), "docs/testing.md quotes the whole-build floors");
        assertEquals(property(pom, "coverage.bundle.line"), quoted.group(1), "the line floor the guide quotes");
        assertEquals(property(pom, "coverage.bundle.method"), quoted.group(2), "the method floor the guide quotes");
        assertFalse(quoted.find(), "the floors are quoted once, so there is one place to keep right");
    }

    private static String property(String pom, String name) {
        java.util.regex.Matcher value = java.util.regex.Pattern.compile(
                        "<" + java.util.regex.Pattern.quote(name) + ">([^<]+)</")
                .matcher(pom);
        assertTrue(value.find(), name + " is a property of the pom");
        return value.group(1).strip();
    }

    @Test
    void noProfileThatActivatesByOsOrJdkTouchesTheCoverageGate() throws IOException {
        // PLAT-12 (Pauta 6.0.1): the pom has one set of floors. A runner that measures lower says
        // so on its own command line, in the workflow, where the choice can be read.
        String pom = read(Path.of("pom.xml"));
        java.util.regex.Matcher profile =
                java.util.regex.Pattern.compile("(?s)<profile>(.*?)</profile>").matcher(pom);
        int byOs = 0;
        while (profile.find()) {
            String body = profile.group(1);
            if (!body.contains("<os>") && !body.contains("<jdk>")) continue;
            byOs++;
            assertFalse(body.contains("coverage.bundle."), "an OS profile sets a coverage floor");
            assertFalse(body.contains("jacoco.skip"), "an OS profile skips JaCoCo");
            assertFalse(body.contains("jacoco-check"), "an OS profile touches the check");
        }
        assertTrue(byOs >= 3, "the per-OS profiles were read: " + byOs);
        for (String workflow : List.of(".github/workflows/ci.yml", ".github/workflows/release.yml")) {
            assertTrue(
                    read(Path.of(workflow)).contains("-Dcoverage.bundle.line=0.45"),
                    workflow + " passes the Windows runner's floors");
        }
    }

    @Test
    void theWindowsImageCarriesAConsoleLauncher() throws IOException {
        // CLI-11 (Pauta 6.0.1): Termina.exe has no console, so --help, --version and --report need a
        // launcher that does; it runs the same entry point, because it names no other.
        String launcher = read(Path.of("packaging/windows/termina-cli.properties"));
        assertTrue(launcher.lines().anyMatch("win-console=true"::equals), launcher);
        assertFalse(
                launcher.contains("main-class") || launcher.contains("module="), "it has an entry point of its own");
        assertFalse(launcher.lines().anyMatch(line -> line.startsWith("arguments=")), "it adds arguments");
        String pom = read(Path.of("pom.xml"));
        int windows = pom.indexOf("<id>os-windows</id>");
        int linux = pom.indexOf("<id>os-linux</id>");
        assertTrue(windows > 0 && linux > windows, "the profiles are where this test looks");
        String profile = pom.substring(windows, linux);
        assertTrue(profile.contains("<name>termina-cli</name>"), "the Windows profile adds the launcher");
        assertTrue(profile.contains("packaging/windows/termina-cli.properties"), "from this file");
        assertFalse(pom.substring(linux).contains("termina-cli"), "no other platform gets one");
        assertTrue(
                read(Path.of(".github/workflows/ci.yml")).contains("termina-cli.exe --version"),
                "CI runs it on the Windows image");
    }

    /**
     * PERF-17 (Pauta 6.0.1): the release build precompiles the stylesheets, every build first
     * removes the ones an earlier release build left, and CI asks the linked image for them.
     */
    @Test
    void theReleaseBuildPrecompilesTheStylesheetsAndCiLooksInTheImage() throws IOException {
        String pom = read(Path.of("pom.xml"));
        int dist = pom.indexOf("<id>dist</id>");
        int compile = pom.indexOf("<id>compile-styles</id>");
        assertTrue(dist > 0 && compile > dist, "the dist profile compiles the styles");
        String step = pom.substring(compile, pom.indexOf("</execution>", compile));
        assertTrue(step.contains("<phase>process-classes</phase>"), "before the jar is made");
        assertTrue(step.contains("com.termina.ui.StyleCompiler"));

        int drop = pom.indexOf("<id>drop-stale-bss</id>");
        assertTrue(drop > 0 && drop < dist, "every build, not only a release, drops the stale ones");
        String dropStep = pom.substring(drop, pom.indexOf("</execution>", drop));
        assertTrue(dropStep.contains("<phase>process-resources</phase>"), "before they are compiled again");
        assertTrue(
                dropStep.contains("<excludeDefaultDirectories>true</excludeDefaultDirectories>"), "and nothing else");

        String ci = read(Path.of(".github/workflows/ci.yml"));
        assertTrue(ci.contains("jimage list"), "CI lists the linked image");
        assertTrue(ci.contains("com/termina/styles/$sheet\\.bss"), "and looks for each sheet's .bss");
    }
}
