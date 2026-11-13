# Design System Touch Targets, Motion and Shadows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the app up to `docs/design-system.md`:
- cyclers at 48 dp;
- focus and toggles at 120 ms;
- overlays fading in at 160 ms;
- `shadow-overlay` defined, and cast by the floating panels.

**Architecture:**
- **`ui/theme/Motion.kt`** holds the durations.
- **The focus fade** is an animated alpha inside `focusRing`/`focusStop`, passed to `focusIndicator` and the fill.
- **`SwitchRow`** animates its thumb's offset and its colours.
- **The overlay fade:**
  - `OverlayPanel` fades in once, on its first composition.
  - The pause menu fades in only when the menu opens, keyed to state that survives being composed again.
- **The shadow** is a `GsColors.shadow` colour, with `Modifier.shadow(8.dp, GsShapes.large, …)` on the floating panels.

**Tech Stack:** Kotlin, Jetpack Compose (animation, ui-test), JUnit 4, ktlint.

**Spec:** `docs/superpowers/specs/2026-11-13-android-design-system-motion-design.md`

## Global Constraints

- **Durations:** focus and toggles 120 ms; dialogs fade in at 160 ms; nothing fades out.
- **`shadow-overlay`:** 8 dp elevation, black at 60% (dark; 25% for a future light theme, in the doc and
  `tokens.json` only), following `radius-lg`.
- **Who casts it:** `OverlayPanel`'s panel, `GameMenu`'s `MenuPanel` and `SidePanel`. Nothing else.
- **Reduce motion** relies on Compose following the system animator scale. The tests pass their own
  `MotionDurationScale`.
- **Unchanged:**
  - no motion over a running game;
  - focus moves into a panel on its first frame, as now;
  - only the ring and the focused fill fade, not other focus tints.
- **`docs/design-system.md` changes only where the spec says,** along with `docs/design-system/tokens.json`.
- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- **Checks:**
  - every task: `./gradlew assembleDebug testDebugUnitTest ktlintCheck`;
  - the named `ui` classes on emulator-5580 with `-Pgoose.orchestrator=false`, reading the XML;
  - at the end: the CI tasks, and the quick tier from a detached worktree with the emusync AAR copied in.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A focus ring that never fully shows, or lingers on the element that lost focus,** when focus moves quickly
   (holding the D-pad down a list).
2. **The pause menu fading again** when it's composed again after the in-game settings, or after a layout change.
3. **A test that read a ring, fill or colour on the frame after a key press,** and now reads a half-faded one.
   Every changed test says why.
4. **A layout at 640 × 360 or at 1.3× text that a 48 dp cycler pushes off-screen,** or that no longer reaches focus.
   `OrientationLayoutTest` and `FocusAuditTest` cover this in the quick tier.
5. **The shadow clipped,** or drawn outside the panel's rounded corners, or cast twice where an `OverlayPanel` holds a
   `SidePanel`.

---

### Task 1: Cyclers at 48 dp

**Files:**
- modify `ui/components/SettingsRows.kt` (`Cycler`, `CyclerArrow`);
- test: `androidTest/.../ui/SettingsRowsTest.kt`, and `NumberRowTest.kt` for its comment.

- [ ] **Step 1: Add `aCyclersArrowsAndWellAreTouchSized`:** a `CyclerRow` whose two arrows (found by their test tags,
  added in Step 3 as `"cycler-back"` and `"cycler-forward"`) are each `assertWidthIsAtLeast(48.dp)` and
  `assertHeightIsAtLeast(48.dp)`, and whose well (`"cycler-well"`) is `assertHeightIsAtLeast(48.dp)`.
- [ ] **Step 2: Run** `SettingsRowsTest` on the emulator. Expected: the new test fails, because the tags don't exist
  yet.
- [ ] **Step 3: Make the cycler touch-sized.** Add the tags, then set `CyclerArrow` to `size(48.dp)` and `Cycler` to
  `heightIn(min = 48.dp)`. Update `NumberRowTest`'s tap comment.
- [ ] **Step 4: Run** `SettingsRowsTest`, `NumberRowTest`, `GameRowsTest` and `PortScreenTest`. Expected: all pass.
- [ ] **Step 5: Commit:** "Android: cyclers' arrows and well at the 48 dp touch size".

### Task 2: Focus and toggles at 120 ms

**Files:**
- create `ui/theme/Motion.kt`;
- modify `ui/components/FocusRing.kt` and `ui/components/SettingsRows.kt` (`SwitchRow`'s toggle);
- test: `androidTest/.../ui/SettingsRowsTest.kt` and `FocusRingTest.kt`.

**Interfaces (produced):** `object Motion { const val FOCUS_MS = 120; const val TOGGLE_MS = 120; const val DIALOG_MS = 160 }`.
`focusIndicator(focused: Boolean, …)` gains `alpha: Float = 1f`, scaling the ring's colour.

- [ ] **Step 1: Write the tests.** Each takes its own rule,
  `createComposeRule(effectContext = MotionDurationScale(scale))`, with `mainClock.autoAdvance = false`:
  - `aToggleSlidesOver120ms` (scale 1): after clicking a `SwitchRow`, the thumb's left (tag `"switch-thumb"`) is
    strictly between its two end positions at 60 ms, and at the far end by 140 ms;
  - `withAnimationsOffAToggleJumps` (scale 0): the thumb is at the far end one frame after the click;
  - `theFocusRingFadesIn` (`FocusRingTest`, scale 1): a ring pixel is partly drawn at 60 ms and fully drawn by 140 ms,
    sampled from `captureToImage()` the way `FocusRingTest` already reads pixels.

  If the v2 `createComposeRule` takes no `effectContext`, use the v1 one for these tests only, and ledger it.
- [ ] **Step 2: Run them.** Expected: the timed ones fail, since nothing animates yet.
- [ ] **Step 3: Write the motion.**
  - **Focus:** in `focusRing` and `focusStop`, `animateFloatAsState(if (focused) 1f else 0f, tween(Motion.FOCUS_MS))`.
    It drives the ring (`focusIndicator`'s new `alpha`) and the fill (`focusedFill.copy(alpha = fill.alpha * a)`).
    `ring = false` elements that call `focusIndicator` themselves get the same animated alpha from their own focus.
  - **Toggles:** in `SwitchRow`, the thumb's offset uses `animateDpAsState`, and the track's and thumb's colours use
    `animateColorAsState`, all with `tween(Motion.TOGGLE_MS)`.
- [ ] **Step 4: Run** `SettingsRowsTest`, `FocusRingTest`, `GameMenuTest`, `TouchOverlayTest`, `PsGlyphTest` and
  `FocusAuditTest`. Expected: all pass. Where an existing test read a colour too early, it waits, with a comment.
- [ ] **Step 5: Commit:** "Android: focus fades and toggles slide over 120 ms".

### Task 3: Overlays fade in at 160 ms

**Files:**
- modify `ui/components/OverlayPanel.kt` and `ui/GameMenu.kt`;
- test: `androidTest/.../ui/OverlayPanelTest.kt` and `GameMenuTest.kt`.

- [ ] **Step 1: Write the tests:**
  - `anOverlayShowsItsFocusedContentOnItsFirstFrame` (scale 1, `autoAdvance = false`): one frame after showing, the
    panel's first button is focused and in the semantics tree;
  - `anOverlayFadesIn`: the panel is partly transparent at 80 ms and fully opaque by 180 ms, read from pixels;
  - `theMenuDoesntFadeAgainWhenComposedAgain` (`GameMenuTest`): with the menu open and its fade finished, composing it
    again (as after the game's settings, `cameFromSettings`) gives an opaque menu on the first frame.
- [ ] **Step 2: Run them.** Expected: the fade tests fail.
- [ ] **Step 3: Write the fade.**
  - **`OverlayPanel`:** an `Animatable(0f)`, animated to 1 over `Motion.DIALOG_MS` in a `LaunchedEffect(Unit)`. It
    drives `graphicsLayer { alpha }` on the scrim and the panel.
  - **`GameMenu`:** the same, keyed to the menu opening. An `opened` flag kept above the composition that's rebuilt,
    such as `rememberSaveable` in the caller or a value in the menu's state, so being composed again doesn't fade
    it. Side panels don't fade.
- [ ] **Step 4: Run** `OverlayPanelTest`, `GameMenuTest`, `ResumePromptTest` (if present), `UpdateBannerTest` (if
  present) and `FocusAuditTest`. Expected: all pass.
- [ ] **Step 5: Commit:** "Android: overlays and the pause menu fade in over 160 ms".

### Task 4: `shadow-overlay`, and the doc

**Files:**
- modify `ui/theme/Theme.kt` (`GsColors.shadow`), `ui/components/OverlayPanel.kt`, `ui/GameMenu.kt` (`MenuPanel`)
  and `ui/components/SidePanel.kt`;
- modify `docs/design-system.md` and `docs/design-system/tokens.json`.

- [ ] **Step 1: Add the token.** `GsColors.shadow = Color(0x99000000)`, which is black at 60%. In `tokens.json`,
  `shadow-overlay` gets dark `#00000099` and light `#00000040`, with a note giving the 8 dp elevation and the
  `radius-lg` shape.
- [ ] **Step 2: Cast it.** Add `Modifier.shadow(8.dp, GsShapes.large, clip = false, ambientColor = colors.shadow,
  spotColor = colors.shadow)` before each panel's `background`, and on the panel only, never the scrim.
- [ ] **Step 3: Update the doc.**
  - `:103` gains the definition.
  - The colour table gains a `shadow-overlay` row.
  - `:150` names what fades: the focus ring and fill, the toggles, an overlay and the pause menu as they open.
  - `:145` gains the nested-corner line: the outer radius less the padding, and never under 2 dp.
- [ ] **Step 4: Take screenshots** on emulator-5580 at 960 × 540 of a settings page with a focused cycler, an
  `OverlayPanel` (the update prompt or a question), and the pause menu with a side panel open. Use the existing
  screenshot tests' `captureToImage` pattern with `leaveApksInstalledAfterRun`, or adb `screencap` of the app. Look
  at each, and ledger what you saw.
- [ ] **Step 5: Check reduce motion by hand.** Run `adb -s emulator-5580 shell settings put global
  animator_duration_scale 1`, open a settings page and toggle a switch: it slides. Set the scale to 0: it jumps. Put
  the scale back to what it was before. Ledger it.
- [ ] **Step 6: Commit:** "Android: shadow-overlay on the floating panels, and the design system to match".

### Task 5: Whole-branch checks

- [ ] **Step 1: Run** the CI tasks, and the quick tier from `git worktree add --detach /tmp/goose-motion HEAD`, with
  the emusync AAR copied in. Expected: all pass. `SearchFieldTest.theSearchActionClosesTheKeyboard` is a known tier
  failure, with its own proposed task: if it fails, it's noted, not fixed here.
