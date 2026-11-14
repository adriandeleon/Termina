# 0012. Key bindings sync as one file, and a Mac's Command is Ctrl+Shift in it

Recorded: 2026-11-13.

## Context

Settings sync kept the key bindings in two files, `keybindings/macos.properties` for Macs and
`keybindings/windows-linux.properties` for everything else, and each computer synced only its own.
The reason was sound as far as it went: a chord does not mean the same on both. An application
chord is `Cmd+T` on a Mac and `Ctrl+Shift+T` elsewhere, because off macOS plain `Ctrl+T` belongs to
the shell.

The cost was that a shortcut changed on a Mac was not changed on a Linux or Windows computer that
synced with it. Pauta 6.0.1 names that as a difference between systems that is on no row of its
table of allowed ones (OS-04), in Termina by name.

## Decision

One file, `keybindings/keybindings.properties`, for every system. Its chords are written as
Windows and Linux press them, and a Mac translates as it reads and writes (`sync.SharedChords`):

- **`Cmd+K` on a Mac is `Ctrl+Shift+K` in the file, and back.** Option rides along. This is the
  pair most of the two default key maps already are, and it never puts a plain `Ctrl`+letter on
  Linux, which would take that key from the shell. The other candidate, Command for Control as
  written, is simpler and does exactly that: a Mac's `Cmd+K` would have become `Ctrl+K`.
- **A chord with no Command, or without both `Ctrl` and `Shift`, is carried as written**: `F11`,
  `Alt+1`, a plain `Ctrl+K` somebody chose.
- **Control and Command together** (`Ctrl+Cmd+F`) are `Ctrl+Meta+Shift+F` in the file, a form of
  their own to come back from.
- **A Mac keeps the chord it has where the file's says the same thing.** `Cmd+K` and
  `Cmd+Shift+K` are one chord in the file, so only a chord that really differs replaces a Mac's.

## Consequences

- A shortcut changed on any computer is changed on every computer that syncs with it. Two
  computers that had each changed the same command differently now disagree once: the first to
  sync after the update writes its chord, and the next one's wins the conflict, as with any other.
- The defaults are not translated, only what the user changed: `Cmd+Shift+P` and `Ctrl+Shift+P`
  stay the palette's defaults on their systems.
- A Linux chord `Ctrl+Shift+Q` becomes `Cmd+Q` on a Mac, which is the system's Quit. The rule
  cannot know which Mac chords the system keeps for itself; Settings shows the conflict.
- The repository's layout version moves to 2. A computer still on an older Termina reads a newer
  layout and says so, where it would have gone on syncing a file nobody else reads. The two old
  files are left in the repository and no longer read; a computer's own bindings are already in
  its settings, and are written to the new file by its first sync.
- A chord a PC bound to the Windows key (`Meta`) is read on a Mac as Command.
