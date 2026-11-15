# FolderSettings: the saved folders, their grants and their changes, out of AppController

Batch L of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, finding 3.1). It is
the third step of `AppController`'s split, after `DataSync` (#89) and `GameSession` (#113).

## Why

`AppController` is 1,886 lines. About 330 of them keep the saved folders and change them: the BIOS, games and data
folders, their grants, and the data folder change that ends in a restart. No test runs that code on the JVM, because
it calls the system picker, the content resolver and `DataFolderChange` directly. It decides:

- which folder a pick replaces, and what is saved, released and refreshed after it;
- when a pick is refused, and what the refusal says;
- whether a data folder change may start, what it does when the old folder missed changes, and what a failed change
  puts back.

The pieces it leans on are tested alone (`FolderPrefs`, `GrantCheck`, `DataFolderCheck`, `DataFolderChange`), but not
how they are put together. The data folder change is the riskiest of them: it can delete the working copy.

The goal is that this logic **can be tested on the JVM**. A smaller `AppController` comes with it.

## Goals

- **A `FolderSettings` class** in `app/`, owning the folders' state and what changes them. It reaches Android and the
  rest of the app through narrow interfaces, so a JVM test drives it with fakes.
- **JVM tests that pin today's behaviour**: a `FolderSettingsTest`, with the cases listed below.
- **`AppController` delegates to it**, and keeps no copy of the moved state.

## Non-goals

- **No behaviour change.** This is a pure move. A bug or a risk found on the way is noted, then fixed in its own commit
  after the move, or left for a later PR.
- **No change to `MainActivity` or any screen.** `AppController` keeps every public member it has now. The moved ones
  become one-line delegates.
- **No change to `FolderPrefs`, `FolderPicker`, `BiosImporter`, `GrantCheck`, `DataFolderCheck` or
  `DataFolderChange`.**
- **These stay in `AppController` for now:**
  - `finishSetup`, the cold start and the resume, which read and set the folders through `FolderSettings`;
  - `banners` and `dataStatus`, which are one line each over `LibraryBanners`;
  - the SD card's eject and mount, which read the data folder;
  - `dropStaleBiosOverrides`, which writes the core's settings. `FolderSettings` only says the BIOS images changed;
  - the library's refresh (`updateLibrary`, `applyGameFolders`).

## Anti-goals

These would count as failure even if the app still works:

- **A `FolderSettings` that reaches back into `AppController`**, through a reference to it or by reading its fields.
- **A different order of the save, the release and the grant check** after any change. A grant released before the
  save lands, or a mirror restarted before the folder is put back, is the kind of bug this code can have.
- **Tests that only check the fakes were called.** Each test pins a decision: what `folders` holds afterwards, what was
  released, what the status line says.

## What moves

From `AppController.kt`, as of `origin/master` `ece2cbb31`.

**State, owned by `FolderSettings` from now on** (main thread only; the UI reads all but the last two):
- `folders`, `biosImages` and `missingGrants`;
- `dataCandidate` and `dataChange`;
- `oldFolderMissedChanges` and `biosJob`.

**Behaviour:**
- **Loading and saving:** the load at the start, `save`, `release`, `releaseFolder` and `checkGrants`, with
  `dataGranted` and `readableGameFolders`.
- **The BIOS folder:** `importBios`, `changeBios`, `rescanBios` and `useBiosFolder`.
- **The games folders:** `addGamesFolder`, `setGamesFolders`, `removeGamesFolder` and `refuseGamesFolder`.
- **A missing grant:** `repick`.
- **The data folder:** `inspectDataFolder`, `pickDataFolder`, `offerDataFolder`, `cancelDataChange`,
  `changeDataFolder`, `abandonDataChange` and `dataReachable`.

## How it's shaped

These are my calls as the designer. A builder may adjust names, but not the shape.

- **`FolderSettings` constructor:**
  - its `scope` (main thread), and the dispatcher the prefs are written on;
  - the `FolderPrefs`. It is a plain class over a `KeyValueStore`, so a test gives it one in memory;
  - a `FolderAccess`;
  - a `DataFolderSwitch`;
  - a `FolderHooks`;
  - the `StatusLine`.
- **`FolderAccess`:** what the moved code asks of Android.
  - `pick(kind)`: the system picker of the activity shown. It returns null with no activity, as a backed-out pick
    does. Today's code returns before it launches anything in that case, which comes to the same.
  - `release(uri, saved)`: nothing with no activity, as today;
  - `importBios(tree)` and `inspectDataFolder(tree, games)`;
  - `missingGrants(saved)`: `GrantCheck` over the resolver's persisted grants.

  `AppController` builds the Android-backed one over its `picker`, `BiosImporter` and content resolver.
- **`DataFolderSwitch`:** one function, `DataFolderChange.switchFrom` with its store and mirror filled in:
  `switch(newTree, choice, pushOld, lossAccepted, beforeApply): Boolean`. *(My call: the change's own rules are
  tested in the mirror's tests already. What this class decides is around the call, so the fake only answers
  applied, not applied, or throws.)*
- **`FolderHooks`:** what `FolderSettings` asks the app, or tells it.
  - **Asked:**
    - `onFolders` and `onLibrary`, for the two places the code reads the screen. *(My call: two questions, not the
      `Screen` itself, so `Navigator` can take them over later without this class changing.)*
    - `busy`: a game active, the cold start under way, or the data layout not yet read from the core. Today's three
      checks before a data folder change, kept in `AppController` because each belongs to another piece;
    - `mirrorState`, for whether the old folder is reachable.
  - **Told:**
    - `biosImagesChanged()`: stale BIOS overrides are dropped, now or once the core starts;
    - `reloadShownPage()`: the BIOS page shows the images just copied;
    - `updateLibrary()`: the core's game folders, then a refresh;
    - `showFolders()`: the folder settings open on the data folder's choice, after a repick from the library;
    - `pullAll()` and `useMirror(old)`;
    - `settleCore()`: waits out what is queued on the core's dispatcher, before the change is applied;
    - `restart()`.
- **The status line's words** the moved code shows move with it.
- **Threads.** Everything in `FolderSettings` runs on the main thread, and hops off it only as today: the prefs on
  their dispatcher, the binder calls and the folder reads on IO. Each field's KDoc says so.
- **Writes within one function.** Where today's code sets `folders` before it launches the save (`setGamesFolders`),
  it still does, so a second change made meanwhile starts from the first.

## Tests to write (JVM, `FolderSettingsTest`)

These pin today's behaviour, with fakes for `FolderAccess`, `DataFolderSwitch` and `FolderHooks` writing to one
ordered log, a real `FolderPrefs` over an in-memory store, a real `StatusLine`, and `StandardTestDispatcher`s.

1. **The load** reads the saved folders and BIOS images. A save that fails says so, and returns false.
2. **A games folder added** is saved, then the grants are checked and the library updated. One that is there already
   changes nothing. A pick backed out of changes nothing.
3. **A games folder that overlaps the data folder** is refused with the reason, and released.
4. **A games folder removed** is saved before it is released, and the library is updated. Two changes made in a row
   both land.
5. **No games folder changes** while a data folder change is under way.
6. **Change BIOS:** a folder with images becomes the BIOS folder, is saved, and the old one is released. One with
   none is released, the old folder stays, and the line says to pick another. A second press while one runs is
   ignored.
7. **Rescan BIOS:** new images are remembered and `biosImagesChanged` is told. With none, the line says the copies
   already made still work, and nothing is released.
8. **A grant repicked with the same folder** only checks the grants again. A games grant then updates the library,
   and a data grant pulls.
9. **A grant repicked with another folder:** a games folder is replaced in its place; a BIOS folder is imported, and
   refused if it has no images; each is saved before the old one is released.
10. **A data grant repicked with another folder** offers it, and opens the folder settings only from the library.
11. **A data folder picked:** the one already in use says so. Another is inspected and offered, and an earlier
    candidate is released. Cancelling drops the candidate and releases it.
12. **A data folder change doesn't start** without a usable candidate, off the folders screen, while one is under
    way, or while the app is busy, which says to try again.
13. **A change that applies** saves the new folder, releases the old one, and restarts. Its progress lines show in
    order.
14. **A change the old folder missed** (`switch` returns false) stops, says why, and leaves the candidate.
    `dataReachable` is then false, so the next try accepts the loss.
15. **A change that throws, or whose save fails,** puts the old folder back, releases the new one, says why, restarts
    the old mirror and pulls. It doesn't restart the process.

## Constraints

- Kotlin, in `app/`, beside `AppController`, with the project's ktlint rules and KDoc style. Comments say why, and the
  ones in the moved code move with it.
- **Every check stays green:**
  - `assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`, with warnings as
    errors;
  - the quick tier on the test emulators.
- **On a device.** No instrumented test launches `MainActivity`.
  - On the AYN Thor's debug build, with your OK: the app starts with its folders and library as before, the folder
    settings show them, and Rescan BIOS works. None of these changes a saved folder.
  - The flows through the system picker (a games folder added and removed, a data folder offered and cancelled) I'd
    try on a test emulator, driving the picker over adb. If that isn't reliable, I'll say so and list them for you
    to try by hand.
  - **Not run on any device:** a data folder change through to the restart. It rewrites the app's data, so it stays
    with `FolderSettingsTest` and the mirror's own tests unless you want it tried on an emulator.
- **Commits:** `FolderSettings`, its interfaces and its tests first; then `AppController` moved onto it; then any
  follow-up fix; then the review's status section.

## Left to the builder

- File layout: `FolderSettings.kt`, plus a file for the interfaces if that reads better.
- Whether `FolderHooks` is one interface or two (what's asked, what's told).
- How the fakes are built, and the tests' names.

## What follows this, each a PR of its own

`AppController` should come out of this at about 1,650 lines: the hooks and the Android-backed `FolderAccess` stay in
it. The rest of 3.1:

1. **`Navigator`:** the `Screen` machine and its back stack. `SessionHooks.screen` and `FolderHooks`' screen
   questions then become the navigator.
2. **The callbacks adapter:** a class of its own for `NativeCallbacks`. `AppController` is then the wiring, the
   lifecycle and the cold start.
