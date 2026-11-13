# Design system: touch targets, motion and shadows

Batch F of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, section 6). Some of
section 6 has already been fixed since the review:
- the "Set for this game" mark's font;
- What's new's styles, and its lines no longer fake buttons;
- rows with nothing to activate no longer announced as buttons;
- game tiles' and list rows' roles;
- page titles as headings.

This batch is what's left. `docs/design-system.md` is binding, and this work brings the app up to it, or the doc down
to what's built where it says something the platform can't do.

## Why

The design system says four things the app doesn't do:
- **Touch targets.** "Controls are at least 48 dp tall on touch screens and TVs … 40 dp is for pointer-only desktop
  controls" (`:143`). The cycler's arrows and its well are 40 dp (`ui/components/SettingsRows.kt`, `Cycler` and
  `CyclerArrow`). A near-miss lands on the row, which opens the choice list instead of stepping.
- **Motion.** "Focus moves and toggles at 120 ms, dialogs fading in at 160 ms. … Honour the system's reduce-motion
  setting" (`:150`). Nothing animates: the focus ring and fill appear at once, a switch's thumb jumps, and an overlay
  pops in.
- **Shadows.** "Only things that float (dialogs, the pause menu) cast `shadow-overlay`" (`:103`). Nothing casts one,
  and the token was never defined.
- **The toggle's thumb.** It's `RoundedCornerShape(2.dp)`, where toggles are `radius-sm`, 4 dp (`:145`).

## Decided

- **Cyclers at 48 dp.** `CyclerArrow` becomes 48 dp square, and the `Cycler` well 48 dp tall at least. The icons stay
  16 dp. The app runs only on touch screens, handhelds and TVs, so there's no pointer-only case to keep at 40 dp.
- **Focus fades at 120 ms** (your answer). The ring, and the focused fill under it, fade in on the element that gains
  focus and out on the one that loses it, over 120 ms. Nothing glides between elements. This lives in `focusRing`,
  `focusStop` and `focusIndicator` (`ui/components/FocusRing.kt`), so every screen gets it. An element that draws
  its ring on a part of itself (`ring = false`, told by `onFocusChange`) fades the same way where it draws it.
- **Toggles at 120 ms.** A `SwitchRow`'s thumb slides between its ends, and the track's and thumb's colours change
  over the same 120 ms.
- **Overlays fade in at 160 ms.** An `OverlayPanel`, and the pause menu as it opens, fade in with their scrim. Closing
  stays instant, since the doc only speaks of fading in. Focus moves into a panel the moment it shows, as now, so a
  controller can act during the fade.
  - The pause menu is its own scrim and `MenuPanel` in `GameMenu.kt`, not an `OverlayPanel`.
  - *(My call:)* it fades only when the menu opens. It doesn't fade when it's composed again after the game's settings
    or a layout change, so the fade remembers that the menu is already open.
  - A side panel opening beside the menu doesn't fade, since it isn't a dialog.
- **Reduce motion.** With the system's "Remove animations" on (animator duration scale 0), every one of these jumps
  to its end at once. *(My call:)* the app relies on Compose, which follows that scale in the app, so there's no seam
  of its own.
  - The builder confirms this once by hand on emulator-5580: set the scale to 1 with adb and see the toggle slide,
    then set it to 0 and see it jump.
  - The test suite already runs with animations disabled (`testOptions.animationsDisabled`), and a Compose test's
    clock doesn't follow the system scale. So the timed tests pass their own `MotionDurationScale` through
    `createComposeRule(effectContext = …)`: 1 for the 60 ms and 120 ms checks, and 0 for the reduce-motion check.
- **Nothing moves while a game runs,** except the on-screen messages, as now. The pause menu fades in over a paused
  game, which the doc allows.
- **`shadow-overlay`** (your answer):
  - **The look:** an elevation of 8 dp, in black at 60% in the dark theme and 25% in the light one, following the
    panel's `radius-lg` shape.
  - **The token:** the colour table gains `shadow-overlay`, with both values and the 8 dp in its notes (and
    `tokens.json` if the table has one). Android has only the dark theme, and the light one is listed as not built
    yet, so `GsColors` carries only the dark value. *(My call: no light theme is built for this.)*
  - **Who casts it:** an `OverlayPanel`'s panel, the pause menu's `MenuPanel`, and the `SidePanel`s that open beside
    it. *(My call: they float beside the menu, with the same look, so they cast it too.)*
- **The thumb's corner.** The design system gains one line: a shape nested inside another, with padding between them,
  takes the outer radius less the padding, and never less than 2 dp. That's why the thumb, inside a `radius-sm` track
  with 4 dp of padding, keeps its 2 dp. *(My call: it's what the code does, and a 4 dp thumb at 16 dp would look like
  a dot.)*
- **The doc follows the build.** `:150` names what fades where. `:103`, with the colour table, defines
  `shadow-overlay`. `:143` needs no change.

## Tests

- **Compose (`ui` package):**
  - a cycler's arrows are at least 48 dp square, and its well at least 48 dp tall (`assertWidthIsAtLeast` and
    `assertHeightIsAtLeast` on their bounds);
  - a switch's thumb is between its ends 60 ms after a toggle, and at the end by 120 ms (`mainClock.autoAdvance =
    false`, then `advanceTimeBy`);
  - with animations off, the thumb is at the end on the next frame;
  - an `OverlayPanel` shows its content, focused, on its first frame, so the fade doesn't delay focus or semantics.
- **The existing UI tests pass.** Where one checked a colour or a position on the frame right after a change, it
  waits for the animation, not the other way round. Each changed test says why.
- **Seen:** screenshots on emulator-5580 at 960 × 540 of a settings page with a focused cycler, an `OverlayPanel`,
  and the pause menu with a side panel open.
- **The quick tier** on both test emulators, and the CI tasks.

## Constraints

- `docs/design-system.md` is binding, and changes only where this design says.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- No motion over a running game; nothing slower than the doc's durations.
- The Thor (`emulator` tests only): nothing is installed on it unless you ask.

## Not doing

- A focus ring that glides between elements.
- Fading overlays out.
- A shadow on anything but `OverlayPanel` and the pause menu's panels.
- A light theme.
- The deleted save's 0.4 alpha in the memory card editor: the doc has no token it breaks.

## Left to the builder

- **What fades with focus:** only the ring and the focused fill. Other focus-driven tints, such as an icon's tint, a
  cycler arrow's `beak-ink` or a cover's ring, change at once as now, unless they're drawn by `focusIndicator`.
- **Stale comments:** `NumberRowTest`'s tap comment, which assumes 40 dp arrows, gets updated.
- **The easing:** the default `tween` easing is fine.
- **Where the 120 ms and 160 ms live:** a `Motion` object in `ui/theme` is the obvious place.
- **The shadow modifier:** `Modifier.shadow` with ambient and spot colours, or the 28+ `graphicsLayer` equivalent.
- **The order of commits.** A sensible default: the 48 dp cyclers, then motion, then the shadow and the doc.
