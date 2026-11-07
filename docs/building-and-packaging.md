# Building and packaging

From source to an app image and the installers. Publishing them is [release.md](release.md).

## Commands

- Run: `./mvnw javafx:run`
- Run with ad-hoc options: **`scripts/dev-run.sh -Dfoo=bar`**. `mvn javafx:run` cannot take them —
  the plugin's `<options>` are fixed in the pom — and `-Pdist` deletes `target/deps` every time, so
  the script rebuilds the module path itself.
- Build and test: `./mvnw verify`
- Native app image: **`./mvnw clean -Pdist -DskipTests package`** ⇒ `target/dist/Termina{.app}`.
  `clean` is **required**: jlink's input is built from `target/classes`, and an incremental compile
  can leave that inconsistent in ways javac will not correct. Add `-Dskip.installers=false` for the
  installers as well, under `target/installers`.

Run Maven from the project root. The build requires JDK 25 or newer and Maven 3.9 (the enforcer
checks both); `./mvnw` brings the right Maven.

## Formatting

`./mvnw verify` runs the tests, checks formatting and enforces the coverage floors. **Run
`./mvnw spotless:apply` before committing** — `verify` fails on unformatted code, and so does CI.
The compile loop and `mvn javafx:run` are deliberately left alone.

Formatting is [Palantir Java Format](https://github.com/palantir/palantir-java-format) via Spotless:
a 120-column, lambda-friendly fork of google-java-format. Imports are grouped JDK, then javafx, then
everything else, then static. Hand-aligned code can be wrapped in `// spotless:off` … `// spotless:on`.

## App images and installers

jlink and jpackage are host-specific, so each platform's image can only be built on that platform,
which is what the CI and release matrices are for. The `dist` profile builds the jlinked image, adds
`LICENSE` and `NOTICE` (`scripts/add-notices.sh`), trains the AOT cache (`scripts/aot-train.sh`),
fixes and signs the macOS bundle, and builds the installers (`scripts/build-installers.sh`). The
Linux packages have traps of their own, recorded in
[`packaging/linux/NOTES.md`](../packaging/linux/NOTES.md); verify a built package, not the tree.

The version reaches the bundle through two derived values, because jpackage will not accept one it
cannot parse and macOS additionally refuses any whose first number is zero. `jpackage.numericVersion`
is the pom version without any suffix — `-SNAPSHOT` or `-rc1` alike, since jpackage rejects both;
the application and the installers' file names keep the full version. `jpackage.appVersion` is that
with a leading `0.` bumped to `1.` on macOS only, purely to get past the check.
`scripts/fix-mac-bundle-version.sh` then puts the true version back into `Info.plist` and re-signs
the bundle — always, not only when the version changed: jpackage ad-hoc-signs the app image, and the
plist, the notices and the AOT cache added after it are all inside what that seals, so any of them
left unsigned makes macOS refuse to launch the app as damaged.

## Branding

`branding/termina-icon.svg` is the source. The window icons
(`src/main/resources/com/termina/icons/icon-*.png`) and the installer icons (`termina.icns`,
`termina.ico`, `termina.png`) are generated from it — regenerate all of them after editing the SVG:

```bash
for s in 16 24 32 48 64 128 256 512 1024; do
  rsvg-convert -w $s -h $s branding/termina-icon.svg -o src/main/resources/com/termina/icons/icon-$s.png
done
```

It keeps Editora's family — the same Ink navy tile, corner radius and teal gradient, and the same
split of a muted periwinkle framing glyph against a teal focal element — with a shell prompt in
place of a letterform. Two shapes only: an earlier draft added the input line beneath the cursor,
and below 32px it stopped reading as a line and became noise next to the block, which is the size
an app icon has to work hardest at.
