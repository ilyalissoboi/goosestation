<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android on-screen touch controls — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phones and tablets with no controller play with an on-screen PS1 pad over the game, shown in Automatic (no
controller connected), Always or Off, driving Port 1.

**Architecture:** Three pure-Kotlin units hold the logic and are tested on the JVM: `TouchLayout` (where each control
is drawn and where it takes touches, for a screen size, sticks or not, and the Size setting), `TouchTracker` (pointer
events in, the pad's state and press ticks out) and `TouchControls` (the settings, kept in the app's own preferences,
and when the overlay shows). `TouchOverlay` composes them over the game and sends each changed state to the core
through one JNI call. The core applies it to Port 1's controller directly (`Controller::SetBindState`, as Big Picture
does), not as an `Android-<n>` device, so nothing is written to `settings.ini` and no binding can be remapped. It also
reports Port 1's kind (unsupported, digital, analog) as it changes, and puts the picture at the top while portrait
shows the pad.

**Tech Stack:** Kotlin, Jetpack Compose (`pointerInput`, `Canvas`), C++20 in `android/app/src/cpp` over JNI, JUnit
(JVM) and AndroidX instrumented tests (Compose test multi-touch injection, padtest booted on the emulators).

**Spec:** `docs/superpowers/specs/2026-11-08-android-touch-controls-design.md`; layout reference
`docs/superpowers/specs/2026-11-08-android-touch-controls-mockup.html`.

## Global Constraints

- Android-only code in `android/`; no desktop change (nothing in `src/`). Clean room: nothing from DuckStation after
  `a5bf1ba5cee7`, nor from its closed Android app.
- `docs/design-system.md` and `tokens.json` are binding: touch colours are `touch` (rest fill), `touch-pressed` (held
  fill), `touch-line` (outline and labels); face glyphs in `ps-*`. No `Color(0x…)` or font sizes outside `Theme.kt`.
  The overlay is touch-only: it's not focusable and nothing in it is `clickable` (it uses `pointerInput`), so
  `FocusAuditTest`'s "clickable is focusable" holds.
- **Shapes** *(the user's choice at plan review, 2026-11-08)*: face buttons, sticks, the menu button and L3/R3 are
  octagons (principle 3, "round icons are octagons", as `PsGlyph`'s plates); D-pad arms, shoulders, Select and Start
  are rectangles at `GsShapes.medium` (8 dp). The Circle symbol stays a true circle (it's the symbol).
- **Port 1 only**, pad index 0. Buttons, in bit order (`TouchButton.ordinal`), and the core's `GenericInputBinding`
  each drives: Up `DPadUp`, Right `DPadRight`, Down `DPadDown`, Left `DPadLeft`, Triangle, Circle, Cross, Square,
  Select, Start, L1, R1, L2, R2, L3, R3. Sticks: left and right, x and y each -1..1, y down positive, to
  `LeftStickLeft/Right/Up/Down` and `RightStick…` as half axes (0..1 each side). Values go through the port's own
  Analog Sensitivity and dead zone, as any controller's do.
- **Kinds** (`PadKind`): `Analog` = DualShock (`AnalogController`) in analog mode, or `AnalogJoystick`; `Digital` =
  `DigitalController`, or a DualShock not in analog mode; `Unsupported` = any other type, None included. No overlay
  for `Unsupported`.
- **Settings**, in SharedPreferences `"touch"` (never `settings.ini`): mode `Automatic` (default) / `Always` / `Off`;
  Size 75–125% by 5, default 100; Opacity 25–100% by 5, default 100; Vibration on (default) / off.
- **Copy, exact:** section and cycler "Touch controls"; choices "Automatic", "Always", "Off"; cycler note "Automatic
  shows them in a game while no controller is connected."; rows "Size", "Opacity", "Vibration"; Vibration note "A
  short tick on each press."; on-screen once per game start "Touch controls work with a digital pad or an analog
  controller on Port 1."
- Every touch area at least **48 dp** in each direction; no touch area overlaps another control's.
- The press tick uses the device's vibrator (`VibrationEffect.EFFECT_TICK`); a game's rumble never does.
- Tests drive touch with injected motion events (Compose `performTouchInput`/`performMultiModalInput`: down, move,
  up, cancel, several pointers). Emulators only. Commit messages end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; each is shown to the user after the commit.
- Checks before a push: `cd android && ./gradlew assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs`;
  `android/scripts/run-instrumented-tests.sh quick` while building, `full` (with `GOOSE_TEST_DATA=~/work/goose-test-data`,
  in the background, from a detached worktree) before the PR, results read from the XML.

## Review Focus

1. **A controller connects while a finger holds a button** (Automatic hides the overlay mid-press): every button is
   released and both sticks centre. Test: Task 6 `hidingReleasesEverything`.
2. **Port 1's type changes in the game's settings while the game is paused** (the core makes a new controller): no
   press carries over to the new one, and a press held after resume reaches it. Test: Task 4
   `aNewControllerGetsTheHeldState`.
3. **The game stops with a finger down, and another starts:** nothing is held in the new game. Test: Task 4
   `aNewGameStartsWithNothingHeld`.
4. **Analog mode goes off while a thumb is on a stick** (the sticks disappear): that stick centres, and the finger
   presses nothing else as it moves. Test: Task 3 `aControlThatGoesReleasesItsFingers`.
5. **Rotation or a window resize mid-touch** (split-screen, a fold): the layout changes under the finger; nothing
   stays pressed from the old layout. Test: Task 3 `aNewLayoutReleasesEverything`.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `input/TouchPad.kt` (new) | `TouchButton`, `TouchPadState`, `PadKind`: the values sent to the core. |
| `input/TouchLayout.kt` (new) | Geometry: drawn and touch rects of every control for a screen, from the mockup's proportions. |
| `input/TouchTracker.kt` (new) | Pointers to state: D-pad and face sliding, fixed buttons, sticks, release, ticks. |
| `app/TouchControls.kt` (new) | `TouchSettings`, `TouchPrefs` (on `KeyValueStore`), when the overlay shows, the message once. |
| `ui/components/PsGlyph.kt` | Exposes the four face symbols' geometry (`PsFaceSymbols`); `PsGlyph` draws as before. |
| `ui/TouchOverlay.kt` (new) | The overlay composable: draws the layout, feeds `pointerInput` to the tracker, sends state, ticks. |
| `ui/theme/Theme.kt` | `GsColors.touch`, `touchPressed`, `touchLine`. |
| `ui/ControllersScreen.kt`, `app/ControllersPage.kt`, `MainActivity.kt` | The "Touch controls" section. |
| `ui/GameScreen.kt`, `app/AppController.kt`, `MainActivity.kt` | Showing the overlay, releasing on pause/focus loss, picture at top. |
| `core/NativeLibrary.kt`, `core/NativeCallbacks.kt`, `app/GameCoreApi.kt` | `setTouchPad`, `setPictureAtTop`, `onPadKindChanged`, test hook. |
| `cpp/touch_pad.h`, `cpp/touch_pad.cpp` (new) | Applying a `TouchPadState` change to a `Controller`; Port 1's kind. |
| `cpp/android_host.cpp`, `cpp/android_host.h`, `cpp/jni_bridge.cpp`, `cpp/CMakeLists.txt` | Queueing and applying it on the CPU thread, the kind callback, the alignment override. |
| `docs/design-system/tokens.json`, `docs/design-system.md`, `docs/android.md`, `docs/roadmap.md` (+ `ROADMAP.md` if a copy) | Tokens and rules; docs. |

(Kotlin paths are under `android/app/src/main/java/io/github/ilyalissoboi/goosestation/`, tests under
`android/app/src/test/…` and `android/app/src/androidTest/…`; C++ under `android/app/src/cpp/`.)

---

### Task 1: Touch tokens, colours and the overlay's face glyphs

**Files:**
- Modify: `ui/theme/Theme.kt` (GsColors), `ui/components/PsGlyph.kt`, `docs/design-system/tokens.json` (`touch-button`)
- Create: `ui/components/TouchGlyphs.kt`
- Test: `android/app/src/test/…/ui/TouchGlyphsTest.kt`

**Interfaces:**
- Produces: `GsColors.touch: Color = Color(0x24F3F1EC)`, `touchPressed = Color(0x57F3F1EC)`, `touchLine = Color(0x66F3F1EC)`
  (tokens.json's rgba, alpha rounded to /255).
- Produces: `internal object PsFaceSymbols { val names: List<String> /* Triangle, Circle, Cross, Square */ }` in
  `PsGlyph.kt`, built from the same marks `PsGlyph` draws (one source for both).
- Produces: `object TouchGlyphs { fun bounds(name: String, button: Rect): Rect; fun DrawScope.drawTouchGlyph(name: String, button: Rect, color: Color) }`
  — the symbol without its plate, scaled uniformly so its larger extent is `BOX × button.width` and centred on the
  button's centre (bounds centre, not centroid), stroked at `STROKE × button.width`, square caps, mitred, as
  `PsGlyph`. `bounds` includes the stroke. Constants: `BOX = 0.36f`, `STROKE = 0.055f`.

- [ ] **Step 1: Write the failing tests** (`TouchGlyphsTest`)
  - `everyGlyphIsTheSameSize`: for a 50×50 button, the four `bounds` have max(width, height) equal within 0.5;
    Triangle's smaller side ≥ 0.85 of its larger.
  - `everyGlyphIsCentredInItsButton`: each bounds' centre within 0.5 of the button's centre, for buttons 44 and 50.
  - `everyGlyphIsInsideItsButton`: each bounds inside the button's inscribed octagon (corners cut at 29.3%), with at
    least `0.15 × width` margin.
  - `theSymbolsAreThePsGlyphShapes`: `PsFaceSymbols.names == listOf("Triangle", "Circle", "Cross", "Square")`, and each
    name `PsGlyphs.has`.
- [ ] **Step 2:** `cd android && ./gradlew testDebugUnitTest --tests '*TouchGlyphsTest*'` — FAIL (unresolved).
- [ ] **Step 3:** Add the colours; in `PsGlyph.kt` lift the four symbol marks into `PsFaceSymbols` (geometry and
  stroke ink) used by both `face(...)` and `TouchGlyphs`; write `TouchGlyphs`. Update `tokens.json`'s `touch-button`
  to `"50px"`, usage "Each on-screen face button at Size 100% in the phone layout (D-pad arms 40 by 54); the overlay
  scales it with the screen and the Size setting; every touch area stays at least 48 dp."
- [ ] **Step 4:** Same command — PASS. `PsGlyphTest` unchanged and green at the next quick run.
- [ ] **Step 5:** Commit "Android: touch colours, and the face glyphs drawn one size for the touch overlay".

### Task 2: `TouchLayout`

**Files:**
- Create: `input/TouchPad.kt`, `input/TouchLayout.kt`
- Test: `android/app/src/test/…/input/TouchLayoutTest.kt`

**Interfaces:**
- Produces (`TouchPad.kt`): `enum class TouchButton { Up, Right, Down, Left, Triangle, Circle, Cross, Square, Select, Start, L1, R1, L2, R2, L3, R3 }`
  with `val bit: Int get() = 1 shl ordinal`; `data class TouchPadState(val buttons: Int = 0, val leftX: Float = 0f, val leftY: Float = 0f, val rightX: Float = 0f, val rightY: Float = 0f)`
  with `companion val RELEASED = TouchPadState()`; `enum class PadKind { Unsupported, Digital, Analog }` with
  `fun fromCore(kind: Int): PadKind?` (0, 1, 2; null for -1, no game).
- Produces (`TouchLayout.kt`), all in dp, `androidx.compose.ui.geometry.Rect`:
  ```kotlin
  enum class TouchControlId { DPad, Face, L1, L2, R1, R2, Select, Start, Menu, L3, R3, LeftStick, RightStick }
  data class TouchControl(val id: TouchControlId, val drawn: Rect, val touch: Rect)
  data class TouchLayout(val width: Float, val height: Float, val portrait: Boolean, val scale: Float,
      val controls: List<TouchControl>, val dpadArm: Float /* arm width */, val faceButton: Float /* diameter */,
      val faceOffset: Float /* centre to button centre */, val picture: Rect /* where the 4:3 picture sits */) {
      operator fun get(id: TouchControlId): TouchControl?
      fun faceButtonRect(button: TouchButton): Rect  // Triangle, Circle, Cross, Square
      companion object { fun of(width: Float, height: Float, sticks: Boolean, size: Float): TouchLayout } }
  ```
- Reference geometry (scale 1), from the mockup:
  - **Landscape**, edges mirrored left/right: L2 at (12, 14) 88×34, L1 at (12, 54); R2/R1 mirrored. Menu 40×40,
    centred, top 8. Select and Start 64×26, 12 apart, centred, bottom margin 12. Digital: D-pad centre (78, 220 of
    370), arms 40 wide reaching 74 from centre; face centre (W − 78, same y), buttons 50 at offset 50. Analog: D-pad
    centre (78, 160 of 370), arms 36 reaching 60; face buttons 44 at offset 42; sticks 116 across, centres (208, 294 of
    370) and mirrored; L3/R3 32 across, centres (120, 246 of 370) and mirrored (just above each stick's outer edge).
  - **Portrait**, anchored to the bottom: shoulders 66×32 at top H − 450 (L2 x 12, L1 x 84, mirrored), menu 40×40
    centred at the same top; D-pad centre (90, H − 300), arms 40 reaching 74; face centre (W − 90, H − 300), 50 at 50;
    sticks 110 across, centres (90, H − 133) and mirrored; L3/R3 32 across above each stick's outer edge; Select and
    Start 62×26, 8 apart, centred, bottom margin 24. Picture: `Rect(0, 0, W, W × 3/4)`.
- Scale: `fit × size`, `fit` = landscape `clamp(bar / 153, 0.9, 1.4)` with `bar = (W − H × 4/3) / 2`, portrait
  `clamp(W / 360, 0.9, 1.4)`; then capped so the layout fits the height (landscape `H / 370`, portrait
  `(H − W × 3/4) / 470` but never below `0.9 × size`, where the pad overlaps the picture instead). Margins and
  distances from edges scale with it; vertical positions given "of 370" scale with `H / 370`.
- Touch areas: the D-pad's and face's are their clusters' squares (half-size = reach, resp. offset + button / 2),
  sticks' the ring's square, the rest their drawn rect grown to at least 48 in each direction into the gaps; a stacked
  pair (L2 over L1) splits at the gap's middle, the outer one running to the screen edge. *(Builder's call how, as long
  as the tests below hold.)*

- [ ] **Step 1: Write the failing tests** (`TouchLayoutTest`), each over the screens 800×370, 800×360, 640×360,
  960×540, 1280×800 (landscape) and 360×800, 360×780, 411×891, 800×1280 (portrait), sticks on and off, Size 0.75,
  1.0, 1.25:
  - `everyTouchAreaIsAtLeast48`: every control's touch ≥ 48 wide and tall; the face's press zones (Task 3,
    `0.8 × faceOffset` radius) ≥ 48 across; each D-pad sector ≥ 48 across at the reach.
  - `noTouchAreasOverlap` (pairwise, open rects) and `everythingIsOnScreen`.
  - `theFaceDiamondIsSymmetric`: the four `faceButtonRect`s are equal-sized, at `faceOffset` from one centre, up,
    down, left and right.
  - `theDPadMirrorsTheFace`: D-pad centre x = W − face centre x, same y; drawn inside its touch.
  - `sticksOnlyWhenAsked`: LeftStick, RightStick, L3, R3 present iff `sticks`; analog clusters smaller than digital
    and higher (landscape).
  - `landscapeKeepsToTheBars`: at 800×370, size 1, no control's touch area enters the 4:3 picture except the sticks'.
  - `portraitIsUnderThePicture`: at 360×800, 411×891 and 800×1280, size 1, no touch area enters `picture`.
  - `theMockupsPhoneLayouts`: at 800×370 digital, scale 1: D-pad centre (78, 220), face buttons 50 at 50, L2 drawn
    (12, 14, 100, 48), Select drawn width 64; at 360×780 analog: face centre (270, 480), sticks 110 at (90, 647).
- [ ] **Step 2:** `./gradlew testDebugUnitTest --tests '*TouchLayoutTest*'` — FAIL.
- [ ] **Step 3:** Write `TouchPad.kt` and `TouchLayout.kt`.
- [ ] **Step 4:** Same — PASS.
- [ ] **Step 5:** Commit "Android: the touch controls' layouts, landscape and portrait".

### Task 3: `TouchTracker`

**Files:**
- Create: `input/TouchTracker.kt`
- Test: `android/app/src/test/…/input/TouchTrackerTest.kt`

**Interfaces:**
- Consumes: `TouchLayout`, `TouchButton`, `TouchPadState` (Task 2).
- Produces:
  ```kotlin
  class TouchTracker(layout: TouchLayout) {
      var layout: TouchLayout            // set: every finger is released (Review Focus 5, 4)
      val state: TouchPadState
      fun down(pointer: Long, x: Float, y: Float): Outcome
      fun move(pointer: Long, x: Float, y: Float): Outcome
      fun up(pointer: Long): Outcome
      fun cancel(): Outcome              // every finger
      data class Outcome(val state: TouchPadState, val changed: Boolean, val ticks: Int, val menu: Boolean)
  }
  ```
- Rules: a finger belongs to the control whose touch area it went down in, for its life; none, and it does nothing.
  **D-pad:** from its angle to the centre, 8 sectors of 45°, the diagonals pressing both arms; nothing within
  `0.5 × arm` of the centre; it keeps steering outside the area. **Face:** presses every face button whose centre is
  within `0.8 × faceOffset`, so between two neighbours it presses both, at the centre none; outside, none.
  **Shoulders, Select, Start, L3, R3:** pressed until the finger lifts. **Menu:** `menu = true` on its finger's lift
  inside its touch area. **Sticks:** `(p − centre) / radius`, clamped to length 1, until lift (then 0). A button held
  by several fingers stays pressed until the last lifts. `ticks` = buttons that went from up to down in this event
  (not sticks, not the menu). A new `layout` releases everything (and ends every finger).

- [ ] **Step 1: Write the failing tests** (`TouchTrackerTest`, on `TouchLayout.of(800f, 370f, …, 1f)`):
  `dpadArms`, `dpadDiagonalsPressBoth` (up-right at 45°), `dpadCentreIsNothing`, `dpadSlidesAcrossArms` (down on Left,
  move to Up: Left released, Up pressed, 1 tick), `faceSlidesBetweenButtons` (Cross → between Cross and Circle → Circle:
  states Cross; Cross+Circle; Circle; ticks 1, 1, 0), `faceCentreIsNothing`, `aShoulderKeepsItsFinger` (down on L1,
  slide onto L2: still L1 only), `twoFingersAtOnce` (D-pad Up with pointer 1, Cross with pointer 2, R1 with pointer 3:
  all three; lift 2: Up and R1), `oneButtonTwoFingers`, `cancelReleasesEverything` (state == RELEASED, changed),
  `liftingEveryFingerReleasesEverything`, `stickFollowsAndSpringsBack` (half the radius right: leftX 0.5; past the
  ring: length 1; up: 0), `aTouchOnTheRingTakesTheStick`, `theMenuOpensOnItsLift` (and not when lifted outside),
  `ticksOnlyOnPresses`, `aNewLayoutReleasesEverything`, `aControlThatGoesReleasesItsFingers` (on a stick, layout set to
  no sticks: leftX 0; moving that finger presses nothing).
- [ ] **Step 2:** `./gradlew testDebugUnitTest --tests '*TouchTrackerTest*'` — FAIL.
- [ ] **Step 3:** Write `TouchTracker`.
- [ ] **Step 4:** Same — PASS.
- [ ] **Step 5:** Commit "Android: the touch tracker: sliding, diagonals, several fingers, sticks".

### Task 4: The core side: Port 1's touch state, its kind, the picture at the top

**Files:**
- Create: `android/app/src/cpp/touch_pad.h`, `touch_pad.cpp` (add to `CMakeLists.txt`)
- Modify: `android_host.h`, `android_host.cpp` (input queue, `ProcessInputQueue`, `FrameDone`, `OnSystemDestroyed`,
  `LoadSettings`), `jni_bridge.cpp`, `core/NativeLibrary.kt`, `core/NativeCallbacks.kt`, `app/GameCoreApi.kt` (+ fakes
  in `test/…/app/FakeGameCore.kt` and any `GameCoreApi` fakes in androidTest)
- Test: `android/app/src/androidTest/…/core/TouchPadCoreTest.kt` (`@BootsGames`, `@LargeTest`, padtest as
  `GameCallsTest` boots it)

**Interfaces:**
- Produces (Kotlin):
  - `NativeLibrary.setTouchPad(buttons: Int, leftX: Float, leftY: Float, rightX: Float, rightY: Float)`: any thread,
    never blocks; the latest state is applied to Port 1 on the CPU thread's next input pass. Ignored with no game.
  - `NativeLibrary.setPictureAtTop(top: Boolean)`: while true, `[Display] Alignment` is taken as `LeftOrTop`, never
    written; false restores the loaded value.
  - `NativeLibrary.getPadBindStateForTest(pad: Int, name: String): Float` (the controller's `GetBindState` for the
    binding `name`, -1 when none).
  - `NativeCallbacks.onPadKindChanged(kind: Int)`: Port 1's kind (0 unsupported, 1 digital, 2 analog), from the CPU
    thread, at the first frame of each game and on every change; -1 when the game ends.
  - `GameCoreApi.setTouchPad(state: TouchPadState)`, `GameCoreApi.setPictureAtTop(top: Boolean)`.
- Produces (C++, `touch_pad.h`, namespace `TouchPad`): `struct State { u32 buttons = 0; std::array<float, 4> axes{}; }`;
  `void Apply(Controller& controller, const State& from, const State& to)` (each changed bit or half axis, by the
  binding whose `generic_mapping` matches, `SetBindState(bind_index, …)`; inputs the type lacks are skipped);
  `s32 KindOf(const Controller* controller)`.
- Host: a `TouchPad` input event carrying the whole state; `ProcessInputQueue` keeps the latest and applies the
  difference to `System::GetController(0)` from the last applied state; on every pass while anything is held, the
  held inputs are set again (a no-op on the same controller, since `SetBindState` ignores an unchanged value), so a
  controller the core made anew (Review Focus 2) gets them without a pointer comparison that a reused address
  would fool. `OnSystemDestroyed` forgets the applied and pending states
  (Review Focus 3) and reports kind -1. `FrameDone` reports `KindOf(System::GetController(0))` when it changes.
  `Host::LoadSettings` remembers the loaded alignment, then overrides while the flag is on; `SetPictureAtTop` sets the
  flag and `g_settings.display_alignment` on the CPU thread.

- [ ] **Step 1: Write the failing tests** (`TouchPadCoreTest`, padtest running, Port 1 the default DualShock):
  `buttonsReachPortOne` (Cross|L1|Up: those binds 1, the rest 0), `releasingLetsGo` (then 0 state: all 0),
  `aStickReachesPortOneInAnalogMode` (after `toggleAnalogMode()` and kind 2 reported: leftX 1 → `LRight` > 0, `LLeft`
  0), `theKindFollowsAnalogMode` (1 at start, 2 after the toggle, 1 after the second), `anotherTypeIsUnsupported`
  (`setPortType(0, "NeGcon")` while running: kind 0), `anAnalogJoystickIsAnalog`, `aNewControllerGetsTheHeldState`
  (Cross held, port type changed to DigitalController and back: Cross 1 on the new controller within a few frames,
  with nothing sent again; nothing else held), `aNewGameStartsWithNothingHeld` (Cross held, game stopped, booted again: Cross 0, kind
  reported -1 then 1), `thePictureGoesToTheTop` (`FrameReader(360, 800)` as the surface: with `setPictureAtTop(true)`
  row 10 has picture pixels and row 790 is black; false: row 10 black again; `settings.ini`'s `[Display] Alignment`
  unchanged).
- [ ] **Step 2:** Run one: `cd android && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.core.TouchPadCoreTest`
  on emulator-5580 (with the BIOS pushed) — FAIL (unresolved).
- [ ] **Step 3:** Write `touch_pad.*`, the host changes, the JNI functions and callback (cache the method ID as
  `onFastForwardChanged` does; `proguard-rules.pro` already keeps `NativeLibrary` and `NativeCallbacks` members).
- [ ] **Step 4:** Same — PASS (each test, XML read); `testDebugUnitTest` green (fakes updated).
- [ ] **Step 5:** Commit "Android: the touch pad drives Port 1 in the core, which reports its kind".

### Task 5: Touch settings: `TouchControls` and the Controllers page section

**Files:**
- Create: `app/TouchControls.kt`
- Modify: `ui/ControllersScreen.kt`, `app/ControllersPage.kt` (or `AppController`, wherever the hub's state is made),
  `MainActivity.kt` (the hub's call)
- Test: `android/app/src/test/…/app/TouchControlsTest.kt`; `androidTest/…/ui/ControllersScreenTest.kt`,
  `FocusAuditTest.kt`, `OrientationLayoutTest.kt`

**Interfaces:**
- Consumes: `KeyValueStore`, `SharedPreferencesStore(context, "touch")` (`storage/FolderPrefs.kt`), `PadKind`.
- Produces:
  ```kotlin
  enum class TouchMode { Automatic, Always, Off }
  data class TouchSettings(val mode: TouchMode = TouchMode.Automatic, val size: Int = 100, val opacity: Int = 100, val vibration: Boolean = true)
  class TouchPrefs(store: KeyValueStore) { fun load(): TouchSettings; fun save(settings: TouchSettings): Boolean }
  object TouchVisibility {
      /** Whether the overlay shows, and with sticks. */
      fun of(settings: TouchSettings, controllers: Int, inGame: Boolean, menuOpen: Boolean, kind: PadKind?): Shown?
      data class Shown(val sticks: Boolean) }
  class TouchControls(prefs: TouchPrefs, post: (String) -> Unit) {
      var settings: TouchSettings  // Compose state; set saves
      var kind: PadKind?           // from onPadKindChanged
      fun shown(controllers: Int, inGame: Boolean, menuOpen: Boolean): TouchVisibility.Shown?
      fun gameStarted() }          // the Unsupported message, once per game start
  ```
- Rules: Off → null. Automatic → only with 0 controllers. Not in a game, menu open, kind null or Unsupported → null.
  Sticks iff kind Analog. Stored keys `mode` (`"Automatic"`, …), `size`, `opacity`, `vibration`; unknown or
  out-of-range values read as the defaults; size and opacity snapped to their steps. The message is posted the first
  time after `gameStarted()` that the kind is Unsupported while the overlay would otherwise show.
- Screen: `ControllersScreen(…, touch: TouchSettings, onTouch: (TouchSettings) -> Unit, …)`: a "Touch controls"
  section after "Controllers", before "Ports": the cycler (with its note), `NumberRow` "Size" (`TOUCH_SIZE_SCALE =
  NumberScale.Range(75, 125, 5, listOf(75, 100, 125)) { "$it%" }`), `NumberRow` "Opacity" (`NumberScale.Range(25, 100,
  5, listOf(25, 50, 75, 100)) { "$it%" }`), `SwitchRow` "Vibration" with its note.

- [ ] **Step 1: Write the failing tests.** JVM `TouchControlsTest`: `defaults`, `roundTrips`, `badValuesReadAsDefaults`,
  `offNeverShows`, `automaticHidesWithAController` (1 controller: null; 0: shown), `alwaysShowsWithAController`,
  `notOverTheMenu`, `noOverlayForOtherTypes`, `sticksOnlyInAnalog`, `theMessageOnceAGame` (two kind reports, one
  post; after `gameStarted()` again, one more), `noMessageWhenOff` and `noMessageWithAControllerInAutomatic`.
  Instrumented: `ControllersScreenTest.touchRowsChangeTheSettings` (D-pad to the cycler, Right: Always; Size Right:
  105; Vibration Enter: off; each through `onTouch`), `touchSectionComesBeforePorts`; `FocusAuditTest.controllersWithTouchRows`
  (the hub with no controllers); `OrientationLayoutTest` covers the hub with the new rows (its existing Controllers
  check gains the parameters).
- [ ] **Step 2:** `./gradlew testDebugUnitTest --tests '*TouchControlsTest*'` — FAIL.
- [ ] **Step 3:** Write `TouchControls.kt`; add the section; `AppController` makes `TouchControls` with
  `SharedPreferencesStore(context, "touch")` and `osd::post`, and the hub call passes `touch` and `onTouch`.
- [ ] **Step 4:** JVM PASS; `run-instrumented-tests.sh quick` (or the three classes) green, XML read.
- [ ] **Step 5:** Commit "Android: touch controls settings on the Controllers page".

### Task 6: `TouchOverlay`

**Files:**
- Create: `ui/TouchOverlay.kt`
- Test: `androidTest/…/ui/TouchOverlayTest.kt`, `androidTest/…/ui/OrientationLayoutTest.kt`

**Interfaces:**
- Consumes: `TouchLayout`, `TouchTracker`, `TouchPadState`, `TouchGlyphs`, `GsColors.touch*`.
- Produces:
  ```kotlin
  fun interface TouchSink { fun send(state: TouchPadState) }
  fun interface Ticker { fun tick() }
  @Composable fun TouchOverlay(sticks: Boolean, size: Int, opacity: Int, sink: TouchSink, ticker: Ticker?, onMenu: () -> Unit, modifier: Modifier = Modifier)
  fun deviceTicker(context: Context): Ticker   // Vibrator EFFECT_TICK; a no-op without a vibrator
  ```
- Behaviour: fills its box; `TouchLayout.of(maxWidth, maxHeight, sticks, size / 100f)` with `BoxWithConstraints`; a
  new layout goes to the tracker (which releases). One `pointerInput` on the whole box reads every pointer's changes
  (`awaitPointerEventScope`, `PointerEventPass.Main`), consumes them, and feeds the tracker in event order; a cancelled
  gesture (the scope ending, or `PointerEventType` cancel) calls `cancel()`. Each `changed` state goes to `sink`
  straight away, in the event handler, and `ticker?.tick()` once per event with `ticks > 0`. `DisposableEffect`
  `onDispose` sends `RELEASED` if anything is held (Review Focus 1). Each control is a `Box` at its drawn rect with
  `testTag("touch-<TouchControlId>")` (face buttons `touch-<TouchButton>`, glyphs `touch-glyph-<name>`), hidden from
  accessibility, drawing its shape in `touch` / `touchPressed` with a 1.5 dp `touchLine` edge; labels (L1, L2, R1, R2,
  SELECT, START, L3, R3) in `labelMedium`, `touchLine`; the D-pad arms' arrowheads drawn, not characters; the menu
  button `GsIcons.pause` in `touchLine`; sticks a ring and a knob (40%) at the deflection. `graphicsLayer(alpha =
  opacity / 100f)` on the whole overlay. Requests unbuffered touch dispatch on its view
  (`View.requestUnbufferedDispatch(InputDevice.SOURCE_TOUCHSCREEN)`, API 30+) so moves aren't held for the next frame.

- [ ] **Step 1: Write the failing tests** (`TouchOverlayTest`, a recording `TouchSink` and counting `Ticker`, at
  800×370 dp via `DeviceConfigurationOverride.ForcedSize`): `aTapPressesAndReleases` (Cross: Cross bit, then 0;
  1 tick), `slidingAcrossTheFace` (`performTouchInput { down(cross); moveTo(between); moveTo(circle); up() }`: Cross;
  Cross|Circle; Circle; 0), `dpadDiagonal`, `twoFingers` (`down(0, up)`, `down(1, cross)`, `up(0)`: Up|Cross then
  Cross), `aCancelReleases` (`cancel()`: last send RELEASED), `hidingReleasesEverything` (finger on R1, composable
  removed: last send RELEASED), `noTickWhenOff` (ticker null), `theMenuButtonOpensTheMenu`, `aStickFollowsTheThumb`
  (sticks on: right half radius → leftX ≈ 0.5; up → 0), `theGlyphsAreOneSize` (the four `touch-glyph-*` nodes' bounds:
  equal larger side within 1 dp, each inside its `touch-<button>` node), `opacityFades` (opacity 50: the root layer's
  alpha 0.5, by the `graphicsLayer` semantics or a test tag check).
  `OrientationLayoutTest.theTouchControls`: at 800×360, 640×360, 960×540, 1280×800, 360×800, 411×891, 800×1280, sticks
  on and off, font 1.0 and 1.3: every tagged control on screen, labels not cut or overlapping (the existing
  `assertTextsLaidOut`), drawn nodes as `TouchLayout` says within 1 dp.
- [ ] **Step 2:** Run `TouchOverlayTest` on emulator-5580 — FAIL (unresolved).
- [ ] **Step 3:** Write `TouchOverlay.kt`.
- [ ] **Step 4:** Both classes green on the emulator; XML read.
- [ ] **Step 5:** Commit "Android: the touch overlay: drawn from the layout, driven by every finger".

### Task 7: Showing it in the game

**Files:**
- Modify: `ui/GameScreen.kt`, `MainActivity.kt`, `app/AppController.kt`
- Test: `androidTest/…/ui/GameScreenTouchTest.kt` (new; `GameScreen` with fakes, no core), `test/…/app/…` as needed

**Interfaces:**
- Consumes: `TouchControls`, `TouchOverlay`, `GameCoreApi.setTouchPad/setPictureAtTop`, `ControllerInput.connected`.
- `GameScreen(…, touch: TouchVisibility.Shown? = null, touchSettings: TouchSettings = TouchSettings(), touchSink: TouchSink = TouchSink {}, ticker: Ticker? = null, onPictureAtTop: (Boolean) -> Unit = {})`:
  draws `TouchOverlay` over the surface and the OSD, under the menu, while `touch != null` (`onMenu = onOpenMenu`); in
  portrait (`maxHeight > maxWidth`) calls `onPictureAtTop(touch != null)` on change, and false on leaving.
- `AppController`: `touchControls` (Task 5) gets `onPadKindChanged` (main thread) and `gameStarted()` with each
  `onGameStarted`; `MainActivity` passes `touchControls.shown(controllers.connected.size, inGame, promptOpen)`, the
  sink `NativeLibrary::setTouchPad` (via `GameCoreApi`), `deviceTicker` when `vibration`. `onPause` and focus loss
  in a game also `setTouchPad(RELEASED)` (the overlay's own state is reset by the cancel Android sends).

- [ ] **Step 1: Write the failing tests** (`GameScreenTouchTest`): `theOverlayShowsWhenToldTo` (and not when null),
  `notUnderTheMenu` (menuOpen: no `touch-*` nodes), `itsMenuButtonOpensTheMenu` (`onOpenMenu` called), `pictureAtTopInPortrait`
  (360×800 with touch: true; touch null: false), `notInLandscape` (800×360: never true), `goingAwayReleases` (touch
  set null with a finger down: RELEASED sent).
- [ ] **Step 2:** Run it — FAIL.
- [ ] **Step 3:** Wire it.
- [ ] **Step 4:** Green on the emulator; then `assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs` and
  `run-instrumented-tests.sh quick` (with `GOOSE_TEST_DATA`) green, XML read. Walk through on emulator-5580 with
  padtest and no controller: landscape and a portrait window (`adb shell wm size` on the emulator), screenshots read;
  the test controller (`input` gamepad events) hides it in Automatic.
- [ ] **Step 5:** Commit "Android: the touch controls show in a game with no controller".

### Task 8: Docs, full tier, push

**Files:**
- Modify: `docs/design-system.md` (touch controls out of "Not built yet"; a `TouchOverlay` component section: what it
  draws, tokens, octagons/rects, glyph rule, 48 dp, touch-only and not focusable, shown only without a controller in
  Automatic; the `touch-button` value), `docs/android.md` (Controllers: the Touch controls section and behaviour;
  "Results on the emulator" entry; the user's real-phone check as a step), `docs/roadmap.md` item 16 (and `ROADMAP.md`
  if it is a separate copy) gains "touch controls for phones and tablets without a controller".

- [ ] **Step 1:** Write the docs; `checkNoWindowDialogs ktlintCheck` green.
- [ ] **Step 2:** `GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh full` in the
  background from a detached worktree; read every part's XML (counts, failures, the GL ES part).
- [ ] **Step 3:** Commit "Docs: Android's touch controls", push to `origin/claude/touch-controls`.
- [ ] **Step 4:** `superpowers:finishing-a-development-branch`; the PR only on the user's word.
