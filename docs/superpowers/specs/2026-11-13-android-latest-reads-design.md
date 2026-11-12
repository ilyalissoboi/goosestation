# Latest reads and refusals: design

Batch E, part 2, of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`): the last
two rows of section 4.4. Part 1, the game-aware rows and `Pads`, merged in PR #97.

## Why

**Nine page models write the same check by hand:** has a newer read of the same thing started since this one did? Each
keeps an `Int`, bumps it when a read starts, and drops the answer of any read that isn't the latest. The sites are in
`android/app/src/main/java/io/github/ilyalissoboi/goosestation/app/`:

| Site | Also |
|---|---|
| `SettingsPages.biosRead` | invalidated by a change; `biosReading` tracks a read out, re-read after the change |
| `PageValues.pageRead`, one per page | the same, with `pageReading`, per page |
| `ControllersPage.portRead` | the same, with `portReading`; `setType` invalidates without asking about one out |
| `GameSettingsPages.summaryRead` | invalidated when a save happens |
| `ControllersPage.bindingsRead` | invalidated when no controller is selected |
| `ControllersPage.labelsRead` | none |
| `ControllersPage.hubRead` | none |
| `ControllersPage.macroRead` | none |
| `PostProcessingPage.reads` | invalidated on close; also taken by each edit's write-then-read |

The steps that matter are easy to forget at one site. Finding 1.9(a) was one: a change that didn't drop a read already
out, so the old values came back. That one is fixed, but the next site will be written by hand again.

**`PostProcessingPage` writes one pattern four times:** rename, save, apply and delete a favourite. Each sets
`var refusal: String? = null`, lets the core's change fill it in, and then, once the page is read again, branches on
it. On a refusal it shows the reason, otherwise it does what follows a success.

## Goal

- One small `Latest` class, used by all nine sites.
- One `editOrRefuse` in `PostProcessingPage`, used by the four favourite edits.
- No change in behaviour.

## Decided

- **The per-read counters only** (your answer). These stay hand-written:
  - the session counters, bumped only on open and close and shared by many reads: the three panel models'
    `generation`, `SavesPage.pageId`, `InGameMenu`'s `gameId` and `panelId`, and `PostProcessingPage.session`;
  - `captureId`, whose value is mirrored to the core thread;
  - `leaderboardRequest`, which goes to native code and comes back;
  - `Updates.attempt`, which also names a file.
- **A pure move.** Every site drops the same answers, in the same cases, and re-reads in the same cases as today.
  - Where a site checks more than the counter (the view still shown, the controller still selected,
    `reader.current()`), it keeps that check beside `Latest`.
  - Where a mismatch skips one step and the rest still runs, that stays too: `PostProcessingPage.edit` still calls
    `after` when its read isn't the latest.
- **`Latest`** lives in `app/Latest.kt`. Main thread only, like every site. *(My call: the names.)*
  - `next(): Int`: a read starts. Returns its id, and marks a read as out.
  - `isLatest(id: Int): Boolean`: whether the read `id` is still the latest. It changes nothing.
  - `done()`: the latest read has landed, so none is out. A site calls it where it clears its flag today, so a site
    that drops a latest read for another reason, as `PageValues` does when `!reader.current()`, still leaves one out.
  - `invalidate(): Boolean`: a change makes any read out stale. Bumps the id and returns whether a read was out, so
    the site re-reads after its change, as `biosReading`, `pageReading` and `portReading` do today. A site that never
    asked (`setType`) ignores the result.
  - `pending: Boolean`: read-only, whether a read is out.
- **`PageValues`** keeps one `Latest` per page, in a map, as it keeps one counter and one flag per page today.
- **`editOrRefuse(change: () -> String?, onRefused: (String) -> Unit, onDone: () -> Unit)`**, private to
  `PostProcessingPage`. It runs `change` through `edit`, on the core thread, keeping its refusal. Then, from `edit`'s
  `after`, it calls `onRefused` with the refusal or `onDone`. Each of the four sites passes today's two branches.

## Tests

- **A JVM `LatestTest`:**
  - `next` gives increasing ids, and only the last is the latest;
  - `invalidate` makes the last read stale, and says whether one was out;
  - `done` clears what's out;
  - a read that started before an `invalidate` isn't the latest after it, even with no new read;
  - `pending` follows `next`, `done` and `invalidate` as above. `invalidate` leaves `pending` set: a read is still
    out, it just won't land.
- **The existing tests pass unchanged:**
  - JVM: the page models' tests (`SettingsPagesTest`, `PageValuesTest`, `ControllersPageTest`,
    `GameSettingsPagesTest`, `PostProcessingPageTest`, and the like). They already cover the re-read after a change
    (from 1.9's fix), a stale read dropped, and each favourite edit's refusal and success.
  - On the device: the quick tier on both test emulators.
- **CI:** the CI tasks.

## Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- ktlint, and the repo's KDoc style.

## Not doing

- The session counters, `captureId`, `leaderboardRequest` and `Updates.attempt`.
- Making any check stricter. A site that doesn't check something today doesn't start to.

## Left to the builder

- Whether a site that never tracked a read out (`hubRead`, `macroRead`, `labelsRead`, `summaryRead`, `bindingsRead`,
  `PostProcessingPage.reads`) calls `done()`. It changes nothing either way, since none of them reads `pending`.
- The order of commits. A sensible default: `Latest` with `LatestTest`, then the sites file by file, then
  `editOrRefuse`.
