# 0001. JediTerm is the emulator

Recorded: 2026-11-07, from the reasoning in the code and the old AGENTS.md.

## Context

xterm compatibility is a very long tail — cursor modes, character sets, scroll regions, four mouse
wire formats, a hundred CSI sequences — and getting it wrong surfaces as programs subtly misbehaving
rather than as anything that looks like a terminal bug. Writing an emulator was not the project.

## Decision

Use `jediterm-core`, which is UI-agnostic: it owns a `TerminalTextBuffer` and knows nothing about
drawing. Termina reads the buffer and paints it (`com.termina.ui`); everything between the PTY and
the buffer is JediTerm's.

## Consequences

- Gaps in JediTerm are Termina's to work around where the emulator reads: the interceptor chain in
  `TerminalSession.readThrough` (APC strings, OSC 7/52/133, the kitty keyboard modes, colon SGR).
  `EmulatorGapsTest` pins what is still unrepaired, so a JediTerm upgrade that fixes one fails it.
- Some of its layers are deliberately unused — the text-scanning hyperlink filters (see
  [0004](0004-links-under-the-pointer.md)) and the Swing type-ahead model.
- It is not on Maven Central; the pom names JetBrains' repository.
