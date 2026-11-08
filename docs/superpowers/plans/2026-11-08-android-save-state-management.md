<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android save state management — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish save states on Android: delete, Undo load in the menu, desktop's shared (global) slots as a tab, and
a game's Saves from its library tile.

**Architecture:** The native host gains shared-slot calls (times, preview with the game a state holds, save, load,
delete) and a boot from any state, all through the core's own file names; a state's game is found by a new pure
matcher in `AndroidDiscGroups`, tested on desktop. On the Kotlin side, `SaveReader` reads a game's, its other discs'
and the shared slots into `SlotEntry` lists for both the in-game slot panel (`InGameMenu`) and a new library page
(`SavesPage`), so the two show the same rows. Switching games from a shared slot goes through Exit game's own path,
then `GameStarts` starts the other game from the state once the first has stopped.

**Tech Stack:** C++20 in `android/app/src/cpp` and Android-only blocks of `src/`, JNI, googletest (`common-tests`),
Kotlin, coroutines, Jetpack Compose, JUnit 4 (JVM) and AndroidX instrumented tests on the emulators.

**Spec:** `docs/superpowers/specs/2026-11-08-android-save-state-management-design.md`

## Global Constraints

- Android-only code in `android/` or behind `__ANDROID__` (`src/core/hotkeys.cpp`, `src/core/system.{h,cpp}`);
  `src/util/android_disc_groups.*` is GooseStation's own Android file, built and tested everywhere. No desktop
  behaviour changes. Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor its closed Android app.
- File names stay the core's: `savestates/<key>_<n>.sav`, `<key>_resume.sav`, shared `savestates/savestate_<n>.sav`
  (`System::GetGlobalSaveStateFileName`), backups `Path::ReplaceExtension(path, "bak")`. Ten slots each
  (`SaveSlots.COUNT`, `GLOBAL_SAVE_STATE_SLOTS`).
- Save, load, delete and undo run on `AppController.gameDispatcher`, never the main thread.
- `docs/design-system.md` is binding: controller-first, focus always visible, no window dialogs (`OverlayPanel` or
  in-panel questions), `KeyHints` on each panel and page, questions put the destructive answer first (`Danger`) and
  focus Cancel (`Ghost`). New screens and states go into `FocusAuditTest` and `OrientationLayoutTest`.
- **Copy, exact** (from the spec, plus the plan's own where marked *plan*):
  - Tabs: "This game", "Shared". KeyHints "L1 R1 Switch"; Load and Saves page add "X Delete"; library foot
    "A Play", "X Saves", "Y Game settings".
  - No key: "This game can't keep save states of its own."; no saves: "No saves yet for this game."
  - Cross-game: "Load Crash Bandicoot from shared slot 3? This game stops." (answer "Load"); not in the library:
    "Shared slot 3 needs Crash Bandicoot (SCUS-94900), which isn't in your library."
  - Delete: "Delete the save in slot 3?" (answer "Delete"); *plan:* "Delete the save in shared slot 3?", "Delete your
    resume state?", "Delete disc 2's save in slot 3?" ("Delete the other disc's save in slot 3?" without a disc
    number), "Couldn't delete that save: <reason>".
  - *plan:* "Saved to shared slot 3", "Loaded shared slot 3", "Couldn't save to shared slot 3: <reason>", "Couldn't load
    shared slot 3: <reason>", "Replace the save in shared slot 3?", "Couldn't read that save."
  - Undo: row "Undo load"; "Undid the last load"; *plan:* its value "Nothing to undo" while there's none. Hardcore:
    "Loading a state is off in hardcore mode" (Undo hotkey, Saves page A); rows' value "Off in hardcore mode".
- Test states are made by the tests, never a player's saves. Emulators only; the Thor needs the user's go-ahead.
- Commit each task; messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`, shown to the user
  after each commit.

Kotlin paths below are under `android/app/src/main/java/io/github/ilyalissoboi/goosestation/` (tests under
`android/app/src/test/java/…` and `android/app/src/androidTest/java/…`).

## Running the tests

- Native, desktop: `cmake --build build --target common-tests && build/bin/common-tests
  --gtest_filter='AndroidDiscGroups*'`, then build `duckstation-qt` to check desktop still compiles (a `build` dir
  configured as earlier plans did, if none exists).
- JVM: `cd android && ./gradlew -q testDebugUnitTest --tests '<Class>'`, then `./gradlew -q ktlintCheck`.
- One instrumented class: `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.<pkg>.<Class>`.
- Each task touching the UI or the bridge ends with `android/scripts/run-instrumented-tests.sh quick`, read from the
  result XML under `app/build/outputs/androidTest-results/`, not Gradle's verdict.

## Review Focus

1. **A pick before the shared slot's game is known.** Shared rows show before their previews are read; A on one whose
   `holds` isn't read yet must read it first (on the game dispatcher) and then decide, never load it as this game's.
   Test: `InGameMenuTest.aSharedPickBeforeItsPreviewReadsItFirst`.
2. **A shared state from another game reaching the running one** by any path (a stale `holds`, a corrupt header).
   The native `LoadSharedState` refuses a state that doesn't match the running game ("That save is from another
   game."), and an unreadable one fails. Test: `GameCallsTest.aSharedStateFromAnotherGameIsRefused`.
3. **The list moving under the delete question.** The question holds the entry it asked about (`StateRef`), and
   deletes that, not whatever is focused; after it, the focus goes to the next row (else the previous, else the
   heading). Test: `InGameMenuTest.aDeleteRemovesTheSaveItAskedAbout` and `GameMenuTest.afterADeleteTheNextRowIsFocused`.
4. **Hardcore would start for the other game.** A cross-game load, or A on the Saves page, while
   `hardcoreWouldStart()` is refused before the running game is left, with the hardcore message. Test:
   `InGameMenuTest.aSwitchIsRefusedWhenHardcoreWouldStart`, `SavesPageTest.hardcoreRefusesAStartButDeletes`.
5. **Cancelling half-way through the switch.** Cancel on the exit's card question or the resume question stays in
   the game and forgets the switch; a later plain Exit game doesn't start the other game. Test:
   `InGameMenuTest.aCancelledSwitchIsForgotten`.

---

### Task 1: Core hotkeys out, undo refused in hardcore

**Files:**
- Modify: `src/core/hotkeys.cpp:66-118, 693-750` — `HotkeyLoadStateSlot`, `HotkeySaveStateSlot`, both
  `MAKE_*_STATE_HOTKEY` macros and their 40 uses inside `#ifndef __ANDROID__` (comment: on Android the app's save
  path has the slots, under the game's key; these would key states by the disc's serial).
- Modify: `src/core/system.h` (Android block near 552), `src/core/system.cpp` (by `CanUndoLoadState`, 5040):
  `#ifdef __ANDROID__ void ClearUndoLoadState();` — resets `s_undo_load_state`.
- Modify: `android/app/src/cpp/achievements_bridge.cpp:372` — `OnAchievementsHardcoreModeChanged(true)` calls
  `System::ClearUndoLoadState()` when `!System::IsShutdown()`.
- Modify: `android/app/src/cpp/android_host.{h,cpp}` — `UndoLoadState()` returns false in hardcore
  (`Achievements::IsHardcoreModeActive()`); new `bool CanUndoLoadState()` (false in hardcore or with no system);
  drop `LEFT_OUT_HOTKEY_PREFIXES` and its use in `GetOfferedHotkeys()`, and rewrite the comment at 138-143: the
  menu has Change disc and Reset with their questions; the core's per-slot and global hotkeys aren't built on Android.
- Modify: `android/app/src/cpp/jni_bridge.cpp`, `core/NativeLibrary.kt`: `external fun canUndoLoadState(): Boolean`.
- Test: `androidTest/…/core/GameCallsTest.kt`, `androidTest/…/core/AchievementsOfflineTest.kt`.

**Interfaces:** Produces `NativeLibrary.canUndoLoadState(): Boolean`.

- [ ] **Step 1: Write the failing tests.**
  - `GameCallsTest.theCoresSlotHotkeysAreGone`: in padtest, `pressHotkeyForTest("SaveGameState1")` and
    `("SaveGlobalState1")` are false, and `savestates/` stays empty.
  - `GameCallsTest.undoFollowsALoad`: `canUndoLoadState()` false at boot; after `saveState(PADTEST_SERIAL, 1)` and
    `loadState(PADTEST_SERIAL, 1)` true; after `undoLoadState()` (true) false again.
  - `AchievementsOfflineTest.undoIsRefusedInHardcore`: hardcore online (as `HARDCORE_SETTINGS`' test at 290);
    `canUndoLoadState()` false, `undoLoadState()` false.
  - `AchievementsOfflineTest.turningHardcoreOnDropsTheUndoState`: signed in with `SIGNED_IN_SETTINGS` (casual), save
    and load slot 1 (`canUndoLoadState()` true), then turn `[Cheevos] ChallengeMode` on and apply it as the
    Achievements page does; once `isHardcoreActive()`, `canUndoLoadState()` is false.
- [ ] **Step 2: Run them; they fail** (`canUndoLoadState` unresolved; the hotkeys exist).
- [ ] **Step 3: Implement** as listed under Files.
- [ ] **Step 4: Run** the two classes, then `common-tests` and the `duckstation-qt` build (desktop keeps every hotkey).
- [ ] **Step 5: Commit** "Android: the core's slot hotkeys are gone, and undo is refused in hardcore".

### Task 2: Which library game a state holds

**Files:**
- Modify: `src/util/android_disc_groups.{h,cpp}`
- Test: `src/common-tests/android_disc_groups_tests.cpp`

**Interfaces:** Produces
`std::optional<u32> AndroidDiscGroups::MatchStateGame(const Game& game, std::string_view serial, std::string_view
media_path, u32 subimage_index)` — nullopt when the state isn't [game]'s; else the disc number it was made on, 0 when
the game has no discs or the disc isn't known. Rules, in order:
1. An `.m3u` media path matches only a playlist game whose path's file name is the same, ignoring case; the disc is
   `FindStateDisc()`'s.
2. A non-empty serial equal to `game.key` or to any disc's serial matches; the disc is `FindStateDisc()`'s, or 0.
3. A media file name equal (ignoring case) to `game.path`'s or a disc's file name matches, with that disc.
4. A state with no serial and no media path matches a game with an empty key and no discs (the BIOS alone), disc 0.

- [ ] **Step 1: Write the failing tests** (`TEST(AndroidDiscGroups, …)`, with `TwoDiscs(bool playlist)`):
  `MatchStateGamePlaylistByName` (`"/x/FF7.m3u"`, sub-image 1 → 2u; a playlist named `Other.m3u` → nullopt),
  `MatchStateGameByKey` (a PS-EXE game `{key "HASH-1", path "/g/padtest.psexe", no discs}`, serial `"HASH-1"` → 0u),
  `MatchStateGameByDiscSerial` (`"SCUS-94164"` → 2u), `MatchStateGameByFileName` (serial `""`,
  `"/elsewhere/FF7 (Disc 2).CHD"` → 2u), `MatchStateGameOtherGame` (serial `"SLUS-00001"`, `"/g/crash.chd"` →
  nullopt), `MatchStateGameBiosAlone` (empty game, empty serial and path → 0u; a game with a key → nullopt).
- [ ] **Step 2: Run** `common-tests --gtest_filter='AndroidDiscGroups*'`: fails to compile.
- [ ] **Step 3: Implement `MatchStateGame`.**
- [ ] **Step 4: Run** again: pass.
- [ ] **Step 5: Commit** "Android: find the game a save state holds".

### Task 3: Native shared slots, delete, preview of a state's game, boot from a state

**Files:**
- Modify: `android/app/src/cpp/android_host.{h,cpp}`, `android/app/src/cpp/jni_bridge.cpp` (the
  `SaveStatePreview` class cache at 987), `core/NativeLibrary.kt`, `core/SaveStatePreview.kt`
- Test: `androidTest/…/core/GameCallsTest.kt`

**Interfaces:**
- Consumes: `MatchStateGame` (Task 2).
- Produces (C++ in `AndroidHost`, JNI in `NativeLibrary` with the same names in lower camel case):
  - `std::vector<s64> GetSharedStateTimes()` — 11 entries, `[n]` shared slot n, `[0]` always 0.
  - `struct SaveStatePreview { std::optional<Picture> picture; u32 disc; std::string serial, title, game_path,
    game_title; }` and `GetStatePreview(std::string key, s32 slot, bool shared, std::string game_path)`: the state's
    picture, its header's serial and title, and the game it holds: the running game when `MatchStateGame` matches it,
    else the listed game whose path is `game_path` (the library page's game), else the first listed game that
    matches; `game_path`/`game_title` empty when none does. `disc` is the number in that game, shown only for
    multi-disc games by the caller. The old `GetSaveStatePreview(serial, slot)` becomes
    `GetStatePreview(serial, slot, false, <running game's path>)`.
  - Kotlin `class SaveStatePreview(val picture: ArgbImage?, val disc: Int, val serial: String = "", val title: String
    = "", val gamePath: String = "", val gameTitle: String = "")`.
  - `SaveSharedState(s32 slot)`, `LoadSharedState(s32 slot)` → `std::optional<std::string>`: `RunOnSlot` gains a
    `shared` flag (no key needed; path `GetGlobalSaveStateFileName`). Load refuses as `LoadState` does (hardcore,
    missing file) and also when the header doesn't `MatchStateGame` the running game: "That save is from another
    game." Save keeps `create_save_state_backups`.
  - `DeleteSaveState(std::string key, s32 slot, bool shared)` → `std::optional<std::string>`: deletes the `.sav`,
    then its `.bak` if present; a missing `.sav` is "There's no save in this slot."; an empty key without `shared` is
    `NO_SERIAL`. Works with no game running.
  - `StartGameFromState(std::string path, std::string key, s32 slot, bool shared)`: `StartGame` takes the boot state
    as `(key, slot, shared)` (the resume path becomes `(game.key, -1, false)`); the disc comes from the state as the
    resume's does, its failure "Couldn't start from that save: <MissingDiscMessage>". `startGame(path, disc, resume)`
    stays as it is for Kotlin.

- [ ] **Step 1: Write the failing tests** in `GameCallsTest`:
  - `sharedSlotsSaveLoadAndShowTheirGame`: in padtest, `saveSharedState(3)` null; `getSharedStateTimes()[3] > 0`,
    the rest 0; `getStatePreview("", 3, true, "")` has a picture, `serial == PADTEST_SERIAL` and `gamePath` the exe's
    path; `loadSharedState(3)` null; `savestate_3.sav` exists, and no `HASH-…_3.sav`.
  - `aSharedStateFromAnotherGameIsRefused`: copy padtest's `savestate_3.sav` in, but boot the padtest disc image (a
    different game, `withPadtestDisc` as the Change disc tests do; skipped without `GOOSE_TEST_DATA`);
    `loadSharedState(3)` is "That save is from another game."
  - `deleteTakesTheSaveAndItsBackup`: with `CreateSaveStateBackups` on, save slot 2 twice and shared 4 twice;
    `deleteSaveState(PADTEST_SERIAL, 2, false)` and `deleteSaveState("", 4, true)` null; `.sav` and `.bak` gone;
    slot 1, saved before, is still there; deleting again says "There's no save in this slot."
  - `deleteWorksWithNoGameRunning`: a written `SLUS-00001_5.sav` and `.bak` go; `deleteSaveState("", 5, false)` is
    the no-serial message.
  - `aGameStartsFromASharedState`: save shared 6, stop, `startGameFromState(exe, "", 6, true)`: the game starts, no
    `onError`, and the log says "Starting '…' from 'savestate_6.sav'" (read with the test's log reader, or
    `onGameStarted` and `getCurrentDisc()` if the log isn't reachable).
- [ ] **Step 2: Run them; they fail** (functions unresolved).
- [ ] **Step 3: Implement** the functions above; the JNI preview constructor takes the four new strings.
- [ ] **Step 4: Run** `GameCallsTest`, then `quick`.
- [ ] **Step 5: Commit** "Android: shared save slots, delete and a boot from any state in the host".

### Task 4: The Kotlin save lists and their reader

**Files:**
- Create: `app/SaveLists.kt` (pure), `app/SaveReader.kt`
- Modify: `app/GameCoreApi.kt`, `app/InGameMenu.kt` (move `entries()`/`focus()` out of its companion, read through
  `SaveReader`; behaviour unchanged), `test/…/app/FakeGameCore.kt`
- Test: `test/…/app/SaveListsTest.kt`, `test/…/app/SaveReaderTest.kt`, existing `InGameMenuTest` stays green

**Interfaces:**
- Consumes: Task 1 and 3's `NativeLibrary` calls.
- Produces:
  - `data class StateRef(val key: String, val slot: Int, val shared: Boolean = false)` — `key` ignored when shared.
  - `enum class SlotTab { ThisGame, Shared }`
  - `data class HeldGame(val title: String, val serial: String, val path: String?)` — `title` the library's when
    `path != null`, else the state's own; `val label` = `title` when in the library, else `serial.ifBlank { title }`.
  - `SlotEntry` gains `shared: Boolean = false`, `holds: HeldGame? = null`, and
    `fun SlotEntry.ref(key: String): StateRef` = `StateRef(owner ?: key, slot, shared)`.
  - `GameCoreApi` gains `getSharedStateTimes(): LongArray`, `getStatePreview(state: StateRef, gamePath: String):
    SaveStatePreview?`, `saveSharedState(slot: Int): String?`, `loadSharedState(slot: Int): String?`,
    `deleteState(state: StateRef): String?`, `canUndoLoadState(): Boolean`. `FakeGameCore` gets `sharedTimes`,
    `sharedHolds: HashMap<Int, SaveStatePreview>`, `deleteError`, `canUndo`, and logs "saveSharedState 3",
    "loadSharedState 3", "deleteState KEY 3" / "deleteState shared 3".
  - `object SaveLists`: `entries(mode, times)` and `focus(mode, entries, last)` (moved as they are);
    `shared(mode: SlotMode, times: LongArray): List<SlotEntry>` (Save: slots 1–10, Load: saved ones; `shared = true`);
    `held(preview: SaveStatePreview): HeldGame?` (null when `serial`, `title` and `gamePath` are all empty);
    `deleteQuestion(entry: SlotEntry): String`, `where(entry: SlotEntry): String` ("slot 3", "shared slot 3", "your
    resume state") — the copy in Global Constraints.
  - `class SaveReader(api: GameCoreApi, game: CoroutineDispatcher)`, suspend, each call on `game`:
    `own(key: String, mode: SlotMode): List<SlotEntry>`, `others(key: String, discs: List<GameDisc>):
    List<SlotEntry>` (today's Other saves), `shared(mode: SlotMode): List<SlotEntry>`, and `preview(entry: SlotEntry,
    key: String, gamePath: String, multiDisc: Boolean): SlotEntry` (picture, disc when multi-disc, `holds` for a
    shared one).

- [ ] **Step 1: Write the failing tests.** `SaveListsTest`: `sharedSaveListsTenSlots`, `sharedLoadListsSavedOnes`
  (times `[0, 0, 0, 5]` → one entry, slot 3, shared), `heldGameLabelsByLibraryTitleElseSerial`
  (`gamePath "/g/crash.chd", gameTitle "Crash Bandicoot"` → label "Crash Bandicoot"; no path, serial "SCUS-94900",
  title "CRASH" → label "SCUS-94900"), `deleteQuestionsSayWhichSave` (the five strings from Global Constraints).
  `SaveReaderTest.sharedPreviewsSayTheGameTheyHold` (fake `sharedHolds[3]` → entry's `holds.path`).
- [ ] **Step 2: Run them; they fail** (unresolved).
- [ ] **Step 3: Implement**, and move `InGameMenu.openSlots`' reads onto `SaveReader`.
- [ ] **Step 4: Run** `SaveListsTest`, `SaveReaderTest`, `InGameMenuTest`, `ktlintCheck`.
- [ ] **Step 5: Commit** "Android: save lists and their reader, shared slots among them".

### Task 5: This game and Shared tabs in the slot panel

**Files:**
- Modify: `app/InGameMenu.kt`, `ui/SlotPanel.kt`, `ui/GameMenu.kt` (BackHandler order; the panel's tab switch keeps
  the focus as `AchievementsPanel`'s does), `docs/design-system.md` (`SaveSlotPanel`), `docs/android.md` (the
  in-game menu's slot panel)
- Test: `test/…/app/InGameMenuTest.kt`, `androidTest/…/ui/GameMenuTest.kt`, `FocusAuditTest.kt`,
  `OrientationLayoutTest.kt`

**Interfaces:**
- Consumes: Task 4.
- Produces: `SlotPanel` gains `tab: SlotTab = SlotTab.ThisGame`, `shared: List<SlotEntry>? = null`,
  `hasKey: Boolean = true`; `GameMenuActions.switchTab()` switches the slot panel's tab when it's open (the
  Achievements panel's otherwise); `GameMenuActions.pickShared(slot: Int) {}` (default body).

Behaviour: `openSlots(mode)` needs only `booted` (and, in Load, not hardcore); a game without a key opens on Shared,
its This game tab showing the no-key line and no rows. Shared rows are read on opening (both tabs read at once, so a
switch shows at once). Save to a shared slot: empty saves, saved asks "Replace the save in shared slot 3?". Load of a
shared slot whose `holds` matches the running game (`holds.path == running.path`) loads it, "Loaded shared slot 3",
not remembered as the last slot. A shared row shows "Slot 3", then `holds.label` (`body`, `ink`, wrapping), then
"Saved · Disc 2" / "Empty", then the time. PanelTabs heads the panel ("Save state"/"Load state" moves under it as the
panel's title stays for TalkBack — builder's call within the system); L1/R1 switch on key-up.

- [ ] **Step 1: Write the failing tests.** `InGameMenuTest`: `theSlotPanelOpensOnThisGame`,
  `l1R1SwitchToSharedAndBack`, `aGameWithoutAKeyOpensOnShared` (Save row works for `serial = ""`),
  `savingToAnEmptySharedSlotSaves` ("saveSharedState 2", "Saved to shared slot 2"), `replacingASharedSlotAsks`,
  `loadingThisGamesSharedSlotLoads`, `aSharedPickBeforeItsPreviewReadsItFirst` (preview gated by a
  `CompletableDeferred`; the pick waits, then loads only because it matches). `GameMenuTest`:
  `l1SwitchesTheSlotPanelsTab` (focus on Shared's first row), `aSharedRowSaysItsGame`, `noKeyGameSaysSo`.
  `FocusAuditTest.slotPanelSharedTab`, `slotPanelNoKey`; `OrientationLayoutTest.theSharedSlotTab` (a 60-character
  title in `holds`).
- [ ] **Step 2: Run them; they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the JVM tests, the three instrumented classes, then `quick`.
- [ ] **Step 5: Commit** "Android: This game and Shared tabs in the slot panel".

### Task 6: Delete in the Load panel

**Files:**
- Modify: `app/InGameMenu.kt`, `ui/SlotPanel.kt`, `ui/components/KeyHints.kt` (`PadGlyphs.action` = `Button99`, X,
  "an item's second action, such as Delete"), `docs/design-system.md` (`KeyHints`, `SaveSlotPanel`), `docs/android.md`
- Test: `InGameMenuTest.kt`, `GameMenuTest.kt`, `FocusAuditTest.kt`

**Interfaces:**
- Consumes: Task 4's `deleteState`, `SaveLists.deleteQuestion`.
- Produces: `SlotPanel.deleting: SlotEntry? = null`; `GameMenuActions.askDelete(entry: SlotEntry) {}`,
  `confirmDelete() {}`, `cancelDelete() {}`. A `SlotRow` takes `onLongClick: (() -> Unit)?` and the panel handles
  `Key.ButtonX` on a row's key-up.

Behaviour: Load only (Save stays replace-only). X or a long press on any Load row (slot, Resume, Other saves, shared)
asks `SaveLists.deleteQuestion(entry)` in the panel (Delete `Danger`, Cancel `Ghost` focused). Delete runs
`deleteState(entry.ref(key))` on the game dispatcher, then re-reads the open tab and focuses the next row (else the
previous, else the heading); a failure shows "Couldn't delete that save: <reason>" as the panel's `Danger` notice.
B on the question cancels it, back to the row. KeyHints in Load: "A Load", "X Delete", "L1 R1 Switch", "B Back".

- [ ] **Step 1: Write the failing tests.** `InGameMenuTest`: `xAsksBeforeDeleting` (nothing deleted until confirm),
  `aDeleteRemovesTheSaveItAskedAbout` (ask for slot 3, then the list re-reads with slot 3 moved: "deleteState KEY
  3"), `deletingAnOtherDiscsSaveUsesItsSerial`, `deletingASharedSlot`, `aFailedDeleteSaysWhy`. `GameMenuTest`:
  `xOnARowAsksWithCancelFocused`, `afterADeleteTheNextRowIsFocused`, `aLongPressAsksToo`, `bCancelsTheDelete`.
  `FocusAuditTest.slotPanelDeleteQuestion`.
- [ ] **Step 2: Run them; they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the tests, then `quick`.
- [ ] **Step 5: Commit** "Android: delete a save from the Load panel".

### Task 7: Undo load in the menu

**Files:**
- Modify: `app/InGameMenu.kt`, `ui/GameMenu.kt`, `ui/theme/GsIcons.kt` (new `undo`), create
  `docs/design-system/icons/undo.svg` (24 px grid, 2 px strokes, square ends, as `rewind.svg`: an arrow curving back
  to the left), `docs/design-system.md` (`GameMenu` rows, the icon list), `docs/android.md` (the menu, the Undo
  hotkey)
- Test: `InGameMenuTest.kt`, `GameMenuTest.kt`, `FocusAuditTest.kt`

**Interfaces:**
- Consumes: `GameCoreApi.canUndoLoadState()`, `undoLoadState()`.
- Produces: `GameMenuState.canUndo: Boolean = false`, read with the hardcore state in `readGameState()`;
  `GameMenuActions.undoLoad() {}`.

Behaviour: the row "Undo load" (`GsIcons.undo`) follows Load state; enabled when `booted && canUndo && !hardcore`;
value "Off in hardcore mode" in hardcore, "Nothing to undo" without one. A runs the undo on the game dispatcher,
reads the disc for a multi-disc game, says "Undid the last load" (or "No load to undo" if the core had none) and
resumes. The Undo hotkey says "Loading a state is off in hardcore mode" in hardcore and calls nothing.

- [ ] **Step 1: Write the failing tests.** `InGameMenuTest`: `undoLoadUndoesAndResumes`,
  `undoLoadIsOffInHardcore` (no "undoLoadState" call), `theUndoHotkeyIsRefusedInHardcore`,
  `canUndoIsReadAsTheMenuOpens`. `GameMenuTest`: `undoLoadFollowsLoadState`, `undoLoadSaysNothingToUndo`.
  `FocusAuditTest.menuWithUndoLoad`.
- [ ] **Step 2: Run them; they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the tests, then `quick`.
- [ ] **Step 5: Commit** "Android: Undo load in the in-game menu, refused in hardcore".

### Task 8: Loading a shared slot from another game

**Files:**
- Modify: `app/InGameMenu.kt`, `app/GameStarts.kt`, `app/AppController.kt` (`gameMenu`'s `onExit`, `exitGame`,
  `onGameStopped`, `gameStarts`, a `startGameFromStateNow` beside `startGameNow`), `core/NativeLibrary.kt` (already
  has `startGameFromState`), `ui/SlotPanel.kt` (the question), `docs/android.md`
- Test: `InGameMenuTest.kt`, `GameStartsTest.kt`, `GameMenuTest.kt`, `FocusAuditTest.kt`

**Interfaces:**
- Consumes: Tasks 3–5.
- Produces:
  - `data class StateStart(val gamePath: String, val state: StateRef)`.
  - `InGameMenu`'s `onExit: (resumeError: String?, then: StateStart?) -> Unit`; `SlotPanel.switching: SlotEntry? =
    null`; `GameMenuActions.confirmSwitch() {}`, `cancelSwitch() {}`.
  - `GameStarts(…, start, startFrom: (GameEntry, StateRef) -> Unit)` and `fun playFromState(entry: GameEntry, state:
    StateRef, refused: (String) -> Unit)`: on the game dispatcher, refuses with the hardcore message when
    `hardcoreWouldStart()`, else `startFrom(entry, state)`.

Behaviour: a Load pick on a shared row whose `holds.path` is another game's: with `holds.path == null`, the panel's
`Danger` notice "Shared slot 3 needs <title> (<serial>), which isn't in your library." (no parentheses without a
serial); with `hardcoreWouldStart()`, the hardcore message; otherwise the in-panel question "Load <title> from shared
slot 3? This game stops." (Load `Danger`, Cancel focused). Load closes the panel and runs `exit()` with the switch
kept: the card question and the resume question come as for Exit game; any Cancel forgets the switch; Keep it and
Replace both go on. `onExit(error, StateStart(holds.path, ref))`. `AppController.exitGame` keeps it, and
`onGameStopped` starts it: the `GameEntry` with that path in `games` through `gameStarts.playFromState(…) {
showError(it) }`, or, gone from the library meanwhile, the not-in-library line as an error.

- [ ] **Step 1: Write the failing tests.** `InGameMenuTest`: `anotherGamesSharedSlotAsksFirst`,
  `confirmingTheSwitchExitsThenStartsIt` (`onExit(null, StateStart("/g/crash.chd", StateRef("", 3, true)))` after the
  resume save), `theSwitchAsksToReplaceTheResumeState`, `theSwitchWarnsWhileACardIsWritten`,
  `aCancelledSwitchIsForgotten` (then plain Exit gives `then = null`), `aSwitchIsRefusedWhenHardcoreWouldStart`,
  `aGameNotInTheLibraryIsNamed`. `GameStartsTest`: `playFromStateStartsFromIt`, `playFromStateIsRefusedInHardcore`.
  `GameMenuTest.theSwitchQuestionFocusesCancel`; `FocusAuditTest.slotPanelSwitchQuestion`.
- [ ] **Step 2: Run them; they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the tests, then `quick`; on the emulator, check by hand with padtest and its disc image: a shared
  state from the exe, loaded in the disc game, asks, saves the disc game's resume state and starts the exe from it.
- [ ] **Step 5: Commit** "Android: a shared slot from another game starts that game".

### Task 9: The library's Saves page

**Files:**
- Create: `app/SavesPage.kt`, `ui/SavesScreen.kt`, `ui/SlotRows.kt` (`SlotRow` and `Line` moved from `SlotPanel.kt`,
  `internal`)
- Modify: `app/AppController.kt` (`Screen.Saves`, `savesPage`, `openSaves(game)`, `closeSaves()` focusing the tile),
  `MainActivity.kt`, `docs/design-system.md` (`SavesScreen`), `docs/android.md`
- Test: `test/…/app/SavesPageTest.kt`, `androidTest/…/ui/SavesScreenTest.kt`, `FocusAuditTest.kt`,
  `OrientationLayoutTest.kt`

**Interfaces:**
- Consumes: Tasks 4, 6 (`deleteQuestion`, `PadGlyphs.action`), 8 (`GameStarts.playFromState`).
- Produces:
  - `data class SavesPageState(val game: GameEntry, val tab: SlotTab, val entries: List<SlotEntry>? = null, val
    others: List<SlotEntry> = emptyList(), val shared: List<SlotEntry>? = null, val deleting: SlotEntry? = null, val
    hardcore: Boolean = false, val error: String? = null)`.
  - `class SavesPage(scope, game: CoroutineDispatcher, reader: SaveReader, api: GameCoreApi, games: () ->
    List<GameEntry>, play: (GameEntry, StateRef, refused: (String) -> Unit) -> Unit)` with `state`, `open(game)`,
    `close()`, `switchTab()`, `pick(entry)`, `askDelete(entry)`, `confirmDelete()`, `cancelDelete()`.
  - `@Composable fun SavesScreen(page: SavesPageState, actions: SavesActions, now: ZonedDateTime, onBack: () -> Unit)`.

Behaviour: the page, in the app's window: `Eyebrow(game.title)`, "Saves" (`headlineMedium`), `PanelTabs` (This game,
Shared; L1/R1), then the Load panel's rows (Resume, slots, Other saves; or the shared slots), scrolling, `KeyHints`
fixed at the foot ("A Start", "X Delete", "L1 R1 Switch", "B Back"). Focus on the first row, or the heading with
none. A game without a key opens on Shared, its This game tab the no-key line. No saves: "No saves yet for this game."
A on a row: `play(game, ref)` for this game's rows; for a shared row, the game `holds.path` names (from `games()`), or
the not-in-library line; `hardcore` (read with `hardcoreWouldStart()` on opening) refuses with the hardcore line as
the page's `Danger` notice. X or a long press asks in an `OverlayPanel` (Delete `Danger`, Cancel focused), focus back
to the next row after. B on the page goes back to the library, focusing the game's tile.

- [ ] **Step 1: Write the failing tests.** `SavesPageTest`: `opensOnThisGameWithItsSaves`,
  `aGameWithoutAKeyOpensOnShared`, `aStartsTheGameFromTheSave`, `aSharedSlotStartsTheGameItHolds`,
  `aSharedSlotOfAGameNotInTheLibraryIsNamed`, `hardcoreRefusesAStartButDeletes`, `deleteAsksThenRemovesAndRereads`.
  `SavesScreenTest`: `theFirstRowIsFocused`, `l1R1SwitchTabs`, `xAsksInAnOverlayWithCancelFocused`,
  `noSavesSaysSo`, `bGoesBack`. `FocusAuditTest.savesPage`, `savesPageShared`, `savesPageDeleteQuestion`,
  `savesPageEmpty`; `OrientationLayoutTest.theSavesPage` (a long title, a shared row of a long title).
- [ ] **Step 2: Run them; they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the tests, then `quick`.
- [ ] **Step 5: Commit** "Android: a game's Saves page".

### Task 10: X Saves and the long press on a library tile

**Files:**
- Modify: `ui/LibraryScreen.kt`, `MainActivity.kt` (`onSaves = AppController::openSaves`), `docs/design-system.md`
  (the library's foot), `docs/android.md` (the library)
- Test: `androidTest/…/ui/LibraryScreenTest.kt`, `FocusAuditTest.kt`, `KeyHintsTest.kt` if it pins the foot

**Interfaces:** `LibraryScreen(…, onSaves: (GameEntry) -> Unit = {})`.

Behaviour: X on a tile or row (key-up, as Y's `gameSettingsKey`) opens Saves. The foot reads "A Play", "X Saves",
"Y Game settings". A long press on a game with a key opens an `OverlayPanel` titled with the game's title: Saves
(focused) and Game settings (both `Secondary`); B closes it, focus back on the tile. On a game without a key the long
press opens Saves at once.

- [ ] **Step 1: Write the failing tests.** `LibraryScreenTest`: `xOpensSaves`, `theFootNamesXSaves`,
  `aLongPressOffersSavesAndGameSettings`, `aLongPressOnAGameWithoutAKeyOpensSaves`, `bClosesTheChoiceBackToTheTile`.
  `FocusAuditTest.libraryLongPressChoice`.
- [ ] **Step 2: Run them; they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the tests, then `quick`.
- [ ] **Step 5: Commit** "Android: X on a library tile opens its Saves".

### Task 11: Docs, roadmap and the full tier

**Files:** `docs/android.md` (drop the Known limits line at 1284 about Undo load, global slots, delete and rename;
add "Saves have no names, as on desktop" where the slot panel is described), `docs/roadmap.md` (item 16: save state
management done, its PR number once opened).

- [ ] **Step 1:** Update the docs; re-read every changed file of the branch and tighten (the `code-simplifier`
  agent for the larger Kotlin diffs).
- [ ] **Step 2:** `cd android && ./gradlew assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs`; desktop
  `common-tests` and `duckstation-qt`.
- [ ] **Step 3:** `GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh full` from a
  detached worktree at the branch head, in the background; read the result XML (tests, failures, skipped).
- [ ] **Step 4: Commit** "Docs: save state management on Android", push the branch to
  `origin/claude/save-state-management`.
