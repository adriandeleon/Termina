package com.termina.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * Which of several edits to one thing is the newest, for edits whose answers come back later and
 * in any order.
 *
 * <p>A profile's form is committed whole, and whether its command and its folder exist is asked
 * off the FX thread, each commit on a thread of its own. Two commits a moment apart can therefore
 * answer out of order, and the older one, applied last, wrote the folder it had seen back over the
 * newer one's. Each commit takes a number here as it is made, and only the one holding the newest
 * number for its profile is applied: it was made from the form as it stood last, so it has
 * everything the older ones had.
 *
 * <p>Used from one thread, the FX thread: a number is taken when the edit is committed and asked
 * about when its answer is handed back there.
 */
final class LatestEdit {

    private final Map<String, Long> newest = new HashMap<>();
    private long counter;

    /** Takes the number of a new edit to {@code id}, which becomes its newest. */
    long begin(String id) {
        long edit = ++counter;
        newest.put(id, edit);
        return edit;
    }

    /** Whether {@code edit} is still the newest edit to {@code id}: no later one has begun. */
    boolean isNewest(String id, long edit) {
        Long latest = newest.get(id);
        return latest != null && latest == edit;
    }
}
