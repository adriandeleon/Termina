# 0009. Config is JSON, with a schema version

Recorded: 2026-11-07. Supersedes [0005](0005-settings-in-a-properties-file.md).

## Context

[0005](0005-settings-in-a-properties-file.md) kept settings in a hand-editable
`settings.properties` with no version. Pauta 2.0.2 requires every config file to carry a schema
version with a migration step per version (SET-09), and a file from a newer build to be backed up
rather than read (SET-10). It also recommends JSON (PLAT-14), with preferences and session state in
separate files (SET-08). An unversioned file can't tell a build whether it understands it.

## Decision

- **Two JSON files.** `settings.json` holds preferences and `workspace-state.json` holds session
  state (window geometry, the update check's memory). Each is
  `{"schemaVersion": n, "settings": {"dotted.key": value}}`.
- **Flat dotted keys.** The keys are the names the code and the docs already use. A value is written
  as a JSON boolean or number only when it reads back to exactly the same text.
- **Streaming, not data binding.** jackson-core's streaming API reads and writes the files
  (`ConfigJson`); a two-file config needs no data-binding library, and the runtime image no
  reflection.
- **Migrations.** `ConfigJson.MIGRATIONS` holds one step per version.
- **Files that won't be read.** A newer file is kept as `.v<n>.bak` and defaults load; one that isn't
  this format is kept as `.corrupt.bak`.
- **The old file.** A pre-0.15 `settings.properties` is imported once and kept as
  `.properties.migrated`.

## Consequences

- **What stays the same.**
  - Clamping on read.
  - The save that merges only this process's changes under a lock (now per file).
  - Every key name.
- **Hand-editing is a little stricter.** JSON escapes backslashes, so a Windows path is typed as
  `"C:\\Windows\\System32\\wsl.exe"`. A file that isn't valid JSON is set aside whole, where
  a properties file lost only its bad line.
- **The JSON recommendation is met, but not with data-bound objects.** The file shape is a
  versioned object, which is what the rule's check asks for.
