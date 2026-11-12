# Native Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `android/app/src/cpp/android_host.cpp` and `jni_bridge.cpp` into one file per domain, moving every
body byte for byte, with a script that proves nothing changed.

**Architecture:**
- **The proof:** a move-check script compares the top-level items of the two original files, at the branch's base,
  with those of every file after the split.
- **Shared state:** state several domains use moves to one internal header and one `.cpp`, in today's order.
- **The commits:** each domain comes out in its own commit, and each one builds.

**Tech Stack:** C++20, the NDK through Gradle's CMake build, Python 3 for the script (unittest, as
`android/scripts/tests/test_check_jni_keeps.py`).

**Spec:** `docs/superpowers/specs/2026-11-12-android-native-split-design.md`

## Global Constraints

- **No body changes.** Only linkage (`static`, anonymous namespaces, `extern` declarations in an internal header),
  include lines, namespace wrappers and per-file `Log_SetChannel` lines may differ. Comments move with their code, as
  they are.
- **Shared state** is defined once, in one `.cpp`, in today's order. State one domain uses stays private to its file.
- **Each new file uses its origin's log channel,** so the logs read the same.
- **One commit per domain,** each building with `./gradlew -q assembleDebug`, and each with the move-check script
  clean.
- **Comments that name a split file** are updated to the file that now holds the code: `android_host.h`,
  `proguard-rules.pro`, `check-jni-keeps.py`, `AppController.kt`, `MemcardSync.kt` and `TouchPad.kt`. Nothing else in
  them changes.
- **Final checks:**
  - `./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`;
  - `assembleRelease`, then `check-jni-keeps.py` on its `seeds.txt`;
  - `run-native-tests.sh` on `emulator-5580`;
  - `GOOSE_TEST_SERIALS=emulator-5580,emulator-5582 run-instrumented-tests.sh quick`, release part included. Run it
    from a detached worktree of the head, with `local.properties` and the emusync AAR copied in.
  - The exported symbols of `libgoosestation.so` are the same before and after.
- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A shared variable defined in two files.** Two copies would each hold half the state. The script fails on any
   global defined more than once.
2. **A helper copied into two files instead of shared.** The copies would drift. The script fails on any item that
   appears more often after the split than before.
3. **A native lost or doubled.** The app would only find out at the call, as an `UnsatisfiedLinkError`. The script
   compares the set of `NATIVE_LIBRARY_FUNCTION` names, and Task 6 compares the library's exported symbols.
4. **Shared state built in a different order,** because it's now spread over several files. All of it sits in one
   `.cpp`, in today's order, and the script checks that order.
5. **A new file logging under another channel.** The logs would then name the wrong place. The script checks each new
   file's `Log_SetChannel` against its origin's.

Tests: 1, 2, 4 and 5 are in the script's own tests (Task 1); 3 is in Task 1 and Task 6.

---

### Task 1: The move-check script, test-first

**Files:**
- create `android/scripts/check-native-split.py`;
- test: create `android/scripts/tests/test_check_native_split.py`.

**Interfaces (produced, run by every later task):**

```
check-native-split.py BASE_REF CPP_DIR
  # Reads android_host.cpp and jni_bridge.cpp at BASE_REF (git show), and every .cpp/.h in CPP_DIR that the split
  # made or changed (the two originals, android_host_*.cpp, jni_*.cpp, android_host_state.*, jni_classes.*).
  # Exits 0 when the items match, else prints each difference and exits 1.
```

- **What an item is:** a top-level unit, found by splitting at column 0 and balancing braces and parentheses outside
  strings and comments. That covers a function with its leading comment block, a global with its comment, a type, a
  macro, or a free comment block.
- **Normalising an item:** drop a leading `static `, and drop `namespace … {`, `} // namespace …` and `extern "C" {`
  lines. Each item's text is otherwise exact.
- **Ignored lines:** `#include`, `#pragma once` and `Log_SetChannel` lines, plus declarations in the new internal
  headers that a shared item needs.
- **Checks:**
  - the before and after multisets of items are equal;
  - no global is defined in two files;
  - every `NATIVE_LIBRARY_FUNCTION` name appears once before and once after;
  - the shared statics keep their relative order;
  - each new file's `Log_SetChannel` names a channel its origin file used.

- [ ] **Step 1: Write the failing tests** in `test_check_native_split.py`, over small fixture sources in temporary git
  repos:
  - `aPureMoveIsClean`: two functions and a static, moved to two files.
  - `aChangedBodyFails`: one character changed in a moved body; the report names the item.
  - `aDroppedItemFails` and `aDuplicatedHelperFails` (Review Focus 2).
  - `aGlobalDefinedTwiceFails` (Review Focus 1).
  - `aLostNativeFails` (Review Focus 3).
  - `sharedStateOutOfOrderFails` (Review Focus 4).
  - `aNewLogChannelFails` (Review Focus 5).
  - `staticAndNamespaceLinesAreIgnored`.
- [ ] **Step 2: Run** `python3 android/scripts/tests/test_check_native_split.py`. Expected: it fails, because the
  script doesn't exist.
- [ ] **Step 3: Write the script.**
- [ ] **Step 4: Run the tests.** Expected: all pass. Then run the script on the unsplit tree:
  `python3 android/scripts/check-native-split.py HEAD android/app/src/cpp`. Expected: exit 0, nothing moved.
- [ ] **Step 5: Commit:** "Android: a script that checks the native split moved code without changing it".

### Task 2: Shared state, out of `android_host.cpp`

**Files:**
- create `android/app/src/cpp/android_host_state.h` and `android_host_state.cpp`;
- modify `android_host.cpp` and `CMakeLists.txt`.

**Interfaces (produced):**
- `android_host_state.h` declares, in `namespace AndroidHost` (in an inner `namespace State` if a name would clash),
  every file-level static and anonymous-namespace type and helper that two or more of Task 3's domains use. That
  includes `InputEvent` and `ConnectedDevice` if shared state needs them.
- `android_host_state.cpp` defines those statics, in today's order, with today's initialisers. Find which are shared
  by checking each static's uses against Task 3's domain table.

- [ ] **Step 1: Move** the shared statics and helpers. Single-domain ones stay in `android_host.cpp` until their
  domain's commit.
- [ ] **Step 2: Build and check:** `./gradlew -q assembleDebug`, then `check-native-split.py <base> …`. Expected: the
  build succeeds and the script is clean.
- [ ] **Step 3: Commit:** "Android: android_host's shared state, declared once in android_host_state".

### Task 3: `android_host.cpp` by domain, one commit each

**Files:** create `android_host_<domain>.cpp` for each row below; modify `android_host.cpp` and `CMakeLists.txt`.

| File | From (today's lines) | First and last function |
|---|---|---|
| `android_host_settings.cpp` | ~493–720 | `ReloadSettings` … `IsVulkanRenderer` |
| `android_host_game_files.cpp` | ~720–1365 | game settings path helpers … `UpdateGameControllers` (post-processing and favourites included) |
| `android_host_folders.cpp` | ~1365–1535 | `AddRunningTypesDefaults` … `MissingDiscMessage` |
| `android_host_boot.cpp` | ~1536–1768 | `SetListedGames` … `SetPaused` |
| `android_host_states.cpp` | ~1768–2268 | `GetSaveStateTimes` … `SaveScreenshot` (discs, patch codes, resume, analog, reset) |
| `android_host_input.cpp` | ~2268–2630 | `SurfaceChanged` … `ProcessInputQueue` (touch pad included) |
| `android_host_controllers.cpp` | ~2630–3673 | `FindDescriptorIndex` … `CancelBindingCapture` |
| `android_host_host.cpp` | ~3673–end | `Host::LoadSettings` … `InputManager` hooks |

- What stays in `android_host.cpp`: the `Host::` memory card sync secret hooks, `SetShaderPacksFolder`, `Initialize`,
  `InitializeConfig`, `SetDefaultSettings`, `LoadFolders`, `SetGameFolders`, `GetBIOSDirectory` and `RefreshGameList`.
- A function between two rows goes with the domain whose state it uses. The commit message says which.
- **Ruling:** a few test hooks sit inside another domain's range: `PressHotkeyForTest`, `IsFastmemAvailable` and
  `ReloadPostProcessingShaders`. They go where they sit today, since their file doesn't change what they do.

- [ ] **Step 1, for each row in order:**
  - move the functions, and the statics only that domain uses;
  - add the file to `CMakeLists.txt`;
  - build with `./gradlew -q assembleDebug`;
  - run `check-native-split.py` (expected: clean);
  - commit "Android: android_host's <domain>, in android_host_<domain>.cpp".
- [ ] **Step 2:** after the last row, `android_host.cpp` holds only initialisation. The script is clean.

### Task 4: The JNI cache, out of `jni_bridge.cpp`

**Files:** create `android/app/src/cpp/jni_classes.h` and `jni_classes.cpp`; modify `jni_bridge.cpp` and
`CMakeLists.txt`.

**Interfaces (produced):**
- **`jni_classes.h`:**
  - `NATIVE_LIBRARY_FUNCTION(ret, name)`, unchanged;
  - `extern` declarations of the cached classes, method IDs and callback IDs;
  - the `Notify*` declarations `android_host.h` doesn't already have;
  - `WithCallbacks` and `WithCallbacksOr`;
  - every shared marshalling helper (`To…` / `From…` functions defined in `jni_bridge.cpp`, as they are).
- **`jni_classes.cpp`:** their definitions, in today's order, with `JNI_OnLoad` and the `Notify*` bodies.

- [ ] **Step 1: Move,** then build and run the script. Expected: clean.
- [ ] **Step 2: Commit:** "Android: the JNI class and callback cache, in jni_classes".

### Task 5: `jni_bridge.cpp`'s natives by domain, one commit each

**Files:** create `jni_<name>.cpp` for each row below; delete `jni_bridge.cpp` in the last commit; modify
`CMakeLists.txt`.

| File | Natives (today's order) |
|---|---|
| `jni_settings.cpp` | `setShaderPacksFolder` … `getSettingNames`, `getSharedCardNames`, `getRewindMemoryUsage`, `applySettings`, `isVulkanFailed` |
| `jni_game_files.cpp` | `hasGameSetting` … `applyGameSettings`, and the post-processing natives |
| `jni_covers.cpp` | `downloadCovers` … `httpTestFetchQueued` |
| `jni_achievements.cpp` | `getAchievementsAccount` … `achievementsNetworkBack` |
| `jni_memcard_sync.cpp` | `isMemcardSyncAvailable` … `setMemcardSyncPeriodForTest` |
| `jni_folders.cpp` | `getMemoryCardPaths` … `getCacheFolder` |
| `jni_bios.cpp` | `importBios`, `getBiosImages`, `getAutoBios` |
| `jni_library.cpp` | `refreshGameList`, `getGameList` and its playlist cache, `setCallbacks` |
| `jni_boot.cpp` | `startGame`, `startGameFromState`, `shutdownGame`, `setPaused` |
| `jni_states.cpp` | `getSaveStateTimes` … `toggleAnalogMode`, `saveScreenshot` |
| `jni_input.cpp` | `setTouchPad` … `releaseAllInputs`, `decodeUtf8ForTest` |
| `jni_controllers.cpp` | `getPadBindings` … `setListedKeyForTest`, the hotkey and binding-capture natives |
| `jni_shader_packs.cpp` | `getShaderPacks` … the shader pack test natives |
| `jni_memcard_editor.cpp` | the memory card editor's natives |

- **Ruling:** `decodeUtf8ForTest` goes with input. It tests `ToJString`, which every file uses, so it has no domain,
  and input is where it sits today.
- `setCallbacks` goes with the library, as the game list is the first thing it serves. A native that sits between two
  rows goes with the host file it calls, and the commit says which.

- [ ] **Step 1, for each row:** move, add to CMake, build, run the script (expected: clean), and commit "Android: the
  <name> natives, in jni_<name>.cpp".
- [ ] **Step 2:** the last commit deletes the empty `jni_bridge.cpp`. The script is clean.

### Task 6: Comments, and the final checks

**Files:** `android_host.h`, `android/app/proguard-rules.pro`, `android/scripts/check-jni-keeps.py`, and the Kotlin
comments in `AppController.kt`, `MemcardSync.kt` and `TouchPad.kt`.

- [ ] **Step 1: Update** each comment that names `jni_bridge.cpp` or `android_host.cpp` to the file that now holds
  that code. Find them with `grep -rn "jni_bridge\|android_host.cpp" android docs/android.md`. Change only the file
  name.
- [ ] **Step 2: Commit:** "Android: comments name the files the native split moved code to".
- [ ] **Step 3: The exported symbols** (Review Focus 3).
  - Build `libgoosestation.so` for arm64 at the branch's base and at its head.
  - Compare `llvm-nm -D --defined-only` of both, filtered to `Java_` and `JNI_OnLoad`.
  - Expected: identical.
- [ ] **Step 4: The final checks** from the Global Constraints, the quick tier in the background. Expected: all green,
  the tier's XML read for counts, and its release part says "launched, loaded the core and asked for the update".
