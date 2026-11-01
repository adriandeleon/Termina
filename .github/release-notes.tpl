## Install

**macOS** — `brew install --cask adriandeleon/tap/termina`, or download the DMG below
(`macos-arm64` for Apple Silicon, `macos-x64` for Intel), open it, and drag Termina to Applications.

The app is signed with a Developer ID and notarized by Apple, so it opens on first launch like any
other app.

**Windows** — `winget install AdrianDeLeon.Termina`, or download and run the `windows-x64` MSI
below. The build is unsigned, so SmartScreen may warn on first run.

**Linux** — download the DEB or RPM for your architecture (`linux-x64` or `linux-arm64`).
On Debian or Ubuntu, install with `sudo apt install ./Termina-*.deb`; on Fedora, install with
`sudo dnf install ./Termina-*.rpm`. Without either, or without root, the `AppImage` runs as it is
(`chmod +x`, then run it), and the `tar.gz` unpacks to a directory whose `bin/Termina` starts it.

Verify a download against `checksums_sha256.txt`. Each Linux download is also signed: import
`termina-signing-key.asc` (`gpg --import termina-signing-key.asc`) and run
`gpg --verify <file>.asc <file>`. The RPM carries its signature inside as well, which
`rpm --import termina-signing-key.asc` lets `rpm -K` and `dnf` check.

## Changes

{{changelogChanges}}

{{changelogContributors}}
