# Testing

Unit tests for the decisions, the headless JavaFX platform for what needs the toolkit, and
`DevCapture` for everything that can only be seen.

## Running the tests

- `./mvnw test` runs everything; `./mvnw verify` adds the Spotless check and the coverage floors, and
  is what CI runs.
- `./mvnw test -Dtest='Foo*Test'` runs a few; do this while working and leave the whole suite to CI.
- `./mvnw test -DexcludedGroups=fx` runs everything that does not start the JavaFX toolkit. A test
  class that does carries `@Tag("fx")`; `FxTestTagsTest` fails one that does not.
- Every test runs on JavaFX's Headless glass platform (`glass.platform=Headless`, set in surefire):
  no display, no Monocle, no xvfb, the same on a laptop and a runner. The FX tests also set it
  themselves, for a run from an IDE.
- Tests that start a real shell (`TerminalSessionPtyTest`, `ProcessCwdPtyTest`) are POSIX-only.

## Writing a test

- **Extract the decision, then test it.** Anything with a rule in it becomes a static method or a
  small class with no JavaFX in it, tested on its own — see [conventions.md](conventions.md#code).
  The toolkit is for what only the toolkit can show: layout, focus, skins.
- **A guard that cannot fail is decoration.** When adding a test that pins a drift (catalogue
  parity, workflow rules, the Pauta edition), break the guarded thing once and watch it fail.

## Coverage

Only packages whose coverage does not vary by platform are gated. `com.termina.term` and
`com.termina.pty` are not: almost all of their coverage comes from an integration test that starts a
real shell, which is POSIX-only, so on Windows they measure 0% and 26% against 64% and 69% on macOS.
Measure from a clean target — `jacoco.exec` accumulates across runs, so a second measurement without
`clean` silently reports the union of both.

Coverage floors are a regression net rather than a target — each sits well below what is actually
measured, so they catch tests being deleted, not a few lines of drift. `com.termina.sync`'s floor is
set from what it measures without the five tests disabled on Windows (83%, against 92% on Unix).
The whole build is gated too (PLAT-12, Pauta 2.0.2): 0.90 lines and 0.90 methods on macOS and Linux,
and branches as a net under what is measured. Most of it comes from headless tests that drive the
real windows on real shells — `WindowCommandsFxTest` runs every palette command, `MenusFxTest` fires
every menu item, `DevCaptureFxTest` runs the capture harness, `DialogsFxTest` answers every question
the app asks. Windows skips those, because its shell path has never run, and measures far lower. No
profile lowers the floors for it (PLAT-12, Pauta 6.0.1): the Windows runner in `ci.yml` and
`release.yml` passes its own with `-Dcoverage.bundle.*`, and `./mvnw verify` on a Windows machine
needs the same three options until those tests run there.

Three things these tests have to get right, all learnt the hard way:

- **A modal dialog is answered from inside it.** `showAndWait` runs a nested event loop, so
  `DialogAnswers.next` queues an answer just before the call that shows the dialog; it presses the
  button once the dialog is up. Only the latest queued answer acts — one left over from a button that
  opened nothing would otherwise answer the next dialog.
- **Closing a manager's last window ends the toolkit for every later test.** `WindowManager` calls
  `Platform.exit()` when its list empties. Every window test keeps a second window open, and none
  calls `closeAll`.
- **The toolkit is shared.** A test that walks `Window.getWindows()` sees every other test's windows;
  take the set before opening anything and touch only what is new. Imported themes go where
  `Themes.useDirectory` says, which only the window manager sets — a test without one sets it. `./mvnw clean -Pdist -DskipTests package` produces a native app image
under `target/dist` — jlink and jpackage are host-specific, so each platform's image can only be
built on that platform, which is what the CI matrix is for.

## Development capture

A capture run is always a process of its own: `-Dtermina.capture` skips the single-instance hand-off,
so it never becomes a window in a Termina you have open. Anything else started on your own config
folder does hand off; give a manual run `--config-dir` or `--new-instance`.

Terminal bugs are visual and none of them throw. `DevCapture` renders the real window, types into
the real shell, and writes a PNG:

```bash
java --module-path <deps> --add-modules com.termina \
  -Dtermina.capture=/tmp/shot.png \
  -Dtermina.captureCommand='ls --color=always' \
  -m com.termina/com.termina.Main
```

`-Dtermina.captureNativeChord=A;CONTROL+F` presses those keys through the **operating system**, via
a JavaFX `Robot`, rather than firing a KeyEvent we built. That distinction is the point: macOS binds
Ctrl+A, Ctrl+F and their neighbours to its own text commands before an application sees them, so a
synthetic event can only confirm what we already believe the OS sends. Pair it with
`-Dtermina.keyTrace=true`, which logs every key event and the bytes it put on the pseudo-terminal,
flagging any that are not valid UTF-8 — a shell renders an invalid byte and an unknown glyph
identically, and only one of those is a bug on this side.

`-Dtermina.captureLinkAt=x,y[;x,y…]` hovers those points with the link modifier held and prints what
each one resolves to. What a link means depends on the shell's output, the tab's directory and the
filesystem, none of which a unit test has, so this is the only honest check of it.

It can also drive the mouse, firing real events at the view so the whole path is exercised — pixel
to cell, anchor to drag, buffer coordinates to extracted text — and printing what copying yields:

```bash
  -Dtermina.captureDrag=0,35,240,75    # drag-select a region
  -Dtermina.captureDragShift=true      # ...holding shift, to bypass mouse reporting
  -Dtermina.captureClick=60,55,2       # x, y, click count (2 = word, 3 = line)
  -Dtermina.captureScroll=400,200,-320 # x, y, deltaY (negative scrolls down)
  -Dtermina.captureMenu=300,300,false  # screenX, screenY, shift — photographs the context menu
  -Dtermina.captureSettings=true       # ...the settings window instead
  -Dtermina.captureSettingsKeys=file.newTab:C-S-y;file.closeTab:BS
                                       # ...pressing chords into Keyboard rows, by command id and
                                       # KeyChord token (BS is a bare Backspace), printing each row
  -Dtermina.captureProfiles=true       # ...the profile dropdown, listing every entry and what it
                                       # runs — the only way to see what a machine actually found
  -Dtermina.captureTabs=2              # open N extra tabs first
  -Dtermina.captureWindows=1           # ...and N extra windows
  -Dtermina.captureWindowIndex=1       # photograph a window other than the first
  -Dtermina.captureCloseTabs=2         # close N tabs, reporting the child-process count
  -Dtermina.captureCloseAnswer=cancel  # ...answering the running-program prompt (cancel|close)
  -Dtermina.capturePasteAnswer=paste   # answer the multi-line paste prompt up front (paste|cancel);
                                       # without it a run that reaches the prompt hangs on it
  -Dtermina.captureTheme=editora-light # switch theme after the windows exist
  -Dtermina.captureChord=T             # fire an application chord at the scene
                                       # ...,plain for a bare key (F11) with no shortcut modifier
                                       # ...,alt for Alt+key (X,false,alt fires Alt+X)
  -Dtermina.capturePalette=reo         # open the command palette with that query, listing the rows
  -Dtermina.capturePaletteKeys=C-n,C-p # fire chords at the open palette by Emacs token, reporting
                                       # the highlighted row after each — the only check of the
                                       # navigation that goes through the real filters; enter
                                       # runs the row, and any menu it leaves open is listed with
                                       # its anchor (Tab Menu)
  -Dtermina.captureMenuItem='Zoom In'  # click a context-menu item, reporting the setting it moved
  -Dtermina.captureTypeAtFocus=hello   # type at whatever owns the keyboard, and report whether the
                                       # shell received it — the only input option that goes through
                                       # focus rather than around it
  -Dtermina.captureChordAtFocus=C-S-a  # fire whole chords at whatever owns the keyboard, through
                                       # the scene — with captureSearch, whether a chord the find
                                       # bar has no use for leaks past it (captureSelectionAtEnd)
  -Dtermina.captureCheckUpdates=true   # run the update check, then list every dialog left open
  -Dtermina.captureDebugLogFind=warning  # open the Debug Log and search it; that window is the picture
  -Dtermina.captureMessageLogFilter=sync # open the Message Log and type into its filter; likewise
  -Dtermina.captureUpdateAvailable=99.0.0  # a newer version, as the check would report it: the
                                       # Help row and the status bar's link (statusBar= reports it)
  -Dtermina.captureSyncNow=true        # run Sync Settings Now, then list every dialog left open
  -Dtermina.captureImportConfig=/x.zip;KEY_BINDINGS,THEMES
                                       # import a configuration zip ticking those parts, skipping
                                       # the file chooser and the dialog, and print the settings
                                       # and theme list after; pair with -Duser.home=<scratch> for
                                       # an export from the palette (capturePalette + enter)
  -Dtermina.captureStealFocus=tabs     # hand focus to chrome (tabs|newtab|menubar) and check
                                       # the guard takes it back
  -Dtermina.captureSearch=error        # open the find bar and type the query, reporting the count,
                                       # the current match and where the view scrolled to
  -Dtermina.captureSearchRegex=true    # ...as a regular expression
  -Dtermina.captureSearchSteps=2       # ...pressing Enter that many times
  -Dtermina.captureMarks=true          # print the OSC 133 commands the shell has marked
  -Dtermina.captureJumpPrompt=prev,next # jump between them, printing where each landed
  -Dtermina.captureSelectLastOutput=true # select the last command's output and print it — read
                                       # from the buffer, never the clipboard, which is the user's
  -Dtermina.capturePredict='git status' # draw a type-ahead prediction at the cursor
  -Dtermina.captureKeystrokes=C-a,S-enter,escape,key:SHIFT
                                       # fire whole keystrokes (press, typed, release) at the
                                       # terminal; pair with a program that prints what it reads.
                                       # Reports the context menu and where it opened if one is up
                                       # (S-f10, key:CONTEXT_MENU)
  -Dtermina.capturePanes=right,down,left,close
                                       # split, move between and close panes, printing the tree
                                       # after each step: H[0, V[1*, 2]], * on the active pane;
                                       # also max, move (to a new tab), move:N, closeTab, reopen,
                                       # profile:N (open profile N) and styles (each pane's
                                       # profile, theme and font)
  -Dtermina.captureDragPath='10,300;10,-60' # press, drag through the points, hold the last for
  -Dtermina.captureDragHoldMs=1000     # ...this long (a pointer past the edge autoscrolls), release
  -Dtermina.captureDragAlt=true        # ...holding Alt, for a block
  -Dtermina.captureSelectionAtEnd=true # print the selection again just before the photograph
  -Dtermina.captureMiddleClick=200,100 # paste with the middle button (PRIMARY on Linux)
  -Dtermina.captureImportTheme=Dracula.terminal # import a scheme, select it, print its colours
  -Dtermina.captureCloseWindow=1       # close window N, counting the shells before and after
  -Dtermina.captureReopenWindow=1      # close window N as the user would, then Reopen Closed Tab
                                       # in the first, counting windows and tabs
  -Dtermina.captureMoveTab=0,3         # move a tab as a drop would; the drag report then says
                                       # whether every header still carries its gestures
  -Dtermina.captureTabMenuEscape=true  # with captureTabMenu, report the menu and the keyboard's
                                       # owner a moment later, then press Escape at it
  -Dtermina.captureFailShellStarts=2   # make the next N shell starts fail, as an unstartable
                                       # shell's would (with captureWindows=1: the window stays)
  -Dtermina.captureSettleMs=2500       # how long to wait for the shell before driving it
  -Dtermina.captureAfterCommandMs=2000 # ...and after captureCommand before the input options fire;
                                       # the command is re-sent every 400 ms until it is on screen,
                                       # so a command that scrolls itself off needs more
  -Dtermina.captureTypeTab=git ch      # type the text then press Tab, without Return — completion
  -Dtermina.captureAfterTab=y          # ...answering zsh's "list all N possibilities?" question
  -Dtermina.capturePressKey=TAB,true   # fire one real key event (KeyCode, shift) at the terminal —
                                       # for keys the view handles itself (Shift+PageUp, Shift+Insert)
  -Dtermina.capturePressKeyAfter='ls ' # ...after typing a partial command, so it lands in the
                                       # shell's line editor
  -Dtermina.captureKeyProbe=true       # press the keys that have no encoding and report whether
                                       # focus stayed in the terminal
  -Dtermina.captureTypeIn=1:ls         # run text in tab N without selecting it
  -Dtermina.captureSelectTab=1         # select tab N, to check what follows the selection
  -Dtermina.captureSelectTabLater=1,1000 # ...N milliseconds later, after its output has arrived
  -Dtermina.captureBellIn=1            # ring the bell in tab N, to check the mark on its header
  -Dtermina.captureRenameTab=build     # name the active tab, bypassing the dialog
  -Dtermina.captureDuplicateTab=true   # duplicate the active tab, reporting the count
  -Dtermina.captureReopenTab=true      # reopen the last closed tab; pair with captureCloseTabs
  -Dtermina.captureTabMenu=1           # open tab N's header menu (see captureTabMenuEscape)
  -Dtermina.captureBarMenu=File        # open that menu of the in-window bar and photograph it
  -Dtermina.captureMenuDismiss=escape  # open the context menu and dismiss it (escape|click)
  -Dtermina.captureScrollBarTo=120     # move the scrollbar as a drag would, through its listener
  -Dtermina.captureDragScrollBar=40,300 # drag the bar's thumb from y1 to y2, fired at the bar
  -Dtermina.captureResizeTo=1000,700   # resize the window before it closes, so the next run can
                                       # show whether the size was remembered
  -Dtermina.captureOpenLink=true       # with captureLinkAt: actually open the last link found
  -Dtermina.captureScreenText=true     # print the screen as text — the smoke test's check that a
                                       # shell ran and the emulator parsed what it wrote
  -Dtermina.captureLogProbe=true       # push a warning and an uncaught exception through the
                                       # logging path, to check the debug log captures them
  -Dtermina.captureShortcuts=true      # photograph the Keyboard Shortcuts window (or =<query>)
  -Dtermina.captureAbout=true          # ...the About window
  -Dtermina.capturePauta=true          # ...the Pauta Conformance window
  -Dtermina.captureDoctor=true         # ...the Doctor; its checks run on a worker, so add
                                       #    -Dtermina.captureAfterCommandMs=12000 to see the rows
  -Dtermina.captureScale=2             # write the picture at twice the window's size, as a display
                                       # of twice the density shows it (one to four)
```

Each run prints a `windows=… terminals=… descendants=…` line. The descendant count is the useful
one — each tab owns a shell, so a tab that closes without reaping it leaks a process and nothing on
screen looks wrong. Read it only once things have settled: a login shell forks several children
while it runs the user's profile, so a count taken too early reads high.

`scripts/dev-run.sh` wraps all of this: it builds the module path itself, so any `-D` can be passed
through (`mvn javafx:run` cannot take ad-hoc options — the plugin's are fixed in the pom).

### The README's pictures

`scripts/screenshots.sh` retakes the README's hero, dark (`docs/screenshot.png`) and light
(`docs/screenshot-light.png`), at 2×. It runs the capture hook on the headless platform, so no
window opens, with a configuration folder and a shell start-up file of its own, so nothing of your
desktop or your prompt is in the picture. Run it after any change to how the window looks, and use
`-Dtermina.captureScale=2` for a picture meant for the website or a bug report.

At a scale above 1 the scene is drawn through a transform, which keeps everything made of nodes
sharp. The terminal is a canvas, which is a bitmap: for the run, each view's canvas is given the
larger picture's pixels, drawn at the scale, and shown at its layout size, as a display of that
density would have it. Without that it is the window's own pixels stretched.

## Diagnosing a freeze

"It froze" has two causes that look identical from the outside — the UI thread blocked, or the
shell had nothing to say yet — and only the first is Termina's. `-Dtermina.stallLog=<ms>` reports
gaps between animation frames, which the FX thread drives, so a long gap means it was genuinely
blocked:

```bash
scripts/dev-run.sh -Dtermina.stallLog=400
JAVA_TOOL_OPTIONS=-Dtermina.stallLog=400 ./target/dist/Termina.app/Contents/MacOS/Termina
```

The gap says how long; a second line says where. While the thread is stuck, a watcher on a thread
of its own (`ui.StallSampler`) prints its stack, once for each stall:

```
[termina] UI thread not responding for 412ms; it is in:
[termina]   at com.termina/com.termina.ui.MonospaceFonts.isMonospace(MonospaceFonts.java:147)
[termina]   …
[termina] UI thread blocked for 431ms
```

That one is real: it is how the font picker's measuring was found on the FX thread, and moved off
it.

It is opt-in because an always-running `AnimationTimer` forces a 60fps pulse even when the terminal
is idle.
