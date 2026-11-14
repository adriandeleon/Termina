#!/usr/bin/env python3
"""Writes the high-contrast control stylesheet (ACC-09, Pauta 6.0.1) from the dark one.

A theme's stylesheet is a block of -color-* tokens and then rules that name only those tokens, so
the high-contrast sheet is the dark sheet with other values for the tokens and nothing else changed.
Run it after any change to editora-dark.css:

    scripts/high-contrast-theme.py     # rewrites src/main/resources/com/termina/styles/editora-contrast.css

ChromeThemeTest fails when the two differ anywhere but in the header and the tokens.
"""
import re
from pathlib import Path

STYLES = Path(__file__).resolve().parent.parent / "src/main/resources/com/termina/styles"

# Black ground, white text and borders, one cyan accent; yellow, green and a light red for the
# states. Every foreground is at least 7:1 on the black (WCAG AAA), the muted ones included.
SCALES = {
    "base": ["#ffffff", "#ffffff", "#ffffff", "#e0e0e0", "#c0c0c0", "#a0a0a0", "#404040", "#202020", "#000000", "#000000"],
    "accent": ["#e8fdff", "#c2f9ff", "#8cf4ff", "#1aebff", "#15c4d6", "#11a1b0", "#0d7f8b", "#095d66", "#063e44", "#031f22"],
    "success": ["#e9ffe9", "#c4fcc4", "#8ef88e", "#3ff23f", "#2fc42f", "#259c25", "#1c781c", "#145614", "#0c370c", "#051a05"],
    "warning": ["#ffffe0", "#ffffb0", "#ffff70", "#ffff00", "#d6d600", "#adad00", "#858500", "#5e5e00", "#3a3a00", "#1c1c00"],
    "danger": ["#ffe9e9", "#ffc8c8", "#ffa0a0", "#ff7070", "#e04848", "#b83232", "#942626", "#6e1b1b", "#481111", "#240808"],
}
TOKENS = {
    "dark": "#000000",
    "light": "#ffffff",
    "fg-default": "#ffffff",
    "fg-muted": "#e0e0e0",
    "fg-subtle": "#c0c0c0",
    "fg-emphasis": "#000000",
    "bg-default": "#000000",
    "bg-overlay": "#000000",
    "bg-subtle": "#1a1a1a",
    "bg-inset": "#000000",
    "border-default": "#ffffff",
    "border-muted": "#c0c0c0",
    "border-subtle": "#808080",
    "shadow-default": "#000000",
    "neutral-emphasis-plus": "#ffffff",
    "neutral-emphasis": "#c0c0c0",
    "neutral-muted": "#606060",
    "neutral-subtle": "#303030",
    "accent-fg": "#1aebff",
    "accent-emphasis": "#1aebff",
    "accent-muted": "rgba(26, 235, 255, 0.4)",
    "accent-subtle": "#063e44",
    "warning-fg": "#ffff00",
    "warning-emphasis": "#ffff00",
    "warning-muted": "rgba(255, 255, 0, 0.4)",
    "warning-subtle": "#3a3a00",
    "success-fg": "#3ff23f",
    "success-emphasis": "#3ff23f",
    "success-muted": "rgba(63, 242, 63, 0.4)",
    "success-subtle": "#0c370c",
    "danger-fg": "#ff7070",
    "danger-emphasis": "#ff7070",
    "danger-muted": "rgba(255, 112, 112, 0.4)",
    "danger-subtle": "#481111",
}
for name, steps in SCALES.items():
    for step, value in enumerate(steps):
        TOKENS[f"{name}-{step}"] = value

HEADER = """/* High Contrast: the dark control theme with its colour tokens replaced (ACC-09, Pauta 6.0.1).
   Black ground, white text and borders, one cyan accent; yellow, green and a light red for the
   states. Written by scripts/high-contrast-theme.py from editora-dark.css: change that file or
   the script, never this one. ChromeThemeTest holds the two to the same rules.

   Structure follows the vendored AtlantaFX community themes: a `.root` palette block of
   `-color-*` tokens, then component rules that reference only those tokens. */
"""


def main():
    dark = (STYLES / "editora-dark.css").read_text(encoding="utf-8")
    end = dark.index("*/\n") + 3
    body = dark[end:]
    seen = set()

    def swap(match):
        name = match.group(2)
        if name not in TOKENS:
            return match.group(0)
        seen.add(name)
        return f"{match.group(1)}-color-{name}: {TOKENS[name]};"

    # Only the first definition of each token, which is the palette block's.
    root_end = body.index("\n}\n")
    block = re.sub(r"^(\s+)-color-([a-z0-9-]+): [^;]+;", swap, body[:root_end], flags=re.M)
    missing = set(TOKENS) - seen
    if missing:
        raise SystemExit(f"tokens the dark sheet no longer defines: {sorted(missing)}")
    (STYLES / "editora-contrast.css").write_text(HEADER + block + body[root_end:], encoding="utf-8")
    print(f"wrote editora-contrast.css: {len(seen)} tokens replaced")


if __name__ == "__main__":
    main()
