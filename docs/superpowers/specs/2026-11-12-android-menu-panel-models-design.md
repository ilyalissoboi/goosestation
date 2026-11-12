# The in-game menu's codes and achievements panels as models of their own

Batch C, part 5, of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, the rest
of finding 3.2). The save lists, the first half of 3.2, became `SaveListModel` in PR #90.

## Why

`app/InGameMenu.kt` (1,104 lines) still runs two panels inline:
- **Patch codes:** open and read the list, switch a code (shown at once, put back with a reason if the core refuses),
  and apply one.
- **Achievements:** its two tabs and their reads, the open leaderboard with its own request counter, and the core's
  later answer for it.

Both drop late reads by the menu-wide `panelId`. The review's bug from that (one panel's read dropped by another's)
went with `withSide`, since only one side panel is open at a time now. What's left is structure: the menu is larger
than it needs to be, and these panels are tested only through the whole menu.

**The goal is two small models, each with its own counter for dropping late reads, tested on their own, that the
menu delegates to, as it does to `SaveListModel`.** This is the user's choice: a pure move, with no behaviour change.

## Goals

1. **`PatchCodesModel`** in `app/`. It owns the `CodePanel` state and its reads: open, close, `setCode(index, on)`
   with its rollback on a refusal, and `applyCode(index)`.
2. **`AchievementsPanelModel`** in `app/`. It owns the `AchievementsPanelState` and its reads:
   - open on a tab, close, and switch tabs (an open leaderboard closes, and its late answer is dropped);
   - open and close a leaderboard;
   - take the core's `leaderboardFetched(request, page, error)`;
   - read the Achievements tab's sections again when the sign-in or the game's achievements change, and only while
     that tab is open, as today.
3. **Each has its own counter,** and the leaderboard keeps its request counter, both private to the model.
4. **`InGameMenu` delegates.** It keeps:
   - the checks it makes before a panel opens (the game booted, hardcore, the game's achievement status);
   - `withSide`, which closes the other panels;
   - the callbacks that reach the menu: `achievementsChanged()` and `leaderboardFetched(...)`.

   Each model pushes its state to the menu through `onChange`, so `GameMenuState.codes` and `.cheevos` keep their
   types, and the UI doesn't change.

## Non-goals

- **No behaviour change:** same reads, in the same order, on the same dispatcher, dropped in the same cases.
- **No UI change.** `GameMenuState`, `CodePanel`, `AchievementsPanelState` and `GameMenuActions` keep their shape.
- The rest of the menu (the slot panel, discs, exit, hotkeys) stays as it is.

## Anti-goals

- **A late read that lands.** A read or a code's refusal that comes back after its panel closed, was opened again,
  or switched tabs is dropped, as now.
- **A model that reaches into the menu.** Each gets its scope, the game dispatcher, the API and `onChange`, and
  nothing else.
- **A panel left open in a model while the menu shows none,** or the reverse. Every path that drops a panel today
  must close its model:
  - `withSide`, when another panel opens;
  - `opened()`;
  - `gameStarted()` and `gameStopped()`;
  - the panel's own close.

## How it's shaped

My calls; a builder may adjust names, not the shape.

- **The models follow `SaveListModel`:** a plain class, main thread only, with an `onChange: (S?) -> Unit` and a
  private generation that `open` and `close` bump. The achievements model's tab switch bumps it too, as today's
  `switchTab` bumps `panelId`.
- **Each model is built once,** as `slotLists` is, so its leaderboard request counter keeps counting up across opens.
- **The menu decides whether a panel may open,** and on which tab, and whether a tab switch may happen (the game has
  both tabs). It then calls the model. *(My calls, from the builder check.)*
- **`InGameMenu`'s public API** (its `GameMenuActions`, `achievementsChanged()` and `leaderboardFetched()`) is
  unchanged, so `MainActivity`, `AppController` and the UI are untouched.
- **`panelId` stays** for what's left that uses it: the slot panel's own work, and the disc panel.
- **Commits:**
  1. `PatchCodesModel`, test-first, and the menu onto it.
  2. `AchievementsPanelModel`, test-first, and the menu onto it.

## Tests

- **JVM:**
  - **`PatchCodesModelTest`:**
    - open reads the list;
    - a refused switch is put back, with its reason;
    - apply says what was applied, or why not;
    - a late read or refusal after a close, or after opening again, is dropped.
  - **`AchievementsPanelModelTest`:**
    - open reads the tab;
    - a switch reads the other tab and closes an open leaderboard;
    - a leaderboard's answer lands only for the request still open;
    - a reread after the achievements change reads only the Achievements tab;
    - a late read after a close is dropped.
  - **`InGameMenuTest`** passes unchanged. It drives these panels through the menu today, so it pins the move.
- **Instrumented** (the `ui` package, on the emulator): `GameMenuTest` and the achievements panel's UI tests pass.
- **CI:** `assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs` green.

## Constraints

- Kotlin in `app/`, with the project's ktlint rules and KDoc style.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.

## Left to the builder

- File names, and the tests' names and fakes. The existing `FakeGameCore` should serve.
- Whether a model takes the `GameCoreApi` as a whole or only the calls it makes.
