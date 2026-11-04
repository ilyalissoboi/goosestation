# Android: memory card editor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Settings → Memory cards → Manage cards opens a two-pane memory card editor with everything the desktop
editor does, for a controller, edits held in memory until Save, and Save synced through the mirror's route for the
cards sync knows.

**Architecture:**
- **The core** (`src/core/memory_card_image.{h,cpp}`) gets in-memory `ImportSave` and `ExportSave` variants, and a fix
  so a deleted save of more than one block can be undeleted (see "Found in the code"). `MemcardSync` gets
  `IsCardKnown()` (Android only).
- **The bridge** (`android/app/src/cpp/memcard_editor_bridge.{h,cpp}`, new) lists the cards (one per library game at
  the path it boots with, plus the other card files), and does every card operation on a card image passed in as
  bytes, returning the new bytes. It holds no state. Writing a card goes through `MemcardSyncBridge::HoldCard` when
  sync knows the card. Everything runs on the CPU thread (`EmuThread::RunOnThread(…, true)`).
- **The app:** `MemcardEditorApi` (an interface, `NativeMemcardEditorApi` over JNI), `MemcardEditor` (the
  controller: the card list, both panes, each card's file bytes and edited bytes, the questions, Save), a
  `DocumentPicker` for `content://` open and create, `MemcardEditorScreen` and its panels, an `OverlayPanel`
  component, and the wiring (a `Screen`, the Memory cards page's Manage cards row).

**Tech Stack:** C++20, CMake, Android NDK, JNI, Kotlin, coroutines, Jetpack Compose, the Storage Access Framework,
GoogleTest (`common-tests`), JUnit 4, AndroidX Test, Compose UI tests.

**Spec:** [docs/superpowers/specs/2026-11-05-android-memcard-editor-design.md](../specs/2026-11-05-android-memcard-editor-design.md).

**Execution:** native (the user's choice): every task built in the session, committed as it's done, then one review
of the whole branch.

**Where to build:** branch `claude/android-memcard-editor`, from the design commit `2b1de2851`, pushed to
`origin/claude/android-memcard-editor`.

## Found in the code, and how this plan deals with it

- **Undeleting a save of two or more blocks corrupts it, on desktop too.** `DeleteFile(…, clear_sectors = false)`
  sets every block's next-block link to `0xFFFF`. Afterwards `EnumerateFiles` sees a deleted 3-block save as 1 block,
  and `UndeleteFile` gives back only its first block: a save whose size says 3 blocks, with blocks 2 and 3 free for
  the next write. `EnumerateFiles` also treats a deleted middle or last block (`0xA2`, `0xA3`) as the start of a save.
  **Task 1 fixes it in the core** *(agreed with the user on 2026-11-05: the desktop editor gets the fix too)*:
  `DeleteFile` keeps the links, `EnumerateFiles` starts a deleted save only at `0xA1`, and `UndeleteFile` refuses a
  save whose chain is shorter than its size (cards deleted by the old code).
- **`ImportCard` already has an in-memory variant; `ImportSave` and `ExportSave` don't.** Task 1 adds both. The file
  variants call them, with the same behaviour. The editor exports to bytes and the app writes them to the picked
  document, with no temporary file (this replaces the design's "temporary path").
- **`WithCardHeld` registers the card** (`es_container_add`), and that publishes a card the repository doesn't know.
  So "sync only cards sync knows" needs a check first: emusync's `es_card_get_mode(client, "ps1:<card>")` returns
  `ES_E_NOT_FOUND` for an unknown card. The card's name is `CardNameFor(path)`, as `RegisterCard` uses.
  `MemcardSync::IsCardKnown(path)` wraps it. Unknown cards (or sync off) are written with `SaveToFile` alone.
- **A playlist boots with a different card than `System::GetGameMemoryCardPath` computes.** At boot, for
  `PerGameTitle`, an M3U's running title is the playlist's file title (`CDImage::GetMetadata("title")`). When that
  differs from the database title, the card is `<playlist title>_N.mcd`, unless the database-title card exists or
  `UsePlaylistTitle` is off (`system.cpp:3735–3770`). `GetGameMemoryCardPath(serial, path)` always gives the
  database-title (or disc set) card. The bridge applies the boot rule for playlists, through a small pure function
  that `common-tests` covers. Everything else uses `GetGameMemoryCardPath`, which matches the boot code for single
  discs, disc sets, PS-EXEs and discs the database doesn't know.
- **A multi-disc game's card** is the one its first disc boots with: the boot serial is the first disc's serial (the
  key for a PS-EXE), and the boot path is the playlist's or the first disc's.
- **`GetGameMemoryCardPath`'s `Shared` branch** names `shared_card_{slot + 2}` (`system.cpp:5430`). Games whose card
  type is `Shared` aren't listed as cards of their own: they use the shared card, which is listed by itself.
- **The desktop's copy refuses** a destination with a save of the same name, deleted or not. The editor asks to
  replace a live one (the design's call) and leaves a deleted one alone. Undelete then refuses while a live save has
  the same name, so a card never holds two live saves of one name.
- **The core's `ImportSave` clears a deleted save of the same name** and refuses a live one. The editor keeps that
  behaviour and says why, in the core's words.
- **Mirror and editor writes:** the mirror's pull holds a card while it replaces it, but two holds on one path don't
  exclude each other. The window is small, and the file write is an atomic rename either way. Save's check that the
  file still matches what the editor read, made inside the hold, catches a pull that finished first.
- **After Save** the app pushes the mirror (`push(force = true)`), as after a game stops.
- **R8:** classes made by native code need keep rules (`proguard-rules.pro`), as `ArgbImage` has.

## Calls made while writing this plan *(writer's call; revisit in review)*

- **Card names:** a game's card is the game's title; its slot 2 card (only with a per-game type for slot 2) is
  "<title> (card 2)". The default shared cards are "Shared card 1" and "Shared card 2". A card named by `Card1Path` or
  `Card2Path` is shown by its file name without `.mcd`. Any other `.mcd` in the memory card folder is shown by its file
  name without `_1.mcd` (or `.mcd`). The picker lists games by title, then shared cards, then the rest by name. A card
  path two games share is one entry, under the first game's title.
- **A shared card is listed** when its file exists or the global type for its slot is `Shared`. The other cards are
  listed only when their file exists.
- **The Manage cards row's summary** is "N cards" (card files that exist; "1 card"), or "No cards yet".
- **A card file that can't be read** (wrong size, or no `MC` header) shows "Can't read this card" in its picker. Its
  actions are Import card and Format only, both asking first. Nothing is written to it unless one of those is taken.
- **The change count** counts edits since the last Save or Discard, and is 0 whenever every card's bytes match its
  file's: "1 change not saved", "3 changes not saved".
- **The save's icon** is drawn at 32 dp on `sunken`, with nearest-neighbour filtering.
- **Questions** take the save panel's place, or open in their own `OverlayPanel` for card actions, Save and leaving.
  Each puts its destructive button first, and the focus on the safe one. Buttons in panels are `Secondary`, `Danger`
  or `Ghost`: the only `beak` fill on the screen is Save.
- **Wording** not fixed by the design:
  - Undelete refused: "Can't undelete <title>: another save has used its blocks." or "Can't undelete <title>: this
    card already has a save named <name>."
  - Import failed: "Couldn't import <file>: <the core's reason>." Too big: "<file> is too big to be a memory card or a
    save." (The cap is 256 KiB; the largest format the core imports is a `.gme`, under 132 KiB.)
  - Export: "Exported <title>." or "Couldn't export <title>: <reason>."
  - Save: "Saved". Failed: "Couldn't save <card>: <reason>". Busy (a hold refused): "Couldn't save <card>: memory card
    sync is busy with it. Try again in a moment."
  - Changed under the editor: "<card> changed on this device since you opened it. Save over it?", with **Save over
    it** (`Danger`) and **Cancel** (`Ghost`, focused).
  - Leaving with edits: "Save your changes?", with **Save** (`Secondary`), **Discard** (`Danger`) and **Keep
    editing** (`Ghost`, focused). Discard: "Discard your changes?", with **Discard** (`Danger`) and **Keep editing**
    (`Ghost`, focused).
  - The rename messages and warnings carry the desktop's meaning in the design system's voice (Task 6 lists them).
  - The rename's sync warning shows when sync is on and sync knows the card. Desktop shows it whenever sync is on, but
    on Android an unknown card doesn't sync at Save.
- **The narrow layout** (under 720 dp wide): one pane, with `PanelTabs` naming both cards above it.

## Global Constraints

- The design system (`docs/design-system.md`, `docs/design-system/tokens.json`) is binding: controller-first,
  something always focused, every clickable thing focusable, no window dialogs (`checkNoWindowDialogs`), one `beak`
  fill, colours, type and shapes from the theme only, 48 dp touch targets, layouts working at 360 dp wide with text at
  1.3×.
- The editor is locked while a game runs (`settingsReady`), so it never edits a card a game has open.
- Nothing is written to a card file before Save. Every destructive action asks first.
- The UI thread never waits on card I/O or the core: every `MemcardEditorApi` call runs on `coreDispatcher`.
- Card operations go through `memory_card_image` and `memory_card_helpers` over JNI, on the CPU thread. The rename
  rules aren't reimplemented in Kotlin.
- Files come and go only through Android's file picker (`content://`), never a path.
- Test cards are made by the tests (cards formatted and written by the core), never a player's real saves.
- No desktop behaviour change beyond Task 1's fix. Clean room: nothing from DuckStation after
  `a5bf1ba5cee7`, nothing from its closed Android app.
- Each task's commit message is shown to the user after the commit. Messages end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Tests: `./gradlew testDebugUnitTest` and `check` (JVM, ktlint, lint, `checkNoWindowDialogs`);
  `android/scripts/run-instrumented-tests.sh quick` while building, and `full` with
  `GOOSE_TEST_DATA=~/work/goose-test-data` before the PR, reading the result XML under
  `android/app/build/outputs/androidTest-results/`; `common-tests` (desktop build, and
  `android/scripts/run-native-tests.sh` on an emulator) for core changes. Emulators only.

## Review Focus

1. **A card file that isn't a card** (0 bytes, the wrong size, another tool's file named `.mcd`) in the memory card
   folder: the picker shows "Can't read this card", the pane offers Import card and Format only, nothing crashes,
   and the file is untouched unless one of those is saved. *Test: Task 3 `anUnreadableCardIsListedAndNotLoaded`,
   Task 5 `anUnreadableCardOffersOnlyImportAndFormat`.*
2. **A picked file that's too big, unreadable, or not picked at all** (the player backs out): nothing changes, and a
   message says why (or, for backing out, nothing does). *Test: Task 5 `importSaveCancelledChangesNothing`,
   `aTooBigFileIsRefused`, `anUnreadableDocumentSaysWhy`.*
3. **Undelete while a live save has the same file name** (after a copy or an import of a save that was deleted):
   refused, with the reason. *Test: Task 5 `undeleteRefusesADuplicateName`.*
4. **The file changed or went away under the editor:** with no edits, the pane reloads, and a missing file shows "No
   card yet". With edits, Save asks before writing over it. *Test: Task 6 `aChangedFileWithoutEditsReloads`,
   `aDeletedFileWithoutEditsIsNoCardYet`, `saveAsksWhenTheFileChanged`; Task 4 `writeCardCreatesThenSaysChanged`.*
5. **Two library games on one card path, and a game whose type is `Shared`:** one entry each, and no game entry for
   the shared card. *Test: Task 3 `twoGamesOnOneCardAreOneEntry`, `aSharedTypeGameIsNotItsOwnCard`.*

---

### Task 1: The core: undelete that works, and saves to and from memory

**Files:**
- Modify: `src/core/memory_card_image.h`, `src/core/memory_card_image.cpp`
- Modify: `src/common-tests/CMakeLists.txt` (add `memory_card_image_tests.cpp` and `../core/memory_card_image.cpp`)
- Create: `src/common-tests/memory_card_image_tests.cpp`

**Interfaces:**
- Produces:
  - `bool MemoryCardImage::ImportSave(DataArray* data, const char* filename, std::span<const u8> file_data, Error* error)`:
    the format from `filename`'s extension (`.mcs` with its directory frame, else a raw save named after
    `filename`'s file title), as the file variant does, which now reads the file and calls this one.
  - `bool MemoryCardImage::ExportSave(const DataArray& data, const FileInfo& fi, std::vector<u8>* out, Error* error)`:
    the `.mcs` bytes (the directory frame, then the blocks). The file variant writes these bytes.
  - `DeleteFile(…, false)` keeps each block's link; `EnumerateFiles(…, true)` lists a deleted save once, from its
    `0xA1` block, with all its blocks; `UndeleteFile` returns false when `fi.num_blocks * BLOCK_SIZE < fi.size`.

The tests build their cards with the core: `Format`, then `WriteFile` with a title frame whose icon flag is `0x11`
(so `EnumerateFiles` lists it). A helper `MakeSave(u32 blocks, char fill)` in the test file builds that buffer.

- [ ] **Step 1: Write the failing tests** in `memory_card_image_tests.cpp`:
  - `TEST(MemoryCardImage, DeletedMultiBlockSaveListsOnceWithItsBlocks)`: write a 3-block save "BASLUS-00001", delete
    it (`clear_sectors = false`); `EnumerateFiles(card, true)` has exactly one entry, `deleted`, `num_blocks == 3`;
    `GetFreeBlockCount == 15`.
  - `TEST(MemoryCardImage, UndeleteRestoresAMultiBlockSave)`: as above, then `UndeleteFile` is true; the save lists
    live with 3 blocks, `ReadFile` gives back the bytes written, and `GetFreeBlockCount == 12`.
  - `TEST(MemoryCardImage, UndeleteRefusedOnceABlockIsReused)`: delete a 3-block save, write a 1-block save (it takes
    block 1); `UndeleteFile` on the deleted entry is false.
  - `TEST(MemoryCardImage, UndeleteRefusedForABrokenChain)`: a deleted save whose directory frame says 3 blocks, with
    its link set to `0xFFFF` by hand (as the old code left it); `UndeleteFile` is false and the card is unchanged.
  - `TEST(MemoryCardImage, ExportThenImportFromMemoryRoundTrips)`: `ExportSave` to bytes (`size == 128 + 2 * 8192` for
    2 blocks); `ImportSave(&other, "save.mcs", bytes)` onto a formatted card lists the same file name, title and data.
  - `TEST(MemoryCardImage, ImportRawSaveFromMemoryTakesTheFileTitle)`: `ImportSave(&card, "BESLES-01234.bin",
    8192 bytes)` lists a save named "BESLES-01234".
  - `TEST(MemoryCardImage, ImportSaveFromMemoryRefusesALiveDuplicate)`: importing the same `.mcs` twice fails the
    second time, and the card is unchanged.
- [ ] **Step 2: Run them and see them fail.** Configure the desktop build if this worktree has none
  (`cmake -B build -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo -DBUILD_TESTS=ON
  -DCMAKE_PREFIX_PATH=$HOME/work/goosestation-deps`, as the earlier worktrees have it), then `cmake --build build --target common-tests && build/bin/common-tests --gtest_filter='MemoryCardImage.*'`.
  Expected: the in-memory tests don't compile until Step 3 declares them; once declared, the undelete tests fail.
- [ ] **Step 3: Implement.** `DeleteFile` stops writing `next_block_number = 0xFFFF` when `!clear_sectors`.
  `EnumerateFiles` takes `0xA1` (not `0xA2`/`0xA3`) as a deleted save's start. `UndeleteFile` checks the chain length
  against `fi.size` first. Move `ImportSaveWithDirectoryFrame` and `ImportRawSave` onto a span (the size checks use
  `file_data.size()`), and make the file `ImportSave` read the file with `FileSystem::ReadBinaryFile` (keeping "File
  does not exist, or is empty."). Make the file `ExportSave` call the new one and write the bytes atomically. Drop the
  unused `system.h` and `util/state_wrapper.h` includes, so `common-tests` links without the rest of the core.
- [ ] **Step 4: Run** `MemoryCardImage.*` and the whole `common-tests` on desktop. Expected: all pass. Then
  `android/scripts/run-native-tests.sh` on an emulator: all pass.
- [ ] **Step 5: Check the desktop editor still builds** (`cmake --build build --target duckstation-qt`).
- [ ] **Step 6: Commit** (`Memory cards: undelete saves of more than one block, and import and export saves in
  memory`).

### Task 2: The card rules that need no core: a playlist's card, and other cards' names

**Files:**
- Create: `src/util/android_memcards.h`, `src/util/android_memcards.cpp`
- Create: `src/common-tests/android_memcards_tests.cpp`
- Modify: `src/common-tests/CMakeLists.txt`; `android/app/src/cpp/CMakeLists.txt` (or wherever
  `android_disc_groups.cpp` is added to the app's library, alongside it)

**Interfaces:**
- Produces (namespace `AndroidMemcards`):
  - `std::optional<std::string> PlaylistCardTitle(std::string_view playlist_title, std::string_view database_title,
    bool database_card_exists, bool use_playlist_title)`: the title a `PerGameTitle` playlist's card is named after
    at boot when it isn't the one `System::GetGameMemoryCardPath` gives, else nullopt. That's `playlist_title` when it
    differs from `database_title` (not empty), `use_playlist_title` is on and the database-title card doesn't exist.
  - `std::string OtherCardName(std::string_view file_name)`: `"Crash_1.mcd"` → `"Crash"`, `"Backup.mcd"` →
    `"Backup"`, `"Crash_2.mcd"` → `"Crash_2"`, `"odd.MCD"` → `"odd"`.

- [ ] **Step 1: Write the failing tests:** `TEST(AndroidMemcards, PlaylistCardTitle)`: ("FF7 (USA)", "Final Fantasy
  VII", false, true) → "FF7 (USA)"; the same with `database_card_exists` → nullopt; with `use_playlist_title` false →
  nullopt; ("Final Fantasy VII", "Final Fantasy VII", false, true) → nullopt; an empty database title → nullopt.
  `TEST(AndroidMemcards, OtherCardName)` with the four cases above.
- [ ] **Step 2: Run** `common-tests --gtest_filter='AndroidMemcards.*'`. Expected: fails to build.
- [ ] **Step 3: Implement** both functions.
- [ ] **Step 4: Run** the filter, then all of `common-tests`. Expected: pass.
- [ ] **Step 5: Commit** (`Android: the memory card editor's card rules: a playlist's card, other cards' names`).

### Task 3: The card list

**Files:**
- Create: `android/app/src/cpp/memcard_editor_bridge.h`, `android/app/src/cpp/memcard_editor_bridge.cpp`
- Modify: `android/app/src/cpp/CMakeLists.txt`, `android/app/src/cpp/jni_bridge.cpp`
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/core/MemcardFile.kt`
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/app/MemcardGames.kt`
- Modify: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/core/NativeLibrary.kt`,
  `android/app/proguard-rules.pro`
- Test: `android/app/src/test/java/io/github/ilyalissoboi/goosestation/app/MemcardGamesTest.kt`,
  `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/core/MemcardEditorListTest.kt`

**Interfaces:**
- Consumes: `AndroidMemcards::PlaylistCardTitle`, `OtherCardName` (Task 2).
- Produces:
  - Kotlin `enum class MemcardKind { Game, Shared, Other }` and
    `class MemcardFile(val path: String, val name: String, val kind: MemcardKind, val exists: Boolean)`, `path`
    absolute, as the core opens it. Made by native code (keep rule for the constructor and the enum's values).
  - Kotlin `data class MemcardGame(val title: String, val serial: String, val bootPath: String, val playlist: Boolean)`
    and `object MemcardGames { fun of(games: List<GameEntry>): List<MemcardGame> }`: the boot serial is the first
    disc's non-blank serial, else the entry's `serial`; the boot path is `path` for a playlist or a game without discs,
    else the first disc's `path`. Sorted by title, case-insensitively.
  - `NativeLibrary.getMemcardFiles(titles: Array<String>, serials: Array<String>, bootPaths: Array<String>,
    playlists: BooleanArray): Array<MemcardFile>`
  - C++ `MemcardEditorBridge::ListCards(std::span<const GameCard> games) -> std::vector<CardFile>`, where
    `GameCard{title, serial, boot_path, playlist}` and `CardFile{path, name, kind, exists}`. On the CPU thread,
    waiting for it.

`ListCards`, in order:
1. For each game and slot 0 and 1: `System::GetGameMemoryCardPath(serial, boot_path, slot, &type)`. Only `PerGame`,
   `PerGameTitle` and `PerGameFileTitle` give a game card. For a playlist with `PerGameTitle` and a database entry,
   `PlaylistCardTitle(Path::GetFileTitle(display name of boot_path), entry->title, FileExists(database-title card),
   [MemoryCards] UsePlaylistTitle)` names the card instead, through `g_settings.GetGameMemoryCardPath(
   Path::SanitizeFileName(title), slot)`. The name is the title, plus " (card 2)" for slot 1. The first game wins a
   path.
2. Slots 0 and 1: the shared card, `Settings::GetSharedMemoryCardPath(slot)` as the base settings resolve it, when its
   file exists or the base `Card{N}Type` is `Shared`. Named "Shared card N" for the default file, else its file name
   without `.mcd`.
3. `Card1Path`/`Card2Path` from settings.ini and every game's ini (as `AndroidHost::GetMemoryCardPaths()` reads them),
   resolved against the memory card folder, inside the data root (`AndroidHost::RelativeToDataRoot`), existing, not
   yet listed: `Other`, by file name without `.mcd`.
4. Every `*.mcd` directly in `EmuFolders::MemoryCards` not yet listed: `Other`, `OtherCardName(file name)`.

Then shared cards, then the others by name, after the game cards (in the order given).

- [ ] **Step 1: Write the failing JVM test** `MemcardGamesTest`: a single disc (boot path = the disc's), a two-disc
  set (the first disc's serial and path), a playlist (the playlist's path, the first disc's serial), a PS-EXE without
  discs (the entry's path and serial), a disc with a blank serial (the entry's serial), and the sort.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*MemcardGamesTest'`. Expected: FAIL (no `MemcardGames`).
- [ ] **Step 3: Implement** `MemcardGames`; run again. Expected: PASS.
- [ ] **Step 4: Write the failing device test** `MemcardEditorListTest` (starts the core as `MemcardSyncCardsTest`
  does, with a fresh data root, and makes its cards with `writeMemoryCardSaveForTest`):
  - `aGameWithoutACardIsNoCardYet`: a game "Pad Test", serial `HASH-…`, boot path `…/padtest.psexe` gives a `Game`
    card at `<memcards>/padtest_1.mcd`, `exists == false`.
  - `aWrittenCardExists`: after writing a save to that path, `exists == true`.
  - `twoGamesOnOneCardAreOneEntry`: two entries with the same boot file title give one card, named after the first.
  - `aSharedTypeGameIsNotItsOwnCard`: a game whose `gamesettings/<serial>.ini` sets `Card1Type = Shared` gives no
    `Game` card. "Shared card 1" is listed.
  - `theSharedCardIsListedWhenItExists`: with `shared_card_1.mcd` written, it's a `Shared` card named "Shared card 1";
    without it (and the default type), it isn't listed.
  - `aStrayCardIsListedByName`: `memcards/Backup_1.mcd` written → `Other`, "Backup".
  - `aCardPathFromAGameIniIsListed`: a game ini's `Card1Path = elsewhere/mine.mcd` with that file written → `Other`,
    "mine".
  - `anUnreadableCardIsListedAndNotLoaded`: a 10-byte `memcards/junk.mcd` is listed (`exists == true`). Loading it is
    Task 4's.
- [ ] **Step 5: Run** `run-instrumented-tests.sh quick` (or the class alone with
  `-Pandroid.testInstrumentationRunnerArguments.class=…MemcardEditorListTest`). Expected: FAIL (no
  `getMemcardFiles`).
- [ ] **Step 6: Implement** the bridge, the JNI function (the `MemcardFile` class and the `MemcardKind` values looked
  up at load, as `MemcardSyncState` is), `NativeLibrary.getMemcardFiles`, and the keep rules.
- [ ] **Step 7: Run** the class again, then `quick`. Expected: PASS, nothing else broken.
- [ ] **Step 8: Commit** (`Android: list the memory cards: each library game's, the shared ones, and the rest`).

### Task 4: Card operations, writing, and what sync knows

**Files:**
- Modify: `src/core/memcard_sync.h`, `src/core/memcard_sync.cpp` (Android only)
- Modify: `android/app/src/cpp/memcard_editor_bridge.{h,cpp}`, `android/app/src/cpp/jni_bridge.cpp`,
  `android/app/src/cpp/memcard_sync_bridge.{h,cpp}` (`IsCardKnown`)
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/core/Memcards.kt` (the types below)
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/app/MemcardEditorApi.kt`
- Modify: `NativeLibrary.kt`, `proguard-rules.pro`
- Test: `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/core/MemcardEditorCoreTest.kt`,
  `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/core/MemcardEditorSyncTest.kt` (`@LargeTest`)

**Interfaces:**
- Consumes: Task 1's `ImportSave`/`ExportSave` in memory; `MemoryCardHelpers`; `MemcardSyncBridge::HoldCard`.
- Produces:
  - C++ `bool MemcardSync::IsCardKnown(std::string_view path)` (`#ifdef __ANDROID__`): with a client,
    `es_card_get_mode(s_client, ("ps1:" + CardNameFor(path)).c_str(), &mode) != ES_E_NOT_FOUND` (and not an error
    other than that: any other failure counts as known, so the card is held). Without a client, false.
  - Kotlin, made by native code (keep rules):
    - `class MemcardSave(val firstBlock: Int, val fileName: String, val title: String, val blocks: Int,
      val deleted: Boolean, val icon: ArgbImage?)` (the icon's first frame, or null without one)
    - `class MemcardContents(val freeBlocks: Int, val saves: List<MemcardSave>)`
    - `class MemcardEdit(val bytes: ByteArray?, val error: String)`: the new card, or null with the core's reason
    - `class MemcardRenameCheck(val titleError: Int, val nameError: Int, val singleByteTitle: Boolean,
      val titleNotShownExactly: Boolean, val nameNotStandard: Boolean)`: the errors are
      `MemoryCardHelpers::TitleError` and `FileNameError` as ints (0 for None, in the enums' order)
    - `enum class MemcardWriteOutcome { Saved, Changed, Busy, Failed }`, `class MemcardWrite(val outcome:
      MemcardWriteOutcome, val error: String)`
  - `interface MemcardEditorApi` (in `app/`), with `object NativeMemcardEditorApi : MemcardEditorApi` over
    `NativeLibrary`'s functions of the same names:
    - `fun listCards(games: List<MemcardGame>): List<MemcardFile>` (Task 3)
    - `fun readCard(path: String): ByteArray?`: the file's bytes, at most 256 KiB, or null without a file
    - `fun contents(card: ByteArray): MemcardContents?`: null unless it's 128 KiB starting with `MC`
    - `fun format(): ByteArray`
    - `fun copySave(from: ByteArray, firstBlock: Int, to: ByteArray, replace: Boolean): MemcardEdit`: `replace`
      first clears the live save of the same file name on `to`
    - `fun deleteSave(card: ByteArray, firstBlock: Int, permanently: Boolean): ByteArray`
    - `fun undeleteSave(card: ByteArray, firstBlock: Int): ByteArray?`: null when `UndeleteFile` refuses
    - `fun checkRename(card: ByteArray, firstBlock: Int, title: String?, fileName: String?): MemcardRenameCheck`:
      null leaves that part unchanged. The title in its own style (`GetTitleStyle` of the stored title). The other
      names are the card's live saves but this one.
    - `fun rename(card: ByteArray, firstBlock: Int, title: String?, fileName: String?): ByteArray?`
    - `fun importSave(card: ByteArray, fileName: String, data: ByteArray): MemcardEdit`
    - `fun importCard(fileName: String, data: ByteArray): MemcardEdit`: onto a formatted card
    - `fun exportSave(card: ByteArray, firstBlock: Int): MemcardEdit`
    - `fun isKnownToSync(path: String): Boolean`
    - `fun writeCard(path: String, card: ByteArray, expected: ByteArray?, force: Boolean): MemcardWrite`
  - Every one runs on the CPU thread (`EmuThread::RunOnThread(…, true)`) and is called on `coreDispatcher`. A save is
    found by `first_block` among `EnumerateFiles(card, true)`; none found gives the "no such save" error, or null.

`writeCard`: the action reads the file (missing is null). Unless `force`, when that isn't `expected` (byte for byte,
null for null), it returns `Changed` without writing. Otherwise it writes with `SaveToFile` (creating the memory card
folder if it's missing). The action runs inside `MemcardSyncBridge::HoldCard(relative path, action)` when
`IsCardKnown`, and `Busy` when the hold refuses; otherwise it runs directly. `Failed` carries the `Error`'s
description.

- [ ] **Step 1: Write the failing device tests** in `MemcardEditorCoreTest` (core started, as Task 3's test; cards
  made with `format()` plus `importSave` of raw 8 KiB saves with the icon flag at byte 2 set to `0x11`):
  - `contentsListsSavesAndFreeBlocks`: two saves (1 and 2 blocks) → 12 free, both listed with file name, title,
    blocks and an icon of 16 × 16.
  - `contentsIsNullForNotACard`: 10 bytes, and 128 KiB of zeros, are null.
  - `copyCopiesAndReplaceReplaces`: a copy adds the save; a second copy without `replace` gives an error and no
    bytes; with `replace`, one save of that name is left, with the copied data (checked with `exportSave`).
  - `deleteThenUndelete`: delete keeps the save listed as deleted; undelete brings it back. A 2-block save too.
  - `deletePermanentlyDropsIt`: a deleted save, deleted permanently, is no longer listed.
  - `undeleteRefusedOnceReused`: delete a 2-block save, import a 1-block save, then undelete gives null.
  - `checkRenameSaysWhatsWrong`: an empty name → `nameError == 1` (Empty); 21 characters → 2; `"A*B"` → 3;
    another save's name → 4; `"MYSAVE"` → 0 with `nameNotStandard`; a 33-character full-width title → `titleError == 2`
    and not `singleByteTitle`; `"☃"` → 1.
  - `renameRenames`: title and file name both change, as `contents` shows.
  - `exportThenImportRoundTrips`: export, import into a formatted card as `x.mcs`: same name and data.
  - `importCardReplacesEverything`: a card's 128 KiB imported as `card.mcd` gives the same saves; `card.txt` gives an
    error.
  - `writeCardCreatesThenSaysChanged` (*Review Focus 4*): `writeCard(path, card, expected = null)` creates the file;
    writing again with `expected = null` is `Changed` and leaves it; with the file's bytes as `expected` it's `Saved`;
    with `force` it's `Saved` whatever `expected` is.
- [ ] **Step 2: Run the class.** Expected: FAIL (no functions).
- [ ] **Step 3: Implement** the bridge functions, the JNI (classes looked up at load), `NativeLibrary`'s externals, the
  API, and the keep rules. `IsCardKnown` comes in Step 5's cycle.
- [ ] **Step 4: Run the class.** Expected: PASS.
- [ ] **Step 5: Write the failing sync tests** in `MemcardEditorSyncTest` (`@LargeTest`, two devices through
  `FakeS3` and `SyncTestCore`, as `MemcardSyncCardsTest` has them):
  - `aCardNoGameUsedIsUnknownAndStaysLocal`: sync on, a card written with `writeCard` → `isKnownToSync` false before
    and after, and after a sync the other device doesn't have the card.
  - `aCardAGameUsedIsKnown`: after `memcardCardOpenedForTest`/`ClosedForTest` on it, `isKnownToSync` is true.
  - `savingAKnownCardSyncs`: a known card written with a new save through `writeCard` reaches the other device.
  - `writeCardIsBusyWhileAGameHasTheCard`: with the card opened for test (and not closed), `writeCard` is `Busy`, and
    the file is unchanged.
- [ ] **Step 6: Run them.** Expected: FAIL. **Implement** `IsCardKnown` and the hold in `writeCard`. **Run again.**
  Expected: PASS. Then `common-tests` (the core changed) and `quick`.
- [ ] **Step 7: Commit** (`Android: the memory card editor's card operations, and Save through sync's hold`).

### Task 5: The editor's controller: cards, panes and edits in memory

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/app/MemcardEditor.kt`
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/app/MemcardEditorWords.kt`
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/storage/Documents.kt` (the interface only)
- Test: `android/app/src/test/java/io/github/ilyalissoboi/goosestation/app/MemcardEditorTest.kt`,
  `android/app/src/test/java/io/github/ilyalissoboi/goosestation/app/FakeMemcardEditorApi.kt`,
  `android/app/src/test/java/io/github/ilyalissoboi/goosestation/app/FakeDocuments.kt`

**Interfaces:**
- Consumes: `MemcardEditorApi`, `MemcardFile`, `MemcardGame`/`MemcardGames` (Tasks 3, 4).
- Produces:
  - `interface Documents` (`storage/`): `suspend fun open(): PickedDocument?` (null when the player backs out),
    `suspend fun create(suggestedName: String): String?` (the new document's URI), `fun read(uri: String,
    limit: Int): DocumentRead` and `fun write(uri: String, bytes: ByteArray): String?` (null once written, else why
    not), both on `io`. `data class PickedDocument(val uri: String, val displayName: String)`.
    `sealed interface DocumentRead { class Bytes(val bytes: ByteArray); data object TooBig; class Failed(val reason:
    String) }`.
  - `class MemcardEditor(scope: CoroutineScope, core: CoroutineDispatcher, io: CoroutineDispatcher,
    api: MemcardEditorApi, documents: Documents, syncOn: () -> Boolean)`, main thread only, with Compose state:
    - `val cards: List<MemcardFile>?` (null until loaded), `val panes: List<EditorCard?>` (two),
      `val changes: Int`, `val message: EditorMessage?`, `val question: EditorQuestion?`, `val working: Boolean`
      (a core call or a pick is under way: every action is ignored meanwhile).
    - `class EditorCard(val file: MemcardFile, val onFile: ByteArray?, val current: ByteArray?,
      val contents: MemcardContents?, val knownToSync: Boolean)`, with `val state: CardState` (`NoCardYet` when
      `current == null`, `Unreadable` when `contents == null`, else `Ready`) and `val edited: Boolean`
      (`current` isn't `onFile`, byte for byte).
    - `data class EditorMessage(val text: String, val error: Boolean)`;
      `class EditorQuestion(val text: String, val confirm: String, val onConfirm: () -> Unit,
      val choices: List<QuestionChoice> = emptyList())`, where a `QuestionChoice(label, kind: ButtonKind, action)` is
      an extra button (for leaving: Save). The safe answer is always "Cancel" (or "Keep editing"), which drops the
      question.
    - `fun open(games: List<GameEntry>)`, `fun pick(pane: Int, path: String)`, `fun copy(pane: Int,
      save: MemcardSave)`, `fun delete(pane: Int, save: MemcardSave)`, `fun deletePermanently(pane: Int,
      save: MemcardSave)`, `fun undelete(pane: Int, save: MemcardSave)`, `fun format(pane: Int)`,
      `fun importSave(pane: Int)`, `fun importCard(pane: Int)`, `fun export(pane: Int, save: MemcardSave)`,
      `fun answer(confirmed: Boolean)`, `fun clearMessage()`.
  - `object MemcardEditorWords`: every sentence below, as functions of their parts, so the tests and screens share
    them.
  - For the screen (Task 7): `data class MemcardEditorUi(val cards: List<MemcardFile>, val panes: List<EditorCard?>,
    val changes: Int, val message: EditorMessage?, val question: EditorQuestion?, val working: Boolean)` (Task 6 adds
    `panel` and `renameError`), as `MemcardEditor.ui` (null until the cards are loaded), and
    `interface MemcardEditorActions`, holding every action above (Task 6 adds its own), which `MemcardEditor`
    implements.

Rules:
- **Edits are kept per card, not per pane:** a card picked away from and back keeps its edits. `changes` counts each
  edit applied, and drops to 0 when no loaded card is `edited`.
- **Opening:** the left pane shows the first card listed whose file exists, the right the next (with fewer, the first
  cards listed, in order). Each card is loaded with `readCard`, `contents` and `isKnownToSync`.
- **Picking** the other pane's card swaps the panes.
- **Copy** goes to the other pane's card. On an `Unreadable` card it isn't offered (the screen hides it; here it does
  nothing). A live save of the same file name there asks "Replace the save of the same name on <card>?" (`Replace`).
  The blocks needed are the save's, against the free blocks plus the replaced save's: short, the message is "<card>
  needs N free blocks; it has M." (error). On a `NoCardYet` card, the card is `format()`ted first.
- **Delete** asks "Delete <title>? You can undelete it while its blocks aren't reused." (`Delete`). **Delete
  permanently** asks "Erase <title> for good? It can't be undeleted." (`Erase`). **Format** asks "Erase every save
  on <card>?" (`Erase`). **Import card** asks "Replace everything on <card> with <file>?" (`Replace`) after the pick.
  On a card `knownToSync` while `syncOn()`, each of these four adds the line "Memory card sync is on: your other
  devices will see these saves removed." after a blank line.
- **Undelete** refuses, with an error message, when a live save has the same file name ("Can't undelete <title>: this
  card already has a save named <name>.") or when `undeleteSave` gives null ("Can't undelete <title>: another save
  has used its blocks.").
- **Import save / Import card** read the picked document with a 256 KiB limit: `TooBig` says "<file> is too big to be a
  memory card or a save.", `Failed` says "Couldn't read <file>: <reason>.", and a core error says "Couldn't import
  <file>: <the core's reason>". Backing out of the picker changes nothing and says nothing. Import save onto a
  `NoCardYet` card formats it first.
- **Export** suggests "<file name>.mcs", writes `exportSave`'s bytes through `documents.write`, and says "Exported
  <title>." (or "Couldn't export <title>: <reason>.", an error).
- Each action clears the message first.

`FakeMemcardEditorApi` keeps each card as a list of `(fileName, title, blocks, deleted)` saves, encoded into the
`ByteArray` the controller passes around (any stable encoding: the controller never looks inside). `FakeDocuments`
returns what the test sets and records what's written.

- [ ] **Step 1: Write the failing tests** in `MemcardEditorTest` (with `StandardTestDispatcher`, as
  `MemcardSyncPageTest` runs its coroutines):
  - `opensWithTheFirstTwoCardsThatExist`, `pickingTheOtherPanesCardSwaps`, `editsStayWithTheirCard`
  - `copyAddsTheSave`, `copyAsksBeforeReplacing`, `copyRefusesWithoutTheBlocks` (the exact sentence),
    `copyCountsTheReplacedSavesBlocks`, `copyOntoNoCardYetFormatsIt`
  - `deleteAsksThenMarksDeleted`, `cancelChangesNothing`, `deletePermanentlyAsks`, `formatAsks`,
    `theSyncLineIsAddedOnlyForAKnownCardWithSyncOn`
  - `undeleteRefusesADuplicateName` (*Review Focus 3*), `undeleteRefusedWhenReused`
  - `importSaveCancelledChangesNothing`, `aTooBigFileIsRefused`, `anUnreadableDocumentSaysWhy` (*Review Focus 2*),
    `importSaveShowsTheCoresReason`, `importCardAsksThenReplaces`
  - `anUnreadableCardOffersOnlyImportAndFormat` (*Review Focus 1*: its state is `Unreadable`, and `copy` from the
    other pane and `importSave` onto it do nothing)
  - `exportWritesTheMcs`, `exportSaysWhenItCantWrite`
  - `changesCountEditsAndDropToZeroWhenUndone` (delete then undelete: 0)
  - `actionsAreIgnoredWhileWorking`
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*MemcardEditorTest'`. Expected: FAIL (no
  `MemcardEditor`).
- [ ] **Step 3: Implement** `Documents`, `MemcardEditor` and `MemcardEditorWords`.
- [ ] **Step 4: Run** the class, then `./gradlew check`. Expected: PASS.
- [ ] **Step 5: Commit** (`Android: the memory card editor's edits, held in memory`).

### Task 6: The editor's controller: the save panel, rename, Save, Discard, leaving and reloading

**Files:**
- Modify: `app/MemcardEditor.kt`, `app/MemcardEditorWords.kt`
- Test: `app/MemcardEditorTest.kt` (more tests), `app/FakeMemcardEditorApi.kt`

**Interfaces:**
- Consumes: Task 5's `MemcardEditor`; `MemcardEditorApi.checkRename`, `rename`, `writeCard`, `listCards`.
- Produces, on `MemcardEditor`:
  - `val panel: SavePanel?`, `data class SavePanel(val pane: Int, val firstBlock: Int, val renaming: Boolean)`;
    `fun openSave(pane: Int, save: MemcardSave)`, `fun startRename()`, `fun closePanel()`. A question asked from the
    panel shows in its place; when the action is done (or cancelled), the panel closes.
  - `val renameError: String?`; `fun rename(title: String, fileName: String)` for the open panel's save.
  - `fun save()`, `fun discard()`, `fun back(leave: () -> Unit)`, `fun refresh()`, `onSaved: () -> Unit`
    (a constructor parameter, defaulting to nothing: the app pushes the mirror there).
  - `val cardCount: Int?` and `fun loadCount(games: List<GameEntry>)`: the card files that exist, for the Memory cards
    page.

Rules:
- **Rename:** neither part changed closes the panel. Otherwise `checkRename` with the changed parts only. An error
  sets `renameError` and keeps the form. The errors' words:
  - title 1: "The title has characters a memory card can't store."
  - title 2: "The title is too long: it can be up to 32 full-width characters.", or, for a `singleByteTitle`, "The
    title is too long: it can be up to 64 characters, or 32 if any aren't ASCII."
  - name 1: "Enter a file name." · 2: "File names can be up to 20 characters." · 3: "File names can use only ASCII
    letters, numbers, spaces and punctuation, but not * or ?." · 4: "This card already has a save named "<name>"."

  Without errors, the warnings that apply, each a paragraph, then "Rename anyway?", are a question (`Rename`):
  - `titleNotShownExactly` (title changed): "Some characters in the current title, such as ー or ・, can't be shown
    exactly here. The new title is saved as you see it, so those characters may look different in the game."
  - `nameNotStandard` (name changed): ""<name>" doesn't follow the usual pattern for save names: B, a region letter,
    then the game's product code, such as BASLUS-00067. The game may no longer find or load this save."
  - name changed, `syncOn()` and the card `knownToSync`: "Memory card sync is on: your other devices will see the save
    "<old>" deleted and a new save "<new>" added. The history of "<old>" stays available under its old name."

  No warnings, or Rename, applies `rename`, one change.
- **Save** writes each `edited` card with `writeCard(path, current, onFile, force = false)`. `Saved` makes `current`
  the card's `onFile`. `Busy` and `Failed` say "Couldn't save <card>: memory card sync is busy with it. Try again in
  a moment." or "Couldn't save <card>: <reason>" (errors), and keep those edits. The `Changed` ones are asked about at
  the end, together: "<card> changed on this device since you opened it. Save over it?" (two or more names joined
  with ", " and " and ", "… changed …"), `Save over it`, which writes them with `force`. When every card is written:
  `changes` 0, the message "Saved", the card list read again (a new card now exists), and `onSaved()`.
- **Discard** asks "Discard your changes?" (`Discard`, safe answer "Keep editing"), then reads every loaded card
  from its file again.
- **Back** closes the panel or drops the question first. With `changes > 0` it asks "Save your changes?": `Discard`
  (the confirm), a `Save` choice (`Secondary`), and "Keep editing". Save leaves once everything is written (not if a
  write failed or a `Changed` question is cancelled); Discard drops the edits and leaves. With no changes, it leaves.
- **Refresh** reads the card list again, and each loaded card without edits from its file. Bytes that differ replace
  it, and a file gone makes it `NoCardYet`. A card with edits is left for Save's check.

- [ ] **Step 1: Write the failing tests:**
  - `aPanelOpensAndCloses`, `aQuestionFromThePanelTakesItsPlace`
  - `renameUnchangedJustCloses`, `renameErrorsKeepTheForm` (one test per error, each exact sentence),
    `renameWarningsAskFirst` (each warning, and the sync one only with sync on and a known card), `renameApplies`
  - `saveWritesEditedCardsOnly`, `saveSaysSavedAndCallsOnSaved`, `saveAsksWhenTheFileChanged` (*Review Focus 4*),
    `saveOverItForces`, `aBusyCardKeepsItsEdits`, `aFailedWriteSaysWhy`
  - `discardAsksThenReadsTheFilesAgain`
  - `backClosesThePanelFirst`, `backWithoutChangesLeaves`, `backWithChangesAsks`, `backSaveLeavesOnceSaved`,
    `backSaveStaysWhenAWriteFails`, `backDiscardLeaves`
  - `aChangedFileWithoutEditsReloads`, `aDeletedFileWithoutEditsIsNoCardYet` (*Review Focus 4*),
    `aChangedFileWithEditsIsLeftForSave`
  - `cardCountCountsTheFilesThatExist`
- [ ] **Step 2: Run** `--tests '*MemcardEditorTest'`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the class, then `./gradlew check`. Expected: PASS.
- [ ] **Step 5: Commit** (`Android: the memory card editor's rename, Save, Discard and leaving`).

### Task 7: The editor screen: header, two panes, picker, saves and card actions

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/MemcardEditorScreen.kt`
- Test: `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/ui/MemcardEditorScreenTest.kt`,
  `FocusAuditTest.kt`, `OrientationLayoutTest.kt` (new cases), and a recording `FakeMemcardEditorActions` in the
  androidTest `ui` package

**Interfaces:**
- Consumes: `MemcardEditorUi`, `MemcardEditorActions`, `EditorCard`, `MemcardSave` (Tasks 4–6); `ChoiceList` and
  `Choice` (`ui/components/ChoiceList.kt`, module-internal), `PanelTabs`, `KeyHints`, `ActionButton`, `Notice`,
  `focusRing`.
- Produces: `@Composable fun MemcardEditorScreen(ui: MemcardEditorUi, actions: MemcardEditorActions,
  onLeave: () -> Unit)`. Task 8 adds the panels to it.

Layout, on `ground`, 32 dp from the edges (as `SettingsPage` pads):
- **Header:** "Memory cards" (`headlineMedium`), the change count beside it in `bodyMedium` `ink-muted`
  ("1 change not saved", hidden at 0), then at the end, in a `FlowRow`, **Discard** (`Secondary`) and **Save**
  (`Primary`), both disabled at 0 changes. The message under it, while there is one: a `Notice` (`Success` for
  "Saved" and the like, `Danger` for an error) with its text.
- **Panes:** with 720 dp or more of width, two side by side, 16 dp apart, each `weight(1f)`. Under that, one: the
  `PanelTabs` with both cards' names over it, L1 and R1 switching. Each pane is a `surface` panel at `radius-md`
  with a 1 dp edge, `line-strong` on the pane holding the focus and `line` on the other, and scrolls on its own
  (4 dp content padding for the ring).
- **The picker row** (`focusRing`, `Role.Button`): the card's name in `titleMedium` `ink` (two lines at most, then an
  ellipsis), and under it "N of 15 blocks free" ("1 of 15 blocks free"), "No card yet" or "Can't read this card",
  `bodyMedium` `ink-muted`. A opens a `ChoiceList` under it: every card, the pane's current. Picking calls
  `actions.pick(pane, path)`.
- **Save rows** (`focusRing`): the icon's first frame at 32 dp on `sunken` at `radius-sm` (`FilterQuality.None`), or
  `GsIcons.memoryCard` in `ink-faint` without one; the title in `titleMedium` (two lines at most); the file name in
  `mono` `ink-muted`; and "1 block" / "N blocks" at the end in `bodyMedium`. A deleted save's title is "Deleted ·
  <title>", and its texts and icon are in `ink-faint`. A calls `actions.openSave(pane, save)`. A card with no saves
  says "No saves on this card." in `bodyMedium` `ink-muted`.
- **Card actions** under the rows, a `FlowRow`: **Import save** and **Import card** (`Secondary`) and **Format**
  (`Danger`). On a `NoCardYet` card, Import save and Import card. On an `Unreadable` card, Import card and Format.
- **`KeyHints`** at the foot: A Actions, B Back, L1 R1 Other card.
- **Keys:** L1 and R1 (`Key.ButtonL1`, `Key.ButtonR1`) move the focus to the other pane's picker (in the narrow layout,
  they switch the pane, focusing its picker). Left and right at a pane's edge move the focus to the other pane, as
  Compose's focus search does for side-by-side panes. B, Back and the system's Back call `actions.back(onLeave)`.
- **Focus on opening:** the left pane's first save, or its picker when it has none.
- While `ui.working`, nothing changes how it looks. The actions are ignored by the controller.

- [ ] **Step 1: Write the failing screen tests** in `MemcardEditorScreenTest`, driven by keys
  (`pressKey(Key.DirectionDown)`, `Key.Enter`, `Key.ButtonB`, `Key.ButtonL1`), with `MemcardEditorUi`s built by hand
  and `FakeMemcardEditorActions` recording the calls:
  - `opensFocusedOnTheLeftPanesFirstSave`, `opensOnThePickerWhenTheCardHasNoSaves`
  - `r1AndL1MoveBetweenThePanes`, `rightAtThePanesEdgeMovesToTheOtherPane`
  - `thePickerListsTheCardsAndPicks` (A on the picker, Down, A → `pick(0, path)`)
  - `aRowOpensItsSave` (A → `openSave(0, save)`)
  - `deletedSavesSayDeleted` (the "Deleted · " text; the row still focusable)
  - `noCardYetOffersImportOnly`, `anUnreadableCardOffersImportCardAndFormat`
  - `saveAndDiscardAreDisabledWithoutChanges`, `theChangeCountShows` ("2 changes not saved"), `saveCallsSave`
  - `backCallsBack`
  - `aNarrowScreenShowsOnePaneWithTabs` (in a 600 dp wide box: one pane's picker, R1 switches to the other card's)
- [ ] **Step 2: Add** `FocusAuditTest` cases `memcardEditor`, `memcardEditorNarrow`, `memcardEditorPickerOpen`, and
  an `OrientationLayoutTest` case `theMemoryCardEditorFitsWithLongNames` (a long game title as a card name, 32
  full-width characters as a save title, 20-character file names, a full card of 15 one-block saves: no text cut,
  every focusable reached with the D-pad and scrolled into view).
- [ ] **Step 3: Run** the ui tests (`run-instrumented-tests.sh quick`). Expected: FAIL (no screen).
- [ ] **Step 4: Implement** `MemcardEditorScreen`.
- [ ] **Step 5: Run** `quick` again. Expected: PASS. Then `./gradlew check`.
- [ ] **Step 6: Commit** (`Android: the memory card editor's screen: two cards side by side`).

### Task 8: The panels: a save's actions, the questions, and the rename form

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/components/OverlayPanel.kt`
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/MemcardPanels.kt`
- Modify: `ui/MemcardEditorScreen.kt` (shows them)
- Test: `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/ui/MemcardPanelsTest.kt`,
  `OverlayPanelTest.kt`, `FocusAuditTest.kt`, `OrientationLayoutTest.kt`

**Interfaces:**
- Consumes: `SavePanel`, `EditorQuestion`, `QuestionChoice`, `renameError` (Task 6); `TextFieldRow`.
- Produces: `@Composable fun OverlayPanel(onBack: () -> Unit, modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit)`: a `scrim` over the whole screen that takes every tap, a centred
  `surface` panel at `radius-lg`, at most 420 dp wide, 24 dp padding, scrolling, which the focus can't leave. B and
  Back call `onBack` on the key-up of a press that began in it (as `ResumePrompt` does), and it has a `BackHandler`.
  `ResumePrompt` is left as it is.

The panels, all in an `OverlayPanel`:
- **A save's actions** (`ui.panel` set, no `renaming`, no question): the save's title in `headlineSmall` (two lines
  at most), its file name in `mono` under it, then one button per action, full width, in a column 8 dp apart:
  - a live save: **Copy to <other card's name>** (`Secondary`, focused; left out when the other card is
    `Unreadable`), **Rename…**, **Export…** (`Secondary`), **Delete** (`Danger`);
  - a deleted save: **Undelete** (`Secondary`, focused), **Delete permanently** (`Danger`).

  B closes it (`closePanel`).
- **A question** (`ui.question` set): its text in `bodyLarge` `ink` (the paragraphs as they are), then a `FlowRow`:
  the confirm button (`Danger`), any `choices` (their kinds), then **Cancel**, or **Keep editing** for the questions
  that have it (`Ghost`, focused). B is the safe answer (`answer(false)`). Asked from a save panel, it shows in that
  panel's place.
- **The rename form** (`renaming`): "Rename" in `headlineSmall`, a `TextFieldRow` "Title" holding the title, a
  `TextFieldRow` "File name" holding the file name, a `Danger` `Notice` with `renameError` while there is one, then
  **Rename** (`Secondary`) and **Cancel** (`Ghost`). The title field is focused on opening, with the keyboard closed
  (as `TextFieldRow` does). The text stays as typed when an error shows. Rename calls `actions.rename(title,
  fileName)`. B or Cancel close the form and the panel.

- [ ] **Step 1: Write the failing tests:**
  - `OverlayPanelTest`: `theFocusCantLeave` (Down and Up from its last and first buttons stay inside),
    `bOnKeyUpCallsBack`, `aBHeldFromBeforeDoesNothing`, `tapsDontReachWhatsUnder`
  - `MemcardPanelsTest`: `aLiveSaveOffersCopyFirstAndFocused`, `copyIsLeftOutForAnUnreadableCard`,
    `aDeletedSaveOffersUndeleteAndDeletePermanently`, `eachButtonCallsItsAction`, `bClosesThePanel`,
    `aQuestionFocusesCancel`, `theDangerButtonConfirms`, `bAnswersNo`, `leavingOffersSaveDiscardAndKeepEditing`,
    `theRenameFormShowsTheErrorAndKeepsTheText`, `renameCallsRenameWithBothFields`
- [ ] **Step 2: Add** `FocusAuditTest` cases `memcardSavePanel`, `memcardDeletedSavePanel`, `memcardQuestion`,
  `memcardRenameForm`, and `OrientationLayoutTest` cases `aMemoryCardQuestionWithTheSyncLineFits` and
  `theRenameFormFits` (32 full-width characters as the title).
- [ ] **Step 3: Run** `quick`. Expected: FAIL.
- [ ] **Step 4: Implement** `OverlayPanel` and `MemcardPanels`, and show them from the screen.
- [ ] **Step 5: Run** `quick`, then `./gradlew check`. Expected: PASS.
- [ ] **Step 6: Commit** (`Android: the memory card editor's panels: a save's actions, questions, rename`).

### Task 9: Wiring: Manage cards, the file picker, Save's push and reloading

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/storage/DocumentPicker.kt`
- Modify: `MainActivity.kt`, `app/AppController.kt`, `ui/MemcardSyncScreen.kt`
- Test: `ui/MemcardSyncScreenTest.kt`, `FocusAuditTest.kt` (`memoryCards` gets the row)

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `class DocumentPicker(activity: ComponentActivity) : Documents`, made in `onCreate` like `FolderPicker`, with
    `ActivityResultContracts.OpenDocument()` (`arrayOf("*/*")`) and `CreateDocument("application/octet-stream")`. One
    pick at a time. The display name comes from `OpenableColumns.DISPLAY_NAME` (the URI's last segment without one).
    `read` stops past `limit` bytes (`TooBig`). `write` opens the document with mode `"wt"`. Both run on `io`.
  - `Screen.MemcardEditor` (no hub entry: `page = null`). `AppController.openMemcardEditor()`: only from
    `Screen.MemoryCards` while `settingsReady`, calls `memcardEditor.open(games)`. `closeMemcardEditor()` goes back to
    `Screen.MemoryCards`, which focuses Manage cards.
  - `AppController.memcardEditor`, made with `onSaved = { scope.launch { push(force = true) } }` and
    `syncOn = { memcardSyncStatus.state?.enabled == true }`.
  - `MemcardSyncScreen` gets `cardsSummary: String?`, `onManageCards: () -> Unit` and `focusManageCards: Boolean`.
    Its first row, before the sync switch, is `NavRow("Manage cards", summary, onManageCards)`, the summary "N cards",
    "1 card" or "No cards yet" (`MemcardEditorWords`), or "Counting your cards…" while `cardCount` is null.
    Opening the Memory cards page calls `memcardEditor.loadCount(games)`.
  - The editor's `refresh()` runs, while `screen == Screen.MemcardEditor`, after each pull, when the app comes back
    to the front (`onStart`'s pull), and on `onMemcardSyncChanged`.
  - `MainActivity`: `Screen.MemcardEditor ->` the screen once `memcardEditor.ui` isn't null, else
    `SettingsLoadingPage("Memory cards", …)`, with `onLeave = AppController::closeMemcardEditor`.

- [ ] **Step 1: Write the failing tests** in `MemcardSyncScreenTest`: `manageCardsIsTheFirstRowAndOpens` (focused on
  opening, A → `onManageCards`), `manageCardsSaysHowManyCards` ("3 cards", "1 card", "No cards yet", "Counting your
  cards…"), `comingBackFocusesManageCards`. Update the `FocusAuditTest` and `OrientationLayoutTest` cases that show
  the page.
- [ ] **Step 2: Run** `quick`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** the picker, the screen's row, the controller wiring and `MainActivity`'s case.
- [ ] **Step 4: Run** `quick`, `./gradlew check` and `./gradlew assembleRelease`. Expected: PASS, and R8 keeps the
  native-made classes (`build/outputs/mapping/release/seeds.txt` lists them).
- [ ] **Step 5: Try it on an emulator** (`emulator-5580`, with the BIOS and padtest from `GOOSE_TEST_DATA`): boot
  padtest so it has a card; open Manage cards; check both panes, the picker, L1/R1, and a copy to Shared card 1; Save;
  export a save to Downloads and import it on the other card; rename it (a warning shows); delete and undelete it;
  format a card; leave with changes (the question); check the files in the data folder. Record what was checked for
  Task 10.
- [ ] **Step 6: Commit** (`Android: Settings > Memory cards > Manage cards opens the memory card editor`).

### Task 10: Docs, the full tier, and the branch

**Files:**
- Modify: `docs/design-system.md` (`OverlayPanel` under Components; the editor under "Library and game screens", or a
  new "Memory card editor" entry, with its layout and keys), `docs/android.md` (the editor, its tests, and the
  emulator check's results), `docs/roadmap.md` (item 16's memory card editor line)

- [ ] **Step 1: Write the docs.**
- [ ] **Step 2: Run everything:** `./gradlew check`; `common-tests` on desktop and
  `android/scripts/run-native-tests.sh`; then `GOOSE_TEST_DATA=~/work/goose-test-data
  android/scripts/run-instrumented-tests.sh full` in the background. Read each part's result XML under
  `android/app/build/outputs/androidTest-results/` (tests, failures, errors, skipped), not just Gradle's verdict.
- [ ] **Step 3: Commit** (`Docs: the Android memory card editor`), and push the branch to
  `origin/claude/android-memcard-editor`.
- [ ] **Step 4:** `superpowers:finishing-a-development-branch`. A PR to `master` only once the user says so.
