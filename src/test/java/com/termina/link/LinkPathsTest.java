package com.termina.link;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which file a token names. Never whether it exists — that is the caller's question. */
class LinkPathsTest {

    private static final Path CWD = Path.of("/home/dev/project");

    /**
     * An absolute path on whichever platform is running.
     *
     * <p>{@code /etc/hosts} is absolute on Unix and <em>not</em> on Windows, where a path without a
     * drive letter is relative to the current one — so a literal would be testing the platform's
     * definition of absolute rather than this code.
     */
    private static final Path ABSOLUTE = Path.of("/etc/hosts").toAbsolutePath();

    @Test
    void aRelativeTokenResolvesAgainstTheShellsDirectory() {
        // The whole point: a build's output names files relative to where the build ran, and every
        // tab is somewhere different.
        assertEquals(Path.of("/home/dev/project/src/Foo.java"), LinkPaths.resolve("src/Foo.java", CWD));
    }

    @Test
    void anAbsoluteTokenIgnoresIt() {
        assertEquals(ABSOLUTE, LinkPaths.resolve(ABSOLUTE.toString(), CWD));
    }

    @Test
    void dotSegmentsAreNormalised() {
        assertEquals(Path.of("/home/dev/other"), LinkPaths.resolve("../other", CWD));
    }

    @Test
    void tildeIsTheRunningUsersHome() {
        String home = System.getProperty("user.home");
        assertEquals(Path.of(home, "notes.txt"), LinkPaths.resolve("~/notes.txt", CWD));
        assertEquals(Path.of(home), LinkPaths.resolve("~", CWD));
    }

    @Test
    void anotherUsersTildeIsLeftAlone() {
        // ~alice is a shell expansion we cannot do without reading the password database, so it
        // stays a relative path and simply will not exist.
        assertEquals(Path.of("/home/dev/project/~alice/x"), LinkPaths.resolve("~alice/x", CWD));
    }

    @Test
    void aRelativeTokenWithNoDirectoryToResolveAgainstIsNotAPath() {
        assertNull(LinkPaths.resolve("src/Foo.java", null));
        assertEquals(ABSOLUTE, LinkPaths.resolve(ABSOLUTE.toString(), null));
    }

    @Test
    void nothingIsNotAPath() {
        assertNull(LinkPaths.resolve(null, CWD));
        assertNull(LinkPaths.resolve("   ", CWD));
    }

    @Test
    void aFileUrlIsDecodedRatherThanTakenLiterally() {
        // Percent-encoding is the difference between a URL and a path: taken literally this looks
        // for a file whose name contains "%20".
        assertEquals(Path.of("/tmp/a b.txt"), LinkPaths.fromFileUri("file:///tmp/a%20b.txt"));
        assertEquals(Path.of("/tmp/x"), LinkPaths.fromFileUri("file://localhost/tmp/x"));
    }

    @Test
    void anotherHostsFileUrlIsNotOursToOpen() {
        assertNull(LinkPaths.fromFileUri("file://fileserver/share/x"));
    }

    @Test
    void nonFileUrlsAreNotPaths() {
        assertNull(LinkPaths.fromFileUri("https://example.com/a"));
        assertNull(LinkPaths.fromFileUri(null));
    }

    @Test
    void aShareIsRecognisedInEitherSeparator() {
        assertTrue(LinkPaths.isUnc("\\\\host\\share\\x"));
        assertTrue(LinkPaths.isUnc("//host/share/x"));
        assertTrue(LinkPaths.isUnc("\\/host/share"));
        assertTrue(LinkPaths.isUnc("/\\host\\share"));
        assertTrue(LinkPaths.isUnc("\\\\?\\UNC\\host\\share"));
        assertFalse(LinkPaths.isUnc("/tmp/x"));
        assertFalse(LinkPaths.isUnc("\\tmp"));
        assertFalse(LinkPaths.isUnc("C:\\x"));
        assertFalse(LinkPaths.isUnc("/"));
    }

    @Test
    void aShareTokenIsNeverAPath() {
        // Refused before anything can ask whether it exists: on Windows that question is an SMB
        // connection to the host, made by hovering over text any program could print.
        assertNull(LinkPaths.resolve("\\\\evil\\share\\x.txt", CWD));
        assertNull(LinkPaths.resolve("//evil/share/x.txt", CWD));
        assertNull(LinkPaths.resolve("\\/evil/share", CWD));
    }

    @Test
    void aFileUrlNamingAShareThroughAnEmptyHostIsRefused() {
        // file:////host/share has no host part, which reads as local, and a path that is a share.
        assertNull(LinkPaths.fileUriPath("file:////evil/share/x", host -> true));
        assertNull(LinkPaths.fileUriPath("file:///%5C%5Cevil/share/x", host -> true));
        assertNull(LinkPaths.fromFileUri("file:////evil/share/x"));
    }

    @Test
    void aFileUrlOnAnotherHostIsRefusedWhateverItsPath() {
        assertNull(LinkPaths.fileUriPath("file://evil/tmp/x", host -> false));
        assertEquals("/tmp/x", LinkPaths.fileUriPath("file://devbox/tmp/x", "devbox"::equals));
        assertEquals("/tmp/x", LinkPaths.fileUriPath("file://LOCALHOST/tmp/x", host -> false));
        assertEquals("/tmp/x", LinkPaths.fileUriPath("file:///tmp/x", host -> false));
    }

    @Test
    void aDrivePathLosesTheSlashNoPathParserWouldTake() {
        assertEquals("C:/Users/me/a*b", LinkPaths.fileUriPath("file:///C:/Users/me/a%2Ab", host -> true));
    }

    @Test
    void onlyFileUrlsAreFileUrls() {
        assertTrue(LinkPaths.isFileUri("FILE:///tmp"));
        assertFalse(LinkPaths.isFileUri("https://example.com"));
        assertFalse(LinkPaths.isFileUri("/tmp/file:x"));
    }
}
