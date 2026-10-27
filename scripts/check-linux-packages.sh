#!/usr/bin/env bash
# Checks the built .deb and .rpm against what they must contain, as installed rather than as
# written: every failure below once produced a package that built, installed and looked fine.
# Run by CI on every push and by the release on each Linux build before anything is published.
#
# Needs dpkg-deb, desktop-file-validate, rpm, rpm2cpio and cpio; appstreamcli if installed.
#
# Usage: check-linux-packages.sh <installers-dir> <branding-icon>
set -euo pipefail

dir="${1:?installers directory required}"
icon="${2:?branding icon required}"
# Absolute, because the rpm is unpacked from inside another directory.
dir="$(cd "$dir" && pwd)"
icon="$(cd "$(dirname "$icon")" && pwd)/$(basename "$icon")"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail() { echo "::error::$*"; exit 1; }

# Lists go to files rather than being piped into grep -q: grep -q exits at the first match, which
# closes the pipe under dpkg-deb, and pipefail then reports the whole pipeline as failed — so a check
# once said "no AOT cache" about a package that contained one.
has() { grep -q -- "$2" "$1"; }

# The desktop entry jpackage will happily replace with its own if the override is not named exactly
# after the launcher (packaging/linux/NOTES.md).
check_desktop_entry() {
    local entry="$1"
    echo "--- shipped desktop entry ---"; cat "$entry"
    desktop-file-validate "$entry"
    has "$entry" "^StartupWMClass=com.termina.App$" ||
        fail "no StartupWMClass — the override was ignored, as it is when misnamed"
    has "$entry" "^Categories=.*TerminalEmulator" || fail "not registered as a terminal emulator"
    has "$entry" "^X-TerminalArgExec=-e$" || fail "no X-TerminalArgExec — xdg-terminal-exec cannot run a command in it"
    # jpackage substitutes only some of its own tokens; an unsubstituted one ships literally.
    if has "$entry" "APPLICATION_"; then fail "an APPLICATION_* placeholder was not substituted"; fi
}

# The bundlers ignore --icon and regenerate lib/Termina.png; the 0.1.0 packages shipped jpackage's
# 32x32 Java icon that way.
check_icon() {
    local shipped="$1" want got
    want=$(sha256sum "$icon" | cut -d' ' -f1)
    got=$(sha256sum "$shipped" | cut -d' ' -f1)
    [ "$want" = "$got" ] || fail "lib/Termina.png is not branding/termina.png ($got) — the icon override was lost"
}

# ---------------------------------------------------------------------------------------------- deb
deb=$(ls "$dir"/*.deb)
echo "=== $deb"
dpkg-deb --info "$deb" > "$work/deb-info.txt"
dpkg-deb --field "$deb" Depends > "$work/deb-depends.txt"
dpkg-deb --contents "$deb" > "$work/deb-contents.txt"
echo "Depends: $(cat "$work/deb-depends.txt")"

has "$work/deb-contents.txt" "termina.aot" || fail "the deb has no AOT cache — startup would be a quarter slower"
has "$work/deb-contents.txt" "/opt/termina/lib/app/NOTICE" || fail "the deb has no NOTICE"
has "$work/deb-contents.txt" "/opt/termina/lib/app/LICENSE" || fail "the deb has no LICENSE"
has "$work/deb-contents.txt" "io.github.adriandeleon.Termina.metainfo.xml" || fail "the deb has no AppStream metainfo"

# The build host's names leak into Depends. A t64-renamed package on its own (libasound2t64 was the
# one) exists only on Debian 13+ and Ubuntu 24.04+, and makes the package uninstallable on Debian 12
# and Ubuntu 22.04. Allowed only as one side of an alternative.
tr ',' '\n' < "$work/deb-depends.txt" | sed 's/^ *//; s/ *$//' > "$work/deb-depends-list.txt"
if grep -E 't64( |\(|$)' "$work/deb-depends-list.txt" | grep -v '|' > "$work/t64.txt"; then
    fail "the deb depends on a t64-only package with no fallback: $(tr '\n' ' ' < "$work/t64.txt")"
fi
if has "$work/deb-depends-list.txt" "libasound"; then fail "the deb depends on ALSA, which Termina never uses"; fi
# JavaFX's own libraries, which the bundler cannot see (scripts/build-installers.sh).
for lib in libgtk-3-0 libgl1 libxxf86vm1 libfontconfig1; do
    has "$work/deb-depends-list.txt" "$lib" || fail "the deb does not depend on $lib, which JavaFX needs"
done

rm -rf "$work/deb" && mkdir -p "$work/deb"
dpkg-deb -R "$deb" "$work/deb"
entry=$(find "$work/deb" -type f -name "*.desktop" -print -quit)
[ -n "$entry" ] || fail "the deb installs no desktop entry"
check_desktop_entry "$entry"
check_icon "$work/deb/opt/termina/lib/Termina.png"

copyright=$(find "$work/deb" -type f -name copyright -print -quit)
[ -n "$copyright" ] || fail "the deb has no copyright file"
has "$copyright" "^License: MIT$" || fail "the deb's copyright does not name the licence"

for script in postinst prerm; do
    sh -n "$work/deb/DEBIAN/$script" || fail "the deb's $script does not parse"
    # The override replaces jpackage's script; its desktop-menu commands must have survived.
    has "$work/deb/DEBIAN/$script" "xdg-desktop-menu" || fail "the deb's $script lost jpackage's desktop-menu commands"
done
has "$work/deb/DEBIAN/postinst" "update-alternatives --install /usr/bin/x-terminal-emulator" ||
    fail "the deb does not register as an x-terminal-emulator"
has "$work/deb/DEBIAN/postinst" "/usr/bin/termina" || fail "the deb does not put termina on PATH"
[ -x "$work/deb/opt/termina/bin/Termina" ] || fail "the launcher the maintainer scripts link to is not at /opt/termina/bin/Termina"

if command -v appstreamcli >/dev/null 2>&1; then
    appstreamcli validate --no-net "$work/deb/opt/termina/lib/io.github.adriandeleon.Termina.metainfo.xml"
fi
echo "deb: OK"

# ---------------------------------------------------------------------------------------------- rpm
# Never inspected before this: it shipped on every release unopened.
rpm=$(ls "$dir"/*.rpm)
echo "=== $rpm"
rpm -qpl "$rpm" > "$work/rpm-contents.txt"
rpm -qpR "$rpm" > "$work/rpm-requires.txt"
rpm -qp --scripts "$rpm" > "$work/rpm-scripts.txt"
echo "--- Requires ---"; cat "$work/rpm-requires.txt"

has "$work/rpm-contents.txt" "termina.aot" || fail "the rpm has no AOT cache"
has "$work/rpm-contents.txt" "^/opt/termina/lib/app/NOTICE$" || fail "the rpm has no NOTICE"
has "$work/rpm-contents.txt" "^/usr/bin/termina$" || fail "the rpm does not put termina on PATH"
has "$work/rpm-contents.txt" "^/usr/share/metainfo/io.github.adriandeleon.Termina.metainfo.xml$" ||
    fail "the rpm installs no AppStream metainfo"
has "$work/rpm-contents.txt" "LICENSE" || fail "the rpm carries no licence file"
if has "$work/rpm-requires.txt" "libasound"; then fail "the rpm requires ALSA, which Termina never uses"; fi
has "$work/rpm-requires.txt" "libgtk-3.so.0" || fail "the rpm does not require GTK 3, which JavaFX needs"
has "$work/rpm-scripts.txt" "xdg-desktop-menu" || fail "the rpm's scriptlets lost jpackage's desktop-menu commands"

rm -rf "$work/rpm" && mkdir -p "$work/rpm"
(cd "$work/rpm" && rpm2cpio "$rpm" | cpio -idm --quiet)
entry=$(find "$work/rpm" -type f -name "*.desktop" -print -quit)
[ -n "$entry" ] || fail "the rpm installs no desktop entry"
check_desktop_entry "$entry"
check_icon "$work/rpm/opt/termina/lib/Termina.png"
[ "$(readlink "$work/rpm/usr/bin/termina")" = "/opt/termina/bin/Termina" ] ||
    fail "the rpm's /usr/bin/termina does not point at the launcher"
echo "rpm: OK"
