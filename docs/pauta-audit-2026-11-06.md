# Termina against Pauta 2.0.2 — audit, 2026-11-06

Pauta's audit procedure (coding-agents page): find the edition the app names; "if the app names none,
use the newest". Termina names none — no `pauta.version` in `pom.xml`, nothing in the README, About
or AGENTS.md — so this is against **2.0.2** (352 rules). Every rule was checked against the code,
build, workflows and docs on `master` (the uncommitted StaticFX spike was ignored), not taken from
the site's gaps page. Evidence is `file:line` as of this date.

## Since this audit

Addressed on 2026-11-07, branch `pauta-quick-wins`:

- **Now met:**
  - ABOUT-04, ABOUT-05, ABOUT-06, ABOUT-08.
  - TABS-06, REL-22, REL-23, REL-26, REL-27.
  - INTL-08, ICON-02, ICON-10, ICON-05.
  - The coding-agents page's `## Pauta` section and edition number.
- **Narrowed:**
  - ABOUT-02 / WIN-07: About lacks only the commit now.
  - WIN-05 / CONF-01 / CONF-03: every Settings removal asks, through `ui.Confirm`, with a danger-styled
    button. The tab-close and paste prompts in `TerminalWindow` are still hand-built.
  - ICON-09: `-color-warning-4` text in the Keys page remains.
  - DOCS-09 / KBD-03 / QUAL-12: every command now has a `.desc`, and `MessagesTest` fails on one
    without. The right-click-only items are listed there as not yet commands (KBD-01).
  - ICON-21 / TBAR-01 / KBD-17: icon-only buttons are named through `ui.IconNames`. No test stops a
    new one bypassing it.
  - REPO-04: the MIT line is in NOTICE. The NOTICE tests are still missing.

Addressed on 2026-11-07, branch `pauta-build-gates`:

- **Now met:**
  - PLAT-01: the enforcer requires JDK 25+ and Maven 3.9.
  - QUAL-06: FX tests are tagged, and `FxTestTagsTest` enforces it.
  - REL-07: `scripts/check-release-assets.sh` checks the downloads against `jreleaser.yml`.
  - REL-18: the checksums file is attested, and the attested list is checked to be JReleaser's.
  - REL-19: CI has a separate Format job. The required status checks are a repository setting.
  - REL-21: a manual run is a JReleaser `--dry-run`, and the snapshot bump job runs after a release.
  - REL-06: master moves to the next snapshot through that bump job.
  - DIAG-04: the handler is installed before argument parsing.
  - ABOUT-02: the commit is in About and `--version`.
  - QUAL-15: `WorkflowRulesTest` keeps the pins, timeouts, permissions, asset check and
    attestations in place.
- **Narrowed:**
  - PLAT-11: Headless glass is set in surefire. There is still no TestFX.

Addressed on 2026-11-07, branch `pauta-repo-docs`:

- **Now met:**
  - REPO-02: CHANGELOG is in Keep a Changelog 1.1 form, every entry opens with a bold summary, and
    "Known gaps" became a note on its version.
  - REPO-05: AGENTS.md has commands, worktrees and a change checklist, down from 455 lines to 167.
  - REPO-06: CONTRIBUTING.md.
  - REPO-07: `docs/` has architecture, conventions, testing, gotchas, release,
    building-and-packaging and `decisions/`.
  - REPO-09: `bug_report.yml` with `environment` and `report`.
  - DOCS-12: `docs/README.md`.
  - DOCS-13: seven decision records.
  - DOCS-16, DOCS-17: both rules are stated in `docs/conventions.md` and AGENTS.md.
  - `RepositoryDocsTest` keeps this shape.
- **Narrowed:**
  - DOCS-14: AGENTS.md is short and points into `docs/`. The long catalogue is `docs/gotchas.md`,
    as in Editora, rather than `docs/reference/`.
  - REPO-01 / DOCS-19: the README lost its contributor sections and gained a License section. At
    804 lines, the feature catalogue that belongs on the website is still in it.

Everything else below stands.

## Totals

MUSTs: **59 fail, 34 are partly met**, and 2 (DOCS-01/02, the website's docs) cannot be judged
from this repository. Under Pauta's rule, any failed MUST blocks a release, so
Termina cannot claim even 2.0.2 compliance yet. Most of the rest pass or are N/A: there is no tray,
no tool panels, no toolbar, no Welcome page, no quick-open picker, and no documents.

## The site's gaps page against this audit

The gaps page lists ten items for Termina. All ten are confirmed:

| Gaps-page item | Rule | Confirmed |
|---|---|---|
| Help → Documentation | DOCS-06 | Help is Shortcuts · About · Check for Updates (`ui/TerminalWindow.java:2897`) |
| `docs/README.md` | DOCS-12 | absent |
| Message Log | DIAG-01, WIN-01 | "Termina has no status bar" (`ui/TerminalWindow.java:2899, 3025`) |
| Report a Problem | DIAG-06, CLI-05 | no report, redaction or `--report` |
| single instance | CLI-07 | no endpoint; each launch is its own process |
| `bug_report.yml` | REPO-09 | no `.github/ISSUE_TEMPLATE/` |
| `CONTRIBUTING.md` | REPO-06 | absent |
| full coverage gate | PLAT-12 | only PACKAGE LINE floors; no BUNDLE 0.90 LINE/METHOD (`pom.xml:274-415`) |
| Windows signing | REL-17 | MSI unsigned. This rule is a **SHOULD**, yet the page lists it under "Missing MUSTs" |
| Pauta edition in About | ABOUT-05..08 | no `pauta.version` anywhere |

**The page is far from complete**: it lists 10 items, but 59 MUSTs fail outright. It also misses
things it does list for sibling apps. Torrenta's row has "Keep-a-Changelog format", which Termina
also fails.

## Failed MUSTs (59)

### Repository and docs
- **REPO-01 / DOCS-19** — `README.md` is 1086 lines with a feature catalogue (`:74-667`), against
  "about 200 lines" that defer to the website. It has no License section.
- **REPO-02** — `CHANGELOG.md:3` names SemVer but not Keep a Changelog. `## Unreleased` has no
  brackets, there are extra sections (`### Internal`, `### Known gaps`, `### Maintenance`), and 44 of
  272 entries do not open with `- **`.
- **REPO-06** — no `CONTRIBUTING.md`.
- **REPO-07** — `docs/` has none of `architecture.md`, `conventions.md`, `testing.md`, `gotchas.md`,
  `release.md`, `building-and-packaging.md` or `decisions/`.
- **REPO-09** — no `.github/ISSUE_TEMPLATE/bug_report.yml` with `environment` and `report` ids.
- **DOCS-05** — release notes come from conventional commits (`jreleaser.yml:53-61`,
  `.github/release-notes.tpl`), not from the CHANGELOG section. The app has no What's New.
- **DOCS-06** — no `help.documentation` command and no docs URL in `src/main`.
- **DOCS-09 / KBD-03** — `.desc` is optional (`ui/TerminalWindow.java:2934`). Missing for
  `commandPalette`, `goToTab`, `goToLastTab` and `newTabProfileNumber`, among others. No test fails on
  a missing one.
- **DOCS-12** — no `docs/README.md`.
- **DOCS-14** — the three-file split is missing: no CONTRIBUTING, no `docs/reference/`, and AGENTS.md
  (455 lines) carries the long gotcha catalogue without linking into `docs/`.
- **DOCS-16 / DOCS-17** — nothing states "docs change in the same PR" or "the code wins".

### Build and quality gates
- **PLAT-05 / WIN-02 (half)** — no `atlantafx-base` dependency. The Editora themes are vendored CSS
  (NOTICE:71-73).
- **PLAT-12** — no build-wide 0.90 line and method floors (see the gaps table).
- **QUAL-06** — no `@Tag("fx")` anywhere. Five test classes call `Platform.startup`, so
  `-DexcludedGroups=fx` cannot run the pure suite alone.

### Performance
- **PERF-01** — the FX thread blocks on several paths:
  - Every setting change runs `Settings.save()` synchronously: a file lock, a re-read, a temp write
    and a move (`config/Settings.java:255`, called from setters at `:489, :595, :750, :870`). That
    includes zoom and every Settings control.
  - The PTY fork (`ui/TerminalView.java:561` → `pty/ShellLauncher.java:307`).
  - `/proc` reads for a new tab's directory and for the close prompt.
  - The scrollback regex search (`ui/TerminalView.java:3364-3399`).
  - `Files.exists` on hover (`:1766`).
  - Debug-log file writes on the logging thread (`ui/DebugLog.java:151`).
  - Theme removal (`ui/Themes.java:191`).
- **PERF-15** — all six font families, shell discovery (`wsl.exe`/`reg.exe` on Windows) and the
  update check start before the first frame (`App.java:57, 93`; `ui/WindowManager.java:66, 270`).

### Keyboard, commands, menus
- **KBD-01 / TABS-01 / MENU-05** — Close Other Tabs and Close Tabs to the Right are tab-menu
  items with no id and no palette entry (`ui/TerminalWindow.java:1548-1550`). So are Open Link and
  Copy Link Address. Debug Log and Import/Remove Theme are reachable only from Settings. Close All,
  Close to the Left and Pin do not exist. Menu rows run their own `Runnable`, not an id.
- **TABS-03 / TABS-04 / TABS-05** — no pinned tabs, so there are no pinned headers, no pin-aware
  bulk close and no pin boundary. These all follow from Pin being absent.
- **KBD-09** — no Emacs/CUA JSON keymaps or `.mac` variants. There is one code-defined table plus
  `keys.*` overrides.
- **KBD-17 / ICON-21 / TBAR-01** — icon-only buttons (`+`, the profile chevron, tab close, the find
  bar arrows and close, the zoom row) have tooltips but **no accessible text**. `setAccessibleText`
  appears only in `ui/SettingsWindow.java:609, 1647`.
- **MENU-02** — `setAccelerator` on menu items (`ui/MenuAction.java:230`). This is deliberate (see
  the conflicts below).
- **MENU-07** — a press inside the terminal that dismisses its context menu is consumed
  (`ui/TerminalView.java:2349-2353`).

### Window, theme, colour
- **WIN-01 / DIAG-01** — no status bar, no echo area and no Message Log. Messages are modal alerts
  (`ui/TerminalWindow.java:3025-3045`).
- **WIN-02 / ICON-16** — no "follow the system" theme. There is no system value in
  `Settings.themeId()`, and nothing listens to `Platform.getPreferences().colorSchemeProperty()`.
- **WIN-05 / CONF-01** — Remove Theme deletes the file without asking, and there is no undo
  (`ui/SettingsWindow.java:801-813`). Remove Profile (`:1821`) and Remove Credentials (`:2021`) also
  act without asking.
- **WIN-07 / ABOUT-02** — About has no Pauta edition, no commit and no Java/JavaFX versions.
  `--version` prints different text.
- **ICON-07** — no `-state-*` tokens. State colours are used raw (`app.css:80, 527`) and set inline
  in `SettingsWindow.java:642, 1427, 1882, 1958`.
- **ICON-09** — readable text in `-color-fg-subtle`: About credits and path (`app.css:78-79`), and
  Settings group headers (`settings.css:19`), at about 3.2:1. Warning text in `-color-warning-4` is
  2.97:1.
- **ICON-10** — the light theme's tinted text is 3.8:1 on accent-subtle. Cause: Termina's
  `editora-light.css` is a **stale copy**. Lines 78, 82 and 86 still have the accent, warning and
  success colours Editora darkened for contrast. `editora-dark.css` is identical to Editora's.
- **ICON-12** — no `-focus-ring` and no `:focus-visible` block.
- **ICON-20** — New Tab is a bare `+` on the strip (`app.css:216`) but a tab-with-plus glyph in the
  menus (`MenuIcons.newTab`).

### Settings, diagnostics, command line
- **SET-06** — most settings have no palette command. Only theme, zoom and the menu-bar toggle do.
- **SET-09 / SET-10** — `settings.properties` has no schema version and no migrations, and nothing
  detects a newer file to back up as `.v<n>.bak`. It is partly mitigated: save merges over a
  re-read, so unknown keys survive.
- **SET-12** — no Export or Import of the configuration.
- **DIAG-06 / CLI-05** — no Report a Problem and no `--report`.
- **CLI-07** — no single-instance hand-off.

### About and the Pauta edition
- **ABOUT-05..08** — no `pauta.version` in `pom.xml` or `build-info.properties`, no "Built to Pauta"
  link, no `Pauta:` line, no README badge. The coding-agents page adds two more: AGENTS.md needs a
  `## Pauta` section naming the edition with its `llms.txt` and `llms-full.txt` addresses, and that
  same number must appear in `pom.xml`, `AGENTS.md`, the README and About.

### Release workflows
- **REL-22** — every `uses:` is a tag (`@v4`, `@v5`). The only SHA pin, `release.yml:464`, has the
  comment `# 2.5.0`, not `# v2.5.0`.
- **REL-23** — no `.github/dependabot.yml`.
- **REL-26** — no job sets `timeout-minutes` (only one step does, at `release.yml:187`).
  `ci.yml:23` has `cancel-in-progress: true` for every event, master included, and `release.yml` has
  no concurrency.
- **REL-27** — surefire reports upload only `if: failure()` (`ci.yml:52`). There is no JaCoCo upload
  and no coverage line in `$GITHUB_STEP_SUMMARY`.

## Partly met MUSTs (34)

- PLAT-01: no enforcer `requireJavaVersion [25,)`.
- PLAT-11: no TestFX, and `glass.platform=Headless` is set per test, not in surefire.
- REPO-04: NOTICE has no "licensed under the MIT License" line, and no test checks NOTICE coverage
  or packaging. A shell check covers Linux only.
- REPO-05: CLAUDE.md is right, but AGENTS.md has no worktree-per-task section and no change
  checklist.
- QUAL-05: some decision helpers take JavaFX types (`PaletteKeys`, `MouseEncoding`).
- QUAL-12: no `.desc` parity check.
- PERF-03: no generation guard. Staleness is avoided only because the workers are FIFO or
  single-flight.
- PERF-11: `termina-session.log` grows without limit within a session.
- PERF-12: paste has no size threshold.
- PERF-13: release on window close is untested.
- PERF-14: the palette is built with the window, and nothing tests this rule.
- ICON-02: no `OFL.txt` beside the Inter faces.
- ICON-08: the hex grep is clean, but `rgba(0,0,0,…)` remains at `app.css:343, 355, 512`.
- TBAR-03: the find-bar and zoom-row tooltips leave out their chords.
- TABS-06: Reopen Closed Tab keeps 10 (`ui/ClosedTabs.java:33`), not 20.
- MENU-01: no registry that runs commands by id; enablement is patched in `onShowing`.
- MENU-06: one menu at a time relies on autoHide and is untested.
- MENU-09: no keyboard route to the tab menu.
- TRAY-09: a notifier failure is swallowed, not logged (`ui/DesktopNotifier.java:160`).
- SET-03: plain substring search, and section headings are not searchable.
- SET-07 / CLI-02: no `--dev` and no `~/.termina-dev`.
- DIAG-02: the Debug Log captures WARNING and above only, keeps no `.prev.log`, and JediTerm/pty4j
  log through slf4j-simple to stderr, bypassing it.
- DIAG-03: the Debug Log has no Export or Report button and no command; it opens only from Settings.
- DIAG-04: the handler is installed after argument parsing, and there is no echo message.
- DIAG-05: the update check has no delay after the first frame, and an update shows as a Help-menu
  item, not in a status bar.
- INTL-08: the parity test does not fail on an empty value. The catalogs are in parity today, with
  393 keys each.
- PAL-02: some commands never reach the palette, and nothing tests `.desc`.
- ABOUT-01: About is missing from the macOS application menu, and the id is `about`, not
  `help.about`.
- CONF-03: confirm buttons have no danger style.
- REL-15: no team-ID check, and the smoke launch runs before notarization, not after.
- REL-18: `checksums_sha256.txt` is not attested.
- REL-19: no separate Format job.
- REL-21: no release-asset check, no snapshot-bump job, and no JReleaser `--dry-run`.

## Where Pauta and Termina's own design disagree

Pauta's coding-agents page says: "the code is right until someone checks … report the difference
instead of picking a side". These failures follow from choices AGENTS.md records on purpose. Each
needs a decision: change Termina, or argue for a terminal-scope exemption in Pauta.

- **MENU-02 (accelerators)**: AGENTS.md says one `CustomMenuItem` costs the whole macOS screen menu
  bar, so the accelerators are kept display-only. Pauta's own macOS branch already writes the chord
  into the title with no icon, so Termina could follow it without a CustomMenuItem.
- **KBD-05/09 (M-x, Emacs/CUA keymaps)**: Alt+X and plain Ctrl chords belong to the shell, and the
  single keymap is a terminal-specific choice. Pauta already gives terminals their own chords for
  FIND-04 and MENU-08, but not here.
- **WIN-01 / DIAG-01 (status bar)**: Termina has none by design. Pauta contradicts itself here:
  MLOG-01 is scoped to "every app with a status bar", but WIN-01 makes a status bar a MUST.
- **TABS-01/03/04/05 (Pin)**: whether pinning is wanted for terminal tabs.
- **PLAT-05 (AtlantaFX)**: whether the vendored Editora CSS should become the dependency.
- **SET-09 (schema version)**: the file is `settings.properties`, kept hand-editable on purpose.
  PLAT-14 assumes JSON.

## Quick wins (small, mechanical)

1. Add `pauta.version` to `pom.xml` → `build-info.properties` → `AppInfo` → About (link and
   `Pauta:` line, plus the Java/JavaFX runtime). Add the AGENTS.md `## Pauta` section and the README
   badge.
2. About: stop saying "up to date" before a check has answered (ABOUT-04).
3. Workflows: pin to SHAs with `# vX.Y.Z`, add `timeout-minutes`, cancel only on PRs, upload reports
   `if: always()`, add a JaCoCo summary and add `dependabot.yml`.
4. Confirm before Remove Theme, Remove Profile and Remove Credentials.
5. Re-copy `editora-light.css` from Editora. That fixes most of ICON-10.
6. `ClosedTabs.MAX` = 20. Add `OFL.txt`. Add the MIT line to NOTICE.
7. Add the missing `.desc` keys, plus a parity test that fails on a command with no `.desc` and on an
   empty value.
8. Accessible text on icon-only buttons through one helper.
