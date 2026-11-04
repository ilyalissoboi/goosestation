# emusync

Memory card sync (`src/core/memcard_sync.h`) uses [emusync](https://github.com/ilyalissoboi/emusync),
a library that syncs emulator saves through storage the user owns (S3-compatible services).

- `include/emusync.h` is emusync's C API, from emusync 0.3.0 (the `v0.3.0` release; SHA-256
  `d1e528f445331ebe229af43079e9e9072610554c117a78cd6a2bb46d15e2d278`). The core builds against
  it and loads the library at run time, so nothing links against emusync and a build or a system
  without the library runs without sync.
- The library comes from the same release: `emusync.props` (MSBuild) and
  `CMakeModules/EmuSync.cmake` (CMake) download the pinned package, check its SHA-256, and put
  `emusync.dll`, `libemusync.so` or `libemusync.dylib` next to the program.
- Android: `android/app/build.gradle.kts` takes the release's `emusync-0.3.0.aar` (SHA-256
  `964c65791c8f690ce82281dac244500f9a1d08a56b9ca4e9fc082c22756a8b81`), which puts `libemusync.so` in the APK; a
  copy placed here is used instead of downloading.

To update: change the version and the SHA-256s in the three build files (from the release's
`SHA256SUMS`), copy the release's `emusync.h` here, and update the version and hash above.

emusync is MIT OR Apache-2.0 licensed.
