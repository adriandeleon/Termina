# Gotchas

Each of these cost real time, and none of them announces itself. Each entry says what goes wrong,
why, and what the code does about it; search here before concluding JavaFX, JediTerm or a platform
is broken.

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
  the menu and the filter cannot disagree. **And the bar's rows have no accelerators at all**
  (MENU-02): `MenuRows` draws each chord itself — a right-aligned column in-window, appended to the
  title on macOS's screen menu bar. An accelerator is acted on: JavaFX copies every item's into the
  scene and runs it for any press that bubbles up unconsumed — which is what the filter lets through
  on purpose while the palette is open or a text field has the key, so Ctrl+Shift+W in the palette
  closed the tab behind it. The right-click profile lists still carry accelerators as labels, so
  `keepAcceleratorsDisplayOnly` still keeps the scene's table empty, and `onUnclaimedKeyPressed`
  consumes a bound chord that comes back up unclaimed, so nothing past the scene gets a second go.
- **Tab headers are the skin's, and rebuilt.** A reordered tab is removed and added, and gets a new
  header with nothing of ours on it. Everything attached to a header — drag, double-click rename,
  pane drop, close tooltip — goes on in `decorateHeaders`, after every change to the tab list.
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
- **GNOME Software has two pages for a package, and only one reads the listing.** Double-clicking
  a downloaded `.deb` opens an installation window built from the package's control fields alone:
  "termina", a generic icon, Unknown License, the summary as the description, and whatever
  `Homepage` says. No metainfo, icon or licence reaches it, from the package or from the system, so
  no packaging change can fill it in; `--about-url` is the one field that shows. The listing —
  name, icon, MIT, release notes — is on the installed app's page, under Installed. Every screenshot
  of the bare page was the installation window, which cost a wrong fix before it was understood.
  `/usr/libexec/gnome-software-cmd filename-to-app <deb> --show-results` prints what that window
  gets, and `gnome-software-cmd installed --show-results` what the installed page gets.
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
  is built only where it can be drawn (`WindowRules.showsZoomRow`), and the collapse now asks
  `menusFitASystemMenuBar` first, which mirrors JavaFX's rule — including that `SeparatorMenuItem`
  extends `CustomMenuItem` and does not count — so a future custom item degrades to an in-window bar
  rather than to nothing. The right-click menu is a popup JavaFX draws itself and keeps its row on
  every platform. In-window, every command row *is* a `CustomMenuItem` (`MenuRows`, MENU-02), so
  rows are built for the bar they are in: drawn rows where JavaFX draws the bar, plain text with the
  chord appended under `useSystemMenuBar`.
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
  have been reported writing UTF-8. **And with no distributions it is not empty**: it exits non-zero
  and prints an explanation, each line of which became a menu entry. So the registry is read first
  (`reg.exe query HKCU\…\Lxss /s /v DistributionName`, one `DistributionName` per distribution), and
  `wsl.exe` is asked only when `reg.exe` cannot answer — and then believed only on exit code 0.
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
  before it is shown (`WindowRules.usesHeaderBar`) and never changed after. `HeaderBar` ships
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
  **The buttons' light or dark style is the scene's `Preferences.colorScheme`**, which defaults to
  the desktop's, so every window's scene is set from the theme (`ThemeEntry.colorScheme`; terminal
  windows per active pane, the rest through a `Window.getWindows()` listener in `WindowManager`).
  Only the *active* window shows the difference: inactive glyphs are `#777` in both styles. The
  same preference is the native frame's dark variant on Windows and macOS; GTK ignores it.
- **`scripts/dev-run.sh` refreshes `target/deps` from empty.** `copy-dependencies` adds and never
  removes, so a version bump left both JavaFX jars there, both declaring the same module, and the
  old one won: the code compiled against 27 and ran against 26, failing on the first 27-only API.
- **Double-width characters occupy two cells.** JediTerm marks the second with `CharUtils.DWC`;
  drawing it produces a stray bar between every pair of CJK characters. Runs containing non-ASCII
  fall back to per-glyph drawing, because a wide glyph's natural advance would otherwise push the
  rest of the line off the grid.
- **JediTerm's cursor x is a slot, not a column.** An astral character (an emoji, a Nerd Font
  Material icon) is two slots and one column, so drawing `getCursorX()` as a column put the cursor a
  cell right per icon on the line. `CursorDraw.cell` converts for drawing; the glyph under a block
  cursor and an OSC 133 mark want the slot as it is. A zero-advance code point (a combining mark,
  ZWJ, VS16) is the opposite: a cell in the buffer and no advance in one `fillText`, so
  `CellWidth.isOneCellPerChar` sends those runs per glyph.
- **Every pixel-to-cell sum goes through the padding.** `terminal.padding` insets the text inside
  the pane; the grid sent to the PTY, the cell under the pointer (`textX`/`textY`), and every
  overlay drawn on the text (translated in `render`) are measured from inside it. A new
  `e.getX() / charWidth` that skips it is off by the padding — a few pixels, so it looks right
  until it selects the wrong cell at an edge.
- **`Font.getFamilies()` is a snapshot.** JavaFX builds the list the first time it is asked and
  never adds a font registered after, though `Font.font` finds one. Since the bundled families other
  than the terminal's load after the first frame (`Fonts.ensure`, `loadRest`), `MonospaceFonts` adds
  `Fonts.loadedFamilies()` to what it reads as installed — without it a profile in Fira Code drew in
  JetBrains Mono, with nothing logged. `DeferredFontsTest` pins it, and runs its resolve case first
  because a font cannot be unloaded.
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
  projects, or the two palettes stop agreeing. One place differs on purpose: `PickerKeys`'
  JavaFX half (the event, the `ListView`) is `PaletteNav` here, so `PaletteKeys` imports no JavaFX
  type (QUAL-05); worth porting back. The chord that opens it is an ordinary
  key binding (`keys.palette.show`, which was `keys.commandPalette` until the command took the id
  Pauta names), the line it had when it was the only chord that could change.
- **Every binding has two lists: the defaults and the chords in force.** `TerminalWindow.table`
  holds each command on its default chord, by a stable id (`newTab` — the catalogue key without
  `command.`), and `register` still throws on two *defaults* on one chord. `bindings`, which the key
  filter runs, is derived from it by `Keybindings.resolve` against the stored `keys.*` block on
  every `applySettings`, along with each menu row's chord, the palette chip and the tab
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
- **What the emulator reads is a chain, built in one place.** `TerminalSession.readThrough` wraps
  the PTY stream in four interceptors: APC/SOS/PM strings dropped (JediTerm prints their bodies — a
  kitty image arrived as base64), the OSC commands above, the kitty keyboard modes, and colon SGR
  respelled with semicolons (JediTerm prints from the first colon on and loses the colour).
  Underline colour (`58`) is dropped rather than respelled: JediTerm has no such attribute, and read
  with semicolons its `2` and `5` are faint and blink. `EmulatorGapsTest` feeds sequences through
  that same chain, and pins the gaps nothing repairs yet — REP, DECSLRM, `?12` — at what JediTerm
  does now, so a JediTerm upgrade that fixes one fails its test.
- **Keys fired at a program right after it starts can race its startup.** Neovim asks the terminal
  for the kitty protocol as it starts and turns it on when answered; a capture that typed into it
  within its first seconds saw Escape ignored, and the same keys after a longer
  `captureAfterCommandMs` worked. Settle before concluding a key is broken.
- **An `--add-opens` for a package only one platform has warns on every launch elsewhere.**
  `ui.MacAppMenu` reaches the macOS application menu through `com.sun.glass.ui.mac`, which exists
  only in macOS's JavaFX; opened on Linux the JVM prints `WARNING: package com.sun.glass.ui.mac not
  in javafx.graphics` at every start. So it is a property the `os-mac` profile sets
  (`glass.mac.opens`) and a `uname` test in the two scripts, defaulting to a repeat of the
  `com.sun.glass.ui` export — harmless, where an empty launcher option is not. `MacAppMenuTest`
  checks every launcher. The item itself has only ever run as far as Glass's proxy: Headless and
  GTK have no application menu, so a Mac is the only test of it.
- **The close prompt is modal, and a capture run that reaches it hangs.** A fresh shell often has a
  child for a moment (a prompt framework's helper), so closing a pane or tab in a capture asks —
  pass `-Dtermina.captureCloseAnswer=close`, which `capturePanes` and `captureCloseTabs` both read.
- **A tab is there before its shell is.** The shell starts on a worker (it forks, and may wait on a
  login shell for `PATH`) and the session is built back on the FX thread, so for a moment after
  `openTab` the view `isStarted()` false and has no session. Everything that sends to the shell
  checks first; a test waits for `isStarted()` rather than calling `getSession()` straight after
  opening a tab. The close prompt is the same shape: the shells' children are read off the FX
  thread and the question comes a little later, so a test that answers it waits for the answer
  (`DialogAnswers.answered()`) before queueing another — a newer answer cancels an older one, and
  the prompt then sits unanswered and catches the next test's.
