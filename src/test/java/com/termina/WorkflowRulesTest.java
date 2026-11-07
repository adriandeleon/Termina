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
}
