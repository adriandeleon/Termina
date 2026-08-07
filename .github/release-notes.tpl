## Install

**macOS** — `brew install --cask adriandeleon/tap/termina`, or take the archive below
(`macos-arm64` for Apple Silicon, `macos-x64` for Intel).

Termina is not yet signed with an Apple Developer ID, so macOS will refuse to open it the first
time. Allow it once under System Settings → Privacy & Security → Open Anyway.

**Windows** — `winget install AdrianDeLeon.Termina`, or extract the `windows-x64` archive and run
`Termina\Termina.exe`. The build is unsigned, so SmartScreen will warn on first run.

**Linux** — extract the `linux-x64` archive and run `./install.sh` for this user, or
`sudo ./install.sh` for everyone. It puts `termina` on PATH, adds the launcher entry and icon, and
registers Termina as an `x-terminal-emulator` alternative so it can be chosen as the system
terminal.

Verify a download against `checksums.txt`.

## Changes

{{changelogChanges}}

{{changelogContributors}}
