# What JediTerm gives us

Read from the JediTerm **3.74** sources, not from its documentation. This is a dependency audit with
a shelf life — re-check it when `jediterm.version` in `pom.xml` moves.

The emulator decides which of the gaps in
[competitive-landscape.md](competitive-landscape.md) are ours to close and which are already closed.

## Already there

| | |
|---|---|
| VT100/VT220/xterm core | 23 DEC private modes, scroll regions, origin mode, reverse wraparound, G0–G3 charsets with SS2/SS3 |
| Colour | 24-bit RGB, 256-colour, 16 ANSI plus bright 90–107 |
| Alternate screen, bracketed paste | yes |
| Mouse | five modes across four wire formats (X10, xterm-ext, URXVT, SGR), plus focus reporting |
| Synchronised output (mode 2026) | **yes** — 500 ms timeout, 1 MiB buffer cap |
| OSC 8 hyperlinks | parsed, but dropped unless `setUrlHyperlinkFilter` is called — which we do not, so this is **not** wired up |
| Type-ahead prediction | `TerminalTypeAheadManager` — local echo to hide SSH latency |
| Double width | wcwidth tables, surrogate pairs counted correctly |

Two of those are worth calling out. **Synchronised output is done**, so it comes off the list of
things to copy from the GPU terminals. And **type-ahead prediction** is a feature none of the three
platform defaults has — it is what makes typing over a slow link feel local.

## Not there

The most consequential line in the dependency:

```java
private boolean deviceControlString(SystemCommandSequence args) {
  return false;
}
```

DCS is a stub. That single empty method is why there is no sixel, no ReGIS, no DECRQSS and no tmux
control mode. Everything DCS-based is unavailable until someone writes it.

**No images by any protocol.** No sixel (DCS), no kitty graphics (APC), no iTerm2 inline images —
the OSC dispatch handles 0, 1, 2, 7, 8, 10, 11, 104 and 1341, and returns false for everything else.

**OSC gaps, in rough order of what they cost us:**

- **133** — no shell integration. No prompt marks, so no jump-to-previous-command, no command status,
  no "select the last command's output". This underpins the best features in both iTerm2 and Windows
  Terminal.
- **7** — an explicit stub carrying the comment *"Support for OSC 7 is pending"*. No CWD tracking, so
  a new tab cannot open in the current directory once we have tabs.
- **52** — no clipboard access from a remote shell.
- **4** — colours can be queried (10, 11) but not set, so programs cannot theme the palette.
- **9 / 777** — no desktop notifications.

**No kitty keyboard protocol** (no CSI u).

**SGR is thinner than it appears.** The style model is exactly eight options: bold, italic, dim, slow
blink, rapid blink, inverse, underline, hidden. No strikethrough (SGR 9), no double underline (21),
no curly, dotted or dashed underlines (4:3–4:5), no underline colour (58, 59), no overline (53).
Strikethrough turns up in real output more than that list suggests.

**No grapheme clustering.** Surrogate pairs are measured correctly, but there is no ZWJ or
variation-selector handling, so family emoji and flag sequences break into separate cells. Windows
Terminal shipped grapheme clusters in 1.22, so this is where the field is going.

**No BiDi or RTL.** VTE's best feature, and hard enough that it should be treated as out of scope
rather than as backlog.

## Ligatures are our renderer, not JediTerm

Worth separating, because it is the one item the emulator has no opinion about. JediTerm hands us a
`TerminalTextBuffer` of styled cells; glyphs are entirely `TerminalView`.

VTE can never do ligatures because it lays out per cell against a width fixed at the measured ASCII
advance. We do not do that. Drawing is per *style run* — one `fillText` per run of identically-styled
text — which is exactly the shape that makes ligatures reachable, because the shaper sees `!=` as one
string rather than as two isolated cells.

The catch is the one that stopped VTE, arriving a step later: a ligated run's advance no longer
equals *n* × cell width, so the run drifts off the grid. That is the same failure the non-ASCII
per-glyph fallback already exists to prevent. So the work is not "enable ligatures", it is per-run
**cell snapping** — shape the run, then place glyph clusters on cell boundaries instead of letting
natural advances accumulate.

Before that goes on a roadmap, spike it: `GraphicsContext.fillText` exposes no OpenType feature
control, so whether `liga`/`calt` fire at all depends on the font and on JavaFX's default shaping.
Ten minutes with Fira Code answers it.

## What each gap costs

**Cheap, because we own the read loop.** `TerminalSession` sees bytes before JediTerm does, so
OSC 7, 52, 133 and 9 can be intercepted and handled there without touching the emulator — they are
notifications rather than screen mutations. **OSC 133 is the best value per line of code on this
list.**

**Expensive, because they mutate the screen model.** Images need the DCS stub filled *and* a
placement model that survives scrolling, reflow and scrollback eviction — either a JediTerm fork or a
parallel overlay keyed to buffer coordinates and shifted in step with the text. We already do that
shifting for selections, so the machinery is at least familiar. Kitty graphics is APC-based and needs
the same.

**Probably not worth it.** BiDi, and grapheme clustering unless emoji-heavy output matters.
