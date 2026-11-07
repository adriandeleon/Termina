# 0005. Settings are a hand-editable properties file

Recorded: 2026-11-07, from the reasoning in the code and the old AGENTS.md.

## Context

A terminal's settings file is one someone will reasonably open in a text editor, and a JSON or TOML
library is a dependency for one file.

## Decision

`~/.termina/settings.properties`, read through `SettingsFormat` (properties with backslashes taken
literally, in UTF-8, so a Windows path can be typed as it is). Every getter falls back to a default,
and every value is clamped on read as well as on write, so a typo or an out-of-range number
degrades that one key rather than the whole file.

## Consequences

- No dependency, and the file reads well by hand.
- There is no schema version or migration step yet (Pauta SET-09, SET-10). Unknown keys survive
  a save, because a save writes only this process's changes over a fresh read of the file, so an
  older build does not drop what a newer one wrote; but it cannot recognise a file newer than
  itself.
