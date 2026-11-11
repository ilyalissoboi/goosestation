# DataSync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the data folder mirror's runs and their state out of `AppController` into a JVM-testable `DataSync`,
with no behaviour change, and pin today's behaviour with JVM tests.

**Architecture:**
- `DataSync<M : MirrorRuns>` in `app/` owns the mirror, `LayoutSettling`, `CatchUpRunner`, the memory card paths, the
  data layout and `holdMemcards`.
- It reaches the core through `SyncCore`, and the app through `SyncHooks`.
- `Mirror` declares `: MirrorRuns`.
- `AppController` builds `DataSync<Mirror>` and delegates every moved call to it.

**Tech Stack:** Kotlin 2.4, kotlinx-coroutines and kotlinx-coroutines-test (`StandardTestDispatcher`, `runCurrent`),
JUnit 4, ktlint.

**Spec:** `docs/superpowers/specs/2026-11-11-android-datasync-design.md`

## Global Constraints

- **A pure move.** No behaviour change. The order of mirror runs, core calls and page reloads, and the thread each
  runs on, stay as they are in `AppController.kt` at `origin/master` `a798e7697`.
- **No back-reference.** `DataSync` holds no reference to `AppController` and reads none of its fields.
- **`Mirror` changes only its declaration:** `: MirrorRuns`, with `override` on `state`, `pull`, `push` and `letGo`.
- **The moved comments move with the code**, word for word, unless they named something that's now a hook.
- **Every check stays green:**
  - `./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`;
  - the instrumented `MemcardSync*`, `DataFolderChangeTest` and `MirrorTest` classes on `emulator-5580`;
  - `GOOSE_TEST_PARTS=release GOOSE_TEST_SERIALS=emulator-5580 android/scripts/run-instrumented-tests.sh quick`.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A second `useMirror`** (the data folder change, or its failure, going back to the old folder). `mirrorState`
   follows the new mirror only; the old one's collection is cancelled.
2. **The core reports no memory card paths** (`getMemoryCardPaths()` is null). `MemoryCardPaths.DEFAULT` is merged in,
   as today.
3. **The core reports no data folders** (`getDataFolders()` is null). `readCorePaths()` returns false and leaves
   `dataLayout` as it was.
4. **A push that copies a new `settings.ini` in** (the folder's side changed too). The push runs the same after-pull
   steps a pull does.
5. **A catch-up that ends while the line shows something other than plain "Copying…"** (an error, say). The line is
   left alone.

---

### Task 1: The seams: `MirrorRuns`, `SyncCore`, `SyncHooks`

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/mirror/MirrorRuns.kt`
- Modify: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/mirror/Mirror.kt`: the class declaration, and
  `override` on four members.
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/app/SyncCore.kt`, holding `SyncCore`,
  `NativeSyncCore` and `SyncHooks`.

**Interfaces (produced, used by Tasks 2 and 3):**

```kotlin
interface MirrorRuns {
    val state: StateFlow<MirrorState>
    suspend fun pull(deferred: (String) -> Boolean): PullResult
    suspend fun push(force: Boolean, deferred: (String) -> Boolean): PushResult
    fun letGo()
}

interface SyncCore {
    fun reloadSettings()
    fun getMemoryCardPaths(): List<String>?
    fun getDataFolders(): List<String>?
    fun getDataFiles(): List<String>?
    fun getCacheFolder(): String?
    fun setGameFolders(folders: Array<String>)
    fun refreshGameList(invalidateCache: Boolean)
    fun getGameList(): List<GameEntry>
    fun holdCard(path: String, action: () -> Unit): Boolean   // NativeLibrary.memcardSyncHoldCard
    fun getShadersFolder(): String?
    fun shaderPackIds(): List<String>
    fun shaderPackMarkerFiles(json: String): List<String>?
}

object NativeSyncCore : SyncCore   // each call is NativeLibrary's, with arrays turned into lists

interface SyncHooks {
    fun coreStarted(): Boolean
    fun dataGranted(): Boolean
    fun readableGameFolders(): Array<String>
    suspend fun onSettingsReloadedOnCore()           // today's readLibraryView(), on coreDispatcher
    fun onLibraryRead(games: List<GameEntry>)        // today's `games = it`
    fun onSettingsReloaded(steps: List<StartupStep>) // today's page reloads at the end of applyChanged
    fun refreshEditor()
    fun showCopying(progress: Pair<Int, Int>?)       // null: COPYING; (n, total): "$COPYING (n of total)"
    fun copyingShown(): Boolean                       // the line is the plain COPYING
    fun clearCopying()
}
```

- [ ] **Step 1: Write `MirrorRuns`, and declare `Mirror : MirrorRuns`** with `override` on `state`, `pull`, `push` and
  `letGo`. Nothing else in `Mirror.kt` changes.
- [ ] **Step 2: Write `SyncCore`, `NativeSyncCore` and `SyncHooks`** as above. The KDoc of each hook says the thread it
  runs on.
- [ ] **Step 3: Verify.** Run `./gradlew -q compileDebugKotlin ktlintCheck testDebugUnitTest`. Expected: build success,
  1139 tests passed. No test is added: these are interfaces, and `NativeSyncCore` is only calls through to
  `NativeLibrary`.
- [ ] **Step 4: Commit:** "Android: the seams DataSync will take: MirrorRuns, SyncCore and SyncHooks".

### Task 2: `DataSync`, test-first

**Files:**
- Create: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/app/DataSync.kt`
- Test: `android/app/src/test/java/io/github/ilyalissoboi/goosestation/app/DataSyncTest.kt`, with `FakeMirror`,
  `FakeSyncCore` and `FakeSyncHooks` in the same file.

**Interfaces:**
- **Consumes:** Task 1's types; `LayoutSettling`, `CatchUpRunner`, `MemoryCardPaths`, `DataLayout`, `StartupPlan`,
  `StartupStep`, `EmusyncCarryOver`, `KeyValueStore`, `CopyGuard`, `PackMarkers`.
- **Produces (used by Task 3):**

```kotlin
class DataSync<M : MirrorRuns>(
    scope: CoroutineScope,                 // the main thread's
    coreDispatcher: CoroutineDispatcher,
    io: CoroutineDispatcher,                // prepareWorkingCopy()'s, today Dispatchers.IO
    core: SyncCore,
    hooks: SyncHooks,
    workingCopy: File,
    manifestFile: File,
    carryOverStore: KeyValueStore,          // the "memcard_sync" store EmusyncCarryOver.drop takes
    makeMirror: (data: String, guard: CopyGuard, packMarkers: () -> PackMarkers?, removeStrays: () -> Boolean,
                 layout: () -> DataLayout) -> M,
    log: (String) -> Unit,                  // Log.w/i/e with AppController's TAG, today
) {
    val mirror: M?
    val mirrorState: StateFlow<MirrorState>
    val dataLayout: DataLayout                 // @Volatile, written on coreDispatcher
    var holdMemcards: Boolean                  // main thread writes; mirror thread reads (@Volatile)
    val startsAllowed: Boolean                 // catchUps.startsAllowed
    fun keepsLine(message: String?, refusal: String): Boolean  // catchUps.keepsLine
    fun packMarkers(): PackMarkers?
    fun useMirror(data: String)
    suspend fun firstPull()
    suspend fun pull(onlyMemcards: Boolean): Boolean
    suspend fun push(force: Boolean)
    fun catchUp(): Job?
    fun readCorePaths(): Boolean                // the caller runs it on coreDispatcher, as today
}
```

Each body is today's `AppController` code, moved, with these substitutions:
- `NativeLibrary.x` becomes `core.x`.
- `coreStarted` and `dataGranted` become `hooks.coreStarted()` and `hooks.dataGranted()`.
- `message = COPYING`, and the "(n of total)" line, become `hooks.showCopying(...)`.
- `catchUp`'s `onEnd` becomes `if (hooks.copyingShown() && !catchUps.keepsLine(COPYING_LINE, COPYING_LINE))
  hooks.clearCopying()`. `COPYING_LINE` is any non-null marker, since `keepsLine` only compares it with its `refusal`.
- `readLibraryView()` becomes `hooks.onSettingsReloadedOnCore()`; `games = it` becomes `hooks.onLibraryRead(it)`.
- The five page reloads at the end of `applyChanged` become `hooks.onSettingsReloaded(steps)`.
- `refreshEditor()` becomes `hooks.refreshEditor()`; `Log.x(TAG, …)` becomes `log(…)`.

`dataLayout`, `memcardPaths` and `holdMemcards` keep `@Volatile`. `LayoutSettling`, `CatchUpRunner`, `cardGuard` and
`Applied` are private.

- [ ] **Step 1: Write the failing tests** in `DataSyncTest`. Each uses `runTest` with `StandardTestDispatcher(
  testScheduler)` for the scope, core and io dispatchers.
  - **`FakeMirror`** calls `deferred` for each path in its `paths` list and records `paths.filter(deferred)` as `left`.
    It returns `PullResult(changed, complete)` and `PushResult(changed, complete)` from settable fields.
  - **`FakeSyncCore`** appends each call's name to a shared `calls` list, with settable answers.
  - **`FakeSyncHooks`** keeps `line: String?` for the message line, and `coreStarted` and `dataGranted` flags, and
    appends its own calls to `calls`.

  The tests, each from the spec's list or the Review Focus:
  1. `nothingRunsBeforeTheCoreOrWithoutTheGrant`. With `coreStarted = false`, `pull(false)` and `push(true)` make no
     mirror run and no core call. The same with `dataGranted = false`.
  2. `aFullPullLeavesOnlyARunningGamesCards`. Paths `["memcards/a.mcd", "settings.ini"]`, card paths default. With
     `holdMemcards = true`, `left == ["memcards/a.mcd"]`; with it false, `left` is empty.
  3. `aCardsOnlyPullLeavesTheCardsOfAGameStartedMeanwhile`. `pull(onlyMemcards = true)` with `holdMemcards = false`
     leaves `["settings.ini"]`. Setting `holdMemcards = true` inside the fake's `deferred` callback, before the card,
     leaves both.
  4. `aPushDefersARunningGamesCards`. With `holdMemcards = true`, `push(false)` defers `"memcards/a.mcd"` and nothing
     else.
  5. `aPulledSettingsIniReloadsInOrder`. With `changed = setOf("settings.ini")`, `calls` is, in order:
     `reloadSettings`, `onSettingsReloadedOnCore`, `getMemoryCardPaths`, `getDataFolders`, `getDataFiles`,
     `getCacheFolder`, `setGameFolders`, `refreshGameList false`, `getGameList`, `onLibraryRead`,
     `onSettingsReloaded`.
  6. `aPulledGamesSettingsReadsOnlyTheCorePaths`. With `changed = setOf("gamesettings/SLUS-00001.ini")`, there's no
     `reloadSettings`, `setGameFolders` or `refreshGameList`; the core path calls are made, then
     `onSettingsReloaded`.
  7. `aGrownLayoutCatchesUpAndRefusesStartsUntilDone`. `getDataFolders` returns a new folder after the first pull.
     `startsAllowed` is false until `runCurrent()` finishes the catch-up's pulls (the fake reports no further
     growth), then true. A line set to plain "Copying…" before is cleared at the end.
  8. `readCorePathsKeepsCardsNoLongerReported`. Reported `["memcards/", "shared/a.mcd"]`, then `["memcards/"]`: a
     later pull's per-file check still treats `shared/a.mcd` as a card. `readCorePaths()` returns true on the
     first growth, and false when nothing new is reported.
  9. `theCardGuardHoldsOnlyCardsOnceTheCoreRuns`. Through the `guard` that `makeMirror` received: with
     `coreStarted = false`, a card path runs its action directly; with true, a card path goes through
     `core.holdCard`, and `"settings.ini"` runs directly.
  10. `theFirstPullPreparesTheWorkingCopyAndShowsProgress`. In a `TemporaryFolder`, a missing working copy is created
      and a stale manifest deleted before the pull. While the fake's state is `MirrorState.Syncing(done = 1,
      total = 3)`, the line reads "(2 of 3)", that is `showCopying(2 to 3)`.
  - **Review Focus:**
    - `aSecondMirrorReplacesTheFirstsState`. After `useMirror("b")`, the first fake's state changes no longer reach
      `mirrorState`.
    - `noReportedCardPathsMeansTheDefault`. `getMemoryCardPaths` is null, and `"memcards/x.mcd"` is still a card for
      `holdMemcards`.
    - `noDataFoldersLeavesTheLayout`. `getDataFolders` is null: `readCorePaths()` is false and `dataLayout` is
      unchanged.
    - `aPushThatBringsSettingsIniReloads`. With the push's `changed = setOf("settings.ini")`, the calls are the same
      as in test 5.
    - `aCatchUpEndingUnderAnotherLineLeavesIt`. The line is "Can't reach the folder." when the catch-up ends, and is
      still that after.
- [ ] **Step 2: Run** `./gradlew -q testDebugUnitTest --tests '*DataSyncTest'`. Expected: it fails to compile, as
  `DataSync` doesn't exist yet.
- [ ] **Step 3: Write `DataSync`** as in the Interfaces block, moving today's code.
- [ ] **Step 4: Run** `./gradlew -q testDebugUnitTest --tests '*DataSyncTest' ktlintCheck`. Expected: all 15 pass.
  Any failure means the move changed behaviour: compare with `AppController` at `a798e7697`, and don't change the
  test.
- [ ] **Step 5: Commit:** "Android: DataSync, the mirror's runs, with JVM tests of what they decide".

### Task 3: `AppController` delegates to `DataSync`

**Files:**
- Modify: `android/app/src/main/java/io/github/ilyalissoboi/goosestation/app/AppController.kt`

**Interfaces:**
- **Consumes:** Task 2's `DataSync` API, and `NativeSyncCore`.
- **Produces:** nothing new.

- [ ] **Step 1: Build `dataSync`** in `AppController`, as a `val dataSync: DataSync<Mirror>`:
  - `scope`, `coreDispatcher` and `Dispatchers.IO`;
  - `NativeSyncCore`;
  - a private `SyncHooks` object over `AppController`'s state (`coreStarted`, `dataGranted`, `readableGameFolders()`,
    `readLibraryView()`, the page reloads, `games`, `refreshEditor()`, and `message` with `COPYING`);
  - `workingCopy` and `manifestFile`;
  - `SharedPreferencesStore(context, "memcard_sync")`;
  - a factory making `Mirror(SafFolderStore(context.contentResolver, data), workingCopy, manifestFile,
    System::currentTimeMillis, removeStrays, guard, packMarkers, layout)`;
  - `log = { Log.w(TAG, it) }`. The one `Log.i` and one `Log.e` keep their levels through a `level` argument, or
    through two lambdas; the builder picks.
- [ ] **Step 2: Remove the moved members and point their callers at `dataSync`.**
  - **The members:** `mirror`, `mirrorStateJob`, `mutableMirrorState`, `memcardPaths`, `dataLayout`, `holdMemcards`,
    `layoutSettling`, `catchUps`, `cardGuard`, `shaderPackIds`, `packMarkers()`, `useMirror`, `firstPull`,
    `prepareWorkingCopy`, `pull`, `pullGrew`, `pullOnce`, `push`, `catchUp`, `Applied`, `applyChanged` and
    `readCorePaths`.
  - **The callers:**
    - the cold start and resume;
    - the ticker and `onStop`;
    - the grant callback;
    - `beginGame` (`holdMemcards = true`, `startsAllowed`);
    - `onGameStopped` (`holdMemcards = false`, `keepsLine`, `readCorePaths` on `coreDispatcher`, the pull and the
      push);
    - the eject (`mirror.letGo()`, `push`);
    - the data folder change (`mirror`, `dataLayout`, `::packMarkers` become `dataSync.packMarkers()`, `useMirror`,
      `pull`);
    - `mirrorState`'s public getter, which becomes `dataSync.mirrorState`.
- [ ] **Step 3: Verify.** Run `./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck
  checkNoWindowDialogs`. Expected: build success, 1154 JVM tests passed.
- [ ] **Step 4: Verify on `emulator-5580`, one class at a time:** `MemcardSyncTest`, `MemcardSyncCardsTest`,
  `MemcardSyncStateTest`, `MemcardSyncLifecycleTest`, `MemcardSyncSecretsTest`, `DataFolderChangeTest` and
  `MirrorTest`, then the tiers' release part. Expected: every class passes with none skipped, and the release part
  says "launched, loaded the core and asked for the update".
- [ ] **Step 5: Commit:** "Android: AppController hands the mirror's runs to DataSync".
