#!/usr/bin/env bash
# Puts the true version into the packaged bundle's Info.plist, and re-seals the bundle.
#
# jpackage refuses an --app-version whose first number is zero, but only on macOS, so a 0.x release
# has to be built under a placeholder (0.1.0 -> 1.1.0). Left there, Finder's Get Info, `mdls` and
# System Settings would all report a version the application itself disagrees with.
#
# jpackage ad-hoc-signs the app image, and everything inside it is sealed by that signature — the
# Info.plist, and also the files the build adds afterwards: the notices and the AOT cache, both
# written into Contents/app after jpackage has signed. Any of them makes macOS refuse to launch the
# app as damaged. So the re-sign is unconditional. It used to happen only when the version had to be
# changed, which is every 0.x release and no 1.x one: from 1.0.0 on the plist is already right, the
# script exited early, and the bundle would have shipped with the cache outside its seal. CI's
# unquarantined smoke test does not check the seal, so nothing would have said so.
#
# Usage: fix-mac-bundle-version.sh <app-bundle> <true-version>
set -euo pipefail

app="${1:?app bundle path required}"
version="${2:?version required}"

# Only macOS produces a bundle at all; everywhere else this is a no-op by design.
[ "$(uname -s)" = "Darwin" ] || exit 0
[ -d "$app" ] || exit 0

plist="$app/Contents/Info.plist"
current=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$plist" 2>/dev/null || echo "")
if [ "$current" != "$version" ]; then
    /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $version" "$plist"
    /usr/libexec/PlistBuddy -c "Set :CFBundleVersion $version" "$plist"
    echo "bundle version: $current -> $version"
fi

codesign --force --deep --sign - "$app"
# Checked here rather than trusted: a seal that does not verify is a "damaged" dialog on every
# user's machine, and this is the last step that can still fail the build over it.
codesign --verify --deep --verbose=2 "$app"
echo "bundle re-signed and verified"
