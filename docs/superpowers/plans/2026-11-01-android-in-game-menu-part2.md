# Android in-game menu, part 2: discs and patch codes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a multi-disc game is one library tile, with one set of save states and one resume state under the game's
key, and states that find their disc by identity; the menu gains **Change disc** and **Patch codes** panels beside it.

**Architecture:**
- **Grouping:** a pure C++ helper (`src/util/android_disc_groups`, in common-tests) groups the game list's files
  natively, in `getGameList`, into games: M3U playlists, then the database's disc sets, then disc markers in file
  names. Kotlin gets finished games: `GameEntry.serial` becomes the game's key, and `GameEntry.discs` lists its discs.
  The host keeps the last list, and copies the started game's discs as the **running game**.
- **States:** the host keys save states and the resume state by the running game's key. One `#ifdef __ANDROID__` hook
  in `System::LoadStateFromBuffer` asks the host which disc a state needs (`Host::ResolveSaveStateMedia`), by serial,
  playlist position or file name, never by its stale path. The one-time move of each disc's states under the key is
  planned in Kotlin (`StateMigration`) and run at the game's first play.
- **Patch codes:** the host builds the game's list once per boot from the player's `.cht` files and the database's
  codes for every disc (`AndroidPatchCodes::Combine`, pure). It sets that list again after every disc change, and
  writes which codes are on to `cheats/<key>.patchcodes`.
- **The menu:** `InGameMenu` gains the disc and code panels' state. `GameMenu` shows one side panel at a time: slots,
  discs or codes.

**Tech Stack:** C++20, CMake, Android NDK, JNI, Kotlin, coroutines, Jetpack Compose (BOM 2026.09.00), Material 3,
GoogleTest, JUnit 4, kotlinx-coroutines-test, AndroidX Test (orchestrator), Compose UI tests.

**Spec:** [docs/superpowers/specs/2026-11-01-android-in-game-menu-part2-design.md](../specs/2026-11-01-android-in-game-menu-part2-design.md).
Its *(writer's call)* items are settled. Part 1's design
([2026-10-31-android-in-game-menu-design.md](../specs/2026-10-31-android-in-game-menu-design.md)) and plan
([2026-10-31-android-in-game-menu-part1.md](2026-10-31-android-in-game-menu-part1.md)) still hold where this plan
doesn't change them.

**Choices made with the user on 2026-11-01** (where the design left them open):
- **Grouping is native**, in `getGameList`, so the host has the groups it needs for state lookup, disc swaps and
  every disc's codes. This was chosen over grouping in Kotlin over raw file facts.
- **The state migration** runs at a game's first play, before the resume check, off the UI thread. A pure Kotlin
  planner works from `getSaveStateTimes` per disc, and a native `moveSaveState` renames the files. It's recorded as
  `migrated.<key>` in the app's `game` preferences, on this device. This was chosen over a marker in the data folder
  and over migrating at every library scan.
- **Which codes are on** is kept in `cheats/<key>.patchcodes`: one line per code the player switched,
  `on|off<TAB>group<TAB>name`. A code not named keeps its default. This was chosen over one INI for every game and
  over a section in `gamesettings/<key>.ini`.
- **An M3U always takes the discs it lists.** When its discs are also a database set (Ace Combat 3's two CHDs with
  their M3U), the playlist is the one tile, and Change disc switches its sub-image. This was chosen over the set
  hiding the playlist.

**Found in the code, and how this plan deals with it** *(raised with the user; revisit in review)*:
- **`LoadStateFromBuffer` opens the state's own media path.** When that won't open, it keeps the current disc with an
  OSD warning, the case the design forbids. Hence the Android hook (Task 3). `ExtendedSaveStateInfo` also gains
  `media_subimage_index`, which desktop never reads: an M3U state records the boot disc's serial, so only its
  sub-image says which disc it was made on.
- **The core applies whatever code list is set, whatever `Console/EnableCheats` says.** The setting only makes the
  core load `<disc title>.cht` itself as each disc starts, and drop the list when it changes. So on Android the host
  turns it off in memory, in `Host::LoadSettings` (as it does for the status indicators), and never writes it. It
  owns the list instead. The player sees what the design asks for: no gate, and only the codes they switch on apply.
- **Part 1 already saves the resume state through the host** (`AndroidHost::SaveResumeState`), not through
  `ShutdownSystem(true)`, so only the key it saves under changes.
- **`chtdb.txt` files one block under several serials** (Ace Combat 3's `SLPS-02020` and `SLPS-02021`, every disc of
  Final Fantasy VIII), and Final Fantasy VII's codes under disc 1 only. Merging by group and name handles both.
- **Per-title memory cards** (the core's default) follow a database set's `disc_set_name`, so a set's discs share one
  card. A name-grouped game the database doesn't know gets a card per disc title. That's the core's rule, as on
  desktop, and goes under "Known limits".

**Calls made while writing this plan** *(writer's call; revisit in review)*:
- **Disc markers:** the last marker in the file title counts. It's `disc` or `cd`, in any case, then nothing, a space or
  `_`, then 1 or 2 digits (1 to 99), then optionally ` of ` and digits, with nothing else inside. The bracketed form
  needs a matching `)` or `]`. The bare form must follow the start of the title, a space, `-`, `_`, `(` or `[`, and
  the separators before it go with it. What remains, with its spaces collapsed and trimmed, is the base name.
- **Name groups** are discs the database doesn't know (`GameDatabase::GetEntryForSerial` finds nothing), not claimed by
  a playlist, in the same folder (`Path::GetDirectory`), with the same base name ignoring case. A group needs two or more
  discs, and a base name whose disc numbers repeat isn't grouped at all. The key, path and cover are the lowest
  number's.
- **Database sets** follow the core's own rule (disc 1 present, and another disc). A second file for a disc already in
  the set stays a tile of its own.
- **Tile titles:** a set's `disc_set_name`, else the playlist's or lowest disc's title with its marker taken out.
  Play time is summed over the distinct serials of the files the game stands for, and the last played time is the
  latest.
- **Change disc details:** a disc's title when it differs from the game's, else its serial.
- **The last played disc** is `lastDisc.<key>` in the `game` preferences. It's recorded as each game boots (whichever
  disc it booted, a resume state's included), after a disc change, and after a state load.
- **"Other saves":** Load state also lists the states still filed under the game's other discs' serials. These are the
  migration's overflow, and anything desktop saved per disc since. They're listed after the slots, under an
  `Eyebrow("Other saves")`. Loading one says "Loaded disc 2's slot 3" (or "disc 2's resume state"). It's never the
  last slot.
- **A state's disc** is resolved in this order: an M3U state's sub-image; else the one disc with the state's serial;
  else the disc whose file name matches the state's, ignoring case. Loading a state whose disc can't be found fails
  with "The disc this state was saved on isn't in your library: <file name>." The undo state, and any state whose path
  is the disc in the drive now, pass straight through.
- **Codes:** the app's file outranks a player's `[*]` mark, which outranks the off default. A Manual code is never
  "on": it's an Apply row. Database codes have no Apply rows. Groups show in first-seen order. Codes without a group
  (`Ungrouped`) come last under "Other", or with no heading when no code has a group.
- **"Applied: <name>"** shows in the panel as a `Success` notice, since the menu stays open over the OSD layer.
- **The core's messages:** `DiscInserted`, `media_switch_subimage` and `ReloadMemoryCardsFromGameChange` show nothing
  (the app says "Disc 2 inserted"). `CheatsLoadWarning` becomes "1 patch code is on" or "N patch codes are on".
- **Icons:** Change disc `GsIcons.disc`; Patch codes a new `GsIcons.code` (`<` and `>` on the 24 px grid).
- **KeyHints:** Change disc A Insert, B Back; Patch codes A Select, B Back.

## Global Constraints

- Everything in part 1's plan's Global Constraints holds: the design system is binding (tokens only, existing
  components first, `focusRing`, focus on open, 4 dp for the ring, no window dialogs, one `beak` fill, new screens in
  `FocusAuditTest` and `OrientationLayoutTest` with a key-driven screen test). So do its writing and motion rules,
  core calls on the CPU thread with `GameCoreApi` calls on `AppController.gameDispatcher`, Plan 2's JNI rules, the SPDX
  headers and the clean room (nothing from DuckStation after `a5bf1ba5cee7`, nor its closed Android app).
- No core behaviour change for desktop. Android-only code is behind `__ANDROID__`, in `src/util/android_*`, or in
  `android/`. The one change outside them, `ExtendedSaveStateInfo::media_subimage_index`, is filled in on every
  platform and read only on Android. After every task that touches `src/`, `cmake --build build` succeeds with no new
  warnings in changed files, and `build/bin/common-tests` passes.
- **Desktop's files are read, never rewritten:** the player's `.cht` files, `settings.ini`'s `Console/EnableCheats`,
  `gamesettings/`. The app writes only `savestates/` (moves, part 1's saves), `cheats/<key>.patchcodes`, and its own
  preferences.
- **Nothing is lost:** a migration only renames, never onto an existing file, and stops at the first failed rename
  without recording itself, so the next play tries again.
- Long code names and notes wrap (the 1.3× text rule). The panels scroll, keeping 4 dp around the focus ring.
- Devices: `goose-arm64` (`adb -s emulator-5554`) for every task. The AYN Thor (`adb -s 64dc3c35`) only after the user
  says go. Word Thor steps with Back, Select + Start and the D-pad (no Mode button; the D-pad is a hat). Test assets in
  `~/work/goose-test`, including both Ace Combat 3 CHDs.
- Commits: the user reviews every commit message in chat first. Work on this worktree's branch, pushed to
  `origin/claude/android-in-game-menu-part2` (the design and this plan are on it), as `ilyalissoboi`
  (`GH_TOKEN=$(gh auth token --user ilyalissoboi)`, and the credential-helper pattern in the user's memory note). One
  commit per task, in the log's style (`Core: …`, `Util: …`, `Android: …`, `Docs: …`), with a bullet body and the
  `Co-Authored-By` line.
- Commands, as in part 1: `cd android && ./gradlew testDebugUnitTest`, `ANDROID_SERIAL=emulator-5554 ./gradlew
  connectedDebugAndroidTest` (one class with `-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`),
  `./gradlew check`, and `android/scripts/run-native-tests.sh`.

## Review Focus

1. **A state made on another device or on desktop** (a `content://` path from another folder pick, or
   `/home/…/FF7 (Disc 2).chd`). It loads with the library's disc 2, found by serial or file name. A state whose disc
   isn't in the library fails with the `Danger` notice naming it, and the game keeps its disc and place. (Task 1
   `FindStateDisc*`; Task 3 Step 5 check; Task 8 check.)
2. **Two discs both holding slot 3 and a resume state, and all ten slots full.** The newest keeps each place. The rest
   take the lowest free slots, the key's own first, and what doesn't fit stays where it was, listed under "Other
   saves". Running the plan again finds nothing to move. (Task 4 `StateMigrationTest`.)
3. **Names that look like discs and aren't**, and redump names with tags after the marker: "Discworld", "Disc Jockey",
   "ABCD 2", "Foo (Disc 1) (USA)" with "Foo (USA) (Disc 2)", the same disc twice in two formats. None merges two games.
   (Task 1 `DiscMarker*`, `GroupFiles*`.)
4. **A code the player switched off that their own `.cht` marks `[*]`.** It stays off across a reboot, a disc swap and
   a cross-disc state load, and never applies for a frame at boot. (Task 6 `CombineHonoursTheAppsFile`; Task 8 check
   on the Thor with a visible code.)
5. **A disc deleted after the scan.** Change disc to it fails in the panel ("Disc 2 isn't in your library any more."),
   the game keeps its disc, and the menu stays open. (Task 5 `InGameMenuTest.aMissingDiscKeepsTheMenuOpen`; Task 8
   check.)

## Files

| File | Responsibility | Task |
| --- | --- | --- |
| `src/util/android_disc_groups.h/.cpp` (new) | Disc markers, playlist entries, grouping, a state's disc | 1 |
| `src/common-tests/android_disc_groups_tests.cpp` (new) | Their tests | 1 |
| `android/app/src/cpp/jni_bridge.cpp`, `android_host.h/.cpp` | The grouped list; the running game; keyed states; disc calls; codes | 2, 3, 5, 6 |
| `core/GameEntry.kt`, `core/GameDisc.kt` (new) | A game and its discs | 2 |
| `src/core/system.h/.cpp` | `media_subimage_index`; the Android media hook | 3 |
| `core/SaveStatePreview.kt` (new), `app/GameCoreApi.kt`, `test/…/app/FakeGameCore.kt` | The keyed calls | 3, 5, 7 |
| `app/StateMigration.kt`, `app/GameRecords.kt` (new) | The migration plan; `migrated.`/`lastDisc.` records | 4 |
| `app/GameStarts.kt`, `app/InGameMenu.kt`, `app/AppController.kt` | Starts, other saves, discs, codes | 2–7 |
| `ui/SlotPanel.kt`, `ui/DiscPanel.kt` (new), `ui/PatchCodePanel.kt` (new), `ui/GameMenu.kt` | The panels | 4, 5, 7 |
| `src/util/android_patch_codes.h/.cpp` (new), `src/common-tests/android_patch_codes_tests.cpp` (new) | The codes file; combining the lists | 6 |
| `android/app/src/cpp/game_codes.h/.cpp` (new) | The running game's code list | 6 |
| `app/CoreMessages.kt` | Disc and code messages | 5, 6 |
| `core/PatchCode.kt` (new), `app/PatchCodeList.kt` (new) | The codes from native; as the panel groups them | 6, 7 |
| `docs/design-system/icons/code.svg` (new), `ui/theme/GsIcons.kt` (generated) | The Patch codes icon | 7 |
| `docs/android.md`, `docs/design-system.md` | Docs | 8 |

Paths under `ui/`, `app/`, `core/` are in `android/app/src/main/java/io/github/ilyalissoboi/goosestation/`, with
their tests under `android/app/src/test/…` (JVM) and `android/app/src/androidTest/…` (instrumented), in the same
packages. New `src/util/android_*.cpp` files join `util`'s `if(ANDROID)` sources and `common-tests`' sources, as
`android_input_keys.cpp` does.

---
### Task 1: disc markers and grouping (`AndroidDiscGroups`)

**Files:**
- Create: `src/util/android_disc_groups.h`, `src/util/android_disc_groups.cpp`
- Create: `src/common-tests/android_disc_groups_tests.cpp`
- Modify: `src/util/CMakeLists.txt` (the `if(ANDROID)` sources), `src/common-tests/CMakeLists.txt`

**Interfaces:**
- Produces (`namespace AndroidDiscGroups`, pure: no Android headers, no `GameList`, so it builds and is tested on
  every platform; it uses only `common/path.h` and `common/string_util.h`):
  - `struct DiscMarker { std::string base; u32 number; };`
  - `std::optional<DiscMarker> ParseDiscMarker(std::string_view file_title);`: the rule under "Calls made while writing
    this plan". Nullopt when there's no marker.
  - `std::string StripDiscMarker(std::string_view title);`: `ParseDiscMarker(title)->base`, or the title unchanged.
  - `std::vector<std::string> ParsePlaylist(std::string_view m3u_path, std::string_view text);`: the M3U's entries as
    the core's M3U reader takes them. Lines are trimmed. Blank lines and lines starting with `#` are skipped.
    `Path::ToNativePath`, and a relative entry becomes `Path::BuildRelativePath(m3u_path, entry)`.
  - `enum class FileType { Disc, Playlist, Other };`
  - `struct ListedFile { FileType type; std::string path, serial, title, disc_set_name; s32 disc_set_index = -1;
    bool known = false; std::vector<std::string> playlist_paths; };`: one game-list entry (not `DiscSet` ones), with
    `known` meaning the database has its serial, and `playlist_paths` filled for a playlist.
  - `struct Disc { std::string path, serial, title; u32 number; };`
  - `struct Game { std::string key, title, path; bool playlist = false; std::vector<Disc> discs;
    std::vector<size_t> files; };`. `files` are the indices of the listed files the game stands for (for play time and
    the cover). `discs` holds every disc of a disc game, in number order (one for a single disc), and none for anything
    else. `path` is the playlist's, or the lowest disc's, or the file's.
  - `std::vector<Game> GroupFiles(std::span<const ListedFile> files);`: every listed file is in exactly one game.
    It's done in this order:
    1. each playlist is a game whose discs are its entries, numbered from 1. An entry's serial and title come from the
       listed disc with that exact path, else they're blank and `Path::GetFileTitle` of the path. The playlist claims
       those listed discs (the first playlist, if two list one disc). The key is the playlist's serial, and the title
       `StripDiscMarker` of its title;
    2. each database set, from the unclaimed discs, by the core's rule (a disc with index 0, and one with another
       index), its key the index-0 disc's serial, its title `disc_set_name`. A second file for an index already taken
       stays a game of its own;
    3. name groups, from what's left, by the rule above, titled with their base name;
    4. everything else, a game of its own, keyed by its serial, keeping its title.
  - `std::optional<u32> FindStateDisc(const Game& game, std::string_view serial, std::string_view media_path,
    u32 subimage_index);`: the disc number a state was made on, by the order under "Calls made…". A media path whose
    file name ends in `.m3u` (ignoring case) gives `subimage_index + 1` if the game has that many discs. Nullopt when
    nothing matches.

- [ ] **Step 1: Write the failing tests** (`TEST(AndroidDiscGroups, …)`):
  - `DiscMarkerForms`: each form gives base "Foo" and number 2: `Foo (Disc 2)`, `Foo [Disc 2]`, `Foo Disc 2`,
    `Foo - Disc 2`, `Foo_Disc_2`, `Foo (Disc 2 of 3)`, `Foo [Disc 2 of 3]`, `Foo Disc 2 of 3`, `Foo (CD 2)`,
    `Foo CD2`, `FOO (DISC 2)` (base "FOO"), `Foo (disc 2)`;
  - `DiscMarkerInTheMiddle`: `Foo (Disc 1) (USA)` gives "Foo (USA)", 1;
  - `DiscMarkerNonMatches`: nullopt for `Discworld`, `Discworld II`, `Disc Jockey`, `ABCD 2`, `Foo (Disc)`,
    `Foo (Disc 0)`, `Foo (Disc 2` (no closing bracket), `Foo (Disc 2]`;
  - `PlaylistEntries`: a `content://…/tree/primary%3APS1/AC3/ac3.m3u` with `# comment`, a blank line,
    `  Disc 1.chd  ` and an absolute `/sdcard/x.chd` gives `…/AC3/Disc 1.chd` and `/sdcard/x.chd`;
  - `GroupFilesDatabaseSet`: two discs with `disc_set_name` "Ace Combat 3 - Electrosphere (Japan)", indices 1 and 0 → one game,
    key `SLPS-02020`, its discs in order 1, 2, and two `files`;
  - `GroupFilesSetNeedsDiscOne`: indices 1 and 2 only → two games;
  - `GroupFilesPlaylistTakesItsDiscs` (the user's choice): the set above plus an M3U listing both → one game, the
    playlist, `playlist == true`, discs with the CHDs' serials, three `files`;
  - `GroupFilesByName`: unknown `Foo (Disc 1).chd` and `Foo (Disc 2).chd` in one folder → one game titled "Foo", key the
    disc 1 serial; in two folders → two games;
  - `GroupFilesByNameMixedTags`: `Foo (Disc 1) (USA)` and `Foo (USA) (Disc 2)` → one game;
  - `GroupFilesNameRepeatsStayApart`: `Foo (Disc 1).chd`, `Foo (Disc 1).cue`, `Foo (Disc 2).chd` → three games;
  - `GroupFilesKnownDiscsAreNotNameGrouped`: the same names with `known = true` and no set → two games;
  - `GroupFilesKeyIsTheHashWithoutASerial`: unknown discs whose serials are `HASH-…` → key is disc 1's;
  - `GroupFilesOthersAreSingle`: a PS-EXE → a game with no discs, its title unchanged;
  - `FindStateDiscBySubimage`, `FindStateDiscBySerial`, `FindStateDiscByFileName` (a desktop path
    `/home/a/FF7 (Disc 2).CHD` against `content://…/ff7 (disc 2).chd`), `FindStateDiscNone`, and
    `FindStateDiscSerialOnTwoDiscs` (both discs have the serial → falls back to the file name).
- [ ] **Step 2: Run them to see them fail:** `cmake --build build && build/bin/common-tests
  --gtest_filter='AndroidDiscGroups.*'` (it doesn't compile).
- [ ] **Step 3: Implement** `android_disc_groups.h/.cpp`, with a KDoc-style comment on each function as
  `android_input_keys.h` has, and add them to both CMake lists.
- [ ] **Step 4: Run** `build/bin/common-tests` → PASS, and `android/scripts/run-native-tests.sh
  --gtest_filter='AndroidDiscGroups.*'` on `goose-arm64` → PASS.
- [ ] **Step 5: Commit** (message shown first): `Util: Android disc grouping, by playlist, disc set and disc marker`.

### Task 2: one tile per game, and starting a game's disc

**Files:**
- Modify: `android/app/src/cpp/jni_bridge.cpp` (`getGameList`, `startGame`, `JNI_OnLoad`), `android_host.h/.cpp`
- Create: `core/GameDisc.kt`
- Modify: `core/GameEntry.kt`, `core/NativeLibrary.kt`, `app/GameStarts.kt`, `app/AppController.kt`
- Test: `test/…/app/GameStartsTest.kt`, `androidTest/…/core/GameCallsTest.kt`

**Interfaces:**
- Consumes: `AndroidDiscGroups::GroupFiles`, `ParsePlaylist`, `StripDiscMarker` (Task 1).
- Produces (C++, Android host):
  - `std::optional<AndroidDiscGroups::Game> s_running_game`: the started game, its key, title, path and discs.
  - `void SetListedGames(std::vector<AndroidDiscGroups::Game> games)`, under its own mutex: the last list
    `getGameList` made.
  - `StartGame(std::string path, u32 disc, bool resume)`, replacing `(path, resume_serial)`:
    - it finds the listed game whose `path` is `path`, copies it as the pending running game (`NotifyError("This game
      isn't in your library any more.")` and `NotifyGameStopped()` if there's none), and boots disc `disc`. That's the
      playlist with `media_playlist_index = disc - 1`, else that disc's path. Disc 0 or a number past the end means
      disc 1;
    - with `resume`, the boot state is `GetGameSaveStateFileName(key, -1)`. Task 3 picks its disc;
    - `s_running_game` takes the pending game in `BootPendingGame`, and `Host::OnSystemDestroyed` clears it. It's read
      and written on the CPU thread only.
- `getGameList`: after the copy under the lock, each Playlist entry's file is read (`FileSystem::ReadFileToString`)
  for `ParsePlaylist`, `known` is `GameDatabase::GetEntryForSerial(serial) != nullptr`, and the entries are grouped.
  Each game becomes one `GameEntry`:
  - `title` the game's, `serial` its key, `region` its first file's;
  - play time summed, last played the latest, over the distinct serials of its `files` (a playlist and its disc 1
    share a serial, so it's counted once);
  - the cover `GetCoverImagePath` of the game's `path`, serial and title, else of disc 1's;
  - its discs as a `GameDisc[]`, and `playlist`.

  The sort by title is unchanged. Then `SetListedGames`.
- Produces (Kotlin):
  - `data class GameDisc(val path: String, val serial: String, val title: String, val number: Int)`, made by native
    code (`(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V`, cached at `JNI_OnLoad`).
  - `GameEntry` gains `val discs: List<GameDisc> = emptyList()` and `val playlist: Boolean = false`, and a second
    constructor taking `discs: Array<GameDisc>` for native code. `serial` is documented as **the game's key**: a set's
    first serial, a playlist's first entry's, a name group's lowest disc's, or a single file's own. KDoc: "multi-disc"
    means `discs.size > 1`.
  - `NativeLibrary.startGame(path: String, disc: Int, resume: Boolean)`.
  - `GameStarts(scope, game, api, records: GameRecordStore, start: (GameEntry, disc: Int, resume: Boolean) -> Unit)`.
    `play` reads `records.lastDisc(entry.serial) ?: 1` on `game` and passes it on. `resume()` passes it with `true`, and
    `startOver()` with `false`. A blank key starts disc 1 at once, as before. (`GameRecordStore` is Task 4's: create
    its interface and `lastDisc` here, and `migrated` in Task 4.)
  - `interface GameRecordStore { fun lastDisc(key: String): Int?; fun setLastDisc(key: String, disc: Int) }`, and
    `class GameRecords(store: KeyValueStore) : GameRecordStore` with `lastDisc.<key>` in the `game` preferences.
    Both block.
  - `AppController`: `startGameNow(game, disc, resume)` calls `NativeLibrary.startGame(game.path, disc, resume)`, and
    keeps `runningGame: GameEntry?`, set there and cleared in `onGameStopped`.

- [ ] **Step 1: Write the failing tests.**
  - `GameStartsTest`, updated for the new `start`: `aGameStartsOnItsLastDisc` (`lastDisc` 2 → `start(entry, 2, false)`),
    `aGameWithNoRecordStartsOnDisc1`, `resumeBootsFromIt` (`start(entry, 2, true)`), with an in-memory `GameRecordStore`.
  - `GameCallsTest.aGroupedListHasOneEntryPerGame`: with a games folder holding `padtest.psexe` only (as the existing
    tests set up), `getGameList()` has one entry with `discs` empty.
  - `GameCallsTest.startingAGameNotListedSaysSo`: `startGame("content://nowhere/x.chd", 1, false)` → `onError("This
    game isn't in your library any more.")` then `onGameStopped()`.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement** the native list, `SetListedGames`, `StartGame`, `GameDisc`, `GameEntry`, `GameRecords`,
  `GameStarts` and the `AppController` wiring.
- [ ] **Step 4: Run** the JVM suite, `GameCallsTest`, `LibraryScreenTest`, `./gradlew check`, desktop build and
  `common-tests` → PASS.
- [ ] **Step 5: Check on `goose-arm64`** with both Ace Combat 3 CHDs in the games folder:
  - one tile, "Ace Combat 3 - Electrosphere (Japan)", with disc 1's cover and serial;
  - adding their M3U still shows one tile, now the playlist (logcat: `Starting '…ac3.m3u'`);
  - play, then Exit game: it boots.
- [ ] **Step 6: Commit** (message shown first): `Android: one library tile per multi-disc game`.

### Task 3: states keyed by the game, finding their disc by identity

**Files:**
- Modify: `src/core/system.h`, `src/core/system.cpp`
- Modify: `android/app/src/cpp/android_host.h/.cpp`, `jni_bridge.cpp`
- Create: `core/SaveStatePreview.kt`
- Modify: `core/NativeLibrary.kt`, `app/GameCoreApi.kt`, `test/…/app/FakeGameCore.kt`, `app/InGameMenu.kt`,
  `app/GameStarts.kt`, `app/AppController.kt`
- Test: `test/…/app/InGameMenuTest.kt`, `test/…/app/GameStartsTest.kt`, `androidTest/…/core/GameCallsTest.kt`

**Interfaces:**
- Consumes: `s_running_game` (Task 2); `FindStateDisc` (Task 1).
- Produces (core):
  - `ExtendedSaveStateInfo` gains `u32 media_subimage_index = 0;`, filled in by `GetExtendedSaveStateInfo` from the
    state's buffer on every platform.
  - In `system.h`, `namespace Host`, under `#ifdef __ANDROID__`:
    `struct SaveStateMedia { std::string path; u32 subimage_index; };` and
    `std::optional<SaveStateMedia> ResolveSaveStateMedia(std::string_view serial, std::string_view media_path,
    u32 subimage_index, Error* error);`, documented as: "Android: the disc a save state was made on, found by its
    identity rather than its path, which may be from another folder or device: the path to open now and the sub-image
    to switch to. Nullopt, with `error` set, when that disc isn't in the library."
  - `LoadStateFromBuffer`, under `#ifdef __ANDROID__`, before `CDROM::RemoveMedia`: when `buffer.media_path` isn't
    empty, it resolves the media and uses the result's path and sub-image in place of the buffer's. A nullopt returns
    false with the error, the disc untouched. If the resolved disc won't open, the current one goes back in and the
    load fails: the "Using existing image" branch isn't taken on Android. Desktop compiles exactly as before.
- Produces (C++, Android host): `Host::ResolveSaveStateMedia` on `s_running_game`:
  - a `media_path` that is `System::GetMediaFileName()` (the undo state; a state of the disc in the drive), or no running
    game, passes straight through;
  - otherwise `FindStateDisc` gives the number. A playlist game gives `{game.path, number - 1}`, else
    `{disc.path, 0}`, if `FileSystem::FileExists`;
  - otherwise the error "The disc this state was saved on isn't in your library: <Path::GetFileName(media_path)>.".
- Produces (C++, Android host, changed), each on the CPU thread and blocking as before:
  - `SaveState(key, slot)`, `LoadState(key, slot)` and `SaveResumeState(key)` use
    `GetGameSaveStateFileName(key, slot)`, not the running disc's serial. A blank key gives part 1's `NO_SERIAL`. A
    load may name another disc's serial (Task 4's other saves).
  - `StartGame(…, resume = true)`: the resume state's `ExtendedSaveStateInfo` gives its serial, media path and
    sub-image. `FindStateDisc` picks the disc to boot (for a playlist, `media_playlist_index`). No disc means
    `NotifyError("Couldn't resume: <the resolver's error>")` and `NotifyGameStopped()`, with no boot.
  - `std::optional<SaveStatePreview> GetSaveStatePreview(std::string serial, s32 slot)`, replacing
    `GetSaveStateScreenshot`: the picture (or none), and the state's disc number by `FindStateDisc` on
    `s_running_game`, 0 when there's none or no running game.
  - `bool MoveSaveState(std::string from_serial, s32 from_slot, std::string to_serial, s32 to_slot)`:
    `FileSystem::RenamePath`, only if the source exists and the destination doesn't.
- Produces (Kotlin):
  - `class SaveStatePreview(val picture: ArgbImage?, val disc: Int)`, constructor
    `(Lio/github/ilyalissoboi/goosestation/core/ArgbImage;I)V`, cached at `JNI_OnLoad`.
  - `NativeLibrary` and `GameCoreApi` (the same names in both; `FakeGameCore` follows, with `previews` by slot and
    serial, and `moves` logged as `"moveSaveState A 3 K 5"`):
    - `saveState(key: String, slot: Int): String?`, `loadState(key: String, slot: Int): String?`,
      `saveResumeState(key: String): String?`;
    - `getSaveStatePreview(serial: String, slot: Int): SaveStatePreview?`, replacing `getSaveStateScreenshot`;
    - `moveSaveState(fromSerial: String, fromSlot: Int, toSerial: String, toSlot: Int): Boolean`.
  - `InGameMenu.gameStarted(game: GameEntry)`, replacing `(serial: String)`. It keeps `game` and its key
    (`game.serial`), and every slot call passes the key. `AppController.onGameStarted` passes `runningGame`.
    `GameStarts` uses `preview?.picture`.

- [ ] **Step 1: Write the failing tests.**
  - `InGameMenuTest`, its calls updated (`"saveState K 2"`), plus `slotsUseTheGamesKeyNotTheDisc`: a game keyed `K`
    whose core-side running serial differs → every call names `K`.
  - `GameCallsTest`:
    - `withoutAGameTheCallsSaySo` updated for the keyed calls;
    - `aStateMovesOnlyOntoNothing`: `SLUS-00001_3.sav` and `SLUS-00002_5.sav` → `moveSaveState("SLUS-00001", 3,
      "SLUS-00002", 5)` is false, and `(…, 6)` is true, leaving `SLUS-00002_6.sav` with the same bytes and time;
    - `aPreviewWithoutAGameHasNoDisc`: `getSaveStatePreview` of those bytes gives `disc == 0` and no picture.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement** the core hook and field, the host, the JNI and the Kotlin changes.
- [ ] **Step 4: Run** the desktop build and `common-tests`, `run-native-tests.sh`, the JVM and instrumented suites, and
  `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`** with the Ace Combat 3 set (both CHDs, no M3U) in games folder A.
  - Save to slot 1 on disc 1: `files/data/savestates/SLPS-02020_1.sav`.
  - Remove folder A and add folder B with the same files (another path): Load state → Slot 1 loads (logcat: the
    resolved `Opening …` from folder B, and no "Using existing image").
  - Delete disc 2 from B with a state saved on it (Task 5 makes that easy; until then, push a desktop-style state
    whose media path is a missing `…(Disc 2).chd`): the load fails with the notice naming it, and the game keeps going
    where it was.
- [ ] **Step 6: Commit** (message shown first): `Android: save states keyed by the game, finding their disc by
  identity` (it touches `src/core/system`, behind `__ANDROID__` but for one field; say so in the body).

### Task 4: moving each disc's states under the game, and "Other saves"

**Files:**
- Create: `app/StateMigration.kt`
- Modify: `app/GameRecords.kt` (Task 2's), `app/GameStarts.kt`, `app/InGameMenu.kt`, `app/AppController.kt`,
  `ui/SlotPanel.kt`, `ui/GameMenu.kt`
- Test: `test/…/app/StateMigrationTest.kt`, `test/…/app/GameStartsTest.kt`, `test/…/app/InGameMenuTest.kt`,
  `test/…/app/GameRecordsTest.kt`, `androidTest/…/ui/GameMenuTest.kt`, `androidTest/…/ui/FocusAuditTest.kt`

**Interfaces:**
- Consumes: `getSaveStateTimes`, `moveSaveState`, `getSaveStatePreview` (Task 3); `GameRecordStore` (Task 2);
  `LastSlotStore` (part 1).
- Produces:
  - `data class StateMove(val serial: String, val slot: Int, val toSlot: Int)`: the state at `serial`'s `slot` goes to the
    key's `toSlot`.
  - `object StateMigration`:
    - `fun plan(key: String, times: Map<String, LongArray>): List<StateMove>`. `times` is each disc serial's
      `getSaveStateTimes`, the key's included, in disc order. The moves come in the order to run them, the key's own
      first. The algorithm:
      1. For each place (the resume state, then slots 1 to 10), the candidates are every serial's state there.
      2. The newest wins the place. On a tie, the key's own wins, then the earlier disc.
      3. The losers are ordered the key's own first, then newest first.
      4. The free slots are slots 1 to 10 that no serial uses, from the lowest.
      5. Each loser in turn takes the next free slot. A loser left without one stays where it is. If it's the key's
         own, its place's winner doesn't move either.
      6. A move is each winner not already the key's, to its place, and each placed loser, to its slot.
    - `fun lastSlot(key: String, lastSlots: Map<String, Int>, times: Map<String, LongArray>, moves: List<StateMove>): Int?`:
      among the serials whose last slot holds a state, the one whose state is newest; where that state ends up under
      the key; null if it stays under another disc.
  - `GameRecordStore` gains `fun migrated(key: String): Boolean` and `fun setMigrated(key: String)`
    (`migrated.<key>` = `"1"`).
  - `GameStarts(…, lastSlots: LastSlotStore, …)`. In `play`, on `game`, before the resume check, for a multi-disc game
    not yet `migrated`:
    - read the times for each distinct non-blank serial among `entry.discs` and the key;
    - plan, and run each move with `api.moveSaveState`, stopping at the first that returns false, without recording;
    - when all ran: set the key's last slot from `lastSlot` if it's non-null, and `setMigrated(key)`.

    `AppController` builds one `GameRecords` and one `LastSlots` on the `game` preferences, and gives both to
    `GameStarts` and `InGameMenu`.
  - `SlotEntry` gains `val owner: String? = null` (null: the game's own; else the serial it's filed under) and
    `val disc: Int? = null` (shown only for a multi-disc game).
  - `SlotPanel` gains `val others: List<SlotEntry> = emptyList()`.
  - `GameMenuActions.pickOther(serial: String, slot: Int)`.
  - In `InGameMenu`:
    - `openSlots(Load)` also reads the times for each other disc serial (distinct, non-blank, not the key) and lists their
      saved states in `others`, resume first, then by slot, each with `disc` = that serial's disc number;
    - previews are read for own entries, then others. A multi-disc game's entries take `disc` from the preview when
      it's above 0;
    - `pickOther` loads with `api.loadState(serial, slot)`, saying "Loaded disc N's slot S" or "Loaded disc N's resume
      state", and on failure "Couldn't load that save: <reason>";
    - it never sets the last slot;
    - "No saves yet for this game." shows only when `entries` and `others` are both empty.
  - `SaveSlotPanel`: after the slots, `Eyebrow("Other saves")` and their rows, when there are any. A slot row shows
    "Disc N" (`bodyMedium`, `inkMuted`) under its time when `entry.disc != null`. Picking an other row calls
    `pickOther`.

- [ ] **Step 1: Write the failing tests.**
  - `StateMigrationTest` (Review Focus 2), with key `K` and a second disc `B`:
    - `aLoneDiscsStatesMoveToTheirPlaces`: `B` has resume and slot 2, `K` nothing → `[B -1→-1, B 2→2]`;
    - `theNewestKeepsThePlace`: both have slot 3, `B`'s newer → `K 3→1` first, then `B 3→3`;
    - `theKeysOwnLoserMovesFirst`: as above, the first move is the key's;
    - `twoResumeStates`: `B`'s resume newer → `K -1→1`, `B -1→-1`;
    - `fullSlotsLeaveTheRestInPlace`: `K` holds 1 to 10, `B` holds 1 to 3, all newer → no move, nothing lost;
    - `fullSlotsMoveWhatFits`: `K` holds 1 to 9 (all older), `B` holds 1 and 2 (newer) → `K 1→10`, `B 1→1`, and slot 2
      is left alone;
    - `aSecondPlanFindsNothing`: planning again on the times after the moves → `[]`;
    - `theLastSlotFollowsItsState`: `B`'s last slot 3 (newest), moved to 3 → 3; one left under `B` → null.
  - `GameRecordsTest`: `migrated` and `lastDisc` round trips; a garbled `lastDisc` gives null.
  - `GameStartsTest`:
    - `aMultiDiscGameMigratesOnceBeforeTheResumeCheck`: calls in order, the moves before `getSaveStateTimes K`'s resume
      read, and `migrated` set; a second play makes no move;
    - `aFailedMoveIsTriedAgainNextTime`: `moveSaveState` false → not migrated, no further moves;
    - `aSingleDiscGameNeverMigrates`.
  - `InGameMenuTest`:
    - `loadListsOtherDiscsSavesAfterTheSlots`: a `B` resume and slot 4 → `others` `[B -1, B 4]` with `disc = 2`;
    - `pickingAnOtherSaveLoadsIt`: `"loadState B 4"`, "Loaded disc 2's slot 4", no last slot set;
    - `saveNeverListsOtherSaves`;
    - `aMultiDiscGamesRowsTakeTheirDisc`: preview disc 2 → entry `disc == 2`; a single-disc game's stays null.
  - `GameMenuTest`:
    - `otherSavesShowUnderTheirHeading`: "Other saves" shows, and D-pad down from the last slot reaches the first
      other row;
    - `aRowShowsItsDisc`: "Disc 2" under the time.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement**, as listed. Add `gameMenuLoadWithOtherSaves()` to `FocusAuditTest`, and other saves with a
  long title to `OrientationLayoutTest.theGameMenuAndItsSlots`.
- [ ] **Step 4: Run** the JVM and instrumented suites and `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`.** With the Ace Combat 3 CHDs as separate tiles (a build from before Task 2, or
  states placed by hand), make `SLPS-02020_resume.sav`, `SLPS-02020_3.sav`, `SLPS-02021_3.sav` (newer) and
  `SLPS-02021_5.sav` in `files/data/savestates`. Play the grouped tile:
  - afterwards there's `SLPS-02020_3.sav` with disc 2's time, `SLPS-02020_1.sav` with disc 1's old slot 3,
    `SLPS-02020_5.sav`, and no `SLPS-02021_*` file;
  - the data folder has the same after the next push;
  - Load state shows them with "Disc 1" or "Disc 2";
  - a second play moves nothing (logcat).
- [ ] **Step 6: Commit** (message shown first): `Android: one set of save states per game, migrated from each disc's`.

### Task 5: Change disc

**Files:**
- Modify: `android/app/src/cpp/android_host.h/.cpp`, `jni_bridge.cpp`, `core/NativeLibrary.kt`, `app/GameCoreApi.kt`,
  `test/…/app/FakeGameCore.kt`, `app/InGameMenu.kt`, `app/CoreMessages.kt`, `ui/GameMenu.kt`
- Create: `ui/DiscPanel.kt`
- Test: `test/…/app/InGameMenuTest.kt`, `test/…/app/CoreMessagesTest.kt`, `androidTest/…/ui/GameMenuTest.kt`,
  `androidTest/…/ui/FocusAuditTest.kt`, `androidTest/…/ui/OrientationLayoutTest.kt`, `androidTest/…/core/GameCallsTest.kt`

**Interfaces:**
- Consumes: `s_running_game` (Task 2); `GameRecordStore.setLastDisc` (Task 2); `GameDisc` (Task 2).
- Produces (C++, Android host, CPU thread, blocking):
  - `u32 GetCurrentDisc()`: for a playlist, `System::GetMediaSubImageIndex() + 1`; else the number of the disc whose path
    is `System::GetMediaFileName()`. 0 without a game or a match.
  - `std::optional<std::string> ChangeDisc(u32 number)`, which returns:
    - "No game is running.";
    - "This game has one disc." for a game without two discs;
    - nothing (a no-op) for the current disc;
    - "Disc N isn't in your library any more." when the file is gone (`FileSystem::FileExists`);
    - else the result of `System::SwitchMediaSubImage(number - 1)` (a playlist) or `System::InsertMedia(path)`, with
      "Couldn't insert disc N." when that fails.
- Produces (Kotlin):
  - `NativeLibrary`/`GameCoreApi`: `getCurrentDisc(): Int` and `changeDisc(number: Int): String?`. The fake has
    `currentDisc` and `discError`.
  - `data class DiscChoice(val discs: List<GameDisc>, val current: Int, val busy: Boolean = false,
    val error: String? = null)`; `GameMenuState` gains `val disc: Int = 0`, `val discCount: Int = 0` and
    `val discs: DiscChoice? = null`.
  - `GameMenuActions`: `openDiscs()`, `closeDiscs()`, `pickDisc(number: Int)`.
  - `InGameMenu`:
    - `gameStarted` sets `discCount` from `game.discs.size`;
    - `readGameState()` also reads `getCurrentDisc()` into `disc`, and records it with `records.setLastDisc` when it's
      above 0. `InGameMenu` takes a `records: GameRecordStore`, after `lastSlots` (`AppController` passes Task 4's);
    - a load that works reads the current disc again and records it;
    - `openDiscs` (only with `discCount > 1`) closes any other panel and opens `DiscChoice(game.discs, disc)`;
    - `pickDisc(current)` just closes. Another disc sets `busy` and calls `changeDisc` on `game`;
    - on success: `disc` and the last disc set, the panel closed, `onMessage("Disc N inserted")`, `onResume()`;
    - on failure: `busy = false`, `error` the reason. The menu stays open.
  - Only one side panel is open at a time: `openSlots` and `openDiscs` (and Task 7's `openCodes`) close the others.
  - `CoreMessages.translate`: null for `DiscInserted`, `media_switch_subimage` and `ReloadMemoryCardsFromGameChange`.
  - `GameMenu`:
    - a **Change disc** row after Load state (`GsIcons.disc`, value "Disc 1 of 2"), only when `discCount > 1`, enabled
      when booted, current while its panel is open;
    - the B order gains the disc panel (after the slot panel's question, before the reset question);
    - `CameFrom` gains the disc row.
  - `@Composable fun DiscPanel(choice: DiscChoice, gameTitle: String, actions: GameMenuActions, modifier: Modifier)`,
    framed as `SaveSlotPanel` (`surface`, `GsShapes.large`, 24 dp padding, focus trapped):
    - the title "Change disc", and a `Notice(Danger)` with `error`, or "Inserting…" while `busy`;
    - one 48 dp row per disc: "Disc N" (`titleMedium`), with its detail under it (`bodyMedium`, `inkMuted`): the
      disc's title when it differs from `gameTitle`, else its serial;
    - the current disc's row on `beakSoft` with "Current" at its end;
    - the current disc focused on opening;
    - `KeyHints` A Insert, B Back.

- [ ] **Step 1: Write the failing tests.**
  - `InGameMenuTest`:
    - `changeDiscShowsOnlyForMultiDiscGames`;
    - `theCurrentDiscIsReadAsTheMenuOpens` (`disc` 2, and the last disc recorded);
    - `pickingAnotherDiscInsertsItAndResumes` (`"changeDisc 2"`, "Disc 2 inserted", a resume, last disc 2);
    - `pickingTheCurrentDiscOnlyCloses` (no call);
    - `aMissingDiscKeepsTheMenuOpen` (Review Focus 5: `discError` "Disc 2 isn't in your library any more." → `error`,
      no resume);
    - `aLoadRecordsItsDisc`;
    - `onePanelAtATime`.
  - `CoreMessagesTest`: the three keys give null.
  - `GameMenuTest`:
    - `changeDiscSitsAfterLoadState` (with its "Disc 1 of 2");
    - `theDiscPanelFocusesTheCurrentDisc` (and it's selected-looking: "Current" shown);
    - `bLeavesTheDiscPanelForItsRow`;
    - `theDiscPanelsHintsSayInsert`.
  - `GameCallsTest.changingDiscsWithoutAGameSaysSo`.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement.** Add `gameMenuDiscs()` to `FocusAuditTest`, and the disc panel, with long disc titles at every
  size and text scale, to `OrientationLayoutTest`.
- [ ] **Step 4: Run** the JVM and instrumented suites and `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`**, with the set, then the M3U:
  - Change disc → Disc 2 → "Disc 2 inserted", and logcat `Inserted media from …(Disc 2)` (the set) or `Switched to
    sub-image` (the M3U);
  - Exit, then Play: it boots disc 2;
  - deleting disc 2's file (the set) and picking it gives the notice, and the game goes on with disc 1.
- [ ] **Step 6: Commit** (message shown first): `Android: Change disc, in a panel beside the in-game menu`.

### Task 6: the game's patch codes, in the host

**Files:**
- Create: `src/util/android_patch_codes.h/.cpp`, `src/common-tests/android_patch_codes_tests.cpp`
- Create: `android/app/src/cpp/game_codes.h/.cpp` (added to `android/app/src/cpp/CMakeLists.txt`)
- Modify: `android/app/src/cpp/android_host.cpp` (`Host::LoadSettings`, `BootPendingGame`, `Host::OnGameChanged`,
  `Host::OnSystemDestroyed`), `jni_bridge.cpp`, `src/util/CMakeLists.txt`, `src/common-tests/CMakeLists.txt`
- Create: `core/PatchCode.kt`; modify: `core/NativeLibrary.kt`, `app/GameCoreApi.kt`, `test/…/app/FakeGameCore.kt`,
  `app/CoreMessages.kt`
- Test: `test/…/app/CoreMessagesTest.kt`, `androidTest/…/core/GameCallsTest.kt`

**Interfaces:**
- Produces (`namespace AndroidPatchCodes`, pure, in common-tests):
  - `struct Switch { std::string group, name; bool on; };`
  - `std::vector<Switch> Parse(std::string_view text);`: one switch per `on|off<TAB>group<TAB>name` line. Lines that are
    blank, start with `#` or are anything else are skipped.
  - `std::string Format(std::span<const Switch> switches);`: the header line `# GooseStation: the patch codes you
    switched on or off for this game. The app writes this file.`, then one line each. A switch whose group or name
    holds a tab or a line break is left out.
  - `std::string SafeFileName(std::string_view name);`: each of `< > : " / \ | ? *` and each control character
    becomes `_`. That's stricter than `Path::SanitizeFileName`, which keeps `:` on Android, while an SD card's file
    system and Windows refuse it.
  - `std::string FileName(std::string_view key);`: `SafeFileName(key) + ".patchcodes"`.
  - `struct Listed { std::string group, name; bool marked_on; bool manual; };`
  - `struct Entry { bool from_player; size_t index; bool on; };`
  - `std::vector<Entry> Combine(std::span<const Listed> player, std::span<const Listed> database,
    std::span<const Switch> switches);`. `player` holds the codes of each player file in reading order, and `database`
    every disc's database codes. Each code is kept once by group and name, the first occurrence winning, so a player's
    file wins over the database. Its `on` is the app's switch if there is one, else `marked_on`. A manual code is
    never on.
  - `struct State { std::string group, name; bool default_on, on; };`
  - `std::vector<Switch> Remember(std::span<const Switch> old, std::span<const State> codes);`: the app's file after a
    change. It keeps the old switches for codes not in `codes` (a disc or file missing today), and adds one for each
    code whose `on` differs from `default_on`.
- Produces (C++, `namespace GameCodes`, `game_codes.h`, CPU thread only):
  - `void Load(const AndroidDiscGroups::Game& game)`. The player files come first:
    - `cheats/<game title>.cht` (the name desktop's `GetCheatFileName` gives), then `<SafeFileName(game title)>.cht`;
    - then each disc's `<disc title>.cht` and its safe form, each path read once, with
      `CheatList::LoadFromFile(…, Autodetect)`.

    Then each distinct disc serial's `CheatList::LoadFromPackage`, and the app's `cheats/<FileName(key)>`. `Combine`
    gives the master list (a `CheatList`, built with `AddCode` copies). Each code's default is kept, for `Remember`.
  - `void Apply()`: `System::SetCheatList` with a copy of the master list. It's called from `Host::OnGameChanged`, which
    ends every `UpdateRunningGame` (the boot's included), right after the core has dropped its list.
  - `std::vector<Code> List()`, with `struct Code { std::string group, name; bool manual, on; };` (the group empty for
    `Ungrouped`).
  - `std::optional<std::string> SetOn(u32 index, bool on)`: the master and the live list (`CheatList::SetCodeEnabled`,
    which undoes a code switched off); then the app's file written as `Remember(…)` gives it, to `<file>.goosetmp`
    then `FileSystem::RenamePath` over it (`EnsureDirectoryExists` on `cheats/` first). It returns the reason when
    writing fails, and the code stays as switched for this session.
  - `std::optional<std::string> ApplyOnce(u32 index)`: `CheatCode::Apply()` of a manual code; "This code isn't applied
    by hand." otherwise.
  - `void Clear()`, from `Host::OnSystemDestroyed`. The master list is built in `BootPendingGame`, before
    `BootSystem`, so it's ready for the boot's own `OnGameChanged`.
- `Host::LoadSettings` sets `g_settings.enable_cheats = false`, with the comment: the host owns the game's code list
  and sets it itself (the core applies any list it's given). With this on, the core would load `<disc title>.cht` at
  every disc change. Nothing writes it to `settings.ini`.
- Produces (Kotlin):
  - `class PatchCode(val index: Int, val group: String, val name: String, val manual: Boolean, val on: Boolean)` and
    `class PatchCodes(val fileName: String, val codes: Array<PatchCode>)`. `fileName` is
    `<SafeFileName(game title)>.cht`, for the panel's "Add …" line. Native constructors cached at `JNI_OnLoad`.
  - `NativeLibrary`/`GameCoreApi`: `getPatchCodes(): PatchCodes` (empty without a game),
    `setPatchCode(index: Int, on: Boolean): String?`, `applyPatchCode(index: Int): String?`. The fake has `codes`, logs
    `"setPatchCode 2 true"`, and has `codeError`.
  - `CoreMessages.translate("CheatsLoadWarning", "2 cheat(s) are enabled. This may crash games.")` is "2 patch codes
    are on"; with 1, "1 patch code is on".

- [ ] **Step 1: Write the failing tests.**
  - `TEST(AndroidPatchCodes, …)`:
    - `ParseAndFormatRoundTrip`, `ParseSkipsCommentsAndJunk`, `FormatLeavesOutTabs`, `SafeFileNameReplacesWhatFileSystemsRefuse`
      (`Ace Combat 3: Electrosphere` → `Ace Combat 3_ Electrosphere`), `FileNameIsTheKey`
      (`SLPS-02020` → `SLPS-02020.patchcodes`);
    - `CombinePlayerWinsOverDatabase` (the same group and name in both → one entry, `from_player`);
    - `CombineDedupesAcrossDiscs` (two discs' identical blocks → once);
    - `CombineDatabaseStartsOff`, `CombinePlayerMarkStartsOn`;
    - `CombineHonoursTheAppsFile` (Review Focus 4: a `[*]` code with an `off` switch → off; a database code with `on`
      → on);
    - `CombineManualIsNeverOn`;
    - `RememberKeepsSwitchesForAbsentCodes`, `RememberWritesOnlyDifferences`.
  - `CoreMessagesTest.theCodesWarningCountsThem`.
  - `GameCallsTest.withoutAGameThereAreNoCodes` (`codes` empty, `setPatchCode` "No game is running.").
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** the desktop build and `common-tests`, `run-native-tests.sh`, the JVM and instrumented suites, and
  `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`** with Ace Combat 3 (the set). The boot's logcat says how many codes loaded from
  each disc's package. The boot takes at most about 0.2 s longer than before this task (`Boot Filename` to `Game
  started`, three runs each). Put an `Ace Combat 3 - Electrosphere (Japan).cht` with one `[*]` code in `files/data/cheats`, and
  the boot shows "1 patch code is on". `settings.ini` has no new `EnableCheats` line.
- [ ] **Step 6: Commit** (message shown first): `Android: each game's patch codes, from the database for every disc and
  the player's files` (touches `src/util`).

### Task 7: the Patch codes panel

**Files:**
- Create: `app/PatchCodeList.kt`, `ui/PatchCodePanel.kt`, `docs/design-system/icons/code.svg`
- Modify: `ui/theme/GsIcons.kt` (regenerated by `python scripts/branding/generate-gsicons.py`), `app/InGameMenu.kt`,
  `ui/GameMenu.kt`
- Test: `test/…/app/PatchCodeListTest.kt`, `test/…/app/InGameMenuTest.kt`, `androidTest/…/ui/GameMenuTest.kt`,
  `androidTest/…/ui/FocusAuditTest.kt`, `androidTest/…/ui/OrientationLayoutTest.kt`

**Interfaces:**
- Consumes: `PatchCode`, `PatchCodes`, `getPatchCodes`, `setPatchCode`, `applyPatchCode` (Task 6).
- Produces:
  - `data class PatchCodeRow(val index: Int, val name: String, val note: String?, val manual: Boolean, val on: Boolean)`
  - `data class PatchCodeGroup(val title: String?, val codes: List<PatchCodeRow>)`
  - `object PatchCodeList`:
    - `fun split(name: String): Pair<String, String?>`: the name up to its first `:` and the rest as the note, both
      trimmed. A blank part keeps the whole name, with no note;
    - `fun groups(codes: List<PatchCode>): List<PatchCodeGroup>`: by the rule under "Calls made…".
  - `data class CodePanel(val groups: List<PatchCodeGroup>? = null, val fileName: String = "", val applied: String? = null,
    val error: String? = null)`. `groups` is null while it's read. `GameMenuState` gains `val codes: CodePanel? = null`.
  - `GameMenuActions`: `openCodes()`, `closeCodes()`, `setCode(index: Int, on: Boolean)`, `applyCode(index: Int)`.
  - `InGameMenu`:
    - `openCodes` closes the other panels and reads `getPatchCodes()` on `game`;
    - `setCode` changes the row at once, then calls the core. A failure puts the row back, with `error` "Couldn't
      switch <name>: <reason>";
    - `applyCode` sets `applied` to "Applied: <name>", or `error` "Couldn't apply <name>: <reason>";
    - none of them closes the menu or resumes.
  - `GameMenu`: a **Patch codes** row after Screenshot (`GsIcons.code`), always shown, enabled when booted, current while
    its panel is open; the B order and `CameFrom` gain it.
  - `@Composable fun PatchCodePanel(panel: CodePanel, actions: GameMenuActions, modifier: Modifier)`, framed as the
    other side panels:
    - the title "Patch codes", then a `Notice(Success)` with `applied`, or a `Notice(Danger)` with `error`;
    - a scrolling column (4 dp padding): each group's `Eyebrow(title)` when it has one, then its codes. A switchable code
      is a `SwitchRow(name, on, note = note)`. A manual code is an Apply row: a `focusRing`ed row (`Role.Button`) with
      `LabelValue` of the name in `titleMedium` and "Apply" in `bodyLarge` `inkMuted`, and its note under it, as
      `SwitchRow` has;
    - with no codes: "No patch codes for this game." (`bodyLarge`), then "Add <fileName> to the cheats folder in your
      data folder.", the file name in `type.mono`, with the title focusable so B reaches the panel;
    - the first row focused on opening; `KeyHints` A Select, B Back.
  - `code.svg`: `<` and `>` as two polylines on the 24 px grid, 2 px strokes, square caps, mitred joins, stroked
    `#14171c`, as the system's icons are drawn.

- [ ] **Step 1: Write the failing tests.**
  - `PatchCodeListTest`:
    - `aColonSplitsTheNote` ("Infinite HP Slot 1:Turn Off When Out of Battle");
    - `aNameWithoutAColonHasNoNote`;
    - `aLeadingColonKeepsTheName`;
    - `groupsKeepTheirOrderAndOtherComesLast`;
    - `allUngroupedHasNoHeading`.
  - `InGameMenuTest`:
    - `codesOpenBesideTheMenu`;
    - `switchingACodeKeepsTheMenuOpen` (`"setPatchCode 2 true"`, no resume);
    - `aFailedSwitchPutsTheRowBack`;
    - `applyingSaysSo`;
    - `aGameWithNoCodesNamesTheFile`.
  - `GameMenuTest`:
    - `patchCodesSitsAfterScreenshot`;
    - `aCodeIsASwitchRow` (`assertIsOff()`, then Enter → `setCode(…, true)`);
    - `aManualCodeHasApply`;
    - `groupsShowTheirHeadings`;
    - `noCodesSaysWhichFileToAdd`;
    - `bLeavesTheCodePanelForItsRow`.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement.** Draw `code.svg` and regenerate `GsIcons.kt`. Add `gameMenuCodes()` and
  `gameMenuNoCodes()` to `FocusAuditTest`, and the code panel with long names and notes at every size and text scale to
  `OrientationLayoutTest`.
- [ ] **Step 4: Run** the JVM and instrumented suites and `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`** with Ace Combat 3: Patch codes lists the database's codes, all off.
  - Switch one on: it shows on at once, and the menu stays open.
  - Resume: the code acts (pick one visible on the title screen or in the first mission).
  - `files/data/cheats/SLPS-02020.patchcodes` names it, and the data folder gets it on the next push.
  - Exit and play again: it's still on.
- [ ] **Step 6: Commit** (message shown first): `Android: a Patch codes panel beside the in-game menu`.

### Task 8: docs, and the checks on the emulator and the Thor

**Files:**
- Modify: `docs/android.md`, `docs/design-system.md`

- [ ] **Step 1: `docs/android.md`.**
  - The library section says how discs are grouped (the three rules, and that a playlist takes the discs it lists),
    which disc a tile starts, and the summed play time.
  - "The in-game menu" gains Change disc, Patch codes (where codes come from, the `.patchcodes` file, that the
    player's `.cht` files are only read, and the exact file to add), one set of states per game with the migration and
    "Other saves", and states finding their disc.
  - "Known limits":
    - drop "An M3U's discs are listed on their own as well.";
    - add that desktop lists a game's states under disc 1;
    - add that a name-grouped game the database doesn't know keeps a memory card per disc title;
    - add that the migration runs once per device;
    - add that there's no disc hotkey.
- [ ] **Step 2: `docs/design-system.md`.** Under "Library and game screens":
  - `GameMenu`'s rows gain Change disc (with its value) and Patch codes;
  - `SaveSlotPanel` gains "Other saves" and the disc line;
  - new `DiscPanel` and `PatchCodePanel` entries;
  - "Icons" mentions `code`.
- [ ] **Step 3: Everything green:**
  - `cmake --build build` and `build/bin/common-tests`;
  - `./gradlew check`, `testDebugUnitTest`, `connectedDebugAndroidTest` and `assembleDebug`;
  - `run-native-tests.sh`.
- [ ] **Step 4: The emulator's full pass**, recorded under "Results on the emulator": Task 2 Step 5, Task 3 Step 5,
  Task 4 Step 5, Task 5 Step 5, Task 6 Step 5 and Task 7 Step 5 in one run. Also check:
  - two made-up ISOs, `Test (Disc 1).iso` and `Test (Disc 2).iso`, unknown to the database, show as one tile, "Test",
    and a third, `Test (Disc 1).cue`, splits them back into three;
  - a state copied from another folder pick loads with the library's disc.
- [ ] **Step 5: The Thor** (`adb -s 64dc3c35`), **only after the user says go**, recorded under "Results on the AYN
  Thor". Word every step with Back, Select + Start and the D-pad. Check:
  - the library's one tile for Ace Combat 3;
  - Change disc and its panel with the pad alone;
  - Patch codes with a long list, scrolling and wrapping at the Thor's 640 dp;
  - Review Focus 4 with a `.cht` the user writes on desktop;
  - nothing pressed in the panels reaches the game.
- [ ] **Step 6: Commit** (message shown first): `Docs: multi-disc games, Change disc and patch codes on Android`.
- [ ] **Step 7: Finish the branch** (`superpowers:finishing-a-development-branch`): push to
  `origin/claude/android-in-game-menu-part2` as `ilyalissoboi`, open the PR to `master`, and request the whole-branch
  review (`superpowers:requesting-code-review`).
