# emusync

Memory card sync (`src/core/memcard_sync.h`) uses [emusync](https://github.com/ilyalissoboi/emusync),
a library that syncs emulator saves through storage the user owns (S3-compatible services).

- `include/emusync.h` is emusync's C API, from emusync 0.3.0 (the `v0.3.0` release; SHA-256
  `0aa43a645c789472d7f2d255892b6e616af1a50ab871841e299a82061c11d249`). The core builds against
  it and loads the library at run time, so nothing links against emusync and a build or a system
  without the library runs without sync.
- The library comes from the same release: `emusync.props` (MSBuild) and
  `CMakeModules/EmuSync.cmake` (CMake) download the pinned package, check its SHA-256, and put
  `emusync.dll`, `libemusync.so` or `libemusync.dylib` next to the program.

To update: change the version and the SHA-256s in both build files (from the release's
`SHA256SUMS`), copy the release's `emusync.h` here, and update the version and hash above.

emusync is MIT OR Apache-2.0 licensed.
