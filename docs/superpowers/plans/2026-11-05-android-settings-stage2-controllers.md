# Android settings, stage 2: controllers — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each connected controller gets a port (Port 1 by default, multitap ports when multitap is on anywhere). Each
port gets its controller type, the type's settings and macros. Multitap, the hotkeys the core keeps on Android, and
rumble with a strength for each controller come with them. All use desktop's keys and values, so `settings.ini` stays
one desktop reads.

**Architecture:**
- **The host** (`android/app/src/cpp/android_host.cpp`) keeps each controller's port and rumble strength in
  `[Android] ControllerPorts` / `ControllerRumble`, beside `ControllerDescriptors`. Every place that today means
  "Port 1" (`GetPort1ControllerInfo`, `[Pad1]`) becomes "this controller's port". Moving a controller moves its
  `Android-<n>/…` entries from `[PadOld]` to `[PadNew]`. Picking a type adds that type's defaults where a controller
  has none.
- **Rumble** goes through no settings key. On Android only, the core binds each vibrating port's motors to the Android
  source by port (`input_manager.cpp`, `#ifdef __ANDROID__`). The source's `RumbleRouter` (in `android_input_keys`,
  tested on desktop) sends each port's motor levels, times the controller's strength, to the port's last-used
  controller. The app drives that controller's own `InputDevice` vibrators.
- **Hotkeys** are the core's list (`InputManager::GetHotkeyList()`), minus the ones the app handles itself. The six
  save-state hotkeys move to host hotkeys (behind `#ifndef __ANDROID__` in `hotkeys.cpp`) that call the app's own
  save path.
- **Pages:** the Controllers hub (controllers with their port and rumble, Multitap, one row per port, Hotkeys). It
  opens a **Port** page (type, the type's settings, a controller's bindings, macros), a **Macro** page and a
  **Hotkeys** page. They are built from stage 1's rows (`CyclerRow`, `NumberRow`, `SwitchRow`, `NavRow`,
  `SettingsSection`) and its model (`Setting`, `SettingValues`).

**Tech Stack:** C++20, Android NDK, JNI, googletest (`common-tests`), Kotlin, coroutines, Jetpack Compose, JUnit 4,
AndroidX Test, Compose UI tests.

**Spec:** [docs/superpowers/specs/2026-11-05-android-settings-design.md](../specs/2026-11-05-android-settings-design.md),
"Controllers" and stage 2. Stage 1's patterns:
[2026-11-05-android-settings-stage1.md](2026-11-05-android-settings-stage1.md).

**Where to build:** this worktree, on `master` at `972808c96`. The branch is renamed
`claude/android-settings-controllers` and pushed there. Env: `source /tmp/gs3-env.sh`. Desktop:
`cmake -B build -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo -DBUILD_TESTS=ON
-DCMAKE_PREFIX_PATH=$HOME/work/goosestation-deps`.

## Global Constraints

- `docs/design-system.md` and its tokens are binding: controller-first, focus always visible, no window dialogs.
  Every new page goes into `FocusAuditTest` and `OrientationLayoutTest`, with a key-driven screen test.
- Keys and values are desktop's: `[PadN] Type`, the type's `SettingInfo` keys, `[ControllerPorts] MultitapMode`,
  `[PadN] MacroK`/`MacroKBinds` (`" & "`-joined input names, the key deleted when empty)/`MacroKFrequency`/
  `MacroKToggle`, and `[Hotkeys] <name>`. Names, display names, settings, ranges and formats come from the core over
  JNI (`Controller::GetControllerInfo`, `Settings::GetMultitapModeName`, `InputManager::GetHotkeyList`), never a list
  copied into Kotlin.
- Types offered: every core type except `GunCon`, `Justifier` and `PlayStationMouse`. A file's other type shows as
  itself, "(from settings.ini)", and is kept until another is picked.
- The port is global, never per game. Only `Android-<n>/…` entries ever move or change: desktop's `SDL-*` and keyboard
  bindings in the same keys stay where they are, in order.
- No motor binding key is written. Rumble only ever uses a controller's own `InputDevice` vibrators: never
  `Context`'s vibrator, and never a device with `isExternal == false` (a handheld's built-in controls drive the
  handheld's own motor) *(builder's call, from the design's "never the device's own motor")*.
- Hotkeys left out: `TogglePause` (not on Android already), `ChangeDisc`, `Reset`, and the per-slot
  `Load/SaveGameState<N>` and `Load/SaveGlobalState<N>` (the app has no global slots; per-slot hotkeys would be 20
  rows) *(builder's call)*. Hotkeys keep being Select chords, one per controller.
- Settings change only from the library (`settingsReady`), so every change takes effect at the next boot, as stage 1.
- Android-only core code goes behind `__ANDROID__`; desktop behaviour is unchanged. Clean room: nothing from
  DuckStation after `a5bf1ba5cee7`, nor its closed Android app.
- `gh` as ilyalissoboi (`GH_TOKEN=$(gh auth token --user ilyalissoboi)`), never the GitHub MCP. Emulators only; the
  AYN Thor needs the user's go-ahead. Commit each task when it's done and show its message after. Commit messages end
  with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Found in the code, and how this plan deals with it

- **The design's claims hold:** every controller's defaults go to `[Pad1]` (`AddDefaultBindings`, `MapNewController`,
  `HasDeviceBinding`, `ResetPadBindings` all use `GetSettingsSection(0)`). `EnumerateMotors()` and
  `UpdateMotorState()` are empty. The manifest has no `VIBRATE`. `HOTKEYS` is three names.
- **Motor keys are one string per port** (`input_manager.cpp:906-934`, `GetStringValue`). An Android
  `LargeMotor = Android-0/LargeMotor` would replace desktop's `SDL-0/LargeMotor` in a shared file. So on Android the
  core ignores the motor keys and binds each vibrating port's motors to the Android source (Task 1). Desktop's keys
  stay as they are.
- **`ParseGenericControllerKey` knows no motors**: the port motor keys are built in code, never parsed from settings.
- **Core multitap bugs, desktop too, not fixed here:** `Settings::IsPort2MultitapEnabled` tests `Port1Only ||
  BothPorts` (`settings.h:320`), and `Settings::Load` passes the slot to `PadIsMultitapSlot` (`settings.cpp:375`).
  Under `Port2Only`, 2C and 2D are forced to None. The start message (Task 7) reads the core's own result
  (`g_settings.controller_types`), so it tells the truth either way. The fix is offered as its own follow-up, as #46
  was.
- **The core's save-state hotkeys bypass the app**: they use `SaveStateSelectorUI` and `System::GetGameSerial()`, not
  the tile's key, `LastSlots` or the app's messages. Task 4 moves them behind `#ifndef __ANDROID__` and defines host
  hotkeys of the same names.
- **`UndoLoadState` already works**: `AndroidHost::LoadState` passes `save_undo_state = true` (`android_host.cpp:1088`).
- **The core re-sends motor levels every 0.5 s** while they're above 0 (`VIBRATION_UPDATE_INTERVAL_SECONDS`), and sends
  0 on pause (`PauseVibration`). So the app plays one-shots of 1 s and cancels on 0.
- **`IntegerList` settings store `min + index`** (Qt's `BindWidgetToIntSetting(…, IntegerMinValue())`). Floats show
  through the setting's printf `format` with its `multiplier` (`"%.0f%%"` × 100 → "25%").
- **Today's hotkey labels are the app's** ("Fast forward"). They become the core's display names ("Toggle Fast
  Forward") under the core's categories, as desktop shows them.
- **Bindings for types used only in games' files** (the design's "any type in use") need per-game types, which stage 3
  writes. Here the Port page shows the port's own type. Stage 3 adds the others.

## Review Focus

1. **A controller left on a port nothing uses** (multitap turned off everywhere after it was put on 1C): its Port row
   shows "Port 1C" as itself, noted "No game uses this port". It drives nothing, the game's start says so, and moving
   it back to Port 1 brings back its bindings. Pinned in Task 2 and Task 7.
2. **Two controllers on one port, the last-used one disconnects while rumbling**: the next level goes to the other
   one, never to the gone device. A game's end stops every motor. Pinned in Task 1 (`RumbleRouter`) and Task 6
   (`Rumble`).
3. **A desktop `settings.ini`** with `[Pad2] Type = GunCon` and `SDL-0/…` bindings: the Port page shows "GunCon (from
   settings.ini)", and moving an Android controller to Port 2 leaves the SDL entries first and untouched. Pinned in
   Task 2 and Task 3.
4. **Picking a type adds defaults only where there are none**: a remapped Cross stays remapped across Analog → Digital
   → Analog. A NeGcon's Steering gets its default the first time. Pinned in Task 3.
5. **A save-state hotkey in a game with no key, or Load in hardcore**: nothing is written or loaded, and a message
   says why. Pinned in Task 4.

---

## File Structure

- `src/util/android_input_keys.{h,cpp}`: `RumbleRouter`, `MakePortMotorKey`, `PadForIndex`/`SetPadForIndex` (the
  `ControllerPorts` list). `src/util/android_input_source.{h,cpp}`: last input per device, the router, the motor
  callback. `src/util/input_manager.cpp`: the `#ifdef __ANDROID__` motor binding.
  `src/core/hotkeys.cpp`: `#ifndef __ANDROID__` around the six save-state hotkeys.
  `src/common-tests/android_input_keys_tests.cpp`.
- `android/app/src/cpp/android_host.{h,cpp}`: ports, types, defaults per type, macros, hotkey list, host save-state
  hotkeys, rumble strengths, the start message, the test hooks. `jni_bridge.cpp`: their JNI.
- `core/NativeLibrary.kt`, `core/NativeCallbacks.kt`, `core/PadBinding.kt` (+`category`), `core/TypeSetting.kt`
  (new).
- `settings/NameList.kt` (+`ControllerType`, `MultitapMode`), `settings/PortSettings.kt` (new: a type's `Setting`s
  and rows), `settings/MacroSettings.kt` (new).
- `app/CoreApi.kt`, `app/GameCoreApi.kt` (+`undoLoadState`), `app/ControllersPage.kt` (reworked),
  `app/InGameMenu.kt` (hotkeys), `app/AppController.kt`, `MainActivity.kt` (routes).
- `input/ControllerInput.kt` (+motors), `input/Rumble.kt` (new), `AndroidManifest.xml` (`VIBRATE`).
- `ui/ControllersScreen.kt` (the hub, rewritten), `ui/PortScreen.kt`, `ui/MacroScreen.kt`, `ui/HotkeysScreen.kt`
  (new), `ui/BindingRow.kt` (moved out of `ControllersScreen.kt`).
- Tests: `androidTest/…/core/ControllerBindingsTest.kt`, `GameCallsTest.kt`; `androidTest/…/ui/ControllersScreenTest.kt`,
  `PortScreenTest.kt`, `MacroScreenTest.kt`, `HotkeysScreenTest.kt` (new), `FocusAuditTest.kt`,
  `OrientationLayoutTest.kt`; `test/…/app/ControllersPageTest.kt`, `InGameMenuTest.kt`, `FakeCoreApi.kt`,
  `FakeGameCore.kt`; `test/…/settings/PortSettingsTest.kt`, `MacroSettingsTest.kt`; `test/…/input/RumbleTest.kt`.
- Docs: `docs/android.md` "Controllers" (Task 12).

Kotlin paths are under `android/app/src/main/java/io/github/ilyalissoboi/goosestation/` (tests under
`android/app/src/test/java/…` and `android/app/src/androidTest/java/…`).

## Running the tests

- Native, desktop: `cmake --build build --target common-tests && build/bin/common-tests
  --gtest_filter='AndroidInputKeys*'`. On the emulator: `android/scripts/run-native-tests.sh`. Then build
  `duckstation-qt` to check that desktop still compiles.
- JVM: `cd android && ./gradlew -q testDebugUnitTest --tests '<Class>'`, then `./gradlew -q ktlintCheck`.
- One instrumented class: `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.<pkg>.<Class>`.
- Each task that touches the UI or the bridge ends with `android/scripts/run-instrumented-tests.sh quick`, read from
  the result XML under `app/build/outputs/androidTest-results/`, not Gradle's verdict.

---
### Task 1: The core: ports in `[Android]`, the rumble router, Android's port motors

**Files:**
- Modify: `src/util/android_input_keys.{h,cpp}`, `src/util/android_input_source.{h,cpp}`,
  `src/util/input_manager.cpp:906-934` (inside `#ifdef __ANDROID__`)
- Test: `src/common-tests/android_input_keys_tests.cpp`

**Interfaces:**
- Produces (`AndroidInputKeys`):
  - `inline constexpr const char* PORTS_KEY = "ControllerPorts";` and `RUMBLE_KEY = "ControllerRumble";`: lists
    parallel to `ControllerDescriptors`. Entry `n` is `Android-<n>`'s pad number (`1`–`8`, as `[PadN]`) or its
    strength in percent (`0`–`100`). A missing or bad entry means pad 0 (Port 1) or 100%.
  - `u32 PadForIndex(std::span<const std::string> ports, u32 index)` (0-based pad) and
    `std::vector<std::string> SetPadForIndex(std::span<const std::string> ports, u32 index, u32 pad)`. The second pads
    missing entries with `"1"`. `u32 RumbleForIndex(...)` / `SetRumbleForIndex(...)` work the same way, clamped to
    0–100.
  - `InputBindingKey MakePortMotorKey(u32 pad, u32 motor)`: `PAD_SOURCE_TYPE`, `source_index = pad`, `source_subtype
    = InputSubclass::ControllerMotor`, `data = motor` (0 large or single, 1 small).
  - `class RumbleRouter` with `struct Command { s32 device_id; float large; float small; }` and these methods:
    `void OnConnected(s32 device_id, u32 index)`, `void OnDisconnected(s32 device_id)`, `void OnInput(s32 device_id)`
    (the controller was just used), `void SetPads(std::vector<u32> pad_of_index)`,
    `void SetStrengths(std::vector<float> strength_of_index)` (0–1, missing = 1), and
    `std::vector<Command> SetMotor(u32 pad, u32 motor, float value)`. `SetMotor` records the pad's level, then returns
    the commands: the pad's levels, times the target's strength, to the target (the connected device on that pad with
    the latest `OnInput`, or the first connected there if none has input), and `{old, 0, 0}` to the previous target
    when it changed and is still connected. It returns nothing when no device is on the pad.
- Produces (`AndroidInputSource`): `void SetRumbleCallback(std::function<void(s32 device_id, float large, float
  small)>)`, `void SetControllerPads(std::vector<u32>)`, `void SetRumbleStrengths(std::vector<float>)`. Each key or
  axis event calls `OnInput`. `UpdateMotorState` (both overloads) reads the pad and motor from the key and calls the
  callback with the router's commands. `EnumerateMotors()` stays empty.
- Produces (core, Android only): `AddPadBindings` binds a port with `LargeSmallMotors` to `MakePortMotorKey(pad, 0)`
  and `(pad, 1)`, and one with `SingleMotor` to `(pad, 0)`, on the Android source, whatever its motor keys say.

- [ ] **Step 1: Write the failing tests** (`AndroidInputKeys.*`):

```cpp
TEST(AndroidInputKeys, PortsListDefaultsToPort1) {
  EXPECT_EQ(PadForIndex({}, 3), 0u);
  EXPECT_EQ(PadForIndex(std::vector<std::string>{"2", "x"}, 1), 0u);
  EXPECT_EQ(SetPadForIndex({}, 2, 4), (std::vector<std::string>{"1", "1", "5"}));
}
TEST(AndroidInputKeys, RumbleGoesToTheLastUsedOnThePort) {
  RumbleRouter r; r.OnConnected(10, 0); r.OnConnected(11, 1); r.OnConnected(12, 2);
  r.SetPads({0, 0, 1}); r.OnInput(10); r.OnInput(11);
  EXPECT_EQ(r.SetMotor(0, 0, 1.0f), (std::vector<Command>{{11, 1.0f, 0.0f}}));
  r.OnInput(10);
  EXPECT_EQ(r.SetMotor(0, 1, 0.5f), (std::vector<Command>{{11, 0, 0}, {10, 1.0f, 0.5f}}));
}
TEST(AndroidInputKeys, RumbleSkipsAGoneDevice) {  // 11 last used, then gone: 10 takes it, 11 gets nothing
  ...; r.OnDisconnected(11);
  EXPECT_EQ(r.SetMotor(0, 0, 1.0f), (std::vector<Command>{{10, 1.0f, 0.0f}}));
}
TEST(AndroidInputKeys, RumbleIsScaledByStrength) {  // SetStrengths({0.5f}) → {10, 0.5f, 0.25f} for levels 1 and 0.5
}
TEST(AndroidInputKeys, NoRumbleWithNobodyOnThePort) { EXPECT_TRUE(RumbleRouter().SetMotor(1, 0, 1.0f).empty()); }
```

`Command` gets `operator==`.

- [ ] **Step 2: Run them and check they fail.** `cmake --build build --target common-tests` fails to compile with
  unknown names.
- [ ] **Step 3: Implement** the helpers and `RumbleRouter` in `android_input_keys.cpp`, then the source's use of the
  router and the `#ifdef __ANDROID__` block in `AddPadBindings`, which replaces the motor-key parsing there and adds
  `vib` with both motors' `source` set to the Android source.
- [ ] **Step 4: Run** `build/bin/common-tests --gtest_filter='AndroidInputKeys*'`: PASS. Then build `duckstation-qt`,
  run `android/scripts/run-native-tests.sh --gtest_filter='AndroidInputKeys*'`, and
  `cmake --build build-android --target goosestation`.
- [ ] **Step 5: Commit**

```
Core: Android rumble goes to each port's last-used controller

- AndroidInputKeys: the [Android] ControllerPorts and ControllerRumble lists, parallel to ControllerDescriptors
- RumbleRouter: a port's motor levels, times the controller's strength, to its last-used connected controller
- On Android, each vibrating port's motors are bound to the Android source by port; the [PadN] motor keys aren't
  read, so a settings.ini shared with desktop keeps desktop's own
- Desktop unchanged
```

---

### Task 2: The host: a port for each controller

**Files:**
- Modify: `android/app/src/cpp/android_host.{h,cpp}` (from `GetPort1ControllerInfo` to `ResetPadBindings`),
  `jni_bridge.cpp`, `core/NativeLibrary.kt`, `app/CoreApi.kt`, `test/…/app/FakeCoreApi.kt`
- Test: `androidTest/…/core/ControllerBindingsTest.kt`

**Interfaces:**
- Consumes: Task 1's `PadForIndex`/`SetPadForIndex`, `AndroidInputSource::SetControllerPads`.
- Produces (host, CPU thread, under the settings lock): `u32 GetPadOfIndex(si, index)`,
  `const ControllerInfo* GetPadControllerInfo(si, pad)` (replaces `GetPort1ControllerInfo`), and
  `AddDefaultBindings(si, index, pad)`, `HasDeviceBinding(si, index, pad)` on the controller's own pad. Every existing
  binding call (`GetPadBindings`, `SetPadBinding`, `ResetPadBindings`, `MapNewController`, `Port1HasAnalogButton` →
  `PadHasAnalogButton`) uses the controller's pad.
- Produces (Kotlin `NativeLibrary`, as `CoreApi` methods of the same names):
  - `getControllerPort(descriptor: String): Int` (0-based pad, −1 for an unknown controller) and
    `setControllerPort(descriptor: String, pad: Int)`. The setter moves every `Android-<n>/…` entry from the old
    `[PadN]` (every key, macro triggers included) to the same keys of the new one, adds the new type's defaults where
    the controller has none, saves, updates the source's pads and reloads the bindings.
  - `getOfferedPorts(): Array<String>`: pad number, label, pad number, label…, in pad order: `0`, `1`, then `2`–`4`
    when Port 1 has multitap in `settings.ini` or any `gamesettings/*.ini`'s `[ControllerPorts] MultitapMode`
    (`Port1Only` or `BothPorts`), then `5`–`7` likewise for Port 2 (`Port2Only` or `BothPorts`, read correctly, not
    through `IsPort2MultitapEnabled`). Labels come from `Controller::ConvertPadToPortAndSlot`: "Port 1", "Port 2", or
    "Port 1A"–"Port 1D" / "Port 2A"–"Port 2D" for a port with multitap.
  - `getPortType(pad: Int): String?`: `[PadN] Type`, or the core's default for the pad.
  - `getPadBindStateForTest(pad: Int, name: String): Float`: `System::GetController(pad)->GetBindState(…)`, 0 without
    one (debug builds only, as the other `ForTest` calls).
- `getPort1ControllerType()` goes. `ControllersPage` asks `getPortType(getControllerPort(d))` instead (Task 8).

- [ ] **Step 1: Write the failing tests** in `ControllerBindingsTest`:

```kotlin
@Test fun aNewControllerIsOnPort1() { connectNewPad(1, "pad-a"); assertEquals(0, NativeLibrary.getControllerPort("pad-a")) }
@Test fun movingAControllerMovesItsBindings() {
    connectNewPad(1, "pad-a"); connectNewPad(2, "pad-b")
    NativeLibrary.setControllerPort("pad-b", 1)
    assertEquals(listOf("Android-0/Button96"), readSettings().values("Pad1", "Cross"))
    assertEquals(listOf("Android-1/Button96"), readSettings().values("Pad2", "Cross"))
    assertEquals(listOf("1", "2"), readSettings().values("Android", "ControllerPorts"))
}
@Test fun aMoveKeepsOtherSourcesEntries() {  // settings.ini with [Pad2] Cross = SDL-0/FaceSouth, Type = GunCon
    NativeLibrary.setControllerPort("pad-a", 1)
    assertEquals("SDL-0/FaceSouth", readSettings().values("Pad2", "Cross").first())
}
@Test fun movingBackRestoresTheRemap() {  // remap Cross to Button97 on Port 1, move to 2 and back: still Button97
}
@Test fun thePortSurvivesARestart() {  // setControllerPort then initialize(existing settings): getControllerPort == 1
}
@Test fun multitapInAGamesFileOffersItsPorts() {  // gamesettings/SLUS-00001.ini: [ControllerPorts] MultitapMode = Port1Only
    assertEquals(listOf("Port 1A", "Port 2", "Port 1B", "Port 1C", "Port 1D"),
        NativeLibrary.getOfferedPorts().toList().chunked(2).map { it[1] })
}
@Test fun withoutMultitapOnlyPorts1And2() { assertEquals(listOf("0", "Port 1", "1", "Port 2"), NativeLibrary.getOfferedPorts().toList()) }
```

In `GameCallsTest`: `aControllerOnPort2DrivesPort2`. Under `withPadtest`, the pad goes on Port 2, `[Pad2] Type =
AnalogController` is set, and Cross is pressed. Then `getPadBindStateForTest(1, "Cross") == 1f` and
`getPadBindStateForTest(0, "Cross") == 0f`.

- [ ] **Step 2: Run** both classes and check they fail (`UnsatisfiedLinkError`).
- [ ] **Step 3: Implement.** `SetControllerPort` uses `GetKeyValueList` on the old section, `GetDeviceBinding` and
  `SetDeviceBinding` per key. `[Android] ControllerPorts` is written with `SetStringList`, as the descriptors are.
  The source gets the pads on creation (`ProcessInputQueue`) and after each change.
- [ ] **Step 4: Run** both classes again: PASS. Then `./gradlew -q testDebugUnitTest ktlintCheck` and the quick tier.
- [ ] **Step 5: Commit**

```
Android: each controller has a port, kept with its descriptor

- [Android] ControllerPorts beside ControllerDescriptors; a new controller is on Port 1
- setControllerPort moves the controller's own Android-<n> entries to the new [PadN] and adds that type's defaults
  where it has none; other sources' entries stay where they are
- getOfferedPorts: Ports 1 and 2, plus multitap's when settings.ini or any game's file turns it on
- Bindings, defaults, reset and the first mapping use the controller's port, not Port 1
```

---

### Task 3: The host: types, their settings, multitap

**Files:**
- Modify: `android_host.{h,cpp}`, `jni_bridge.cpp`, `core/NativeLibrary.kt`, `settings/NameList.kt`, `app/CoreApi.kt`,
  `FakeCoreApi.kt`
- Create: `core/TypeSetting.kt`, `settings/PortSettings.kt`
- Test: `ControllerBindingsTest.kt`, `SettingsAccessTest.kt`, `test/…/settings/PortSettingsTest.kt`

**Interfaces:**
- Produces:
  - `NameList.ControllerType("ControllerType")`, every core type but `GunCon`, `Justifier`, `PlayStationMouse`, in
    the core's order (`None` first, as "Not Connected"). `NameList.MultitapMode("MultitapMode")`.
  - `NativeLibrary.setPortType(pad: Int, type: String)`: writes `[PadN] Type`. For each controller on that pad, it
    adds the type's default bindings for each input the controller has no entry for, then saves and reloads.
  - `data class TypeSetting(val kind: Kind, val key: String, val label: String, val description: String,
    val default: String, val min: String, val max: String, val step: String, val format: String,
    val multiplier: Float, val options: List<String>)` with `enum class Kind { Bool, Int, IntList, Float, String }`.
    It is made by native code from `ControllerInfo::settings`, with null strings as `""`.
  - `NativeLibrary.getTypeSettings(type: String): Array<TypeSetting>` (empty for an unknown type).
  - `object PortSettings`: `fun settings(pad: Int, info: List<TypeSetting>): List<Setting<*>>` (`BoolSetting`,
    `IntSetting` for Int and IntList, `FloatSetting`, `StringSetting`, all in `Pad${pad + 1}`), and
    `fun scale(s: TypeSetting): NumberScale` for Int and Float. For Float, units are steps:
    `round(value / step)`, `Range(min/step, max/step, 1, presets = min, default, max)`, and the label is
    `String.format(Locale.ROOT, format, units * step * multiplier)`, or the plain value when `format` is empty.
    `fun choices(s: TypeSetting): List<Choice<Int>>` gives `min + i` to `options[i]`.
    `val MULTITAP = NameSetting("ControllerPorts", "MultitapMode", "Disabled", NameList.MultitapMode)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// ControllerBindingsTest
@Test fun pickingATypeAddsItsMissingDefaults() {
    connectNewPad(1, "pad-a"); NativeLibrary.setPadBinding("pad-a", "Cross", "Button97")
    NativeLibrary.setPortType(0, "NeGcon")
    assertEquals(listOf("Android-0/Button97"), readSettings().values("Pad1", "Cross"))  // kept
    assertTrue(readSettings().values("Pad1", "Steering").single().startsWith("Android-0/"))
    NativeLibrary.setPortType(0, "DigitalController"); NativeLibrary.setPortType(0, "AnalogController")
    assertEquals(listOf("Android-0/Button97"), readSettings().values("Pad1", "Cross"))
}
@Test fun typeSettingsAreTheCores() {
    val s = NativeLibrary.getTypeSettings("AnalogController").associateBy { it.key }
    assertEquals(TypeSetting.Kind.Float, s.getValue("AnalogDeadzone").kind); assertEquals("%.0f%%", s.getValue("AnalogDeadzone").format)
    assertEquals(4, s.getValue("InvertLeftStick").options.size)
    assertEquals(0, NativeLibrary.getTypeSettings("NoSuchType").size)
}
// SettingsAccessTest
@Test fun controllerTypesLeaveOutPointers() {
    val names = NativeLibrary.getSettingNames("ControllerType").toList().chunked(2).map { it[0] }
    assertEquals(listOf("None", "DigitalController", "AnalogController", "AnalogJoystick", "NeGcon", "NeGconRumble"), names)
}
@Test fun typeSettingDefaultsMatchTheCore() { /* for each offered type, each PortSettings setting read on a fresh root == its default */ }
// PortSettingsTest (JVM)
@Test fun aFloatShowsThroughItsFormat() { assertEquals("25%", scale(deadzone).label(25)) }   // step 0.01, ×100
@Test fun anIntListStoresMinPlusIndex() { assertEquals(listOf(0, 1, 2, 3), choices(invert).map { it.value }) }
@Test fun aValueOffTheStepsShowsAsItself() { /* 0.333 → units 33, label "33%" */ }
```

- [ ] **Step 2: Run** them and check they fail.
- [ ] **Step 3: Implement.** The `ControllerType` and `MultitapMode` kinds join `getSettingNames`' table, with the
  pointer types filtered out by `ControllerType`. `getTypeSettings` builds the `TypeSetting` objects with JNI
  (`options` from the null-terminated array). `AddDefaultBindings` gains its per-input check (`GetDeviceBinding`
  empty).
- [ ] **Step 4: Run** the three classes again: PASS. Then JVM, ktlint and the quick tier.
- [ ] **Step 5: Commit**

```
Android: each port's controller type, its settings and multitap through the bridge

- getSettingNames("ControllerType") (no GunCon, Justifier or mouse) and ("MultitapMode"), the core's names
- setPortType writes [PadN] Type and gives each controller on the port the type's defaults where it has none
- getTypeSettings: the type's SettingInfo (kind, range, step, format, options); PortSettings makes them Settings
```

---

### Task 4: Hotkeys: the core's list, and the save-state ones through the app

**Files:**
- Modify: `src/core/hotkeys.cpp:592-626` (`#ifndef __ANDROID__` around `LoadSelectedSaveState`,
  `SaveSelectedSaveState`, `SelectPreviousSaveStateSlot`, `SelectNextSaveStateSlot`, `SaveStateAndSelectNextSlot`,
  `UndoLoadState`)
- Modify: `android_host.cpp` (`HOTKEYS` → `GetOfferedHotkeys()`, `g_host_hotkeys`), `jni_bridge.cpp`,
  `core/PadBinding.kt` (+`val category: String = ""`), `core/NativeCallbacks.kt` (+`fun onHotkey(name: String) {}`),
  `core/NativeLibrary.kt` (+`undoLoadState(): Boolean`), `app/GameCoreApi.kt`, `app/InGameMenu.kt`,
  `app/AppController.kt`, `test/…/app/FakeGameCore.kt`
- Test: `GameCallsTest.kt`, `ControllerBindingsTest.kt`, `test/…/app/InGameMenuTest.kt`

**Interfaces:**
- Produces:
  - `GetOfferedHotkeys(si, pad)`: `InputManager::GetHotkeyList()` in its order, without `ChangeDisc`, `Reset`, names
    starting `LoadGameState`, `SaveGameState`, `LoadGlobalState` or `SaveGlobalState`, and `ToggleAnalogMode` unless
    the pad's type has the Analog button. `getHotkeyBindings` lists them with `category` and the core's
    `display_name`. `setHotkeyBinding` and `ResetPadBindings` use the same list.
  - Host hotkeys `LoadSelectedSaveState`, `SaveSelectedSaveState`, `SelectPreviousSaveStateSlot`,
    `SelectNextSaveStateSlot`, `SaveStateAndSelectNextSlot`, `UndoLoadState` (category "Save States", the core's
    display names). On release, each calls `NotifyHotkey(name)` → `NativeCallbacks.onHotkey(name)`.
  - `enum class StateHotkey(val coreName: String) { Save, Load, Previous, Next, SaveAndNext, Undo }` in
    `InGameMenu.kt`, with `fun hotkey(which: StateHotkey)` on `InGameMenu`. The selected slot starts as
    `lastSlots.get(key) ?: 1`. Previous and Next step through 1–`SaveSlots.COUNT` and wrap. Save and Load use that
    slot through the existing `run(…)` (so `LastSlots`, the disc read and the messages are the menu's), without
    opening the panel or resuming. Undo calls `api.undoLoadState()`.
  - Messages: `"Slot $n selected"`, the menu's own save and load messages, `"Undid the last load"` /
    `"No load to undo"`, `"This game can't keep save states"` (blank key), `"Loading a state is off in hardcore
    mode"`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// InGameMenuTest (JVM, FakeGameCore)
@Test fun saveHotkeySavesToTheLastSlot() { lastSlots.set("SLUS-1", 4); menu.hotkey(StateHotkey.Save); assertEquals(listOf("save SLUS-1 4"), core.calls); assertEquals("Saved to slot 4", messages.last()) }
@Test fun nextWrapsAndSays() { /* last 10 → Next → "Slot 1 selected", then Save writes slot 1 */ }
@Test fun loadInHardcoreIsRefused() { /* hardcore = true → no load call; the message */ }
@Test fun noKeyNoState() { /* serial "" → no call; "This game can't keep save states" */ }
@Test fun undoSaysWhetherItDid() { /* fake undo false → "No load to undo" */ }
// ControllerBindingsTest
@Test fun hotkeysAreTheCoresListWithoutTheMenus() {
    val names = NativeLibrary.getHotkeyBindings("pad-a")!!.map { it.name }
    assertTrue("Rewind" in names && "SaveSelectedSaveState" in names && "ToggleAnalogMode" in names)
    assertTrue(names.none { it in setOf("Reset", "ChangeDisc", "TogglePause") || it.startsWith("LoadGameState") })
    assertEquals("System", NativeLibrary.getHotkeyBindings("pad-a")!!.first { it.name == "Rewind" }.category)
}
// GameCallsTest
@Test fun aSaveStateHotkeyGoesToTheApp() { /* withPadtest: Select + X bound to SaveSelectedSaveState; press → onHotkey("SaveSelectedSaveState") within 3 s, and no file appears under savestates/ */ }
```

- [ ] **Step 2: Run** them and check they fail.
- [ ] **Step 3: Implement.** `AppController.onHotkey` posts to the main thread and maps `coreName` to
  `gameMenu.hotkey(it)`. `readSelectChords` needs no change, as `getHotkeyBindings` now lists every hotkey.
- [ ] **Step 4: Run** the three classes again: PASS. Then `common-tests`, the `duckstation-qt` build, JVM, ktlint and
  the quick tier.
- [ ] **Step 5: Commit**

```
Android: the core's hotkeys on the Controllers page, save states through the app

- getHotkeyBindings lists InputManager's hotkeys with their categories and names, without Change disc, Reset and the
  per-slot ones; the analog mode hotkey for a port with the Analog button only
- The six save-state hotkeys are the host's on Android (the core's are compiled out there): they save, load and pick
  slots through the in-game menu's path, under the game's key, with its messages
- undoLoadState through the bridge
```

---
### Task 5: Macros through the bridge

**Files:**
- Modify: `android_host.cpp` (`GetPadBindings`' neighbour, `SetPadBinding`), `jni_bridge.cpp`, `core/NativeLibrary.kt`,
  `app/CoreApi.kt`, `FakeCoreApi.kt`
- Create: `settings/MacroSettings.kt`
- Test: `GameCallsTest.kt`, `ControllerBindingsTest.kt`, `test/…/settings/MacroSettingsTest.kt`

**Interfaces:**
- Produces:
  - `NativeLibrary.getMacroBindings(descriptor: String): Array<PadBinding>?`: `Macro1`–`Macro4` (display name
    "Macro 1"…, kind Button), the controller's trigger on its pad, or null as `getPadBindings`. `setPadBinding`
    accepts `Macro1`–`Macro4`, and `ResetPadBindings` already clears every key.
  - `object MacroSettings`: `fun settings(pad: Int, macro: Int): List<Setting<*>>`, which are
    `StringSetting("Pad${pad+1}", "Macro${macro}Binds", "")`, `IntSetting(…"Macro${macro}Frequency", 0)` and
    `BoolSetting(…"Macro${macro}Toggle", false)`. Also `fun buttons(binds: String): List<String>` (split on `&`,
    trimmed, empties dropped), `fun binds(buttons: List<String>): String` (joined with `" & "`, in the type's input
    order), `val FREQUENCY: NumberScale` (`Range(0, 60, 1, presets 0, 1, 2, 5, 10, 30, 60)`, label 0 → "Doesn't
    repeat", n → "Every $n frames"), and `fun inputs(bindings: List<PadBinding>)` (the buttons and axes a macro can
    press: the type's bindings).
  - An empty `Binds` is written by deleting the key, as Qt does: `StringSetting` with an empty value calls
    `CoreSettings.delete` (added beside `setString`, with the fake).

- [ ] **Step 1: Write the failing tests**

```kotlin
// MacroSettingsTest
@Test fun bindsRoundTripAsDesktopWritesThem() { assertEquals("Cross & Circle", binds(listOf("Cross", "Circle"))); assertEquals(listOf("Cross", "Circle"), buttons("Cross&Circle ")) }
@Test fun noButtonsDeletesTheKey() { /* MemoryCoreSettings: write "" → no Pad1/Macro1Binds */ }
// ControllerBindingsTest
@Test fun aMacroTriggerIsTheControllersOwn() { /* setPadBinding("pad-a", "Macro1", "Button99") → [Pad1] Macro1 = Android-0/Button99; getMacroBindings shows it */ }
// GameCallsTest
@Test fun aMacroPressesItsButtons() {
    withPadtest(settings = { NativeLibrary.setStringSetting("Pad1", "Macro1Binds", "Cross & Circle") }) {
        NativeLibrary.setPadBinding(PAD, "Macro1", "Button99"); Thread.sleep(500)
        NativeLibrary.onKey(DEVICE_ID, X, true); Thread.sleep(300)
        assertEquals(1f, NativeLibrary.getPadBindStateForTest(0, "Cross")); assertEquals(1f, NativeLibrary.getPadBindStateForTest(0, "Circle"))
        NativeLibrary.onKey(DEVICE_ID, X, false)
    }
}
```

- [ ] **Step 2: Run** them and check they fail. **Step 3: Implement.** **Step 4: Run** them again: PASS, then JVM,
  ktlint and the quick tier.
- [ ] **Step 5: Commit**

```
Android: macros through the bridge

- getMacroBindings: each controller's trigger for Macro1-4 on its port; setPadBinding takes them
- MacroSettings: MacroKBinds (" & "-joined, deleted when empty, as desktop), MacroKFrequency, MacroKToggle
```

---

### Task 6: Rumble on the app's side

**Files:**
- Create: `input/Rumble.kt`
- Modify: `input/ControllerInput.kt` (`ConnectedController` + `val motors: Int`), `core/NativeCallbacks.kt`
  (+`fun onRumble(deviceId: Int, large: Float, small: Float) {}`), `android_host.cpp` (the source's callback →
  `NotifyRumble`, the strengths), `jni_bridge.cpp`, `core/NativeLibrary.kt`, `app/CoreApi.kt`, `app/AppController.kt`,
  `AndroidManifest.xml` (`<uses-permission android:name="android.permission.VIBRATE" />`)
- Test: `test/…/input/RumbleTest.kt`, `GameCallsTest.kt`, `ControllerBindingsTest.kt`

**Interfaces:**
- Consumes: Task 1's router and callback.
- Produces:
  - `NativeLibrary.getRumbleStrength(descriptor: String): Int` (percent, −1 for an unknown controller) and
    `setRumbleStrength(descriptor: String, percent: Int)`, kept in `[Android] ControllerRumble` and passed to the
    source as 0–1.
  - `NativeLibrary.setPadVibrationForTest(pad: Int, large: Float, small: Float)`:
    `InputManager::SetPadVibrationIntensity` on the CPU thread (debug only).
  - `interface Motors { val count: Int; fun set(large: Float, small: Float); fun off() }` and
    `class Rumble(motorsFor: (deviceId: Int) -> Motors?, post: (Runnable) -> Unit)` with
    `fun onRumble(deviceId: Int, large: Float, small: Float)` (both 0 → `off()`; one motor →
    `set(max(large, small), 0f)`) and `fun stopAll()` (every device it started). `post` runs on a `HandlerThread`
    named `Rumble`, so neither the CPU thread nor the main thread waits on a vibrator.
  - `class DeviceMotors(device: InputDevice) : Motors`. It has 0 motors for `!device.isExternal`. On API 31+ it uses
    `device.vibratorManager.vibratorIds` (the first is large, the second small, at most 2), with
    `CombinedVibration.startParallel()` and `VibrationEffect.createOneShot(RUMBLE_MS = 1000, amplitude)`. Before 31 it
    uses `device.vibrator` when `hasVibrator()`, as one motor. `fun amplitude(level: Float, control: Boolean): Int` is
    `(level * 255).roundToInt().coerceIn(1, 255)`, or `VibrationEffect.DEFAULT_AMPLITUDE` without amplitude control.
  - `AppController` calls `rumble.stopAll()` in `onGameStopped` and as the menu opens.

- [ ] **Step 1: Write the failing tests**

```kotlin
// RumbleTest (JVM, a fake Motors, post = run inline)
@Test fun zeroTurnsItOff() { rumble.onRumble(1, 0f, 0f); assertEquals(listOf("off"), fake.calls) }
@Test fun oneMotorTakesTheStronger() { fake.count = 1; rumble.onRumble(1, 0.2f, 0.7f); assertEquals(listOf("set 0.7 0.0"), fake.calls) }
@Test fun noMotorsNothing() { fake.count = 0; rumble.onRumble(1, 1f, 1f); assertTrue(fake.calls.isEmpty()) }
@Test fun stopAllStopsEachStarted() { rumble.onRumble(1, 1f, 0f); rumble.onRumble(2, 1f, 0f); rumble.stopAll(); /* both off */ }
@Test fun amplitudeIsNeverZeroWhileOn() { assertEquals(1, DeviceMotors.amplitude(0.001f, true)); assertEquals(255, DeviceMotors.amplitude(1f, true)) }
// GameCallsTest
@Test fun rumbleGoesToTheLastUsedControllerOnItsPort() {
    // withPadtest with two pads, A (device 1) and B (device 2), both on Port 1; B presses last;
    // setRumbleStrength(B, 50); setPadVibrationForTest(0, 1f, 0.5f) → onRumble(2, 0.5f, 0.25f) within 3 s, nothing for 1
}
@Test fun aControllerOnAnotherPortDoesntRumble() { /* A on Port 2 → Port 1's vibration only ever reaches B */ }
// ControllerBindingsTest
@Test fun rumbleStrengthIsKeptPerController() { /* set 30 → [Android] ControllerRumble = 100, 30; survives a restart */ }
```

- [ ] **Step 2: Run** them and check they fail. **Step 3: Implement.** **Step 4: Run** them again: PASS, then JVM,
  ktlint and the quick tier.
- [ ] **Step 5: Commit**

```
Android: rumble on controllers whose motors Android exposes

- Each port's motor levels reach its last-used controller (onRumble), times the controller's strength, kept in
  [Android] ControllerRumble
- Rumble drives the controller's own InputDevice vibrators (VibratorManager on Android 12+, its vibrator before),
  never the handheld's own, on a thread of its own; it stops with the game and as the menu opens
- The VIBRATE permission
```

---

### Task 7: The start message for a controller on a port the game doesn't use

**Files:**
- Modify: `android_host.cpp` (where the game-started notification is sent)
- Test: `GameCallsTest.kt`

**Interfaces:**
- Produces: after a boot, for each connected controller whose pad isn't in use, one
  `Host::AddOSDMessage("<name> is on <label>, which this game doesn't use.", Host::OSD_INFO_DURATION)`. A pad is in
  use when `g_settings.controller_types[pad] != ControllerType::None` and, for a multitap slot, its port's multitap is
  on in `g_settings.multitap_mode`. `<label>` comes from Task 2's port labels for the ports offered now. Two
  controllers that share a descriptor give one message.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun aControllerOnAnUnusedPortIsTold() {
    // multitap Port1Only in a game's file only (so 1C is offered); the pad on pad 3 (1C); boot padtest (no game file)
    // → onOsdMessage text "Pad pad-a is on Port 1C, which this game doesn't use." within 5 s
}
@Test fun noMessageOnAUsedPort() { /* the pad on Port 1: no such message in 3 s */ }
@Test fun aNotConnectedPortCountsAsUnused() { /* the pad on Port 2, [Pad2] Type = None → the message names Port 2 */ }
```

- [ ] **Step 2: Run** and check they fail. **Step 3: Implement.** **Step 4: Run** again: PASS, then the quick tier.
- [ ] **Step 5: Commit**

```
Android: a game's start says which controllers are on a port it doesn't use

- One message per controller whose port has no controller type, or is a multitap port with multitap off, as the
  core set it up for this game
```

---

### Task 8: The Controllers page's model

**Files:**
- Modify: `app/ControllersPage.kt`, `app/CoreApi.kt` (`PadPort`), `test/…/app/FakeCoreApi.kt`
- Test: `test/…/app/ControllersPageTest.kt`

**Interfaces:**
- Consumes: Tasks 2–6's `CoreApi` calls; `PortSettings`, `MacroSettings`; `SettingValues`.
- Produces:
  - `ControllersState` gains `ports: Map<String, Int>` (descriptor → pad), `rumble: Map<String, Int>`,
    `offered: List<Choice<Int>>` (Task 2's, plus any controller's pad that isn't among them, labelled as the core
    would, noted "No game uses this port"), `portTypes: Map<Int, String>`, `multitap: SettingValues?`, and the open
    sub-page: `view: ControllersView` (`sealed interface`: `Hub`, `Port(pad)`, `Macro(pad, macro)`, `Hotkeys`).
    It also gains `typeSettings: List<TypeSetting>` and `portValues: SettingValues?` for the open port, and
    `macroValues: SettingValues?` and `macros: List<PadBinding>?` for the open macro. `port1Type` goes.
    `bindingsType: String?` is the selected controller's port type, which picks the glyphs.
  - `fun setPort(descriptor, pad)`, `fun setRumble(descriptor, percent)`, `fun setMultitap(values)` (re-reads
    `offered` after the save), `fun openPort(pad)`, `fun setType(pad, type)` (reads the type settings and bindings
    again), `fun changePort(values)`, `fun openMacro(pad, macro)`, `fun changeMacro(values)`, `fun openHotkeys()`,
    `fun back()` (one level up, `Hub` from a top page).
  - On `Port(pad)`, `select` and the controller choice cover only the controllers on that pad. With none,
    `selected` is null and no capture starts. `Hotkeys` covers every connected controller. `capture(name)` works for
    a binding, a hotkey or `MacroK` alike. A read that comes back after a later change or view switch is dropped, as
    `bindingsRead` does today.

- [ ] **Step 1: Write the failing tests** (`ControllersPageTest`, with `FakeCoreApi` holding ports, types and
  settings in memory):

```kotlin
@Test fun movingAControllerToPort2ShowsItThere() { page.setPort("a", 1); advance(); assertEquals(1, page.state.ports["a"]); page.openPort(1); advance(); assertEquals("a", page.state.selected) }
@Test fun aPortWithNoControllerHasNoSelection() { page.openPort(1); advance(); assertNull(page.state.selected) }
@Test fun aPadNotOfferedStillShows() { /* fake ports a → 3, offered 0,1 → offered has 3 with the note */ }
@Test fun multitapOnOffersItsPorts() { /* setMultitap(Port1Only) → offered re-read → labels Port 1A…1D */ }
@Test fun aTypeChangeReadsItsSettings() { /* setType(0, "NeGcon") → typeSettings are NeGcon's, bindingsType "NeGcon" */ }
@Test fun aStaleBindingsReadIsDropped() { /* openPort(0) then openPort(1) before the first read returns → port 1's */ }
@Test fun backGoesUpOneLevel() { /* Macro(0,1) → Port(0) → Hub */ }
@Test fun aMacroTriggerCaptures() { /* openMacro(0, 1); capture("Macro1"); onBindingCaptured("Button99") → setPadBinding(a, Macro1, Button99) */ }
```

- [ ] **Step 2: Run** and check they fail. **Step 3: Implement.** **Step 4: Run** again: PASS, then ktlint.
- [ ] **Step 5: Commit**

```
Android: the Controllers page's model gains ports, types, macros and hotkeys

- Each controller's port and rumble strength; the ports offered; multitap
- A Port view (type, its settings, the bindings of a controller on it), a Macro view and a Hotkeys view
- Reads that come back after a later change or view are dropped, as the bindings' are
```

---
### Task 9: The Controllers hub

**Files:**
- Modify: `ui/ControllersScreen.kt` (rewritten), `MainActivity.kt`, `app/AppController.kt` (Back through
  `controllersPage.back()`)
- Create: `ui/BindingRow.kt` (today's `BindingRow` and `Line`, moved as they are)
- Test: `ui/ControllersScreenTest.kt`, `FocusAuditTest.kt`, `OrientationLayoutTest.kt`

**Interfaces:**
- Produces: `ControllersScreen(state: ControllersState, onPort: (String, Int) -> Unit, onRumble: (String, Int) ->
  Unit, onMultitap: (SettingValues) -> Unit, onOpenPort: (Int) -> Unit, onOpenHotkeys: () -> Unit, names: CoreNames,
  onBack: () -> Unit)`, from top to bottom:
  - the menu triggers' notice, as today;
  - **Controllers** (`SettingsSection`): with none connected, "Connect a controller to set it up." (focusable, as
    today). Otherwise, for each controller, a `CyclerRow` labelled with its name (`labels`) over `offered`, whose note
    is the pad's when there is one. Then a `NumberRow` "Rumble strength" (`Range(0, 100, 10, presets 0, 25, 50, 75,
    100)`, "$n%"). For a controller with `motors == 0`, a line instead: "This controller has no motors Android can
    drive.";
  - **Ports**: the `CyclerRow` "Multitap" (core names), then one `NavRow` per offered pad, labelled with the port and
    summarized as "<type display name> · <names on it>" or "<type> · No controller";
  - **Hotkeys**: a `NavRow` "Hotkeys", summarized as "Select + a button, while a game runs".
  - The focus starts on the first row, never on a value that changes as controllers come and go.

- [ ] **Step 1: Write the failing tests**: key-driven `ControllersScreenTest` cases (the port cycler steps with
  Right; the rumble row steps by 10; the no-motor line; Multitap's list; A on a port's row calls `onOpenPort`; Back
  calls `onBack`). `FocusAuditTest` and `OrientationLayoutTest` cases for the hub with none, one and three
  controllers (one with no motors), with and without multitap.
- [ ] **Step 2: Run** and check they fail. **Step 3: Implement.** Today's binding tests in `ControllersScreenTest`
  move to `PortScreenTest` in Task 10, unchanged but for the screen they open.
- [ ] **Step 4: Run** the three classes again: PASS, then the quick tier.
- [ ] **Step 5: Commit**

```
Android: the Controllers hub: each controller's port and rumble, multitap, the ports and hotkeys

- One port row per controller (the offered ports, or its own when none uses it) and its rumble strength, or a line
  when it has no motors Android drives
- Multitap, then a row per port with its type and controllers, then Hotkeys
- In FocusAuditTest and OrientationLayoutTest
```

---

### Task 10: The Port and Macro pages

**Files:**
- Create: `ui/PortScreen.kt`, `ui/MacroScreen.kt`
- Modify: `MainActivity.kt`, `docs/design-system.md` (nothing new, unless a row needs a note there)
- Test: `ui/PortScreenTest.kt`, `ui/MacroScreenTest.kt`, `FocusAuditTest.kt`, `OrientationLayoutTest.kt`

**Interfaces:**
- Produces:
  - `PortScreen(state, names, onType, onChange: (SettingValues) -> Unit, onSelect, onCapture, onCancelCapture,
    onClear, onUndo, onDropUndo, onReset, onOpenMacro: (Int) -> Unit, onBack)`, titled with the port's label:
    **Controller type** (`CyclerRow` over `NameList.ControllerType`, `offered()` for a type from the file).
    **Settings**: a row for each `TypeSetting` (`SwitchRow`, `NumberRow` with `PortSettings.scale`, `CyclerRow` with
    `PortSettings.choices`), with the description as the note. **Bindings**: the controller row (a `CyclerRow` with
    more than one on the port, the name with one), then today's `BindingRow`s with glyphs when `bindingsType` is in
    `GLYPH_TYPES`. With none on the port, "No controller is on <port>. Pick <port> for one on the Controllers page.".
    **Macros**: a `NavRow` each for Macro 1–4, summarized as its buttons or "Not set". Then **Reset to defaults**
    (the controller's bindings, as today).
    A type with no settings (Digital) has no Settings section. With "Not Connected" (`None`), only the type row shows.
  - `MacroScreen(state, onChange, onCapture, onCancelCapture, onClear, onUndo, onDropUndo, onBack)`, titled "Macro
    <k> · <port>": **Trigger**, the selected controller's `BindingRow` for `MacroK` (with no controller there, the
    Port page's line). **Presses**: a `SwitchRow` per input of the type (`MacroSettings.inputs`), with glyphs as
    bindings have them. **Repeat**: `NumberRow` with `MacroSettings.FREQUENCY`, then `SwitchRow` "Press to toggle"
    (`MacroKToggle`).

- [ ] **Step 1: Write the failing tests**: today's binding cases, moved to `PortScreenTest`, plus the type row,
  a Float row stepping (`25%` → `26%` with Right), an IntList row's list, the empty-port line, a type with no
  settings, and the macros' rows. `MacroScreenTest`: a press switched on calls `onChange` with `" & "`-joined binds,
  the repeat row's "Doesn't repeat", the trigger's capture prompt. `FocusAuditTest` and `OrientationLayoutTest` for
  each page (Analog, NeGcon (the longest), None; a macro page) (`@LargeTest` for NeGcon's orientation pass if it's
  slow).
- [ ] **Step 2: Run** and check they fail. **Step 3: Implement.** **Step 4: Run** them again: PASS, then the quick tier.
- [ ] **Step 5: Commit**

```
Android: the Port and Macro pages

- Port: the controller type (no GunCon, Justifier or mouse), the type's settings from the core, the bindings of a
  controller on the port, its four macros and Reset to defaults
- Macro: the controller's trigger, the inputs it presses, how often it repeats and whether a press toggles it
- In FocusAuditTest and OrientationLayoutTest
```

---

### Task 11: The Hotkeys page

**Files:**
- Create: `ui/HotkeysScreen.kt`
- Modify: `MainActivity.kt`
- Test: `ui/HotkeysScreenTest.kt`, `FocusAuditTest.kt`, `OrientationLayoutTest.kt`

**Interfaces:**
- Produces: `HotkeysScreen(state, onSelect, onCaptureHotkey, onCancelCapture, onClear, onUndo, onDropUndo, onBack)`:
  the controller row (as on the Port page, over every connected controller), the line "Hold Select and press the
  button you set. They work while a game runs.", then a `SettingsSection` per category in the core's order, each
  hotkey a `BindingRow` named by its core display name, with today's prompt "Hold Select and press a button for
  <name>… Back cancels (n)". `HOTKEY_LABELS` goes.

- [ ] **Step 1: Write the failing tests**: categories in order, a capture and Clear on a hotkey in the middle of the
  list (scrolled into view), and no Reset/Change disc rows. Today's hotkey cases from `ControllersScreenTest` move
  here with the core's names. `FocusAuditTest` and `OrientationLayoutTest` for the page with one and two
  controllers.
- [ ] **Step 2: Run** and check they fail. **Step 3: Implement.** **Step 4: Run** them again: PASS, then the quick tier.
- [ ] **Step 5: Commit**

```
Android: the Hotkeys page, with the core's hotkeys by category

- Every hotkey the page offers, under desktop's categories and names, each a Select chord for one controller
- In FocusAuditTest and OrientationLayoutTest
```

---

### Task 12: Docs, the full tier, the emulator walk-through

**Files:**
- Modify: `docs/android.md` ("Controllers", "Changing settings" (drop "until the next stage…"), "Results on the
  emulator" (new "The settings pages, stage 2" entry), "Checks on the AYN Thor" (rumble, a second controller, the
  built-in pad never rumbling))

- [ ] **Step 1:** Rewrite "Controllers" for the hub, the ports (`ControllerPorts`, moving bindings, multitap ports
  offered from any game's file, the start message), types and their settings, per-type defaults, macros, the hotkeys
  (which are left out, and why; save states through the app), rumble (`ControllerRumble`, last used, never the
  handheld's own motor, `VIBRATE`, the core's motor keys not read on Android), and the core multitap bugs as a known
  limit.
- [ ] **Step 2:** `GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh full` in the
  background. Read every part's XML, then `./gradlew check`, `assembleRelease`, `common-tests` on desktop and the
  emulator, and the `duckstation-qt` build.
- [ ] **Step 3:** Walk through on an emulator, with two virtual pads if it offers them, otherwise one: the hub; Port 2
  and back; a NeGcon's settings; a macro firing in padtest; a save-state hotkey saving and loading a slot; the start
  message on 1C. Record what passed in "Results on the emulator". Rumble and two real controllers wait for the Thor
  (the user's go-ahead).
- [ ] **Step 4: Commit** the docs and push: `git push origin HEAD:claude/android-settings-controllers`.

```
Docs: Android's controllers settings, stage 2

- Controllers: ports, types and their settings, multitap, macros, the hotkeys and rumble, as built
- The emulator results, and the Thor checks rumble and a second controller wait for
```
