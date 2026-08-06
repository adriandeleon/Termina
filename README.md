# Termina

A cross-platform terminal emulator built on JavaFX. Runs a real shell on a real pseudo-terminal and
renders the emulated screen to a canvas.

Status: **early**. A working terminal — colour, styling, scrollback, resize, alternate-screen
programs (vim, less, htop) — with a short list of known gaps at the bottom of this file.

## Running

```bash
mvn javafx:run
```

Requires JDK 25. Everything else is fetched by Maven.

```bash
mvn test
```

## How it fits together

Three pieces, each doing one thing:

| Layer | What it is | Where |
|---|---|---|
| PTY | Starts the shell on a pseudo-terminal and moves bytes | `com.termina.pty` |
| Emulation | Parses escape sequences into a screen buffer | JediTerm (external) |
| Rendering & input | Paints the buffer, encodes keystrokes | `com.termina.ui` |

`TerminalSession` (`com.termina.term`) wires them together and owns the read loop.

**The emulator is not ours.** VT/xterm compatibility is a very long tail — cursor modes, character
sets, scroll regions, mouse protocols, a hundred CSI sequences — and getting it wrong shows up as
programs subtly misbehaving rather than as anything that looks like a bug in a terminal. JetBrains'
[JediTerm](https://github.com/JetBrains/jediterm) core does that part. It is UI-agnostic: it owns a
`TerminalTextBuffer` and knows nothing about how it gets drawn.

**The PTY is not ours either.** Java has no PTY API, and the platforms disagree fundamentally —
Unix has `forkpty`, Windows has ConPTY (and winpty before it).
[pty4j](https://github.com/JetBrains/pty4j) covers all of them, and ships every platform's native
library in a single jar, so there is no per-OS native build step.

What *is* ours is the JavaFX half: the canvas renderer, the key encoder, the colour resolution, and
the session lifecycle.

### Rendering

`TerminalView` paints `TerminalTextBuffer` onto a `Canvas`.

Repaints are **decoupled from output**. The emulator signals a change per write — thousands a
second when a command floods output — so a change only sets a dirty flag, and one `AnimationTimer`
repaints at most once a frame. Painting per signal would starve the UI thread during any large
output.

Drawing is per **style run**, not per character: `processScreenLines` hands back each run of
identically-styled text and each becomes one `fillText`. Runs containing non-ASCII fall back to
per-glyph drawing, because a double-width character (CJK, emoji) occupies two cells and its
natural advance would otherwise push the rest of the line off the grid.

### Input

Special keys are encoded by **JediTerm**, not by us, via `TerminalStarter.getCode`. The right bytes
depend on emulator state: in application-cursor-key mode Up is `ESC O A`, otherwise `ESC [ A`.
Hard-coding either breaks half the programs a terminal exists to run. Printable characters go
through `KEY_TYPED` instead, which is the only event that reports the *composed* character — what
makes dead keys, AltGr layouts, and IME input work.

## Notable details

- **Login shells.** A GUI process inherits a stripped `PATH` with no Homebrew and no version-manager
  directories. The shell is started with `-l` so the user's profile puts them back.
- **AWT is forced headless** in `App.main`. We require `java.desktop` (JediTerm's `TtyConnector`
  declares `java.awt.Dimension` overloads) but never use AWT — and on macOS an initialised AWT
  contends with JavaFX for the AppKit run loop, which hangs rather than fails.
- **Explicit `requires` for `kotlin.stdlib` and `org.slf4j`.** JediTerm and pty4j are automatic
  modules; an automatic module reads everything in the graph but does not pull explicit modules
  *into* it. Without those two lines the build is clean and the first emulator call dies with
  `NoClassDefFoundError`.
- **Paste** is `Cmd+V` on macOS and `Ctrl+Shift+V` elsewhere — plain `Ctrl+V` is readline's
  literal-next and belongs to the shell.

## Development capture

Terminal bugs are visual and none of them throw. `DevCapture` renders the real window, types into
the real shell, and writes a PNG:

```bash
java --module-path <deps> --add-modules com.termina \
  -Dtermina.capture=/tmp/shot.png \
  -Dtermina.captureCommand='ls --color=always' \
  -m com.termina/com.termina.App
```

## Not done yet

- **Selection and copy.** `TerminalDisplay.getSelection()` returns null; there is no mouse selection.
- **Mouse reporting.** The modes are tracked but no mouse events are forwarded, so click-to-position
  in vim and scrolling in htop do not work.
- **Tabs and splits.** One session per window.
- **Configuration.** Font, colours, shell, and scrollback depth are constants.
- **Search in scrollback.**
- **Packaging.** No `jpackage`/`jlink` profile yet. That will need moditect descriptors for the four
  automatic modules (`jediterm-core`, `pty4j`, `jna`, `jna-platform`) before jlink can link them.
- **Windows and Linux are untested.** The code paths exist and pty4j carries the natives, but only
  macOS has actually been run.
