# Releasing

Cutting a release, and what the release workflow signs, checks and publishes. Building the images
it publishes is [building-and-packaging.md](building-and-packaging.md).

## Cutting a release

To cut a release: rename `CHANGELOG.md`'s `## [Unreleased]` to `## [X.Y.Z] - <date>` (with a fresh
empty `## [Unreleased]` above it and the compare link at the bottom), set `<version>` in `pom.xml`
to the release version (drop `-SNAPSHOT`), commit, then push a `vX.Y.Z` tag. The release notes are
that section, under the install instructions in `.github/release-notes-install.md`
(`scripts/release-notes.sh`); a tag whose version has no section fails before anything is built. The workflow refuses to build if the tag and the pom disagree, or if the
pom still carries `-SNAPSHOT` — artifacts labelled with a version they were not built from are worse
than no artifacts. A tag with a suffix (`v1.0.0-rc1`) publishes as a pre-release.

Before tagging, make a pass with a screen reader (ACC-07, Pauta 6.0.1), on the build that is
about to be released: with **Narrator** on Windows and with **VoiceOver** on macOS, open the Command
Palette, run a command, and move through Settings, listening for each control's name and role. CI's
smoke test starts the app with nobody there to listen, so this is a step of its own and a person's.
Record the pass in `pauta.json`'s `checked` list, with the version it was made on.

`master` is a protected branch (REL-19, Pauta 6.0.1): **Format** and the three **Test** jobs of
`ci.yml` are required status checks, so a pull request cannot be merged until they have passed.
Administrators are not held to it, which is what lets the release commit be pushed straight to
`master`; GitHub says so on each such push. To read the setting:
`gh api repos/adriandeleon/Termina/branches/master/protection`. A job renamed in `ci.yml` has to be
renamed there too, or the pull requests wait for a check that never reports.

After tagging, `master` goes to the next `-SNAPSHOT`, so a build from source says it is one: the
update check treats a snapshot as the release it leads up to, and never offers it the release it is
already ahead of. **The release workflow's Start the next snapshot job does this; do not push the
bump by hand.** Once the release is published it pushes a branch, `build/start-X.Y.0-SNAPSHOT`,
with the minor number up (`0.16.0-SNAPSHOT` after 0.15.0, and after 0.15.1), and opens a pull
request from it. Opened with the workflow's own token, that pull request starts no CI: close and
reopen it to run the checks, then merge it. If `master` is already on that version the job says so
and does nothing.

The pull request needs **Allow GitHub Actions to create and approve pull requests** in the
repository's Actions settings (`gh api repos/adriandeleon/Termina/actions/permissions/workflow`,
`can_approve_pull_request_reviews`). It is off, so today the job pushes the branch and then fails,
"GitHub Actions is not permitted to create or approve pull requests", which fails the run after
everything is published. Open the pull request from that branch by hand
(`gh pr create --head build/start-X.Y.0-SNAPSHOT --fill`); it runs CI, being yours.

The release builds run the tests (`verify`, not CI's `-DskipTests package`) — a tag is not gated on
CI, and the `linux-arm64` and `macos-x64` runners build nowhere else — and then the same checks CI
makes of its own artefacts: the smoke test (`scripts/smoke-test.sh`), the macOS signature, an AOT
cache (`TERMINA_REQUIRE_AOT=1`), the Linux package contents (`scripts/check-linux-packages.sh`) and a
smoke test of the AppImage.
Any of them failing fails that target, and publishing needs every target. The publish job is the
only one holding a write token; it attests build provenance for each installer
(`gh attestation verify <file> --repo adriandeleon/Termina`), and the JReleaser action and the
JReleaser it runs are pinned.

## Package managers

JReleaser publishes the GitHub release and updates two package managers. It builds nothing — the app
images come from `-Pdist` on each platform, since jlink and jpackage are host-specific.

**Without a `PACKAGES_TOKEN` secret the release still works** — the workflow detects its absence and
excludes both packagers, so a tag produces the GitHub release, the four archives, checksums and the
notes, and says in the log that it skipped the cask and the pull request. Adding the token later
turns them on with no change to anything else.

To publish to Homebrew and winget:

1. ~~Create `adriandeleon/homebrew-tap`~~ — done.
2. ~~Fork `microsoft/winget-pkgs` to `adriandeleon/winget-pkgs`~~ — done.
3. Add a `PACKAGES_TOKEN` secret: a **classic** personal access token with `repo` scope
   (<https://github.com/settings/tokens/new>). The default `GITHUB_TOKEN` is scoped to this
   repository alone and cannot write to the tap or the fork. A fine-grained token will not do:
   opening a pull request against `microsoft/winget-pkgs` is not something it can express.

Neither is turnkey after that. **winget submissions are reviewed by Microsoft**, and the first one
for a new publisher is a human process rather than an automatic merge. **The official homebrew-cask
repository has notability requirements** a new project does not meet, which is why this uses a
personal tap.

The generated manifests can be inspected without releasing anything:

```
jreleaser package --basedir .
```

which writes them under `out/jreleaser/package`. Worth doing after any change to `jreleaser.yml`:
the stock templates assume a command-line tool unpacked into a folder, and both needed overriding —
they live in `src/jreleaser/distributions/`.

## macOS signing

Both macOS legs sign the app and dmg with the **Developer ID Application** certificate of team
`8JKSF797L8` (Adrian De Leon Saldivar), have Apple notarize the dmg and staple the ticket to it, so
Gatekeeper opens a downloaded Termina with no warning. The same certificate and API key sign Editora
and Torrenta.

- **Import signing certificate (macOS)** puts the `.p12` into a throw-away keychain and exports
  `TERMINA_MAC_SIGN_IDENTITY` / `TERMINA_MAC_KEYCHAIN`. On a tag, missing secrets fail the leg; a
  dry run without them builds ad-hoc signed and warns.
- `scripts/sign-mac-app.sh`, run by the `dist` profile after `fix-mac-bundle-version.sh` and before
  `build-installers.sh`, first sets `<signed>true</signed>` in `Contents/app/.jpackage.xml`
  (jpackage's dmg wrap otherwise re-signs its copy ad hoc, discarding ours), then signs every
  Mach-O file, the runtime bundle, and the app with the hardened runtime and
  `packaging/macos/Termina.entitlements` (JIT, plus library validation off for the natives JNA and
  pty4j unpack). `build-installers.sh` then signs the dmg. Without the identity (any local or CI
  build) nothing changes.
- The **smoke test** runs that signed app, so a real shell on a real pty is proven to work under the
  hardened runtime before anything is uploaded.
- **Notarize the dmg (macOS)** checks the app inside the dmg still verifies and is signed by team
  `8JKSF797L8`, runs `notarytool submit --wait` with the App Store Connect API key, prints Apple's
  log, staples, checks the dmg and its app with `spctl` the way Gatekeeper will, and then runs the
  smoke test once more on the notarized app inside the dmg (REL-15, Pauta 2.0.2).
  `WorkflowRulesTest` holds that order.

Repository secrets, the same values as Editora's and Torrenta's: `MACOS_CERT_P12` (base64 of a
`.p12` with the certificate, its key and Apple's Developer ID G2 intermediate, exported with
`openssl pkcs12 -export -legacy`), `MACOS_CERT_PASSWORD`, `APPLE_API_KEY_P8` (base64 of the team
API key, Developer role), `APPLE_API_KEY_ID` (10 characters) and `APPLE_API_ISSUER` (a UUID). The
certificate expires **2027-11-30** and the Apple Developer Program membership **2027-10-31**: renew
the membership, make a new certificate, and replace the first two secrets in all three repositories.

## Linux signing

The `sign-linux` job in `release.yml` signs both architectures' Linux downloads once the builds are
done, in a job of its own so the key is never on a runner that builds (those run whatever Maven
resolves). `scripts/sign-linux-packages.sh` signs the rpm with `rpmsign` and the deb with an origin
signature in debsigs' format (an `_gpgorigin` member; debsigs itself skips `.xz` members), then
writes an armoured `<file>.asc` beside all four formats — the only signature the AppImage and the
tarball carry, since appimagetool can embed one only while it builds.
`scripts/check-linux-signatures.sh` then checks every signature against the public key alone, with
`gpgv`, `rpmkeys` and `debsig-verify`. The job replaces the build artifacts with the signed files,
so the publish job attests and attaches only those, with `termina-signing-key.asc` beside them.

CI runs both scripts on every push with a key made for the job. On a tag the real key is required
and must be the committed `packaging/linux/signing-key.asc`; a manual dry run without it signs with
a throw-away key and warns.

One key signs the Linux releases of every project — Termina, Editora and Torrenta — as one
Developer ID certificate signs their macOS builds: users trust one key for all of them, and there is
one key to back up and renew. The cost is that a leaked key taints all three at once, and all three
move to a new one together. Its user id names the person, not a project, for that reason.

Repository secrets, the same values in each repository: `LINUX_GPG_KEY`, the armoured secret key,
and `LINUX_GPG_PASSPHRASE`, its passphrase (leave unset for a key without one). Each repository also
commits the public half as `packaging/linux/signing-key.asc`, which its release checks the secret
against. RSA rather than Ed25519, which older rpm and debsig-verify cannot check. Made once, in the
everyday keyring so it is there to sign with and to renew:

```bash
gpg --quick-gen-key 'Adrian De Leon (release signing) <adrian.deleon@gmail.com>' rsa4096 sign 5y
fpr=$(gpg --with-colons --list-secret-keys 'release signing' | awk -F: '$1 == "fpr" { print $10; exit }')
gpg --armor --export "$fpr" > packaging/linux/signing-key.asc
for repo in Termina Editora torrenta; do
  gpg --armor --export-secret-keys "$fpr" | gh secret set LINUX_GPG_KEY --repo "adriandeleon/$repo"
  gh secret set LINUX_GPG_PASSPHRASE --repo "adriandeleon/$repo"
done
```

Keep an offline copy of the secret key (`gpg --armor --export-secret-keys "$fpr"`) and of the
revocation certificate gpg wrote to `~/.gnupg/openpgp-revocs.d/`: GitHub will not give a secret
back, and a lost key means every user re-importing a new one. Copy the same `signing-key.asc` into
the other repositories. Renew before the expiry with `gpg --quick-set-expire "$fpr" 5y`, then
re-export both halves to every repository.
