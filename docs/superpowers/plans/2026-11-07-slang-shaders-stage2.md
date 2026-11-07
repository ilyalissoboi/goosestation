<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Slang shader presets, stage 2: the shader browser and pack downloads — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adding a post-processing stage opens a browser of every shader and preset (by folder, with a search), and
each post-processing page can download, update and remove the four slang packs, on Qt, Big Picture and Android.

**Architecture:** The core owns everything below the UIs. `src/util/postprocessing_browser` turns the shader list
(now with kinds, from the shaders folder and a second "packs" root) into folders and search results.
`src/core/shader_packs` holds the pack table, GitHub version parsing, markers, dependency order and the install
sequence, and talks to the network only through a small `ShaderPacks::Transport` interface: desktop's wraps
`HTTPDownloader`, Android's streams to a file through Kotlin, and the tests use a fake one. `src/core/shader_pack_archive`
extracts a zip file with minizip, path hardening and size caps. Qt gets two dialogs, Big Picture two popups and
Android two sub-pages of Post-processing. Android's packs live in the app's own storage, and its mirror skips the
files desktop's markers list.

**Tech Stack:** C++20 core (`src/common`, `src/util`, `src/core`), minizip (`dep/minizip`), the core's own small
JSON reader (moved out of `achievements_offline_store.cpp`), Qt 6, Dear ImGui (Big Picture), Kotlin/Jetpack Compose
over JNI, gtest (`common-tests`), JUnit and AndroidX tests.

**Spec:** `docs/superpowers/specs/2026-11-07-slang-shaders-design.md` ("The shader browser", "Shader packs", stage 2
of "Stages"). Reference: the PCSX2 fork (`github.com/ilyalissoboi/pcsx2`, GPL, the user's): `pcsx2/ShaderPacks.*`,
`pcsx2/ShaderPackArchive.*`, `pcsx2-qt/ShaderPackDownloadDialog.*`, `pcsx2-qt/ShaderPresetPickerDialog.*`,
`tests/ctest/core/shader_pack*_tests.cpp`, `docs/superpowers/specs/2026-09-13-shader-pack-downloader-design.md`.

## Global Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`. The PCSX2 fork's code may be adapted.
- **The pack table, exactly:**

| id | Name | Repository | Version | Asset | Strip | Installed under | Skips | Needs | Licence |
|---|---|---|---|---|---|---|---|---|---|
| `shaders_slang` | libretro slang shaders | `libretro/slang-shaders` | head of `master` | `github.com/<repo>/archive/<sha>.zip` | 1 | `shaders_slang` | | | Each shader's own (see its file) |
| `retro-crisis-gdv-ntsc` | Retro Crisis GDV-NTSC | `RetroCrisis/Retro-Crisis-GDV-NTSC` | latest release | first `.zip` | 1 | `shaders_slang/retro crisis` | | `shaders_slang` | GPL-3.0 |
| `satpixie-crt` | satpixie CRT | `Conkwer/satpixie-crt-shader` | latest release | first `.zip` not containing `variants` | 4 | `shaders_slang` | | | MIT or public domain |
| `lola-retro` | lola-retro | `zoran123456/lola-retro` | latest release | first `.zip` starting `lola-retro-v` | 1 | `lola-retro` | `hdr/` | | MIT |

  Descriptions: libretro "RetroArch's slang shader collection: CRT, NTSC, scalers, handheld screens and more.";
  Retro Crisis "CRT presets built on Guest Advanced NTSC, by display resolution. Needs the libretro slang shaders.";
  satpixie "A lightweight CRT shader with ghosting and chroma options."; lola-retro "An analog CRT simulation:
  curved, flat, lite and per-system presets."
- **Where packs go:** desktop `EmuFolders::Shaders`; Android `<filesDir>/shaderpacks` (never under
  `<filesDir>/data`, which "use the folder's data" wipes). Markers `<root>/.shaderpacks/<id>.json`; a download in
  progress is `<root>/.shaderpacks/<id>.zip.part`.
- **Caps:** an archive entry 64 MiB, all entries 1 GiB, the download 1 GiB.
- **Copy, exact** (core strings through `TRANSLATE_FS("ShaderPacks", …)`; the UIs use the same words):
  - Steps: "Checking {name}…", "Downloading {name}…", "Removing the previous {name}…", "Extracting {name}…".
  - Failures: "Couldn't reach GitHub: check the connection." (status 0, −1); "GitHub took too long to answer: try
    again later." (−2); "Cancelled." (−3, or cancelled); "GitHub's rate limit was reached: try again later." (403 or
    429 from `api.github.com`); "Checking {name} failed (HTTP {n})."; "Downloading {name} failed (HTTP {n}).";
    "The download for {name} is larger than 1 GB."; "The download for {name} isn't a zip archive.";
    "Refusing unsafe archive entry '{entry}'."; "Archive entry '{entry}' is too large ({n} bytes).";
    "Needs {name}, which didn't install."; "Release {tag} has no matching .zip file.".
  - States: "Not installed"; "Installed {version}"; "Update available: {old} → {new}"; "Installed {version}
    (couldn't check for updates)"; "Incomplete: install it again".
  - Notes: "Third-party content under its own licences."; removing libretro while Retro Crisis stays: "The Retro
    Crisis presets need the libretro slang shaders and will stop working."
  - Entry points: Qt "Download Shader Packs…", Big Picture "Download Shader Packs", Android "Download shader packs".
- **Kinds** shown in the browser: "GLSL", "dolphinfx" (a GLSL shader under `dolphinfx/`), "ReShade", "Slang".
- **Last folder used:** desktop `[UI] ShaderBrowserFolder` in `settings.ini` (Qt and Big Picture share it); Android
  the app's `FolderPrefs` (per device). It's the folder of the last shader added.
- Android: `docs/design-system.md` binding; new pages in `FocusAuditTest` and `OrientationLayoutTest`; emulators
  only; the Thor only with the user's go-ahead, and no scratch memory cards in its data folder.
- `gh` as ilyalissoboi (`GH_TOKEN=$(gh auth token --user ilyalissoboi)`), never the GitHub MCP; push with a
  credential helper giving username ilyalissoboi and that token. Branch `claude/slang-shaders-stage2` (from `master`
  1bbb19258), pushed as itself. Commit each task when it's done and show its message after; messages end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. New sources go into the `.vcxproj`/`.filters` files
  too; new test files are CMake-only, as stage 1's are.

## Found in the code

- **Listing** (`src/util/postprocessing.cpp:147-224`, `GetAvailableShaderNames`): GLSL from the resources'
  `shaders/` and `EmuFolders::Shaders` (recursive, name = path without `.glsl`; dolphinfx is GLSL under
  `dolphinfx/`), ReShade from both `reshade/Shaders` (name relative to it, no `.fx`), slang from
  `Slang::FindPresets(EmuFolders::Shaders)` only when librashader loads. Directory entries only, no file reads.
  Every lookup (`TryLoadingShader`, `:745-797`; slang's `ResolvePresetPath`, `:811`) is against one shaders root.
  Kinds are only worked out on Android (`android_host.cpp:902-917`). No renderer filter outside Android's
  `offered()` (`settings/PostProcessingSettings.kt:77-81`, ReShade hidden under OpenGL ES).
- **The packs, checked live on 2026-11-07:** libretro `master` is 5,256 files (2,662 presets, none `.glsl` or
  `.fx`), ~140 MB; its archive has no size up front. Retro Crisis 20260927's zip has a `retro crisis/` top folder,
  355 `__MACOSX/` entries and a `.DS_Store`. satpixie 20260122: strip 4 keeps `crt/…` and also lands its ReShade
  `CRT-Satpixie-Simplified.fx` at `shaders_slang/`'s top, where no listing looks (ReShade is only listed under
  `reshade/Shaders`), so it's inert. lola-retro v1.19: `lola-retro-v1.19.zip` beside `-86box-`, `-amiberry-`,
  `-kodi-` zips; 299 entries under `lola-retro/`, 68 of them `hdr/`.
- **HTTP** (`src/util/http_downloader.*`): memory only; the timeout restarts on each chunk (an idle timeout);
  cancel only through the request's `ProgressCallback::IsCancelled()`; progress is `SetProgressRange(content_length)`
  and `SetProgressValue(bytes)` on each poll, overwriting the caller's range; no custom headers; curl reports a
  transport failure as status 0. Android's (`android/app/src/cpp/http_downloader_android.cpp`, `HttpBridge.kt:43-125`)
  reads the whole body into memory, reports no progress, and runs on Kotlin's "HTTP" pool; `HttpBridgeTest` tests
  `HttpBridge.run` against a local `ServerSocket`.
- **Archives and JSON:** minizip (`dep/minizip`, reader and writer) with `MinizipHelpers::OpenUnzFile`
  (`src/common/minizip_helpers.h:71-89`); the updater's loop is the pattern (`src/updater/updater.cpp:163-240`).
  `core` and `util` don't link minizip; `duckstation-qt`, the updater and Android's library do. No JSON library: the
  offline achievements store has a strict private reader (`src/core/achievements_offline_store.cpp:28-304`, whole
  numbers only, depth 16, `AppendJsonString` writer); rapidyaml here can't parse JSON.
- **Qt** (`src/duckstation-qt/postprocessingsettingswidget.cpp:160-195`): Add is a flat `QMenu` of
  `GetAvailableShaderNames()`; each action takes `Host::GetSettingsLock()`, calls `Config::AddStage`, then
  `commitSettingsUpdate()`. The top row of `postprocessingchainconfigwidget.ui:15-46` holds Enable and Reload.
  `QtAsyncProgressThread` (`qtprogresscallback.h:45-85`) and `CoverDownloadDialog` are the worker pattern.
- **Big Picture** (`src/core/fullscreen_ui.cpp`, not built on Android): Add Shader (`:5024-5056`) is
  `OpenChoiceDialog` over `GetAvailableShaderNames()`; its callback runs after the page's settings lock is released
  (`imgui_fullscreen.cpp:523-528`) and writes without it, and its failure toast drops the error (one `{}`, two
  arguments). No sub-pages inside a settings page; the file selector (`imgui_fullscreen.cpp:2113-2325`) goes up with
  X, closes with B, and defers to the host's selector, so it can't be reused. `OpenInputStringDialog` refuses empty
  text; inline `ImGui::InputText` starts the host's text input (`imgui_manager.cpp:420-450`, Steam's keyboard).
  The background progress dialog has no cancel. `MemcardSync::ApplySettingsAsync` + `UpdateMemcardSyncState`
  (`memcard_sync.cpp:1205-1225`, `fullscreen_ui.cpp:4165-4182`) is the worker-and-poll pattern. Strings are
  `FSUI_*`, regenerated into the block at `:7562-8293` by `scripts/generate_fullscreen_ui_translation_strings.py`.
- **Android:** EmuFolders come from `AndroidHost::Initialize` (`android_host.cpp:265-308`) and `LoadFolders`
  (`:418-423`, which forces `Bios`); `NativeLibrary.initialize(dataRoot, resourcesRoot, biosDir, …)`
  (`NativeLibrary.kt:28`, called at `AppController.kt:1424-1442`). The add-shader flow: `getPostProcessingShaders`
  (`jni_bridge.cpp:2893-2902`, flat name/kind pairs, non-slang shaders loaded to drop ones that fail) →
  `PostProcessingPage.read()` (`app/PostProcessingPage.kt:377-395`) → `AddShaderPage` (`ui/PostProcessingScreen.kt:237-254`,
  a non-lazy `SettingsPage`). Sub-pages are model state (`adding`, `stage`). `TextFieldRow`
  (`ui/components/SettingsRows.kt:611-695`) opens the keyboard on A. No window dialogs (`checkNoWindowDialogs`);
  `OverlayPanel` for questions. No native progress bridge exists. JVM tests can't touch `android.*` (no
  Robolectric), so `org.json` is out of JVM tests.
- **Android's mirror** (`mirror/DataLayout.kt`): `mirrored = inLayout && !kept`, `stray = !inLayout && !kept`;
  `kept()` (`:78-83`) is a static list; `Mirror.sync` asks for the layout once per run (`Mirror.kt:196`), then
  `store.listAll(layout)` (`SafFolderStore.walk`, `FolderStore.kt:206-222`), then `dropStrays` (deletes stray
  working-copy files that are in step with the manifest). `FolderStore` can't read a file's content. Tests:
  `test/…/mirror/DataLayoutTest.kt`, `MirrorPlannerTest.kt`; instrumented `androidTest/…/mirror/MirrorTest.kt`.
- **Android's prefs:** `storage/FolderPrefs.kt` over a `SharedPreferencesStore` (`AppController.kt:452`).

## Design calls (builder's calls the spec leaves open)

1. **One install sequence, in the core, behind a transport.** `ShaderPacks::Install` does resolve, download to
   `<id>.zip.part`, open and check the zip, remove the previous version, extract, record, for every platform.
   `Transport` has `Get` (a small API answer, into memory) and `Download` (to a file, with a size cap). Desktop's is
   `HTTPDownloader` (the body in memory, then written; a progress wrapper cancels past the cap); Android's `Get` is
   the same and its `Download` streams in Kotlin. The tests drive the whole sequence with a fake transport, so
   dependencies, cancel, offline, rate limits and incomplete installs are tested without the network.
2. **The libretro version comes from `GET /repos/libretro/slang-shaders/branches/master`** (`commit.sha`), not
   `commits/master`, whose answer carries the commit's whole diff.
3. **JSON:** the offline store's reader moves to `src/common/json_reader.{h,cpp}` (and learns fractions and
   exponents, which GitHub could send), shared by the store and the packs. No new dependency.
4. **A marker is written before extraction**, with an empty version and the files the archive will write (from its
   central directory), then rewritten with the version and the files actually written. So a pack killed mid-way (the
   app swiped away) is "Incomplete" and Remove or a reinstall cleans it up.
5. **A file another installed pack's marker also lists is never deleted** by removing or updating a pack.
6. **A second shader root.** `EmuFolders::ShaderPacks` (not saved, not a data folder): desktop's is
   `EmuFolders::Shaders`; Android's is `<filesDir>/shaderpacks`, passed to `initialize`. Listing reads both (once
   when they're the same) and drops a name the shaders folder already has; every lookup tries the shaders folder
   first, then the packs root.
7. **The browser** shows one folder at a time on Big Picture and Android (folders first, then shaders, A opens or
   adds; B clears a search first, then goes up, and closes at the top), and a tree on Qt. A search lists every
   entry whose path contains the text (case-insensitive), with its folder beside it (Qt: the tree, filtered and
   opened). A ReShade shader's path in the browser is `reshade/<name>`. It opens on the last folder used, or its
   nearest folder that still exists.
8. **The packs' pages:** Qt adapts the fork's dialog (a table with check boxes, Install, Remove, Close). Big Picture
   and Android list the four packs with their state, size (the release's asset size before a download; the archive's
   once installed, kept in the marker as `archive_size`), licence and a per-pack action (Install, Update, Remove),
   with the job's step, progress and Cancel while it runs. Versions are checked when the page opens. Android's job
   belongs to the app (`AppController`), so leaving the page doesn't stop it.
9. **Android's mirror** reads `shaders/.shaderpacks/*.json` from the data folder at the start of each run (parsing
   through the core, so JVM tests see a plain set), and treats those files and the markers as strays: never
   mirrored, and copies already in the working copy are dropped when they're in step.

## Review Focus

1. **Two packs writing into one folder** (satpixie and libretro both under `shaders_slang/crt/`): removing or
   updating one leaves every file of the other, and only empty folders are pruned (Task 6's
   `RemovingAPackKeepsFilesAnotherPackLists`, `PruneLeavesFoldersWithOtherFiles`).
2. **The app killed or the computer off mid-extraction:** the pack reads as Incomplete, and Remove deletes what
   landed (Task 7's `TheMarkerBeforeExtractionListsThePlannedFiles`).
3. **Cancel or a dropped connection during the 140 MB download:** the previous version is untouched and no
   `.zip.part` is left (Task 7's `CancelDuringTheDownloadKeepsThePreviousVersion`,
   `AFailedDownloadLeavesNoPartFile`; Task 13's `aCancelledStreamDeletesTheFile`).
4. **Opening the packs page offline or rate-limited:** installed packs still show as installed, "(couldn't check
   for updates)", and Remove still works (Task 6's `StatusWithoutACheck…` tests; checked by hand offline).
5. **The last folder used gone** (its pack removed) **or a name in both roots** on Android: the browser opens on the
   nearest folder that exists; the data folder's file wins (Task 3's `OpenFallsBackToTheNearestFolder`, Task 2's
   `TheShadersFolderWinsOverThePacks`).

---

## Tasks

### Task 1: A shared JSON reader

**Files:**
- Create: `src/common/json_reader.h`, `src/common/json_reader.cpp` (moved from `src/core/achievements_offline_store.cpp:28-304`)
- Modify: `src/core/achievements_offline_store.cpp` (uses it), `src/common/CMakeLists.txt`, `src/common/common.vcxproj(.filters)`
- Test: `src/common-tests/json_reader_tests.cpp` (+ `CMakeLists.txt`)

**Interfaces:**
- Produces (namespace `JsonReader`): `struct Value` (the store's `Json`, renamed: `Type`, `boolean`, `number`
  (s64), `string`, `items`, `names`, `values`, `Find`, `NumberAt`, `StringAt`, `BoolAt`);
  `std::optional<Value> Parse(std::string_view text);` (depth 16); `void AppendString(std::string* out,
  std::string_view value);` (quoted, escaped).

- [ ] **Step 1: Write the failing tests** — `ParsesObjectsArraysStringsBoolsAndNull`; `UnicodeEscapesBecomeUtf8`
  (`"é"` → `é`); `FractionsAndExponentsAreAccepted` (`{"a":1.5,"b":-2e3}` parses; `number` is the whole part,
  1 and −2000); `RejectsTrailingGarbageAndDeepNesting` (`{} x`, 17 nested arrays → nullopt);
  `AppendStringEscapesQuotesBackslashesAndControls`, and a round trip through `Parse`.
- [ ] **Step 2: Run them** (`cmake --build build --target common-tests && build/bin/common-tests
  --gtest_filter='JsonReader.*'`) — fail to compile.
- [ ] **Step 3: Move the reader**, add fractions and exponents to its number rule, switch the store to it.
- [ ] **Step 4: Run** `JsonReader.*` and `AchievementsOfflineStore*` — pass.
- [ ] **Step 5: Commit** — "Common: the offline store's JSON reader, shared".

### Task 2: A second shader root, and the list with kinds

**Files:**
- Modify: `src/core/settings.{h,cpp}` (`EmuFolders::ShaderPacks`: set to `Shaders` in `LoadConfig` and
  `SetDefaults`, never saved), `src/util/postprocessing.{h,cpp}`, `src/util/postprocessing_shader_slang.{h,cpp}`
- Test: `src/common-tests/postprocessing_slang_tests.cpp`

**Interfaces:**
- Produces (namespace `PostProcessing`):
  - `enum class ShaderKind : u8 { GLSL, DolphinFX, ReShade, Slang };`
  - `struct AvailableShader { std::string name; ShaderKind kind; };`
  - `std::vector<AvailableShader> GetAvailableShaders();` — sorted case-insensitively by `GetBrowserPath`; replaces
    `GetAvailableShaderNames()`, which goes.
  - `const char* GetShaderKindName(ShaderKind kind);` — Global Constraints' kind words.
  - `std::string GetBrowserPath(const AvailableShader& shader);` — `reshade/<name>` for ReShade, else the name.
  - `Slang::FindPreset(std::string_view name)` → `std::string` absolute path: `ResolvePresetPath` under
    `EmuFolders::Shaders` if the file exists, else under `EmuFolders::ShaderPacks`, else empty.
- GLSL and ReShade lookups (`TryLoadingShader`) try the shaders folder, then the packs root (when it differs), then
  the resources, as today's order otherwise.

- [ ] **Step 1: Write the failing tests** — `AvailableShadersCarryTheirKinds` (temp shaders folder with `a.glsl`,
  `dolphinfx/crt/b.glsl`, `reshade/Shaders/c.fx`; kinds GLSL, DolphinFX, ReShade; browser paths `a`,
  `dolphinfx/crt/b`, `reshade/c`); `PresetsFromThePacksRootAreListed` (packs root ≠ shaders folder, a preset in
  each → both, once each, kind Slang; skipped without the library); `TheShadersFolderWinsOverThePacks`
  (`x/p.slangp` in both: listed once, `FindPreset` gives the shaders folder's; only in the packs root: the packs
  root's); `ADesktopPacksRootIsTheShadersFolder` (after `LoadConfig`, `ShaderPacks == Shaders`, and
  `GetAvailableShaders` lists a preset once). Rewrite `AvailableShaderNamesListPresetsOnlyWithTheLibrary` against
  `GetAvailableShaders`.
- [ ] **Step 2: Run them** (`--gtest_filter='SlangNames.*:SlangStage.*'`) — fail.
- [ ] **Step 3: Implement**; switch the three callers (Qt menu, Big Picture choice, Android's
  `GetPostProcessingShaders`) to `GetAvailableShaders` with their current display ("{name} [{kind}]") so each still
  builds, until Tasks 8, 10 and 15 replace them.
- [ ] **Step 4: Run** all `common-tests` — pass; `duckstation-qt` builds.
- [ ] **Step 5: Commit** — "Util: shaders with their kinds, from the shaders folder and a packs root".

### Task 3: The browser's folders and search

**Files:**
- Create: `src/util/postprocessing_browser.h`, `src/util/postprocessing_browser.cpp` (+ `CMakeLists.txt`, `util.vcxproj(.filters)`)
- Test: `src/common-tests/postprocessing_browser_tests.cpp`

**Interfaces:**
- Produces (namespace `PostProcessing::Browser`; folders are `/`-separated, `""` is the top):
  - `struct Listing { std::vector<std::string> folders; std::vector<size_t> shaders; };` — child folder names
    (sorted, case-insensitive) and indices into the list, in its order.
  - `Listing List(std::span<const AvailableShader> shaders, std::string_view folder);`
  - `std::vector<size_t> Search(std::span<const AvailableShader> shaders, std::string_view text);` — every shader
    whose browser path contains the trimmed text, ignoring case; empty text gives none.
  - `std::string Parent(std::string_view folder);` — `""` for the top.
  - `std::string FolderOf(std::string_view browser_path);`
  - `std::string LeafName(const AvailableShader& shader);` — the last component, `.slangp` dropped.
  - `std::string OpenFolder(std::span<const AvailableShader> shaders, std::string_view last);` — `last` if it holds
    anything, else its nearest ancestor that does, else `""`.

- [ ] **Step 1: Write the failing tests** — `ListShowsChildFoldersThenShaders` (`a`, `crt/x.slangp`,
  `crt/ntsc/y.slangp`, `reshade/c` at the top: folders `crt`, `reshade`, shader `a`; in `crt`: folder `ntsc`,
  shader `x.slangp`); `SearchMatchesAnywhereInThePathIgnoringCase` ("CRT" finds `crt/x.slangp` and
  `crt/ntsc/y.slangp`, and `a` doesn't; "  " finds none); `ParentAndFolderOf` (`crt/ntsc` → `crt` → `""`);
  `LeafNamesDropThePresetExtension`; `OpenFallsBackToTheNearestFolder` (`crt/gone` → `crt`; `gone/deeper` → `""`).
- [ ] **Step 2: Run them** (`--gtest_filter='ShaderBrowser.*'`) — fail to compile.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — pass.
- [ ] **Step 5: Commit** — "Util: the shader browser's folders and search".

### Task 4: The pack table and version parsing

**Files:**
- Create: `src/core/shader_packs.h`, `src/core/shader_packs.cpp` (+ `CMakeLists.txt`, `core.vcxproj(.filters)`)
- Test: `src/common-tests/shader_packs_tests.cpp` (+ `CMakeLists.txt`)

**Interfaces:**
- Produces (namespace `ShaderPacks`, adapting the fork's):
  - `enum class VersionSource { BranchHead, LatestRelease };`
  - `struct PackInfo { const char* id; const char* name; const char* description; const char* licence; const char*
    repo; VersionSource source; const char* branch; const char* asset_prefix; const char* asset_exclude; u32 strip;
    const char* install_dir; std::span<const char* const> skip; const char* needs; };`
  - `std::span<const PackInfo> GetPacks();`, `const PackInfo* FindPack(std::string_view id);`
  - `struct ResolvedVersion { std::string version; std::string download_url; u64 size = 0; };`
  - `std::string GetVersionUrl(const PackInfo& pack);` — `https://api.github.com/repos/<repo>/branches/<branch>`
    or `…/releases/latest`.
  - `bool ParseBranchJson(std::string_view json, const PackInfo& pack, ResolvedVersion* out, Error* error);` —
    version is the sha's first 12, URL `https://github.com/<repo>/archive/<sha>.zip`.
  - `bool ParseReleaseJson(std::string_view json, const PackInfo& pack, ResolvedVersion* out, Error* error);` —
    `tag_name`, the first matching asset's `browser_download_url` and `size`.
  - `std::string DescribeFailure(bool checking, const PackInfo& pack, s32 status);` — Global Constraints' failure
    words for a status.

- [ ] **Step 1: Write the failing tests** — `TheTableIsTheDesignsFourPacks` (every column of Global Constraints'
  table, each id file-name safe, `needs` names a pack); `VersionUrlsFollowGitHubsApi`;
  `ABranchAnswerGivesTheShortShaAndTheArchiveUrl` (answer shaped `{"name":"master","commit":{"sha":"3685eda5…"}}`);
  `AReleaseAnswerPicksTheMatchingZip` (satpixie: `-variants-` listed first is passed over; size read);
  `LolaRetroTakesOnlyItsOwnZip` (v1.19's four assets → `lola-retro-v1.19.zip`; a release with only the 86Box zip →
  "Release v1.19 has no matching .zip file."); `MalformedAnswersFail` (not JSON, no `tag_name`, no `commit.sha`);
  `FailuresAreDescribed` (0 and −1 offline, −2, −3, 403 and 429 while checking → rate limit, 403 while downloading
  → "Downloading lola-retro failed (HTTP 403).", 404).
- [ ] **Step 2: Run them** (`--gtest_filter='ShaderPacks.*'`) — fail to compile.
- [ ] **Step 3: Implement** (strings through `TRANSLATE_FS("ShaderPacks", …)`).
- [ ] **Step 4: Run** — pass.
- [ ] **Step 5: Commit** — "Core: the shader pack table, and GitHub's version answers".

### Task 5: Archive extraction, hardened

**Files:**
- Create: `src/core/shader_pack_archive.h`, `src/core/shader_pack_archive.cpp` (+ `CMakeLists.txt`, `core.vcxproj(.filters)`)
- Modify: `src/core/CMakeLists.txt` (`minizip` PRIVATE), `src/core/core.vcxproj` (minizip's project reference and
  include dir), `src/common-tests/CMakeLists.txt` (`minizip`, for the tests' writer)
- Test: `src/common-tests/shader_pack_archive_tests.cpp`

**Interfaces:**
- Produces (namespace `ShaderPackArchive`, adapting the fork's):
  - `constexpr u64 MAX_ENTRY_SIZE = 64 MiB, MAX_TOTAL_SIZE = 1 GiB;`
  - `enum class EntryDisposition { Extract, Skip, Reject };`
  - `EntryDisposition TransformEntryName(std::string_view entry, u32 strip, std::span<const char* const> skip,
    std::string* relative, Error* error);` — the fork's rules, then a relative path starting with a `skip` prefix is
    skipped.
  - `bool ListFiles(const std::string& zip_path, u32 strip, std::span<const char* const> skip,
    std::vector<std::string>* out, Error* error);` — what extraction would write (central directory only); fails on
    a file that isn't a zip ("isn't a zip archive" is the caller's words) or a rejected entry.
  - `bool Extract(const std::string& zip_path, const std::string& dest, u32 strip, std::span<const char* const>
    skip, ProgressCallback* progress, std::vector<std::string>* written, Error* error, u64 max_entry =
    MAX_ENTRY_SIZE, u64 max_total = MAX_TOTAL_SIZE);` — through `MinizipHelpers::OpenUnzFile`; per entry, checks
    cancel, the caps (from the archive's sizes, before reading) and that `Path::Canonicalize` stays under `dest`;
    skips symbolic links (Unix mode `S_IFLNK` in `external_fa >> 16`); writes each file through
    `FileSystem::WriteBinaryFile`; `written` always lists what landed, in order; progress range is the entry count.

- [ ] **Step 1: Write the failing tests** (zips built in the test with minizip's `zipOpen64`/`zipOpenNewFileInZip`
  into a temp folder, as the fork's `MakeZip`; no committed binaries) — `TransformEntryNameRules` (the fork's
  cases, plus `lola-retro/hdr/curved/a.slangp` with skip `hdr/` → Skip, `lola-retro/hdrx/a.slangp` → Extract);
  `ExtractStripsSkipsAndRecordsFiles` (Retro Crisis-shaped: `retro crisis/1080p Flat/a.slangp`,
  `__MACOSX/retro crisis/._a.slangp`, `retro crisis/.DS_Store` → one file, listed); `ExtractAbortsOnTraversalAndKeepsWrittenList`;
  `ExtractRejectsAnOversizedEntryAndAnOversizedTotal` (caps 10 and 25 bytes); `SymbolicLinksAreSkipped`;
  `ListFilesMatchesWhatExtractWrites`; `ANonZipFails`; `CancelStopsBetweenEntries` (a progress callback cancelled
  after the first entry → one file written, "Cancelled.").
- [ ] **Step 2: Run them** (`--gtest_filter='ShaderPackArchive.*'`) — fail to compile.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — pass; Android's library still links (`./gradlew assembleDebug`).
- [ ] **Step 5: Commit** — "Core: extracting a shader pack's archive, with path hardening and size caps".

### Task 6: Markers, removal, dependencies and status

**Files:**
- Modify: `src/core/shader_packs.{h,cpp}`
- Test: `src/common-tests/shader_packs_tests.cpp`

**Interfaces:**
- Produces (namespace `ShaderPacks`):
  - `struct InstalledPack { std::string id, version, source_url, installed_at; u64 archive_size = 0;
    std::vector<std::string> files; };` — files relative to the root, `/`.
  - `std::string GetMarkerPath(std::string_view root, std::string_view id);`
  - `std::optional<InstalledPack> ParseMarker(std::string_view json);` — unsafe file paths dropped (empty,
    absolute, `\`, `X:`, `..`); nullopt without `id`, `version` or `files`.
  - `std::string FormatMarker(const InstalledPack& pack);`
  - `std::optional<InstalledPack> ReadMarker(std::string_view root, std::string_view id);`,
    `bool WriteMarker(std::string_view root, const InstalledPack& pack, Error* error);` (atomic rename)
  - `std::vector<std::string> ExpandDependencies(std::string_view root, std::span<const std::string> ids);`
  - `void RemoveFiles(std::string_view root, const InstalledPack& pack);` — deletes the pack's files except those
    another marker under `root` lists, then prunes empty folders under its `install_dir` (deepest first, the folder
    itself last).
  - `bool Uninstall(std::string_view root, std::string_view id, Error* error);`
  - `enum class PackState { NotInstalled, Installed, UpdateAvailable, Incomplete };`
  - `struct PackStatus { PackState state; std::string installed, latest; bool checked = false; };`
  - `PackStatus GetPackStatus(const std::optional<InstalledPack>& installed, const std::optional<ResolvedVersion>&
    latest);`, `std::string DescribePackStatus(const PackStatus& status);` — Global Constraints' state words.

- [ ] **Step 1: Write the failing tests** — `MarkerRoundTrip` (with `archive_size`, an empty version, an empty
  list); `MarkersDropUnsafePaths`; `MalformedMarkersReadAsNotInstalled`;
  `ExpandDependenciesPutsLibretroFirstOnlyWhenMissing` (with and without its marker; a marker whose files were
  deleted still counts; duplicates and unknown ids dropped); `UninstallDeletesListedFilesOnly`;
  `RemovingAPackKeepsFilesAnotherPackLists` (satpixie and libretro markers both listing `shaders_slang/crt/x.slang`);
  `PruneLeavesFoldersWithOtherFiles`; `StatusFollowsMarkerAndCheck` (none, same, newer, empty version → each state
  and its words); `StatusWithoutACheckSaysSo` ("Installed 3685eda59ffd (couldn't check for updates)", and "Not
  installed").
- [ ] **Step 2: Run them** — fail.
- [ ] **Step 3: Implement** (markers through `JsonReader`).
- [ ] **Step 4: Run** — pass.
- [ ] **Step 5: Commit** — "Core: shader pack markers, removal, dependencies and status".

### Task 7: The install sequence, and desktop's transport

**Files:**
- Modify: `src/core/shader_packs.{h,cpp}`
- Test: `src/common-tests/shader_packs_tests.cpp` (a `FakeTransport`)

**Interfaces:**
- Produces (namespace `ShaderPacks`):
  - `class Transport { public: virtual ~Transport(); virtual s32 Get(const std::string& url, std::string* body,
    ProgressCallback* progress) = 0; virtual s32 Download(const std::string& url, const std::string& path, u64
    max_size, ProgressCallback* progress, Error* error) = 0; };` — HTTP status (200) or `HTTPDownloader`'s
    negative codes; `Download` past `max_size` returns −1 with Global Constraints' "larger than 1 GB" text in `error`.
  - `std::unique_ptr<Transport> CreateTransport();` — defined in `shader_packs.cpp` behind `#ifndef __ANDROID__`
    (`HTTPDownloader` with `Host::GetHTTPUserAgent()`, 30 s idle timeout, a `ProgressCallback` wrapper that
    forwards, adds the cap and cancels past it); Android defines its own (Task 13).
  - `std::optional<ResolvedVersion> ResolveLatest(const PackInfo& pack, Transport& transport, ProgressCallback*
    progress, Error* error);`
  - `struct InstallResult { std::string id; bool success = false; bool cancelled = false; std::string message; };`
  - `std::vector<InstallResult> Install(std::string_view root, std::span<const std::string> ids, Transport&
    transport, ProgressCallback* progress);` — design call 1's order; design call 4's early marker; removes a stale
    `<id>.zip.part` first and the new one always; a cancelled pack stops the rest; a pack whose dependency failed
    gets "Needs …, which didn't install.".

- [ ] **Step 1: Write the failing tests** (`FakeTransport`: a map of URL → status and body or file, a log of calls,
  an optional cancel at a URL) — `InstallsDependenciesFirst` (Retro Crisis alone → libretro then Retro Crisis, files
  where the table says, markers with versions and `archive_size`); `AnUpdateRemovesThePreviousFilesAfterTheCheck`
  (old marker lists `shaders_slang/old.slang`; a bad zip → old file kept; a good zip → gone, new files in);
  `CancelDuringTheDownloadKeepsThePreviousVersion`; `AFailedDownloadLeavesNoPartFile` (−1, 404, too large);
  `OfflineAndRateLimitedChecksSayWhy` (status 0 → "Couldn't reach GitHub: check the connection.", 403 →
  rate limit); `ADependencyThatFailsSkipsItsDependent`; `TheMarkerBeforeExtractionListsThePlannedFiles` (a progress
  callback reads the marker on "Extracting …": empty version, every planned file); `AnAbortedExtractionIsIncomplete`
  (a `..` entry → marker with empty version and what landed, result failed); `LolaRetroSkipsHdr`.
- [ ] **Step 2: Run them** — fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** all `common-tests` — pass. **By hand** (a throwaway `ShaderPacks.LiveInstall` behind
  `GOOSESTATION_PACKS_LIVE=1`, not committed): install all four into a temp root on this Mac; file counts match
  the archives (libretro 5,256 less skips), lola-retro has no `hdr/`.
- [ ] **Step 5: Commit** — "Core: installing shader packs, through a transport".

### Task 8: Qt's shader browser

**Files:**
- Create: `src/duckstation-qt/shaderbrowserdialog.{h,cpp,ui}` (+ `CMakeLists.txt`, `duckstation-qt.vcxproj(.filters)`)
- Modify: `src/duckstation-qt/postprocessingsettingswidget.{h,cpp}` (`onAddButtonClicked`)

**Interfaces:**
- Consumes: `GetAvailableShaders`, `GetShaderKindName`, `GetBrowserPath`, `Browser::LeafName`, `Browser::OpenFolder`.
- Produces: `ShaderBrowserDialog(QWidget* parent, std::vector<PostProcessing::AvailableShader> shaders)`;
  `std::optional<std::string> ShaderBrowserDialog::selectedShader() const`. A `QTreeView` (columns Name, Kind) over
  a `QStandardItemModel` (folder nodes per path component, leaves carry the stage name), a recursive
  case-insensitive `QSortFilterProxyModel` on the path role driven by a search `QLineEdit` (expanded while
  filtering), OK only on a shader, Enter or double-click adds. Opens with `[UI] ShaderBrowserFolder`'s folder
  (`OpenFolder`) expanded and scrolled to; the folder of the shader added is written back.

- [ ] **Step 1: Build it** and swap Add's menu for it (the lock, `AddStage` and `commitSettingsUpdate` as today).
  (No Qt test harness here: the logic is Task 3's, tested; this is wiring.)
- [ ] **Step 2: Check by hand** on this Mac: both chains, globally and for a game; "crt" filters and opens;
  the dialog reopens on the last folder; a GLSL, a ReShade and a slang stage each add; Escape adds nothing.
- [ ] **Step 3: Commit** — "Qt: a shader browser for adding a post-processing stage".

### Task 9: Qt's shader pack dialog

**Files:**
- Create: `src/duckstation-qt/shaderpackdialog.{h,cpp,ui}` (+ `CMakeLists.txt`, `.vcxproj(.filters)`)
- Modify: `src/duckstation-qt/postprocessingchainconfigwidget.ui` (a "Download Shader Packs…" button after
  Reload), `postprocessingsettingswidget.cpp` (opens it)

**Interfaces:**
- Consumes: Tasks 4, 6, 7 (`GetPacks`, `ReadMarker`, `GetPackStatus`, `DescribePackStatus`, `ExpandDependencies`,
  `ResolveLatest`, `Install`, `Uninstall`, `CreateTransport`), root `EmuFolders::ShaderPacks`.
- Produces: `ShaderPackDialog(QWidget* parent)`, adapting the fork's `ShaderPackDownloadDialog`: table (pack with
  check box, description, licence, size, status), the note, status line, progress bar (shown only while a job
  runs), Install (Cancel while installing), Remove (asks, with the Retro Crisis line when it applies), Close; a
  `QtAsyncProgressThread` worker for Check, Install and Remove; every way out cancels and joins the worker first
  (`done()` override).

- [ ] **Step 1: Build it.**
- [ ] **Step 2: Check by hand** on this Mac (real downloads): fresh install of all four; reopen shows them
  installed and up to date; Retro Crisis alone after removing both adds libretro; remove satpixie removes only its
  files; cancel mid-download keeps the previous version; offline (Wi-Fi off) shows "couldn't check" and Install
  says why; a downloaded preset of each pack adds through the browser and renders under Metal.
- [ ] **Step 3: Commit** — "Qt: downloading, updating and removing shader packs".

### Task 10: Big Picture's shader browser

**Files:**
- Modify: `src/core/fullscreen_ui.cpp` (Add Shader, `:5024-5056`)

**Interfaces:**
- Consumes: Tasks 2 and 3.
- Produces (file-local): `OpenShaderBrowser(const char* section)`, `DrawShaderBrowser()` (called beside the other
  popups), state `s_shader_browser { bool open; std::string section; std::vector<AvailableShader> shaders;
  std::string folder; std::string search; }`. A popup the size of the file selector's: an inline `ImGui::InputText`
  search row first (A focuses it, starting the host's text input), then a row per child folder (folder icon), then
  a row per shader (leaf name; summary "Slang · shaders_slang/crt"); with search text, the matches instead. A on a
  folder opens it, A on a shader adds it (under `Host::GetSettingsLock()`, toast "Shader {} added as stage {}." or
  the error with its text), saves `[UI] ShaderBrowserFolder`, closes. B clears the search, else goes up, else
  closes (design call 7). Focus resets to the first row after each move. Footer help: "A: Open/Add · B: Back · Up/Down: Move".

- [ ] **Step 1: Build it**; regenerate the translation block
  (`scripts/generate_fullscreen_ui_translation_strings.py`; stage 1's strings join it too).
- [ ] **Step 2: Check by hand** on this Mac with the keyboard and a controller if one's attached: open, go two
  folders down and back, B at the top closes, search "royale" and add one, reopen on its folder; both chains and a
  game's own chain.
- [ ] **Step 3: Commit** — "Big Picture: a shader browser for adding a post-processing stage".

### Task 11: Big Picture's shader packs

**Files:**
- Modify: `src/core/fullscreen_ui.cpp`

**Interfaces:**
- Consumes: Tasks 4, 6, 7.
- Produces (file-local): `OpenShaderPacks()`, `DrawShaderPacks()`, and a job: `struct PackJob { std::mutex lock;
  std::string status; u32 value, range; bool running; std::vector<InstallResult> results;
  std::vector<std::optional<ResolvedVersion>> checks; std::atomic_bool cancel; std::thread thread; }` with a
  `ProgressCallback` subclass writing to it (cancelled from `cancel`). On open, a Check job. A row per pack
  (`MenuButtonWithValue`: name; summary "description · licence · size"; value the state words); A opens a choice of
  the actions that apply (Install / Update / Remove, Remove confirmed with the Retro Crisis line when it applies).
  While a job runs: its step and "{value} of {range}" (MB for a download) under the packs, a "Cancel" row, and the
  pack rows disabled. The note at the top. Closing the popup cancels and joins. "Download Shader Packs" on the
  post-processing page after Add Shader opens it.

- [ ] **Step 1: Build it**, regenerate the translation block.
- [ ] **Step 2: Check by hand** on this Mac (Task 9's list, through Big Picture).
- [ ] **Step 3: Commit** — "Big Picture: downloading, updating and removing shader packs".

### Task 12: Android's packs root, and the list with kinds

**Files:**
- Modify: `android/app/src/cpp/android_host.{h,cpp}` (`Initialize` takes `packs_root`; `LoadFolders` sets
  `EmuFolders::ShaderPacks` to it after `LoadConfig`; `GetPostProcessingShaders` from `GetAvailableShaders`, kinds
  by `GetShaderKindName`; `ShaderKind()` goes), `jni_bridge.cpp` (`initialize`), `core/NativeLibrary.kt`
  (`initialize(…, packsRoot: String, …)`), `app/AppController.kt` (`File(context.filesDir, "shaderpacks")`),
  `settings/PostProcessingSettings.kt` (`ShaderKind.DolphinFX`, label "dolphinfx"), the stale KDocs
  (`NativeLibrary.kt:666-669`, `CoreApi.kt:124`, `android_host.h:105,118`)
- Test: `android/app/src/test/…/settings/PostProcessingSettingsTest.kt`, `androidTest/…/core/PostProcessingAccessTest.kt`

- [ ] **Step 1: Write the failing tests** — JVM `dolphinfxIsAKindOfItsOwn` (`ShaderKind.of("dolphinfx")`);
  instrumented `presetsInTheAppsPacksAreListedAndLoad` (a preset copied under the test app's `filesDir/shaderpacks`
  → listed as Slang, adds without error), `theDataFoldersPresetWinsOverThePacks`, and the dolphinfx case of the
  existing kind test.
- [ ] **Step 2: Run them** (`./gradlew testDebugUnitTest`; the instrumented ones by class) — fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** them, then `run-instrumented-tests.sh quick` — pass.
- [ ] **Step 5: Commit** — "Android: shader packs in the app's own storage, and the dolphinfx kind".

### Task 13: Android's streaming download

**Files:**
- Modify: `core/HttpBridge.kt` (`stream` and `download`), `core/NativeLibrary.kt` (`downloadProgress`),
  `android/app/src/cpp/http_downloader_android.cpp` (or a new `shader_packs_android.cpp`: `ShaderPacks::CreateTransport`)
- Test: `android/app/src/test/…/core/HttpBridgeTest.kt`

**Interfaces:**
- Produces:
  - `internal fun HttpBridge.stream(url: String, file: File, userAgent: String, timeoutMs: Int, maxBytes: Long,
    onProgress: (done: Long, total: Long) -> Boolean, log: (String) -> Unit): Int` — follows redirects (GitHub's
    release downloads redirect), writes `file`, calls `onProgress` at most every 64 KiB (`total` −1 when unknown),
    returns the status; deletes `file` on any failure, cancel (`onProgress` false → `STATUS_CANCELLED` −3) or going
    past `maxBytes` (`STATUS_TOO_LARGE`, mapped to −1 with the cap's text by the native side).
  - `@JvmStatic fun HttpBridge.download(url: String, path: String, userAgent: String, timeoutMs: Int, maxBytes:
    Long, progress: Long): Int` — `stream` with `onProgress = { d, t -> NativeLibrary.downloadProgress(progress, d, t) }`.
  - `external fun NativeLibrary.downloadProgress(progress: Long, done: Long, total: Long): Boolean` — sets the
    `ProgressCallback*`'s range and value; false when it's cancelled.
  - Android's `ShaderPacks::CreateTransport()`: `Get` through `HTTPDownloader` (as desktop); `Download` through
    `HttpBridge.download`, called on the installing thread.

- [ ] **Step 1: Write the failing tests** (the existing local `ServerSocket` style) — `aStreamWritesTheBodyToTheFile`
  (1 MB body, progress reaches 1 MB of 1 MB); `aStreamFollowsARedirect`; `aCancelledStreamDeletesTheFile`;
  `aStreamPastTheCapStopsAndDeletesTheFile`; `aStreamOfAnErrorStatusWritesNothing` (404); `aStalledStreamTimesOut`.
- [ ] **Step 2: Run them** (`./gradlew testDebugUnitTest --tests '*HttpBridgeTest*'`) — fail.
- [ ] **Step 3: Implement** both sides.
- [ ] **Step 4: Run** — pass; `assembleDebug` links.
- [ ] **Step 5: Commit** — "Android: shader pack downloads stream to a file, with progress and cancel".

### Task 14: Android's shader packs page

**Files:**
- Create: `app/ShaderPacks.kt` (the job and its state, owned by `AppController`), `ui/ShaderPacksScreen.kt`,
  `core/ShaderPackInfo.kt` (`ShaderPackInfo`, `ShaderPackCheck`, `ShaderPackResult`, `interface PackProgress`)
- Modify: `android_host.{h,cpp}`, `jni_bridge.cpp`, `NativeLibrary.kt`, `CoreApi.kt` (+ `FakeCoreApi`),
  `app/PostProcessingPage.kt` (`packsOpen`, `openPacks()`, `closePacks()`), `ui/PostProcessingScreen.kt` (a
  "Download shader packs" `NavRow` after the chains; the sub-page)
- Test: `test/…/app/ShaderPacksTest.kt`, `androidTest/…/ui/ShaderPacksScreenTest.kt`, `FocusAuditTest`,
  `OrientationLayoutTest`, `SamplePostProcessing.kt` (+ a sample packs model)

**Interfaces:**
- Produces:
  - JNI (all blocking, never on the core thread): `getShaderPacks(): Array<ShaderPackInfo>` (id, name,
    description, licence, needs, installed version or null, incomplete, installed size);
    `checkShaderPacks(progress: PackProgress): Array<ShaderPackCheck>` (id, version, size, error);
    `installShaderPacks(ids: Array<String>, progress: PackProgress): Array<ShaderPackResult>` (id, ok, cancelled,
    message); `removeShaderPack(id: String): String?`; `describeShaderPack(id, latestVersion?): String` (the state
    words). `interface PackProgress { fun status(text: String); fun progress(value: Long, range: Long); fun
    cancelled(): Boolean }`, bridged by a native `ProgressCallback` subclass.
  - `class ShaderPacks(scope, io: CoroutineDispatcher, api: CoreApi)`: `packs` (rows: info, state words, size),
    `job` (null, or step text, value, range, which pack), `install(id)`, `update(id)`, `remove(id)` (after the
    page's confirm), `cancel()`, `check()` on open; a finished job re-reads the packs and the post-processing page's
    shaders.
  - The page: the note as a `Notice`; a row per pack (name; "description · licence · size"; state) whose A opens an
    `OverlayPanel` of its actions (Install / Update / Remove, Remove asking, with the Retro Crisis line when it
    applies); while a job runs, its step and progress ("Downloading libretro slang shaders… 45 of 140 MB") with a
    Ghost "Cancel" button, and the pack rows disabled saying "Busy with another pack.". B closes the page (the job
    keeps going).

- [ ] **Step 1: Write the failing tests** — JVM `ShaderPacksTest`: `openingChecksOnce`, `installingRetroCrisisSaysLibretroComesFirst`,
  `cancelReachesTheJob`, `aFinishedJobReReadsThePacks`, `anOfflineCheckKeepsInstalledPacksRemovable`; screen test:
  the rows, the actions panel, a running job's progress and Cancel; `FocusAuditTest`: `shaderPacks`,
  `shaderPacksBusy`, `shaderPacksActions`; `OrientationLayoutTest`: `theShaderPacksPage`, `theShaderPacksPageBusy`.
- [ ] **Step 2: Run them** — fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the JVM tests, the screen test, then `run-instrumented-tests.sh quick`. **By hand** on an
  emulator (real downloads): install all four, Retro Crisis alone adds libretro, cancel mid-download, remove, airplane
  mode; nothing appears in the data folder; a pack preset renders.
- [ ] **Step 5: Commit** — "Android: a shader packs page on Post-processing".

### Task 15: Android's shader browser

**Files:**
- Create: `settings/ShaderBrowser.kt` (pure)
- Modify: `app/PostProcessingPage.kt` (`adding` becomes `browsing: Browse?` with chain, folder and search;
  `openAdd`, `browseInto(folder)`, `back()`, `search(text)`, `add`), `ui/PostProcessingScreen.kt` (`BrowsePage`
  replaces `AddShaderPage`), `storage/FolderPrefs.kt` (`shaderBrowserFolder()`, `setShaderBrowserFolder(folder)`)
- Test: `test/…/settings/ShaderBrowserTest.kt`, `test/…/app/PostProcessingPageTest.kt`,
  `androidTest/…/ui/PostProcessingScreenTest.kt`, `FocusAuditTest`, `OrientationLayoutTest` (`thePostProcessingAddPage` becomes the browser's)

**Interfaces:**
- Produces: `object ShaderBrowser { data class Listing(val folders: List<String>, val shaders: List<Shader>); fun
  path(shader: Shader): String; fun list(shaders: List<Shader>, folder: String): Listing; fun search(shaders:
  List<Shader>, text: String): List<Shader>; fun parent(folder: String): String; fun leaf(shader: Shader): String;
  fun open(shaders: List<Shader>, last: String): String }` — Task 3's rules, the same cases.
- The page: a `LazyColumn` (the library's pattern, 4 dp ring margin) whose first item is a search `TextFieldRow`,
  then "Up to {parent}" unless at the top or searching, then folder `NavRow`s, then shader `NavRow`s (leaf name;
  "Slang · shaders_slang/crt"), all from `offered()`. B: design call 7. The title says the folder ("Add to the
  Display chain: shaders_slang/crt"). Focus goes to the first entry on each move, and to the new stage after adding.

- [ ] **Step 1: Write the failing tests** — `ShaderBrowserTest` with Task 3's cases; `PostProcessingPageTest`:
  `theBrowserOpensOnTheLastFolder`, `backClearsTheSearchThenGoesUpThenCloses`, `addingRemembersTheFolder`; screen
  test: folders open with A, search narrows; `FocusAuditTest`: `postProcessingBrowseTop`, `postProcessingBrowseFolder`,
  `postProcessingBrowseSearch`, `postProcessingBrowseNoMatches`; `OrientationLayoutTest`: the browser at the top and
  in a folder.
- [ ] **Step 2: Run them** — fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** them, then `run-instrumented-tests.sh quick`. **By hand** on an emulator with the libretro
  pack: a search with thousands of matches scrolls smoothly; the keyboard opens on A and closes on Done.
- [ ] **Step 5: Commit** — "Android: a shader browser for adding a post-processing stage".

### Task 16: Android's mirror skips desktop's packs

**Files:**
- Modify: `mirror/DataLayout.kt` (`withPackFiles(markers: String, files: Set<String>)`, `markers` being the
  data folder's `<shaders>/.shaderpacks`; `stray` includes everything under `markers` and every path in `files`, and
  neither is mirrored), `mirror/FolderStore.kt`
  (`readSmall(path: String, maxBytes: Int): ByteArray?`, `listFolder(path: String): List<String>`), `mirror/Mirror.kt`
  (before `listAll`: list `markers`, read each `.json` up to 4 MiB, `parseMarker` each, `layout = layout.withPackFiles(…)`), `AppController.kt`
  (`parseMarker = NativeLibrary::shaderPackMarkerFiles`), `jni_bridge.cpp` + `NativeLibrary.kt`
  (`shaderPackMarkerFiles(json: String): Array<String>?` through `ShaderPacks::ParseMarker`, files prefixed with
  the shaders folder's path relative to the data root)
- Test: `test/…/mirror/DataLayoutTest.kt`, `MirrorPlannerTest.kt`, `androidTest/…/mirror/MirrorTest.kt`

- [ ] **Step 1: Write the failing tests** — `packFilesAndMarkersAreNeitherMirroredNorKept` (`shaders/.shaderpacks/shaders_slang.json`,
  a listed `shaders/shaders_slang/crt/a.slangp` → not mirrored, stray; an unlisted `shaders/shaders_slang/mine.slangp`
  → mirrored); planner `listedPackFilesAreNeitherPulledNorPushed`; instrumented
  `aDesktopPackInTheDataFolderIsNotCopied` (marker + files in the test provider's tree → none in the working copy;
  the user's own preset beside them is) and `packFilesAlreadyCopiedAreDropped`; `aMalformedMarkerSkipsNothing`.
- [ ] **Step 2: Run them** — fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** them, then `run-instrumented-tests.sh quick` — pass.
- [ ] **Step 5: Commit** — "Android: the mirror skips the shader packs a desktop installed in the data folder".

### Task 17: Docs and verification

**Files:**
- Modify: `docs/android.md` (Post-processing: the browser, the packs page, where packs live, the mirror rule;
  Known limits: a preset in the data folder can't refer to a pack's files by a relative path),
  `docs/roadmap.md` (item 17: "Stages 1 (#56) and 2 (#NN) done … stage 3 (favourites and hotkeys) to come"),
  `README.md:27` (slang presets and the pack downloads)

- [ ] **Step 1: Write the docs; commit** — "Docs: the shader browser and shader packs".
- [ ] **Step 2: Desktop:** `common-tests` all green (with and without `GOOSESTATION_LIBRASHADER_PATH`);
  `duckstation-qt` builds; Tasks 8–11's hand checks re-run on the final branch.
- [ ] **Step 3: Android:** `./gradlew assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs`;
  `run-native-tests.sh` (the new core suites on the emulator); full tier in the background
  (`GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh full`), the result XML read
  (count, zero failures, nothing skipped that shouldn't be).
- [ ] **Step 4:** CI on the branch (with the user's word: `gh workflow run`), every job green (Windows builds the
  new `.vcxproj` entries; nothing here can).
- [ ] **Step 5:** Push `claude/slang-shaders-stage2`; `superpowers:finishing-a-development-branch`; a PR to
  `master` only on the user's word.
