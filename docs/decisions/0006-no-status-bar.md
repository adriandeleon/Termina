# 0006. No status bar

**Superseded by [0008](0008-optional-status-bar.md).**

Recorded: 2026-11-07, from the reasoning in the code and the old AGENTS.md.

## Context

Every row of the window that is not the terminal is a row the terminal does not get, and the
terminal is what the user is looking at.

## Decision

No status bar. An available update shows in the Help menu; a message that needs saying is a dialog
(`TerminalWindow.report`), reused so that a sequence ("checking…", then the answer) is one dialog.

## Consequences

- Pauta's echo area and Message Log (WIN-01, DIAG-01, MLOG-01) have nowhere to live. Pauta scopes the
  Message Log to "every app with a status bar" while making the status bar a MUST for all, so this
  is open with Pauta rather than settled.
