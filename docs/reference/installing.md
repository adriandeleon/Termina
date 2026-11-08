# Installing

Releases carry native installers, built on the platform they target — jpackage and jlink are
host-specific and there is no cross-building.

| Platform | Download | Install |
| --- | --- | --- |
| macOS, Apple Silicon | `macos-arm64.dmg` | open, drag to Applications |
| macOS, Intel | `macos-x64.dmg` | open, drag to Applications |
| Windows | `windows-x64.msi` | run it |
| Debian, Ubuntu | `linux-x64.deb`, `linux-arm64.deb` | `sudo dpkg -i` or `sudo apt install ./…` |
| Fedora, RHEL, openSUSE | `linux-x64.rpm`, `linux-arm64.rpm` | `sudo rpm -i` or `sudo dnf install ./…` |
| Any Linux | `linux-x64.AppImage`, `linux-arm64.AppImage` | `chmod +x`, run it (needs FUSE) |
| Any Linux, no root | `linux-x64.tar.gz`, `linux-arm64.tar.gz` | unpack, run `termina-<version>/bin/Termina` |

Every Linux download has an OpenPGP signature beside it, `<file>.asc`, by the key attached to the
release as `termina-signing-key.asc` and committed as `packaging/linux/signing-key.asc`:

```bash
gpg --import termina-signing-key.asc
gpg --verify Termina-<version>-linux-x64.deb.asc Termina-<version>-linux-x64.deb
```

The rpm and deb are also signed inside: `sudo rpm --import termina-signing-key.asc` and `rpm -K`
(and `dnf`) check the rpm; `debsig-verify` checks the deb's origin signature, given a policy for the
key. apt and dpkg check nothing about a downloaded `.deb`, so for one the `.asc` is the check.

The Linux packages install a launcher entry carrying `StartupWMClass` and the `TerminalEmulator`
category, so Termina appears in the application menu and desktops offer it where a terminal is
wanted. That works because Termina accepts `-e command`, which is the interface everything expects
of a system terminal. They also put `termina` on `PATH` (`/usr/bin/termina`), install AppStream
metainfo for GNOME Software and KDE Discover — with the description, licence, release notes and an
icon in the theme, so Software lists Termina rather than a bare `termina` package — and declare `X-TerminalArgExec=-e` for
`xdg-terminal-exec`; the deb registers an `x-terminal-emulator` alternative, at a priority below
GNOME Terminal's and Konsole's, so `update-alternatives --config x-terminal-emulator` offers it
without it taking over. Details in [`packaging/linux/NOTES.md`](../../packaging/linux/NOTES.md).

The deb installs on Debian 12 and Ubuntu 22.04 as well as their successors: it declares the
libraries JavaFX loads (GTK 3, GL, fontconfig and the rest), which the bundler cannot see, and no
longer depends on ALSA, which Termina never used. The 0.12.0 debs required `libasound2t64` and
installed only on Debian 13 and Ubuntu 24.04 onwards.

Every installed copy carries `LICENSE` and `NOTICE` beside the application's jars, and the jar has
them under `META-INF/`.

The entry sets `StartupWMClass=com.termina.App`, which is the class JavaFX derives from the module's
main class rather than from the application name. Without it a running window cannot be matched to
its launcher, so the dock shows a second generic icon beside the entry instead of highlighting it.
**This one wants checking on a real desktop** — `xprop WM_CLASS`, then click the window.
