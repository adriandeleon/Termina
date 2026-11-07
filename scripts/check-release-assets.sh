#!/usr/bin/env bash
#
# Fails when the files gathered for a release are not exactly the ones jreleaser.yml attaches.
#
#   scripts/check-release-assets.sh <version> <artifacts-dir> [jreleaser.yml]
#
# GitHub releases are immutable: a download missing from a published release cannot be added, so
# the check runs before anything is published (REL-07, Pauta 2.0.2). The expected list is read from
# jreleaser.yml rather than written out here a second time, so the two cannot drift: a file the
# configuration names and the build did not produce fails, and so does a file the build produced
# that nothing would attach.
set -euo pipefail

version="${1:?version}"
dir="${2:?artifacts directory}"
config="${3:-jreleaser.yml}"

expected="$(grep -oE "path: 'artifacts/[^']+'" "$config" \
  | sed -e "s|path: 'artifacts/||" -e "s|'\$||" -e "s|{{projectVersion}}|$version|g" \
  | sort -u)"
if [ -z "$expected" ]; then
  echo "::error::no artifacts/ paths found in $config"
  exit 1
fi
actual="$(cd "$dir" && find . -maxdepth 1 -type f | sed 's|^\./||' | sort -u)"

missing="$(comm -23 <(echo "$expected") <(echo "$actual"))"
extra="$(comm -13 <(echo "$expected") <(echo "$actual"))"

status=0
if [ -n "$missing" ]; then
  while IFS= read -r f; do echo "::error::missing from the release: $f"; done <<<"$missing"
  status=1
fi
if [ -n "$extra" ]; then
  while IFS= read -r f; do echo "::error::built but attached by nothing in $config: $f"; done <<<"$extra"
  status=1
fi
if [ "$status" = 0 ]; then
  echo "All $(echo "$expected" | wc -l | tr -d ' ') release files are present, and nothing else."
fi
exit "$status"
