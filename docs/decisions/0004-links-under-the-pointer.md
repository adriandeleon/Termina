# 0004. Links are found under the pointer

Recorded: 2026-11-07, from the reasoning in the code and the old AGENTS.md.

## Context

JediTerm can mark links as lines are written, through a `TextProcessing` filter run on the emulator
thread for every line. A path is only a link if it names a file that exists, and that check would
then run for every token of every line as output streams past.

## Decision

Find links when the pointer hovers (`com.termina.link.LinkScanner`): once per hover, not once per
line. Links a program declares with OSC 8 still go through JediTerm, with `ExplicitLink.FILTER`
installed before the emulator starts reading.

## Consequences

- Nothing is underlined until it is hovered.
- The existence check is off the output path entirely.
