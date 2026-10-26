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

| Tool or library | Version |
| --- | --- |
| Gradle (wrapper) | 9.8.2 |
| Android Gradle Plugin | 9.4.1 (runs on JDK 17) |
| Kotlin | 2.4.21 (AGP compiles Kotlin itself; the Kotlin plugin is declared only to pin this version) |
| Compose BOM | 2026.09.00 |
| `androidx.activity:activity-compose` | 1.13.0 |
| `androidx.core:core-ktx` | 1.19.1 |
| `compileSdk` / `targetSdk` / `minSdk` | 37 / 37 / 29 |
| NDK / CMake (`ndkVersion`, `externalNativeBuild.cmake.version`) | 30.0.16248370 / 4.1.2 |

## Installing the SDK

```bash
brew install --cask android-commandlinetools
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/30.0.16248370

yes | sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "ndk;30.0.16248370" \
    "cmake;4.1.2" "emulator" "system-images;android-37.0;google_apis;arm64-v8a"
```

The cask puts the SDK in `/opt/homebrew/share/android-commandlinetools`. Put the two `export` lines in your shell
profile: the dependency script needs `ANDROID_NDK_HOME`, and Gradle finds the SDK through `ANDROID_HOME`.

## The emulator

Create the AVD once:

```bash
echo no | avdmanager create avd -n goose-arm64 -k "system-images;android-37.0;google_apis;arm64-v8a" -d pixel_7
```

Start it, then check that `adb` sees it:

```bash
$ANDROID_HOME/emulator/emulator -avd goose-arm64 &
adb wait-for-device
adb devices
```

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
to its internal files on first run and whenever the version code changes.

## Running it

The app lists games from its external files folder, and keeps its settings, memory cards and BIOS images in `data`
next to it. Start the app once first, so it creates the folders itself (folders that `adb` creates belong to the shell,
and the app can't write to them), then push the files:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.ilyalissoboi.goosestation/.MainActivity
FILES=/sdcard/Android/data/io.github.ilyalissoboi.goosestation/files
adb push extras/padtest/padtest.psexe $FILES/games/
adb push <your BIOS image> $FILES/data/bios/
```

The list refreshes when the app comes back to the foreground. A, Enter or a tap starts the selected game; Back leaves
it.
