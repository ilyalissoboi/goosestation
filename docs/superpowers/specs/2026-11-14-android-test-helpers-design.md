# Test helpers: one copy of what the tests share

Batch I of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, section 4.5). A
pure move: no test checks anything it didn't, and no app code changes.

## Why

The tests carry the same helpers many times over, counted on master at 331ef25d9:
- **Instrumented core tests** (`androidTest/…/core`, and one each in `library` and `storage`):
  - `NativeLibrary.initialize(…)` with its 7 positional arguments is written out in 16 files;
  - `waitFor` is defined 6 times, with limits of 5, 10 and 20 s. The 5 s ones are the likeliest to run out on a
    loaded emulator;
  - `copyAsShell` is defined 4 times, in three forms: `cat <path>`, a glob through `sh -c`, and both behind a flag.
- **Compose tests** (`androidTest/…/ui`):
  - `press(key)` is defined in 39 files, identically but for one with a `times` argument;
  - `focusedHas(text)` is defined in 13, in five spellings of two meanings (the text as a substring, or whole);
  - "walk Down until this has the focus" loops are written by hand, with limits from 12 to 120.
- **JVM tests** (`test/`):
  - an in-memory `KeyValueStore` is written 10 times (`MemoryStore` ×3, `MapStore`, and six anonymous objects);
  - `TestScope.until` twice, identically;
  - a `game(…)` builder for `GameEntry` in four files, and inline in more.

## Decided

### Instrumented core tests: `core/TestCore.kt`

- **`initializeCore(dataRoot, biosDir, offline, token = null, deviceName = "Goose Test Device")`** extracts the
  resources, calls `NativeLibrary.initialize` with the tests' install ID, and asserts it started. The 16 sites call it.
  Each keeps what it does around the call: writing settings.ini first, setting callbacks, connecting a pad.
  - *(My call:)* `biosDir` and `offline` stay arguments, since the sites put them in different places
    (`File(root, "bios")` or a field; `File(root, "offline")` or beside the data root), and moving them would change
    what a test leaves on disk.
- **`waitFor(what, seconds = 20, condition)`**, top-level, replaces the five private copies. *(My call:)* every caller
  gets 20 s. A wait that passes returns as soon as its condition holds, so only a failing test gets slower.
  `SyncTestCore.waitFor` stays, since its failure adds the sync state, and calls the shared one.
- **`copyFromShell(path, destination)`**, one form, the glob one: it reads a plain path too. The four copies and
  `copyGlobAsShell` go.

### Compose tests: `ui/ControllerKeys.kt`

- **An interface, `ControllerKeys`,** that a test class implements, with the rule as its one member and the helpers
  as its defaults:
  - `press(key, times = 1)`;
  - `focusedHas(text)` (the text as a substring) and `focusedIs(text)` (the whole text);
  - `walkTo(text, key = Key.DirectionDown, limit = 60)`: presses until `focusedHas(text)`, and fails naming the text
    and the key if it never does.
- *(My call: an interface, not extensions on the rule as the review suggested.)* The tests' call sites stay
  `press(Key.ButtonA)` as they are, some thousands of lines, rather than each becoming `rule.press(…)`. Each file
  loses its private copy and gains `: ControllerKeys` and `override` on its rule.
- **Walk loops move to `walkTo` only where they're the plain shape** (check, press, repeat, fail). A loop that does
  something else in its body, or doesn't fail at its end, stays as written. *(My call:)* the plain ones all get the
  limit of 60: it only bounds a failure.
- A file whose `press` or `focusedHas` differs in meaning (several keys at once; `vararg` texts) keeps its own.

### JVM tests

- **`MemoryStore`** (`test/…/storage/MemoryStore.kt`): one in-memory `KeyValueStore`, with its `values` map readable,
  as the tests that look inside need. The 10 copies go, the anonymous ones included, where they're only that. One
  that does more (a failing or sealing store) stays.
- **`TestScope.until`** (`test/…/app/TestScopes.kt`): the one copy.
- **`testGame(title, …)`** (`test/…/core/TestGames.kt`): one `GameEntry` builder with named, defaulted arguments. The
  four `game(…)` builders call it or go; where a file's defaults differ (its path pattern), the file keeps a one-line
  `game` that passes them.
- **`FakeMemcardEditorApi`'s `sameBytes`:** already the app's, since #86. Nothing to do.
- *(My call: no `testFixtures` source set.)* Nothing here is needed by both `test` and `androidTest`, and it would add
  build configuration for no shared code.

## Tests

- **Counts unchanged.** The JVM tests and the quick tier report the same number of tests before and after, all
  passing. No test's assertions change.
- **CI's Gradle line** passes, with warnings as errors: an unused private helper left behind fails the build.
- **The quick tier** on both test emulators, and the classes that only the full tier runs (`@LargeTest`) where this
  touches them, run once by class.

## Constraints

- A pure move: no app code, and no change to what any test checks.
- The tiers run only on the test emulators on the Mac, never the Thor.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.

## Not doing

- A `testFixtures` source set.
- Rewriting walk loops that aren't the plain shape.
- The sample-data files in `ui/` (`SampleNames`, `SlotPanels`, …): already shared.
- Fixed sleeps anywhere.

## Left to the builder

- **The order of commits.** A sensible default: the JVM fakes, then the core helpers, then `ControllerKeys`, each its
  own commit, so a failure points at one part.
- **Names,** where a better one turns up.
- **Which walk loops are the plain shape:** listed in the plan.
