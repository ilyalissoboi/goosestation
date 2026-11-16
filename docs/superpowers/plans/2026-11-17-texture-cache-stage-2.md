# Hardware texture cache, stage 2 (Android) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The texture cache from stage 1 on Android, on OpenGL ES and Vulkan: packs read in place from the data
folder through `content://` (never copied by the mirror), dumps written to the app's storage and mirrored out, and
memory limits suited to phones and handhelds.

**Architecture:** The core gets two texture roots: where packs are read (on Android the data folder's
`content://…/textures`, set by the host; on desktop `EmuFolders::Textures`) and where dumps are written (always the
local `EmuFolders::Textures`). The mirror leaves `textures/` alone except a game's `dumps/` and a `config.yaml` the
first dump wrote, which it sends out and never pulls back. Android's defaults for the cache's memory budgets are
lower. The renderer code is stage 1's; OpenGL ES and Android's Vulkan are checked by instrumented frame tests.

**Tech Stack:** C++20 core (CMake, NDK), Kotlin (the mirror, JNI), googletest (`common-tests`, also run on the
emulators by `android/scripts/run-native-tests.sh`), JUnit (JVM tests), instrumented tests (`GameCallsTest`,
`FrameReader`).

**Spec:** `docs/superpowers/specs/2026-11-16-texture-cache-design.md` ("Where packs live on Android", Goals →
Limits, Anti-goals, Constraints). Stage 1's rules: `docs/texture-replacement.md`.

## Global Constraints

- Clean room as in stage 1: nothing from DuckStation's code after `a5bf1ba5cee7`, nor the sources the design puts off
  limits.
- **Packs are read straight from the data folder**: the mirror copies nothing under `textures/` except
  `textures/*/dumps/` (sent only) and `textures/*/config.yaml` written by a first dump (sent once, never pulled back
  over a pack's). A legacy `vram-write-` pack in `textures/<serial>/` is read in place too. Copies an earlier build
  pulled into the working copy are strays and dropped.
- **Dumps go to the app's storage** (`EmuFolders::Textures/<serial>/dumps/`); legacy `vram-write-` dumps stay in
  `dump/textures/<serial>/`.
- **The search skips `dumps/`** in both roots.
- **A pack's `config.yaml`** in the data folder is what the core reads; only without one does it read the local one a
  first dump wrote.
- Android defaults (ours, written down): `MaxHashCacheVRAMUsageMB` 512, `MaxReplacementCacheVRAMUsage` 256; desktop
  keeps 2048 and 512. A value set in settings or `config.yaml` wins.
- Settings rows are stage 3; tests and the Thor check set the keys directly.
- Android: `docs/design-system.md` has nothing to bind here (no UI). Before the PR: the quick and full tiers (from a
  detached worktree with the emusync AAR copied in), and CI's Android line locally (`assembleDebug testDebugUnitTest
  ktlintCheck checkNoWindowDialogs`). The Thor checks (the font pack, memory, frame time) only with the user's
  go-ahead; the Thor has the release build (plain ID) — debug builds go on as `.debug`, never uninstalling the plain
  one, and nothing is put in its data folder but the pack for the check.
- Every rule found or decided goes into `docs/texture-replacement.md` with how it was established. Commit each task,
  messages ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; no PR until the user says so.

## Review Focus

1. **A large pack is never copied**: with a 2 GB pack in the data folder, the mirror's plan copies nothing under
   `textures/<serial>/` but `dumps/`. Pinned in Task 4 (`ATexturePackIsntMirrored`).
2. **A pack's `config.yaml` is never overwritten** by a first dump on the device, nor pulled back over by the
   mirror. Pinned in Tasks 2 (`AFirstDumpWithAPackConfigWritesNone`) and 4 (`ADumpsConfigIsSentOnceAndNeverPulled`).
3. **A data folder without permission or gone** (revoked grant, SD card out): the index is empty, the game plays, no
   crash. Pinned in Task 2 (`AnUnreadablePackFolderIndexesNothing`).
4. **Many files over `content://`** (a 51,000-file pack): indexing reads one listing per folder, not a stat per file.
   Pinned in Task 2 (`IndexingAContentTreeListsEachFolderOnce`, counting the fake provider's calls).
5. **Switching the data folder mid-session** reindexes from the new one on the next reload. Pinned in Task 3
   (`ChangingTheDataFolderMovesThePackFolder`).

---

### Task 1: Stage 1's fixture tests on the devices

`TextureNames.EveryFontPackNameRoundTrips` and `TextureCacheReference.EveryReferenceNameRoundTrips` read
`src/common-tests/data/` from the source tree, which the native runner doesn't push, so they fail on the emulators.

**Files:**
- Modify: `android/scripts/run-native-tests.sh` (push `src/common-tests/data` beside the binary, set
  `GOOSESTATION_TEST_DATA_DIR` for the run)
- Modify: `src/common-tests/texture_names_tests.cpp`, `src/common-tests/texture_cache_reference_tests.cpp` (the folder
  from the `GOOSESTATION_TEST_DATA_DIR` environment variable when set, else the compiled-in path; skip, saying why, if
  neither has the file)

TDD skipped (test plumbing); checked by running.

- [ ] **Step 1:** Run `android/scripts/run-native-tests.sh --gtest_filter='TextureNames*:TextureCacheReference*'` on
  emulator-5580; expect the two tests to FAIL (no fixture).
- [ ] **Step 2:** Make the change; rerun; expect PASS on the device, and `build/bin/common-tests` PASS on desktop.
- [ ] **Step 3: Commit.**

### Task 2: Two texture roots in the core

**Files:**
- Modify: `src/core/texture_replacements.h`, `src/core/texture_replacements.cpp`, `src/core/gpu_texture_cache.cpp`
  (first-dump `config.yaml` and `replacements/`)
- Create: `src/common-tests/fake_content_provider.h` (the `FakeProvider` from `content_uri_tests.cpp`, moved, with call
  counts), used by `content_uri_tests.cpp` and the new tests
- Test: `src/common-tests/texture_replacements_tests.cpp`

**Interfaces:**
- Produces (namespace `TextureReplacements`):
  - `void SetPackFolder(std::string folder);` — where packs are read (`<data folder URI>/textures` on Android); empty
    means `EmuFolders::Textures`. Takes effect on the next `Reload()`.
  - `std::string GetGameDirectory();` — now the game's folder under the pack folder (read);
  - `std::string GetLocalGameDirectory();` — `EmuFolders::Textures/<serial>` (write: dumps, first-dump config);
  - `std::string GetTextureDumpDirectory();` — `GetLocalGameDirectory()/dumps`;
  - `LoadGameConfig(pack_dir, local_dir, base)` — the pack's `config.yaml`/`config.yml`, else the local one, else base.
- `GPUTextureCache::SetDumping(dump_dir, config_dir, …)`: `config_dir` is where a first dump may write `config.yaml`
  and `replacements/` — the local game folder — and it writes none if the pack folder has a config.

- [ ] **Step 1: Write the failing tests:** `APackIsIndexedThroughContent` (a FakeProvider tree
  `content://fake/tree/data/textures/SLUS-00000/{replacements/<name>.png, dumps/<name>.png, config.yaml}`: the
  replacement is indexed, the dump isn't, the config's keys apply); `DumpsGoToTheLocalFolder`
  (`GetTextureDumpDirectory()` is under `EmuFolders::Textures` while the pack folder is `content://`);
  `AFirstDumpWithAPackConfigWritesNone`; `ALocalConfigIsReadOnlyWithoutThePacksOwn`; `AnUnreadablePackFolderIndexesNothing`
  (the provider refuses the tree: empty index, no crash); `IndexingAContentTreeListsEachFolderOnce`.
- [ ] **Step 2: Run** `--gtest_filter='TextureReplacementIndex*:ContentURI*'`; expect build failures.
- [ ] **Step 3: Implement.** `Build` already walks with `FindFiles`, which lists content trees; the dumps folder is
  skipped by its path under the pack folder. Image loading opens `content://` read-only through `RGBA8Image`.
- [ ] **Step 4: Run** the filter, then all of `common-tests`, on desktop and with `run-native-tests.sh`; expect PASS.
- [ ] **Step 5: Write down** the two roots and the config rule; **commit.**

### Task 3: The data folder's URI reaches the core

**Files:**
- Modify: `android/app/src/cpp/jni_settings.cpp` (`setDataFolder(String)`), `android/app/src/cpp/android_host.{h,cpp}`
  (`AndroidHost::SetDataFolder`: `TextureReplacements::SetPackFolder(Path::Combine(uri, "textures"))`, then a reload
  if a game runs), `android/app/src/main/java/.../core/NativeLibrary.kt`, `.../app/FolderSettings.kt` (call it when
  the data folder is used or changed, as `setGameFolders` is)
- Test: `android/app/src/test/java/.../app/FolderSettingsTest.kt` (or the existing test for `use`), and a native test
  `AndroidHost`-free: `TextureReplacementIndex.ChangingTheDataFolderMovesThePackFolder` in `common-tests`

- [ ] **Step 1: Write the failing tests:** the JVM test that `use(saved)` passes the data folder to the native side
  (through the fake `NativeLibrary` seam the existing tests use); the native test that `SetPackFolder` then `Reload`
  indexes the new folder.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*FolderSettings*'` and the native filter; expect FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** both; expect PASS. **Commit.**

### Task 4: The mirror leaves packs alone

**Files:**
- Modify: `android/app/src/main/java/.../mirror/DataLayout.kt` (`textures/` out of `FOLDERS`' plain rule:
  `textures/*/dumps/**` mirrored sent-only; `textures/*/config.yaml` sent once; everything else under `textures/` a
  stray), `.../mirror/MirrorPlanner.kt` (a "sent if absent" kind: pushed when the data folder has no such file, never
  pulled, never overwriting)
- Test: `android/app/src/test/java/.../mirror/DataLayoutTest.kt`, `MirrorPlannerTest.kt`

- [ ] **Step 1: Write the failing tests:** `ATexturePackIsntMirrored` (a data folder listing with
  `textures/SLUS-01040/replacements/*.png` × many, `textures/SLUS-01040/vram-write-….png`: no copy either way);
  `DumpsAreSentAndNeverPulled`; `ADumpsConfigIsSentOnceAndNeverPulled` (local `config.yaml`, none in the folder:
  copied out; the folder's own changed later: never pulled, never overwritten); `PackCopiesFromEarlierBuildsAreStrays`;
  the legacy `dump/textures/` stays mirrored as before.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*DataLayout*' --tests '*MirrorPlanner*'`; expect FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run**; expect PASS. **Write down** the mirror rules; **commit.**

### Task 5: Android's memory defaults

**Files:**
- Modify: `src/core/texture_pack_config.h` (defaults under `#ifdef __ANDROID__`), `docs/texture-replacement.md`
- Test: `src/common-tests/texture_pack_config_tests.cpp` (`TheDefaultsAreTheReleasesDefaults` becomes per platform:
  desktop 2048/512, Android 512/256)

- [ ] **Step 1:** Change the test's expected values for Android; run on the emulator; expect FAIL.
- [ ] **Step 2:** Implement; run on desktop and the emulator; expect PASS. **Commit.**

### Task 6: OpenGL ES and Vulkan on the emulators

**Files:**
- Modify: `android/app/src/androidTest/java/.../core/GameCallsTest.kt` (or a new `TextureCacheTest.kt` beside it,
  reusing `FrameReader` and the `GOOSE_TEST_DATA` game setup), `android/scripts/run-instrumented-tests.sh` (list
  `games/Vagrant Story (USA).chd` and `savestates/SLUS-01040_2.sav` as optional data; the tests skip without them)
- Outside the repo: the user's Vagrant Story image and save states in `GOOSE_TEST_DATA` (as on the Mac), the font
  pack's `textures/SLUS-01040/` copied into the test's data folder

TDD: each test is first run with the cache off, to see its comparison fail, before it's run as written.

- [ ] **Step 1: Write the tests:** `withoutAPackFramesAreUnchanged` (state 2, cache on, replacements on, an empty
  pack folder: a frame equal to the cache off); `theFontPackDrawsInTheMenu` (state 2 with the font pack in the data
  folder: the menu's region differs from the cache off, and the log lists placed pack names); `aPackIsntCopied` (after
  the run, the working copy has no `textures/SLUS-01040/replacements/`). Each under OpenGL ES and Vulkan, as the slang
  tests are (`@BootsGames`, the renderer as a parameter).
- [ ] **Step 2: Run** on emulator-5580 (Vulkan) and emulator-5582 (OpenGL ES via ANGLE/SwiftShader); expect PASS.
  Any renderer difference is debugged (superpowers:systematic-debugging) before going on.
- [ ] **Step 3: Write down** the results; **commit.**

### Task 7: Stage 1's loose ends

**Files:**
- Modify: `src/core/texture_replacements.cpp` (duplicates found by full name, not neighbours of one hash),
  `src/common-tests/gpu_texture_cache_tests.cpp` (`AFillThatWrapsLandsOnBothEdges`, `AMaskedCopySkipsMaskedPixels`),
  `src/common-tests/texture_replacements_tests.cpp` (`DuplicatesAreFoundWhateverLiesBetween`)

- [ ] **Step 1:** Write the three tests; run; expect the duplicates test to FAIL (the two cache tests may pass — they
  pin behaviour stage 1 had untested).
- [ ] **Step 2:** Fix the duplicate removal; run all; expect PASS. **Commit.**

### Task 8: The Thor, docs, finishing

- [ ] **Step 1 (with the user's go-ahead):** a debug build (`.debug`, beside the release, which stays) on the Thor, the font pack in its
  data folder, state 2: the menu's glyphs (the user's eyes, or a screenshot from the app's own screenshot), memory (the
  app's PSS before and after), and frame time with the cache off, on without a pack, and with the pack (the app's
  frame time overlay).
- [ ] **Step 2:** `docs/texture-replacement.md`: Android's rules (roots, mirror, config, limits) and the GLES/Vulkan
  and Thor results; `docs/roadmap.md` item 20: stage 2.
- [ ] **Step 3:** The quick and full tiers; CI's Android line; `common-tests` on desktop; all PASS. Commit; push.
- [ ] **Step 4:** superpowers:finishing-a-development-branch; a PR only when the user says so.
