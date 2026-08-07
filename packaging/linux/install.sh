#!/usr/bin/env bash
# Installs Termina from the extracted archive, and registers it with the desktop.
#
# A terminal that is not in the application menu is a terminal you can only start from another
# terminal, which rather defeats it. This copies the image into place, puts `termina` on PATH,
# installs the launcher entry and icon, and — on Debian and derivatives — offers Termina as an
# x-terminal-emulator alternative so it can be made the system default.
#
#   ./install.sh              install for this user (~/.local)
#   sudo ./install.sh         install for everyone (/opt)
#   ./install.sh --uninstall  remove whichever of the two this is
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
image="$here/Termina"
uninstall=false
prefix=""

while [ $# -gt 0 ]; do
    case "$1" in
        --uninstall) uninstall=true; shift ;;
        --prefix) prefix="$2"; shift 2 ;;
        -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

# Root installs system-wide, a normal user installs into their own home. Deciding by id rather than
# asking means `sudo ./install.sh` does the obvious thing.
if [ -n "$prefix" ]; then
    root="$prefix"; bindir="$prefix/bin"; apps="$prefix/share/applications"; icons="$prefix/share/icons/hicolor"
elif [ "$(id -u)" = "0" ]; then
    root="/opt/termina"; bindir="/usr/local/bin"; apps="/usr/share/applications"; icons="/usr/share/icons/hicolor"
else
    root="$HOME/.local/share/termina"; bindir="$HOME/.local/bin"; apps="$HOME/.local/share/applications"; icons="$HOME/.local/share/icons/hicolor"
fi

desktop="$apps/termina.desktop"
icon="$icons/256x256/apps/termina.png"

if [ "$uninstall" = true ]; then
    rm -rf "$root"
    rm -f "$bindir/termina" "$desktop" "$icon"
    if [ "$(id -u)" = "0" ] && command -v update-alternatives >/dev/null 2>&1; then
        update-alternatives --remove x-terminal-emulator "$root/bin/Termina" 2>/dev/null || true
    fi
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$apps" 2>/dev/null || true
    fi
    echo "Termina removed."
    exit 0
fi

[ -d "$image" ] || { echo "no Termina image beside this script (expected $image)" >&2; exit 1; }

mkdir -p "$root" "$bindir" "$apps" "$(dirname "$icon")"
# -a, so the launcher keeps its executable bit and the runtime its symlinks; a jlink runtime dedups
# its licence files as symlinks, and turning those into copies bloats the install for nothing.
cp -a "$image/." "$root/"
ln -sf "$root/bin/Termina" "$bindir/termina"

# jpackage puts the icon in the image; fall back to the one shipped beside this script.
if [ -f "$root/lib/Termina.png" ]; then
    cp -f "$root/lib/Termina.png" "$icon"
elif [ -f "$here/termina.png" ]; then
    cp -f "$here/termina.png" "$icon"
fi

# Exec must be an absolute path when bindir is not on PATH — which ~/.local/bin often is not, and a
# menu entry pointing at a command the session cannot resolve simply does nothing when clicked.
sed "s|^Exec=termina$|Exec=$root/bin/Termina|" "$here/termina.desktop" > "$desktop"
chmod 644 "$desktop"

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$apps" 2>/dev/null || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -f -t "$icons" 2>/dev/null || true
fi

# Debian's mechanism for "which terminal does the system use". Priority 40 is below the usual
# defaults, so installing Termina does not silently take over; `update-alternatives --config
# x-terminal-emulator` is how someone chooses it.
if [ "$(id -u)" = "0" ] && command -v update-alternatives >/dev/null 2>&1; then
    update-alternatives --install /usr/bin/x-terminal-emulator x-terminal-emulator "$root/bin/Termina" 40 \
        >/dev/null 2>&1 || true
fi

echo "Termina installed to $root"
echo "  command: $bindir/termina"
case ":$PATH:" in
    *":$bindir:"*) ;;
    *) echo "  note: $bindir is not on your PATH" ;;
esac
