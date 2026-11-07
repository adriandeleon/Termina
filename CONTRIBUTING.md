# Contributing to Termina

Thanks for working on Termina. This is the short version; the contributor guides are in
[`docs/`](docs/README.md), and what users read is on [the website](https://termina-project.dev/docs/).

## Setup

- **JDK 25** or newer. `./mvnw` brings the right Maven.
- Run the app: `./mvnw javafx:run`, or `scripts/dev-run.sh -Dfoo=bar` to pass options.
- Run the tests: `./mvnw test` (`-DexcludedGroups=fx` leaves out the ones that start JavaFX).
- Format: `./mvnw spotless:apply`. CI fails unformatted code.
- Everything CI checks: `./mvnw verify` — tests, formatting, null checking and the coverage floors.

Building app images and installers: [`docs/building-and-packaging.md`](docs/building-and-packaging.md).

## Making a change

1. **Branch from `master` in a worktree of its own**, so parallel work cannot collide:
   `git worktree add ../Termina-worktrees/my-change -b my-change origin/master`.
2. **Make the change** following [`docs/conventions.md`](docs/conventions.md). The ones that get pull
   requests sent back: a decision left inside UI code instead of extracted and tested; work on the
   JavaFX thread that could block; a string not added to all six `messages*.properties` catalogues;
   an application chord on plain `Ctrl+key`, which belongs to the shell.
3. **See it work.** Terminal bugs are visual and none of them throw: drive the real window with the
   capture hook ([`docs/testing.md`](docs/testing.md#development-capture)) before calling a UI change
   done.
4. **`./mvnw spotless:apply`**, then the tests for what you touched.
5. **Update the docs in the same pull request**: `CHANGELOG.md` under `## [Unreleased]` for anything
   a user can see, the website for user documentation, and the guide in `docs/` that covers
   anything about how Termina is built or put together.
6. **Open a pull request against `master`.** Say what changed, why, how you checked it, and what you
   did not check.

## Reporting a problem

Open an issue with the bug report form. About → **Copy details** copies what `termina --version`
prints, for its Environment field.
