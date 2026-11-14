package com.termina.ui;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Which tabs a bulk close takes, and why it would take none: the rule behind the File menu's
 * greying, the palette's reason and the closes themselves, kept apart from all three so it is
 * tested once. A pinned tab is never taken by a bulk close (TABS-04, Pauta 2.0.2); only closing it
 * by itself does.
 */
final class TabRanges {

    static final String CLOSE_OTHERS = "file.closeOtherTabs";
    static final String CLOSE_LEFT = "file.closeTabsToTheLeft";
    static final String CLOSE_RIGHT = "file.closeTabsToTheRight";

    private TabRanges() {}

    /**
     * The positions command {@code id} closes, left to right.
     *
     * @param index the tab it is run on, or -1 when none
     * @param pinned for each tab in strip order, whether it is pinned
     */
    static List<Integer> closing(String id, int index, List<Boolean> pinned) {
        List<Integer> out = new ArrayList<>();
        if (index < 0 || index >= pinned.size()) return out;
        for (int i : span(id, index, pinned.size())) {
            if (!pinned.get(i)) out.add(i);
        }
        return out;
    }

    /**
     * The catalogue key saying why command {@code id} would close nothing, or null when it would
     * close at least one tab.
     */
    static @Nullable String unavailable(String id, int index, List<Boolean> pinned) {
        int count = pinned.size();
        if (index < 0 || index >= count) return "palette.disabled.noTab";
        if (span(id, index, count).isEmpty()) {
            return switch (id) {
                case CLOSE_OTHERS -> "palette.disabled.onlyTab";
                case CLOSE_LEFT -> "palette.disabled.noTabsLeft";
                case CLOSE_RIGHT -> "palette.disabled.noTabsRight";
                default -> null;
            };
        }
        return closing(id, index, pinned).isEmpty() ? "palette.disabled.allPinned" : null;
    }

    /**
     * Why the tab at {@code index} cannot move one place left ({@code delta} below zero) or right,
     * as a catalogue key, or null when it can. Moving does not wrap, so the first tab has nowhere
     * to go left and the last nowhere right.
     */
    static @Nullable String moveUnavailable(int delta, int index, int count) {
        if (index < 0 || index >= count) return "palette.disabled.noTab";
        if (delta < 0 && index == 0) return "palette.disabled.firstTab";
        if (delta > 0 && index == count - 1) return "palette.disabled.lastTab";
        return null;
    }

    /** The positions the command reaches before pinned tabs are spared. */
    private static List<Integer> span(String id, int index, int count) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boolean reached = switch (id) {
                case CLOSE_OTHERS -> i != index;
                case CLOSE_LEFT -> i < index;
                case CLOSE_RIGHT -> i > index;
                default -> false;
            };
            if (reached) out.add(i);
        }
        return out;
    }
}
