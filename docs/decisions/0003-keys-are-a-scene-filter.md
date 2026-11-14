# 0003. Key bindings are a scene filter, and app chords leave plain Ctrl to the shell

Recorded: 2026-11-07, from the reasoning in the code and the old AGENTS.md.

## Context

The plain `Ctrl+letter` chords belong to the shell: Ctrl+T is readline's transpose, Ctrl+W deletes
a word, Ctrl+C is SIGINT. And JavaFX fires menu accelerators only after an event bubbles unconsumed,
which the terminal view never lets a `Ctrl+letter` do.

## Decision

Application chords are `Ctrl+Shift+key` off macOS (`Cmd+key` on it). Bindings run from a scene-level
event filter in the capturing phase; menu accelerators are labels only (`keepAcceleratorsDisplayOnly`),
and `MenuAction` holds label, chord and action together so the menu and the filter cannot disagree.
There is one table of default chords, with the user's `keys.*` overrides on top.

## Consequences

- Pauta's Emacs `M-x` and its JSON keymaps (KBD-05, KBD-09) do not fit: Alt+X and most Emacs chords
  are the shell's in a terminal. The palette opens on Ctrl+Shift+P / Cmd+Shift+P.
- Pauta's menu rows built as `CustomMenuItem`s (MENU-02) do not fit either: one such item makes
  JavaFX decline the macOS screen menu bar for every menu.
- Since 2026-11-07 the default chords are data, `keymaps/keymap.json` and `keymap.mac.json`
  (`ui.Keymap`), one file per platform. That meets the half of KBD-09 that asks for keymaps as
  data, but not the Emacs/CUA pair, for the reason above.
