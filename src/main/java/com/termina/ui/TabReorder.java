package com.termina.ui;

/**
 * Where a dragged tab lands.
 *
 * <p>Pure, and separate, because it is the classic off-by-one: the drop position is expressed
 * against the list as it looks <em>during</em> the drag, but the insert happens against the list
 * with the dragged tab already taken out of it. Getting it wrong moves the tab one place from where
 * it was dropped, which looks like sloppiness rather than a bug and is easy to live with by
 * accident.
 */
final class TabReorder {

    private TabReorder() {}

    /**
     * @param from index of the tab being dragged
     * @param over index of the tab it was dropped on
     * @param after true when dropped on the right half of that tab, i.e. after it
     * @param count how many tabs there are
     * @return the index to insert at once {@code from} has been removed, or {@code from} itself
     *     when the drop is a no-op
     */
    static int insertIndex(int from, int over, boolean after, int count) {
        if (count <= 1) return from;
        if (from < 0 || from >= count || over < 0 || over >= count) return from;

        int target = over + (after ? 1 : 0);
        // Removing the dragged tab shifts everything above it down one.
        if (target > from) target--;
        if (target < 0) target = 0;
        if (target > count - 1) target = count - 1;
        return target;
    }

    /** Whether a drop actually moves anything, so a plain click is not treated as a reorder. */
    static boolean isMove(int from, int to) {
        return from != to && from >= 0 && to >= 0;
    }
}
