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

# --license-file is Linux only. On the DMG and the MSI it becomes an agreement the user has to click
# through before installing, which a permissive licence does not ask for; there the notices travel
# inside the app image (scripts/add-notices.sh), as they do in the Linux packages too.
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
        #
        # Beside those, the deb's postinst and prerm and the rpm's termina.spec: jpackage's own
        # templates with `termina` on PATH, the x-terminal-emulator alternative and the AppStream
        # listing added (packaging/linux/NOTES.md). jpackage installs only under /opt, so this is
        # the one way anything reaches /usr.
        linux="$(dirname "$0")/../packaging/linux"
        resources="$(mktemp -d)"
        cp "$linux/"*.desktop "$linux/postinst" "$linux/prerm" "$linux/termina.spec" "$resources/"
        [ -n "$icon" ] && [ -f "$icon" ] && cp "$icon" "$resources/Termina.png"
        # Into the image's lib/, next to the desktop entry and icon the bundlers put there; the
        # maintainer scripts and the spec install it from there into /usr/share/metainfo.
        cp "$linux/io.github.adriandeleon.Termina.metainfo.xml" "$image/lib/"

        # What JavaFX links. Its natives are extracted from the module jars at run time, so the
        # bundlers' library scan never sees them and declares none of this — the package installed
        # on a minimal system and the app died at start. From `readelf -d` of libglassgtk3,
        # libprism_es2 and libjavafx_font_pango/freetype (JavaFX 27). Debian names with the t64
        # rename as an alternative, so one package installs on Debian 12 and 13, and Ubuntu 22.04
        # and 24.04; the rpm by shared-library capability, which Fedora, RHEL and openSUSE all
        # answer whatever they call the package.
        deb_deps="libgtk-3-0t64 | libgtk-3-0, libgl1, libx11-6, libxxf86vm1, libxtst6"
        deb_deps+=", libpango-1.0-0, libpangoft2-1.0-0, libfontconfig1, libfreetype6"
        rpm_deps="libgtk-3.so.0()(64bit), libGL.so.1()(64bit), libX11.so.6()(64bit)"
        rpm_deps+=", libXxf86vm.so.1()(64bit), libXtst.so.6()(64bit), libpangoft2-1.0.so.0()(64bit)"
        rpm_deps+=", libfontconfig.so.1()(64bit), libfreetype.so.6()(64bit)"

        # The licence goes into the deb's copyright file (otherwise "License: Unknown") and the
        # rpm's licence directory. The full notices are in the image already (add-notices.sh).
        license="$(dirname "$0")/../LICENSE"
        # The deb's copyright is written here rather than left to --license-file, which pastes the
        # file under "License:" as is: the name becomes "MIT License" and the first blank line ends
        # the paragraph, so the result is not machine-readable. This is the Debian copyright format,
        # generated from LICENSE so the licence still has one source.
        {
            echo "Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/"
            echo "Upstream-Name: Termina"
            echo "Source: https://github.com/adriandeleon/Termina"
            echo
            echo "Files: *"
            echo "Copyright: $(sed -n 's/^Copyright (c) //p' "$license" | head -1)"
            echo "License: MIT"
            echo "Comment: Third-party components and their licences are listed in"
            echo " /opt/termina/lib/app/NOTICE; the Java runtime's own notices are in"
            echo " /opt/termina/lib/runtime/legal."
            echo
            echo "License: MIT"
            sed -e '1{/^MIT License$/d;}' "$license" | sed -e '1{/^$/d;}' -e 's/^$/./' -e 's/^/ /'
        } > "$resources/copyright"

        build deb \
            --linux-package-name termina \
            --linux-deb-maintainer "adrian.deleon@gmail.com" \
            --linux-menu-group "System;TerminalEmulator" \
            --linux-shortcut \
            --linux-package-deps "$deb_deps" \
            --license-file "$license" \
            --resource-dir "$resources"
        build rpm \
            --linux-package-name termina \
            --linux-rpm-license-type MIT \
            --linux-menu-group "System;TerminalEmulator" \
            --linux-shortcut \
            --linux-package-deps "$rpm_deps" \
            --license-file "$license" \
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
