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
| `org.jlleitschuh.gradle.ktlint` (`./gradlew ktlintCheck`, part of `check`; latest stable on 2026-10-30) / ktlint | 14.2.0 / 1.8.0 |
| `compileSdk` / `targetSdk` / `minSdk` | 37 / 37 / 29 |
| NDK / CMake (`ndkVersion`, `externalNativeBuild.cmake.version`) | 30.0.16248370 / 4.1.2 |
| emusync's Android library (memory card sync; `emusync-0.3.0.aar` from the `v0.3.0` release, SHA-256 `964c65791c8f690ce82281dac244500f9a1d08a56b9ca4e9fc082c22756a8b81`, checked by `build.gradle.kts`, which downloads it once into `app/build/emusync/`, or takes a copy placed in `dep/emusync/` for builds without network) | 0.3.0 |

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

### The test emulators

The instrumented tests run on two emulators of their own, so `goose-arm64` stays as it is for checks by hand.
`android/scripts/create-test-avd.sh` makes them once, `goose-test-1` and `goose-test-2`, from the same system image:

| Setting (`config.ini`) | Value | Why |
| --- | --- | --- |
| `hw.gpu.enabled`, `hw.gpu.mode` | `yes`, `host` | Drawn on the Mac's GPU, where `goose-arm64` (`hw.gpu.enabled=no`) draws in software |
| `hw.lcd.width`, `hw.lcd.height`, `hw.lcd.density` | 1080, 2400, 420 | `goose-arm64`'s (a Pixel 7's): the tests that don't force a size of their own were written at it |
| `hw.cpu.ncore`, `hw.ramSize` | 4, 3072M | |
| `fastboot.forceColdBoot` | `yes` | Nothing is saved to a snapshot, so each boot is cold |

`android/scripts/start-test-emulators.sh` starts both without a window (`-no-window -no-boot-anim
-no-snapshot-save -no-audio -gpu host`), `goose-test-1` as `emulator-5580` and `goose-test-2` as `emulator-5582`,
waits for them to boot and turns their animations off. If an emulator doesn't boot on the host's GPU without a window,
it starts it again with `-gpu swiftshader_indirect`, and says so. On an M2 Pro, both boot on the GPU in about 30 s
(`GLES: ... Android Emulator OpenGL ES Translator (Apple M2 Pro), OpenGL ES 3.0 (4.1 Metal - 90.5)` in `adb shell
dumpsys SurfaceFlinger`). Stop them with `adb -s emulator-5580 emu kill` and `adb -s emulator-5582 emu kill`.

On the host's GPU, the core's Vulkan renderer runs (`Using Vulkan GPU 'Goldfish GFXStream (Apple M2 Pro)'`), and
`Automatic` picks it, so the tests that boot padtest run under Vulkan there, where `goose-arm64` runs them under OpenGL
ES. The core's OpenGL ES renderer can't start on the host's GPU: the guest gets OpenGL ES 3.0 (the emulator's Metal
translator), and the core needs 3.1 ("OpenGL renderer unavailable"); with `-feature GuestAngle`, ANGLE has no EGL
config for it (`eglChooseConfig() failed`). OpenGL ES runs with `-gpu swiftshader_indirect`, as `goose-arm64` draws
(ANGLE over SwiftShader, OpenGL ES 3.1): `run-instrumented-tests.sh full` boots the games there under OpenGL ES (see
"Testing the app"), and `GOOSE_TEST_GPU=swiftshader_indirect android/scripts/start-test-emulators.sh` starts both
emulators so. With AVD names as arguments, the script starts only those. It takes an emulator already on 5580 or 5582
as one of its own only if it runs that AVD, and `create-test-avd.sh` won't make an AVD again while it's running.

## Dependencies

`scripts/deps/build-dependencies-android.sh` builds the core's dependencies for arm64-v8a, API 29, with the NDK. It
uses the same versions and sources as `build-dependencies-linux.sh`, and leaves out what Android doesn't use (SDL3,
Qt, CURL, discord-rpc and libbacktrace):

```bash
scripts/deps/build-dependencies-android.sh [-skip-download] [-skip-cleanup] [<output directory>]
```

It needs `ANDROID_NDK_HOME`, and runs the `cmake` and `ninja` on your `PATH` (Homebrew's, CMake 4.4.0 on
2026-10-25). The SDK's `cmake;4.1.2` is the one Gradle uses. librashader (slang presets) is built with Rust: the
script needs `rustup` ([rustup.rs](https://rustup.rs)) and installs the toolchain it pins (1.88, with the
`aarch64-linux-android` target) itself.

The output directory defaults to `~/work/goosestation-deps-android`. The script works in `deps-build/` under the
current directory and removes it at the end, unless `-skip-cleanup` is given. It takes about 5 minutes on an Apple
Silicon Mac.

zstd, libpng, libjpeg-turbo, libwebp, FreeType (with HarfBuzz), lunasvg and cpuinfo are built as static libraries,
linked into `libgoosestation.so`. zlib comes from the NDK. Four libraries are shared, and packaged in the APK:

- `lib/libshaderc_shared.so` and `lib/libspirv-cross-c-shared.so`, which the core loads at run time;
- `lib/libsoundtouch.so`, SoundTouch's C wrapper, which the core links. Its build has no static option;
- `lib/librashader.so`, librashader 0.12.1 (MPL-2.0) with its Vulkan and OpenGL runtimes, which the core loads at
  run time for slang presets. It links libc++ statically, so the APK carries no `libc++_shared.so`.

The NDK's CMake platform leaves out SOVERSION, so the first three are unversioned, as APKs require; librashader's
SONAME is set to `librashader.so` by the script. They keep their debug
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
  `/data/local/tmp` on the emulator and runs it there. Its exit code is the tests'. The tests link the core, on a
  no-op host (`src/common-tests/core_host_stubs.cpp`), so `libsoundtouch.so` from the dependency prefix goes along. It needs `ANDROID_HOME` for
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

## The app's look

The app's colours, fonts, icons and components follow the design system: see
[docs/design-system.md](design-system.md) before adding a screen or a control. The theme is in `ui/theme/`
(`Theme.kt`, `GsIcons.kt`, `GooseMark.kt`), the shared components in `ui/components/`, and the bundled fonts in
`app/src/main/res/font/`, with their licences in `app/src/main/assets/licenses/`. `GsIcons.kt` is generated from
`docs/design-system/icons/` by `scripts/branding/generate-gsicons.py`. The launcher icon is an adaptive icon of
vector layers (background, the goose mark, and a monochrome layer for themed icons) in `res/mipmap-anydpi/`,
generated from the design system's goose mark by `scripts/branding/generate-android-icon.py`.

## Testing the app

```bash
cd android
./gradlew testDebugUnitTest                                    # JVM unit tests, in app/src/test
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest   # instrumented tests, in app/src/androidTest
```

`android/scripts/run-instrumented-tests.sh` runs the instrumented tests on the two test emulators (see "The test
emulators" above) at once, half the suite on each:

```bash
android/scripts/start-test-emulators.sh
GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh quick   # on every change
GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh full    # before a pull request
```

- **quick** leaves out the classes tagged `@LargeTest` (`androidx.test.filters.LargeTest`): `OrientationLayoutTest`,
  `AchievementsOfflineTest` and the `MemcardSync*` classes (no other class takes over a minute; tag one that comes
  to). It's the runner's `notAnnotation=androidx.test.filters.LargeTest`. **full** runs every test, then the classes
  that boot a game (tagged `@BootsGames`: `GameCallsTest`, `AchievementsSignInTest`, `AchievementsOfflineTest` and
  `SettingsSetupTest`) once more under OpenGL ES, which the host's GPU can't run (see "The test emulators"): it
  restarts `goose-test-2` with `-gpu swiftshader_indirect`, runs them there unsharded under the Orchestrator with the
  runner argument `gooseRenderer=OpenGL` (each test writes `[GPU] Renderer = OpenGL` before it boots padtest:
  `core/TestRenderer.kt`), checks the log has `Created an OpenGL ES context` and no Vulkan device, and puts
  `goose-test-2` back on the host's GPU. full won't run without `GOOSE_TEST_DATA`'s BIOS, so most core tests can't
  skip in it.
- Each emulator runs one shard (`-Pandroid.testInstrumentationRunnerArguments.numShards=2` and `shardIndex`), with its
  own `ANDROID_SERIAL`, in two parts: the `ui` package's tests, which don't start the core, in one process without the
  Orchestrator (`-Pgoose.orchestrator=false`), then the rest under it. Each part's results are under
  `app/build/outputs/androidTest-results/shard<N>-<part>` (`-Pgoose.testResults`), its log in `$TMPDIR`; the script
  prints the counts and every failure, and exits non-zero on one.
- It only runs on emulators (`GOOSE_TEST_SERIALS`, by default `emulator-5580 emulator-5582`), never a phone or
  handheld that's plugged in.
- `GOOSE_TEST_DATA` is a folder laid out as `/sdcard/GooseTest`, pushed to each emulator: `bios/ps-30j.bin`,
  `games/padtest.psexe` and padtest's disc image, `games/Pad Test #1 (100%).cue` and `.bin`. Here it's
  `~/work/goose-test-data`: the BIOS is a dump of a Japanese v3.0 one, `padtest.psexe` is
  `extras/padtest/padtest.psexe`, and the disc image is made from it as the CUE/BIN row of Plan 2's results describes
  ("Results on the emulator"). `full` won't start unless all four are there, since a test missing one is skipped
  rather than failed. Without the folder (quick only) just `extras/padtest/padtest.psexe` is pushed, and the tests
  that need the BIOS are skipped.
- The tests run with animations off (`testOptions.animationsDisabled`).

```bash
./gradlew ktlintCheck    # the Kotlin layout rules, in android/.editorconfig; ktlintFormat fixes what it can
./gradlew check          # ktlintCheck, Android Lint, checkNoWindowDialogs and the JVM tests
```

On GitHub, `.github/workflows/android.yml` ("Android build") runs on pull requests and pushes to `master` that touch
the app, the core or `scripts/deps/build-dependencies-android.sh`, and twice a week to keep its cache warm. On
`ubuntu-24.04` it installs the SDK's NDK, CMake (with its Ninja) and platform at the versions above, builds the
dependency prefix into `~/deps-android` (cached by the script's hash and the NDK version), then runs `assembleDebug`,
`testDebugUnitTest`, `ktlintCheck` and `checkNoWindowDialogs`, and uploads the debug APK as `android-debug-apk`. It
has no secrets, and isn't part of a release.

The instrumented tests run under the AndroidX Test Orchestrator, so each test gets a process of its own (the core
can only be initialized once per process); with `-Pgoose.orchestrator=false` they share one, which only the `ui`
package's tests can, and each must leave nothing behind for the next. Tests that need a document tree build one in `TestDocumentsProvider`, a
`DocumentsProvider` in the debug build only (`app/src/debug`), over `files/test-documents`. It's disabled until a
test enables it, and shares the app's UID, so the app reads it without a grant.

## Running it

The app asks for no storage permission. It works with three kinds of folder, picked in Android's folder picker
(the Storage Access Framework), and keeps a grant for each:

- **The BIOS folder**, read only. The app copies the PS1 BIOS images it finds there into its own `files/bios`, and
  the core uses the copies. A BIOS folder whose grant is gone doesn't stop anything: the copies still work.
- **One or more games folders**, read only. Games are played from where they are, through `content://` paths.
- **The data folder**, read and write: settings, memory cards, save states, covers, play time and the rest of the
  core's data root. The core never writes to it: it works on a copy in the app's internal storage, which the app
  mirrors to the folder (see "The data folder and its mirror").

Put the files in folders on the device first. The shell can make folders under `/sdcard`, and the picker can open
them:

```bash
adb shell mkdir -p /sdcard/GooseStation/bios /sdcard/GooseStation/games /sdcard/GooseStation/data
adb push <your BIOS image> /sdcard/GooseStation/bios/
adb push extras/padtest/padtest.psexe /sdcard/GooseStation/games/
adb push "<a disc image>.chd" /sdcard/GooseStation/games/
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.ilyalissoboi.goosestation/.MainActivity
```

The first launch opens **setup**: a welcome screen, then the BIOS folder, the games folders and the data folder, one
step each. Every step works with a D-pad: A or Enter presses the focused button, B or Back goes back a step. The
system folder picker is the exception, and may need touch (each step says so). In the picker, open the folder, press
**Use this folder**, then **Allow**.

- The BIOS step lists the images it copied (`SCPH-5500 (v3.0 09-09-96 J)`, say), and won't go on without one.
- The games step takes any number of folders, and needs at least one.
- The data folder can be empty, or hold data GooseStation saved before, here or on another device (but see "Known
  limits": it isn't shared live). Setup says which:
  "This folder is empty. GooseStation will start fresh here.", or "This folder already has GooseStation data
  (settings and 2 memory cards). GooseStation will use it." A folder that holds other things but no GooseStation
  data gets "No GooseStation data here yet. GooseStation will start fresh in this folder." When the folder holds
  anything at its top that isn't GooseStation's (see "The data folder and its mirror"), a line follows: "This folder
  also holds other things (games, notes.txt and 2 more). GooseStation leaves them alone." It doesn't stop Finish,
  and the data folder change shows it as well.
- Setup refuses a data folder, in the screen, with Finish kept off until another is picked, when:
  - it's one of the games folders, holds one or sits in one: "The data folder can't be a games folder, nor hold
    one or sit in one. Pick another." (the first pull would copy the games into the app). This goes by the folders'
    document IDs, such as `primary:PS1/games`; a cloud provider's opaque IDs are only caught when they're the same
    folder. A BIOS folder inside the data folder is fine. Going back and adding a games folder that overlaps the
    data folder is refused the same way ("A games folder can't be the data folder, nor hold it or sit in it. Pick
    another.").
  - its storage can't rename files: "This folder's storage can't replace files safely. Pick another." The check
    creates `.goosestation-probe.goosetmp` at the top of the folder, reads whether it can be renamed, and deletes it
    (a leftover is cleaned up as any `.goosetmp` is). A folder where no file can be created gets "GooseStation can't
    create files in this folder. Pick another."

Finish starts the core and shows the **library**: a grid of covers by default, or a list (the **List** and **Grid**
button switches, and the choice is saved as `[UI] GameListGridView` in `settings.ini`). **Settings** opens the
settings (see "Changing settings"), and **Refresh** scans the games folders again. The library also refreshes when
the app comes back to the foreground. A, Enter or a tap starts the selected game; Back leaves it. The tile for the
last game played is focused when the library comes back. A tile shows the game's cover from `covers/` in the data
folder, or its title when there's none; a list row shows the title, region, serial and play time.

The library lists what the core's game list finds in the games folders, as on desktop: disc images (CUE/BIN, CHD,
ISO and the rest), M3U playlists, and PS-X EXE files. Disc images in the same folder as a CUE resolve by name, with
a case-insensitive match when the case differs (`FILE "TRACK 01.BIN"` next to `Track 01.bin`), and names holding
`#`, `%` or `?` work as they are.

**A multi-disc game is one tile** (and one list row). Its discs are found, in this order (`AndroidDiscGroups`):

1. **An M3U playlist** takes the discs it lists: the playlist is the game, and its discs have no tile of their own,
   even when they're also one of the database's disc sets (Ace Combat 3's two CHDs and their M3U are one tile, the
   M3U).
2. **The database's disc sets**, as the core makes them: disc 1 and at least one other disc. A second file for a disc
   already in the set (a CUE beside a CHD) keeps a tile of its own.
3. **File names**, for discs the database doesn't know: files in one folder whose names are the same, ignoring case,
   once a disc marker is taken out. Markers are `(Disc N)`, `[Disc N]`, `Disc N`, the same with `of M`, and the same
   with `CD`, in any case; "Discworld" and "Disc Jockey" aren't. Two files with the same name and disc number aren't
   grouped at all: they're different dumps.

The tile shows the game's title without its disc marker (the set's name, for a database set), disc 1's cover, and
the play time of all its discs. It starts on the disc last played in it on this device (`lastDisc.<key>` in the
app's `game` preferences), or disc 1. The game's **key** is the serial its save states and patch codes are kept under:
the set's first serial, the playlist's first disc's, the lowest disc's for a name group (its content hash, `HASH-…`,
without a serial), or a single disc's own; the list row shows it as the serial.

In a game, Back opens its menu (see "The in-game menu"). Home pauses the game, and
reopening the app resumes it. Pausing also writes any memory card changes to their files
straight away (a paused game never reaches the card's usual save, 5 emulated seconds after the last write), so a save
made just before Home survives the app being killed in the background. Going to the background, or losing focus to
the notification shade or a dialog, also lets go of every button and stick, so press or move them again after
coming back.

In a game, the app keeps only the controllers' gamepad buttons and D-pad keys. Volume, media and other keys go to
the system, so the volume keys work during a game.

Never commit BIOS images or games. The checks below use `~/work/goose-test/ps-30j.bin` and the CHDs from the same
folder.

### The folder settings

**Settings** → **Folders** in the library opens them, with the same D-pad rules as setup:

- **BIOS folder**: **Change** picks another folder, which replaces the old one only if it holds a BIOS image.
  **Rescan** copies the images in the same folder again, after you add one to it.
- **Games folders**: **Add folder**, and **Remove** next to each one. The library is scanned again after either.
- **Data folder**: **Change** picks another folder, then asks what to do with it. A folder setup would refuse (see
  above) gets no choice: the screen says why, with **Pick another** and **Cancel**. **Add folder** under the games
  folders refuses one that overlaps the data folder, in the same way.
  - **Copy this device's data into it**: the settings, memory cards and save states here go into the new folder,
    over its files of the same name.
  - **Use the data already there**: the data here is replaced with the new folder's. The old data folder keeps its
    copy. If the old folder can't be reached already (or its grant is gone), the choice says up front that changes
    not in it are lost, and one press goes ahead. If it can be reached but the push of the latest changes to it
    fails at the moment of the change, nothing changes yet: the choice is shown again with that warning, and only a
    second press goes ahead.

  Either way, the app first saves its latest changes to the old folder, if it can be reached, and then restarts:
  the core's data root can't change while it runs. If the change fails, nothing is saved, and the old folder stays
  the data folder.

Folders the app no longer uses have their grants released, unless another saved folder still needs the same one.

### The data folder and its mirror

The core reads and writes `files/data` in the app's internal storage (the working copy). The app keeps it in step
with the data folder, and remembers what it last saw of both in `files/mirror-manifest.txt`. The BIOS copies are in
`files/bios`. The working copy is private, but a debug build can be inspected with `run-as`:

```bash
adb shell run-as io.github.ilyalissoboi.goosestation ls -R files/data
adb shell run-as io.github.ilyalissoboi.goosestation cat files/mirror-manifest.txt
```

The rules:

- **Only GooseStation's own data is mirrored.** That is `settings.ini`, `playtime.dat` and
  `custom_properties.ini` at the top of the folder (desktop's `secrets.ini` counts as GooseStation's, so it raises no
  banner, but it's never copied either way: Android keeps its RetroAchievements token in the Keystore), and everything in the desktop data folder's folders: `bios`,
  `cheats`, `covers`, `dump`, `gameicons`, `gamesettings`, `inputprofiles`, `memcards`, `postprocessing` (the
  post-processing favourites), `savestates`, `screenshots`, `shaders`, `textures`, `resources` and `videos`. Memory card sync's own data, `emusync/`, stays on
  the device, like the cache: it holds this device's sync identity and the storage's key, so it's never copied either
  way, and a copy an earlier build left in the data folder is left alone. A copy an earlier build pulled into the
  working copy is another install's identity, so it's deleted once, before the core first starts
  (`EmusyncCarryOver`); and it's left out of backups and device-to-device transfer (`data_extraction_rules.xml`).
  emusync's scratch files beside a card it's writing (`*.emusync-tmp`, `-old`, `-swap`) stay out too.
  Once the core has started, the folders its `settings.ini` names inside the data folder (a `[Folders]` entry, or
  `[MemoryCards] Directory`, such as `mycheats`) and the shared memory cards are mirrored too; the app asks the core
  for them, and pulls again while that adds any (a game settings folder, then the cards its files name), at most 4
  times. These catch-up pulls run before a game can start: at a cold start the library says "Copying your data from
  the data folder…" until they're done, and when a pull on coming back adds paths, a game started before its
  catch-up is done is refused with the same message. `cache/` (and the core's cache folder, wherever it is) and
  `goosestation.log` stay on the device. **Anything else in the data folder is left alone**: a `games/` folder, say,
  is never copied in, never written to and never deleted, and the mirror doesn't even list what's inside it. Folder
  names are matched as the desktop writes them, in lower case: a `Memcards/` folder counts as something else. Files
  outside the layout in the working copy (an earlier build copied in everything) are deleted from the working copy,
  never from the folder, once the core has said where its folders are and a complete pull (every file it planned)
  has found nothing more for the layout, and their manifest entries go too; only those
  in step with the folder, though. One changed since the last run, or never mirrored (a card in a folder
  `settings.ini` no longer names, say), stays in the working copy, as it may hold a change the folder lacks. A
  leftover `.goosetmp` or `.goosebak` in a folder that is no longer part of the layout isn't cleaned up: it stays in
  the data folder under that name.
- **The folder is the source of truth.** The app pulls (folder to working copy) at start, before the core reads
  `settings.ini`, and whenever it comes back to the foreground. A file that is new or changed in the folder is copied
  in, even over a change of the working copy's, and a file deleted from the folder is deleted from the working copy.
  There is no conflict detection: when both sides changed, the folder wins. A file copied in keeps the folder's time
  for it, as the in-game menu shows a save state's time from its file; one the folder's provider gives no time gets
  the time of the copy.
- **Memory cards are never replaced under a running game.** The memory cards are the files in the core's memory
  card folder (`[MemoryCards] Directory`, `memcards/` by default) and the shared cards `[MemoryCards] Card1Path` and
  `Card2Path` name, wherever they are in the data folder: the app asks the core once it has started, and again after
  `settings.ini` is reloaded, and uses `memcards/` until then. The game's own card writes go out during the game,
  as any other file's (on the emulator, a game's save reached the folder in 3.9 s). Only a card the folder changed
  as well waits: while the game runs, nothing replaces or deletes it in the working copy, and nothing is sent over
  the folder's version. When the game stops, the folder's version comes in and wins, so the game's writes to that
  card since the folder's change are lost.
- **Pushes** (working copy to folder) run every 2 s while the app is in the foreground, when it goes to the
  background, and when a game stops. A file is sent once it has been unchanged for 2 s, so it isn't sent mid-write.
  The push on going to the background and at a game's stop doesn't wait. Where the folder's side changed as well,
  the folder wins, as in a pull, and its file is copied in instead.
- **No half-written files.** A file is replaced by writing `<name>.goosetmp`, renaming the old file to
  `<name>.goosebak`, renaming the temporary file into place, then deleting the `.goosebak`. A leftover `.goosetmp` or
  `.goosebak` (the app was killed between the steps) is cleaned up on the next run; a file whose only copy is its
  `.goosebak` gets it back. Neither kind is ever mirrored, nor is `cache/`, which the core rebuilds. A provider that
  can't rename documents gets no write at all: written in place, a write cut short would leave a short file that the
  next pull took as the folder's change. Setup and the data folder change refuse such a folder; if one gets through
  anyway, pushes stop as for an unreachable folder ("The data folder isn't reachable (its storage can't replace
  files safely)…"), and every change stays on the device.
- **Neither side is acted on unless it's listed in full.** A data folder still loading, a working copy that's
  missing, or a folder in it that can't be read, stops the run before it does anything: listed as empty, its files
  would read as deleted, and be deleted on the other side. A fresh install's working copy is created before the
  first pull; one that has gone missing is created again, and its manifest deleted, so the folder's files all come
  in.
- **Changes are kept when the folder is gone.** If the folder can't be reached (the SD card is out, or the folder was
  moved), the run stops without touching the folder, and every change stays in the working copy until a later run
  reaches it.

The library shows a banner when something is wrong, and the folder settings show the data folder's state:

- **A grant is gone** (the folder was deleted through the system's Files app, say; see "Known limits"): "Can't
  read your BIOS folder. The copies already made still work.", "Can't read <folder>. Its games are hidden until you
  pick it again." (with **Remove folder** as well), or "GooseStation can't reach your data folder. Changes are kept
  on this device until you pick it again." Each has **Pick again**. Picking the same folder restores it; another one
  replaces it (for the data folder, through the folder settings' choice above). The app never drops a folder on its
  own, nor falls back to another place.
- **The data folder can't be reached**: "The data folder isn't reachable (…). Your changes are kept on this device and
  saved to the folder when it's back." It goes once a run reaches the folder again.
- **Changes aren't reaching the folder**: when two pushes in a row reached the folder but couldn't save every change
  to it (a file failed, or a side couldn't be listed in full), "Some changes couldn't be saved to the folder.", in
  the library and as the folder settings' data folder status. The changes stay on the device, and every push tries
  again; it goes once one saves everything. After one such push, the status says "Saving…".

### Moving Plan 1's data

Plan 1's builds kept everything in the app's external files folder,
`/sdcard/Android/data/io.github.ilyalissoboi.goosestation/files`: games in `games`, and the data root in `data`. The
app doesn't use either any more, and doesn't delete them. To keep Plan 1's data, copy the contents of `files/data`
into the new data folder **before** setup picks it, except `bios`: its images go in the BIOS folder, and the games in
a games folder. Setup then says the folder holds GooseStation data, and the first start pulls it in.

`adb` can't make this copy. The core writes its files as temporary files renamed into place, so they're mode 0600:
on the Thor, `adb shell cp` and `adb pull` of `settings.ini` and the memory cards both failed with "Permission
denied", and `run-as` can't reach external storage. Copy the files on the device with a file manager that can open
`Android/data`, or from a computer over USB. Neither way was checked for this plan.

Plan 1's `settings.ini` has no `[Android] InstallID` (and `SettingsVersion = 1`, which is upgraded to 3), so the first
start with it adapts it as a file from another install (see "Changing settings"): the renderer goes back to
`Automatic`, the Port 1 bindings are reset (each connected pad adds its own again), and the game folders are replaced
by the ones setup picked. That happens once; other settings are kept.

### Changing settings

**Settings**, in the library's top bar, opens a hub with twelve pages, in desktop's order: **Interface**, **Console**,
**Emulation**, **BIOS**, **Graphics**, **Post-processing** (below), **Audio**, **Controllers**, **Memory cards** (see
"Memory card sync"), **Achievements** (see "RetroAchievements"), **Advanced** and **Folders** (the folder settings
above). Every page works with a D-pad
alone, as setup does: the first row is focused when a page opens, A or Enter activates, and B or Back goes back. Back
from a page focuses its entry in the hub, and Back from the hub focuses **Settings** in the library. A long page is
split into desktop's sections, each under its name in small capitals (a heading, never a stop for the focus).

- **A setting with a few values** shows its current one between two arrows: left and right step through the values,
  stopping at the first and the last, and each step is the change; a tap on an arrow steps its way. A, Enter or a tap
  on the row open its choices as a list under it, in the page: each value with its note, the current one marked and
  focused. Up and down move through the list (the focus stays in it), A picks a value and closes the list, and B,
  Back or a tap on the row close it with nothing changed; a tap elsewhere closes it and does what it would have. One
  list is open at a time. A value's own note, such as "made for NTSC-J", shows under the row while it's the current
  one.
- **A number, or a long ladder of values** (speeds, volumes, sizes, counts) looks the same: left and right step by
  the row's step and stop at its ends, a held key repeats, and A opens a list of the common values with the current
  one among them. A value off the row's steps (`EmulationSpeed = 1.33` from desktop) shows as itself ("133%"), and
  a step goes to the nearest one on the steps that way.
- **An on/off setting** is a square toggle, which A, Enter or a tap flip. Touch works too.
- **A row that does nothing as things are set is left out**, as Big Picture leaves it out: the hardware renderers'
  rows under Software, PGXP's options with PGXP off, the frame time buffer without reduced input latency, a slot's
  shared card unless its type is Shared, and so on. Turning the setting they depend on on shows them under it.
- **Hardcore mode** (`[Cheevos] ChallengeMode` while signed in) is what the core makes of it
  (`Settings::FixIncompatibleSettings`): Rewind shows disabled, "Off in hardcore mode", and skipped by the focus; a
  speed below 100% says "Runs at 100% in hardcore mode."; an overclock below 100% says "Off in hardcore mode below
  100%.". Run-ahead isn't refused.

- **The hub opens whenever the folder settings could**: even when the core failed to start, or during setup's first
  pull, so the folders can still be picked again. The other pages need the core: until it has started (and its first
  catch-up pulls are done, see "The data folder and its mirror"), they're shown disabled, with "Available once
  GooseStation has started.", and the hub's focus starts on Folders.
- **Settings change only from the library**, never while a game runs, so every change takes effect at the next game
  start. A game's own settings (below) are the exception: the running game's change from its menu.
- **Each change is saved at once**, to the working copy's `settings.ini`, which the core then applies
  (`System::ApplySettings`); the next push, within 2 s, takes it to the data folder. The pages use desktop's keys and
  values, so the file stays one desktop can read. Only the setting changed is written: opening a page writes nothing.
- **The choices are the core's own**: each list of names (renderers, filters, aspect ratios, card types and the rest)
  comes from the core's `Settings::Get…Name` and `Get…DisplayName` (`NativeLibrary.getSettingNames`), as desktop
  shows them. A value in `settings.ini` the core doesn't name is shown marked "(from settings.ini)", and kept until
  another is picked.
- **The defaults are the core's Android ones**, which a new `settings.ini` holds (`SettingsAccessTest` checks each
  page's against it). Big Picture's own rows differ in a few places, and the pages don't copy them: the turbo speed
  defaults to 0 (unlimited), the fast-forward volume to 100% with a maximum of 100%, the vertex cache to off, and the
  depth clear threshold is `[GPU] PGXPDepthClearThreshold` (Big Picture's row writes `PGXPDepthBuffer`).

| Page | Section | Rows (`[Section] Key`) |
| --- | --- | --- |
| Interface | | Pause on start (`[Main] StartPaused`: the game boots paused, with its menu open), pause when a controller disconnects (`PauseOnControllerDisconnection`: the running game opens its menu, as Back does, and says "<name> disconnected"), save state on exit (`SaveStateOnExit`), create save state backups (`CreateSaveStateBackups`, off on Android: saving over a slot keeps the old state as `<key>_<slot>.bak`; the resume state never has one) |
| Console | Console | Region (`[Console] Region`), 8 MB RAM (`Enable8MBRAM`), disable all enhancements (`[Main] DisableAllEnhancements`) |
| Console | CPU | Execution mode (`[CPU] ExecutionMode`), overclocking (`OverclockEnable`) and its percentage, 10–1000% by 10 (written as desktop's fraction, `OverclockNumerator`/`OverclockDenominator`, 150% as 3/2), recompiler ICache (`RecompilerICache`) |
| Console | CD-ROM | Read speed-up (`[CDROM] ReadSpeedup`: 1 "None (double speed)" to 10 "10x (20x speed)"), seek speed-up (`SeekSpeedup`: 0 "Instant", 1 "None (normal speed)", to 10x), readahead (`ReadaheadSectors`, 0 "Off" to 32 sectors), preload disc to RAM (`LoadImageToRAM`), apply image patches (`LoadImagePatches`) |
| Emulation | Speed | Emulation, fast-forward and turbo speed (`[Main] EmulationSpeed`, `FastForwardSpeed`, `TurboSpeed`: desktop's ladder, "Unlimited" (0), 10% to 1000%, written as factors such as `1.25`) |
| Emulation | Frame pacing | VSync (`[Display] VSync`), sync to host refresh rate (`[Main] SyncToHostRefreshRate`; the app gives the core the screen's refresh rate with the surface), optimal frame pacing (`[Display] OptimalFramePacing`), reduce input latency (`PreFrameSleep`) and its frame time buffer (`PreFrameSleepBuffer`, 0–20 ms by 1), skip duplicate frames (`SkipPresentingDuplicateFrames`, left out with VSync and sync to host both on) |
| Emulation | Rewind | Rewind (`[Main] RewindEnable`; "Off while run-ahead is on." with run-ahead), save frequency (`RewindFrequency`: "Every frame", 0.05 s to 60 s), save slots (`RewindSaveSlots`, 1 to 10000, with the core's estimate under it: "Goes back up to 100.0 s, using up to … MB of memory and … MB of video memory.") |
| Emulation | Run-ahead | Run-ahead (`[Main] RunaheadFrameCount`, "Off" to 10 frames) |
| Graphics | Rendering | Renderer (`[GPU] Renderer`: Automatic, Vulkan, OpenGL, Software; noted "Vulkan where it works well, else OpenGL ES" and "OpenGL ES"), internal resolution (`ResolutionScale`, "Match the screen" (0), "1x (native)", to 16x), downsampling (`DownsampleMode`) and, for Box, its scale (`DownsampleScale`), multisampling (`Multisamples` with `PerSampleShading`: Off, 2x/4x/8x MSAA, 2x/4x/8x SSAA; the device's maximum applies), threaded presentation (`ThreadedPresentation`, Vulkan and Automatic), threaded rendering (`UseThread`, Software) |
| Graphics | Enhancements | Texture and sprite texture filtering (`TextureFilter`, `SpriteTextureFilter`: unset, the texture filter's), true colour (`TrueColor`) with debanding (`Debanding`) or else scaled dithering and accurate blending (`ScaledDithering`, `AccurateBlending`), widescreen rendering (`WidescreenHack`), round upscaled texture coordinates (`ForceRoundTextureCoordinates`, above 1x), line detection (`LineDetectMode`), force NTSC timings (`ForceNTSCTimings`), disable interlacing (`DisableInterlacing`), FMV chroma smoothing (`ChromaSmoothing24Bit`), software renderer for readbacks (`UseSoftwareRendererForReadbacks`), use old MDEC routines (`[Hacks] UseOldMDECRoutines`) |
| Graphics | PGXP | Geometry correction (`[GPU] PGXPEnable`), then perspective-correct textures and colours (`PGXPTextureCorrection`, `PGXPColorCorrection`), culling correction (`PGXPCulling`), preserve projection precision (`PGXPPreserveProjFP`), depth buffer (`PGXPDepthBuffer`) and its clear threshold (`PGXPDepthClearThreshold`, 0–4096 by 10), CPU mode (`PGXPCPU`), vertex cache (`PGXPVertexCache`), disable on 2D polygons (`PGXPDisableOn2DPolygons`), geometry tolerance (`PGXPTolerance`: "Off" (-1), 0 to 100 px by 0.25) |
| Graphics | Display | Aspect ratio (`[Display] AspectRatio`, every name the core has, Custom with its width and height, `CustomAspectRatioNumerator`/`Denominator`), deinterlacing (`DeinterlacingMode`), crop (`CropMode`), scaling (`Scaling`), screen position (`Alignment`), rotation (`Rotation`), stretch vertically (`StretchVertically`), force 4:3 for FMVs (`Force4_3For24Bit`) |
| Graphics | On-screen display | Show messages (`[Display] ShowOSDMessages`: the core's messages, which reach the app's message layer only while it's on; the app's own still show), show FPS, speed, resolution, CPU usage, GPU usage, GPU statistics, latency, frame times and controller input (`ShowFPS`, `ShowSpeed`, `ShowResolution`, `ShowCPU`, `ShowGPU`, `ShowGPUStatistics`, `ShowLatencyStatistics`, `ShowFrameTimes`, `ShowInputs`), statistics size (`OSDScale`, 25–500%: the core's statistics, not the app's messages) |
| Graphics | Screenshots | Size (`[Display] ScreenshotMode`), format (`ScreenshotFormat`), quality (`ScreenshotQuality`, 1–100%) |
| Graphics | Texture replacement | VRAM write replacements (`[TextureReplacements] EnableVRAMWriteReplacements`, from the data folder's `textures/`) and preloading them (`PreloadTextures`) |
| Audio | Volume | Output volume (`[Audio] OutputVolume`, 0–200%), fast-forward volume (`FastForwardVolume`, 0–100%), mute (`OutputMuted`), mute CD audio (`[CDROM] MuteCDAudio`) |
| Audio | Output | Stretch mode (`[Audio] StretchMode`), buffer size (`BufferMS`, 10–500 ms, 100 on Android), minimal output latency (`OutputLatencyMinimal`) or else output latency (`OutputLatencyMS`, 1–500 ms) |
| Memory cards | Cards | After Manage cards and sync: card 1 and card 2 type (`[MemoryCards] Card1Type`, `Card2Type`), each Shared slot's card (`Card1Path`, `Card2Path`: "shared_card_<n>.mcd (the default)" (empty, as no key is), then the memory card folder's `.mcd` files), one card for all discs of a game (`UsePlaylistTitle`) |
| Achievements | | After live indicators: encore mode (`[Cheevos] EncoreMode`), spectator mode (`SpectatorMode`), test unofficial achievements (`UnofficialTestMode`), and while messages show, unlock and leaderboard message time (`NotificationsDuration`, `LeaderboardsDuration`, 3–30 s; the unlock card lasts that long) |
| Advanced | Logging | Log level (`[Logging] LogLevel`, the core's levels, Information by default; logcat gets the core's messages at that level), log to file (`LogToFile`: `goosestation.log` in the data folder, and the last run's as `goosestation-previous.log`, below) |
| Advanced | Save states | Save state compression (`[Main] SaveStateCompression`, Zstandard (Default) by default), load devices from save states (`LoadDevicesFromSaveStates`) |
| Advanced | CD-ROM | CD-ROM region check (`[CDROM] RegionCheck`), allow booting without an SBI file (`AllowBootingWithoutSBIFile`: the core's question about it is answered yes on Android) |
| Advanced | CPU | Recompiler memory exceptions (`[CPU] RecompilerMemoryExceptions`), block linking (`RecompilerBlockLinking`), fast memory access (`FastmemMode`, MMap by default; disabled, "This device's memory pages are too large for it.", where the host turns it off) |
| BIOS | | NTSC-U (Americas), NTSC-J (Japan), PAL (Europe) (`[BIOS] PathNTSCU`, `PathNTSCJ`, `PathPAL`): "Automatic: <the image the core picks>" (no key, the default), or "Automatic: none found"; then each image copied from the BIOS folder, by its description, noted "made for <region>" when it's for another region. Fast boot (skip the BIOS intro) (`[BIOS] PatchFastBoot`, on on Android) |

The internal resolution's note, for any value but 1x, says "Higher is sharper and slower. Match the screen picks the
highest that fits." The BIOS page ends with "The images come from your BIOS folder. Change it, or rescan it, in
Folders." A region whose image the BIOS folder no longer has, after a **Change**, a **Rescan**, a re-pick or setup's
import, goes back to Automatic: its key is deleted, with no prompt (`Back to Automatic, as the BIOS folder no longer
has their image: [NtscU, Pal]`, say, in the log). After setup that waits until the core has started, since only
the core reads and writes `settings.ini`. Controllers is under "Controllers" below.

A new `settings.ini` from the core holds a custom aspect ratio of 4:3 (`CustomAspectRatioNumerator = 4`,
`CustomAspectRatioDenominator = 3`). A file from desktop can still hold a term of 0: the page shows the 1 the core takes
it as.

**Log to file** writes the core's log to `goosestation.log` in the working copy, as desktop writes it in its data
folder. While it's on, the core names the log, and the log the last run left, as files of the data folder's layout,
so the mirror copies both to the data folder with its pushes (after a game, and on leaving the app). They're only
ever sent: a pull never takes the folder's over them, since desktop sharing the folder (and `settings.ini`'s Log to
file) writes a `goosestation.log` of its own there, and the core holds this one open. At each start,
before the core opens the log, the host renames the last run's `goosestation.log` to `goosestation-previous.log`, so
the log of a run that crashed still reaches the data folder. Off, both stay on the device, as before. Leaving the
Advanced page has the mirror take the change.

**Every other setting** is changed by editing `settings.ini` in the data folder, on the device or from a computer:
developer tools (wireframe, the GPU's debug and disable switches, VRAM write dumps, `[Hacks]` but old MDEC routines,
TTY logging, `[PCDrv]`, the display offsets, the log filter, desktop's other Advanced tweaks), input profiles, cheats (patch codes cover them), and the
hotkeys the Controllers page leaves out (see "Controllers").
Then switch to the app, or reopen it: the pull on coming back copies the file in, and the core reloads it, then scans
the library again. While the app stays in front, the next push (within 2 s) also notices the change and copies it in.
Edit the file the app wrote, so it keeps its `[Android] InstallID` (see below). For example, to turn on the GPU's
wireframe view:

```bash
adb pull /sdcard/GooseStation/data/settings.ini /tmp/settings.ini
# In /tmp/settings.ini: under [GPU], WireframeMode = OverlayWireframe.
adb push /tmp/settings.ini /sdcard/GooseStation/data/settings.ini
adb shell input keyevent KEYCODE_HOME
adb shell am start -n io.github.ilyalissoboi.goosestation/.MainActivity
```

- `[Main] SettingsVersion = 3` must be there, or the core resets the file to its defaults. Desktop and Android use
  the same version (`Settings::SETTINGS_VERSION`), and Android upgrades Plan 1's version 1 files.
- Keep `[Android] InstallID`. A file without this install's ID is taken as one from another install, and its
  device-specific keys are rewritten, `Renderer` among them.
- Every setting the file leaves out takes its default.
- **A `settings.ini` from another install** (desktop, another device, or a reinstall, which gets a new ID) is
  adapted the first time this install reads it. The file records the install it was last adapted for in
  `[Android] InstallID`; when that isn't this install's, the device-specific keys are rewritten: the renderer goes back
  to `Automatic`; `[GameList]` paths are replaced by the games folders; the BIOS image choices are dropped, and the
  BIOS search folder is the app's copy folder; the input sources, Port 1 bindings and hotkeys are reset to their
  defaults, and `[Android] ControllerDescriptors` is deleted, so every controller is new here and adds its own
  bindings; and any folder outside the data folder (an absolute path elsewhere, or a Windows path such as
  `C:\Users\…\memcards`) is set back to its default under the data folder. Everything else, such as the OSD or
  enhancement settings, is kept. The adapted file is saved with this install's ID, and goes back to the data folder
  with the next push.
- `[GPU] Renderer` is `Automatic` (the default), `Vulkan`, `OpenGL` or `Software`. On Android, `Automatic` prefers
  Vulkan, as on Linux: `VulkanDevice::IsSuitableDefaultRenderer()` takes the first Vulkan GPU unless it's a software
  one (its name starts with `llvmpipe` or `SwiftShader`) or an Intel one, and OpenGL ES otherwise. `Vulkan` and
  `OpenGL` (OpenGL ES on Android) force one. If the device is created but the hardware renderer fails, the core
  falls back to its software renderer.
- **If the Vulkan device can't be created**, with `Vulkan`, or `Automatic` where it means Vulkan, the game boots on
  OpenGL ES instead, straight away. The log has `Vulkan didn't start (…), using OpenGL ES until the app restarts.`,
  the OSD says "Vulkan didn't start on this device, so GooseStation is using OpenGL ES.", and the Graphics page's
  Renderer row says "Vulkan didn't start on this device, so games use OpenGL ES until the app restarts." That lasts
  for the rest of the process: later hardware-renderer games go straight to OpenGL ES, and a settings reload doesn't
  switch back. `settings.ini` keeps the choice the user made, so the next start of the app tries Vulkan again, once.
  The device's API changes, not the renderer: `Software` stays the software renderer, on an OpenGL ES device for that
  game (a later `Software` game tries Vulkan for its device again).
- **To test the fallback**, `adb shell setprop debug.goosestation.fail_vulkan 1` makes the next Vulkan device fail
  (`Failing the Vulkan device for testing (debug.goosestation.fail_vulkan).`), as long as Vulkan hasn't failed yet in
  this process. Set it before starting a game, since any Vulkan device created while it's set fails, and set it back
  with `adb shell setprop debug.goosestation.fail_vulkan 0`. Only `adb shell` can set `debug.` properties.
- `ShowFPS`, `ShowSpeed` and `ShowCPU` turn on the OSD's frame rates (`G:` game, `V:` video), speed and frame times.
  With the default CPU settings, `ShowCPU` adds a `CPU[I]` line when the CPU falls back to the interpreter, and none
  on the recompiler.
- The Port 1 bindings live in this file too, with `[Android] ControllerDescriptors` (see "Controllers"). Leaving
  them out is fine: each controller adds its default bindings the first time it's seen.

### Post-processing

**Post-processing**, after Graphics in the hub, edits the core's two shader chains, as Qt's two tabs do, one at a
time: its **Chain** switch, first, picks **Display** (`[PostProcessing]`, over the picture at the screen's resolution)
or **Internal** (`[InternalPostProcessing]`, at the internal resolution, before the picture is scaled), with a line
saying which is which, and opens on Display each time. Under it, the chosen chain's **On** switch (`Enabled`), its
stages in order ("1. crt-lottes", with the shader's kind and how many options it has), and **Add a shader**; both
chains stay in the files as desktop has them. Every edit is the core's own `PostProcessing::Config` call on `settings.ini`, so the keys are
desktop's: `StageCount`, and `[PostProcessing/Stage1] ShaderName` with the stage's options beside it.

- **Add a shader** opens the **shader browser**: a search field, then the shaders that work here by folder, each with
  its kind: the APK's (the repository's `data/resources/shaders`), the data folder's `shaders/` and the app's own
  packs (below), as desktop finds them (`.glsl` files, a `dolphinfx/` one being kind dolphinfx; ReShade's `.fx` under
  `shaders/reshade/Shaders`, shown under `reshade/`). Folders come first, then shaders; A opens a folder or adds a
  shader as the chain's last stage, and the focus goes to it. B clears a search, then goes up a folder (an "Up to …"
  row does too), then closes. The search, typed with Android's keyboard (A on the field opens it), lists every shader
  whose path holds the text, ignoring case, with its folder beside it. The browser opens on the folder of the last
  shader added (this device's own preference), or the nearest folder still there; its rows are a lazy list, so a
  pack's thousands of presets cost nothing until they show. A shader whose metadata doesn't load isn't offered.
  **Slang presets** (RetroArch's `.slangp`, run by librashader, which the APK carries) are listed by their path with
  the extension (`shaders_slang/crt/crt-royale.slangp`, kind Slang), never loaded to list them; one that doesn't load
  says why when it's added. A slang stage's options are the preset's parameters, and it shades the game's picture at
  the console's resolution (320×240, say), drawing the result where the picture sits. A stage's name is looked up in
  the data folder's `shaders/` first, then in the app's packs.
- **Download shader packs**, after the chain, opens a panel over the page: the four packs of the core's table (libretro's
  slang shaders, Retro Crisis GDV-NTSC, satpixie CRT and lola-retro), each with its state ("Not installed",
  "Installed …", "Update available: … → …", "Incomplete: install it again"), what it is, its licence and its size,
  under "Third-party content under its own licences.". Their versions are checked against GitHub when it opens
  (until a check works: offline, an installed pack says "couldn't check for updates" and can still be removed). A on a
  pack shows Install, Update or Remove under it (Remove asks first, in the panel; removing libretro's warns when Retro
  Crisis stays). While a job runs its step and a progress bar show ("Downloading libretro slang shaders… 45%"; a
  download whose size GitHub doesn't give moves along the bar as it comes), with Cancel, and the packs say they're
  busy. B closes the panel; the job is the app's, so it goes on, its step shown on the Download shader packs row, and
  the pages read their shaders again when it ends. The
  core does the work, as desktop's does: dependencies first (Retro Crisis brings libretro's), the archive **streamed
  to a file** through HttpBridge (never held in memory), checked, the previous version's files removed, extracted with
  the path hardening and size caps, and a marker (`.shaderpacks/<id>.json`) listing exactly the files written.
  **Packs live in the app's own storage** (`files/shaderpacks`), never in the data folder: they're not mirrored, and
  "use the folder's data" doesn't touch them. Each device has its own.
- **The mirror leaves a desktop's packs out**: at each run it reads the data folder's `shaders/.shaderpacks/*.json`
  (through the core's marker parser), and neither those markers nor any file they list is copied either way; copies
  an earlier build pulled in are dropped from the working copy. The player's own shaders beside a pack's are mirrored
  as before.
- **Favourites**, under the Display chain (the Internal chain has none), are saved display chains, shared with
  desktop through the data folder: each is a file of its own, `postprocessing/<name>.ini`, holding the chain as
  `settings.ini` does (`[PostProcessing]` with `Enabled` on, and its `[PostProcessing/StageN]` sections), and their
  order is `settings.ini`'s `[PostProcessingFavourites] Order` (a file copied in by hand goes after the ordered ones).
  Each shows numbered in order ("1. CRT royale + border") with its shaders, or "Not installed: …" naming those this
  install hasn't got (it applies anyway, and the stage then says it isn't found, as any such stage does). **Save as
  favourite**, after them ("No favourites yet: save the current chain as one." while there are none), asks a name in
  a panel: a name already taken, ignoring case, asks "A favourite named … already exists. Replace it?" (it keeps its
  place), and a name the core refuses (empty, a leading dot, or `/ \ : * ? " < > |`) says why in the form. A on a
  favourite opens its panel: **Apply** (first, focused; over a chain with shaders it asks "Apply …? It replaces the
  2 shaders in the chain."), **Rename…**, **Move up**, **Move down** and **Delete** (asking first). Questions take the
  panel's place with Cancel focused; B drops a question, then closes the panel, back to its row. On a game's page,
  saving takes the chain the game runs (its own, or the global one), and applying gives the game its own display
  chain. The core's `PostProcessing::Favourites` does the work, as on desktop.
- **The favourite hotkeys**, **Next Post-Processing Favourite** and **Previous Post-Processing Favourite**, are core
  hotkeys, so the hotkeys page offers them as Select chords (see "Controllers"). In a game they replace the display
  chain with the next or previous favourite, wrapping, saying "Post-processing: <name>." ("No post-processing
  favourites are saved." without any). They write where the running chain is: the game's file when it has a display
  chain of its own, else `settings.ini`, so the pages show what's on screen. The one after is the next after the last
  one a hotkey applied while the chain still equals it, else after the first the chain equals, else the first.
- **A stage's page** has **Move up**, **Move down** and **Remove** (no question, as desktop), then the shader's
  options, as desktop builds them from its metadata: an on/off option is a switch, an option with named values a
  choice, and a number a row per component ("Tint (Red)", "Tint (Green)"…), stepping by the shader's step (a
  hundredth of its range without one) and listing its minimum, default, maximum and the quarters between. An option
  with no name is hidden, as desktop hides it, and the shader's categories are the page's sections. A value off the
  steps shows as itself. The core writes each value, in its own format.
- **Reload shaders** shows in a running game's own settings (the global pages are locked while a game runs): the core
  reads the chains' shader files again, saying "Post-processing shaders reloaded.". Each game's boot loads them anyway.
- **What runs**, checked on the emulators (padtest at 1280×960, each shader alone):

| Shaders | Vulkan | OpenGL ES |
| --- | --- | --- |
| GLSL (`shaders/*.glsl`) | all run | all run |
| "dolphinfx" (`shaders/dolphinfx/**/*.glsl`, loaded as GLSL: this core has no loader of their own) | all run | all run |
| ReShade (`shaders/reshade/Shaders/**/*.fx`) | all run but `crt/crt-hyllian-sinc` | none compile |
| Slang presets (libretro's `crt-royale`, `crt-geom`, `crt-guest-advanced`, `ntsc-256px-svideo`; 2026-11-07) | all run | all run |

  So under OpenGL ES (the renderer, a game's own on its pages, or Vulkan having failed) no ReShade shader is offered, and a ReShade stage
  already in the chain (from desktop, say) says "Doesn't run with OpenGL ES: post-processing stays off while it's in
  the chain.": the core turns the whole chain off with "Failed to compile post-processing shader …". A stage whose
  shader this install hasn't got says "Not found in this install's shaders: …" in the same way. Both can still be
  moved and removed. On the emulator's GPU the slowest shader took 2.2 ms a frame (`edge-smoothing/super-xbr`); under
  SwiftShader, a CPU renderer, `crt-lottes` halved the frame rate. On the AYN Thor every shader keeps the full frame rate
  (see "Post-processing on the Thor" under its results).
- **For a game**, each chain is the game's own or the global one, as the core takes it: the game's file's chain
  applies, whole, only while the file sets that chain's `Enabled`. Its On row is "Use global (On)", On and Off.
  Picking On or Off gives the game its own chain: a copy of the global one, unless the file already has stages of its
  own (as Qt can leave a file). "Use global" deletes the game's chain (`Enabled`, `StageCount`, the stages), asking
  first when it has shaders ("Use the global Display chain? This game's 3 shaders are removed."). While a chain is the
  global one, its stages show dimmed, saying "The global chain: pick On or Off to give this game its own." The hub
  counts each chain the game sets as one. Desktop's Copy global settings doesn't copy the chains, and neither does
  this app's.

### A game's saves

**X** on a focused game in the library opens its **Saves** page; a long press, by touch, offers **Saves** and **Game
settings** (a game without a key opens Saves straight away). The library's foot says "A Play · X Saves · Y Game
settings". The page, in the app's window, has the game's title over "Saves", then the in-game Load panel's two tabs
(L1 and R1) and rows: **This game**, the resume state, slots 1 to 10 and Other saves, and **Shared**, desktop's
shared slots, each naming the game it holds. The page scrolls as one, with `KeyHints` "A Start · X Delete · L1 R1
Switch · B Back" at its foot. The first save is focused; with none, it says "No saves yet for this game.", and a
game without a key opens on Shared, its This game tab saying "This game can't keep save states of its own."

- **A** starts the game from that save, on the disc it was made on, as Resume does from the resume state. A shared
  slot starts the game it holds, which may be another; one the library doesn't have says which game it needs ("Shared
  slot 3 needs Crash Bandicoot (SCUS-94900), which isn't in your library."). When hardcore mode would start, no save
  can start the game: "Loading a state is off in hardcore mode".
- **X** (a long press, by touch) asks before deleting the save, in a panel over the page: Delete and Cancel, Cancel
  focused. It works in hardcore mode too, where the in-game Load panel stays closed. The focus goes to the next save.
- **B** goes back to the library, focusing the game's tile.

### Settings for each game

A game can have settings of its own, over the global ones, as on desktop. They open from its tile: **Y** on a focused
game in the library (or Game settings in its long press's choice, by touch), which the library's foot says ("A Play ·
X Saves · Y Game settings"); and, for the
running game, from **Settings** in its in-game menu (after Achievements). The library's opens only as the global
pages do, with no game under way; a file with no key (a PSF) has none, and its menu's Settings says so.

- **The hub** says "Game settings" under the game's title, its serial and region, and what its file sets ("3 settings
  of its own, and 4 this app doesn't show", or "No settings of its own"): the keys no page shows are desktop's own,
  or its bindings. Then its pages, in desktop's order, each saying how many it sets: **Console**, **Emulation**,
  **Graphics** (the on-screen display too, as on Qt), **Post-processing** (each chain as a whole, below), **Audio**,
  **Controllers**, **Memory cards** (the card types and shared cards; one card for all discs stays global),
  **Achievements** (the switches and the messages' times), **Advanced** (all but logging, which desktop's Copy keeps
  global). Interface, BIOS, Folders and the memory card editor are global only, and the in-game Settings never
  reaches them, nor any global page.
- **Copy global settings** and **Clear settings** each ask first, in a panel over the hub, Cancel focused. Copy is
  desktop's: the global settings as `Settings::Save(si, true)` writes them, over the game's own (so it leaves out what
  desktop keeps global: the on-screen display, the Interface rows, logging). Clear is Big Picture's: the game's file
  goes, with the keys this app doesn't show. In a running game in hardcore mode both wait until it stops (they
  rewrite `[Cheevos]`, whose Encore, Spectator and Unofficial modes would end it), saying so.
- **Every row can use the global setting**: a choice's first value is "Use global (<the global value>)", an on/off
  row is a choice of "Use global (On)", On and Off, and a number steps from the global value (Right from "Use global"
  makes the global value the game's own; Left from the lowest value goes back). Choosing "Use global" deletes the key;
  a file left empty is deleted, and the next push deletes it in the data folder too. A row the game sets says **Set
  for this game**, even when its value is the global one: it no longer follows it. A row of several keys (overclocking,
  multisampling, the unlock and leaderboard messages) uses the global setting as a whole.
- **In the running game** each change is saved and applied straight away (`System::ReloadGameSettings`). Region,
  8 MB RAM, Preload disc to RAM and Apply image patches say "Applies when the game next starts."; hardcore mode says
  "Turning it on applies when the game next starts." (turning it off is at once); and while hardcore mode is on,
  Encore, Spectator and Test unofficial achievements are disabled ("Change it before the game starts: now it would
  end hardcore mode."), since the core restarts the client for them. B from the hub goes back to the menu, on its
  Settings row; Mode resumes the game from anywhere in them.
- **The file** is `gamesettings/<key>.ini`, as desktop finds it for the game's key (`System::GetGameSettingsPath`):
  for a disc set the database knows, its first serial, shared with desktop. For an M3U or a name group the database
  doesn't know as a set, Android loads the key's file for every disc of the tile (`System::SetGameSettingsSerial`,
  Android only), as the tile keeps its saves under one key; desktop reading the same data folder applies it to that
  disc only, as it would anyway. A file desktop made under another disc's serial of such a group is left alone, and
  isn't read here.
- **Controllers for a game**: multitap, each port its multitap offers, with its type and the type's settings. The
  bindings stay global ("Bindings are the global ones: Settings → Controllers."). Android writes `[ControllerPorts]
  UseGameSettingsForController = true` while the game sets any of these, with only these keys, and deletes it once it
  sets none; the core reads a game's controller keys only with it, so the page does too.
- **What Android ignores in a desktop game file**, in memory only (the file is never rewritten by this, so desktop
  keeps them; the adapted copy is never saved): its `[PadN]` bindings and macros (a desktop file with per-game controllers holds desktop's whole
  bindings, `SDL-0/…`), `[InputSources]`, `[Hotkeys]`, a renderer Android doesn't have, BIOS names its BIOS folder
  hasn't, the BIOS search folder and an absolute memory card folder. The type and the type's settings still apply.
  A file naming desktop's input profile (`InputProfileName`) uses the global controllers here: its Controllers page
  says so and can't be changed, since writing `UseGameSettingsForController` would take desktop off the profile.

### Controllers

Each controller is on a port: Port 1 unless it's been put on another. The core knows each one as `Android-<n>`, and
its port's `[PadN]` bindings name it that way (`Cross = Android-0/Button96`, one entry per controller, as each binding
is a list). `[Android]` in `settings.ini` keeps three lists, one line per controller in the same order, whose position
is `<n>`: `ControllerDescriptors` (Android's input device descriptors), `ControllerPorts` (each one's `[PadN]` number,
1 to 8) and `ControllerRumble` (its rumble strength, 0 to 100). A missing entry is Port 1 and 100%. So a controller
keeps its number, port, strength and bindings across restarts, whatever order the controllers connect in. Desktop
ignores `[Android]`, as before.

- **The first time a controller is seen**, its descriptor is added to the list, and its default bindings to its
  port's: the log has `'<name>' is a new controller, Android-<n>.` and `Added 24 bindings for Android-<n> to Port 1.`
  A controller seen before gets nothing added, so a binding removed from it stays removed. The exception is one with
  no binding at all on its port (a `settings.ini` restored from an older copy, say): it gets its defaults again when
  it connects, and after a settings reload.
- **Two identical controllers that Android gives the same descriptor** are one controller to the app: they share a
  mapping and a port, and the Controllers page lists them once.
- **Controllers that share a name but not a descriptor** (two DualShock 4s, say) are numbered on the Controllers page
  by their place in the list, plus one: "Wireless Controller (1)", "Wireless Controller (2)". The numbers stay the
  same from one run to the next. A controller whose name no other connected one has is shown by its name alone.

**Settings** → **Controllers** opens on the hub:

- First, a card: "Back, the Mode (Home or Guide) button, or holding Select + Start for a second pauses the game and
  opens the menu. These buttons always do this, whatever is mapped to them."
- **Controllers**: each connected controller's port, a row named after it ("Connect a controller to set it up." with
  none). The ports offered, in desktop's order (a port's slots together), are Port 1 and Port 2, and, for a port that has multitap in `settings.ini` or in any game's
  file (`gamesettings/*.ini`, `[ControllerPorts] MultitapMode`), its four slots instead: Port 1A to 1D, Port 2A to 2D.
  A controller on a port nothing offers any more (multitap turned off everywhere) shows it as itself, "No game uses
  this port". Moving a controller moves its own `Android-<n>` entries from the old `[PadN]`'s keys to the new one's
  (macro triggers too); other sources' entries in those keys, a desktop's `SDL-0/…` say, stay where they are, in
  order. The new port's type then gets its defaults for each input the controller has none for. The port is global,
  never a game's. Under it, **Rumble strength** (0 to 100% by 10%), or "<name> has no motors Android can drive, so it
  doesn't rumble."
- **Ports**: **Multitap** (`[ControllerPorts] MultitapMode`, the core's modes), then a row per port offered, with its
  type and the controllers on it ("Analog Controller (DualShock) · Wireless Controller"), which opens its page.
- **Touch controls**: when the on-screen touch controls show, their **Size** and **Opacity**, and **Vibration** (see
  "Touch controls" below).
- **Hotkeys**, which opens the hotkeys page.
- Back from a port's page, or the hotkeys, focuses its row on the hub, and Back from the hub leaves the page.

**A port's page** (titled with its port, "Port 1" or "Port 1A"):

- **Controller type** (`[PadN] Type`): the core's types but those that need a pointer Android hasn't got (the GunCon,
  the Justifier and the mouse). A type a file names that the page doesn't offer (a desktop's GunCon) shows as itself,
  "(from settings.ini)", until another is picked. Picking a type gives each controller on the port that type's
  default bindings for each input it has none for: the bindings are kept for each type, as its inputs' keys, so a
  remapped Cross stays remapped from Analog to Digital and back, and a NeGcon's steering gets a default the first
  time. Not Connected (`None`) shows its type row and nothing else.
- **Settings**: the type's own (`Controller::ControllerInfo::settings`), as the core describes them: a switch for an
  on/off one, a list for one of a few options, and a number for the rest, stepped by the core's step and shown through
  its format ("25%" for a deadzone of `0.25`). The core's description is each row's note. A value desktop wrote off
  the steps shows as itself.
- **Bindings**: those of a controller on the port, as the rest of this section describes, with a **Controller** row
  when there's more than one on it. With none: "No controller is on Port 2. Pick Port 2 for one on the Controllers
  page." Then "Each controller on Port 1 keeps its own bindings." On a digital pad or DualShock each input shows as its
  PlayStation button: the face buttons as their symbols in their colours, the D-pad, the sticks with their direction,
  the shoulder buttons, Select and Start; screen readers say its name. Other types keep the names. Each row has the
  binding's name: `A`, `B`, `X`, `Y`, `L1`, `Start`, `D-pad up`, `Left stick left`, `Brake (left trigger)`, `Button
  1`, `Key <n>` for any other key, or `Not set`. Picking another controller shows "Reading <name>'s bindings…" in their
  place until its own are read.
- **Macros**: Macro 1 to 4, each with the inputs it presses ("Cross & Circle", or "Not set"), which opens its page.
- **Reset to defaults**, at the end, drops all of the controller's bindings on the port and its hotkeys, and adds its
  defaults again.

**A macro's page** ("Macro 1 · Port 1"): its **Trigger**, the controller's own binding for `[PadN] MacroK`, captured,
cleared and undone as a binding is; **Presses**, a switch for each of the type's inputs (`MacroKBinds`, written as
desktop writes it, "Cross & Circle" in the type's order, the key deleted when there are none; a name the type hasn't
got, from another type, is kept); and **Repeat**: **Repeats** (`MacroKFrequency`, "Doesn't repeat" or every 1 to 60
frames, how often a held macro lets go of its inputs and presses them again) and **Press to toggle**
(`MacroKToggle`). With no controller on the port, the presses and repeat can still be set.

On a binding, a macro trigger or a hotkey:

- A on a row asks for the input: "Press the new button for <input>… Back cancels (5)", counting down. The controller's
  next press, or a stick or trigger pushed past halfway, becomes that row's binding for this controller only; the other
  controllers' entries in the key stay. A button already held when it starts isn't taken, nor is an axis until it has
  been near rest (within a quarter): during the capture, or as the pad last reported it before the capture began. The
  app remembers each pad's latest axis values from every motion it sends, passed to the core or not, and sends them as
  the capture starts, so a quick flick of a centred stick is taken on its first push. A pad that hasn't sent any motion
  since it connected has no known values: its axes must come back to rest first, and so must a trigger resting at −1 or
  a stick still held. Left alone, the capture ends after 5 s with the binding unchanged. Back (the system's Back key or
  gesture) cancels it; B is captured like any other button.
- **Clear**, at the end of each row (Right reaches it), removes this controller's binding for it. Once a capture
  lands, **Undo** takes Clear's place on that row and puts back the binding the capture replaced, until the focus
  leaves the row. Once pressed it reads **Undone**, and does nothing until the focus has left the row, so a second
  press doesn't clear what it put back. **Reset to defaults**, at the end, drops all of this controller's bindings and adds its defaults
  again.
- **The menu's triggers can't be remapped.** Back and Mode can't be bound: Back never reaches the core, and the core
  refuses Mode, so a capture never sees either. Select and Start can be bound to anything, but the controller's own
  Select and Start keys, held together, open the menu whatever they're mapped to (see "The in-game menu").
- **The menu's triggers can't be remapped.** Back and Mode can't be bound: Back never reaches the core, and the core
  refuses Mode, so a capture never sees either. Select and Start can be bound to anything, but the controller's own
  Select and Start keys, held together, open the menu whatever they're mapped to (see "The in-game menu").

**The hotkeys page** has the **Controller** row (any connected controller), "Hold Select and press the button you set.
They work while a game runs.", then the core's hotkeys under its categories and names, in its order (General, System,
Graphics, Audio, Save States, Controller): fast forward and turbo, rewind, the speeds, the renderer, PGXP, the
resolution, widescreen, the on-screen display, rotation, mute and volume, the save states, and so on. Left out: Change
disc and Reset (the in-game menu has them, with their questions), Toggle pause (not on Android: it would pause with no
menu), and the per-slot and global save state hotkeys (the app's slots are each game's; the selected slot's do the
same). Each is Select held with a button: A on a row asks "Hold Select and press a button for <hotkey>… Back cancels
(5)" and takes the next button but Select and Start; its value then reads "Select + X". Each controller has its own, in
`[Hotkeys]`, as the core's chord bindings (`Android-0/Button109 & Android-0/Button99`). They work while a game runs and
the menu is closed. A chord's keys don't reach the game: when one completes, the core lets go of the pad buttons on
its keys in the same pass (`InputManager::ProcessEvent`, on Android only), so the game sees neither Select nor the
button. **Toggle Analog Mode** (the app's own, pressing the DualShock's Analog button) is offered for a controller on a
port whose type has the button, and presses the Analog button of the port whose controller fired it. An "Analog
Toggle" binding from before (`[Pad1] Analog`) becomes it, with Select, the first time the settings are read.

- **The save state hotkeys go through the app**: on Android the core's own are compiled out, and the app's save
  them, load them and pick the slot the in-game menu's way, under the game's key, so the states keep their pictures
  and the resume rules. **Save To Selected Slot** and **Load From Selected Slot** use the slot last saved to or loaded
  from (slot 1 before any), and say "Saved to slot 4" or "Loaded slot 4"; **Select Previous/Next Save Slot** step
  through 1 to 10, wrapping, "Slot 5 selected"; **Save State and Select Next Slot** saves, then selects the next;
  **Undo Load State** says "Undid the last load" or "No load to undo". Load and Undo Load State in hardcore mode say
  "Loading a state is off in hardcore mode", and a game without a key "This game can't keep save states". None opens
  the menu. The core's own per-slot and global-slot hotkeys (Load Game State 1…, Save Global State 1…) aren't built
  on Android, so a binding to them from a desktop `settings.ini` does nothing: they'd save under the disc's serial.
- **Select on a controller with a hotkey** is held back from the game until it's known not to start a chord: it goes
  to the game when it's let go (held down 100 ms, as a tap), together with the next button pressed (in one batch, so
  the core's chord fires and the game sees neither), or once held for 500 ms, for a game that wants Select held (a map,
  say). A chord pressed after that still fires, but the game has seen Select by then. Select and Start held for the
  menu never reach the game. A controller with no hotkey sends Select as it comes.


**A controller on a port the game doesn't use** drives nothing: a port whose type is Not Connected, or a multitap slot
while the game has no multitap on that port (multitap on in another game's file, say). As the game starts, each such
controller is told: "Wireless Controller is on Port 1C, which this game doesn't use." What counts is the core's own
setup for the game (`g_settings.controller_types` and `multitap_mode`).

**Rumble** goes to a controller's own motors only, those Android gives its input device (its `VibratorManager` on
Android 12 and later, the first motor large and the second small; its vibrator before), never the phone's or
handheld's own: a device Android says is built in (`InputDevice.isExternal()` false), such as a handheld's own
controls, doesn't rumble. The app has the `VIBRATE` permission for it, granted at install. On Android the core doesn't
read the `[PadN]` motor keys (`LargeMotor`, `SmallMotor`, `Motor`): each vibrating port's motors are bound to the
Android source by port (`InputManager::AddPadBindings`, `#ifdef __ANDROID__`), so a `settings.ini` shared with desktop
keeps desktop's own motor bindings. With several controllers on one port, the one last used (the last whose input
changed what it reports) rumbles, at its own strength; if it goes, the next level goes to another on the port. The
core repeats a level every half second while it lasts, and the app plays each for a second, so it doesn't gap; a level
of 0, the menu opening and the game ending stop it. Each controller's strength scales both motors.

**A type a game sets** for a port (its own Controllers page, below, or a desktop file's) has its inputs listed on the
Port page after the port's own type's, under the type's name ("NeGcon (a game's)"), to remap them. Each controller on
that port gets the type's default bindings where it has none, when the game's page sets the type and again as the game
starts, so a game's type is never left unbound. Reset to defaults gives them back too.

### Touch controls

Phones and tablets with no controller play with an on-screen PS1 pad over the game. **Settings** → **Controllers** →
**Touch controls** picks when it shows: **Automatic** (the default) shows it in a game while no controller is
connected, built-in pads included, so the Thor and anyone with a gamepad never see it; it hides as one connects and
comes back when the last one goes. **Always** shows it with controllers too (for a device that reports a pad nobody
holds), and **Off** never. **Size** (80 to 130%), **Opacity** (25 to 100%) and **Vibration** (a short tick on each
press, on by default) are kept for this device only, in the app's own preferences (`touch`), never `settings.ini`.

- **What's on it:** the D-pad, the four face buttons, L1, L2, R1, R2, Select, Start and a menu button (it opens the
  game's menu as Back does). The two sticks, with L3 and R3 above them, show only while Port 1 is a DualShock in
  analog mode, or an analog joystick, and appear and go as the mode changes (the Analog mode hotkey or menu entry,
  or the game). Port 1 as any other type (a NeGcon, GunCon, Justifier, the mouse, or nothing) gets no touch controls,
  and the game's start says once "Touch controls work with a digital pad or an analog controller on Port 1."
- **Where:** in landscape, in the bars beside the 4:3 picture, the shoulders in the top corners, Select and Start at
  the bottom middle, the menu at the top middle; with sticks, the D-pad and face buttons move up and shrink, and the
  sticks reach over the picture's lower corners. Where the bars are narrower than the controls, they're drawn over
  the picture. In portrait (where Android lets the app turn: screens 600 dp wide and up, split-screen, freeform), the
  picture moves to the top while they're on, and the pad sits under it; it goes back where `[Display] Alignment`
  puts it when they're off. The controls grow with the screen and the Size setting, up to 1.4 times the phone
  layout, and shrink as far as they must for every touch area to stay at least 48 dp and clear of the others.
- **How they work:** everything stays where it's drawn. A thumb slides from one face button to the next, or rests
  between two and presses both; the D-pad takes diagonals and keeps steering as the thumb slides. The shoulders,
  Select, Start, L3 and R3 hold until the finger lifts. A stick follows the thumb within its ring, full at the edge,
  and springs back on release. Each finger is tracked on its own. Lifting every finger, a touch the system cancels
  (the notification shade, a gesture), going to the background, the window losing focus, the menu opening and a
  controller connecting in Automatic let go of everything. The D-pad and the face buttons are kept from Android's edge
  gestures, so on gesture navigation a thumb sliding in from the screen's edge over them plays instead of going Back
  (Android allows this for up to 200 dp of each edge's height).
- **What it drives:** Port 1, straight to its controller's inputs (`NativeLibrary.setTouchPad`), with fixed
  buttons that the Controllers page doesn't list or remap, and nothing written to `settings.ini`. Stick values go
  through Port 1's Analog Sensitivity and dead zone, as any controller's do. On Always beside a controller on Port 1, both hold their
  inputs: letting go of a touch button or stick leaves what the controller still holds held (the core hears the
  controllers' held inputs again in the same pass). A game's rumble never plays on the
  device's own vibrator: only the press tick uses it.

### The in-game menu

In a game, three things pause it and open its menu:

- **Back**: the Back gesture, the system's Back key, or a controller's own Back key (the Thor's pad has one);
- **Mode**: a controller's Home or Guide button (`KEYCODE_BUTTON_MODE`), on its key-down;
- **Select and Start held together for a second**, on one controller.

The game never sees the press that opens it. The menu is a panel at the left, over the dimmed game: "Paused" over the
game's title, its region, serial and play time, then its rows, **Resume** focused:

- **Save state** and **Load state** open the slot panel beside the menu (below it on a phone held upright, with the
  menu keeping the top 40% of the screen). Save lists Slot 1 to Slot 10; Load lists only the slots that hold a
  save, with **Resume**, the resume state, first. Each shows the screenshot saved in it, "Saved" or "Empty" and when,
  in the form "Today · 14:05". The slot last saved to or loaded from in this game is focused, or else Slot 1 (Save)
  or the newest save (Load). With no saves at all, Load says "No saves yet for this game." Saving to an empty slot saves at
  once; a filled one asks "Replace the save in slot 3?" in the panel, Cancel focused. A save or load that works goes
  back to the game, saying "Saved to slot 3", "Loaded slot 3" or "Loaded your resume state"; one that fails stays in
  the panel with the reason ("Couldn't save to slot 3: …"). States are the core's own, at
  `savestates/<key>_<n>.sav` (the game's key, whichever disc is in), mirrored to the data folder like the rest. A PS-EXE, or a disc the core's database
  doesn't know and with no serial of its own, has the core's content-hash ID (`HASH-…`) in the serial's place, so it
  has slots and a resume state like any game. The last slot used is kept per game on
  this device (`SharedPreferences("game")`).

  For a multi-disc game, each row says the disc its state was made on ("Saved · Disc 2"). A state loads on that
  disc, found by its identity (its sub-image for a playlist, else its serial, else its file name), not by the path it
  recorded, which may be another folder's, another device's or desktop's: a state saved on disc 2 puts disc 2 in. A
  state whose disc isn't in the library fails to load with "Couldn't load slot 3: The disc this state was saved on
  isn't in your library: <file>.", and the game keeps its disc and place.

  **This game and Shared.** The panel has two tabs, L1 and R1 switching them (`KeyHints` "L1 R1 Switch"): **This
  game**, the slots above, focused as it opens, and **Shared**, desktop's ten global slots
  (`savestates/savestate_<n>.sav`, the same files, so a data folder shared with desktop shows the same ones). Each
  shared row names the game it holds under its slot (the library's title, or its serial for a game the library
  doesn't know), and its disc for a multi-disc game. Saving to a shared slot writes the running game ("Saved to shared
  slot 3"), asking "Replace the save in shared slot 3?" first when it holds one. A shared slot made in the running
  game loads like any slot ("Loaded shared slot 3"). One made in another game asks first, in the panel: "Load Crash
  Bandicoot from shared slot 3? This game stops.", Load and Cancel, Cancel focused. Load leaves the running game as
  Exit game does (its resume state saved when that's on, with Exit's question to replace one, and its warning while a
  memory card is being written; Cancel on either stays in the game), and once it has stopped, and its memory cards
  have been synced, starts the other game from the state, on the disc it was made on. If that game isn't in the
  library, the load is refused with the game it needs ("Shared slot 3 needs Crash Bandicoot (SCUS-94900), which isn't
  in your library."), and when hardcore mode would start, with "Loading a state is off in hardcore mode", both before
  the running game is left. The host itself refuses to load another game's state into the running one ("That save
  is from another game."), and one whose state can't be read says "Couldn't read that save." A game
  without a key (the BIOS alone, say) opens the panel on Shared, its This game tab saying "This game can't keep save
  states of its own." In a list under 150 dp tall (a handheld's at large text), the rows are compact: the small
  picture, the game on one line, and the time on Saved's line.

  **Delete.** In Load, X on a row (or a long press, by touch) asks in the panel: "Delete the save in slot 3?" (or
  "Delete the save in shared slot 3?", "Delete your resume state?", "Delete disc 2's save in slot 3?"), Delete and
  Cancel, Cancel focused (`KeyHints` "X Delete"). Delete removes that save, the one the question named whatever the
  list has done since, with the `.bak` a save kept of it (Settings → Emulation's save state backups), and the panel
  stays open on the row after it. The deletion reaches the data folder through the mirror, as any change does. Save
  has no Delete: it replaces. Saves have no names, as on desktop, which names them only by slot.

  **One set of states per game.** States made before discs were grouped (part 1 kept them under each disc's serial)
  are moved under the game's key the first time the game is played on this device, before it asks to resume: the
  newest state keeps each place, the others take the lowest free slots, and nothing is overwritten. The move is
  recorded as `migrated.<key>` in the `game` preferences, so it runs once per device. What doesn't fit, and anything
  desktop saves per disc later, is listed in Load state after the slots, under **Other saves**; loading one says
  "Loaded disc 2's slot 4".
- **Undo load**, after Load state: puts the game back as it was before the last load in this game (the core keeps one
  undo state), says "Undid the last load" and goes back to the game. It's off, with "Nothing to undo", until a load,
  and off in hardcore mode, as Load state is; the hotkey and the host refuse it there too, and turning hardcore on
  drops the undo state, so it can't come back once hardcore is off again.
- **Change disc**, for a multi-disc game, with the disc in the drive ("Disc 1 of 2"): opens a panel beside the menu
  with a row per disc, "Disc 1" to "Disc N", each with its own title (or its serial, when its title is the game's),
  the disc in the drive marked Current. **A** Insert puts the disc in (a playlist's sub-image, or the disc's file),
  closes the menu and resumes, saying "Disc 2 inserted"; the disc in the drive just closes the panel. A disc whose
  file has gone keeps the panel open with "Disc 2 isn't in your library any more.". The disc in the drive when the
  game boots, after a change and after a state load becomes the game's disc for its next start.
- **Fast forward**, with "On" or "Off": switches it and goes back to the game, at `[Main] FastForwardSpeed`. While
  it's on, its marker shows at the top right. It's off again whenever a game starts.
- **Analog mode**, with "On" or "Off", only for the DualShock (`AnalogController`): presses the controller's Analog
  button, as the real one does, and says "Analog mode on" or "Analog mode off" (or that the game keeps the controller
  in its mode).
- **Screenshot**: saves the game's picture, without the menu, to the data folder's `screenshots`, and says
  "Screenshot saved".
- **Patch codes**: opens a panel beside the menu with the game's codes, in their groups (codes without one last, under
  "Other", or with no heading when none has a group). Each is a switch, with its note under it (the database writes
  notes into names after a colon: "Infinite HP Slot 1:Turn Off When Out of Battle"); a code applied by hand (a
  `#activation=Manual` code in a player's file) is an **Apply** row instead, which applies it once and says "Applied:
  <name>". Switching a code changes it at once, and the menu stays open. A name and its note show two lines at most,
  then an ellipsis. With no codes the panel says "No patch codes for this game." and names the file to add.

  The codes are one list per game (`GameCodes`), built as it boots and given to the core again after every disc
  change or cross-disc load: the player's `cheats/<game title>.cht` (the tile's title, as written or with `< > : " / \
  | ? *` as `_`) and each disc's own `cheats/<disc title>.cht`, as desktop makes them, then the database's codes
  (`chtdb.txt`) for every disc of the game, each code once by group and name, a player's file winning. The player's
  files are only read, never written. Which codes are on is kept in the app's own file, `cheats/<key>.patchcodes`
  (mirrored; one `on` or `off`, a tab, the group, a tab and the name per line, for the codes switched from their
  default). A code a player's file marks `[*Name]` starts on; the database's start off. There's no separate on/off
  switch: only the codes switched on apply, and `[Console] EnableCheats` is off in memory on Android (the host owns
  the list) and never written, so desktop's own setting stays as it is. While codes are on, the game's start says "1
  patch code is on".
- **Reset console** asks "Reset the console? Progress since your last save is lost." in the menu, Cancel focused,
  then restarts the game: "Console reset".
- **Exit game** saves the game's resume state (`savestates/<key>_resume.sav`, when `[Main] SaveStateOnExit` is on,
  as it is by default, and the game has an ID, its serial or that content hash), with "Saving your place…" in the menu
  meanwhile, then stops it. If that save fails, the game still stops, and the library says "Couldn't save your place:
  <reason>". A game still starting saves none, so a resume state from before isn't replaced. When the game already has a resume state, Exit
  game asks first: "Replace your saved place from Today · 14:05?", with Replace and **Keep it** (focused). Both exit;
  Keep it saves nothing, so the old one stays. B stays in the game.
- **While the game is writing its memory card** (the menu was opened during a save in the game), Reset console warns
  that the save may be damaged, Exit game asks first ("Exit the game? It's still saving to its memory card, and that
  save may be damaged."), and Save state says "The game is still saving to its memory card. Resume it for a moment,
  then save again." A load that fails part-way puts the game back where it was (the core's undo state).

Until a game has booted, only Resume and Exit game work, and the details line says "Starting…". The foot of each panel
names the buttons: **A** Select (or Save, Load or Insert), **B** Back. While the menu shows, the controllers move its focus,
as on any other screen. B and Back step out one layer at a time, each acting on a press that began on it: a question,
then the side panel (slots, discs or codes: back to its row), then the menu, which resumes. Mode resumes from anywhere. Everything held is let
go as it opens and again on resuming, so nothing stays pressed in the game. The pause writes the memory cards, as
Home's does. With the menu open, Home and back keeps the game paused, with the menu showing.

**Resume where you left off?** When a game with a resume state is started from the library, it asks first, over the
library, with the state's screenshot and time: **Resume** (focused) boots it from there; **Start over** boots it afresh
and keeps the resume state until the next Exit game replaces it; B cancels. A resume state that can't be loaded (damaged,
or from an incompatible build through a shared data folder) takes the app back to the library with the reason, and
the file is kept, so the next start asks again. A fatal error, the SD card removed, or the app killed saves none.

**On-screen messages** over the game are drawn by the app, at the top left, in the design system's `osd` style (see
`OsdLayer` in `docs/design-system.md`): its own for 3 s, and the core's (a memory card saved, a controller connected,
an error) for the core's time, without their emoji. The core's own messages and status icons don't show on Android.

A stick the game had (or a capture, on the Controllers page) stays the core's after the menu opens or the capture
ends, until it's pushed less than 0.45 from rest, so it doesn't move the menus' focus while it's still held. That's
just under the 0.5 at which Android turns a stick into D-pad presses; a controller that declares a larger dead zone
for its stick or hat raises it to that, but never to 0.5. As the stick comes under it, the core is told it's at rest,
so a game resumed with the stick let go doesn't see it pushed, and the menus take it from then on.

Select and Start keep working in games. Pressed alone, each goes to the game as it comes (but Select on a controller
with a hotkey, see "Controllers"). When one is held and the
other goes down, that second press is held back: if both are still held 1 s later, the menu opens and the held-back
press is never sent. If either is let go first, the held-back press is sent then, late. A Start tapped while Select is
held therefore reaches the game when it's let go, and lasts 100 ms (6 frames at 60 Hz), however short the tap, so the
game's once-a-frame read of the pad sees it.

### RetroAchievements

**Signing in.** Settings → Achievements has the username and password fields, typed with Android's keyboard (focus
doesn't open it, so a D-pad passes over the fields; A or a tap does), and **Sign in**. A wrong password shows the
server's reason; no connection shows "Couldn't reach RetroAchievements". Signed in, the page says who and how many
points, with **Sign out**. Signing in turns achievements on (`[Cheevos] Enabled`); signing out turns them off. The
page can't be opened during a game, so its changes take effect from the next game start.

**The token.** The password is never stored. The server's login token is sealed with an AES-GCM key the Android
Keystore holds (alias `retroachievements-token`) and kept in the app's private `achievements` preferences; the host
hands it to the core in memory at startup, and the core's secrets layer on Android is in memory only, so no
`secrets.ini` is read or written. Keystore keys aren't backed up: after a restore or on a new phone the token won't
open, so the app starts signed out. A `settings.ini` from another install comes in signed out too (`Enabled` off, its
username and any old token removed). The token is never logged: `HttpBridge` logs only each request's method, host,
path and status.

**Offline.** A sign-in that fails for want of a connection keeps the token: the page says "Not connected.
RetroAchievements is checked again when a game starts.", and the next game start tries again, as does a network
coming back (the app listens for a validated network while it's in front). Only a rejected or expired token signs the
player out, with "RetroAchievements sign-in expired: sign in again in Settings" at the next game start (". 2 unlocks
are kept and sent once you sign in again" when some are waiting).

- **Nothing earned is lost for want of a connection.** Every unlock and leaderboard entry rcheevos sends is written
  to the app's own storage (`files/achievements/queue.json`, never in the data folder, so never mirrored, and not
  backed up) before it goes, and taken out once the server has it, already had it, or refused it. One the app
  closed on is sent at the next sign-in, a network coming back, or a game start, dated with its age: the server
  backdates by up to two weeks (rcheevos v12.5.0's `o`), and an older one counts from when it arrives. No file holds
  the token: a record names its user, and is signed again when it's sent.
- **Per account.** Records are sent only while their own user is signed in. A sign-out, an expired token or another
  account's sign-in keeps them, and Settings → Achievements says so ("2 unlocks for goose are kept until goose signs
  in again").
- **A game started offline** runs from the set saved at its last online start (the set, each disc hash seen online,
  the session and the account, in `files/achievements/`), in casual even with Hardcore on, through a Reset too, and
  says "Offline: achievements are saved and sent later (casual mode)" once. Its sign-in is built from the saved
  account and the token in memory, and goes when the game stops. A disc never started online, or a game never
  started online, has no achievements offline, and says "Couldn't reach RetroAchievements; achievements are off for
  this game" once. A saved set is used however old it is: an achievement removed since is refused when it's sent.
- **What the player sees.** An unlock made without a connection shows "Sent when you're online" under its card (from
  the first one rcheevos couldn't send, or for every unlock in a game started offline); the menu's row reads
  "14 of 40 · 2 to send"; Settings → Achievements, "2 unlocks waiting to be sent". Once they've gone: "3 unlocks sent
  to RetroAchievements"; one refused is dropped with "Couldn't send 'Ace Pilot': RetroAchievements refused it". The
  Leaderboards tab says "Not available offline" in a game started offline. The core's own "Achievements
  Disconnected" and "Reconnected" toasts aren't shown.

RetroAchievements' FAQ says sessions should start online ("We don't currently support a fully offline mode"), which is
why a game started offline is casual only; its hardcore compliance requirements ask for exactly this queue ("Unlocks
created while offline must be securely cached and sync to RetroAchievements when connectivity returns"). Desktop
doesn't keep a queue yet (roadmap, Later).

**Hardcore mode** (Settings → Achievements, off by default) starts with the next game start. While it's active the
menu's Load state, Patch codes and Fast forward are greyed out ("Off in hardcore mode"), no patch code applies, and
fast forward and turbo are turned off every frame (which covers the hotkeys). Saving states still works. A game with a
resume state that would start in hardcore offers only **Start**, keeping the resume state: a boot from it would turn
hardcore off (the core's `ConfirmMessage` answers yes on Android). Hardcore drops by itself a few seconds after boot for
a game RetroAchievements doesn't know, and the rows come back. GooseStation isn't registered with RetroAchievements
yet (roadmap item 6), so hardcore unlocks may not count.

**In the menu**, the **Achievements** row after Patch codes shows the progress ("14 of 40") or why it's off. Its panel
has Achievements and Leaderboards tabs, which L1 and R1 switch. Achievements are in rcheevos's sections by progress
(unlocked ones newest first); a leaderboard opens to its top 10 and the entries around you, fetched each time.

**Over the game**, an unlock shows a card at the top centre for 5 s (a mastered set 10 s), one at a time, waiting while
the menu is open; the game-start summary and leaderboard messages join the message stack; leaderboard timers,
challenges and progress show at the bottom right. The page's switches turn the messages, sounds and live indicators
off. The core's own sounds (`data/resources/sounds/achievements/`) play through a `SoundPool`.

**Testing.** No test uses real credentials. `AchievementsSignInTest` and `AchievementsOfflineTest` point rcheevos at
`FakeRetroAchievements`, a local server (`NativeLibrary.setAchievementsServerForTest`) answering `login2`, a game with
one achievement and one leaderboard for any hash (`achievementsets`, `gameid`, `startsession`), and its unlocks and
entries, with switches for an unreachable server, an outage page, a refused unlock and one already had; debug builds
allow cleartext to `127.0.0.1` for it (`src/debug/res/xml/network_security_config.xml`), release builds don't. A
PS-EXE can't be hashed, so `NativeLibrary.setAchievementsGameHashForTest` gives padtest the fake's hash; the hardcore tests boot
padtest's disc image (`/sdcard/GooseTest/games/Pad Test #1 (100%).cue` and `.bin`), as a PS-EXE boot turns hardcore
off. rcheevos waits up to two minutes between retries of a send; `NativeLibrary.skipAchievementsRetryWaitForTest`
moves its clock on, so the test of a retry doesn't wait for it (debug builds only). The store's own tests are in `common-tests`
(`AchievementsOfflineStore.*`).

### Memory card sync

**The page.** Settings → Memory cards has what desktop and Big Picture have, with the same `settings.ini` keys
(`[MemoryCardSync] Enabled`, `RemoteURI`, `AccessKeyID`, `DeviceName`): **Sync memory cards**, **Storage**, **Access
key ID**, **Secret access key**, **Encryption passphrase** (and **Remove passphrase** once there is one), **Device
name**, the **Status** line (Big Picture's words), **Sync now**, and **N conflicts** when there are any. Each text
setting is a row showing its value; A or a tap opens its field in its place, with Android's keyboard: Done saves, B
cancels. One field is open at a time. A change is saved, then applied off the main thread
(`MemcardSync::ApplySettingsAsync`). The page can't be opened during a game.

**The secrets.** The secret access key and the passphrase are sealed with an AES-GCM key of their own in the Android
Keystore (alias `memcard-sync-secrets`) and kept in the app's private `memcard_sync` preferences (`SyncSecrets`), never
in `settings.ini`, the working copy or the data folder; the page never shows one back, only "Saved" or "Not set". The
core asks the app for them (emusync's `es_secret_*` aren't available on Android), and asks the Keystore, not the
ini's `SecretStored`/`PassphraseStored`, whether there is one: a data folder copied from desktop says its own
keychain has them, so here they're "Not set" and the badge says "Needs setup" until they're entered.

**This device's name.** An empty **Device name** is this device's (emusync's `SyncSettings.defaultDeviceName()`, the
maker and model), not Android's "localhost", and a `settings.ini` adapted from another install gets it.

**When it syncs.** Only while the app is open, on the working copy's cards, as desktop does on its own: when the core
starts, when a game opens and closes its cards, every 5 minutes, and on **Sync now**. In the background, after the
pause's card writes, it pushes what it has (`es_app_suspend`, 3 s at most) and rests; back in front, once the mirror's
pull is done, it syncs at once (`es_app_resume`). No background job, no notification. The mirror carries what sync
brings to the data folder like any other change; while it copies a card in from the folder, or deletes one, sync
holds that card (a session on it, as a game's), so neither ever writes the file under the other. A pull that brings a
`settings.ini` whose `[MemoryCardSync]` changed applies it.

**Status.** The library's top bar shows a badge while sync is on: **Synced**, **Syncing…**, **Offline**, **N
conflicts**, **Needs setup** (a secret or passphrase missing here, or the access key refused) or **Sync failed**, each
with its icon. It opens the conflicts, or the page. In a game, a card that got saves from other devices says
"Updated 2 saves from other devices"; a conflict found while playing says "A save needs your choice. Settle it in
Memory cards after this game."

**Conflicts.** Settle them after the game, from the page's conflicts row or the badge: one row per save, with the
game's title when the database knows it, and each device's version (device, time, blocks, or deleted). A on a row
offers this device's version, another device's, or deciding later, the newest focused; **Keep all from this device**
asks first. A choice for a card in use is written once the card closes.

**Testing.** `FakeS3` (`androidTest/…/core/`) is as much of S3 as emusync uses (create-only PutObject, GetObject,
HeadObject, ListObjectsV2, path-style), on `127.0.0.1`, with made-up credentials only (`GOOSEFAKEACCESSKEY` /
`not-a-real-secret`). Two spellings of its address (`path_style=1` and `path_style=true`) have separate emusync data,
so one test can play two devices. `MemcardSyncStateTest`, `MemcardSyncCardsTest`, `MemcardSyncLifecycleTest` and
`MemcardSyncSecretsTest` drive the real core through it, with test hooks for a card's open, write and close.
Real storage is checked by hand, with your own credentials.

### The memory card editor

**Where.** Settings → Memory cards → **Manage cards** (the page's first row, "N cards"). Like the page, it can't be
opened during a game, so it never edits a card a game has open.

**Which cards.** One card per library game, at the path the game boots with: `System::GetGameMemoryCardPath` for its
first disc's serial and path, except a playlist whose file title differs from the database's title, which boots with
a card named after the playlist (unless the database-title card exists, or `[MemoryCards] UsePlaylistTitle` is off in
the game's own settings or settings.ini), as the boot code chooses. A game's card that
doesn't exist yet says "No card yet"; Save creates it (formatted). Slot 2 is listed only for a per-game card type
there. Then the shared cards (when they exist, settings.ini makes the type Shared, or a game's ini does), and the
other card files: those `Card1Path`/`Card2Path` name, and every `.mcd` in the memory card folder, by name.

**Editing.** Two cards side by side (one at a time under 720 dp wide). A on a save: Copy to the other card (asking
before replacing a save of the same name, refused without the free blocks), Rename (the core's rules and the desktop
editor's warnings), Export (Android's file picker, an `.mcs`), Delete (undeletable while its blocks aren't reused),
and, on a deleted save, Undelete and Delete permanently. A card's own actions: Import save (any format the core
imports), Import card (replacing everything, asking first), Format (asking first). Files come and go only through the
picker's `content://` documents, at most 256 KiB read. A card that can't be read offers Import card and Format only.

**Saving.** Edits stay in memory, per card, until **Save** (or **Discard**); leaving with changes asks Save, Discard
or Keep editing. Save writes each changed card atomically, refusing one whose file changed since it was read unless
the player says to write over it, then pushes the data folder's mirror. A card memory card sync knows (a game used it
here, or it came from another device: emusync's `es_card_get_mode` finds it) is written under sync's hold, so it
syncs as one change; any other card stays on this device until a game uses it. With sync on, Delete, Delete
permanently, Format and Import card on a card sync knows say other devices will see the saves removed. A card changed
under the editor (a pull, or sync) with no edits is read again.

**Testing.** `MemcardEditorListTest`, `MemcardEditorCoreTest` and `MemcardEditorSyncTest` (`@LargeTest`, two devices
through `FakeS3`) drive the real core; `MemcardEditorTest` and `MemcardEditorSaveTest` the controller on fake cards
(`FakeMemcardEditorApi`); `MemcardEditorScreenTest`, `MemcardPanelsTest` and `OverlayPanelTest` the screens by keys,
with `FocusAuditTest` and `OrientationLayoutTest`. The core's card image code is in `common-tests`
(`memory_card_image_tests.cpp`, with the undelete fix), and the playlist rule in `android_memcards_tests.cpp`.

### Known limits

- A slang preset in the data folder's `shaders/` can't refer to a pack's files by a relative path (`../shaders_slang/…`):
  Android's packs are in the app's own storage, not beside it. Presets inside a pack refer to each other as usual.

- **Post-processing:** ReShade (`.fx`) shaders don't compile under OpenGL ES: reshadefx's GLSL generator relies on
  implicit int-to-float conversions (`float / const int`), which GLSL ES rejects. They aren't offered there.
  `crt/crt-hyllian-sinc.fx` doesn't load anywhere (desktop too): `data/resources` lacks the
  `../misc/include/mask.fxh` and `geom.fxh` it includes. Slang presets run under both renderers, on the emulators
  and on the AYN Thor's Adreno 740 (see "Slang presets on the Thor"). Packs aren't downloaded in the app yet (stage 2
  of the slang design).

- **Memory card editor:** edits held in memory are lost if Android ends the process while the app is in the
  background, without a notice afterwards. Cards from outside the data folder come in only through Import card, and
  multitap cards aren't shown.

- **Removing the SD card stops a game played from it.** Android kills any process that still has a file open on a
  card it unmounts, about 5 s after the eject begins (`exited due to signal 2`), and a game holds its disc open. So
  when the card that holds the running game, or the data folder, is ejected (`ACTION_MEDIA_EJECT`, or its unmount or
  removal if no eject came first), the app saves the game's memory cards to the working copy straight away, rather
  than 5 emulated seconds after the game's last write, stops the game, and goes back to the library: "The SD card was
  removed, so the game was stopped. Its memory cards are saved on this device." A data folder on the card stops being
  mirrored at once: the run under way stops before its next file, and the library shows the unreachable banner ("The
  data folder isn't reachable (the SD card was removed)…"). Once the card is mounted again, the next run saves the
  changes to it. With no game running, only the mirror stops. The Thor's first result below is from the build before
  this; its recheck with this build passed. Ejecting the data folder's card stops the game even when the game itself
  is on internal storage: rechecked with the user on 2026-10-27, and kept.
- **The Vulkan fallback lasts until the app restarts.** Once Vulkan has failed, every game in that process uses
  OpenGL ES, even after `Renderer` is changed back to `Vulkan`. Each new process tries Vulkan once more, as
  `settings.ini` keeps the user's choice.
- **A controller connected during a game** has its Select held back for its hotkeys from the next game on: they're
  read as a game starts.
- **The core's screenshot message lasts 2 s,** not the 5 s it means: its duration is passed to the text's formatting
  instead (`gpu.cpp`, desktop code, left as it is).
- **The settings pages are reachable only from the library**, not from a game or its prompt. Leave the game to change
  them.
- **Leaving during a boot freezes the UI until the boot is done.** Destroying the surface waits for the CPU thread,
  which is still booting. On the emulator, leaving during an Ace Combat 3 boot (with Back, in Plan 1's build) froze
  the UI for 0.7 to 1.2 s (43 and 67 skipped frames), and the boot took 1.2 to 1.4 s. That's well under the 5 s ANR
  limit, but a slow first boot on a device could take longer.
- **Reopening the audio stream mid-game is untested.** The AAudio stream is reopened when its device goes away, as
  when headphones are unplugged. The emulator can't check this. On the Thor the sound moved over with headphones, but
  Android re-routed the stream itself, so the reopen never ran (see "Results on the AYN Thor").
- **A missing grant can't be made with `adb`.** Deleting a folder through the Storage Access Framework (the
  system's Files app, say) should take its grant with it (not checked here). Deleting or moving it from the shell
  (`rm`, `mv`) or by its file path leaves the grant, and looks like an unreachable folder instead. `GrantCheckTest` and
  `LibraryBannersTest` cover the grant check and its banners.
- **The data folder isn't shared live with desktop or another install.** Each install mirrors its own working copy,
  and the folder wins when both changed, with no merging, so two installs using one folder at once can lose each
  other's changes. Each install also adapts a `settings.ini` another one wrote (see "Changing settings"). Memory
  cards sync between devices through emusync instead (see "Memory card sync").
- **A memory card deleted in the data folder comes back.** The mirror deletes it in the working copy, but emusync
  reads a missing card as one not created yet, not as saves deleted: the next sync writes the card's saves back, and
  the mirror pushes it again. Sync never deletes saves on other devices because a file went away.
- **Desktop lists a multi-disc game's states under disc 1.** It keys states by the disc running, and Android keeps
  them all under the game's key, which is disc 1's serial for a set; desktop's own states for disc 2 show under
  "Other saves" on Android. Desktop opens a state by the path it recorded, which is Android's and won't open there,
  so it keeps the disc in its drive: **on desktop, put in the disc a state was made on before loading it** (Android's
  slot rows say which: "Saved · Disc 2"), or desktop runs it on the wrong disc.
- **A name-grouped game keeps a memory card per disc**, with the core's per-title cards (the default): the title of a
  disc the database doesn't know is its file name, so "Foo (Disc 2)" has its own card. A database set's discs share
  the set's card. A playlist's card is named after the playlist, so Ace Combat 3's M3U and its CHDs played on their
  own use different cards. Both are the core's rules, as on desktop.
- **The migration runs once per device**, recorded in the app's preferences: a second device using the same data
  folder runs it once too, which moves only what's still under a disc's serial (desktop's later saves, say).
- **No hotkey changes discs**; the menu's Change disc does.
- **Play time is updated on the next refresh.** Coming back from a game doesn't rescan the library, so the list shows
  the time played before it until the next refresh or resume.
- **A provider that keeps a folder "loading" stops the mirror quietly.** A listing that isn't complete is never acted
  on (it would read as deletions), so the run does nothing and tries again next time, with only a log line
  (`Mirror: Can't list the data folder; the next run tries again.`) and no banner.
- **Settings changes during a game.** A `settings.ini` pulled in during a game (from the folder, by the 2 s push) is
  reloaded straight away, under the running game, so it can change the renderer or the bindings mid-game; a file
  from another install resets the bindings while the game is played. The library is also rescanned while the game
  reads its disc. Change settings with no game running.

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

**Storage, setup and the library** (Plan 2), checked later on 2026-10-26 with the debug APK, on the same emulator,
with the real folder picker. The folders were `/sdcard/GooseTest/bios` (`ps-30j.bin`), `/sdcard/GooseTest/games`
and an empty `/sdcard/GooseTest/data`. The games folder held `padtest.psexe`, both Ace Combat 3 discs as CHDs, an M3U
of the two, and a CUE/BIN made for the check (below). Setup and the library were driven with D-pad key events, and the
picker with taps; the games with a gamepad made with `uinput`, as above.

| Check | Result |
| --- | --- |
| Fresh install, setup, library | Pass. After `adb uninstall`, the first launch showed setup's welcome (`am start -W`: `TotalTime: 1462`). Each step's button was focused when it opened, and D-pad down and Enter went through them; the picker needed taps (**Use this folder**, **Allow**). The BIOS step listed `SCPH-5500 (v3.0 09-09-96 J)`, the data step said "This folder is empty. GooseStation will start fresh here.". Finish extracted the resources and started the core in 1.0 s (`Extracting the resources …` to `The core is ready.`), and the library listed five entries: both discs, the M3U, the CUE and padtest. |
| CUE/BIN | Opens, but doesn't boot to a picture, on desktop either, so the image is the likely cause. No real CUE/BIN was at hand, so it was made for the check: `Pad Test #1 (100%).cue`, whose `FILE "PAD TEST #1 (100%).BIN"` differs in case from `Pad Test #1 (100%).bin`, a 1 MB MODE2/2352 image of an ISO 9660 file system (made with `hdiutil makehybrid -iso`) holding `SYSTEM.CNF` and padtest as `PADTEST.EXE`, with the NTSC-J licence text in sector 4. The core opened the CUE, found the BIN by its case-insensitive match, read the licence sector (`Auto-detected console NTSC-J region`) and the file system (`Game changed: 'Pad Test #1 (100%)' (PADTESTEXE)`), and `Game started` came 1.25 s after `Boot Filename`. The screen then stayed black, with or without `;1` in `SYSTEM.CNF`. The desktop build does the same with the same files: `goosestation-regtest` (built with `-DBUILD_REGTEST=ON`) ran the CUE for 1800 frames, dumping one every 300, and all six were the same black frame, while Ace Combat 3's CHD, run the same way, gave five different frames. The BIOS apparently doesn't start the hand-made image's executable. The Thor checklist boots a real one. |
| M3U set | Pass. `Loaded 2 paths from m3u`, then `Game changed: 'Ace Combat 3 Electrosphere' (SLPS-02020)`; `Game started` 1.3 s after `Boot Filename`, and "Press START button" by 30 s. |
| CHD | Pass. Disc 1 on its own: `Game started` 1.3 s after `Boot Filename`, and the title screen by 30 s (`G: 59.53`). Plan 1 recorded 0.7 s (`Starting '…'` to `Game started`, in the table above) and 1.2 to 1.4 s (in "Known limits") for Ace Combat 3 from the app's own folder, so reading through `content://` isn't clearly slower on the emulator. The Thor checklist compares on the device. |
| A memory card save reaches the folder | Pass. Entering Ace Combat 3's link mode menu wrote the card: `Saving memory card to Ace Combat 3 Electrosphere_1...` at 22:31:13.258, and the folder's file had a new time of 22:31:17.197, 3.9 s later. In a later push (the account save below), polling the folder every 0.05 to 0.1 s saw `Ace Combat 3 Electrosphere_1.mcd.goosetmp` once and `….mcd.goosebak` once, between the steps of the replace. None was left: `find /sdcard/GooseTest/data -name "*.goose*"` found nothing at the end. |
| Home mid-game, card replaced in the folder | Pass. The card's MD5 was `f8eaf894…` on both sides. Home, then `adb push` of a copy with one byte changed (`e6df94db…`) over the folder's card, then reopening: the game carried on (no second `Game started`), and the working copy still had `f8eaf894…`. After Back (`Game stopped.`), the working copy had `e6df94db…`, the folder's. |
| A save state deleted from the folder | Pass. There's no way to save a state yet, so a 300 KB `savestates/SLPS-02020_1.sav` was pushed into the folder. Home and reopening copied it into the working copy (`run-as … ls files/data/savestates`). Deleting it from the folder, then Home and reopening, deleted it from the working copy. |
| Data folder unreachable | Pass. After `mv /sdcard/GooseTest/data /sdcard/GooseTest/data-away`, the library showed "The data folder isn't reachable (Can't list primary:GooseTest/data). Your changes are kept on this device and saved to the folder when it's back." within 5 s, and the log had `Mirror: The data folder can't be reached.` every 2 s. A new Ace Combat 3 account, saved to the card in slot 1 (`Writing memory card sector 77`, `78`, then `Saving memory card …` at 22:42:25.725), changed the working copy's card to `72153b85…`; `data-away` kept `e6df94db…`, and no new `data` folder appeared. After moving it back, Home and reopening, the folder's card was `72153b85…`, and the banner was gone. |
| Killed during a large push | Pass. A 200 MB file of random bytes was copied into `files/data/screenshots/big.bin` with `run-as`, then Home started the push. Once `big.bin.goosetmp` showed in the folder, `am kill` ended the process (it's only allowed in the background), leaving a 15,400,960-byte `big.bin.goosetmp` and no `big.bin`. On the next start (`TotalTime: 1077`), the leftover was deleted and the push began again: 2 s later `big.bin.goosetmp` was full size, 4 s later `big.bin` was 209,715,200 bytes with the source's MD5 (`dfda8f9f…`), and no temporary was left. |
| A desktop `settings.ini` | Pass. A version 3 file with `Renderer = Software`, `[Pad1]` bound to `SDL-0` (D-pad, Cross, Circle, Start), `[InputSources] SDL = true`, Windows paths for the game list, the BIOS folder and the memory card folder, and `ShowFPS = true`, was pushed over the folder's. Home and reopening: `Reloaded the settings …`, `The settings are from another install (''), adapting them.`, `[MemoryCards] Directory 'C:\Users\goose\Documents\GooseStation\memcards' is outside the data folder, using 'memcards'.` and `Added 24 bindings for Android-0 to Port 1.` The working copy's file had no `Renderer` and no `SDL-0` binding, `RecursivePaths` set to the games folder, `[BIOS] SearchDirectory = bios`, no `PathNTSCJ`, and `ShowFPS = true` still; the folder's copy got this install's `[Android] InstallID` with the next push. padtest then booted with `Allowing Vulkan as default renderer.` (Automatic), the OSD's frame rates showed, and the pad's Cross and D-pad right lit up. |
| Crashes | None: `adb logcat -b crash -d` was empty at the end, and the app's process stayed the same from setup to the `am kill` (PID 9010), and from then to the end (PID 11609). The crash buffer was also cleared with each `adb logcat -c` along the way. |

**Settings and controls** (Plan 3), checked on 2026-10-28 with the debug APK built from `f40e18f92`, on the same
emulator, with the folders as in Plan 2's checks (`/sdcard/GooseTest/bios`, `games` and `data`). The pages were driven
with D-pad key events, the picker with taps, and the games with `uinput` gamepads, as above: "Goose Virtual Pad" and
"Second Wireless Controller With A Long Name" (vid 1118, pid 654 and 655), and for the prompt "Goose Prompt Pad"
(vid 1118, pid 655, with Select, Start and Mode). On this emulator's key layout (`Vendor_045e_Product_028f.kl`),
`BTN_WEST` (308) is `BUTTON_Y` and 307 is `BUTTON_X`. padtest is `padtest.psexe`.

| Check | Result |
| --- | --- |
| A BIOS override the BIOS folder lacks, at setup | Pass. Before setup, the data folder's `settings.ini` had `[BIOS] PathNTSCU = missing.bin` and this install's `InstallID` (put in the app's preferences before the first launch, so the file wasn't adapted). Setup's BIOS step copied only `ps-30j.bin`. Once the core started: `The core is ready.`, then `Back to Automatic, as the BIOS folder no longer has their image: [NtscU]` 1 ms later. `PathNTSCU` was gone from both copies of `settings.ini`, and the BIOS page showed NTSC-U as "Automatic: SCPH-5500 (v3.0 09-09-96 J)". |
| BIOS override, then a new BIOS folder | Pass. NTSC-U and PAL set to the image on the BIOS page (its list noted "made for NTSC-J"): the data folder's file had `PathNTSCU = ps-30j.bin` and `PathPAL = ps-30j.bin`. padtest (a PAL executable) booted with no `Searching for a PAL BIOS` line and no `No BIOS image found`, and `BIOS region NTSC-J does not match requested region PAL.` and `Using BIOS: SCPH-5500 (v3.0 09-09-96 J)`. Then **Folders** → **BIOS folder** → **Change** to a folder holding the same image as `scph5500-copy.bin`: `Back to Automatic, as the BIOS folder no longer has their image: [NtscU, Pal]`, both keys were gone from the data folder's file, and the page showed Automatic for each region. |
| Graphics round trip | Pass. From the defaults, the page set OpenGL ES, 3x, "Stretch to the screen" and Show FPS (the resolution's note showed under 3x). The data folder's file had `Renderer = OpenGL`, `ResolutionScale = 3`, `AspectRatio = Stretch To Fill` and `ShowFPS = true` 1 s after the last change. Back went to the hub with Graphics focused, and to the library with **Settings** focused. padtest then logged `Trying to create a OpenGL GPU device...`, filled the screen's width, and showed `G:`/`V:`. |
| A desktop `settings.ini` with `AspectRatio = Stretch To Fill` | Pass. The page's aspect ratio was set to 4:3 first. A file with no `InstallID`, `Renderer = Software`, `ResolutionScale = 2`, `AspectRatio = Stretch To Fill`, `ShowFPS = true`, a Windows BIOS folder, `PathNTSCJ` and `SDL-0` bindings was pushed into the data folder. Home and reopening: `Reloaded the settings …` and `The settings are from another install (''), adapting them.` The Graphics page then showed Automatic, 2x, "Stretch to the screen" and Show FPS On, and padtest booted on Vulkan (`Trying to create a Vulkan GPU device...`), filling the screen. |
| Vulkan fallback, `Renderer = Vulkan` | Pass. Vulkan set on the page, `setprop debug.goosestation.fail_vulkan 1`, then padtest: `Trying to create a Vulkan GPU device...`, `Failing the Vulkan device for testing (debug.goosestation.fail_vulkan).`, `Vulkan didn't start (…), using OpenGL ES until the app restarts.`, `Trying to create a OpenGL GPU device...`, then `Game started: padtest`, all within 0.6 s. padtest drew, with the OSD message. The Graphics page's Renderer row then said "Vulkan didn't start on this device, so games use OpenGL ES until the app restarts." Booting padtest again in the same process logged only `Trying to create a OpenGL GPU device...`. Both copies of `settings.ini` kept `Renderer = Vulkan`. With the property at 0, a force-stop and a new start used Vulkan (`using first (Goldfish GFXStream …)`). |
| Vulkan fallback, `Renderer = Automatic` | Pass. The same lines in the same order, the OSD message, OpenGL ES only on the second boot in the process, `Renderer = Automatic` kept, and Vulkan again in a new process with the property at 0. |
| Remapping, and a restart in swapped order | Pass. Both pads connected as new: `'Goose Virtual Pad' is a new controller, Android-0.` and `Added 24 bindings for Android-0 to Port 1.`, then the same for the second as `Android-1`. `[Android] ControllerDescriptors` listed both. Settings → Controllers: the card, then the Controller row, focused. On the second pad, Cross captured 307 (`Captured Button99 for Cross`, the row read X) and Circle captured `BTN_SOUTH` (`Captured Button96 for Circle`, A). The data folder's file had `Cross = Android-1/Button99` and `Circle = Android-1/Button96`, and the first pad's `Button96`/`Button97` unchanged. A capture left alone counted down and ended with the row back at X, and no `Captured` line. In padtest, the second pad's X lit Cross (and Square, whose default is the same `BUTTON_X`), its A lit Circle, and the first pad's A lit Cross. Then both pads were removed, the app force-stopped and started, and the second pad registered before the first: `Device 'Android-1' connected: 'Second …'`, then `Device 'Android-0' connected: 'Goose Virtual Pad'`, with no `is a new controller` or `Added` line. `settings.ini` was byte-identical to before the restart, with no `Android-1/Button96` for Cross, and the page listed the second pad first, with Cross = X. |
| Two pads with one descriptor | Seen by chance: "Goose Prompt Pad" has the same vendor and product as the second pad, and no serial number, so Android gave it the same descriptor (`37bf902b…`). It connected as `Android-1`, with no new bindings, and used the second pad's remap. |
| A stick held as a game starts | No repeat found. In the library, a stick held right moved the focus three tiles in under a second (Android's joystick-to-D-pad repeat). With the stick held down on padtest's tile (no tile below), A started padtest: padtest showed no D-pad press and the stick at 0 (the core releases everything at a game's start, and the held stick sent nothing new); letting the stick go in the game showed nothing either. After quitting, the D-pad moved the focus up, and nothing moved it back down in the next 3.5 s, so no repeat was still running in the app. The capture variant (a stick held as a capture starts) wasn't tried. |
| Prompt: Back | Pass. `input keyevent KEYCODE_BACK` in padtest opened "Paused", with **Resume** focused and **Quit to library**, and the core's pause icon showed (padtest has no frame counter; the pause is the icon and `AAudioStream_requestPause`). The pad's B resumed (`requestStart`), and padtest didn't light Circle. Back again, the hat down moved the focus to **Quit to library**, and A gave `Game stopped.` and the library, with padtest's tile focused. |
| Prompt: Mode | Pass. `BTN_MODE` down opened the prompt, its key-up didn't close it, and a second press resumed (`requestStart`). The prompt covers padtest while it's open, so a Mode press there isn't visible; padtest has no Mode button to light, and after resuming with Mode (in the D-pad check below) nothing was lit. |
| Prompt: Select+Start | Pass. Select held, Start down 0.4 s later and held: at 0.8 s padtest showed Select lit and Start not; by 1.8 s the prompt was open. After letting both go and A on **Resume**, nothing was lit. A Start tapped for 0.3 s while Select was held, in a 4 s `screenrecord` (76 frames): Select lit in 32 frames, Start in 2, and nothing stayed lit. |
| Prompt: D-pad held | Pass for the hat. Hat right held (D-pad right lit), Mode opened the prompt with the focus staying on **Resume**, the hat let go, Mode again: nothing lit. The D-pad as keys couldn't be checked: this key layout maps no `BTN_DPAD_*` key. The Thor checklist has it. |
| Prompt: Home with it open | Pass. Home, then reopening: the prompt still showed, with **Resume** focused, two screenshots 1.5 s apart were identical, and there was no `requestStart`. |
| Prompt: Back during a boot | Pass. Back 0.3 s after starting Ace Combat 3 Disc 1: the prompt showed; `Game started` came at 22:11:20.061 and the pause 19 ms later; screenshots 3 s apart were identical. **Resume** gave `requestStart`, and the picture moved. |
| Catch-up at a cold start | Pass. The data folder got `[Folders] GameSettings = mygs`, `mygs/PADTESTEXE.ini` (`Card1Type = Shared`, `Card1Path = ../cards/padtest.mcd`), `cards/padtest.mcd` and a 200 MB `mygs/big.bin`, all pulled in once. Then, with the app stopped, the working copy's `mygs/` and its manifest lines were deleted, and the app cold-started: the library said "Copying your data from the data folder…", then `The core is ready.`, `Catch-up pull: the data layout grew.` and `… stopped growing.` 0.66 s later, and `mygs/` (with `big.bin`) was back. `files/data/cards/padtest.mcd` kept inode 574438 and its time throughout, polled every 0.4 s. |
| Catch-up on coming back | Pass. In the background, `Card2Path = ../cards2/big.mcd` was added to the game settings, with a 500 MB `cards2/big.mcd`. On reopening, A on padtest's tile was refused with "Copying your data from the data folder…"; the catch-up ended 2.5 s after that press (`… stopped growing.` at 22:15:03.414), and the next A started padtest (`Game started` at 22:15:05.295). Afterwards, with the extra folders and the key removed, a cold start removed the working copy's `mygs/`, `cards/` and `cards2/` as strays. |
| Release build (R8) | Pass. `./gradlew assembleRelease` builds. R8's `seeds.txt` keeps `NativeCallbacks.onBindingCaptured`, `AppController.onBindingCaptured`, the `PadBinding` and `BiosImage` constructors and `PadBindingKind`'s values. The release APK, signed with the debug key and installed over the debug one, started the core, listed the BIOS page, and captured a binding (`Captured Button102 for Select`); **Reset to defaults** then put the defaults back. |
| Portrait | Not possible full screen. With `wm set-ignore-orientation-request true` and the rotation at 0, the app was letterboxed in a landscape-shaped window (411x309 dp) in the middle of the portrait screen. Plan 3's layout check used freeform portrait windows instead (411x862 and 360x800 dp). The Thor checklist has it. |
| Crashes | None: `adb logcat -b crash -d` was empty at the end, and logcat had no `FATAL EXCEPTION` or `Fatal signal`. Every new process came from a force-stop or an install. |

### The in-game menu (2026-10-31)

Checked on `goose-arm64` with the debug APK, by key events (`adb shell input keyevent`, and `input gamepad` for the
hotkeys), with Ace Combat 3 (SLPS-02020) and padtest. The game screen is landscape, so the panels sat side by side.

| Check | Result |
| --- | --- |
| Save to an empty slot | Pass. Slot 1 saved (`savestates/SLPS-02020_1.sav`, 1.4 MB), the game resumed, "Saved to slot 1" showed top left; the core's own save message didn't. |
| Replace a save | Pass. "Replace the save in slot 1?" in the panel, Cancel focused; Replace saved again. |
| Load | Pass. The panel showed the slot's screenshot and "Today · 16:48", the empty slots faint and skipped; loading resumed at the saved point with "Loaded slot 1". The slot last used was focused next time. |
| Fast forward | Pass. The row read "On", the marker showed top right, and the game ran faster; again, off. |
| Screenshot, reset | Pass. A PNG in `screenshots`, "Screenshot saved"; Reset asked first (Cancel focused), then "Console reset" (seen in a `screenrecord`: it shows about 2.5 s). |
| Exit game and resume | Pass. Exit game wrote `SLPS-02020_resume.sav`; the next start asked "Resume where you left off?" with its picture and time; Resume booted `from 'SLPS-02020_resume.sav'`, Start over booted afresh, B cancelled. |
| A resume that fails | Pass. With `savestates` read-only, Exit game still stopped the game and the library said "Couldn't save your place: …". A resume state cut to 100 bytes: Resume returned to the library with "Failed to start …: Failed to load save state file …", and the next start asked again. |
| Mirror | Pass. The data folder's save states matched the working copy's (md5), with no `*.sav.XXXXXX`, and the screenshots were copied. |
| Hotkey capture | Pass. Fast forward's row took Select + X (Select skipped) and showed "Select + X"; `settings.ini` has `ToggleFastForward = Android-0/Button109 & Android-0/Button99`. |
| Hotkey in a game | Pass. Select + X in padtest turned fast forward on, and again off. Whether padtest saw Select or X couldn't be seen (its picture stayed black under the emulator's software Vulkan); `SelectChordTest` and `PadKeysTest` cover it, and the Thor checklist has it. |

Found and fixed during the pass: the core's own status icons (pause, fast forward) drew beside the app's marker, so
they're off on Android; the core's messages start with an emoji, which kept "Screenshot saved" from matching, so emoji
are left out; slot pictures drew at their own size, not filling their box. The emulator's GPU emulation wedged twice
during the instrumented runs (the app's render thread stuck in `eglSwapBuffers`), and the emulator was restarted.

### The in-game menu, part 2: discs and patch codes (2026-11-01)

Checked on `goose-arm64` with the debug APK (software Vulkan, lavapipe, after a cold boot), by key events and taps,
with both Ace Combat 3 CHDs (SLPS-02020 and SLPS-02021), their M3U, the Pad Test CUE and padtest.

| Check | Result |
| --- | --- |
| One tile per game | Pass. The two CHDs alone: one tile, "Ace Combat 3 - Electrosphere (Japan)", booting `…(Disc 1).chd`. With the M3U: one tile, the playlist ("Loaded 2 paths from m3u"). The library went from five tiles to three. |
| One set of states | Pass. A resume state made with the set was offered on the playlist's tile (both keyed `SLPS-02020`). |
| A state finds its disc | Pass. With disc 1's CHD renamed, slot 1 loaded on the renamed file ("The state's disc '…(Disc 1).chd' is disc 1 here"). With it renamed again during the game, the load failed in the panel with "The disc this state was saved on isn't in your library: Ace Combat 3 Electrosphere (Disc 1).chd.", and the game went on. |
| Migration | Pass. `SLPS-02021_3.sav` and `_5.sav`, put in the data folder before setup, moved to `SLPS-02020_2.sav` and `_5.sav` at the first play; no `SLPS-02021_*` was left, nothing was overwritten, and `migrated.SLPS-02020` was set. The pull had dated every state at the pull (a pull keeps the folder's times only since the next section's fix), so slot 3 went to the key's own. |
| Change disc | Pass. On the playlist: Disc 2 → "Switched to sub-image … (Disc 2)", and the next start booted it (`SLPS-02021`). On the set: Disc 1 → `Inserted media from …(Disc 1).chd`. With disc 2's file renamed, picking it said "Disc 2 isn't in your library any more." in the panel. |
| Grouping by name | Pass. Two ISOs the database doesn't know, `Test (Disc 1).iso` and `Test (Disc 2).iso`, showed as one tile, "Test". With `Test (Disc 1).cue` beside them, the group split: "Test (Disc 1)" tiles showed in its place (`GroupFilesNameRepeatsStayApart` checks the three). |
| Patch codes | Pass. A player's `Ace Combat 3 Electrosphere.cht` with two codes, one `[*]`: the game's list had four (the player's two, then the database's two others; "Infinite Missiles" once), one on, built in about 0.15 s before the boot. `settings.ini` got no `EnableCheats`. `GameCallsTest.codesFollowTheAppsFileNotThePlayers` checks the app's file and that the player's is untouched. |
| Patch codes panel | Pass. The panel listed the four, the player's `[*]` code on. Switching Rapid Missile Fire on and Infinite Missiles off kept the menu open and wrote `cheats/SLPS-02020.patchcodes` (`off … Infinite Missiles`, `on … Rapid Missile Fire`); the player's file was unchanged. The data folder had the file after Exit game, and the next start had Rapid Missile Fire on and Infinite Missiles off. |

Found and fixed during the pass: the core's `LoadState` read no serial from a state, so a renamed disc couldn't be
found by it (it reads it on Android now); listing the library opened every M3U each time (`LibraryScanTest`), so a
playlist's entries are kept until its file changes; slot rows grew a line for the disc and didn't fit the 640 x 360
panel at text 1.3x, so the disc joins the status ("Saved · Disc 2"); "Change disc" with "Disc 1 of 4" broke mid-word in
the 240 dp menu beside a side panel, so a row's value goes under its label when they don't fit; a code with a long name
and note was taller than the panel at 800 x 360 and text 1.3x, so names and notes show two lines at most. The
emulator wedged once during an instrumented run (the render thread spinning in the kernel) and was cold-booted.

### A pull keeps the folder's file times (2026-11-01)

Checked on `goose-arm64` with the debug APK, after `adb uninstall` and a fresh setup (the picker driven with taps)
whose data folder was `/sdcard/GooseTest/data`, with 14 files and save states in slots 1, 2, 3 and 5 of SLPS-02020.

| Check | Result |
| --- | --- |
| The first pull | Pass. Every working-copy file pulled has its folder file's time, to the millisecond (`stat`: `12:35:47.057003798` in the folder, `12:35:47.057000000` in the working copy, for slot 1), and the manifest holds the same time for both sides. `settings.ini` alone had a new time, as setup wrote its `InstallID` and games folder into it, and that went out to the folder. |
| Resume prompt | Pass. "Today · 12:21", the time of the folder's `SLPS-02020_resume.sav` (12:21:24). |
| Load state slots | Pass. Slot 1 "Today · 12:35", slot 2 "Today · 14:09", slot 3 "Today · 10:00", slot 5 "Today · 14:09", the times of the folder's files. |
| Nothing pushed back | Pass. After the game and Home (whose push doesn't wait), the folder's times and MD5s were as before setup, except `settings.ini`, and no `.goosetmp` or `.goosebak` was left. |

### RetroAchievements (2026-11-02)

Checked on `goose-arm64` with the debug APK, by key events and taps, signed in with the user's own account (they typed
the password on the emulator), with Ace Combat 3 (SLPS-02020).

| Check | Result |
| --- | --- |
| Sign-in | Pass. "Signed in as …" with the account's points, and **Sign out** focused. |
| The token | Pass. Sealed in the `achievements` preferences; no `secrets.ini` in the working copy or the data folder, and no token in `settings.ini` or logcat (`GooseStationHttp` logged only `POST retroachievements.org/dorequest.php -> 200`). |
| The menu's row and panel | Pass. The row read "2 of 142": one of the two is RetroAchievements' own "Warning: Unknown Emulator", which the server unlocks for a client it doesn't know (roadmap item 6). The panel showed the set's sections and badges. |
| A leaderboard | Pass. A real board loaded 957 entries, its top 10, and "You have no entry yet" (the account has none, so "Around you" wasn't seen). |
| Hardcore | Pass. With a resume state, the prompt offered only **Start** and kept the resume state; Load state, Patch codes and Fast forward were greyed with "Off in hardcore mode". |
| Offline | Pass. Started with the network off: the page said "Not connected", and the game said "Couldn't reach RetroAchievements; achievements are off for this game" once (`Login failed: No response`). With the network back, the next game start signed in and loaded the set. |

Found and fixed during the pass: switching tabs, or opening and closing a board, scrolled the menu away from its
Achievements row, where the menu scrolls, as the panel's focused row went with the old content and the focus fell to
the menu's top (fixed in `74a90b39d`: the row holds the focus for the change). Not checked by hand: an unlock's card and chime, which need a
real unlock in play (`AchievementOverlayTest` and `OsdLayerTest` cover the card).

### RetroAchievements without a connection (2026-11-03)

Checked on `goose-arm64-offline`, a second AVD made from `goose-arm64`'s config (with `/sdcard/GooseTest`'s BIOS and
games copied over), as `goose-arm64` was busy with part 3's tests. Debug APK, driven by taps and key events, signed in
with the user's own account (they typed the password), with Ace Combat 3 (SLPS-02020) against the real server. The
network was turned off and on with `svc wifi` and `svc data`.

| Check | Result |
| --- | --- |
| An online start saves the set | Pass. `files/achievements/` held `hashes.json` (disc 1's hash as game 11308), `sets/11308.json`, the session and the account. |
| A playlist's Change disc | Pass. The M3U switched sub-image ("Disc 2 of 'Ace Combat 3 Electrosphere' is in."), and the hash stayed disc 1's, as the playlist's first disc counts. |
| A disc set's Change disc, online | Pass. With the M3U moved aside, the library showed the disc set; Disc 2 went in (`Inserted media from …(Disc 2).`), hashed `34c17c45…`, and `hashes.json` gained it as game 11308. |
| An offline start with Hardcore on | Pass. Network off, Hardcore on: "Offline: achievements are saved and sent later (casual mode)" over the game (`Starting offline, from the set saved for '34c17c45…'`, disc 2's, the disc last in the drive). Load state stayed available (casual), the row read "1 of 141", and the Leaderboards tab "Not available offline". |
| Reset console offline | Pass. "System reset."; no "Hardcore mode is now enabled", and Load state stayed available. |
| The offline game stops | Pass. `The offline game stopped; signing out of its offline sign-in.`; Settings → Achievements: "Not connected. RetroAchievements is checked again when a game starts." |
| The network comes back in the library | Pass. Wi-Fi and data on: `Signing in again as …` within a second, `POST retroachievements.org/dorequest.php -> 200`, and the page showed the account's points. |
| No token on disk | Pass. `grep -rl -e Token -e '"t"' files/achievements` found nothing. |

Not checked by hand: an unlock earned offline, its card's note, and its send on reopening, which need a real
achievement earned in play; `AchievementsOfflineTest` covers them against the fake server (17 tests), and the full
instrumented suite passed (437 tests) on the same AVD.

### Memory card sync (2026-11-04)

Checked on `goose-arm64` with the debug APK of `f0cae7512`, set up afresh (`/sdcard/GooseTest`'s BIOS, games and data
folders), driven by taps and key events, with made-up credentials and an unreachable storage address (no real
storage: that check is the user's).

| Check | Result |
| --- | --- |
| The page, filled in | Pass. Each row opened its field with the keyboard; Enter saved it. The secret and passphrase rows said "Saved", their fields never showed the value, and Remove passphrase took the passphrase away. An empty Device name showed "Google sdk_gphone64_arm64". |
| Sync on, storage unreachable | Pass. "Offline at 17:47: changes upload once the storage can be reached."; Sync now tried again ("Offline at 17:48"). |
| The badge | Pass. "Offline", on the warning ground with the cloud-off icon; Left from Settings reached it, and A opened Settings → Memory cards. |
| Background and back | Pass. Leaving the app: `In the background: memory card sync pushed what it had (ok), and waits.`; back in front: `Back in front: memory card sync resumes.`, then the next sync. |
| No secret written out | Pass. `grep -r not-a-real-secret` found nothing in the data folder, the working copy or the app's preferences; `settings.ini` had `SecretStored = true` only. |
| `emusync/` stays on the device | Pass. The working copy had `emusync/1968633d21287e0e`; the data folder had no `emusync`. |

The full instrumented suite passed on the same AVD (509 of 510; the other, `ControllersScreenTest`, lost its test
process and passed on its own, 27/27), and the native tests (196).

### The memory card editor (2026-11-05)

Checked on `goose-arm64` (2400 x 1080, landscape) with the debug APK of the editor's branch, set up afresh with
`/sdcard/GooseTest`'s folders, whose data folder held four cards from earlier checks (backed up first, and put back
after). Driven by key events, the file picker by taps.

| Check | Result |
| --- | --- |
| The list | Pass. "Manage cards · 4 cards", focused on opening. The picker listed Ace Combat 3 Electrosphere, Pad Test #1 (100%) and padtest (the games) and Ace Combat 3 - Electrosphere (Japan) (a stray file). |
| A playlist's card | Pass. Ace Combat 3's playlist is named apart from the database; the editor showed `Ace Combat 3 Electrosphere_1.mcd` as its card, and booting the playlist opened that file (`Memory Card Slot 1: …/memcards/Ace Combat 3 Electrosphere_1.mcd`). |
| A real save | Pass. エ-スコンバット3 エレクトロスフィア, 2 blocks, `BISLPSP02020AC3ES`, with its icon. |
| Copy, Replace?, Save | Pass. Copy to Pad Test #1 (100%) made "1 change not saved"; a second copy asked "Replace the save of the same name on Pad Test #1 (100%)?", Cancel focused; Save said "Saved", and the card was the same in the working copy and the data folder (the mirror pushed it). |
| Delete, Undelete | Pass. "Delete エ-スコンバット3 エレクトロスフィア? You can undelete it while its blocks aren't reused."; the save stayed listed as "Deleted · …", still 2 blocks; Undelete brought it back (13 of 15 blocks free). |
| Export, Import | Pass. Export… opened the picker with `BISLPSP02020AC3ES.mcs`; the file had 16,512 bytes and the editor said "Exported …". Import save of it onto the stray card added the save. Import card of the `.mcs` was refused with the core's reason ("Unknown extension 'mcs'."). Backing out of the picker changed nothing. |
| Rename | Pass. File name MYSAVE asked first, with the usual-pattern warning and "Rename anyway?"; Cancel went back to the form with MYSAVE kept. |
| Leaving | Pass. B with a change asked "Save your changes?" (Discard, Save, Keep editing focused); Discard left, and the card's file was unchanged. |
| Found and fixed | The focus went to the left card's picker after a panel closed, as Android focused the screen's first element first; Export said it failed after writing the file (a `?:` on the picker's null, which means success); the overlay's scrim left the cutout strip and status bar undimmed. All fixed and rechecked here; the export has `CurrentDocumentsTest`. |
| Crashes | None (`adb logcat -b crash -d` empty). |

### The settings pages, stage 1 (2026-11-05)

Checked on `goose-arm64` (2400 x 1080, landscape) with the debug APK of `c8d18dff2`, installed over the existing one
(data kept). Driven by key events, the library's tiles by taps. Every setting changed was turned back off afterwards,
and the states saved were deleted (the data folder's mirror had none left).

| Check | Result |
| --- | --- |
| The hub | Pass. The ten pages in desktop's order, Controllers focused on opening; Graphics' summary "Renderer, enhancements, display, on-screen display", Memory cards' "Card types, sync, the card editor". |
| Emulation | Pass. The Speed section's heading over Emulation speed (100%), Fast-forward and Turbo speed (Unlimited, the back arrow faint at that end), Turbo's note "While the Turbo hotkey is held.". Right made it 125%, and `settings.ini` had `EmulationSpeed = 1.25`; Left put it back. |
| Pause on start | Pass. With `StartPaused = true`, padtest booted paused with its menu open on Resume. |
| The refresh rate | Pass. With VSync and sync to host refresh rate on, the log had `Refresh rate: Host=60.000004hz Guest=49.74682hz Ratio=1.2061074 - can't sync`: the core had the screen's rate (padtest is PAL, so it rightly doesn't sync). |
| Save state backups | Pass. With `CreateSaveStateBackups = true`, saving to slot 1, then Replace on slot 1, left `HASH-9FDA0E1A56D8DF5B_1.sav` and `_1.bak`. |
| Pause on a disconnect | Not checked: the emulator has no controller to unplug. It waits for the Thor. |
| Crashes | None (`adb logcat -b crash -d` empty). The emulator's System UI asked to be waited for once, with three emulators running. |

### The settings pages, stage 2: controllers (2026-11-06)

On `goose-arm64` (emulator-5554) at `ea98ffb14`, with no controller connected (the emulator has none). The full tier
at the same commit: 816/816 (four shard parts 734 and the OpenGL ES part 82; 40 OpenGL ES contexts, 0 Vulkan), read from
the result XML.

| Check | Result |
|---|---|
| Settings hub | Pass. Controllers' summary "Ports, types, bindings, hotkeys, rumble". |
| Controllers hub | Pass. The menu triggers' card, "Connect a controller to set it up.", Multitap Disabled, Port 1 "Analog Controller · No controller", Port 2 "Not Connected · No controller", Hotkeys. |
| Multitap | Pass. Right made it "Enable on Port 1 Only" (`[ControllerPorts] MultitapMode = Port1Only`), and the ports became 1A, 2, 1B, 1C, 1D (in pad order then; the final review's fix shows a port's slots together, 1A to 1D then 2, as desktop does). |
| Port 1A | Pass. Controller type Analog Controller, the core's settings with its descriptions (Analog Deadzone 0%, Analog Sensitivity 133%); Right on the deadzone wrote `AnalogDeadzone = 0.01`. Bindings: "No controller is on Port 1A…". Macros 1 to 4, "Not set". |
| Macro 4 | Pass. Trigger line for no controller, a switch per input; switching Circle on wrote `Macro4Binds = Circle`, and off deleted the key. Back focused Macro 4, now "Circle". |
| Back | Pass. Back from Port 1A focused its row on the hub; Port 1C (Not Connected) shows its type row only. |
| Hotkeys | Pass. "Connect a controller to set its hotkeys." with none. |

Not checkable on the emulator: a controller's port, bindings, hotkeys and macro triggers (no controller), and rumble (no
motors); `ControllerBindingsTest` and `GameCallsTest` cover them with the core, and the Thor's checklist (step 14) has
them by hand. The emulator's System UI and the app both showed "isn't responding" once early on, while the host was
loaded from the test runs (load average 28): the app's trace showed its main thread running a layout, not blocked, and
the page opened at once after a fresh start.

### The settings pages, stage 3: settings for each game (2026-11-06)

On `goose-arm64` (emulator-5554, 2400 x 1080, landscape) with the debug APK of `4b312dbc9`, installed over the existing
one (data kept), driven by key events. The game was padtest (`HASH-9FDA0E1A56D8DF5B`).

| Check | Result |
|---|---|
| The library | Pass. The foot says "A Play · Y Game settings". Y on padtest opened "Game settings" under "PADTEST", "HASH-9FDA0E1A56D8DF5B · PAL", "No settings of its own.", Console focused. |
| A game's row | Pass. Graphics: every row "Use global (…)"; Right on Internal resolution made 1x the game's own, marked "Set for this game", and the hub then said "1 setting of its own." and Graphics "1 set for this game". `gamesettings/HASH-9FDA0E1A56D8DF5B.ini` had `ResolutionScale`, in the working copy and, after the push, the data folder. |
| The game's start | Pass. padtest booted with `Loading game settings from 'HASH-9FDA0E1A56D8DF5B.ini'...` and `Resolution Scale: 2`. |
| In the game | Pass. The menu's Settings row (after Achievements) opened the same hub over the paused game; 3x applied at once ("Resolution scale set to 3x"); Console's Region and 8 MB RAM said "Applies when the game next starts."; Controllers showed "Bindings are the global ones: Settings → Controllers.", Multitap and Port 1's type and settings, each "Use global (…)". Back went to the menu, on Settings. |
| Use global | Pass. "Use global (1x (native))" first in the list; picked, the file went from the working copy and, with the next push, the data folder; the hub said "No settings of its own."; the running game went back to 1x. |
| Crashes | None (`adb logcat -b crash -d` empty). |

Before a reboot of the emulator, the mirror's pushes failed for every file ("The data folder created gamesettings as
gamesettings (1)", and the same for `settings.ini`): the documents provider listed the data folder without files it
held. After the reboot every push worked. That's the emulator's provider, not the app; the mirror's own rule, the
folder's side wins, then kept the data folder's copy over a change it hadn't sent.

### The settings pages, stage 4: post-processing and Advanced (2026-11-06)

On `goose-arm64` (emulator-5554, 2400 x 1080, landscape) with the debug APK of `4ee90ced9`, installed over the existing
one (data kept), driven by key events. Its Vulkan device is lavapipe through gfxstream, which the core takes as a
default renderer ("Allowing Vulkan as default renderer"), so the game ran on Vulkan. The game was padtest.

| Check | Result |
|---|---|
| The hub | Pass. Post-processing after Graphics, Advanced before Folders. |
| The chains | Pass. Display and Internal, each with its switch and "Add a shader: 22 shaders to choose from" (every shipped one but `crt/crt-hyllian-sinc`, ReShade included on Vulkan). The Add page listed each with its kind; A on crt-lottes went back with the focus on "1. crt-lottes", "GLSL · 12 options". |
| A stage | Pass. "Stage 1: crt-lottes", Move up and Move down disabled, Remove focused; the options as their values (-8, -3, 0.031). Right on Screen Warp X wrote `warpX = 0.04` to the working copy, and the next push to the data folder's `settings.ini`. |
| In the game | Pass. padtest showed crt-lottes: the curved corners and the scanlines. |
| The game's page | Pass. From the in-game Settings: "PADTEST" over Post-processing, "Use global (On)", the global stage dimmed with "The global chain: pick On or Off to give this game its own."; Right to Off copied the chain into `gamesettings/HASH-9FDA0E1A56D8DF5B.ini` with `Enabled = false`, marked "Set for this game". Use global asked "Use the global Display chain? This game's shader is removed.", Cancel focused; Use global deleted the file, and the focus came back to the row. Reload shaders: "Post-processing shaders reloaded." |
| Advanced | Pass. Logging, Save states, CD-ROM and CPU, with the core's names ("Information", "Zstandard (Default)"). Log to file on, then leaving the page: `goosestation.log` in the data folder. After a force-stop and a start, `goosestation-previous.log` there too, and the new `goosestation.log` from the build line on. |
| Crashes | None (`adb logcat -b crash -d` empty). |

### Slang presets, stage 1 (2026-11-07)

On the test emulators (`goose-test-1`, emulator-5580, the host's GPU, so Vulkan; `goose-test-2`, emulator-5582,
restarted with SwiftShader for OpenGL ES 3.1 through ANGLE), with the debug APK of the branch merged as #56.

| Check | Result |
|---|---|
| The library | Pass. `lib/arm64-v8a/librashader.so` in the APK (SONAME `librashader.so`, no `libc++_shared.so` needed, 16 KiB pages); "librashader loaded from librashader.so (ABI 2, API 5)". |
| The picture | Pass, both renderers. padtest with a probe preset (red over the top half of what it draws, blue below) as the Display chain's only stage: the frame's picture red at the top, blue at the bottom ("Slang preset p/probe.slangp loaded."). |
| Pack presets | Pass, both renderers. libretro's `crt/crt-royale`, `crt/crt-geom`, `crt/crt-guest-advanced` and `ntsc/ntsc-256px-svideo` compile and draw (the native GPU test, offscreen). |
| The lists | Pass. A preset is listed with kind Slang without being loaded (a file that isn't a preset is listed too, and says why when it's added); its options are its parameters (`TEST_GAIN`, 0 to 2 by 0.1), written as desktop writes them. |

### Slang presets on the Thor (2026-11-07)

The branch's debug APK and its test APK, installed over the existing app (data kept); the app's own APK went back
afterwards. Nothing touched the data folder: the frame test keeps its data in the app's cache, and the native tests
ran from `/data/local/tmp`.

| Check | Vulkan | OpenGL ES |
|---|---|---|
| The frame test (padtest, the probe preset as the Display chain) | Pass: the picture red at the top, blue at the bottom | Pass, the same ("GL_RENDERER: Adreno (TM) 740") |
| The native GPU tests (offscreen, read back) | 6/6 | 5/5 (the sixth is Vulkan's) |
| `crt/crt-royale` | 9.7 ms a frame | 7.3 ms |
| `crt/crt-geom` | 6.1 ms | 6.7 ms |
| `crt/crt-guest-advanced` | 11.5 ms | 10.7 ms |
| `ntsc/ntsc-256px-svideo` | 4.2 ms | 4.2 ms |

The times are a throwaway test's (not committed): padtest's 1280×960 picture scaled to 320×240, the preset drawn at
1440×1080 into a 1920×1080 target, 120 frames each followed by a one-pixel read back, so each includes a full wait
for the GPU. All fit a 60 fps frame. No picture was corrupt, so the extra submit after each chain frame ARMSX2
needed on Adreno isn't needed here.

### Touch controls (2026-11-08)

On `goose-test-1` (emulator-5580, a 1080x2400 phone at 420 dpi, landscape), the debug APK, set up by touch through the
folder picker, padtest, no controller connected. The tests: `TouchLayoutTest`, `TouchTrackerTest`, `TouchGlyphsTest`
and `TouchControlsTest` (JVM); `TouchOverlayTest`, `GameScreenTouchTest`, `ControllersScreenTest`,
`OrientationLayoutTest.theTouchControls` and `theTouchControlsWithSticks`, and the touch tests in `GameCallsTest`
(instrumented).

| Check | Result |
|---|---|
| The overlay in a game | Pass. With sticks, as padtest turns analog mode on: shoulders in the corners, D-pad and face diamond beside the picture, sticks over its lower corners, L3 and R3 above them. |
| Cross | Pass. Held, padtest's Cross lit, and the overlay's Cross drew pressed. |
| The left stick | Pass. Dragged up-right, padtest read X 117, Y -122; released, -1 and -1 (the core's centre, 127, as any controller at rest). |
| The menu button | Pass. Opened the menu; Resume by touch went back to the game. |
| Portrait | Not seen here: with the density at 280 (617 dp wide) Android letterboxed the landscape-locked app into a 617x463 dp landscape box instead of turning it, and the controls overlapped the near-4:3 picture there, as they should. Portrait's layout, the picture at the top and its centring again are the tests'. |

Not checkable on the emulator: Automatic hiding as a real controller connects (the emulator has no non-virtual pad),
and the press tick (no vibrator); `TouchControlsTest`, `GameScreenTouchTest` and `TouchOverlayTest` cover them. A
phone with no controller (on gesture navigation, slides in from the edge over the D-pad and Circle too), and a tablet
in portrait, are the user's check.

### Save state management (2026-11-08)

On `goose-test-1` (emulator-5580, landscape), the debug APK, set up by touch through the folder picker with
`GooseTest/bios`, `GooseTest/games` (padtest and its disc image) and an empty data folder, driven with gamepad keys.
The tests: `SaveListsTest`, `SaveReaderTest`, `SavesPageTest`, `InGameMenuTest` and `GameStartsTest` (JVM);
`GameMenuTest`, `SavesScreenTest`, `LibraryScreenTest`, `FocusAuditTest`, `OrientationLayoutTest`, and the save state
tests in `GameCallsTest` and `AchievementsOfflineTest` (instrumented); `AndroidDiscGroups.MatchStateGame*`
(`common-tests`).

| Check | Result |
|---|---|
| The library's foot | Pass. "A Play · X Saves · Y Game settings". |
| Save to a shared slot | Pass. In padtest, Save state, R1: the Shared tab, Slot 1 focused; slot 3 saved `savestates/savestate_3.sav`, mirrored to the data folder, and no `HASH-…_3.sav`. |
| A shared slot of another game | Pass. In the Pad Test disc, Load state, R1, slot 3: "Load padtest from shared slot 3? This game stops.", Cancel focused. Load saved the disc's resume state (`PADTESTEXE_resume.sav`), the game stopped, then the log had "Starting '…padtest.psexe' from 'savestate_3.sav'" and the core's "Loading state from 'savestate_3.sav'...". |
| Saves from the tile | Pass. X on padtest opened "Saves" under "PADTEST": Resume with its screenshot and time, focused. R1: "Slot 3", "padtest", Saved, its time. |
| Delete | Pass. X on shared slot 3 asked "Delete the save in shared slot 3?" over the page, Cancel focused; Delete left "No shared saves yet.", and the data folder's `savestate_3.sav` was gone after the push. |

Hardcore mode isn't checkable here without the RetroAchievements fake's padtest disc set:
`AchievementsOfflineTest.undoIsRefusedInHardcore` and `turningHardcoreOnDropsTheUndoState` cover the native side, and
`InGameMenuTest` and `SavesPageTest` the app's.

## Results on the AYN Thor

Checked on 2026-10-26 with the debug APK built from `9dc56372e`, on an AYN Thor: Android 13 (API 33), Snapdragon
8 Gen 2 (QCS8550), Adreno 740, 4 KB pages. The built-in pad connects as `Odin Controller` (`Android-0`) and reports
X/Y (left stick), Z/RZ (right stick), HAT_X/HAT_Y (D-pad) and BRAKE/GAS (L2/R2), all of which the app forwards. Its
key layout has real BACK, HOME and BUTTON_MODE keys, so on the Thor the pad's Back key left a game (in that build;
it now opens the prompt, see "The in-game menu"). No physical button on the Thor sends `BTN_MODE`, though: its only
buttons besides the gamepad controls are Back and Home (found 2026-10-30).

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

Found: the memory card save message showed the Japanese game title as `?????????`. The OSD's text font (Roboto) had
Latin glyphs only, and nothing loaded a CJK font for game titles (the same happened on desktop unless the UI language
was CJK). Fixed in #24: the OSD now draws CJK text with a system fallback font. Since 2026-10-31 the app draws the
core's messages itself (see "The in-game menu"). Not re-checked on the Thor with a Japanese title.

Not checked yet: a Bluetooth pad (step 2 below), the volume keys during a game, and the save-then-swipe-away check
in step 3.

**Storage, setup and the library** (Plan 2, step 7 below), checked on 2026-10-27 with the debug APK built from
`8004548f5` plus the mirror's layout fix (below), on the same Thor. The SD card is exFAT (`9C33-6BBD`). The app was
installed over Plan 1's (`adb install -r`); setup runs because the new build keeps its folders in its own preferences.
The BIOS folder was `ROMs/bios` on the SD card the first time and `GooseStation/bios` the second; the games were the
user's own `ROMs/psx` (83 CHDs) and `GooseStationGames`, which held padtest, both Ace Combat 3 discs as CHDs, an M3U of
the two, and a real CUE/BIN (`AC3 Disc 1 (CUE)`, extracted from the Disc 1 CHD with `chdman extractcd`, its `FILE` line
in upper case). The data folder was `GooseStation` on the SD card, then `GooseStation/data` on internal storage.

| Check | Result |
| --- | --- |
| Setup with the built-in controller | Pass, twice. The D-pad and A drove every step, B went back, and the focused button had a ring; touch only in the system picker. |
| BIOS import from a mixed folder | Pass. From `ROMs/bios` (Mega CD, PS2 and Switch files as well), the four images the core knows were copied to app storage (`SCPH1001.BIN` and three PS2 BIOSes, which the core accepts); the rest were skipped. |
| First setup's data folder held other files | **Found and fixed.** `GooseStation` on the SD card held a `games` folder (1.4 GB) and a `bios` folder. The mirror then copied everything in the data folder, so the first pull put 1.4 GB of games into app storage. The mirror now copies only GooseStation's own files and folders (see "The data folder and its mirror"), and setup says what else the folder holds. With the fix, the second setup's working copy was 2.4 MB: `settings.ini`, `playtime.dat` and the data folder's `bios/ps-30j.bin`; `games/` was left alone. |
| A games folder inside the data folder | Refused, as designed: `GooseStation/games` couldn't be added while `GooseStation` was the data folder. The test games were moved to `GooseStationGames`. |
| Library | Pass. The user's 83 CHDs from `ROMs/psx` as a cover grid; later the six entries of `GooseStationGames`. |
| CUE/BIN, M3U, CHD, EXE boots | Pass, all to a picture. `Boot Filename` to `Game started`: the CUE/BIN 2.99 s (its BIN found by the case-insensitive match; `SLPS-02020`, NTSC-J), the Disc 1 CHD 2.49 s, the M3U set 2.51 s (`Loaded 2 paths from m3u`), padtest 2.50 s. Nearly the same for every format, so reading in place isn't what the time goes on. |
| A memory card save reaches the folder | Pass. `Saving memory card to Ace Combat 3 Electrosphere_1...` at 10:06:27.447; the SD card's copy had a new time of 10:06:30.100, 2.65 s later, with the same MD5 (`f8eaf894…`). Another at 10:29:13.370 arrived at 10:29:15.150. No temporary file was left. |
| Card replaced in the folder mid-game | Pass, driven with `adb`. Both copies `b3201b01…`; Home; a changed card (`8234fabb…`) pushed into the folder; reopening: the game carried on (no second `Game started`) and the working copy kept `b3201b01…`; Back (`Game stopped.`): the working copy had `8234fabb…`, the folder's. |
| Home, then reopen, mid-game | Pass. The game resumed where it was. |
| Data folder unreachable | Pass. After `mv GooseStation GooseStation-away` on the SD card, the library showed "The data folder isn't reachable (Can't list 9C33-6BBD:GooseStation). Your changes are kept on this device and saved to the folder when it's back.", the log had `The data folder can't be reached.` every 2 s, and no new `GooseStation` folder appeared. A file added to the working copy reached the folder 2 s after it was moved back. |
| SD card removed mid-game | Android ended the app. With the data folder and the game both on the card, the eject began at 10:29:20; the mirror logged the unreachable folder every 2 s; at 10:29:25.9 Android found the app holding the CHD open on the card and killed it (`exited due to signal 2`). The card saved at 10:29:13 was safe on both sides (`eda799a8…`). A card write the game made at 10:29:24, after the card was out and not yet saved to the file, was lost with the process. After the card went back in, the app cold-started and listed the games again. See "Known limits". |
| SD card removed mid-game, with the eject handling | Pass, re-checked later on 2026-10-27 with the build that handles the eject. The data folder on internal storage, the CUE/BIN on the card. The game saved the card at 13:02:34.455; the folder's copy had a new time of 13:02:36.711, with the same MD5 (`8a250fe6…`). The eject began at 13:02:43.152, the app got `MEDIA_EJECT for volume 9C33-6BBD` 2 ms later, and `Game stopped.` came at 13:02:43.178, 26 ms after the eject began and 0.2 s before the card was unmounted. The process stayed the same (pid 14280): no kill. The library showed the message, `MEDIA_UNMOUNTED`/`MEDIA_BAD_REMOVAL` were handled once, and `MEDIA_MOUNTED` at 13:02:58.143 after the card went back in. |
| Killed during a 200 MB push | Pass. `am kill` with `big.bin.goosetmp` at 56 MB left a 153 MB temporary and no `big.bin`. On the next start the leftover was cleared and the push began again; 13 s later `big.bin` was 209,715,200 bytes with the source's MD5 (`89f5951e…`) and no temporary. Moving the finished temporary into place took about 11 s on this card. |
| A desktop `settings.ini` | Pass. A version 3 file with `Renderer = Software`, `SDL-0` bindings and Windows folder paths, pushed into the data folder: reopening logged `Reloaded the settings …`, `The settings are from another install (''), adapting them.` and `[MemoryCards] Directory 'C:\…\memcards' is outside the data folder, using 'memcards'.`; the result had no `Renderer` and no `PathNTSCJ`, `SearchDirectory = bios`, `Directory = memcards`, Android bindings on Port 1, `ShowFPS = true` kept, and this install's `InstallID`, which reached the folder with the next push. |
| Changing the data folder | Pass. **Folders** → **Data folder** → **Change**, `GooseStation/data` on internal storage, **Copy this device's data into it**: the app restarted (the new process's core was ready 0.5 s after the old one ended) and the new folder held the working copy's seven files with the same MD5s. |
| Moving Plan 1's data with `adb` | Doesn't work: `adb shell cp` and `adb pull` of the core's files fail with "Permission denied" (mode 0600). See "Moving Plan 1's data". |
| Crashes | None. `adb logcat -b crash` was empty; the one process death was Android's on the SD card's removal. |

Not checked: whether setup's data step showed the "other things" line on the Thor (not reported), padtest's controls
again (Plan 1's results stand), and a games folder on internal storage as a boot-time baseline.

**Settings and controls** (Plan 3, step 8 below), checked on 2026-10-29 with the debug APK built from `f40e18f92`,
installed over Plan 2's (`adb install -r`), on the same Thor. Its folders and grants were kept, and it went straight to
the library (`The core is ready.`, then `Catch-up pull: the data layout stopped growing.`). The Thor has a second,
lower screen (1080x1240), which shows the launcher; the app runs on the main one, in landscape (1920x1080). These
checks were driven with `adb` (`input -d 0 keyevent` for the pages, `setprop`, logcat and screenshots), apart from
those with the built-in pad, which the user did between 12:10 and 12:16, with `getevent -lt` recording the pad's
events. `uiautomator dump` doesn't work on this device ("null root node"), so screenshots were the evidence.
`[Display] ShowSpeed = true` was already in the data folder's `settings.ini`, so the OSD showed the speed. The built-in
pad connects as `Odin Controller` (descriptor `8e1073ea…`); its key layout (`Vendor_2020_Product_0111.kl`) maps both
`BTN_DPAD_*` keys and `ABS_HAT0X`/`ABS_HAT0Y`, but its D-pad sent only the hat. Afterwards the data folder's
`settings.ini` was put back as it was before these checks.

| Check | Result |
| --- | --- |
| Graphics: OpenGL ES, 4x, Show FPS | Pass. Set on the page (the resolution note showed). Ace Combat 3 (the CUE/BIN) logged `Trying to create a OpenGL GPU device...` and `GL_RENDERER: Adreno (TM) 740`; in the attract FMVs from 40 s, four OSD readings were `G: 14.95` to `16.95`, `V: 59.82`, `100% (100%)`. |
| Graphics: Vulkan, 4x, Show FPS | **Found: a crash, once in five Vulkan boots.** The first Vulkan boot, in a process whose previous game had used OpenGL ES, ended 1 s in with `Fatal signal 6 (SIGABRT)` on the CPU thread, while the loading screen showed `Compiling Pipelines: 1034/2257`. The core's crash handler caught a fault in `VulkanTexture::TransitionSubresourcesToLayout`, from `VulkanTexture::TransitionToLayout`, `GPUDevice::RenderImGui` and `Host::DisplayLoadingScreen`, in `GPU_HW::CompilePipelines` during `System::BootSystem` (`thor/crash-1.txt`). Four later Vulkan boots, in new processes and after an OpenGL ES game in the same process, ran (`No GPU requested, using first (Adreno (TM) 740)`): `G: 14.95`, `V: 59.79` to `59.82`, `100% (100%)` in the FMVs. Every Vulkan boot logged `Failed to read pipeline cache.` and compiled the 2257 pipelines again (about 2.6 s). **Cause and fix:** Android's `FullscreenUI::Shutdown()` stub didn't call `ImGuiFullscreen::Shutdown()`, so an OpenGL ES loading-screen texture stayed cached and was used as a `VulkanTexture` on the next Vulkan device. Fixed in `652a4f808`: the stub now calls it, as desktop's does. Checked on the emulator, in the same OpenGL ES to Vulkan switch in one process: 4 crashes in 4 before the fix, 0 in 4 after. A native test (`FullscreenUIStubs.ShutdownReleasesCachedTextures`) covers it. Rechecked on the Thor on 2026-10-30: 0 crashes in 3 (next row). |
| Vulkan crash recheck, 2026-10-30 | Pass: **0 crashes in 3 valid attempts** with the debug APK built from `85f32f5be` (the fix and master's pipeline cache, #27 and #28), where the same scenario crashed 3 times in 3 before the fix. Each attempt, in one process: `adb install -r` (which leaves the driver's shader cache cold), the core's `opengles_pipelines.bin`, `vulkan_pipelines.bin` and `vulkan_shaders.bin`/`.idx` deleted from the working copy's `cache/` (with `run-as`), Ace Combat 3 on OpenGL ES at 4x, whose loading screen showed (`Compiling Pipelines: 1988/2254`), Back and **Quit to library**, `settings.ini` switched to Vulkan at 4x and the app brought back (`Reloaded the settings …`), then Ace Combat 3 on Vulkan. With #27 a Vulkan compile from nothing takes about 0.87 s here, under the loading screen's 1 s, so three first attempts drew no loading screen on Vulkan and don't count; the three that count had 16 `yes` processes running during the Vulkan boot, which then took 3.7 to 4.8 s and drew its loading screen 16 to 25 times, with the logo (`thor/recheck/T-GREEN4/T-GREEN4-vulkan-second/ls07.png`). The OSD then showed `V: 59.82` and `100% (100%)`. With the pipeline cache saved (`Compressing and writing 464781 bytes to 'vulkan_pipelines.bin'`), the next Vulkan boot loaded it (`Pipeline cache hash: 35A614F1…`) and went from `Trying to create a Vulkan GPU device...` to `Created HW framebuffer` in 0.10 s, with no loading screen. The crash buffer was empty. |
| Vulkan fallback (test switch only) | Pass. A real Vulkan failure can't be forced on the Thor. With `Renderer = Vulkan` and `debug.goosestation.fail_vulkan` at 1, Ace Combat 3 logged `Trying to create a Vulkan GPU device...`, `Failing the Vulkan device for testing …`, `Vulkan didn't start (…), using OpenGL ES until the app restarts.`, the OSD message, `Trying to create a OpenGL GPU device...` and `Game started` 0.33 s later; the OSD showed the message, then `G: 59.82`, `V: 59.82`, 100%. The Graphics page's Renderer row then had the note. The property was set back to 0 and the app restarted, and the next boot used Vulkan. |
| Fast boot | Pass. Off (`PatchFastBoot = false`): the BIOS intro played, its diamond at 7 s and the Sony Computer Entertainment screen at 10 s after starting the game, and the log had no fast boot line. On: `Patching BIOS for fast boot.` and `Using Type 1 fast boot patch at offset 0x00018000.`, black until the "Produced by namco" screen, and no Sony screen in five screenshots over 17 s. |
| Portrait | Not applicable. The main screen's smallest width is about 468 dp, under the 600 dp at which Android may ignore the app's landscape lock, and the app was always shown in landscape. |
| Built-in controller alone, every page | User-reported: as expected (the focus ring always showed, and B went back with the entry focused). The pad's events (`getevent` on `Odin Controller`) show the walk through the pages driven with the D-pad, A, B and the Back key, with no touch. |
| Remap on the built-in pad, restart, reset | Pass, but on **D-Pad Up**, not Cross: the user remapped the first row, which is focused when the page opens. `Captured Button19 for Up` (the D-pad itself, at 12:11:58), then `Captured Button100 for Up` 16 s later, as the top face button (`BTN_WEST`, `BUTTON_Y`) went down. After a restart from Recents (a new process at 12:12:39), the log had no `is a new controller` line, so nothing was added back. **Reset to defaults** at 12:12:46 rewrote `settings.ini` (its working copy's time is 12:12:46.666, the folder's 12:12:49.973), and `Up = Android-0/Button19` is the default again. User-reported: the remap held after the restart. padtest wasn't run while the remap was in place, so its effect in a game wasn't seen. **Again on 2026-10-30, partly:** `Captured Button100 for Up` at 11:02:33 (the top face button, on the first row again), a restart from Recents (a new process at 11:02:46), then `Captured Button100 for Cross` at 11:03:14 and padtest 2 s later in the same process, with no restart in between. In padtest the pad sent the top face button six times and never the bottom one (`getevent`); the user reports it worked as expected. **Reset to defaults** then left `settings.ini` byte-identical to the copy taken before the steps (`Up = Android-0/Button19`, `Cross = Android-0/Button96`). So the remap seen in padtest after a restart is still not shown. |
| A Bluetooth pad | **Not done**: no other controller connected during the session (no `connected` line besides `Odin Controller`, and `dumpsys input` lists none). Still not done on 2026-10-30: no Bluetooth pad was available. |
| Ace Combat 3: Back, Mode, Select+Start | Back and Select+Start: pass. The pad's Back key at 12:13:34.364 paused the game 0.15 s later (`AAudioStream_requestPause`), and A on **Resume** restarted it. Select held, then Start, both held: the pause came 1.008 s after Start went down (12:14:00.025), and A resumed it. A Select tap followed by a separate Start tap (not held together) went to the game, as it should. **Mode wasn't pressed**: the pad sent no `BTN_MODE` all session. User-reported: no in-game pause menu after **Resume**. **Mode can't be checked on the Thor:** besides the gamepad controls, its only buttons are Back and Home (the system's Home), so nothing sends the `BTN_MODE` its key layout lists. Mode was checked on the emulator with a `uinput` pad ("Prompt: Mode" there), and is still open on real hardware with a Guide or PS button. |
| The memory card reaches the data folder | Pass. The game wrote sector 63 at 12:13:46.084 and `Saving memory card to Ace Combat 3 - Electrosphere (Japan)_1...` followed 5 s later (the core's own timer, before **Quit to library**, so the quit had nothing new to write). The folder's copy had a new time 2.2 s after that (12:13:53.301), with the working copy's MD5 (`f8eaf894…`). Back then opened the prompt and **Quit to library** gave `Game stopped.` at 12:14:33. |
| D-pad held across the prompt | The built-in D-pad sends a hat only (`ABS_HAT0X`/`ABS_HAT0Y`, no `BTN_DPAD_*` key all session), so the keys variant can't be done with it. With the hat: padtest, hat right held from 12:14:57.941, the Back key opened the prompt (pause at 12:14:58.727), the hat was let go with the prompt open (12:15:01.267), and A resumed: a screenshot about a minute later shows nothing lit (`thor/now-state.png`). The prompt was opened with Back, not Mode. |
| Vulkan during the user's steps | The user's Ace Combat 3 ran on Vulkan at 4x (12:13:03 to 12:14:33) with no crash; at 12:16 the crash buffer still held only the 01:30 crash. |
| Crash buffer | **Not empty**: it holds the Vulkan crash above (saved as `thor/crash-1.txt`; the buffer was cleared afterwards). No other crash. That crash is fixed in `652a4f808` (see the Vulkan row): 0 crashes in 4 on the emulator after it, where it was 4 in 4 before, and a native test covers it. Checked again on 2026-10-30 with the fix: empty after the recheck and the user's steps. |

Checked on 2026-11-01 with the debug APK built from `fdce23d5f` (PR #34), installed over the previous build with its
data kept. The user drove the checks with the built-in pad.

| Check | Result |
| --- | --- |
| Choice lists (checklist step 10) | User-reported: as expected. On Graphics, A opened each row's list on its current value, up and down moved and stopped at the ends, A picked a value, B closed a list unchanged, and left and right still stepped a closed row. |
| PlayStation glyphs (checklist step 10) | User-reported: as expected. Every Port 1 row on Controllers showed its glyph, readable at the system's largest font size, and the hotkey rows kept their words. |
| Hotkeys set (checklist step 9) | Pass. With Select held: `Captured Button105 for ToggleFastForward` (R2) at 01:56:54.694, `Captured Button102 for Screenshot` (L1) at 01:56:59.922, and `Captured Button99 for ToggleAnalogMode` (X) at 01:57:09.495, replacing R1 set 12 s earlier. On the Thor, X is `BTN_NORTH` (`getevent`). |
| Hotkeys in padtest | User-reported: as expected (each chord fired, padtest lit neither Select nor the button; a Select tap lit it briefly, a held one from about half a second). Select + X four times gave four "locked to analog mode by the game" messages (padtest locks it), one each. Of 12 Select + Start presses, only the one held 1.57 s opened the menu (`AAudioStream_requestPause` at 01:58:15.700). |
| Select held in a game | User-reported: as expected in Ace Combat 3. 13 Select presses alone, held up to 2 s, fired no hotkey. |
| Analog mode hotkey in a game | Pass in Ace Combat 3: 12 Select + X chords gave 12 toggles, each about 130 ms after the press, four in a row at 02:00:52–54 included; X alone 11 times toggled nothing. The core's own log line names the mode before the toggle (`SetAnalogMode` logs the old value); its on-screen message, which the app shows, names the new one. |
| Select + Square's button in a game | User-reported: as expected; the hotkey fired and Square didn't happen in Ace Combat 3. |
| Screenshot hotkey | Pass. Select + L1 in Ace Combat 3 at 02:07:53 saved `… 2026-11-01-02-07-53.png` to `screenshots`, mirrored to the data folder. |
| Exit over a resume state | Ace Combat 3 resumed from `SLPS-02020_resume.sav` (01:58:47); Exit game at 02:02:00 replaced it 4 s later (`State saved to 'SLPS-02020_resume.sav'`). Keep it and B: user-reported as expected; they leave nothing in the log, and the game wasn't started again after Keep it. |
| Scrolling and focus rings | User-reported: as expected. |
| Crash buffer | Pass: empty before (01:55) and after (02:08). |

padtest saved a resume state on Exit (`HASH-9FDA0E1A56D8DF5B_resume.sav`, 01:58:34): the core gives a PS-EXE its
content-hash ID in the serial's place, and its slots, resume state and resume prompt key on it as on a serial. That's
by design (#35 corrects the design's wording, which said such a game had no serial and saved none).

Checked on 2026-11-01 with the debug APK built from `79b977125` (part 2), installed over the previous build with its
data kept, then again with `1ffdb2763` for the fix below. The user drove the checks (checklist step 11) with the
built-in pad. The games folder held both Ace Combat 3 CHDs, their M3U and a CUE copy of disc 1, and the data folder a
test `Ace Combat 3 Electrosphere.cht` of eight copies of real Ace Combat 3 codes, in three groups, one `[*]`.

| Check | Result |
| --- | --- |
| One tile per game | User-reported: as expected. The M3U was one tile; the CUE copy, a tile of its own. |
| Change disc | Pass. Disc 2, then 1, then 2 went in (`Disc 2 of 'Ace Combat 3 Electrosphere' is in.` at 17:41:49, 17:42:23), with "Disc 2 inserted"; B went back to the row. |
| A state finds its disc | Pass. Slot 1, saved while playing the CUE copy, loaded from the M3U on disc 1 (`The state's disc 'AC3 Disc 1 (CUE).cue' is disc 1 here: …m3u`, 17:42:44 and 17:43:17), found by its serial. |
| Patch codes | User-reported: as expected. 11 codes (the player's eight, then the database's three: its ungrouped Infinite Missiles is a code of its own beside the player's in Weapons, as codes are told apart by group and name), the `[*]` one on, long names cut at two lines, the list scrolling. The switches held across disc changes and a restart (`cheats/SLPS-02020.patchcodes`: `on … Copy 1 of Infinite Missiles`), and the player's file was unchanged. |
| B out of the Patch codes panel | **Failed, then fixed.** The row had the focus but the menu showed its top: the panel's focused row went first, and the focus landed for a moment on another row, whose scroll outlasted the Patch codes row's. Fixed in `1ffdb2763` (the menu gives the focus back before a side panel goes); user-reported: as expected with the fix. |
| Input | User-reported: nothing pressed in the menu or its panels reached the game, and nothing stayed held. |
| Crash buffer | Pass: empty before (17:35) and after (18:42). |

Checked on 2026-11-02 with the debug APK built from `74a90b39d` (part 3), installed over part 2's with its data kept,
then again with each fix below. The user drove the checks (checklist step 12) with the built-in pad, on Wi-Fi, signed
in with their own account, with Ace Combat 3 (SLPS-02020).

| Check | Result |
| --- | --- |
| Sign-in | User-reported: as expected. The fields opened Android's keyboard on A, not on focus; "Signed in as …" with the points. |
| The game-start summary | User-reported: as expected. "You have unlocked 2 of 142 achievements…" at the top left, with the game's icon. |
| The menu's panel | User-reported: as expected. The row read "2 of 142"; the panel's sections and badges scrolled with the D-pad. |
| Switching tabs | **Failed, then fixed, twice.** The first R1 to Leaderboards scrolled the menu several rows up, and L1 scrolled it back: the board list was still loading, so the tab's heading held the focus, and it stopped being focusable before a board took it, so Android put the focus on the window's first focusable, the menu's top. Fixed in `1dcf03dd1` (the heading stays focusable while it has the focus); user-reported: the menu stays put. Then the menu's Achievements row flashed its focus ring at each switch, as it holds the focus for a frame while the panel changes. Fixed in the same commit (the menu's rows draw no ring while a side panel is open); user-reported: no flash. |
| A leaderboard | User-reported: as expected. 957 entries, the top list and "You have no entry yet"; B went back to the board, and B again to the menu's row. |
| Hardcore | User-reported: as expected. With a resume state, only **Start**, with the resume state kept; Load state, Fast forward and Patch codes greyed with "Off in hardcore mode", and the D-pad skipped them. |

Not checked on the Thor: an unlock's card and chime (no unlock came in play), and portrait, which the Thor doesn't
reach (`OrientationLayoutTest` covers it).

### RetroAchievements without a connection (2026-11-03)

The debug APK of `ec769a1f3` installed over the existing one (data kept), signed in already with the user's account,
Hardcore off (left as the user had it), driven by key events. Ace Combat 3's M3U tile with **Start over**, each exit
answered **Keep it**, so the resume state of 1 Nov 02:02 stayed as it was. Wi-Fi off and on with `svc wifi`.

| Check | Result |
| --- | --- |
| An online start saves the set | Pass. `files/achievements/` held `hashes.json`, `sets/11308.json`, the session and the account. |
| An offline start | Pass. Wi-Fi off: `Starting offline, from the set saved for '63e54a7f…'`; the menu's row read "1 of 141" and the Leaderboards tab "Not available offline". |
| The offline game stops | Pass. `The offline game stopped; signing out of its offline sign-in.` |
| Wi-Fi comes back in the library | Pass. `Signing in again as …` 5 s later, and `POST retroachievements.org/dorequest.php -> 200`. |
| The resume state | Pass. `SLPS-02020_resume.sav` still 2026-11-01 02:02. |

Not checked: the game's own picture (the screen capture can't read its surface), and an unlock earned offline.

### The memory card editor (2026-11-05)

The debug APK of `e7f87d062` installed over the existing one (data kept), with the user's memory card sync on, so
nothing was saved: every check ended in Discard or Cancel. The three cards (`padtest_1.mcd` and Ace Combat 3's two)
were backed up first and were byte-for-byte the same afterwards, in the working copy and the data folder. The user
drove it with the Thor's own controls; the keyboard was also checked over adb.

| Check | Result |
| --- | --- |
| Manage cards | Pass. The page's first row, "3 cards", focused on opening. |
| The editor at 640 x 360 | Pass. Two cards side by side; icons, the Japanese title, file names and block counts readable. |
| L1, R1, Left, Right | Pass. L1 and R1 moved between the cards, as did Left and Right at a card's edge (the pad's D-pad is a hat). |
| The card list | Pass. A opened it; B closed it unchanged. |
| A save's panel | Pass. Copy first and focused; B closed it, the focus back on the save. |
| Copy | Pass. "1 change not saved", the save on the other card. |
| Rename and the keyboard | **Found: A on a field didn't open the keyboard**, only a touch did. Compose's text field starts the text session the keyboard needs only for a tap or while it shows the keyboard on focus, which `TextFieldRow` turns off; the Memory cards page's fields and the Achievements sign-in had it too. Fixed in `e37377b84` (A or the D-pad centre turn it on while the field has the focus, with `TextFieldRowTest`); rechecked by the user with the pad: Gboard opened docked under the app, and the rename warning and Cancel worked. |
| Export | Pass. The picker opened from Export…, and the editor said "Exported …". |
| Delete | Pass. "Deleted · …" greyed, its blocks kept. |
| Leaving | Pass. B asked "Save your changes?" with Keep editing focused; Discard went back to Memory cards, Manage cards focused. |

Not checked on the Thor: Save itself (it would have reached the user's other devices; checked on the emulator).

### Post-processing on the Thor (2026-11-06)

The debug APK of `a953a8a9f`, with one extra line (not committed) that logged the core's frame rate and GPU time each
second, installed over the existing one (data kept); then the plain APK of `a953a8a9f` again. Each shipped shader was
put alone in padtest's own Display chain from the in-game Settings, by key events, and timed for 5 s (GPU time from
`[Display] ShowGPU`, set in padtest's file). The picture was 1920x1080; the global settings were the user's
(`ResolutionScale = 4`, `DownsampleMode = Box`). Afterwards padtest's Clear settings deleted its file, from the working
copy and, with the next push, the data folder.

| Shader | Vulkan: GPU ms a frame | OpenGL ES: GPU ms a frame |
| --- | --- | --- |
| None | 0.57 | 0.09 (the software renderer, below) |
| `anti-aliasing/fxaa` | 5.49 | not offered |
| `Cccalibrator` | 0.75 | 1.70 |
| `crt-lottes` | 5.49 | 5.03 |
| `CRT-NewPixie` | 3.24 | not offered |
| `CRTLottes2` | 2.49 | not offered |
| `Daltonize` | 0.72 | not offered |
| `Deband` | 2.01 | not offered |
| `dolphinfx/crt/CRT-HYLLIAN` | 2.06 | 1.70 |
| `dolphinfx/edge-smoothing/DDT` | 0.85 | 0.43 |
| `dolphinfx/edge-smoothing/XBR` | 4.43 | 3.99 |
| `dolphinfx/pixel-art/PIXELLATE` | 1.34 | 0.86 |
| `edge-smoothing/super-xbr` | 6.54 | not offered |
| `FilmGrain2` | 3.23 | not offered |
| `interpolation/bicubic` | 1.29 | not offered |
| `LUT` | 0.82 | not offered |
| `misc/multi-LUT` | 0.82 | not offered |
| `ntsc/ntsc-adaptive-lite` | 5.21 | not offered |
| `scanlines-abs` | 0.72 | not offered |
| `simple-brightness` | 0.67 | 0.18 |
| `simple-flip` | 0.67 | 0.18 |
| `simple-gamma` | 0.85 | 0.37 |
| `simple-sharpen` | 0.81 | 0.33 |

- Every shader compiled and kept the full frame rate (59.3 fps; 58.9 for XBR on OpenGL ES): the costliest, super-xbr,
  takes 6.5 ms of the 16.7 ms a frame has. So none is too slow to offer.
- Under OpenGL ES (padtest's own `[GPU] Renderer = OpenGL`), Add a shader offered the 10 GLSL shaders only, as on the
  emulators.
- **Found, outside post-processing:** with `DownsampleMode = Box`, the hardware renderer doesn't start on the Thor's
  OpenGL ES: the box downsampling shader defines `FACTOR` as an `int` and compares it with a `uint`, which Adreno's
  GLSL ES compiler rejects ("no operation '<' exists that takes … 'uint' and … 'const int'"). The core falls back to
  the software renderer ("Failed to initialize OpenGL renderer, falling back to software renderer"), whose paused
  picture behind the menu was sheared. Vulkan isn't affected. Both are fixed: the shader since #53 (`FACTOR` is a
  `uint`), and the sheared picture, a row alignment in OpenGL texture uploads, since #54.
- The pages themselves on the Thor: the game's Post-processing ("Use global (Off)", then On, "Set for this game"), the
  Add page, a stage's page (Remove focused on a lone stage) and Clear settings' question all worked with key events;
  the hub counted the chain as one ("3 settings of its own": two in Graphics, one in Post-processing).

### Touch controls on the Thor (2026-11-08)

The debug APK of `2ece4813e` (the head of #61, now on `master`), installed over the existing one (data kept). The user
checked the touch controls by hand: all of them worked as expected. On the Thor they show only with Touch controls on
Always, since its built-in pad is a connected controller and Automatic hides them.

Not checkable on the Thor: Automatic with no controller connected, the edge gestures on a phone with gesture
navigation, and portrait (the Thor's screens are landscape). A phone with no controller and a tablet in portrait are
still to be checked.

## Checks on the AYN Thor

The full checklist, on the Thor itself, with a controller and headphones. Find the Thor's serial with
`adb devices`, and allow USB debugging on the Thor when it asks. Use the serial in place of `emulator-5554`.

The BIOS and the games go on the SD card, and the data folder on internal storage. Find the SD card's folder with
`adb shell ls /storage` (a name like `1234-ABCD`). If the shell can't write to the SD card, copy the files with a
computer or a file manager instead. Plan 1's checks (steps 1 to 6) need nothing more than this; step 7 needs a
CUE/BIN game and an M3U set as well.

```bash
export ANDROID_SERIAL=<the Thor's serial>
SD=/storage/<the SD card's folder>
adb shell mkdir -p $SD/GooseStation/bios $SD/GooseStation/games /sdcard/GooseStation/data
adb push ~/work/goose-test/ps-30j.bin $SD/GooseStation/bios/
adb push extras/padtest/padtest.psexe $SD/GooseStation/games/
adb push "$HOME/work/goose-test/Ace Combat 3 Electrosphere (Disc 1).chd" $SD/GooseStation/games/
adb uninstall io.github.ilyalissoboi.goosestation   # setup runs on a fresh install only
adb install android/app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -n io.github.ilyalissoboi.goosestation/.MainActivity
```

Run setup with the built-in controller first (step 7's first check), picking `GooseStation/bios` and
`GooseStation/games` on the SD card and `GooseStation/data` on internal storage. Settings are changed in
`/sdcard/GooseStation/data/settings.ini`, as in "Changing settings": edit the app's file, since one pushed without
its `[Android] InstallID` loses its `Renderer`.

Keep a log of each session in a second terminal. `ANDROID_SERIAL` is per shell, so set it there too (or pass `-s`):

```bash
export ANDROID_SERIAL=<the Thor's serial>
adb logcat -v time > thor.log
```

1. **padtest, with the Thor's own controls.** Start padtest from the library.
   - [x] Each face button lights the right PS1 button: bottom = Cross, right = Circle, left = Square, top =
     Triangle. If Cross/Circle and Square/Triangle come out swapped, the pad reports Nintendo labels: note it, for
     the remap screen. Don't work around it in code.
   - [x] L1, R1, L2, R2, L3, R3, Start, Select and the D-pad each light the right button.
   - [x] Both sticks move their crosses and go back to 0 when let go.
   - [x] Nothing sticks or repeats: hold each button, then let go.
   - [x] L2 and R2 press once each, and let go cleanly, whether they're analog or digital.
   - [ ] No button leaves the game on its own; Home does, and Back, Mode or Select+Start open the prompt (step 8).
     Note any other button that sends Back.
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
   - [x] Full speed: the OSD's speed stays at 100%, and `V:` is about 59.9.
   - [x] No audio crackle.
   - [x] Plug headphones in mid-game, then unplug them: the sound moves over each time, with no crash and no lasting
     silence. The log should have `AAudio stream disconnected, reopening it on the new output device.` each time, and
     no `Failed to reopen the AAudio stream`. On the Thor: the sound moved over, but Android re-routed the stream,
     so it was never disconnected and the reopen path wasn't exercised.
   - [x] Home, then reopen the app: the game resumes where it was.
   - [x] Screen off and back on: the game resumes, without a black screen.
   - [ ] Save to the memory card, press Home within 5 s, then swipe the app away in Recents. Start it and the game
     again: the save is there. The log has `Saving memory card to …` just after the Home.
4. **Ace Combat 3, with Vulkan.** Repeat step 3 with `Renderer = Vulkan` in the pushed `settings.ini`.
5. **The default renderer.** Push the file again with the `Renderer = …` line left out (or `Renderer = Automatic`),
   and start Ace Combat 3.
   - [x] The log has `Allowing Vulkan as default renderer.` and `Trying to create a Vulkan GPU device...`. If it
     has `Not using Vulkan …` and `Trying to create a OpenGL GPU device...` instead, note the GPU name it logs.
6. **Afterwards.**
   - [x] `adb logcat -b crash -d` is empty.
   - [x] Note the renderer lines (`adb logcat -d | grep -E "GPUDevice|Trying to create"`).
   - [x] Note the page size (`adb shell getconf PAGE_SIZE`). The core is built for 16 KB pages, so on 4096 or 16384
     fastmem stays on and the log has no `fastmem is disabled` line.
7. **Storage, setup and the library.** The emulator's checks above, with the BIOS and games on the SD card and the
   data folder on internal storage. Put a CUE/BIN game (a real one, so it boots to a picture) and an M3U set in the
   games folder first.
   - [x] The whole setup with the built-in controller, run before step 1: the D-pad moves between the buttons, A
     presses them, B goes back a step, and the focused button has a visible ring. Touch only in the system picker,
     if it needs it. The
     library then lists every game.
   - [x] The CUE/BIN game, the M3U set and the CHD each boot from the library.
   - [x] Save on a memory card in a game. `/sdcard/GooseStation/data/memcards/` has the change within 5 s. A
     `.goosetmp` may show up between the steps of a write, but none is left behind.
   - [x] Home mid-game, replace the card in the folder with `adb push`, then reopen. The game keeps its card. After
     quitting, the working copy has the folder's version
     (`adb shell run-as io.github.ilyalissoboi.goosestation md5sum "files/data/memcards/<the card>"`).
   - [ ] Delete a save state from the folder, then reopen: it's gone from the working copy
     (`adb shell run-as io.github.ilyalissoboi.goosestation ls files/data/savestates`).
   - [ ] Move the data folder away (`adb shell mv /sdcard/GooseStation/data /sdcard/GooseStation/data-away`), then
     save on a memory card. The unreachable banner shows, and the save stays in the working copy. Move it back and
     reopen the app: the save reaches the folder, and the banner goes.
   - [x] Remove the SD card mid-game, a moment after a memory card save. The game stops, and the library says "The
     SD card was removed, so the game was stopped. Its memory cards are saved on this device."; the app isn't killed
     (no `exited due to signal` in the log). Note what the library shows after it (a banner for the games folder, or
     none). Nothing is lost: the memory card in the data folder has the game's last save. Put the card back and
     reopen the app: the games are listed again. On the Thor: what the library showed afterwards wasn't noted.
   - [ ] Repeat with the data folder on the SD card. Make a `GooseStation/data` folder there, then **Settings** →
     **Folders** → **Data folder** → **Change**, pick it, and choose **Copy this device's data into it**; the app
     restarts on it. Then remove the card mid-game, a moment after a save: the game stops with the same message, the
     unreachable banner shows "(the SD card was removed)", the working copy keeps the save, and it reaches the folder
     once the card is back.
   - [x] Kill the app during a large push. Make a 200 MB file (`adb shell "dd if=/dev/urandom
     of=/data/local/tmp/big.bin bs=1048576 count=200 && chmod 644 /data/local/tmp/big.bin"`), copy it in with
     `adb shell run-as io.github.ilyalissoboi.goosestation cp /data/local/tmp/big.bin files/data/screenshots/big.bin`,
     press Home,
     then `adb shell am kill io.github.ilyalissoboi.goosestation` once a `big.bin.goosetmp` shows in the folder.
     Start the app again: the folder has no `big.bin` that is short, no temporary document is left, and the next
     push completes.
   - [x] Copy a desktop `settings.ini` (version 3, `Renderer = Software`, an `SDL-0` binding) into the data folder,
     then reopen. The renderer is back to Automatic, and the pad maps.
   - [ ] Boot time from the library: the time from `System: Boot Filename: …` to `AndroidHost: Game started: …` in
     the log, for Ace Combat 3 read in place from the SD card, three boots. For a baseline, also boot it from a games
     folder on internal storage (add one in **Settings** → **Folders**), or with Plan 1's build (#23, from
     `7e1d16150`, installed over this one with `adb install -r -d`) and the CHD pushed into
     `/sdcard/Android/data/io.github.ilyalissoboi.goosestation/files/games`; its log has the same two lines. Plan 1
     didn't record a boot time on the Thor; on the emulator it was 0.7 to 1.4 s. This shows whether reading in place
     is slower.
   - [x] `adb logcat -b crash -d` is empty.
8. **Settings and controls.** With the build that has the settings pages, after step 7's setup.
   - [x] Everything with the built-in controller alone, no touch: **Settings**, each page, every list (Renderer,
     Internal resolution, Aspect ratio, each BIOS region, the Controller row with a second pad connected), the
     Controllers page and a capture on it, then back to the library. The focused element always has a ring, B goes
     back a step, and each page's entry in the hub (or **Settings** in the library) has the focus again after it.
   - [ ] B also goes back from a page whose only focusable line does nothing: "Reading the settings…" while a page
     loads, and "Connect a controller to remap it." with no controller connected (the framework turns `BUTTON_B`
     into Back there).
   - [ ] Remap a button on the built-in pad (Controllers → Cross, then another button), then swipe the app away and
     start it again: the remap holds, in the page and in padtest. **Reset to defaults** restores the default.
   - [ ] A Bluetooth pad, connected for the first time, gets its own mapping (`'<name>' is a new controller,
     Android-<n>.` and `Added … bindings for Android-<n> to Port 1.`). Remapping it leaves the built-in pad's
     mapping alone, in the page and in padtest.
   - [ ] In Ace Combat 3, the pad's Back key, the Mode key, and holding Select+Start for a second each open the
     prompt, and the game doesn't react to the press: no in-game pause menu after **Resume**. **Quit to library**
     saves the memory card (`Saving memory card to …`), and the card reaches the data folder
     (`/sdcard/GooseStation/data/memcards/`).
   - [ ] Hold the D-pad as keys while opening the prompt, let go, then **Resume**: nothing stays pressed in padtest.
     padtest can't tell keys from a hat, so check what the D-pad sends first, with `adb shell getevent -l` on the
     pad's `/dev/input/event…` (`BTN_DPAD_*` keys or `ABS_HAT0X`/`ABS_HAT0Y`), or use a Bluetooth pad whose D-pad
     sends keys. With a hat only, this item can't be done with that pad.
   - [x] Graphics: OpenGL ES, then Vulkan, each with 4x and Show FPS, in Ace Combat 3: the OSD's `G:` and `V:` show,
     the log has the renderer's `Trying to create a … GPU device...`, and the speed stays at 100% (turn on
     `[Display] ShowSpeed` in `settings.ini` for the speed; Show FPS alone gives only `G:` and `V:`).
   - [x] The Vulkan fallback. A real Vulkan failure can't be forced on the Thor, so this checks it with the test
     switch only: `adb shell setprop debug.goosestation.fail_vulkan 1`, then Ace Combat 3 with Vulkan. The OSD says
     "Vulkan didn't start on this device, so GooseStation is using OpenGL ES.", the game runs on OpenGL ES, and the
     Graphics page's Renderer row says so. Set it back with `adb shell setprop debug.goosestation.fail_vulkan 0`
     and restart the app.
   - [x] BIOS: Fast boot off, start a game: the BIOS intro plays. On: it doesn't.
   - [ ] If the Thor shows the app in portrait (a large screen's orientation override), the settings pages look
     right, with nothing under the navigation bar.
   - [x] `adb logcat -b crash -d` is empty.

   Checked on 2026-10-29 and 2026-10-30 (see "Results on the AYN Thor"). Still open: B on the lines with no action
   (not reachable on the Thor: the loading line lasts milliseconds, and the built-in pad is always connected), the
   remap seen in padtest after a restart (the capture, padtest with a remap, and the reset were seen, but not the
   restart in between), a Bluetooth pad (none available), the Mode key in a game (the Thor has no Mode button; it
   was checked on the emulator with `uinput`, and is open on real hardware with a Guide or PS button), and the D-pad
   as keys (the built-in one sends a hat). The Vulkan crash from 2026-10-29 (an OpenGL ES loading-screen texture
   left cached by Android's `FullscreenUI::Shutdown()` stub, then used as a `VulkanTexture` on the next Vulkan
   device) is fixed in `652a4f808`: 0 crashes in 3 on the Thor afterwards, where it was 3 in 3 before.

9. **The in-game menu and hotkeys.** With the build that has the in-game menu, after step 7's setup. The Thor's pad
   has no Mode button and its D-pad is a hat: open the menu with Back or Select + Start, and move with the D-pad.
   - [ ] Every row works with the pad alone: Save state (an empty slot, then the same slot again: "Replace the save
     in slot 1?", Replace and Cancel), Load state, Fast forward (the marker), Analog mode, Screenshot, Reset console
     (Reset and Cancel), Exit game.
   - [ ] B steps out one layer at a time: the question, then the slot panel (back to its row), then the menu.
   - [x] Nothing pressed in the menu reaches the game, and a direction or button held as it opens doesn't stick after
     Resume.
   - [ ] Exit game, then start the game again: "Resume where you left off?", with its picture and time; Resume, Start
     over and B each work.
   - [ ] Exit game again: "Replace your saved place from …?", Keep it focused. Keep it exits and the next start
     offers the old resume state's picture; Replace exits and replaces it; B stays in the game.
   - [x] Set each hotkey on the Controllers page (Select + a face button). In padtest: Select + that button fires it,
     and padtest lights neither Select nor the button; a Select tap lights Select briefly; Select held lights it from
     about half a second; Select + Start held opens the menu without Select lighting.
   - [ ] In a game with a map or menu on Select, holding Select still shows it.
   - [x] With the Analog mode hotkey set (Select + Square's button), a single press of that button in a game does
     nothing to the analog mode; Select + it toggles it once.
   - [x] In a game where Square does something visible, Select + Square's button fires its hotkey and Square doesn't
     happen.
   - [x] Scrolling: on a long settings page, the focused row stays in the middle; at the bottom the last row's ring
     shows whole, and going back up shows the page's title. The ring of a focused menu row isn't cut by the row
     below.
   - Exiting a game that's still starting (it saves no resume state) is too quick to try by hand on the Thor; it's
     covered by `InGameMenuTest.exitBeforeTheBootSavesNothing` and `GameMenuTest.gameRowsWaitForTheBoot`.
   - [x] `adb logcat -b crash -d` is empty.

10. **Choice lists and PlayStation glyphs.** With the build that has them.
   - [x] On Graphics, A on each row opens its list, with the current value focused and marked. Up and down move and
     stop at the ends; A on another value picks it, and the row shows it; B closes a list with nothing changed. Left
     and right on a closed row still step.
   - [x] On Controllers, every Port 1 row shows its PlayStation glyph (△ ○ × □ in their colours, the D-pad, sticks,
     L1–R3, Select, Start), readable with the system's largest font size; the hotkey rows keep their words.
11. **Discs and patch codes.** With the build that has them, an M3U set with its discs in the games folder, and a
    player's `cheats/<game title>.cht` in the data folder with a few codes in groups, one marked `[*]`.
   - [x] The set is one tile (the M3U); a separate CUE copy of a disc is a tile of its own.
   - [ ] Change disc comes after Load state with "Disc 1 of 2"; its panel focuses the disc in the drive, marked
     Current; another disc goes in with "Disc 2 inserted"; B goes back to the row. (Passed on the Thor: discs went in
     with "Disc 2 inserted" and B went back; the row's place, its value and the panel's focus weren't recorded.)
   - [ ] With disc 2 in, a slot saved on disc 1 reads "Saved · Disc 1" and loads on disc 1.
   - [x] Patch codes comes after Screenshot; its groups, the `[*]` code on, long names wrapping at two lines, every
     row reached with the D-pad and seen whole; switching codes keeps the menu open; B goes back to the row, in view.
     On the Thor: B out of the panel failed at first, and passed with the fix in `1ffdb2763`.
   - [x] The switches hold across a disc change and an Exit and restart; the player's file is unchanged.
   - [x] Nothing pressed in the panels reaches the game.

12. **RetroAchievements.** With the build that has them, on Wi-Fi, with a RetroAchievements account and a game it
    knows that has leaderboards.
   - [ ] Settings → Achievements: the D-pad passes over the fields without opening the keyboard; A opens it. Sign in:
     "Signed in as …" with the points, **Sign out** focused. (Passed on the Thor: the keyboard opened on A, not on
     focus, and "Signed in as …" showed the points; Sign out's focus wasn't recorded.)
   - [x] A game start shows "You have unlocked N of M achievements…" with the game's icon, and the menu's
     **Achievements** row shows "N of M".
   - [x] In its panel, the sections and badges scroll with the D-pad; L1 and R1 switch the tabs, and the menu stays
     still each time, its Achievements row in view, with no focus ring flashing on it. On the Thor: the menu scrolled
     and the ring flashed at first, and both passed with the fixes in `1dcf03dd1`.
   - [x] A on a leaderboard: its entry count, the top 10 and your entry or "You have no entry yet"; B goes back to the
     board, and B again to the menu's row.
   - [x] Hardcore mode on: a game with a resume state offers only **Start**; Load state, Fast forward and Patch codes
     are greyed with "Off in hardcore mode", and the D-pad skips them. Switch it off afterwards.
   - [ ] An unlock in play shows its card at the top centre, with its sound.

13. **The memory card editor.** With the build that has it. With memory card sync on, press nothing that saves:
    leave with **Discard**.
   - [x] Settings → Memory cards → **Manage cards**, the first row, with the number of cards; the editor shows two
     cards side by side, readable at 640 x 360.
   - [x] L1 and R1 move between the cards, as do Left and Right at a card's edge; A on a card's name opens the list of
     cards, B closes it.
   - [x] A on a save: its panel, Copy focused; B back to the save. Copy makes "1 change not saved".
   - [x] Rename…: A on Title or File name opens the keyboard, docked under the app (fixed in `e37377b84`); a name that
     doesn't follow the usual pattern asks "Rename anyway?", and B goes back to the form with the name kept.
   - [x] Export… opens the file picker and says "Exported …"; Delete keeps the save listed, greyed.
   - [x] B with changes asks Save, Discard or Keep editing (focused); Discard leaves, and the cards are unchanged.

14. **Controllers, stage 2.** With the build that has it, a Bluetooth controller with motors (a DualShock 4 or an Xbox
    pad) and the Thor's own controls. Note `settings.ini`'s `[Android]` and `[PadN]` before and after.
   - [ ] The hub lists both controllers; the Thor's own says it has no motors Android can drive, and the Bluetooth
     pad has **Rumble strength** at 100%.
   - [ ] The Bluetooth pad on **Port 2**, Port 2's type **Analog Controller (DualShock)**: in a two-player game, each
     controller drives its own player; `[Android] ControllerPorts` has `2` for it, and its bindings are in `[Pad2]`.
   - [ ] Rumble in a game that rumbles: the Bluetooth pad rumbles, never the Thor itself; at 30% it's weaker, at 0%
     it doesn't. With both on Port 1, the one pressed last rumbles. The menu opening and the game's end stop it.
   - [ ] A save state hotkey (Select + a button for **Save To Selected Slot**) saves "Saved to slot N", which the
     in-game menu's Load lists with its picture; **Load From Selected Slot** loads it.
   - [ ] Multitap **Enable on Port 1 Only**: the ports become 1A to 1D; a controller on 1C starts a game without
     multitap with "<name> is on Port 1C, which this game doesn't use."
   - [ ] A macro (Cross & Circle, Repeats every 5 frames) fires while its trigger is held.
15. **Settings for each game.** With the build that has them, the Thor's own controls, and a game whose file desktop
    hasn't touched. Note `gamesettings/` in the data folder before and after.
   - [ ] **Y** on the game's tile opens "Game settings" with its title and "No settings of its own"; the foot of the
     library says "Y Game settings".
   - [ ] Graphics, Internal resolution: Right from "Use global (…)" sets it, the row says "Set for this game", the hub
     says "1 set for this game", and `gamesettings/<serial>.ini` has `ResolutionScale`. The game starts at it, and
     another game doesn't.
   - [ ] In the game, the menu's **Settings**: Internal resolution changed there shows at once over the paused game
     once resumed; Region says "Applies when the game next starts.". B goes back to the menu on Settings.
   - [ ] "Use global" on the last row: the hub says "No settings of its own", and the file is gone from the data
     folder after the next push.
   - [ ] Controllers for the game: Port 1's type **NeGcon** for this game only: the game sees a NeGcon (its steering
     works with the Thor's stick), another game a DualShock; the global Port 1 page lists the NeGcon's inputs under
     "NeGcon (a game's)".
16. **Save state management.** With the build that has it, the Thor's own controls, and two games with a key. Note the
    data folder's `savestates/` before and after.
   - [ ] In the first game, Save state, R1: the Shared tab; saving to slot 3 writes `savestate_3.sav`, and the slot
     names the game.
   - [ ] In the second game, Load state, R1, slot 3: the question names the first game, Cancel focused; Load saves the
     second game's place and starts the first from the state.
   - [ ] Load state, then **Undo load** after Load state puts the game back, "Undid the last load"; until a load it
     says "Nothing to undo".
   - [ ] In Load, X on a slot asks before deleting; Delete removes it, with its `.bak`.
   - [ ] In the library, X on a tile opens Saves; A on a slot starts the game from it, on its disc; a long press by
     touch offers Saves and Game settings.
   - [ ] With hardcore mode on (step 12): Load state and Undo load say "Off in hardcore mode", the Undo hotkey says
     "Loading a state is off in hardcore mode", and the Saves page deletes but won't start from a save.
