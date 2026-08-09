# Termina

A cross-platform terminal emulator. JDK 25 + JavaFX 26 + Maven, modular (JPMS, module
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

`com.termina.term.TerminalSession` wires them together and owns the read loop.
`com.termina.ui.TerminalWindow` is one window (menu bar over a tab strip); `WindowManager` owns the
window set and what they share — settings, the preferences window, the theme, the update check.

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
  empty chrome above the terminal. Collapsed in CSS (`system-menu-bar-host`) rather than hidden or
  unmanaged, so the registration that depends on it being live is untouched.
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
- **Double-width characters occupy two cells.** JediTerm marks the second with `CharUtils.DWC`;
  drawing it produces a stray bar between every pair of CJK characters. Runs containing non-ASCII
  fall back to per-glyph drawing, because a wide glyph's natural advance would otherwise push the
  rest of the line off the grid.

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

## Not done

Windows and Linux are **unrun** — including `-Pdist`. Installers are not built and nothing is
signed. No tab reordering, splits, scrollback search, or keybinding customisation. See
`CHANGELOG.md` for the current list.
