# Changelog

Notable changes, newest first. Versions follow [semantic versioning](https://semver.org); until
1.0.0 the minor number moves for anything user-visible.

## Unreleased

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

### Known gaps

- Windows and Linux are unrun. The code paths exist and pty4j carries every platform's natives, but
  only macOS has actually been exercised.
- Installers are not built and nothing is signed — `-Pdist` produces an app image, and macOS will
  refuse a downloaded copy until it is signed and notarised.
- Clear Light inherits four low-contrast colours from Apple's palette (white, bright yellow, bright
  cyan, bright white against its white background). Kept rather than corrected, so the port matches
  its source.
- No tab reordering, no splits, no search in scrollback, no keybinding customisation.
