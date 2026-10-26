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

The Gradle, Android Gradle Plugin, Kotlin and Compose versions are pinned in the Android project itself.

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
