package com.termina.link;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which host names in a file:// URI mean this machine. */
class LocalHostTest {

    @Test
    void noHostAndLocalhostAreAlwaysLocal() {
        assertTrue(LocalHost.isLocal(null, "devbox"));
        assertTrue(LocalHost.isLocal("", "devbox"));
        assertTrue(LocalHost.isLocal("LOCALHOST", ""));
    }

    @Test
    void theMachinesOwnNameIsLocalWhetherShortOrFull() {
        assertTrue(LocalHost.isLocal("devbox", "devbox"));
        assertTrue(LocalHost.isLocal("DevBox.lan", "devbox"));
        assertTrue(LocalHost.isLocal("devbox", "devbox.example.com"));
    }

    @Test
    void anyOtherNameIsNot() {
        assertFalse(LocalHost.isLocal("server", "devbox"));
        assertFalse(LocalHost.isLocal("devbox2", "devbox"));
        assertFalse(LocalHost.isLocal("devbox", ""));
    }
}
