# Android Stage 1, Plan 1: the emulator on Android — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a debug APK that boots a PS1 game on an arm64 Android device (the Android emulator, then the AYN Thor),
with picture, sound and correctly mapped controller input, launched from a bare list of games.

**Architecture:** the existing C++ core is built by the NDK into `libgoosestation.so`, together with the Android
pieces upstream left out (file layer, shared memory, AAudio, EGL, an Android input source) and a new host layer
(`android/app/src/cpp`) that runs the core on its own thread behind a small JNI API. A Kotlin + Compose app shows a
game list and a game screen with a `SurfaceView`, and forwards controller events. Storage in this plan is
app-specific only, filled with `adb push`. The Storage Access Framework comes in Plan 2.

**Tech Stack:** C++20, CMake, Android NDK (Clang), Kotlin, Jetpack Compose, Material 3, Gradle (AGP), AAudio,
Vulkan and EGL/GLES, GoogleTest.

**Spec:** [docs/superpowers/specs/2026-10-25-android-frontend-design.md](../specs/2026-10-25-android-frontend-design.md).
Stage 1 is split into three plans, each ending in something testable on the device:
1. **This plan:** the emulator on Android.
2. **Storage, setup and the library:** SAF grants, `content://` in the file layer with sibling resolution, the BIOS
   copy, the data folder mirror, first-run setup, the cover grid and list. Written after this plan, using what it
   learns.
3. **Settings and controls:** the minimum settings pages, the remap screen, the Back prompt with its other
   triggers, controller focus everywhere, and the roadmap wording fix.

## Global Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app. Dolphin and ARMSX2
  are reference for approach only; copy no code.
- New files: `// SPDX-FileCopyrightText: 2026 GooseStation contributors` and `// SPDX-License-Identifier: GPL-3.0`
  (or `#` comments for scripts and Gradle files).
- Android: `minSdk 29`, `arm64-v8a` only, application ID and package `io.github.ilyalissoboi.goosestation`, app name
  "GooseStation".
- Versions: the latest stable NDK, Android Gradle Plugin, Gradle, Kotlin, Compose BOM and compile/target SDK
  available when the task runs. Pin every one (`gradle/libs.versions.toml`, `ndkVersion`,
  `gradle-wrapper.properties`) and record them in `docs/android.md`. JDK 17.
- Dependency versions for Android are the same as `scripts/deps/build-dependencies-linux.sh`.
- Desktop must stay green: after every task that touches `src/` or `CMakeModules/`, the macOS build
  (`cmake --build build`) succeeds with no new warnings in changed files, and `build/bin/common-tests` passes.
- Core changes for Android sit behind `__ANDROID__` / `if(ANDROID)` or shared abstractions. No other platform's
  behaviour changes.
- `libgoosestation.so` links with `-Wl,--no-undefined` (missing symbols fail the build) and
  `-Wl,-z,max-page-size=16384` (loads on 16 KB-page devices).
- BIOS images and games are never committed. Test assets come from `~/work/goose-test` (`ps-30j.bin`, two CHD
  discs) and the repo's `extras/padtest/padtest.psexe`, pushed with `adb`.
- Commits: the user reviews every commit message in chat before it is committed. Work continues uncommitted
  between checkpoints; propose commits after Task 2, Task 5 and Task 8, one per task, with messages in the log's
  style (`Android: …`, `Util: …`, `Common: …`, `Build: …`).

## Review Focus

1. **Face-button layout.** Mapping assumes `KEYCODE_BUTTON_A` is the bottom face button (Xbox layout). A device
   whose pad reports by Nintendo labels would swap Cross/Circle and Square/Triangle. Check on the Thor with
   padtest (Task 8); if swapped, record it for Plan 3's remap screen rather than special-casing the Thor.
2. **Triggers as axes and as buttons.** Some pads report L2/R2 as `AXIS_LTRIGGER`/`AXIS_RTRIGGER`, some as
   `AXIS_BRAKE`/`AXIS_GAS`, some as `KEYCODE_BUTTON_L2`/`R2`, and some as several at once. Each must work, and a
   pad sending both must not press twice or stick. (Task 5 tests, Task 8 check.)
3. **Surface loss mid-game.** Home, screen off or a configuration change destroys the `SurfaceView`'s surface
   while the core is rendering. It must go surfaceless and recover without a crash or a black screen. (Task 8.)
4. **Controller disconnect mid-game.** Every button and axis of the lost device is released (no stuck input), and
   a reconnected pad maps back to Port 1. (Task 6, Task 8.)
5. **Page size.** The core is built with 16 KB pages (`HOST_PAGE_SIZE=16384`), which also work on 4 KB-page
   devices; on a device with pages larger than 16 KB, fastmem must be turned off at startup instead of crashing.
   (Task 6.) *Changed during implementation: the plan first built with 4 KB pages, but a 16 KB build runs fastmem
   on both 4 KB and 16 KB devices.*

---

### Task 1: Android toolchain and dependency build

**Files:**
- Create: `scripts/deps/build-dependencies-android.sh`
- Create: `docs/android.md`

**Interfaces:**
- Produces: a dependency prefix (default `~/work/goosestation-deps-android`, overridable with the script's first
  argument) with `include/`, `lib/` and `lib/cmake` for zstd, libpng, libjpeg-turbo, libwebp, FreeType (with
  HarfBuzz, as on Linux), lunasvg, cpuinfo, SoundTouch, shaderc and SPIRV-Cross, built for `arm64-v8a`, API 29.
  shaderc and SPIRV-Cross also install shared libraries named `libshaderc_shared.so` and
  `libspirv-cross-c-shared.so` (unversioned, as APKs require).

- [ ] **Step 1: Install the SDK and NDK.** `brew install --cask android-commandlinetools`, then `sdkmanager` for
  `platform-tools`, the chosen `platforms;android-N`, `build-tools;N`, `ndk;<version>`, `cmake;<version>`,
  `emulator` and an arm64 system image (`system-images;android-N;google_apis;arm64-v8a`). Create an AVD named
  `goose-arm64`. Write the exact commands and versions into `docs/android.md`.
- [ ] **Step 2: Write the script** on the pattern of `build-dependencies-linux.sh`: the same versions, URLs,
  `SHASUMS` check, `-skip-download`/`-skip-cleanup` flags, one CMake + Ninja build per library. Every CMake call adds
  `-DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM=29`. No SDL3, Qt, CURL, discord-rpc or libbacktrace.
- [ ] **Step 3: Run it.**
  Run: `scripts/deps/build-dependencies-android.sh` (with `ANDROID_NDK_HOME` set)
  Expected: exit 0; `file <prefix>/lib/libzstd.a <prefix>/lib/libshaderc_shared.so` reports ARM aarch64.

### Task 2: Android as a CMake platform, and the library target

**Files:**
- Modify: `CMakeModules/GooseStationUtils.cmake` (`detect_operating_system`, ~20–30)
- Modify: `CMakeLists.txt` (PIC, ~38–42)
- Modify: `CMakeModules/GooseStationDependencies.cmake` (SDL3, DiscordRPC, CURL, Libbacktrace behind `NOT ANDROID`;
  shaderc/SPIRV-Cross headers available even with `ENABLE_VULKAN=OFF`)
- Modify: `src/util/CMakeLists.txt` (remove the stale `android/app/src/cpp` include at ~137–139)
- Modify: `src/CMakeLists.txt` (`if(ANDROID) add_subdirectory(${CMAKE_SOURCE_DIR}/android/app/src/cpp ...)`)
- Modify: `.gitignore` (drop `/android` at ~46; ignore `android/.gradle`, `android/**/build`, `android/local.properties`)
- Create: `android/app/src/cpp/CMakeLists.txt`, `android/app/src/cpp/jni_onload.cpp`
- Create: `android/scripts/configure-native.sh` (configures a standalone NDK build in `build-android/` for tests)

**Interfaces:**
- Produces: CMake target `goosestation` (SHARED, output `libgoosestation.so`) linking `core common util imgui
  minizip scmversion android log EGL aaudio`, with the link flags from Global Constraints. In this task it holds
  only `JNI_OnLoad` returning `JNI_VERSION_1_6`.
- Produces: `android/scripts/configure-native.sh [BUILD_TESTS=ON]` running CMake with the NDK toolchain,
  `-DBUILD_QT_FRONTEND=OFF -DUSE_EMUSYNC=OFF -DHOST_PAGE_SIZE=16384 -DCMAKE_FIND_ROOT_PATH=<deps prefix>`.
  *Changed during implementation: 16384 rather than 4096, so fastmem works on 16 KB-page devices too (Review
  Focus 5).*

- [ ] **Step 1: Make CMake accept Android** and gate the desktop-only dependencies as listed.
- [ ] **Step 2: Add the `goosestation` target** and the configure script.
- [ ] **Step 3: Configure for Android.**
  Run: `android/scripts/configure-native.sh`
  Expected: configure succeeds. Building `goosestation` may still fail on missing Android code; record the
  undefined-symbol list in the ledger, since it is Tasks 3–6's checklist.
- [ ] **Step 4: Desktop still builds.**
  Run: `cmake --build build && cmake --build build --target common-tests && build/bin/common-tests`
  Expected: build succeeds; all tests pass.
- [ ] **Step 5: Checkpoint** — propose the commit for Tasks 1–2 in chat; commit after approval.

### Task 3: `common` on Android

**Files:**
- Modify: `src/common/file_system.cpp` (the `#ifndef __ANDROID__` at ~48–1256 and `#elif !defined(__ANDROID__)` at
  ~2206 become ordinary POSIX code on Android)
- Modify: `src/common/memmap.cpp` (~30 and ~601–828: the POSIX code on Android, with shared memory from
  `ASharedMemory_create` instead of `shm_open`)
- Modify: `src/common/crash_handler.cpp` (~187–189, ~370: the stub handler on Android, no `<backtrace.h>`)
- Modify: `src/common-tests/file_system_tests.cpp`
- Create: `android/scripts/run-native-tests.sh` (builds `common-tests` with `configure-native.sh BUILD_TESTS=ON`,
  `adb push`es it to `/data/local/tmp`, runs it, returns its exit code)

**Interfaces:**
- Produces: on Android, the full `FileSystem` and `Path` API and `MemMap` API, as on Linux. `GetProgramPath`
  returns an empty string on Android, and `GetRootDirectoryList` returns an empty list.

- [ ] **Step 1: Write POSIX file-system tests** in `file_system_tests.cpp` (outside the `_WIN32` block), in a temp
  directory: `CreateDirectory` + `DirectoryExists`; `WriteStringToFile` + `ReadFileToString` round trip;
  `StatFile` size equals bytes written; `FindFiles` with `FILESYSTEM_FIND_FILES` finds exactly the two files
  created; `RenamePath` moves a file (old path gone, new path exists); `DeleteFile` removes it.
- [ ] **Step 2: They pass on macOS** (the code exists there): `build/bin/common-tests --gtest_filter='FileSystem.*'`
  → PASS.
- [ ] **Step 3: They fail on Android.** Start the `goose-arm64` emulator, run `android/scripts/run-native-tests.sh`
  → link errors for `FileSystem::`/`Path::` symbols (RED).
- [ ] **Step 4: Re-enable the code on Android**, as listed under Files.
- [ ] **Step 5: Run on Android** — `android/scripts/run-native-tests.sh` → all tests pass, including `FileSystem.*`
  and `Path.*`.
- [ ] **Step 6: Desktop still green** (Global Constraints command).

### Task 4: `util` on Android: platform, audio, EGL

**Files:**
- Create: `src/util/platform_misc_android.cpp` (`PlatformMisc::*` as no-ops: no screensaver inhibit, no sound
  effects), built only on Android
- Create: `src/util/http_downloader_android.cpp`: `HTTPDownloader::Create` returns `nullptr` with an `Error`
  saying downloads aren't supported yet
- Create: `src/util/aaudio_audio_stream.cpp`: `AudioStream::CreateAAudioAudioStream`
- Modify: `src/util/audio_stream.cpp`: `CreateOpenSLESAudioStream` returns `nullptr` with an `Error`
- Create: `src/util/opengl_context_egl_android.h/.cpp`: `ContextEGLAndroid`, a subclass of `OpenGLContextEGL`
  overriding `GetPlatformDisplay` and `CreatePlatformSurface` for `ANativeWindow`
- Modify: `src/util/opengl_context.cpp` (~22: include the header from `src/util`)
- Modify: `src/common/dynamic_library.cpp` (~80–87) or `src/util/gpu_device.cpp` (~1370): on Android load
  `libspirv-cross-c-shared.so` unversioned
- Modify: `src/util/CMakeLists.txt` (build the new files on Android)
- Create: `android/app/src/cpp/fullscreen_ui_stubs.cpp`: no-op definitions of the `FullscreenUI::` functions the
  linker reports missing (they're compiled out on Android); each returns `false`/does nothing

**Interfaces:**
- Produces: `AudioStream::CreateAAudioAudioStream(u32 sample_rate, const AudioStreamParameters&, Error*)` returning
  a stream that opens AAudio in low-latency, shared mode, 16-bit, `m_output_channels` channels, with a data callback
  calling `ReadFrames`, and `SetPaused` starting/pausing the AAudio stream. It reopens the stream on
  `AAUDIO_ERROR_DISCONNECTED` (headphones plugged in or out).

- [ ] **Step 1: Write each file** as listed. Use `sdl_audio_stream.cpp` as the shape for the AAudio stream.
- [ ] **Step 2: Build for Android** — `cmake --build build-android --target goosestation`. Expected: the only
  undefined symbols left are `Host::*`, `InputSource::CreateAndroidSource` and the hotkey/keyboard symbols that
  Tasks 5–6 provide.
- [ ] **Step 3: Native tests on the emulator still pass**; desktop still green.

### Task 5: Android input source

**Files:**
- Create: `src/util/android_input_keys.h/.cpp`: pure mapping logic, no Android headers (keycode and axis numbers
  as named constants), so `common-tests` compiles it on desktop
- Create: `src/util/android_input_source.h/.cpp`: `InputSource::CreateAndroidSource()`
- Create: `src/common-tests/android_input_keys_tests.cpp` (add it and `../util/android_input_keys.cpp` to
  `src/common-tests/CMakeLists.txt`, `.vcxproj` and `.vcxproj.filters`)

**Interfaces:**
- Produces (namespace `AndroidInputKeys`):
  - `std::optional<GenericInputBinding> GetBindingForKeyCode(s32 keycode);`
  - `std::optional<GenericInputBinding> GetBindingForAxis(s32 axis, bool positive);`
  - `GenericInputBindingMapping GetDefaultMapping(u32 device_index);` — the binding strings for one device, used by
    `InputManager::MapController`
- Produces: `AndroidInputSource` with a thread-safe queue fed by
  `void QueueKeyEvent(s32 device_id, s32 keycode, bool pressed)`, `void QueueAxisEvent(s32 device_id, s32 axis,
  float value)`, `void QueueDeviceConnected(s32 device_id, std::string name, std::string descriptor)` and
  `void QueueDeviceDisconnected(s32 device_id)`, drained in `PollEvents` into `InputManager::InvokeEvents`.
  Devices are named `Android-<index>`, where the index is stable per descriptor for the process's life. Bindings
  use the generic helpers: `Button<keycode>`, `+Axis<n>`, `-Axis<n>`, `FullAxis<n>`.

- [ ] **Step 1: Write the failing tests** (suite `AndroidInputKeys`), with Android's values:
  - keys: `96` (BUTTON_A) → Cross, `97` (B) → Circle, `99` (X) → Square, `100` (Y) → Triangle, `102` (L1) → L1,
    `103` (R1) → R1, `104` (L2) → L2, `105` (R2) → R2, `106` (THUMBL) → L3, `107` (THUMBR) → R3, `108` (START) →
    Start, `109` (SELECT) → Select, `19/20/21/22` (DPAD_UP/DOWN/LEFT/RIGHT) → DPad Up/Down/Left/Right, `4` (BACK)
    → none, `110` (MODE) → none (both are menu triggers, not pad buttons).
  - axes: `0` (X) ± → LeftStick Left/Right, `1` (Y) ± → LeftStick Up/Down, `11` (Z) ± → RightStick Left/Right,
    `14` (RZ) ± → RightStick Up/Down, `17` (LTRIGGER) + → L2, `18` (RTRIGGER) + → R2, `23` (BRAKE) + → L2,
    `22` (GAS) + → R2, `15` (HAT_X) ± → DPad Left/Right, `16` (HAT_Y) ± → DPad Up/Down.
  - `GetDefaultMapping(0)` binds Cross to `Android-0/Button96` and L2 to `Android-0/+Axis17` as well as
    `Android-0/Button104`.
- [ ] **Step 2: Run to verify they fail** — `cmake --build build --target common-tests` → build error (RED).
- [ ] **Step 3: Implement `android_input_keys.cpp`.** Then implement `AndroidInputSource`:
  - `PollEvents` drains the queue;
  - axis values are passed as is (sticks −1..1, triggers 0..1), and a hat produces button presses/releases;
  - a trigger that reports both as an axis and as a key passes the larger of the two, so it can't stick or press
    twice;
  - a disconnect releases every key and axis it reported for that device before removing it.
- [ ] **Step 4: Tests pass on desktop and on the emulator** (`run-native-tests.sh`).
- [ ] **Step 5: Checkpoint** — propose commits for Tasks 3, 4 and 5 in chat; commit after approval.

### Task 6: Host layer, CPU thread and JNI API

**Files:**
- Create: `android/app/src/cpp/android_host.cpp/.h`: every `Host::` function a frontend must define (the list is in
  `src/duckstation-regtest/regtest_host.cpp`; Qt's `qthost.cpp` shows the real ones), plus `g_host_hotkeys` (empty
  in this plan) and `InputManager::ConvertHostKeyboard*` (no keyboard support yet)
- Create: `android/app/src/cpp/emu_thread.cpp/.h`: the CPU thread
- Create: `android/app/src/cpp/jni_bridge.cpp`: the JNI functions below (replaces `jni_onload.cpp`)

**Interfaces:**
- Consumes: `AndroidInputSource` queue functions (Task 5); `ContextEGLAndroid` and the AAudio stream (Task 4).
- Produces: Kotlin `object NativeLibrary` in package `io.github.ilyalissoboi.goosestation.core`, with
  `System.loadLibrary("goosestation")` and:
  - `external fun initialize(dataRoot: String, resourcesRoot: String): Boolean`
  - `external fun startGame(path: String)`, asynchronous; the result arrives through a callback
  - `external fun shutdownGame()`
  - `external fun setPaused(paused: Boolean)`
  - `external fun surfaceChanged(surface: Surface?, width: Int, height: Int, scale: Float)` (`null` means destroyed)
  - `external fun onKey(deviceId: Int, keyCode: Int, pressed: Boolean): Boolean` (returns whether it was consumed)
  - `external fun onAxis(deviceId: Int, axis: Int, value: Float)`
  - `external fun onDeviceConnected(deviceId: Int, name: String, descriptor: String)` and
    `external fun onDeviceDisconnected(deviceId: Int)`
  - callbacks on a Kotlin `interface NativeCallbacks` registered with `NativeLibrary.setCallbacks(...)`:
    `onGameStarted(title: String)`, `onGameStopped()`, `onError(message: String)`

Behaviour that the signatures don't fix:
- `initialize` sets `EmuFolders::AppRoot`/`Resources` to `resourcesRoot` and `DataRoot` to `dataRoot`, then loads
  `settings.ini` as `qthost.cpp`'s `InitializeConfig` does: defaults on a missing file or version, a `secrets.ini`
  layer, `EmuFolders::LoadConfig` and `EnsureFoldersExist`. It then starts the CPU thread with
  `PerformEarlyHardwareChecks` → `ProcessStartup` → `CPUThreadInitialize`.
- The CPU thread loops like Qt's `EmuThread::run`. `Host::RunOnCPUThread` posts to a mutex-protected queue drained
  in `PumpMessagesOnCPUThread` and while idle, honouring `block`. Every JNI entry point that touches the core posts
  to this queue.
- **Page size:** if `sysconf(_SC_PAGESIZE)` is larger than `HOST_PAGE_SIZE` (16384), force
  `CPUFastmemMode::Disabled` before the first boot and log it. *Changed during implementation, with the 16 KB build
  (Review Focus 5).*
- **Default mapping:** when a controller connects, add its `AndroidInputKeys::GetDefaultMapping` bindings to Port 1's
  (each binding is a list), skipping any already there, and save the settings. *Changed during the final review:
  the plan mapped a controller only when Port 1 had no bindings for any connected device, so a second pad never
  drove Port 1.*
  `Settings::SetDefaultControllerConfig` skips auto-mapping on Android, so this is what maps the Thor's pad.
- **The surface** follows the Qt pattern: a size change calls `Host::ResizeDisplayWindow`; a new or destroyed
  surface swaps the `ANativeWindow` (acquired/released with `ANativeWindow_fromSurface`/`_release`) and calls
  `Host::UpdateDisplayWindow()` blocking, going surfaceless on `null`. `Host::AcquireRenderWindow` returns a
  `WindowInfo` of `Type::Android`.
- Errors (`ReportErrorAsync`, a failed boot) reach Kotlin through `onError`, never an abort. `ReportFatalError`
  logs, calls `onError`, and shuts the game down.
- Every JNI call caches its class and method IDs at `JNI_OnLoad`, attaches the CPU thread to the JVM once, and
  clears pending Java exceptions.

- [ ] **Step 1: Implement** the three files.
- [ ] **Step 2: Build** — `cmake --build build-android --target goosestation` → links with no undefined symbols.
- [ ] **Step 3: Native tests on the emulator and desktop still green.**

### Task 7: The Gradle project and the app

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`,
  `android/gradle/libs.versions.toml`, the Gradle wrapper, `android/app/build.gradle.kts`,
  `android/app/src/main/AndroidManifest.xml`
- Create (Kotlin, package `io.github.ilyalissoboi.goosestation`): `MainActivity.kt`, `core/NativeLibrary.kt`,
  `core/NativeCallbacks.kt`, `core/Resources.kt`, `input/ControllerInput.kt`, `ui/GameListScreen.kt`,
  `ui/GameScreen.kt`, `ui/theme/Theme.kt`

**Interfaces:**
- Consumes: the JNI API from Task 6.
- `app/build.gradle.kts`: `externalNativeBuild.cmake.path` points at the repository's root `CMakeLists.txt`, with
  the same arguments as `configure-native.sh` and `targets("goosestation")`; `abiFilters("arm64-v8a")`; the deps
  prefix's `libshaderc_shared.so` and `libspirv-cross-c-shared.so` are packaged as jniLibs. `data/resources` is
  packaged as assets under `resources/`.

Behaviour:
- **Folders (this plan only):**
  - `dataRoot` is `getExternalFilesDir(null)/data`;
  - games are listed from `getExternalFilesDir(null)/games`;
  - the BIOS goes in `dataRoot/bios`.

  All three are filled with `adb push`. `Resources.kt` copies the `resources/` assets to `filesDir/resources` on
  first run and when the app's version code changes.
- **One activity**, locked to landscape, which declares the configuration changes it handles itself, so rotation
  and controller changes don't recreate it. It has two Compose screens:
  - `GameListScreen`: the files in the games folder, with loadable extensions, as a focusable list. It's usable with
    the D-pad, and the first item is focused when it opens. A or Enter starts the game.
  - `GameScreen`: a full-screen `SurfaceView` in an `AndroidView`. Its `SurfaceHolder.Callback` calls
    `surfaceChanged`.
- **Input:**
  - `MainActivity.dispatchKeyEvent` and `dispatchGenericMotionEvent` send gamepad events (sources
    `SOURCE_GAMEPAD`/`SOURCE_JOYSTICK`/`SOURCE_DPAD`) to `onKey`/`onAxis` while `GameScreen` is showing, and
    consume them. In the list, they drive Compose focus.
  - `InputManager.InputDeviceListener` sends device connect and disconnect events, and devices already present
    are reported at start.
  - Axes sent are X, Y, Z, RZ, LTRIGGER, RTRIGGER, BRAKE, GAS, HAT_X and HAT_Y, and only when a value changes.
- **Back** on the game screen calls `shutdownGame` and returns to the list (Plan 3 adds the prompt). `onPause` calls
  `setPaused(true)`, and `onResume` resumes a running game.
- **Display:** a game running keeps the screen on and hides the system bars (immersive). `onError` shows the
  message on the list screen, inside the screen and not as a dialog.

- [ ] **Step 1: Write the project and the app.**
- [ ] **Step 2: Build the APK.**
  Run: `cd android && ./gradlew assembleDebug`
  Expected: BUILD SUCCESSFUL; `app/build/outputs/apk/debug/app-debug.apk` contains `lib/arm64-v8a/libgoosestation.so`.
- [ ] **Step 3: Desktop still green.**

### Task 8: Run it on the emulator and on the Thor

**Files:**
- Modify: `docs/android.md`: how to build, push test files and install; the results of the checks below.

- [ ] **Step 1: Emulator boot.**
  - Install the APK on `goose-arm64`.
  - Push `ps-30j.bin` to `.../files/data/bios/`, and `padtest.psexe` and one CHD to `.../files/games/`.
  - Launch padtest from the list. Expected: it renders (OpenGL ES on the emulator is fine), logcat shows the
    recompiler in use (or says why not), and there's no crash.
  - Launch the CHD. Expected: it boots to the game's title screen with sound.
  - Press Back. Expected: it returns to the list.
  - Record the renderer, the frame rate shown by logcat or the OSD, and any errors.
- [ ] **Step 2: Lifecycle on the emulator.** While a game runs:
  - press Home and reopen the app; it resumes the game (Review Focus 3);
  - rotate the emulator; the activity isn't recreated.
- [ ] **Step 3: Hand checks on the Thor (the user):** install with `adb install`, push the same files, and with
  padtest:
  - every face button, shoulder, trigger, stick, L3/R3, Start, Select and the D-pad shows the right PS1 button;
  - nothing sticks or repeats;
  - triggers press once (Review Focus 1, 2);
  - disconnecting a Bluetooth pad mid-game leaves nothing pressed (Review Focus 4).

  Then play the CHD for a few minutes with Vulkan and with OpenGL ES (set `[GPU] Renderer` in `settings.ini` with
  `adb`, since there's no settings screen yet): full speed, no audio crackle. Record the results in
  `docs/android.md`.
- [ ] **Step 4: Checkpoint:** propose the commits for Tasks 6, 7 and 8 in chat, and commit after approval.
