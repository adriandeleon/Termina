# Conventions

The rules a change follows. [`CONTRIBUTING.md`](../CONTRIBUTING.md) is the short version; the change
checklist in [`AGENTS.md`](../AGENTS.md) is the one agents work through.

## Code

- **Performance.** The emulator signals a change per write. Repaints are decoupled from that: a
  change sets a dirty flag and one `AnimationTimer` repaints per frame. Drawing is per style run,
  not per character. Do not add per-keystroke or per-pulse work that is not coalesced.
- **Extract the decision, then test it.** Anything with a rule in it — which chord, who owns
  right-click, when the tab bar shows, whether a colour is legible — becomes a static method with
  its own tests, in a class that imports no JavaFX type, tested without the toolkit (QUAL-05,
  Pauta 2.0.2): `WindowRules` (`shouldShowTabBar`, `usesHeaderBar`, …), `MouseEncoding`,
  `PaletteKeys`, `SettingsSearch`. The JavaFX half that reads the event and applies the answer sits
  beside it (`PaletteNav`). `PureDecisionsTest` keeps the list honest.
- **Verify by driving it.** Terminal bugs are visual and none of them throw. `DevCapture` renders
  the real window, types into the real shell, fires real mouse and key events, and writes a PNG.
  Reasoning about JavaFX dispatch order is not evidence — fire the chord. See [testing.md](testing.md#development-capture)
  for the full set of `-Dtermina.capture*` options.
- **A settings change applies live.** Every control writes through immediately; there is no OK
  button. Two settings cannot (scrollback sizes an existing buffer, the shell is already running)
  and each says so on its own row rather than appearing to work. Live means memory and the change
  broadcast; the *file* is written 300 ms after the last change in a run, on a daemon thread
  (`Settings.deferred`), and `WindowManager.closeAll` flushes what is pending. A test that wants
  the file current calls `flush()` or uses the plain constructor, which writes at once.
- **Settings are clamped on read as well as write.** The file is meant to be hand-edited; clamping
  only on write would let a two-million-line scrollback exhaust memory at the next launch.
- **Ported themes are faithful, not improved.** Clear Dark/Light colours were decoded from Apple's
  own `.terminal` profiles. Clear Light is genuinely low-contrast in four places; that is pinned in
  `ThemeTest` rather than corrected, because a port that "fixes" its source is no longer that theme.

## Null annotations

JSpecify annotations, checked by [NullAway](https://github.com/uber/NullAway) (an Error Prone
plugin) in the main compile. The setup is Editora's, so the rules below are the same in both projects.
`mvn compile` fails on any null that can reach a type not marked nullable.

- **Every package is `@NullMarked`, and a new one starts that way.** Give it a `package-info.java`:
  `@NullMarked package com.termina.example;` with `import org.jspecify.annotations.NullMarked;`.
  `NullMarkedPackagesTest` fails for a package without one, and fails if NullAway leaves the pom.
- **In a marked package everything is non-null unless it says `@Nullable`.** Mark what really may be
  null: a field set later, a parameter callers pass null to, a "not found" return. The annotations are
  type-use, so they go next to the type: `private @Nullable Foo foo`, `java.nio.file.@Nullable Path`,
  `Settings.@Nullable ProfileStyle`, `byte @Nullable []` (the array may be null),
  `List<@Nullable String>` (its elements may be).
- **Prefer a shape that cannot be null over `@Nullable`** when the null is an accident of the code,
  not part of its meaning. For state that must exist by the time it is used, write an accessor that
  fails with a clear message: `TerminalView.getSession()`/`buffer()` (null until `start`), with
  `isStarted()` for the two callers that ask. Make lazily built things through one method that
  builds them (`WindowManager.settingsWindow()`, `TerminalWindow.shortcutsWindow()`). Things built
  together go in one record behind one nullable field (`TerminalView.ContextItems`), not six.
- **NullAway does not credit an assignment it cannot see as initialization.** A field assigned inside
  another call's argument list, or by a builder reached through a loop and a switch, counts as
  unassigned. `TerminalWindow`'s palette and prompt actions are `final` fields with initializers for
  that reason; `buildMenuBar` only registers them. `SettingsWindow`, whose controls are all made by
  `build()`, suppresses only the initialization check on its constructor
  (`@SuppressWarnings("NullAway.Init")`). Reads of those fields are still checked.
- **NullAway does not follow a boolean that holds a null check.** After `boolean present = style !=
  null; if (!present) return;`, `style` still counts as nullable. Write the check inline. The same goes
  for calling a getter twice: `if (x.y() != null) x.y().z()` is flagged, so read it into a local.
- **JavaFX returns null more often than it looks:** `Node.getScene()`, `Scene.getWindow()`,
  `Node.getParent()`, a selection model's selected item, a `ComboBox` value.
- **Watch for over-wide annotations.** When a caller has a null for a parameter that does not take
  one, make the parameter `@Nullable` only if the method already copes with null. NullAway then
  checks that it does. Otherwise check at the caller. Widening one method after another until the
  error moves out of sight is how a whole API turns `@Nullable`.
- **Native bindings are `@NullUnmarked`.** A JNA `Library` interface follows the C API's nullness
  (`ProcessCwd`, `WindowsCwd`, `ChildProcesses`).
- **Suppress rarely**, on the narrowest element, with a comment saying why.
- **Plumbing.** Only NullAway runs (`-XepDisableAllChecks -Xep:NullAway:ERROR`), and only in the main
  compile, not on tests. `.mvn/jvm.config` exports the javac internals that Error Prone needs. JSpecify
  is a `provided` dependency read through `requires static org.jspecify`, so nothing ships.

## Documentation

- **Docs change in the same pull request as the behaviour.** A change a user can see updates
  `CHANGELOG.md` (under `## [Unreleased]`, in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
  form, each entry opening with a bold one-line summary) and the user docs in the
  [website repository](https://github.com/adriandeleon/termina-website). A change to how Termina is
  built, tested or put together updates the guide here that covers it. Never as a follow-up: a
  feature pull request with no docs change is not finished.
- **When the docs and the code disagree, the code wins**, and the docs are fixed in the same change.
- **Where a thing goes.** Commands and the checklist an agent works through: `AGENTS.md`, kept short.
  How to start contributing: `CONTRIBUTING.md`. How Termina works and why: a guide in `docs/`. A
  choice a newcomer would question: a decision record in [`decisions/`](decisions/README.md).
  Everything a user reads: the website, not this repository.
- **`README.md` is the landing page.** Change it when what it says about installing, building, the
  command line or the config folder changes.
