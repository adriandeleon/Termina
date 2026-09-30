## Install

**macOS** — `brew install --cask adriandeleon/tap/termina`, or download the DMG below
(`macos-arm64` for Apple Silicon, `macos-x64` for Intel), open it, and drag Termina to Applications.

Termina is not yet signed with an Apple Developer ID, so macOS will refuse to open it the first
time. Allow it once under System Settings → Privacy & Security → Open Anyway.

**Windows** — `winget install AdrianDeLeon.Termina`, or download and run the `windows-x64` MSI
below. The build is unsigned, so SmartScreen may warn on first run.

**Linux** — download the DEB or RPM for your architecture (`linux-x64` or `linux-arm64`).
On Debian or Ubuntu, install with `sudo apt install ./Termina-*.deb`; on Fedora, install with
`sudo dnf install ./Termina-*.rpm`.

Verify a download against `checksums_sha256.txt`.

## Changes

{{changelogChanges}}

{{changelogContributors}}
