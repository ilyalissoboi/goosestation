# DataSync: the data folder mirror's runs, out of AppController

Batch C, part 1, of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, finding
3.1).

## Why

`AppController` is about 2,100 lines and runs on the JVM in no test: it calls `NativeLibrary` directly and builds its
own Android pieces. The data folder mirror's orchestration lives inside it. That code decides:

- which files a pull leaves alone (a running game's memory cards);
- when a catch-up pull runs, and that no game starts until it's done;
- what a pull that changed `settings.ini` or a game's settings does next: reload the settings, set the game folders
  again, rescan the library;
- when the data layout has grown.

It's the app's most intricate logic, and it has no test of its own. Review finding 1.1 sat in it: the card pull after a
game stopped replaced the cards of the game started meanwhile. That was caught only by reading.

The goal is that this logic **can be tested on the JVM**. A smaller `AppController` comes with it, but isn't the point.

## Goals

- **A `DataSync` class** in `app/`, owning the mirror's runs and the state they decide by. It takes its dependencies
  through narrow interfaces, so a JVM test can drive it with fakes.
- **JVM tests that pin today's behaviour** of what moves: a `DataSyncTest`, with the cases listed below.
- **`AppController` delegates to it** and keeps no copy of the moved state.

## Non-goals

- **No behaviour change.** This is a pure move. A bug or a risk found on the way is noted, then fixed in its own
  commit after the move, or left for a later PR. It is never mixed into the move. That includes the open 1.13 item,
  waiting for queued settings writes before a boot.
- **No change to how `Mirror`, `MirrorPlanner`, `FolderStore` or `DataFolderChange` behave.** The one edit to `Mirror`
  is declaring that it implements `MirrorRuns`, adding `override`s. `Mirror` keeps its instrumented tests. `DataSync`'s tests fake the mirror; they check `DataSync`'s decisions, not the file copying.
- **These stay in `AppController` for now:**
  - the data folder change (`changeDataFolder` and `DataFolderChange`);
  - SD card eject (`onVolumeEjected`, `EjectPlan`);
  - memory card sync's foreground and background (`SyncVisibility`);
  - the two-second push ticker's lifecycle;
  - folder grants;
  - the game session.

  They keep calling into `DataSync`, as they call into the mirror now.

## Anti-goals

These would count as failure even if the app still works:

- **A `DataSync` that reaches back into `AppController`**, through a reference to it or by reading its fields. It
  gets everything through its constructor and its interfaces.
- **A different sequence of calls.** The order of mirror runs, `NativeLibrary` calls and page reloads, and the thread
  each runs on, must be what it is now. Most of this code's past bugs were ordering bugs, and the comments record why
  each order is what it is.
- **Tests that only check the fakes were called.** Each test pins a decision: what a pull leaves, whether a catch-up
  runs, whether starts are refused, which steps follow a change.

## What moves

From `AppController.kt`, as of `origin/master` `a798e7697`, sections "The sequences" and the mirror's helpers:

**State, owned by `DataSync` from now on:**
- `mirror`, and the published `mirrorState`;
- `layoutSettling`;
- `catchUps` (the `CatchUpRunner`);
- `memcardPaths` and `dataLayout`, both `@Volatile`: written on `coreDispatcher`, read on the mirror's thread;
- `holdMemcards`: set by the game session, read for each file a run decides on.

**Behaviour:**
- `cardGuard`, `shaderPackIds` and `packMarkers()`;
- `useMirror(data)`, `firstPull()` and `prepareWorkingCopy()`;
- `pull(onlyMemcards)`, `pullGrew()`, `pullOnce(...)` and `push(force)`;
- `catchUp()`, `applyChanged(...)` with its `Applied`, and `readCorePaths()`.

**What `AppController` keeps, delegating:**
- **The game session:**
  - `beginGame` sets `holdMemcards = true` through `DataSync`;
  - `onGameStopped` clears it, and runs `readCorePaths`, a cards-only pull and a forced push through `DataSync`;
  - `beginGame` asks `DataSync` whether starts are allowed (`catchUps.startsAllowed`).
- **Lifecycle:** the cold start, resume, the ticker, `onStop`'s forced push, and a newly granted data folder's pull
  all call `DataSync`.
- **The data folder change and eject** read `mirror`, `dataLayout` and `packMarkers` from `DataSync`, and call
  `useMirror`, `push` and `pull` on it.

## How it's shaped

These are my calls as the designer. A builder may adjust names, but not the shape.

- **`DataSync` constructor:**
  - its `scope` (main thread) and `coreDispatcher`;
  - a `MirrorFactory`, or the mirror passed through `useMirror`;
  - a `SyncCore`;
  - a `SyncHooks`;
  - the working-copy folder, the manifest file, and the `memcard_sync` key-value store for `EmusyncCarryOver`.
- **A mirror interface.** `DataSync` depends on a small interface, `MirrorRuns`, which `Mirror` implements: `pull(leave)`,
  `push(force, defer)`, `state` and `letGo()`.
  - **`DataSync` is generic over it:** `DataSync<M : MirrorRuns>`, with `M = Mirror` in the app.
  - **The data folder change and the eject** take `DataSync.mirror`, typed `M?`. So `DataFolderChange.switchFrom(old:
    Mirror?)` still gets the concrete `Mirror`, unchanged, and `AppController` keeps no mirror of its own.
  - **Tests** use `DataSync<FakeMirror>`. The fake records each file's decision and returns a chosen result: what
    changed, and whether it completed.
  - *(My call: the alternatives, an adapter around `Mirror` or loosening `switchFrom`, either hand out two views of one
    mirror or change `DataFolderChange`.)*
- **`SyncCore`:** the `NativeLibrary` calls the moved code makes, and no others.
  - `reloadSettings()`, `getMemoryCardPaths()`, `getDataFolders()`, `getDataFiles()`, `getCacheFolder()`;
  - `setGameFolders(...)`, `refreshGameList(...)`, `getGameList()`;
  - `memcardSyncHoldCard(path, action)`;
  - `getShadersFolder()`, `shaderPackIds()`, `shaderPackMarkerFiles(id)`.

  `NativeSyncCore` is the `NativeLibrary`-backed implementation. A fake records the calls and their order.
- **`SyncHooks`:** what `DataSync` needs to tell the app, or ask it.
  - `coreStarted()` and `dataGranted()`, which are read, not stored;
  - `readableGameFolders()`;
  - `onLibraryRead(games)`;
  - `onSettingsReloadedOnCore()`: today's `readLibraryView()`, run on `coreDispatcher` within the reload step;
  - `onSettingsReloaded(steps)`: the page reloads `applyChanged` does after the core work, on the main thread;
  - `refreshEditor()`;
  - the message line:
    - `showCopying(progress?)` sets the line to `COPYING`, or "COPYING (n of total)", without touching
      `messageIsError`, as `firstPull` does today (`message = ...`, not `showMessage`);
    - `copyingShown()` says whether the line is still the plain `COPYING`;
    - `clearCopying()` clears it.

    `catchUp`'s end runs today's whole rule in `DataSync`: `if (copyingShown() && !catchUps.keepsLine(COPYING,
    COPYING)) clearCopying()`. Test 7 then covers all of it. *(My call: a query hook, rather than splitting the rule
    between `DataSync` and `AppController`.)*
  - `startsAllowed`: `DataSync` exposes `catchUps.startsAllowed` and `keepsLine` for `beginGame` and `onGameStopped`,
    which keep their message handling.
- **Threads, as now.** `DataSync` runs on the main thread, except where the code says otherwise today: the core work
  on `coreDispatcher`, and the mirror's per-file decisions on the mirror's thread. Each field's KDoc says which thread
  writes it and which reads it.

## Tests to write (JVM, `DataSyncTest`)

These pin today's behaviour, with a fake `MirrorRuns`, `SyncCore` and `SyncHooks`, and `StandardTestDispatcher`s.

1. **Nothing runs** before the core has started, or without the data grant: no pull, no push, no core call.
2. **A full pull** leaves only the running game's cards while `holdMemcards`, and nothing otherwise.
3. **A cards-only pull** brings in only the cards, and leaves the cards of a game started meanwhile. This repeats
   1.1's case at this level.
4. **A push** defers the running game's cards while `holdMemcards`.
5. **A pull that changed `settings.ini`** runs, in order on `coreDispatcher`: reload settings, the on-core hook, read
   the core's paths, set the game folders, rescan. Then `onLibraryRead` with the new list, and `onSettingsReloaded`.
6. **A pull that changed only a game's settings** reads the core's paths, and nothing else.
7. **When reading the core's paths grows the layout:** the layout is unsettled, a catch-up runs, starts are refused
   until it ends, and the "Copying…" line follows `CatchUpRunner`'s rule.
8. **`readCorePaths`** keeps cards the core no longer reports (`MemoryCardPaths.merge`). It returns whether the layout
   grew.
9. **`cardGuard`** holds a card through the core only once the core has started, and only for a card path. Anything
   else runs the action directly.
10. **`firstPull`** shows its progress on the message line as "(n of total)", and prepares the working copy first.

## Constraints

- Kotlin, in `app/`, beside `AppController`, with the project's ktlint rules and KDoc style. Comments say why, as the
  code around them does.
- **Every check stays green:**
  - `assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`;
  - the instrumented `MemcardSync*`, `DataFolderChangeTest` and `MirrorTest` classes on the emulator;
  - the tiers' release part. R8 is unaffected, since no JNI names change.
- **Commits:** the new types and their tests first, then `AppController` moved onto them, then any follow-up fix.

## Left to the builder

- File layout: `DataSync.kt`, plus `SyncCore.kt` if that reads better.
- Whether `MirrorRuns` sits in `mirror/` or in `app/`.
- How the fakes are built, and the tests' names.
- Whether `Applied` stays private to `DataSync`.
