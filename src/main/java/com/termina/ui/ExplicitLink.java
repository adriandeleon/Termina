package com.termina.ui;

import java.util.Locale;

import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter;
import com.jediterm.terminal.model.hyperlinks.LinkInfo;
import com.jediterm.terminal.model.hyperlinks.LinkResult;
import com.jediterm.terminal.model.hyperlinks.LinkResultItem;
import com.termina.link.LinkScanner;

/**
 * A link a program declared with OSC 8 — {@code ls --hyperlink}, compilers, {@code gh} — carried
 * on the cells it covers.
 *
 * <p>JediTerm parses OSC 8 and then drops it unless a filter turns the URI into a {@link LinkInfo};
 * with one, it styles the cells written until the link closes with a {@code HyperlinkStyle} holding
 * that object. A {@code LinkInfo} only knows how to run a callback, so this one also keeps the URI,
 * which is what the hover, the status line and the context menu need to say where it goes. It is
 * opened by the view like any other link, through {@link com.termina.link.LinkActions}; the
 * callback is never used.
 *
 * <p>The same scheme allowlist as links found in the text. OSC 8 is exactly where a program can
 * show one thing and point at another, and a {@code javascript:} or custom-handler URI behind
 * innocent text is not something a click in a terminal should reach.
 */
final class ExplicitLink extends LinkInfo {

    private final String uri;

    private ExplicitLink(String uri) {
        super(() -> {});
        this.uri = uri;
    }

    String uri() {
        return uri;
    }

    /** What the emulator is given: a link for an allowed URI, nothing for any other. */
    static final HyperlinkFilter FILTER = uri -> {
        if (!opens(uri)) return null;
        return new LinkResult(new LinkResultItem(0, uri.length(), new ExplicitLink(uri)));
    };

    /** Whether a URI has a scheme we will open — {@link LinkScanner#SCHEMES}. */
    static boolean opens(String uri) {
        if (uri == null) return false;
        int colon = uri.indexOf(':');
        if (colon <= 0) return false;
        return LinkScanner.SCHEMES.contains(uri.substring(0, colon).toLowerCase(Locale.ROOT));
    }
}
