# 0010. The status bar and the title-bar menus are on by default

Recorded: 2026-11-12. Supersedes the default in [0008](0008-optional-status-bar.md); the rest of
that record stands.

## Context

[0008](0008-optional-status-bar.md) made the status bar an option and left it off, for the row it
costs the terminal. The menu bar in the title bar was an option too, off by default, because a
stage's style cannot change once the window is shown and the decorated window was the known one.

Pauta 6.0.1 asks for both as defaults: a status bar shown unless the user hides it (WIN-01), and on
Windows and Linux the menu bar in the title bar, with a setting that falls back to a decorated
window (WIN-03). Termina recorded both as pending when it moved to that edition. Both options
already existed and were tested; only where they start was in question.

## Decision

`ui.showStatusBar` and `window.menuBarInTitleBar` default to on. Both stay settings, with the same
rows, the same View menu toggle and the same commands.

## Consequences

- A message goes to the echo area, not a dialog, unless the bar is hidden. Fewer interruptions;
  the Message Log still has every one.
- With both on, the terminal has the same number of rows as before on Windows and Linux: the
  title bar's row goes to the menus and the status bar takes one back. On macOS, where the menus
  were never in the window, the terminal is one row shorter.
- Someone who never touched either setting sees both change on the update that carries this. The
  changelog says so, and each is one click to undo.
- Stored values are untouched: a file that says `false` keeps saying it.
