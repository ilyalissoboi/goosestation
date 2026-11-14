# Small architecture items: the status line's owners, the activity's pause calls, the library's focus

Batch J of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, section 3.5): the
three items there that are small and stand alone, and the review's own status section, which is out of date.

## Scope

**In this batch:**
1. The status line with many owners, cleared by comparing its text (3.5, row 1).
2. `MainActivity` writing `AppController.resumed` itself (3.5, row 6).
3. `LibraryScreen.kt`'s focus class and `MessageBar` (3.5, row 5).
4. The review document's status section.

**Not in this batch,** each being a design of its own:
- the rest of `AppController`'s split into `GameSession`, `FolderSettings`, `Navigator` and a callbacks adapter (3.1);
- the sealed `SidePanel` type in `InGameMenu` (3.2);
- the controllers screens' 16–28 parameters (3.5, row 3).

Already done, and only recorded in item 4: `beginGame` (3.5, row 2) and the native split (row 4, #92).

## Why

- **The status line.** `AppController.message` is one field written from about 15 places. Two of them decide what to
  do by comparing its text: `copyingShown() = message == COPYING` (`:456`) and
  `if (message == "Looking at the folder…") message = null` (`:1282`); `DataSync.keepsLine(message, COPYING)` does the
  same one call away. Others clear whatever is there: the library scan's end (`:1680`) clears any line that isn't an
  error, its own or not. So a line can vanish, or stay, depending on what finished first.
- **Pausing.** `MainActivity.onResume` and `onPause` set `AppController.resumed` and hold the pause policy
  themselves, without the `isCurrent(activity)` check that `onStart` and `onStop` go through. An activity being
  replaced can pause after its replacement resumed, and leave `resumed` false under a game on screen.
- **The library's focus.** `GameFocus` registers a requester for each game as it's composed
  (`requesters[path] = requester`, in composition) and never removes one, so it holds a requester for every game
  ever scrolled past, and relies on `runCatching` to swallow requests to those no longer on screen. It sits in
  `LibraryScreen.kt` (703 lines) with `MessageBar`, which `FoldersScreen` uses too.

## Decided

### The status line: `app/StatusLine.kt`

- **A small class,** JVM-tested, that `AppController` holds in place of `message` and `messageIsError`. It keeps the
  text, whether it's an error, and **who put it there**: a `Status` kind.
  - `Starting`, `StartingCore`, `Copying` (the plain line), `CopyingProgress` (with its counts), `LookingAtFolder`,
    `Scanning`, `Stopping`: the lines for work in progress, each with one owner.
  - `Note`: something to read (a refusal, "That's your data folder already.").
  - `Error`.
- **`show(kind, text)`, `error(text)`, `clear(kind)` and `shows(kind)`.** `clear(kind)` clears the line only if it's
  that kind's, so an owner can only clear its own line. `shows(kind)` replaces every comparison of text.
- **An error is never replaced by a progress line,** as now (`if (!messageIsError) message = …`): that rule moves
  into `show`, for the progress kinds. A `Note` or another `Error` replaces it, as `showMessage` and `showError` do
  today.
- **The texts stay where they're shown,** in constants beside `COPYING`.
- **`keepsLine`** takes whether the plain `Copying` line shows, not the text.
- **What changes on screen, on purpose:** a scan ending, or the folder check ending, no longer clears another
  owner's line. Everything else is the same line at the same moment.
  - *(My call:)* where today's code clears unconditionally and that's the point (leaving the screen, a game starting:
    `:1305`, `:1365`, `:1523`), it stays a plain `clear()`.
- `AppController.message` and `messageIsError` stay as read-only properties for the UI, reading the status line.

### Pausing: `AppController.onResume(activity)` and `onPause(activity)`

- The two bodies move from `MainActivity` into `AppController`, behind the same `isCurrent(activity)` check as
  `onStart` and `onStop`. `MainActivity` calls them, as it does those.
- `resumed` becomes `private set`.
- *(My call:)* the "in a game" test they make moves with them, as `screen == Screen.Game`, which is what
  `MainActivity`'s `inGame` reads.
- **What changes, on purpose:** a pause or resume from an activity that isn't the current one does nothing.

### The library's focus: `ui/LibraryFocus.kt`

- `GameFocus` moves to its own file, unchanged in what it does for the screen.
- **It registers a game's requester in a `DisposableEffect`, and removes it on dispose** (if it's still that game's),
  so it holds requesters only for the games composed now. `refocus` and `request` find nothing for a game scrolled
  away, as their comments already say, rather than a requester that throws.
  - *(My call:)* the `runCatching` stays around the two requests: a requester can still be asked in the frame its
    item leaves.
- **`MessageBar` moves to `ui/components/`,** beside `Notice`, which it wraps.
- `GameChoicePanel`, `libraryHints` and the grid's helpers stay in `LibraryScreen.kt`: they're the screen's own.

### The review document

- Its "Status" section is brought up to today: which PR closed each finding in sections 1 to 6, and what's still
  open (the three items under "Not in this batch", and 1.9's hub read, left on purpose).

## Tests

- **JVM, new `StatusLineTest`:** an owner clears only its own line; an error isn't replaced by a progress line, and
  is by a note or an error; `shows` for each kind; `clear()` clears anything.
- **JVM, existing:** `DataSyncTest` and `CatchUp`'s tests pass with `keepsLine`'s new argument, with the same cases.
- **Instrumented, new, in `LibraryScreenTest`:** after scrolling a game away, asking it for the focus does nothing and
  doesn't throw; scrolled back, it takes the focus when asked.
- **The pause calls** have no JVM seam (`AppController` isn't built on the JVM): checked by the existing
  instrumented tests that pause and resume a game, and by hand on an emulator: Home and back during a game, and with
  the menu open.
- **CI's Gradle line, and the quick tier** on both test emulators.

## Constraints

- No change to what a screen shows, but for the two marked "on purpose".
- The tiers run only on the test emulators on the Mac, never the Thor.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.

## Left to the builder

- **The kinds' names,** and whether `StartingCore` and `Starting` are one.
- **Each of the 15 sites' kind,** listed in the plan before the change.
- **The order of commits.** A sensible default: the status line, then the pause calls, then the library's focus,
  then the review document.
