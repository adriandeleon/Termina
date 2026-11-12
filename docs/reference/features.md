# Termina as built

The catalogue the README used to carry: what each part of Termina does, how, and why, with the
gaps that are known. It describes the code as it is; what a user reads is on the website,
[termina-project.dev/docs](https://termina-project.dev/docs/). When this page and the code
disagree, the code wins, and the page is fixed in the same change.

## How it fits together

Three pieces, each doing one thing:

| Layer | What it is | Where |
|---|---|---|
| PTY | Starts the shell on a pseudo-terminal and moves bytes | `com.termina.pty` |
| Emulation | Parses escape sequences into a screen buffer | JediTerm (external) |
| Rendering & input | Paints the buffer, encodes keystrokes | `com.termina.ui` |

`TerminalSession` (`com.termina.term`) wires them together and owns the read loop.

**The emulator is not ours.** VT/xterm compatibility is a very long tail — cursor modes, character
sets, scroll regions, mouse protocols, a hundred CSI sequences — and getting it wrong shows up as
programs subtly misbehaving rather than as anything that looks like a bug in a terminal. JetBrains'
[JediTerm](https://github.com/JetBrains/jediterm) core does that part. It is UI-agnostic: it owns a
`TerminalTextBuffer` and knows nothing about how it gets drawn.

**The PTY is not ours either.** Java has no PTY API, and the platforms disagree fundamentally —
Unix has `forkpty`, Windows has ConPTY (and winpty before it).
[pty4j](https://github.com/JetBrains/pty4j) covers all of them, and ships every platform's native
library in a single jar, so there is no per-OS native build step.

What *is* ours is the JavaFX half: the canvas renderer, the key encoder, the colour resolution, and
the session lifecycle.

### Rendering

`TerminalView` paints `TerminalTextBuffer` onto a `Canvas`.

Repaints are **decoupled from output**. The emulator signals a change per write — thousands a
second when a command floods output — so a change only sets a dirty flag, and one `AnimationTimer`
repaints at most once a frame. Painting per signal would starve the UI thread during any large
output.

Drawing is per **style run**, not per character: `processScreenLines` hands back each run of
identically-styled text and each becomes one `fillText`. Runs containing non-ASCII fall back to
per-glyph drawing, because a double-width character (CJK, emoji) occupies two cells and its
natural advance would otherwise push the rest of the line off the grid.

### Input

Special keys are encoded by **JediTerm** via `TerminalStarter.getCode` — but not all of them: its
encoder has no entry for **Tab or Escape**. Both are control characters, so the `KEY_TYPED` path
discards them too, and without a fallback they reach the shell by no route at all. Worse, returning
null leaves the event unconsumed, and JavaFX then treats Tab as focus traversal — focus leaves the
terminal and everything typed afterwards goes somewhere else, which presents as the terminal
freezing. `KeyEncoding.literalFallback` covers them, with `ESC [ Z` for Shift+Tab.

The invariant is that **a key we claim to handle must never return null**, since null means
unconsumed and unconsumed means JavaFX may act on it. That is asserted rather than assumed.

Otherwise, special keys are encoded by JediTerm via `TerminalStarter.getCode`. The right bytes
depend on emulator state: in application-cursor-key mode Up is `ESC O A`, otherwise `ESC [ A`.
Hard-coding either breaks half the programs a terminal exists to run. Printable characters go
through `KEY_TYPED` instead, which is the only event that reports the *composed* character — what
makes dead keys, AltGr layouts, and IME input work.

### Selection

Drag to select, double-click for a word, triple-click for a line. Copy is `Cmd+C` / `Ctrl+Shift+C`
(plain `Ctrl+C` is SIGINT and belongs to the shell).

Selection coordinates are the same axis as the scroll origin: row 0 is the top of the live screen
and negative rows reach into history. Extracting the text is JediTerm's `SelectionUtil`, which knows
a wrapped line is one logical line and must not gain a newline in the middle.

Two things follow from output arriving while a selection exists. As lines age off the screen into
history, both the selection and a scrolled-back viewport are shifted to stay on their text —
otherwise a selection made a moment ago comes to refer to different characters, and copying it
produces the wrong thing silently. And `getNextSeparator` returns the last character *of* a word
while a selection's end is exclusive, so word selection adds one; without it double-clicking
`india` copies `indi`.

The highlight is a translucent wash painted *over* the text. Painting it behind would mean threading
selection state through every style run and splitting runs at its edges; the wash keeps the run loop
untouched and stays legible over any of 16 million possible foreground colours.

**Past the edge.** Dragging above or below the pane scrolls the scrollback and keeps extending the
selection, faster the further out the pointer is, and keeps going with the pointer held still — no
event arrives then, so it runs on the painter's frames rather than on mouse events. The drag's
anchor moves with its text as output scrolls, as the selection does; otherwise a drag held while a
build runs extends from whatever has since scrolled under where it began.

**Blocks.** `Option`+drag on macOS, `Alt`+drag elsewhere — `Ctrl+Alt`+drag where the window manager
takes Alt+drag to move windows, as GNOME and KDE do — selects a rectangle: the same columns on every
row, copied one row per line whether or not a row wrapped. A block is measured in columns, not slots,
so it stays straight over Nerd Font icons and wide glyphs.

**It survives typing.** A keystroke no longer clears the selection. What does is the text under it
changing — a line editor redrawing the prompt — checked on the next frame against the text it was
made with, since copying a highlight over redrawn text would produce something nobody selected.

**Copy on select** is a setting on Terminal → Input, off by default. On Linux every selection is
also the **PRIMARY** selection, and a middle-click pastes PRIMARY, as on any X11 terminal. JavaFX
cannot reach PRIMARY at all, so both go through `wl-paste`/`wl-copy` under Wayland or `xclip`/`xsel`
under X11; without one of them, and on macOS and Windows, middle-click pastes the clipboard.

### Mouse reporting

Clicks, drags and the wheel are offered to the program running in the terminal before any local
gesture runs, so vim positions its cursor and htop responds to clicks. Only the *mapping* from
JavaFX events to button codes is ours — JediTerm encodes the escape sequences, because there are
four incompatible wire formats (X10, UTF-8, URXVT, SGR) and the active one is chosen by the running
program.

The mapping mirrors JediTerm's own AWT adapter, which is the implementation its encoder was written
against. Two parts look like bugs and are not: the **motion flag is never set here** (the encoder
derives it from the event type, and setting it twice reports a drag as a different button), and the
**scroll codes read backwards** — `SCROLLDOWN` is sent when the wheel turns up, because the names
describe which way the content moves.

The menu closes on Escape, on a click anywhere in the terminal, or on a click outside the window.
Escape needs handling explicitly: the terminal's key filter runs before the menu would see the key,
and Escape now encodes to a real byte, so without it the menu would stay open and the shell would
receive an ESC nobody asked for.

**Shift bypasses reporting.** That is the xterm convention, and it is the only way to select text
out of a program that has grabbed the mouse — without it htop's output cannot be copied.

On the alternate screen a wheel scroll falls back to arrow keys, so `less` and `man` scroll with the
wheel even though neither ever enables mouse reporting. Scroll magnitude is capped, or one inertial
trackpad fling sends hundreds of keypresses to the shell.

### Tabs, windows and the menu bar

`TerminalWindow` is one window: a menu bar over a tab strip, each tab a `PaneGroup` of one or more
`TerminalView`s, each with its own shell (see [Split panes](#split-panes)). `WindowManager` owns the set of windows and what they share —
the settings, the single preferences window, and the theme. Sessions are never shared.

**Tab disposal is driven off the tab list, not the close button.** A tab owns a PTY process, two
pump threads and an emulator thread; a removal path that skips disposal leaks all of it and nothing
on screen looks wrong. Listening to the list covers every path at once — the close button, Close
Tab, the shell exiting, and the window closing — and every pane of a removed tab goes with it.
Verified by counting child processes rather than by reading the code: three tabs, close two, one
shell left; three panes, close two, likewise.

**The tab strip hides itself while only one tab is open** (on by default, matching iTerm2, GNOME
Terminal and Windows Terminal; Settings → Appearance → Tabs). JavaFX has no property for this, and
`visibility: hidden` alone leaves the row's height behind as a blank band — min, pref *and* max
height have to be pinned to zero and the padding cleared with them, or the skin's own padding
survives as a few stubborn pixels. Collapsing reclaims the row for the terminal, so the shell gains
a line and gets resized accordingly.

**Menu items and key bindings come from one value.** `MenuAction` holds the label, the accelerator
and the action together, so the menu cannot advertise a shortcut that nothing implements. The
binding is a **scene-level event filter**, not the menu's accelerator: JavaFX fires accelerators
only after an event has bubbled unconsumed, and `TerminalView` consumes `Ctrl+<letter>` first to
encode it as a control byte. Filters run in the capturing phase, ahead of that.

Off macOS an application chord is `Ctrl+Shift+key`, never `Ctrl+key` — the plain form belongs to the
shell (Ctrl+T is readline's transpose, Ctrl+W deletes a word, Ctrl+C is SIGINT). That rule has its
own tests. Whether the shortcut modifier *is* Ctrl or Cmd is JavaFX's decision, not ours, and the
tests deliberately do not assert it.

### Hiding the menu bar

Settings → Appearance → Window, or `Cmd/Ctrl+Shift+M`. **No effect on macOS**, where the menus
belong to the screen menu bar and there is nothing in the window to hide — the settings row is
disabled there and says so, rather than being a switch that silently does nothing.

Hiding it strands no commands: the key bindings are a scene-level filter independent of the menu,
and Settings is on the right-click menu, which is how it comes back.

Hiding it is done in **code**, not CSS, and the two platforms need different mechanisms. Under a
system menu bar the node must stay live — that is what JavaFX forwards from — so it is only
collapsed to zero height, and nothing paints in the window because the menus are elsewhere.
Everywhere else the bar really is in the window, so hiding it sets `visible` and `managed` to false.

CSS was tried first and was wrong: `visibility: hidden` never applied (the node still reported
`isVisible() == true`), and a zero-height Region does not clip its children, so the menu buttons
carried on painting over the terminal's first rows while occupying no space of their own.

`-Dtermina.forceInWindowMenuBar=true` makes a Mac lay out like the other platforms. Without it the
in-window menu bar — the only place this setting does anything — cannot be seen on the machine this
is developed on.

### The menu bar in the title bar

An option (Appearance → Window, off by default) merges the two rows: the window draws its own
title bar, with the menus at the left, the title in the middle and the window buttons at the right,
and the terminal gains the row the native title bar took. It is JavaFX 27's extended stage style —
`StageStyle.EXTENDED`, with a `HeaderBar` at the top of the scene in place of the system's title
bar. The platform keeps its window buttons (JavaFX draws GNOME- or KDE-styled ones on Linux, and
Windows' own on Windows) and reserves their space; the bar pads for them itself. The empty header
is the drag handle, and so is the title, which is marked draggable on purpose — a title bar you
cannot drag by its title is a trap. The menus stay clickable because content is not draggable by
default.

It applies to windows opened after the change. A stage's style is fixed before it is first shown,
so an existing window keeps the title bar it came up with, and the row says so rather than
appearing to do nothing. Hiding the menu bar still works inside the header: the title and the
buttons stay, since a window with no title bar at all could not be moved.

Not offered on macOS, where the menus are in the screen menu bar and there is nothing in the window
to merge, nor where `Platform.isSupported(EXTENDED_WINDOW)` says no — JavaFX would downgrade the
style to `DECORATED` silently and leave the header as a second row under the native one. In both
cases the checkbox is disabled with the reason on its row. The decision is
`WindowRules.usesHeaderBar`, tested in `MenuBarVisibilityTest`.

One thing a capture cannot show: the window buttons are an overlay JavaFX draws outside the scene
graph, so a scene snapshot has the menus and the title but a blank where the buttons are. The
layout report prints the system insets JavaFX reserved for them instead.

### The tab strip

Tabs tile the full width and shrink as more are added, the way macOS Terminal and GNOME Terminal
lay them out — down to a floor of 60px, past which the strip overflows rather than rendering
slivers with no readable title. Whatever the count, they meet the new-tab button.

An optional **scrollbar** (Appearance → Window, on by default) shows the scrollback and drags it.
It takes a real column of width rather than floating over the text, so the shell is resized when it
appears — a bar drawn over the last column would cover a character of every line. It hides itself on
the alternate screen, where a full-screen program owns the viewport and there is no scrollback to
reach, and it is coloured from the terminal palette rather than the window theme, since the two can
differ.

A **command palette** on Ctrl+Shift+P (Cmd+Shift+P on macOS), as in Windows Terminal and VS Code,
lists every command in the window, found by typing part of its name: "nw" reaches New Window, "ct" Close Tab,
"tab close" finds Close Tab with the words in either order, and "file new" reaches New Tab through
the menu it lives in. It is Editora's palette in shape and behaviour: the query row is led by a chip
naming the chord that opened it, the characters that matched are emboldened so the ranking explains
itself, a line under the list says what the highlighted command does, and a legend names the keys.
Those keys are Emacs's as well as the arrows: Ctrl+N and Ctrl+P move, Ctrl+V and Alt+V page,
Alt+< and Alt+> jump to the ends, Ctrl+G cancels, and the query field takes Ctrl+A, Ctrl+E,
Ctrl+K and the rest of the Emacs editing chords. A command that cannot run right now — Reopen
Closed Tab with nothing to reopen — is listed greyed with the reason as its tooltip rather than
dropped, and the cursor steps over it. The commands are the menu bar's own actions rather than a
parallel list that could disagree with it, so an entry is in the palette because it is in a menu,
and its shortcut is shown because the action already carries one. The colour themes are appended,
since a menu listing every theme is a submenu nobody opens. The chord is Terminal → Keyboard in
Settings: click the field and press another one. A bare key is refused — the shell owns it — and
so is a chord another command already has, since the first binding on a chord is the only one
that runs.

A **debug log** (Advanced → Diagnostics) keeps this session's warnings, errors and uncaught
exceptions, and mirrors them to `termina-session.log` beside the settings. A packaged application
has no console, so without it the one sentence explaining why something failed goes nowhere.

**Six interface languages** — English, Italian, Spanish, French, Portuguese, German — chosen in
Appearance → Language, or followed from the system when left on Automatic. English is both the base
catalogue and the fallback, so a key missing from a translation shows English rather than a gap, and
a test holds the six catalogues to identical key sets: drift here is silent, since nobody testing in
English ever sees it.

**Fonts are bundled**, not borrowed from the machine: five monospace families (JetBrains Mono,
Cascadia Code, Fira Code, IBM Plex Mono, Source Code Pro) and Inter for the interface. A picker over
whatever happens to be installed gives Menlo on macOS, Consolas on Windows and DejaVu on Linux —
three sets of metrics, and no way to describe a setup to anyone else. The bundled families lead the
picker, since they are the only ones guaranteed to be there; every installed monospace face is still
listed after them. They are the same families Editora ships, which is where the themes come from.

**Window size is remembered** across launches, clamped to the screen that is actually present — a
size saved on a 4K display and restored on a laptop would otherwise put the title bar off-screen,
where no mouse can reach it. Only the un-maximized size is stored, so un-maximizing does not restore
to full screen.

The tab-strip glyphs — each tab's close button and the new-tab button — are shaped Regions on one
grid at one size, rather than a shape beside a font character, which never match in weight or
centring. The new-tab button takes the strip's height so its glyph lands on the same line as the
close buttons.

Right-clicking a tab gives New Tab, Close Tab, Close Other Tabs, Close Tabs to the Right, and Move
Tab Left/Right, with the inapplicable ones disabled. It is attached with `Tab.setContextMenu`, so it
acts on the tab that was clicked rather than whichever is selected.

The **window title follows the selected tab**, bound to that terminal's own title property so it
tracks the shell live rather than only at the moment of selection.

The **+** button floats over the right end of the strip rather than being a tab of its own. A
sentinel "+" tab would pollute every count and index in the window — tab disposal, reordering, the
hide-when-single rule, next/previous — each of which would then need to know it is not a real tab.

`TabLayout.tabWidth` is pure and tested. The per-tab chrome constant was **measured**, not guessed:
JavaFX renders a tab at the width set plus about 17px of its own padding, and an earlier value of 38
left a visible gap at seven tabs — arithmetic that tiled correctly against the wrong constant.

### Reordering tabs

Drag a tab, or `Cmd/Ctrl+Shift+Left/Right` from the keyboard. JavaFX has no tab reordering, so the
gesture is built on a `Label` graphic — a `Tab` is not a `Node` and has nowhere to attach drag
handlers.

**A reorder is a remove followed by an add**, which the tab-disposal listener would otherwise read
as "close that session" — dragging a tab would kill the shell being dragged and leave an empty tab
behind, for a gesture meant to change nothing but order. A `reordering` guard suppresses disposal,
and suppresses the close-the-window-when-empty rule with it, since a single-tab window is briefly
empty mid-move.

Where a dragged tab lands is `TabReorder.insertIndex`, kept pure and tested: the drop position is
expressed against the list as it looks *during* the drag, but the insert happens against the list
with the dragged tab already removed. Off by one there moves the tab one place from where it was
dropped, which reads as sloppiness rather than a bug.

### Context menu

Right-click gives Copy, Paste, Select All, Clear Scrollback and Settings. Copy and Paste are
disabled rather than present-and-inert when there is nothing to act on.

Right-click is **contested**, and the rule matters: a terminal wants it for Copy and Paste, while a
mouse-aware TUI (Midnight Commander, some file pickers) wants it as button 2 — and Termina reports
it as such. So while a program has grabbed the mouse it wins, and **Shift forces the menu**, the
same escape hatch that governs selection. Without it there is no way to copy anything, or reach
Settings, from inside htop. The keyboard menu key is never contested. `shouldShowMenu` is a static
predicate with its own tests, because getting it backwards is invisible in a plain shell and breaks
exactly one of the two cases.

### Split panes

**Window → Split Right** and **Split Down** (`Cmd+D` and `Cmd+Shift+D` on macOS, iTerm2's;
`Ctrl+Shift+E` and `Ctrl+Shift+O` elsewhere, Terminator's) open another shell beside or below the
one you are in, like Duplicate Tab: the same shell or profile, in the directory you are in now. The
keyboard moves between panes with `Cmd+Option+arrows` (`Ctrl+Alt+Shift+arrows` off macOS — Ctrl+Alt
alone switches GNOME workspaces) or a click, and the pane being typed in is outlined in the theme's
blue while there is more than one.

A tab is a `PaneGroup`: a tree of `SplitPane`s with a `TerminalView` at each leaf. Splitting the way
a pane's parent already runs adds a sibling rather than nesting, so three panes side by side share
the width evenly; closing one that leaves a split with a single child collapses it. The tab is
titled after the **active** pane, and Copy, Find, the prompt jumps and a new tab's directory all act
on it.

Close Tab's chord closes the active pane of a split tab — the File menu and the palette say "Close
Pane" while that is what it will do — and the tab with its last pane. The tab's own close button
and menu still close every pane, asking once if anything is running in any of them. A shell that
exits closes its own pane.

**Window → Maximize Pane** (`Cmd+Shift+Enter`, `Ctrl+Shift+Enter` elsewhere) shows the active pane
on its own, with "Pane 2 of 3" in its corner; the others keep running, and splitting, moving between
or closing panes puts them back first. **Move Pane to New Tab** gives a pane a tab of its own, and
the palette lists **Move Pane to Tab** for each other tab by name. A pane can also be dragged:
`Cmd+Option+Shift`+drag (`Ctrl+Alt+Shift`+drag elsewhere) picks it up, a drop on a tab joins that tab
and a drop on the strip's empty part makes it a tab. Its shell keeps running throughout; only its
place changes. **Reopen Closed Tab** brings a split tab back as it was — every pane, in its last
directory, arranged the same, with the same one active. It undoes the most recent close wherever it
was: a pane closed with the Close chord comes back as a tab of its own beside the one it left, and a
window closed with its tabs comes back as a window, each tab in its place.

Two things about the layout cost time and do not announce themselves. A new `SplitPane` has no skin
until the next CSS pass, and without one its items are not in the scene — a pane started there
began at a guessed 80×24 and dropped the focus request, so the split is styled and laid out before
the shell starts. And a hidden tab defers painting, so selecting a split tab has to wake every pane,
not only the active one.

### Search

**Edit → Find…** (`Cmd+F` / `Ctrl+Shift+F`) opens a bar at the top right of the pane: it searches
the whole scrollback and the screen as you type, highlights every match and outlines the current
one. **Enter steps up**, to older output, and Shift+Enter down — a terminal is searched for
something recent, so the search starts at the newest match and works backwards, as iTerm2's does.
`Cmd+G`/`Cmd+Shift+G` (`Ctrl+Shift+G`/`Ctrl+Shift+H`, GNOME Terminal's) do the same from anywhere,
and a short selection becomes the query.

Case is smart: all lower case matches either case, a capital makes it exact. `.*` switches to a
regular expression, as does the Toggle Regular Expression command from the palette or a chord of
your own; one that does not compile yet says so instead of reporting no matches. A word
that wrapped at the right edge is found as one word, double-width characters match without their
placeholder cells, and the count stops at 1000.

The rows are copied out under the buffer lock and matched outside it, so a regex over ten thousand
lines holds the emulator up for a copy, not for the search. Matches are recorded against a running
count of the rows the text has moved (`linesScrolled`), so they stay on their text as output
arrives without being shifted one by one; a reflow invalidates them and they are found again, at
most four times a second while output streams.

### Shell integration

A shell can describe its commands with **OSC 133** marks — where each prompt starts, where its
output starts, and how it ended. With them, **Edit → Previous Prompt** and **Next Prompt**
(`Cmd+Up`/`Cmd+Down`, Terminal.app's; `Ctrl+Shift+Up`/`Down` elsewhere) scroll to the prompts,
**Select Last Output** selects what the last command printed, ready to copy, and a command that
failed gets a short red bar in the margin of its prompt. Without marks the three commands are
greyed, and the palette says why.

Some shells and prompt frameworks send the marks by themselves. For bash and zsh, add this to
`~/.bashrc`:

```bash
PS0='\e]133;C\a'
PROMPT_COMMAND='printf "\e]133;D;%s\a\e]133;A\a" "$?"'"${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
```

or to `~/.zshrc`:

```zsh
_termina_precmd() { local s=$?; print -n "\e]133;D;$s\a\e]133;A\a" }
_termina_preexec() { print -n "\e]133;C\a" }
precmd_functions=(_termina_precmd $precmd_functions)
preexec_functions+=(_termina_preexec)
```

**The marks are taken where the emulator reads, not where the PTY delivers.** `OscInterceptor`
wraps the stream JediTerm pulls characters from, and the emulator acts on each character before
pulling the next — so when a mark arrives, everything before it is on the screen and the cursor is
on the prompt's row. Watching the PTY's reads instead would see a chunk at once and record every
mark in it a chunk early. The interceptor takes only the codes Termina handles and pushes everything
else back untouched, titles and OSC 8 included.

A resize to a different width rebuilds every row, and a row number recorded before it means
nothing after. So just before the view resizes the shell it describes each mark as a logical line
and an offset into it (`LogicalLines`), and places it again against the new width — what JediTerm
does for the cursor and the selection, with no hook to have it do the same for anything else.

### Programs' requests

- **Declared links (OSC 8).** `ls --hyperlink`, compilers and `gh` can mark text as a link to
  somewhere else. Hovering one with the link modifier underlines every cell it covers and shows its
  real target in a line at the bottom of the pane — the text can say anything, so where it goes is
  shown before the click. The same scheme allowlist as links found in the text applies, so a
  `javascript:` target behind innocent text is not a link at all. A `file://` URI naming this
  machine by its host name — which is how `ls` writes them — opens the file.
- **The clipboard (OSC 52).** A program can put text on the clipboard; it is how vim and tmux copy
  across SSH. Programs can never *read* it — that would hand the clipboard to anything on the far
  side of an `ssh` that asked — and Terminal → Input turns writing off too.
- **The directory (OSC 7, and ConEmu's OSC 9;9).** Believed only on Windows, and only for a
  directory on this machine that exists. Elsewhere the OS is asked instead, so a shell inside `ssh`
  cannot claim the tab for another machine's path. Windows is asked too — the directory is read out
  of the shell process's own parameters — but a WSL tab's process is `wsl.exe`, whose directory is
  not the Linux shell's, so there a shell's report wins and WSL's launchers are never asked.
- **Notifications (OSC 9, OSC 777).** `printf '\e]777;notify;Build;done\a'` (urxvt's form, which
  foot, kitty and WezTerm read too) or `printf '\e]9;done\a'` (iTerm2's). The tab is marked with a
  magenta dot that outranks the bell's, and the message goes to the desktop unless you are looking
  at that pane — through `notify-send`, or `gdbus` straight to the session bus, on Linux;
  `osascript` on macOS; a PowerShell toast on Windows. One per pane every two seconds reaches the
  desktop. The text is treated as hostile: control characters are stripped, it is capped, and it
  reaches each command as an argument or an environment variable, never inside a script. ConEmu's
  numbered OSC 9 subcommands (`9;4` is a progress bar) are not notifications. Terminal → Display
  turns the desktop half off.

### Type-ahead over SSH

While `ssh` is the shell's foreground program and the far end is slow to echo, what you type
appears at once, dimmed and underlined until the echo confirms it, and the cursor moves ahead with
it. JediTerm's core has the predictor — it measures latency, learns what echoes, notices a password
prompt and backs off when it is wrong — and shows nothing until the median echo is over 100 ms, so
on a fast link it stays out of the way. A local shell is never predicted: it echoes within a frame,
and a prediction there could only ever be wrong. Terminal → Input turns it off.

The predictor's other half, the line it edits and the code that draws it, lives in JediTerm's Swing
UI and writes into a package-private field of `TerminalLine`, which is out of reach across a module
boundary. `TypeAheadModel` keeps the predicted line itself and the view draws it over the real one.

### The kitty keyboard protocol

A program can ask for the [kitty keyboard
protocol](https://sw.kovidgoyal.net/kitty/keyboard-protocol/), and Termina implements all of it:
Escape stops being the start of every sequence, Ctrl+I stops being Tab and Shift+Enter is its own
key; with the further flags, repeats and releases, the shifted key, every key including the
modifiers, and the text each key types. Neovim, Vim, Helix, fish and nushell turn it on by
themselves. Nothing changes until a program asks — every key keeps its legacy bytes — and plain
typing, Enter, Tab and Backspace keep them under the first flag too, so `reset` can still be typed
after a program that crashed with the mode on. `reset` clears it.

**The mode requests are taken out before JediTerm sees them.** Each is a `CSI … u` with a marker
(`>` push, `<` pop, `=` set, `?` query), and JediTerm reads any `CSI u` as SCORC, restore the saved
cursor, ignoring the marker — so a program turning the protocol on would have had its cursor thrown
somewhere instead. `KeyboardModeInterceptor` wraps the same stream as the OSC interceptor, and
`KeyboardFlags` keeps a stack per screen, as the specification requires, so an editor on the
alternate screen can change the mode there without disturbing the shell's.

JavaFX reports a keystroke as three events and says neither when a press is a repeat nor which
releases a program is owed; `KittyKeyInput` works both out — a press of a key already down is a
repeat, and a release is sent only for a key whose press went out as an escape. `KittyKeys` is the
encoder, pure and tested against the specification's own examples. Three things are approximated:
JavaFX cannot tell a left modifier from a right one, so all are reported as the left; it has no
"base layout key", so that optional field is never sent; and AltGr is told from Ctrl+Alt the way the
legacy encoder already does, by whether the press composed something.

### Settings

`Cmd/Ctrl+,` or the context menu opens preferences: theme, font family and size, cursor shape, visual bell, scrollback
depth, shell, and Alt-as-Meta. Stored as JSON in `~/.termina`: `settings.json` for preferences and
`workspace-state.json` for the window's place and size and the update check's memory. Each file
names its `schemaVersion`, and its keys are the dotted names used here (`"font.size": 13`), flat, one
per line, so a key is easy to find and change by hand. Every getter falls back to a default, so one
bad hand-edited value costs that key and not the rest, and values are clamped **on read as well as
on write** (a hand-edited two-million-line scrollback would otherwise exhaust memory at the next
launch).

A file written by a newer Termina is not read, since its keys may mean things this one does not
know: it is kept as `settings.json.v<n>.bak` and defaults load. A file that is not valid JSON is kept
as `settings.json.corrupt.bak`. A `settings.properties` from before 0.15 is imported into the two
files on the first launch and kept as `settings.properties.migrated`. More than one Termina may run
on one config folder: each save writes only the keys that process changed, under a lock, on top of
whatever the file holds by then.

**Export and Import.** Settings ▸ Advanced, or the palette's Export Configuration, writes
`termina-config-<date>-<time>.zip` of the config folder to your home folder; locks, logs, the
instance socket and the sync clone are left out. Import Configuration reads one back and lists what
it holds — preferences, key bindings, shell profiles, imported themes — each ticked; only the ticked
parts are replaced, each as a whole. Window positions and the settings-sync connection belong to the
computer and are never imported. The current configuration is copied to
`backups/termina-config.before-import-<date>-<time>.zip` first, so an import can be undone by
importing that.

A setting that only makes Termina less safe has no row in Settings; it is a key in `settings.json`
for anyone who means it. There is one: `"input.confirmPaste": false` pastes several lines without
first showing them, where by default a paste with a line break asks.

The window follows Editora's: a grouped sidebar, a search box that narrows both rows and
categories, and **no OK or Cancel**. Every control writes its setting immediately and the terminal
re-applies. That is the point rather than a shortcut — choosing a font or a theme is a judgement
about how something looks, and a dialog that defers the result until it is dismissed makes you
guess. The Appearance page shows a live sample rendered with the real palette and the real font for
the same reason.

Two settings deliberately do **not** apply live, and say so on their own row: scrollback sizes a
buffer that already exists, and the shell is a process already running. Both take effect in the next
session.

The **Keyboard** page lists every command under the menu it lives in, each with its shortcut in a
field: click it and press the new chord. A chord another command already has is not taken on the
first press — the row says whose it is, and pressing it again moves it, leaving the other command
with none. Backspace removes a shortcut, Reset puts one back (asking first if that takes it from
someone else), and Reset All Shortcuts puts them all back. A bare key is refused, because the shell
would lose it; Ctrl+letter off macOS is allowed with a note that the shell no longer receives it,
which is why none of the defaults use it. Commands with no shortcut of their own — Rename Tab,
Duplicate Tab — can be given one. In the file each binding is one line, keyed by command:
`keys.newTab=Ctrl+Shift+Y`, or `keys.closeTab=none` for no shortcut at all. A line that does not
parse, or that takes a chord an earlier line already took, is dropped on read and the command keeps
its default. Five commands go by the ids Pauta names: `keys.palette.show`, `keys.help.about`,
`keys.help.documentation`, `keys.view.messageLog` and `keys.find.show`. A file that still has their older names
(`keys.commandPalette` and so on) is brought forward when it is read, and so is what settings sync
receives from a computer on an older Termina.

### Shell profiles

A terminal on Windows is not one program. Command Prompt, Windows PowerShell, PowerShell 7 and every
installed WSL distribution are separate shells with separate command lines, and a single "path to
your shell" setting can only ever name one of them. So a **profile** — a name, a command, optionally
a directory — is the unit a tab is opened as. The chevron beside the `+` drops the list, **File ▸ New
Tab With** carries the same entries, the command palette lists them by name, and `Ctrl+Shift+1`
through `9` (`Cmd+Option+1` through `9` on macOS, where Cmd+Shift+3, 4 and 5 are the screenshot keys)
open the first nine.

What is on the list is **found, not configured**: someone who has just installed PowerShell 7 or a
second WSL distribution should find it in the menu rather than have to describe it in a settings file
first. On Windows that means both PowerShells (7 first — it is the one somebody installed on
purpose), Command Prompt, Git Bash, and each distribution `wsl.exe --list` reports; on macOS and
Linux, the shells named in `/etc/shells` and on the path. Nothing found is written down, so
uninstalling a shell removes it rather than leaving an entry that fails to start.

Four decisions in there are less obvious than they look:

- **`wsl.exe --list` writes UTF-16, not UTF-8**, even when its output is redirected. Read as UTF-8
  every distribution name comes back with a NUL between each of its letters, which survives far
  enough to become a menu entry that starts nothing. The encoding is sniffed rather than assumed,
  since newer builds have been reported writing UTF-8 instead.
- **Docker Desktop and Rancher register pseudo-distributions** that `wsl --list` reports and nobody
  wants a terminal in. They are filtered by name, which is what Windows Terminal does too.
- **The login flag is per shell.** Every shell in the POSIX family plus fish and tcsh takes `-l`; the
  newer ones vary, and passing one an option it does not understand turns a menu entry into a tab
  that prints usage and exits — which reads as the shell being broken rather than as us guessing.
- **One entry per shell, not per binary.** A machine with Homebrew has two zsh executables, and a
  menu offering "Zsh" twice with no way to tell them apart is worse than one that picks the first.
  Discovery also drops whichever shell is already your own, so the list does not carry "Default
  Shell" and "Zsh" running the identical command.

Your own shell is always first and is the one profile that cannot be hidden — a settings file that
hid it would leave a window with no way to open a tab at all. Everything is keyed by a stable id
rather than by position, so installing a shell does not silently change which profile the `+` opens,
and a default naming something since uninstalled falls back rather than refusing to start.

A profile can have **its own font, size and theme** — Settings → Profiles, Default following the
global setting. They are stored by the profile's id rather than in the profile, so the system shell
and the discovered profiles can have one too, and the id travels with each pane, so a split, a
duplicate, a reopened tab or a moved pane keeps its profile's look. A profile's size goes through the
global zoom. With several themes in one window, the window's own colours — behind the panes, the
window buttons, the tab marks — follow the pane being typed in.

Font choices are restricted to **monospace faces**, filtered by measuring whether `i` and `M` have
the same advance. That is not tidiness — the renderer places every glyph on a fixed cell grid, so a
proportional face misaligns every column on screen.

### Themes

Four built in, and any number imported. **Editora Dark** and **Editora Light** are carried over from Editora — Caret teal on Ink
navy. Their control stylesheets are AtlantaFX-derived and self-contained, so they are applied with
`Application.setUserAgentStylesheet` directly and AtlantaFX is not a dependency.

**Clear Dark** and **Clear Light** are ported from macOS Terminal, with the colours decoded out of
Apple's own `.terminal` profiles rather than matched by eye. Two caveats, both stated because a port
should be honest about what it is: Apple ships them translucent (95% and 93% over a blurred
desktop) and Termina has no window transparency, so they render opaque; and Clear Light is
genuinely low-contrast in four places — white, bright yellow, bright cyan and bright white against
its white background. That is Apple's choice and it is kept, pinned in `ThemeTest` so nobody
"fixes" it by accident. A ported theme that improves its source is no longer that theme.

Ported palettes bring no opinion about how a settings window should look, so they borrow whichever
Caret & Ink control stylesheet matches their brightness.

The ANSI colours are derived from the matching Editora *editor* theme's syntax palette: keyword
becomes red, string blue, escape green, type yellow, function magenta, and the Caret accent becomes
cyan — so a shell's `ls` colours stay in the same family as the editor's code colours.

On the light theme ANSI "white" is rendered **dark**. It has to be: a program that sets colour 7 on
a white background would otherwise write invisible text. `ThemeTest` enforces this as a contrast
ratio rather than leaving it to judgement, for every colour in both themes.

**Importing.** Settings → Appearance → **Import…** reads a scheme another terminal wrote: an iTerm2
`.itermcolors`, a Terminal `.terminal` profile, a Windows Terminal scheme or a whole `settings.json`
(every scheme in it), or a Ghostty, kitty or Alacritty theme — the formats iTerm2-Color-Schemes
publishes every scheme in. It is stored as `<config>/themes/<name>.properties`, plain and editable,
and listed after the built-in four; importing it again replaces it, and **Remove** deletes it. Its
colours are taken exactly as the file has them, cursor and selection included where it names them,
and the chrome follows its background. A `.terminal` profile is the awkward one: each colour is an
`NSColor` archived with `NSKeyedArchiver` into a *binary* property list inside the XML one, so it has
a small `bplist00` reader of its own. The XML is parsed with every external DTD and entity refused,
since the file is whatever was downloaded. Themes are not synced at all — sync carries only the key
bindings — and a settings file naming a theme this machine does not have falls back to Editora Dark.

## Notable details

- **Login shells.** A GUI process inherits a stripped `PATH` with no Homebrew and no version-manager
  directories. The shell is started with `-l` so the user's profile puts them back.
- **AWT is forced headless** in `App.main`. We require `java.desktop` (JediTerm's `TtyConnector`
  declares `java.awt.Dimension` overloads) but never use AWT — and on macOS an initialised AWT
  contends with JavaFX for the AppKit run loop, which hangs rather than fails.
- **Explicit `requires` for `kotlin.stdlib` and `org.slf4j`.** JediTerm and pty4j are automatic
  modules; an automatic module reads everything in the graph but does not pull explicit modules
  *into* it. Without those two lines the build is clean and the first emulator call dies with
  `NoClassDefFoundError`.
- **Paste** is `Cmd+V` on macOS and `Ctrl+Shift+V` elsewhere — plain `Ctrl+V` is readline's
  literal-next and belongs to the shell.

## Not done yet

- **Panes.** No dragging a pane to another *window*, and no dropping one where the tab strip is
  hidden (a single tab) — Move Pane to New Tab covers that case.
- **Escape sequences.** No inline images or ligatures. JavaFX 27 looks not to shape plain Latin
  text through `fillText` at all, so ligatures may be blocked by the toolkit before they are a
  renderer question; that wants the spike in the capabilities doc first. Which of these JediTerm
  already handles, and what each would cost, is in
  [docs/jediterm-capabilities.md](jediterm-capabilities.md); how other terminals compare is in
  [docs/competitive-landscape.md](competitive-landscape.md).
- **Unrun on Windows and macOS:** the Windows directory read (the PEB walk is tested against a
  made-up address space; the system calls have not run), the PowerShell toast, the `osascript`
  notification and Option+drag blocks. PRIMARY and `notify-send` have not been tried on a machine
  that has `wl-clipboard`, `xclip` or `libnotify-bin`; the `gdbus` route has.
- **Signing on Windows.** The macOS dmgs are signed and notarized and the Linux downloads are
  OpenPGP-signed (see [installing.md](installing.md)); the MSI is not signed — see
  [Known issues](#known-issues).
- **Windows has not been run by a human.** CI builds and packages it on every commit, and pty4j
  carries the ConPTY natives, but nobody has opened a window there. macOS and Linux are run; the
  installers on all three have not been installed by anyone yet.

## Command line

```
termina [options] [-e command [args...]]

  -h, --help                     Show this help and exit
  -v, --version                  Show the version and exit
      --report                   Write a problem report to a file, open a GitHub issue for it, and exit
      --new-instance             Start a Termina of its own, not a window in the running one
      --dev                      Use ~/.termina-dev for settings, leaving ~/.termina alone
  -d, --working-directory=DIR    Start the shell in DIR
  -T, --title=TITLE              Name the first tab TITLE
  -e, --command CMD [args...]    Run CMD instead of the shell; must come last
      --config-dir=DIR           Read and write settings in DIR
```

`--help` and `--version` are answered before the toolkit starts, so they work without a display —
`--version` is what ends up in a bug report. An unknown option is refused rather than ignored, since
an option that is silently dropped looks exactly like one that exists and does nothing.

`-e` takes the rest of the line, which is xterm's convention and why it has to come last: `-e ls -l`
passes `-l` to `ls` rather than rejecting it here. The command replaces the shell rather than running
inside one, so the terminal closes when it exits.

One Termina runs per config folder. Launching it again — from a shell, a desktop shortcut, or as
the system terminal — opens a new window in the running one, with that launch's `-d`, `-e` and `-T`,
and in the directory it was launched from when `-d` is not given; the second process exits as soon
as the first has the request. `--new-instance` starts a separate Termina instead, and a different
`--config-dir` is a separate Termina anyway.

The command line applies to the first tab of the first window only. A second tab re-running `-e vim`,
or reopening in a directory you have since navigated away from, is not what anybody means by it.

## Known issues

**The Windows build is unsigned**, so SmartScreen will warn on first run. "More info" → "Run anyway".

**Intel Macs get no Homebrew cask.** A cask accepts exactly one artifact, so the tap serves Apple
Silicon; Intel users can take the `macos-x64` DMG from the release page.

**The installers have not been installed by anybody yet.** They are built and their contents
verified — the DMG was mounted and the app inside checked for the right version, a valid signature
and its AOT cache — but no one has yet double-clicked an MSI or run `dpkg -i` on a real machine.
