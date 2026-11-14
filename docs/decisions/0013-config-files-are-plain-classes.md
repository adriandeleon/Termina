# 0013. Config files are plain classes, read and written by Jackson

Recorded: 2026-11-13. Supersedes the "Streaming, not data binding" point of
[0009](0009-config-in-json.md); the rest of 0009 stands.

## Context

[0009](0009-config-in-json.md) made the settings JSON and chose jackson-core's streaming API to
read and write it: `ConfigJson` built the file a token at a time. The reasons given were a small
dependency and a runtime image with no reflection in it. Pauta 6.0.1's PLAT-14 asks for more than
the format: config files are JSON *read and written by Jackson as plain classes*, and its check is
that no store builds or parses its JSON by hand.

Two more stores had grown beside the settings since 0009:

- The tabs of the last session ([0011](0011-the-last-session-comes-back.md)), a JSON value written
  with the same streaming code.
- An imported colour scheme, `themes/<name>.properties`, which was not JSON at all.

## Decision

- **One mapper.** `ConfigJson.mapper()` is the `ObjectMapper` every store uses. It passes over a
  field this build does not know, refuses anything after the object, and writes map keys sorted, so
  a file changes only where a value did.
- **One plain class per file.** `ConfigJson.Stored` is `settings.json` and `workspace-state.json`;
  `SessionStore.Stored` is the session's tabs; `ThemeFile.Stored` is a theme. Each is public fields
  and nothing else, with the field order fixed by an annotation.
- **Themes are JSON.** A theme is `themes/<name>.json`, schema version 2, its sixteen colours one
  list. A `<name>.properties` from an older Termina is read, written as JSON under the same name,
  and kept as `<name>.properties.migrated`, the first time the folder is read. The theme's id is its
  file's name without the ending, so nothing that names a theme changes.
- **The module opens three packages to Jackson and to nothing else:** `config`, `ui` and `theme`.

## Consequences

- **The files on disk are unchanged** for the settings and the state: the same shape, the same
  printer. A hand-edited file reads as before.
- **A value of the wrong kind is refused sooner.** In the session's tabs a text where a tab belongs
  now refuses the whole value, where the streaming reader skipped that tab. A tab that is the right
  shape and makes no sense (a split in no known direction) is still left out alone.
- **A zip exported before 0.15 still imports.** Its `themes/*.properties` are taken as they are and
  rewritten as JSON at the next read of the folder.
- **Removing a theme retires an older file of its name too**, as `.migrated`. Left under its own
  name it would be read as a theme not yet rewritten, and the theme would come back.
- **The image grows by about 1.8 MB**: jackson-databind and jackson-annotations. Both are JPMS
  modules, so jlink links them as they are.
- **Reflection is in the image**, on three packages. The tests run on the class path and cannot
  see a missing `opens`; that is a trap, and it is in [`docs/gotchas.md`](../gotchas.md).
- **The files the sync repository holds are not config files** in the rule's sense (SET-22): the
  clone's working tree is Git's, and stays properties.
