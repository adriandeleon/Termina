# Competitive landscape

Researched August 2026. What the three platform-default terminals do, where each one is stuck, and
which of those gaps are worth Termina's time. [The wider field](#the-wider-field) — the
alternatives people actually install on each platform, and a feature matrix with Termina in it —
was added in October 2026.

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
1.25 (stable in October 2026) added the kitty keyboard protocol, synchronised output, search across
the settings UI and an editor for actions.

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

## The wider field

October 2026. The three defaults are not what a dissatisfied user switches *to*, so this widens the
comparison to the alternatives on each platform and puts Termina in the same table.

| | Platform default | Feature-rich alternatives | Lean and fast |
|---|---|---|---|
| **Linux** | GNOME Terminal → Ptyxis (VTE); Konsole on KDE | Konsole, WezTerm, kitty, Ghostty | Alacritty, foot |
| **macOS** | Terminal.app | iTerm2, Ghostty, WezTerm, kitty, Warp | Alacritty |
| **Windows** | Windows Terminal | WezTerm, Tabby, Warp | Alacritty |

Of the serious ones, only WezTerm and Alacritty cover all three platforms the way Termina does.
kitty and Ghostty have no Windows build. Ghostty takes the opposite approach to ours, with a native
UI per platform (AppKit, GTK) around a shared core, where Termina draws the same JavaFX UI everywhere.

L/M/W = Linux, macOS, Windows. `~` is partial, explained in the notes below the table. Termina's
column is as of the split panes, search and shell integration work in late October 2026; the rows
it was missing before then are the first two items under "Where it is behind".

| | Termina | Win Term | iTerm2 | Term.app | Ptyxis | Konsole | kitty | WezTerm | Ghostty | Alacritty |
|---|---|---|---|---|---|---|---|---|---|---|
| Platforms | LMW | W | M | M | L | L | LM | LMW | LM | LMW |
| Tabs | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ |
| Split panes | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✗ |
| Scrollback search | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ~ | ✓ | ✓ | ✓ |
| Ligatures | ✗ | ✓ | ✓ | ✓ | ✗ | ✓ | ✓ | ✓ | ✓ | ✗ |
| Inline images | ✗ | sixel | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✗ |
| Settings window | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ |
| Plain-text config | ✓ | ✓ | ~ | ✗ | ✗ | ~ | ✓ | ✓ Lua | ✓ | ✓ |
| Command palette | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ |
| Scripting | ✗ | ✗ | ✓ | ✓ | ✗ | ✓ | ✓ | ✓ | ~ | ✗ |
| Multiplexing | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ |
| Prompt marks (OSC 133) | ✓ | ✓ | ✓ | ~ | ✗ | ✓ | ✓ | ✓ | ✓ | ✗ |

Notes on the partial and less obvious cells:

- **Search.** kitty's is the pager: the scrollback opens in `less` and you search there. Ghostty
  gained a search bar in 1.3 (March 2026).
- **Ligatures** follow the Fira Code project's terminal compatibility list. VTE is on its "doesn't
  work" side, so Ptyxis and GNOME Terminal are too.
- **Images.** Konsole does sixel and kitty graphics. Ghostty and kitty do kitty graphics. WezTerm
  does all three protocols, including iTerm2's.
- **Config.** iTerm2's plain text is Dynamic Profiles (JSON) beside a plist. Konsole keeps profiles
  as ini files. Ptyxis is gsettings, the same dconf problem as GNOME Terminal.
- **Command palette.** Konsole's is an open request on KDE's forum, not a shipped feature.
- **Scripting.** iTerm2 has Python and AppleScript, Terminal.app AppleScript, Konsole D-Bus, kitty
  remote control, WezTerm Lua. Ghostty's AppleScript arrived in 1.3 as a macOS-only preview.
- **Multiplexing** means tmux integration or a built-in multiplexer: iTerm2's `tmux -CC`, WezTerm's
  own mux server.
- **Prompt marks.** Terminal.app marks prompt lines itself and jumps between them with ⌘↑/⌘↓,
  without OSC 133. Konsole has handled OSC 133 since KDE Gear 22.08. Ghostty 1.3 uses it for
  click-to-move-cursor in prompts and command-finished notifications. Nothing in Ptyxis's 49.x
  release notes mentions it.

Outside the table: the **kitty keyboard protocol** is now in kitty, Ghostty, WezTerm (opt-in),
Alacritty and Windows Terminal 1.25, so VTE and Termina are increasingly the exceptions.

### Where Termina is ahead or level

- **Shell discovery on every platform.** Both PowerShells, Command Prompt, Git Bash and every WSL
  distribution on Windows, `/etc/shells` elsewhere. Only Windows Terminal matches it, and only on
  Windows. Ptyxis does the container equivalent (toolbox, distrobox, podman, APX).
- **A settings window over a text file**, applied live, the model Windows Terminal is judged by
  above. The GPU terminals have only the file, and Terminal.app and Ptyxis have only the window.
- **The working directory without the prompt's help.** Read from the OS, so it survives prompt
  frameworks that drop OSC 7. Most terminals depend on the prompt.
- **Paths are only links if the file exists.** Found under the pointer and checked, close to
  iTerm2's semantic history. Most others match URLs only.
- **Safety:** a paste with a line break asks first and is stripped of control characters, Escape
  never leaves full screen, and closing a tab with a running program asks.
- **Tab handling:** reopen closed tab, duplicate, rename, and a mark on a background tab for new
  output or a bell.
- **Icons without a Nerd Font installed**, which neither Alacritty nor VTE manages.
- **Native installers with a bundled runtime and no store**: DMG, MSI, deb and rpm.
- **Type-ahead over SSH**, which none of the platform defaults has: typed characters show at once on
  a slow link, dimmed until the echo confirms them.
- **Keyboard-driven:** a command palette with Emacs keys, and a searchable list of every shortcut
  generated from the binding table.

### Where it is behind

In rough order of how often a user notices:

1. ~~**Split panes and scrollback search.**~~ Done. The first is in everything serious except
   Alacritty and the GNOME family; every terminal in the table has some form of search.
2. ~~**OSC 133, 8 and 52.**~~ Done, along with OSC 7 where the OS cannot be asked. Notifications
   (OSC 9/777) remain, and JavaFX has nowhere to send them.
3. **Ligatures.** A renderer question, open to us, needing the spike described in the audit.
4. **Kitty keyboard protocol, grapheme clusters, underline styles.** Modern TUIs increasingly
   assume them.
5. **Images, scripting, multiplexing.** Expensive, and they matter to power users.
6. **Footprint and fidelity.** A bundled JVM starts and uses memory more heavily than a native
   terminal. Translucency is plain alpha, with no blur, so Apple's Clear themes render opaque.
   JediTerm's throughput has not been measured against VTE or the GPU terminals.

And maturity. Windows has not been run by a human, and none of the packages has been installed by
anyone, so on Windows everything in Termina's column is a claim about code. Against Windows
Terminal the case for Termina is one terminal on all three machines, not anything Windows-specific.

## Where this leaves Termina

Gaps in the incumbents that are durable rather than merely current:

1. **Split panes.** Nothing in the GNOME family has them and Ptyxis has ruled them out. Panes plus a
   text config file already beats every GNOME-family terminal. Konsole has panes, but configures
   them through a GUI and ini files.
2. **A text config file that a GUI writes.** dconf is the easiest of the three to beat. WezTerm's
   Lua is the high-water mark if we ever want configuration to be programmable.
3. **Ligatures and an image protocol.** VTE can never do the first and does not ship the second.
   Both are open to us, at different prices — see the JediTerm audit.
4. **One artefact, three platforms, no store.** Given what MSIX cost Microsoft, the `-Pdist`
   jlink/jpackage image is a real advantage: a self-contained bundle with no store, no package
   identity and no dependency chain to go missing on an offline machine.

Worth copying rather than inventing:

- **The kitty keyboard protocol.** Cheap, immediately visible to anyone running a modern TUI, absent
  from VTE, and since 1.25 in Windows Terminal too. (Synchronised output would belong here too, but JediTerm already implements it.)
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

For the wider field (October 2026):
[Windows Terminal 1.25](https://devblogs.microsoft.com/commandline/windows-terminal-preview-1-25-release/) ·
[Ghostty 1.3.0](https://ghostty.org/docs/install/release-notes/1-3-0),
[1.2.0](https://ghostty.org/docs/install/release-notes/1-2-0),
[AppleScript](https://ghostty.org/docs/features/applescript) ·
[kitty overview](https://sw.kovidgoyal.net/kitty/overview/) ·
[Konsole semantic shell integration](https://docs.kde.org/stable5/en/konsole/konsole/semantic-shell-integration.html),
[22.08 changelog](https://konsole.kde.org/changelogs/22.08.html),
[command palette request](https://discuss.kde.org/t/command-palette-support/35160) ·
[Konsole graphics protocols (ucs-detect)](https://ucs-detect.readthedocs.io/sw_results/Konsole.html) ·
[Fira Code terminal compatibility](https://github.com/tonsky/FiraCode#terminal-compatibility-list) ·
[Ptyxis 49 NEWS](https://download.gnome.org/sources/ptyxis/49/ptyxis-49.3.news)
