# Android Stage 1, Plan 2: storage, setup and the library — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the user picks a BIOS folder, one or more games folders and a data folder with the system folder picker,
then browses their games in a cover grid (or a list) and launches them from it. Games are read in place, and
everything the core writes reaches the data folder within seconds.

**Architecture:** games are named to the core as **tree URI + relative path**
(`content://<authority>/tree/<treeId>/<dir>/<name>`). Ordinary path string operations, and so every disc reader's
sibling lookup, work on that unchanged. `FileSystem` sends `content://` paths to a registered `ContentProvider`. On
Android, that provider calls Kotlin over JNI. Kotlin resolves names to document IDs by listing each directory
(cached), and returns file descriptors. The core's own `GameList` builds the library through that provider. The
data folder is mirrored by Kotlin into a working copy in `filesDir/data` (the core's fixed `DataRoot`). It is
pulled at start and on resume, with the folder winning conflicts. Changes are pushed by a scan of the working copy
every 2 s, and when the app goes to the background or a game stops. BIOS images are copied to `filesDir/bios`,
outside the mirror.

**Tech Stack:** C++20, CMake, Android NDK, JNI, Kotlin, Kotlin coroutines, Jetpack Compose, Material 3, Storage
Access Framework (`DocumentsContract`), GoogleTest, JUnit 4, AndroidX Test (with the orchestrator), Compose UI tests.

**Spec:** [docs/superpowers/specs/2026-10-25-android-frontend-design.md](../specs/2026-10-25-android-frontend-design.md):
*Storage* in full, and Goals 1–3. It follows
[Plan 1](2026-10-25-android-stage1-plan1-emulator.md), merged as #23. Plan 3 (settings, remapping, the Back prompt)
follows this one.

**Choices made with the user on 2026-10-26** (where the design left them open):
- **Paths:** tree URI + relative path, as above. This was chosen over the provider's own document URIs, which
  would need `Path::` to decode document IDs, and over short virtual mounts, which would need a registry.
- **Push detection:** a stat scan of the working copy every 2 s in the foreground, plus at background and at game
  stop. This was chosen over hooks in the core's writes, and over `FileObserver`.
- **Library:** the core's `GameList`, through `FindFiles` on `content://`. Its cache (`cache/gamelist.cache`) is
  keyed by path and modified time, not by document ID. Kotlin doesn't keep a second cache.
- **Working copy:** `filesDir/data`, not `getExternalFilesDir`.

**Calls made while writing this plan** *(writer's call; revisit in review)*:
- **Foreign settings.** A `settings.ini` is from somewhere else when its `[Android] InstallID` isn't this
  install's. The ID is a random UUID kept in the app's preferences, not mirrored. That covers desktop files (which
  have no ID), other Android installs and reinstalls. Such a file has its device-specific keys rewritten (Task 3).
- **One settings version.** Desktop's `SETTINGS_VERSION` (3) moves into the core and both frontends use it.
  Android upgrades its Plan 1 files (version 1, same keys) to 3 in place, without resetting them.
- **Grid or list** is desktop's `[UI] GameListGridView`, but on Android it defaults to `true` (the grid).
- **Disc sets** aren't merged in Stage 1: each disc is its own tile. `DiscSet` entries are left out.
- **BIOS:** only files the core recognises by hash (`ImageInfo` not null) are copied. Setup needs at least one.

## Global Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app. Dolphin and ARMSX2
  are reference for approach only; copy no code.
- New files: `// SPDX-FileCopyrightText: 2026 GooseStation contributors` and `// SPDX-License-Identifier: GPL-3.0`
  (`#` comments for scripts and Gradle files, `<!-- -->` for XML).
- Android: `minSdk 29`, `arm64-v8a` only, package `io.github.ilyalissoboi.goosestation`. The manifest asks for **no**
  storage permission (no `READ_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE`).
- Grants: `takePersistableUriPermission` with read **and** write for the data folder, and read only for the BIOS and
  games folders. Saved URIs are compared **exactly** (string equality) with
  `ContentResolver.persistedUriPermissions`, at start and on every resume. A missing grant always leads to an
  in-screen prompt to pick the folder again. Never drop a folder silently, and never fall back to another location.
- Nothing is ever written through `content://` by the core. Only Kotlin's mirror writes to the data folder.
- No `AlertDialog`, Compose `Dialog`, `DropdownMenu` or `ModalBottomSheet`. Prompts and choices are drawn inside the
  screen. Every new screen works with a D-pad alone: a visible focus ring, the right element focused when it opens,
  A or Enter activates, and B or Back goes back. The system folder picker is the one exception, and the screen that
  opens it says touch may be needed there.
- SAF queries, file copies, scans and JNI calls that do I/O never run on the main thread.
- The JNI bridge caches class, method and field IDs at `JNI_OnLoad`, deletes every local reference it makes in a
  loop, attaches native threads that aren't attached (and detaches them again), and turns every Java exception into
  an error return, never a crash.
- New dependencies are the latest stable versions on the day the task runs, pinned in `gradle/libs.versions.toml`
  and recorded in `docs/android.md`.
- Desktop must stay green: after every task that touches `src/`, `cmake --build build` succeeds with no new warnings
  in changed files, and `build/bin/common-tests` passes. `android/scripts/run-native-tests.sh` passes on
  `goose-arm64`.
- Core changes sit behind `__ANDROID__` or shared abstractions. On other platforms nothing changes for paths that
  don't start with `content://`.
- Devices: the arm64 emulator (`goose-arm64`, `adb -s emulator-5554`) for every task. Use the AYN Thor
  (`adb -s 64dc3c35`) only after the user says go.
- Commits: the user reviews every commit message in chat before it is committed. Work on a branch
  `claude/android-storage-library`. Propose one commit per task at the checkpoints after Tasks 2, 5, 8 and 12. Use
  the log's style for messages (`Common: …`, `Android: …`, `Core: …`, `Docs: …`). Push as `ilyalissoboi`, as in the
  user's memory note.

## Review Focus

1. **Names that look like URI syntax.** A game called `Final Fantasy #1 (100%).cue` holds `#` and `%`, and a folder
   is called `Disc?`. The relative part of a content path is raw text, never URL-decoded or parsed as a URI, so the
   game opens and its sibling BIN resolves. (Task 1 Path test, Task 2 `ContentPathTest` and bridge test.)
2. **The CUE's case doesn't match the file's.** `FILE "TRACK 01.BIN"` sits next to `Track 01.bin`. An exact match
   wins, otherwise a single case-insensitive match is used, as the user's own storage would. (Task 2.)
3. **The folder changes while a push is pending.** The user copies a memory card into the data folder from a PC
   while the app has its own change to that card waiting. The folder's version wins, and the app's version is not
   written over it. (Task 6, `folderChangedSincePushWins`.)
4. **The data folder goes away mid-session.** The SD card is removed, or the grant revoked, during a game. Pushes
   stop without a half-written file, the library shows a warning, the working copy keeps every change, and they
   are pushed once the folder is back. (Task 7 test, Task 12 check.)
5. **A crash between the steps of a replace.** Leftover `.goosetmp` and `.goosebak` documents are cleaned up. A
   file whose only copy is the `.goosebak` gets it back. The next pull never reads the missing file as a deletion
   and deletes the working copy's save. (Task 7, `recoversInterruptedReplace`.)

---

### Task 1: `content://` in the file layer, and the atomic-file fd leak

**Files:**
- Modify: `src/common/file_system.h`, `src/common/file_system.cpp`
- Create: `src/common-tests/content_uri_tests.cpp` (add it to `src/common-tests/CMakeLists.txt`, `.vcxproj` and
  `.vcxproj.filters`)
- Modify: `src/common-tests/file_system_tests.cpp`

**Interfaces:**
- Produces, in `file_system.h`, namespace `FileSystem`:
  ```cpp
  /// True for "content://" paths, which a ContentProvider serves.
  bool IsContentURI(std::string_view path);

  /// Serves content:// paths, read only. Registered once; nullptr removes it.
  class ContentProvider
  {
  public:
    virtual ~ContentProvider();
    /// A file descriptor the caller owns, or -1 with the error set (errno-style: ENOENT, EACCES, EIO).
    virtual int OpenForReading(std::string_view uri, Error* error) = 0;
    virtual bool Stat(std::string_view uri, FILESYSTEM_STAT_DATA* sd) = 0;
    /// One level: FileName is the bare name. False if the URI isn't a readable directory.
    virtual bool ListDirectory(std::string_view uri, FindResultsArray* entries, Error* error) = 0;
  };
  void SetContentProvider(ContentProvider* provider);
  ```
- Behaviour for a content URI (and no change for any other path):
  - `OpenCFile`/`OpenManagedCFile`/`OpenSharedCFile` with mode `r` or `rb` → `fdopen` of `OpenForReading`. Any
    other mode fails with `EROFS`. A descriptor that can't seek (`lseek` fails with `ESPIPE`) is closed and fails
    with "… can't be read in place: the storage gives no seekable file".
  - `StatFile` (both path overloads), `FileExists`, `DirectoryExists`, `IsRealDirectory` and `GetPathFileSize` →
    `Stat`.
  - `FindFiles` → `ListDirectory`, honouring `RECURSIVE`, `RELATIVE_PATHS`, `FILES`, `FOLDERS`, `HIDDEN_FILES`
    (names starting with `.`), `KEEP_ARRAY`, `SORT_BY_NAME` and the wildcard pattern, as the POSIX version does.
    Full paths are `Path::Combine(dir, name)`.
  - Every function that writes (`CreateDirectory`, `EnsureDirectoryExists`, `DeleteFile`, `DeleteDirectory`,
    `RecursiveDeleteDirectory`, `RenamePath`, `CopyFilePath`, `OpenExistingOrCreateCFile`,
    `CreateAtomicRenamedFile`) fails with `EROFS` and touches nothing. Nothing may create a local `content:`
    directory.
  - With no provider registered, a content URI behaves as a missing file (`ENOENT`).
  - `Path::IsAbsolute` is true for content URIs. `Path::Canonicalize` and `Path::RealPath` keep the
    `content://<authority>/tree/<id>` root as it is, and canonicalise only what follows. `..` stops at the tree
    root.
- `CreateAtomicRenamedFile` on POSIX opens the temporary file with `fdopen` on `mkstemp`'s descriptor (closing it if
  `fdopen` fails) instead of opening the name a second time.

- [ ] **Step 1: Write the failing tests** in `content_uri_tests.cpp`, suite `ContentURI`, with a `FakeProvider`
  that maps `content://fake/tree/root` onto a temporary directory (opening the real file with `open(O_RDONLY)`).
  `R` below is `content://fake/tree/root`.
  - `IsContentURI("content://a/tree/b")` is true; `IsContentURI("/content/x")` and `IsContentURI("")` are false.
  - `Path::IsAbsolute(R + "/x.cue")` is true. `Path::Canonicalize(R + "/a/./b/../c.cue") == R + "/a/c.cue"`.
    `Path::Canonicalize(R + "/../x") == R + "/x"`.
  - Sibling resolution, exactly as each reader does it, on files created in the temporary directory:
    - CUE: `Path::BuildRelativePath(R + "/FF7/disc 1.cue", "disc 1 (Track 1).bin")` opens with
      `OpenCFile(…, "rb")`, and reads the bytes written.
    - M3U: `BuildRelativePath(R + "/set/game.m3u", "game (Disc 2).cue")` exists.
    - SBI and MDF: `FileExists(Path::ReplaceExtension(R + "/g/x.img", "sbi"))`.
    - PPF: `FileExists(BuildRelativePath(img, ReplaceExtension(Path::GetFileName(img), "ppf")))`.
    - CHD parent: `FindFiles(std::string(Path::GetDirectory(R + "/c/child.chd")).c_str(), "*.*", FILESYSTEM_FIND_FILES,
      &r)` returns both `R + "/c/child.chd"` and `R + "/c/parent.chd"` with their sizes.
  - `Final Fantasy #1 (100%).cue` in a folder `Disc?`: it opens, and its BIN resolves (Review Focus 1).
  - `FindFiles(R, "*", RECURSIVE | FILES | RELATIVE_PATHS)` returns `sub/a.bin` and `b.cue`, and skips `.hidden`
    unless `HIDDEN_FILES` is given.
  - `OpenCFile(R + "/x.bin", "wb")`, `CreateDirectory(R + "/new", false)` and `DeleteFile(R + "/x.bin")` fail with
    `EROFS`. The file is unchanged, and no `content:` directory exists in the working directory.
  - A provider returning a pipe's read end makes `OpenCFile` fail, with "can't be read in place" in the error.
  - `SetContentProvider(nullptr)`: `FileExists(R + "/x.bin")` is false, and `OpenCFile` fails with `ENOENT`.
  - In `file_system_tests.cpp`: `FileSystem.CreateAtomicRenamedFileClosesTempDescriptor` counts the entries in
    `/dev/fd` (`/proc/self/fd` on Android), writes 50 files with `WriteAtomicRenamedFile`, and the count is
    unchanged.
- [ ] **Step 2: Run them to see them fail.** `cmake --build build --target common-tests` → build error (RED).
- [ ] **Step 3: Implement**, as listed under Interfaces.
- [ ] **Step 4: Run them to see them pass.** `build/bin/common-tests --gtest_filter='ContentURI.*:FileSystem.*:Path.*'`
  → PASS. Then `android/scripts/run-native-tests.sh` → all pass on `goose-arm64`.
- [ ] **Step 5: Desktop still green** (Global Constraints).

### Task 2: the JNI content bridge, and test infrastructure

**Files:**
- Create: `android/app/src/cpp/jni_util.h/.cpp` (`ScopedJNIEnv`, which gets or attaches the calling thread and
  detaches on destruction if it attached it. Move `FromJString`/`ToJString` and the attach logic in
  `CallCallback` from `jni_bridge.cpp` here.)
- Create: `android/app/src/cpp/content_bridge.cpp/.h` (`AndroidContentProvider : FileSystem::ContentProvider`,
  registered in `JNI_OnLoad`)
- Modify: `android/app/src/cpp/jni_bridge.cpp`, `android/app/src/cpp/CMakeLists.txt`
- Create (Kotlin, package `…goosestation.storage`): `ContentPath.kt`, `DocumentResolver.kt`, `ContentBridge.kt`,
  `NativeDocument.kt`
- Create: `android/app/src/debug/AndroidManifest.xml` and `android/app/src/debug/java/…/storage/TestDocumentsProvider.kt`
  (a `DocumentsProvider` over a directory in `filesDir/test-documents`, authority
  `io.github.ilyalissoboi.goosestation.testdocuments`, declared with `android:enabled="false"`. Tests enable it
  with `PackageManager.setComponentEnabledSetting`. It lives in the app's debug build so it shares the app's UID,
  which gives the app access without a grant.)
- Modify: `android/app/build.gradle.kts`, `android/gradle/libs.versions.toml`: JUnit 4,
  `kotlinx-coroutines-android` and `-test`, `androidx.test:runner`, `androidx.test.ext:junit`,
  `androidx.test:orchestrator` (`testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"`, so each instrumented test
  gets a fresh process and its own one-shot `NativeLibrary.initialize`), and Compose `ui-test-junit4` with
  `ui-test-manifest` (debug).
- Test: `android/app/src/test/java/…/storage/ContentPathTest.kt`, `DocumentResolverTest.kt`;
  `android/app/src/androidTest/java/…/storage/ContentBridgeTest.kt`, `TestTrees.kt` (helpers that build a tree in
  `TestDocumentsProvider` and return its tree URI)

**Interfaces:**
- Consumes: `FileSystem::ContentProvider` (Task 1).
- Produces (Kotlin):
  - `data class ContentPath(val treeUri: String, val segments: List<String>)`, with
    `companion fun parse(path: String): ContentPath?` and `override fun toString()`.
    - `parse` accepts `content://<authority>/tree/<one encoded segment>` followed by `/`-separated raw names. It
      drops empty and `.` segments, and returns null for `..`, for no `/tree/`, or for an empty tree segment.
    - The raw names are never URL-decoded, and the full string is never given to `Uri.parse`. Only `treeUri` is.
  - `data class NativeDocument(val name: String, val documentId: String, val size: Long, val lastModifiedMs: Long,
    val isDirectory: Boolean)`
  - `interface DocumentLister { fun listChildren(treeUri: String, parentDocumentId: String): List<NativeDocument>?;
    fun rootDocumentId(treeUri: String): String }`, implemented for real by `SafDocumentLister(ContentResolver)`.
    That implementation makes one `query` on `buildChildDocumentsUriUsingTree`, with `COLUMN_DOCUMENT_ID`,
    `COLUMN_DISPLAY_NAME`, `COLUMN_MIME_TYPE`, `COLUMN_SIZE` and `COLUMN_LAST_MODIFIED`. A null `LAST_MODIFIED`
    reads as 0.
  - `class DocumentResolver(lister: DocumentLister, clock: () -> Long)`:
    - `resolve(path: ContentPath): NativeDocument?`: the tree root resolves to a directory document.
    - `list(path: ContentPath): List<NativeDocument>?`
    - `invalidate()`

    Listings are cached per `(treeUri, documentId)` for 5 s, with at most 64 entries, LRU. A name matches exactly
    first; failing that, it matches case-insensitively (`Locale.ROOT`), but only if exactly one name does.
  - `object ContentBridge`, `@JvmStatic`, called only from C++. `init(context)` must be called once at startup.
    - `openForReading(path: String): Int`: `openFileDescriptor(uri, "r").detachFd()`, or a negative errno: `-2`
      for not found, `-13` for no grant or a `SecurityException`, `-5` for anything else.
    - `stat(path: String): LongArray?`: `[size, lastModifiedMs, 1 if directory else 0]`, or null.
    - `listDirectory(path: String): Array<NativeDocument>?`
- Produces (C++): `AndroidContentProvider` converts milliseconds to `time_t` seconds and `isDirectory` to
  `FILESYSTEM_FILE_ATTRIBUTE_DIRECTORY`. It maps a negative return to `Error::SetErrno`.

- [ ] **Step 1: Write the failing JVM tests.**
  - `ContentPathTest`:
    - `parse("content://a.b/tree/primary%3APS1/FF7/x y.cue")` gives `treeUri ==
      "content://a.b/tree/primary%3APS1"` and `segments == ["FF7", "x y.cue"]`, and `toString()` round-trips it.
    - `#`, `%`, `?` stay raw in segments.
    - Null for `content://a/document/x`, `content://a/tree/`, `content://a/tree/x/../y` and `/sdcard/x`.
  - `DocumentResolverTest`, with a fake `DocumentLister` that counts calls:
    - Resolving 20 siblings in one folder costs one `listChildren` for that folder.
    - After 5 s on the fake clock it lists again.
    - `TRACK 01.BIN` finds `Track 01.bin`.
    - Two case-variant matches with no exact one gives null.
    - `invalidate()` forces a new listing.
- [ ] **Step 2: Write the failing instrumented test** `ContentBridgeTest`, on a `TestDocumentsProvider` tree with
  `FF7/disc 1.cue`, `FF7/disc 1 (Track 1).bin` (4096 bytes) and `Disc?/Final Fantasy #1 (100%).cue`:
  - `openForReading` returns an fd whose `Os.fstat` size is 4096.
  - `stat` of `FF7` has the directory flag.
  - `listDirectory` of the root has `FF7` and `Disc?`.
  - A missing name gives `-2`.
  - A tree URI from an authority with no provider gives a negative value, without throwing.
- [ ] **Step 3: Run to see them fail.** `cd android && ./gradlew testDebugUnitTest` → compile errors (RED).
  `./gradlew connectedDebugAndroidTest` (with `goose-arm64` running) → compile errors (RED).
- [ ] **Step 4: Implement** the Kotlin classes, `TestDocumentsProvider`, `jni_util` and `content_bridge`. Call
  `ContentBridge.init` before `NativeLibrary` is first used.
- [ ] **Step 5: Run to see them pass.** Both Gradle commands → PASS. `cmake --build build-android --target
  goosestation` links, and `android/scripts/check-undefined.sh` exits 0.
- [ ] **Step 6: Checkpoint.** Propose the commits for Tasks 1 and 2 in chat, and commit after approval.

### Task 3: settings version, foreign settings, BIOS folder and game folders in the host

**Files:**
- Modify: `src/core/settings.h`: `static constexpr u32 SETTINGS_VERSION = 3;` in `struct Settings`.
- Modify: `src/duckstation-qt/qthost.cpp`: use `Settings::SETTINGS_VERSION`, and drop its own constant (no
  behaviour change).
- Modify: `src/core/game_list.cpp` (~787–789): remove the upstream `#ifdef __ANDROID__` that adds
  `DataRoot/games` to the scanned folders. Games come only from the picked folders.
- Modify: `android/app/src/cpp/android_host.cpp/.h`, `android/app/src/cpp/jni_bridge.cpp`
- Create: `android/app/src/cpp/foreign_settings.cpp/.h`
- Modify: `…/core/NativeLibrary.kt`, `…/MainActivity.kt` (the new `initialize` arguments)
- Test: `android/app/src/androidTest/java/…/core/SettingsSetupTest.kt`

**Interfaces:**
- Produces (Kotlin `NativeLibrary`), each callable from any thread:
  - `external fun initialize(dataRoot: String, resourcesRoot: String, biosDir: String, installId: String): Boolean`
    replaces the two-argument one.
  - `external fun setGameFolders(treeUris: Array<String>)` sets `[GameList] RecursivePaths` to exactly these
    URIs, clears `[GameList] Paths`, and saves.
  - `external fun reloadSettings()` runs on the CPU thread, blocking. It reloads `settings.ini` from disk (after a
    pull changed it), applies the foreign-settings rule again, then runs `EmuFolders::Update()` and
    `System::ApplySettings(false)`.
  - `external fun getBoolSetting(section: String, key: String, default: Boolean): Boolean` and
    `external fun setBoolSetting(section: String, key: String, value: Boolean)` (base layer, saved).
- Produces (C++): `void AndroidHost::AdaptForeignSettings(SettingsInterface& si, std::string_view install_id)`
  (`foreign_settings.h`). If `[Android] InstallID` isn't `install_id`, it does the following, then sets
  `InstallID` to `install_id`:
  - deletes `[GPU] Renderer`;
  - clears `[GameList] Paths` and `RecursivePaths`;
  - deletes `[BIOS] PathNTSCU`, `PathNTSCJ` and `PathPAL`, and sets `[BIOS] SearchDirectory = bios`;
  - runs `InputManager::SetDefaultSourceConfig`, `Settings::SetDefaultControllerConfig` and
    `Settings::SetDefaultHotkeyConfig`;
  - resets to its default every `[Folders]` value and `[MemoryCards] Directory` that is absolute and not under
    `DataRoot`, or that is relative and leaves it through `..`;
  - deletes `[MemoryCards] Card1Path`/`Card2Path` when they're absolute and outside `DataRoot`.

  It returns whether it changed anything.

Behaviour:
- `InitializeConfig` accepts `SettingsVersion` 1 (Plan 1's Android files) by rewriting it to 3, and keeps
  everything else. Any other mismatch resets to defaults, as now. Then it calls `AdaptForeignSettings` and saves
  if anything changed.
- After every `EmuFolders::LoadConfig` (at start, and in `reloadSettings`), `EmuFolders::Bios` is set to `biosDir`.
  It is never saved: `[BIOS] SearchDirectory` stays as the file has it.
- `GameListGridView` isn't written by default. The library reads it with default `true` (Task 10).

- [ ] **Step 1: Write the failing instrumented tests** (`SettingsSetupTest`, each test in its own process through
  the orchestrator). Each one writes a `settings.ini` into a fresh `dataRoot` under the test's `cacheDir`, calls
  `initialize` (`biosDir` a sibling folder, `installId = "test-install"`), then reads `settings.ini` back with
  `INISettingsInterface`-compatible parsing (plain text is enough).
  - `desktopFileIsAdapted`: the file has `SettingsVersion = 3`, `[GPU] Renderer = Software`,
    `[GameList] RecursivePaths = /Users/x/games`, `[Folders] Covers = /Users/x/covers`, `[Folders] Cheats = mycheats`,
    `[MemoryCards] Directory = ../cards`, `[Pad1] Cross = SDL-0/A`, `[BIOS] PathNTSCJ = scph5500.bin` and
    `[Display] ShowFPS = true`. After `initialize`:
    - no `Renderer`, no `RecursivePaths`, no `PathNTSCJ`;
    - `Covers = covers`, `Cheats = mycheats` (inside, so kept), `Directory = memcards`;
    - no `SDL-0` binding;
    - `ShowFPS = true` (kept);
    - `[Android] InstallID = test-install`, `SettingsVersion = 3`.
  - `ownFileIsKept`: the same file with `[Android] InstallID = test-install` keeps `Renderer = Software` and
    `Pad1 Cross`.
  - `plan1FileIsUpgraded`: `SettingsVersion = 1` with `[Display] ShowFPS = true` gives `SettingsVersion = 3` and
    keeps `ShowFPS`.
  - `biosDirIsUsed`: after `initialize`, `setGameFolders(arrayOf(uriA, uriB))` writes both under `[GameList]
    RecursivePaths`. `settings.ini` has `SearchDirectory = bios` (not `biosDir`).
- [ ] **Step 2: Run to see them fail.** `./gradlew connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=…core.SettingsSetupTest` → compile error (RED).
- [ ] **Step 3: Implement**, as listed. `Host::Internal`'s settings lock guards every read and write of the base
  layer.
- [ ] **Step 4: Run to see them pass**, then the whole instrumented suite. Desktop still green (Qt builds, with the
  shared constant).

### Task 4: BIOS import

**Files:**
- Modify: `android/app/src/cpp/jni_bridge.cpp` (or a new `bios_import.cpp`), `…/core/NativeLibrary.kt`
- Create: `…/storage/BiosImporter.kt`
- Test: `android/app/src/androidTest/java/…/storage/BiosImporterTest.kt`

**Interfaces:**
- Consumes: `FileSystem` content support (Tasks 1–2); `BIOS::FindBIOSImagesInDirectory`.
- Produces: `external fun importBios(sourceTreeUri: String, destDir: String): Array<String>?`. It doesn't need
  `initialize`, and returns null on error. It works as follows:
  - It lists the source with `FindBIOSImagesInDirectory` (top level only, as the core does).
  - It copies each image with a non-null `info` to `destDir/<file name>` with `WriteAtomicRenamedFile`.
  - Then it deletes every other file in `destDir`.
  - It returns `info->description` for each image copied, in name order.
- Produces: `class BiosImporter(context: Context)` with
  `suspend fun import(treeUri: String): BiosImportResult` (runs on `Dispatchers.IO`), and
  `data class BiosImportResult(val images: List<String>, val error: String?)`. `destDir` is `filesDir/bios`.

- [ ] **Step 1: Write the failing tests** (`BiosImporterTest`):
  - `junkIsSkipped`: a tree with a 512 KiB file of zeros and a `readme.txt`. The result has no images, and
    `filesDir/bios` is empty.
  - `staleCopiesAreRemoved`: `filesDir/bios/old.bin` exists before the import, and is gone after.
  - `realBiosIsCopied`: `assumeTrue` that `/data/local/tmp/goose-test/ps-30j.bin` exists (pushed by hand, never
    committed). It is copied into the tree, imported, and the result names `SCPH-5500`. `filesDir/bios/ps-30j.bin`
    has the same bytes.
- [ ] **Step 2: Run to see them fail** (RED: `importBios` is missing).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to see them pass**, with `adb push ~/work/goose-test/ps-30j.bin /data/local/tmp/goose-test/`
  done first, so `realBiosIsCopied` runs rather than being skipped.

### Task 5: folder preferences and grant checks

**Files:**
- Create: `…/storage/FolderPrefs.kt`, `…/storage/GrantCheck.kt`, `…/storage/FolderPicker.kt`
- Test: `android/app/src/test/java/…/storage/GrantCheckTest.kt`, `FolderPrefsTest.kt` (with an in-memory store)

**Interfaces:**
- Produces:
  - `data class SavedFolders(val bios: String?, val games: List<String>, val data: String?, val setupComplete:
    Boolean)`
  - `interface KeyValueStore` (`getString`/`putString`), backed by `SharedPreferences("folders")`.
    - `class FolderPrefs(store: KeyValueStore)` with `load(): SavedFolders`, `save(SavedFolders)` and
      `installId(): String`. The install ID is a UUID created on first call, then kept.
    - Games are stored as one string, in order, with each URI on its own line.
  - `data class PersistedGrant(val uri: String, val read: Boolean, val write: Boolean)` (mapped from `UriPermission`)
  - `enum class FolderKind { Bios, Games, Data }`, and `data class MissingGrant(val kind: FolderKind, val uri: String)`
  - `object GrantCheck { fun missing(saved: SavedFolders, persisted: List<PersistedGrant>): List<MissingGrant> }`.
    Data needs `read && write`; BIOS and games need `read`. URIs match only by string equality.
  - `class FolderPicker(activity: ComponentActivity)`, which registers `OpenDocumentTree` once in `onCreate`:
    - `suspend fun pick(kind: FolderKind): String?` launches the picker. On a result it calls
      `takePersistableUriPermission` with read (plus write for `Data`) and returns the URI string.
    - `fun release(uri: String)` releases a removed folder's grant (`releasePersistableUriPermission`).

- [ ] **Step 1: Write the failing tests.**
  - `GrantCheckTest`:
    - All granted gives an empty list.
    - The data URI granted read-only gives `MissingGrant(Data, …)`.
    - A games URI that differs only by a trailing `/` or by case is missing.
    - A grant for the tree's parent doesn't count.
    - Missing grants come back in BIOS, games, data order.
  - `FolderPrefsTest`:
    - Save then load round-trips three game folders in order.
    - `installId()` is stable across two `FolderPrefs` on the same store.
- [ ] **Step 2: Run to see them fail** (`./gradlew testDebugUnitTest`, RED).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to see them pass.**
- [ ] **Step 5: Checkpoint.** Propose the commits for Tasks 3, 4 and 5 in chat, and commit after approval.

### Task 6: the mirror's manifest and plan

The pure part of the mirror: what to copy and delete, decided from three listings. There's no I/O in this task.

**Files:**
- Create: `…/mirror/FileState.kt`, `…/mirror/Manifest.kt`, `…/mirror/MirrorPlanner.kt`
- Test: `android/app/src/test/java/…/mirror/ManifestTest.kt`, `MirrorPlannerTest.kt`

**Interfaces:**
- Produces:
  - `data class FileState(val size: Long, val lastModifiedMs: Long)`.
  - `data class ManifestEntry(val local: FileState, val folder: FileState)`: both sides as they were when they were
    last known to match.
  - `class Manifest(entries: Map<String, ManifestEntry>)` holds the entries, keyed by relative path with `/`
    separators. It has:
    - `fun encode(): String`
    - `companion fun decode(text: String): Manifest?`, which returns null on any malformed line, so the caller
      starts from an empty manifest.

    The text format is a first line `goosestation-mirror 1`, then one line per entry: path, local size, local
    time, folder size, folder time, separated by tabs. `\`, tab and newline in paths are escaped as `\\`, `\t` and
    `\n`.
  - `sealed interface MirrorAction`, with one class per action:
    - `CopyToLocal(path)`
    - `CopyToFolder(path)`
    - `DeleteLocal(path)`
    - `DeleteFromFolder(path)`
    - `Forget(path)`, which drops the manifest entry when both sides are gone.
  - `object MirrorPlanner`:
    - `fun excluded(path: String): Boolean` is true for anything under `cache/`, and for names ending in
      `.goosetmp` or `.goosebak`.
    - `fun pull(folder: Map<String, FileState>, local: Map<String, FileState>, manifest: Manifest, deferred:
      (String) -> Boolean): List<MirrorAction>`
    - `fun push(folder: Map<String, FileState>, local: Map<String, FileState>, manifest: Manifest, nowMs: Long,
      force: Boolean, deferred: (String) -> Boolean): List<MirrorAction>`

  `deferred` is `path.startsWith("memcards/")` while a game is running.

Rules (the spec's *Data folder* rules; "changed" means differs from the manifest's side):
- **pull:**
  - A folder file that is new, or changed → `CopyToLocal`. The folder wins, even if the local file changed too.
  - A manifest path the folder no longer has → `DeleteLocal` (deletions follow the folder), or `Forget` when the
    local file is gone as well.
  - A local file that isn't in the manifest and isn't in the folder is left alone (it's new, and the next push
    sends it).
  - Deferred paths produce nothing.
- **push:**
  - A local file that is new or changed, and unchanged for 2000 ms (`nowMs - lastModifiedMs >= 2000`, skipped
    when `force`) → `CopyToFolder`. If its folder file is new or changed too, the folder wins → `CopyToLocal`
    instead. That `CopyToLocal` is deferred like a pull's.
  - A local file that is new or changed, whose folder file the manifest has but the folder no longer does → the
    folder deleted it, and deletions follow the folder → `DeleteLocal` (deferred like a pull's).
  - A manifest path whose local file is gone → `DeleteFromFolder`. If the folder file changed, the folder wins →
    `CopyToLocal`.
- Excluded paths never appear in either plan.

- [ ] **Step 1: Write the failing tests.** In `MirrorPlannerTest`, with `s(n) = FileState(n, n * 1000)`:
  - `newFolderFileIsPulled`, `changedFolderFileIsPulled`.
  - `folderWinsWhenBothChanged`: the manifest has `s(1)`/`s(1)`, the folder has `s(2)` and local has `s(3)`. pull
    → `CopyToLocal`.
  - `folderDeletionDeletesLocal`. `newLocalFileSurvivesPull`: it is in local only, with no manifest entry, and pull
    gives no action.
  - `memcardsDeferredWhileRunning`: a changed `memcards/a.mcd` with `deferred` true gives nothing. The same with
    `deferred` false gives `CopyToLocal`.
  - `changedLocalFileIsPushed`. `freshLocalFileWaits`: modified 500 ms ago, nothing; with `force`, `CopyToFolder`.
  - `folderChangedSincePushWins` (Review Focus 3): local changed, folder changed. push → `CopyToLocal`, not
    `CopyToFolder`.
  - `localDeletionDeletesFolder`, `localDeletionLosesToChangedFolder`, and `folderDeletionWinsOverLocalChange`
    (local changed, folder deleted: push gives `DeleteLocal`).
  - `cacheAndTempsExcluded`: `cache/shaders.bin`, `memcards/a.mcd.goosetmp` and `x.goosebak` never appear.
  - In `ManifestTest`: a round trip with paths holding a tab, a newline and `\`; `decode("garbage")` is null.
- [ ] **Step 2: Run to see them fail** (RED).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to see them pass.**

### Task 7: the mirror on real folders

**Files:**
- Create: `…/mirror/FolderStore.kt`, `…/mirror/LocalTree.kt`, `…/mirror/Mirror.kt`
- Test: `android/app/src/androidTest/java/…/mirror/MirrorTest.kt` (on `TestDocumentsProvider` and a temporary
  working copy)

**Interfaces:**
- Consumes: `Manifest`, `MirrorPlanner`, `FileState` (Task 6); `SafDocumentLister` (Task 2).
- Produces:
  - `interface FolderStore`:
    - `listAll(): Map<String, FileState>` (recursive, one query per directory);
    - `copyFrom(path, dest: File)`;
    - `replace(path, source: File): FileState` (returns the folder state after the write);
    - `delete(path)`;
    - `recover()`.

    Every method throws `FolderUnreachableException` (holding the cause) on `SecurityException`,
    `FileNotFoundException` for the root, `IllegalArgumentException` or `IllegalStateException` from the provider.
    `SafFolderStore(resolver, treeUri)` implements it.
  - `SafFolderStore.replace` never leaves a half-written file under the real name, and works like this:
    1. Create the missing parent directories.
    2. Create `<name>.goosetmp` and write it, then `FileOutputStream.fd.sync()`. Use the display name that
       `createDocument` returned, which a provider may change.
    3. If `<name>` exists, rename it to `<name>.goosebak`.
    4. Rename the `.goosetmp` to `<name>`.
    5. Delete the `.goosebak`.

    A provider without `FLAG_SUPPORTS_RENAME` gets an in-place `"wt"` write instead, and a logged warning.
  - `SafFolderStore.recover()` works through every directory. A `.goosebak` with no `<name>` is renamed back;
    otherwise the `.goosebak` is deleted. Every `.goosetmp` is deleted.
  - `object LocalTree`:
    - `fun list(root: File): Map<String, FileState>`
    - `fun copyIn(source: InputStream, dest: File)`: writes `.<name>.goosetmp` in the same folder, then renames it.
  - `class Mirror(store: FolderStore, workingCopy: File, manifestFile: File, clock: () -> Long)`:
    - `suspend fun pull(deferred: (String) -> Boolean): PullResult`, where
      `data class PullResult(val changed: Set<String>)`
    - `suspend fun push(force: Boolean, deferred: (String) -> Boolean)`
    - `val state: StateFlow<MirrorState>`, where `sealed interface MirrorState` is `Idle`, `Syncing(done: Int,
      total: Int)` or `Unreachable(reason: String)`.

    Both run on one single-threaded dispatcher (`Dispatchers.IO.limitedParallelism(1)`), so they never overlap.
    `pull` calls `store.recover()` first. Each action updates the manifest entry from the states actually observed
    after it ran. The manifest is written atomically (temp file, then rename) after each action. Its file is
    `filesDir/mirror-manifest.txt`, outside the working copy.
  - On `FolderUnreachableException`, the run stops. The state becomes `Unreachable`, and the manifest keeps what
    was done before it. The next run tries again and, once it succeeds, sets the state back to `Idle`.

- [ ] **Step 1: Write the failing tests** (`MirrorTest`):
  - `firstPullFillsEmptyWorkingCopy`: the folder holds `settings.ini` and `memcards/a.mcd`; after pull, both are
    local with the same bytes, and `PullResult.changed` contains both.
  - `pushSendsLocalChange`: change `memcards/a.mcd` locally (`setLastModified` 3 s ago), push, and the folder has
    the new bytes. No `.goosetmp` or `.goosebak` is left in the folder.
  - `folderEditWins`: after a sync, change the card on both sides, push, and the local file has the folder's
    bytes.
  - `deletionsFollow`: a file deleted from the folder is gone locally after pull. A file deleted locally is gone
    from the folder after push.
  - `cacheNotMirrored`: local `cache/gamelist.cache` never reaches the folder.
  - `recoversInterruptedReplace` (Review Focus 5): put `a.mcd.goosebak` (old bytes) and `a.mcd.goosetmp` (half
    written) in the folder, with no `a.mcd`, and the manifest listing `a.mcd`. After pull, the folder has `a.mcd`
    with the old bytes, the local `a.mcd` still exists, and no temporary document is left.
  - `unreachableKeepsWorkingCopy` (Review Focus 4): disable `TestDocumentsProvider` mid-test, then change a local
    file and push. The state becomes `Unreachable`, and the local file and manifest are unchanged. Enable it
    again, push, and the folder has the change and the state is `Idle`.
- [ ] **Step 2: Run to see them fail** (RED).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to see them pass**, plus the JVM tests.

### Task 8: the library through the core's game list

**Files:**
- Modify: `android/app/src/cpp/jni_bridge.cpp` (or a new `game_list_jni.cpp`), `…/core/NativeLibrary.kt`
- Create: `…/core/GameEntry.kt`
- Test: `android/app/src/androidTest/java/…/library/LibraryScanTest.kt`

**Interfaces:**
- Consumes: `initialize` and `setGameFolders` (Task 3); content support (Tasks 1–2).
- Produces:
  - `data class GameEntry(val path: String, val title: String, val serial: String, val region: String, val
    totalPlayedSeconds: Long, val lastPlayedSeconds: Long, val coverPath: String?)`. `region` is
    `Settings::GetDiscRegionName`. `coverPath` is `GameList::GetCoverImagePathForEntry` (a working-copy path), or
    null when it's empty.
  - `external fun refreshGameList(invalidateCache: Boolean)` blocks, and is called on `Dispatchers.IO`. It is
    `GameList::Refresh(invalidateCache, false, nullptr)`. It needs `initialize`.
  - `external fun getGameList(): Array<GameEntry>`: every entry except `EntryType::DiscSet`, sorted by title,
    case-insensitively. Strings use `ToJString`.
  - `NativeLibrary.startGame(path)` takes a content path as is.

- [ ] **Step 1: Write the failing test** (`LibraryScanTest`). The test tree holds:
  - `Game A/Game A.cue`, naming `Game A (Track 1).bin`, a MODE2/2352 BIN of 300 sectors of zeros;
  - `Set/Game B.m3u`, naming `Game B (Disc 1).cue` and `Game B (Disc 2).cue`, each with its own BIN;
  - `notes.txt`.

  The test calls `initialize` with a fresh data root, `setGameFolders(arrayOf(tree))`, then
  `refreshGameList(false)`.
  - `getGameList()` has an entry whose path ends in `/Game A/Game A.cue`, and the list holds no BIN on its own.
  - That entry's title is `Game A` (from the file name).
  - The M3U's entry exists (its sub-images opened through the sibling CUEs).
  - `notes.txt` is not listed, and `GAME C.PSEXE` (a 2 KiB file with a valid `PS-X EXE` header) is: extensions
    match regardless of case.
  - A second `refreshGameList(false)` lists each folder once, and opens no image again. Check it with a counter on
    `TestDocumentsProvider.openDocument`, which stays unchanged. This is the cache keyed by path and time.
  - Rewrite `Game A.cue` (a new modified time): the next refresh opens it again.
- [ ] **Step 2: Run to see it fail** (RED).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to see it pass.**
- [ ] **Step 5: Checkpoint.** Propose the commits for Tasks 6, 7 and 8 in chat, and commit after approval.

### Task 9: start-up, first-run setup and re-pick prompts

**Files:**
- Create: `…/app/AppController.kt`. Plan 1's private `App` object moves out of `MainActivity.kt`, and grows the
  start-up sequence, the screen state, the mirror and the library.
- Create: `…/ui/SetupScreen.kt`, `…/ui/components/Banner.kt` (in-screen prompt with up to two focusable actions),
  `…/ui/components/FocusRing.kt` (the focus border and A-button handling from Plan 1's `GameRow`, shared)
- Modify: `MainActivity.kt` (register `FolderPicker`; `onStart`/`onResume`/`onStop` to `AppController`)
- Test: `android/app/src/test/java/…/app/StartupPlanTest.kt`; `android/app/src/androidTest/java/…/ui/SetupScreenTest.kt`

**Interfaces:**
- Consumes: `FolderPrefs`, `GrantCheck`, `FolderPicker` (Task 5); `BiosImporter` (Task 4); `Mirror` (Task 7);
  `NativeLibrary.initialize`, `setGameFolders`, `reloadSettings` (Task 3); `refreshGameList`, `getGameList`
  (Task 8).
- Produces:
  - `enum class Screen { Setup, Library, Folders, Game }`
  - `object AppController`, main thread only, with Compose state:
    - `screen`
    - `missingGrants: List<MissingGrant>`
    - `mirrorState: StateFlow<MirrorState>`
    - `games: List<GameEntry>`
    - `message`/`messageIsError` (as in Plan 1)
    - `fun repick(grant: MissingGrant)`
  - `object StartupPlan`, pure and unit-tested, with `enum class StartupStep { ShowSetup, Pull, InitializeCore,
    SetGameFolders, RefreshLibrary, ReloadSettings }` and two functions:
    - `fun steps(saved: SavedFolders, missing: List<MissingGrant>, coreStarted: Boolean): List<StartupStep>`
    - `fun afterPull(changed: Set<String>): List<StartupStep>`
  - `data class BannerModel(val text: String, val actions: List<Pair<String, () -> Unit>>)`, with at most two
    actions, drawn by `Banner`.
- Removes Plan 1's app-specific folders:
  - `getExternalFilesDir(null)/games` is no longer listed or created, and `…/data` is no longer the data root.
  - Neither is deleted, so a developer can copy their data out (Task 12 documents it).

Behaviour:
- **Cold start, set up.** In order: check grants → pull, if the data grant is there → `initialize(filesDir/data,
  resources, filesDir/bios, installId)` → `setGameFolders(granted game trees)` → `refreshGameList(false)` →
  library. While the first pull runs, the library shows "Copying your data from the data folder… (n of m)".
- **Cold start, not set up** (`setupComplete` false) → the setup screen. The core isn't initialised until setup
  ends.
- **Resume** (`onStart`): check grants again → pull with `deferred` = memory cards while a game runs → if
  `settings.ini` is in `PullResult.changed`, `reloadSettings()` → if on the library, `refreshGameList(false)`.
- **Push:**
  - every 2 s while started, `push(force = false)`, from a coroutine started in `onStart` and cancelled in
    `onStop`;
  - `push(force = true)` in `onStop`, and after `onGameStopped`;
  - after `onGameStopped`, a deferred pull of `memcards/` first.
- **Missing grants** show as a `Banner` on the library:
  - Data: "GooseStation can't reach your data folder. Changes are kept on this device until you pick it
    again." with **Pick again**.
  - Games: "Can't read <folder name>. Its games are hidden until you pick it again." with **Pick again** and
    **Remove folder**. Only removing changes `setGameFolders`.
  - BIOS: "Can't read your BIOS folder. The copies already made still work." with **Pick again**.

  Picking the same URI again restores it. A different URI for the data folder goes to Task 11's change flow. A
  different URI for games or BIOS replaces that folder (BIOS is imported again).
- **Unreachable** (`MirrorState.Unreachable`, with the grant present) shows as a banner: "The data folder isn't
  reachable (<reason>). Your changes are kept on this device and saved to the folder when it's back." No action.
- **Setup screen.** Four steps, one at a time, each with its primary button focused when it opens. B goes back a
  step.
  1. **Welcome:** what the three folders are for. "Android's folder picker may need touch."
  2. **BIOS folder:** **Pick folder** → import → the list of images found. If none: "No PS1 BIOS found in this
     folder. Pick another." and **Next** stays disabled.
  3. **Games folders:** **Add folder** (repeatable) and a list with **Remove** on each. **Next** needs at least one.
  4. **Data folder:** **Pick folder**, then a top-level `listChildren` of it:
     - if it has `settings.ini` or a non-empty `memcards`: "This folder already has GooseStation data (settings
       and N memory cards). GooseStation will use it.";
     - otherwise: "This folder is empty. GooseStation will start fresh here."

     Then **Finish** saves `setupComplete` and runs the cold-start sequence.

- [ ] **Step 1: Write the failing tests.**
  - `StartupPlanTest`:
    - not set up gives `[ShowSetup]`;
    - set up with every grant, core not started, gives `[Pull, InitializeCore, SetGameFolders, RefreshLibrary]`;
    - a missing data grant gives the same without `Pull`;
    - core started (a resume) gives `[Pull, RefreshLibrary]`, plus `ReloadSettings` when the pull changed
      `settings.ini` (`afterPull(setOf("settings.ini"))` is `[ReloadSettings]`, and `afterPull(setOf("memcards/a.mcd"))`
    is empty).
  - `SetupScreenTest` (Compose UI test, with a fake picker and importer injected through the screen's parameters):
    - on opening, **Start** is focused;
    - `DirectionDown` and `Enter` move through the steps;
    - with an importer result of no images, **Next** is disabled;
    - with one, **Next** is enabled;
    - `Back` on step 3 returns to step 2.
- [ ] **Step 2: Run to see them fail** (RED).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to see them pass.** Install on `goose-arm64` and complete setup with the real picker: BIOS from
  a folder holding `ps-30j.bin`, games from a folder holding padtest and a CHD, and an empty data folder, all
  created in `/sdcard/GooseTest/` with `adb push`. Expected: the library lists both games, and `settings.ini`
  appears in the data folder within 5 s (`adb shell ls /sdcard/GooseTest/data`).

### Task 10: the library screen: cover grid and list

**Files:**
- Create: `…/ui/LibraryScreen.kt`, `…/ui/GameTile.kt`, `…/ui/GameListRow.kt`, `…/library/CoverLoader.kt`,
  `…/library/PlayTime.kt`
- Delete: `…/ui/GameListScreen.kt` (Plan 1's list)
- Test: `android/app/src/test/java/…/library/PlayTimeTest.kt`; `android/app/src/androidTest/java/…/ui/LibraryScreenTest.kt`

**Interfaces:**
- Consumes: `GameEntry` (Task 8); `AppController` state, `Banner` and `FocusRing` (Task 9);
  `NativeLibrary.getBoolSetting`/`setBoolSetting` (Task 3).
- Produces:
  - `@Composable fun LibraryScreen(games: List<GameEntry>, gridView: Boolean, banners: List<BannerModel>,
    message: String?, isError: Boolean, onToggleView: () -> Unit, onOpenFolders: () -> Unit, onRefresh: () ->
    Unit, onStartGame: (GameEntry) -> Unit, focusPath: String?)`
  - `class CoverLoader(maxBytes: Int)`, with `suspend fun load(path: String, targetPx: Int): ImageBitmap?`. It
    decodes on `Dispatchers.IO` with `inSampleSize` for `targetPx`, and keeps an `LruCache` keyed by path and the
    file's modified time.
  - `object PlayTime { fun format(seconds: Long): String }`:
    - `0` → `Never played`
    - `59` → `Less than a minute`
    - `3600` → `1h 0m`
    - `5400` → `1h 30m`
    - `600` → `10m`

Behaviour:
- **The top bar:** the title, then **Grid/List** (it toggles `[UI] GameListGridView`, which defaults to `true`, and
  saves it), **Folders** (opens Task 11's screen) and **Refresh** (runs `refreshGameList(false)` in the background,
  showing "Scanning your games…"). Banners and the message sit under it.
- **The grid** is a `LazyVerticalGrid(GridCells.Adaptive(150.dp))` of square tiles with the title under each. A
  tile is either:
  - the cover (`ContentScale.Fit`, letterboxed on `surfaceVariant`), or
  - a placeholder: the title centred on `surfaceVariant`, at most three lines.
- **The list** is a `LazyColumn`. Each row shows the title, then region, serial and play time in a second line.
- **Focus:**
  - On opening, the game at `focusPath` (the last one launched) is focused, scrolled into view first; otherwise
    the first game is.
  - A, Enter or a tap starts a game, with Plan 1's guard against an A released from the game screen.
  - Toggling the view keeps the focused game.
  - Up from the first row reaches the top bar.
- **No games:** "No games found in your games folders." with **Folders** focused.

- [ ] **Step 1: Write the failing tests.** `PlayTimeTest` covers the values above. `LibraryScreenTest`, with 30 fake
  entries, one of them with a real PNG cover written to the test's cache:
  - with `focusPath` set to entry 25, that tile has focus after composition;
  - `DirectionRight` then `Enter` calls `onStartGame` with entry 26;
  - with `gridView = false` and entry 25 focused, the row for entry 25 has focus;
  - an entry without a cover shows its title in the tile;
  - with an empty list, **Folders** is focused.
- [ ] **Step 2: Run to see them fail** (RED).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to see them pass.** On `goose-arm64`, with the D-pad (`adb shell input keyevent
  DPAD_RIGHT` and so on): the grid, the list, and launching padtest from each. Put
  `covers/<serial or title>.png` into the data folder, resume the app, and the tile shows it.

### Task 11: folder settings, and changing the data folder

**Files:**
- Create: `…/ui/FoldersScreen.kt`, `…/app/ProcessRestart.kt`, `…/mirror/DataFolderChange.kt`
- Test: `android/app/src/androidTest/java/…/mirror/DataFolderChangeTest.kt`, `…/ui/FoldersScreenTest.kt`

**Interfaces:**
- Consumes: `FolderPicker`, `FolderPrefs` (Task 5); `BiosImporter` (Task 4); `Mirror`, `SafFolderStore` (Task 7);
  `setGameFolders` (Task 3).
- Produces:
  - `enum class DataFolderChoice { CopyThisDevicesData, UseFolderData }`
  - `class DataFolderChange(workingCopy: File, manifestFile: File)` with `suspend fun apply(newTree: String, choice:
    DataFolderChoice, store: FolderStore)`:
    - `CopyThisDevicesData`: every working-copy file that isn't excluded is `replace`d into the new folder. Its
      other files are left alone, and come in at the next pull. The manifest is rebuilt from both sides' states.
    - `UseFolderData`: the working copy's contents, except `cache/`, are deleted, the manifest is emptied, and
      the folder is pulled in full.
  - `object ProcessRestart { fun restart(activity: Activity) }`: `startActivity(Intent.makeRestartActivityTask(
    componentName))`, then `exitProcess(0)`.

Behaviour (screen reached from **Folders** in the library; B goes back to the library, with the focus on the
**Folders** button):
- **BIOS:** the folder's name, the images last imported, **Change** (pick, then import) and **Rescan** (import
  again from the same folder).
- **Games:** each folder with **Remove** (it releases the grant), and **Add folder**. A change calls
  `setGameFolders` and then refreshes the library.
- **Data:** the folder's name, and the mirror's state ("Saved to the folder", "Saving…", or the unreachable text),
  with **Change**.
  - **Change** picks the new folder, then shows its contents as setup's step 4 does, with two in-screen choices:
    **Copy this device's data into it** and **Use the data already there**.
  - Either choice runs a forced push to the old folder first, if it's reachable, then `apply`. It saves the new
    URI, releases the old grant, and calls `ProcessRestart.restart`. The core's data root can't change while it
    runs.
  - Changing the data folder is only offered with no game running: the screen is only reachable from the library.

- [ ] **Step 1: Write the failing tests.**
  - `DataFolderChangeTest` (on two `TestDocumentsProvider` trees):
    - `copyThisDevicesData`: working copy `memcards/a.mcd`, new tree `memcards/b.mcd`. Afterwards the new tree has
      both, and a pull brings `b.mcd` in.
    - `useFolderData`: afterwards the working copy is exactly the new tree's files, and `cache/` is kept.
  - `FoldersScreenTest`:
    - on opening, the BIOS **Change** is focused;
    - choosing **Remove** on the second of two games folders calls the callback with the first only;
    - the data **Change** flow shows both choices, in the screen, with the first one focused.
- [ ] **Step 2: Run to see them fail** (RED).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to see them pass**, then try both choices by hand on `goose-arm64`. Expected: after the
  restart, the library is back, and `settings.ini` and the memory cards are in the new folder.

### Task 12: docs, roadmap, and the checks on the emulator and the Thor

**Files:**
- Modify: `docs/android.md`:
  - "Running it" becomes setup with the folder picker, and the `adb push` steps target folders under `/sdcard`.
  - Moving Plan 1's data: copy `Android/data/…/files/data` into the data folder before setup.
  - "Changing settings" becomes: edit `settings.ini` in the data folder, then reopen the app (it's pulled in, and
    reloaded).
  - The mirror's rules and its banners.
  - The new versions.
  - The results below.
- Modify: `docs/roadmap.md`, item 16: Plan 1 (the emulator on Android, #23) is done, and Plan 2 (storage, setup and
  the library) is in progress, or done with its PR number at merge.

- [ ] **Step 1: Emulator checks** (`goose-arm64`, the real picker, folders under `/sdcard/GooseTest`):
  - [ ] Fresh install → setup → library. Then a CUE/BIN game, an M3U set and a CHD each boot.
  - [ ] Save on a memory card in a game. `/sdcard/GooseTest/data/memcards/` has the change within 5 s. A `.goosetmp`
    may show up between steps of a write, but none is left behind.
  - [ ] Home mid-game, replace the card in the folder with `adb push`, then reopen. The game keeps its card. After
    quitting, the working copy has the folder's version (the folder wins, and the replace waited for the game to
    close).
  - [ ] Delete a save state from the folder, then reopen: it's gone from the working copy
    (`adb shell run-as io.github.ilyalissoboi.goosestation ls files/data/savestates`).
  - [ ] Make the data folder unreachable: `adb shell mv /sdcard/GooseTest/data /sdcard/GooseTest/data-away`, then
    save on a memory card. The unreachable banner shows, and the save stays in the working copy. Move it back and
    reopen the app: the save reaches the folder, and the banner goes. A *missing grant* can't be made from `adb`;
    `GrantCheckTest` and the banner logic in Task 9 cover it.
  - [ ] Kill the app during a large push: copy a 200 MB file in with `adb shell run-as
    io.github.ilyalissoboi.goosestation cp /data/local/tmp/big.bin files/data/screenshots/big.bin`, then
    `adb shell am kill io.github.ilyalissoboi.goosestation` once a `big.bin.goosetmp` shows in the folder. Start
    the app again: the folder has no `big.bin` that is short, no temporary document is left, and the next push
    completes.
  - [ ] Copy a desktop `settings.ini` (version 3, `Renderer = Software`, an `SDL-0` binding) into the data folder,
    then reopen. The renderer is back to Automatic, and the pad maps.
  - [ ] `adb logcat -b crash -d` is empty.
- [ ] **Step 2: The Thor** (only with the user's go-ahead). The same checklist, with the BIOS and games on the SD
  card and the data folder on internal storage. Also:
  - [ ] the whole setup with the built-in controller (touch only in the system picker, if needed);
  - [ ] removing the SD card mid-game shows the unreachable banner after the game, with nothing lost;
  - [ ] boot time from the library, compared with Plan 1's adb-pushed copy, which shows whether reading in place
    is slower.

  Record the results in `docs/android.md`.
- [ ] **Step 3: Desktop still green**, the JVM, instrumented and native test suites pass, and
  `./gradlew assembleDebug` succeeds.
- [ ] **Step 4: Checkpoint.** Propose the commits for Tasks 9–12 in chat, commit after approval, and then open the
  PR.
