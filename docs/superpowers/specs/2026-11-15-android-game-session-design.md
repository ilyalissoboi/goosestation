# GameSession: a game's start, pause, menu and stop, out of AppController

Batch K of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, finding 3.1). It is
the second step of `AppController`'s split, after `DataSync` (#89).

## Why

`AppController` is 1,990 lines. `DataSync`, `StatusLine` and the activity's pause calls have come out of it, but the
game session is still inside, and no test runs it on the JVM: it calls `NativeLibrary` directly. That code decides:

- whether a start goes ahead, and what it says when it doesn't;
- whether a game runs or stays paused: after its boot, on the app's pause and resume, and as its menu opens and closes;
- what a stop resets, and what happens after it: the cards pulled, the writes pushed, and the next game started.

Three of the review's bugs sat in it: 1.1 (the card pull under the next game), 1.2 (Exit game lost) and 1.13 (the
delayed start). Each was found by reading. The pieces it leans on are tested alone (`PausePolicy`, `GameStartGate`,
`GameSwitch`), but not how they are put together.

The goal is that this logic **can be tested on the JVM**. A smaller `AppController` comes with it.

## Goals

- **A `GameSession` class** in `app/`, owning the session's state and what it does. It reaches the core and the rest of
  the app through two narrow interfaces, so a JVM test drives it with fakes.
- **JVM tests that pin today's behaviour**: a `GameSessionTest`, with the cases listed below.
- **`AppController` delegates to it**, and keeps no copy of the moved state.

## Non-goals

- **No behaviour change.** This is a pure move. A bug or a risk found on the way is noted, then fixed in its own commit
  after the move, or left for a later PR.
- **No change to `MainActivity` or any screen.** `AppController` keeps every public member it has now. The moved ones
  become one-line delegates (`val menuOpen get() = session.menuOpen`).
- **No change to `PausePolicy`, `GameStartGate`, `GameSwitch`, `GameStarts` or `InGameMenu`.** `GameSession` uses the
  first three as they are.
- **These stay in `AppController` for now:**
  - the `NativeCallbacks` themselves. `onGameStarted` and `onGameStopped` still post to the main thread there, then
    call the session;
  - RetroAchievements' messages (`signInExpired`, `unreachableSaid`) and `readSelectChords`;
  - SD card eject's plan (`onVolumeEjected`), which calls the session to stop the game;
  - `gameStarts`, the resume question, which calls the session to start.

## Anti-goals

These would count as failure even if the app still works:

- **A `GameSession` that reaches back into `AppController`**, through a reference to it or by reading its fields.
- **A different sequence of core calls**, or a different thread for any of them. `setPaused`, `startGame`,
  `shutdownGame`, the card pull and the push must come in today's order. Most of this code's past bugs were ordering
  bugs.
- **Tests that only check the fakes were called.** Each test pins a decision: whether a start boots, whether the game
  is paused, what a stop leaves behind.

## What moves

From `AppController.kt`, as of `origin/master` `225d1e8b6`.

**State, owned by `GameSession` from now on** (main thread only):
- `gameActive`, `gameRunning`, `runningGame` and `lastGamePath`;
- `menuOpen`;
- `resumed`;
- `pausedDuringBoot` and `pausesOnDisconnect`;
- `startGate` and `gameSwitch`.

**Behaviour:**
- **Starts:** `startGameNow`, `startGameFromStateNow` and `beginGame`, with its three refusals and their lines.
- **The menu and pausing:** `openMenu`, `closeMenu`, `pauseGame`, `exitToLibrary`, `exitGame`, `leaveGame` and
  `controllerDisconnected`.
- **The app's pause and resume:** the game's half of `onResume(activity)` and `onPause(activity)`. The `isCurrent`
  check stays in `AppController`.
- **The eject's stop:** the three lines under `EjectAction.FlushAndStopGame`.
- **The core's two events:** the session's part of `onGameStarted` and `onGameStopped`, called on the main thread.

## How it's shaped

These are my calls as the designer. A builder may adjust names, but not the shape.

- **`GameSession` constructor:**
  - its `scope` (main thread);
  - `settle`, what a start waits for (`settingsQueue.settle()`), for its `GameStartGate`;
  - a `SessionCore`;
  - a `SessionHooks`;
  - the `StatusLine`. It is a plain class already, so the session shows and clears its own lines, and a test reads
    them.
- **`SessionCore`:** the four `NativeLibrary` calls the moved code makes, and no others.
  - `startGame(path, disc, resume)` and `startGameFromState(path, key, slot, shared)`;
  - `shutdownGame()`;
  - `setPaused(paused)`.

  `NativeSessionCore` is the `NativeLibrary`-backed implementation. A fake records the calls in order.
- **`SessionHooks`:** what the session asks the app, or tells it.
  - **Asked:**
    - `ready`, `startsAllowed` (the catch-up's) and `dataChanging`;
    - `screen`, read and set. The session only ever sets `Game` or `Library`. *(My call: a property on the hooks until
      `Navigator` exists. The alternative, splitting `Navigator` out first, would pull in about 12 page models before
      any of this could be tested.)*
    - `keepsCopyingLine()`, for a stop's rule on the status line.
  - **Told:**
    - `onBegin(game)`: the library's focus and search are reset, the cards are held, a cover download stops, and the
      Saves page closes;
    - `onStarted(game)`: the menu, the touch controls, the sign-in message and the Select chords;
    - `onStopped()`: the messages over the game, the menu, the chords and the rumble are cleared;
    - `menuShown(open)`: the achievement overlay's cards wait while it is open;
    - `menuOpened()`, `releaseInput()`, `releaseTouch()`, `stopRumble()` and `say(text)`;
    - `readCorePaths()` after a start, and `syncAfterStop()`: the core's paths, the cards-only pull, then the forced
      push;
    - `startNext(next)`: the game the menu left for.
- **The status line's words** the session shows (`COPYING`, "Stopping the game…", "… didn't start. …", "Couldn't save
  your place: …") move with it. `COPYING` is shared with the cold start, so it becomes one `internal const`.
- **Threads.** Everything in `GameSession` runs on the main thread. `AppController`'s callbacks keep posting to it
  before they call in. Each field's KDoc says so, and which fields the UI reads.
- **Writes within one function.** Where a hook groups several of today's writes (`onBegin`), they stay in one main
  thread turn, so nothing can observe them part done. The order of the core calls around them doesn't change.

## Tests to write (JVM, `GameSessionTest`)

These pin today's behaviour, with a fake `SessionCore` and `SessionHooks`, a real `StatusLine`, and
`StandardTestDispatcher`s.

1. **A start is ignored** before the app is ready, while a game is under way, and while another start waits. Nothing
   boots.
2. **A start during a catch-up** shows "Copying…", boots nothing, and leaves the screen.
3. **A start that finds the player elsewhere**, or a data folder change under way, says the game didn't start. This
   repeats 1.13's case.
4. **A start that goes ahead** waits for the settings first. Then the game is active, the game screen shows and
   `onBegin` has run, all before the core's `startGame`. A start from a state calls `startGameFromState` the same way.
5. **The menu:** opening it releases the input, stops the rumble and pauses. A second open is ignored. Closing it
   unpauses only with the app in front and the game running.
6. **A pause while the game boots** is undone once it has booted, if it may run by then. A game that booted in the
   background, or with the menu open, is paused.
7. **A game that boots paused** (`StartPaused`) opens its menu.
8. **The app's pause and resume:** a pause off the game screen does nothing. A resume with the menu open leaves the
   game paused.
9. **Leaving a game** shows the library and "Stopping the game…", and shuts the game down. The game stays active until
   the core says it stopped, so a start meanwhile is ignored. An error already showing keeps its line.
10. **A stop** resets the session, and takes a game screen back to the library. It clears the status line, unless that
    is an error or a refused start's "Copying…".
11. **After a stop,** the cards sync first, and only then does the game the menu left for start. A stop with no game
    waiting starts none. This repeats 1.1's case at this level.
12. **Exit game** leaves the game even when the resume state failed, and then shows why. This repeats 1.2's case.
13. **A controller going** opens the menu and says so, only as `PausePolicy.pausesOnDisconnect` allows.
14. **The eject's stop** pauses, then shuts down, and takes a game screen back to the library.

## Constraints

- Kotlin, in `app/`, beside `AppController`, with the project's ktlint rules and KDoc style. Comments say why, and the
  ones in the moved code move with it.
- **Every check stays green:**
  - `assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`, with warnings as
    errors;
  - the quick tier on the test emulators.
- **On a device.** No instrumented test launches `MainActivity`, so the move is checked on the AYN Thor's debug build,
  with a game running: start, the menu, Home and back, Exit game, and a second start. That needs the Thor and your OK.
- **Commits:** `GameSession`, its interfaces and its tests first; then `AppController` moved onto it; then any
  follow-up fix; then the review's status section.

## Left to the builder

- File layout: `GameSession.kt`, plus `SessionCore.kt` if that reads better.
- Whether `SessionHooks` is one interface or two (what's asked, what's told).
- How the fakes are built, and the tests' names.

## What follows this, each a PR of its own

`AppController` comes out of this at 1,886 lines: what the session asks and tells the app stays in it, as its
`SessionHooks`. The rest of 3.1, in the order I'd take it:

1. **`FolderSettings`:** the saved folders, the grants, the BIOS import, the pickers and the data folder change.
2. **`Navigator`:** the `Screen` machine and its back stack (`settingsEntry`, `settingsInPage`, `focusSettings`,
   `libraryFocus`, `conflictsFrom`). `SessionHooks.screen` then becomes the navigator.
3. **The callbacks adapter:** a class of its own for `NativeCallbacks`, which posts to the main thread and calls the
   pieces. `AppController` is then the wiring, the lifecycle and the cold start, and its class doc can say truthfully
   which thread touches what.
