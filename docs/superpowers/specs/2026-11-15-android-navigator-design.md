# Navigator: the app's screens and how the player moves between them, out of AppController

Batch M of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, finding 3.1). It is
the fourth step of `AppController`'s split, after `DataSync` (#89), `GameSession` (#113) and `FolderSettings` (#114).

## Why

`AppController` is 1,691 lines. About 220 of them decide which screen shows and where the focus lands on the next
one: the settings' categories and pages, a game's Saves and its own settings, the memory card editor, and sync's
conflict screen. No test runs that code on the JVM. It decides:

- whether a screen may open from where the player is, and whether the core must be ready for it;
- which settings page closes before another opens, and when one can't close (Folders, during a data folder change);
- where back goes: the categories with the page's entry focused, the library with its Settings button or the game
  focused, or the screen the conflicts were opened from;
- in the settings' two panels, whether the focus is in the page or on its category.

Each rule is short, but there are about twenty of them, and a wrong one strands the focus or leaves a page's model
open. `GameSession` and `FolderSettings` both read and set the screen through their hooks today. With the screen in a
class of its own, those hooks call it, and its rules can be tested.

The goal is that these rules **can be tested on the JVM**. A smaller `AppController` comes with it.

## Goals

- **A `Navigator` class** in `app/`, owning the screen, the focus hints and the moves between screens. It reaches the
  page models through a narrow interface, so a JVM test drives it with a fake.
- **JVM tests that pin today's behaviour**: a `NavigatorTest`, with the cases listed below.
- **`AppController` delegates to it**, and keeps no copy of the moved state.

## Non-goals

- **No behaviour change.** This is a pure move. A bug or a risk found on the way is noted, then fixed in its own commit
  after the move, or left for a later PR.
- **No change to `MainActivity` or any screen.** `AppController` keeps every public member it has now. The moved ones
  become one-line delegates.
- **No change to `GameSession`, `FolderSettings` or their hooks' interfaces.** *(My call: the design for `GameSession`
  said `SessionHooks.screen` would become the navigator. It does, inside `AppController`'s implementation of the hook.
  Changing the interface itself would rewrite 38 passing tests for no gain.)*
- **No change to the page models** (`SettingsPages`, `ControllersPage`, `SavesPage` and the rest), nor to the
  Controllers page's own back (`controllersBack`), which stays in `AppController`.

## Anti-goals

These would count as failure even if the app still works:

- **A `Navigator` that reaches back into `AppController`**, or holds a page model.
- **A different order of a page's close, the next page's load, and the screen change.** A page that loads before the
  last one has closed, or a screen that changes before its page is read, is the kind of bug this code can have.
- **Tests that only check the fake was called.** Each test pins a decision: which screen shows afterwards, where the
  focus goes, or whether the move was refused.

## What moves

From `AppController.kt`, as of `origin/master` `6508bbfbc`.

**State, owned by `Navigator` from now on** (main thread only; the UI reads all but the last):
- `screen`;
- `settingsEntry`, `settingsInPage` and `focusSettings`;
- `libraryFocus`;
- `conflictsFrom`.

**The `Screen` enum** moves to `Navigator.kt`, in the same package, so no import changes.

**Behaviour:**
- **The settings:** `openSettings`, `closeSettings`, `openSettingsPage`, `showSettingsPage`, `enterSettingsPage`,
  `closeSettingsPage`, `leaveSettings`, and the rules of `loadSettingsPage` and `unloadSettingsPage`. What each page
  loads and closes stays with the pages, behind the interface below.
- **The library's other screens:** `openSaves`, `closeSaves`, `openGameSettings`, `closeGameSettings`.
- **Under Memory cards:** `openMemcardEditor`, `closeMemcardEditor`, `openSyncConflicts`, `openSyncBadge` and
  `closeSyncConflicts`.
- **The moves other pieces make:** to the game and back to the library (`GameSession`), the folder settings opened
  from a library banner (`FolderSettings`), Setup and the library at the cold start, and Setup's Finish. Each is a
  named function, not a free write to `screen`.
- **A game's start resetting the focus** (`libraryFocus`, `focusSettings`), the navigation half of `onBegin`.

## How it's shaped

These are my calls as the designer. A builder may adjust names, but not the shape.

- **`Navigator` constructor:** a `ScreenPages` only. It needs no scope: every move is synchronous today, and stays so.
- **`ScreenPages`:** what the navigator asks of the pages and the app, or tells them.
  - **Asked:**
    - `ready` and `gameActive`, for `settingsReady`;
    - `hasConflicts`, for the library's sync badge.
  - **Told, in the order the code calls them now:**
    - `loadPage(entry)`: reads what `entry`'s page shows. The navigator then makes it the screen;
    - `closePage(entry)`: false if it can't close (Folders during a data folder change), and the move stops there.
      Otherwise the page's model closes (Folders also drops its candidate);
    - `openSaves(game)` and `closeSaves()`, which returns the game's path for the library's focus;
    - `openGameSettings(game)` and `closeGameSettings()`, the same;
    - `openMemcardEditor()`, and `memcardEditorClosed()`, which counts the cards again;
    - `openSyncConflicts()` and `syncConflictsClosed()`;
    - `savesLeftForGame()`: the Saves page closes as a game starts from it.
- **Threads.** Main thread only, as today. Each field's KDoc says which the UI reads.
- **`AppController`'s hooks for the other pieces** read and move through the navigator: `SessionHooks.screen`'s
  setter calls the navigator's move to the game or to the library, `FolderHooks.onFolders`, `onLibrary` and
  `showFolders` call it too, and so do the settings reloads and `Updates`' two flows.

## Tests to write (JVM, `NavigatorTest`)

These pin today's behaviour, with a fake `ScreenPages` writing to one ordered log.

1. **The settings open** only from the library, on their categories with nothing focused, and close back to the
   library with its Settings button focused.
2. **A page opens from the categories** with the focus in it. One that needs the core doesn't open until the app is
   ready and no game is under way. Folders opens anyway.
3. **A page shown beside the categories** replaces the open one: the old page closes before the new one loads. Its own
   category only moves the focus out of it. One that can't close keeps its place.
4. **Back from a page** goes to the categories with its entry focused. **Leaving the settings** from a page closes it
   and focuses the library's Settings button. Neither happens while Folders can't close.
5. **Entering the page** moves the focus into it, and does nothing on the categories.
6. **Saves and a game's own settings** open only from the library, the latter only with the settings ready and a
   serial. Back focuses that game in the library, not the Settings button.
7. **The memory card editor** opens only from Memory cards with the settings ready, and back counts the cards again.
8. **The conflict screen** goes back to where it was opened from: Memory cards or the library.
9. **The library's sync badge** opens the conflicts when there are any, and otherwise Memory cards with the focus in
   the page.
10. **The game's moves:** a start clears the library's focus hints and closes Saves if it showed. Leaving the game
    shows the library.
11. **The folder settings from a banner** open in their page, with the focus in it.
12. **Setup's Finish** goes ahead once only, from Setup.

## Constraints

- Kotlin, in `app/`, beside `AppController`, with the project's ktlint rules and KDoc style. Comments say why, and the
  ones in the moved code move with it.
- **Every check stays green:**
  - `assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`, with warnings as
    errors;
  - the quick tier on the test emulators.
- **On a device.** No instrumented test launches `MainActivity`. On the AYN Thor's debug build, with your OK: the
  settings opened from the library, a page entered and left, one page shown beside another, back to the library, a
  game's Saves and its settings opened and closed, and the memory card editor. None of these changes a setting.
- **Commits:** `Navigator`, its interface and its tests first; then `AppController` moved onto it; then any follow-up
  fix; then the review's status section.

## Left to the builder

- File layout: `Navigator.kt`, with `Screen` and `ScreenPages` in it or beside it.
- How the fake is built, and the tests' names.

## What follows this

`AppController` comes out of this at 1,591 lines: what each page loads and closes stays in it, as its `ScreenPages`.
One step of 3.1 is left, as a PR of its own:

- **The callbacks adapter:** a class of its own for `NativeCallbacks`' 30 overrides, which posts to the main thread
  and calls the pieces. `AppController` is then the wiring, the lifecycle and the cold start, and its class doc can
  say truthfully which thread touches what.
