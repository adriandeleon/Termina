# Termina agent guide

A cross-platform terminal emulator. JDK 25 + JavaFX 27 + Maven, modular (JPMS, module
`com.termina`).

This file is the operational guide for coding agents: the commands, how to work, and the traps worth
knowing before touching anything. How Termina works and why is in [`docs/`](docs/README.md); keep
this file short, and never a changelog. When documentation and code disagree, the code wins: verify
it, and fix the documentation in the same change.

## Pauta

This app is built to Pauta 2.0.2. Its rules: https://pauta-project.dev/2.0.2/llms.txt (every rule
in one file: https://pauta-project.dev/2.0.2/llms-full.txt). What it does not meet yet is in
`docs/pauta-audit-2026-11-06.md`.

- Before building a feature, read the rules on its page and copy from the code each rule's
  Reference names (Editora, usually checked out beside this repository).
- Cite a rule by its ID and edition: "KBD-07 (Pauta 2.0.2)".
- Read the numbered edition, never `latest/`. Moving to a newer edition is its own change:
  `pauta.version` in `pom.xml`, this file and the README badge change together, and `AppInfoTest`
  fails when one is left behind.
- Where Pauta and this file disagree, report the difference rather than picking a side.

## Essential commands

Run Maven from the project root.

- Run: `./mvnw javafx:run`
- Run with ad-hoc options: **`scripts/dev-run.sh -Dfoo=bar`** (`javafx:run` cannot take them).
- Test: `./mvnw test`; a few: `./mvnw test -Dtest='Foo*Test'`; without the JavaFX tests:
  `./mvnw test -DexcludedGroups=fx`.
- Format: `./mvnw spotless:apply`
- Everything CI checks: `./mvnw verify` (tests, Spotless, NullAway, coverage floors).
- Native app image: **`./mvnw clean -Pdist -DskipTests package`** ⇒ `target/dist/Termina{.app}`.
  `clean` is **required**: jlink's input is built from `target/classes`, and an incremental compile
  can leave that inconsistent in ways javac will not correct.

More in [building and packaging](docs/building-and-packaging.md) and [testing](docs/testing.md).

## Worktrees — one per task

Several sessions may work on this repository at once. Every task gets its own git worktree, so
branch switches and commits cannot interfere.

- Sessions in the Claude desktop app get one under `.claude/worktrees/`. Otherwise:
  `git worktree add ../Termina-worktrees/<slug> -b <branch> origin/master`.
- Leave the main checkout's branch and its uncommitted changes alone; they may be another task's.
  Work in the worktree, by explicit path if the shell starts elsewhere.
- Worktrees share objects, branches and the stash, not working directories. Never remove another
  task's worktree, and never a bare `git stash`/`git stash pop`.

## Change checklist

- Read the nearby code and its tests first, and follow their patterns.
- **A user action is a command**: a `MenuAction` with an id in `TerminalWindow.table`, titled by
  `menu.<id>` with a `menu.<id>.desc`, in all six `messages*.properties`. Its default chord, if it
  has one, goes in `src/main/resources/com/termina/keymaps/keymap.json` and `keymap.mac.json`, not
  in the code (`KeymapTest` checks both). Anything that shows a chord asks `chordOf(id)`, never the
  action's default.
- **Application chords are `Ctrl+Shift+key` off macOS**, never plain `Ctrl+key`, which is the
  shell's.
- **Nothing blocks the JavaFX thread** — no file or network I/O, no process start or wait, no
  sleep — and per-write or per-keystroke work is coalesced: one `AnimationTimer` repaints per frame.
- **Extract the decision, then test it**: a pure method or class with unit tests. A test that starts
  the toolkit carries `@Tag("fx")`.
- **Settings apply live**, are clamped on read, and a setting that cannot apply live says so on its
  row. New strings go into all six catalogues.
- **A new package** starts with a `package-info.java` marked `@NullMarked`; fix what NullAway
  reports rather than suppressing it ([null annotations](docs/conventions.md#null-annotations)).
- Check `module-info.java` when a dependency, reflection or a service crosses JPMS.
- **See it work** before calling a UI change done: drive the real window with `DevCapture`,
  headless where it can be ([testing](docs/testing.md#development-capture)).
- `./mvnw spotless:apply`, then the tests for what you touched; CI runs the whole suite.
- **Docs in the same change**: `CHANGELOG.md` under `## [Unreleased]` (Keep a Changelog, each entry
  opening with a bold one-line summary) for anything a user can see; the website for user docs; the
  guide in `docs/` that covers it; a [decision record](docs/decisions/README.md) for a choice a
  newcomer would question. `README.md` only when install, build, command-line or config facts change.
- Cite a Pauta rule by its ID and edition when a change is made for one.
- Commit no generated output, credentials or unrelated changes.

## Traps

Each of these cost real time and announces nothing. One line each here; what goes wrong, why, and
what the code does about it is in [`docs/gotchas.md`](docs/gotchas.md) — read the entry before
touching its area.

- `module-info` requires `kotlin.stdlib` and `org.slf4j` explicitly.
- AWT is forced headless in `App.main`, as the first statement.
- Special keys go through JediTerm's encoder
- Application chords are `Ctrl+Shift+key` off macOS, never `Ctrl+key`.
- Key bindings are a scene-level filter, not menu accelerators.
- Tab headers are the skin's, and rebuilt.
- Nothing in the chrome may be focus-traversable.
- Driving input in a capture run mostly goes *around* focus.
- The shell will not tell you where it is, and often will not tell you anything.
- The deb and rpm bundlers throw your icon away.
- The deb's dependencies are the build host's package names for whatever is in the image.
- GNOME Software has two pages for a package, and only one reads the listing.
- Full-screen geometry cannot be diagnosed from inside the app on Wayland.
- Closing asks; shutting down does not.
- Tab disposal is driven off the tab list, not the close button.
- The macOS system menu bar still occupies layout.
- One `CustomMenuItem` costs the whole screen menu bar.
- JediTerm's text-scanning hyperlink layer is deliberately unused; its OSC 8 path is used.
- Closing a stage takes nothing out of the tab list.
- A pane moves by leaving its group before its tab goes.
- A pane's launch options go in before its settings.
- The drag anchor is buffer state too.
- A `.terminal` profile is two property lists deep.
- `gdbus call` parses `-1` as an option.
- Asking for a row above the scrollback is an ERROR line, not an exception.
- Hiding the tab strip needs min, pref *and* max height at zero.
- jlink silently omits ServiceLoader-only modules.
- pty4j's natives survive packaging by accident.
- `wsl.exe --list --quiet` writes UTF-16LE.
- A `StackPane` resizes a child up to its *maximum*, and an `HBox` reports an unbounded one.
- A `Labeled` ignores a graphic that is unmanaged when it is set, for good.
- JavaFX has no structural pseudo-classes.
- An SVG path JavaFX cannot read draws nothing and says nothing.
- The menu bar can live in the title bar, and three things about that are invisible.
- `scripts/dev-run.sh` refreshes `target/deps` from empty.
- Double-width characters occupy two cells.
- JediTerm's cursor x is a slot, not a column.
- Every pixel-to-cell sum goes through the padding.
- `Font.getFamilies()` is a snapshot.
- JavaFX's fallback list is not a Nerd Font lookup.
- The command palette is a port of Editora's, and the pure parts are verbatim.
- Every binding has two lists: the defaults and the chords in force.
- `KeyCombination.equals` compares modifiers as written.
- A `ListView` asked to scroll before it has a size scrolls to the wrong place.
- A control created in this pulse has no skin, and a skinless control is half a control.
- Operating-system commands are intercepted where the emulator reads, not where the PTY delivers.
- Things positioned in the buffer ride on `linesScrolled`, not on per-change shifting.
- A text field inside the terminal still passes through the terminal's key filters.
- A `SplitPane` item's parent is not the `SplitPane`.
- JediTerm ships the type-ahead predictor without the model it edits.
- JediTerm reads any `CSI … u` as SCORC, restore cursor, and ignores the private marker.
- What the emulator reads is a chain, built in one place.
- Keys fired at a program right after it starts can race its startup.
- An `--add-opens` for a package only one platform has warns on every launch elsewhere.
- The close prompt is modal, and a capture run that reaches it hangs.

## Where things are

- [`docs/architecture.md`](docs/architecture.md) — PTY (pty4j) → emulation (JediTerm) → rendering
  and input (`com.termina.ui`); shells and profiles, themes, settings sync; `TerminalSession`,
  `TerminalWindow`, `PaneGroup`, `TerminalView`, `WindowManager`.
- [`docs/conventions.md`](docs/conventions.md) — performance, decisions, live settings, null
  annotations, documentation.
- [`docs/testing.md`](docs/testing.md) — tests, coverage, every `-Dtermina.capture*` option.
- [`docs/release.md`](docs/release.md) — releasing, package managers, signing. Read it, and
  [`packaging/linux/NOTES.md`](packaging/linux/NOTES.md), before touching a workflow or an installer.
- [`docs/decisions/`](docs/decisions/README.md) — why things are the way they are.

## Not done

Windows and Linux are **unrun** — including `-Pdist`. The Windows MSI is unsigned; the macOS dmgs
are notarized and the Linux downloads OpenPGP-signed. Key bindings have no multi-key sequences; no images or ligatures. See `CHANGELOG.md` for the
current list.

**Windows' directory read (`pty.WindowsCwd`) has never run on Windows**, nor has the PowerShell
toast; the PEB walk is tested against a made-up address space, and the offsets are the ones every
process explorer uses, but the system calls around it are claims. The same for the macOS
notification and Option+drag blocks.

**The shell profiles' Windows half has never run on Windows.** Every branch of it is driven in
`ShellDiscoveryTest` with the filesystem and `wsl.exe` probes replaced, and the macOS half is
device-tested, but "PowerShell 7 is found at that path" and "`wsl --list` answers in that shape" are
claims about a machine rather than about the code. Check it with
`-Dtermina.captureProfiles=true`, which reports every entry and the command it runs.
