# 0007. The working directory is read from the OS

Recorded: 2026-11-07, from the reasoning in the code and the old AGENTS.md.

## Context

The shell will not reliably say where it is. A window title arrives only as an escape sequence (OSC
0), emitting it is the prompt's job, and every prompt framework that replaces `PS1` drops it.

## Decision

Read the foreground process's directory from the operating system (`ProcessCwd`, `CwdWatcher`):
`/proc` on Linux, `proc_pidinfo` on macOS, the PEB on Windows. A title the shell does set still
takes precedence, and `FxTerminalDisplay` keeps the two apart, so a program that sets and later
clears a title (vim, on exit) leaves the directory showing again.

## Consequences

- New tabs inherit the directory, and relative paths are links, whatever the prompt does.
- The Windows read has never run on Windows.
