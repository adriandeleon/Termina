# TODO

The open list: what Termina does not do yet, or has not been checked on. It is kept in the same pull
request as the change, so an item goes when the change that closes it lands and a new one is added
with the change that leaves it open. What has shipped is in [`CHANGELOG.md`](CHANGELOG.md).

## Pauta 6.0.1

The rules of [Pauta](https://pauta-project.dev/6.0.1/) not met yet, as
[`pauta.json`](src/main/resources/com/termina/pauta.json) records them and Help ▸ Pauta Conformance
shows them; tracked in [adriandeleon/Termina#92](https://github.com/adriandeleon/Termina/issues/92).
A test fails when this list and that file disagree.

### MUST

- [ ] **[OS-04](https://pauta-project.dev/6.0.1/same-on-every-os/#os-04)** Key bindings sync through one file for macOS and another for Windows and Linux, so a shortcut changed on a Mac is not changed on the others.
- [ ] **[PAUTA-13](https://pauta-project.dev/6.0.1/conformance/#pauta-13)** The website has no page yet that lists the Pauta edition, the conformance and these exceptions.
- [ ] **[DOCS-01](https://pauta-project.dev/6.0.1/documentation/#docs-01)** The user guide on the website has no screenshots yet.
- [ ] **[DOCS-02](https://pauta-project.dev/6.0.1/documentation/#docs-02)** How to install a download and check its signature is explained in the repository, not in the user guide on the website.
- [ ] **[REL-19](https://pauta-project.dev/6.0.1/release/#rel-19)** A change can still be merged without the format and test checks having passed.

### SHOULD

- [ ] **[PAUTA-14](https://pauta-project.dev/6.0.1/conformance/#pauta-14)** The website cannot build its conformance page from this list yet.
- [ ] **[PLAT-14](https://pauta-project.dev/6.0.1/platform/#plat-14)** The settings file is read and written by hand-written code and not by the JSON library the guidelines name.
- [ ] **[DOCS-03](https://pauta-project.dev/6.0.1/documentation/#docs-03)** The Documentation command opens the newest user guide, not the one for the version you are running.
- [ ] **[DOCS-04](https://pauta-project.dev/6.0.1/documentation/#docs-04)** The website's What's New page is written by hand, and nothing checks the command names and shortcuts the guide quotes.
- [ ] **[DOCS-18](https://pauta-project.dev/6.0.1/documentation/#docs-18)** The website's screenshots are from version 0.13.0 and were taken at normal size, not at double size like the README's.
- [ ] **[KBD-04](https://pauta-project.dev/6.0.1/keyboard/#kbd-04)** On macOS a greyed row in the menu bar at the top of the screen does not say why it cannot run; every menu Termina draws itself does.
- [ ] **[ACC-09](https://pauta-project.dev/6.0.1/accessibility/#acc-09)** Termina does not follow Windows' high-contrast mode.
- [ ] **[WIN-15](https://pauta-project.dev/6.0.1/window-shell/#win-15)** On macOS, Settings is in the File menu and not in the application menu.
- [ ] **[PAL-06](https://pauta-project.dev/6.0.1/templates/command-palette/#pal-06)** The Command Palette's movement keys are fixed and do not follow the key bindings.
- [ ] **[PAL-08](https://pauta-project.dev/6.0.1/templates/command-palette/#pal-08)** The Command Palette has no key that opens the documentation for the highlighted command.
- [ ] **[QOPEN-01](https://pauta-project.dev/6.0.1/templates/quick-open/#qopen-01)** There is no quick picker for themes, profiles or a setting's values; each is a separate row of the Command Palette.
- [ ] **[REL-17](https://pauta-project.dev/6.0.1/release/#rel-17)** The Windows installer is not signed.

One optional rule is not met and has no entry there: window modes that hide chrome
([WIN-10](https://pauta-project.dev/6.0.1/window-shell/#win-10)).

## Never run

- [ ] **Windows.** Nothing has run there: the installer, the ConPTY path, the directory read
  (`pty.WindowsCwd`), the PowerShell toast, the shell discovery (PowerShell, Git Bash, WSL) and
  high-contrast mode. The MSI is unsigned.
- [ ] **macOS:** the notification and Option+drag blocks, and what the Pauta 6.0.1 work added (the
  greyed rows of the screen menu bar, the Doctor).
- [ ] **The Doctor and the menus' reasons on a real desktop.** They have been seen in headless
  captures and tests only.

## Terminal

- [ ] Key bindings of more than one chord.
- [ ] Images (sixel, the kitty and iTerm2 protocols) and font ligatures.
- [ ] The emulator's gaps that nothing repairs yet, pinned in `EmulatorGapsTest`: REP, DECSLRM and
  cursor blink (`?12`).
- [ ] Restoring the tabs that were open at the last quit.

## Performance

- [ ] Damage tracking: a dirty frame redraws every visible row
  ([review of 2026-11-06](docs/reference/review-2026-11-06.md), 3.1, and the items listed as not
  done at its top).
