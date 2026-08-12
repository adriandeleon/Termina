# Changelog

Notable changes, newest first. Versions follow [semantic versioning](https://semver.org); until
1.0.0 the minor number moves for anything user-visible.

## 0.6.2 — 2026-08-11

Interface fixes and polish. No new behaviour, so the patch number moves.

### Changed

- **The zoom row separates full screen from the zoom controls.** The minus, the percentage and the plus
  are one control — a value with a decrement and an increment — while full screen is a different action
  that happens to share the row, and the only button there that closes the menu. It now sits a little
  apart instead of flush against the plus. The row does not get wider: the gap comes out of the space
  between the label and the controls.

- **"Colour theme" is now "Color theme."** The only British spelling in the interface. Its Settings
  search still matches both, so typing either word finds the row.

### Fixed

- **Tabs can be dragged to reorder them.** They always could, in principle: the gesture, the drop-side
  detection and the accent edge showing where the tab would land have been there since tabs were. It was
  installed on the title *label*, though, and a tab is a great deal wider than its name — measured on a
  three-tab window, 286 pixels of tab and 13 of label. Unless you caught the two characters of text,
  nothing happened, which from the outside is a terminal that cannot reorder tabs. The gesture now covers
  the whole tab, and what follows the pointer is the tab rather than its text.

### Known gaps

Unchanged from 0.6.1.

- **OSC 8 hyperlinks are not honoured.** Links are found by looking at the text on screen, so one an
  application declares explicitly — `ls --hyperlink`, some build tools — is only clickable when its
  visible text happens to be the target. See issue #11.
- **Nothing is signed or notarised.** macOS will refuse a downloaded copy until it is; Windows will
  warn.
- **Full screen is misplaced under GNOME's fractional scaling on Wayland.** JavaFX has no Wayland
  backend and runs through XWayland; with a fractional monitor scale, and more so with mutter's
  experimental `xwayland-native-scaling`, the window is sized and positioned by the compositor to
  something other than the screen it was given. JavaFX reports the window as full screen and
  correctly sized throughout, which is why nothing inside the app can see it. Maximising is
  unaffected.
- **Quitting is not guarded**, only closing is. There is no Quit item, so on Linux and Windows
  quitting *is* closing the last window and the prompt covers it; macOS `Cmd+Q` reaches
  `App.stop()`, which is after the decision and while the toolkit is stopping — a dialog there asks
  a question nobody can act on.
- **The working directory is not shown on Windows**, and a new tab there starts at home rather than
  inheriting — which also means no tab tooltip and no relative-path links there. Handling OSC 7
  would cover Windows and any shell configured to emit it, and is not implemented.
- Windows is still unrun by a human. CI builds and packages it on every commit, which is not the
  same thing.
- Clear Light inherits four low-contrast colours from Apple's palette (white, bright yellow, bright
  cyan, bright white against its white background). Kept rather than corrected, so the port matches
  its source.
- No splits, no search in scrollback, no keybinding customisation.

## 0.6.1 — 2026-08-11

Two fixes to the clickable links 0.6.0 introduced, and a set of dependency bumps. No new behaviour, so
the patch number moves rather than the minor.

### Fixed

- **"Could not run Editora" said nothing about what to do.** Typing an application's name into
  *Open files with* is the obvious thing to try, and on macOS there is usually no command by that
  name — the program lives inside the bundle. The message now distinguishes a command that names
  nothing on this machine from one that would not start, and says what a working value looks like.

- **A configured command is now found the way the user's shell would find it.** An application
  launched from Finder or a desktop entry inherits a stripped `PATH` — no Homebrew, no
  `~/.local/bin`, nothing a version manager added — so a command that runs perfectly in a terminal
  was simply not there from a click. The PATH is taken from a login shell, asked once and only when
  a bare command actually has to be resolved.

### Internal

- Routine dependency bumps: pty4j 0.13.10 → 0.13.12, JNA 5.17.0 → 5.19.1, Jackson 2.22.1, JUnit 5.14.4.
  All patch or minor.

  Checked against a packaged build rather than the suite alone, because pty4j ships the native libraries
  for every platform and JNA is how they are found — and those survive packaging by an accident of JPMS
  rather than by design (see the note in the README). The built app image starts a real shell and runs a
  command in it.

### Known gaps

Unchanged from 0.6.0.

- **OSC 8 hyperlinks are not honoured.** Links are found by looking at the text on screen, so one an
  application declares explicitly — `ls --hyperlink`, some build tools — is only clickable when its
  visible text happens to be the target. See issue #11.
- **Nothing is signed or notarised.** macOS will refuse a downloaded copy until it is; Windows will
  warn.
- **Full screen is misplaced under GNOME's fractional scaling on Wayland.** JavaFX has no Wayland
  backend and runs through XWayland; with a fractional monitor scale, and more so with mutter's
  experimental `xwayland-native-scaling`, the window is sized and positioned by the compositor to
  something other than the screen it was given. JavaFX reports the window as full screen and
  correctly sized throughout, which is why nothing inside the app can see it. Maximising is
  unaffected.
- **Quitting is not guarded**, only closing is. There is no Quit item, so on Linux and Windows
  quitting *is* closing the last window and the prompt covers it; macOS `Cmd+Q` reaches
  `App.stop()`, which is after the decision and while the toolkit is stopping — a dialog there asks
  a question nobody can act on.
- **The working directory is not shown on Windows**, and a new tab there starts at home rather than
  inheriting — which also means no tab tooltip and no relative-path links there. Handling OSC 7
  would cover Windows and any shell configured to emit it, and is not implemented.
- Windows is still unrun by a human. CI builds and packages it on every commit, which is not the
  same thing.
- Clear Light inherits four low-contrast colours from Apple's palette (white, bright yellow, bright
  cyan, bright white against its white background). Kept rather than corrected, so the port matches
  its source.
- No splits, no search in scrollback, no keybinding customisation.

## 0.6.0 — 2026-08-10

The minor number moves for the clickable links; the Meta fix goes with it, and it is the one to read
first if any keyboard chord has ever behaved oddly for you.

### Added

- **Clickable links.** Cmd-click on macOS, Ctrl-click elsewhere, opens what is under the pointer:
  URLs go to the browser, and **paths go to the file** — including a `Foo.java:42:7` out of a stack
  trace or a compiler error. Hold the modifier and a link underlines itself and the pointer becomes
  a hand, so the gesture announces itself; the right-click menu carries **Open Link** and **Copy
  Link Address** for anyone who never finds it. A URL that wraps at the right edge is one link, not
  two.

  A path is only a link when it names a file that **exists**, resolved against that tab's own shell
  directory — which is what makes it usable rather than a screen full of underlined words, since no
  pattern can tell a filename from an ordinary word but the filesystem can. Only `http`, `https`,
  `ftp`, `ftps`, `file` and `mailto` are ever opened as URLs: terminal output is untrusted text, and
  a `javascript:` or an `app://` handler would be a click away from running something.

  Files open in whatever the desktop opens them with. **Settings ▸ Terminal ▸ Links** takes a
  command instead — `editora {file}:{line}` — which is the only way the line number survives, since
  `open` and `xdg-open` take a file and nothing else.

- **Hovering a tab shows its directory**, in full, with the home directory abbreviated as a shell
  writes it. The tab itself is a hundred-odd pixels and JavaFX ellipsises from the end, so it can
  only carry a name; the hover is where the path fits. It keeps showing the directory even when a
  program has set the tab's title — a tab reading `vim` has already said what is running, and where
  it is running has nowhere else to appear. Nothing is shown on Windows, which cannot yet report a
  shell's directory at all.

### Fixed

- **`M-f` and every other Meta chord typed garbage instead of running its command.** On macOS Option
  is a compose key — Option+F is `ƒ` — and the escape was being prefixed to *that*, so `M-f` went out
  as `1b c6 92`. A shell reads the escape as the meta prefix, takes the `c6` with it, and is left
  holding a lone UTF-8 continuation byte, which it renders as an unprintable box wherever the cursor
  was. So the whole readline word-movement and word-killing set — `M-f`, `M-b`, `M-d`, `M-u`, `M-l`
  and the rest — inserted a stray character instead of doing anything.

  Meta is now encoded from the key press, where the key is still the key, rather than from the typed
  event, which by then reports whatever the OS composed. Alt with Ctrl is left alone, because that
  combination is AltGr on Windows and Linux and composing is exactly what it is for.

### Known gaps

Unchanged from 0.5.1, plus the first entry below.

- **OSC 8 hyperlinks are not honoured.** Links are found by looking at the text on screen, so one an
  application declares explicitly — `ls --hyperlink`, some build tools — is only clickable when its
  visible text happens to be the target. See issue #11.
- **Nothing is signed or notarised.** macOS will refuse a downloaded copy until it is; Windows will
  warn.
- **Full screen is misplaced under GNOME's fractional scaling on Wayland.** JavaFX has no Wayland
  backend and runs through XWayland; with a fractional monitor scale, and more so with mutter's
  experimental `xwayland-native-scaling`, the window is sized and positioned by the compositor to
  something other than the screen it was given. JavaFX reports the window as full screen and
  correctly sized throughout, which is why nothing inside the app can see it. Maximising is
  unaffected.
- **Quitting is not guarded**, only closing is. There is no Quit item, so on Linux and Windows
  quitting *is* closing the last window and the prompt covers it; macOS `Cmd+Q` reaches
  `App.stop()`, which is after the decision and while the toolkit is stopping — a dialog there asks
  a question nobody can act on.
- **The working directory is not shown on Windows**, and a new tab there starts at home rather than
  inheriting — which also means no tab tooltip and no relative-path links there. Handling OSC 7
  would cover Windows and any shell configured to emit it, and is not implemented.
- Windows is still unrun by a human. CI builds and packages it on every commit, which is not the
  same thing.
- Clear Light inherits four low-contrast colours from Apple's palette (white, bright yellow, bright
  cyan, bright white against its white background). Kept rather than corrected, so the port matches
  its source.
- No splits, no search in scrollback, no keybinding customisation.

## 0.5.1 — 2026-08-10

Two fixes, no new behaviour, so the patch number moves rather than the minor.

### Fixed

- **macOS had no menus at all.** Not in the screen menu bar and not in the window: File, Edit, View,
  Window and Help were unreachable, leaving only the chords, the command palette and the right-click
  menu. JavaFX refuses `useSystemMenuBar` for the whole bar when any menu holds a custom item —
  AppKit draws those menus and cannot render a JavaFX node — and the zoom row is one, so the menus
  fell back into the window, where macOS collapses the bar to nothing on the assumption that the
  screen has them. The View menu now shows Zoom In, Zoom Out, Actual Size and Full Screen as
  ordinary items on macOS, which is what the platform can draw, and keeps the row everywhere else
  and in the right-click menu on every platform. Should a custom item reach the bar again, the
  in-window bar is no longer collapsed, so the cost is a Mac's screen menus rather than all of them.

- **The settings pages ran into the scrollbar.** Every control on the right — the combos, the
  checkboxes, the opacity readout — ended flush against it, and the preview's border appeared to
  pass underneath. The page now keeps the same margin from the scrollbar that it keeps from the
  sidebar, whether or not it is long enough to scroll.

### Known gaps

Unchanged from 0.5.0, less the macOS menus above.

- **Nothing is signed or notarised.** macOS will refuse a downloaded copy until it is; Windows will
  warn.
- **Full screen is misplaced under GNOME's fractional scaling on Wayland.** JavaFX has no Wayland
  backend and runs through XWayland; with a fractional monitor scale, and more so with mutter's
  experimental `xwayland-native-scaling`, the window is sized and positioned by the compositor to
  something other than the screen it was given. JavaFX reports the window as full screen and
  correctly sized throughout, which is why nothing inside the app can see it. Maximising is
  unaffected.
- **Quitting is not guarded**, only closing is. There is no Quit item, so on Linux and Windows
  quitting *is* closing the last window and the prompt covers it; macOS `Cmd+Q` reaches
  `App.stop()`, which is after the decision and while the toolkit is stopping — a dialog there asks
  a question nobody can act on.
- **The working directory is not shown on Windows**, and a new tab there starts at home rather than
  inheriting. Handling OSC 7 would cover Windows and any shell configured to emit it, and is not
  implemented.
- Windows is still unrun by a human. CI builds and packages it on every commit, which is not the
  same thing.
- Clear Light inherits four low-contrast colours from Apple's palette (white, bright yellow, bright
  cyan, bright white against its white background). Kept rather than corrected, so the port matches
  its source.
- No splits, no search in scrollback, no keybinding customisation.

## 0.5.0 — 2026-08-08

### Added

- **A tooltip on each tab's close button**, naming the action and its chord, as the new-tab button
  already did.

### Fixed

- **Full screen from the zoom row looked like it did nothing.** The window went full screen, but the
  menu stayed open on top of it — and the menu is the thing you are looking at. The row keeps itself
  open on purpose, because zooming is two or three presses to find the right size; going full screen
  is done once, so that button now closes the menu, as the same button does in Firefox's panel.

### Known gaps

- **Nothing is signed or notarised.** macOS will refuse a downloaded copy until it is; Windows will
  warn.
- **Full screen is misplaced under GNOME's fractional scaling on Wayland.** JavaFX has no Wayland
  backend and runs through XWayland; with a fractional monitor scale, and more so with mutter's
  experimental `xwayland-native-scaling`, the window is sized and positioned by the compositor to
  something other than the screen it was given. JavaFX reports the window as full screen and
  correctly sized throughout, which is why nothing inside the app can see it. Maximising is
  unaffected.
- **Quitting is not guarded**, only closing is. There is no Quit item, so on Linux and Windows
  quitting *is* closing the last window and the prompt covers it; macOS `Cmd+Q` reaches
  `App.stop()`, which is after the decision and while the toolkit is stopping — a dialog there asks
  a question nobody can act on.
- **The working directory is not shown on Windows**, and a new tab there starts at home rather than
  inheriting. Handling OSC 7 would cover Windows and any shell configured to emit it, and is not
  implemented.
- Windows is still unrun by a human. CI builds and packages it on every commit, which is not the
  same thing.
- Clear Light inherits four low-contrast colours from Apple's palette (white, bright yellow, bright
  cyan, bright white against its white background). Kept rather than corrected, so the port matches
  its source.
- No splits, no search in scrollback, no keybinding customisation.

## 0.4.0 — 2026-08-08

### Changed

- **No rule between settings rows.** A line under every row turned a page of eight settings into
  eight boxes, and the eye ended up reading the lines rather than the text. Separation is space
  now, as it is in Editora's settings — which is why the row padding grew as the border went. Row
  titles and descriptions take Editora's sizes with them, stated in px for both rather than left to
  inherit.

### Added

- **A new tab opens in the current tab's directory**, instead of always starting at home. Read from
  the shell process, the same way the tab titles are, so it needs nothing of the shell. Linux and
  macOS; Windows keeps starting at home, as it did.
- **Jump straight to a tab** with `Cmd+1`…`9` on macOS, `Alt+1`…`9` elsewhere — the convention both
  platforms already use. The last digit means the *last* tab rather than the ninth, so it works in a
  session with three tabs open. Chords and palette entries only: nine menu rows would be most of the
  Window menu, and all but the first few dead most of the time.
- **Closing a tab with something running asks first.** A tab always has a shell, so the question is
  whether the shell has *children* — an idle prompt has none, and one running `vim`, an ssh session
  or a build has one, which is what keeps this from becoming a confirmation people learn to dismiss
  without reading. The dialog names what it is about to end. Background jobs count: `sleep 60 &`
  dies with the tab just as surely. Every deliberate close goes through one place — the close
  button, the menu item, the chord, Close Others, Close to the Right, and closing the window, which
  ends every tab's programs at once. A reorder and a shell that exited on its own do not ask, since
  neither ends anything. Settings → Terminal turns it off for anyone who would rather not be asked.

### Known gaps

- **Nothing is signed or notarised.** macOS will refuse a downloaded copy until it is; Windows will
  warn.
- **Quitting is not guarded**, only closing is. There is no Quit item, so on Linux and Windows
  quitting *is* closing the last window and the prompt covers it; macOS `Cmd+Q` reaches
  `App.stop()`, which is after the decision and while the toolkit is stopping — a dialog there asks
  a question nobody can act on. Doing it properly means intercepting the quit before `Platform.exit`.
- **The working directory is not shown on Windows**, and a new tab there starts at home rather than
  inheriting. Reading another process's cwd means `NtQueryInformationProcess`, a different order of
  undertaking from a `readlink`. Handling OSC 7 would cover Windows and any shell configured to
  emit it, and is not implemented.
- Windows is still unrun by a human. CI builds and packages it on every commit, which is not the
  same thing.
- Clear Light inherits four low-contrast colours from Apple's palette (white, bright yellow, bright
  cyan, bright white against its white background). Kept rather than corrected, so the port matches
  its source.
- No splits, no search in scrollback, no keybinding customisation.

## 0.3.0 — 2026-08-08

### Added

- **Zoom is one row in the menus, as a browser does it** — `Zoom  −  110%  +  ⤢` — in both the View
  menu and the right-click menu, replacing the three separate Zoom In / Zoom Out / Actual Size
  items. The menu stays open while the buttons are pressed, because zooming is something you do two
  or three times to find the size you want. The percentage is itself the reset. The three actions
  keep their chords and their places in the command palette.
- **Zoom is now a level, separate from the configured font size.** It used to add a pixel to the
  font-size preference, so there was no "100%" to show and resetting the zoom silently discarded the
  size the user had chosen. `font.zoom` multiplies `font.size`; Settings still edits the size, and
  the zoom row moves through a browser's ladder (30% to 300%). **On upgrade, whatever size you had
  becomes your base at 100%** — a size previously arrived at by zooming cannot be told from one that
  was chosen.
- **Full screen** (`F11`, `Ctrl+Cmd+F` on macOS), from the zoom row and the command palette.

### Fixed

- **The terminal could still lose focus, by paths other than the one already fixed.** Making the
  tab strip non-traversable stopped it being *traversed* to; it did not stop anything calling
  `requestFocus`, which ignores that flag. A click on the already-selected tab header takes focus
  without changing the selection, so the listener that follows a tab switch never runs — and the
  strip's empty space, a drag that reorders nothing, and the menu bar after its menu closes all do
  the same. Every one of them presents as a window that looks ready and swallows typing, which is
  why the report behind this could not be reproduced. The terminal now takes focus back whenever
  nothing else has a claim on it; while the palette, a menu or the context menu is open, focus is
  left exactly where it is.

- **A directory listing's columns still did not line up.** Fixing the advance *within* a style run
  left the run's *starting* position measured in the wrong unit: JediTerm hands the renderer an
  index into the line's `char[]`, not its column, and the two disagree by one for every astral
  character earlier in the line — which with `eza --icons` is one per name. The drift accumulates,
  so a row with two icons ended two columns right of a row with one and the listing diverged the
  further right you looked. The emulated grid was correct throughout; only the drawing was wrong.
- **Selecting a line with icons copied text a column off from what it highlighted.** The same
  disagreement in the other direction: a mouse position and a highlight rectangle are columns,
  while a selection is handed to JediTerm, which indexes the line's `char[]`. Dragging across
  `Desktop … Downloads … Games` copied `…Game`. Whole-line and select-all were bounded by the
  column count too, which cut the tail off any line carrying more slots than the grid has columns.

### Known gaps

- **Nothing is signed or notarised.** macOS will refuse a downloaded copy until it is; Windows will
  warn.
- **The working directory is not shown on Windows.** Reading another process's cwd there means
  `NtQueryInformationProcess`, a different order of undertaking from a `readlink`. Handling OSC 7 —
  the escape sequence a shell can send to report its directory — would cover Windows and any shell
  configured to emit it, and is not implemented.
- Windows is still unrun by a human. CI builds and packages it on every commit, which is not the
  same thing.
- Clear Light inherits four low-contrast colours from Apple's palette (white, bright yellow, bright
  cyan, bright white against its white background). Kept rather than corrected, so the port matches
  its source.
- No splits, no search in scrollback, no keybinding customisation.

## 0.2.0 — 2026-08-08

### Added

- **Tabs and the title bar show the working directory**, read from the shell process itself rather
  than waiting to be told. The usual channel is an escape sequence the prompt emits, and any prompt
  framework that replaces `PS1` — Oh My Bash, Starship, powerlevel10k — quietly drops it; with one
  of those installed every tab was labelled "Termina" and none could be told from another. The title
  bar gets the path (`~/src/adl/Termina`), the tab its last segment (`Termina`), and a title a
  program sets for itself still wins. Linux and macOS.
- **Zoom In, Zoom Out and Actual Size in the right-click menu**, plus **Show Menu Bar** as a
  checkbox — the View menu keeps its "Hide Menu Bar" verb, because it lives inside the bar and can
  only be opened while the bar is showing. The right-click menu is the one you reach once the bar is
  gone, so it states which state you are in, and is now the direct way back.

### Fixed

- **Nothing could be typed into the first window until a second tab was opened.** The scene gives
  initial focus to the first focus-traversable node, and a `TabPane` is one — so it raced the
  terminal's own focus request and, on a cold launch where there is a JVM to warm and a shell to
  start, won. The window looked completely ready with every keystroke going to the tab strip. No
  part of the chrome is focus-traversable now, so the terminal is the only thing focus can land on.
- **The Linux launcher showed a stock Java icon.** `--icon` reaches the app image but not the deb
  and rpm bundlers, which regenerate `lib/Termina.png` from their own resources — the 0.1.0 packages
  shipped a file byte-identical to jpackage's generic 32×32 `JavaApp.png`. The icon is now staged
  into the jpackage resource directory, which is the only override those bundlers honour.
- **Development builds were told daily to update to the release they are ahead of.** Only releases
  are ever offered, so the version compared against has to be a release too — and a snapshot is not
  one. `0.1.0-SNAPSHOT` now counts as `0.1.0` rather than sorting below it, while a genuinely newer
  release is still offered. Release candidates are unaffected: an `-rc1` really does precede its
  release.
- **Dismissing the update-check result revealed a stale "Checking for updates…" behind it.** The
  check reports twice — once when it starts, once when it finishes — and each report opened its own
  alert instead of replacing the first, so the two stacked and were dismissed in reverse order.

### Known gaps

- **Nothing is signed or notarised.** macOS will refuse a downloaded copy until it is; Windows will
  warn.
- **The working directory is not shown on Windows.** Reading another process's cwd there means
  `NtQueryInformationProcess`, a different order of undertaking from a `readlink`. Handling OSC 7 —
  the escape sequence a shell can send to report its directory — would cover Windows and any shell
  configured to emit it, and is not implemented.
- Windows is still unrun by a human. CI builds and packages it on every commit, which is not the
  same thing.
- Clear Light inherits four low-contrast colours from Apple's palette (white, bright yellow, bright
  cyan, bright white against its white background). Kept rather than corrected, so the port matches
  its source.
- No splits, no search in scrollback, no keybinding customisation.

## 0.1.0 — 2026-08-07

First release.

### Added

- **Tabs and multiple windows.** Each tab owns its own shell; each window its own tab strip and menu
  bar. Settings, the preferences window and the theme are shared across all of them.
- **Menu bar** — File, Edit, View, Window, Help — using the macOS system menu bar where there is
  one. Menu items and key bindings come from a single definition, so the menu cannot advertise a
  shortcut nothing implements.
- **Right-click menu**: New Tab, New Window, Copy, Paste, Select All, Clear Scrollback, Settings.
  Suppressed while a program has grabbed the mouse; Shift forces it.
- **Settings window** (`Cmd/Ctrl+,`): theme, font family and size, cursor shape, visual bell,
  scrollback depth, shell, Alt-as-Meta, and hiding the tab bar. Live apply, no OK button.
- **Themes**: Editora Dark and Light (Caret & Ink), plus **Clear Dark** and **Clear Light** ported
  from macOS Terminal with colours read out of Apple's own profiles.
- **Menu bar can be hidden** (`Cmd/Ctrl+Shift+M`), on the platforms that draw one in the window.
- **Tabs tile the window width** and shrink as more are added, with a **+** button in the strip.
- **Per-tab right-click menu**: new, close, close others, close to the right, move left/right.
- **Window title follows the selected tab**, as macOS Terminal does.
- **Optional scrollbar** for the scrollback (Appearance → Window), palette-coloured.
- Tab-strip glyphs matched in size and alignment; dragging the scrollbar no longer selects text.
- **Window size is remembered** across launches, clamped to the current screen.
- **Fixed**: a Nerd Font icon (or any emoji) in a directory listing shifted the rest of its line one
  column right, so columns did not line up between rows.
- **Command palette** (Shift+Cmd/Ctrl+P) over every menu command, plus the colour themes.
- **Debug log** in Advanced → Diagnostics, mirrored to a file beside the settings.
- **Six interface languages** (en/it/es/fr/pt/de), picked in Appearance → Language.
- **Window opacity** (Appearance → Window), clamped so it cannot hide the window that undoes it.
- **Bundled fonts**: five monospace families plus Inter for the interface, so a terminal looks the
  same on every platform. JetBrains Mono is now the default.
- Tooltip on the new-tab button; "Hide Menu Bar" no longer appears in the menu on macOS.
- **Tab reordering** by drag, or `Cmd/Ctrl+Shift+Left/Right`.
- **Tab bar hides itself** while only one tab is open, reclaiming the row for the terminal.
- **About window** and an **update check** against GitHub releases, throttled to once a day and
  disableable.
- **Selection and copy**: drag, double-click for a word, triple-click for a line.
- **Mouse reporting**, so vim positions its cursor and htop responds to clicks. On the alternate
  screen the wheel falls back to arrow keys, so `less` and `man` scroll without it.
- **Application icon**, carried into window icons and the macOS/Windows/Linux installer formats.
- **Native app image** via `-Pdist` (moditect + jlink + jpackage), and a Maven wrapper so a system
  Maven is not required.

### Fixed

- **Tab and Escape did nothing, and Tab froze the terminal.** Neither is in JediTerm's key encoder,
  and both are control characters the typed-character path drops, so they never reached the shell —
  and the unconsumed Tab moved focus out of the terminal, so everything typed after it went
  elsewhere.
- **A hidden menu bar painted over the terminal**, overlapping the first rows of output.

### Known gaps

- Windows and Linux are unrun. The code paths exist and pty4j carries every platform's natives, but
  only macOS has actually been exercised.
- Installers are not built and nothing is signed — `-Pdist` produces an app image, and macOS will
  refuse a downloaded copy until it is signed and notarised.
- Clear Light inherits four low-contrast colours from Apple's palette (white, bright yellow, bright
  cyan, bright white against its white background). Kept rather than corrected, so the port matches
  its source.
- No tab reordering, no splits, no search in scrollback, no keybinding customisation.
