<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android on-screen touch controls — design

Part of roadmap item 16 (the Android frontend; the roadmap gains a line for touch controls). Agreed in conversation on 2026-11-08. Calls
made while writing this up, not yet confirmed, are marked *(writer's call)*; calls the builder may make are marked
*(builder's call)*.

## Intent

The app needs a controller, built in or connected, to play. Phones and tablets without one can't play at all. This
adds an on-screen PS1 pad over the game for **phones and tablets with no controller** *(the player's choice)*, so the
app works on most Android devices, while the Thor and anyone with a gamepad never see it unless they ask.

## What exists

- **The design system defines touch controls but nothing draws them** (`docs/design-system.md`, "Not built yet"):
  tokens `touch` (button fill at rest), `touch-pressed` (fill while held), `touch-line` (outline and glyph), and
  `touch-button` 64 dp (each face button and D-pad arm); the published design page previews them.
- **PlayStation glyphs** the Controllers page draws (`ui/components/PsGlyph.kt`, `docs/design-system/pad-glyphs/`):
  each on an octagon plate, and not one size (Cross spans 8 units, Square 9, Circle 10, Triangle 10×9 and off-centre;
  `PsGlyph.kt:197-198, 280-284`). The theme has no touch colours yet (`Theme.kt`, only `osd` and `osdInk`).
- **Input** (`input/ControllerInput.kt`, `KeyRouter.kt`, `MenuTrigger.kt`, `Rumble.kt`): each controller is
  `Android-<n>` to the core with its own bindings and a port (settings stage 2); the menu opens from Back, Mode, or
  Select + Start held. The app has the `VIBRATE` permission (rumble).
- **The app is locked to landscape** (the activity's `android:screenOrientation="sensorLandscape"`,
  `AndroidManifest.xml:37`); it still shows in portrait where Android ignores the lock (screens 600 dp and wider) and
  in split-screen or freeform windows. The core centres the picture (`[Display] Alignment`, default Center).
- **Port 1's type** defaults to a DualShock (`AnalogController`); `NativeLibrary.getAnalogMode()` reports its analog
  state and -1 for any other type (`NativeLibrary.kt:524`).
- The manifest doesn't require a touch screen (`android.hardware.touchscreen` not required), and the app's own screens
  already work by touch.

## Goals

### When they show

- **Automatically when no controller is connected** *(the player's choice)*: in a game, the overlay shows while no
  controller is connected (the app's own list of connected controllers, built-in pads included, so the Thor never
  sees it) and hides when one connects, coming back when the last one goes *(writer's call)*. A device that reports
  a pad nobody holds can use **Always**.
- **Settings → Controllers → Touch controls**: **Automatic** (the default), **Always**, **Off** *(writer's call)*.

### What's on them

*(The player's choice.)*

- **A full PS1 pad:** the D-pad, the four face buttons, L1, L2, R1, R2, Select, Start, and a **menu button** that
  opens the in-game menu as Back does.
- **Two sticks only when the port's controller is a DualShock in analog mode** (the core's controller type and its
  analog state), so digital games stay uncluttered. They appear and go as the mode changes (the Analog mode hotkey or
  menu entry, or the game switching it). An **Analog Joystick** is always analog, so it always has them *(writer's
  call)*.
- **Other Port 1 types** (NeGcon, GunCon, Justifier, Mouse): no overlay, and the game's start says once "Touch
  controls work with a digital pad or an analog controller on Port 1." *(writer's call)*.
- **L3 and R3** *(the player's choice)*: two small buttons, shown only with the sticks, each just above the outer
  edge of its stick, never pressed by steering. (The Analog button isn't on the overlay; the in-game menu switches
  analog mode.)
- **The face buttons are a symmetric diamond** *(the player's requirement)*: four buttons of one size, the same
  distance from one centre on every side. **Every glyph is drawn the same size and centred in its button**: the app's
  own PlayStation glyph shapes, without their octagon plates, each scaled to one common box (the same visual extent
  and stroke weight) on the overlay only, so `PsGlyph` and the remap rows don't change *(writer's call)*; never font
  characters. A test checks the four glyphs' drawn bounds match and sit inside their buttons.
- **Colours** *(writer's call, as the agreed mockups)*: buttons in `touch` / `touch-pressed` with a `touch-line`
  outline; face glyphs in their PlayStation colours, other labels in `touch-line`. The Opacity setting fades all of
  it. The design system's touch rules are written to say so.
- The D-pad mirrors the face buttons on the other side, at the same height.

### The layouts

*(The player's choice: good defaults, no moving buttons; the mockups agreed,
[2026-11-08-android-touch-controls-mockup.html](2026-11-08-android-touch-controls-mockup.html), are the reference.)*

- **Landscape** (phones, and most tablets): the controls sit in the bars beside the 4:3 picture, so they cover
  little of the game. Shoulders in the top corners (L2 above L1, R2 above R1), Select and Start at the bottom middle,
  the menu button at the top middle, the D-pad and face diamond centred in their bars at the same height.
  - **With sticks**, both clusters move up and shrink by the same amount, and the sticks mirror each other at the
    bottom, reaching faintly over the picture's lower corners.
  - On a screen whose bars are narrower than the controls (a wide picture setting, or a near-4:3 tablet), the
    controls overlap the picture, drawn in the touch tokens so the game shows through *(writer's call)*.
- **Portrait** (where Android ignores the landscape lock on screens 600 dp and wider, and in split-screen or freeform
  windows): the picture at the top, the pad below it, nothing over the game. The picture moves to the top only while
  the overlay shows; otherwise it's where `[Display] Alignment` puts it, so the player's setting holds with a
  controller *(writer's call)*. Shoulders in a row under the picture (L2 L1 · R1 R2 from the outside in), the menu
  button between them, the D-pad and face diamond mirrored around the middle, the sticks below them (analog only),
  Select and Start at the bottom.
- **Sizes follow the agreed mockups' proportions**, not the `touch-button` token's 64 dp, which doesn't fit beside the
  picture on phones (a 64 dp diamond is 192 dp across; the bar on an 800 dp-wide phone is about 153) *(writer's
  call; the token is updated to the built size)*. The controls scale with the screen to stay in the bars where they
  can. **Every touch area is at least 48 dp** in each direction, the smallest touch target, even where the drawn
  shape is smaller (Select, Start, the shoulders): the touch area extends into the gaps around it, never into another
  control's.
- **Settings** *(the player's choice: only these)*: overall **Size** and **Opacity**, on Settings → Controllers,
  saved for this device only (the app's own preferences, not `settings.ini`, since screens differ) *(writer's call)*.

### How they feel

*(The player's choice.)*

- **Fixed:** everything stays where it's drawn, sticks included.
- **The D-pad takes diagonals** (a touch between two arms presses both).
- **Sliding:** a thumb can slide from one face button to the next, or rest between two and press both, without
  lifting; the same across the D-pad's arms. Shoulders, Select, Start and the menu button react to the touch that
  started on them *(writer's call)*.
- **No rumble by touch:** a game's rumble never plays on the device's own vibrator, as the player chose for rumble in
  settings stage 2 (never the phone's or handheld's own motor). Only the press tick uses it.
- **Multi-touch:** each finger is tracked on its own, so the D-pad, a face button and a shoulder can be held together.
- **A short vibration tick** on each press, with a **Vibration** switch on Settings → Controllers (on by default)
  *(writer's call on the default)*. It uses the device's own vibrator, which is separate from a controller's rumble.
- **Sticks:** the knob follows the thumb within the ring, full deflection at the ring's edge, and springs back on
  release; a touch that starts on the ring, not just the knob, takes the stick *(writer's call)*.

### What they drive

- **Port 1**, like every other controller *(writer's call)*: the touch pad is one more controller to the core, with
  fixed bindings that the Controllers page doesn't remap *(writer's call)*. Hotkeys aren't on the overlay; the menu
  button reaches the menu, which has fast forward, save states and the rest.
- The overlay never reaches the app's menus: the in-game menu and every other screen keep their own touch handling.

## Non-goals

- Moving or resizing individual buttons, layout editing, per-game layouts *(the player's choice: defaults only)*.
- Touch controls on the Thor or with a controller in use, unless set to Always.
- Remapping touch buttons, turbo, macros or hotkey buttons on the overlay.
- Light guns or the PlayStation Mouse by touch.

## Anti-goals

- A face button whose glyph is a different size from the others, drawn outside its button, or a face diamond that
  isn't symmetric (the mockups' first draft got both wrong).
- An overlay that shows on the Thor or with a gamepad in use, in Automatic.
- A press lost or stuck: lifting every finger, a touch cancelled by the system (a notification shade, a gesture), the
  app going to the background, or the overlay hiding must release every button and centre both sticks.
- Touch targets under 48 dp, or controls drawn outside the design system's touch tokens.
- Input latency the player can feel: touch events reach the core without waiting on the UI's next frame
  *(builder's call how)*.

## Constraints

- The design system ([`docs/design-system.md`](../../design-system.md)) is binding: tokens only, and the touch
  controls move from "Not built yet" to the built components with their rules. The overlay goes into
  `OrientationLayoutTest` (landscape and portrait, phone and tablet sizes) and the settings rows into `FocusAuditTest`.
- Tests drive touch by injected motion events (single and multiple fingers, slides, cancels); the quick tier while
  building and the full tier before the PR (`android/scripts/run-instrumented-tests.sh`). The emulators are the test
  devices; a real phone with no controller is the user's check, with their go-ahead.
- Android-only code in `android/`; no desktop change. Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor
  its closed Android app. `docs/android.md` and the roadmap (item 16) are updated. The user reviews every commit
  message.

## Left to the builder

How the touch pad reaches the core (a virtual `Android-<n>` controller or the pad state directly); how the overlay
learns the port's controller type and analog mode; exact positions within the agreed layouts; the size and opacity
ranges; the order of implementation.
