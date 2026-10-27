# Termina

A cross-platform terminal emulator. JDK 25 + JavaFX 27 + Maven, modular (JPMS, module
`com.termina`).

## Commands

- Run: `./mvnw javafx:run`
- Test: `./mvnw test`
- Native app image: **`./mvnw clean -Pdist -DskipTests package`** ⇒ `target/dist/Termina{.app}`.
  `clean` is **required**: jlink's input is built from `target/classes`, and an incremental compile
  can leave that inconsistent in ways javac will not correct.
- Run with ad-hoc options: **`scripts/dev-run.sh -Dfoo=bar`**. `mvn javafx:run` cannot take them —
  the plugin's `<options>` are fixed in the pom — and `-Pdist` deletes `target/deps` every time, so
  the script rebuilds the module path itself.

Run Maven from the project root.

## Architecture

Three layers, and **two of them are deliberately not ours**.

| Layer | What | Where |
|---|---|---|
| PTY | Starts the shell on a pseudo-terminal, moves bytes | `com.termina.pty` |
| Emulation | Parses escape sequences into a screen buffer | JediTerm (dependency) |
| Rendering & input | Paints the buffer, encodes keys and mouse | `com.termina.ui` |

**The emulator is JediTerm's.** xterm compatibility is a very long tail — cursor modes, character
sets, scroll regions, four mouse wire formats, a hundred CSI sequences — and getting it wrong
surfaces as programs subtly misbehaving rather than as anything that looks like a terminal bug.
`jediterm-core` is UI-agnostic: it owns a `TerminalTextBuffer` and knows nothing about drawing. It
is **not on Maven Central** — see the `<repositories>` block.

**The PTY is pty4j's.** Java has no PTY API and the platforms disagree fundamentally (`forkpty` vs
ConPTY). One jar carries macOS, seven Linux arches, and Windows ConPTY + winpty, so there is **no
per-OS native build step**.

**Which shell is a fourth thing, in `com.termina.shell`.** A `Profile` is a name, a command and
optionally a directory; `ShellDiscovery` enumerates what a machine has (both PowerShells, Command
Prompt, Git Bash and every WSL distribution on Windows; `/etc/shells` and the path elsewhere) and
`ShellProfiles` merges that with the system shell and the user's own list. Discovery's probes are
parameters rather than static calls, because the Windows branch is the one the feature exists for and
the one that cannot be run here.

**Importing a colour scheme is in `com.termina.theme`**: one reader per format, all to a
JavaFX-free `ColorScheme`, stored as `<config>/themes/*.properties` and listed with the built-ins by
`ui.Themes`. Anything that draws asks `Themes.byId`, never the `Theme` enum, which is only the four
built-ins.

**Settings sync is a fifth, in `com.termina.sync`, ported from Editora's.** It carries only the key
bindings, one file per platform family (`keybindings/macos.properties`,
`keybindings/windows-linux.properties`), through a private clone in `<config>/sync/repo` that git
never merges: the remote is checked out, a per-command three-way merge against
`refs/termina/base` is written over it, and that is pushed. `ui.SettingsSync` schedules runs and
writes results into the live `Settings`; one process per config folder syncs, by file lock. For an
`https://` repository, `SyncAccount` remembers which `gh` account can read it (in the clone's own
config) and hands git a credential helper that asks `gh auth token --user` for that one, so switching
the active account does not lock sync out.

`com.termina.term.TerminalSession` wires them together and owns the read loop.
`com.termina.ui.TerminalWindow` is one window (menu bar over a tab strip); each tab is a `PaneGroup`
of one or more `TerminalView`s. `WindowManager` owns the window set and what they share — settings,
the preferences window, the theme, the update check.

## Things that will bite you

Each of these cost real time, and none of them announces itself.

- **`module-info` requires `kotlin.stdlib` and `org.slf4j` explicitly.** jediterm-core and pty4j are
  automatic modules. An automatic module reads everything *in* the graph but does not pull explicit
  modules *into* it, so without those two lines the build is clean and the first emulator call dies
  with `NoClassDefFoundError`.
- **AWT is forced headless in `App.main`, as the first statement.** We require `java.desktop`
  (JediTerm's `TtyConnector` declares `java.awt.Dimension` overloads) but never use AWT — and on
  macOS an initialised AWT contends with JavaFX for the AppKit run loop, which hangs rather than
  fails.
- **Special keys go through JediTerm's encoder** (`TerminalStarter.getCode`), never hard-coded. In
  application-cursor-key mode Up is `ESC O A`, otherwise `ESC [ A`. Printable characters come from
  `KEY_TYPED`, the only event reporting the *composed* character — that is what makes dead keys,
  AltGr and IME work.
- **Application chords are `Ctrl+Shift+key` off macOS, never `Ctrl+key`.** The plain form belongs to
  the shell: Ctrl+T is readline's transpose, Ctrl+W deletes a word, Ctrl+C is SIGINT.
- **Key bindings are a scene-level filter, not menu accelerators.** JavaFX fires accelerators only
  after an event bubbles unconsumed, and `TerminalView` consumes `Ctrl+<letter>` first. Filters run
  in the capturing phase, ahead of that. `MenuAction` holds the label, chord and action together so
  the menu and the filter cannot disagree.
- **Nothing in the chrome may be focus-traversable.** The scene hands initial focus to the first
  focus-traversable node it finds, and a `TabPane` is one by default — so it raced the
  `requestFocus` in `openTab` and, on a cold first launch, won: the window came up looking ready
  with every keystroke going to the tab strip. Opening a second tab appeared to fix it only because
  the strip then had focus to give away. `tabs` and `newTabButton` are both
  `setFocusTraversable(false)`. Traversal into chrome is unwanted here anyway — Tab belongs to the
  shell. **That is not sufficient on its own**: `requestFocus` ignores the flag, and a click on the
  already-selected tab header takes focus without changing the selection, so the listener that
  follows a tab switch never runs. `FocusGuard` is the backstop — the terminal takes focus back
  whenever nothing else has a claim on it. The claim is the load-bearing half: while the palette, a
  menu or the context menu is open, focus is left alone, because reclaiming would make the palette
  untypable and dismiss the context menu on the way to showing it.
- **Driving input in a capture run mostly goes *around* focus.** `captureCommand` writes into the
  PTY and the mouse/key options fire at the view by name, so all of them pass in a window where
  nothing the user types reaches the shell. `captureTypeAtFocus` is the one that fires at the focus
  owner, and `focusReport()` prints who that is; the focus bug above was invisible to every other
  check, including the screenshot.
- **The shell will not tell you where it is, and often will not tell you anything.** The window
  title arrives only as an escape sequence (OSC 0), and emitting it is the *prompt's* job — every
  prompt framework that replaces `PS1` drops it without a word. GNOME Terminal appears to escape
  this only because `/etc/profile.d/vte-2.91.sh` re-adds it, gated on `VTE_VERSION`, which we do not
  set. So the directory is read from the OS instead (`ProcessCwd`/`CwdWatcher`), and a shell-set
  title still takes precedence. `FxTerminalDisplay` keeps the two apart rather than resolving on
  arrival, because a program that sets a title and later clears it (vim, on exit) has to leave the
  directory showing again.
- **The deb and rpm bundlers throw your icon away.** See `packaging/linux/NOTES.md` — `--icon`
  reaches the app image only, and the 0.1.0 packages shipped jpackage's generic Java icon as a
  result. Verify a built package, not the tree.
- **The deb's dependencies are the build host's package names for whatever is in the image.**
  `libjsound` in `java.desktop` made the 0.12.0 debs depend on `libasound2t64`, which only Debian
  13+/Ubuntu 24.04+ have; jlink now excludes it. JavaFX's own libraries are invisible to the scan
  (extracted from jars at run time) and are declared by hand in `build-installers.sh`. The deb's
  `postinst`/`prerm` and the rpm's spec are overrides of jpackage's templates — keep its tokens.
  All in `packaging/linux/NOTES.md`; `scripts/check-linux-packages.sh` asserts it.
- **Full-screen geometry cannot be diagnosed from inside the app on Wayland.** JavaFX has no
  Wayland backend, so it runs through XWayland, and under GNOME's fractional scaling (and its
  experimental `xwayland-native-scaling`) the compositor's idea of the surface and JavaFX's disagree.
  `stage.isFullScreen()`, `stage.getX/Y/Width/Height` and `Screen.getPrimary()` all report a correct,
  screen-filling window while the window on screen is smaller, offset, and clipped. Every probe we
  can write reads JavaFX's own state, so it will agree with itself and say nothing. The check that
  distinguishes it is outside the app: `xrandr` for the panel size against what `Screen` reports, and
  `gsettings get org.gnome.mutter experimental-features`.
- **Closing asks; shutting down does not.** `TerminalWindow.close()` is the user's request and
  prompts when a shell has children; `closeForShutdown()` is teardown and must not. `App.stop()`
  reaches `WindowManager.closeAll()` after the quit is already decided and while the toolkit is
  stopping — a modal dialog there asks a question nobody can act on, in a place where
  `showAndWait` may never return. Quitting is therefore *not* guarded; doing that properly means
  intercepting the quit before `Platform.exit`.
- **Tab disposal is driven off the tab list, not the close button.** A tab owns a PTY process, two
  pump threads and an emulator thread. Any removal path that skips disposal leaks all of it and
  nothing on screen looks wrong.
- **The macOS system menu bar still occupies layout.** `setUseSystemMenuBar(true)` moves the menus
  to the screen bar but leaves the node in the scene graph with its own padding — measured 8px of
  empty chrome above the terminal. Collapsed in CSS (`collapsed-menu-bar`) rather than hidden or
  unmanaged, so the registration that depends on it being live is untouched.
- **One `CustomMenuItem` costs the whole screen menu bar.** `MenuBarSkin` walks every menu in the
  bar and, on finding one, declines `useSystemMenuBar` for all of them — AppKit draws those menus
  and cannot render a JavaFX node. It says so only as `Warning: MenuBar ignored property
  useSystemMenuBar because menus contain CustomMenuItem` on stderr; there is no API to ask, and a
  packaged app has no stderr anyone reads. Together with the collapse above, the zoom row left macOS
  with **no menus anywhere** — none in the screen bar, and the window's at zero height. So the row
  is built only where it can be drawn (`TerminalWindow.showsZoomRow`), and the collapse now asks
  `menusFitASystemMenuBar` first, which mirrors JavaFX's rule — including that `SeparatorMenuItem`
  extends `CustomMenuItem` and does not count — so a future custom item degrades to an in-window bar
  rather than to nothing. The right-click menu is a popup JavaFX draws itself and keeps its row on
  every platform.
- **JediTerm's text-scanning hyperlink layer is deliberately unused; its OSC 8 path is used.** The
  dependency has a whole layer: a `TextProcessing` handed to `TerminalTextBuffer` runs filters over
  each line as it is written and marks the runs with a `HyperlinkStyle`. We do not use it. Links
  found in the text are found *under the pointer* instead (`com.termina.link.LinkScanner`), because a
  path is only a link if it names a file that exists, and that check has to happen somewhere: through
  JediTerm it would run for every token of every line as output streams past, on the emulator thread.
  Under the pointer it runs once per hover. **OSC 8 is the other road**: `JediTerminal.setLinkUriStarted`
  drops a declared link unless `setUrlHyperlinkFilter` has been called, so `TerminalView.start` calls
  it with `ExplicitLink.FILTER`, which applies the same scheme allowlist and keeps the URI on the
  cells for the hover's status line. Install it before the emulator starts reading.
- **Closing a stage takes nothing out of the tab list.** Disposal lives on the list, so a window
  closed with others still open — its close button, Close Window — left every shell in it running
  until the app quit, and nothing on screen said so. The window clears its tabs on
  `WINDOW_HIDDEN`, which covers every way out; `-Dtermina.captureCloseWindow=N` counts the shells
  before and after.
- **A pane moves by leaving its group before its tab goes.** Moving one between tabs is
  `PaneGroup.remove` first — the tab-list listener then finds no panes to close — and `wirePane`
  after, because every callback a pane holds (bell and output marks, the session-ended close)
  captured the tab it was created in. `TerminalView` survives leaving the scene; nothing disposes on
  removal. A maximized pane has a placeholder in its place in the tree, and everything that walks
  the tree reads the placeholder as the pane.
- **A pane's launch options go in before its settings.** `createPane` used to apply settings first;
  harmless while nothing in them depended on the pane, wrong once the profile's font and theme do —
  the pane drew in the global ones until the next settings change.
- **The drag anchor is buffer state too.** It moves with its line on every scroll, like the
  selection; it used not to, and a drag held while output arrived — routine once a drag past the
  edge autoscrolls — extended from whatever had scrolled under the press.
- **A `.terminal` profile is two property lists deep.** Each colour is `<data>` holding an `NSColor`
  archived by `NSKeyedArchiver` as a *binary* plist, so the import reads `bplist00` itself
  (`theme.BinaryPlist`) and walks `$top`/`$objects` to the colour's `NSRGB` or `NSWhite` ASCII
  components. They are taken as sRGB, like every other importer's numbers.
- **`gdbus call` parses `-1` as an option.** The notification's expiry is the last argument and is
  negative, so without `--` before the method's arguments gdbus prints its usage, exits 1, and —
  run from a worker with its output discarded — posts nothing, silently. `dbus-monitor --session
  "interface='org.freedesktop.Notifications'"` is how to see whether a notification was sent.
- **Asking for a row above the scrollback is an ERROR line, not an exception.**
  `TerminalTextBuffer.getLine(-1)` with no history logs `Attempt to get line out of bounds` through
  slf4j and returns. On a path that runs per mouse move — walking back to find the start of a wrapped
  line — that is a log flood rather than a crash, which is why the walk is bounded by
  `getHistoryLinesCount()` as well as by a row count.
- **Hiding the tab strip needs min, pref *and* max height at zero.** `visibility: hidden` alone
  leaves the row's height behind as a blank band, because `TabPaneSkin` lays the header out from its
  own computed size.
- **jlink silently omits ServiceLoader-only modules.** The slf4j binding is reached only through
  `ServiceLoader`, so nothing `requires` it; the image built, launched, and dropped every log line
  until it was named in `<addModules>`.
- **pty4j's natives survive packaging by accident.** JPMS encapsulates resources whose directory
  maps to a valid package name. `resources/com/pty4j/native/…` does not, because `native` is a
  reserved word. JNA is covered by the same accident (a hyphen in `darwin-aarch64`). A rename
  upstream would break the packaged build only.
- **`wsl.exe --list --quiet` writes UTF-16LE.** Even redirected. Read as UTF-8 every distribution
  name comes back with a NUL between each of its letters, and that survives all the way to a menu
  entry that starts nothing. `ShellDiscovery.decode` sniffs rather than assumes, because newer builds
  have been reported writing UTF-8. The call is also not gated on the exit code — `wsl --list` exits
  non-zero when there are no distributions, and parsing an empty answer already gives the right
  result.
- **A `StackPane` resizes a child up to its *maximum*, and an `HBox` reports an unbounded one.** The
  new-tab `+` was aligned `TOP_RIGHT` and stayed there for as long as it was a lone `Button`, whose
  maximum is its preferred width. Putting it in a box with the profile chevron stretched the box the
  full width of the strip and packed both buttons against its left edge, over the first tab. Pin the
  width — but **not** the height: the buttons take the strip's height from a binding installed after
  the first layout, and a box pinned to its preferred height never grows to it, leaving the glyphs
  eight pixels above the close buttons they line up with.
- **A `Labeled` ignores a graphic that is unmanaged when it is set, for good.** `LabeledSkinBase`
  adds the graphic to its children only if it is managed at that moment and never revisits the
  decision, so a node created hidden and shown later is laid out by nothing, has no scene, and is
  styled and painted by nothing — while `isVisible()`, `getFill()` and even `getBoundsInParent()`
  all report a node that looks fine. The tab marks shipped that way once: the report said
  "bell(visible)" and the screenshot showed no dot. Attach and detach the graphic (`setGraphic`)
  instead of toggling it, and colour it in code if it must be right before CSS has run.
- **JavaFX has no structural pseudo-classes.** `:last-child` parses without complaint and matches
  nothing, so anything positional needs a style class of its own.
- **An SVG path JavaFX cannot read draws nothing and says nothing.** No exception, no log line — the
  node simply has no geometry, and the row shows a gap. `ProfileIconsTest` builds every glyph and
  asserts it has bounds, which is the only way to find out. Two things it settled: packed
  elliptical-arc flags (`a1 1 0 000-.5`) *do* parse here, and the real hazard is transcription —
  wrapping path data across source lines with a whitespace-aware wrapper consumed the space it
  wrapped at and joined two numbers. One of eleven glyphs failed loudly; the rest would have drawn
  quietly distorted. Slice path strings at a fixed width, never wrap them on whitespace.
- **The menu bar can live in the title bar, and three things about that are invisible.** It is
  JavaFX 27's `StageStyle.EXTENDED` with a `HeaderBar` at the top of the scene, chosen per window
  before it is shown (`TerminalWindow.usesHeaderBar`) and never changed after. `HeaderBar` ships
  with **no style class** — add `header-bar` yourself or every rule for it matches nothing. The
  window buttons are an overlay JavaFX draws outside the scene graph on Linux and Windows, so a
  scene snapshot (`-Dtermina.capture`) **never shows them**; `layoutReport` prints the system insets
  (`108x36` on the right under GNOME) and a screenshot of the X window is the only picture of them.
  And an unsupported platform downgrades `EXTENDED` to `DECORATED` silently, which would leave our
  header under a native title bar — hence the `Platform.isSupported(EXTENDED_WINDOW)` leg.
  **Full screen keeps both** unless told: the `HeaderBar` stays in the scene, and taking it out
  leaves the button overlay drawn over the terminal, because the overlay belongs to the stage. It
  goes with `HeaderBar.setSystemButtonHeight(stage, 0)` (`placeHeaderBar`). `systemInsets` reads
  `0x0` in full screen whether the buttons are drawn or not, so only an X screenshot shows them.
- **`scripts/dev-run.sh` refreshes `target/deps` from empty.** `copy-dependencies` adds and never
  removes, so a version bump left both JavaFX jars there, both declaring the same module, and the
  old one won: the code compiled against 27 and ran against 26, failing on the first 27-only API.
- **Double-width characters occupy two cells.** JediTerm marks the second with `CharUtils.DWC`;
  drawing it produces a stray bar between every pair of CJK characters. Runs containing non-ASCII
  fall back to per-glyph drawing, because a wide glyph's natural advance would otherwise push the
  rest of the line off the grid.
- **JavaFX's fallback list is not a Nerd Font lookup.** No bundled face has private-use glyphs, so
  an icon goes to fontconfig's *sans* ordering and the first face that maps the code point wins.
  Arial (msttcorefonts) maps U+F016 and MathJax_WinIE6 maps U+E271 to glyphs of their own, both
  ahead of the Nerd Font, so `eza` drew a stray `¯`/`ÿ` beside `vmlinuz` and `initrd.img` and
  every other icon was right. `IconFont` names a Nerd Font for private-use code points instead —
  the terminal face's own Nerd build if installed, else the bundled `Symbols Nerd Font Mono`,
  which is kept out of the font picker because it has no letters. Its icons are 1 em squares,
  so at text size each spills two-thirds of a cell into the name after it; it is drawn 1.5 cells
  wide, like the NF builds' icons, and centred on the row, except Powerline separators, which
  must span the line. Which face JavaFX chose is not visible through public API; the slot list
  lives in
  `com.sun.javafx.font.FallbackResource`, reached by reflection with `--add-opens`.

- **The command palette is a port of Editora's, and the pure parts are verbatim.** `FuzzyMatch`,
  `PaletteKeys`, `TextInputKeys` and `MatchText` are Editora's `search.FuzzyMatch`, `ui.PickerKeys`,
  `command.TextInputKeymap` and `ui.MatchText` with the keymap manager replaced by a fixed Emacs
  table in `KeyChord` tokens (`C-n`, `M-S-.`). A ranking or navigation change belongs in both
  projects, or the two palettes stop agreeing. The chord that opens it is an ordinary key binding
  (`keys.commandPalette`), which is the line it had when it was the only chord that could change.
- **Every binding has two lists: the defaults and the chords in force.** `TerminalWindow.table`
  holds each command on its default chord, by a stable id (`newTab` — the catalogue key without
  `menu.`), and `register` still throws on two *defaults* on one chord. `bindings`, which the key
  filter runs, is derived from it by `Keybindings.resolve` against the stored `keys.*` block on
  every `applySettings`, along with each menu item's accelerator, the palette chip and the tab
  strip's tooltips. Anything new that shows a chord must ask `chordOf(id)`, not the action's
  `accelerator()` — that is the default, and showing it advertises a chord the user may have
  moved. `KeybindingEdits` is Editora's port, keyed by command rather than by chord.
- **`KeyCombination.equals` compares modifiers as written.** The defaults say `Shortcut+Shift+T`;
  the recorder writes `Ctrl+Shift+T`. They match the same keystroke off macOS and are unequal, so a
  plain `equals` check let two commands share a chord with the filter reaching only the first.
  Every comparison goes through `Keybindings.same`, which spells Shortcut out for the platform.
- **A `ListView` asked to scroll before it has a size scrolls to the wrong place.** `scrollTo` on
  a virtual flow with no layout yet positions by the fraction `index / count` and never clamps, so
  the capture — which opens the palette and fires keys at it within one pulse — showed the last row
  half clipped while the same keys from a hand worked. `firePaletteKeysForCapture` runs `applyCss`
  and `layout` before each key for that reason; a row that is "not scrolled into view" in a capture
  is this before it is anything else.
- **A control created in this pulse has no skin, and a skinless control is half a control.** A
  `TextField` without its skin has no behaviour and ignores typing (the find bar, typed into by a
  capture in the pulse that opened it); a new `SplitPane` without its skin has not put its items in
  the scene, so a pane started there began at a guessed 80×24 and dropped its focus request.
  `applyCss` and `layout` on the parent first. The same family as the `ListView` entry above.
- **Operating-system commands are intercepted where the emulator reads, not where the PTY
  delivers.** `OscInterceptor` wraps the `TerminalDataStream`, and JediTerm acts on each character
  before pulling the next, so an OSC 133 mark arrives with everything before it on screen and the
  cursor on its row. A filter on the PTY's reads sees a chunk at once and places every mark in it
  early. Only 7, 52 and 133 are taken; everything else is pushed back for JediTerm, in order.
- **Things positioned in the buffer ride on `linesScrolled`, not on per-change shifting.**
  `TerminalView` counts every row the text has moved up; a search match or a prompt mark records
  that count beside its line and works out where it went. `layoutEpoch` bumps when rows are rebuilt
  rather than moved (a reflow, a cleared history), after which recorded lines mean nothing: search
  re-runs, and prompt marks are carried through by `LogicalLines` from a snapshot taken just before
  the view resizes the shell. A resize the view did not ask for has no snapshot and drops the marks.
- **A text field inside the terminal still passes through the terminal's key filters.** The find
  bar is a child of `TerminalView`, whose `KEY_PRESSED`/`KEY_TYPED` filters run first in the
  capturing phase — every query letter would go to the shell. They return early for events headed
  into the bar (`fromSearchBar`), and the window's chord filter lets the editing chords through to a
  focused text field (`yieldsToTextField`), or Cmd+C in the query copies the terminal.
- **A `SplitPane` item's parent is not the `SplitPane`.** It is the skin's private wrapper, so
  `PaneGroup` finds a pane's split by walking down from its root. `Node.focusedProperty` is also
  taken: the group's is `activeProperty`, the pane the keyboard was last in, which the window updates
  from the scene's focus owner — including the find bar of a pane, where the pane itself is not
  focused.
- **JediTerm ships the type-ahead predictor without the model it edits.** The Swing UI's model
  writes into a package-private field of `TerminalLine`; across a module boundary that is out of
  reach, so `TypeAheadModel` keeps the predicted line and the view draws it. Wanted only while
  `ssh` is the foreground program, and the predictor shows nothing until it has measured echoes
  over 100 ms.
- **JediTerm reads any `CSI … u` as SCORC, restore cursor, and ignores the private marker.** So
  the kitty keyboard protocol's `CSI > 1 u` moved the cursor instead of doing nothing — worse than
  unsupported. `KeyboardModeInterceptor` takes the four mode requests (`>` `<` `=` `?`) out first,
  and lets everything else, DA2's `CSI > c` included, through untouched. Kitty encoding happens
  only while a program has set flags; at zero every key takes the legacy path, byte for byte.
- **Keys fired at a program right after it starts can race its startup.** Neovim asks the terminal
  for the kitty protocol as it starts and turns it on when answered; a capture that typed into it
  within its first seconds saw Escape ignored, and the same keys after a longer
  `captureAfterCommandMs` worked. Settle before concluding a key is broken.
- **The close prompt is modal, and a capture run that reaches it hangs.** A fresh shell often has a
  child for a moment (a prompt framework's helper), so closing a pane or tab in a capture asks —
  pass `-Dtermina.captureCloseAnswer=close`, which `capturePanes` and `captureCloseTabs` both read.

## Conventions

- **Performance.** The emulator signals a change per write. Repaints are decoupled from that: a
  change sets a dirty flag and one `AnimationTimer` repaints per frame. Drawing is per style run,
  not per character. Do not add per-keystroke or per-pulse work that is not coalesced.
- **Extract the decision, then test it.** Anything with a rule in it — which chord, who owns
  right-click, when the tab bar shows, whether a colour is legible — becomes a static method with
  its own tests. `MouseEncoding`, `MenuAction.appChord`, `TerminalWindow.shouldShowMenu`,
  `shouldShowTabBar`.
- **Verify by driving it.** Terminal bugs are visual and none of them throw. `DevCapture` renders
  the real window, types into the real shell, fires real mouse and key events, and writes a PNG.
  Reasoning about JavaFX dispatch order is not evidence — fire the chord. See the README for the
  full set of `-Dtermina.capture*` options.
- **A settings change applies live.** Every control writes through immediately; there is no OK
  button. Two settings cannot (scrollback sizes an existing buffer, the shell is already running)
  and each says so on its own row rather than appearing to work.
- **Settings are clamped on read as well as write.** The file is meant to be hand-edited; clamping
  only on write would let a two-million-line scrollback exhaust memory at the next launch.
- **Ported themes are faithful, not improved.** Clear Dark/Light colours were decoded from Apple's
  own `.terminal` profiles. Clear Light is genuinely low-contrast in four places; that is pinned in
  `ThemeTest` rather than corrected, because a port that "fixes" its source is no longer that theme.

## Null annotations

JSpecify annotations, checked by [NullAway](https://github.com/uber/NullAway) (an Error Prone
plugin) in the main compile. The setup is Editora's, so the rules below are the same in both projects.
`mvn compile` fails on any null that can reach a type not marked nullable.

- **Every package is `@NullMarked`, and a new one starts that way.** Give it a `package-info.java`:
  `@NullMarked package com.termina.example;` with `import org.jspecify.annotations.NullMarked;`.
  `NullMarkedPackagesTest` fails for a package without one, and fails if NullAway leaves the pom.
- **In a marked package everything is non-null unless it says `@Nullable`.** Mark what really may be
  null: a field set later, a parameter callers pass null to, a "not found" return. The annotations are
  type-use, so they go next to the type: `private @Nullable Foo foo`, `java.nio.file.@Nullable Path`,
  `Settings.@Nullable ProfileStyle`, `byte @Nullable []` (the array may be null),
  `List<@Nullable String>` (its elements may be).
- **Prefer a shape that cannot be null over `@Nullable`** when the null is an accident of the code,
  not part of its meaning. For state that must exist by the time it is used, write an accessor that
  fails with a clear message: `TerminalView.getSession()`/`buffer()` (null until `start`), with
  `isStarted()` for the two callers that ask. Make lazily built things through one method that
  builds them (`WindowManager.settingsWindow()`, `TerminalWindow.shortcutsWindow()`). Things built
  together go in one record behind one nullable field (`TerminalView.ContextItems`), not six.
- **NullAway does not credit an assignment it cannot see as initialization.** A field assigned inside
  another call's argument list, or by a builder reached through a loop and a switch, counts as
  unassigned. `TerminalWindow`'s palette and prompt actions are `final` fields with initializers for
  that reason; `buildMenuBar` only registers them. `SettingsWindow`, whose controls are all made by
  `build()`, suppresses only the initialization check on its constructor
  (`@SuppressWarnings("NullAway.Init")`). Reads of those fields are still checked.
- **NullAway does not follow a boolean that holds a null check.** After `boolean present = style !=
  null; if (!present) return;`, `style` still counts as nullable. Write the check inline. The same goes
  for calling a getter twice: `if (x.y() != null) x.y().z()` is flagged, so read it into a local.
- **JavaFX returns null more often than it looks:** `Node.getScene()`, `Scene.getWindow()`,
  `Node.getParent()`, a selection model's selected item, a `ComboBox` value.
- **Watch for over-wide annotations.** When a caller has a null for a parameter that does not take
  one, make the parameter `@Nullable` only if the method already copes with null. NullAway then
  checks that it does. Otherwise check at the caller. Widening one method after another until the
  error moves out of sight is how a whole API turns `@Nullable`.
- **Native bindings are `@NullUnmarked`.** A JNA `Library` interface follows the C API's nullness
  (`ProcessCwd`, `WindowsCwd`, `ChildProcesses`).
- **Suppress rarely**, on the narrowest element, with a comment saying why.
- **Plumbing.** Only NullAway runs (`-XepDisableAllChecks -Xep:NullAway:ERROR`), and only in the main
  compile, not on tests. `.mvn/jvm.config` exports the javac internals that Error Prone needs. JSpecify
  is a `provided` dependency read through `requires static org.jspecify`, so nothing ships.

## Not done

Windows and Linux are **unrun** — including `-Pdist`. Installers are not built and nothing is
signed. Key bindings have no multi-key sequences; no images or ligatures. See `CHANGELOG.md` for the
current list.

**Windows' directory read (`pty.WindowsCwd`) has never run on Windows**, nor has the PowerShell
toast; the PEB walk is tested against a made-up address space, and the offsets are the ones every
process explorer uses, but the system calls around it are claims. The same for the macOS
notification and Option+drag blocks.

**The shell profiles' Windows half has never run on Windows.** Every branch of it is driven in
`ShellDiscoveryTest` with the filesystem and `wsl.exe` probes replaced, and the macOS half is
device-tested, but "PowerShell 7 is found at that path" and "`wsl --list` answers in that shape" are
claims about a machine rather than about the code. Check it with
`-Dtermina.captureProfiles=true`, which reports every entry and the command it runs.
