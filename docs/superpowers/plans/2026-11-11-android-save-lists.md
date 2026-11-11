# Save Lists Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One copy of the save lists for the library's Saves page and the in-game slot panel. The two copies agree
first, through test-first drift fixes; then they share one model and one row composable, and the slot panel's
questions become one value.

**Architecture:**
- `SaveListModel` (`app/`) owns the lists, the picture reads, the shared slot's game read, the delete and the error
  line. It pushes each new `SaveListState` to its owner through `onChange`, so each owner keeps one immutable state
  tree, with one writer.
- `SavesPage` and `InGameMenu`'s slot panel own a model each.
- `SaveRows` and `SaveListEmptyLine` (`ui/`) draw the rows on both screens.

**Tech Stack:** Kotlin 2.4, Jetpack Compose, kotlinx-coroutines-test (`StandardTestDispatcher`, `runCurrent`), JUnit 4,
Compose UI tests, ktlint.

**Spec:** `docs/superpowers/specs/2026-11-11-android-save-lists-design.md`

## Global Constraints

- **Drift fixes before the move,** each its own test-first commit (Tasks 1–4). The move (Tasks 5–9) changes no
  behaviour.
- **Tests:** `InGameMenuTest` and `SavesPageTest` change only where the state's shape changes, never in what they
  expect to happen. The same goes for `GameMenuTest` and `SavesScreenTest`.
- **No visible change** beyond goal 1's. Same layout, words, keys and focus order.
- **No stale read lands.** A read for a closed or re-read page or panel is dropped.
- **No owner flag:** the model never branches on which screen owns it.
- `docs/design-system.md` is binding.
- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- **Checks:**
  - `./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`;
  - the `ui` package's `SavesScreenTest` and `GameMenuTest` on `emulator-5580`, with `-Pgoose.orchestrator=false`.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **The page or panel closes mid-delete.** The delete still completes, but its re-read lands nowhere. A page or panel
   opened again starts idle, not "Deleting…".
2. **A failed delete.** Busy ends, the error shows, and the focus goes back to the row asked about, which is pickable
   again.
3. **A game without a key** (a PSF or the BIOS). It opens on Shared, its own list is empty, This game shows the
   no-key line, and a shared slot's delete still works.
4. **Save mode in the slot panel.** Empty slots are listed, "Other saves" isn't, and a delete is never asked.
5. **A shared slot's game read when the panel closes or re-reads meanwhile.** The read is dropped: no switch question,
   no load, no error on a panel that's gone.

Tests: 1, 3 and 5 in `SaveListModelTest` (Task 6); 2 and 4 there too; 1 and 5 also in `InGameMenuTest` (Task 8).

---

### Task 1: A delete is busy until the lists are read again, on both screens

**Files:**
- Modify: `app/SavesPage.kt`, `app/InGameMenu.kt`, `ui/SavesScreen.kt`
- Modify: `test/.../app/FakeGameCore.kt`, adding `var onTimes: () -> Unit = {}`, called first in
  `getSaveStateTimes`.
- Test: `test/.../app/SavesPageTest.kt`, `test/.../app/InGameMenuTest.kt`,
  `androidTest/.../ui/SavesScreenTest.kt`

(Paths are under `android/app/src/` and the `io/github/ilyalissoboi/goosestation/` package from here on.)

**Interfaces:**
- **Produces:** `SavesPageState.removing: Boolean`, true from `confirmDelete` until the re-read's lists are in or the
  delete fails.
- While `removing`, `pick`, `askDelete` and `switchTab` do nothing, and the page shows `SlotLine("Deleting…")` under
  its error line.
- On the slot panel, `busy` and `removing` stay true until `readSlots`' lists are in.

- [ ] **Step 1: Write the failing tests.**
  - `SavesPageTest.aDeleteIsBusyUntilTheListsAreReadAgain`. Saves in slots 2 and 5; ask and confirm slot 2.
    - Right after `confirmDelete()`: `removing` is true.
    - `pick` of slot 5 and `askDelete` of slot 5 add nothing to `calls`, and `switchTab()` leaves the tab.
    - Inside `api.onTimes` during the re-read, which is after `deleteState` has returned: `removing` is still true.
    - After `runCurrent()`: `removing` is false and the entries are `[5]`.
  - `SavesPageTest.aFailedDeleteIsNoLongerBusy`. With `deleteError` set: after `runCurrent()`, `removing` is false and
    the error is "Couldn't delete that save: …".
  - `InGameMenuTest.aDeleteIsBusyUntilTheListsAreReadAgain`. The same, through `api.onTimes`: `busy` and `removing`
    are still true when the re-read lists the times. After `runCurrent()`, both are false.
  - `SavesScreenTest.aDeleteUnderWaySaysSo`. A `SavesPageState` with `removing = true` shows "Deleting…".
- [ ] **Step 2: Run.**
  - `./gradlew -q testDebugUnitTest --tests '*SavesPageTest' --tests '*InGameMenuTest'`. Expected: the three new JVM
    tests fail, and the rest pass.
  - The UI test fails to compile.
- [ ] **Step 3: Implement.**
  - `SavesPage.confirmDelete` sets `removing`, and clears it where the re-read sets the lists, or on failure.
  - `InGameMenu.confirmDelete` passes the clearing to `readSlots`, through a `removed: Boolean` parameter that clears
    `busy` and `removing` in the update that sets the lists.
  - `SavesScreen` shows the line.
- [ ] **Step 4: Run** the JVM tests as in Step 2, then `SavesScreenTest` on `emulator-5580` (see Global Constraints).
  Expected: all pass.
- [ ] **Step 5: Commit:** "Android: a delete is busy until the lists are read again, on the Saves page too".

### Task 2: The Saves page asks to delete only a row it still lists

**Files:** `app/SavesPage.kt`; test: `SavesPageTest.kt`

- [ ] **Step 1: Write the failing test** `SavesPageTest.aDeleteIsAskedOnlyForASavedRowStillListed`.
  - `askDelete(SlotEntry(7, 700))`, a slot the lists don't have, leaves `deleting` null.
  - `askDelete` of a listed row whose `savedAt` is null also leaves it null.
  - A listed saved row is asked.
- [ ] **Step 2: Run** `--tests '*SavesPageTest'`. Expected: the new test fails on the unlisted row.
- [ ] **Step 3: Implement** the slot panel's check in `SavesPage.askDelete`: some row of `entries + others + shared`
  has the same `ref(key)` and a `savedAt`.
- [ ] **Step 4: Run.** Expected: all pass.
- [ ] **Step 5: Commit:** "Android: the Saves page asks to delete only a save it still lists".

### Task 3: One picture read at a time, on both screens

**Files:** `app/SavesPage.kt`, `app/InGameMenu.kt`, `FakeGameCore.kt` (adding `var onPreview: (slot: Int) -> Unit = {}`,
called first in `getSaveStatePreview` and `getStatePreview`); tests: `SavesPageTest.kt`, `InGameMenuTest.kt`

**Interfaces:** each owner keeps its picture loop as a `Job?`, cancelled before the next list read starts and on
close.

- [ ] **Step 1: Write the failing tests** `SavesPageTest.aRereadStopsTheLastPictureReads` and
  `InGameMenuTest.aRereadStopsTheLastPictureReads`.
  - Saves in slots 2, 5 and 8, each with a picture.
  - `api.onPreview` asks for and confirms the delete of slot 2, the first time slot 2's picture is read.
  - After `runCurrent()`, `calls.count { it == "getSaveStatePreview $SERIAL 5" } == 1`, and the same for slot 8.
- [ ] **Step 2: Run.** Expected: both fail, with a count of 2.
- [ ] **Step 3: Implement** the `Job`.
- [ ] **Step 4: Run.** Expected: all pass.
- [ ] **Step 5: Commit:** "Android: a re-read of the save lists stops the last one's picture reads".

### Task 4: A tab switch clears the row to focus on the slot panel

**Files:** `app/InGameMenu.kt`; test: `InGameMenuTest.kt`

- [ ] **Step 1: Write the failing test** `InGameMenuTest.aTabSwitchClearsTheRowToFocus`. After a delete whose re-read
  set `focusRow`, `switchTab()` leaves `focusRow` null.
- [ ] **Step 2: Run.** Expected: it fails.
- [ ] **Step 3: Implement:** the slot branch of `switchTab` copies with `focusRow = null`.
- [ ] **Step 4: Run.** Expected: all pass.
- [ ] **Step 5: Commit:** "Android: a tab switch clears the slot panel's row to focus, as the Saves page's does".

### Task 5: `SlotQuestion` and `SlotWork`

**Files:** `app/InGameMenu.kt`, `ui/SlotPanel.kt`, `ui/GameMenu.kt`; tests: `InGameMenuTest.kt`, `GameMenuTest.kt`,
changed in shape only

**Interfaces (produced):**

```kotlin
sealed interface SlotQuestion {
    data class Replace(val slot: Int) : SlotQuestion
    data class Switch(val entry: SlotEntry) : SlotQuestion
}
enum class SlotWork { Saving, Loading }
// SlotPanel: `question: SlotQuestion?` and `work: SlotWork?` replace `replacing`, `switching` and `busy`;
// `deleting` and `removing` stay until Task 8.
// `val idle: Boolean get() = question == null && work == null && deleting == null && !removing`
```

- `run(...)` takes the `SlotWork`: `Saving` for `save` and `saveShared`, `Loading` for `load`, `pickOther` and
  `loadShared` (which is busy during its game read, as now).
- The line reads "Deleting…" while `removing`, "Saving…" for `Saving` and "Loading…" for `Loading`.
- Every "idle" check (`askDelete`, `pickSlot`, `pickShared`, `pickOther`, `switchTab`, `closeSlots`, the UI's
  `asking` and `busy`) reads `idle`, or `question`/`work` where it checked one field before.
- **Ruling:** `pickOther` and `askDelete` didn't check `switching` before. They check `idle` now, which adds that
  check. It can't arise: the switch question replaces the rows, so neither can be reached while it shows. Pin it in a
  test.

- [ ] **Step 1: Write the failing test** `InGameMenuTest.slotQuestionsAreOneAtATime`. With `Replace` asked: `askDelete`,
  `pickShared` and `switchTab` change nothing. With `Switch` asked: `pickOther` and `askDelete` change nothing.
- [ ] **Step 2: Run.** Expected: it fails to compile.
- [ ] **Step 3: Implement,** and move each existing test's field reads to the new shape:
  - `replacing == 3` becomes `question == SlotQuestion.Replace(3)`;
  - `busy` becomes `work != null`, or `!idle` where it means "anything".
- [ ] **Step 4: Run** `--tests '*InGameMenuTest'`, then `GameMenuTest` on the emulator. Expected: all pass.
- [ ] **Step 5: Commit:** "Android: the slot panel's question and work are one value each".

### Task 6: `SaveListModel`, test-first

**Files:** create `app/SaveListModel.kt`; test: create `test/.../app/SaveListModelTest.kt`, with `FakeGameCore` and
`SaveReader`

**Interfaces (produced, used by Tasks 7–9):**

```kotlin
/** What a save list shows, and what it asks or does. */
data class SaveListState(
    val mode: SlotMode,
    val tab: SlotTab,
    val hasKey: Boolean,
    val entries: List<SlotEntry>? = null,      // the game's own; null while read
    val others: List<SlotEntry> = emptyList(), // Load only: the other discs'
    val shared: List<SlotEntry>? = null,
    val focus: Int? = null,                    // SaveLists.focus(mode, entries, last), with a lastSlot
    val sharedFocus: Int? = null,
    val focusRow: SlotEntry? = null,
    val deleting: SlotEntry? = null,           // the delete asked about
    val removing: Boolean = false,             // a delete under way, until the re-read's lists are in
    val error: String? = null,
) {
    val idle: Boolean get() = deleting == null && !removing
    val shownEntries: List<SlotEntry>? get()   // shared on Shared; else entries, or empty without a key
    val shownOthers: List<SlotEntry> get()     // others on This game with a key; else empty
}

class SaveListModel(
    scope: CoroutineScope,
    game: CoroutineDispatcher,
    reader: SaveReader,
    api: GameCoreApi,
    onChange: (SaveListState?) -> Unit,
) {
    val state: SaveListState?
    fun open(key: String, path: String, discs: List<GameDisc>, mode: SlotMode, lastSlot: (suspend () -> Int?)? = null)
    fun close()
    fun switchTab()                       // not while !idle; clears error and focusRow
    fun askDelete(entry: SlotEntry)       // Load only, idle only, a saved row still listed; clears error
    fun cancelDelete()                    // focusRow = the row asked about
    fun confirmDelete()                   // removing until the re-read's lists are in; on failure, error + focusRow
    suspend fun readHolds(entry: SlotEntry): SlotEntry?  // a shared row with its game read; null if dropped meanwhile
    fun fail(error: String)               // an owner's failure into the one error line
    fun clearError()
}
```

- `open` reads the lists in `SavesPage.read`'s and `InGameMenu.readSlots`' order: own, then `lastSlot` on `game`,
  then others in Load only, then shared.
- It then reads the saved rows' pictures, the open tab's first, in one `Job`.
- Its generation is private to the model.
- `UNREADABLE` moves to `SaveLists` as `const val UNREADABLE = "Couldn't read that save."`.

- [ ] **Step 1: Write the failing tests** in `SaveListModelTest`:
  - `loadListsTheSavesAndOtherDiscs` and `saveListsEverySlotAndNoOthers` (Review Focus 4: `askDelete` does nothing in
    Save);
  - `aGameWithoutAKeyListsOnlyShared` (Review Focus 3: no `getSaveStateTimes` call, Shared open, a shared delete
    works);
  - `picturesComeOpenTabFirst`: the order of `getSaveStatePreview` and `getStatePreview` calls;
  - `focusFollowsTheLastSlot`: with `lastSlot = { 3 }`, `focus == 3`; without one, null;
  - `aCloseDropsEveryRead`: a close before `runCurrent()` leaves `state` null, and `onChange` isn't called again;
  - `deleteIsBusyUntilTheReread`: the Task 1 cases, through `onTimes`;
  - `aFailedDeleteFocusesTheRowAgain` (Review Focus 2);
  - `aCloseMidDeleteLeavesTheNextOpenIdle` (Review Focus 1): confirm, close, `runCurrent()`, then open again; the new
    state is idle, and the old re-read doesn't land;
  - `aDeleteIsAskedOnlyForASavedRowStillListed`: as Task 2;
  - `aRereadStopsTheLastPictureReads`: as Task 3;
  - `aTabSwitchClearsTheRowToFocus` and `noTabSwitchWhileDeleting`;
  - `readHoldsReadsTheGameOnceAndUpdatesTheRow`: a second call doesn't read again;
  - `readHoldsIsDroppedByACloseOrReread` (Review Focus 5): it returns null;
  - `failShowsTheOwnersError`: and `askDelete` and `switchTab` clear it.
- [ ] **Step 2: Run** `--tests '*SaveListModelTest'`. Expected: it fails to compile.
- [ ] **Step 3: Write `SaveListModel`,** taking its bodies from the two owners as they are after Tasks 1–4.
- [ ] **Step 4: Run** `--tests '*SaveListModelTest' ktlintCheck`. Expected: all pass.
- [ ] **Step 5: Commit:** "Android: SaveListModel, the save lists both screens show, with JVM tests".

### Task 7: The Saves page on `SaveListModel`

**Files:** `app/SavesPage.kt`, `ui/SavesScreen.kt`, `MainActivity.kt` if it reads the moved fields; tests:
`SavesPageTest.kt` and `SavesScreenTest.kt`, in shape only

**Interfaces:**
- **Consumes:** Task 6.
- **Produces:** `SavesPageState(game, lists: SaveListState, hardcore)`, with `hasKey` from `lists`.
  `SavesActions` is unchanged.

- [ ] **Step 1: Move.**
  - `SavesPage` opens its model in Load with no `lastSlot`, and forwards `switchTab`, `askDelete`, `cancelDelete` and
    `confirmDelete`.
  - `pick` keeps the hardcore check and `play`; it uses `readHolds` for a shared row, and `fail` for its errors.
  - `close` closes the model.
  - The removed fields are `entries`, `others`, `shared`, `deleting`, `focusRow`, `removing` and `error`, all now
    `lists.*`.
- [ ] **Step 2: Run** `--tests '*SavesPageTest'` (expected: all pass, changed in shape only), then `SavesScreenTest` on
  the emulator.
- [ ] **Step 3: Commit:** "Android: the Saves page's lists are a SaveListModel".

### Task 8: The slot panel on `SaveListModel`, and one copy of each message

**Files:** `app/InGameMenu.kt`, `ui/SlotPanel.kt`, `ui/GameMenu.kt`; tests: `InGameMenuTest.kt` and `GameMenuTest.kt`,
in shape only, plus two new tests

**Interfaces:**
- **Consumes:** Tasks 5 and 6.
- **Produces:** `SlotPanel(lists: SaveListState, question: SlotQuestion?, work: SlotWork?)`, with
  `val idle get() = question == null && work == null && lists.idle`. `GameMenuActions` is unchanged.

- [ ] **Step 1: Write the failing tests** in `InGameMenuTest`:
  - `closingThePanelMidDeleteReopensIdle` (Review Focus 1);
  - `aSharedSlotReadDroppedByACloseAsksNothing` (Review Focus 5).

  Both pass today. They're for the move: watch each fail once by commenting out the model's generation check, then
  restore it.
- [ ] **Step 2: Move.**
  - `openSlots` opens the model with `lastSlot = { lastSlots.get(serial) }`, and closes it wherever the panel goes:
    `dropSlots`, `withSide` without slots, `gameStarted`, `gameStopped`, `opened`.
  - `loadShared` uses `readHolds`. `run`'s failures and `loadShared`'s refusals go through `fail`.
  - `panelId` no longer guards the slot panel. `updatePanel` checks that the panel is open.
  - `withRow` and the `UNREADABLE` copy are removed. `HARDCORE_LOAD` uses `GameStarts.HARDCORE_LOAD`.
- [ ] **Step 3: Run** `--tests '*InGameMenuTest'` (all pass), then `GameMenuTest` on the emulator.
- [ ] **Step 4: Commit:** "Android: the slot panel's lists are a SaveListModel, and each message has one copy".

### Task 9: `SaveRows` and `SaveListEmptyLine`

**Files:** create `ui/SaveRows.kt`; modify `ui/SlotPanel.kt` and `ui/SavesScreen.kt`

**Interfaces (produced):**

```kotlin
/** The rows' focus requesters, by row, kept across reads. */
@Stable class SaveRowFocus { fun requester(entry: SlotEntry): FocusRequester }
@Composable fun rememberSaveRowFocus(): SaveRowFocus

/** The rows, then "Other saves" and theirs. onDelete, when given, only for saved rows. */
@Composable internal fun SaveRows(
    entries: List<SlotEntry>?, others: List<SlotEntry>, now: ZonedDateTime, short: Boolean, focus: SaveRowFocus,
    enabled: (SlotEntry) -> Boolean, onPick: (SlotEntry) -> Unit, onDelete: ((SlotEntry) -> Unit)?,
)

/** The no-key line, or the empty list's, or nothing. */
@Composable internal fun SaveListEmptyLine(lists: SaveListState)
```

- The slot panel's empty condition stands for both: "no saved own row, no others".
- Each screen keeps its own focus `LaunchedEffect`, now with `focus.requester(...)`. The two differ on purpose:
  - the slot panel has its questions, `returnTo` and the last slot;
  - the page focuses the first row.
- **Ruling:** the spec puts "the focus rules" in `SaveRows`. What's shared is the requester map and `focusRow` first.
  Moving each screen's fallback into one effect would change one screen's focus, which the spec forbids.
- [ ] **Step 1: Move** both screens onto `SaveRows` and `SaveListEmptyLine`. Each keeps its frame, tabs, error line,
  busy line, questions, key hints and empty-line placement.
- [ ] **Step 2: Run** `SavesScreenTest` and `GameMenuTest` on `emulator-5580`, then the CI tasks. Expected: all pass,
  none skipped, and neither UI test changed in this task.
- [ ] **Step 3: Commit:** "Android: SaveRows draws both screens' save rows".
