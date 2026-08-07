# Termina

[![CI](https://github.com/adriandeleon/Termina/actions/workflows/ci.yml/badge.svg)](https://github.com/adriandeleon/Termina/actions/workflows/ci.yml)

A cross-platform terminal emulator built on JavaFX. Runs a real shell on a real pseudo-terminal and
renders the emulated screen to a canvas.

Status: **early**. A working terminal — tabs, multiple windows, a menu bar, colour and styling,
scrollback, selection and copy, mouse-aware full-screen programs (vim, less, htop), themes and font
settings — with a short list of known gaps at the bottom of this file.

## Running

```bash
./mvnw javafx:run
```

Requires JDK 25. Everything else — including Maven itself, via the wrapper — is fetched on demand.

```bash
./mvnw test
```

### Building a native app image

```bash
./mvnw clean -Pdist -DskipTests package     # => target/dist/Termina{.app}
```

Self-contained: bundled runtime, no JDK needed to run it. `clean` is not optional — jlink's input is
built from `target/classes`, and an incremental compile can leave that inconsistent in ways javac
will not correct on its own.

Four dependencies (`jediterm-core`, `pty4j`, `jna`, `jna-platform`) are **automatic modules**, and
jlink refuses to link those. moditect injects real descriptors; an antrun step then overlays the
patched jars over the plain ones so exactly one copy of each is on the module path.

Two things about this build are worth knowing because neither announces itself:

- **The slf4j binding has to be named explicitly.** It is reached only through `ServiceLoader`, so
  nothing `requires` it and jlink leaves it out. The image builds, launches, and silently drops
  every log line from pty4j and JediTerm — in the one build where a native library failing to load
  is hardest to diagnose.
- **pty4j's native libraries survive by luck, not design.** JPMS encapsulates resources whose
  directory maps to a valid package name. `resources/com/pty4j/native/…` does not, because `native`
  is a reserved word, so it stays readable. The same accident covers JNA (`darwin-aarch64` contains
  a hyphen). Neither would work if those directories were named differently.

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

Special keys are encoded by **JediTerm** via `TerminalStarter.getCode` — but not all of them: its
encoder has no entry for **Tab or Escape**. Both are control characters, so the `KEY_TYPED` path
discards them too, and without a fallback they reach the shell by no route at all. Worse, returning
null leaves the event unconsumed, and JavaFX then treats Tab as focus traversal — focus leaves the
terminal and everything typed afterwards goes somewhere else, which presents as the terminal
freezing. `KeyEncoding.literalFallback` covers them, with `ESC [ Z` for Shift+Tab.

The invariant is that **a key we claim to handle must never return null**, since null means
unconsumed and unconsumed means JavaFX may act on it. That is asserted rather than assumed.

Otherwise, special keys are encoded by JediTerm via `TerminalStarter.getCode`. The right bytes
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

### Mouse reporting

Clicks, drags and the wheel are offered to the program running in the terminal before any local
gesture runs, so vim positions its cursor and htop responds to clicks. Only the *mapping* from
JavaFX events to button codes is ours — JediTerm encodes the escape sequences, because there are
four incompatible wire formats (X10, UTF-8, URXVT, SGR) and the active one is chosen by the running
program.

The mapping mirrors JediTerm's own AWT adapter, which is the implementation its encoder was written
against. Two parts look like bugs and are not: the **motion flag is never set here** (the encoder
derives it from the event type, and setting it twice reports a drag as a different button), and the
**scroll codes read backwards** — `SCROLLDOWN` is sent when the wheel turns up, because the names
describe which way the content moves.

The menu closes on Escape, on a click anywhere in the terminal, or on a click outside the window.
Escape needs handling explicitly: the terminal's key filter runs before the menu would see the key,
and Escape now encodes to a real byte, so without it the menu would stay open and the shell would
receive an ESC nobody asked for.

**Shift bypasses reporting.** That is the xterm convention, and it is the only way to select text
out of a program that has grabbed the mouse — without it htop's output cannot be copied.

On the alternate screen a wheel scroll falls back to arrow keys, so `less` and `man` scroll with the
wheel even though neither ever enables mouse reporting. Scroll magnitude is capped, or one inertial
trackpad fling sends hundreds of keypresses to the shell.

### Tabs, windows and the menu bar

`TerminalWindow` is one window: a menu bar over a tab strip, each tab owning its own
`TerminalView` and its own shell. `WindowManager` owns the set of windows and what they share —
the settings, the single preferences window, and the theme. Sessions are never shared.

**Tab disposal is driven off the tab list, not the close button.** A tab owns a PTY process, two
pump threads and an emulator thread; a removal path that skips disposal leaks all of it and nothing
on screen looks wrong. Listening to the list covers every path at once — the close button, Close
Tab, the shell exiting, and the window closing. Verified by counting child processes rather than by
reading the code: three tabs, close two, one shell left.

**The tab strip hides itself while only one tab is open** (on by default, matching iTerm2, GNOME
Terminal and Windows Terminal; Settings → Appearance → Tabs). JavaFX has no property for this, and
`visibility: hidden` alone leaves the row's height behind as a blank band — min, pref *and* max
height have to be pinned to zero and the padding cleared with them, or the skin's own padding
survives as a few stubborn pixels. Collapsing reclaims the row for the terminal, so the shell gains
a line and gets resized accordingly.

**Menu items and key bindings come from one value.** `MenuAction` holds the label, the accelerator
and the action together, so the menu cannot advertise a shortcut that nothing implements. The
binding is a **scene-level event filter**, not the menu's accelerator: JavaFX fires accelerators
only after an event has bubbled unconsumed, and `TerminalView` consumes `Ctrl+<letter>` first to
encode it as a control byte. Filters run in the capturing phase, ahead of that.

Off macOS an application chord is `Ctrl+Shift+key`, never `Ctrl+key` — the plain form belongs to the
shell (Ctrl+T is readline's transpose, Ctrl+W deletes a word, Ctrl+C is SIGINT). That rule has its
own tests. Whether the shortcut modifier *is* Ctrl or Cmd is JavaFX's decision, not ours, and the
tests deliberately do not assert it.

### Hiding the menu bar

Settings → Appearance → Window, or `Cmd/Ctrl+Shift+M`. **No effect on macOS**, where the menus
belong to the screen menu bar and there is nothing in the window to hide — the settings row is
disabled there and says so, rather than being a switch that silently does nothing.

Hiding it strands no commands: the key bindings are a scene-level filter independent of the menu,
and Settings is on the right-click menu, which is how it comes back.

Hiding it is done in **code**, not CSS, and the two platforms need different mechanisms. Under a
system menu bar the node must stay live — that is what JavaFX forwards from — so it is only
collapsed to zero height, and nothing paints in the window because the menus are elsewhere.
Everywhere else the bar really is in the window, so hiding it sets `visible` and `managed` to false.

CSS was tried first and was wrong: `visibility: hidden` never applied (the node still reported
`isVisible() == true`), and a zero-height Region does not clip its children, so the menu buttons
carried on painting over the terminal's first rows while occupying no space of their own.

`-Dtermina.forceInWindowMenuBar=true` makes a Mac lay out like the other platforms. Without it the
in-window menu bar — the only place this setting does anything — cannot be seen on the machine this
is developed on.

### The tab strip

Tabs tile the full width and shrink as more are added, the way macOS Terminal and GNOME Terminal
lay them out — down to a floor of 60px, past which the strip overflows rather than rendering
slivers with no readable title. Whatever the count, they meet the new-tab button.

An optional **scrollbar** (Appearance → Window, on by default) shows the scrollback and drags it.
It takes a real column of width rather than floating over the text, so the shell is resized when it
appears — a bar drawn over the last column would cover a character of every line. It hides itself on
the alternate screen, where a full-screen program owns the viewport and there is no scrollback to
reach, and it is coloured from the terminal palette rather than the window theme, since the two can
differ.

A **command palette** on Shift+Cmd/Ctrl+P lists every command in the window, found by typing part of
its name — "nw" reaches New Window, "ct" Close Tab. The commands are the menu bar's own actions
rather than a parallel list that could disagree with it, so an entry is in the palette because it is
in a menu, and its shortcut is shown because the action already carries one. The colour themes are
appended, since a menu listing every theme is a submenu nobody opens.

A **debug log** (Advanced → Diagnostics) keeps this session's warnings, errors and uncaught
exceptions, and mirrors them to `termina-session.log` beside the settings. A packaged application
has no console, so without it the one sentence explaining why something failed goes nowhere.

**Six interface languages** — English, Italian, Spanish, French, Portuguese, German — chosen in
Appearance → Language, or followed from the system when left on Automatic. English is both the base
catalogue and the fallback, so a key missing from a translation shows English rather than a gap, and
a test holds the six catalogues to identical key sets: drift here is silent, since nobody testing in
English ever sees it.

**Fonts are bundled**, not borrowed from the machine: five monospace families (JetBrains Mono,
Cascadia Code, Fira Code, IBM Plex Mono, Source Code Pro) and Inter for the interface. A picker over
whatever happens to be installed gives Menlo on macOS, Consolas on Windows and DejaVu on Linux —
three sets of metrics, and no way to describe a setup to anyone else. The bundled families lead the
picker, since they are the only ones guaranteed to be there; every installed monospace face is still
listed after them. They are the same families Editora ships, which is where the themes come from.

**Window size is remembered** across launches, clamped to the screen that is actually present — a
size saved on a 4K display and restored on a laptop would otherwise put the title bar off-screen,
where no mouse can reach it. Only the un-maximized size is stored, so un-maximizing does not restore
to full screen.

The tab-strip glyphs — each tab's close button and the new-tab button — are shaped Regions on one
grid at one size, rather than a shape beside a font character, which never match in weight or
centring. The new-tab button takes the strip's height so its glyph lands on the same line as the
close buttons.

Right-clicking a tab gives New Tab, Close Tab, Close Other Tabs, Close Tabs to the Right, and Move
Tab Left/Right, with the inapplicable ones disabled. It is attached with `Tab.setContextMenu`, so it
acts on the tab that was clicked rather than whichever is selected.

The **window title follows the selected tab**, bound to that terminal's own title property so it
tracks the shell live rather than only at the moment of selection.

The **+** button floats over the right end of the strip rather than being a tab of its own. A
sentinel "+" tab would pollute every count and index in the window — tab disposal, reordering, the
hide-when-single rule, next/previous — each of which would then need to know it is not a real tab.

`TabLayout.tabWidth` is pure and tested. The per-tab chrome constant was **measured**, not guessed:
JavaFX renders a tab at the width set plus about 17px of its own padding, and an earlier value of 38
left a visible gap at seven tabs — arithmetic that tiled correctly against the wrong constant.

### Reordering tabs

Drag a tab, or `Cmd/Ctrl+Shift+Left/Right` from the keyboard. JavaFX has no tab reordering, so the
gesture is built on a `Label` graphic — a `Tab` is not a `Node` and has nowhere to attach drag
handlers.

**A reorder is a remove followed by an add**, which the tab-disposal listener would otherwise read
as "close that session" — dragging a tab would kill the shell being dragged and leave an empty tab
behind, for a gesture meant to change nothing but order. A `reordering` guard suppresses disposal,
and suppresses the close-the-window-when-empty rule with it, since a single-tab window is briefly
empty mid-move.

Where a dragged tab lands is `TabReorder.insertIndex`, kept pure and tested: the drop position is
expressed against the list as it looks *during* the drag, but the insert happens against the list
with the dragged tab already removed. Off by one there moves the tab one place from where it was
dropped, which reads as sloppiness rather than a bug.

### Context menu

Right-click gives Copy, Paste, Select All, Clear Scrollback and Settings. Copy and Paste are
disabled rather than present-and-inert when there is nothing to act on.

Right-click is **contested**, and the rule matters: a terminal wants it for Copy and Paste, while a
mouse-aware TUI (Midnight Commander, some file pickers) wants it as button 2 — and Termina reports
it as such. So while a program has grabbed the mouse it wins, and **Shift forces the menu**, the
same escape hatch that governs selection. Without it there is no way to copy anything, or reach
Settings, from inside htop. The keyboard menu key is never contested. `shouldShowMenu` is a static
predicate with its own tests, because getting it backwards is invisible in a plain shell and breaks
exactly one of the two cases.

### Settings

`Cmd/Ctrl+,` or the context menu opens preferences: theme, font family and size, cursor shape, visual bell, scrollback
depth, shell, and Alt-as-Meta. Stored as a plain properties file at
`~/.termina/settings.properties` — no dependency, and a file someone will reasonably open in an
editor. Every getter falls back to a default, so one bad hand-edited line costs that key and not the
rest, and values are clamped **on read as well as on write** (a hand-edited two-million-line
scrollback would otherwise exhaust memory at the next launch).

The window follows Editora's: a grouped sidebar, a search box that narrows both rows and
categories, and **no OK or Cancel**. Every control writes its setting immediately and the terminal
re-applies. That is the point rather than a shortcut — choosing a font or a theme is a judgement
about how something looks, and a dialog that defers the result until it is dismissed makes you
guess. The Appearance page shows a live sample rendered with the real palette and the real font for
the same reason.

Two settings deliberately do **not** apply live, and say so on their own row: scrollback sizes a
buffer that already exists, and the shell is a process already running. Both take effect in the next
session.

Font choices are restricted to **monospace faces**, filtered by measuring whether `i` and `M` have
the same advance. That is not tidiness — the renderer places every glyph on a fixed cell grid, so a
proportional face misaligns every column on screen.

### Themes

Four. **Editora Dark** and **Editora Light** are carried over from Editora — Caret teal on Ink
navy. Their control stylesheets are AtlantaFX-derived and self-contained, so they are applied with
`Application.setUserAgentStylesheet` directly and AtlantaFX is not a dependency.

**Clear Dark** and **Clear Light** are ported from macOS Terminal, with the colours decoded out of
Apple's own `.terminal` profiles rather than matched by eye. Two caveats, both stated because a port
should be honest about what it is: Apple ships them translucent (95% and 93% over a blurred
desktop) and Termina has no window transparency, so they render opaque; and Clear Light is
genuinely low-contrast in four places — white, bright yellow, bright cyan and bright white against
its white background. That is Apple's choice and it is kept, pinned in `ThemeTest` so nobody
"fixes" it by accident. A ported theme that improves its source is no longer that theme.

Ported palettes bring no opinion about how a settings window should look, so they borrow whichever
Caret & Ink control stylesheet matches their brightness.

The ANSI colours are derived from the matching Editora *editor* theme's syntax palette: keyword
becomes red, string blue, escape green, type yellow, function magenta, and the Caret accent becomes
cyan — so a shell's `ls` colours stay in the same family as the editor's code colours.

On the light theme ANSI "white" is rendered **dark**. It has to be: a program that sets colour 7 on
a white background would otherwise write invisible text. `ThemeTest` enforces this as a contrast
ratio rather than leaving it to judgement, for every colour in both themes.

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

## Diagnosing a freeze

"It froze" has two causes that look identical from the outside — the UI thread blocked, or the
shell had nothing to say yet — and only the first is Termina's. `-Dtermina.stallLog=<ms>` reports
gaps between animation frames, which the FX thread drives, so a long gap means it was genuinely
blocked:

```bash
scripts/dev-run.sh -Dtermina.stallLog=400
JAVA_TOOL_OPTIONS=-Dtermina.stallLog=400 ./target/dist/Termina.app/Contents/MacOS/Termina
```

It is opt-in because an always-running `AnimationTimer` forces a 60fps pulse even when the terminal
is idle.

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
  -Dtermina.captureDragShift=true      # ...holding shift, to bypass mouse reporting
  -Dtermina.captureClick=60,55,2       # x, y, click count (2 = word, 3 = line)
  -Dtermina.captureScroll=400,200,-320 # x, y, deltaY (negative scrolls down)
  -Dtermina.captureMenu=300,300,false  # screenX, screenY, shift — photographs the context menu
  -Dtermina.captureSettings=true       # ...the settings window instead
  -Dtermina.captureTabs=2              # open N extra tabs first
  -Dtermina.captureWindows=1           # ...and N extra windows
  -Dtermina.captureWindowIndex=1       # photograph a window other than the first
  -Dtermina.captureCloseTabs=2         # close N tabs, reporting the child-process count
  -Dtermina.captureTheme=editora-light # switch theme after the windows exist
  -Dtermina.captureChord=T             # fire an application chord at the scene
```

Each run prints a `windows=… terminals=… descendants=…` line. The descendant count is the useful
one — each tab owns a shell, so a tab that closes without reaping it leaks a process and nothing on
screen looks wrong. Read it only once things have settled: a login shell forks several children
while it runs the user's profile, so a count taken too early reads high.

`scripts/dev-run.sh` wraps all of this: it builds the module path itself, so any `-D` can be passed
through (`mvn javafx:run` cannot take ad-hoc options — the plugin's are fixed in the pom).

## Branding

`branding/termina-icon.svg` is the source. The window icons
(`src/main/resources/com/termina/icons/icon-*.png`) and the installer icons (`termina.icns`,
`termina.ico`, `termina.png`) are generated from it — regenerate all of them after editing the SVG:

```bash
for s in 16 24 32 48 64 128 256 512 1024; do
  rsvg-convert -w $s -h $s branding/termina-icon.svg -o src/main/resources/com/termina/icons/icon-$s.png
done
```

It keeps Editora's family — the same Ink navy tile, corner radius and teal gradient, and the same
split of a muted periwinkle framing glyph against a teal focal element — with a shell prompt in
place of a letterform. Two shapes only: an earlier draft added the input line beneath the cursor,
and below 32px it stopped reading as a line and became noise next to the block, which is the size
an app icon has to work hardest at.

## Not done yet

- **Selection refinements.** No autoscroll when dragging past the top or bottom edge, no
  rectangular (block) selection, no copy-on-select, and a selection is dropped on the next
  keystroke rather than surviving it.
- **Tabs and splits.** One session per window.
- **Configuration.** No keybinding customisation, no custom themes beyond the two built in, and no
  per-profile settings.
- **Search in scrollback.**
- **Installers.** `-Pdist` builds an app image, not a `.dmg`/`.msi`/`.deb`, and it is unsigned —
  macOS will refuse to launch a downloaded copy until it is signed and notarised.
- **Windows and Linux are untested.** The code paths exist and pty4j carries the natives, but only
  macOS has actually been run — including the `-Pdist` build, whose per-OS behaviour (ConPTY, the
  Linux launcher layout) is unverified.

## Command line

```
termina [options] [-e command [args...]]

  -h, --help                     Show this help and exit
  -v, --version                  Show the version and exit
  -d, --working-directory=DIR    Start the shell in DIR
  -e, --command CMD [args...]    Run CMD instead of the shell; must come last
      --config-dir=DIR           Read and write settings in DIR
```

`--help` and `--version` are answered before the toolkit starts, so they work without a display —
`--version` is what ends up in a bug report. An unknown option is refused rather than ignored, since
an option that is silently dropped looks exactly like one that exists and does nothing.

`-e` takes the rest of the line, which is xterm's convention and why it has to come last: `-e ls -l`
passes `-l` to `ls` rather than rejecting it here. The command replaces the shell rather than running
inside one, so the terminal closes when it exits.

The command line applies to the first tab of the first window only. A second tab re-running `-e vim`,
or reopening in a directory you have since navigated away from, is not what anybody means by it.

## Building and releasing

`./mvnw verify` runs the tests. `./mvnw clean -Pdist -DskipTests package` produces a native app image
under `target/dist` — jlink and jpackage are host-specific, so each platform's image can only be
built on that platform, which is what the CI matrix is for.

To cut a release: set `<version>` in `pom.xml` to the release version (drop `-SNAPSHOT`), commit,
then push a `vX.Y.Z` tag. The workflow refuses to build if the tag and the pom disagree, or if the
pom still carries `-SNAPSHOT` — artifacts labelled with a version they were not built from are worse
than no artifacts. A tag with a suffix (`v1.0.0-rc1`) publishes as a pre-release.

The version reaches the bundle through two derived values, because jpackage will not accept one it
cannot parse and macOS additionally refuses any whose first number is zero. `jpackage.publicVersion`
is the pom version without `-SNAPSHOT`; `jpackage.appVersion` is that with a leading `0.` bumped to
`1.` on macOS only, purely to get past the check. `scripts/fix-mac-bundle-version.sh` then puts the
true version back into `Info.plist` and re-signs the bundle — jpackage ad-hoc-signs the app image and
the plist is part of what that seals, so editing it without re-signing makes macOS refuse to launch
the app as tampered with.
