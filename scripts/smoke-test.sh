#!/usr/bin/env bash
# Launches a packaged Termina and checks that a shell actually ran in it.
#
# Text on screen proves the whole chain — JVM, JavaFX, a real pseudo-terminal, a shell, and the
# emulator parsing what it wrote — where a screenshot proves only that a window appeared. Run by CI
# on every push and by the release on every artefact it publishes.
#
# The check is a line of the captured screen that *is* the user name. It used to be "the log
# contains the user name anywhere", which the prompt (runner@host) and the JVM's own "Picked up
# JAVA_TOOL_OPTIONS: …/home/runner/…" line both satisfy without a shell ever running a command.
#
# Usage: smoke-test.sh <launcher> <work-dir>
set -euo pipefail

launcher="${1:?launcher required}"
work="${2:?work directory required}"
[ -x "$launcher" ] || { echo "::error::no launcher at $launcher"; exit 1; }
mkdir -p "$work"
log="$work/smoke.log"

# whoami exists on all three platforms, takes no arguments — JAVA_TOOL_OPTIONS cannot carry a space
# inside a -D value — and prints something this script can predict. On Windows the shell's whoami
# answers DOMAIN\user where Git Bash's answers user, hence the second form in the match below.
me=$(whoami)
echo "expecting the shell to print: $me"

export JAVA_TOOL_OPTIONS="-Dtermina.capture=$work/smoke.png -Dtermina.captureCommand=whoami -Dtermina.captureScreenText=true -Dtermina.captureAfterCommandMs=4000"
export TERMINA_CONFIG_DIR="$work/smoke-config"

if [ "$(uname -s)" = "Linux" ] && [ -z "${DISPLAY:-}" ]; then
    command -v xvfb-run >/dev/null || { echo "::error::no display and no xvfb-run"; exit 1; }
    xvfb-run -a "$launcher" > "$log" 2>&1 || true
else
    "$launcher" > "$log" 2>&1 || true
fi

echo "--- what the terminal showed ---"
sed -n '/\[capture\] screen<<</,/\[capture\] >>>/p' "$log" || true

[ -s "$work/smoke.png" ] || { echo "::error::no screenshot — the window never rendered"; exit 1; }
grep -q "terminals=1" "$log" || { echo "::error::no terminal was created"; exit 1; }

# The command being on screen only proves it was typed; a line that is the answer proves a shell ran
# it. Only lines inside the screen block count, compared whole (ignoring case and trailing blanks
# and carriage returns), or ending in \user for Windows' DOMAIN\user.
if ! awk -v me="$me" '
    /\[capture\] screen<<</ { inside = 1; next }
    /\[capture\] >>>/       { inside = 0; next }
    inside {
        line = tolower($0); sub(/[ \t\r]+$/, "", line)
        m = tolower(me)
        if (line == m) found = 1
        if (length(line) > length(m) && substr(line, length(line) - length(m)) == "\\" m) found = 1
    }
    END { exit found ? 0 : 1 }' "$log"; then
    echo "::error::the shell did not run the command — no screen line reads $me"
    exit 1
fi
echo "smoke test: a shell ran and printed in the packaged app"
