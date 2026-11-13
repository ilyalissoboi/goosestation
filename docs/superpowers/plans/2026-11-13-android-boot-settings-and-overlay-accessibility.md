# Settings Before a Boot, Sign-in, and Overlays for TalkBack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- Sign-in runs off the core queue.
- A start waits for the settings work queued before it.
- Overlays draw into a host that hides the screen under them from accessibility and announces them as panes.

**Architecture:**
- **`SettingsQueue`** wraps the core queue for the page models. It counts the work it dispatches and lets a start
  wait for it, while scans and covers keep the raw queue.
- **`GameStartGate`** owns the start's wait and its "starting" flag.
- **`OverlayHost`** is a `CompositionLocal` stack, drawn at the root and in `GameScreen`. Its container hides what's
  under it while an entry shows.

**Tech Stack:** Kotlin, kotlinx-coroutines(-test), Jetpack Compose (semantics, ui-test), JUnit 4, ktlint.

**Spec:** `docs/superpowers/specs/2026-11-13-android-boot-settings-and-overlay-accessibility-design.md`

## Global Constraints

- **Sign-in:** on its own single-thread dispatcher, with every other achievements call left where it is.
  `AchievementsPage`'s KDoc names what still waits on the emulation thread.
- **A start:** it waits **before** `beginGame`'s checks, for the settings work outstanding then. Scans and cover work
  don't hold it up. A second start during the wait is ignored.
- **Overlays:**
  - `OverlayPanel` draws into the nearest `LocalOverlayHost`, or in place with none.
  - Hosts sit at the root (`MainActivity`) and around the in-game settings (`GameScreen`).
  - The sort list uses the host.
  - The pause menu and its inline `Question`s stay as they are.
  - Every overlay has `paneTitle`.
  - Focus, keys, Back, the 160 ms fade, the shadow and the scrim are unchanged.
- **Windows:** no `Dialog` or `Popup` windows (`checkNoWindowDialogs`).
- **`docs/design-system.md` is binding.** It changes only to name the host and `title`.
- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- **Checks:**
  - every task: `./gradlew assembleDebug testDebugUnitTest ktlintCheck`;
  - `ui` classes on emulator-5580 with `-Pgoose.orchestrator=false`;
  - at the end:
    - the CI tasks;
    - the quick tier from a detached worktree with the emusync AAR copied in;
    - a TalkBack pass by hand.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A settings write still on the raw core queue,** so a start doesn't wait for it: one in `AppController` itself,
   or a page model built with the raw dispatcher. Task 2's Step 4 lists every construction, and the reviewer
   re-checks.
2. **An overlay that loses focus on open, or lets it escape,** once its content draws in the host: for example the
   update prompt or What's new over the library, or a question over Saves.
3. **Back closing the wrong thing** with two overlays shown: the sort list, then a resume prompt over it.
4. **The Mode key over the in-game settings' overlays,** and an overlay over the in-game settings hiding the game
   screen's touch controls. Those are already hidden, so there's no change.
5. **A start refused by `beginGame` after the wait** (the screen changed, or a data-folder change began) leaving
   "starting" set, so every later start is ignored.

The tests for items 2, 3 and 5 are in the tasks below. Items 1 and 4 are for the final reviewer.

---

### Task 1: Sign-in off the core queue

**Files:** `app/AchievementsPage.kt` and `app/AppController.kt` (its construction); test: `test/.../app/AchievementsPageTest.kt`.

- [ ] **Step 1: Write `aSignInDoesntHoldUpASettingsRead`.** A fake whose `signIn` suspends on a `CompletableDeferred`,
  and a page built with separate core and sign-in dispatchers (`StandardTestDispatcher`s on one scheduler).
  While the sign-in is open, a settings read on the core queue completes. Then the sign-in finishes, and the account
  shows.
- [ ] **Step 2: Run it.** Expected: it fails to compile, since `AchievementsPage` has no sign-in dispatcher yet.
- [ ] **Step 3: Add the dispatcher.** `AchievementsPage(scope, core, signIn: CoroutineDispatcher, api, settings)` runs
  `api.signIn` and the `readAccount()` after it on `signIn`. `AppController` passes
  `Dispatchers.IO.limitedParallelism(1)` as `signInDispatcher`. The KDoc names what still waits on the emulation
  thread.
- [ ] **Step 4: Run** `--tests '*AchievementsPage*'`. Expected: all pass.
- [ ] **Step 5: Commit:** "Android: sign-in on a queue of its own, so pages don't wait behind it".

### Task 2: A start waits for the settings work queued before it

**Files:**
- create `app/SettingsQueue.kt` and `app/GameStartGate.kt`;
- modify `app/AppController.kt`;
- tests: create `test/.../app/SettingsQueueTest.kt` and `GameStartGateTest.kt`.

**Interfaces (produced):**

```kotlin
class SettingsQueue(private val core: CoroutineDispatcher) : CoroutineDispatcher() {
    // dispatch(): runs on core, counting each block until it ends
    suspend fun settle()   // returns once every block dispatched before the call has ended
}
class GameStartGate(private val scope: CoroutineScope, private val settle: suspend () -> Unit) {
    val starting: Boolean
    fun start(begin: () -> Boolean, boot: () -> Unit)   // ignored while starting; settle(), then begin() and, if true, boot()
}
```

- [ ] **Step 1: Write the tests:**
  - `settleWaitsForTheWorkBeforeIt` (`SettingsQueueTest`): a block gated on a deferred is dispatched, and `settle()`
    doesn't return until the gate opens;
  - `settleDoesntWaitForWorkAfterIt`: a block dispatched after `settle()` was called doesn't hold it;
  - `rawCoreWorkDoesntCount`: a gated block launched on the raw core dispatcher, standing in for a scan, doesn't hold
    `settle()`;
  - `aStartBootsAfterTheSettle` (`GameStartGateTest`): with a gated `settle`, `boot` isn't called until it ends, then
    is called once;
  - `aSecondStartWhileStartingIsIgnored`;
  - `aRefusedStartLeavesNothingStarting` (Review Focus 5): when `begin` returns false, `boot` isn't called and
    `starting` is false.
- [ ] **Step 2: Run them.** Expected: they fail to compile.
- [ ] **Step 3: Write `SettingsQueue` and `GameStartGate`.**
  - `SettingsQueue`: each dispatch takes a sequence number, and a `MutableStateFlow` holds the lowest number still
    running. `settle()` waits until everything numbered before its own snapshot has ended.
  - `GameStartGate`: `starting` is cleared in a `finally`.
- [ ] **Step 4: Put it to use in `AppController`:**
  - `val settingsQueue = SettingsQueue(coreDispatcher)` goes to every page model and to `AppController`'s own
    settings writes. That covers `SettingsPages`, `GameSettingsPages`, `ControllersPage`, `AchievementsPage` (its
    `core`), `PostProcessingPage`, `MemcardSyncPage`, `SavesPage` if it writes, and `AppController`'s
    `applySettings` callers.
  - The library scans, covers, `readCorePaths` and `DataSync` keep the raw `coreDispatcher`.
  - List each construction's choice in the ledger.
  - `startGameNow` and `startGameFromStateNow` call `gate.start(begin = { beginGame(game) }, boot = { NativeLibrary.startGame…(…) })`.
- [ ] **Step 5: Run** `--tests '*SettingsQueueTest' '*GameStartGateTest'` and the page models' tests. Expected: all
  pass, with the page tests unchanged.
- [ ] **Step 6: Commit:** "Android: a game boots only after the settings work queued before Play".

### Task 3: Overlays draw into a host, as panes

**Files:**
- create `ui/components/OverlayHost.kt`;
- modify:
  - `ui/components/OverlayPanel.kt` and `ui/components/Question.kt`;
  - `MainActivity.kt` and `ui/GameScreen.kt`;
  - `ui/LibrarySortList.kt` and `ui/LibraryScreen.kt`;
  - every `OverlayPanel` caller, for its `title`, and `ResumePrompt`, `WhatsNewPanel` and `ShaderPacksPanel`, to move
    their focus requests into the content;
  - `docs/design-system.md`;
- test: create `androidTest/.../ui/OverlayHostTest.kt`.

**Interfaces (produced):**

```kotlin
class OverlayHost { val showing: Boolean }    // a stack of entries, newest on top
val LocalOverlayHost: ProvidableCompositionLocal<OverlayHost?>
@Composable fun OverlayHostBox(host: OverlayHost, content: @Composable () -> Unit)  // content hidden from accessibility while showing; entries drawn above it
@Composable fun Overlay(title: String, content: @Composable () -> Unit)  // into the nearest host, or in place; the pane is titled
fun OverlayPanel(onBack: () -> Unit, title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)
```

- [ ] **Step 1: Write `OverlayHostTest`.** Each case is inside an `OverlayHostBox`, over a page with a button
  "Under":
  - `theScreenUnderAnOverlayIsHiddenFromAccessibility`: with an `OverlayPanel` titled "Delete?" shown, "Under" is
    hidden, its node carrying `SemanticsProperties.HideFromAccessibility` or its ancestor's, and the panel's "Delete"
    button isn't;
  - `theOverlayIsAPaneWithItsTitle`: a node has `paneTitle` "Delete?";
  - `closingItGivesTheScreenBack`: once it's closed, "Under" isn't hidden;
  - `focusMovesIntoTheOverlayAsItOpens` (Review Focus 2): the panel's first button is focused once idle;
  - `backClosesTheNewestOfTwo` (Review Focus 3): with two overlays, Back closes only the newer one.
- [ ] **Step 2: Run** it on the emulator. Expected: it fails to compile.
- [ ] **Step 3: Write the host.**
  - Each entry holds its content through `rememberUpdatedState`.
  - `Overlay` registers in a `DisposableEffect` and draws in place when `LocalOverlayHost.current` is null.
  - `OverlayPanel` wraps its scrim and panel in `Overlay(title)`. Its `BackHandler` stays at the call site.
  - The panel gets `Modifier.semantics { paneTitle = title }`.
  - `Question` and `QuestionPanel` pass their text as `title`.
  - `MainActivity` wraps its screens in `OverlayHostBox`.
  - `GameScreen` wraps the in-game settings in one, inside its Mode-key `Box`.
  - `LibrarySortList` wraps itself in `Overlay("Sort by")`.
- [ ] **Step 4: Give every `OverlayPanel` call site its `title`,** the line it shows at the top. Move the three
  outside focus requests inside the content.
- [ ] **Step 5: Update the design system.** Its `OverlayPanel` entry names the host, `title` and the pane.
- [ ] **Step 6: Run** `OverlayHostTest`, `OverlayPanelTest`, `ResumePromptTest`, `UpdateBannerTest`,
  `LibraryScreenTest`, `SavesScreenTest`, `CoversScreenTest`, `PostProcessingScreenTest`, `ShaderPacksScreenTest`,
  `MemcardPanelsTest`, `GameSettingsScreenTest`, `GameMenuTest` and `FocusAuditTest` (whichever exist). Expected: all
  pass.
- [ ] **Step 7: Commit:** "Android: overlays draw into a host that hides the screen under them from TalkBack".

### Task 4: Whole-branch checks

- [ ] **Step 1: Run** the CI tasks, and the quick tier from `git worktree add --detach /tmp/goose-g HEAD`, with the
  emusync AAR copied in.
- [ ] **Step 2: Do a TalkBack pass by hand on emulator-5580:**
  1. `adb shell settings put secure enabled_accessibility_services com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService`,
     if TalkBack is installed;
  2. open a question over a settings page;
  3. swipe through it: only the question is read, and its pane is announced as it opens;
  4. turn TalkBack off again.

  If TalkBack isn't on the image, write that in the ledger and rely on `OverlayHostTest`.
