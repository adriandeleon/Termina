# The Linux menu entry

`Termina.desktop` replaces the one jpackage generates, to add `StartupWMClass` — the class JavaFX
derives from the module's main class, without which a running window cannot be matched to its
launcher and the dock shows a second generic icon beside it.

Two things about this file are easy to get wrong, and both fail quietly.

**The name must match the launcher exactly.** jpackage looks for `Termina.desktop`; given
`termina.desktop` it ignores the override without a word and ships its own. The first build of these
packages went out that way — valid, installable, and missing `StartupWMClass` entirely.

On macOS and Windows the two spellings are the same file, so a rename between them can appear to
work while git still records the old case. `git ls-files packaging/linux` is the check. (`git rm -f
termina.desktop` will also delete a `Termina.desktop` you have just written.)

**Only some `APPLICATION_*` tokens are substituted.** `APPLICATION_NAME`, `APPLICATION_DESCRIPTION`,
`APPLICATION_LAUNCHER` and `APPLICATION_ICON` are replaced. `Icon=` does not use its token: it names
`io.github.adriandeleon.Termina`, the icon every format installs into the hicolor theme, because
`APPLICATION_ICON` is a path under `/opt` and a software centre looks an icon up by name. `APPLICATION_CATEGORIES` and
`APPLICATION_MIMETYPES` are *not* — a package built with those in place ships them literally, which
makes `Categories` invalid and stops the entry being classified as a terminal at all. Both are
written out here instead, which is also why `--linux-menu-group` on the jpackage command line has no
effect on this file.

Comments are deliberately absent from the entry itself: it is installed onto users' machines, and
notes about our build belong here.

CI asserts all of this against the built `.deb` and `.rpm` (`scripts/check-linux-packages.sh`), and
the release does the same before publishing, rather than against this file, because every failure
above produces a package that looks fine from the outside.

## The icon does not come through `--icon`

`--icon` is enough for the app image: `-Pdist` produces `Termina/lib/Termina.png` holding the real
512x512 branding. The **deb and rpm bundlers ignore it** and regenerate `lib/<name>.png` from their
own resources — the 0.1.0 packages shipped a file byte-identical to jpackage's bundled 32x32
`JavaApp.png`, so the launcher showed a stock Java icon while the app image they were wrapped from
had the correct one. The `.desktop` then points `Icon=` at that generic file.

Supplying `Termina.png` through the resource directory is the only override those bundlers honour.
`build-installers.sh` stages it there from `branding/`, rather than committing a second copy beside
this file, so the branding has one source.

The packages also install the same file as
`/usr/share/icons/hicolor/512x512/apps/io.github.adriandeleon.Termina.png`, which is the one the
menu entry and the metainfo name.

Verify against a built package rather than against the tree — nothing at build time fails when this
regresses. `check-linux-packages.sh` compares the hashes in CI and in the release; by hand:

```bash
dpkg-deb --fsys-tarfile target/installers/termina_*.deb \
  | tar -xO ./opt/termina/lib/Termina.png | sha256sum
sha256sum branding/termina.png
```

## Beyond /opt: the maintainer scripts and the spec

jpackage installs the application under `/opt/termina` and nothing anywhere else. Several things have
to be outside it, and its resource directory and a rebuild of the finished deb are the only ways to
get them there:

- **`termina` on `PATH`** — `/usr/bin/termina`, a link to `/opt/termina/bin/Termina`. The launcher
  resolves its runtime from its own real path, so a link works where a copy would not.
- **The `x-terminal-emulator` alternative** (Debian and Ubuntu only), at priority 20 — xterm's, below
  GNOME Terminal and Konsole, so installing Termina adds it to the choice without taking the default.
- **AppStream metainfo**, `io.github.adriandeleon.Termina.metainfo.xml` in `/usr/share/metainfo`,
  which is what GNOME Software and KDE Discover list, and the **icon** it and the menu entry name. Its
  `launchable` names `termina-Termina.desktop`, the id jpackage's `xdg-desktop-menu install` gives
  the menu entry (package name, dash, launcher).

**The metainfo has to be a file the package owns**, not one a maintainer script puts there. Software
ties a listing to its package by asking the package manager which package owns the metainfo file
(and by `<pkgname>`, which the metainfo now also carries, because a distribution's catalogue would
add it and a downloaded package has none). The 0.13.0 deb copied it in from `postinst`, so dpkg
said no package owned it and Software showed the package bare instead: "termina" in lower case, a
generic icon, "Unknown License", and the summary standing in for the description. Nothing anywhere
reports this; `appstreamcli get io.github.adriandeleon.Termina` reads the file perfectly well.

For the rpm, the spec installs both as files of the package. The deb has no such hook — jpackage's
deb bundler packages `/opt/termina` and nothing else — so `build-installers.sh` unpacks the finished
deb, adds the metainfo, the icon and `/usr/share/doc/termina/copyright`, rewrites `md5sums` and
`Installed-Size`, and rebuilds it. `check-linux-packages.sh` checks the deb owns them and that its
sums match. The link and the alternative stay in `postinst`: the link is left alone when something
else has the name, which a packaged file could not do.

For the deb, `postinst` and `prerm` here replace jpackage's; for the rpm, `termina.spec` replaces its
spec, and the link, the metainfo and the icon are files the package owns. **All three are jpackage's own
templates (JDK 25's `template.postinst`, `template.prerm`, `template.spec`) with Termina's lines
added**, and they keep jpackage's tokens — `DESKTOP_COMMANDS_INSTALL` and the rest are substituted in
an override just as in the stock file. Losing them would silently drop the menu entry, so CI checks
the built scripts still call `xdg-desktop-menu`. After a JDK upgrade, compare against the new
templates:

```bash
jimage extract --dir /tmp/jp --include 'glob:/jdk.jpackage/**' "$JAVA_HOME/lib/modules"
ls /tmp/jp/jdk.jpackage/jdk/jpackage/internal/resources/template.*
```

The removal side runs on `remove`, not on `upgrade`: the new `postinst` puts the same things back,
and dropping the alternative in between would forget a user's choice of Termina as the default.

Termina takes `-e` as `x-terminal-emulator` and `xdg-terminal-exec` (`X-TerminalArgExec=-e`) expect,
and `-T title`, which Debian policy also asks of an `x-terminal-emulator`; it names the first tab.

**The metainfo's `<releases>` is written by hand.** Add the version, date and a sentence or two of
`<description>` there when cutting a release, or Software lists the previous one as the latest, and
says "No details for this release" for one with no description.

## Dependencies the bundler cannot see

The deb bundler declares a dependency on whatever package owns each native library in the image, *as
named on the build host*. Two consequences:

- **The image decides the dependencies, not us.** `java.desktop` carries `libjsound`, and on
  `ubuntu-latest` its ALSA library belongs to `libasound2t64` — a name that exists only on Debian 13
  and Ubuntu 24.04 onwards, so the 0.12.0 debs could not be installed on Debian 12 or Ubuntu 22.04.
  Termina plays no sound, so jlink leaves `libjsound` out (`--exclude-files` in the pom) and the
  dependency goes with it. CI fails any `…t64` dependency that is not one side of an alternative.
- **JavaFX is invisible to it.** Its natives are extracted from the module jars at run time, so GTK 3,
  GL, `libXxf86vm`, `libXtst`, Pango, fontconfig and FreeType were never declared, and a minimal
  system installed the package and then failed at start. `build-installers.sh` declares them with
  `--linux-package-deps`, from `readelf -d` of `libglassgtk3.so`, `libprism_es2.so` and
  `libjavafx_font_*.so` in the JavaFX Linux jar. Recheck after a JavaFX upgrade:

```bash
unzip -o -q ~/.m2/repository/org/openjfx/javafx-graphics/<v>/javafx-graphics-<v>-linux.jar -d /tmp/fx
for f in /tmp/fx/*.so; do echo "$f"; readelf -d "$f" | grep NEEDED; done
```

## Licence

The deb's `copyright` is generated by `build-installers.sh` from `LICENSE` in the Debian copyright
format; `--license-file` alone pastes the file in raw, which is not machine-readable. The rpm gets
`LICENSE` in its licence directory. Both, like every platform's image, carry `LICENSE` and `NOTICE`
in the app directory (`scripts/add-notices.sh`).

## The AppImage and the tarball

Both carry the same image as the packages, untouched; neither goes through jpackage.

The **tarball** is `target/dist/Termina` renamed to `termina-<version>/`, owned by root and without
group or world write, so it unpacks the same whoever built it. `bin/Termina` starts it.

The **AppImage** is built by `appimagetool` with the static type-2 runtime, both pinned by version and
SHA-256 in `build-installers.sh` and cached in `~/.cache/termina-build`. The image goes under
`usr/lib/termina`, and `AppRun` (here) starts its launcher where it lies. Two things about it fail
quietly:

- **appimagetool finds the metainfo by the desktop entry's name, and only as `.appdata.xml`.** With
  the packages' `termina-Termina.desktop` it found nothing and skipped validating, with a warning in
  the middle of its output. The AppImage's entry is `io.github.adriandeleon.Termina.desktop`, its
  metainfo `io.github.adriandeleon.Termina.appdata.xml`, with the `launchable` rewritten to match.
- **The entry has no `TryExec`.** The AppImage is not on `PATH`, and an entry whose `TryExec` cannot
  be found is hidden from the menu.

Running it needs FUSE. A runner, a container or a sandbox without it fails with `fusermount: mount
failed`; `APPIMAGE_EXTRACT_AND_RUN=1` runs it from an extracted copy, which is how CI smoke-tests it.

## Signing

`scripts/sign-linux-packages.sh` signs, `scripts/check-linux-signatures.sh` checks, and the README's
"Linux signing" has the key and the secrets. What each format carries:

| Format | Embedded | Checked by | Detached `.asc` |
| --- | --- | --- | --- |
| rpm | `rpmsign` header signature | `rpm -K`, dnf, zypper | yes |
| deb | `debsigs` origin signature (`_gpgorigin`) | `debsig-verify` | yes |
| AppImage | none | — | yes |
| tar.gz | none | — | yes |

**apt and dpkg ignore a deb's embedded signature.** apt checks a repository's signed `Release`, not
packages, and dpkg checks nothing unless `debsig-verify` is installed and configured. For a deb
downloaded from the release page, the `.asc` is what a user can check.

**The AppImage could carry an embedded signature only if it were signed where it is built**:
appimagetool signs while it writes the file. That would put the key on the build runners, which run
whatever Maven resolves from a repository outside Central, so it is signed after, by a job that runs
nothing else, and carries only the `.asc`.

The check runs against the public key alone (`gpgv` with a one-key keyring, an rpm database holding
only that key), never in the keyring that signed: there a signature by any key it holds would pass.
