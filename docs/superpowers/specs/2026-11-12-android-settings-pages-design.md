# One page model for the global and the game's settings pages

Batch C, part 3, of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, finding
3.4).

## Why

`app/SettingsPages.kt` (the global settings pages) and `app/GameSettingsPages.kt` (a game's own) are two copies of
one page model. Both keep:

- the values shown per page;
- the read counters, and the rule that a change made while a read is out reads again once it's saved;
- the core's choice lists (`names`), the memory card folder's cards (`sharedCards`), and the `vulkanFailed`,
  `fastmemAvailable` and `rewindUsage` the pages show;
- the same read (`PageRead`), and the same `change()`: save only what changed, apply, and for Emulation read the
  rewind estimate again.

The screens are dispatched twice too: `MainActivity` and `ui/GameSettingsHost.kt` each map Console, Emulation,
Graphics, Audio and Advanced to their screens, with the same extras.

The copies have drifted already. Finding 1.8's estimate-after-a-change had to land twice, and two drifts are left.
**The goal is one copy, so a fix lands once and the two can't drift again.** This is the user's choice, as with the
save lists (`2026-11-11-android-save-lists-design.md`).

## Goals

1. **The two copies agree first.** This is the user's choice. Each fix is its own test-first commit, made before the
   move:
   - **The game's rewind estimate is read inside the page's read,** as the global page's is. Today the game copy reads
     it after its stale check, in a second trip to the core. A change made in between can then show an estimate for
     the slot count before it, or one for a game whose page has since closed.
   - **`GameSettingsPages.open()` closes post-processing,** as `close()` does. Today, opening another game's settings
     while a Post-processing page shows can leave that page holding the old game.
2. **One page model,** `PageValues<P>` in `app/`, generic over the page enum (`CorePage`, `GamePage`). It owns:
   - the values shown;
   - the read counters and the re-read-after-change rule;
   - the shared extras (`names`, `sharedCards`, `vulkanFailed`, `fastmemAvailable`, `rewindUsage`);
   - the rewind estimate after a change.

   Each owner plugs in how a page is read, saved and applied. *(The review's name; a builder may pick another.)*
3. **One dispatcher** for the pages both have: Console, Emulation, Graphics, Audio and Advanced. It's a composable that
   draws a shared page with its values and the model's extras, used by `MainActivity`'s routes and by
   `GameSettingsHost`. This is the user's choice of scope.

## Non-goals

- **What only one owner has stays its own:**
  - the global BIOS page and the Interface page's update check;
  - the game's hub, its summary and its count after a change;
  - the Controllers page and its re-read on a type or multitap change;
  - the Copy/Clear questions;
  - the Memory cards and Achievements pages' own layouts;
  - the "is this still the same game" check on a read.
- **`ControllersPage`'s own copy of the names.** The user chose not to include it.
- **Post-processing pages:** `PostProcessingPage` is already shared.
- **No visible change** other than goal 1's. Pages, rows, words and focus stay the same.
- The native file split, and the codes and achievements models: other parts of batch C.

## Anti-goals

These would count as failure even if every page works:

- **A model that knows which owner it serves:** an `isGame` flag, or a branch on the page enum's type. What differs is
  passed in.
- **A stale read that lands.** A read that comes back after a newer read or a change is dropped, as now. For the game,
  so is a read for a game no longer shown.
- **A drift fix mixed into the move.** The fixes come first, each with the test that failed without it. The move then
  changes no behaviour, which the existing tests show.
- **A third copy:** the shared dispatcher must replace both mappings, not sit beside them.

## How it's shaped

These are my calls as the designer. A builder may adjust names, but not the shape.

- **`PageValues<P>`** is a plain class, main thread only, with its reads and saves on the core dispatcher. It takes:
  - the scope and the core dispatcher;
  - the core API, for the extras;
  - a mapping from `P` to the shared page it is, if any. Emulation, Graphics, MemoryCards and Advanced decide which
    extras a read fetches.
  - for each read, an owner function that reads the page's values on the core thread. The game's reads its file over
    the global one, and its Controllers page also reads its pads.
  - for each change, an owner function that saves and applies on the core thread. The game's also updates the
    controllers and reads the summary again, and returns that summary to the owner.

  The extras stay readable as Compose state, as now.
- **What the owners plug in, and when.** These are my calls, from the builder check:
  - **The "same game" check:** a read is refused through a check the owner gives, or a token taken as the read
    starts. The Controllers page's pads come back with its values, under the same stale check.
  - **Whether a change saved:** `change` says whether it saved anything. The game bumps its summary counter only then,
    as it does today after the `new == old` return.
  - **The Controllers page's forced read:** the read after a type or multitap change goes in as `change`'s "read
    again anyway". So one read starts, never two.
  - **Clearing the shown values:** opening or closing a game's settings clears the values shown without touching the
    read counters or the extras, as today.
- **Owners keep their public API.** `SettingsPages` and `GameSettingsPages` keep the members their callers use
  (`values`, `load`/`openPage`, `change`, `reloadShown`, `names`, `rewindUsage`, …). They delegate to their
  `PageValues`, so `AppController`, `MainActivity` and the screens change only where the dispatcher moves.
- **The shared page enum** is a small `enum class` (Console, Emulation, Graphics, Audio, MemoryCards, Advanced) that
  both `CorePage` and `GamePage` map onto. The dispatcher and the extras rule both read it.
- **Commits, in order:**
  1. The two drift fixes, one each, test-first.
  2. `PageValues`, test-first in its own JVM test.
  3. `SettingsPages` onto it.
  4. `GameSettingsPages` onto it.
  5. The shared dispatcher, with both screens' callers moved onto it.

## Tests

- **JVM:**
  - a `PageValuesTest` for what the model decides:
    - a stale read dropped;
    - a change during a read reads again;
    - which extras each shared page reads;
    - the rewind estimate after a change, kept only if the values are still those;
    - no change saved when nothing changed.
  - each drift fix's test in `GameSettingsPagesTest`;
  - for the move, `core/GameCallsTest.aChangeInTheMenuAppliesAtOnce` too: it drives `GameSettingsPages` on a real core;
  - `SettingsPagesTest` (12) and `GameSettingsPagesTest` (21) stay green, unchanged in what they expect.
- **Instrumented** (the `ui` package, `-Pgoose.orchestrator=false`, on the emulator): `ConsoleScreenTest`,
  `EmulationScreenTest`, `GraphicsScreenTest`, `AudioScreenTest`, `AdvancedScreenTest`, `GameSettingsScreenTest` and
  `SettingsScreenTest` pass. They draw each screen directly, so a new Compose test pins the dispatcher's wiring:
  which page and which extras each shared page gets, from each owner. *(My call, from the builder check: no existing
  test drives `GameSettingsHost` or `MainActivity`'s routes.)*
- **CI:** `assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs` green.

## Constraints

- Kotlin and Compose in `app/` and `ui/`, with the project's ktlint rules and KDoc style. Comments say why.
- `docs/design-system.md` is binding: the screens don't change.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.

## Left to the builder

- File layout and names: `PageValues.kt`, the shared page enum's name, and where the dispatcher sits. It takes the
  model each owner exposes, or a small interface over the extras.
- How each owner function is shaped (lambdas, or a small interface).
- How the fakes are built, and the tests' names.
