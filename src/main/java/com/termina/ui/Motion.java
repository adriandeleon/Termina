package com.termina.ui;

import javafx.animation.Animation;
import javafx.application.Platform;

/**
 * Motion follows the OS (ACC-04, Pauta 6.0.1): every animation that moves, fades or flashes part of
 * the UI starts here, and while the OS asks for reduced motion it jumps to its end. Read as each
 * one starts, so a change to the OS's setting applies to the next.
 *
 * <p>Not for a timer that only waits (a debounce, a settle): nothing moves while it runs.
 */
final class Motion {

    private Motion() {}

    /** Whether motion plays now: not while the OS asks for less of it. FX thread. */
    static boolean plays() {
        return plays(Platform.getPreferences().isReducedMotion());
    }

    /** The decision, pure. */
    static boolean plays(boolean reducedMotion) {
        return !reducedMotion;
    }

    /**
     * Starts {@code animation} from its beginning, or with motion reduced takes it straight to its
     * end, where its finish handler runs as it would have.
     */
    static void play(Animation animation) {
        if (plays()) {
            animation.playFromStart();
            return;
        }
        animation.stop();
        animation.jumpTo("end");
        var finished = animation.getOnFinished();
        if (finished != null) finished.handle(new javafx.event.ActionEvent(animation, null));
    }
}
