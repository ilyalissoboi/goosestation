<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android choice lists and PlayStation button glyphs — design

Two changes from the player's feedback after the first device pass of the in-game menu (PR #33), agreed in
conversation on 2026-10-31 to ship together as one PR after #33. Calls made while writing this up, not yet confirmed,
are marked *(writer's call)*; calls the builder may make are marked *(builder's call)*.

## Intent

- **Choice lists.** A setting with a few values (`CyclerRow`) can only be stepped through, one value at a time, with
  only the selected value's note in view. The player expected A to open the choices. A should open them as a list,
  in the page, with each choice's note beside it, so the player can see them all and pick one; left and right keep
  stepping as they do now.
- **PlayStation button glyphs.** The Controllers page names Port 1's inputs in words ("Triangle", "Left Stick Left").
  The player wants them shown as they look on the PlayStation controller: the face buttons as their shapes in their
  colours, and every other input as a glyph too.

## Part A: the choice list

### Behaviour

- **Closed**, a `CyclerRow` is as it is today: the label, the selected value between two arrows, the row's note and
  the selected choice's note. Left and right step, stopping at the ends; a tap on an arrow steps its way.
- **A, Enter, the D-pad centre or a tap on the row open the list** (instead of stepping forward). With no choices, they
  do nothing.
- **The list opens in place**, under the row's label line, inside the page: the rows below move down to make room. It
  is never a window, a popup or an overlay.
- **It lists every choice:** its label, and its note when it has one. The selected choice is marked as current and
  has the focus when the list opens. A value that isn't among the choices marks none, and the first choice has the
  focus.
- **Up and down move the focus through the list**, stopping at the ends. The focus can't leave the open list.
- **Nothing changes until A.** A (or Enter, the D-pad centre, a tap) on a choice selects it (`onSelect`, only if it
  differs from the selected one), closes the list, and puts the focus back on the row.
- **B or Back closes the list without changing anything**, focus back on the row. B and Back reach the list before
  the page, so they close the list rather than leaving the page.
- **Left and right do nothing while the list is open** *(writer's call: stepping a value the player isn't looking at
  would be a hidden change)*.
- **Scrolling:** the open list is brought into view as a whole when it fits, else the focused choice is kept in the
  middle like any focused row (the app's centred scrolling).
- **If the choices change while the list is open** (a controller is unplugged from the controller picker), the list
  shows the new choices; if the focused choice is gone, the focus goes to the current one, else the first; if none are
  left, the list closes *(writer's call)*.
- **Touch:** a tap on a choice selects it as A does; a tap on the row's label line closes the list without changing
  anything *(writer's call)*. A tap anywhere else on the page closes the list and then does what it would have done
  (toggles a switch, starts a capture, opens another row's list): the list is part of the page, not a window over
  it *(writer's call)*. Opening a list closes any other, so one list is open at a time.

### Look

- The list sits under the label line on `sunken` at `radius-md`, inset 16 dp like the row's content, with a
  `line-strong` 1 dp edge, the same well the value already uses *(writer's call)*.
- Each choice is a row at least 48 dp tall: the label in `label`, its note under it in `body-sm`
  `ink-muted`. It has the `focusRing` and lifts to `raised` when focused, as every row does.
- The current choice is on `beak-soft` with a `check` icon (`GsIcons.check`) in `beak-ink` at the end, as the in-game
  menu marks its current row. Text on it stays `ink`.
- While the list is open, the row's arrows are hidden or faint *(builder's call)*; the value shown stays the selected
  one until A.
- Accessibility: the row says whether it's expanded; each choice is a selectable item, the current one selected. The
  existing Previous and Next custom actions stay.

### Where it applies

Every `CyclerRow`: the BIOS page, the three on the Graphics page, and the controller picker on the Controllers page.
`SwitchRow` (A toggles) and the binding rows (A captures) are unchanged.

## Part B: PlayStation button glyphs

### Behaviour

- **On the Controllers page's Port 1 rows**, the input's name on the left becomes its glyph. The bound button on the
  player's own controller, on the right, stays text: their controller's buttons aren't PlayStation ones.
- **Only when Port 1 is a digital pad or a DualShock** (`[Pad1] Type` is `DigitalController` or `AnalogController`),
  since other types reuse the names for other things (the PlayStation Mouse's `Left` and `Right` are its buttons).
  The type reaches Kotlin with the bindings (a field on what `GetPadBindings` returns, or a call of its own: builder's
  call) *(writer's call)*. With any other type, every row keeps its text, as today.
- **The glyph is chosen by the core's input name** (`PadBinding.name`): `Up`, `Down`, `Left`, `Right`, `Triangle`,
  `Circle`, `Cross`, `Square`, `Select`, `Start`, `L1`, `R1`, `L2`, `R2`, `L3`, `R3`, `LUp`, `LDown`, `LLeft`,
  `LRight`, `RUp`, `RDown`, `RLeft`, `RRight`. **A name without a glyph shows its display name as text**, as today.
  (`Analog` has no row: the app moved it to the Analog mode hotkey, so it gets no glyph.)
- **No words beside the glyphs.** Screen readers read the display name ("Triangle", "Left Stick Left") as the glyph's
  content description.
- The capture prompt that replaces the bound value while a button is captured keeps its wording, with the input's
  display name in words *(writer's call)*.
- Not on the Hotkeys rows (their chords are the player's own controller's buttons), the key hints, or the in-game
  menu.

### The glyphs

Drawn for GooseStation, in the design system's icon style: a 24 dp-tall grid, 2 dp strokes, square caps, mitred
corners, the octagon wherever the controller has a round button. The reference drawings are
[`docs/design-system/pad-glyphs/`](../../design-system/pad-glyphs/), one SVG per core name, in the light theme's
colours; they're the source the Compose code follows. Chosen on 2026-10-31 over Kenney's "Input Prompts" and Xelu's
"Free Controller Prompts" (both CC0), compared side by side in both themes.

- **Face buttons:** an octagon housing on `sunken` with a `line-strong` 1.5 dp edge; inside, the symbol in a 2 dp
  stroke in its colour: a triangle, a circle (a true circle, the one exception to octagons: it's the symbol, not a
  housing), a cross, a square.
- **D-pad:** the plus outline in `ink`, with the pressed arm filled.
- **Sticks:** the octagon (the stick's top) in `ink`, the stick's letter (L or R) in its middle, and a filled
  arrowhead inside its edge pointing the direction. **L3 and R3:** the octagon with a smaller one inside, pressed in,
  and "L3" or "R3".
- **Shoulder buttons:** a tab, flat at the bottom, on `sunken` with an `ink` edge, the label inside ("L1", "R2"); 1
  and 2 differ in the tab's corners. 30 units wide.
- **Select:** a flat bar; **Start:** a right-pointing triangle; each followed by its word in capitals ("SELECT",
  "START"), as printed on the controller.
- Labels are Atkinson Hyperlegible Next (the `text` family), bold, at the sizes in the SVGs.
- Shown 32 dp tall in the rows (24 units scale by 4/3), widths following the drawings (24, 30, or wider for the
  labelled ones). **They grow with the font scale**, as text does, and by 1.5 on a TV like control heights, so the
  labels in them stay as readable as the page's text *(writer's call)*. A wide glyph may push the row into
  `LabelValue`'s stacked layout on a narrow screen, as long text does now.

### Colours

Four new tokens, for the face symbols only; everything else uses existing tokens (`ink`, `sunken`, `line-strong`).
The app has only the dark theme, so Compose takes the dark values; the light ones are for the design system's
reference and a light theme later.

| Token | Dark | Light | Contrast on `sunken` (dark / light) |
| --- | --- | --- | --- |
| `ps-triangle` | `#3fd6a6` | `#0b7a5a` | 10.6 / 4.3 |
| `ps-circle` | `#ff6b6b` | `#c42b2b` | 7.1 / 4.5 |
| `ps-cross` | `#7aa7ff` | `#2b57c4` | 8.2 / 5.2 |
| `ps-square` | `#f28ad6` | `#a8338e` | 8.7 / 4.8 |

All clear the design system's 3:1 for icons in both themes. They're the PlayStation's green, red, blue and pink,
tuned per theme; the shapes differ, so nothing relies on colour alone.

## Non-goals

- Glyphs anywhere but the Port 1 rows; glyphs for the player's own controller's buttons.
- Glyphs for controller types other than the digital pad and DualShock (they keep text).
- Choice lists for anything but `CyclerRow`; search or type-to-filter in a list.

## Anti-goals

- A window dialog, popup or overlay for the list: the design system has none, and a controller-first page shouldn't.
- A list that changes the setting as the focus moves through it, or a B that changes anything.
- Third-party art in the app, or anything from DuckStation after `a5bf1ba5cee7` or its closed Android app.

## Constraints

- The design system is binding: tokens only, `focusRing` on everything activatable, 4 dp of room for the ring, focus
  on open. The new tokens go in `docs/design-system/tokens.json` and `GsColors`, and the list, the glyphs and the
  tokens in `docs/design-system.md`. The published artifact page is a maintainer's to update, outside this PR.
- Tests per the design system's checklist: a key-driven test for the choice list (open, move, pick, cancel with B,
  left and right while open, a value not among the choices, choices changing while open), `FocusAuditTest` and
  `OrientationLayoutTest` cover an open list and the glyph rows at 1.3x font scale, and the Controllers page's test
  checks a glyph and its content description, the text fallback for a name without one, and text for another
  controller type. The labels inside glyphs must count for the cut-off checks (so drawn as text, or checked another
  way: builder's call).
- The PlayStation face-button shapes are Sony trademarks; using them to name the buttons is what emulators commonly
  do. This design doesn't take a legal view.

## Left to the builder

- How the glyphs are drawn in Compose (a `Canvas` per glyph, `ImageVector`s plus `Text`, or a generator from the
  SVGs), as long as they match the drawings in both themes.
- The names of the new composables and whether `CyclerRow` is renamed.
- The open and close animation, if any, within the design system's motion rules.
