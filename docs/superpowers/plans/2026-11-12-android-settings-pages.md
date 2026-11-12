# Settings Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One page model for the global and the game's settings pages, and one dispatcher for the five pages both
have. The game copy's two drifts are fixed first.

**Architecture:**
- `PageValues<P>` (`app/`) owns the values shown, the read counters, the re-read-after-change rule and the shared
  extras.
- Each owner hands it a `PageReader` per read and a save function per change. The owner prepares both on the main
  thread, and they run on the core thread.
- `SharedPage` names the five pages both owners have, plus Memory cards. `SharedPageScreen` (`ui/`) draws the five.

**Tech Stack:** Kotlin 2.4, Jetpack Compose, kotlinx-coroutines-test, JUnit 4, Compose UI tests, ktlint.

**Spec:** `docs/superpowers/specs/2026-11-12-android-settings-pages-design.md`

## Global Constraints

- **Drift fixes first** (Tasks 1–2), each its own test-first commit. The move (Tasks 3–6) changes no behaviour.
- `SettingsPagesTest` (12) and `GameSettingsPagesTest` (21) stay unchanged in what they expect.
- **No owner flag:** the model never branches on which owner it serves.
- **No stale read lands:** a read overtaken by a newer read or a change is dropped. For the game, so is a read for a
  game no longer shown.
- **No third copy:** the shared dispatcher replaces both mappings.
- `docs/design-system.md` is binding: no screen changes.
- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- **Checks:**
  - `./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`;
  - on `emulator-5580`, the `ui` package's `ConsoleScreenTest`, `EmulationScreenTest`, `GraphicsScreenTest`,
    `AudioScreenTest`, `AdvancedScreenTest`, `GameSettingsScreenTest`, `SettingsScreenTest` and the new
    `SharedPageScreenTest`, with `-Pgoose.orchestrator=false`;
  - `core.GameCallsTest#aChangeInTheMenuAppliesAtOnce`, with the orchestrator.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Another game's settings opened, or the settings closed, while a page read is out.** The read lands nowhere. The
   new game's page never shows the old game's values or estimate.
2. **A change while a read is out.** The read is dropped, the change shows meanwhile, and the page reads again once
   the change is saved.
3. **A change that changes nothing.** Nothing is saved or applied, and the game's summary counter isn't bumped, so a
   summary read already out still lands.
4. **Two quick changes of the rewind slots.** Only the estimate for the values still shown is kept.
5. **The core's choice lists.** They're read with the first page and reused after.

Tests: 1 in Task 1 and Task 5; 2, 3, 4 and 5 in `PageValuesTest` (Task 3); 3 also in Task 5.

---

### Task 1: A game's rewind estimate is read inside its page's read

**Files:** `app/GameSettingsPages.kt`; `test/.../app/FakeCoreApi.kt` (adding `var onRewind: () -> Unit = {}`, run
once, first in `getRewindMemoryUsage`); test: `GameSettingsPagesTest.kt`

(Paths are under `android/app/src/` and the `io/github/ilyalissoboi/goosestation/` package from here on.)

- [ ] **Step 1: Write the failing test** `GameSettingsPagesTest.anotherGameOpenedMidReadShowsNoneOfTheFirstsEmulation`
  (Review Focus 1).
  - Open `game`, and `openPage(GamePage.Emulation)`.
  - `api.onRewind` opens another game (`game.copy(serial = "SLUS-00002", path = "/games/other.cue")`).
  - After `runCurrent()`: `pages.values(GamePage.Emulation)` is null, and `pages.rewindUsage` is null.
- [ ] **Step 2: Run** `./gradlew -q testDebugUnitTest --tests '*GameSettingsPagesTest'`. Expected: the new test fails,
  because the first game's values show.
- [ ] **Step 3: Implement:** `read`'s Emulation estimate moves into its `withContext(core)` block, after the values,
  as `SettingsPages.read` does, and lands with the values under the stale check.
- [ ] **Step 4: Run** the tests again. Expected: all pass, `theRewindEstimateFollowsAChangeOfSaveSlots` included.
- [ ] **Step 5: Commit:** "Android: a game's rewind estimate is read with its page, as the global page's is".

### Task 2: Opening a game's settings closes post-processing

**Files:** `app/GameSettingsPages.kt`; test: `GameSettingsPagesTest.kt`

- [ ] **Step 1: Write the failing test** `GameSettingsPagesTest.openingAnotherGameClosesItsPostProcessing`.
  - `openPage(GamePage.PostProcessing)` on `game`, then `runCurrent()`.
  - `pages.postProcessing.key == KEY`.
  - `pages.open(other, running = false)`, then `runCurrent()`.
  - `pages.postProcessing.key` is null.
- [ ] **Step 2: Run.** Expected: it fails, because the key is still `KEY`.
- [ ] **Step 3: Implement:** `open()` calls `postProcessing.close()`, as `close()` does.
- [ ] **Step 4: Run.** Expected: all pass.
- [ ] **Step 5: Commit:** "Android: opening a game's settings closes the last game's post-processing page".

### Task 3: `SharedPage` and `PageValues`, test-first

**Files:** create `app/PageValues.kt`; test: create `test/.../app/PageValuesTest.kt`, with `FakeCoreApi` and
`MemoryCoreSettings`

**Interfaces (produced, used by Tasks 4–6):**

```kotlin
/** The pages both settings owners have, by what a read fetches beside their values. */
enum class SharedPage { Console, Emulation, Graphics, Audio, MemoryCards, Advanced }

/** One read's values, and what its owner does with the rest of it, on the main thread, if the read lands. */
class PageLoad(val values: SettingValues, val then: () -> Unit = {})

/** One read: [load] on the core thread; dropped if it's no longer [current] when it comes back. */
class PageReader(val load: () -> PageLoad, val current: () -> Boolean = { true })

/** What a page's screen shows beside its values. */
interface PageExtras {
    val names: CoreNames
    val sharedCards: List<String>
    val vulkanFailed: Boolean
    val fastmemAvailable: Boolean
    val rewindUsage: RewindUsage?
}

class PageValues<P : Any>(
    scope: CoroutineScope,
    core: CoroutineDispatcher,
    api: CoreApi,
    shared: (P) -> SharedPage?,
    reader: (P) -> PageReader?,       // main thread, as a read starts; null: nothing to read
) : PageExtras {
    fun values(page: P): SettingValues?
    fun isShown(page: P): Boolean
    fun load(page: P)                 // from none shown
    fun reread(page: P)               // if shown: the old values stay until the read lands
    fun clear()                       // the values shown only: neither counters nor extras
    /** Saves with [save] on core (save and apply), which returns what the owner does after, on the main thread. */
    fun change(page: P, new: SettingValues, readAgain: Boolean = false,
               save: (new: SettingValues, old: SettingValues) -> () -> Unit): Boolean   // whether it saved
}
```

- **What a read fetches on core, in this order:**
  - the names, if none are known;
  - `reader.load()`;
  - for Graphics, `isVulkanFailed`;
  - for Advanced, `isFastmemAvailable`;
  - for Emulation, `getRewindMemoryUsage` of the loaded slots;
  - for Memory cards, `getSharedCardNames`.
- **When a read lands,** if it isn't stale and the reader is still current, in this order:
  - the names and flags;
  - the estimate and the cards;
  - `then()`;
  - the values.
- **A change runs on core** as `save`, then the Emulation estimate. Back on the main thread, the model runs what
  `save` returned, then keeps the estimate if the values shown are still the new ones.
- **Ruling:** the game's Controllers read used to come before the names. It now comes after them. Both are reads, so
  the order makes no difference, and no test checks it.

- [ ] **Step 1: Write the failing tests** in `PageValuesTest`, over a `String` page type with
  `shared = { SharedPage.valueOf(it) }`, values read from `MemoryCoreSettings`:
  - `aReadShowsItsValuesAndReadsTheNamesOnce` (Review Focus 5): two pages read, and the names are read once.
  - `eachSharedPageReadsItsExtras`: Graphics sets `vulkanFailed`, Advanced `fastmemAvailable`, Emulation
    `rewindUsage`, MemoryCards `sharedCards`. Console reads none of them.
  - `aReadOvertakenByANewerOneIsDropped`.
  - `aReadNoLongerCurrentIsDropped`: `current` is false when it lands, so neither its values nor its `then` land.
  - `aChangeDuringAReadReadsAgainAfterItsSave` (Review Focus 2): the change shows at once, the first read's values
    never land, and the second read reads what the save wrote.
  - `aChangeThatChangesNothingSavesNothing` (Review Focus 3): `change` returns false, and `save` isn't called.
  - `theEstimateFollowsAChangeAndOnlyTheLastOne` (Review Focus 4): two quick Emulation changes, 30 then 40. After
    `runCurrent()`, `rewindUsage` is 40's.
  - `aChangeRunsWhatItsSaveReturned`: that lambda runs on the main thread, after the save.
  - `readAgainReadsAfterTheSave`: `change(..., readAgain = true)` reads once more, even with no read out.
  - `clearKeepsTheExtras`: after `clear()`, the values are null and the names are still there.
  - `rereadReadsOnlyAShownPage`.
- [ ] **Step 2: Run** `--tests '*PageValuesTest'`. Expected: it fails to compile.
- [ ] **Step 3: Write `PageValues`,** taking its bodies from `SettingsPages` and `GameSettingsPages` as they are after
  Tasks 1–2.
- [ ] **Step 4: Run** `--tests '*PageValuesTest' ktlintCheck`. Expected: all pass.
- [ ] **Step 5: Commit:** "Android: PageValues, the page model both settings owners share, with JVM tests".

### Task 4: `SettingsPages` on `PageValues`

**Files:** `app/SettingsPages.kt`; test: `SettingsPagesTest.kt`, unchanged

**Interfaces:**
- **Consumes:** Task 3.
- **Produces:**
  - `CorePage.shared: SharedPage?`: Console, Emulation, Graphics, Audio, MemoryCards and Advanced map to theirs,
    Interface to null.
  - `SettingsPages.extras: PageExtras`.
  - Every public member keeps its signature: `values`, `load`, `reloadShown`, `change`, and `names`, `rewindUsage`,
    `sharedCards`, `fastmemAvailable` and `vulkanFailed` as getters over the model. The BIOS page is unchanged.

- [ ] **Step 1: Move.**
  - The reader is `PageReader({ PageLoad(SettingValues.read(settings, page.settings)) })`.
  - The save is `{ new, old -> new.save(settings, old); api.applySettings(); {} }`.
  - The private `PageRead`, `shown`, `pageRead` and `pageReading` go.
- [ ] **Step 2: Run** `--tests '*SettingsPagesTest' --tests '*PageValuesTest'`. Expected: all pass, unchanged.
- [ ] **Step 3: Commit:** "Android: the global settings pages' values are a PageValues".

### Task 5: `GameSettingsPages` on `PageValues`

**Files:** `app/GameSettingsPages.kt`; test: `GameSettingsPagesTest.kt`, unchanged but for one new test

**Interfaces:**
- **Consumes:** Task 3.
- **Produces:**
  - `GamePage.shared: SharedPage?`: Console, Emulation, Graphics, Audio, MemoryCards and Advanced map to theirs;
    PostProcessing, Controllers and Achievements to null.
  - `GameSettingsPages.extras: PageExtras`.
  - The public members are unchanged.

- [ ] **Step 1: Write the test** `GameSettingsPagesTest.aChangeThatChangesNothingLeavesTheSummaryRead` (Review Focus
  3). Open, so a summary read is out, then `change` a page with its own values. After `runCurrent()`, the summary is
  set. It passes today. It pins `change`'s return for the move: comment out the `if (saved)` around `summaryRead++`
  once to watch it fail, then restore.
- [ ] **Step 2: Move.**
  - **The reader,** with `key = game?.serial ?: return null`, taken as the read starts:
    - `load` reads the values, and for Controllers its pads, which `then` puts in `controllers`;
    - `current = { game?.serial == key }`.
  - **The change:**
    - take `key` and `apply = running` as it starts;
    - `retyped` comes from `values(page)` and `new`;
    - call `pages.change(page, new, readAgain = retyped) { new, old -> save; update controllers; apply;
      readSummary(key).let { s -> { showSummary(s) } } }`;
    - bump `summaryRead` only if it returns true.
  - **Open and close:** `open()` and `close()` call `pages.clear()`.
  - **The page:** `openPage` calls `pages.load(page)`, and `reloadShown` calls `pages.reread(page)`.
- [ ] **Step 3: Run** `--tests '*GameSettingsPagesTest' --tests '*PageValuesTest'`. Expected: all pass.
- [ ] **Step 4: Commit:** "Android: a game's settings pages' values are a PageValues".

### Task 6: `SharedPageScreen`, one dispatcher for both owners

**Files:**
- create `ui/SharedPageScreen.kt`;
- modify `MainActivity.kt` (Console, Emulation, Graphics, Audio and Advanced routes) and `ui/GameSettingsHost.kt`
  (`GamePageScreen`'s same five);
- test: create `androidTest/.../ui/SharedPageScreenTest.kt`, and `PageValuesTest`'s mapping tests.

**Interfaces:**
- **Produces:**

  ```kotlin
  @Composable
  fun SharedPageScreen(page: SharedPage, values: SettingValues, extras: PageExtras,
                       onChange: (SettingValues) -> Unit, onBack: () -> Unit)
  ```

  - It draws `ConsoleScreen`, `EmulationScreen`, `GraphicsScreen`, `AudioScreen` and `AdvancedScreen`, with their
    extras.
  - `SharedPage.MemoryCards` isn't drawn here: each owner's Memory cards page has its own layout. It's an `error(...)`
    branch the owners never reach.
- **Ruling:** the spec asks for a test of "which extras each shared page gets, from each owner". It's split in two:
  - `SharedPageScreenTest` pins what each page shows of its extras;
  - two JVM tests pin `CorePage.shared` and `GamePage.shared`.

  What's left of the owners' wiring is one call each, `SharedPageScreen(page.shared!!, values, owner.extras, …)`.

- [ ] **Step 1: Write the failing tests.**
  - **`SharedPageScreenTest`**, a fake `PageExtras` with distinct values:
    - `emulationShowsTheRewindEstimate`;
    - `graphicsSaysVulkanFailed`;
    - `advancedSaysFastmemIsUnavailable`;
    - `consoleNamesItsChoicesFromTheNames`;
    - `audioShows`.
  - **`PageValuesTest`:**
    - `corePagesMapToTheirSharedPages`;
    - `gamePagesMapToTheirSharedPages`.
- [ ] **Step 2: Run** the JVM tests (expected: they fail to compile, without `shared` on the enums if Tasks 4–5
  haven't added it, or pass if they have), then `SharedPageScreenTest` (expected: it fails to compile).
- [ ] **Step 3: Implement** `SharedPageScreen`, and move both callers' five pages onto it. `CorePageRoute` stays and
  passes its values. `GamePageScreen` keeps Memory cards, Achievements and Controllers.
- [ ] **Step 4: Run.**
  - On `emulator-5580`, the Global Constraints' UI classes. Expected: all pass, none skipped.
  - `GameCallsTest#aChangeInTheMenuAppliesAtOnce`.
  - The CI tasks.
- [ ] **Step 5: Commit:** "Android: SharedPageScreen draws the pages both settings owners have".
