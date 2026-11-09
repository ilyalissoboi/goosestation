<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android cover downloads — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Covers page in Android's Settings, after Folders: the player's cover sources (one preset), Save covers by
serial, and Download covers, which fetches a cover for each library tile that has none, natively, cancellably.

**Architecture:** The loop's logic is pure C++ in `src/util/android_cover_downloads.{h,cpp}` (Android-only, built and
tested on every platform in `common-tests`, as `android_disc_groups` is), driven through hooks. Android's glue
(`android/app/src/cpp/cover_downloads_android.{h,cpp}`) gives it the library's tiles (`AndroidHost`'s listed games),
the library's own cover lookup, the core's `HTTPDownloader` with a cancel-only `ProgressCallback` per request, and
writes under `EmuFolders::Covers`; JNI exposes one blocking call. Kotlin keeps the sources and the switch in
`settings.ini` `[Covers]`, an app-owned `Covers` model (as `ShaderPacks` is) runs the download on IO and updates the
library's tiles as covers land, and `CoversScreen` is the page.

**Tech Stack:** C++20 (`src/util`, `android/app/src/cpp`, JNI), googletest (`common-tests`), Kotlin, Jetpack Compose,
JUnit 4 on the JVM, AndroidX instrumented tests on the emulators.

**Spec:** `docs/superpowers/specs/2026-11-09-android-cover-downloads-design.md`

## Global Constraints

- Android-only code in `android/`, plus a new Android-only `src/util/android_cover_downloads.{h,cpp}` (in util's
  `if(ANDROID)` sources and in `common-tests`). `GameList::DownloadCovers`, its path helpers and desktop's
  `coverdownloaddialog` don't change. Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor its closed
  Android app.
- `docs/design-system.md` is binding: controller-first, focus always visible, 48 dp targets, no window dialogs
  (`checkNoWindowDialogs`), a question in an `OverlayPanel` puts its destructive answer first and focuses the safe one.
  The page goes into `FocusAuditTest` and `OrientationLayoutTest`, with a key-driven screen test.
- Nothing goes to the network unless the player pressed Download covers. A cover already there is never replaced.
- The preset, exact: `https://raw.githubusercontent.com/xlenore/psx-covers/main/covers/default/${serial}.jpg`.
- Keys *(plan)*: `[Covers] SourceCount` (absent: never saved, so the preset shows), `[Covers] Source1` …
  `Source<N>`, `[Covers] UseSerial` (absent: true). Desktop reads none of them.
- The covers the library reads: `jpg`, `jpeg`, `png`, `webp` (`GameList::GetCoverImagePath`'s extensions).
- **Copy, exact** (the spec's, plus the plan's where marked *plan*):
  - Hub entry: "Covers", summary "Cover sources and downloads" *(plan)*.
  - Section headings *(plan)*: "Sources", "Downloading".
  - Each source row's label: "Source 1", "Source 2", … *(plan)*. A row without a variable has the note "Needs
    ${serial}, ${title} or ${filetitle}." *(plan)*.
  - With no sources *(plan)*: "No sources. Add one, or restore the preset source."
  - The note under the sources *(plan)*: "${serial}, ${title} and ${filetitle} stand for each game's serial, title
    and file name. GooseStation hosts no covers: the sources are yours to choose."
  - Buttons: "Add a source", "Remove", "Restore the preset source", "Download covers", "Cancel".
  - The restore question *(plan)*: "Replace your sources with the preset source?", answers "Replace" (Danger, first)
    and "Keep mine" (Ghost, focused).
  - Switch: "Save covers by serial", note *(plan)* "Off saves them by title. A game without a serial is saved by
    title."
  - Blocked download: "Each source needs ${serial}, ${title} or ${filetitle}." (spec); with no sources, "Add a source
    first." *(plan)*.
  - Progress: "Downloading the cover for Crash Bandicoot… 12 of 48" (the design-system ellipsis, no space before
    it); before the first game, "Starting…".
  - Summary: "Found 31 covers. 17 games had none at these sources." Singular "1 cover", "1 game had none". Then, when
    non-zero, " 3 couldn't be reached." and " 1 couldn't be saved." *(plan)*. With nothing to fetch, "Every game in
    the library has a cover." *(plan)*. With every game unreachable and none found, "The sources couldn't be
    reached. Check your network connection." *(plan)*. Cancelled: "Cancelled. Found 4 covers." or, with none,
    "Cancelled." *(plan)*.

## Review Focus

1. **A source answering 200 with a web page** (a captive portal, an error page): nothing is saved; the game counts as
   having none there, and the next source is tried. Pinned in Task 1 (`RunSkipsANonImageAnswer`).
2. **No serial and a title that can't be a file name**, with Save covers by serial on: saved by the file's title,
   never `covers/.jpg`; with neither usable, counted as couldn't be saved. Pinned in Task 1 (`CoverFileName*`).
3. **A cover that appears during the run** (the mirror pulls one in, or desktop wrote one): never overwritten. Pinned
   in Task 1 (`RunRechecksBeforeEachGame`) and Task 2 (`keepsACoverThatIsAlreadyThere`).
4. **No network**: every request fails, and the summary says the sources couldn't be reached, not that games had
   none. Pinned in Task 1 (`RunCountsUnreachable`), Task 2 (`aClosedPortIsUnreachable`) and Task 4.
5. **Typing a source and leaving at once** (Back, the app hidden): the edit is saved, not lost to the debounce.
   Pinned in Task 4 (`closeSavesAnEditAtOnce`).

---

### Task 1: The loop and its helpers, pure C++

**Files:**
- Create: `src/util/android_cover_downloads.h`, `src/util/android_cover_downloads.cpp`
- Modify: `src/util/CMakeLists.txt` (the `if(ANDROID)` sources), `src/common-tests/CMakeLists.txt` (the test and
  `../util/android_cover_downloads.cpp`)
- Test: `src/common-tests/android_cover_downloads_tests.cpp`

**Interfaces:**
- Produces, in `namespace AndroidCoverDownloads`:
  - `bool HasVariable(std::string_view source)` — holds `${serial}`, `${title}` or `${filetitle}`.
  - `struct Target { std::string key; std::string title; std::string path_title; std::string display_title; }` — a
    tile's key (serial), title, `Path::GetFileTitle(path)` (what the library's lookup tries), and the file title of
    its display name (for `${filetitle}`, as the core makes it).
  - `std::string ExpandSource(std::string_view source, const Target& target)` — each variable replaced by
    `Path::URLEncode` of key, title, display_title.
  - `std::optional<std::string> CoverExtension(std::string_view content_type, std::string_view url)` — nullopt when
    the content type is present and neither `image/*` nor `application/octet-stream`; else the content type's
    extension (`HTTPDownloader::GetExtensionForContentType`) if it's one the library reads, else the URL's last path
    segment's (query and fragment dropped) if it's one, else `"jpg"`.
  - `std::optional<std::string> CoverFileName(const Target& target, bool use_serial, std::string_view extension)` —
    the first usable of: key (when `use_serial`), title, key, path_title, plus `.` and the extension; usable means
    non-empty and `Path::SanitizeFileName(name) == name`. Nullopt when none is.
  - `struct Response { s32 status; std::string content_type; std::vector<u8> data; }`
  - `struct Hooks { std::function<bool(size_t)> has_cover; std::function<Response(const std::string&)> fetch;
    std::function<std::optional<std::string>(size_t, const std::string& file_name, std::span<const u8>)> save;
    std::function<void(size_t index, u32 position, u32 total)> progress; std::function<void(size_t, const
    std::string& saved_path)> found; std::function<bool()> cancelled; }`
  - `struct Summary { u32 total = 0, found = 0, missing = 0, unreachable = 0, unsaved = 0; bool cancelled = false; }`
  - `Summary Run(std::span<const Target> targets, std::span<const std::string> sources, bool use_serial, const
    Hooks& hooks)`

`Run`: the targets without a cover at the start (`has_cover`) are the `total`. For each, in order: stop if
`cancelled()`; skip it (uncounted) if `has_cover` is now true; `progress(index, position, total)` (position from 1);
try the sources in order, skipping one without a variable: `fetch`; a negative status other than
`HTTP_STATUS_CANCELLED` is unreachable, `HTTP_STATUS_CANCELLED` or `cancelled()` after a fetch stops the run, 200 with
data and a `CoverExtension` is an answer, anything else is none. On an answer, `CoverFileName`, then `save`: a path is
`found` (and the hook), nullopt is `unsaved`; either way no more sources. A game with no answer is `unreachable` when
every source it tried was, else `missing`.

- [ ] **Step 1: Write the failing tests** in `android_cover_downloads_tests.cpp`:
  - `HasVariable`: true for each of the three, false for `"https://example.com/cover.jpg"` and `"${Serial}"`.
  - `ExpandSourceEncodesEachVariable`: `ExpandSource("http://h/${serial}/${title}/${filetitle}.jpg", {"SCUS-94900",
    "Crash Bandicoot: Warped", "x", "Crash 3 (USA)"})` equals `"http://h/SCUS-94900/" + Path::URLEncode("Crash
    Bandicoot: Warped") + "/" + Path::URLEncode("Crash 3 (USA)") + ".jpg"`.
  - `CoverExtension`: `("image/png", "http://h/a.jpg")` → `"png"`; `("", "http://h/a.webp?x=1")` → `"webp"`;
    `("application/octet-stream", "http://h/a.PNG")` → `"png"` (lower-cased); `("", "http://h/a")` → `"jpg"`;
    `("image/gif", "http://h/a.gif")` → `"jpg"`; `("text/html", "http://h/a.jpg")` → nullopt.
  - `CoverFileNameBySerial`: key `"SCUS-94900"`, title `"Crash"`, use_serial true → `"SCUS-94900.jpg"`; false →
    `"Crash.jpg"`.
  - `CoverFileNameUnsafeTitle`: title `"Ape Escape: Special?"`, key `"SCPS-10091"`, use_serial false →
    `"SCPS-10091.jpg"` (as the core's `GetNewCoverImagePathForEntry`).
  - `CoverFileNameNoSerial`: key `""`, title `"Demo"`, use_serial true → `"Demo.png"`; key `""`, title `"A:B"`,
    path_title `"ab"` → `"ab.png"`; key `""`, title `"A:B"`, path_title `""` → nullopt.
  - With a `FakeHooks` (a map of URL → `Response`, a set of covered indices, the saved names, the progress calls):
    - `RunTriesSourcesInOrder`: source 1 404, source 2 200 `image/jpeg` → summary `{total 1, found 1}`, one save
      named `"SCUS-94900.jpg"`, fetches in source order, and none for a source without a variable.
    - `RunSkipsGamesWithACover`: targets 0 and 1, 0 covered → total 1, no fetch for 0, `progress(1, 1, 1)`.
    - `RunSkipsANonImageAnswer`: source 1 200 `text/html`, source 2 200 `image/png` → found 1, saved `.png`.
    - `RunCountsUnreachable`: every fetch status -1 → `{total 2, unreachable 2}`; one 404 and one -1 → missing 1.
    - `RunRechecksBeforeEachGame`: `has_cover` false at the start for both, true for index 1 when asked again →
      total 2, found 1, index 1 never fetched.
    - `RunStopsWhenCancelled`: `cancelled()` true after the first fetch → `cancelled`, one fetch; status -3 alone
      also stops it.
    - `RunCountsUnsaved`: save returns nullopt → unsaved 1, no second source tried.

- [ ] **Step 2: Run** `cmake --build build --target common-tests && build/bin/common-tests
  --gtest_filter='AndroidCoverDownloads*'` (configure `build` first if this worktree has none, `-DBUILD_TESTS=ON`, in
  the background). Expected: fails to compile.
- [ ] **Step 3: Implement** the header and source as the Interfaces say.
- [ ] **Step 4: Run** the filter again, then all of `common-tests`. Expected: all pass.
- [ ] **Step 5: Commit** ("Android: cover downloads' loop, as pure code tested on every platform").

### Task 2: Native glue, JNI and the Kotlin API

**Files:**
- Create: `android/app/src/cpp/cover_downloads_android.h`, `android/app/src/cpp/cover_downloads_android.cpp`,
  `android/app/src/main/java/io/github/ilyalissoboi/goosestation/core/CoverApi.kt`
- Modify: `android/app/src/cpp/CMakeLists.txt`, `android/app/src/cpp/android_host.{h,cpp}` (`GetListedGames`),
  `android/app/src/cpp/jni_bridge.cpp` (`getGameList`'s cover lookup through `FindLibraryCover`; two JNI functions),
  `core/NativeLibrary.kt`
- Test: `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/core/CoverDownloadsTest.kt`

**Interfaces:**
- Consumes: Task 1's `AndroidCoverDownloads`.
- Produces:
  - C++ `std::vector<AndroidDiscGroups::Game> AndroidHost::GetListedGames()` (a copy, under its mutex).
  - C++ `std::string CoverDownloadsAndroid::FindLibraryCover(const AndroidDiscGroups::Game& game)` — the lookup
    `getGameList` does now (the game's path, key, title; then its first disc's), which it calls instead.
  - C++ `AndroidCoverDownloads::Summary CoverDownloadsAndroid::Download(JNIEnv* env, std::vector<std::string>
    sources, bool use_serial, jobject listener)` — the listed games as `Target`s; `fetch` through one
    `HTTPDownloader::Create(Host::GetHTTPUserAgent())` per run, each request given a `ProgressCallback` subclass whose
    `IsCancelled()` asks the listener (so the poll cancels the request in flight) and whose range and value calls do
    nothing else; `save` makes `EmuFolders::Covers` if missing, refuses a file already there, writes with
    `FileSystem::WriteAtomicRenamedFile`; `progress` and `found` call the listener. No downloader: every target
    unreachable.
  - JNI `NativeLibrary.downloadCovers(sources: Array<String>, useSerial: Boolean, listener: CoverProgress): IntArray`
    (`[total, found, missing, unreachable, unsaved, cancelled 0/1]`); blocks; never on the main or the core's thread.
  - Kotlin, `core/CoverApi.kt`: `interface CoverProgress { fun game(position: Int, total: Int, title: String); fun
    found(gamePath: String, coverPath: String); fun cancelled(): Boolean }`; `data class CoverSummary(val total: Int,
    val found: Int, val missing: Int, val unreachable: Int, val unsaved: Int, val cancelled: Boolean)`; `interface
    CoverApi { fun downloadCovers(sources: List<String>, useSerial: Boolean, progress: CoverProgress): CoverSummary
    }`; `object NativeCoverApi : CoverApi`.

- [ ] **Step 1: Write the failing test** `CoverDownloadsTest`: set up as `LibraryScanTest` does (its own data root,
  a `TestTrees` tree holding `GAME C.PSEXE` and `GAME D.PSEXE`, `setGameFolders`, `refreshGameList`, then
  `getGameList` so the listed games are set), with a `ServerSocket` on 127.0.0.1 answering as `HttpDownloaderTest`
  does (matching on the URL-decoded path), and a recording `CoverProgress`. Sources use `${title}` (PS-EXEs have no
  serial). Tests:
  - `savesTheFirstSourceThatAnswers`: `/a/GAME C.png` 404, `/b/GAME C.png` 200 `image/png` (a few bytes) →
    summary found ≥ 1, `data/covers/GAME C.png` holds those bytes, `found` called with GAME C's tile path and that
    file, and `getGameList()` now gives GAME C that cover.
  - `keepsACoverThatIsAlreadyThere`: `covers/GAME C.jpg` written first → no request for GAME C, its file unchanged.
  - `cancelStopsTheRequestInFlight`: the server never answers; `cancelled()` turns true 500 ms after the first
    `game()` → returns within 5 s (not the 30 s timeout) with `cancelled`, nothing saved.
  - `aClosedPortIsUnreachable`: a source on a port nothing listens on → unreachable = total, found 0.
- [ ] **Step 2: Run** `CoverDownloadsTest` alone on an emulator (`./gradlew connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.core.CoverDownloadsTest`).
  Expected: fails to compile (`downloadCovers` missing).
- [ ] **Step 3: Implement** the C++ and Kotlin as the Interfaces say; add the source files to the CMake list.
- [ ] **Step 4: Run** the class again, then `LibraryScanTest` (the lookup moved). Expected: all pass; read the XML.
- [ ] **Step 5: Commit** ("Android: download covers natively, cancellable mid-request").

### Task 3: The sources and the switch in settings.ini

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/settings/CoverSettings.kt`
- Test: `android/app/src/test/java/io/github/ilyalissoboi/goosestation/settings/CoverSettingsTest.kt`

**Interfaces:**
- Produces: `object CoverSources { const val PRESET: String; fun load(s: CoreSettings): List<String>; fun save(s:
  CoreSettings, sources: List<String>) }` (save drops blank lines, writes `SourceCount` and `Source1..N`, deletes the
  old ones past N); `object CoverUseSerial { fun load(s: CoreSettings): Boolean; fun save(s: CoreSettings, on:
  Boolean) }`; `fun coverSourceProblem(source: String): String?` (null for a blank line or one with a variable, else
  the row note).

- [ ] **Step 1: Write the failing tests** with `MemoryCoreSettings`: `loadIsThePresetUntilSaved` (`[PRESET]`);
  `anEmptiedListStaysEmpty` (save `[]` then load → `[]`, `SourceCount` 0); `saveDropsBlankLinesAndOldKeys` (save 3,
  then `["a ${serial}", " "]` → load `["a ${serial}"]`, `Source2` and `Source3` gone); `useSerialIsOnByDefault`;
  `aSourceWithoutAVariableHasANote` ("Needs ${serial}, ${title} or ${filetitle}.", null for `""` and for the preset).
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*CoverSettingsTest'`. Expected: fails to compile.
- [ ] **Step 3: Implement** `CoverSettings.kt`.
- [ ] **Step 4: Run** it again. Expected: pass.
- [ ] **Step 5: Commit** ("Android: keep cover sources and Save by serial in settings.ini").

### Task 4: The Covers model

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/app/Covers.kt`
- Test: `android/app/src/test/java/io/github/ilyalissoboi/goosestation/app/CoversTest.kt`

**Interfaces:**
- Consumes: Task 2's `CoverApi`, `CoverProgress`, `CoverSummary`; Task 3's settings.
- Produces:
  - `data class CoverSource(val id: Int, val text: String)`; `data class CoverJob(val title: String?, val position:
    Int = 0, val total: Int = 0)`.
  - `interface CoversModel { val sources: List<CoverSource>?; val useSerial: Boolean; val job: CoverJob?; val
    message: String?; val restoring: Boolean; val blocked: String?; fun open(); fun edit(id: Int, text: String); fun
    add(): Int; fun remove(id: Int); fun askRestore(); fun confirmRestore(); fun cancelRestore(); fun
    setUseSerial(on: Boolean); fun download(); fun cancel() }` (`sources` null until read; `blocked` is the reason
    Download waits, or null; Restore shows while `sources != listOf(PRESET)`).
  - `class Covers(scope, io: CoroutineDispatcher, core: CoroutineDispatcher, api: CoverApi, settings: CoreSettings,
    onFound: (gamePath: String, coverPath: String) -> Unit) : CoversModel`, plus `fun close()` (saves an edit still
    waiting) and `fun stop()` (`close()` and cancels a download: a game starts, or the app is hidden).
  - `fun coverProgressText(job: CoverJob): String`, `fun coverSummaryText(summary: CoverSummary): String`.

Settings are read and written on `core`; an edit is saved 500 ms after the last one *(plan)*, or at once by `close`,
`stop`, add, remove, restore or download. `download` saves, then runs the sources as they were on IO; one run at a
time. `askRestore` restores at once when the list is empty.

- [ ] **Step 1: Write the failing tests** with a `FakeCoverApi` (records its calls; a test drives its `progress`),
  `MemoryCoreSettings`, `StandardTestDispatcher`:
  - `opensWithThePreset`; `anEditIsSavedAfterAPause` (not before 500 ms, then `Source1`); `closeSavesAnEditAtOnce`.
  - `removingTheLastSourceKeepsTheListEmpty` (reopened: still `[]`); `restoreAsksWhenItReplacesSources` (`restoring`
    true, nothing changed until `confirmRestore`); `restoreOnAnEmptyListDoesNotAsk`.
  - `downloadIsBlockedByASourceWithoutAVariable` (`blocked` is the spec's line, `download()` doesn't call the api);
    `downloadIsBlockedWithNoSources` ("Add a source first."); blank rows don't block and aren't sent.
  - `downloadShowsProgressAndTheSummary` (`job` "Starting…" then "Downloading the cover for Crash Bandicoot… 12 of
    48"; `found` reaches `onFound`; message "Found 31 covers. 17 games had none at these sources.").
  - `downloadTwiceRunsOnce`; `cancelStopsTheRun` (`progress.cancelled()` true after `cancel()`, message
    "Cancelled. Found 4 covers."); `stopCancelsARun`.
  - `coverSummaryText` for each line in the Global Constraints' copy, singular and plural.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*CoversTest'`. Expected: fails to compile.
- [ ] **Step 3: Implement** `Covers.kt`, following `ShaderPacks.kt`'s shape (Compose state, a `@Volatile` cancel
  flag read by the progress object, progress reaching the state through `scope.launch`).
- [ ] **Step 4: Run** it again. Expected: pass.
- [ ] **Step 5: Commit** ("Android: the Covers model: sources, the preset, and a download the app owns").

### Task 5: The Covers page

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/CoversScreen.kt`
- Modify: `ui/components/SettingsRows.kt` (`TextFieldRow` gains `note: String? = null`, shown as the other rows'
  notes), `docs/design-system.md` (that note; the sources as an editable list of text rows)
- Test: `androidTest/…/ui/CoversScreenTest.kt`, `FocusAuditTest.kt`, `OrientationLayoutTest.kt`

**Interfaces:**
- Consumes: Task 4's `CoversModel`.
- Produces: `@Composable fun CoversScreen(model: CoversModel, onBack: () -> Unit)`.

Layout: `SettingsPage("Covers")` (`SettingsLoadingPage` while `sources` is null); "Sources" section; each source a
`Row` of `TextFieldRow("Source N", note = coverSourceProblem(…), imeAction = Done)` (weight 1) and a Ghost "Remove",
bottom-aligned, the field's `TextFieldState` kept per `CoverSource.id` and its text sent through `edit`; the empty
line when there are none; a `FlowRow` of "Add a source" and, when shown, "Restore the preset source"; the note.
"Downloading" section: the `SwitchRow`; while `job` is set, its line, a `ProgressBar` (`position / total`, or none
before the first game) and "Cancel"; else "Download covers" (Primary, disabled with `blocked` under it); the message.
The restore question is an `OverlayPanel`. Focus: on opening, Source 1 (or Add a source); after Add, the new field;
after Remove, the next field, else the one before, else Add a source; on a job starting, Cancel; when it ends,
Download covers.

- [ ] **Step 1: Write the failing tests.** `CoversScreenTest`, with a fake `CoversModel`, driven by keys: opens on
  Source 1; Right reaches its Remove; Down from the last row reaches Add a source; Enter on Add a source focuses the
  new field; Remove focuses the next field; Restore opens the question focused on "Keep mine", B closes it; a source
  without a variable shows its note and Download covers is disabled with the line under it; a job shows the progress
  line and focuses Cancel. `FocusAuditTest`: `coversPage`, `coversPageEmpty`, `coversPageDownloading`,
  `coversRestoreQuestion`. `OrientationLayoutTest`: the page with three long sources (200-character URLs) and a
  running job, and the restore question.
- [ ] **Step 2: Run** the three classes one at a time on an emulator. Expected: fail to compile.
- [ ] **Step 3: Implement** `CoversScreen.kt` and the `TextFieldRow` note.
- [ ] **Step 4: Run** them again, and `SettingsRowsTest` (the `TextFieldRow` change). Expected: pass; read the XML.
- [ ] **Step 5: Commit** ("Android: the Covers page").

### Task 6: Into Settings and the app

**Files:**
- Modify: `ui/SettingsScreen.kt` (`SettingsEntry.Covers` after Folders), `app/AppController.kt` (`Screen.Covers`; the
  `covers` instance on `Dispatchers.IO` and `coreDispatcher`, `onFound` replacing the tile's `coverPath` in `games`;
  `openSettingsPage` → `covers.open()`; `closeSettingsPage` → `covers.close()`; `covers.stop()` in `startGameNow`,
  `startGameFromStateNow` and `onStop`), `MainActivity.kt` (the route)
- Test: `androidTest/…/ui/SettingsScreenTest.kt`

- [ ] **Step 1: Write the failing test** in `SettingsScreenTest`: the hub's last entry is Covers, disabled with
  `NEEDS_CORE` while the core isn't ready, and opens with Enter when it is.
- [ ] **Step 2: Run** it. Expected: fails (no `SettingsEntry.Covers`).
- [ ] **Step 3: Implement** the entry, the screen, the wiring and the route.
- [ ] **Step 4: Run** `SettingsScreenTest`, `FocusAuditTest`'s hub tests, then `assembleDebug testDebugUnitTest
  ktlintCheck checkNoWindowDialogs` and `android/scripts/run-instrumented-tests.sh quick` (from a detached worktree).
  Expected: green; read the XML.
- [ ] **Step 5: Commit** ("Android: Covers in Settings, cancelled by a game start or the app hidden").

### Task 7: Docs and the full tier

**Files:**
- Modify: `docs/android.md` (the Covers page; the preset and that GooseStation hosts nothing; the keys; that tests
  serve covers locally), `docs/roadmap.md` (item 16: cover downloads followed)

- [ ] **Step 1: Write** the docs.
- [ ] **Step 2: Run** `GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh full` from a
  detached worktree at the branch's head, in the background; `common-tests`; the `duckstation-qt` build (desktop
  unchanged). Expected: green; read the XML for case and failure counts.
- [ ] **Step 3: Commit** ("Docs: Android cover downloads"), and push to `claude/cover-downloads`.
