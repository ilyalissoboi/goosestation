# Android Stage 1, Plan 3: settings and controls — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the minimum settings, changed from the library with a controller: BIOS per region, renderer, internal
resolution, aspect ratio, fast boot, the FPS overlay and the folders. Each controller can be remapped on its own, and
the mapping is kept in the data folder. Back, the Mode button, or holding Select+Start for one second pauses the game
and offers **Resume** or **Quit to library**. If Vulkan won't start, OpenGL ES takes over.

**Architecture:** settings stay in the core's `settings.ini`, read and written over JNI with desktop's keys and
values. The library's **Settings** button opens a hub with four pages: Controllers, Graphics, BIOS and Folders.
Folders is Plan 2's screen. Choices open as lists drawn in the page itself, never in a window.

Each controller's `Android-<n>` index is stored in settings.ini, keyed by its Android descriptor, so `[Pad1]`'s
bindings mean the same pad after a restart. Remapping edits only that device's entries in `[Pad1]`, and the next
input is captured through the core's `InputManager` hook, as Qt does it.

The Back prompt is a Compose overlay on the game screen. Kotlin decides when it opens, before any key reaches the
core, so the game never sees the press that opens it. The Vulkan fallback is a small hook in `System::CreateGPU`,
behind `__ANDROID__`: the Android host sets OpenGL ES for the rest of the process.

**Tech Stack:** C++20, CMake, Android NDK, JNI, Kotlin, coroutines, Jetpack Compose, Material 3, GoogleTest, JUnit 4,
AndroidX Test (orchestrator), Compose UI tests.

**Spec:** [docs/superpowers/specs/2026-10-25-android-frontend-design.md](../specs/2026-10-25-android-frontend-design.md):
Goals 2 and 5, *Controller and UI*, the roadmap line under *Constraints*, and *Settings* under *The emulator inside the
app*. It follows [Plan 1](2026-10-25-android-stage1-plan1-emulator.md) (#23) and
[Plan 2](2026-10-26-android-stage1-plan2-storage-library.md) (#25), and ends Stage 1.

**Choices made with the user on 2026-10-27** (where the design left them open):
- **Binding storage:** a descriptor table in settings.ini (`[Android] ControllerDescriptors`, a string list whose
  position is the `Android-<n>` index). The bindings stay in `[Pad1]` as desktop's do. This was chosen over one file
  per controller, and over mapping keys in Kotlin.
- **Vulkan failure:** retry with OpenGL ES straight away, for the rest of the process. settings.ini keeps the user's
  choice. An OSD message and a note on the Graphics page say so. This was chosen over saving `Renderer = OpenGL`.
- **Choices with several values:** an inline list. A on the row opens its options right below it, with the current
  one focused. A picks one and closes the list, and B closes it with no change. This was chosen over Left/Right
  cyclers.
- **SD card eject (rechecked from Plan 2):** it stays as it is. Ejecting the data folder's card stops the game even
  when the game itself is on internal storage.

**Calls made while writing this plan** *(writer's call; revisit in review)*:
- **Select+Start doesn't reach the game.** When one of the two is held and the other goes down, that second press is
  held back. If both are still held after 1000 ms, the prompt opens and the held-back press is never sent. If either
  is let go first, the held-back press is sent then. Passing both through would let Start open the game's own pause
  menu, and the spec says the game doesn't receive the press.
- **The menu triggers are the pad's own keys:** `KEYCODE_BACK`, `KEYCODE_BUTTON_MODE`, and the pad's own
  `KEYCODE_BUTTON_SELECT` + `KEYCODE_BUTTON_START`, whatever they are mapped to. A remap can't lock the user out of the
  menu. Back and Mode can't be bound.
- **Settings change only from the library**, never while a game runs. So nothing has to apply mid-game.
- **Hub order:** Controllers, Graphics, BIOS, Folders. Fast boot goes on the BIOS page and the FPS overlay on Graphics,
  as on desktop. The FPS overlay is `[Display] ShowFPS` alone.
- **Offered values** (a value in the file that isn't offered is shown, marked "(from settings.ini)", and kept until
  changed):
  - **Renderer:** `Automatic`, `Vulkan`, `OpenGL` (labelled OpenGL ES).
  - **Resolution:** `0` (labelled "Match the screen") and `1` to `8`.
  - **Aspect ratio:** every core name except `Custom`.
- **Default bindings are added only the first time a descriptor is seen**, not on every connection. Otherwise a
  reconnect would add back a binding the user had removed.

## Global Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app. Dolphin and ARMSX2
  are reference for approach only; copy no code.
- New files: `// SPDX-FileCopyrightText: 2026 GooseStation contributors` and `// SPDX-License-Identifier: GPL-3.0`
  (`#` comments for scripts and Gradle files, `<!-- -->` for XML).
- Android: `minSdk 29`, `arm64-v8a` only, package `io.github.ilyalissoboi.goosestation`. No new permissions.
- Settings are the core's `settings.ini` in the data folder, with desktop's section, key and value names. There is no
  Android-only settings store. The only new keys are under `[Android]`.
- No `AlertDialog`, Compose `Dialog` or `Popup`, `DropdownMenu`, `ExposedDropdownMenuBox` or `ModalBottomSheet`.
  Task 9's build check enforces this.
- Every screen works with a D-pad alone:
  - a visible focus ring;
  - the right element focused when the screen opens;
  - A or Enter activates, and B or Back goes back;
  - Back from a page focuses the hub entry that opened it.

  Touch works too.
- Settings reads and writes, BIOS listing and JNI calls that do I/O never run on the main thread. They go through
  `AppController`'s `coreDispatcher`, so they never overlap `reloadSettings` or a library scan.
- JNI: class and method IDs are cached at `JNI_OnLoad`, local references are deleted in loops, and a Java exception
  becomes an error return. These are Plan 2's rules, kept for the new calls.
- Desktop must stay green: after every task that touches `src/`, `cmake --build build` succeeds with no new warnings
  in changed files, and `build/bin/common-tests` passes. `android/scripts/run-native-tests.sh` passes on
  `goose-arm64`.
- Core changes sit behind `__ANDROID__` or in Android-only files (`src/util/android_*`). Nothing changes on other
  platforms.
- Devices: the arm64 emulator (`goose-arm64`, `adb -s emulator-5554`) for every task. Use the AYN Thor
  (`adb -s 64dc3c35`) only after the user says go.
- Commits: the user reviews every commit message in chat before it is committed. Work on a branch
  `claude/android-settings-controls`, cut from `master`. Propose one commit per task at the checkpoints after Tasks
  4, 7, 10 and 11, in the log's style (`Core: …`, `Util: …`, `Android: …`, `Docs: …`). The plan document goes in
  the first checkpoint, as its own `Docs:` commit. Push as `ilyalissoboi`, as in the user's memory note.

## Review Focus

1. **A pad that reports Nintendo labels**, so `BUTTON_A` is the right face button. Remapping Cross to the bottom
   button and Circle to the right one must hold after the app restarts, and after the pad reconnects. No default may
   be added back. (Task 5 `remapSurvivesRestartAndReconnect`, Task 11 check.)
2. **An axis resting away from 0, or a button held as capture starts.** Some pads report their triggers on `Z`/`RZ`,
   resting at −1, and the user may still be holding A from pressing the row. Capture must take the next real press,
   not the resting value or the held button. (Task 6 `BindingCapture` tests.)
3. **Select+Start as a chord.** Pressing Start while Select is held, and letting go within a second, sends Start
   once, late, and never sticks. Holding Select alone works as Select. The prompt opens only when both are held for
   1 s. (Task 8 `MenuTriggerTest`.)
4. **The prompt and the app's lifecycle.**
   - Home with the prompt open, then back: the game stays paused, with the prompt showing.
   - The prompt opened during a boot pauses the game once it boots.
   - Nothing held when the prompt opened stays pressed after Resume.

   (Task 8 tests and checks.)
5. **A BIOS override whose file is gone.** A rescan or a new BIOS folder removes the image an override names. The
   region goes back to Automatic, so the next boot doesn't fail on a missing file. (Task 4 `BiosOverridesTest`.)

---
### Task 1: settings access from Kotlin, and desktop's aspect ratio names

**Files:**
- Modify: `src/core/settings.cpp`:
  - In `s_display_aspect_ratio_names` (~1384), remove the `#ifndef __ANDROID__` branch, so Android uses desktop's
    names (`Stretch To Fill`, not `Auto (Match Window)`).
  - In `Settings::Load` (~448), remove the `[Display] Stretch` override under `#ifdef __ANDROID__`. Keep the
    audio-expansion line.
- Modify: `android/app/src/cpp/android_host.cpp/.h`, `android/app/src/cpp/jni_bridge.cpp`, `…/core/NativeLibrary.kt`
- Create: `…/settings/CoreSettings.kt`
- Test: `android/app/src/androidTest/java/…/core/SettingsAccessTest.kt`

**Interfaces:**
- Produces (`NativeLibrary`, every call takes the settings lock, works on the base layer, saves, and does nothing or
  returns the default before `initialize`):
  - `external fun getStringSetting(section: String, key: String, default: String): String`
  - `external fun setStringSetting(section: String, key: String, value: String?)`: null deletes the key.
  - `external fun getIntSetting(section: String, key: String, default: Int): Int`
  - `external fun setIntSetting(section: String, key: String, value: Int)`
  - `external fun applySettings()`: `System::ApplySettings(false)` on the CPU thread, blocking.
  - `external fun getAspectRatioNames(): Array<String>`: `Settings::GetDisplayAspectRatioName` for each
    `DisplayAspectRatio` below `Count`, in enum order.
- Produces (Kotlin, package `…goosestation.settings`):
  - `interface CoreSettings`, with `getString`, `setString`, `getInt`, `setInt`, `getBool` and `setBool`, the same
    shapes as above.
  - `object NativeCoreSettings : CoreSettings`, over `NativeLibrary`.
  - `class MemoryCoreSettings : CoreSettings`, a map keyed by `section/key`, in `src/test` for the JVM tests.
    Instrumented tests use the real one.

- [ ] **Step 1: Write the failing instrumented tests** (`SettingsAccessTest`, set up like `SettingsSetupTest`, with
  its `initialize` helper and `readSettings()` parser):
  - `stringSettingRoundTrips`: `setStringSetting("GPU", "Renderer", "OpenGL")`. `getStringSetting` returns it, and
    `settings.ini` has `Renderer = OpenGL` under `[GPU]`.
  - `nullDeletes`: then `setStringSetting("GPU", "Renderer", null)`. The key is gone from the file, and
    `getStringSetting(…, "Automatic")` gives `Automatic`.
  - `intSettingRoundTrips`: `setIntSetting("GPU", "ResolutionScale", 4)` gives `ResolutionScale = 4` in the file and
    `4` back.
  - `aspectRatioNamesAreDesktops`: `getAspectRatioNames()` contains `Stretch To Fill`, `Auto (Game Native)`, `4:3`
    and `PAR 1:1`, and doesn't contain `Auto (Match Window)`.
  - `defaultsBeforeInitialize`: in a fresh process, `getIntSetting("GPU", "ResolutionScale", 7)` is `7`.
- [ ] **Step 2: Run them to see them fail.** `./gradlew connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.core.SettingsAccessTest`
  → compile errors (RED).
- [ ] **Step 3: Implement**, as listed. Follow `GetBoolSetting`/`SetBoolSetting` for the host functions.
- [ ] **Step 4: Run them to see them pass**, then the whole instrumented suite. Desktop still green.

### Task 2: the Vulkan → OpenGL ES fallback

**Files:**
- Modify: `src/core/host.h`, adding under `#ifdef __ANDROID__`:
  `bool OnVulkanDeviceFailed(const Error& error);` ("The Vulkan device couldn't be created: returns whether to try
  OpenGL ES instead.").
- Modify: `src/core/system.cpp`, in `System::CreateGPU` (~2333). Under `#ifdef __ANDROID__`: when
  `Host::CreateGPUDevice(api, error)` fails with `api == RenderAPI::Vulkan` and `Host::OnVulkanDeviceFailed(*error)`
  returns true, do the following:
  - set `renderer = GPURenderer::HardwareOpenGL` and `api = RenderAPI::OpenGL`;
  - clear `error`;
  - try `Host::CreateGPUDevice` once more before giving up.

  `error` may be null, so use a local `Error` when it is.
- Modify: `android/app/src/cpp/android_host.cpp/.h`, `jni_bridge.cpp`, `…/core/NativeLibrary.kt`

**Interfaces:**
- Produces (C++, Android host):
  - `static std::atomic_bool s_vulkan_failed`, which lives as long as the process.
  - `Host::OnVulkanDeviceFailed` does the following and returns true:
    - logs `Vulkan didn't start ({error}), using OpenGL ES until the app restarts.`;
    - sets `s_vulkan_failed`;
    - sets `g_settings.gpu_renderer = GPURenderer::HardwareOpenGL`;
    - shows `Host::AddOSDMessage("Vulkan didn't start on this device, so GooseStation is using OpenGL ES.",
      Host::OSD_WARNING_DURATION)`.
  - `Host::LoadSettings`: while `s_vulkan_failed` is set, a `gpu_renderer` whose API is Vulkan
    (`Settings::GetRenderAPIForRenderer`, with `Automatic` resolved through `Settings::GetAutomaticRenderer()`)
    becomes `HardwareOpenGL`. This follows the fastmem override already there. Later boots in the process go straight
    to OpenGL ES, and `ApplySettings` never flips it back.
  - A test hook: `Host::AcquireRenderWindow(recreate_window = true)` fails, logging
    `Failing the Vulkan device for testing (debug.goosestation.fail_vulkan).`, when all of these hold:
    - `__system_property_get("debug.goosestation.fail_vulkan")` is `"1"`;
    - `g_gpu_device` is a Vulkan device (`GetRenderAPI() == RenderAPI::Vulkan`);
    - `s_vulkan_failed` is false.

    `debug.*` properties can only be set from `adb shell setprop`.
- Produces (Kotlin): `external fun isVulkanFailed(): Boolean`.

TDD note: the boot path needs a BIOS and a surface, which the instrumented suite doesn't have, so this task is
checked by hand on the emulator (Step 3). Say so in the review package.

- [ ] **Step 1: Implement**, as listed.
- [ ] **Step 2: Desktop still green.** `cmake --build build` and `build/bin/common-tests`. The Qt build doesn't see
  `OnVulkanDeviceFailed`.
- [ ] **Step 3: Check on `goose-arm64`** (BIOS and padtest set up as in `docs/android.md`). Set
  `[GPU] Renderer = Vulkan` (edit settings.ini, or use Task 3's page once it exists), then
  `adb shell setprop debug.goosestation.fail_vulkan 1`, and start padtest.
  - Expected in the log, in order:
    - `Trying to create a Vulkan GPU device...`
    - `Failing the Vulkan device for testing`
    - `Vulkan didn't start (…), using OpenGL ES until the app restarts.`
    - `Trying to create a OpenGL GPU device...`
  - padtest draws, and the OSD shows the message.
  - Quit and start it again: no `Trying to create a Vulkan GPU device...` line.
  - `settings.ini` still has `Renderer = Vulkan`.
  - `adb shell setprop debug.goosestation.fail_vulkan 0`, force-stop and start again: Vulkan is used.
  - Repeat once with `Renderer = Automatic`.
- [ ] **Step 4: Run** `./gradlew connectedDebugAndroidTest` and `android/scripts/run-native-tests.sh` → PASS.

### Task 3: the settings hub, its rows, and the Graphics page

**Files:**
- Create: `…/ui/components/SettingsRows.kt`, `…/ui/SettingsScreen.kt` (the hub), `…/ui/GraphicsScreen.kt`,
  `…/settings/GraphicsSettings.kt`
- Modify:
  - `…/app/AppController.kt`:
    - `Screen` gains `Settings`, `Graphics`, `Bios` and `Controllers`;
    - `focusFolders` becomes `focusSettings`, and so does `LibraryScreen`'s parameter of that name;
    - `closeFolders` returns to `Settings`.
  - `…/ui/LibraryScreen.kt`: the top bar's **Folders** becomes **Settings**, and `onOpenFolders` becomes
    `onOpenSettings`.
  - `…/MainActivity.kt`
  - `…/ui/FoldersScreen.kt`: only its doc comment and back target change.
- Test: `android/app/src/test/java/…/settings/GraphicsSettingsTest.kt`; `android/app/src/androidTest/java/…/ui/SettingsRowsTest.kt`,
  `…/ui/SettingsScreenTest.kt`; update `LibraryScreenTest` (Folders → Settings)

**Interfaces:**
- Consumes: `CoreSettings`, `getAspectRatioNames`, `applySettings` (Task 1); `isVulkanFailed` (Task 2);
  `focusRing`, `ActionButton`, `MessageBar` (Plan 2).
- Produces (`SettingsRows.kt`):
  - `data class Choice<T>(val value: T, val label: String, val note: String? = null)`
  - `@Composable fun ChoiceRow(label: String, choices: List<Choice<T>>, selected: T, onSelect: (T) -> Unit,
    modifier: Modifier = Modifier, note: String? = null)`:
    - Closed, the row shows the label and the selected choice's label.
    - A, Enter or a tap opens it. The choices are listed under the row, the selected one is focused and marked
      "✓", and the page scrolls to keep it in view.
    - A on a choice calls `onSelect`, if it's a different value, then closes the list with focus back on the row.
    - B or Back closes it with no change, again with focus back on the row. That is a `BackHandler(enabled =
      open)` inside the row.
    - Only one row is open at a time: opening another closes the first.
  - `@Composable fun SwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier =
    Modifier, note: String? = null)`: A toggles it, and it shows "On" or "Off".
  - `@Composable fun NavRow(label: String, summary: String?, onClick: () -> Unit, modifier: Modifier = Modifier)`
  - `@Composable fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit)`:
    a title bar, a scrolling column, and `BackHandler(onBack = onBack)`.
- Produces (`GraphicsSettings.kt`):
  - `data class GraphicsSettings(val renderer: String, val resolutionScale: Int, val aspectRatio: String, val
    showFps: Boolean)`, with `companion fun load(s: CoreSettings): GraphicsSettings` and `fun save(s: CoreSettings,
    old: GraphicsSettings)` (writes only the fields that changed). The keys and defaults are:
    - `[GPU] Renderer`, default `Automatic`;
    - `[GPU] ResolutionScale`, default `1`;
    - `[Display] AspectRatio`, default `Auto (Game Native)`;
    - `[Display] ShowFPS`, default `false`.
  - `object GraphicsChoices` builds each row's choices:
    - `fun renderers(current: String): List<Choice<String>>`: `Automatic` ("Automatic (Vulkan where it works
      well)"), `Vulkan`, and `OpenGL` ("OpenGL ES"). A current value not among them is added at the end with
      "(from settings.ini)".
    - `fun scales(current: Int): List<Choice<Int>>`: `0` ("Match the screen"), `1` ("1x (native)"), and `2` to `8`
      ("2x" to "8x"). Any other current value is added in order, with "(from settings.ini)".
    - `fun aspectRatios(names: List<String>, current: String): List<Choice<String>>`: every name but `Custom`, with
      these labels:
      - `Auto (Game Native)` → "The game's own (4:3 for most games)"
      - `Stretch To Fill` → "Stretch to the screen"
      - `PAR 1:1` → "Square pixels"
      - any other name is shown as it is.
- Produces (hub, `SettingsScreen`): `@Composable fun SettingsScreen(focusEntry: SettingsEntry?, onOpen:
  (SettingsEntry) -> Unit, onBack: () -> Unit)`, with `enum class SettingsEntry { Controllers, Graphics, Bios,
  Folders }`.
  - The entries are in that order, with these summaries:
    - "Remap each controller's buttons"
    - "Renderer, resolution, aspect ratio, FPS"
    - "Which BIOS each region uses, fast boot"
    - "BIOS, games and data folders"
  - On opening, `focusEntry` has focus (the page just left), or Controllers if it's null.
  - B goes back to the library, with the focus on **Settings**.
- `GraphicsScreen(settings: GraphicsSettings, aspectNames: List<String>, vulkanFailed: Boolean, onChange:
  (GraphicsSettings) -> Unit, onBack: () -> Unit)`. The rows are Renderer, Internal resolution, Aspect ratio and
  "Show FPS".
  - With `vulkanFailed`, Renderer's note is "Vulkan didn't start on this device, so games use OpenGL ES until the app
    restarts."
  - The internal resolution's note, for any value but `1`: "Higher is sharper and slower. Match the screen picks the
    highest that fits."
  - The first row is focused on opening.
- `AppController`:
  - `fun openSettings()`, `fun openSettingsPage(entry: SettingsEntry)`, `fun closeSettingsPage()`, `fun
    closeSettings()`;
  - `var graphics: GraphicsSettings?` (null until loaded), and `fun changeGraphics(new: GraphicsSettings)`.
  - Loading and saving run on `coreDispatcher`. After a save comes `NativeLibrary.applySettings()`; the mirror's
    next push sends `settings.ini`.
  - Settings open only from the library, and only when `ready` and `!gameActive`.

- [ ] **Step 1: Write the failing JVM tests** (`GraphicsSettingsTest`, on `MemoryCoreSettings`):
  - `defaultsWhenMissing`: an empty store gives `GraphicsSettings("Automatic", 1, "Auto (Game Native)", false)`.
  - `saveWritesOnlyChanges`: changing `resolutionScale` from 1 to 4 writes `[GPU] ResolutionScale = 4`, and nothing
    else is written.
  - `unofferedValuesAreKept`: `scales(12)` ends with `Choice(12, "12x (from settings.ini)")`. `renderers("Software")`
    ends with a `Software` choice marked "(from settings.ini)".
  - `customAspectIsLeftOut`: `aspectRatios(names, "4:3")` has no `Custom`, and labels `Stretch To Fill` "Stretch to
    the screen".
- [ ] **Step 2: Write the failing UI tests.**
  - `SettingsRowsTest`, on a page holding two `ChoiceRow`s and a `SwitchRow`:
    - the first row's `Enter` opens it, and the selected choice is focused;
    - `DirectionDown` then `Enter` calls `onSelect` with the next value, closes the list, and focuses the row
      again;
    - `Back` while it's open closes it without calling `onSelect`, and the page's own back isn't called;
    - opening the second row closes the first;
    - `SwitchRow`'s `Enter` calls `onToggle(true)`.
  - `SettingsScreenTest`:
    - Controllers has focus with a null `focusEntry`, and BIOS has it with `focusEntry = Bios`;
    - `Back` calls `onBack`.
  - In `LibraryScreenTest`, the old Folders checks now use **Settings**.
- [ ] **Step 3: Run them to see them fail** (RED).
- [ ] **Step 4: Implement**, as listed.
- [ ] **Step 5: Run them to see them pass**, then try it on `goose-arm64` with the D-pad.
  - Library → Settings → Graphics: set OpenGL ES, 3x and Stretch to the screen, and turn on Show FPS.
  - Back twice to the library, start padtest: the log has `Trying to create a OpenGL GPU device...`, the picture
    fills the screen, and the FPS shows.
  - `settings.ini` in the data folder has `Renderer = OpenGL`, `ResolutionScale = 3`, `AspectRatio = Stretch To Fill`
    and `ShowFPS = true` within 5 s.

### Task 4: the BIOS page

**Files:**
- Modify: `android/app/src/cpp/bios_import.cpp/.h` (or `android_host.cpp`), `jni_bridge.cpp`, `…/core/NativeLibrary.kt`
- Create: `…/core/BiosImage.kt`, `…/settings/BiosSettings.kt`, `…/ui/BiosScreen.kt`
- Modify: `…/app/AppController.kt`, in every successful import: setup, Change, Rescan and re-pick.
- Test: `android/app/src/test/java/…/settings/BiosOverridesTest.kt`; `android/app/src/androidTest/java/…/storage/BiosImporterTest.kt`
  (extend it); `…/ui/BiosScreenTest.kt`

**Interfaces:**
- Consumes: `CoreSettings` (Task 1); `ChoiceRow`, `SwitchRow`, `SettingsPage`, `SettingsEntry.Bios` (Task 3).
- Produces (Kotlin `NativeLibrary`, both after `initialize` only; off the main thread):
  - `external fun getBiosImages(): Array<BiosImage>` lists `EmuFolders::Bios` (the app's copies) with
    `BIOS::FindBIOSImagesInDirectory`. Images whose `info` is null are left out, and the list is sorted by file name.
  - `external fun getAutoBios(region: String): String?` gives the `info->description` that
    `BIOS::FindBIOSImageInDirectory` picks for `region` (`NTSC-U`, `NTSC-J` or `PAL`), or null when there's none.
- `data class BiosImage(val fileName: String, val description: String, val region: String)`. `region` is
  `Settings::GetConsoleRegionName(info->region)`, and an image good for any region reads `Auto`.
- `enum class BiosRegion(val label: String, val key: String) { NtscU("NTSC-U (Americas)", "PathNTSCU"),
  NtscJ("NTSC-J (Japan)", "PathNTSCJ"), Pal("PAL (Europe)", "PathPAL") }`, with `val coreName` (`NTSC-U`, `NTSC-J`,
  `PAL`).
- `object BiosOverrides`:
  - `fun load(s: CoreSettings): Map<BiosRegion, String>` holds only the regions set. An empty or missing key means
    Automatic.
  - `fun set(s: CoreSettings, region: BiosRegion, fileName: String?)`: null deletes the key.
  - `fun stale(overrides: Map<BiosRegion, String>, copies: List<String>): List<BiosRegion>` is pure: the regions
    whose file isn't among `copies`.
- Behaviour:
  - After every import that copied images (`BiosImportResult.images` not empty), once the core is started,
    `AppController` deletes the `stale` overrides, using the file names in `filesDir/bios`, then calls
    `applySettings()`. A region set back like this shows on the page as Automatic, with no prompt.
  - `BiosScreen(images: List<BiosImage>, auto: Map<BiosRegion, String?>, overrides: Map<BiosRegion, String>,
    fastBoot: Boolean, onSetOverride: (BiosRegion, String?) -> Unit, onFastBoot: (Boolean) -> Unit, onBack: () ->
    Unit)`:
    - One `ChoiceRow` per region:
      - the first choice is `null`, "Automatic: <auto description>", or "Automatic: none found" when there's none;
      - then each image as its description, with the note "made for <region>" when `region` isn't `Auto` and isn't
        that row's.
    - Then `SwitchRow("Fast boot (skip the BIOS intro)")` on `[BIOS] PatchFastBoot`. Its default is the core's
      Android one, `true` (`Settings::DEFAULT_FAST_BOOT_VALUE`). Read it with `getBoolSetting(…, true)`.
    - A last line: "The images come from your BIOS folder. Change it, or rescan it, in Folders."
    - The first region row is focused on opening.

- [ ] **Step 1: Write the failing tests.**
  - `BiosOverridesTest`:
    - `staleOverrideIsFound`: `{NtscJ: "ps-30j.bin", Pal: "gone.bin"}` with copies `["ps-30j.bin"]` gives `[Pal]`.
    - `emptyMeansAutomatic`: `[BIOS] PathNTSCU = ""` isn't in `load`.
    - `setNullDeletes`.
  - `BiosImporterTest`, extended. `realBiosIsListed` (`assumeTrue` on `/data/local/tmp/goose-test/ps-30j.bin`):
    - after `initialize` and the import, `getBiosImages()` has `ps-30j.bin` with a description naming `SCPH-5500`
      and region `NTSC-J`;
    - `getAutoBios("NTSC-J")` names it;
    - `getAutoBios("PAL")` is that image too (the core falls back to a mismatched one) or null. Assert whichever
      `FindBIOSImageInDirectory` does, and comment why.
  - `BiosScreenTest`:
    - NTSC-U's row is focused on opening;
    - opening the NTSC-J row and picking the image calls `onSetOverride(NtscJ, "ps-30j.bin")`;
    - picking Automatic calls it with `null`;
    - the image's row under NTSC-U shows "made for NTSC-J".
- [ ] **Step 2: Run them to see them fail** (RED).
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run them to see them pass.** On `goose-arm64`:
  - Set NTSC-U to `ps-30j.bin` and start padtest: the log has no `No BIOS image found`, and has the region warning
    `BIOS region NTSC-J does not match requested region NTSC-U`.
  - Then **Folders** → **BIOS** → **Change** to a folder holding a different image: NTSC-U shows Automatic again,
    and `[BIOS] PathNTSCU` is gone from `settings.ini`.
- [ ] **Step 5: Checkpoint.** Propose the plan's `Docs:` commit and the commits for Tasks 1–4 in chat, and commit
  after approval.

### Task 5: controller indices kept across runs, and per-device bindings

**Files:**
- Modify: `src/util/android_input_keys.h/.cpp`, `src/util/android_input_source.h/.cpp`
- Modify: `android/app/src/cpp/android_host.cpp/.h`, `foreign_settings.cpp`, `jni_bridge.cpp`, `…/core/NativeLibrary.kt`
- Create: `…/core/PadBinding.kt`
- Test: `src/common-tests/android_input_keys_tests.cpp`; `android/app/src/androidTest/java/…/core/ControllerBindingsTest.kt`;
  extend `SettingsSetupTest`

**Interfaces:**
- Produces (C++, `AndroidInputKeys`, pure and built in common-tests):
  - `u32 IndexForDescriptor(std::vector<std::string>& descriptors, std::string_view descriptor, bool* added)`
    returns the descriptor's position, appending it (and setting `*added`) when it's new.
  - `std::optional<std::string> GetDeviceBinding(std::span<const std::string> bindings, u32 index)` gives the first
    entry that starts with `Android-<index>/`, without that prefix.
  - `std::vector<std::string> SetDeviceBinding(std::span<const std::string> bindings, u32 index, std::string_view
    binding)` drops every entry of `Android-<index>/`, keeps the others in order, then appends
    `Android-<index>/<binding>` unless `binding` is empty.
- `AndroidInputSource`:
  - Its descriptor table (`s_descriptors`, `GetIndexForDescriptor`) moves to the host.
  - `QueueDeviceConnected(s32 device_id, u32 index, std::string name)` takes the index from the host.
  - Device names stay `Android-<index>`.
- Android host:
  - The CPU thread keeps `s_descriptors` for the life of the process. `InitializeConfig` seeds it from
    `[Android] ControllerDescriptors`, a string list, after `AdaptForeignSettings`.
  - `ProcessInputQueue`'s `Connected` gets the index with `IndexForDescriptor`. When the descriptor is new, it saves
    the list and maps the pad's defaults to Port 1 (`AddControllerToPort1`). A pad already in the list gets nothing
    added, even if it has no bindings.
  - `AdaptForeignSettings` deletes `[Android] ControllerDescriptors`. After any adapt (at start, or in
    `reloadSettings`), the host writes `s_descriptors` back and maps the connected pads, as it does now. In-process
    indices never change.
  - `QueueKeyEvent` refuses only `KEYCODE_BACK` and `KEYCODE_BUTTON_MODE` (the menu triggers). Every other pad key
    reaches the source, so `BUTTON_C`, `BUTTON_Z` and `BUTTON_1`–`16` can be bound. `PadState::OnKey` reports a key
    with no generic binding as `Button<keycode>`, with `GenericInputBinding::Unknown`.
- Produces (Kotlin `NativeLibrary`; these run on the CPU thread and block; each change saves and then
  `System::ReloadInputBindings()`):
  - `external fun getPadBindings(descriptor: String): Array<PadBinding>?` is null for a descriptor that was never
    connected. It lists Port 1's controller type's bindings of type `Button`, `Axis` and `HalfAxis`, in the core's
    order.
  - `external fun setPadBinding(descriptor: String, name: String, binding: String?)` uses `SetDeviceBinding` on
    `[Pad1] <name>`. Null or empty clears this pad's entry.
  - `external fun resetPadBindings(descriptor: String)` drops this pad's entries from every `[Pad1]` key, then adds
    its defaults (`GetDefaultMapping`, as `AddControllerToPort1` does).
- `data class PadBinding(val name: String, val displayName: String, val kind: PadBindingKind, val binding: String?)`
  with `enum class PadBindingKind { Button, Axis, HalfAxis }`. `binding` is the part after `Android-<n>/` (for
  example `Button96` or `+Axis23`), or null.

- [ ] **Step 1: Write the failing native tests** (`android_input_keys_tests.cpp`):
  - `IndexForDescriptor`: `["a"]` with `"b"` gives 1 and added, then `"a"` gives 0 and not added.
  - `SetDeviceBinding`: `["Android-0/Button96", "Android-1/Button96"]`, setting index 1 to `Button97`, gives
    `["Android-0/Button96", "Android-1/Button97"]`. An empty binding gives `["Android-0/Button96"]`.
    `Android-10/…` isn't index 1's.
  - `GetDeviceBinding`: index 1 of the list above gives `Button96`, and index 2 gives nullopt.
  - `UnmappedKeysAreButtons`: `PadState::OnKey(188, true, …)` reports `Button` 188 at 1.0.
- [ ] **Step 2: Write the failing instrumented tests** (`ControllerBindingsTest`, with `SettingsSetupTest`'s helpers
  and its `waitFor`):
  - `descriptorTableIsSaved`: connect `pad-a`, then `pad-b`. `[Android] ControllerDescriptors` lists `pad-a` then
    `pad-b`.
  - `remapSurvivesRestartAndReconnect` (Review Focus 1): settings.ini is written before `initialize`, with this
    install's ID, `ControllerDescriptors = pad-b`, `ControllerDescriptors = pad-a`, and `[Pad1] Cross =
    Android-1/Button97`.
    - Connect `pad-a`: `getPadBindings("pad-a")`'s `Cross` is `Button97`, and `[Pad1] Cross` has exactly one entry.
    - Disconnect and connect it again (a new device ID): the same.
  - `newPadGetsDefaults`: an unseen `pad-c` gets `Cross = Android-<n>/Button96`.
  - `setPadBindingTouchesOnlyThatPad`: with `pad-a` and `pad-b` connected, `setPadBinding("pad-b", "Cross",
    "Button99")` leaves `pad-a`'s `Cross` as `Button96`.
  - `resetRestoresDefaults`: after that, `resetPadBindings("pad-b")` gives `pad-b`'s `Cross` `Button96` again.
  - `unmappedKeyReachesCore`: `onKey(id, 188, true)` returns true. `onKey(id, 110, true)` (Mode) and
    `onKey(id, 4, true)` (Back) return false.
  - In `SettingsSetupTest`, `foreignFileDropsDescriptors`: a foreign file with `ControllerDescriptors = other-pad`
    no longer has `other-pad` after `initialize`.
- [ ] **Step 3: Run them to see them fail** (RED).
- [ ] **Step 4: Implement**, as listed.
- [ ] **Step 5: Run them to see them pass**, with desktop still green. On `goose-arm64`, with two `uinput` pads (as in
  `docs/android.md`'s results), padtest still maps both, and `settings.ini` gains `[Android] ControllerDescriptors`
  listing them.

### Task 6: capturing the next input for a binding

**Files:**
- Modify: `src/util/android_input_keys.h/.cpp`, `android/app/src/cpp/android_host.cpp/.h`, `jni_bridge.cpp`,
  `…/core/NativeLibrary.kt`, `…/core/NativeCallbacks.kt`
- Test: `src/common-tests/android_input_keys_tests.cpp`; `android/app/src/androidTest/java/…/core/BindingCaptureTest.kt`

**Interfaces:**
- Consumes: the descriptor table (Task 5).
- Produces (C++, `AndroidInputKeys`, pure): `class BindingCapture`. It decides which input the user meant, from
  `InputManager`'s events, which come after `PadState`, so hat and trigger merging are already applied.
  - `explicit BindingCapture(u32 device_index)`.
  - `std::optional<InputBindingKey> OnEvent(InputBindingKey key, float value)`. It returns the key to bind, with
    `modifier = InputModifier::Negate` for a negative axis direction, and follows these rules:
    - Events from another source type or `source_index` give nothing.
    - **Buttons** are captured on a press (`value >= 0.5`). Releases give nothing. A button held as capture starts
      only sends its release, so it's never taken.
    - **Axes** are captured when `|value| >= 0.5`, but only once that axis has been seen within `0.25` of 0 during
      this capture. An axis resting at ±1, such as a trigger on `Z`, or a stick held as capture starts, must come
      back first (Review Focus 2).
- Android host:
  - `StartBindingCapture(std::string descriptor)` runs on the CPU thread. With the descriptor's index, it sets
    `InputManager::SetHook`. Each event goes to a `BindingCapture`:
    - When one returns a key, the host returns `RemoveHookAndStopProcessingEvent` and calls
      `NotifyBindingCaptured(InputManager::ConvertInputBindingKeyToString(HalfAxis or Button, key))`, with the
      `Android-<n>/` prefix removed.
    - Otherwise it returns `StopProcessingEvent`.
  - An unknown descriptor does nothing.
  - `CancelBindingCapture()` is `InputManager::RemoveHook()`, on the CPU thread.
- Produces (Kotlin): `external fun startBindingCapture(descriptor: String)` and `external fun
  cancelBindingCapture()`. `NativeCallbacks` gains `fun onBindingCaptured(binding: String) {}`, with a default body,
  so existing fakes still compile. Like the others, it's called on the core's thread.

- [ ] **Step 1: Write the failing native tests** (`BindingCapture`, keys built like the source's: `InputSourceType::Android`,
  `source_index` 0, and subtype `ControllerButton` or `ControllerAxis`):
  - `CapturesButtonPress`: Button 96 at 1.0 gives Button 96.
  - `IgnoresReleaseAndOtherDevice`: Button 96 at 0.0 gives nothing, and Button 99 at 1.0 on `source_index` 1 gives
    nothing.
  - `RestingAxisMustReturnFirst`: Axis 11 at −0.9, then −0.6, gives nothing. Then 0.1, then 0.7, gives Axis 11 with
    no modifier.
  - `NegativeDirection`: Axis 0 at 0.05, then −0.6, gives Axis 0 with `Negate`.
  - `HeldStickIsNotTaken`: Axis 1 first seen at 0.8 gives nothing until it's seen under 0.25.
- [ ] **Step 2: Write the failing instrumented test** (`BindingCaptureTest`):
  - connect `pad-a`, register callbacks with a latch, `startBindingCapture("pad-a")`, then `onKey(id, 99, true)`:
    `onBindingCaptured("Button99")` arrives within 2 s;
  - after `cancelBindingCapture()`, another key gives no callback within 500 ms;
  - `onAxis(id, 23, 0.0f)` then `onAxis(id, 23, 1.0f)` (Brake) gives a binding of the form `+Axis<n>` or
    `Button104`. Assert it's one of `+Axis17`, `+Axis23` or `Button104`: `PadState` reports all three for a trigger,
    and the first to cross wins.
- [ ] **Step 3: Run them to see them fail** (RED).
- [ ] **Step 4: Implement**, as listed.
- [ ] **Step 5: Run them to see them pass.** Desktop still green.

### Task 7: the Controllers page (remapping)

**Files:**
- Create: `…/input/BindingLabels.kt`, `…/ui/ControllersScreen.kt`
- Modify: `…/input/ControllerInput.kt`, `…/app/AppController.kt`, `…/MainActivity.kt`
- Test: `android/app/src/test/java/…/input/BindingLabelsTest.kt`; `android/app/src/androidTest/java/…/ui/ControllersScreenTest.kt`

**Interfaces:**
- Consumes: `getPadBindings`, `setPadBinding`, `resetPadBindings`, `PadBinding` (Task 5); `startBindingCapture`,
  `cancelBindingCapture`, `onBindingCaptured` (Task 6); `ChoiceRow`, `NavRow`, `SettingsPage`,
  `SettingsEntry.Controllers` (Task 3).
- Produces:
  - `data class ConnectedController(val deviceId: Int, val name: String, val descriptor: String)`.
    - `ControllerInput.connected: StateFlow<List<ConnectedController>>` holds one entry per descriptor (the first
      device ID), in connection order. It's updated from the device listener.
  - `object BindingLabels { fun label(binding: String?): String }`, pure, with no Android calls, so it runs on the
    JVM. It names a binding as follows:
    - `null` or empty → `Not set`
    - `Button96`–`100` → `A`, `B`, `C`, `X`, `Y`
    - `Button101` → `Z`
    - `Button102`–`105` → `L1`, `R1`, `L2`, `R2`
    - `Button106` → `L3`, and `Button107` → `R3`
    - `Button108` → `Start`, `Button109` → `Select`, and `Button110` → `Mode`
    - `Button19`–`22` → `D-pad up`, `D-pad down`, `D-pad left`, `D-pad right`
    - `Button188`–`203` → `Button 1` to `Button 16`
    - any other `Button<n>` → `Key <n>`
    - `+Axis0`/`-Axis0` → `Left stick right`/`Left stick left`, and `+Axis1`/`-Axis1` → `Left stick down`/`Left stick
      up`
    - Axes 11 and 14 the same way, for the right stick
    - `+Axis17` → `Left trigger`, `+Axis18` → `Right trigger`, `+Axis23` → `Brake (left trigger)` and `+Axis22` →
      `Gas (right trigger)`
    - `+Axis15`/`-Axis15`/`+Axis16`/`-Axis16` → the D-pad names
    - any other axis → `Axis <n> +` or `Axis <n> −`
    - a chord (`a & b`) is labelled part by part, joined with ` + `
  - `ControllersScreen(controllers: List<ConnectedController>, selected: String?, bindings: List<PadBinding>,
    capturing: String?, secondsLeft: Int, onSelect: (String) -> Unit, onCapture: (String) -> Unit, onCancelCapture:
    () -> Unit, onClear: (String) -> Unit, onReset: () -> Unit, onBack: () -> Unit)`:
    - First, a card with the menu triggers: "Back, the Mode (Home or Guide) button, or holding Select + Start for a
      second pauses the game and opens the menu. These buttons always do this, whatever is mapped to them."
    - Then:
      - with more than one controller, a `ChoiceRow("Controller")` of their names;
      - with one, its name as a line;
      - with none, "Connect a controller to remap it." and nothing below.
    - "Every connected controller drives Port 1. Each one keeps its own mapping."
    - One row per binding: `displayName` on the left, and `BindingLabels.label(binding)` on the right.
      - A starts capture (`onCapture(name)`).
      - **Clear** sits at the row's end, reached with Right.
      - While `capturing == name`, the row reads "Press a button or move a stick on <controller name>… (secondsLeft)".
    - **Reset to defaults** at the end.
    - On opening, the first binding row is focused, or the Controller row when there's more than one controller.
  - `AppController`:
    - `controllersState` holds the controllers, the selected descriptor (the last one picked, else the first), its
      bindings, `capturing` and `secondsLeft`.
    - `fun captureBinding(name)` and `fun cancelCapture()`. A capture lasts 5 s, counting down once a second.
      Timing out cancels it.
    - `onBindingCaptured(binding)` posts to the main thread, then calls `setPadBinding(selected, capturing,
      binding)`, reloads the bindings, ends the capture, and calls `controllers.releaseAll()`.
    - `val capturingInput: Boolean` is true while a capture runs.
  - `MainActivity.dispatchKeyEvent` and `dispatchGenericMotionEvent` send controller events to `ControllerInput`
    when `inGame || AppController.capturingInput`. Task 8 adds `&& !promptOpen` to the `inGame` term.
  - During a capture:
    - `KEYCODE_BACK`, which isn't a pad key, reaches Compose. The page's `BackHandler(enabled = capturing != null)`
      cancels the capture.
    - B is captured like any other button.
    - The pad's Mode key is swallowed, as in a game.
  - When a capture ends, the rest of the captured press (its key-up) goes to Compose. `focusRing` only acts on an A
    pressed on the element, so it does nothing.

- [ ] **Step 1: Write the failing tests.**
  - `BindingLabelsTest`: the listed values, including `Button97` → `B`, `-Axis14` → `Right stick up`, `Button190` →
    `Button 3`, `Button250` → `Key 250`, `+Axis32` → `Axis 32 +`, `null` → `Not set`, and `Android-0/Button108 &
    Android-0/Button109`'s parts (after the prefix is removed: `Button108 & Button109` → `Start + Select`).
  - `ControllersScreenTest`, with one fake controller and three bindings:
    - the first binding row is focused on opening;
    - `Enter` calls `onCapture("Up")`;
    - with `capturing = "Up"` the row shows "Press a button" and the seconds;
    - `Back` then calls `onCancelCapture` and not `onBack`;
    - Right then `Enter` on a row calls `onClear`;
    - with no controllers, "Connect a controller" shows and there's no **Reset to defaults**;
    - the menu triggers card is shown.
- [ ] **Step 2: Run them to see them fail** (RED).
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run them to see them pass.** On `goose-arm64`, with two `uinput` pads as in `docs/android.md`'s
  results:
  - Settings → Controllers: the Controller row lists both.
  - Remap pad 2's Cross to its X button by injecting `BTN_WEST`, and Circle to its A button.
  - In padtest, pad 2's X lights Cross, and pad 1 is unchanged.
  - Force-stop the app, start it again, register the pads in the opposite order: pad 2 still has its remap, and
    `[Pad1]` has no default `Button96` added back for pad 2's index.
  - A capture left alone ends after 5 s, and the row shows the old binding.
- [ ] **Step 5: Checkpoint.** Propose the commits for Tasks 5–7 in chat, and commit after approval.

### Task 8: the Back prompt and its triggers

**Files:**
- Create: `…/input/MenuTrigger.kt`, `…/app/PausePolicy.kt`, `…/ui/GamePrompt.kt`
- Modify: `…/input/ControllerInput.kt`, `…/ui/GameScreen.kt`, `…/app/AppController.kt`, `…/MainActivity.kt`
- Test: `android/app/src/test/java/…/input/MenuTriggerTest.kt`, `…/app/PausePolicyTest.kt`;
  `android/app/src/androidTest/java/…/ui/GamePromptTest.kt`

**Interfaces:**
- Produces:
  - `class MenuTrigger(private val holdMs: Long = 1000)`, pure, keyed by device ID, with the Android keycodes as
    constants (`KEYCODE_BUTTON_MODE = 110`, `SELECT = 109`, `START = 108`), so it runs on the JVM:
    - `data class KeyPress(val keyCode: Int, val pressed: Boolean)`
    - `data class Result(val send: List<KeyPress>, val openMenu: Boolean)`
    - `fun onKey(deviceId: Int, keyCode: Int, pressed: Boolean, nowMs: Long): Result`
    - `fun deadline(): Long?`: when `onTime` must next be called, or null.
    - `fun onTime(nowMs: Long): Boolean`: true when the hold has completed. The menu opens, and the held-back press
      is dropped.
    - `fun reset()`

    Its rules:
    - **Mode:** going down gives `openMenu` and sends nothing. Going up sends nothing.
    - **Select or Start, with the other not held on that device:** sent as it is.
    - **The second of the pair going down while the first is held:** held back, and the deadline is `now + holdMs`.
      - If both are still held at the deadline, `onTime` gives true and the state is cleared.
      - If the held-back key goes up first: send `[it down, it up]`.
      - If the first key goes up first: send `[first up, held-back down]`. The held-back key is an ordinary pressed
        key from then on.
      - Either way, the deadline is cleared.
    - **Any other key** is sent as it is.
  - `ControllerInput` runs every pad key through `MenuTrigger` before `NativeLibrary.onKey`:
    - `var onMenuRequested: (() -> Unit)?`
    - one main-thread `Handler` runnable, posted for `deadline()` and removed when it's cleared;
    - `releaseAll()` also calls `menuTrigger.reset()` and removes the runnable.
  - `object PausePolicy { fun running(resumed: Boolean, promptOpen: Boolean): Boolean = resumed && !promptOpen }`.
    Every place that unpauses asks it: `MainActivity.onResume`, `onGameStarted` and `resumeFromPrompt`.
  - `AppController`:
    - `var promptOpen by mutableStateOf(false)` (private set);
    - `fun openPrompt()`: only on `Screen.Game` and when it's closed. It sets `promptOpen`, calls
      `controllers.releaseAll()` and then `NativeLibrary.setPaused(true)`, which also writes the memory cards;
    - `fun resumeFromPrompt()`: clears it, then `setPaused(false)` if `gameRunning && PausePolicy.running(resumed,
      false)`;
    - `fun quitFromPrompt()`: clears it, then `leaveGame()`;
    - `leaveGame()` and `onGameStopped()` clear it as well.
  - `@Composable fun GamePrompt(onResume: () -> Unit, onQuit: () -> Unit)` is drawn in `GameScreen`'s own window,
    over the `SurfaceView`, inside a `Box`:
    - a scrim (`Color.Black.copy(alpha = 0.6f)`) and a centred card reading "Paused";
    - **Resume** (focused when it opens), then **Quit to library**;
    - B or Back resume (`BackHandler`), and so does `Key.ButtonMode`, through `onKeyEvent` on the card.
  - `GameScreen(promptOpen: Boolean, onOpenPrompt: () -> Unit, onResume: () -> Unit, onQuit: () -> Unit)`. With the
    prompt closed, its `BackHandler` calls `onOpenPrompt` and no longer leaves the game. `ControllerInput.onMenuRequested
    = AppController::openPrompt`.
  - `MainActivity` sends controller events to `ControllerInput` only while the prompt is closed (Task 7's
    condition). While it's open they move Compose's focus, as on any other screen.

- [ ] **Step 1: Write the failing JVM tests.**
  - `MenuTriggerTest` (Review Focus 3):
    - `modeOpensMenu`: Mode down gives `openMenu` with nothing sent. Mode up gives nothing.
    - `selectAloneIsForwarded`: down then up are sent as they are.
    - `holdingBothOpens`: Select down at 0 is sent. Start down at 100 is held back, and `deadline() == 1100`.
      `onTime(1099)` is false, and `onTime(1100)` is true. No `Start` down was ever sent.
    - `chordLetGoEarly`: Select 0, Start 100, Start up at 400 sends `[Start down, Start up]`. The deadline is null.
    - `firstLetGoEarly`: Select 0, Start 100, Select up at 300 sends `[Select up, Start down]`. Then Start up sends
      `[Start up]`.
    - `pairIsPerDevice`: Select on device 1 and Start on device 2 are both sent, and there's no deadline.
    - `otherKeysPass`: `BUTTON_A` down is sent as it is.
  - `PausePolicyTest`: `running(true, false)` is true; `running(true, true)` and `running(false, false)` are false.
- [ ] **Step 2: Write the failing UI test** (`GamePromptTest`):
  - Resume is focused when it opens;
  - `Back` calls `onResume`;
  - `DirectionDown` then `Enter` calls `onQuit`;
  - a `ButtonMode` key calls `onResume`.
- [ ] **Step 3: Run them to see them fail** (RED).
- [ ] **Step 4: Implement**, as listed.
- [ ] **Step 5: Run them to see them pass.** On `goose-arm64`, padtest with a `uinput` pad:
  - [ ] The Back gesture (`adb shell input keyevent KEYCODE_BACK`) opens the prompt, and the game pauses (its frame
    counter stops). **Resume** carries on, and **Quit to library** goes back with `Game stopped.`.
  - [ ] Injecting `BTN_MODE` opens it. padtest never shows a button for it.
  - [ ] Holding `BTN_SELECT` then `BTN_START` for over 1 s opens it. padtest shows Select pressed, never Start.
    Tapping Start while Select is held, under 1 s, shows Start pressed briefly in padtest, and nothing stays pressed.
  - [ ] With the D-pad held, open the prompt and resume: nothing is pressed in padtest.
  - [ ] With the prompt open, Home then reopen: the prompt is still there, and the game is still paused.
  - [ ] Back during a boot (right after launching Ace Combat 3): the prompt shows. Once the game boots it stays
    paused, and **Resume** starts it.
  - [ ] `adb logcat -b crash -d` is empty.

### Task 9: controller focus everywhere, and the window-dialog check

**Files:**
- Modify: `android/app/build.gradle.kts`, adding a `checkNoWindowDialogs` task that `preBuild` depends on.
- Modify: `…/ui/LibraryScreen.kt` (`GameFocus`).
- Create: `android/app/src/androidTest/java/…/ui/FocusAudit.kt`, `…/ui/FocusAuditTest.kt`
- Modify: `LibraryScreenTest`

**Interfaces:**
- Produces:
  - `checkNoWindowDialogs` scans `src/main/java/**/*.kt` for the forbidden calls and imports, and fails with
    `file:line: <name> opens its own window, which can swallow controller keys. Draw it in the screen instead.` The
    names, matched as words (`\bName\b`), are:
    - `AlertDialog`, `BasicAlertDialog`, `DatePickerDialog`, `TimePickerDialog`, `DropdownMenu`,
      `ExposedDropdownMenuBox`, `ModalBottomSheet`;
    - the imports `androidx.compose.ui.window.Dialog` and `androidx.compose.ui.window.Popup`;
    - `android.app.Dialog`.

    It's an ordinary Gradle task with declared inputs, so it's cached.
  - `fun ComposeContentTestRule.assertControllerReady()` (`FocusAudit.kt`), which asserts:
    - some node is focused once the screen settles;
    - every node with `SemanticsActions.OnClick` that isn't disabled also has `SemanticsActions.RequestFocus`, so
      it can be reached with a D-pad.

    On failure it names the node's text.
  - `GameFocus.pending` is cleared as soon as anything else in the screen takes focus. A game that was scrolled away
    from before it composed no longer grabs focus later.

- [ ] **Step 1: Write the failing tests.**
  - `FocusAuditTest`: one test per screen, each composed with fake data and then `assertControllerReady()`. The
    screens are `SetupScreen` (each of its four steps), `LibraryScreen` (grid, list, empty), `SettingsScreen`,
    `FoldersScreen`, `GraphicsScreen`, `BiosScreen`, `ControllersScreen` (no controllers, and two) and
    `GamePrompt`.
  - `LibraryScreenTest.stalePendingFocusIsDropped`: 30 entries, `focusPath` the last. Before the list has scrolled
    it in, the test focuses **Settings** (`requestFocus` through its semantics), then `performScrollToIndex(29)` on
    the grid. **Settings** still has focus.
- [ ] **Step 2: Run them to see them fail.** The audit fails only where a screen has a gap; fix any it finds as part
  of this task. `stalePendingFocusIsDropped` is RED.
- [ ] **Step 3: Implement** the Gradle task and the `GameFocus` fix.
- [ ] **Step 4: Run them to see them pass.**
  - Check the check: add a scratch file using `DropdownMenu` to `src/main`, and `./gradlew :app:checkNoWindowDialogs`
    fails naming it. Delete the file, and it passes.
  - On `goose-arm64`, with a `uinput` pad only, go Library → Settings → each page → back, to Setup and back. B goes
    back everywhere (the framework turns `BUTTON_B` into Back outside a game), and the hub entry or **Settings**
    takes the focus back each time.

### Task 10: the cold start settles before a game can start (from Plan 2)

Plan 2 left this open. When `settings.ini` moves the game settings folder from its default, a cold start pulls in
rounds:
1. the first pull, with the default layout;
2. a catch-up pull for the folders the core's settings name;
3. another for the cards the game settings it just pulled name.

Each run that lists both sides first removes the working copy's strays, which are files outside the layout as the
core last said it. So a per-game memory card is removed in round 2, before its game settings are local, and comes
back in round 3. Those catch-up pulls run in the background with `ready` already true, so a game booted between them
gets a blank card.

**Files:**
- Create: `…/app/CatchUp.kt`
- Modify: `…/mirror/Mirror.kt`, `…/app/AppController.kt`
- Test: `android/app/src/test/java/…/app/CatchUpTest.kt`; extend `android/app/src/androidTest/java/…/mirror/MirrorTest.kt`

**Interfaces:**
- Produces:
  - `object CatchUp`:
    - `const val MAX_PULLS = 4`
    - `suspend fun run(pull: suspend () -> Boolean): Int` pulls while the last pull grew the layout, at most
      `MAX_PULLS` times, and returns how many it ran. It logs `The data layout kept growing; stopping after 4 pulls.`
      when it hits the cap.
  - `Mirror(…, removeStrays: () -> Boolean = { true })`: strays are removed only when `layout.fromCore &&
    removeStrays()`.
  - `AppController`:
    - `@Volatile private var catchingUp`. `removeStrays = { !catchingUp }`.
    - After `initializeCore`, the cold start runs `CatchUp.run { pullGrew() }` with `catchingUp = true`, then sets
      `catchingUp = false`. `pullGrew()` is `pull(onlyMemcards = false)`, returning whether `readCorePaths` grew the
      layout instead of launching a pull of its own.
    - `ready` becomes true only after that. The library shows "Copying your data from the data folder…" until then.
    - The same catch-up replaces `applyChanged`'s `scope.launch { pull(onlyMemcards = false) }` for later growth (a
      resume that brings new game settings). While it runs, `startGame` is refused with "Copying your data from the
      data folder…".

- [ ] **Step 1: Write the failing tests.**
  - `CatchUpTest`:
    - a fake that grows twice and then doesn't gives 3 pulls;
    - one that never grows stops at 4;
    - one that doesn't grow at all gives 1.
  - `MirrorTest.straysKeptWhileRemovalIsOff`:
    - set-up: a layout `fromCore` without `cards/`, and the working copy's `cards/ac3.mcd` in step with the folder
      (manifest and both sides match);
    - a pull with `removeStrays = { false }` leaves the local file;
    - a pull with `{ true }` deletes it locally, and the folder keeps it.
- [ ] **Step 2: Run them to see them fail** (RED).
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run them to see them pass.** Then on `goose-arm64`:
  - Set-up:
    - the data folder's `settings.ini` has `[Folders] GameSettings = mygs`;
    - `mygs/<padtest's serial or title>.ini` sets `[MemoryCards] Card1Type = Shared` and `Card1Path =
      cards/padtest.mcd`;
    - `cards/padtest.mcd` is in the folder, synced once.
  - Delete the local `mygs` (`run-as … rm -r files/data/mygs`), force-stop, and start the app.
  - Expected:
    - the log shows every catch-up pull before a game can be started;
    - `files/data/cards/padtest.mcd` is never deleted (its inode, from `run-as … ls -i`, is unchanged);
    - starting padtest straight away is refused, with the "Copying your data…" message, until the pulls finish.
- [ ] **Step 5: Checkpoint.** Propose the commits for Tasks 8–10 in chat, and commit after approval.

### Task 11: docs, the roadmap wording, and the checks on the emulator and the Thor

**Files:**
- Modify: `docs/android.md`:
  - **"Changing settings"** describes the settings pages first. Editing `settings.ini` stays, as the way to reach
    every other key. Remove "There's no fallback from Vulkan to OpenGL ES yet (Plan 3)". Describe the fallback, and
    `debug.goosestation.fail_vulkan`.
  - **New "Controllers" section:**
    - automatic mapping the first time a pad is seen;
    - `[Android] ControllerDescriptors`;
    - the remap page;
    - the menu triggers, which can't be remapped;
    - two identical pads that Android gives one descriptor share a mapping.
  - **New "Leaving a game" section:** the prompt, its three triggers, and Select+Start's held-back press.
  - **Known limits:**
    - remove "In a game, a controller alone can't get back to the library";
    - add that the Vulkan fallback lasts until the app restarts, and each new process tries Vulkan once;
    - add that settings pages are reachable only from the library;
    - note that the SD-eject behaviour was rechecked with the user on 2026-10-27 and kept.
  - **The results**, from the steps below.
- Modify: `docs/roadmap.md`. This is the spec's wording fix (*Constraints*: "Correct both lines as part of Stage 1"):
  - **Item 14:** replace "Android answers the same hook with its system keyboard (item 16)." with "Android's own
    screens are native, so they use Android's keyboard directly (item 16)."
  - **Item 16:** replace "Text entry uses the system keyboard, through item 14's hook." with "Its screens are native
    Android ones, so text entry uses Android's keyboard." Its *After* column goes from `12, 14` to `12`. That's a
    writer's call, for the same reason; leave it if the review disagrees. Then set the status: "Plan 3 (settings,
    remapping and the Back prompt) is done (#<PR>), which completes Stage 1." Write "is in progress" until the merge.
  - **Suggested order:** replace "Then 12 and 14 lead to Android (16); 14 comes before 12, and gives Android's Big
    Picture screens the system keyboard." with "Then 14 and 12 (in that order: 14 gives Big Picture the platforms'
    keyboards, which 12's settings use)."

- [ ] **Step 1: Emulator checks** (`goose-arm64`, `uinput` pads, folders as in Plan 2), each recorded in `docs/android.md`:
  - [ ] Task 3's Graphics round trip, Task 4's BIOS override and its reset after a BIOS change, and Task 7's remap
    surviving a restart in swapped order.
  - [ ] Task 2's Vulkan fallback, with both `Vulkan` and `Automatic`.
  - [ ] Task 8's prompt checks, all of them.
  - [ ] Task 10's catch-up check.
  - [ ] A desktop `settings.ini` with `AspectRatio = Stretch To Fill`, pushed into the data folder: the Graphics page
    shows "Stretch to the screen", and padtest fills the screen.
  - [ ] `adb logcat -b crash -d` is empty.
- [ ] **Step 2: The Thor** (only with the user's go-ahead). Add **step 8, "Settings and controls"**, to "Checks on
  the AYN Thor", then run it:
  - [ ] Everything with the built-in controller alone: Settings, each page, every inline list, the remap page, and
    back to the library.
  - [ ] Remap a button on the built-in pad, then restart the app: it holds. Reset to defaults restores it.
  - [ ] A Bluetooth pad gets its own mapping. Remapping it leaves the built-in pad's alone.
  - [ ] In Ace Combat 3, the pad's Back key, the Mode key, and holding Select+Start each open the prompt, and the
    game doesn't react to the press: no in-game pause menu after **Resume**. **Quit to library** saves the memory
    card (`Saving memory card to …`), and it reaches the data folder.
  - [ ] Graphics: OpenGL ES, then Vulkan, 4x and Show FPS: the OSD's `G:`/`V:` show, and the speed stays at 100%.
  - [ ] Fast boot off: the BIOS intro plays. On: it doesn't.
  - [ ] `adb logcat -b crash -d` is empty.
- [ ] **Step 3: Everything green:** desktop, the JVM, instrumented and native suites, `./gradlew assembleDebug`, and
  `checkNoWindowDialogs`.
- [ ] **Step 4: Checkpoint.** Propose the commits for Task 11 in chat, commit after approval, and then open the PR.
