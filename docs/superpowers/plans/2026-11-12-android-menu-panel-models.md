# Menu Panel Models Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the in-game menu's patch codes and achievements panels out of `InGameMenu` into `PatchCodesModel` and
`AchievementsPanelModel`, each with its own counter for late reads, with no behaviour change.

**Architecture:**
- **The models:** each one follows `SaveListModel`. It's a plain class, main thread only, with
  `onChange: (S?) -> Unit` and a private generation.
- **The menu:** it builds each model once, decides whether a panel may open (and on which tab), and closes the model
  on every path that drops its panel.
- **What doesn't change:** `GameMenuState`, `CodePanel`, `AchievementsPanelState` and `GameMenuActions`.

**Tech Stack:** Kotlin 2.4, kotlinx-coroutines-test, JUnit 4, ktlint.

**Spec:** `docs/superpowers/specs/2026-11-12-android-menu-panel-models-design.md`

## Global Constraints

- **A pure move.** The same reads, in the same order, on the same dispatcher, dropped in the same cases.
  `InGameMenuTest` passes unchanged.
- **A model gets nothing from the menu** but its scope, the game dispatcher, `GameCoreApi` and `onChange`.
- **Every path that drops a panel closes its model:** `withSide`, `opened()`, `gameStarted()`, `gameStopped()` and
  the panel's own close.
- **Checks:**
  - `./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`;
  - `ui.GameMenuTest` and `ui.AchievementsPanelTest` (if present) on `emulator-5580`, with
    `-Pgoose.orchestrator=false`.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A code's refusal that comes back after the panel closed and opened again.** It doesn't flip the reopened list's
   row back, or show its error there.
2. **A leaderboard's answer for a request no longer open.** That covers a closed leaderboard, a switched tab or a
   closed panel. It lands nowhere.
3. **The achievements changing while the Leaderboards tab is open.** Nothing is read.
4. **A panel opened while another is open.** The other model is closed, so its late reads land nowhere.
5. **The game stopping with a panel open.** Its model is closed, and the next game's menu opens with no panel.

Tests: 1 in Task 1; 2 and 3 in Task 2; 4 and 5 in Task 2's `InGameMenuTest` additions.

---

### Task 1: `PatchCodesModel`, test-first, and the menu onto it

**Files:**
- create `app/PatchCodesModel.kt`;
- modify `app/InGameMenu.kt`;
- test: create `test/.../app/PatchCodesModelTest.kt`, with `FakeGameCore`.

**Interfaces (produced):**

```kotlin
class PatchCodesModel(scope: CoroutineScope, game: CoroutineDispatcher, api: GameCoreApi,
                      onChange: (CodePanel?) -> Unit) {
    val state: CodePanel?
    fun open()                         // CodePanel(), then the list and the player's file
    fun close()
    fun setCode(index: Int, on: Boolean)   // shown at once; put back, with "Couldn't switch <name>: <reason>", if refused
    fun applyCode(index: Int)              // "Applied: <name>", or "Couldn't apply <name>: <reason>"
}
```

- [ ] **Step 1: Write the failing tests** in `PatchCodesModelTest`:
  - `openReadsTheGroupsAndTheFile`;
  - `aRefusedSwitchIsPutBackWithItsReason`;
  - `applySaysWhatWasAppliedOrWhyNot`;
  - `aRefusalAfterACloseAndReopenIsDropped` (Review Focus 1);
  - `aReadAfterACloseIsDropped`.

  Each uses the existing `FakeGameCore` (`codes`, `codesFile`, `codeError`).
- [ ] **Step 2: Run** `--tests '*PatchCodesModelTest'`. Expected: it fails to compile.
- [ ] **Step 3: Write `PatchCodesModel`** from `InGameMenu`'s `openCodes`, `closeCodes`, `setCode`, `applyCode`,
  `codeRow` and `changeCode` bodies, as they are, with the model's generation in place of `panelId`.
- [ ] **Step 4: Move the menu onto it.**
  - `openCodes` keeps its `booted` and `hardcore` checks, does `state = withSide()`, then `codes.open()`.
  - `closeCodes`, `setCode` and `applyCode` delegate.
  - `withSide`, `opened`, `gameStarted` and `gameStopped` call `codes.close()`.
  - `onChange` sets `state.codes`.
- [ ] **Step 5: Run** `--tests '*PatchCodesModelTest' --tests '*InGameMenuTest' ktlintCheck`. Expected: all pass,
  and `InGameMenuTest` is unchanged.
- [ ] **Step 6: Commit:** "Android: the in-game menu's patch codes are a PatchCodesModel".

### Task 2: `AchievementsPanelModel`, test-first, and the menu onto it

**Files:**
- create `app/AchievementsPanelModel.kt`;
- modify `app/InGameMenu.kt`;
- test: create `test/.../app/AchievementsPanelModelTest.kt`, and two additions to `InGameMenuTest`.

**Interfaces (produced):**

```kotlin
class AchievementsPanelModel(scope: CoroutineScope, game: CoroutineDispatcher, api: GameCoreApi,
                             onChange: (AchievementsPanelState?) -> Unit) {
    val state: AchievementsPanelState?
    fun open(tab: CheevoTab)
    fun close()
    fun switchTab()                     // the other tab, read; an open leaderboard closes
    fun openLeaderboard(id: Int)
    fun closeLeaderboard()
    fun leaderboardFetched(request: Int, page: LeaderboardPage?, error: String?)
    fun achievementsChanged()           // the Achievements tab's sections again, only while that tab is open
}
```

- `open`, `close` and `switchTab` bump the model's generation, as they bump `panelId` today.
- The leaderboard request counter is the model's, and it counts up across opens.

- [ ] **Step 1: Write the failing tests** in `AchievementsPanelModelTest`:
  - `openReadsItsTab`;
  - `aSwitchReadsTheOtherTabAndClosesTheLeaderboard`;
  - `aLeaderboardAnswerLandsOnlyForTheOpenRequest` (Review Focus 2: closed leaderboard, switched tab, closed panel);
  - `achievementsChangedReadsOnlyTheAchievementsTab` (Review Focus 3);
  - `aReadAfterACloseIsDropped`.

  In `InGameMenuTest`:
  - `openingAnotherPanelClosesTheCodesModel` (Review Focus 4): a codes refusal that lands after the achievements
    opened changes nothing;
  - `theGameStoppingClosesAnOpenPanel` (Review Focus 5).

  Both pass today; they pin the move.
- [ ] **Step 2: Run** `--tests '*AchievementsPanelModelTest'`. Expected: it fails to compile.
- [ ] **Step 3: Write `AchievementsPanelModel`** from `InGameMenu`'s achievements bodies (`openAchievements`'s state
  and read, `closeAchievements`, the cheevos branch of `switchTab`, `openLeaderboard`, `closeLeaderboard`,
  `leaderboardFetched`, `readTab`, `readLeaderboards`, `readSections`), as they are.
- [ ] **Step 4: Move the menu onto it.**
  - `openAchievements` keeps its checks and its choice of tab, does `state = withSide()`, then `cheevos.open(tab)`.
  - `switchTab`'s cheevos branch keeps the both-tabs check, then calls `cheevos.switchTab()`.
  - `achievementsChanged()` calls `readGameState()`, then `cheevos.achievementsChanged()`.
  - The rest delegates.
  - `withSide`, `opened`, `gameStarted` and `gameStopped` call `cheevos.close()`.
  - `leaderboardRequest` goes from the menu.
- [ ] **Step 5: Run** the JVM tests, then `ui.GameMenuTest` on the emulator, then the CI tasks. Expected: all pass,
  and `InGameMenuTest`'s existing tests are unchanged.
- [ ] **Step 6: Commit:** "Android: the in-game menu's achievements panel is an AchievementsPanelModel".
