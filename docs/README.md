# Contributor documentation

This folder is for people working **on** Termina. What a user reads — getting started, features,
keybindings, settings — lives on the website, [termina-project.dev/docs](https://termina-project.dev/docs/),
from the [termina-website](https://github.com/adriandeleon/termina-website) repository.

**When these pages and the code disagree, the code wins**, and the page is fixed in the same change.
[`AGENTS.md`](../AGENTS.md) is the short operational guide; [`CONTRIBUTING.md`](../CONTRIBUTING.md)
the short version for a person.

## Guides

- [architecture.md](architecture.md) — the three layers (PTY, JediTerm, rendering), shells and
  profiles, themes, settings sync, and which class owns what.
- [conventions.md](conventions.md) — the rules a change follows: performance, extracting decisions,
  live settings, null annotations, and where documentation goes.
- [testing.md](testing.md) — running and writing tests, coverage, the `DevCapture` hook and every
  `-Dtermina.capture*` option, and diagnosing a freeze.
- [gotchas.md](gotchas.md) — the traps that cost real time and announce nothing.
- [building-and-packaging.md](building-and-packaging.md) — commands, formatting, app images,
  installers and the icon.
- [release.md](release.md) — cutting a release, package managers, macOS and Linux signing.
- [decisions/](decisions/README.md) — why JediTerm, why pty4j, why a scene filter for keys, and the
  other choices a newcomer would question.

## Reference and history

- [pauta-audit-2026-11-06.md](pauta-audit-2026-11-06.md) — Termina against the Pauta 2.0.2
  guidelines: what is met, what is not, and where the two disagree.
- [jediterm-capabilities.md](jediterm-capabilities.md) — what JediTerm already handles and what each
  missing capability would cost.
- [competitive-landscape.md](competitive-landscape.md) — how other terminals compare.
- Reviews: [2026-10-20](review-2026-10-20.md), [2026-10-27](review-2026-10-27.md),
  [2026-11-06](review-2026-11-06.md) — findings, and what was done about each.
