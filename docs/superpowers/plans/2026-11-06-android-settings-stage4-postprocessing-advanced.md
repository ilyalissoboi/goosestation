<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android settings, stage 4: post-processing and Advanced — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The settings get a Post-processing page (each chain's On, its shaders added, removed, moved and reloaded,
each stage's options from the shader's metadata) and an Advanced page with the useful rows, both global and for
each game, kept as desktop keeps them.

**Architecture:** The bridge gains the core's `PostProcessing::Config` calls on a layer chosen by key (blank:
`settings.ini`; else the game's file), the shaders that load, and Reload. A `PostProcessingPage` controller reads
and edits the chains on the core's dispatcher, as the other pages do; its screens (the chains, Add a shader, a
stage) serve the global hub and the game's hub alike. Advanced is a `CorePage` of plain `Setting`s, plus the app's
work for Log to file (the log reaches the data folder) and fast memory access (said when the device can't).

**Tech Stack:** C++20 core (`src/util`), JNI (`android/app/src/cpp`), Kotlin + Jetpack Compose, JUnit (JVM),
AndroidX instrumented tests.

**Spec:** `docs/superpowers/specs/2026-11-05-android-settings-design.md` (the Post-processing and Advanced rows of
"The pages", "Settings for each game", stage 4).

## Global Constraints

- `docs/design-system.md` and its tokens are binding: controller-first, focus always visible, no window dialogs.
  Every new page goes into `FocusAuditTest` and `OrientationLayoutTest`, with a key-driven screen test.
- Keys and values are desktop's: `[PostProcessing]` and `[InternalPostProcessing]` `Enabled`, `StageCount`,
  `[<chain>/StageN] ShaderName` and option keys, all written by `PostProcessing::Config` itself; Advanced as listed in
  Task 6. Files stay ones desktop reads.
- A game's chain is the core's: the game's file's chain applies, whole, only when it sets that chain's `Enabled`
  (`PostProcessing::GetLoadSettingsInterface`); otherwise the global one does. There's no key-by-key fall through.
- Offer only shaders that work: those whose metadata loads, and no ReShade (`.fx`) shader while the chain runs on
  OpenGL ES (spike, below).
- The library's global settings stay locked while a game runs; Reload is offered in a running game's settings only.
- Developer tools stay in `settings.ini`. Don't copy Big Picture's known bugs (depth clear threshold row, vertex
  cache default, fast-forward volume default, "Log To System Console"'s section name).
- Android-only core code goes behind `__ANDROID__`; desktop behaviour is unchanged, but for Task 1's bug fix. Clean
  room: nothing from DuckStation after `a5bf1ba5cee7`, nor its closed Android app.
- `gh` as ilyalissoboi (`GH_TOKEN=$(gh auth token --user ilyalissoboi)`), never the GitHub MCP. Emulators only; the
  AYN Thor needs the user's go-ahead. Commit each task when it's done and show its message after. Commit messages end
  with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Found in the code, and on the emulators

**The spike** (padtest at 1280×960, each shipped shader alone in the chain; a throwaway test and a perf log line,
never committed, removed before Task 1):

| Shader kind | Vulkan (emulator-5580, host GPU) | OpenGL ES (emulator-5582, SwiftShader) |
|---|---|---|
| GLSL (`shaders/*.glsl`, 6) | all compile and run | all compile and run |
| "dolphinfx" (`shaders/dolphinfx/**/*.glsl`, 4) | all compile and run | all compile and run |
| ReShade (`shaders/reshade/Shaders/**/*.fx`, 13) | 12 run; `crt/crt-hyllian-sinc` fails to load | **none compile** |

- This core has **no dolphinfx loader**: the `dolphinfx/` files are GLSL and load as GLSL ("… [GLSL]").
- **ReShade under OpenGL ES:** reshadefx's GLSL generator relies on implicit int→float conversion (`float / const
  int`), which GLSL ES rejects, so every `.fx` fails to compile and the core turns the chain off ("Failed to compile
  post-processing shader …"). Fixing the generator is its own task (follow-up).
- **`crt/crt-hyllian-sinc.fx` is broken everywhere** (desktop too): it includes `../misc/include/mask.fxh` and
  `geom.fxh`, which `data/resources` lacks. Its metadata doesn't load, so the "loads" filter hides it.
- **Speed:** nothing near the frame budget on the emulator's GPU (GPU time 0.1 ms with no chain, at most 2.2 ms for
  `edge-smoothing/super-xbr`; 59 fps throughout). On SwiftShader (a CPU rasteriser, not a device figure)
  `crt-lottes` halves the frame rate (28 fps); the rest hold 56–59. Real numbers need the Thor (go-ahead).
- **The Internal chain** (`[InternalPostProcessing]`, Qt's second tab, not in Big Picture) runs the same set under
  Vulkan.

**The code:**

- `PostProcessing::Config` (`src/util/postprocessing.cpp:227-366`) does every edit on a `SettingsInterface`:
  `AddStage` (loads the shader's metadata first; options left unset), `RemoveStage`, `MoveStageUp/Down` (swap the
  stage sections, so options move with their stage), `SetStageOption` (the core's own value format),
  `GetStageOptions` (values over defaults), `ClearStages`. `GetAvailableShaderNames()` lists the resources' and the
  mirrored `shaders/` folder's `.glsl` and `reshade/Shaders/*.fx`.
- **Applying:** every `System::ApplySettings` calls `PostProcessing::UpdateSettings()` (`system.cpp:4526`), which
  reloads a stage only when its shader changed and takes option values at once. A game's change goes through stage
  3's `applyGameSettings(key)`.
- **`Chain::HasStages()` is inverted** (`postprocessing.h:117`, `return m_stages.empty();`): `ReloadShaders()` says
  "No post-processing shaders are selected." with stages, and "reloads" without. Big Picture's and Qt's Reload and
  the hotkey are broken on desktop too. Task 1 fixes it.
- **Log to file** writes `goosestation.log` in the working copy (`settings.cpp:869`), which `DataLayout.kept()`
  never mirrors (`mirror/DataLayout.kt`), and each start rewrites it: as is, the row would do nothing a player sees.
- **Fast memory access:** the host forces it off on devices whose pages are larger than the build's
  (`android_host.cpp:2909-2920`); `MMap` is the default on Android arm64 (`settings.h:486`).
- **Qt shows Advanced, logging included, in a game's settings;** desktop's Copy (`Settings::Save(si, true)`) leaves
  logging out. When Region check and Allow booting without an SBI file are read (boot, disc insert) is the builder's
  to confirm in `cdrom.cpp:833` and `system.cpp:4157`.

## Review Focus

1. **A chain from desktop that can't run here** (a ReShade stage while on OpenGL ES; a shader name this install
   doesn't have): the core turns the whole chain off; the page must say which stage and why, not show it as
   working. Pinned in Task 3 (`aStageThatCantRunSaysSo`) and Task 2 (`aMissingShaderReadsAsMissing`).
2. **A float option's value** written by Android reads on desktop exactly as desktop writes it (`0.0001`, never
   `1.0E-4`): written by the core's `SetStageOption`, never formatted in Kotlin. Pinned in Task 2
   (`aFloatOptionIsWrittenInTheCoresFormat`).
3. **Moving or removing a stage keeps each stage's options with it.** Pinned in Task 2
   (`moveAndRemoveKeepEachStagesOptions`).
4. **A desktop game file with stages but no `Enabled`** (Qt lets that happen): the core ignores it; the page shows
   "Use global", counts the keys as the page's, and taking its own chain keeps those stages rather than copying the
   global one over them. Pinned in Task 5 (`aGamesStagesWithoutEnabledAreKept`).
5. **The mirror pulls a changed `settings.ini` or game file while a chain shows:** the page reads it again, keeping
   its focus. Pinned in Task 3 (`aPullReadsTheChainsAgain`).

---

## Calls made here *(builder's calls, for the user to confirm with the plan)*

- **Both chains, as Qt:** the Post-processing page has a **Display** section and an **Internal** section (applied
  at the internal resolution, before the picture is scaled), each with its On row, its stages and "Add a shader".
  Big Picture shows Display only; the Internal chain works here, and the code is the same for both.
- **The pages:** a stage is a `NavRow` ("1. crt-lottes", summary "GLSL · 6 options", or why it can't run). Its page
  has Move up, Move down and Remove (`ActionButton`s; Remove doesn't ask, as desktop) over the shader's options,
  grouped by the shader's categories. "Add a shader" opens a page listing the offered shaders (`NavRow`s, the kind as
  summary); A adds it last and goes back, the focus on the new stage. No "Clear shaders": Remove covers it.
- **Option rows:** on/off is a `SwitchRow`; an int with named choices a `CyclerRow`; a number a `NumberRow` per
  component, labelled "Name (Red)", "(Green)", "(Blue)", "(Alpha)" as Qt, stepping by the shader's step (or a
  hundredth of the range without one, as Qt), its list the minimum, default and maximum and the quarter points. An
  option without a UI name is hidden, as desktop. The shader's tooltip is the note.
- **A stage that can't run** (a ReShade stage on OpenGL ES, or its shader not found) says so as its summary and on
  its page: "Doesn't run with OpenGL ES: post-processing stays off while it's in the chain." / "Not found in this
  install's shaders: post-processing stays off while it's in the chain." It can still be moved and removed.
- **Reload shaders** (an `ActionButton` under the chains) is offered in a running game's settings only: the library's
  global pages are locked while a game runs, and each boot loads the shaders anyway.
- **For a game:** each chain's On row is "Use global (On)", On and Off. Picking On or Off gives the game its own
  chain: a copy of the global one, unless its file already has stages. "Use global" deletes the game's chain
  (`Enabled`, `StageCount` and the stages), asking first when it has stages ("This game's 3 shaders are removed."),
  in an `OverlayPanel`. While it uses the global chain, its stages show read-only, dimmed, saying "The global chain:
  pick On or Off to give this game its own." A chain the game sets is one row in the hub's count.
- **Advanced's sections:** Logging (log level, log to file), Save states (compression, load devices from save
  states), CD-ROM (region check, allow booting without an SBI file), CPU (recompiler memory exceptions, block
  linking, fast memory access), with desktop's names and notes.
- **A game's Advanced page leaves out the two logging rows:** desktop's Copy treats them as global, and a game's
  own log setting would change which file reaches the data folder mid-session.
- **Log to file reaches the data folder:** while it's on in `settings.ini`, the core reports `goosestation.log` and
  `goosestation-previous.log` as layout files and the mirror copies them; at each start the host renames the last
  run's log to `goosestation-previous.log` before the core opens it, so a crash's log survives the restart.
- **Fast memory access on a device with larger pages:** the row shows disabled, "This device's memory pages are too
  large for it.", as a hardcore row does.
- **Task 1's core fix** goes in this branch, as its own commit: Reload needs it, it's one line, and it fixes
  desktop's Reload too. (The other way: its own PR first, as #46 and #47 were.)

## File Structure

- `src/util/postprocessing.h` (Task 1's fix).
- `android/app/src/cpp/android_host.{h,cpp}`, `jni_bridge.cpp`: the chain calls, the shaders, Reload, the renderer
  check, the fastmem check, the log's layout files and rename, the name lists.
- `core/NativeLibrary.kt`, `core/PostProcessingInfo.kt` (new: the bridge's types); `app/CoreApi.kt` (+calls).
- `settings/PostProcessingSettings.kt` (new: chains, stages, options, option rows and scales),
  `settings/AdvancedSettings.kt` (new), `settings/NameList.kt` (+3), `settings/NextStart.kt`.
- `app/PostProcessingPage.kt` (new), `app/SettingsPages.kt` (`CorePage.Advanced`), `app/GameSettingsPages.kt`
  (`GamePage.PostProcessing`, `.Advanced`), `app/AppController.kt`, `MainActivity.kt`.
- `ui/PostProcessingScreen.kt` (new: the chains, Add a shader, a stage), `ui/AdvancedScreen.kt` (new),
  `ui/SettingsScreen.kt` (+2 entries), `ui/GameSettingsHost.kt`.
- `mirror/DataLayout.kt` (the log while it's on).
- Tests: `androidTest/…/core/PostProcessingAccessTest.kt` (new), `GameCallsTest.kt`, `SettingsAccessTest.kt`;
  `androidTest/…/ui/PostProcessingScreenTest.kt` (new), `AdvancedScreenTest.kt` (new), `GamePagesScreenTest.kt`,
  `FocusAuditTest.kt`, `OrientationLayoutTest.kt`, `SettingsScreenTest.kt`; `test/…/settings/
  PostProcessingSettingsTest.kt` (new), `test/…/app/PostProcessingPageTest.kt` (new), `GameSettingsPagesTest.kt`,
  `SettingsPagesTest.kt`, `FakeCoreApi.kt`; `test/…/mirror/DataLayoutTest.kt`.
- Docs: `docs/android.md` (the pages, the spike's table, known limits), `docs/design-system.md` (option rows, if a
  new pattern), `docs/roadmap.md` item 16.

Kotlin paths are under `android/app/src/main/java/io/github/ilyalissoboi/goosestation/` (tests under
`android/app/src/test/java/…` and `android/app/src/androidTest/java/…`).

## Running the tests

- Environment: `source /tmp/gs3-env.sh`. Desktop: `cmake --build build --target common-tests duckstation-qt &&
  build/bin/common-tests`.
- JVM: `cd android && ./gradlew -q testDebugUnitTest --tests '<Class>'`, then `./gradlew -q ktlintCheck`.
- One instrumented class: `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.<pkg>.<Class>`.
- Each task that touches the UI or the bridge ends with `android/scripts/run-instrumented-tests.sh quick`, read from
  the result XML under `app/build/outputs/androidTest-results/`. The full tier (`GOOSE_TEST_DATA=~/work/goose-test-data`,
  in the background) runs in Task 8.

---

### Task 1: The core: Reload with stages reloads them

**Files:**
- Modify: `src/util/postprocessing.h:117`
- Test: `androidTest/…/core/GameCallsTest.kt`

**Interfaces:**
- Produces (test JNI): `NativeLibrary.reloadPostProcessingShaders(): Boolean` (`PostProcessing::ReloadShaders()` on
  the CPU thread, waiting; false without a game), used for real by Task 5.

- [ ] **Step 1: Write the failing test** (`GameCallsTest`, booting padtest with `withPadtest`):

```kotlin
@Test fun reloadWithAStageReloadsIt() {
    // settings: [PostProcessing] Enabled = true, StageCount = 1, [PostProcessing/Stage1] ShaderName = simple-flip
    // body: assertTrue(NativeLibrary.reloadPostProcessingShaders())
    //       the OSD messages include "Post-processing shaders reloaded.", never "No post-processing shaders are selected."
}
```

- [ ] **Step 2: Run it:** FAIL (false, "No post-processing shaders are selected.").
- [ ] **Step 3: Fix** `HasStages()` to `return !m_stages.empty();`, and add the JNI call.
- [ ] **Step 4: Run** it: PASS. Build `duckstation-qt` and run `common-tests`.
- [ ] **Step 5: Commit**

```
Core: Reload Shaders reloads a chain that has shaders

- PostProcessing::Chain::HasStages() was inverted, so ReloadShaders() refused a chain with stages ("No
  post-processing shaders are selected.") and went on without: desktop's Reload Shaders and the hotkey too
- Android: reloadPostProcessingShaders() over JNI
```

---

### Task 2: The bridge: the chains on a layer, and the shaders that load

**Files:**
- Modify: `android/app/src/cpp/android_host.{h,cpp}`, `jni_bridge.cpp`, `core/NativeLibrary.kt`, `app/CoreApi.kt`
  (+`NativeCoreApi`), `test/…/app/FakeCoreApi.kt`
- Create: `core/PostProcessingInfo.kt`; Test: `androidTest/…/core/PostProcessingAccessTest.kt`

**Interfaces:**
- Produces (`core/PostProcessingInfo.kt`, built by JNI):
  - `class ShaderOptionInfo(val name: String, val uiName: String, val category: String, val tooltip: String, val
    type: Int /* 0 bool, 1 int, 2 float */, val vectorSize: Int, val defaultValue: FloatArray, val minValue:
    FloatArray, val maxValue: FloatArray, val stepValue: FloatArray, val value: FloatArray, val choices:
    Array<String>)`: ints and bools carried as floats (exact below 2^24).
  - `class StageInfo(val shader: String, val kind: String /* "GLSL", "ReShade", "" when not found */, val options:
    Array<ShaderOptionInfo>)`.
- Produces (`NativeLibrary`; `key` null for `settings.ini` under the settings lock, else the game's file through
  stage 3's `EditGameSettings`/`ReadGameSetting`; `section` `"PostProcessing"` or `"InternalPostProcessing"`; on the
  caller's thread; nothing before `initialize`):
  - `getPostProcessingShaders(): Array<String>`: name, kind, name, kind… of `GetAvailableShaderNames()` whose
    metadata loads (`Config::AddStage` on a scratch `MemorySettingsInterface` succeeds). The kind is ReShade when
    the name's `.fx` exists in the mirrored or the resources' `reshade/Shaders`, as `TryLoadingShader` looks first.
  - `getPostProcessingStages(key: String?, section: String): Array<StageInfo>` (`GetStageCount`,
    `GetStageShaderName`, `GetStageOptions`; a shader that doesn't load has kind `""` and no options).
  - `addPostProcessingStage(key: String?, section: String, shader: String): String?` (null, or the error).
  - `removePostProcessingStage(key, section, index: Int)`, `movePostProcessingStage(key, section, index: Int, down:
    Boolean)`.
  - `setPostProcessingOption(key, section, index: Int, name: String, values: FloatArray)`: finds the option in
    `GetStageOptions`, sets its `value` (ints rounded, bools 0/1) and calls `Config::SetStageOption`.
  - `copyPostProcessingChain(key: String, section: String)`: the global `Enabled`, `StageCount` and stage sections
    into the game's file; `clearPostProcessingChain(key: String, section: String)`: `Config::ClearStages` then deletes
    `StageCount` and `Enabled`.
  - `postProcessingUsesOpenGLES(): Boolean`: with a game, `g_gpu_device`'s API is OpenGL ES; else the layered
    renderer's `Settings::GetRenderAPIForRenderer` is, or Vulkan failed in this run.
  - test only: `isPostProcessingActiveForTest(section: String): Boolean` (the chain's `IsActive()`, on the CPU thread).
- Produces (`CoreApi`, and `NativeCoreApi`, `FakeCoreApi` in memory): the same calls and Task 1's
  `reloadPostProcessingShaders()`, with `List<Pair<String,
  String>>` for the shaders and `List<StageInfo>` for the stages.

- [ ] **Step 1: Write the failing tests** (`PostProcessingAccessTest`, as `SettingsAccessTest`, files read with
  `Ini`):

```kotlin
@Test fun theShippedShadersAreOfferedButTheBrokenOne() { /* has "crt-lottes" to "GLSL", "dolphinfx/edge-smoothing/XBR" to "GLSL", "FilmGrain2" to "ReShade"; no "crt/crt-hyllian-sinc" */ }
@Test fun aShaderInTheShadersFolderIsOffered() { /* dataRoot/shaders/mine.glsl (a copy of simple-flip): "mine" to "GLSL" */ }
@Test fun addWritesDesktopsKeys() { /* add crt-lottes to null/PostProcessing: [PostProcessing] StageCount = 1, [PostProcessing/Stage1] ShaderName = crt-lottes */ }
@Test fun addingAnUnknownShaderSaysWhy() { /* returns non-null; StageCount unchanged */ }
@Test fun theStagesReadWithTheirOptions() { /* crt-lottes: options non-empty, value == defaultValue for an unset one */ }
@Test fun aFloatOptionIsWrittenInTheCoresFormat() { /* set an option to 0.0001f: the stage section has "0.0001", read back 0.0001f */ }
@Test fun moveAndRemoveKeepEachStagesOptions() { /* two stages with a set option each; move 0 down: names and values swap; remove 0: the other's value stays */ }
@Test fun aMissingShaderReadsAsMissing() { /* StageCount = 1, ShaderName = nothing-here: kind "", no options */ }
@Test fun aGamesChainIsItsFile() { /* key SLUS-00001: add writes gamesettings/SLUS-00001.ini only; settings.ini untouched */ }
@Test fun copyAndClearTheGamesChain() { /* global chain of 2 copied whole; clear leaves no [PostProcessing] keys, and the file goes when empty */ }
@Test fun withoutAGameOpenGLFollowsTheRenderer() { /* [GPU] Renderer = OpenGL: true; Vulkan: false */ }
```

and in `GameCallsTest`: `aGlobalChainRunsInTheGame` (settings: Enabled, simple-flip; `isPostProcessingActiveForTest
("PostProcessing")` true) and `aGamesChainReplacesTheGlobalOne` (global Enabled = true with simple-flip; the key's
file Enabled = false: inactive; `applyGameSettings` after the file's Enabled = true: active).

- [ ] **Step 2: Run** `PostProcessingAccessTest`: fails to compile.
- [ ] **Step 3: Implement** the host's calls (one helper that runs an edit on the key's layer: the base layer, saved
  as the other base writes are, or stage 3's `EditGameSettings`), their JNI, the types, `NativeLibrary`, `CoreApi`
  and `FakeCoreApi`.
- [ ] **Step 4: Run** it and the two `GameCallsTest` tests: PASS. Then `run-instrumented-tests.sh quick`.
- [ ] **Step 5: Commit**

```
Android: the bridge edits the post-processing chains, on settings.ini or a game's file

- The core's PostProcessing::Config calls by key and chain: stages with their options, add, remove, move, set an
  option in the core's own format
- The shaders offered: those whose metadata loads, each with its kind (GLSL or ReShade)
- A game's chain copied from the global one or cleared; whether the chain runs on OpenGL ES
```

---

### Task 3: The chains, their stages and options, in Kotlin

**Files:**
- Create: `settings/PostProcessingSettings.kt`, `app/PostProcessingPage.kt`
- Test: `test/…/settings/PostProcessingSettingsTest.kt`, `test/…/app/PostProcessingPageTest.kt`

**Interfaces:**
- Consumes: Task 2's `CoreApi` calls and types.
- Produces (`settings/PostProcessingSettings.kt`):
  - `enum class Chain(val section: String, val label: String) { Display("PostProcessing", "Display"),
    Internal("InternalPostProcessing", "Internal") }`, `fun Chain.enabled() = BoolSetting(section, "Enabled", false)`.
  - `enum class ShaderKind { GLSL, ReShade }`; `data class Shader(val name: String, val kind: ShaderKind)`.
  - `data class Stage(val shader: String, val kind: ShaderKind?, val options: List<ShaderOptionInfo>)` and
    `fun Stage.problem(gles: Boolean): String?` (the two notes in the Calls, or null).
  - `fun offered(shaders: List<Shader>, gles: Boolean): List<Shader>` (no ReShade on OpenGL ES).
  - `sealed interface OptionRow { data class Heading(val category: String); data class Toggle(val option:
    ShaderOptionInfo); data class Pick(val option: ShaderOptionInfo); data class Value(val option: ShaderOptionInfo,
    val component: Int, val label: String, val scale: NumberScale) }` and `fun optionRows(options:
    List<ShaderOptionInfo>): List<OptionRow>`.
  - `fun OptionRow.Value.units(value: Float): Int` and `fun OptionRow.Value.value(units: Int): Float`: the scale is
    fixed-point, `10^d` units per 1, `d` the decimals the step, minimum and default need (at most 4), so a value off
    the steps still shows as itself.
- Produces (`app/PostProcessingPage.kt`):
  - `data class ChainView(val chain: Chain, val enabled: Boolean, val stages: List<Stage>, val own: Boolean /* true
    globally; for a game, its file sets Enabled */, val globalEnabled: Boolean)`.
  - `class PostProcessingPage(scope, core: CoroutineDispatcher, api: CoreApi)`, main thread only, with state
    `key: String?`, `running: Boolean`, `chains: Map<Chain, ChainView>` (empty until read), `shaders: List<Shader>`
    (offered), `gles: Boolean`, `stage: Pair<Chain, Int>?` (the stage page), `adding: Chain?` (the Add page),
    `asking: Chain?` (Use global's question), `focus: Pair<Chain, Int>?` (the stage to focus on the chains page);
    and `open(key: String?, running: Boolean)`, `close()`, `setEnabled(chain, on: Boolean?)` (null: Use global),
    `confirmUseGlobal()`, `cancelQuestion()`, `openAdd(chain)`, `closeAdd()`, `add(chain, shader)`, `openStage(chain,
    index)`, `closeStage()`, `remove(chain, index)`, `move(chain, index, down: Boolean)`, `setOption(chain, index,
    option: ShaderOptionInfo, values: List<Float>)`, `reload()`, `reread()` (a pull changed the files).
  - Every edit runs on `core`: the bridge call, then `applyGameSettings(key)` when `running`; then the chains are
    read again. Reads overtaken by an edit are dropped, as `SettingsPages`' are.

- [ ] **Step 1: Write the failing JVM tests:**

```kotlin
// PostProcessingSettingsTest
@Test fun reShadeIsntOfferedOnOpenGLES() { offered(listOf(glsl, reshade), gles = true) == listOf(glsl) }
@Test fun aStageThatCantRunSaysSo() { /* ReShade + gles: "Doesn't run with OpenGL ES: …"; kind null: "Not found in …"; GLSL: null */ }
@Test fun anOptionWithoutAUiNameIsHidden() { }
@Test fun categoriesBecomeHeadings() { /* [Heading("Scanlines"), Value…, Heading("Mask"), …] in the shader's order */ }
@Test fun aVectorIsAValueRowPerComponent() { /* vectorSize 3: labels "Tint (Red)", "Tint (Green)", "Tint (Blue)" */ }
@Test fun anIntWithChoicesIsAPick() { }
@Test fun aStepFreeRangeStepsByAHundredth() { /* min 0, max 2, step 0: next(0) is 0.02 */ }
@Test fun aValueOffTheStepsShowsAsItself() { /* step 0.05, value 0.123: label "0.123", next up is 0.15 */ }
@Test fun thePresetsAreTheEndsDefaultAndQuarters() { }

// PostProcessingPageTest (FakeCoreApi)
@Test fun openReadsBothChainsAndTheOfferedShaders() { }
@Test fun addAppendsAndFocusesTheNewStage() { }
@Test fun moveFollowsTheStage() { /* stage page open on 0, move down: stage == Display to 1 */ }
@Test fun removeGoesBackFocusingTheNext() { }
@Test fun anEditInARunningGameApplies() { /* applyGameSettings(key) called once per edit; never without running */ }
@Test fun aPullReadsTheChainsAgain() { /* reread() with the fake's chain changed: chains updated, stage page kept */ }
@Test fun aReadOvertakenByAnEditIsDropped() { }
```

- [ ] **Step 2: Run them:** fail to compile.
- [ ] **Step 3: Implement** both files. Game-specific behaviour (`own`, Use global) is Task 5's; here `own` is true
  for a null key.
- [ ] **Step 4: Run them:** PASS; `ktlintCheck` green.
- [ ] **Step 5: Commit**

```
Android: the post-processing chains, their stages and options, for the pages

- Display and Internal chains read with their stages; the shaders offered (no ReShade on OpenGL ES)
- Each option's rows: on/off, a choice, or a number per component on the shader's own steps, its value kept exact
- PostProcessingPage: add, remove, move, set an option, reload, each on the core's dispatcher
```

---

### Task 4: The Post-processing pages in the settings hub

**Files:**
- Create: `ui/PostProcessingScreen.kt`
- Modify: `ui/SettingsScreen.kt` (`SettingsEntry.PostProcessing`, after Graphics: "Post-processing", "Shaders over
  the picture"), `app/AppController.kt` (`Screen.PostProcessing`, `openSettingsPage`, `closeSettingsPage`),
  `MainActivity.kt`
- Test: `androidTest/…/ui/PostProcessingScreenTest.kt` (new), `SettingsScreenTest.kt`, `FocusAuditTest.kt`,
  `OrientationLayoutTest.kt`

**Interfaces:**
- Consumes: Task 3's `PostProcessingPage`, `ChainView`, `optionRows`, `Stage.problem`.
- Produces: `@Composable fun PostProcessingScreen(page: PostProcessingPage, onBack: () -> Unit)`, which shows the
  chains page, the Add page (`page.adding`) or a stage's page (`page.stage`), B on either going back to the chains
  page and focusing what opened it. Internal composables take a `ChainView` and callbacks, so Task 5 reuses them.

- [ ] **Step 1: Write the failing screen tests** (`PostProcessingScreenTest`, driven by keys, over a
  `PostProcessingPage` on a `FakeCoreApi`):

```kotlin
@Test fun theChainsPageListsEachChainsStages() { /* "Display" section: Post-processing switch, "1. crt-lottes" (summary "GLSL · N options"), "Add a shader"; then "Internal" */ }
@Test fun addOpensTheListAndAddsWithA() { /* A on Add a shader; DPAD to "simple-flip"; A: back on the chains page, focus on "2. simple-flip" */ }
@Test fun noReShadeOnOpenGLES() { /* fake gles = true: the Add list has no "FilmGrain2" */ }
@Test fun aStagesPageMovesAndRemoves() { /* Move up disabled on the first; Move down: the page title is now stage 2; Remove: back, focus on the next */ }
@Test fun aNumberOptionStepsWithTheDPad() { /* RIGHT on a float row: setOption called with value + step */ }
@Test fun aVectorShowsAComponentPerRow() { }
@Test fun aStageThatCantRunShowsWhy() { }
@Test fun reloadOnlyInARunningGame() { /* running false: no "Reload shaders"; true: A calls reload */ }
```

`SettingsScreenTest`: the hub's entries in order (… Graphics, Post-processing, Audio …). `FocusAuditTest` and
`OrientationLayoutTest`: the chains page, the Add page and a stage's page (with a vector and a choice option), in both
orientations.

- [ ] **Step 2: Run them:** fail to compile.
- [ ] **Step 3: Implement** the screens with the existing rows (`SettingsPage`, `SettingsSection`, `SwitchRow`,
  `NavRow`, `ActionButton`, `CyclerRow`, `NumberRow`), and the hub's route (`PostProcessingPage.open(null, false)`).
- [ ] **Step 4: Run them**, `lintDebug`, then `run-instrumented-tests.sh quick` (XML).
- [ ] **Step 5: Commit**

```
Android: a Post-processing page for each chain's shaders

- The hub's Post-processing (after Graphics): the Display and Internal chains, each with its On switch and stages
- Add a shader lists those that work here; a stage's page moves, removes and sets its options
- A stage that can't run (ReShade on OpenGL ES, a shader not found) says so
```

---

### Task 5: A game's Post-processing page

**Files:**
- Modify: `app/PostProcessingPage.kt` (a game's chains), `app/GameSettingsPages.kt` (`GamePage.PostProcessing`
  after Graphics; its count), `ui/PostProcessingScreen.kt` (Use global, the question, read-only global stages),
  `ui/GameSettingsHost.kt`, `app/AppController.kt` (the game hub's and the in-game menu's pages)
- Test: `test/…/app/PostProcessingPageTest.kt`, `GameSettingsPagesTest.kt`; `androidTest/…/ui/
  GamePagesScreenTest.kt`, `PostProcessingScreenTest.kt`, `FocusAuditTest.kt`, `OrientationLayoutTest.kt`;
  `androidTest/…/core/GameCallsTest.kt`

**Interfaces:**
- Consumes: Task 2's `copyPostProcessingChain`, `clearPostProcessingChain`; Task 3's page; stage 3's
  `GameSettingsPages`, `LocalGameContext`, `useGlobalLabel`, `gameMark`.
- Produces: `PostProcessingPage.open(key, running)` for a game: each `ChainView.own` is whether the game's file
  sets that chain's `Enabled`; `setEnabled(chain, on)` on a chain the game doesn't own copies the global chain first
  unless the file has stages; `setEnabled(chain, null)` sets `asking` when the game's chain has stages, else clears
  it. `GameSummary.counts[GamePage.PostProcessing]` counts each chain the file sets (`Enabled`, `StageCount` or a
  stage section) as one, and those keys leave `others`.

- [ ] **Step 1: Write the failing tests:**

```kotlin
// PostProcessingPageTest
@Test fun aGameWithoutItsOwnChainShowsTheGlobalOne() { /* own false, stages are the global ones */ }
@Test fun onCopiesTheGlobalChain() { /* setEnabled(Display, true): copyPostProcessingChain then Enabled = true in the game's file */ }
@Test fun aGamesStagesWithoutEnabledAreKept() { /* the file has StageCount = 1 (desktop's), no Enabled: on keeps its stage, no copy */ }
@Test fun useGlobalAsksWhenThereAreStages() { /* asking == Display; confirm: clearPostProcessingChain; cancel: nothing */ }
@Test fun useGlobalWithoutStagesDoesntAsk() { }
// GameSettingsPagesTest
@Test fun aChainCountsOnceOnItsPage() { /* Enabled, StageCount, Stage1/ShaderName, Stage1/Opt: counts[PostProcessing] == 1, others == 0 */ }
// GamePagesScreenTest
@Test fun theGamesChainRowUsesGlobal() { /* "Use global (Off)" first; the stages dimmed with the global chain's note; picking On marks the row "Set for this game" */ }
// GameCallsTest
@Test fun aChainChangeInTheGameAppliesAtOnce() { /* in-game: setEnabled(Display, true) with simple-flip: isPostProcessingActiveForTest true without a restart */ }
@Test fun reloadInTheGame() { /* page.reload() in a running game: "Post-processing shaders reloaded." */ }
```

`FocusAuditTest`/`OrientationLayoutTest`: the game's chains page and its question.

- [ ] **Step 2: Run them:** fail (compile, then the behaviour).
- [ ] **Step 3: Implement** the game's side of the page, the hub's entry and count, and the route from both hubs.
- [ ] **Step 4: Run them**, then `run-instrumented-tests.sh quick` (XML).
- [ ] **Step 5: Commit**

```
Android: a game's own post-processing chains

- Each chain uses the global one until the game takes its own (a copy of the global chain, or the stages its file
  already has); Use global removes the game's chain, asking first when it has shaders
- The game's hub counts each chain it sets once; in a running game a change applies at once, and Reload is offered
```

---

### Task 6: The Advanced page, global and for a game

**Files:**
- Create: `settings/AdvancedSettings.kt`, `ui/AdvancedScreen.kt`
- Modify: `settings/NameList.kt` (`SaveStateCompression("SaveStateCompressionMode")`, `FastmemMode("CPUFastmemMode")`,
  `LogLevel("LogLevel")`), `android/app/src/cpp/jni_bridge.cpp` (their lists; `isFastmemAvailable()`),
  `core/NativeLibrary.kt`, `app/CoreApi.kt`, `app/SettingsPages.kt` (`CorePage.Advanced`; `fastmemAvailable`),
  `app/GameSettingsPages.kt` (`GamePage.Advanced`, last), `settings/NextStart.kt`, `ui/SettingsScreen.kt`
  (`SettingsEntry.Advanced`, before Folders: "Advanced", "Logging, save states, CPU and CD-ROM tweaks"),
  `ui/GameSettingsHost.kt`, `app/AppController.kt`, `MainActivity.kt`
- Test: `androidTest/…/ui/AdvancedScreenTest.kt` (new), `GamePagesScreenTest.kt`, `SettingsScreenTest.kt`,
  `FocusAuditTest.kt`, `OrientationLayoutTest.kt`; `androidTest/…/core/SettingsAccessTest.kt`;
  `test/…/app/SettingsPagesTest.kt`, `GameSettingsPagesTest.kt`

**Interfaces:**
- Produces (`AdvancedSettings`, the core's Android arm64 defaults):
  `LOG_LEVEL = NameSetting("Logging", "LogLevel", "Info", NameList.LogLevel)`,
  `LOG_TO_FILE = BoolSetting("Logging", "LogToFile", false)`,
  `SAVE_STATE_COMPRESSION = NameSetting("Main", "SaveStateCompression", "ZstDefault", NameList.SaveStateCompression)`,
  `LOAD_DEVICES = BoolSetting("Main", "LoadDevicesFromSaveStates", false)`,
  `REGION_CHECK = BoolSetting("CDROM", "RegionCheck", false)`,
  `BOOT_WITHOUT_SBI = BoolSetting("CDROM", "AllowBootingWithoutSBIFile", false)`,
  `MEMORY_EXCEPTIONS = BoolSetting("CPU", "RecompilerMemoryExceptions", false)`,
  `BLOCK_LINKING = BoolSetting("CPU", "RecompilerBlockLinking", true)`,
  `FASTMEM = NameSetting("CPU", "FastmemMode", "MMap", NameList.FastmemMode)`;
  `val all`, and `val GAME = all - LOG_LEVEL - LOG_TO_FILE`.
- Produces: `NativeLibrary.isFastmemAvailable(): Boolean` (the system's page size is at most the build's).
- Produces: `@Composable fun AdvancedScreen(values: SettingValues, names: CoreNames, fastmemAvailable: Boolean,
  onChange: (SettingValues) -> Unit, onBack: () -> Unit)`, rows through `ui/PageRows.kt`'s helpers (so a game's are
  "Use global" rows), the logging section left out when `values.isGame`.

- [ ] **Step 1: Write the failing tests:**

```kotlin
// SettingsAccessTest
@Test fun advancedNameListsAreTheCores() { /* SaveStateCompressionMode starts "Uncompressed", has "ZstDefault"; CPUFastmemMode Disabled, MMap, LUT; LogLevel None…Trace */ }
// AdvancedScreenTest
@Test fun theRowsInTheirSections() { /* Logging: Log level, Log to file; Save states: …; CD-ROM: …; CPU: … */ }
@Test fun aChangeWritesDesktopsKey() { /* RIGHT on Save state compression: ZstHigh; switch on Region check */ }
@Test fun fastmemOffOnLargePages() { /* fastmemAvailable = false: disabled, "This device's memory pages are too large for it." */ }
// GamePagesScreenTest
@Test fun aGamesAdvancedHasNoLogging() { /* no "Log level"; "Use global (Off)" on Region check */ }
@Test fun regionCheckAppliesNextStart() { /* running: "Applies when the game next starts." on Region check and Allow booting without an SBI file */ }
// SettingsPagesTest / GameSettingsPagesTest
@Test fun advancedSavesOnlyWhatChanged() { }
@Test fun aGamesAdvancedCountsItsRows() { }
```

plus the hub order in `SettingsScreenTest`, and the global and game Advanced pages in `FocusAuditTest` and
`OrientationLayoutTest`.

- [ ] **Step 2: Run them:** fail to compile.
- [ ] **Step 3: Implement**, confirming in the core which rows wait for the next start before listing them in
  `NextStart` (Region check, Allow booting without an SBI file expected; the CPU rows reset the code cache at once).
- [ ] **Step 4: Run them**, `lintDebug`, then `run-instrumented-tests.sh quick` (XML).
- [ ] **Step 5: Commit**

```
Android: an Advanced page with the useful tweaks, global and for each game

- Logging (level, to file), save states (compression, load devices), CD-ROM (region check, boot without an SBI
  file), CPU (memory exceptions, block linking, fast memory access), by desktop's keys and the core's names
- A game's page has all but the logging rows; region check and SBI apply when the game next starts
- Fast memory access is disabled, saying why, on a device whose pages are too large for it
```

---

### Task 7: Log to file reaches the data folder

**Files:**
- Modify: `android/app/src/cpp/android_host.cpp` (`AndroidHost::Initialize`: the last run's log renamed; the
  layout's files), `jni_bridge.cpp` and `core/NativeLibrary.kt` (`getDataFiles()`), `mirror/DataLayout.kt`,
  `app/AppController.kt:1490` (where the layout is taken from the core)
- Test: `test/…/mirror/DataLayoutTest.kt`; `androidTest/…/core/SettingsAccessTest.kt`

**Interfaces:**
- Produces (host): at `Initialize`, before the settings load, `goosestation.log` in the data root is renamed over
  `goosestation-previous.log`. `NativeLibrary.getDataFiles(): Array<String>`: `goosestation.log` and
  `goosestation-previous.log` while `g_settings.log_to_file`, else empty; `AppController` adds them to the paths it
  gives `DataLayout.withCorePaths`.
- Produces (`DataLayout`): `kept()` keeps the two logs off the mirror only while the layout's files don't name them.

- [ ] **Step 1: Write the failing tests:**

```kotlin
// DataLayoutTest
@Test fun theLogIsMirroredOnlyWhenTheCoreNamesIt() { /* DEFAULT: mirrored("goosestation.log") false; withCorePaths(listOf("goosestation.log", "goosestation-previous.log"), …): both true */ }
// SettingsAccessTest
@Test fun theLastRunsLogIsKept() { /* write dataRoot/goosestation.log "old"; initialize: goosestation-previous.log reads "old" */ }
@Test fun theLogIsInTheLayoutWhileItsOn() { /* LogToFile = true, applySettings: the core's paths include both; off: neither */ }
```

- [ ] **Step 2: Run them:** FAIL.
- [ ] **Step 3: Implement** the rename, the layout report and `kept()`.
- [ ] **Step 4: Run them**, then `run-instrumented-tests.sh quick` (XML).
- [ ] **Step 5: Commit**

```
Android: with Log to file on, the core's log reaches the data folder

- The mirror copies goosestation.log while [Logging] LogToFile is on, and the last run's, kept at each start as
  goosestation-previous.log so a crash's log survives the restart
- Off, the logs stay on the device as before
```

---

### Task 8: Docs, the full tier and the emulator walk-through

**Files:**
- Modify: `docs/android.md` ("Changing settings": the two pages; the spike's table and the ReShade/OpenGL ES limit
  under "Known limits"; a walk-through section "The settings pages, stage 4"), `docs/design-system.md` (shader
  option rows, if the builder made a pattern other pages may use), `docs/roadmap.md` item 16 (the settings complete)

- [ ] **Step 1: Update the docs.**
- [ ] **Step 2: Run** `./gradlew check`, `common-tests`, `duckstation-qt`, then the full tier in the background
  (`GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh full`), reading every part's XML
  and the OpenGL ES part's context count.
- [ ] **Step 3: Walk through on goose-arm64** (the app, a real game): add crt-lottes and a ReShade shader, change
  options in the game and see the picture change (screenshots), move, remove, reload; a game's own chain; Advanced's
  rows; Log to file's two files in the data folder after a restart. Note results in `docs/android.md`.
- [ ] **Step 4: Commit**

```
Docs: Android's Post-processing and Advanced pages

- docs/android.md: the pages, which shaders run on which renderer, the stage 4 walk-through
- Roadmap item 16: Android's settings are complete
```

On the Thor (with the user's go-ahead only): each shipped shader's speed under Vulkan and OpenGL ES.
