module com.termina {
    requires javafx.controls;
    requires javafx.graphics;

    // The VT/xterm emulation core and the PTY. Both are automatic modules (no module-info,
    // no Automatic-Module-Name), so these names are derived from the jar file names —
    // renaming or shading either jar silently breaks the build.
    requires jediterm.core;
    requires pty4j;

    // An automatic module reads every module *in the graph*, but it does not pull explicit
    // modules into it. jediterm-core and pty4j are both Kotlin and both log through slf4j,
    // so without these two lines nothing requires kotlin.stdlib / org.slf4j, they are never
    // resolved, and the first emulator call dies with NoClassDefFoundError at runtime —
    // a clean compile proves nothing here.
    requires kotlin.stdlib;
    requires org.slf4j;

    // TtyConnector declares default resize(java.awt.Dimension) overloads. We never call them,
    // but the interface cannot be verified without java.desktop on the module path.
    // See App.main for why AWT is nonetheless forced headless.
    requires java.desktop;
    // The debug log captures j.u.l output, which is where JavaFX and the emulator report trouble.
    requires java.logging;

    // The update check: one HTTPS request, and a real parser for its response.
    requires java.net.http;
    requires com.fasterxml.jackson.core;

    exports com.termina;
}
