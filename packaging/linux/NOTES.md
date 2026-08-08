# The Linux menu entry

`Termina.desktop` replaces the one jpackage generates, to add `StartupWMClass` — the class JavaFX
derives from the module's main class, without which a running window cannot be matched to its
launcher and the dock shows a second generic icon beside it.

Two things about this file are easy to get wrong, and both fail quietly.

**The name must match the launcher exactly.** jpackage looks for `Termina.desktop`; given
`termina.desktop` it ignores the override without a word and ships its own. The first build of these
packages went out that way — valid, installable, and missing `StartupWMClass` entirely.

On macOS and Windows the two spellings are the same file, so a rename between them can appear to
work while git still records the old case. `git ls-files packaging/linux` is the check. (`git rm -f
termina.desktop` will also delete a `Termina.desktop` you have just written.)

**Only some `APPLICATION_*` tokens are substituted.** `APPLICATION_NAME`, `APPLICATION_DESCRIPTION`,
`APPLICATION_LAUNCHER` and `APPLICATION_ICON` are replaced. `APPLICATION_CATEGORIES` and
`APPLICATION_MIMETYPES` are *not* — a package built with those in place ships them literally, which
makes `Categories` invalid and stops the entry being classified as a terminal at all. Both are
written out here instead, which is also why `--linux-menu-group` on the jpackage command line has no
effect on this file.

Comments are deliberately absent from the entry itself: it is installed onto users' machines, and
notes about our build belong here.

CI asserts all of this against a built `.deb` rather than against this file, because every failure
above produces a package that looks fine from the outside.
