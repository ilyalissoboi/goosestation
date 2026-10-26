<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Building for Android

The Android app is arm64-v8a only, with a minimum of Android 10 (API 29). It is built on macOS (Apple Silicon)
with the Android command-line tools from Homebrew and JDK 17. No Android Studio is needed.

## Versions

Installed on 2026-10-25, the latest stable releases on that day:

| Package | Version |
| --- | --- |
| Command-line tools (`android-commandlinetools` cask) | 15859902 (`sdkmanager` 22.0) |
| `platform-tools` | 37.0.1 |
| `platforms;android-37.0` (compile and target SDK) | 37.0, revision 2 |
| `build-tools;37.0.0` | 37.0.0 |
| `ndk;30.0.16248370` | r30 (30.0.16248370) |
| `cmake;4.1.2` | 4.1.2 |
| `emulator` | 37.2.12 |
| `system-images;android-37.0;google_apis;arm64-v8a` | Android 17, revision 8 |
| JDK | 17 (Temurin 17.0.8.1, `/usr/bin/java`) |

The Android project pins its own versions, the latest stable releases on 2026-10-26: in
`android/gradle/libs.versions.toml`, and Gradle in `android/gradle/wrapper/gradle-wrapper.properties`.

`android/gradle/wrapper/gradle-wrapper.jar` matches Gradle's published checksum for 9.8.2
(`https://services.gradle.org/distributions/gradle-9.8.2-wrapper.jar.sha256`): SHA-256
`3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98`, checked on 2026-10-26. The wrapper checks the
distribution itself against `distributionSha256Sum`, which matches the published `gradle-9.8.2-bin.zip.sha256`.

| Tool or library | Version |
| --- | --- |
| Gradle (wrapper) | 9.8.2 |
| Android Gradle Plugin | 9.4.1 (runs on JDK 17) |
| Kotlin | 2.4.21 (AGP compiles Kotlin itself; the Kotlin plugin is declared only to pin this version) |
| Compose BOM | 2026.09.00 |
| `androidx.activity:activity-compose` | 1.13.0 |
| `androidx.core:core-ktx` | 1.19.1 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` and `-test` | 1.11.0 |
| `junit:junit` (JVM unit tests) | 4.13.2 |
| `androidx.test:runner` | 1.7.0 |
| `androidx.test.ext:junit` | 1.3.0 |
| `androidx.test:orchestrator` (brings `androidx.test.services:test-services` 1.6.0) | 1.6.1 |
| Compose `ui-test-junit4` and `ui-test-manifest` | from the Compose BOM |
| `androidx.test.espresso:espresso-core` (pinned for the Compose UI tests: the 3.5.0 they bring fails on API 37) | 3.7.0 |
| `compileSdk` / `targetSdk` / `minSdk` | 37 / 37 / 29 |
| NDK / CMake (`ndkVersion`, `externalNativeBuild.cmake.version`) | 30.0.16248370 / 4.1.2 |

## Installing the SDK

On a fresh Mac, install the host tools first: CMake and Ninja for the dependency script and the standalone native
build, and JDK 17 for Gradle.

```bash
brew install cmake ninja
brew install --cask temurin@17
java -version   # 17.x
```

Then the SDK:

```bash
brew install --cask android-commandlinetools
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/30.0.16248370
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

yes | sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "ndk;30.0.16248370" \
    "cmake;4.1.2" "emulator" "system-images;android-37.0;google_apis;arm64-v8a"
```

The cask puts the SDK in `/opt/homebrew/share/android-commandlinetools`, and links only the command-line tools
(`sdkmanager`, `avdmanager` and so on) into `/opt/homebrew/bin`. `adb` (in `platform-tools`) and `emulator` aren't
on `PATH` until you add them, as the third `export` does. It puts them first, ahead of any other `adb`, such as the
`android-platform-tools` cask's. Put the three `export` lines in your shell profile: the dependency script needs
`ANDROID_NDK_HOME`, Gradle finds the SDK through `ANDROID_HOME`, and every `adb` command below needs the `PATH`
entry.

## The emulator

Create the AVD once:

```bash
echo no | avdmanager create avd -n goose-arm64 -k "system-images;android-37.0;google_apis;arm64-v8a" -d pixel_7
```

Start it, then check that `adb` sees it:

```bash
emulator -avd goose-arm64 -no-snapshot-save &
adb wait-for-device
adb devices
```

When a phone or handheld is plugged in as well, `adb` needs to know which device you mean: pass `-s emulator-5554`
(the emulator's serial in `adb devices`), or `export ANDROID_SERIAL=emulator-5554`. The commands below leave `-s`
out.

The image uses 4 KB pages, like most devices. `libgoosestation.so` is also linked to load on 16 KB-page devices.

## Dependencies

`scripts/deps/build-dependencies-android.sh` builds the core's dependencies for arm64-v8a, API 29, with the NDK. It
uses the same versions and sources as `build-dependencies-linux.sh`, and leaves out what Android doesn't use (SDL3,
Qt, CURL, discord-rpc and libbacktrace):

```bash
scripts/deps/build-dependencies-android.sh [-skip-download] [-skip-cleanup] [<output directory>]
```

It needs `ANDROID_NDK_HOME`, and runs the `cmake` and `ninja` on your `PATH` (Homebrew's, CMake 4.4.0 on
2026-10-25). The SDK's `cmake;4.1.2` is the one Gradle uses.

The output directory defaults to `~/work/goosestation-deps-android`. The script works in `deps-build/` under the
current directory and removes it at the end, unless `-skip-cleanup` is given. It takes about 5 minutes on an Apple
Silicon Mac.

zstd, libpng, libjpeg-turbo, libwebp, FreeType (with HarfBuzz), lunasvg and cpuinfo are built as static libraries,
linked into `libgoosestation.so`. zlib comes from the NDK. Three libraries are shared, and packaged in the APK:

- `lib/libshaderc_shared.so` and `lib/libspirv-cross-c-shared.so`, which the core loads at run time;
- `lib/libsoundtouch.so`, SoundTouch's C wrapper, which the core links. Its build has no static option.

The NDK's CMake platform leaves out SOVERSION, so all three are unversioned, as APKs require. They keep their debug
information; the Android Gradle Plugin strips them when it packages the APK.

The Gradle build and the scripts below look for the prefix in `~/work/goosestation-deps-android`. If you built it
somewhere else, set `GOOSESTATION_ANDROID_DEPS` to that directory.

## Building the native code without Gradle

`android/scripts/` builds and checks the native code on its own, faster than going through Gradle. All three
scripts need `ANDROID_NDK_HOME` and the dependency prefix, and work in `build-android/` at the top of the
repository (git-ignored).

- `configure-native.sh [BUILD_TESTS=ON]` configures `build-android/` for arm64-v8a, API 29, with the same CMake
  arguments as the Gradle build. `BUILD_TESTS=ON` adds `common-tests`. Build with
  `cmake --build build-android --target goosestation`.
- `run-native-tests.sh [gtest arguments]` configures with `BUILD_TESTS=ON`, builds `common-tests`, pushes it to
  `/data/local/tmp` on the emulator and runs it there. Its exit code is the tests'. It needs `ANDROID_HOME` for
  `adb`, and uses `ANDROID_SERIAL` (default `emulator-5554`) to pick the device. For example:
  `android/scripts/run-native-tests.sh --gtest_filter='FileSystem.*'`.
- `check-undefined.sh` builds `goosestation` in `build-android/` (configure it first), then links all of core,
  util and common into a throwaway library with `--no-undefined`. It lists any symbol the app would miss once it
  uses the whole core: the real link only pulls in what the JNI code references. It exits 0 when nothing is
  undefined, and 1 when something is. It exits 2 when it can't check: `build-android/` isn't configured, the
  `goosestation` link command doesn't link the core, or the link fails for another reason than undefined symbols.

```bash
android/scripts/configure-native.sh
cmake --build build-android --target goosestation
android/scripts/check-undefined.sh
android/scripts/run-native-tests.sh
```

## Building the app

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties   # git-ignored; or rely on ANDROID_HOME
./gradlew assembleDebug
```

The APK is `android/app/build/outputs/apk/debug/app-debug.apk`. Gradle builds the `goosestation` target from the
repository's top-level `CMakeLists.txt` with the same CMake arguments as `android/scripts/configure-native.sh`,
staging it in `android/app/.cxx`. The native code is built as RelWithDebInfo for both the debug and release APKs: a
Debug core is far too slow to play. The first build compiles the whole core and takes a few minutes.

The APK also carries the dependency prefix's three shared libraries (`GOOSESTATION_ANDROID_DEPS` overrides the
prefix, as for the configure script) and `data/resources` as assets under `resources/`. The app copies the resources
to its internal files on first run and whenever the app is reinstalled or updated: it compares the version code and
the install's `lastUpdateTime` (`<versionCode>-<lastUpdateTime>`) with the last copy's.

## Testing the app

```bash
cd android
./gradlew testDebugUnitTest                                    # JVM unit tests, in app/src/test
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest   # instrumented tests, in app/src/androidTest
```

The instrumented tests run under the AndroidX Test Orchestrator, so each test gets a process of its own (the core
can only be initialized once per process). Tests that need a document tree build one in `TestDocumentsProvider`, a
`DocumentsProvider` in the debug build only (`app/src/debug`), over `files/test-documents`. It's disabled until a
test enables it, and shares the app's UID, so the app reads it without a grant.

## Running it

The app lists games from its external files folder, and keeps its settings, memory cards and BIOS images in `data`
next to it.

**Launch the app once before pushing anything.** The first launch creates `files/games`, `files/data` and its
subfolders, owned by the app. A folder that `adb push` or `adb shell mkdir` creates first belongs to the shell, and
the core can't write to it. The first launch also extracts the resources, so it takes 10 to 20 seconds on the
emulator.

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n io.github.ilyalissoboi.goosestation/.MainActivity   # wait for the list to appear
FILES=/sdcard/Android/data/io.github.ilyalissoboi.goosestation/files
adb push <your BIOS image> $FILES/data/bios/
adb push extras/padtest/padtest.psexe $FILES/games/
adb push "<a disc image>.chd" $FILES/games/
```

The list refreshes when the app comes back to the foreground. A, Enter or a tap starts the selected game; Back leaves
it. Home pauses the game, and reopening the app resumes it. Pausing also writes any memory card changes to their files
straight away (a paused game never reaches the card's usual save, 5 emulated seconds after the last write), so a save
made just before Home survives the app being killed in the background. Going to the background, or losing focus to
the notification shade or a dialog, also lets go of every button and stick, so press or move them again after
coming back.

In a game, the app keeps only the controllers' gamepad buttons and D-pad keys. Volume, media and other keys go to
the system, so the volume keys work during a game.

Never commit BIOS images or games. The checks below use `~/work/goose-test/ps-30j.bin` and a CHD from the same
folder.

### Changing settings

There's no settings screen yet, so settings are changed by replacing `files/data/settings.ini`. The shell can't read
or edit the app's copy: the core writes it as a temporary file renamed into place, so it's mode 0600, and
`run-as` can't reach external storage. The shell can delete it, though, and the app reads a file the shell pushes.
Stop the app, replace the file, then start the app again:

```bash
cat > /tmp/settings.ini <<'EOF'
[Main]
SettingsVersion = 1

[GPU]
Renderer = Vulkan

[Display]
ShowFPS = true
ShowSpeed = true
ShowCPU = true
EOF
adb shell am force-stop io.github.ilyalissoboi.goosestation
adb shell rm $FILES/data/settings.ini
adb push /tmp/settings.ini $FILES/data/settings.ini
adb shell am start -n io.github.ilyalissoboi.goosestation/.MainActivity
```

- `[Main] SettingsVersion = 1` must be there, or the core resets the file to its defaults.
- Every setting the file leaves out takes its default.
- `[GPU] Renderer` is `Automatic` (the default), `Vulkan`, `OpenGL` or `Software`. On Android, `Automatic` prefers
  Vulkan, as on Linux: `VulkanDevice::IsSuitableDefaultRenderer()` takes the first Vulkan GPU unless it's a software
  one (its name starts with `llvmpipe` or `SwiftShader`) or an Intel one, and OpenGL ES otherwise. `Vulkan` and
  `OpenGL` (OpenGL ES on Android) force one. There's no fallback from Vulkan to OpenGL ES yet (Plan 3): if the
  Vulkan device can't be created, the boot fails with `Failed to create render device`, and if the device is created
  but the hardware renderer fails, the core falls back to its software renderer. Set `Renderer = OpenGL` if that
  happens.
- `ShowFPS`, `ShowSpeed` and `ShowCPU` turn on the OSD's frame rates (`G:` game, `V:` video), speed and frame times.
  With the default CPU settings, `ShowCPU` adds a `CPU[I]` line when the CPU falls back to the interpreter, and none
  on the recompiler.
- The Port 1 bindings live in this file too. Leaving them out is fine: whenever a controller connects, the core adds
  its default bindings to Port 1's (each binding is a list, so every connected pad drives Port 1), skips those
  already there, and saves the file. That save makes the file mode 0600 again.

### Known limits

- **In a game, a controller alone can't get back to the list.** B goes to the game, as Circle. Use the Back gesture,
  or Home. A later plan adds the Mode button and Select+Start.
- **Leaving during a boot freezes the UI until the boot is done.** Destroying the surface waits for the CPU thread,
  which is still booting. On the emulator, Back during an Ace Combat 3 boot froze the UI for 0.7 to 1.2 s (43 and
  67 skipped frames), and the boot took 1.2 to 1.4 s. That's well under the 5 s ANR limit, but a slow first boot on a
  device could take longer.
- **Switching audio output mid-game is untested.** The AAudio stream is reopened when its device goes away, as when
  headphones are unplugged. The emulator can't check this; the device checklist below does.

## Results on the emulator

Checked on 2026-10-26 with the debug APK, on `goose-arm64` (Android 17, 4 KB pages). The BIOS was
`ps-30j.bin` (SCPH-5500, NTSC-J), with `padtest.psexe` and Ace Combat 3 Electrosphere (Disc 1) as a CHD.

| Check | Result |
| --- | --- |
| Install, first launch | Pass. The first launch extracts the resources (`Resources: Extracting the resources for install …`), so the cold start took 17 s (`am start -W`, `TotalTime: 17284`). |
| padtest renders | Pass. Port 1 shows as Analog. padtest is a PAL executable, so the core warns `BIOS region NTSC-J does not match requested region PAL` and boots it anyway. |
| Ace Combat 3 boots | Pass. It shows "Press START button" by 30 s after the boot (fast boot is on), then the attract FMVs. The boot itself (`Starting` to `Game started`) took 0.7 s. |
| Sound | An AAudio stream (44100 Hz, stereo, legacy path, 882-frame bursts) is open and active: `dumpsys media.audio_flinger` showed its track consuming frames, with 4 underruns over about 2 minutes. Not checked by ear. |
| Back | Pass. It returns to the list (`Game stopped`, `Surface destroyed`). |
| Home, then reopen | Pass, with both renderers. On Home (OpenGL ES run), the core pauses (`AAudioStream_requestPause`, then `Surface destroyed`, `Render window is 0x0`), and the process uses 0.0% CPU in `top`. On reopening, the stream restarts (`AAudioStream_requestStart` at 50.388 s), then `New surface` (50.659 s), and the game carries on from where it was. No second `Game started`, so no reboot. |
| Rotation | Pass, with both renderers. `adb emu rotate` went from 90° to 270° and back to 90°: 2 steps under OpenGL ES, and 4 under Vulkan. The process (and its PID) stayed the same, with no surface event and no relaunch: the manifest handles the configuration changes, and `sensorLandscape` keeps it landscape. A speed dip to 85% at the turn. Under Vulkan, one screenshot just after the rotations showed a garbled FMV frame; the next four, 2 s apart, were clean, so it's probably the FMV's own transition (not confirmed). |
| Crashes | None: the `crash` log buffer stayed empty, and logcat has no FATAL line. |

**Renderer.** These checks ran when `Automatic` still meant OpenGL ES on Android, so the OpenGL ES column is
`Automatic`: `Host: Trying to create a OpenGL GPU device...`, on
`ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (LLVM 10.0.0)))`, OpenGL ES 3.1. The Vulkan column is
`Renderer = Vulkan`: the Vulkan device (Vulkan 1.3.0, `Goldfish GFXStream (llvmpipe (LLVM 21.1.4, 128 bits))`) also
works, through the same Home and rotation checks. `Automatic` now prefers Vulkan (see "Changing settings"). On the
emulator it logs `Using Vulkan GPU 'Goldfish GFXStream (llvmpipe …)' for automatic renderer check.`, then `Allowing
Vulkan as default renderer.` and `Trying to create a Vulkan GPU device...`: the name starts with `Goldfish`, not
`llvmpipe`.

**Controllers, input release and memory cards**, checked later on 2026-10-26 with two gamepads made with the
system's `uinput` tool (`adb shell uinput -`, which takes JSON `register` and `inject` commands), on padtest and
Ace Combat 3:

| Check | Result |
| --- | --- |
| Pads connected mid-game | Pass. Each logged `Added 24 bindings for Android-<n> to Port 1.`, and both drove Port 1 (Cross from one, Circle from the other). The game kept rendering, with the same PID and no surface event. |
| Reconnecting | Pass. Both pads were removed and registered again: they came back as `Android-0` and `Android-1`, with no new `Added` line. `[Pad1]` in `settings.ini` has 24 entries for each pad, none twice. |
| Held input across Home | Pass. With the hat held right and the left stick held left, Home and reopening left padtest showing nothing pressed (the stick reads X -1, its rest value after a release from the left). Moving them again pressed them again. |
| Held input across focus loss | Pass. With the stick right and the hat down held, opening and closing the notification shade released both. |
| Held button across games | Pass. Cross held while leaving padtest, let go on the list, then pressed in the next padtest run: it registered on the first press. |
| Volume keys in a game | Pass. `input gamepad keyevent KEYCODE_VOLUME_UP` twice, then `VOLUME_DOWN`, moved the music volume 5, 7, 6. |
| Memory card on Home | Pass. With a blank card, Ace Combat 3 wrote sector 63 at 42.300 s; Home paused the game at 42.818 s, and `Saving memory card to Ace Combat 3 - Electrosphere (Japan)_1...` followed at 42.851 s, 0.55 s after the write instead of 5 emulated seconds. |

**Frame rate**, from the OSD (`ShowFPS`, `ShowSpeed`, `ShowCPU`):

| Scene | OpenGL ES | Vulkan |
| --- | --- | --- |
| padtest | G 58.75, V 58.75, 99% | G 59.60, V 59.60, 101% |
| Ace Combat 3 title | G 57.3 to 58.6, V the same, 96 to 98% | not measured |
| Ace Combat 3 FMV | G 13.8 to 14.8 (the FMV's own rate), V 57.3 to 59.1, 96 to 99%; V 50.7, 85% just after a resume, and 85% at a rotation | G 14.7 to 22, V 59.9, 100% |

Both renderers are in software on the host, so these say nothing about a device's speed.

**Recompiler.** In use. `[CPU] ExecutionMode = Recompiler` and `FastmemMode = MMap` are the defaults, and the OSD
shows no `CPU[I]` (interpreter) line. With `[Logging] LogLevel = Dev`, padtest logs the recompiler's fastmem
backpatching (`CPU::Recompiler: Backpatching 0x… (guest PC 0xBFC012B8) to slowmem`), and no
`falling back to uncached interpreter` errors. At startup: `CPU::CodeCache: Using JIT buffer section of size
50331648` and `Bus: Fastmem base: 0x7640000000`. Also at startup: `This build was compiled with 16384 byte pages,
and the system has 4096 byte pages.` That's by design: the build sets `HOST_PAGE_SIZE=16384`, which also works on
4 KB pages, and fastmem is only turned off on pages larger than that.

**Errors in logcat.** Each one is either the emulator's or harmless:

- OpenGL ES: `eglCreateContext() failed: 0x3005` (`EGL_BAD_CONFIG: no ES 3.2 support`). The core tries ES 3.2
  first, then gets 3.1.
- OpenGL ES: `GL error 0x500 condition [!GLESv2Validation::samplerParams(ctx, pname)]`, twice per boot, from
  gfxstream.
- OpenGL ES: `Failed to create GL program from binary: status 0, discarding cache`, on every launch after the
  first. The emulator's driver apparently rejects the program binaries it produced, so the shader cache is rebuilt
  each time.
- Vulkan: `MESA: Failed to open rendernode: No such file or directory`, about 40 times per boot, from the
  emulator's Mesa driver; and `Immediate not supported for vsync-disabled, using mailbox`.
- Hundreds of `GameDatabase: Duplicate code …` warnings while the game database loads, the same as on desktop.

## Results on the AYN Thor

Checked on 2026-10-26 with the debug APK built from `9dc56372e`, on an AYN Thor: Android 13 (API 33), Snapdragon
8 Gen 2 (QCS8550), Adreno 740, 4 KB pages. The built-in pad connects as `Odin Controller` (`Android-0`) and reports
X/Y (left stick), Z/RZ (right stick), HAT_X/HAT_Y (D-pad) and BRAKE/GAS (L2/R2), all of which the app forwards. Its
key layout has real BACK, HOME and BUTTON_MODE keys, so on the Thor the pad's Back key leaves a game.

| Check | Result |
|---|---|
| padtest: face buttons by position, L1/R1/L2/R2/L3/R3, Start, Select, D-pad, both sticks | Pass: each shows the right PS1 control |
| Nothing sticks or repeats; L2/R2 press once | Pass |
| Home, and screen off and on, mid-game | Pass: resumes |
| The pad's Back key | Pass: returns to the list |
| Default renderer | Vulkan (`Allowing Vulkan as default renderer.`, `Using Vulkan GPU 'Adreno (TM) 740'`) |
| Ace Combat 3, Vulkan, in a mission | Full speed: `G: 29.91`, `V: 59.82`, 100%, frame times 16.64–16.80 ms, CPU 7.2% |
| Ace Combat 3, OpenGL ES, in a mission | Full speed: `G: 29.91`, `V: 59.82`, 100%, frame times 7.25–26.17 ms (average 16.72), CPU 8.6% |
| Sound, both renderers | Clean, no crackle |
| Headphones plugged in and out mid-game | Sound moves over. The stream was never disconnected (Android re-routed it), so the reopen code wasn't exercised |
| Recompiler, fastmem | Recompiler in use (no `CPU[I]` line); fastmem on (no `fastmem is disabled` line) |
| Crash buffer | Empty |

Found: the memory card save message shows the Japanese game title as `?????????`. The OSD's text font (Roboto) has
Latin glyphs only, and nothing loads a CJK font for game titles (the same happens on desktop unless the UI language
is CJK).

Not checked yet: a Bluetooth pad (step 2 below), the volume keys during a game, and the save-then-swipe-away check
in step 3.

## Checks on the AYN Thor

The full checklist, on the Thor itself, with a controller and headphones. Find the Thor's serial with
`adb devices`, and allow USB debugging on the Thor when it asks. Use the serial in place of `emulator-5554`.

```bash
export ANDROID_SERIAL=<the Thor's serial>
FILES=/sdcard/Android/data/io.github.ilyalissoboi.goosestation/files
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n io.github.ilyalissoboi.goosestation/.MainActivity   # once, before pushing
adb push ~/work/goose-test/ps-30j.bin $FILES/data/bios/
adb push extras/padtest/padtest.psexe $FILES/games/
adb push "$HOME/work/goose-test/Ace Combat 3 Electrosphere (Disc 1).chd" $FILES/games/
adb logcat -c
```

Keep a log of each session in a second terminal. `ANDROID_SERIAL` is per shell, so set it there too (or pass `-s`):

```bash
export ANDROID_SERIAL=<the Thor's serial>
adb logcat -v time > thor.log
```

1. **padtest, with the Thor's own controls.** Start padtest from the list.
   - [ ] Each face button lights the right PS1 button: bottom = Cross, right = Circle, left = Square, top =
     Triangle. If Cross/Circle and Square/Triangle come out swapped, the pad reports Nintendo labels: note it, for
     the remap screen. Don't work around it in code.
   - [ ] L1, R1, L2, R2, L3, R3, Start, Select and the D-pad each light the right button.
   - [ ] Both sticks move their crosses and go back to 0 when let go.
   - [ ] Nothing sticks or repeats: hold each button, then let go.
   - [ ] L2 and R2 press once each, and let go cleanly, whether they're analog or digital.
   - [ ] No button leaves the game on its own. Only the Back gesture and Home should do that; note any button that
     sends Back.
2. **padtest, with a Bluetooth pad**, paired in Android's settings. Start padtest with the pad off.
   - [ ] Switch the pad on mid-game: padtest keeps rendering, with no relaunch and no black screen (a new input
     device is a configuration change, which the manifest handles).
   - [ ] Both the Bluetooth pad and the Thor's own controls drive Port 1, and the checks in step 1 pass with the
     Bluetooth pad. The log has `Added … bindings for Android-1 to Port 1.` (the index may differ).
   - [ ] Switch the pad off while holding a button and a stick: nothing stays pressed in padtest, and it keeps
     rendering, with no relaunch and no black screen.
   - [ ] Switch it back on: it drives Port 1 again, and the log has no second `Added … bindings` line for it (its
     bindings are already there).
   - [ ] Hold a D-pad direction and a stick, press Home, let go, then reopen the app: nothing is held in padtest.
   - [ ] The volume keys change the volume during the game.
3. **Ace Combat 3, with OpenGL ES.** Turn the OSD on as in "Changing settings", with `Renderer = OpenGL` in the
   pushed `settings.ini`. Play for a few minutes.
   - [ ] Full speed: the OSD's speed stays at 100%, and `V:` is about 59.9.
   - [ ] No audio crackle.
   - [ ] Plug headphones in mid-game, then unplug them: the sound moves over each time, with no crash and no lasting
     silence. The log should have `AAudio stream disconnected, reopening it on the new output device.` each time, and
     no `Failed to reopen the AAudio stream`.
   - [ ] Home, then reopen the app: the game resumes where it was.
   - [ ] Screen off and back on: the game resumes, without a black screen.
   - [ ] Save to the memory card, press Home within 5 s, then swipe the app away in Recents. Start it and the game
     again: the save is there. The log has `Saving memory card to …` just after the Home.
4. **Ace Combat 3, with Vulkan.** Repeat step 3 with `Renderer = Vulkan` in the pushed `settings.ini`.
5. **The default renderer.** Push the file again with the `Renderer = …` line left out (or `Renderer = Automatic`),
   and start Ace Combat 3.
   - [ ] The log has `Allowing Vulkan as default renderer.` and `Trying to create a Vulkan GPU device...`. If it
     has `Not using Vulkan …` and `Trying to create a OpenGL GPU device...` instead, note the GPU name it logs.
6. **Afterwards.**
   - [ ] `adb logcat -b crash -d` is empty.
   - [ ] Note the renderer lines (`adb logcat -d | grep -E "GPUDevice|Trying to create"`).
   - [ ] Note the page size (`adb shell getconf PAGE_SIZE`). The core is built for 16 KB pages, so on 4096 or 16384
     fastmem stays on and the log has no `fastmem is disabled` line.
