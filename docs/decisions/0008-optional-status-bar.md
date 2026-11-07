# 0008. The status bar is an option, off by default

Recorded: 2026-11-07. Supersedes [0006](0006-no-status-bar.md).

## Context

[0006](0006-no-status-bar.md) left the status bar out, because every row that is not the terminal
is a row the terminal does not get. Pauta 2.0.2 makes a status bar with an echo area a MUST
(WIN-01), with a Message Log behind it (DIAG-01, MLOG-01). Without either, a message was a dialog,
and once it was dismissed it was gone.

## Decision

The status bar is a setting, `ui.showStatusBar`, off by default, with a Settings row, a View menu
toggle and a palette entry. Every message goes into one Message Log for the app (Help → Message Log),
whether the bar is on or off. With the bar on, a message goes to its echo area, red for an error
and amber for a warning, with a count of errors nobody has looked at. With it off, a message is the
dialog it always was.

## Consequences

- The terminal keeps its rows unless the user asks for the bar.
- No message is lost: the log has every one, with its time.
- WIN-01's "always present (can be toggled)" is met as an option rather than a default. That
  reason, the cost in rows, is this record.
