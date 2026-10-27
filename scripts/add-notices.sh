#!/usr/bin/env bash
# Copies the licence notices into a finished application image, beside the application's jars.
#
# Every installed copy has to carry them: the MIT licence asks for its notice to travel with the
# software, and the bundled fonts, JavaFX and the runtime each ask the same of theirs. The jar has
# them too (META-INF), but nobody reads a jar; these are the files a user, a packager or a licence
# scanner finds.
#
# Into the app directory rather than the image root, and before AOT training: on macOS everything
# outside Contents/'s standard folders is "unsealed contents" that codesign refuses, and the app
# directory is where termina.aot already goes, so the re-sign after training seals both.
#
# Usage: add-notices.sh <app-image-dir> <file>...
set -euo pipefail

image="${1:?app image directory required}"
shift

# Same layout table as aot-train.sh; Linux is the odd one out.
case "$(uname -s)" in
    Darwin) appdir="$image/Contents/app" ;;
    Linux)  appdir="$image/lib/app" ;;
    *)      appdir="$image/app" ;;
esac

# A missing image is a failed jpackage, which has already said so; a missing notice is ours.
[ -d "$appdir" ] || { echo "[notices] no app directory at $appdir" >&2; exit 1; }
for file in "$@"; do
    [ -f "$file" ] || { echo "[notices] missing $file" >&2; exit 1; }
    cp "$file" "$appdir/"
    echo "[notices] $(basename "$file") -> $appdir"
done
