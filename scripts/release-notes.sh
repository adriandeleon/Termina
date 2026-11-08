#!/usr/bin/env bash
#
# The release notes for a version: how to install it, then that version's section of CHANGELOG.md
# (DOCS-05, REL-04, Pauta 2.0.2). What a user reads on the release page is what was written for
# users, not a list of commit subjects.
#
#   scripts/release-notes.sh <version> [--unreleased-ok]
#
# Fails when CHANGELOG.md has no "## [<version>]" section: a tag must not publish a release whose
# notes nobody wrote. With --unreleased-ok (a manual dry run, whose version has no section yet) the
# "## [Unreleased]" section stands in.
set -euo pipefail

version="${1:?version}"
fallback="${2:-}"
cd "$(dirname "$0")/.."

section() {
    # Everything after the heading line, up to the next version heading or the link references.
    awk -v head="$1" '
        index($0, head) == 1 { found = 1; next }
        found && (/^## \[/ || /^\[[^]]+\]: /) { exit }
        found { print }
    ' CHANGELOG.md
}

notes="$(section "## [$version]")"
title="$version"
if [ -z "$(printf '%s' "$notes" | tr -d '[:space:]')" ]; then
    if [ "$fallback" != "--unreleased-ok" ]; then
        echo "CHANGELOG.md has no section for $version; write it before tagging." >&2
        exit 1
    fi
    notes="$(section "## [Unreleased]")"
    title="$version (unreleased)"
fi

cat .github/release-notes-install.md
printf '## What is new in %s\n' "$title"
printf '%s\n' "$notes"
