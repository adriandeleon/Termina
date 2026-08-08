#!/usr/bin/env bash
# Wraps an already-finished application image into this platform's native installers.
#
# Wraps, rather than builds: the image it is given has already had its AOT cache trained into it and
# its version corrected, and jpackage's --app-image mode packages exactly what it is handed. Building
# the installer from scratch instead would produce a second, untrained image.
#
# jpackage is host-specific and there is no cross-building, so each platform makes its own:
#   macOS   .dmg
#   Windows .msi
#   Linux   .deb and .rpm
#
# Usage: build-installers.sh <app-image> <app-version> <dest-dir> <icon>
set -euo pipefail

image="${1:?app image required}"
version="${2:?app version required}"
dest="${3:?destination required}"
icon="${4:-}"

[ -d "$image" ] || { echo "no app image at $image" >&2; exit 1; }
mkdir -p "$dest"

common=(--app-image "$image" --name Termina --app-version "$version" --dest "$dest"
        --vendor "Adrian De Leon"
        --description "A keyboard-driven terminal emulator"
        --copyright "Copyright 2026 Adrian De Leon")
[ -n "$icon" ] && [ -f "$icon" ] && common+=(--icon "$icon")

build() {
    local type="$1"; shift
    echo "[installers] building $type"
    if jpackage --type "$type" "${common[@]}" "$@"; then
        echo "[installers] $type OK"
    else
        # Named, not swallowed. A release that quietly ships three installers instead of four is a
        # bug report from whichever platform was missing.
        echo "::error::[installers] $type failed" >&2
        return 1
    fi
}

case "$(uname -s)" in
    Darwin)
        build dmg --mac-package-name Termina
        ;;
    Linux)
        # The resource directory overrides two things jpackage would otherwise generate.
        #
        # The desktop entry, which carries StartupWMClass and the TerminalEmulator category —
        # without it the package installs an entry that no window can be matched to, and that
        # desktops do not offer as a terminal.
        #
        # And the icon. --icon above is enough for the app image and is ignored by the deb and rpm
        # bundlers, which regenerate lib/<name>.png from their own resources: the 0.1.0 packages
        # shipped a file byte-identical to jpackage's bundled 32x32 JavaApp.png, so the launcher
        # showed a stock Java icon while the app image beside it had the right one. Supplying
        # Termina.png here is the only override those bundlers honour.
        #
        # Staged into a copy rather than committed alongside the desktop entry, so branding/ stays
        # the single source of the icon.
        resources="$(mktemp -d)"
        cp "$(dirname "$0")/../packaging/linux/"*.desktop "$resources/"
        [ -n "$icon" ] && [ -f "$icon" ] && cp "$icon" "$resources/Termina.png"
        build deb \
            --linux-package-name termina \
            --linux-deb-maintainer "adrian.deleon@gmail.com" \
            --linux-menu-group "System;TerminalEmulator" \
            --linux-shortcut \
            --resource-dir "$resources"
        build rpm \
            --linux-package-name termina \
            --linux-rpm-license-type MIT \
            --linux-menu-group "System;TerminalEmulator" \
            --linux-shortcut \
            --resource-dir "$resources"
        ;;
    *)
        # A fixed upgrade UUID is what makes the next MSI replace this one rather than installing
        # beside it. Generated once and never changed; changing it strands every existing install.
        build msi \
            --win-menu \
            --win-menu-group Termina \
            --win-shortcut \
            --win-dir-chooser \
            --win-per-user-install \
            --win-upgrade-uuid "6f3b1c4a-9d2e-4f18-a5c7-83b0e6d21f47"
        ;;
esac

echo "[installers] produced:"
ls -la "$dest"
