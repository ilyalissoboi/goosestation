<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android choice lists and PlayStation button glyphs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A on a settings row with a few values opens its choices as a list in the page, and the Controllers page
shows Port 1's inputs as PlayStation button glyphs.

**Architecture:** `CyclerRow` gains an open state that draws the choices under its label line. `SettingsPage`
provides one `ChoiceListHost` per page, which keeps at most one list open and closes it on a tap outside it. A new
`PadGlyph` component draws the 24 glyphs from `docs/design-system/pad-glyphs/` in Compose. It colours the four face
symbols with four new tokens. A new native call gives Port 1's controller type, and the Controllers page uses glyphs
only for `DigitalController` and `AnalogController`.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2026.09.00), Material 3, C++20 and JNI for the one native call, JUnit 4,
kotlinx-coroutines-test, Compose UI tests on the emulator.

**Spec:** [docs/superpowers/specs/2026-10-31-android-choice-lists-and-pad-glyphs-design.md](../specs/2026-10-31-android-choice-lists-and-pad-glyphs-design.md),
in full. Its *(writer's call)* items were settled with the user on 2026-10-31. The user chose our own glyphs over
Kenney's and Xelu's packs.

## Global Constraints

- The design system is binding: tokens only (no `Color(0x…)` literals or font sizes in screens), `focusRing` on
  everything activatable, 4 dp of room for the ring in clipping containers, and focus on open.
- No window dialogs, popups or overlays: the list is part of the page.
- Nothing changes until A on a choice; B, Back and a tap on the label line change nothing.
- Glyph colours, dark theme (the app's only one): `ps-triangle` `#3fd6a6`, `ps-circle` `#ff6b6b`, `ps-cross`
  `#7aa7ff`, `ps-square` `#f28ad6`. Light, in tokens.json only: `#0b7a5a`, `#c42b2b`, `#2b57c4`, `#a8338e`.
- Glyphs are 32 dp tall at font scale 1 (the SVGs' 24 units × 4/3), and scale with the font scale.
  They're used only on Port 1 rows, and only when the Port 1 type is `DigitalController` or `AnalogController`.
- The clean room applies: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app; no
  third-party art.
- Every commit message is shown to the user before committing.
- Commands, from `android/`:
  - JVM tests: `./gradlew testDebugUnitTest`
  - lint, ktlint and detekt: `./gradlew check`
  - one instrumented class: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`
  - all instrumented tests: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`
  - formatting: `./gradlew ktlintFormat`

## Review Focus

1. An A held from the previous screen, or the A that opened the list: it must not also pick a choice (the open
   list's choices see that A's release). Pinned in Task 1: `theAThatOpensDoesntPick`.
2. A list taller than the screen (a BIOS page with many images, a phone in landscape): the focused choice stays in
   view and nothing is cut. Pinned in Task 1: `OrientationLayoutTest.biosWithItsListOpen`.
3. The system Back gesture, not just the B button, while a list is open: it closes the list, not the page. Pinned in
   Task 1: `systemBackClosesTheListNotThePage`.
4. The controller picker's choices changing while its list is open, when a pad is unplugged. Pinned in Task 1:
   `choicesChangingWhileOpen` (generic over `CyclerRow`).
5. Glyph rows at 1.3× font scale on a 640 × 360 phone: wide glyphs make the row stack, and none is cut. Pinned in
   Task 4: `OrientationLayoutTest.controllersWithGlyphsAtLargeText`.

---

### Task 1: The choice list in `CyclerRow`

**Files:**
- Modify: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/components/SettingsRows.kt`
  (`SettingsPage`, `CyclerRow`)
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/components/ChoiceList.kt`
- Test: `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/ui/SettingsRowsTest.kt`,
  `OrientationLayoutTest.kt`, `FocusAuditTest.kt`
- Docs: `docs/design-system.md` (the `CyclerRow` entry and a "Choice list" entry under the components)

**Interfaces:**
- Produces:
  - `class ChoiceListHost` with `val open: Any?`, `fun open(key: Any)` and `fun close()`. A page has one; opening
    one list closes any other.
  - `val LocalChoiceListHost: ProvidableCompositionLocal<ChoiceListHost?>`, provided by `SettingsPage`.
  - `CyclerRow`'s signature is unchanged. Its A, Enter, D-pad centre or tap opens the list instead of stepping.

- [ ] **Step 1: Write the failing tests** in `SettingsRowsTest`, on its existing page (Size: Small, Medium, Large;
  Colour: Red, Green with the note "The default"; the Honk switch).
  - Replace `enterStepsForwardRoundToTheFirst` and `buttonAStepsForwardToo` with these:
    - `aOpensTheListOnTheCurrentChoice`: A on Size shows "Small", "Medium" and "Large", with "Small" focused, and
      `sizesPicked` is empty.
    - `enterOpensItToo`: as the test above, with Enter.
    - `upAndDownMoveAndStopAtTheEnds`: open the list, then Down ×5: "Large" is focused. Up ×5: "Small" is focused.
      `sizesPicked` stays empty throughout.
    - `aPicksAndCloses`: open, Down, A: `sizesPicked == ["M"]`; the list is gone ("Large" doesn't exist); the focus
      is on the Size row, which has "Medium".
    - `aOnTheCurrentChoiceChangesNothing`: open, then A: `sizesPicked` is empty and the list is closed.
    - `bClosesWithoutAChange`: open, Down, B: `sizesPicked` is empty, the list is closed, the focus is on Size, and
      `backs == 0`.
    - `systemBackClosesTheListNotThePage`: as `bClosesWithoutAChange`, with `Key.Back`.
    - `leftAndRightDoNothingWhileOpen`: open, Right, Left: the focus stays on "Small" and nothing is picked.
    - `theFocusCantLeaveTheList`: open Size, Down ×3, then Down: the focus is still on a choice ("Large"), not on
      Colour.
    - `eachChoiceShowsItsNote`: open Colour: "The default" shows under "Green" inside the list, and "Green" is
      current (focused on open).
    - `theAThatOpensDoesntPick`: A down, then wait 100 ms, then A up on Size: the list is open with "Small" focused,
      and nothing is picked.
    - `aValueNotAmongTheChoices`: `show(size = "XL")`, then open: no choice is marked current, and "Small" is
      focused.
    - `choicesChangingWhileOpen`: make the Size choices a `mutableStateListOf`. Open with "Medium" focused, then
      remove "Medium": the focus goes to "Small" (the current one). Then clear the list: the list closes and the
      focus is on the row.
    - `aTapOnTheLabelLineCloses`: open Size by A, tap the "Size" label: the list is closed and nothing is picked.
    - `aTapElsewhereClosesAndActs`: open Size, then tap Honk: the list is closed and `toggles == [true]`.
    - `openingAnotherClosesTheFirst`: tap Size's row to open its list, then tap Colour's row: only Colour's choices
      show.
  - Keep `rightStepsToTheNextChoiceAndKeepsTheFocus`, `leftStepsBack` and `theArrowsStopAtTheEnds` unchanged: the
    closed row still steps.
  - Test the current mark through semantics: each choice is `Role.RadioButton` with `selected`, so
    `assertIsSelected()` works. The row reports expanded or collapsed (`SemanticsProperties.ExpandedState` or a state
    description, builder's call), asserted in `aOpensTheListOnTheCurrentChoice`.

- [ ] **Step 2: Run them to see them fail**
  Run: the `SettingsRowsTest` class on the emulator.
  Expected: the new tests FAIL. They fail because A and Enter step instead of opening a list, or with "no node with
  text Large"; not with compile errors. The three kept stepping tests pass.

- [ ] **Step 3: Implement the list**
  - `ChoiceListHost` and `LocalChoiceListHost` go in `ChoiceList.kt`, with `ChoiceList`, the private composable that
    draws the open list.
  - `SettingsPage` remembers one host and provides it. In a pointer-input pass, it closes the host's open list on any
    press outside that list's bounds, without consuming the press, so the tap still acts.
  - `CyclerRow` opens through the host, keyed by its own `remember`ed key. With no host (a `CyclerRow` outside a
    `SettingsPage`), it keeps its own open state.
  - The list is drawn under the label line: on `sunken` at `GsShapes.medium`, with a 1 dp `lineStrong` edge, inset
    16 dp. Each choice is at least 48 dp tall: its label in `titleMedium` and its note in `bodyMedium` `inkMuted`.
    Each has `focusRing` (lifting to `raised`). The current choice is on `beakSoft` with `GsIcons.check` in `beakInk`
    at the end. Room for the ring inside the well is 4 dp.
  - **Focus:**
    - On open, the current choice, else the first.
    - Up and down stop at the ends (`focusProperties` exit, or key handling at the list).
    - Left and right are taken and ignored.
    - A `BackHandler(enabled = open)` in the row closes the list. It's composed after the page's own, so it wins.
      The page's B goes through that handler.
    - On close, focus goes back to the row.
  - The open list is brought into view with `BringIntoViewRequester` when it opens. After that, centred scrolling
    follows the focus.
  - The row hides its arrows while open (builder's call: hidden or faint).

- [ ] **Step 4: Run them to see them pass**
  Run: the `SettingsRowsTest` class.
  Expected: PASS, every test.

- [ ] **Step 5: Add an open list to the layout and focus audits**
  - `OrientationLayoutTest.biosWithItsListOpen`: the BIOS page with the long-names data of `biosWithLongNames`,
    with a region's list opened by A. On each of its sizes, it moves Down through every choice, using
    `pressUntilFocusedInView`. The focused choice is in view each time, and no text is cut.
  - `OrientationLayoutTest.graphicsWithEachListOpen`: open each Graphics row's list in turn, and check as above.
  - `FocusAuditTest.settingsGraphicsWithAListOpen`: the Graphics page with its first list open passes the audit.
  - Run each class. Expected: PASS.

- [ ] **Step 6: Update `docs/design-system.md`**
  - The `CyclerRow` entry: left and right step; A, Enter or a tap opens the list.
  - A new "Choice list" entry, describing the look in Step 3 and the keys in the spec's Behaviour section.

- [ ] **Step 7: Run the JVM tests and `check`**, then commit.
  Run: `./gradlew ktlintFormat testDebugUnitTest check`. Expected: BUILD SUCCESSFUL.
  Commit message (shown to the user first): `Android: A on a setting opens its choices as a list in the page`

### Task 2: Port 1's controller type, from the core

**Files:**
- Modify: `android/app/src/cpp/android_host.h`, `android_host.cpp`, `jni_bridge.cpp`
- Modify: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/core/NativeLibrary.kt`,
  `app/CoreApi.kt`, `app/ControllersPage.kt`
- Test: `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/core/ControllerBindingsTest.kt`,
  `android/app/src/test/java/io/github/ilyalissoboi/goosestation/app/ControllersPageTest.kt`, `FakeCoreApi.kt`

**Interfaces:**
- Produces:
  - `std::optional<std::string> AndroidHost::GetPort1ControllerType()`: Port 1's type name as `GetPort1ControllerInfo`
    resolves it, its `name` (`"AnalogController"`, `"DigitalController"`, …). It's nullopt before `Initialize()`
    succeeded, or for an unknown type.
  - `external fun getPort1ControllerType(): String?` on `NativeLibrary`.
  - `fun getPort1ControllerType(): String?` on `CoreApi`, and on `FakeCoreApi` (a settable `var port1Type: String? =
    "AnalogController"`).
  - `ControllersState.port1Type: String? = null`, read with the bindings in `readBindings`.

- [ ] **Step 1: Write the failing tests**
  - `ControllerBindingsTest.port1TypeIsTheSettingsOrTheDefault`: with no `[Pad1] Type`, it's
    `Controller::GetDefaultPadType(0)`'s name. Assert that Kotlin sees `"AnalogController"`, after confirming that
    this is the core's default in `src/core/controller.cpp`; if it isn't, assert the actual default and note it.
    With `[Pad1] Type = DigitalController` written and settings applied, it's `"DigitalController"`.
  - `ControllersPageTest.thePort1TypeIsReadWithTheBindings`: with `api.port1Type = "PlayStationMouse"`, after `open`
    and the first read, `state.port1Type == "PlayStationMouse"`.

- [ ] **Step 2: Run them to see them fail.** Expected: the build fails because `getPort1ControllerType` and
  `port1Type` don't exist. Add stubs returning null so the tests compile, then run again. Expected: FAIL on the values.

- [ ] **Step 3: Implement it.** The native call runs on the CPU thread like `GetPadBindings` (`EmuThread::RunOnThread`,
  blocking, under the settings lock). JNI returns a Java string or null. `readBindings` reads it in the same
  `withContext(core)` block.

- [ ] **Step 4: Run them to see them pass.** Run: `ControllerBindingsTest` and the JVM tests. Expected: PASS.

- [ ] **Step 5: Commit.** Message: `Android: Port 1's controller type, read with its bindings`

### Task 3: `PadGlyph` and the four face colours

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/components/PadGlyph.kt`
- Modify: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/theme/Theme.kt` (`GsColors`),
  `docs/design-system/tokens.json`, `docs/design-system.md` (the colour table, a "Pad glyphs" section, the files
  table)
- Test: `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/ui/PadGlyphTest.kt`

**Interfaces:**
- Produces:
  - `GsColors.psTriangle`, `psCircle`, `psCross`, `psSquare`: `Color`, with the dark values from Global Constraints.
  - `object PadGlyphs { val names: Set<String>; fun has(name: String): Boolean }`. The names are the 24 core names
    from the spec: `Up Down Left Right Triangle Circle Cross Square Select Start L1 R1 L2 R2 L3 R3 LUp LDown LLeft
    LRight RUp RDown RLeft RRight`.
  - `@Composable fun PadGlyph(name: String, contentDescription: String, modifier: Modifier = Modifier)`. It draws
    `name`'s glyph, and screen readers hear `contentDescription` only. It draws nothing for a name not in
    `PadGlyphs.names`; callers check `has` first.

- [ ] **Step 1: Write the failing tests** in `PadGlyphTest`:
  - `everyNameHasAGlyph`: for each of the 24 names, `PadGlyph(name, "x")` has a width above 0 and a height of 32 dp
    at font scale 1.
  - `theGlyphGrowsWithTheFontScale`: at `fontScale = 1.3f` (a `Density` override in `CompositionLocalProvider`),
    the Triangle glyph is 41.6 dp tall, ±0.5.
  - `screenReadersHearTheName`: the node has the content description "Left Stick Left", and no other text in the
    merged tree.
  - `theFaceSymbolsAreInTheirColours`: capture `Triangle`, `Circle`, `Cross` and `Square` with `captureToImage()`.
    Each has pixels within ±2 per channel of its token's colour, and `Cross` has none of `psTriangle`'s.
  - `theLabelsAreText`: `L1`, `R2`, `L3`, `Select` and `Start` each have a text node "L1" … "SELECT" … "START" in the
    unmerged tree, so `OrientationLayoutTest`'s cut-off checks see them.

- [ ] **Step 2: Run them to see them fail.** Expected: compile failure, because `PadGlyph` doesn't exist. Add an empty
  `PadGlyph` and `PadGlyphs`, then run again. Expected: FAIL on the size, colour and text assertions.

- [ ] **Step 3: Implement it.**
  - Each glyph follows its SVG in `docs/design-system/pad-glyphs/`: the same geometry in a 24-unit-tall box, the box
    widths as in the SVGs (24, 30, 60 for Select, 54 for Start), and stroke widths in units.
  - Colours map like this:
    - the SVG's `#e9e6df` is `sunken`;
    - `#858072` is `lineStrong`;
    - `#14171c` is `ink`;
    - the four face colours are the `ps-*` tokens.
  - Shapes are drawn on a `Canvas`, or as `ImageVector`s (builder's call). The labels ("L", "R", "L1", "L3",
    "SELECT", "START") are `Text` in Atkinson Hyperlegible Next bold (`GsFonts` or the theme's text family), placed
    as in the SVGs.
  - The unit size is `(32.dp × fontScale) / 24`, computed from `LocalDensity.current.fontScale`. The app has no TV
    scaling yet (nothing checks for a TV); when it gets one, as a larger font scale, the glyphs follow. Note this in
    the ledger rather than adding TV detection here.
  - Semantics: `Modifier.semantics(mergeDescendants = true) { this.contentDescription = contentDescription }`, with
    the label `Text`s cleared of their own semantics text, but still laid out as text nodes in the unmerged tree.
    If that combination can't satisfy both `screenReadersHearTheName` and `theLabelsAreText`, keep the content
    description and change `theLabelsAreText` to check the labels' layout bounds instead. Ledger the ruling.

- [ ] **Step 4: Run them to see them pass.** Run: `PadGlyphTest`. Expected: PASS.

- [ ] **Step 5: Update the docs.**
  - `tokens.json` gets the four `ps-*` tokens, with both themes' values and their usage: "A PlayStation face
    button's symbol in a pad glyph".
  - The colour table in `design-system.md` gets them too.
  - A new "Pad glyphs" section describes the shapes, the sizes and the rule that only Port 1 rows use them.
  - The files table at the top gets a row for `docs/design-system/pad-glyphs/`.

- [ ] **Step 6: Run the JVM tests and `check`**, then commit. Message: `Android: PlayStation button glyphs, with the
  face buttons in their colours`

### Task 4: Glyphs on the Controllers page

**Files:**
- Modify: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/ControllersScreen.kt` (`ControllersScreen`,
  `BindingRow`), and where `ControllersScreen` is called (`MainActivity.kt`, from `ControllersState`)
- Test: `ControllersScreenTest.kt`, `OrientationLayoutTest.kt`
- Docs: `docs/android.md` (the Controllers page section, and a Thor checklist step)

**Interfaces:**
- Consumes: `ControllersState.port1Type` (Task 2); `PadGlyph`, `PadGlyphs.has` (Task 3).
- Produces: `ControllersScreen(…, glyphs: Boolean = false)`. The call site passes
  `state.port1Type in GLYPH_TYPES`, where `GLYPH_TYPES = setOf("DigitalController", "AnalogController")` is in
  `ControllersScreen.kt`.

- [ ] **Step 1: Write the failing tests** in `ControllersScreenTest`, with its `BINDINGS`, plus `LLeft`/"Left Stick
  Left" and a made-up `Turbo`/"Turbo" if not already present:
  - `port1ShowsGlyphsWithTheirNamesForScreenReaders`: with `glyphs = true`, the Cross row's left side has the content
    description "Cross", the text "Cross" isn't shown, and the bound value ("Button A", or whatever `BINDINGS` has)
    still shows as text.
  - `aNameWithoutAGlyphStaysText`: with `glyphs = true`, "Turbo" shows as text.
  - `anotherControllerTypeKeepsText`: with `glyphs = false`, "Cross" and "Left Stick Left" show as text.
  - `theCapturePromptKeepsTheWord`: with `glyphs = true` and `capturing = "Cross"`, the prompt names "Cross" in
    words, as it does now.
  - `OrientationLayoutTest.controllersWithGlyphsAtLargeText`: on `controllersWithTwoPadsAndEveryInput`'s data, with
    `glyphs = true` at 640 × 360 and the other sizes, at font scale 1.3, every row is reached by D-pad and in view,
    and nothing is cut.

- [ ] **Step 2: Run them to see them fail.** Expected: compile failure, because there's no `glyphs` parameter. Add
  it, unused, then run again. Expected: FAIL in `port1ShowsGlyphsWithTheirNamesForScreenReaders`.

- [ ] **Step 3: Implement it.**
  - In `BindingRow`, the label is `PadGlyph(binding.name, binding.displayName)` when `glyphs &&
    PadGlyphs.has(binding.name)`, and the existing `Text` otherwise.
  - Hotkey rows are unchanged.
  - The call site passes `glyphs`.

- [ ] **Step 4: Run them to see them pass.** Run: `ControllersScreenTest`, then `OrientationLayoutTest`. Expected:
  PASS.

- [ ] **Step 5: Update `docs/android.md`.**
  - The Controllers page section: Port 1's inputs are shown as PlayStation glyphs on a digital pad or DualShock, and
    as text on other types.
  - The settings pages: A opens a setting's choices.
  - Add a Thor checklist step (a new numbered step after 9): open each Graphics list with the pad and pick a choice,
    and cancel one with B. On Controllers, check that every Port 1 row shows its glyph, readable at the system's
    largest font size.

- [ ] **Step 6: Verify the whole branch.**
  Run: `./gradlew ktlintFormat testDebugUnitTest check`, then all instrumented tests on the emulator.
  Expected: BUILD SUCCESSFUL, with every instrumented test passing.
  Then take an emulator screenshot of Controllers and of Graphics with a list open, and compare them with the
  design. Commit. Message: `Android: Port 1's inputs as PlayStation glyphs on the Controllers page`
