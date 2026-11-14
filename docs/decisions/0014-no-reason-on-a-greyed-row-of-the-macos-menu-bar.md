# 0014. A greyed row in the macOS menu bar gives no reason

Recorded: 2026-11-14. A deviation from Pauta 6.0.1's KBD-04, on macOS only.

## Context

KBD-04 asks that a command that cannot run now be shown greyed, with a tooltip that says why, and
not hidden. Termina does that wherever it draws the menu itself: the bar's menus on Windows and
Linux, a tab's menu, the terminal's right-click menu and the Command Palette. A greyed row keeps
its place, and the pointer over it shows the reason the command registry gives (`ui.MenuReasons`).

On macOS the menus are in the menu bar at the top of the screen, which AppKit draws. JavaFX hands
each row to it through `com.sun.glass.ui.MenuItem`, and that class can be told six things: a title,
a callback, a shortcut, an icon, enabled and checked. It has no tooltip. The reason the registry
gives therefore reaches nothing on screen: the row is greyed, and says no more.

What was considered:

- **Setting the tooltip natively.** An `NSMenuItem` does have a `toolTip`. Reaching it means taking
  the native pointer out of JavaFX's private Mac delegate by reflection and sending it an
  Objective-C message through JNA. It rests on internals that change between JavaFX releases, a
  mistake in it is a crash in native code and not an exception, and nothing here can run it:
  Termina's macOS half is tested headless in CI, where there is no menu bar.
- **Putting the reason in the row's title**, "Reopen Closed Tab (nothing to reopen)". The title is
  one of the six things, so it would work. It makes a row change its words and its width as the
  state changes, which no Mac application does, and the widest reason sets the menu's width.
- **Drawing the menu bar in the window on macOS too**, where Termina's own rows have their
  tooltips. A Mac application's menus belong in the screen's menu bar; trading that for a tooltip
  on rows that are greyed is the wrong way round.

## Decision

On macOS, a greyed row of the screen's menu bar has no tooltip. It is greyed and kept in its place,
as the first half of KBD-04 asks; the reason is given elsewhere:

- **The Command Palette** lists every command, the greyed ones with their reason as a tooltip and
  in what a screen reader says for the row.
- **The menus Termina draws on macOS**, a tab's menu and the terminal's right-click menu, show the
  reason under the pointer as on the other systems.

`pauta.json` records it as a deviation that links this file.

## Consequences

- **A Mac user who sees a greyed row in File, Edit, View, Window or Help is not told why there.**
  To find out they open the Command Palette and look the command up. That is the cost, and it is
  on macOS only.
- **Windows and Linux are unaffected**: their bar is Termina's, and its greyed rows say why.
- **The registry still knows every reason**, so nothing has to be written again if the row can
  one day carry it.
- **To be looked at again** when JavaFX lets a system menu item carry a tooltip, or when someone
  with a Mac can build and test the native route.
- **None of this has been seen on a Mac.** That the screen menu bar shows no reason is read from
  what JavaFX's menu item can be told, not from looking at one.
