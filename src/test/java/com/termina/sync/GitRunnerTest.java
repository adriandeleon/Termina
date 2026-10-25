package com.termina.sync;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What git is started with, and how its answers are read. */
class GitRunnerTest {

    @Test
    void anInheritedRepositoryIsNeverTheOneActedOn() {
        // A dotfiles setup exports GIT_DIR; left in place, every command would run against it.
        Map<String, String> env = GitRunner.environment(Map.of("GIT_DIR", "/home/me/.dotfiles"), true, "");
        assertTrue(env.containsKey("GIT_DIR"));
        assertNull(env.get("GIT_DIR"));
        assertNull(env.get("GIT_WORK_TREE"));
        assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
        assertEquals("C", env.get("LC_MESSAGES"));
    }

    @Test
    void anAutomaticRunAsksForNothing() {
        Map<String, String> env = GitRunner.environment(Map.of(), false, "");
        assertEquals("", env.get("GIT_ASKPASS"));
        assertEquals("", env.get("SSH_ASKPASS"));
        assertEquals("never", env.get("SSH_ASKPASS_REQUIRE"));
        assertEquals("never", env.get("GCM_INTERACTIVE"));
        assertEquals("ssh -o BatchMode=yes", env.get("GIT_SSH_COMMAND"));
    }

    @Test
    void theUsersOwnSshCommandIsLeftAlone() {
        Map<String, String> env = GitRunner.environment(Map.of("GIT_SSH_COMMAND", "ssh -i ~/.ssh/sync"), false, "");
        assertFalse(env.containsKey("GIT_SSH_COMMAND"), "putting it in the map would replace theirs");
    }

    @Test
    void aRunTheUserAskedForMayUseTheirCredentialHelper() {
        Map<String, String> env = GitRunner.environment(Map.of(), true, "");
        assertFalse(env.containsKey("GIT_ASKPASS"));
        assertFalse(env.containsKey("GIT_SSH_COMMAND"));
    }

    @Test
    void lcAllIsMovedWhereItCannotOutrankTheMessageLanguage() {
        Map<String, String> env = GitRunner.environment(Map.of("LC_ALL", "de_DE.UTF-8"), true, "/usr/bin");
        assertEquals("", env.get("LC_ALL"));
        assertEquals("de_DE.UTF-8", env.get("LC_CTYPE"));
        assertEquals("/usr/bin", env.get("PATH"));
    }

    @Test
    void everyCommandRunsWithoutHooksOrSigning() {
        GitRunner git = new GitRunner(java.util.List.of("git"), java.nio.file.Path.of("."), false, "");
        String argv = String.join(" ", git.argv(false, "commit"));
        assertTrue(argv.contains("core.hooksPath="), argv);
        assertTrue(argv.contains("commit.gpgsign=false"), argv);
        assertTrue(argv.contains("credential.interactive=false"), argv);
        assertTrue(argv.endsWith("commit"), argv);
        assertFalse(String.join(" ", git.argv(true, "fetch")).contains("credential.interactive"));
    }

    @Test
    void aRejectedPushIsToldApartFromAFailedOne() {
        String rejected = "To host:r.git\n ! [rejected]        HEAD -> main (fetch first)\nerror: failed to push";
        assertTrue(new GitRunner.Result(1, "", rejected, false).nonFastForward());
        assertFalse(new GitRunner.Result(128, "", "fatal: Authentication failed", false).nonFastForward());
        assertEquals(
                "fatal: Authentication failed",
                new GitRunner.Result(128, "", "hint: try this\n\nfatal: Authentication failed\n", false).transcript());
    }
}
