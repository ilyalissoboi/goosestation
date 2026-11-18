# emusync

Memory card sync (`src/core/memcard_sync.h`) uses [emusync](https://github.com/ilyalissoboi/emusync),
a library that syncs emulator saves through storage the user owns (S3-compatible services).

- `include/emusync.h` is emusync's C API, from emusync 0.3.1 (the `v0.3.1` release; SHA-256
  `f9373581266a8de0d75241e3af403964cb882e1b05ddea3a9638c242dbb5ab59`). The core builds against
  it and loads the library at run time, so nothing links against emusync and a build or a system
  without the library runs without sync.
- The library comes from the same release: `emusync.props` (MSBuild: Windows x64 and ARM64) and
  `CMakeModules/EmuSync.cmake` (CMake: Linux x86_64 and aarch64, macOS) download the pinned
  package, check its SHA-256, and put `emusync.dll`, `libemusync.so` or `libemusync.dylib` next to
  the program. The Flatpak manifest fetches the Linux package for its architecture.
- Android: `android/app/build.gradle.kts` takes the release's `emusync-0.3.1.aar` (SHA-256
  `f953deeae62aa778604c7b7bf9635421d9f276982b46f44182e58bc08eaf90f4`, pinned in
  `android/gradle/libs.versions.toml`), which puts `libemusync.so` in the APK; a
  copy placed here is used instead of downloading. The app's manifest removes the AAR's
  `DropboxRedirectActivity`, as GooseStation has no Dropbox sign-in (`EmusyncManifestTest`).

To update: change the version and the SHA-256s in `emusync.props`, `CMakeModules/EmuSync.cmake`,
the Flatpak manifest (`scripts/flatpak/`) and `android/gradle/libs.versions.toml` (from the
release's `SHA256SUMS`), copy the release's `emusync.h` here, and update the version and hashes
above and in `docs/android.md`.

emusync is MIT OR Apache-2.0 licensed.
