# Android settings, stage 1: global settings — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The settings hub gains Interface, Console, Emulation and Audio pages and a full Graphics page; Memory cards
gains its card slot rows and Achievements its missing rows; each row uses desktop's key and values, and the rows that
need the app or host (pause on disconnect, save state backups, the unlock card's duration, sync to the screen's
refresh rate) do what they say.

**Architecture:**
- **The bridge** (`android/app/src/cpp/`) gains a float getter and setter, the core's names for each choice list
  (`Settings::Get…Name` / `Get…DisplayName`, `AudioStream::GetStretchMode…`), the shared card files, the core's rewind
  memory estimate, and the screen's refresh rate. No `src/core` change.
- **The settings model** (`settings/Setting.kt`, `settings/SettingValues.kt`): a setting is its section, key, type and
  the core's default; a page is a list of them, read into an immutable `SettingValues` and saved by writing the ones
  that changed, as the Graphics page does today. Each page's settings live in one file (`settings/<Page>Settings.kt`).
- **Rows:** a new `NumberRow` (with `NumberScale`), a `SettingsSection` heading, and `enabled` on `CyclerRow`,
  `SwitchRow` and `NumberRow`, in `ui/components/SettingsRows.kt` and `docs/design-system.md`.
- **Pages:** `SettingsPages` loads and saves any `CorePage` generically; one composable per page in `ui/`; the hub,
  `Screen` and `MainActivity` route them.

**Tech Stack:** C++20, Android NDK, JNI, Kotlin, coroutines, Jetpack Compose, JUnit 4, AndroidX Test, Compose UI tests.

**Spec:** [docs/superpowers/specs/2026-11-05-android-settings-design.md](../specs/2026-11-05-android-settings-design.md)
(stage 1 only; stages 2–4 are out of scope).

**Where to build:** this worktree's branch, on the design commit `19ea5b593`, pushed to
`origin/claude/android-settings` (`git push origin HEAD:claude/android-settings`; the parent session's worktree holds
the local branch name). Env: `source /tmp/gs3-env.sh`.

## Global Constraints

- `docs/design-system.md` and its tokens are binding: controller-first, focus always visible, no window dialogs, one
  `beak` fill; every new page goes into `FocusAuditTest` and `OrientationLayoutTest`, with a key-driven screen test.
- Keys and values are desktop's (`src/core/settings.cpp` `Settings::Load`); names and display names come from the
  core's functions over JNI, never a list copied into Kotlin. Defaults are the core's **Android** defaults (pinned by
  Task 1's defaults test), not Big Picture's.
- Don't copy Big Picture's four bugs: the depth clear threshold is `[GPU] PGXPDepthClearThreshold` (not
  `PGXPDepthBuffer`); the vertex cache defaults to `false`; the fast-forward volume defaults to 100 with a maximum of
  100; (the log section name is stage 4's). Also: the turbo speed defaults to `0` (the core's), not Big Picture's 2.0.
- Global settings change only from the library: the pages stay locked while a game runs (`settingsReady`), so every
  change takes effect at the next boot and no row needs a "restart" note in this stage.
- A value in `settings.ini` that a row doesn't offer shows as itself, marked "(from settings.ini)" on choice rows, and
  is kept until another is picked. Nothing is written until the player changes the row.
- Rows whose setting has no effect in the current state (a hardware-only row under Software, PGXP's rows with PGXP
  off) are hidden, as Big Picture hides them; rows the core refuses in hardcore mode show disabled or noted.
- Settings I/O stays off the main thread (the core dispatcher), as the bridge does today; only `applySettings` runs
  on the CPU thread.
- Android-only behaviour stays in `android/`; no desktop behaviour changes. Clean room: nothing from DuckStation after
  `a5bf1ba5cee7`, nor its closed Android app.
- `gh` as ilyalissoboi (`GH_TOKEN=$(gh auth token --user ilyalissoboi)`), never the GitHub MCP. Emulators only; the
  AYN Thor needs the user's go-ahead. Commit each task as it's done and show its message after; commit messages end
  with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Found in the code, and how this plan deals with it

- **`ShowOSDMessages` already reaches the app's message layer.** `ImGuiManager::PostOSDMessage` returns before
  `Host::OnOSDMessagePosted` when it's off (`src/util/imgui_manager.cpp:966`), and the app's own messages (a save,
  a load, a disc change) don't go through it. So the row needs no new code: Task 9 pins it with a test.
- **Leaderboard messages already last `LeaderboardsDuration`:** the app posts them to `OsdMessages` with the core's
  seconds (`AppController.onAchievementNotification`). Only the unlock card ignores its seconds (a fixed 5 s,
  `AchievementOverlay.UNLOCK_MS`). Task 11 makes the unlock card use them; mastery keeps its 10 s (no setting).
- **Pause on controller disconnect can be the app's:** `ControllerInput.onInputDeviceRemoved` already sees each
  controller go, and every controller drives Port 1 (so each has bindings). Task 5 opens the in-game menu from there,
  as Back does, instead of filling `Host::OnInputDeviceDisconnected`.
- **Pause on start pauses with no menu today:** the core boots paused and `PausePolicy.atBoot` leaves it so, with
  nothing on screen to resume from. Task 5 opens the in-game menu in that case.
- **Save state backups:** `AndroidHost::SaveState` passes `false` (`android_host.cpp:1000`); desktop passes the
  setting for slot saves only and never for the resume state (`System::SaveResumeState`). Task 5 passes it for slot
  saves; `.bak` files are mirrored like any other file in `savestates/`.
- **Sync to host refresh rate has no effect on Android:** `Host::AcquireRenderWindow` never sets
  `WindowInfo::surface_refresh_rate`, which `System::UpdateSpeedLimiterState` needs (`system.cpp:3428`). Task 7 passes
  the display's refresh rate with the surface.
- **Hardcore mode refuses less than the design lists:** `Settings::FixIncompatibleSettings` (`settings.cpp:833`)
  raises speeds below 100 % to 100 % (Unlimited stays), turns rewind off, and turns overclocking below 100 % off.
  Run-ahead is allowed. So: Rewind shows disabled ("Off in hardcore mode"); a speed or overclock below 100 % is noted
  ("Runs at 100% in hardcore mode." / "Off in hardcore mode below 100%."). Hardcore here is `[Cheevos] Enabled`
  (signed in) and `ChallengeMode`, read with the page.
- **Rewind and turbo need a hotkey** that the Controllers page offers only from stage 2 (`HOTKEYS` in
  `android_host.cpp:124` is fast forward, analog and screenshot). Their rows are built now, as the design says; a
  `[Hotkeys]` binding made by hand works with them today.
- **The rewind estimate reads `g_settings`:** `System::CalculateRewindMemoryUsage` ignores its scale argument and uses
  `g_settings.gpu_resolution_scale` and `gpu_multisamples`. The bridge calls it on the CPU thread after the page's
  change is applied, so it's current.
- **The renderer list on Android is Automatic, Vulkan, OpenGL, Software** (the enum's `#ifdef`s), so `Software` is
  now offered; the app keeps its notes ("OpenGL ES" on OpenGL) as choice notes, with the core's display names.
- **Desktop's turbo, read and seek speed-up, run-ahead and resolution lists are labels over plain numbers**, not core
  names. Those rows are `NumberRow`s whose labels the app writes ("2x (quad speed)"); only enumerations come from the
  core's names.
- **Big Picture's PGXP tolerance passes `pgxp_enabled` as its step**; Qt's range is −1 (off) to 100 by 0.25. Android
  takes Qt's.
- **A fresh `settings.ini` holds every default:** Android's first start writes `System::SetDefaultSettings`
  (`android_host.cpp:336`), so a test can check each Kotlin default against the file the core wrote (Task 1).

## Review Focus

1. **A desktop value off a row's scale** (`EmulationSpeed = 1.33`, `ReadSpeedup = 12`, an unknown filter name): the
   row shows it as itself, stepping goes to the nearest value that way, and nothing is written until then.
   Pinned in Task 3 (`NumberRow`) and Task 2 (`offered`).
2. **Floats round-trip as desktop writes them:** `0.1f` is written `0.1`, `1.25f` `1.25`, and a value desktop wrote
   (`10.000000`) reads back. Pinned in Task 1.
3. **A page changed under it** (the mirror's pull while it's open): the page reads again, a read that comes back after
   a change is dropped, and the focus stays. Pinned in Task 4 for every page.
4. **Rows appearing and going** as their switch flips (PGXP off hides nine rows; Box downsampling shows its scale): the
   focus stays on the row flipped. Pinned in Task 8.
5. **Hardcore only when signed in:** `ChallengeMode = true` with `Enabled = false` disables nothing. Pinned in Task 7.

---

## File Structure

- `android/app/src/cpp/android_host.{h,cpp}`: float get/set; backups for slot saves; the refresh rate kept with the
  window; the rewind estimate; the game-started notification carries StartPaused and PauseOnControllerDisconnection.
- `android/app/src/cpp/jni_bridge.cpp`: the JNI functions for the above, `getSettingNames`, `getSharedCardNames`.
- `core/NativeLibrary.kt`, `core/NativeCallbacks.kt`: their externals and the callback's new parameters.
- `settings/CoreSettings.kt`: `getFloat`/`setFloat`. `settings/Setting.kt` (new): `Setting<T>` and its four kinds.
  `settings/SettingValues.kt` (new). `settings/NameList.kt` (new): the core's choice lists by kind, `offered()`.
- `settings/InterfaceSettings.kt`, `ConsoleSettings.kt`, `EmulationSettings.kt`, `GraphicsSettings.kt` (rewritten),
  `AudioSettings.kt`, `MemoryCardSettings.kt` (new): each page's `Setting`s and scales.
- `ui/components/SettingsRows.kt`: `NumberRow`, `NumberScale`, `SettingsSection`, `enabled`.
- `app/SettingsPages.kt`: `CorePage`, generic load/change/reload. `app/CoreApi.kt`: the new reads.
- `ui/InterfaceScreen.kt`, `ConsoleScreen.kt`, `EmulationScreen.kt`, `GraphicsScreen.kt` (rewritten),
  `AudioScreen.kt` (new screens); `ui/MemcardSyncScreen.kt`, `ui/AchievementsScreen.kt` (rows added).
- `ui/SettingsScreen.kt`, `app/AppController.kt`, `MainActivity.kt`: the hub's entries and routes.
- `app/PausePolicy.kt`, `input/ControllerInput.kt`, `ui/GameScreen.kt`: the Interface and Emulation app work.
- `app/AchievementNotices.kt`, `app/AchievementOverlay.kt`, `settings/AchievementsSettings.kt`: Task 11.
- Tests: `androidTest/…/core/SettingsAccessTest.kt`, `GameCallsTest.kt`; `androidTest/…/ui/SettingsRowsTest.kt`,
  `FocusAuditTest.kt`, `OrientationLayoutTest.kt`, a `<Page>ScreenTest.kt` per new page; `test/…/settings/`,
  `test/…/app/SettingsPagesTest.kt`, `PausePolicyTest.kt`, `AchievementNoticesTest.kt`, `AchievementOverlayTest.kt`.
- Docs: `docs/design-system.md` (Task 3), `docs/android.md` "Changing settings" (Task 12).

All Kotlin paths are under `android/app/src/main/java/io/github/ilyalissoboi/goosestation/` (tests under
`android/app/src/test/java/…` and `android/app/src/androidTest/java/…`).

## Running the tests

- JVM: `cd android && ./gradlew -q testDebugUnitTest --tests '<Class>'`; layout: `./gradlew -q ktlintCheck`.
- One instrumented class: `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.<pkg>.<Class>`.
- Each task that touches UI or the bridge ends with `android/scripts/run-instrumented-tests.sh quick` (test emulators
  started with `android/scripts/start-test-emulators.sh`), read from the result XML under
  `app/build/outputs/androidTest-results/`, not Gradle's verdict.

---

### Task 1: The bridge: floats, the core's names, shared cards, the rewind estimate

**Files:**
- Modify: `android/app/src/cpp/android_host.{h,cpp}` (after `SetIntSetting`), `android/app/src/cpp/jni_bridge.cpp`
  (beside `getIntSetting`, `getAspectRatioNames`)
- Modify: `core/NativeLibrary.kt`, `settings/CoreSettings.kt`, `app/CoreApi.kt`,
  `test/…/settings/MemoryCoreSettings.kt`, `test/…/app/FakeCoreApi.kt`
- Test: `androidTest/…/core/SettingsAccessTest.kt`

**Interfaces:**
- Produces (Kotlin, `NativeLibrary`): `getFloatSetting(section: String, key: String, default: Float): Float`,
  `setFloatSetting(section: String, key: String, value: Float)`, `getSettingNames(kind: String): Array<String>`
  (name, display name, name, display name…; empty for an unknown kind), `getSharedCardNames(): Array<String>` (the
  memory card folder's `*.mcd` files, relative, sorted), `getRewindMemoryUsage(slots: Int): LongArray` (`[ram, vram]`
  in bytes, on the CPU thread, waiting for it).
- Produces (`CoreSettings`): `getFloat(section, key, default: Float): Float`, `setFloat(section, key, value: Float)`,
  on `NativeCoreSettings` and `MemoryCoreSettings` (which stores `value.toString()`).
- Produces (`CoreApi`): `getSettingNames(kind: String): List<Choice<String>>`, `getSharedCardNames(): List<String>`,
  `getRewindMemoryUsage(slots: Int): RewindUsage` (`data class RewindUsage(val ramBytes: Long, val vramBytes: Long)`,
  in `app/CoreApi.kt`).
- The kinds `getSettingNames` knows, each `Count` of its enum through `Get…Name`/`Get…DisplayName`:
  `ConsoleRegion`, `CPUExecutionMode`, `GPURenderer`, `GPUTextureFilter`, `GPUDownsampleMode`, `GPULineDetectMode`,
  `DisplayAspectRatio`, `DisplayDeinterlacingMode`, `DisplayCropMode`, `DisplayScalingMode`, `DisplayAlignment`,
  `DisplayRotation`, `DisplayScreenshotMode`, `DisplayScreenshotFormat`, `AudioStretchMode`, `MemoryCardType`.
  `getAspectRatioNames` stays until Task 4 moves Graphics over, then goes.

- [ ] **Step 1: Write the failing tests** in `SettingsAccessTest` (each initializes a fresh data root, as today):

```kotlin
@Test fun floatSettingRoundTrips() {
    initialize()
    NativeLibrary.setFloatSetting("Main", "EmulationSpeed", 1.25f)
    assertEquals(1.25f, NativeLibrary.getFloatSetting("Main", "EmulationSpeed", 1f))
    assertEquals("1.25", readSettings().value("Main", "EmulationSpeed"))
    NativeLibrary.setFloatSetting("Main", "RewindFrequency", 0.1f)
    assertEquals("0.1", readSettings().value("Main", "RewindFrequency"))
}
@Test fun aFloatDesktopWroteReads() { /* settings.ini with "EmulationSpeed = 10.000000" before initialize() */
    assertEquals(10f, NativeLibrary.getFloatSetting("Main", "EmulationSpeed", 1f)) }
@Test fun eachKindHasTheCoresNames() {
    initialize()
    for (kind in KINDS) { val names = NativeLibrary.getSettingNames(kind)
        assertTrue("$kind has none", names.isNotEmpty()); assertEquals(0, names.size % 2) }
    assertEquals(listOf("Automatic", "Vulkan", "OpenGL", "Software"),
        NativeLibrary.getSettingNames("GPURenderer").toList().chunked(2).map { it[0] })
    assertTrue("Custom" in NativeLibrary.getSettingNames("DisplayAspectRatio").toList().chunked(2).map { it[0] })
    assertEquals(0, NativeLibrary.getSettingNames("NoSuchKind").size)
}
@Test fun sharedCardNamesAreTheCardFolders() { /* memcards/shared_card_1.mcd, memcards/b.mcd, memcards/notes.txt */
    assertEquals(listOf("b.mcd", "shared_card_1.mcd"), NativeLibrary.getSharedCardNames().toList()) }
@Test fun rewindUsageGrowsWithSlots() {
    initialize()
    val ten = NativeLibrary.getRewindMemoryUsage(10); val twenty = NativeLibrary.getRewindMemoryUsage(20)
    assertTrue(ten[0] > 0 && ten[1] > 0); assertEquals(ten[0] * 2, twenty[0]); assertEquals(ten[1] * 2, twenty[1])
}
```

`KINDS` is the list in the Interfaces block. The defaults test is added in each page's task (Tasks 5–11):
`defaultsMatchTheCore` there checks, for every `Setting` the page declares, that `setting.read(NativeCoreSettings)` on
a fresh data root equals `setting.default` (the core wrote every default into `settings.ini`).

- [ ] **Step 2: Run them; expect failures** (`UnsatisfiedLinkError` / unresolved references).

Run: `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.core.SettingsAccessTest`

- [ ] **Step 3: Implement.** `AndroidHost::GetFloatSetting`/`SetFloatSetting` as the int pair (lock, base layer,
  save). `getSettingNames` is one table in `jni_bridge.cpp` from kind to a lambda filling the pairs (no copied
  strings). `getSharedCardNames` uses `FileSystem::FindFiles(EmuFolders::MemoryCards, "*.mcd", FILES | HIDDEN_FILES |
  RELATIVE_PATHS)`, sorted. `getRewindMemoryUsage` runs `System::CalculateRewindMemoryUsage(slots, 0, …)` in
  `EmuThread::RunOnThread(…, true)`. Kotlin takes the arrays as never null (an allocation failure gives an empty one,
  as `getAspectRatioNames` does).

- [ ] **Step 4: Run the class again; expect PASS.** Then `./gradlew -q testDebugUnitTest ktlintCheck`.

- [ ] **Step 5: Commit**

```
Android: floats, the core's choice names, shared cards and the rewind estimate through the bridge

- getFloatSetting/setFloatSetting, on the base layer under the settings lock, as the int pair
- getSettingNames(kind): each choice list's names and display names from the core's own functions
- getSharedCardNames: the memory card folder's .mcd files; getRewindMemoryUsage: the core's estimate
- CoreSettings.getFloat/setFloat and CoreApi's reads, with the test fakes
```

---

### Task 2: The settings model

**Files:**
- Create: `settings/Setting.kt`, `settings/SettingValues.kt`, `settings/NameList.kt`
- Test: `test/…/settings/SettingValuesTest.kt`, `test/…/settings/NameListTest.kt`

**Interfaces:**
- Consumes: `CoreSettings.getFloat/setFloat` (Task 1).
- Produces:
  - `sealed interface Setting<T> { val section: String; val key: String; val default: T; fun read(s: CoreSettings): T;
    fun write(s: CoreSettings, value: T) }` with `data class BoolSetting`, `IntSetting`, `FloatSetting`,
    `NameSetting(section, key, default: String, list: NameList)` (a string value naming one of `list`'s choices).
    Equality is the data classes' (section, key, default).
  - `class SettingValues` with `operator fun <T> get(setting: Setting<T>): T` (throws for one it wasn't read with),
    `fun <T> with(setting: Setting<T>, value: T): SettingValues`, `fun changed(old: SettingValues): List<Setting<*>>`,
    `fun save(s: CoreSettings, old: SettingValues)` (writes `changed(old)` only), `equals`/`hashCode` by content, and
    `companion fun read(s: CoreSettings, settings: Collection<Setting<*>>): SettingValues`.
  - `enum class NameList(val kind: String)` with one entry per Task 1 kind (`ConsoleRegion("ConsoleRegion")`, …,
    `MemoryCardType("MemoryCardType")`).
  - `typealias CoreNames = Map<NameList, List<Choice<String>>>`.
  - `fun offered(choices: List<Choice<String>>, current: String): List<Choice<String>>`: `choices`, plus
    `Choice(current, "$current (from settings.ini)")` at the end when `current` isn't among them (the rule the Graphics
    page has today, `GraphicsChoices`). `const val FROM_SETTINGS = "(from settings.ini)"` moves here.
  - `fun <T> offered(choices: List<Choice<T>>, current: T, label: (T) -> String): List<Choice<T>>` for non-name rows
    (MSAA).

- [ ] **Step 1: Write the failing tests**

```kotlin
// SettingValuesTest
@Test fun readsEachSettingOrItsDefault()   // MemoryCoreSettings with Main/EmulationSpeed = "1.5": FloatSetting reads 1.5f,
                                           // an unset BoolSetting reads its default
@Test fun savesOnlyWhatChanged()           // old.with(SPEED, 2f).save(s, old): s.values has only Main/EmulationSpeed = "2.0"
@Test fun twoChangesSaveBoth()             // .with(A).with(B): changed(old) == listOf(A, B) in the page's order
@Test fun anUnreadSettingThrows()          // values[OTHER] -> IllegalArgumentException
@Test fun aNameSettingKeepsAnUnknownName() // "Renderer = Metal" reads "Metal"; nothing written by read
// NameListTest
@Test fun anOfferedNameIsLeftAsIs()
@Test fun aNameNotOfferedIsAddedLastMarked() // offered([a, b], "x").last() == Choice("x", "x (from settings.ini)")
@Test fun eachListHasAKind()                 // NameList.entries.map { it.kind } == Task 1's KINDS
```

- [ ] **Step 2: Run; expect FAIL** (`./gradlew -q testDebugUnitTest --tests '*.settings.*'`).
- [ ] **Step 3: Implement** as the Interfaces block says. `SettingValues` keeps the settings' order (a
  `LinkedHashMap`), so `changed` lists them as the page does.
- [ ] **Step 4: Run; expect PASS**, and `ktlintCheck`.
- [ ] **Step 5: Commit**

```
Android: a settings model for the pages: a setting by its key, and a page's values

- Setting<T>: bool, int, float and name settings, each with the core's default
- SettingValues: a page's values as read, changed one at a time, saving only what changed
- NameList for the core's choice lists, and offered() for a value settings.ini has that a row doesn't
```

---

### Task 3: The number row, section headings and disabled rows

**Files:**
- Modify: `ui/components/SettingsRows.kt`, `docs/design-system.md` (the Settings component section)
- Test: `androidTest/…/ui/SettingsRowsTest.kt`, `test/…/ui/NumberScaleTest.kt` (new)

**Interfaces:**
- Produces:
  - `sealed interface NumberScale { val presets: List<Int>; fun label(value: Int): String; fun next(value: Int,
    direction: Int): Int?; }` (`next` is null at an end), with `class NumberScale.Range(min: Int, max: Int, step: Int,
    presets: List<Int>, label: (Int) -> String)` and `class NumberScale.Steps(values: List<Int>, presets: List<Int>,
    label: (Int) -> String)`. Off the scale, `next` goes to the nearest value that way (`Range(0, 32, 1)` from 40 going
    left gives 32; `Steps([0, 10, 20])` from 15 going right gives 20).
  - `@Composable fun NumberRow(label: String, value: Int, scale: NumberScale, onChange: (Int) -> Unit, modifier:
    Modifier = Modifier, note: String? = null, enabled: Boolean = true)`.
  - `@Composable fun SettingsSection(title: String, modifier: Modifier = Modifier)`: an `Eyebrow` with 16 dp start
    padding and 16 dp above (none for a page's first), `semantics { heading() }`, not focusable.
  - `enabled: Boolean = true` on `CyclerRow` and `SwitchRow`: disabled, the row is dimmed (`inkFaint` label, faint
    arrows or toggle), can't take focus (`focusRing(enabled = false)`), and its note still shows.
- `NumberRow` looks and acts as a `CyclerRow` (label, value in the well between arrows; a value off the scale shows as
  `scale.label(value)`): left and right call `onChange(scale.next(value, ∓1))`, on each key-down, so a held key repeats
  with Android's key repeat; a tap on an arrow steps. A, Enter or a tap open the `ChoiceList` of `scale.presets` (the
  current value added in order when it isn't one, so it's marked and focused), under the row as a `CyclerRow`'s.
  Share `Cycler`, `CyclerArrow` and the list handling with `CyclerRow` rather than copying them.

- [ ] **Step 1: Write the failing tests**

```kotlin
// NumberScaleTest (JVM)
@Test fun aRangeStepsAndStopsAtItsEnds()      // Range(0, 32, 1): next(0, -1) == null, next(31, 1) == 32, next(32, 1) == null
@Test fun aRangeStepsByItsStep()             // Range(25, 500, 5): next(100, 1) == 105
@Test fun offTheRangeGoesToTheNearestThatWay() // Range(0, 32, 1): next(40, -1) == 32; Range(25, 500, 5): next(102, 1) == 105, next(102, -1) == 100
@Test fun stepsWalkTheirValues()             // Steps([0, 10, 100, 125]): next(100, 1) == 125, next(15, 1) == 100, next(15, -1) == 10
// SettingsRowsTest (instrumented, keys)
@Test fun aNumberRowStepsWithLeftAndRight()  // Range(0, 10, 1) at 5: Right -> onChange(6); Left -> 4
@Test fun aHeldKeyRepeats()                  // three repeated key-downs (repeatCount 0, 1, 2) of Right step three times
@Test fun aNumberRowStopsAtItsEnds()         // at 10, Right calls nothing; the forward arrow is faint
@Test fun aOpensTheCommonValues()            // presets [0, 5, 10], value 7: A shows 0, 5, 7, 10 with 7 focused and checked;
                                             // Down, A picks 10 and closes; the focus is back on the row
@Test fun bClosesTheListUnchanged()
@Test fun aValueOffTheScaleShowsAsItself()   // Range(0, 32) at 40 shows scale.label(40)
@Test fun aDisabledRowCantBeFocused()        // SwitchRow, CyclerRow and NumberRow with enabled = false: Down skips each
@Test fun aSectionHeadingIsntAStop()         // SettingsSection between two rows: Down goes from one row to the next
```

- [ ] **Step 2: Run; expect FAIL.**
- [ ] **Step 3: Implement** as the Interfaces block says.
- [ ] **Step 4: Document** in `docs/design-system.md` under "Settings": `NumberRow` (a number or a long ladder of
  values: the value between arrows, left and right step by the scale and repeat when held, A opens the common values;
  use it instead of a `CyclerRow` past about eight values), `SettingsSection` (the page's sections, as desktop names
  them, in the eyebrow style; never a focus stop), and disabled rows (dimmed, skipped by the focus, the note says
  why). Update the CyclerRow line "For more than about eight values… make a page of its own" to point at `NumberRow`.
- [ ] **Step 5: Run the tests; expect PASS**, then `run-instrumented-tests.sh quick` (XML).
- [ ] **Step 6: Commit**

```
Android: a number row, section headings and disabled rows for the settings pages

- NumberRow: a value between arrows that left and right step by its scale (repeating when held), and A's list of
  common values; NumberScale for a range by a step or a ladder of values
- SettingsSection: a page's section heading, never a focus stop
- CyclerRow and SwitchRow can be disabled: dimmed and skipped by the focus, their note saying why
- The design system documents all three
```

---

### Task 4: Generic pages in `SettingsPages`, with Graphics moved onto them

**Files:**
- Modify: `app/SettingsPages.kt`, `app/AppController.kt`, `MainActivity.kt`, `ui/GraphicsScreen.kt`,
  `settings/GraphicsSettings.kt` (rewritten as `object GraphicsSettings` of `Setting`s), `ui/SettingsScreen.kt`
- Modify tests: `test/…/settings/GraphicsSettingsTest.kt`, `test/…/app/SettingsPagesTest.kt`,
  `androidTest/…/ui/GraphicsScreenTest.kt`, `FocusAuditTest.kt` (`graphics`), `OrientationLayoutTest.kt` (the two
  Graphics cases)

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces:
  - `enum class CorePage(val title: String)` in `app/SettingsPages.kt`: `Interface`, `Console`, `Emulation`,
    `Graphics`, `Audio`, `MemoryCards` (the card slot rows); each page's settings come from
    `fun CorePage.settings(): List<Setting<*>>` (its `…Settings.all`, added as each page lands; Graphics here).
  - `SettingsPages`: `fun values(page: CorePage): SettingValues?` (Compose state; null until read),
    `var names: CoreNames` (read once, with the first page, then kept), `fun load(page: CorePage)` (from none shown),
    `fun change(page: CorePage, new: SettingValues)` (saves `new.changed(old)` on the core dispatcher, then
    `applySettings()`), `fun reloadShown(entry: SettingsEntry?)` (any `CorePage` whose entry is shown, and BIOS, as
    today). The stale-read rule is today's (`graphicsRead`/`graphicsReading`), kept per page. `vulkanFailed` stays.
    Per-page extras: `var rewindUsage: RewindUsage?` (Task 7), `var sharedCards: List<String>` (Task 10).
  - `GraphicsSettings.RENDERER`, `RESOLUTION_SCALE`, `ASPECT_RATIO`, `SHOW_FPS` (the four rows today), and `all`.
  - `GraphicsScreen(values: SettingValues, names: CoreNames, vulkanFailed: Boolean, onChange: (SettingValues) ->
    Unit, onBack: () -> Unit)`. Same signature shape for every page screen after it, with page extras added.
  - `SettingsEntry.page: CorePage?` maps an entry to its page (Graphics now; the rest as they land).

- [ ] **Step 1: Move the tests over first.** `SettingsPagesTest`: today's Graphics cases (load, change writes only the
  changed key then applies, a change during a read reads again, a stale read is dropped, reloadShown) run through
  `load(CorePage.Graphics)` / `change(…)`; add `namesAreReadOnce` (two loads, one `getSettingNames` per kind on the
  fake). `GraphicsSettingsTest`: the four settings' keys and defaults. Screen tests: the renderer row lists the core's
  four names, with "OpenGL ES" as OpenGL's note and the Vulkan note when `vulkanFailed`; an unknown value marked
  "(from settings.ini)"; aspect ratio now includes Custom (its rows come in Task 9).
- [ ] **Step 2: Run; expect FAIL** (compile errors on the new API).
- [ ] **Step 3: Implement.** `MainActivity` gets one helper used by every page:
  `CorePageRoute(page, content: @Composable (SettingValues) -> Unit)`, showing `SettingsLoadingPage(page.title)`
  until `values(page)` is read. `openSettingsPage` calls `settingsPages.load(entry.page)` for any entry with a page.
  `GraphicsChoices` goes; its labels become choice notes (resolution's "Match the screen" and "1x (native)" move to
  Task 8's scale). The hub's order becomes the design's: Interface, Console, Emulation, BIOS, Graphics, Audio,
  Controllers, Memory cards, Achievements, Folders (each new entry added by its task, in its place); Graphics'
  summary becomes "Renderer, enhancements, display, on-screen display". The hub's focus on opening stays as today
  (the page just left, else Controllers, else Folders).
- [ ] **Step 4: Run the JVM tests and `quick`; expect PASS** (XML).
- [ ] **Step 5: Commit**

```
Android: the settings pages load and save any page's settings, Graphics first

- SettingsPages loads a CorePage's settings and saves only what changed, with the stale-read rule Graphics had
- The core's choice names are read once and kept; Graphics' renderer and aspect ratio come from them
- MainActivity routes each core page through one loading helper
```

---

Tasks 5–11 each add a page or rows. Each one's tests include, beside what it lists: `defaultsMatchTheCore` for its
settings in `SettingsAccessTest` (Task 1's rule); the page in `FocusAuditTest` (`assertControllerReady()`, focus on the
first row); the page in `OrientationLayoutTest` (`@LargeTest`, every config: texts laid out, every row reached with
Down and in view, and each `NumberRow`'s and `CyclerRow`'s list opened at its longest); a `<Page>ScreenTest` driven
by keys; and the hub entry in `SettingsScreenTest` (order and summary). Each ends with `quick` (XML) and a commit.
Labels are desktop's names in sentence case; notes say a consequence and never repeat the label.

### Task 5: Interface, with its app and host work

**Files:**
- Create: `settings/InterfaceSettings.kt`, `ui/InterfaceScreen.kt`, `androidTest/…/ui/InterfaceScreenTest.kt`
- Modify: `android/app/src/cpp/android_host.cpp` (`SaveState`; the game-started notification), `jni_bridge.cpp`
  (`onGameStarted`'s signature), `core/NativeCallbacks.kt`, `app/PausePolicy.kt`, `input/ControllerInput.kt`,
  `app/AppController.kt`, `ui/SettingsScreen.kt`, `MainActivity.kt`
- Test: `test/…/app/PausePolicyTest.kt`, `androidTest/…/core/GameCallsTest.kt`

**Rows** (`[Main]`, no sections):

| Row | Key | Default | Note |
|---|---|---|---|
| Pause on start | `StartPaused` | false | "Each game starts paused, in its menu." |
| Pause when a controller disconnects | `PauseOnControllerDisconnection` | false | "Opens the game's menu." |
| Save state on exit | `SaveStateOnExit` | true | "Exit game saves where you are, to resume from next time." |
| Create save state backups | `CreateSaveStateBackups` | false (Android's) | "Saving over a slot keeps the old state as a .bak file." |

Hub entry: Interface, "Pausing, resume states, backups", first in the hub.

**Interfaces:**
- `NativeCallbacks.onGameStarted(title: String, serial: String, startPaused: Boolean, pauseOnDisconnect: Boolean)`:
  the host reads `g_settings.start_paused` and `pause_on_controller_disconnection` on the CPU thread as it notifies.
- `PausePolicy.opensMenuAtBoot(startPaused: Boolean, atBoot: Boolean?): Boolean` = `startPaused && atBoot == null`
  (the game was left as it booted, so it booted paused).
- `PausePolicy.pausesOnDisconnect(setting: Boolean, gameRunning: Boolean, onGameScreen: Boolean, promptOpen:
  Boolean): Boolean`.
- `ControllerInput.onControllerDisconnected: ((name: String) -> Unit)?`, called from `onInputDeviceRemoved` once a
  reported device is gone and no other device with its descriptor is left.
- `AppController`: keeps the two flags from `onGameStarted`; opens the prompt (`openPrompt()`) when either policy says
  so, and on a disconnect posts "<name> disconnected" to `osd`.
- `AndroidHost::SaveState` passes `g_settings.create_save_state_backups`; the resume state keeps `false`.

- [ ] **Step 1: Failing tests.** `PausePolicyTest`: `startPausedOpensTheMenu` (true, null → true),
  `startPausedLeavesAPausedBootAlone` (true, true → false: the app paused it, as for the background),
  `noMenuWithoutStartPaused`, `aDisconnectPausesARunningGame`, `noPauseWhileTheMenuIsOpen`, `noPauseWhileBooting`,
  `noPauseWithTheSettingOff`. `GameCallsTest` (`@BootsGames`): `aSlotSaveKeepsABackupWhenAsked` (backups on: save
  slot 1 twice, `<key>_1.bak` exists), `noBackupByDefault`, `theResumeStateKeepsNoBackup` (backups on, two
  `saveResumeState`s: no `<key>_resume.bak`), `onGameStartedSaysStartPaused` (StartPaused on: the callback's flag is
  true, and the core is paused). `InterfaceScreenTest`: four switches in order, each toggling its key.
- [ ] **Step 2: Run; expect FAIL.**
- [ ] **Step 3: Implement** as the Interfaces block says.
- [ ] **Step 4: Run; expect PASS; `quick` (XML).** Check on `goose-test-1` (`emulator-5580`) that Pause on start opens
  the menu over a paused padtest. A controller disconnect can't be made on the emulator: it waits for the Thor check
  (with the user's go-ahead).
- [ ] **Step 5: Commit**

```
Android: the Interface page, with pausing on start or on a disconnect into the game's menu

- Pause on start, pause when a controller disconnects, save state on exit, save state backups ([Main])
- A game that boots paused, or loses a controller while running, opens its menu, as Back does
- Slot saves keep a .bak of the state they replace when CreateSaveStateBackups is on; the resume state never does
```

### Task 6: Console

**Files:** Create `settings/ConsoleSettings.kt`, `ui/ConsoleScreen.kt`, `androidTest/…/ui/ConsoleScreenTest.kt`,
`test/…/settings/ConsoleSettingsTest.kt`; modify the hub, `MainActivity.kt`.

**Rows:**

| Section | Row | Key | Kind, default | Scale / shown | Note |
|---|---|---|---|---|---|
| Console | Region | `[Console] Region` | name `ConsoleRegion`, `Auto` | | |
| | 8 MB RAM | `[Console] Enable8MBRAM` | bool false | | "As on development consoles. Few games use it." |
| | Disable all enhancements | `[Main] DisableAllEnhancements` | bool false | | "Runs every game as the console would." |
| CPU | Execution mode | `[CPU] ExecutionMode` | name `CPUExecutionMode`, the core's (`Recompiler` on arm64) | | |
| | Overclocking | `[CPU] OverclockEnable` | bool false | | |
| | Overclocking percentage | `[CPU] OverclockNumerator` / `OverclockDenominator` | ints 1 / 1 | Range 10–1000 by 10, presets 50, 100, 150, 200, 300, 400; shown when Overclocking is on | hardcore and below 100 %: "Off in hardcore mode below 100%." |
| | Recompiler ICache | `[CPU] RecompilerICache` | bool false | | "Closer to the console's speed in some games, a little slower." |
| CD-ROM | Read speed-up | `[CDROM] ReadSpeedup` | int 1 | Steps 1–10; 1 "None (double speed)", n "nx (2n× speed)" written as "${n}x (${2 * n}x speed)" | "Faster loading in some games; breaks others." |
| | Seek speed-up | `[CDROM] SeekSpeedup` | int 1 | Steps 0–10; 0 "Instant", 1 "None (normal speed)", n "${n}x" | same note |
| | Readahead | `[CDROM] ReadaheadSectors` | int 8 | Range 0–32 by 1, presets 0, 4, 8, 16, 32; 0 "Off", n "$n sectors" | "Reads ahead on another thread, against hitches." |
| | Preload disc to RAM | `[CDROM] LoadImageToRAM` | bool false | | |
| | Apply image patches | `[CDROM] LoadImagePatches` | bool false | | "PPF patches beside the disc image." |

Overclock: `ConsoleSettings.overclockPercent(values): Int` = `numerator * 100 / denominator` (the core's
`CPUOverclockFractionToPercent`); `withOverclockPercent(values, percent)` writes the reduced fraction (`gcd(percent,
100)`, the core's `CPUOverclockPercentToFraction`, `settings.cpp:110`). Hardcore: `[Cheevos] Enabled` and
`ChallengeMode` are read with the page (`ConsoleSettings.HARDCORE_SIGNED_IN`, `HARDCORE`).

Hub entry: Console, "Region, CPU, CD-ROM speed", after Interface.

- [ ] **Step 1: Failing tests.** `ConsoleSettingsTest`: `percentOfAFraction` (3/2 → 150), `fractionOfAPercent` (150 →
  3/2, 125 → 5/4, 1000 → 10/1, 100 → 1/1), `aFractionDesktopWroteShows` (7/3 → 233). `ConsoleScreenTest`: the three
  sections in order; percentage hidden until Overclocking is on, then Right steps 100 → 110 and writes 11/10; the
  hardcore note at 90 % when signed in with hardcore, and none when not signed in; Read speed-up's labels at 1 and 4
  ("4x (8x speed)"); Seek speed-up Left from 1 gives 0, "Instant".
- [ ] **Steps 2–4:** FAIL, implement, PASS, `quick` (XML).
- [ ] **Step 5: Commit**

```
Android: the Console page: region, CPU and CD-ROM settings

- Region and execution mode from the core's names; 8 MB RAM; disable all enhancements
- Overclocking and its percentage, kept as desktop's fraction; noted when hardcore mode turns it off
- Read and seek speed-up, readahead, preloading the disc, image patches
```

### Task 7: Emulation, and the screen's refresh rate

**Files:** Create `settings/EmulationSettings.kt`, `ui/EmulationScreen.kt`, `androidTest/…/ui/EmulationScreenTest.kt`,
`test/…/settings/EmulationSettingsTest.kt`; modify `ui/GameScreen.kt`, `core/NativeLibrary.kt` (`surfaceChanged`),
`jni_bridge.cpp`, `android_host.{h,cpp}` (`SurfaceChanged`, `AcquireRenderWindow`), `app/SettingsPages.kt`
(`rewindUsage`), the hub, `MainActivity.kt`; test `GameCallsTest.kt`.

**Rows:**

| Section | Row | Key | Kind, default | Scale / shown | Note |
|---|---|---|---|---|---|
| Speed | Emulation speed | `[Main] EmulationSpeed` | float 1.0 | `SPEEDS` | hardcore and 0 < v < 100: "Runs at 100% in hardcore mode." |
| | Fast-forward speed | `[Main] FastForwardSpeed` | float 0.0 | `SPEEDS` | same hardcore note |
| | Turbo speed | `[Main] TurboSpeed` | float 0.0 (the core's) | `SPEEDS` | "While the Turbo hotkey is held." (+ hardcore note) |
| Frame pacing | VSync | `[Display] VSync` | bool false | | "Matches the screen's refresh. Can add latency." |
| | Sync to host refresh rate | `[Main] SyncToHostRefreshRate` | bool false | | "Runs at the screen's rate when it's within 5% of the game's." |
| | Optimal frame pacing | `[Display] OptimalFramePacing` | bool false | | "Shows every frame. Turn off for sound or speed problems." |
| | Reduce input latency | `[Display] PreFrameSleep` | bool false | shown with optimal frame pacing | "Starts each frame closer to when it's shown." |
| | Frame time buffer | `[Display] PreFrameSleepBuffer` | float 2.0 | in tenths of a ms: Range 0–200 by 10, presets 0, 10, 20, 50, 100, 200; label "%.1f ms" | shown with reduce input latency |
| | Skip duplicate frames | `[Display] SkipPresentingDuplicateFrames` | bool false | shown unless VSync and sync to host are both on | "Can make pacing worse." |
| Rewind | Rewind | `[Main] RewindEnable` | bool false | disabled in hardcore ("Off in hardcore mode") | run-ahead on: "Off while run-ahead is on." |
| | Save frequency | `[Main] RewindFrequency` | float 10.0 | in hundredths of a s: Steps 0, 5, 10, 25, 50, 100, 200, 500, 1000, 1500, 3000, 6000; 0 "Every frame", else "x s" (trailing zeros cut) | shown with rewind |
| | Save slots | `[Main] RewindSaveSlots` | int 10 | Steps 1–10, 20–100 by 10, 200–1000 by 100, 2000–10000 by 1000; "$n states" | shown with rewind; the estimate below |
| Run-ahead | Run-ahead | `[Main] RunaheadFrameCount` | int 0 | Range 0–10 by 1; 0 "Off", 1 "1 frame", n "$n frames" | "Less input lag, at a much higher cost." |

`SPEEDS` (percent, stored as `percent / 100f`): Steps 0, 10–90 by 10, 100, 125, 150, 175, 200, 250, 300, 350, 400, 450,
500, 600–1000 by 100; presets 0, 50, 100, 150, 200, 300, 500, 1000; 0 "Unlimited", else "$n%". A float off the ladder
(1.33) shows as "133%" and steps to its neighbours.

The save slots' note, from `SettingsPages.rewindUsage` (read after each Emulation change is applied, and on load):
"Goes back up to <d> s, using up to <ram> MB of memory and <vram> MB of video memory.", where `d = max(frequency,
1/60) × slots` (desktop's), as `%.1f`, and the sizes in MiB (`/ 1048576`).

Refresh rate: `GameScreen` passes `view.display?.refreshRate ?: 0f` with each `surfaceChanged` (the new
`refreshRate: Float` parameter); `AndroidHost::SurfaceChanged` keeps it beside the size, and `AcquireRenderWindow`
sets `wi.surface_refresh_rate`. Test hook: `NativeLibrary.getSurfaceRefreshRateForTest(): Float` (the running GPU
device's `GetWindowInfo().surface_refresh_rate`, on the CPU thread; 0 without one).

Hub entry: Emulation, "Speed, frame pacing, rewind, run-ahead", after Console.

- [ ] **Step 1: Failing tests.** `EmulationSettingsTest`: `speedsAreStoredAsFactors` (125 → 1.25f; 1.33f shows 133),
  `rewindNote` (frequency 0, 10 slots → "Goes back up to 0.2 s…"; 10.0, 10 → "100.0 s"), `hardcoreNeedsSignIn`
  (ChallengeMode on, Enabled off: no note, rewind enabled). `EmulationScreenTest`: Rewind disabled with its note when
  signed in with hardcore, skipped by Down; the buffer row appears after optimal pacing and reduce latency are on;
  skip duplicate frames goes when VSync and sync are on; the rewind estimate shows the fake's numbers.
  `GameCallsTest`: `theSurfacesRefreshRateReachesTheCore` (surfaceChanged with 60f; after boot the hook gives 60f).
- [ ] **Steps 2–4:** FAIL, implement, PASS, `quick` (XML). On `goose-test-1`, with Sync to host refresh rate and VSync
  on, logcat shows `Refresh rate: Host=60hz`.
- [ ] **Step 5: Commit**

```
Android: the Emulation page: speeds, frame pacing, rewind and run-ahead

- Emulation, fast-forward and turbo speeds as number rows over desktop's ladder, noted in hardcore mode
- VSync, sync to host refresh rate, optimal frame pacing, reduce input latency and its buffer, skip duplicate frames
- Rewind with its frequency, slots and the core's memory estimate (off in hardcore mode); run-ahead
- The screen's refresh rate goes to the core with the surface, so sync to host refresh rate can work
```

### Task 8: Graphics: rendering, enhancements and PGXP

**Files:** Modify `settings/GraphicsSettings.kt`, `ui/GraphicsScreen.kt`, their tests, `OrientationLayoutTest`'s
Graphics cases. "Hardware" means the renderer isn't `Software`.

| Section | Row | Key | Kind, default | Scale / shown | Note |
|---|---|---|---|---|---|
| Rendering | Renderer | `[GPU] Renderer` | name `GPURenderer`, `Automatic` | notes: Automatic "Vulkan where it works well, else OpenGL ES"; OpenGL "OpenGL ES" | the Vulkan-failed note, as today |
| | Internal resolution | `[GPU] ResolutionScale` | int 1 | Range 0–16 by 1, presets 0, 1, 2, 3, 4, 5, 6, 8; 0 "Match the screen", 1 "1x (native)", n "${n}x"; hardware | "Higher is sharper and slower." (not at 1) |
| | Downsampling | `[GPU] DownsampleMode` | name `GPUDownsampleMode` | hardware | |
| | Downsampling scale | `[GPU] DownsampleScale` | int 1 | Range 1–16, "${n}x"; hardware and mode `Box` | |
| | Multisampling | `[GPU] Multisamples` + `[GPU] PerSampleShading` | int 1 + bool false | one CyclerRow: Off (1, false), 2x/4x/8x MSAA (n, false), 2x/4x/8x SSAA (n, true), as Qt without an adapter; another pair offered as "(from settings.ini)"; hardware | "The device's maximum applies." |
| | Threaded presentation | `[GPU] ThreadedPresentation` | bool true (Android's) | Vulkan or Automatic | "Presents on another thread when fast-forwarding or without VSync." |
| | Threaded rendering | `[GPU] UseThread` | bool true | Software only | |
| Enhancements | Texture filtering | `[GPU] TextureFilter` | name `GPUTextureFilter` | hardware | |
| | Sprite texture filtering | `[GPU] SpriteTextureFilter` | name; default: the texture filter's value (the core's rule) | hardware | |
| | True colour | `[GPU] TrueColor` | bool true | hardware | "No dithering, full colour." |
| | Debanding | `[GPU] Debanding` | bool false | hardware, true colour on | |
| | Scaled dithering | `[GPU] ScaledDithering` | bool true | hardware, true colour off | |
| | Accurate blending | `[GPU] AccurateBlending` | bool false | hardware, true colour off | |
| | Widescreen rendering | `[GPU] WidescreenHack` | bool false | | "Wider view in 3D games, at the display's aspect ratio." |
| | Round upscaled texture coordinates | `[GPU] ForceRoundTextureCoordinates` | bool false | hardware, resolution not 1 | |
| | Line detection | `[GPU] LineDetectMode` | name `GPULineDetectMode` | hardware | |
| | Force NTSC timings | `[GPU] ForceNTSCTimings` | bool false | | "PAL games run at 60 Hz. Some run too fast." |
| | Disable interlacing | `[GPU] DisableInterlacing` | bool true | | |
| | FMV chroma smoothing | `[GPU] ChromaSmoothing24Bit` | bool false | | |
| | Software renderer for readbacks | `[GPU] UseSoftwareRendererForReadbacks` | bool false | hardware | |
| | Use old MDEC routines | `[Hacks] UseOldMDECRoutines` | bool false | | |
| PGXP (hardware) | Geometry correction | `[GPU] PGXPEnable` | bool false | | "Less wobbly polygons." The rest of the section shows only with it on |
| | Perspective-correct textures | `[GPU] PGXPTextureCorrection` | bool true | | |
| | Perspective-correct colours | `[GPU] PGXPColorCorrection` | bool false | | |
| | Culling correction | `[GPU] PGXPCulling` | bool true | | |
| | Preserve projection precision | `[GPU] PGXPPreserveProjFP` | bool false | | |
| | Depth buffer | `[GPU] PGXPDepthBuffer` | bool false | with perspective-correct textures | "Low compatibility." |
| | Depth clear threshold | `[GPU] PGXPDepthClearThreshold` | float 300 | Range 0–4096 by 10, presets 0, 100, 300, 1000, 4096; with depth buffer | |
| | CPU mode | `[GPU] PGXPCPU` | bool false | | "PGXP for every instruction. Slower." |
| | Vertex cache | `[GPU] PGXPVertexCache` | bool false | | |
| | Disable on 2D polygons | `[GPU] PGXPDisableOn2DPolygons` | bool false | | |
| | Geometry tolerance | `[GPU] PGXPTolerance` | float −1 | in hundredths: Steps −100, then 0–10000 by 25; presets −100, 0, 50, 100, 200, 500, 1000; −100 "Off", else "x px" | |

- [ ] **Step 1: Failing tests.** `GraphicsScreenTest`: the three sections in order; Software hides every hardware
  row and shows threaded rendering; the MSAA row writes both keys (4x SSAA → 4, true); turning PGXP off while it's
  focused hides its rows and the focus stays on it (Review Focus 4); the depth clear threshold writes
  `PGXPDepthClearThreshold` and leaves `PGXPDepthBuffer` alone; the sprite filter's default follows the texture
  filter's value in settings.ini; Box shows the downsampling scale. `GraphicsSettingsTest`: tolerance −1 ↔ −100,
  0.25 ↔ 25.
- [ ] **Steps 2–4:** FAIL, implement, PASS, `quick` (XML).
- [ ] **Step 5: Commit**

```
Android: Graphics: rendering, enhancements and PGXP, as desktop has them

- Renderer (Software too), internal resolution to 16x, downsampling, multisampling, threaded presentation/rendering
- Texture filters, true colour and its dithering rows, widescreen, line detection, NTSC timings and the rest
- PGXP and its options, shown with it on; the depth clear threshold under its own key
```

### Task 9: Graphics: display, on-screen display, screenshots, texture replacement

**Files:** as Task 8, plus `GameCallsTest.kt`.

| Section | Row | Key | Kind, default | Scale / shown | Note |
|---|---|---|---|---|---|
| Display | Aspect ratio | `[Display] AspectRatio` | name `DisplayAspectRatio`, `Auto (Game Native)` | today's friendly texts become choice notes | |
| | Custom ratio | `[Display] CustomAspectRatioNumerator` / `…Denominator` | ints 4 / 3 | two NumberRows, Range 1–64; with `Custom` | |
| | Deinterlacing | `[Display] DeinterlacingMode` | name | | |
| | Crop | `[Display] CropMode` | name | | |
| | Scaling | `[Display] Scaling` | name | | |
| | Screen position | `[Display] Alignment` | name | | |
| | Rotation | `[Display] Rotation` | name | | |
| | Stretch vertically | `[Display] StretchVertically` | bool false | | |
| | Force 4:3 for FMVs | `[Display] Force4_3For24Bit` | bool false | | |
| On-screen display | Show messages | `[Display] ShowOSDMessages` | bool true | | "The game's messages, such as a memory card save. The app's own still show." |
| | Show FPS, Show speed, Show resolution, Show CPU usage, Show GPU usage, Show GPU statistics, Show latency, Show frame times, Show controller input | `[Display] ShowFPS`, `ShowSpeed`, `ShowResolution`, `ShowCPU`, `ShowGPU`, `ShowGPUStatistics`, `ShowLatencyStatistics`, `ShowFrameTimes`, `ShowInputs` | bools false | | |
| | Statistics size | `[Display] OSDScale` | float 100 | Range 25–500 by 5, presets 50, 75, 100, 150, 200; "$n%" | "The size of what's drawn over the game." |
| Screenshots | Size | `[Display] ScreenshotMode` | name | | |
| | Format | `[Display] ScreenshotFormat` | name | | |
| | Quality | `[Display] ScreenshotQuality` | int 85 | Range 1–100 by 1, presets 50, 75, 85, 90, 95, 100; "$n%" | |
| Texture replacement | VRAM write replacements | `[TextureReplacements] EnableVRAMWriteReplacements` | bool false | | "From the data folder's textures folder." |
| | Preload | `[TextureReplacements] PreloadTextures` | bool false | with replacements on | |

OSD scale reaches the core's ImGui overlays (FPS and the rest), not the app's message layer: the builder's call the
design leaves is to offer it, under that name and note.

- [ ] **Step 1: Failing tests.** `GraphicsScreenTest`: Custom shows the two ratio rows, writing 21 and 9; the OSD
  section's ten switches each toggle their key. `GameCallsTest`: `noCoreMessagesWithShowOSDMessagesOff` (boot padtest
  with it off, `resetGame()`: no `onOsdMessage` with key `system_reset`) beside `aCoreMessageReachesTheApp` (on: it
  comes). Expect this one to pass at once: it pins the core's behaviour (see "Found in the code").
- [ ] **Steps 2–4:** FAIL (screen), implement, PASS, `quick` (XML).
- [ ] **Step 5: Commit**

```
Android: Graphics: display, on-screen display, screenshots and texture replacement

- Every aspect ratio, Custom with its numerator and denominator; deinterlacing, crop, scaling, position, rotation
- The on-screen display's messages, statistics and size; a test that hidden messages never reach the app
- Screenshot size, format and quality; VRAM write texture replacements and preloading
```

### Task 10: Audio, and the memory card slots

**Files:** Create `settings/AudioSettings.kt`, `ui/AudioScreen.kt`, `androidTest/…/ui/AudioScreenTest.kt`,
`settings/MemoryCardSettings.kt`; modify `ui/MemcardSyncScreen.kt`, `MainActivity.kt` (its route also loads
`CorePage.MemoryCards`), `app/SettingsPages.kt` (`sharedCards`, read with that page), `MemcardSyncScreenTest.kt`.

Audio:

| Section | Row | Key | Kind, default | Scale / shown | Note |
|---|---|---|---|---|---|
| Volume | Output volume | `[Audio] OutputVolume` | int 100 | Range 0–200 by 5, presets 0, 50, 100, 150, 200; "$n%" | |
| | Fast-forward volume | `[Audio] FastForwardVolume` | int 100 | Range 0–100 by 5 | |
| | Mute | `[Audio] OutputMuted` | bool false | | |
| | Mute CD audio | `[CDROM] MuteCDAudio` | bool false | | "Music and voices played from the disc." |
| Output | Stretch mode | `[Audio] StretchMode` | name `AudioStretchMode` | | "How sound keeps up when the game isn't at 100%." |
| | Buffer size | `[Audio] BufferMS` | int 100 (Android's) | Range 10–500 by 10, "$n ms" | |
| | Minimal output latency | `[Audio] OutputLatencyMinimal` | bool false | | "The lowest the device allows without crackling." |
| | Output latency | `[Audio] OutputLatencyMS` | int 20 | Range 1–500 by 1, presets 10, 20, 50, 100; "$n ms"; shown with minimal latency off | |

Memory cards: a **Cards** section after the sync rows. Card 1 type and Card 2 type (`[MemoryCards] Card1Type`,
`Card2Type`, name `MemoryCardType`, defaults the core's `PerGameTitle` and `None`); when a type is `Shared`, Shared
card 1/2 (`Card1Path`/`Card2Path`, default `""`): choices `""` labelled "shared_card_1.mcd (the default)" (2 for card
2), then `sharedCards`, then a current value not among them as "(from settings.ini)"; and One card for all discs of a
game (`UsePlaylistTitle`, true; note "A multi-disc game keeps its saves on one card."). Manage cards keeps the focus
on opening.

Hub: Audio, "Volume, latency", after Graphics; Memory cards' summary becomes "Card types, sync, the card editor".

- [ ] **Step 1: Failing tests.** `AudioScreenTest`: the output latency row goes with minimal latency on; fast-forward
  volume stops at 100. `MemcardSyncScreenTest`: Card 1's shared row shows only with `Shared`, lists the fake's cards
  after the default, and picking one writes `Card1Path`; `UsePlaylistTitle` toggles; the focus starts on Manage cards.
- [ ] **Steps 2–4:** FAIL, implement, PASS, `quick` (XML).
- [ ] **Step 5: Commit**

```
Android: the Audio page, and the memory card slots on Memory cards

- Output and fast-forward volume, mute, mute CD audio, stretch mode, buffer size and output latency
- Each slot's card type, its shared card among the memory card folder's, and one card for a game's discs
```

### Task 11: Achievements' rows, and the unlock card's duration

**Files:** Modify `settings/AchievementsSettings.kt`, `ui/AchievementsScreen.kt`, `app/AchievementNotices.kt`,
`app/AchievementOverlay.kt`, `app/AppController.kt` (passes `seconds` to `AchievementNotices.from`); tests
`AchievementNoticesTest`, `AchievementOverlayTest`, `AchievementsPageTest`, `AchievementsScreenTest`.

- `AchievementsSettings` gains `encore` (`[Cheevos] EncoreMode`, false), `spectator` (`SpectatorMode`, false),
  `unofficial` (`UnofficialTestMode`, false), `unlockSeconds` (`NotificationsDuration`, int 5), `leaderboardSeconds`
  (`LeaderboardsDuration`, int 10), written as ints (the core reads ints).
- Rows after Live indicators: Encore mode ("Each session starts as if nothing were unlocked."), Spectator mode
  ("Unlocks aren't sent to RetroAchievements."), Test unofficial achievements ("Lists unofficial sets, which aren't
  tracked."), then, while messages are on, Unlock message time and Leaderboard message time (NumberRows, Range 3–30 by
  1, presets 3, 5, 10, 15, 30, "$n s").
- `AchievementNotices.from(…, seconds: Float, …)`; `AchievementNotice.Card` gains `durationMs: Long`: an unlock's is
  `seconds × 1000`, mastery's stays `AchievementOverlay.MASTERY_MS`. `AchievementOverlay.next()` uses the card's.

- [ ] **Step 1: Failing tests.** `AchievementNoticesTest`: `anUnlockLastsTheCoresSeconds` (12f → 12000),
  `masteryKeepsItsTime`. `AchievementOverlayTest`: a 12 s unlock card is still up at 11 s and gone at 12 s; paused time
  in the menu still holds. `AchievementsPageTest`: the new fields load and save only when changed. Screen: the two
  duration rows go when messages are off.
- [ ] **Steps 2–4:** FAIL, implement, PASS, `quick` (XML).
- [ ] **Step 5: Commit**

```
Android: Achievements: encore, spectator and unofficial modes, and the messages' durations

- Encore mode, spectator mode and test unofficial achievements ([Cheevos])
- How long unlock and leaderboard messages stay; the unlock card now lasts as long as the setting says
```

### Task 12: Docs, the full tier, and the branch

**Files:** `docs/android.md` ("Changing settings", "Results on the emulator").

- [ ] **Step 1:** Rewrite "Changing settings": the hub's ten entries in order; the number row and section headings
  in the controls paragraph; the table gains a row per page section (page, rows, keys, values) instead of a row per
  setting where a section is long; hardcore notes; what still needs `settings.ini` (developer tools, `[Hacks]` but old
  MDEC, input profiles, cheats, the hotkeys stage 2 adds); keep the example, now for a developer key.
- [ ] **Step 2:** `GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh full` in the
  background; read every result XML (shards and the OpenGL ES part). Then `./gradlew check`.
- [ ] **Step 3:** On `goose-test-1`, open each page with the keyboard's arrows and Enter, change a row on each, and
  check `settings.ini` and a padtest boot (the OSD statistics, pause on start, a slot save's `.bak`); record it under
  "Results on the emulator".
- [ ] **Step 4: Commit and push** (`git push origin HEAD:claude/android-settings`, with the credential helper).

```
Docs: Android's settings pages, stage 1

- Changing settings: the ten pages, the number row, sections, hardcore notes, and what stays in settings.ini
- Results on the emulator for stage 1
```

Then `superpowers:finishing-a-development-branch`; the PR to `master` waits for the user's word.
