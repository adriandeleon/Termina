#!/usr/bin/env bash
# Signs the Linux downloads in each directory with the release's OpenPGP key, in place, and writes a
# detached signature beside every one. Run by the release on the finished packages, and by CI with a
# throw-away key so a broken signing step is found on a push rather than on release day.
#
# Two kinds of signature, because each format's own tools check a different thing:
#   .rpm       embedded (rpmsign): `rpm -K`, and dnf or zypper once the key is imported.
#   .deb       embedded, as debsigs' `_gpgorigin` member, which debsig-verify checks. apt and dpkg
#              check nothing about a .deb that was downloaded rather than fetched from a repository.
#   all four   a detached, armoured `<file>.asc`: `gpg --verify <file>.asc <file>`. The only
#              signature the AppImage and the tarball carry.
#
# The AppImage has no embedded signature because appimagetool can sign only while it builds, which
# would put the key on the build runners — and those run whatever Maven resolves from a repository
# outside Central. Signing happens in a job of its own that runs nothing of ours but this.
#
# The key comes from the environment: TERMINA_GPG_KEY, an ASCII-armoured secret key, and
# TERMINA_GPG_PASSPHRASE if it has one. Its public half is written to <public-key-out>. The release
# sets TERMINA_GPG_EXPECTED_KEY to the committed packaging/linux/signing-key.asc, and the key must be
# that one: a release signed by any other key would verify against the key published beside it and
# against nothing else.
#
# Needs gpg, rpmsign and debsigs.
#
# Usage: sign-linux-packages.sh <public-key-out> <dir>...
set -euo pipefail

public_out="${1:?public key output required}"
shift
[ $# -gt 0 ] || { echo "usage: $0 <public-key-out> <dir>..." >&2; exit 2; }
[ -n "${TERMINA_GPG_KEY:-}" ] || { echo "::error::TERMINA_GPG_KEY is not set"; exit 1; }

GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
trap 'gpgconf --kill gpg-agent 2>/dev/null || true; rm -rf "$GNUPGHOME"' EXIT

# Every gpg below, including the ones rpmsign and debsigs start, reads this. Loopback lets the
# passphrase come from the file rather than from a pinentry no runner can answer.
{
    echo "batch"
    echo "no-tty"
    echo "pinentry-mode loopback"
} > "$GNUPGHOME/gpg.conf"
if [ -n "${TERMINA_GPG_PASSPHRASE:-}" ]; then
    printf '%s' "$TERMINA_GPG_PASSPHRASE" > "$GNUPGHOME/passphrase"
    echo "passphrase-file $GNUPGHOME/passphrase" >> "$GNUPGHOME/gpg.conf"
fi

printf '%s\n' "$TERMINA_GPG_KEY" | gpg --quiet --import
fpr=$(gpg --with-colons --list-secret-keys | awk -F: '$1 == "fpr" { print $10; exit }')
[ -n "$fpr" ] || { echo "::error::TERMINA_GPG_KEY holds no secret key"; exit 1; }
echo "Signing with $fpr"

expected="${TERMINA_GPG_EXPECTED_KEY:-}"
if [ -n "$expected" ]; then
    [ -f "$expected" ] || { echo "::error::no public key at $expected to check TERMINA_GPG_KEY against"; exit 1; }
    want=$(gpg --with-colons --import-options show-only --import "$expected" |
        awk -F: '$1 == "fpr" { print $10; exit }')
    [ "$want" = "$fpr" ] || { echo "::error::TERMINA_GPG_KEY is $fpr, but $expected is $want"; exit 1; }
fi
gpg --armor --export "$fpr" > "$public_out"

shopt -s nullglob
signed=0
for dir in "$@"; do
    for rpm in "$dir"/*.rpm; do
        # rpm before 4.19 names the key _gpg_name, from 4.19 _openpgp_sign_id; both are given.
        rpmsign --addsign --define "_gpg_path $GNUPGHOME" --define "_gpg_name $fpr" \
            --define "_openpgp_sign_id $fpr" "$rpm"
    done
    for deb in "$dir"/*.deb; do
        debsigs --sign=origin -k "$fpr" "$deb"
    done
    # After the embedded signatures, which change the files they are added to.
    for file in "$dir"/*.deb "$dir"/*.rpm "$dir"/*.AppImage "$dir"/*.tar.gz; do
        gpg --armor --detach-sign --local-user "$fpr" --output "$file.asc" "$file"
        echo "signed $(basename "$file")"
        signed=$((signed + 1))
    done
done
[ "$signed" -gt 0 ] || { echo "::error::nothing to sign in $*"; exit 1; }
