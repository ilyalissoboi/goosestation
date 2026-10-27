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
button switches, and the choice is saved as `[UI] GameListGridView` in `settings.ini`). **Folders** opens the folder
settings, and **Refresh** scans the games folders again. The library also refreshes when the app comes back to the
foreground. A, Enter or a tap starts the selected game; Back leaves it. The tile for the last game played is
focused when the library comes back. A tile shows the game's cover from `covers/` in the data folder, or its title
when there's none; a list row shows the title, region, serial and play time.

The library lists what the core's game list finds in the games folders, as on desktop: disc images (CUE/BIN, CHD,
ISO and the rest), M3U playlists, and PS-X EXE files. Disc images in the same folder as a CUE resolve by name, with
a case-insensitive match when the case differs (`FILE "TRACK 01.BIN"` next to `Track 01.bin`), and names holding
`#`, `%` or `?` work as they are.

Home pauses the game, and reopening the app resumes it. Pausing also writes any memory card changes to their files
straight away (a paused game never reaches the card's usual save, 5 emulated seconds after the last write), so a save
made just before Home survives the app being killed in the background. Going to the background, or losing focus to
the notification shade or a dialog, also lets go of every button and stick, so press or move them again after
coming back.

In a game, the app keeps only the controllers' gamepad buttons and D-pad keys. Volume, media and other keys go to
the system, so the volume keys work during a game.

Never commit BIOS images or games. The checks below use `~/work/goose-test/ps-30j.bin` and the CHDs from the same
folder.

### The folder settings

**Folders** in the library opens them, with the same D-pad rules as setup:

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

- **Only GooseStation's own data is mirrored.** That is `settings.ini`, `secrets.ini`, `playtime.dat` and
  `custom_properties.ini` at the top of the folder, and everything in the desktop data folder's folders: `bios`,
  `cheats`, `covers`, `dump`, `gameicons`, `gamesettings`, `inputprofiles`, `memcards`, `savestates`,
  `screenshots`, `shaders`, `textures`, `resources` and `videos`, and `emusync` (memory card sync, a later stage).
  Once the core has started, the folders its `settings.ini` names inside the data folder (a `[Folders]` entry, or
  `[MemoryCards] Directory`, such as `mycheats`) and the shared memory cards are mirrored too; the app asks the core
  for them, and pulls once more when that adds any. `cache/` (and the core's cache folder, wherever it is) and
  `goosestation.log` stay on the device. **Anything else in the data folder is left alone**: a `games/` folder, say,
  is never copied in, never written to and never deleted, and the mirror doesn't even list what's inside it. Folder
  names are matched as the desktop writes them, in lower case: a `Memcards/` folder counts as something else. Files
  outside the layout in the working copy (an earlier build copied in everything) are deleted from the working copy,
  never from the folder, once the core has said where its folders are, and their manifest entries go too; only those
  in step with the folder, though. One changed since the last run, or never mirrored (a card in a folder
  `settings.ini` no longer names, say), stays in the working copy, as it may hold a change the folder lacks. A
  leftover `.goosetmp` or `.goosebak` in a folder that is no longer part of the layout isn't cleaned up: it stays in
  the data folder under that name.
- **The folder is the source of truth.** The app pulls (folder to working copy) at start, before the core reads
  `settings.ini`, and whenever it comes back to the foreground. A file that is new or changed in the folder is copied
  in, even over a change of the working copy's, and a file deleted from the folder is deleted from the working copy.
  There is no conflict detection: when both sides changed, the folder wins.
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
  on this device until you pick it again." Each has **Pick again**. Picking the same folder restores it; another one replaces it
  (for the data folder, through the folder settings' choice above). The app never drops a folder on its own, nor
  falls back to another place.
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

`adb pull` may not manage this copy: the core writes `settings.ini` as a temporary file renamed into place, so it's
mode 0600, which the shell can't read, and `run-as` can't reach external storage. Copy the files on the device with
a file manager that can open `Android/data`, or from a computer over USB. Neither way was checked for this plan.

Plan 1's `settings.ini` has no `[Android] InstallID` (and `SettingsVersion = 1`, which is upgraded to 3), so the first
start with it adapts it as a file from another install (see "Changing settings"): the renderer goes back to
`Automatic`, the Port 1 bindings are reset (each connected pad adds its own again), and the game folders are replaced
by the ones setup picked. That happens once; other settings are kept.

### Changing settings

There's no settings screen yet, so settings are changed by editing `settings.ini` in the data folder, on the device
or from a computer. Then switch to the app, or reopen it: the pull on coming back copies the file in, and the core
reloads it, then scans the library again. While the app stays in front, the next push (within 2 s) also notices the
change and copies it in. Edit the file the app wrote, so it keeps its `[Android] InstallID` (see below). For
example, to pick the renderer and turn on the OSD's frame rates:

```bash
adb pull /sdcard/GooseStation/data/settings.ini /tmp/settings.ini
# In /tmp/settings.ini: under [GPU], Renderer = Vulkan; under [Display], ShowFPS, ShowSpeed and ShowCPU = true.
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
  defaults, then each connected pad adds its own; and any folder outside the data folder (an absolute path elsewhere,
  or a Windows path such as `C:\Users\…\memcards`) is set back to its default under the data folder. Everything else,
  such as the OSD or enhancement settings, is kept. The adapted file is saved with this install's ID, and goes back
  to the data folder with the next push.
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
  already there, and saves the file.

### Known limits

- **In a game, a controller alone can't get back to the library.** B goes to the game, as Circle. Use the Back gesture,
  or Home. A later plan adds the Mode button and Select+Start.
- **Leaving during a boot freezes the UI until the boot is done.** Destroying the surface waits for the CPU thread,
  which is still booting. On the emulator, Back during an Ace Combat 3 boot froze the UI for 0.7 to 1.2 s (43 and
  67 skipped frames), and the boot took 1.2 to 1.4 s. That's well under the 5 s ANR limit, but a slow first boot on a
  device could take longer.
- **Switching audio output mid-game is untested.** The AAudio stream is reopened when its device goes away, as when
  headphones are unplugged. The emulator can't check this; the device checklist below does.
- **A missing grant can't be made with `adb`.** Deleting a folder through the Storage Access Framework (the
  system's Files app, say) should take its grant with it (not checked here). Deleting or moving it from the shell
  (`rm`, `mv`) or by its file path leaves the grant, and looks like an unreachable folder instead. `GrantCheckTest` and
  `LibraryBannersTest` cover the grant check and its banners.
- **The data folder isn't shared live with desktop or another install.** Each install mirrors its own working copy,
  and the folder wins when both changed, with no merging, so two installs using one folder at once can lose each
  other's changes. Each install also adapts a `settings.ini` another one wrote (see "Changing settings"). Syncing
  memory cards between devices comes later, through emusync.
- **An M3U's discs are listed on their own as well.** A set's playlist and each of its discs get a tile each.
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
7. **Storage, setup and the library.** The emulator's checks above, with the BIOS and games on the SD card and the
   data folder on internal storage. Put a CUE/BIN game (a real one, so it boots to a picture) and an M3U set in the
   games folder first.
   - [ ] The whole setup with the built-in controller, run before step 1: the D-pad moves between the buttons, A
     presses them, B goes back a step, and the focused button has a visible ring. Touch only in the system picker,
     if it needs it. The
     library then lists every game.
   - [ ] The CUE/BIN game, the M3U set and the CHD each boot from the library.
   - [ ] Save on a memory card in a game. `/sdcard/GooseStation/data/memcards/` has the change within 5 s. A
     `.goosetmp` may show up between the steps of a write, but none is left behind.
   - [ ] Home mid-game, replace the card in the folder with `adb push`, then reopen. The game keeps its card. After
     quitting, the working copy has the folder's version
     (`adb shell run-as io.github.ilyalissoboi.goosestation md5sum "files/data/memcards/<the card>"`).
   - [ ] Delete a save state from the folder, then reopen: it's gone from the working copy
     (`adb shell run-as io.github.ilyalissoboi.goosestation ls files/data/savestates`).
   - [ ] Move the data folder away (`adb shell mv /sdcard/GooseStation/data /sdcard/GooseStation/data-away`), then
     save on a memory card. The unreachable banner shows, and the save stays in the working copy. Move it back and
     reopen the app: the save reaches the folder, and the banner goes.
   - [ ] Remove the SD card mid-game. Note what the game does, and what the library shows after it (a banner for
     the games folder, or none). Nothing is lost: the memory card in the data folder has the game's last save. Put
     the card back and reopen the app: the games are listed again.
   - [ ] Repeat with the data folder on the SD card. Make a `GooseStation/data` folder there, then **Folders** →
     **Data folder** → **Change**, pick it, and choose **Copy this device's data into it**; the app restarts on it.
     Then remove the card mid-game: after the game, the unreachable banner shows, the working copy keeps the save,
     and it reaches the folder once the card is back and the app reopened.
   - [ ] Kill the app during a large push. Make a 200 MB file (`adb shell "dd if=/dev/urandom
     of=/data/local/tmp/big.bin bs=1048576 count=200 && chmod 644 /data/local/tmp/big.bin"`), copy it in with
     `adb shell run-as io.github.ilyalissoboi.goosestation cp /data/local/tmp/big.bin files/data/screenshots/big.bin`,
     press Home,
     then `adb shell am kill io.github.ilyalissoboi.goosestation` once a `big.bin.goosetmp` shows in the folder.
     Start the app again: the folder has no `big.bin` that is short, no temporary document is left, and the next
     push completes.
   - [ ] Copy a desktop `settings.ini` (version 3, `Renderer = Software`, an `SDL-0` binding) into the data folder,
     then reopen. The renderer is back to Automatic, and the pad maps.
   - [ ] Boot time from the library: the time from `System: Boot Filename: …` to `AndroidHost: Game started: …` in
     the log, for Ace Combat 3 read in place from the SD card, three boots. For a baseline, also boot it from a games
     folder on internal storage (add one in **Folders**), or with Plan 1's build (#23, from `7e1d16150`, installed
     over this one with `adb install -r -d`) and the CHD pushed into
     `/sdcard/Android/data/io.github.ilyalissoboi.goosestation/files/games`; its log has the same two lines. Plan 1
     didn't record a boot time on the Thor; on the emulator it was 0.7 to 1.4 s. This shows whether reading in place
     is slower.
   - [ ] `adb logcat -b crash -d` is empty.
