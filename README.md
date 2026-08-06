# Termina

A cross-platform terminal emulator built on JavaFX. Runs a real shell on a real pseudo-terminal and
renders the emulated screen to a canvas.

Status: **early**. A working terminal — colour, styling, scrollback, resize, selection and copy,
alternate-screen programs (vim, less, htop) — with a short list of known gaps at the bottom of this
file.

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

### Selection

Drag to select, double-click for a word, triple-click for a line. Copy is `Cmd+C` / `Ctrl+Shift+C`
(plain `Ctrl+C` is SIGINT and belongs to the shell).

Selection coordinates are the same axis as the scroll origin: row 0 is the top of the live screen
and negative rows reach into history. Extracting the text is JediTerm's `SelectionUtil`, which knows
a wrapped line is one logical line and must not gain a newline in the middle.

Two things follow from output arriving while a selection exists. As lines age off the screen into
history, both the selection and a scrolled-back viewport are shifted to stay on their text —
otherwise a selection made a moment ago comes to refer to different characters, and copying it
produces the wrong thing silently. And `getNextSeparator` returns the last character *of* a word
while a selection's end is exclusive, so word selection adds one; without it double-clicking
`india` copies `indi`.

The highlight is a translucent wash painted *over* the text. Painting it behind would mean threading
selection state through every style run and splitting runs at its edges; the wash keeps the run loop
untouched and stays legible over any of 16 million possible foreground colours.

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

It can also drive the mouse, firing real events at the view so the whole path is exercised — pixel
to cell, anchor to drag, buffer coordinates to extracted text — and printing what copying yields:

```bash
  -Dtermina.captureDrag=0,35,240,75    # drag-select a region
  -Dtermina.captureClick=60,55,2       # x, y, click count (2 = word, 3 = line)
```

## Not done yet

- **Mouse reporting.** The modes are tracked but no mouse events are forwarded, so click-to-position
  in vim and scrolling in htop do not work.
- **Selection refinements.** No autoscroll when dragging past the top or bottom edge, no
  rectangular (block) selection, no copy-on-select, and a selection is dropped on the next
  keystroke rather than surviving it.
- **Tabs and splits.** One session per window.
- **Configuration.** Font, colours, shell, and scrollback depth are constants.
- **Search in scrollback.**
- **Packaging.** No `jpackage`/`jlink` profile yet. That will need moditect descriptors for the four
  automatic modules (`jediterm-core`, `pty4j`, `jna`, `jna-platform`) before jlink can link them.
- **Windows and Linux are untested.** The code paths exist and pty4j carries the natives, but only
  macOS has actually been run.
