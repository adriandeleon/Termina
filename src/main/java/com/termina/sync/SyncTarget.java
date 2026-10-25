package com.termina.sync;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** This computer's side of a sync: the live data, read and written as the repository's files. */
public interface SyncTarget {

    /** The repository paths this computer syncs. Every other file in the repository is left as it is. */
    Set<String> paths();

    /** What this computer holds now, as repository path to text; a path that is absent has no file. */
    Map<String, String> read() throws IOException;

    /**
     * Writes {@code changes} (path to new text, or to {@code null} for "no file") — but only when each of
     * them still holds what {@code expected} says it held. Returns false, having written nothing, when one
     * changed in the meantime.
     */
    boolean apply(Map<String, String> expected, Map<String, String> changes) throws IOException;
}
