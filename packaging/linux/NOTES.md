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
`APPLICATION_LAUNCHER` and `APPLICATION_ICON` are replaced. `APPLICATION_CATEGORIES` and
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

Verify against a built package rather than against the tree — nothing at build time fails when this
regresses. `check-linux-packages.sh` compares the hashes in CI and in the release; by hand:

```bash
dpkg-deb --fsys-tarfile target/installers/termina_*.deb \
  | tar -xO ./opt/termina/lib/Termina.png | sha256sum
sha256sum branding/termina.png
```

## Beyond /opt: the maintainer scripts and the spec

jpackage installs the application under `/opt/termina` and nothing anywhere else. Three things have
to be outside it, and its resource directory is the only way to get them there:

- **`termina` on `PATH`** — `/usr/bin/termina`, a link to `/opt/termina/bin/Termina`. The launcher
  resolves its runtime from its own real path, so a link works where a copy would not.
- **The `x-terminal-emulator` alternative** (Debian and Ubuntu only), at priority 20 — xterm's, below
  GNOME Terminal and Konsole, so installing Termina adds it to the choice without taking the default.
- **AppStream metainfo**, `io.github.adriandeleon.Termina.metainfo.xml` in `/usr/share/metainfo`,
  which is what GNOME Software and KDE Discover list. Its `launchable` names `termina-Termina.desktop`,
  the id jpackage's `xdg-desktop-menu install` gives the menu entry (package name, dash, launcher).
  `build-installers.sh` copies it into the image's `lib/` first, where the scripts install it from.

For the deb, `postinst` and `prerm` here replace jpackage's; for the rpm, `termina.spec` replaces its
spec, and the link and the metainfo are files the package owns. **All three are jpackage's own
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

Termina takes `-e` as `x-terminal-emulator` and `xdg-terminal-exec` (`X-TerminalArgExec=-e`) expect.
It does **not** take `-T title`, which Debian policy also asks of an `x-terminal-emulator`; a caller
passing it gets "unknown option".

**The metainfo's `<releases>` is written by hand.** Add the version and date there when cutting a
release, or Software lists the previous one as the latest.

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
