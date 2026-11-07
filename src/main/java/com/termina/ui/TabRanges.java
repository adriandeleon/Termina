package com.termina.ui;

import org.jspecify.annotations.Nullable;

/**
 * When a bulk tab close has anything to close: the rule behind the File menu's greying and the
 * palette's reason, kept apart from both so it is tested once.
 */
final class TabRanges {

    static final String CLOSE_OTHERS = "closeOtherTabs";
    static final String CLOSE_LEFT = "closeTabsToTheLeft";
    static final String CLOSE_RIGHT = "closeTabsToTheRight";

    private TabRanges() {}

    /**
     * The catalogue key saying why command {@code id} would close nothing, or null when it would
     * close at least one tab.
     *
     * @param index the selected tab's position, or -1 when none is selected
     * @param count how many tabs the window has
     */
    static @Nullable String unavailable(String id, int index, int count) {
        if (index < 0 || index >= count) return "palette.disabled.noTab";
        return switch (id) {
            case CLOSE_OTHERS -> count < 2 ? "palette.disabled.onlyTab" : null;
            case CLOSE_LEFT -> index == 0 ? "palette.disabled.noTabsLeft" : null;
            case CLOSE_RIGHT -> index == count - 1 ? "palette.disabled.noTabsRight" : null;
            default -> null;
        };
    }
}
