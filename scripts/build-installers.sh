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
#   Linux   .deb, .rpm, .AppImage and .tar.gz
#
# Usage: build-installers.sh <app-image> <app-version> <dest-dir> <icon>
set -euo pipefail

image="${1:?app image required}"
version="${2:?app version required}"
dest="${3:?destination required}"
icon="${4:-}"

[ -d "$image" ] || { echo "no app image at $image" >&2; exit 1; }
mkdir -p "$dest"

description="A keyboard-driven terminal emulator"
common=(--app-image "$image" --name Termina --app-version "$version" --dest "$dest"
        --vendor "Adrian De Leon"
        --description "$description"
        # The deb's Homepage, the rpm's URL and the MSI's About link. GNOME Software's window for a
        # downloaded .deb reads nothing but the package's own fields — no listing, licence or icon —
        # and without this its Project Website button went nowhere.
        --about-url "https://termina-project.dev"
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

# jpackage's deb installs /opt/termina and nothing else, and the rpm's spec is ours, so only the deb
# needs this. GNOME Software and KDE Discover tie a listing to its package by asking the package
# manager which package owns the metainfo file; one that a postinst copied into place belongs to
# none, so the 0.13.0 deb showed in Software as the bare package — "termina", a generic icon,
# Unknown License, the summary for a description — while the listing it shipped went unused. So the
# finished deb is unpacked and rebuilt with the metainfo and the icon as files of its own.
own_in_deb() {
    local deb="$1" root size
    root="$(mktemp -d)"
    dpkg-deb -R "$deb" "$root"
    install -D -m 644 "$image/lib/$metainfo" "$root/usr/share/metainfo/$metainfo"
    install -D -m 644 "$icon" "$root/usr/share/icons/hicolor/512x512/apps/$app_id.png"
    # Where Debian policy and the package tools look for it; jpackage's is under /opt.
    install -D -m 644 "$resources/copyright" "$root/usr/share/doc/termina/copyright"
    # The control file's sums and size describe the contents and are rewritten to match them.
    (cd "$root" && find . -path ./DEBIAN -prune -o -type f -printf '%P\0' | LC_ALL=C sort -z |
        xargs -0 md5sum) > "$root/DEBIAN/md5sums"
    size=$(du -sk --exclude=DEBIAN "$root" | cut -f1)
    sed -i "s/^Installed-Size: .*/Installed-Size: $size/" "$root/DEBIAN/control"
    dpkg-deb --root-owner-group --build "$root" "$deb"
    rm -rf "$root"
}

# The app image as it is, for a system without dpkg or rpm, or a user without root. The top
# directory carries the version so two can be unpacked side by side.
build_tarball() {
    local out="$dest/termina-$version-linux.tar.gz"
    echo "[installers] building tar.gz"
    tar --create --gzip --file "$out" --sort=name --owner=0 --group=0 --numeric-owner --mode=go-w \
        --transform "flags=rh;s,^$(basename "$image"),termina-$version," \
        -C "$(dirname "$image")" "$(basename "$image")"
    echo "[installers] tar.gz OK"
}

# appimagetool and the runtime it prepends, pinned by version and hash: each is a binary run on, or
# shipped inside, every AppImage. Kept in the user's cache, checked on every use.
appimage_tool() {
    local name="$1" url="$2" sum="$3" cache file
    cache="${XDG_CACHE_HOME:-$HOME/.cache}/termina-build"
    file="$cache/$sum-$name"
    mkdir -p "$cache"
    if ! echo "$sum  $file" | sha256sum --check --status 2>/dev/null; then
        curl --fail --silent --show-error --location --output "$file.part" "$url"
        echo "$sum  $file.part" | sha256sum --check --quiet ||
            { echo "::error::[installers] $name from $url does not match its pinned hash" >&2; return 1; }
        mv "$file.part" "$file"
    fi
    chmod +x "$file"
    echo "$file"
}

# One file that runs anywhere, carrying the same image as the packages. The layout is AppImage's:
# AppRun, a desktop entry and its icon at the top, and the image under usr/lib. The entry is named
# after the app id rather than jpackage's termina-Termina: appimagetool finds the metainfo by the
# entry's name and otherwise skips validating it, with only a warning. The metainfo's launchable is
# rewritten to match.
build_appimage() {
    local arch tool runtime appdir entry
    echo "[installers] building AppImage"
    arch="$(uname -m)"
    case "$arch" in
        x86_64)
            tool=$(appimage_tool appimagetool-1.9.1-x86_64.AppImage \
                https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage \
                ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0)
            runtime=$(appimage_tool runtime-20251108-x86_64 \
                https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64 \
                2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d) ;;
        aarch64)
            tool=$(appimage_tool appimagetool-1.9.1-aarch64.AppImage \
                https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-aarch64.AppImage \
                f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158)
            runtime=$(appimage_tool runtime-20251108-aarch64 \
                https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-aarch64 \
                00cbdfcf917cc6c0ff6d3347d59e0ca1f7f45a6df1a428a0d6d8a78664d87444) ;;
        *) echo "::error::[installers] no AppImage tools for $arch" >&2; return 1 ;;
    esac

    appdir="$(mktemp -d)/Termina.AppDir"
    mkdir -p "$appdir/usr/lib" "$appdir/usr/share/applications"
    cp -a "$image" "$appdir/usr/lib/termina"
    install -m 755 "$linux/AppRun" "$appdir/AppRun"
    mkdir -p "$appdir/usr/share/metainfo"
    # And under AppStream's older .appdata.xml name, the only one appimagetool 1.9 looks for.
    sed "s|>termina-Termina.desktop<|>$app_id.desktop<|" "$linux/$metainfo" \
        > "$appdir/usr/share/metainfo/$app_id.appdata.xml"
    install -D -m 644 "$icon" "$appdir/usr/share/icons/hicolor/512x512/apps/$app_id.png"
    cp "$icon" "$appdir/$app_id.png"
    ln -s "$app_id.png" "$appdir/.DirIcon"
    # jpackage's tokens, filled in as it fills them for the packages. No TryExec: an AppImage is not
    # on PATH, and an entry whose TryExec is missing is hidden.
    entry="$appdir/usr/share/applications/$app_id.desktop"
    sed -e 's/APPLICATION_NAME/Termina/' -e "s/APPLICATION_DESCRIPTION/$description/" \
        -e '/^TryExec=/d' -e 's/^Exec=APPLICATION_LAUNCHER$/Exec=termina/' \
        "$linux/Termina.desktop" > "$entry"
    cp "$entry" "$appdir/"

    # Extracted rather than mounted: a container or a CI runner may have no FUSE.
    if APPIMAGE_EXTRACT_AND_RUN=1 ARCH="$arch" "$tool" --runtime-file "$runtime" \
        "$appdir" "$dest/Termina-$version.AppImage"; then
        echo "[installers] AppImage OK"
    else
        echo "::error::[installers] AppImage failed" >&2
        return 1
    fi
    rm -rf "$(dirname "$appdir")"
}

# --license-file is Linux only. On the DMG and the MSI it becomes an agreement the user has to click
# through before installing, which a permissive licence does not ask for; there the notices travel
# inside the app image (scripts/add-notices.sh), as they do in the Linux packages too.
case "$(uname -s)" in
    Darwin)
        build dmg --mac-package-name Termina
        # The disk image gets its own Developer ID signature when the app has one (see
        # sign-mac-app.sh), so Gatekeeper can check it before mounting; release.yml then notarizes it.
        if [ -n "${TERMINA_MAC_SIGN_IDENTITY:-}" ]; then
            sign=(codesign --force --timestamp)
            [ -n "${TERMINA_MAC_KEYCHAIN:-}" ] && sign+=(--keychain "$TERMINA_MAC_KEYCHAIN")
            for dmg in "$dest"/*.dmg; do
                "${sign[@]}" --sign "$TERMINA_MAC_SIGN_IDENTITY" "$dmg"
                codesign --verify --verbose=2 "$dmg"
            done
        fi
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
        # templates with `termina` on PATH and the x-terminal-emulator alternative added, and in the
        # spec the AppStream listing and the icon (packaging/linux/NOTES.md). jpackage installs only
        # under /opt; these and own_in_deb are how anything reaches /usr.
        linux="$(dirname "$0")/../packaging/linux"
        # Every format installs the icon under this name and the menu entry asks for it by name:
        # software centres look icons up in the theme, not at a path under /opt.
        app_id=io.github.adriandeleon.Termina
        metainfo="$app_id.metainfo.xml"
        [ -n "$icon" ] && [ -f "$icon" ] || { echo "::error::[installers] no icon at '$icon'" >&2; exit 1; }
        resources="$(mktemp -d)"
        cp "$linux/"*.desktop "$linux/postinst" "$linux/prerm" "$linux/termina.spec" "$resources/"
        cp "$icon" "$resources/Termina.png"
        # Into the image's lib/, next to the desktop entry and icon the bundlers put there; the spec
        # installs it from there into /usr/share/metainfo, and the tarball carries it as it is.
        cp "$linux/$metainfo" "$image/lib/"

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
        own_in_deb "$(ls "$dest"/termina_*.deb)"
        build rpm \
            --linux-package-name termina \
            --linux-rpm-license-type MIT \
            --linux-menu-group "System;TerminalEmulator" \
            --linux-shortcut \
            --linux-package-deps "$rpm_deps" \
            --license-file "$license" \
            --resource-dir "$resources"
        build_tarball
        build_appimage
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
