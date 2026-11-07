<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Slang shader presets, stage 3: favourites and their hotkeys — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A player saves the display chain under a name and applies, renames, reorders and deletes saved chains on
Qt, Big Picture and Android, and steps through them in a running game with Next and Previous favourite hotkeys.

**Architecture:** The core owns everything below the UIs. `src/util/postprocessing_favourites` keeps each favourite
as `<name>.ini` in a new `postprocessing/` folder of the data folder (`EmuFolders::PostProcessing`), holding the
display chain exactly as `settings.ini` does (`[PostProcessing]` and `[PostProcessing/StageN]`), and the list's order
in `settings.ini`'s `[PostProcessingFavourites] Order`. It lists, saves, applies, renames, moves, deletes and steps
favourites against `SettingsInterface`s, so the tests need no running system. The hotkeys pick the layer that holds
the running display chain, step it and reload. Qt gets a dialog, Big Picture a popup, Android a section of the
Post-processing page with a panel; Android's mirror learns the folder.

**Tech Stack:** C++20 core (`src/util`, `src/core`), Qt 6, Dear ImGui (Big Picture), Kotlin/Jetpack Compose over JNI,
gtest (`common-tests`), JUnit and AndroidX tests.

**Spec:** `docs/superpowers/specs/2026-11-07-slang-shaders-design.md` ("Favourites: saved chains", stage 3 of
"Stages"). Reference for UI and on-screen-message patterns only (its model, a list of presets, isn't this one): the
PCSX2 fork's `pcsx2-qt/ShaderFavoritesDialog.*` and `HotkeyCycleShaderPreset` (`pcsx2/GS/GS.cpp:1225`).

## Global Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`. The PCSX2 fork's code may be adapted.
- **A favourite** is one file, `<EmuFolders::PostProcessing>/<name>.ini`, its name the file's name without `.ini`.
  Its content is `Config::CopyChain(<chain's layer>, file, "PostProcessing")` with `[PostProcessing] Enabled = true`:
  `Enabled`, `StageCount`, and each `[PostProcessing/StageN]` (ShaderName and options). Nothing else is read from it.
  The internal chain is never saved or touched.
- **The folder:** `EmuFolders::PostProcessing`, from `[Folders] PostProcessing` (default `postprocessing`, relative to
  the data root, as the other folders), created when the first favourite is saved.
- **The order:** `settings.ini` (the base layer, always), `[PostProcessingFavourites] Order`, a string list of names.
  Listing merges it with the folder: names in `Order` whose file exists, in that order, then files not in it, sorted
  ignoring case. Entries without a file are dropped from the list (and from `Order` at its next write).
- **Names:** trimmed of spaces; refused when empty, when they hold `/ \ : * ? " < > |` or a control character, or
  start with `.`. Two names that differ only in case are the same name (macOS, Windows and SAF folders ignore case);
  a rename that changes only case is allowed.
- **Applying** replaces the display chain of the layer it's given with the favourite's (`CopyChain`), with `Enabled =
  true`. On a game's page it gives the game its own display chain, on every UI. Saving from a game's page saves the
  chain the game runs: its own, or the global one while it uses that.
- **Hotkeys** (category "Graphics"): `NextPostProcessingFavourite` "Next Post-Processing Favourite",
  `PreviousPostProcessingFavourite` "Previous Post-Processing Favourite". On release, in a running game. They write
  the game's file when the game's layer has `[PostProcessing] Enabled` (`Config::HasOwnChain`), else `settings.ini`.
  The current favourite is the last one a hotkey applied while the chain still equals it, else the first whose chain
  equals the running one (stages and options; `Enabled` ignored), else none: Next then goes to the first, Previous to
  the last. Both wrap.
- **Copy, exact** (core strings through `TRANSLATE_FS("PostProcessing", …)`, Big Picture `FSUI_*`, Qt `tr()`):
  - On-screen (keyed `"PostProcessingFavourite"`, `ICON_FA_PAINT_ROLLER`, `Host::OSD_QUICK_DURATION`):
    "Post-processing: {name}."; "No post-processing favourites are saved."
  - Errors: "A favourite needs a name."; "A favourite's name can't start with a dot or have / \ : * ? \" < > | in
    it."; "A favourite named {name} already exists."; "There's no favourite named {name}."; "Couldn't write {path}:
    {error}"; "Couldn't rename {name}: {error}"; "Couldn't delete {name}: {error}".
  - Questions: apply over stages "Apply {name}? It replaces the {n} shaders in the chain." (1: "the shader");
    replace on save "A favourite named {name} already exists. Replace it?"; delete "Delete the favourite {name}?"
  - A favourite's summary: its shaders joined with " · "; with one not installed here, "Not installed: {a}, {b}"
    instead. An empty list: "No favourites yet: save the current chain as one."
  - Entry points: Qt "&Favourites…" (Display tab only); Big Picture "Favourites" (summary "Saves the chain under a
    name, or applies a saved one."), popup title "Post-Processing Favourites"; Android section "Favourites", rows
    "1. <name>", "Save as favourite". Buttons: Qt "Apply", "Save Current…", "Rename…", "Move Up", "Move Down",
    "Delete"; Big Picture "Save Current Chain", "Apply", "Rename", "Move Up", "Move Down", "Delete"; Android "Apply",
    "Rename…", "Move up", "Move down", "Delete", the name form's "Name", "Save", "Rename", "Cancel".
- Favourites show only with the Display chain (Big Picture's Chain choice and Android's Chain switch on Display).
- Android: `docs/design-system.md` binding; new panels in `FocusAuditTest` and `OrientationLayoutTest`; no window
  dialogs; emulators only; the Thor only with the user's go-ahead, and no scratch memory cards in its data folder.
- `gh` as ilyalissoboi (`GH_TOKEN=$(gh auth token --user ilyalissoboi)`), never the GitHub MCP; push with a
  credential helper giving username ilyalissoboi and that token. Branch `claude/slang-shaders-stage3` from `master`
  e1e3d4cd1. Commit each task when it's done and show its message after; messages end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. New sources go into the `.vcxproj`/`.filters` files too;
  new test files are CMake-only, as stages 1 and 2's are.

## Found in the code

- **Chains** (`src/util/postprocessing.{h,cpp}`): `Config::CopyChain(from, to, section)` deletes `to`'s chain and
  copies `Enabled`, `StageCount` and every stage section; `DeleteChain` removes them; stage sections are
  `GetStageConfigSection(section, i)` (`PostProcessing/Stage1`…). The running chain reads the game layer when it
  `ContainsValue("PostProcessing", "Enabled")`, else the base layer (`GetLoadSettingsInterface`, `:872-882`).
  `TryLoadingShader` (static, `:790-842`) looks a name up as a slang preset (`Slang::FindPreset`), then ReShade and
  GLSL in each user root (`GetUserShaderRoots()`), then the resources.
- **Layers** (`src/core/system.cpp:1395-1442`): the game layer is `s_game_settings_interface`, an
  `INISettingsInterface` of `GetGameSettingsPath(serial)` (on Android the serial is `s_game_settings_serial` when
  set, and the layer is adapted in memory and must never be saved). `ReloadGameSettings` reloads it and applies;
  `ApplySettings` → `CheckForSettingsChanges` → `PostProcessing::UpdateSettings()` rebuilds changed stages. Base
  edits go through `Host::Internal::GetBaseSettingsLayer()` under `Host::GetSettingsLock()`, then
  `Host::CommitBaseSettingChanges()` (Android's saves `s_base_settings_interface`, `android_host.cpp:3253`).
- **Hotkeys** (`src/core/hotkeys.cpp:419-436`): `DEFINE_HOTKEY(name, category, display, fn)`, post-processing ones
  under "Graphics". Android offers every core hotkey as a Select chord except `LEFT_OUT_HOTKEYS`
  (`android_host.cpp:140-142`, `GetOfferedHotkeys` `:2988-3006`), so the new ones join with no Android code.
- **Folders** (`src/core/settings.{h,cpp}:1821-1910`): each `EmuFolders::X` has a default in `SetDefaults`, a
  `[Folders]` key in `LoadConfig`/`Save`. Android's mirror takes the core's folders from `AndroidHost::GetDataFolders`
  (`android_host.cpp:1312-1330`) plus `DataLayout`'s defaults (`mirror/DataLayout.kt` `FOLDERS`).
- **Qt** (`src/duckstation-qt/postprocessingsettingswidget.cpp`): one `PostProcessingChainConfigWidget` per chain
  tab; edits take the settings lock on `getSettingsInterfaceToUpdate()` (the game's settings in a game's window),
  then `commitSettingsUpdate()`. The top row of `postprocessingchainconfigwidget.ui` holds Enable, Reload and
  Download Shader Packs. `ShaderPackDialog` is the dialog pattern.
- **Big Picture** (`src/core/fullscreen_ui.cpp:5627-5740`): the page edits `GetEditingSettingsInterface()` (drawn
  under the settings lock), marks changes with `SetSettingsChanged(bsi)`, returns early with "This game uses the
  global chain." on a game using the global chain. The shader packs popup (`OpenShaderPacks`/`DrawShaderPacks`,
  `:5368-5600`, a `View` of a list and a selected item, drawn from `:931`) is the popup pattern;
  `OpenInputStringDialog` asks for text (refuses empty), `OpenConfirmMessageDialog` asks yes or no. Strings are
  regenerated by `scripts/generate_fullscreen_ui_translation_strings.py`.
- **Android** (`app/PostProcessingPage.kt`, `ui/PostProcessingScreen.kt`): the model edits through `CoreApi` on the
  `core` dispatcher (`edit { }`, then `readChains`), one chain shown at a time (`shown`), questions as
  `OverlayPanel`s (`UseGlobalChainPanel`), packs as `ShaderPacksPanel`. The native side (`android_host.cpp:946-1063`)
  runs edits through `EditChainLayer(key, …)` (settings.ini under the lock and saved, or the game's file, loaded
  fresh and saved). The memory card editor's save panel and rename form (`docs/design-system.md:394-404`) are the
  panel pattern: actions first, questions take the panel's place, a `TextFieldRow` and the broken rule as a `Danger`
  notice.

## Design calls (builder's calls the spec leaves open)

1. **The order key** is `[PostProcessingFavourites] Order` in `settings.ini` (a string list), apart from the chain's
   own section so `CopyChain`/`DeleteChain` never touch it.
2. **The name is the file's name**, so a copied file is a favourite under its own name, and a rename is a file
   rename. No name is stored inside the file.
3. **Saving under a taken name asks to replace it**, keeping its place in the order.
4. **No "current favourite" key**: the hotkeys find the current one by comparing chains, with the last one applied
   remembered in memory so two identical favourites still both come round.
5. **The hotkey loads the game's file fresh** (`System::GetGameSettingsLayerPath()`), writes it and reloads the game's
   settings, rather than saving the in-memory layer, which Android adapts and must never save.
6. **`[Folders] PostProcessing`** is configurable as every other folder is, with no folder setting in any UI.

## Review Focus

1. **Names that differ only in case** ("CRT" beside "crt"): saving asks to replace, renaming to another's name is
   refused, a case-only rename works, on every filesystem — tested in Task 2.
2. **A hand-copied or hand-deleted file**: a file not in `Order` is listed after the ordered ones; an `Order` name
   with no file is dropped; a file with no `[PostProcessing]` lists with no shaders and applies as an empty chain —
   tested in Task 2 and Task 3.
3. **A chain edited after a favourite was applied** (no favourite equals it): Next goes to the first, Previous to the
   last; and two identical favourites both come round — tested in Task 3.
4. **The game's other settings survive the hotkey**: only the display chain's keys of the game's file change, and the
   internal chain is untouched — tested in Task 3 (`StepChain` on a file holding other sections).
5. **Applying a shorter chain over a longer one** leaves no stale `[PostProcessing/StageN]` behind — tested in Task 3.

---

### Task 1: The folder, own chains and installed shaders

**Files:**
- Modify: `src/core/settings.h`, `src/core/settings.cpp` (`EmuFolders::PostProcessing`)
- Modify: `src/util/postprocessing.h`, `src/util/postprocessing.cpp`
- Test: `src/common-tests/postprocessing_config_tests.cpp`

**Interfaces:**
- Produces: `extern std::string EmuFolders::PostProcessing;` (default `<DataRoot>/postprocessing`, `[Folders]
  PostProcessing`); `bool PostProcessing::Config::HasOwnChain(const SettingsInterface& si, const char* section)`
  (`ContainsValue(section, "Enabled")`, now used by `GetLoadSettingsInterface`); `bool
  PostProcessing::IsShaderInstalled(const std::string& name)` (the files `TryLoadingShader` would open, by existence
  only, in its order; for a slang name, `!Slang::FindPreset(name).empty()`, whether or not librashader loads).

- [ ] **Step 1: Write the failing tests** in `postprocessing_config_tests.cpp`:
  `HasOwnChainIsEnabledBeingSet` (empty → false; `Enabled = false` → true; only `StageCount` → false);
  `IsShaderInstalledFindsUserGlsl` (a `TempDirectory` as `EmuFolders::Shaders`, `crt/a.glsl` written → `"crt/a"`
  true, `"crt/b"` false), `IsShaderInstalledFindsSlangPreset` (`x/p.slangp` written → `"x/p.slangp"` true,
  `"x/q.slangp"` false), restoring `EmuFolders::Shaders` after each.
- [ ] **Step 2: Run** `cmake --build build --target common-tests && build/bin/common-tests
  --gtest_filter='PostProcessingConfig.*'`. Expected: fails to compile (`HasOwnChain`, `IsShaderInstalled`).
- [ ] **Step 3: Implement** the three in the files above (`SetDefaults`, `LoadConfig`, `Save` for the folder).
- [ ] **Step 4: Run** the same command. Expected: PASS; then the whole `common-tests`, all pass.
- [ ] **Step 5: Commit** "Core: a post-processing favourites folder; a chain's own layer and installed shaders".

### Task 2: Favourites as files, and their order

**Files:**
- Create: `src/util/postprocessing_favourites.h`, `src/util/postprocessing_favourites.cpp` (+ `src/util/CMakeLists.txt`,
  `util.vcxproj`, `util.vcxproj.filters`)
- Test: `src/common-tests/postprocessing_favourites_tests.cpp` (+ `src/common-tests/CMakeLists.txt`)

**Interfaces:**
- Consumes: `IsShaderInstalled`, `Config::CopyChain`, `Config::GetStageCount`, `Config::GetStageShaderName`.
- Produces, in `namespace PostProcessing::Favourites`:
  - `static constexpr const char* ORDER_SECTION = "PostProcessingFavourites"; ORDER_KEY = "Order";`
  - `struct Info { std::string name; std::vector<std::string> shaders; std::vector<std::string> missing; };`
  - `bool CheckName(std::string_view name, std::string* trimmed, Error* error);`
  - `std::vector<Info> List(const std::string& folder, const SettingsInterface& order);`
  - `std::optional<std::string> Find(const std::string& folder, const SettingsInterface& order, std::string_view
    name);` (the listed name equal ignoring case)
  - `bool Save(const std::string& folder, SettingsInterface& order, std::string_view name, const SettingsInterface&
    chain, bool replace, Error* error);` (from `chain`'s display chain; a new name goes last; replacing keeps its
    place and its file's case)
  - `bool Rename(const std::string& folder, SettingsInterface& order, std::string_view from, std::string_view to,
    Error* error);`
  - `void Move(const std::string& folder, SettingsInterface& order, std::string_view name, bool down);` (no-op at
    either end)
  - `bool Delete(const std::string& folder, SettingsInterface& order, std::string_view name, Error* error);`
  - `std::string Summary(const Info& info);` (the copy in Global Constraints)

- [ ] **Step 1: Write the failing tests** (a `TempDirectory` folder, a `MemorySettingsInterface` order and chain
  with two stages and an option): `SaveWritesTheChainAsSettingsDoes` (the file's `StageCount` 2, both
  `ShaderName`s, the option, `Enabled` true, and no other section); `SaveFromADisabledChainEnablesIt`;
  `SaveRefusesBadNames` (`""`, `"  "`, `"a/b"`, `".x"`, `"a:b"` each false with its message; `"  CRT  "` saves as
  `"CRT"`); `SaveRefusesATakenNameIgnoringCase` (`"CRT"` then `"crt"` with `replace=false` → false, "A favourite
  named crt already exists."); `ReplaceKeepsPlaceAndCase` (`"A"`, `"B"`, then `"a"` with `replace=true` → order
  `A, B`, file `A.ini` with the new chain); `ListFollowsOrderThenUnorderedFiles` (Order `B, A`, files `A`, `B`,
  `c`, `D` → `B, A, c, D`); `ListDropsOrderNamesWithoutFiles`; `ListSaysWhichShadersAreMissing` (one stage's GLSL
  in a temp shaders folder, one not → `missing` is the other; `Summary` is "Not installed: <it>"; with none missing,
  the shaders joined " · "); `ListOfAFileWithoutAChainHasNoShaders`; `RenameMovesFileAndKeepsPlace`;
  `RenameRefusesAnotherNamesCase` (`"A"`, `"B"`, rename `B` → `a` false); `RenameChangesCaseOnly` (`"crt"` →
  `"CRT"` true, listed `CRT`); `MoveSwapsAndStopsAtEnds`; `DeleteRemovesFileAndOrderEntry`;
  `DeleteOfAMissingNameFails` ("There's no favourite named x.").
- [ ] **Step 2: Run** `cmake --build build --target common-tests && build/bin/common-tests
  --gtest_filter='PostProcessingFavourites.*'`. Expected: fails to compile.
- [ ] **Step 3: Implement** in `postprocessing_favourites.cpp`. Files through `INISettingsInterface` (load, then
  `Save(&error)`); `FileSystem::FindFiles(folder, "*.ini", FILESYSTEM_FIND_FILES)` for the listing; a case-only
  rename goes through a temporary name, as case-insensitive filesystems need. Every write of the order writes the
  whole merged list with `SetStringList`.
- [ ] **Step 4: Run** the same command. Expected: PASS; the whole `common-tests` passes.
- [ ] **Step 5: Commit** "Core: post-processing favourites as files in the data folder, in settings.ini's order".

### Task 3: Applying and stepping favourites

**Files:**
- Modify: `src/util/postprocessing_favourites.h`, `src/util/postprocessing_favourites.cpp`
- Test: `src/common-tests/postprocessing_favourites_tests.cpp`

**Interfaces:**
- Consumes: Task 2's names, `Config::HasOwnChain`.
- Produces:
  - `bool Apply(const std::string& folder, std::string_view name, SettingsInterface& to, Error* error);`
  - `bool ChainsEqual(const SettingsInterface& a, const SettingsInterface& b);` (display chains: `StageCount` and
    each stage section's keys and values, ignoring order of keys and `Enabled`)
  - `std::optional<std::string> Step(const std::string& folder, const SettingsInterface& order, const
    SettingsInterface& chain, std::string_view last, bool forward);` (the rule in Global Constraints; nullopt with
    no favourites)
  - `std::optional<std::string> StepChain(const std::string& folder, SettingsInterface& base, const SettingsInterface*
    game_layer, const std::string& game_file, std::string* last, bool forward, Error* error);` — picks the layer
    (`game_layer && HasOwnChain(*game_layer, "PostProcessing")` → the game's), steps from that layer's chain, applies
    into `base` or into `game_file` (loaded fresh, saved), sets `*last`; nullopt with no favourites (error empty) or
    on a failed write (error set). The caller holds the settings lock.

- [ ] **Step 1: Write the failing tests:** `ApplyReplacesTheChainAndEnablesIt` (a 3-stage disabled chain, a 1-stage
  favourite → `StageCount` 1, no `PostProcessing/Stage2` or `Stage3` section left, `Enabled` true, the
  `InternalPostProcessing` chain and an `[Main]` key untouched); `ApplyOfAMissingNameFails`;
  `ApplyKeepsAMissingShader` (its `ShaderName` copied as is); `ChainsEqualIgnoresEnabledAndKeyOrder`;
  `ChainsEqualSeesAnOption`; `StepWrapsBothWays` (A, B, C; chain = B → next C, previous A; chain = C → next A; chain
  = A → previous C); `StepFromNoMatchGoesToEnds` (next A, previous C); `StepWithNoFavouritesIsNullopt`;
  `StepComesRoundIdenticalFavourites` (A = B ≠ C: from chain A with last "A" → B; with last "B" → C);
  `StepChainWritesTheBaseWhenTheGameUsesTheGlobalChain` (game layer without `Enabled`: base gets the favourite, the
  game file stays as it was); `StepChainWritesTheGameFileWhenItHasItsOwnChain` (game file on disk with `[Main]
  Foo = 1`, `[PostProcessing] Enabled = false` and a stage; its layer the same → the file gets the favourite and
  keeps `[Main] Foo`, base unchanged, `*last` set); `StepChainWithNoFavourites` (nullopt, error empty, nothing
  written).
- [ ] **Step 2: Run** the Task 2 command. Expected: fails to compile.
- [ ] **Step 3: Implement** the four functions.
- [ ] **Step 4: Run** the same command. Expected: PASS; the whole `common-tests` passes.
- [ ] **Step 5: Commit** "Core: apply a post-processing favourite, and step through them from the running chain".

### Task 4: Next and previous favourite hotkeys

**Files:**
- Modify: `src/core/hotkeys.cpp`, `src/core/system.h`, `src/core/system.cpp`

**Interfaces:**
- Consumes: `StepChain`, `EmuFolders::PostProcessing`.
- Produces: `std::string System::GetGameSettingsLayerPath();` (the game layer's file name, empty without one; CPU
  thread); hotkeys `NextPostProcessingFavourite`, `PreviousPostProcessingFavourite`.

- [ ] **Step 1: Implement** `HotkeyStepPostProcessingFavourite(bool forward)` in `hotkeys.cpp`: under the settings
  lock, `StepChain(EmuFolders::PostProcessing, *GetBaseSettingsLayer(), GetGameSettingsLayer(),
  System::GetGameSettingsLayerPath(), &s_last_favourite, forward, &error)`; after unlocking, the game's file written →
  `System::ReloadGameSettings(false)`, the base → `Host::CommitBaseSettingChanges()` then `System::ApplySettings(false)`;
  then the on-screen message (the name, "No post-processing favourites are saved.", or the error). This glue has no
  unit test: `StepChain` carries the logic, and Step 3 checks it by hand.
- [ ] **Step 2: Run** `cmake --build build --target common-tests duckstation-qt` (Expected: builds) and the whole
  `common-tests` (Expected: all pass).
- [ ] **Step 3: Check by hand** in the Qt build with a test game or the BIOS: two favourites saved, the hotkeys bound
  → each press names the next one and the picture changes; a game with its own chain → its
  `gamesettings/<serial>.ini` changes, `settings.ini`'s chain doesn't; none saved → "No post-processing favourites
  are saved." (This needs Task 5's dialog or hand-written files; with files written by hand it can run now.)
- [ ] **Step 4: Commit** "Core: next and previous post-processing favourite hotkeys".

### Task 5: Qt's favourites dialog

**Files:**
- Create: `src/duckstation-qt/postprocessingfavouritesdialog.{h,cpp,ui}` (+ `CMakeLists.txt`, `duckstation-qt.vcxproj`,
  `.filters`)
- Modify: `src/duckstation-qt/postprocessingchainconfigwidget.ui` (a `favourites` button after `downloadPacks`),
  `postprocessingsettingswidget.{h,cpp}`

**Interfaces:**
- Consumes: Task 2 and 3's functions. The dialog takes the chain widget's layer through two callbacks: one that reads
  the chain the window's settings run (`const SettingsInterface&` under the lock: in a game's window, its settings
  when `HasOwnChain`, else the base layer) and one that applies to the window's settings and commits
  (`commitSettingsUpdate()`, then the chain list refreshes).

- [ ] **Step 1: Implement** the dialog: a `QListWidget` of favourites (each its name, the summary as its tooltip and
  second line, a missing one in the disabled text colour), and Apply, Save Current…, Rename…, Move Up, Move Down,
  Delete and Close (`QDialogButtonBox`). Names through `QInputDialog::getText`; questions through
  `QMessageBox::question` with the exact copy; failures through `QMessageBox::critical` with the core's text. The
  button shows on the Display tab only. The order is the base layer, under the lock, then
  `Host::CommitBaseSettingChanges()`.
- [ ] **Step 2: Run** `cmake --build build --target duckstation-qt`. Expected: builds without warnings in the new
  files.
- [ ] **Step 3: Check by hand** with the offscreen harness from stage 2 (`QT_QPA_PLATFORM=offscreen`, a scratch data
  folder): save, replace, rename (case only too), move, delete, apply over stages; `postprocessing/*.ini` and
  `settings.ini`'s `Order` as expected; and a game's settings window applies into the game's file.
- [ ] **Step 4: Commit** "Qt: post-processing favourites: save, apply, rename, reorder and delete".

### Task 6: Big Picture's favourites popup

**Files:**
- Modify: `src/core/fullscreen_ui.cpp` (and its regenerated `FSUI_` strings block)

**Interfaces:**
- Consumes: Task 2 and 3's functions; `GetEditingSettingsInterface()`, `SetSettingsChanged()`,
  `PopulatePostProcessingChain()`.
- Produces: `OpenPostProcessingFavourites()`, `ClosePostProcessingFavourites()`, `DrawPostProcessingFavourites()`, a
  `PostProcessingFavouritesState { bool open; enum class View { List, Favourite } view; std::string selected;
  std::vector<PostProcessing::Favourites::Info> favourites; }`, read when it opens and after each change.

- [ ] **Step 1: Implement** the popup as the shader packs popup is framed: the List view's "Save Current Chain"
  (`OpenInputStringDialog`, then the replace question when the name is taken), then each favourite
  (`MenuButtonWithValue`: name, summary), or the empty-list line; A on one opens its view: Apply (the apply question
  when the chain has stages), Rename, Move Up, Move Down, Delete (asks). B goes back a view, then closes;
  focus resets as the packs popup's does. The "Favourites" button sits in Operations with the Display chain shown,
  above the early return for a game using the global chain (applying gives it its own). Run
  `scripts/generate_fullscreen_ui_translation_strings.py`.
- [ ] **Step 2: Run** `cmake --build build --target duckstation-qt common-tests` and the whole `common-tests`.
  Expected: builds; all pass.
- [ ] **Step 3: Check by hand** in Big Picture (`-bigpicture`, keyboard as the pad) as far as this Mac allows:
  every row reachable, each question asked, the files and order as in Task 5's check.
- [ ] **Step 4: Commit** "Big Picture: post-processing favourites".

### Task 7: Android's mirror learns `postprocessing/`

**Files:**
- Modify: `android/app/src/cpp/android_host.cpp` (`GetDataFolders` lists `EmuFolders::PostProcessing`),
  `android/app/src/main/java/…/mirror/DataLayout.kt` (`FOLDERS` gains `postprocessing`)
- Test: `android/app/src/test/java/…/mirror/DataLayoutTest.kt`

- [ ] **Step 1: Write the failing test** `favouritesAreMirrored`: in `DataLayout.DEFAULT`,
  `mirrored("postprocessing/CRT royale + border.ini")` is true and `stray(…)` false; `others` of a top-level
  `postprocessing` folder is empty.
- [ ] **Step 2: Run** `cd android && ./gradlew testDebugUnitTest --tests '*DataLayoutTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** both changes.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** "Android: the mirror copies the post-processing favourites folder".

### Task 8: Android's native favourites

**Files:**
- Modify: `android/app/src/cpp/android_host.{h,cpp}`, `android/app/src/cpp/jni_bridge.cpp`,
  `android/app/src/main/java/…/core/NativeLibrary.kt`, `core/PostProcessingInfo.kt` (`FavouriteInfo`),
  `app/CoreApi.kt`
- Test: `android/app/src/androidTest/java/…/core/PostProcessingAccessTest.kt`

**Interfaces:**
- Consumes: Task 2 and 3's functions; `EditChainLayer`, `ReadChainLayer`.
- Produces, on `NativeLibrary` and `CoreApi` (each `""` on success, else the core's message; `key` null for
  settings.ini): `getPostProcessingFavourites(): Array<FavouriteInfo>?` (`FavouriteInfo(name: String, shaders:
  Array<String>, missing: Array<String>)`), `savePostProcessingFavourite(key: String?, name: String, replace:
  Boolean): String` (from the chain the key's game runs: its own, else settings.ini's),
  `applyPostProcessingFavourite(key: String?, name: String): String`, `renamePostProcessingFavourite(from: String,
  to: String): String`, `movePostProcessingFavourite(name: String, down: Boolean)`,
  `deletePostProcessingFavourite(name: String): String`. The order is `s_base_settings_interface` under the settings
  lock, saved after each change.

- [ ] **Step 1: Write the failing instrumented test** `favouritesRoundTrip` in `PostProcessingAccessTest` (its
  scratch data folder): add a GLSL stage to settings.ini's display chain, save "A", save "a" without replace (the
  "already exists" message), save "B", move B up, rename A → C, list → `B, C` with the stage; clear the chain, apply
  C → the chain has the stage; a game key with no file: apply B → the game's file holds the chain and its `Enabled`;
  delete B → list `C`; `postprocessing/C.ini` exists in the data folder.
- [ ] **Step 2: Run** `android/run-instrumented-tests.sh quick` filtered to the class (as `docs/android.md` says).
  Expected: fails to compile.
- [ ] **Step 3: Implement** the AndroidHost functions, the JNI bridge and the Kotlin declarations.
- [ ] **Step 4: Run** the same. Expected: PASS. Then `./gradlew assembleDebug testDebugUnitTest ktlintCheck
  checkNoWindowDialogs`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** "Android: post-processing favourites through the core".

### Task 9: Android's favourites model

**Files:**
- Modify: `android/app/src/main/java/…/app/PostProcessingPage.kt`
- Test: `android/app/src/test/java/…/app/PostProcessingPageTest.kt` (its fake `CoreApi` gains the favourites)

**Interfaces:**
- Consumes: Task 8's `CoreApi` functions.
- Produces on `PostProcessingModel`: `val favourites: List<FavouriteInfo>` (read with the chains);
  `val favourite: String?` (whose panel shows); `val naming: Naming?` (`data class Naming(val renaming: String?,
  val error: String)`: the name form, saving when `renaming` is null); `val favouriteQuestion: FavouriteQuestion?`
  (`sealed interface`: `Apply(name, stages)`, `Replace(name)`, `Delete(name)`); `val focusFavourite: Int?` (a row,
  or one past the last, "Save as favourite"); `fun openFavourite(name)`, `closeFavourite()`, `startSaving()`,
  `startRenaming(name)`, `submitName(text)`, `apply(name)`, `moveFavourite(name, down)`, `deleteFavourite(name)`,
  `confirmFavourite()`, `cancelFavourite()`.

- [ ] **Step 1: Write the failing JVM tests:** `applyOverStagesAsksFirst` (2 stages → `Apply(name, 2)`, confirm →
  the fake saw `apply(key, name)`, chains read again); `applyOverAnEmptyChainDoesntAsk`; `savingATakenNameAsksToReplace`
  (case ignored; confirm → `save(replace = true)`); `aRefusedNameStaysInTheForm` (the fake's message as
  `naming.error`); `renameMovesTheFocusWithIt`; `deleteAsksThenFocusesTheNextRow`; `moveKeepsThePanelOnIt`;
  `favouritesReadWithTheChains`; `aGamePageAppliesToTheGame` (`key` passed through).
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*PostProcessingPageTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** in `PostProcessingPage`, each change through `edit { }` so the favourites and chains are
  read again after it.
- [ ] **Step 4: Run** the same. Expected: PASS.
- [ ] **Step 5: Commit** "Android: the Post-processing page's favourites model".

### Task 10: Android's favourites on the page

**Files:**
- Modify: `android/app/src/main/java/…/ui/PostProcessingScreen.kt`
- Test: `android/app/src/androidTest/java/…/ui/PostProcessingScreenTest.kt`, `FocusAuditTest.kt`,
  `OrientationLayoutTest.kt`

- [ ] **Step 1: Write the failing instrumented tests** in `PostProcessingScreenTest`: `favouritesShowWithTheDisplayChain`
  (the section and rows on Display, absent on Internal); `aFavouriteOpensItsPanelWithApplyFocused`;
  `savingAsksANameAndShowsARefusal` (the form's Danger notice); `deleteAsksWithCancelFocused`;
  `focusComesBackToTheRowAfterThePanelCloses`; `aMissingShaderSaysWhich` ("Not installed: …"). `FocusAuditTest` and
  `OrientationLayoutTest` gain the favourite's panel and the name form, with a long name ("CRT royale + border +
  scanlines + halation, for the handheld screen").
- [ ] **Step 2: Run** `android/run-instrumented-tests.sh quick`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement**: under the Display chain's rows, `SettingsSection("Favourites")`, each favourite a
  `NavRow` "N. <name>" with its summary, then "Save as favourite" (`NavRow`); the empty-list line as a note. A on a
  favourite opens an `OverlayPanel`: Apply (focused), Rename…, Move up, Move down, Delete (`Danger`); a question or
  the name form (`TextFieldRow` "Name", the refusal as a `Danger` `Notice`, Save or Rename, Cancel `Ghost`) takes the
  panel's place, Cancel focused in a question. B closes a question to the panel, the panel to its row.
- [ ] **Step 4: Run** the quick tier. Expected: all pass (read the result XML). Then the local CI checks
  (`assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs`). Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** "Android: favourites on the Post-processing page".

### Task 11: Docs, and the whole check

**Files:**
- Modify: `docs/android.md` ("Post-processing": favourites, the hotkeys among the Select chords, the mirrored
  folder), `docs/roadmap.md` (item 17 done: stage 3, the PR number once there is one), `docs/design-system.md` (the
  favourites list under "An ordered list a page edits", if its panel differs from the pattern)

- [ ] **Step 1: Write the docs.**
- [ ] **Step 2: Run** the desktop `common-tests` (all pass), `cmake --build build --target duckstation-qt`
  (builds), Android's local CI checks (BUILD SUCCESSFUL), and `GOOSE_TEST_DATA=~/work/goose-test-data
  android/run-instrumented-tests.sh full` in the background from a detached checkout, reading its result XML (0
  failures, 0 errors).
- [ ] **Step 3: Commit** "Docs: post-processing favourites and their hotkeys; roadmap item 17 done".
