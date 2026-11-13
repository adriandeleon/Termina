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

**Export and Import Configuration are `config.ConfigArchive`, after Editora's.** An export zips the
config folder less what belongs to the run (locks, logs, the socket, `sync/`, `backups/`). An import
is scanned before anything is written, backed up to `<config>/backups/`, and applied by part: theme
files are replaced in `themes/`, and the settings parts are merged key by key
(`ConfigArchive.merge`) into the live `Settings` through `replaceStored`, never by writing over
`settings.json`, so memory and file cannot disagree. `ui.ConfigTransfer` is the FX side: the
worker threads, the chooser and the parts dialog.

**Commands are by id, in `ui.CommandRegistry`.** Each window registers every command with its
action, its label now and its gate now (`TerminalWindow.currentLabel`, `unavailableNow`). Menu items,
the palette and the key bindings all run and name a command through it, and a menu refreshes its
items from it as it opens (MENU-01, Pauta 2.0.2). Which menu lists which ids, in what order, is
`ui.MenuBarModel`, a pure list the bar is assembled from: command ids, rules, and one submenu that
groups the nine numbered profile commands. Nothing in the bar has a title or a handler of its own.
The right-click menu runs the window's commands by id too (`TerminalView.setCommandRunner`). Every
row of every menu wears its command's glyph from one table, `ui.MenuIcons.forCommand` (MENU-14,
Pauta 6.0.1); a new command that gets a menu row needs an entry there, and `MenuIconsTest` and
`NamedControlsFxTest` fail without one.

`com.termina.term.TerminalSession` wires them together and owns the read loop.
`com.termina.ui.TerminalWindow` is one window (menu bar over a tab strip); each tab is a `PaneGroup`
of one or more `TerminalView`s. `WindowManager` owns the window set and what they share — settings,
the preferences window, the theme, the update check.
