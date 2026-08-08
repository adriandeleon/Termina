# Changelog

Notable changes, newest first. Versions follow [semantic versioning](https://semver.org); until
1.0.0 the minor number moves for anything user-visible.

## Unreleased

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
