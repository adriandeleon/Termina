#!/usr/bin/env bash
# Records an AOT cache for the packaged application, into the image it was built from.
#
# JDK 25's ahead-of-time cache archives the classes a run actually loads, so the next start skips
# most of the loading and verifying. For a terminal that is the number that matters: it is opened
# dozens of times a day, and nearly all of its startup is class loading.
#
# The training run must render a window. The bulk of what is worth archiving is JavaFX scene,
# control and CSS machinery, none of which is touched until something paints — so a headless
# training run archives almost nothing while appearing to succeed.
#
# Failure-tolerant by design: the launcher runs under AOTMode=auto, which warns and continues when
# the cache is missing or stale. A machine with no display produces no cache and a working app.
#
# Usage: aot-train.sh <app-image-dir> <cache-file-relative-to-app-dir>
set -uo pipefail

image="${1:?app image directory required}"
cache_name="${2:-termina.aot}"

case "$(uname -s)" in
    Darwin)
        java="$image/Contents/runtime/Contents/Home/bin/java"
        appdir="$image/Contents/app"
        ;;
    *)
        java="$image/runtime/bin/java"
        appdir="$image/app"
        [ -x "$java" ] || java="$image/runtime/bin/java.exe"
        ;;
esac

if [ ! -x "$java" ]; then
    echo "[aot] no runtime java at $java — skipping" >&2
    exit 0
fi
mkdir -p "$appdir"
cache="$appdir/$cache_name"

# es2/sw, never the platform default. CI runners present virtualised GPUs, and on macOS the Metal
# pipeline aborts on one — an Obj-C exception, which is a process abort that Prism's own fallback
# chain cannot catch. Editora shipped eight releases with an untrained macOS arm64 build for exactly
# this reason, and it never reproduced on real hardware.
opts=(-XX:AOTCacheOutput="$cache"
      -Dprism.order=es2,sw
      -Dtermina.aotTrain=1
      --enable-native-access=javafx.graphics,com.sun.jna
      -m com.termina/com.termina.App)

echo "[aot] training -> $cache"
if [ "$(uname -s)" = "Linux" ] && [ -z "${DISPLAY:-}" ] && command -v xvfb-run >/dev/null 2>&1; then
    xvfb-run -a "$java" "${opts[@]}" >/dev/null 2>&1
    status=$?
else
    "$java" "${opts[@]}" >/dev/null 2>&1
    status=$?
fi

if [ -s "$cache" ]; then
    echo "[aot] cache written: $(du -h "$cache" | cut -f1) (trainer exit $status)"
    exit 0
fi

# Reported, not swallowed. A missing cache is a silent regression of roughly a quarter of startup,
# and the only symptom is that the application feels slower than it did.
echo "::warning::[aot] no cache produced (trainer exit $status); the app will start uncached" >&2
if [ "${TERMINA_REQUIRE_AOT:-0}" = "1" ]; then
    echo "[aot] TERMINA_REQUIRE_AOT=1 — failing the build" >&2
    exit 1
fi
exit 0
