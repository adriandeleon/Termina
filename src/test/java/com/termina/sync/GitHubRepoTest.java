package com.termina.sync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Making the sync repository with the GitHub CLI, with a stand-in gh. */
class GitHubRepoTest {

    @TempDir
    Path tmp;

    @Test
    void theRemoteFollowsTheConfiguredProtocol() {
        String out = "✓ Created repository me/termina-sync on github.com\n  https://github.com/me/termina-sync\n";
        assertEquals("https://github.com/me/termina-sync.git", GitHubRepo.remoteUrl(out, "https\n"));
        assertEquals("git@github.com:me/termina-sync.git", GitHubRepo.remoteUrl(out, "ssh\n"));
        assertEquals("https://github.com/me/termina-sync.git", GitHubRepo.remoteUrl(out, ""));
    }

    @Test
    void anEnterpriseHostAndATrailingDotGitAreUnderstood() {
        assertEquals(
                "git@git.example.com:team/my.sync.git",
                GitHubRepo.remoteUrl("https://git.example.com/team/my.sync.git", "ssh"));
    }

    @Test
    void outputWithoutAnAddressGivesNothing() {
        assertEquals("", GitHubRepo.remoteUrl("", "ssh"));
        assertEquals("", GitHubRepo.remoteUrl(null, "ssh"));
        assertEquals("", GitHubRepo.remoteUrl("GraphQL: Name already exists on this account", "https"));
    }

    @Test
    void aNameIsWhatGitHubAccepts() {
        assertTrue(GitHubRepo.isName("termina-sync"));
        assertTrue(GitHubRepo.isName("my.settings_2"));
        assertFalse(GitHubRepo.isName(""));
        assertFalse(GitHubRepo.isName(null));
        assertFalse(GitHubRepo.isName("me/termina-sync"));
        assertFalse(GitHubRepo.isName("termina sync"));
        assertFalse(GitHubRepo.isName(".."));
        assertFalse(GitHubRepo.isName("--private"), "would reach gh as an option");
        assertFalse(GitHubRepo.isName("x".repeat(101)));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCreatedRepositoryIsHandedBackInGhsProtocol() throws Exception {
        Path gh = fakeGh("""
                case "$1 $2" in
                  "repo create")
                    [ "$3 $4 $5" = "termina-sync --private --description" ] || { echo "bad args: $*" >&2; exit 2; }
                    echo "https://github.com/me/termina-sync"; exit 0 ;;
                  "config get") echo ssh; exit 0 ;;
                esac
                exit 1
                """);
        GitHubRepo.Created created = GitHubRepo.createPrivate(List.of(gh.toString()), "termina-sync", "sync");
        assertTrue(created.ok(), created.error());
        assertEquals("git@github.com:me/termina-sync.git", created.url());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void ghsRefusalIsTheMessage() throws Exception {
        Path gh = fakeGh("""
                echo "GraphQL: Name already exists on this account (createRepository)" >&2
                exit 1
                """);
        GitHubRepo.Created created = GitHubRepo.createPrivate(List.of(gh.toString()), "termina-sync", "sync");
        assertFalse(created.ok());
        assertEquals("", created.url());
        assertEquals("GraphQL: Name already exists on this account (createRepository)", created.error());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void signedInIsWhatAuthStatusSays() throws Exception {
        Path in = fakeGh("[ \"$1 $2\" = \"auth status\" ] && exit 0; exit 1\n");
        assertTrue(GitHubRepo.signedIn(List.of(in.toString())));
        Path out = fakeGh("echo 'You are not logged into any GitHub hosts.' >&2; exit 1\n");
        assertFalse(GitHubRepo.signedIn(List.of(out.toString())));
        assertFalse(GitHubRepo.signedIn(List.of(tmp.resolve("no-gh").toString())), "gh not installed");
    }

    private Path fakeGh(String body) throws Exception {
        Path gh = Files.createTempDirectory(tmp, "fake bin").resolve("gh");
        Files.writeString(gh, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(gh, PosixFilePermissions.fromString("rwxr-xr-x"));
        return gh;
    }
}
