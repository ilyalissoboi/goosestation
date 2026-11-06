<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android settings, stage 3: settings for each game — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each game in the library, and the running game from its in-game menu, gets its own settings pages, kept
in `gamesettings/<key>.ini` as desktop keeps them, every row able to fall back to the global setting.

**Architecture:** The core gets two Android-only hooks: the running game's settings file is the tile's key's, for
every disc, and the loaded layer is adapted in memory (desktop's bindings, input profile, renderer and BIOS names
dropped; the file is never changed by this). The bridge gains typed reads and writes of a game's file by key.
`SettingValues` gains a game layer (global values, the keys the game sets), so today's page composables render as
game pages unchanged: the shared row helpers turn into "Use global (…)" choices and mark what the game sets. A
`GameSettingsPages` controller reads, saves and (in a running game) applies them; a game hub (summary, Copy, Clear,
the pages) opens from the library's tiles and from the in-game menu.

**Tech Stack:** C++20 core (`src/core`, `src/util`), JNI (`android/app/src/cpp`), Kotlin + Jetpack Compose, JUnit
(JVM), AndroidX instrumented tests, GoogleTest (`common-tests`).

**Spec:** `docs/superpowers/specs/2026-11-05-android-settings-design.md`, "Settings for each game" (and "Controllers"
for the per-type bindings stage 2 left here).

## Global Constraints

- `docs/design-system.md` and its tokens are binding: controller-first, focus always visible, no window dialogs.
  Every new page goes into `FocusAuditTest` and `OrientationLayoutTest`, with a key-driven screen test.
- Keys and values are desktop's; files stay ones desktop reads. A game's file is `System::GetGameSettingsPath(key)`
  (a database disc set's first serial, sanitized). "Use global" deletes the key; a file left empty is deleted.
- Copy global settings is desktop's: `Settings temp; temp.Load(base, base); temp.Save(game, true)`. Clear settings is
  Big Picture's: the whole file is deleted. Both ask first, in the app's window.
- Game pages: Console, Emulation, Graphics, Audio, Controllers, Memory cards (card types and shared cards only),
  Achievements (the switches and durations, not the account). Post-processing and Advanced come with stage 4. The
  in-game Settings never reaches Interface, BIOS, Folders, the global pages or the memory card editor.
- Controllers for a game: multitap, each port's type and the type's settings. Bindings and macros stay global. A
  game's controller keys count only with `[ControllerPorts] UseGameSettingsForController = true`, which Android sets
  when the game has any and deletes when it has none.
- The library's global settings stay locked while a game runs (`settingsReady`); a game's settings from the library
  need it too.
- Android-only core code goes behind `__ANDROID__`; desktop behaviour is unchanged. Clean room: nothing from
  DuckStation after `a5bf1ba5cee7`, nor its closed Android app.
- `gh` as ilyalissoboi (`GH_TOKEN=$(gh auth token --user ilyalissoboi)`), never the GitHub MCP. Emulators only; the
  AYN Thor needs the user's go-ahead. Commit each task when it's done and show its message after. Commit messages end
  with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Found in the code, and how this plan deals with it

- **The design's claims hold.** `UpdateGameSettingsLayer` (`system.cpp:1384-1445`) loads
  `GetGameSettingsPath(s_running_game_serial)`, the running disc's serial (`system.cpp:4060-4076`), so an M3U's or a
  name group's discs each read their own file. The bridge (`android_host.cpp:528-617`) touches only
  `settings.ini`. `GetControllerSettingsLayers` (`system.cpp:1277-1293`) takes the game layer for controllers only with
  `UseGameSettingsForController`, else an input profile named by `InputProfileName`.
- **The tile's key is known at boot:** `s_running_game->key` (`AndroidDiscGroups::Game::key`) is set in
  `BootPendingGame` before `System::BootSystem`; a file the list doesn't have has an empty key, and keeps the core's
  serial. Task 1 passes the key to the core (`System::SetGameSettingsSerial`).
- **Desktop game files with per-game controllers hold desktop's whole bindings:** turning per-game controllers on
  in Qt or Big Picture runs `InputManager::CopyConfiguration` (`input_manager.cpp:1455-1525`; `gamesummarywidget.cpp:
  340-347`, `fullscreen_ui.cpp:3689-3695`): every `[PadN]` binding (`SDL-0/…`), macros and type settings. Layered
  over `settings.ini`, they'd make Android's controllers dead in that game. An input profile is desktop's bindings
  too. **Safest:** on Android the loaded layer drops every `[PadN]` key but `Type` and the types' settings, the macros,
  `[InputSources]`, `[Hotkeys]` and `InputProfileName`, in memory (Task 1). A file that names an input profile shows
  its Controllers page read-only, saying so, since writing `UseGameSettingsForController` would switch desktop off
  the profile (Task 9).
- **Other keys only desktop can use:** a `[GPU] Renderer` Android doesn't have (D3D11, D3D12, Metal), `[BIOS]` image
  names not in Android's BIOS folder, `SearchDirectory`, an absolute `[MemoryCards] Directory`. Dropped from the loaded
  layer the same way. `foreign_settings.cpp` stays as it is for `settings.ini`.
- **Copy writes `[PadN] Type`, `MultitapMode`, `[MemoryCards]` and `[Cheevos]`** (`settings.cpp:627-662`) and skips
  the OSD, Interface, `[Hacks]`, `[Logging]` and `[Debug]` keys (`settings.cpp:463-683`). Copied types take effect
  only once `UseGameSettingsForController` is on, as on desktop; the Controllers page reads them only then.
- **What applies at once in a running game** (`System::CheckForSettingsChanges`): everything on these pages but Region,
  8 MB RAM, Preload disc to RAM and Apply image patches, which wait for the next boot (`system.cpp:1573`,
  `bus.cpp:385`, `system.cpp:1800, 1607`). Hardcore turned on waits for a reset or the next boot
  (`achievements.cpp:705-708`); turned off, it's at once. Encore, Spectator and Unofficial restart the client, which
  silently ends hardcore until the next reset (`achievements.cpp:712-719`). Any apply clears the rewind and run-ahead
  history (`system.cpp:4310`).
- **`ReloadGameSettings` does nothing when there was no file and still isn't** (`system.cpp:1413`), which is right.
- **Types a game sets need bindings in `settings.ini`:** `AddDefaultBindings` reads the base layer's type
  (`android_host.cpp:1627-1653`), and the Port page lists only that type's inputs (stage 2's known limit). Task 9 gives
  a game's types their defaults, at the change and at boot, and lists their inputs on the global Port page.
- **`GetOfferedPorts` reads `MultitapMode` from every game file** (`android_host.cpp:2006-2054`), with or without the
  flag. Task 9 counts only files whose controller keys count.

## Review Focus

1. **A desktop game file with `UseGameSettingsForController = true` and `SDL-0/…` bindings:** the game's controllers
   are Android's global ones, and its `[PadN] Type` still applies. Pinned in Task 1 (`AndroidGameSettings` tests and
   `aGamesDesktopBindingsDontReplaceAndroids`).
2. **A value picked equal to the global one** is still the game's own: written, marked and counted, so it stays when
   the global one changes (unlike Big Picture, which deletes it at the default). Pinned in Task 3
   (`aGlobalEqualValueIsStillSet`).
3. **A disc change in a playlist** keeps the key's file: the layer is reloaded on the disc change, still under the
   key. Pinned in Task 1 (`theKeysFileHoldsAcrossADiscChange`, full tier).
4. **The last "Use global"** deletes the file, the summary says "No settings of its own", and the mirror's next push
   deletes it in the data folder. Pinned in Task 2 (`theLastDeleteRemovesTheFile`) and Task 7
   (`useGlobalOnTheLastRowEmptiesTheSummary`).
5. **The mirror changes a game's file while its page shows:** the page reads it again, keeping its focus. Pinned in
   Task 7 (`aPullReadsTheShownPageAgain`).

---

## Calls made here *(builder's calls, for the user to confirm with the plan)*

- **Opening a game's settings from the library:** Y on a focused tile or list row, and a long press for touch. The
  library gets a `KeyHints` footer: "A Play · Y Game settings". A tile with a blank key (a PSF) has none.
- **The mark:** a row the game's file sets shows "Set for this game" in `label-sm` `beak-ink`, before its note, and
  TalkBack reads it. It marks any key the file sets, even to the global value (it no longer follows the global one).
  The hub's entries say how many each page sets, and the summary the total, plus any keys the pages don't show ("and 4
  this app doesn't show", desktop's keys or bindings).
- **"Use global" on a number row:** first in its list. Right from it sets the global value as the game's own; Left
  from the scale's lowest value goes back to it. An on/off row is a choice of "Use global (On)", On and Off.
- **In a running game:** Region, 8 MB RAM, Preload disc to RAM and Apply image patches say "Applies when the game
  next starts."; hardcore says "Turning it on applies when the game next starts."; Encore, Spectator and Test
  unofficial achievements are disabled while hardcore mode is active, saying "Change it before the game starts: now
  it would end hardcore mode."
- **The game's Memory cards page** has the card types and shared cards; one card for all discs stays global (the
  design's "card types and shared cards only").
- **The game pages' titles** are the page's, with the game's title as an `Eyebrow` over it.

## File Structure

- `src/util/android_game_settings.{h,cpp}` (new): `AndroidGameSettings::Adapt`, `HasControllerKeys`. Tests:
  `src/common-tests/android_game_settings_tests.cpp` (new). `src/core/system.{h,cpp}`: `SetGameSettingsSerial` and the
  `Host::AdaptGameSettingsLayer` call, `#ifdef __ANDROID__`. `src/core/host.h`: its declaration, the same.
- `android/app/src/cpp/android_host.{h,cpp}`: the key at boot, the adapt hook, the game file calls, per-game type
  defaults, the Port page's other types, the test hooks. `jni_bridge.cpp`: their JNI.
- `core/NativeLibrary.kt`; `settings/LayerSettings.kt` (new: `LayerSettings`, `GameCoreSettings`),
  `settings/SettingValues.kt` (the game layer), `settings/NextStart.kt` (new), `settings/AchievementsSettings.kt`
  (`Setting`s), `settings/MemoryCardSettings.kt` (+`GAME`).
- `app/CoreApi.kt` (+game calls), `app/GameSettingsPages.kt` (new: `GamePage`, `GameSummary`, `GameQuestion`,
  `GameSettingsPages`), `app/AchievementsPage.kt` (`SettingValues`), `app/AppController.kt`, `app/InGameMenu.kt`,
  `MainActivity.kt`.
- `ui/components/SettingsRows.kt` (`mark`, NumberRow's "Use global", the page's eyebrow), `ui/GameContext.kt` (new:
  `GameContext`, `LocalGameContext`), `ui/PageRows.kt` (game mode, `Choices`), `ui/ConsoleScreen.kt`,
  `ui/GraphicsScreen.kt`, `ui/PortScreen.kt`, `ui/MemcardSyncScreen.kt`, `ui/AchievementsScreen.kt` (rows through the
  helpers), `ui/GameSettingsScreen.kt` (new: the game hub), `ui/GameSettingsHost.kt` (new: hub and pages routed),
  `ui/GameControllersScreen.kt` (new), `ui/LibraryScreen.kt`, `ui/GameTile.kt`, `ui/GameListRow.kt`, `ui/GameMenu.kt`,
  `ui/GameScreen.kt`.
- Tests: `androidTest/…/core/GameSettingsAccessTest.kt` (new), `GameCallsTest.kt`, `ControllerBindingsTest.kt`;
  `androidTest/…/ui/SettingsRowsTest.kt`, `NumberRowTest.kt`, `GameSettingsScreenTest.kt` (new),
  `GamePagesScreenTest.kt` (new), `GameControllersScreenTest.kt` (new), `LibraryScreenTest.kt`, `GameMenuTest.kt`,
  `AchievementsScreenTest.kt`, `PortScreenTest.kt`, `FocusAuditTest.kt`, `OrientationLayoutTest.kt`;
  `test/…/settings/SettingValuesTest.kt`, `MemoryCoreSettings.kt` (+`LayerSettings`);
  `test/…/app/GameSettingsPagesTest.kt` (new), `AchievementsPageTest.kt`, `InGameMenuTest.kt`, `FakeCoreApi.kt`.
- Docs: `docs/design-system.md` (game rows, the mark, the game hub), `docs/android.md` "Settings for each game".

Kotlin paths are under `android/app/src/main/java/io/github/ilyalissoboi/goosestation/` (tests under
`android/app/src/test/java/…` and `android/app/src/androidTest/java/…`).

## Running the tests

- Environment: `source /tmp/gs3-env.sh`. Native, desktop: `cmake --build build --target common-tests &&
  build/bin/common-tests --gtest_filter='AndroidGameSettings*'`, then build `duckstation-qt`. On the emulator:
  `android/scripts/run-native-tests.sh`.
- JVM: `cd android && ./gradlew -q testDebugUnitTest --tests '<Class>'`, then `./gradlew -q ktlintCheck`.
- One instrumented class: `./gradlew connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.<pkg>.<Class>`.
- Each task that touches the UI or the bridge ends with `android/scripts/run-instrumented-tests.sh quick`, read from
  the result XML under `app/build/outputs/androidTest-results/`, not Gradle's verdict. The full tier
  (`GOOSE_TEST_DATA=~/work/goose-test-data`, in the background) runs in Task 12.

---

### Task 1: The core: the key's file for every disc, adapted for Android

**Files:**
- Create: `src/util/android_game_settings.{h,cpp}` (add to `src/util/CMakeLists.txt`), and
  `src/common-tests/android_game_settings_tests.cpp` (add to its `CMakeLists.txt`)
- Modify: `src/core/system.{h,cpp}` (`UpdateGameSettingsLayer`), `src/core/host.h`, both `#ifdef __ANDROID__`;
  `android/app/src/cpp/android_host.cpp` (`BootPendingGame`, the game's end), `jni_bridge.cpp`
- Test: `androidTest/…/core/GameCallsTest.kt`

**Interfaces:**
- Produces (`AndroidGameSettings`, `src/util`):
  - `struct Usable { std::vector<std::string> pad_setting_keys; std::vector<std::string> renderers;
    std::function<bool(std::string_view)> has_bios; };`
  - `void Adapt(SettingsInterface& si, const Usable& usable)`: deletes `[ControllerPorts] InputProfileName`; the
    `[InputSources]` and `[Hotkeys]` sections; in `[Pad1]`–`[Pad8]`, every key but `Type` and those in
    `pad_setting_keys`; `[GPU] Renderer` unless in `renderers`; `[BIOS] PathNTSCJ`/`PathNTSCU`/`PathPAL` for which
    `has_bios` is false, and `[BIOS] SearchDirectory`; `[MemoryCards] Directory` when absolute.
  - `bool HasControllerKeys(SettingsInterface& si)`: a key in `[ControllerPorts]` other than
    `UseGameSettingsForController` and `GameSettingsInitialized`, or any key in a `[PadN]`.
- Produces (core, Android only): `void System::SetGameSettingsSerial(std::string serial)`: the serial whose file the
  game layer loads, for every disc, while not empty; else the running disc's, as today. `void
  Host::AdaptGameSettingsLayer(SettingsInterface& si)`, called on the loaded layer before `InputProfileName` is read.
- Produces (host): `BootPendingGame` sets the serial to `s_running_game->key` before `BootSystem`; the game's end
  (where `s_running_game` is reset) sets it to empty. The host's hook calls `Adapt` with every offered and not
  offered type's `ControllerInfo::settings` names, the renderers Android has (`Automatic`, `Vulkan`, `OpenGL`,
  `Software`, by `Settings::GetRendererName`), and `has_bios` = the file exists in `EmuFolders::Bios`.
- Produces (test JNI): `NativeLibrary.setListedKeyForTest(path: String, key: String)` (the listed game's key) and
  `getRunningSettingForTest(section: String, key: String): String` (`Host::GetStringSettingValue`, layered, on the
  CPU thread; empty without a game).

- [ ] **Step 1: Write the failing native tests** (`AndroidGameSettings.*`, on a `MemorySettingsInterface`):

```cpp
TEST(AndroidGameSettings, KeepsTypesAndTypeSettingsDropsBindings) {
  // [Pad1] Type=NeGcon, SteeringDeadzone=0.2, Cross=SDL-0/A, Macro1Binds=Cross; usable.pad_setting_keys={"SteeringDeadzone"}
  Adapt(si, usable);
  EXPECT_EQ(si.GetStringValue("Pad1", "Type"), "NeGcon");
  EXPECT_TRUE(si.ContainsValue("Pad1", "SteeringDeadzone"));
  EXPECT_FALSE(si.ContainsValue("Pad1", "Cross"));
  EXPECT_FALSE(si.ContainsValue("Pad1", "Macro1Binds"));
}
TEST(AndroidGameSettings, DropsTheInputProfileSourcesAndHotkeys) { /* InputProfileName, [InputSources] SDL, [Hotkeys] gone; MultitapMode kept */ }
TEST(AndroidGameSettings, DropsARendererAndroidLacks) { /* D3D12 gone; Vulkan kept */ }
TEST(AndroidGameSettings, DropsMissingBiosNamesAndTheFolder) { /* has_bios("a.bin") true: PathNTSCU=a.bin kept, PathPAL=b.bin gone, SearchDirectory gone */ }
TEST(AndroidGameSettings, DropsAnAbsoluteCardFolder) { /* Directory=/home/x gone, Directory=cards kept */ }
TEST(AndroidGameSettings, ControllerKeysIgnoreTheFlag) {
  // {UseGameSettingsForController, GameSettingsInitialized} only → false; + [Pad2] Type → true; + MultitapMode only → true
}
```

- [ ] **Step 2: Run them and check they fail** to compile (`Adapt` unknown).
- [ ] **Step 3: Implement** `android_game_settings.cpp`, then the two hooks in `UpdateGameSettingsLayer` (the
  serial chosen at its `GetGameSettingsPath` call; `Host::AdaptGameSettingsLayer(*new_interface)` after a good
  `Load()`), and the host's side.
- [ ] **Step 4: Run** `build/bin/common-tests --gtest_filter='AndroidGameSettings*'`: PASS. Build `duckstation-qt`.
- [ ] **Step 5: Write the failing instrumented tests** (`GameCallsTest`, booting padtest as `withListedPadtest`):

```kotlin
@Test fun theKeysFileIsTheGamesLayer() {
    // setListedKeyForTest(padtest, "GOOSE-TEST"); gamesettings/GOOSE-TEST.ini: [Main] EmulationSpeed = 2.0
    // boot: assertEquals("2.0", getRunningSettingForTest("Main", "EmulationSpeed")) (as the core writes floats)
}
@Test fun aGamesDesktopBindingsDontReplaceAndroids() {
    // the key's file: UseGameSettingsForController = true, [Pad1] Type = DigitalController, Cross = SDL-0/A
    // boot: getRunningSettingForTest("Pad1", "Cross") is settings.ini's Android-0 entry; ("Pad1", "Type") is DigitalController
}
@Test fun anInputProfileIsntLoaded() {
    // inputprofiles/Desk.ini [Pad1] Cross = SDL-0/A; the key's file InputProfileName = Desk → Cross is settings.ini's
}
```

and, `@LargeTest` (the full tier, with padtest's disc image as a two-entry M3U of the same image):
`theKeysFileHoldsAcrossADiscChange` (`changeDisc(2)`, then still `2.0`).

- [ ] **Step 6: Run** `GameCallsTest`: the three fail first (the speed reads `1.0`, the bindings `SDL-0/A`), then
  pass after Step 3's host side is built in. Then `run-instrumented-tests.sh quick`.
- [ ] **Step 7: Commit**

```
Core: on Android, a tile's game settings are one file, without desktop's bindings

- The game layer loads gamesettings/<the tile's key>.ini for every disc of the tile (System::SetGameSettingsSerial)
- The loaded layer drops, in memory only, what can't work here: [PadN] bindings and macros, InputProfileName,
  [InputSources], [Hotkeys], a renderer Android lacks, BIOS names not in its BIOS folder, an absolute card folder
- AndroidGameSettings::Adapt and HasControllerKeys, with common-tests
- Desktop unchanged; the files are never rewritten by this
```

---

### Task 2: The bridge: a game's file by key

**Files:**
- Modify: `android/app/src/cpp/android_host.{h,cpp}`, `jni_bridge.cpp`, `core/NativeLibrary.kt`, `app/CoreApi.kt`
- Create: `settings/LayerSettings.kt`; Test: `androidTest/…/core/GameSettingsAccessTest.kt`

**Interfaces:**
- Produces (`NativeLibrary`, each on the caller's thread, `GetGameSettingsPath(key)` taken under the settings lock;
  every write loads the file if it exists, changes it, `RemoveEmptySections()`, then deletes the file if `IsEmpty()`
  or saves it; nothing before `initialize`, or with a blank key):
  `hasGameSetting(key, section, name): Boolean`; `getGame{Bool,Int,Float,String}Setting(key, section, name,
  default)`; `setGame{Bool,Int,Float}Setting(key, section, name, value)`; `setGameStringSetting(key, section, name,
  value: String?)` (null deletes); `deleteGameSetting(key, section, name)`; `getGameSettingKeys(key): Array<String>`
  (section, name, section, name…, the file's order); `copyGlobalSettingsToGame(key)`; `clearGameSettings(key)`
  (deletes the file); `getGameInputProfile(key): String?` (`InputProfileName` when the file names one without
  `UseGameSettingsForController`); `applyGameSettings(key): Boolean` (on the CPU thread, waiting: when the running
  game's key is `key`, `System::ReloadGameSettings(false)` then `System::ReloadInputBindings()`, and true; else
  false).
- Produces (`settings/LayerSettings.kt`): `interface LayerSettings : CoreSettings { fun contains(section: String,
  key: String): Boolean; fun delete(section: String, key: String) }` and `class GameCoreSettings(val key: String) :
  LayerSettings` over the calls above.
- Produces (`CoreApi`, and `NativeCoreApi`, `FakeCoreApi`): `applyGameSettings(key: String): Boolean`,
  `copyGlobalSettingsToGame(key: String)`, `clearGameSettings(key: String)`, `getGameSettingKeys(key: String):
  List<Pair<String, String>>`, `getGameInputProfile(key: String): String?`.

- [ ] **Step 1: Write the failing tests** (`GameSettingsAccessTest`, as `SettingsAccessTest`, reading files with
  `Ini`):

```kotlin
@Test fun aSetWritesTheKeysFile() { setGameIntSetting("SLUS-00001", "GPU", "ResolutionScale", 4); file "gamesettings/SLUS-00001.ini" has ResolutionScale = 4 }
@Test fun aDiscSetsKeyIsItsFirstSerial() { /* a database set's second serial: the file is the first serial's */ }
@Test fun theLastDeleteRemovesTheFile() { set then deleteGameSetting: the file doesn't exist; hasGameSetting false }
@Test fun aStringNullDeletes() { }
@Test fun readsFallBackToTheDefault() { getGameFloatSetting(key, "Main", "EmulationSpeed", 1.0f) == 1.0f with no file }
@Test fun copyIsDesktopsSave() {
    // settings.ini: [GPU] ResolutionScale = 3, [Display] ShowFPS = true, [Main] StartPaused = true
    // copy: ResolutionScale = 3, [Pad1] Type present, no ShowFPS, no StartPaused, no UseGameSettingsForController
}
@Test fun clearDeletesTheWholeFile() { /* a desktop key the pages don't show goes too */ }
@Test fun keysListTheFile() { listOf("GPU" to "ResolutionScale", "Pad1" to "Cross") in the file's order }
@Test fun anInputProfileIsReportedOnlyWithoutTheFlag() { }
@Test fun applyWithoutTheGameRunningIsFalse() { }
```

- [ ] **Step 2: Run** `GameSettingsAccessTest`: fails to compile.
- [ ] **Step 3: Implement** the host's calls (one helper that opens the key's `INISettingsInterface`, one that saves
  or deletes it), their JNI, `NativeLibrary`'s declarations, `LayerSettings`, `GameCoreSettings`, and the `CoreApi`
  calls.
- [ ] **Step 4: Run** it: PASS. Add `GameCallsTest.applyReloadsTheRunningGamesLayer` (boot, set
  `EmulationSpeed = 2` for the key, `applyGameSettings(key)` is true and `getRunningSettingForTest` reads `2.0`): RED
  before `applyGameSettings` reloads, then GREEN. Then `run-instrumented-tests.sh quick`.
- [ ] **Step 5: Commit**

```
Android: the bridge reads and writes a game's own settings file

- Typed reads and writes of gamesettings/<key>.ini, by the core's own path for the key; a file left empty is deleted
- Copy global settings as desktop's (Settings::Save with ignore_base), Clear as Big Picture's (the file goes)
- applyGameSettings reloads and applies the running game's layer when it's that game
- LayerSettings and GameCoreSettings for the pages
```

---
### Task 3: `SettingValues` with a game layer

**Files:**
- Modify: `settings/SettingValues.kt`, `settings/Setting.kt` (+`delete`, `isIn`); Create: `settings/NextStart.kt`
- Test: `test/…/settings/SettingValuesTest.kt`, `MemoryCoreSettings.kt` (now a `LayerSettings`)

**Interfaces:**
- Consumes: `LayerSettings` (Task 2).
- Produces (`SettingValues`):
  - `companion fun readGame(global: CoreSettings, game: LayerSettings, settings: Collection<Setting<*>>)`: each
    setting's global value, and the game's where `game.contains` it.
  - `val isGame: Boolean`; `fun isSet(setting: Setting<*>): Boolean` (false when not a game's);
    `fun <T> global(setting: Setting<T>): T` (the value itself when not a game's); `operator get` is the value in
    effect, the game's or else the global one.
  - `with(setting, value)` on a game's values sets it for the game; `fun withGlobal(setting: Setting<*>):
    SettingValues` takes it back to the global value, unset. Throws when not a game's.
  - `fun setCount(of: Collection<Setting<*>>): Int`.
  - `changed(old)` counts a setting that became set or unset, even at the same value; `save(s, old)` writes each
    changed setting the game sets and deletes each it no longer does (`s` must be a `LayerSettings` for a game's).
  - Equality includes which settings are set.
- Produces (`NextStart`): `val settings: Set<Setting<*>>` = Console's `REGION`, `RAM_8MB`, `PRELOAD`,
  `IMAGE_PATCHES`; `const val NOTE = "Applies when the game next starts."`; `const val HARDCORE_NOTE = "Turning it on
  applies when the game next starts."`.

- [ ] **Step 1: Write the failing tests:**

```kotlin
@Test fun aGamesValueOverridesTheGlobalOne() { global ResolutionScale 3, game 5: v[RES] == 5, v.global(RES) == 3, v.isSet(RES) }
@Test fun unsetFollowsTheGlobalOne() { v[RES] == 3, !v.isSet(RES) }
@Test fun aGlobalEqualValueIsStillSet() { v.with(RES, 3): isSet, changed == [RES], save writes ResolutionScale = 3 to the game }
@Test fun useGlobalDeletes() { v.withGlobal(RES): v[RES] == 3, save deletes the game's key, global untouched }
@Test fun settingsValuesHaveNoLayer() { !read(...).isGame; withGlobal throws }
@Test fun setCountCountsOnlyTheGivenOnes() { }
```

- [ ] **Step 2: Run** `SettingValuesTest`: RED (unresolved `readGame`).
- [ ] **Step 3: Implement** them, the game's set keys kept beside the values.
- [ ] **Step 4: Run** `SettingValuesTest` and the whole JVM suite: PASS; `ktlintCheck`.
- [ ] **Step 5: Commit**

```
Android: a page's values can be a game's, over the global ones

- SettingValues.readGame: each setting's global value, and the game's where its file sets it
- with() sets it for the game, even at the global value; withGlobal() goes back to following it
- save() writes what the game now sets and deletes what it no longer does
- NextStart: the settings a running game only takes when it next starts
```

---

### Task 4: The rows as a game's

**Files:**
- Modify: `ui/components/SettingsRows.kt` (`CyclerRow`/`NumberRow` `mark`; `NumberRow` "Use global";
  `SettingsPage`'s eyebrow), `ui/PageRows.kt`; Create: `ui/GameContext.kt`
- Test: `androidTest/…/ui/SettingsRowsTest.kt`, `NumberRowTest.kt`; Docs: `docs/design-system.md`

**Interfaces:**
- Consumes: Task 3's `SettingValues`, `NextStart`.
- Produces:
  - `data class GameContext(val title: String, val running: Boolean, val hardcoreActive: Boolean)` and
    `val LocalGameContext = staticCompositionLocalOf<GameContext?> { null }`. `SettingsPage` shows
    `Eyebrow(title)` over its title while one is provided.
  - `CyclerRow(…, mark: String? = null)`, `NumberRow(…, mark: String? = null, global: Int? = null,
    onUseGlobal: (() -> Unit)? = null)`: with `global` set (the row follows the global value), it shows "Use global
    (<label of global>)", Right calls `onChange(global)` and Left does nothing; with `onUseGlobal` given and the value
    at the scale's lowest, Left calls it. Its list starts with the "Use global" choice when `onUseGlobal` is given.
    `mark` shows before the note.
  - `const val SET_FOR_GAME = "Set for this game"`; `fun useGlobal(label: String) = "Use global ($label)"`.
  - The helpers, given a game's `values`: `Switch` becomes a `CyclerRow<Boolean?>` of `useGlobal("On"/"Off")`, On,
    Off; `Names` puts `Choice(null, useGlobal(<global's label>))` first; `Number` passes `global`/`onUseGlobal`;
    each marks a set row, and adds `NextStart.NOTE` to the note of a `NextStart` setting while
    `LocalGameContext.current?.running`. New: `@Composable fun <T> Choices(values, onChange, label, setting:
    Setting<T>, choices: List<Choice<T>>, note: String? = null, modifier: Modifier = Modifier)`, a `CyclerRow` of
    `choices` that turns game-aware the same way.
  - A row's choices all come from the row as before; nothing changes on the global pages.

- [ ] **Step 1: Write the failing tests** (key-driven, `PageValues.kt` gains a game's values builder):

```kotlin
@Test fun aGameSwitchIsThreeWay() { /* unset On: "Use global (On)"; Right → On set (marked "Set for this game"); A on the list's first → unset */ }
@Test fun aGameChoiceStartsWithUseGlobal() { }
@Test fun aGameNumberStepsFromTheGlobalValue() { /* global 3: Right → 3 set; Left at the lowest → unset */ }
@Test fun aNextStartRowSaysSoInARunningGame() { /* Region, running: note "Applies when the game next starts."; not running: no note */ }
@Test fun aGamePageShowsTheGamesTitle() { }
@Test fun theGlobalRowsAreUnchanged() { /* no "Use global", no mark */ }
```

- [ ] **Step 2: Run** `SettingsRowsTest` and `NumberRowTest`: RED.
- [ ] **Step 3: Implement** them; document game rows, the mark and the eyebrow in `docs/design-system.md` ("Rows
  for a game").
- [ ] **Step 4: Run** both classes and `FocusAuditTest`: PASS. Then `run-instrumented-tests.sh quick`.
- [ ] **Step 5: Commit**

```
Android: settings rows can be a game's, each able to use the global setting

- On a game's page, a choice row starts with "Use global (<its value>)", an on/off row is Use global, On and Off,
  and a number row steps from the global value
- A row the game sets says "Set for this game"; one a running game takes only at its next start says so
- A game's pages carry its title over theirs
```

---

### Task 5: The rows that aren't one key

**Files:**
- Modify: `ui/ConsoleScreen.kt` (overclock percentage), `ui/GraphicsScreen.kt` (multisampling, sprite texture
  filtering, the custom ratio's terms), `ui/PortScreen.kt` (`TypeSettingRow` through the helpers),
  `ui/MemcardSyncScreen.kt` (`CardSlotRows` internal, `oneCardRow: Boolean`, the shared card through `Choices`),
  `settings/MemoryCardSettings.kt` (+`GAME`: the types and paths)
- Test: `androidTest/…/ui/GamePagesScreenTest.kt` (new)

**Interfaces:**
- Consumes: Task 4's helpers and `CyclerRow`/`NumberRow` parameters.
- Produces: each composite row, on a game's values, offers "Use global" (`withGlobal` on every key it covers),
  writes all its keys on a pick, and is marked when any of them is set: overclock (`OVERCLOCK_NUMERATOR`,
  `OVERCLOCK_DENOMINATOR`), multisampling (its two keys as `GraphicsSettings.withMultisampling` writes them), sprite
  filtering (`SPRITE_TEXTURE_FILTER`), each custom ratio term (its own key). `MemoryCardSettings.GAME`: `CARD1_TYPE`,
  `CARD2_TYPE`, `CARD1_PATH`, `CARD2_PATH`. `CardSlotRows(cards: CardSlots, oneCardRow: Boolean)`.

- [ ] **Step 1: Write the failing tests** (`GamePagesScreenTest`, each page composed with a game's values):

```kotlin
@Test fun overclockUseGlobalUnsetsBothTerms() { }
@Test fun multisamplingPickSetsBothKeys() { }
@Test fun aTypeSettingRowIsAGames() { /* PortScreen's AnalogDeadzone with "Use global (…)" */ }
@Test fun aGamesSharedCardStartsWithUseGlobal() { }
@Test fun everyRowOfEachGamePageCanUseGlobal() {
    // Console, Emulation, Graphics, Audio, the game's cards: for each row shown, its list (A) has "Use global" first
}
```

- [ ] **Step 2: Run** `GamePagesScreenTest`: RED.
- [ ] **Step 3: Implement** them.
- [ ] **Step 4: Run** it, `ConsoleScreenTest`, `GraphicsScreenTest`, `PortScreenTest`, `MemcardSyncScreenTest`:
  PASS. Then `quick`.
- [ ] **Step 5: Commit**

```
Android: the rows that write two keys, or a type's, can be a game's too

- Overclocking, multisampling, sprite filtering, the custom ratio and the shared cards use the global setting as a
  whole and are marked when the game sets any of their keys
- A controller type's settings go through the shared rows
```

---

### Task 6: Achievements' rows as settings

**Files:**
- Modify: `settings/AchievementsSettings.kt` (the data class becomes `Setting`s), `app/AchievementsPage.kt`,
  `ui/AchievementsScreen.kt`, `MainActivity.kt`
- Test: `test/…/app/AchievementsPageTest.kt`, `androidTest/…/ui/AchievementsScreenTest.kt`

**Interfaces:**
- Produces: `object AchievementsSettings` with `HARDCORE`, `NOTIFICATIONS`, `LEADERBOARD_NOTIFICATIONS`, `SOUNDS`,
  `OVERLAYS`, `ENCORE`, `SPECTATOR`, `UNOFFICIAL` (`BoolSetting`s, `[Cheevos]`, the defaults today's `load` has),
  `UNLOCK_SECONDS`, `LEADERBOARD_SECONDS` (`IntSetting`s), `all`, `DURATIONS`, `fun messages(values): Boolean`
  (either notification key) and `fun withMessages(values, on: Boolean)` (both). `AchievementsPage.settings:
  SettingValues?`, `change(new: SettingValues)`. `@Composable fun AchievementRows(values: SettingValues, onChange:
  (SettingValues) -> Unit)`, the page's switch and duration rows, shared with the game's page (Task 8).
- In a running game (`LocalGameContext`): Hardcore's note is `NextStart.HARDCORE_NOTE`; while `hardcoreActive`,
  Encore, Spectator and Test unofficial achievements are disabled with "Change it before the game starts: now it would
  end hardcore mode."

- [ ] **Step 1: Write the failing tests:** the page's tests on `SettingValues` (`messagesWritesBothKeys`, and today's
  save-only-what-changed); `AchievementsScreenTest.inAHardcoreGameEncoreIsDisabled` and
  `aRunningGamesHardcoreSaysWhenItApplies`.
- [ ] **Step 2: Run** them: RED.
- [ ] **Step 3: Implement** the move; the global page's rows and texts stay as they are.
- [ ] **Step 4: Run** `AchievementsPageTest`, `AchievementsScreenTest`: PASS. Then `quick`.
- [ ] **Step 5: Commit**

```
Android: the achievements switches are settings rows, so a game can have its own

- AchievementsSettings becomes [Cheevos] Settings, read and saved as the other pages' are
- AchievementRows, shared with a game's page; the global page reads and works as before
- In a running game, hardcore says when it applies, and Encore, Spectator and Test unofficial achievements wait
  for the next start while hardcore mode is on, since changing them now would end it
```

---
### Task 7: `GameSettingsPages`: a game's pages read, saved and applied

**Files:**
- Create: `app/GameSettingsPages.kt`; Modify: `app/AppController.kt` (the instance, data-change rereads)
- Test: `test/…/app/GameSettingsPagesTest.kt` (new), `FakeCoreApi.kt`

**Interfaces:**
- Consumes: `SettingValues.readGame`, `LayerSettings`, the `CoreApi` game calls (Tasks 2–3).
- Produces:
  - `enum class GamePage(val label: String, val summary: String) { Console, Emulation, Graphics, Audio, Controllers,
    MemoryCards, Achievements }`, with `rows: List<Setting<*>>` (what its rows write; counted) and `settings` (what
    it reads: `rows` plus `Hardcore.settings` where the page reads them). Controllers' rows are dynamic (Task 9).
  - `data class GameSummary(val counts: Map<GamePage, Int>, val others: Int, val inputProfile: String?)` with
    `total`; `others` are the file's keys no page shows, without `UseGameSettingsForController` and
    `GameSettingsInitialized`.
  - `enum class GameQuestion { Copy, Clear }`.
  - `class GameSettingsPages(scope: CoroutineScope, core: CoroutineDispatcher, api: CoreApi, global: CoreSettings,
    layer: (String) -> LayerSettings)`, main thread only, with Compose state: `game: GameEntry?`, `running: Boolean`,
    `page: GamePage?` (null: the hub), `cameFrom: GamePage?`, `summary: GameSummary?`, `asking: GameQuestion?`,
    `names`, `sharedCards`, `vulkanFailed`, `rewindUsage`, `fun values(page: GamePage): SettingValues?`; and
    `open(game: GameEntry, running: Boolean)`, `close()`, `openPage(page)`, `closePage()`, `change(page, new:
    SettingValues)`, `ask(question)`, `cancelQuestion()`, `confirm()`, `reloadShown()`.
  - `change` saves what changed to `layer(key)` on `core`, then `api.applyGameSettings(key)` when `running`, then
    reads the summary again. `confirm` runs Copy or Clear the same way and reads the shown page again. Reads and
    changes are sequenced as `SettingsPages`'s are (a read that comes back after a change is dropped, and read again).
  - AppController calls `reloadShown()` where it calls `settingsPages.reloadShown` (a pull, a reload step).

- [ ] **Step 1: Write the failing tests** (`MemoryCoreSettings` for both layers):

```kotlin
@Test fun openReadsTheSummary() { /* game file: ResolutionScale, Region, [Foo] Bar → Graphics 1, Console 1, others 1 */ }
@Test fun aChangeSavesToTheGamesFileOnly() { }
@Test fun aRunningGamesChangeIsApplied() { /* fake records applyGameSettings(key) after the save */ }
@Test fun aLibraryChangeAppliesNothing() { }
@Test fun useGlobalOnTheLastRowEmptiesTheSummary() { /* total 0 */ }
@Test fun copyAsksFirst() { /* ask(Copy): nothing copied; confirm(): copyGlobalSettingsToGame(key), page reread */ }
@Test fun clearDeletesAndRereads() { }
@Test fun aPullReadsTheShownPageAgain() { /* the file changes under it; reloadShown(): new values, page kept */ }
@Test fun aReadAfterAChangeIsDropped() { /* as SettingsPagesTest's */ }
```

- [ ] **Step 2: Run** `GameSettingsPagesTest`: RED.
- [ ] **Step 3: Implement** it.
- [ ] **Step 4: Run** the JVM suite and `ktlintCheck`: PASS.
- [ ] **Step 5: Commit**

```
Android: a game's settings pages, read from its file over the global ones

- GameSettingsPages reads a page's global and game values, saves only what changed to the game's file, and applies
  it straight away when it's the running game
- The summary counts what each page sets, and the keys no page shows
- Copy global settings and Clear settings ask first; a pull reads the shown page again
```

---

### Task 8: The game's hub and pages

**Files:**
- Create: `ui/GameSettingsScreen.kt`, `ui/GameSettingsHost.kt`
- Modify: `MainActivity.kt` (nothing routed yet: Tasks 10 and 11 do)
- Test: `androidTest/…/ui/GameSettingsScreenTest.kt` (new), `FocusAuditTest.kt`, `OrientationLayoutTest.kt`

**Interfaces:**
- Consumes: `GameSettingsPages` (Task 7), the page screens with a game's values (Tasks 4–6), `CardSlotRows` (Task 5).
- Produces:
  - `GameSettingsScreen(game: GameEntry, summary: GameSummary?, running: Boolean, focusEntry: GamePage?, asking:
    GameQuestion?, onOpen: (GamePage) -> Unit, onAsk: (GameQuestion) -> Unit, onConfirm: () -> Unit, onCancel: () ->
    Unit, onBack: () -> Unit)`: `SettingsPage` titled "Game settings" with the game's title as the eyebrow; the
    serial and region; "3 settings of its own" (or "No settings of its own", and "and 4 this app doesn't show"); a
    `NavRow` per `GamePage`, its summary the page's count ("2 set for this game") or `GamePage.summary`; then Copy
    global settings and Clear settings (`ActionButton`s, Clear `Danger`). Each asks in an `OverlayPanel`: "Copy the
    global settings to <title>? Its own settings are replaced where the global ones are set." (Copy, Cancel focused)
    and "Clear <title>'s settings? Its file goes, with the N settings this app doesn't show." (Clear `Danger`, Cancel
    focused). The first entry is focused on opening, or `focusEntry`.
  - `GameSettingsHost(pages: GameSettingsPages, hardcoreActive: Boolean, onBack: () -> Unit)`: provides
    `LocalGameContext`, and shows the hub or the page: the existing `ConsoleScreen`, `EmulationScreen`,
    `GraphicsScreen`, `AudioScreen` with the game's values; a "Memory cards" page of `CardSlotRows(…, oneCardRow =
    false)`; an "Achievements" page of `AchievementRows`; `SettingsLoadingPage` until read. Controllers routes to Task
    9's screen. B from a page goes to the hub, focusing its entry; from the hub, `onBack`.

- [ ] **Step 1: Write the failing tests:**

```kotlin
@Test fun theHubSaysWhatTheGameSets() { /* "3 settings of its own", Graphics "2 set for this game" */ }
@Test fun copyAsksWithCancelFocused() { /* A on Copy: the panel, Cancel focused; B closes; nothing confirmed */ }
@Test fun clearConfirms() { }
@Test fun backFromAPageFocusesItsEntry() { }
@Test fun theHubHasNoGlobalPages() { /* no Interface, BIOS, Folders, Manage cards */ }
```

  `FocusAuditTest` and `OrientationLayoutTest` gain the hub, its two questions and a game's Graphics page.
- [ ] **Step 2: Run** them: RED.
- [ ] **Step 3: Implement** them.
- [ ] **Step 4: Run** the three classes: PASS. Then `quick`.
- [ ] **Step 5: Commit**

```
Android: a game's settings hub: its summary, Copy and Clear, and its pages

- The hub names the game, says what it sets, and lists its pages, each with its count
- Copy global settings and Clear settings ask in the window first, Cancel focused
- The pages are the global ones' rows, each able to use the global setting
```

---

### Task 9: Controllers for a game

**Files:**
- Create: `ui/GameControllersScreen.kt`
- Modify: `app/GameSettingsPages.kt` (the Controllers page's values), `android_host.{h,cpp}`, `jni_bridge.cpp`,
  `core/NativeLibrary.kt`, `app/CoreApi.kt`, `ui/PortScreen.kt` (other types' inputs), `ui/GameSettingsHost.kt`
- Test: `androidTest/…/ui/GameControllersScreenTest.kt` (new), `androidTest/…/core/ControllerBindingsTest.kt`,
  `GameSettingsAccessTest.kt`, `test/…/app/GameSettingsPagesTest.kt`, `FocusAuditTest.kt`, `OrientationLayoutTest.kt`

**Interfaces:**
- Produces (`NativeLibrary`/`CoreApi`): `updateGameControllers(key)`: sets `UseGameSettingsForController = true` when
  `HasControllerKeys`, deletes it otherwise, and gives each controller on a pad whose type the game sets that type's
  default bindings in `settings.ini` where it has none (`AddDefaultBindings` taking the type), then
  `ReloadInputBindings` if any were added.
- Produces (host): at a game's start, each connected controller gets its pad's type in effect
  (`g_settings.controller_types`) defaults the same way. `GetPadBindings` lists, after the port's own type's inputs,
  those of the other types games' files set for that pad (files whose controller keys count) that it doesn't have,
  each with `category` = the type's display name. `GetOfferedPorts` reads `MultitapMode` only from those files.
- Produces (Kotlin): `GamePage.Controllers`' values, read on `core`: `PortSettings.MULTITAP`, each offered pad's `Type`
  (`NameSetting("PadN", "Type", Controller's default, NameList.ControllerType)`) and its effective type's settings
  (`PortSettings.settings`); with the flag off, all read as unset; with `getGameInputProfile` set, read-only.
  `change` on this page calls `updateGameControllers(key)` after saving, and reads the page again when a type changed.
  The pads offered are the game's multitap's: Ports 1 and 2, or 1A–1D and 2A–2D as `ControllersPage.portLabel`.
- Produces (`GameControllersScreen(values: SettingValues, names: CoreNames, typeSettings: Map<Int, List<TypeSetting>>,
  inputProfile: String?, onChange, onBack)`): Multitap, then each port's section: its type and its type's settings,
  through the shared rows. A note under the title: "Bindings are the global ones: Settings → Controllers." With an
  input profile: only "This game uses desktop's input profile "<name>", which this app doesn't use. Its controllers
  here are the global ones." as a focusable line.
- `PortScreen` groups the extra inputs under a `SettingsSection` per category ("NeGcon (a game's)").

- [ ] **Step 1: Write the failing tests:**

```kotlin
// ControllerBindingsTest
@Test fun aGamesTypeGetsItsDefaults() { /* game sets [Pad1] Type = NeGcon; updateGameControllers: settings.ini [Pad1] SteeringLeft has Android-0's */ }
@Test fun theFlagFollowsTheKeys() { /* the last key unset: no UseGameSettingsForController */ }
@Test fun thePortPageListsAGamesTypesInputs() { /* SteeringLeft with category "NeGcon" */ }
@Test fun aFileWithoutTheFlagOffersNoMultitap() { }
// GameControllersScreenTest
@Test fun aGamesPortTypeCanUseGlobal() { }
@Test fun multitapOffersItsPorts() { /* Port1Only set: 1A–1D and 2 */ }
@Test fun anInputProfileMakesThePageReadOnly() { }
// GameSettingsPagesTest
@Test fun controllerKeysCountOnlyWithTheFlag() { }
```

  `FocusAuditTest` and `OrientationLayoutTest` gain the page and its read-only form.
- [ ] **Step 2: Run** them: RED.
- [ ] **Step 3: Implement** them.
- [ ] **Step 4: Run** the classes, `PortScreenTest`, `ControllersScreenTest`, `common-tests`: PASS. Then `quick`.
- [ ] **Step 5: Commit**

```
Android: a game's controllers: multitap, each port's type and its settings

- Bindings stay global: Android sets UseGameSettingsForController itself, with only these keys in the game's file
- A type a game picks gets each controller's default bindings in settings.ini, and the Controllers page lists its
  inputs on that port for remapping
- A game whose file names desktop's input profile shows its controllers read-only, saying why
- Multitap's ports are offered for a game's file only when its controller keys count
```

---
### Task 10: From the library

**Files:**
- Modify: `ui/LibraryScreen.kt`, `ui/GameTile.kt`, `ui/GameListRow.kt`, `app/AppController.kt` (`Screen.GameSettings`,
  `openGameSettings`, `closeGameSettings`), `MainActivity.kt`
- Test: `androidTest/…/ui/LibraryScreenTest.kt`, `OrientationLayoutTest.kt`, `FocusAuditTest.kt`

**Interfaces:**
- Consumes: `GameSettingsHost` (Task 8), `GameSettingsPages.open` (Task 7).
- Produces: `LibraryScreen(…, onGameSettings: (GameEntry) -> Unit = {})`: Y (`Key.ButtonY`) on a focused tile or row
  with a non-blank `serial`, or a long press, calls it; a `KeyHints` footer "A Play", "Y Game settings" (the latter
  only while a game is focused). `AppController.openGameSettings(game)`: only from `Screen.Library` with
  `settingsReady` and a non-blank key; `gameSettingsPages.open(game, running = false)`; `Screen.GameSettings`.
  `closeGameSettings()`: back to the library, the game focused (`lastGamePath` kept as is; a `focusPath` of that
  game). `PadGlyphs` gets `options` (`Button100`, Y).

- [ ] **Step 1: Write the failing tests:** `yOnATileOpensItsSettings`, `aLongPressOpensItsSettings`,
  `aBlankKeyHasNoGameSettings`, `theFooterSaysYGameSettings`; `OrientationLayoutTest` with the footer in both
  orientations.
- [ ] **Step 2: Run** them: RED.
- [ ] **Step 3: Implement** them.
- [ ] **Step 4: Run** `LibraryScreenTest`, `OrientationLayoutTest`, `FocusAuditTest`: PASS. Then `quick`.
- [ ] **Step 5: Commit**

```
Android: a game's settings open from its tile in the library

- Y on a game, or a long press, opens its settings; the library's footer says so
- Back goes to the library with that game focused
- Not while a game is under way, as the global settings
```

---

### Task 11: From the in-game menu

**Files:**
- Modify: `ui/GameMenu.kt` (the Settings row), `ui/GameScreen.kt` (the host over the game), `app/InGameMenu.kt`
  (`GameMenuState.settings`, `cameFromSettings`; `openSettings`, `closeSettings`), `app/AppController.kt`,
  `MainActivity.kt`
- Test: `androidTest/…/ui/GameMenuTest.kt`, `test/…/app/InGameMenuTest.kt`, `androidTest/…/core/GameCallsTest.kt`,
  `FocusAuditTest.kt`, `OrientationLayoutTest.kt`

**Interfaces:**
- Consumes: `GameSettingsHost`, `GameSettingsPages.open(game, running = true)`.
- Produces: a **Settings** row (`GsIcons.settings`) after Achievements, before the divider, enabled once booted.
  `GameMenuActions.openSettings()` opens the running game's settings (`AppController.runningGame`, its key); the game
  stays paused under them, the menu's layer still open (`promptOpen`), so the pads drive the pages. `GameScreen`
  shows `GameSettingsHost` instead of `GameMenu` while `GameMenuState.settings`, over the surface, which stays.
  B from the hub calls `closeSettings()`: the menu again, the Settings row focused (`cameFromSettings`). Mode resumes
  from the pages as from the menu. `hardcoreActive` is `GameMenuState.hardcore`.

- [ ] **Step 1: Write the failing tests:** `GameMenuTest.settingsRowOpensTheGamesSettings` and
  `backFromSettingsFocusesItsRow`; `InGameMenuTest.settingsOpenForTheRunningGamesKey` and `modeResumesFromSettings`;
  `GameCallsTest.aChangeInTheMenuAppliesAtOnce` (boot; change `[Main] EmulationSpeed` for the key through
  `GameSettingsPages` with `running = true`; `getRunningSettingForTest` reads it without a restart).
  `FocusAuditTest` and `OrientationLayoutTest` gain the menu's row and the hub over a game.
- [ ] **Step 2: Run** them: RED.
- [ ] **Step 3: Implement** them.
- [ ] **Step 4: Run** the classes: PASS. Then `quick`.
- [ ] **Step 5: Commit**

```
Android: the in-game menu's Settings: the running game's own, applied at once

- A Settings row in the menu opens the game's settings over the paused game; never the global pages, Folders or
  the card editor
- Each change is saved to the game's file and applied straight away; what only applies at the next start says so
- Back returns to the menu, its Settings row focused
```

---

### Task 12: Docs and the full tier

**Files:**
- Modify: `docs/android.md` ("Changing settings" → "Settings for each game": where they open, the file and key, use
  global, the mark, Copy and Clear, controllers, desktop's files and what Android ignores in them (and that a file
  under another disc's serial of an M3U or name group is left alone and not read here), what waits for the next start; a walk-through checklist step), `docs/design-system.md` (the game hub, the library footer, the menu's
  Settings row)

- [ ] **Step 1:** Write the docs.
- [ ] **Step 2:** Run `GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh full` in the
  background; read the result XML (every shard and the OpenGL ES part): all pass, none skipped for missing data.
- [ ] **Step 3:** Run `common-tests` (desktop), build `duckstation-qt`, `./gradlew testDebugUnitTest ktlintCheck
  lintDebug`.
- [ ] **Step 4:** Walk through on the emulator: a tile's settings (Y), a Graphics change and its mark, Use global back
  to an empty file (gone from `gamesettings/`), Copy and Clear, a game's controller type, the in-game Settings with a
  live change (resolution) and a next-start note (Region). Record it in `docs/android.md`.
- [ ] **Step 5: Commit**

```
Docs: settings for each game on Android

- Where a game's settings open, its file, Use global and the mark, Copy and Clear, its controllers
- What Android ignores in desktop's game files, and what waits for the next start
- The walk-through's results
```
