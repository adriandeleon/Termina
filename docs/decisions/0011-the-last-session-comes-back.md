# 0011. The last session's tabs come back at launch

Recorded: 2026-11-13.

## Context

Termina restored its window's size and place and nothing else: every launch opened one tab in the
home folder. Reopen Closed Tab already knew how to bring a tab back within a run, with its panes,
its names and each shell's directory, and a whole closed window the same way.

Pauta 6.0.1 asks an app that opens sessions to restore the items that were open (WIN-08), and to
take `--no-session` for a launch without them (CLI-04). Termina recorded WIN-08 as pending when it
moved to that edition.

## Decision

The tabs open when the application ends are written to the session-state file,
`workspace-state.json`, as one value (`session.tabs`) in the form Reopen Closed Tab keeps a closed
window in, and the first window of the next launch opens with them. `session.restore` turns it off and is on by default; `--no-session` skips it once.

- **Which tabs.** The last window's when the last window closes; the front window's on Quit. One
  window is restored, not every window that was open.
- **What comes back.** A new shell for each pane, in its directory, with its profile, its name,
  its place in the split and its pin. Not the scrollback, and not what was running.
- **A tab started with `-e`** comes back as a shell in the same directory. Running somebody's
  command again at the next launch, unasked, is not restoring.
- **A command line that names something** (`-d`, `-T`, `-e`) gets a tab of its own after the
  session's, in front.
- **A directory that is gone** is left blank, and that pane starts where its profile does.
- **It is state, not a preference** (SET-08): it sits beside the window's place and size, an
  import never applies it, a problem report never prints it, and sync does not carry it. A file
  of its own was the first draft; one state file is what the guidelines ask for, and `Settings`
  already versions, saves and flushes that one.
- **A capture run keeps no session and restores none**, so a picture never depends on the run
  before it.

## Consequences

- Someone who quits with tabs open finds them at the next launch, where they found one tab
  before. The changelog says so, and it is one switch to undo.
- Someone who ends each shell with `exit` sees no change: the window closes with no tabs, and an
  empty session is one tab.
- A value that cannot be read costs the tabs and never the launch: it is logged, and the window
  opens as it used to.
- Turning the setting off removes the stored session at the next quit, so an old one cannot come
  back when it is turned on again.
