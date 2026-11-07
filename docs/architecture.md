# Architecture

How Termina is put together: which layers are its own and which are not, and where each part
lives. Traps the structure leads into are in [gotchas.md](gotchas.md).

Three layers, and **two of them are deliberately not ours**.

| Layer | What | Where |
|---|---|---|
| PTY | Starts the shell on a pseudo-terminal, moves bytes | `com.termina.pty` |
| Emulation | Parses escape sequences into a screen buffer | JediTerm (dependency) |
| Rendering & input | Paints the buffer, encodes keys and mouse | `com.termina.ui` |

**The emulator is JediTerm's.** xterm compatibility is a very long tail — cursor modes, character
sets, scroll regions, four mouse wire formats, a hundred CSI sequences — and getting it wrong
surfaces as programs subtly misbehaving rather than as anything that looks like a terminal bug.
`jediterm-core` is UI-agnostic: it owns a `TerminalTextBuffer` and knows nothing about drawing. It
is **not on Maven Central** — see the `<repositories>` block.

**The PTY is pty4j's.** Java has no PTY API and the platforms disagree fundamentally (`forkpty` vs
ConPTY). One jar carries macOS, seven Linux arches, and Windows ConPTY + winpty, so there is **no
per-OS native build step**.

**Which shell is a fourth thing, in `com.termina.shell`.** A `Profile` is a name, a command and
optionally a directory; `ShellDiscovery` enumerates what a machine has (both PowerShells, Command
Prompt, Git Bash and every WSL distribution on Windows; `/etc/shells` and the path elsewhere) and
`ShellProfiles` merges that with the system shell and the user's own list. Discovery's probes are
parameters rather than static calls, because the Windows branch is the one the feature exists for and
the one that cannot be run here.

**Importing a colour scheme is in `com.termina.theme`**: one reader per format, all to a
JavaFX-free `ColorScheme`, stored as `<config>/themes/*.properties` and listed with the built-ins by
`ui.Themes`. Anything that draws asks `Themes.byId`, never the `Theme` enum, which is only the four
built-ins.

**Settings sync is a fifth, in `com.termina.sync`, ported from Editora's.** It carries only the key
bindings, one file per platform family (`keybindings/macos.properties`,
`keybindings/windows-linux.properties`), through a private clone in `<config>/sync/repo` that git
never merges: the remote is checked out, a per-command three-way merge against
`refs/termina/base` is written over it, and that is pushed. `ui.SettingsSync` schedules runs and
writes results into the live `Settings`; one process per config folder syncs, by file lock. For an
`https://` repository, `SyncAccount` remembers which `gh` account can read it (in the clone's own
config) and hands git a credential helper that asks `gh auth token --user` for that one, so switching
the active account does not lock sync out.

`com.termina.term.TerminalSession` wires them together and owns the read loop.
`com.termina.ui.TerminalWindow` is one window (menu bar over a tab strip); each tab is a `PaneGroup`
of one or more `TerminalView`s. `WindowManager` owns the window set and what they share — settings,
the preferences window, the theme, the update check.
