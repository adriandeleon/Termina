package com.termina.sync;

import java.util.List;

/**
 * What one sync did.
 *
 * @param received entries this computer took from the repository
 * @param sent entries the repository took from this computer
 * @param conflicts entries changed differently on both sides; this computer's version was kept (also in
 *     {@code sent})
 * @param detail git's message for a failure
 */
public record SyncReport(
        Status status, List<String> received, List<String> sent, List<String> conflicts, String detail) {

    public enum Status {
        /** Both sides now hold the merged data. */
        OK,
        /** Git is not installed, or does not start. */
        NO_GIT,
        /** The repository could not be reached or refused the sign-in; nothing was changed anywhere. */
        FETCH_FAILED,
        /** This computer is up to date, but its own changes could not be pushed. */
        PUSH_FAILED,
        /** A newer Termina wrote the repository; this build must not touch it. */
        NEWER_FORMAT,
        /** The merge would remove most of the key bindings; nothing was changed. */
        NEEDS_CONFIRMATION,
        /** The bindings changed here while the sync was running; nothing was changed, the next run retries. */
        LOCAL_BUSY,
        /** A local git or file error. */
        FAILED,
        /** Stopped before anything on this computer was written; the next run starts afresh. */
        CANCELLED
    }

    public boolean ok() {
        return status == Status.OK;
    }

    /** Whether the sync moved anything in either direction. */
    public boolean changedAnything() {
        return !received.isEmpty() || !sent.isEmpty();
    }

    static SyncReport failure(Status status, String detail) {
        return new SyncReport(status, List.of(), List.of(), List.of(), detail == null ? "" : detail);
    }
}
