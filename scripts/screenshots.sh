#!/usr/bin/env bash
#
# Retakes the README's hero, in its dark and its light form, at 2x (DOCS-18, Pauta 6.0.1).
#
# The pictures are the real window, drawn by the capture hook on JavaFX's headless platform: no
# window opens on the desktop, and nothing of the desktop is in them. Nothing of your shell's set-up
# is either: the shell is bash with a start-up file written here, so the same commit gives the same
# picture on any machine, the dates in the listing aside.
#
#   scripts/screenshots.sh     # writes docs/screenshot.png (dark) and docs/screenshot-light.png
#
# Run it after any change to how the window looks. It needs bash and, for the file-type icons, eza;
# without eza the listing is plain `ls -l`. TERMINA_DEV_RUN names another launcher than
# scripts/dev-run.sh, one that takes the same -D options.
set -euo pipefail

cd "$(dirname "$0")/.."
root="$PWD"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if command -v eza > /dev/null; then
  listing='eza -l --icons=always --color=always --group-directories-first --no-user'
else
  listing='ls -l --color=always --group-directories-first'
fi

# What the shell in each tab runs as it starts. The first tab stays at home with a bare prompt;
# the second, the one in front, goes to this folder and lists it. The folder is reached by a link
# called Termina and the tab is titled the same, so a checkout under another name (a worktree)
# gives the same picture.
ln -s "$root" "$work/Termina"
{
  printf '%s\n' "PS1='\\[\\e[1;34m\\]\\W\\[\\e[0m\\] \\\$ '"
  printf 'if mkdir %q 2> /dev/null; then\n' "$work/first-tab"
  printf '%s\n' '  cd' 'else'
  printf '  cd %q\n' "$work/Termina"
  printf '%s\n' "  printf '\\033]0;Termina\\007'" '  clear' "  $listing" 'fi'
} > "$work/rc"

# The shell setting is a path, so the start-up file goes in through a launcher of one line.
{
  printf '%s\n' '#!/bin/sh'
  printf 'exec bash --rcfile %q -i\n' "$work/rc"
} > "$work/shell"
chmod +x "$work/shell"

# One picture: a configuration folder of its own, holding only the shell to start and no update
# check, and the theme switched to by the capture hook once the window exists.
shot() {
  local theme="$1" out="$2" config="$work/config-$1"
  mkdir -p "$config"
  rm -rf "$work/first-tab" # each picture's first tab is its own
  printf '{\n  "schemaVersion" : 2,\n  "settings" : {\n    "terminal.shell" : "%s",\n    "updates.check" : "false"\n  }\n}\n' \
    "$work/shell" > "$config/settings.json"
  env -u DISPLAY -u WAYLAND_DISPLAY TERMINA_CONFIG_DIR="$config" \
    "${TERMINA_DEV_RUN:-scripts/dev-run.sh}" \
    -Dglass.platform=Headless -Dprism.order=sw \
    -Dtermina.capture="$out" -Dtermina.captureScale=2 \
    -Dtermina.captureTheme="$theme" -Dtermina.captureTabs=1
  [ -s "$out" ] || {
    echo "screenshots.sh: no picture was written for $theme" >&2
    exit 1
  }
  echo "wrote $out"
}

shot editora-dark "$root/docs/screenshot.png"
shot editora-light "$root/docs/screenshot-light.png"
