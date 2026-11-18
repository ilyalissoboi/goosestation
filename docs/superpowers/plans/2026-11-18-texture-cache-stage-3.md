# Hardware texture cache, stage 3 (settings and the user guide) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The texture cache's settings on Qt, Big Picture and Android, an on-screen notice saying whether a game's pack
was found and why it doesn't apply, and the user guide.

**Architecture:** The notice is one pure function in the core (`TextureReplacements::FormatLoadNotice`) fed by what
`Reload()` just indexed and the settings in effect, shown through the core's OSD from `Reload()`, so every frontend
gets it. Each frontend adds rows to the texture replacement section it already has, bound to the existing
`[GPU]`/`[TextureReplacements]` keys; nothing new is read by the core. The guide goes at the top of
`docs/texture-replacement.md`.

**Tech Stack:** C++20 core and Qt 6 widgets (`SettingWidgetBinder`), Big Picture's ImGui (`fullscreen_ui.cpp`),
Kotlin/Compose (`GraphicsSettings`, `GraphicsScreen`, `PageRows`), googletest (`common-tests`), JUnit, Compose
instrumented tests.

**Spec:** `docs/superpowers/specs/2026-11-18-texture-cache-stage-3-design.md` (and its parent,
`docs/superpowers/specs/2026-11-16-texture-cache-design.md`).

## Global Constraints

- Keys and defaults as the spec's table: `[GPU] EnableTextureCache` off; `[TextureReplacements]`
  `EnableTextureReplacements` off, `DumpTextures` off, `DumpReplacedTextures` on, `DumpTextureForceAlphaChannel` off,
  `AlwaysTrackUploads` off, `MaxHashCacheEntries` 1200, `MaxHashCacheVRAMUsageMB` 2048 desktop / 512 Android,
  `MaxReplacementCacheVRAMUsage` 512 desktop / 256 Android. Defaults in UI code come from `Settings` /
  `TexturePackConfig()` (C++) or match the core's Android values (Kotlin), never a second literal on desktop.
- Every new row is per game where the section's rows are (Qt's and Big Picture's per-game dialogs, Android's
  `GameSwitch`/`GameNumber`).
- A row that needs another: greyed out on Qt; hidden on Big Picture and Android until that one is on.
- Not settings anywhere: `config.yaml`'s keys. No importer, pack browser or "open folder" button.
- Clean room: nothing from DuckStation's code after `a5bf1ba5cee7` (its later Qt/Big Picture texture replacement UI
  included); the existing pre-fork rows are ours to extend.
- Android: `docs/design-system.md` rows; quick and full tiers (detached worktree, emusync AAR copied in) and CI's
  Android line (`assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`) before the
  PR; any Thor check only with the user's go-ahead.
- Commit each task, message shown after, ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; no PR
  until the user says so.

## Review Focus

1. **A game's own setting overriding the global** (global cache on, this game off): the dependent rows follow the
   game's effective value, and the notice says "needs the texture cache". Pinned in Task 4
   (`aGamesOwnCacheOffHidesItsRows`) and Task 1 (`ACacheOffPackSaysSo`).
2. **No serial** (a homebrew EXE, the BIOS alone) with replacements on: no notice at all, not "No texture pack found
   in textures/". Pinned in Task 1 (`NoNoticeWithoutAGame`).
3. **An Android data folder the app can't read** (grant gone): "No texture pack found in textures/SLUS-…", the folder
   named as the guide does, never a `content://` URI. Pinned in Task 1 (`AnUnreadablePackFolderSaysNoPack`).
4. **The software renderer on Android**: the cache rows are hidden with the other hardware rows. Pinned in Task 4
   (`softwareHidesTheCacheRows`).
5. **Android's limits show Android's defaults** (512/256), not desktop's. Pinned in Task 3
   (`theCacheLimitsDefaultToAndroidsBudgets`).

---

### Task 1: The notice

**Files:**
- Modify: `src/core/texture_replacements.h`, `src/core/texture_replacements.cpp` (`Reload()` raises it),
  `src/core/system.cpp` (`CheckForSettingsChanges`: a `gpu_renderer` change reloads replacements too, so the notice
  follows a switch to or from Software)
- Test: `src/common-tests/texture_replacements_tests.cpp`

**Interfaces:**
- Produces (namespace `TextureReplacements`):
  ```cpp
  struct LoadNoticeState
  {
    std::string_view game_id;          // empty: no notice
    bool texture_replacements;         // EnableTextureReplacements
    bool vram_write_replacements;      // EnableVRAMWriteReplacements
    bool texture_cache;                // [GPU] EnableTextureCache
    bool hardware_renderer;            // !IsUsingSoftwareRenderer()
    size_t textures;                   // ReplacementIndex::GetTextureCount()
    size_t vram_writes;                // ReplacementIndex::GetVRAMWriteCount()
  };
  std::optional<std::string> FormatLoadNotice(const LoadNoticeState& state);
  std::optional<std::string> GetLoadNotice(); // for the index as last loaded, from g_settings
  ```
- The rules, in order:
  1. no game, or both replacement switches off → none;
  2. what applies (textures: switch, cache and hardware; VRAM writes: switch and hardware) is non-zero →
     "Texture pack: N replacements", "Texture pack: M VRAM write replacements", or both joined with " and "
     ("1 replacement" singular), counting only what applies;
  3. nothing found → "No texture pack found in textures/<serial>";
  4. found but nothing applies, the first reason that fits: textures found, their switch on, cache off → "Texture
     pack found, but it needs the texture cache"; software renderer → "…, but it needs a hardware renderer"; textures
     found, their switch off → "…, but it needs texture replacements on"; VRAM writes found, their switch off → "…,
     but it needs VRAM write replacements on".

- [ ] **Step 1: Write the failing tests** (`TEST(TextureReplacementNotice, …)` on `FormatLoadNotice`, plus two on
  `Reload()` with the file's `ContentPack` fixture):
  `NoNoticeWithReplacementsOff` (nullopt); `NoNoticeWithoutAGame` (game_id "", switches on, 161 textures → nullopt);
  `APackSaysHowManyApply` (161 textures, all on → "Texture pack: 161 replacements");
  `VRAMWritesAreCountedToo` (161 and 3, both on → "Texture pack: 161 replacements and 3 VRAM write replacements");
  `OnlyWhatAppliesIsCounted` (161 and 3, texture switch off → "Texture pack: 3 VRAM write replacements");
  `OneIsSingular` ("Texture pack: 1 replacement"); `NoPackSaysWhere` (0 and 0, game "SLUS-01040" → "No texture pack
  found in textures/SLUS-01040"); `ACacheOffPackSaysSo` (161, cache off → "Texture pack found, but it needs the
  texture cache"); `SoftwareSaysSo` (161, cache on, software → "…needs a hardware renderer"); `ACacheOffAndSoftwareSaysTheCacheFirst`;
  `AFontPackWithOnlyVRAMWritesOnSaysSo` (161 textures, only VRAM switch on → "…needs texture replacements on");
  `VRAMWritesWithOnlyTexturesOnSaysSo` (3 VRAM writes, only texture switch on, cache on → "…needs VRAM write
  replacements on"); `ReloadNoticesThePack` (fixture's pack, switches and cache on → `GetLoadNotice()` names its count);
  `AnUnreadablePackFolderSaysNoPack` (provider refuses → "No texture pack found in textures/SLUS-00000").
- [ ] **Step 2: Run** `build/bin/common-tests --gtest_filter='TextureReplacementNotice*:ContentPack*'`; expect build
  failure (no `FormatLoadNotice`).
- [ ] **Step 3: Implement** `FormatLoadNotice` and `GetLoadNotice` (strings through `TRANSLATE_STR` /
  `TRANSLATE_PLURAL_STR`, context `"TextureReplacements"`), and at the end of `Reload()` show it with
  `Host::AddIconOSDMessage("TextureReplacementsLoad", ICON_FA_IMAGES, *notice, Host::OSD_INFO_DURATION)` when there is
  one (the key replaces a previous notice, so a reload never stacks them); add `g_settings.gpu_renderer !=
  old_settings.gpu_renderer` to the condition before `TextureReplacements::Reload()` in `CheckForSettingsChanges`.
- [ ] **Step 4: Run** the filter, then all of `common-tests` on desktop and with `android/scripts/run-native-tests.sh`;
  expect PASS.
- [ ] **Step 5: Commit** ("Core: a notice says whether a game's texture pack applies").

### Task 2: Qt and Big Picture rows

**Files:**
- Modify: `src/duckstation-qt/graphicssettingswidget.ui` (Texture Replacements tab: a **Texture Cache** group first
  with `enableTextureCache`; the General group gains `enableTextureReplacements` above `vramWriteReplacement`; a
  **Texture Dumping** group with `dumpTextures`, `dumpReplacedTextures`, `dumpTextureForceAlphaChannel`, before VRAM
  Write Dumping; an **Advanced** group last: `maxHashCacheEntries`, `maxHashCacheVRAMUsage`,
  `maxReplacementCacheVRAMUsage` spin boxes (MB suffix on the two memory ones), `alwaysTrackUploads`)
- Modify: `src/duckstation-qt/graphicssettingswidget.{h,cpp}` (bindings, `registerWidgetHelp` for each, and
  `onEnableTextureCacheChanged()` / an extended `onEnableAnyTextureReplacementsChanged()` greying out: texture
  replacements and dumping need the cache; the two dump options need dumping; preload needs either replacement switch)
- Modify: `src/core/fullscreen_ui.cpp` (the "Texture Replacements" heading: rows in the spec's order; dependent rows
  drawn only when `GetEffectiveBoolSetting` says the row they need is on; a "Texture Cache Limits" heading with
  `DrawIntRangeSetting`s for the three limits and `AlwaysTrackUploads`, shown with the cache on; every new string in
  the file's `TRANSLATE_NOOP` list)

TDD skipped (UI binding with no test harness on either frontend; ruled in the ledger). Checked by building, the
regtest's settings round trip, and by eye (Step 3).

- [ ] **Step 1: Implement** both; limits' defaults from `TexturePackConfig()` and their ranges: entries 100–10000,
  hash memory 64–8192 MB, replacement memory 64–8192 MB *(ours)*.
- [ ] **Step 2: Build** `cmake --build build` (Qt and core); expect no warnings in the changed files.
- [ ] **Step 3: Check by eye** (the user, or a screenshot on the Mac): Qt global and a game's properties, Big Picture
  global and per game: each new row toggles its key in `settings.ini` / the game's ini; dependent rows grey out or
  hide; the limits show 2048/512/1200. Write down what was checked.
- [ ] **Step 4: Commit** ("Qt, Big Picture: the texture cache's settings").

### Task 3: Android's settings

**Files:**
- Modify: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/settings/GraphicsSettings.kt`
- Test: `android/app/src/test/java/io/github/ilyalissoboi/goosestation/settings/GraphicsSettingsTest.kt`

**Interfaces:**
- Produces (in `GraphicsSettings`, all in `all`): `TEXTURE_CACHE = BoolSetting("GPU", "EnableTextureCache", false)`,
  `TEXTURE_REPLACEMENTS = BoolSetting("TextureReplacements", "EnableTextureReplacements", false)`,
  `DUMP_TEXTURES = BoolSetting("TextureReplacements", "DumpTextures", false)`,
  `HASH_CACHE_ENTRIES = IntSetting("TextureReplacements", "MaxHashCacheEntries", 1200)`,
  `HASH_CACHE_MEMORY = IntSetting("TextureReplacements", "MaxHashCacheVRAMUsageMB", 512)`,
  `REPLACEMENT_MEMORY = IntSetting("TextureReplacements", "MaxReplacementCacheVRAMUsage", 256)`;
  `CACHE_ENTRIES: NumberScale` (100–10000 by 100, presets 600, 1200, 2400, 4800) and `CACHE_MEGABYTES: NumberScale`
  (64–4096 by 64, presets 128, 256, 512, 1024, 2048; "N MB").

- [ ] **Step 1: Write the failing tests:** `theCacheLimitsDefaultToAndroidsBudgets` (defaults 1200, 512, 256 from an
  empty file); `theCacheSettingsAreSavedUnderTheirKeys` (each new setting's section and key, as
  `everySettingIsSavedUnderItsKey` does); `megabytesShowAsMB` (`CACHE_MEGABYTES` labels 256 as "256 MB").
- [ ] **Step 2: Run** `cd android && ./gradlew testDebugUnitTest --tests '*GraphicsSettingsTest*'`; expect FAIL
  (unresolved references).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same; expect PASS. **Commit** ("Android: the texture cache's settings").

### Task 4: Android's rows

**Files:**
- Modify: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/GraphicsScreen.kt` (the "Texture
  replacement" section: Texture cache, then with it on Texture replacements; VRAM write replacements; Preload with
  either on (its note: "Loads the pack at the game's start, within the replacement memory below."); Dump textures with
  the cache on (note: "To the data folder's textures/<serial>/dumps."); then a "Texture cache limits" section with the
  cache on: replacement memory, hash cache memory, hash cache entries (`GameNumber`). With the Software renderer the
  cache rows and the limits are hidden, as the other hardware rows are)
- Modify: `docs/android.md` (the settings table's Texture replacement row, and a Texture cache limits row)
- Test: `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/ui/GraphicsScreenTest.kt`

**Interfaces:** Consumes Task 3's settings and scales.

- [ ] **Step 1: Write the failing tests:** `noReplacementRowsWithoutTheCache` ("Texture replacements", "Dump
  textures" and "Texture cache limits" don't exist with the cache off); `theCacheShowsItsRows` (cache on: walk to each);
  `preloadingGoesWithTextureReplacements` (cache and texture replacements on, VRAM writes off: Preload shows);
  `aGamesOwnCacheOffHidesItsRows` (global cache on, the game's off: its rows are gone); `softwareHidesTheCacheRows`
  (Software: no "Texture cache"); `theLimitsWriteTheirKeys` (changing each limit writes only its key); the existing
  `eachRowChangesOnlyItsSetting` extended to the new switches.
- [ ] **Step 2: Run** on emulator-5580: `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.ui.GraphicsScreenTest`; read
  the XML; expect the new tests to FAIL.
- [ ] **Step 3: Implement**, then `./gradlew ktlintCheck`.
- [ ] **Step 4: Run** the class again; expect PASS (all of it). **Write** `docs/android.md`'s rows. **Commit**
  ("Android: the texture cache's rows").

### Task 5: The guide

**Files:**
- Modify: `docs/texture-replacement.md` (a guide before "How names and hashes work here"; the intro's "comes with the
  settings, in stage 3" sentence goes), `README.md` (the Features list: a line linking the guide),
  `docs/roadmap.md` (item 20: stage 3 done; nothing left)

Docs only (no test).

- [ ] **Step 1: Write "Using texture packs"** and **"Making a pack"** as the spec's outline says, using the row names
  Tasks 2–4 shipped and the notice strings Task 1 pinned (each notice, what it means, what to do), with the paths for
  desktop and Android, the Android memory limits and preloading within them, and the troubleshooting cases (a ZIP one
  folder too deep, the wrong serial, `config.yml`, the Android data folder's grant). Link "Which names are compatible"
  and the `config.yaml` section below for details.
- [ ] **Step 2: Check each step as written** against the app on desktop (the Vagrant Story font pack from
  `~/work/goose-test-data/packs/vs-font`, with the regtest where a screen isn't needed: `-setting` the keys the guide
  names, and the log shows the notice) and note any step that doesn't hold.
- [ ] **Step 3: Commit** ("Docs: the texture pack guide").

### Task 6: Verification and finishing

- [ ] **Step 1:** `common-tests` on desktop and the emulator; CI's Android line; the quick and full tiers from a
  detached worktree; all PASS (read the XML).
- [ ] **Step 2 (with the user's go-ahead):** the Thor: the `.debug` build beside the release, the font pack in a
  scratch tree as in stage 2, the notice seen and the rows walked with the pad.
- [ ] **Step 3:** whole-branch review (fresh reviewer), one fix pass, push, superpowers:finishing-a-development-branch;
  a PR only when the user says so.
