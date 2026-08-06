# Competitive landscape

Researched August 2026. What the three platform-default terminals do, where each one is stuck, and
which of those gaps are worth Termina's time.

For what our own emulator already handles — which decides whether a gap here is ours to close at all
— see [jediterm-capabilities.md](jediterm-capabilities.md).

The three are not really competing products. They are three answers to a different question:

- **iTerm2** is a power-user workstation — twenty years of accreted features, one author, macOS only.
- **GNOME Terminal** is a thin app over a widget (VTE). The app is close to feature-frozen; the
  engineering happens in the library, and the GNOME family has since split into three terminals.
- **Windows Terminal** is a platform repair. Much of its design follows from ConPTY and MSIX rather
  than from anything about terminal UX.

## At a glance

| | iTerm2 | GNOME Terminal (VTE) | Windows Terminal |
|---|---|---|---|
| Version | 3.6.11 · 3.7.0b6 | 3.58.1 · VTE 0.84.1 | 1.24.11911.0 |
| Language / licence | Objective-C, GPL-2.0 | C / C++, GPL-3 / LGPL-3 | C++/WinRT + XAML, MIT |
| Maintainer | one person | one person (app *and* VTE) | a Microsoft team |
| Split panes | yes | **no** | yes |
| Config | GUI + plist + profile JSON | **binary dconf database** | `settings.json` + GUI |
| GPU rendering | Metal | GTK4 GSK only (GTK3: none) | AtlasEngine |
| Ligatures | yes | **never** | yes |
| Images | sixel + iTerm2 inline | **none in practice** | sixel |
| Synchronised output | yes | no | yes |
| Scripting API | Python + AppleScript | none | none |
| Multiplexing | tmux control mode | none | none |
| BiDi / RTL | partial | **best anywhere** | partial |
| Accessibility | VoiceOver | **best on Linux** | UIA |
| Encrypted scrollback | no | **yes** | no |

## iTerm2

The feature ceiling for the whole category. tmux control mode (`-CC`) turns tmux windows into native
tabs and is genuinely unmatched. Triggers run actions when a regex matches output. Semantic history
makes a path in a stack trace clickable. There is a real Python API with a session and screen model,
per-pane status bar components, instant replay, a password manager, and an AI chat that reads
terminal output and annotates it in place. The 3.7 beta streams the terminal to an iPhone app as
video.

What people complain about is sprawl. The preferences window is the standing joke, and most users
touch a small fraction of it. Latency and memory drew criticism against Alacritty and kitty; the
Metal renderer narrowed that without closing it.

The part worth remembering is **CVE-2019-9535**: remote code execution through the tmux integration
parsing untrusted output. Every feature that interprets what a program prints is attack surface, and
iTerm2 has more of those than anyone. We inherit a parser we did not write, which puts us in the
same category whether or not we add such features.

## GNOME Terminal and VTE

Several widely repeated claims about VTE are wrong, and it is worth being precise because two of
them would otherwise look like easy wins.

**It is not slow.** In kitty's own throughput benchmark, VTE averaged 61.8 MB/s against Alacritty's
54.1 — it wins. Its actual weakness is CSI-heavy streams, 16.1 against kitty's 59.8. After the GNOME
46 overhaul (GSK render nodes, repaint driven by the frame clock instead of a 40 Hz timer that
predated reliable vblank information) hardware-instrumented latency testing put it on par with
Alacritty.

**Sixel is not supported, but not for the reason usually given.** The code landed in VTE 0.62 in
2020. The meson option has defaulted to `false` since the day it was introduced, there is a second
runtime gate on top of it, and no major distribution turns either on. On a stock desktop there is no
image support of any kind — no sixel, no kitty graphics, no iTerm2 inline images.

What VTE is genuinely good at is unglamorous and hard to copy. BiDi is a full Unicode Bidirectional
Algorithm implementation with box-drawing mirroring and directional arrow keys, the most complete in
any terminal. Accessibility through ATK is why blind Linux users have used GNOME Terminal for two
decades. Scrollback is disk-backed, LZ4-compressed and AES-256-GCM encrypted. Box-drawing, Powerline
and Braille glyphs are synthesised rather than taken from the font, so alignment is exact regardless
of what the user has installed.

The gaps are structural. No split panes — and not only here: neither GNOME Console nor Ptyxis has
them either, and Ptyxis has said it will not. No ligatures, ever, because cell width is the measured
average ASCII advance and text is laid out per cell. No synchronised output, which costs both
throughput and visible TUI flicker. No kitty keyboard protocol.

Configuration is a binary dconf database with profiles keyed by generated UUID. It cannot be
diffed, grepped or committed to a dotfiles repository, and changing one profile's palette from a
script means first discovering its UUID. An entire cottage industry of shell scripts exists to work
around this. The one thing it buys, rarely credited, is central administration: system-wide defaults
and locked mandatory keys, which a `~/.config/foo.toml` cannot offer.

And the strategy has not held. GNOME dropped Terminal from core in 2022 in favour of Console, which
essentially no distribution shipped as default. Distributions then standardised on Ptyxis, which is
not a GNOME core app at all. GNOME Terminal is still GTK3 in 2026. The failure was public enough to
change GNOME's governance, and the diagnosis is worth borrowing: *simple terminal*, *power terminal*
and *container terminal* turned out to be three products, and shipping one as though it were all
three did not work.

## Windows Terminal

It fixed a genuinely broken platform. ConPTY gave Windows a real pseudo-console for the first time;
AtlasEngine is fast; profile discovery for WSL, SSH, Azure and PowerShell is excellent. Recent
releases added sixel, grapheme clusters and a snippets pane (1.22), a rewritten windowing
architecture with proper quake-mode summoning (1.23), and an fzf-style fuzzy command palette (1.24).

Its config model is the one all three should be judged against: a JSON file that a settings GUI
writes, so the file stays authoritative and hand-editing keeps working.

The instructive failure is distribution. Built on MSIX, it took four years — 2019 to 2023 — to ship
a plain ZIP, and Microsoft explicitly declined to bless a community workaround in the interim. The
portable build still carries an official column of no: it cannot be set as the default terminal, has
no auto-update, no update check, no architecture selection, and broken `ms-appx:` icons. Every one
of those traces back to lacking MSIX package identity. Windows Server got no official installation
documentation at all and a "will not launch after installing" bug that stayed open for about three
years. Both blessed channels, Store and winget, are unavailable in precisely the locked-down
environments that most need them.

The lesson for us is not about MSIX. It is that a packaging decision produced four years of the
loudest issue traffic in the repository, and that the workaround shipped years late is still a
second-class citizen.

## Where this leaves Termina

Gaps in the incumbents that are durable rather than merely current:

1. **Split panes.** Nothing in the GNOME family has them and Ptyxis has ruled them out. Panes plus a
   text config file already beats every default Linux terminal.
2. **A text config file that a GUI writes.** dconf is the easiest of the three to beat. WezTerm's
   Lua is the high-water mark if we ever want configuration to be programmable.
3. **Ligatures and an image protocol.** VTE can never do the first and does not ship the second.
   Both are open to us, at different prices — see the JediTerm audit.
4. **One artefact, three platforms, no store.** Given what MSIX cost Microsoft, the `-Pdist`
   jlink/jpackage image is a real advantage: a self-contained bundle with no store, no package
   identity and no dependency chain to go missing on an offline machine.

Worth copying rather than inventing:

- **The kitty keyboard protocol.** Cheap, immediately visible to anyone running a modern TUI, absent
  from VTE. (Synchronised output would belong here too, but JediTerm already implements it.)
- **Encrypted disk-backed scrollback.** Only VTE does it, and it is a real difference rather than a
  checkbox.
- VTE's **termprop mechanism** (OSC 666 with a typed registry) is a well-designed extension point if
  we ever need one.

Two cautions. Anything that parses program output cleverly is attack surface — that is what
CVE-2019-9535 was. And decide which of the three products Termina is before the feature list decides
for us.

## Sources

iTerm2 [downloads](https://iterm2.com/downloads.html) ·
[3.7 changelog](https://iterm2.com/appcasts/testing_changes3.txt) ·
Windows Terminal [1.22](https://devblogs.microsoft.com/commandline/windows-terminal-preview-1-22-release/),
[1.23](https://devblogs.microsoft.com/commandline/windows-terminal-preview-1-23-release/),
[1.24](https://devblogs.microsoft.com/commandline/windows-terminal-preview-1-24-release/),
[distribution types](https://learn.microsoft.com/en-us/windows/terminal/distributions) ·
[VTE](https://gitlab.gnome.org/GNOME/vte) ·
[kitty benchmarks](https://sw.kovidgoyal.net/kitty/performance/) ·
[GNOME 46 latency measurements](https://bxt.rs/blog/just-how-much-faster-are-the-gnome-46-terminals/) ·
[arewesixelyet.com](https://www.arewesixelyet.com/) ·
[Ptyxis](https://gitlab.gnome.org/chergert/ptyxis)
