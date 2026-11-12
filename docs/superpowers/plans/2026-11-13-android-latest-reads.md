# Latest Reads and Refusals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the nine hand-written per-read counters with one `Latest` class, and `PostProcessingPage`'s four
refusal copies with `editOrRefuse`, with no change in behaviour.

**Architecture:**
- **`app/Latest.kt`** holds a main-thread class: an id, and whether a read is out.
- **Each site** keeps its own extra checks (the view, the selected controller, `reader.current()`) beside it.
  `PageValues` keeps a `Latest` per page.
- **`editOrRefuse`** wraps `PostProcessingPage.edit`.

**Tech Stack:** Kotlin, kotlinx-coroutines-test, JUnit 4, ktlint.

**Spec:** `docs/superpowers/specs/2026-11-13-android-latest-reads-design.md`

## Global Constraints

- **A pure move.** Every site drops the same answers, in the same cases, and re-reads in the same cases as today.
- **Out of scope:** the session counters, `captureId`, `leaderboardRequest` and `Updates.attempt`.
- **No stricter checks.** A site that doesn't check something today doesn't start to.
- **Paths:** `android/app/src/main/java/io/github/ilyalissoboi/goosestation/app/`, with tests in
  `android/app/src/test/java/io/github/ilyalissoboi/goosestation/app/`.
- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- **Checks:**
  - every task: `./gradlew testDebugUnitTest ktlintCheck`, reading each named class's results;
  - at the end: the CI tasks, and the quick tier from a detached worktree with the emusync AAR copied in.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A `done()` moved earlier or later than its site cleared its flag today:** before an extra check (the view, the
   selected controller, `reader.current()`), or on a path that didn't clear it.
2. **An `invalidate()` whose result now drives a re-read where the site didn't re-read before.** `setType`
   invalidates `portRead` and ignores whether a read was out.
3. **`next()` moved before an early return,** or the other way round. `PageValues` bumps only after
   `reader(page) ?: return`, and `readLabels` bumps before its own early return.
4. **`PostProcessingPage.edit` still calls `after` when its read isn't the latest.** Its `after` is gated only on
   `session`.
5. **`editOrRefuse`'s branches** keep today's state changes exactly, including the focus each success sets and which
   field a refusal goes to (`naming.error` or `favouriteError`).

The existing tests cover 1–3 for `biosRead`, `pageRead` and `portRead`, from 1.9's fix, and cover 5 per favourite edit.
The final reviewer checks 4 and every site's `done()` placement against the base.

---

### Task 1: `Latest`, test-first

**Files:** create `app/Latest.kt`; test: create `test/.../app/LatestTest.kt`.

**Interfaces (produced):**

```kotlin
class Latest {                 // main thread only
    val pending: Boolean       // a read is out
    fun next(): Int            // a read starts: its id; pending = true
    fun isLatest(id: Int): Boolean
    fun done()                 // the latest read landed: pending = false
    fun invalidate(): Boolean  // a change: bumps the id; returns pending (left set)
}
```

- [ ] **Step 1: Write `LatestTest`:**
  - `onlyTheLastReadIsTheLatest`: two `next()` calls give ids `a < b`, with `isLatest(b)` true and `isLatest(a)` false;
  - `aChangeMakesTheReadOutStale`: after `val a = next()`, `invalidate()` returns true, `isLatest(a)` is false and
    `pending` is still true;
  - `aChangeWithNoReadOutSaysSo`: on a fresh `Latest`, `invalidate()` returns false; after `next()` and `done()` it
    returns false too;
  - `doneClearsTheReadOut`: `next()` makes `pending` true, and `done()` makes it false.
- [ ] **Step 2: Run** `--tests '*LatestTest'`. Expected: it fails to compile.
- [ ] **Step 3: Write `Latest`,** with a KDoc giving the pattern in one paragraph: next, check, done, and invalidate
  then re-read.
- [ ] **Step 4: Run it again.** Expected: 4/4.
- [ ] **Step 5: Commit:** "Android: Latest, for the page models' is-this-read-still-the-latest checks".

### Task 2: The nine sites onto `Latest`

**Files:** `SettingsPages.kt`, `PageValues.kt`, `ControllersPage.kt`, `GameSettingsPages.kt` and
`PostProcessingPage.kt`.

**How each site maps:**

| Site | `next()` | Check | `done()` | `invalidate()` |
|---|---|---|---|---|
| `biosRead` + `biosReading` | `readBios` | `isLatest` | where `biosReading = false` | `setBiosOverride`, `setFastBoot`; re-read if it returned true |
| `pageRead` + `pageReading` (`mutableMapOf<P, Latest>()`, `getOrPut`) | after `reader(page) ?: return` | `isLatest && reader.current()` | after both checks pass | `change()`; re-read if it returned true or `readAgain` |
| `portRead` + `portReading` | `readPort` | `isLatest && state.view == Port(pad)` | after both pass | `changePort` (re-read if true and a port shows); `setType` (result ignored) |
| `summaryRead` | the summary read | `isLatest` (assign only then) | — | `if (saved)` |
| `bindingsRead` | `readBindings` | `isLatest && state.selected == descriptor` | — | both "nothing selected" branches |
| `labelsRead` | `readLabels`, before its early return | `isLatest` | — | — |
| `hubRead`, `macroRead` | their reads | `isLatest` (and the view, for macros) | — | — |
| `PostProcessingPage.reads` | `read()` and `edit` | `isLatest`. `edit` still runs `after` on a mismatch | — | `close()` |

- [ ] **Step 1: Run** `--tests '*SettingsPagesTest' '*PageValuesTest' '*ControllersPageTest' '*GameSettingsPagesTest' '*PostProcessing*Test'`
  on the base, and record the counts in the ledger.
- [ ] **Step 2: Move each site** as the table says. Remove its `Int` and its flag.
- [ ] **Step 3: Run** the same tests. Expected: the same counts, all passing, and no test changed.
- [ ] **Step 4: Commit:** "Android: the page models' latest-read checks through Latest".

### Task 3: `editOrRefuse`

**Files:** `PostProcessingPage.kt`.

**Interfaces (produced, private):**
`editOrRefuse(change: () -> String?, onRefused: (String) -> Unit, onDone: () -> Unit)`. It calls
`edit(after = { refusal?.let(onRefused) ?: onDone() }) { refusal = change() }`.

- [ ] **Step 1: Move** `submitName`'s rename, `save`, `applyNow` and the delete answer onto it, each passing today's
  refused and done branches. Drop the `.orEmpty()` calls, since the refusal is no longer nullable there.
  `moveFavourite` keeps plain `edit`.
- [ ] **Step 2: Run** `--tests '*PostProcessing*Test'`. Expected: the same counts as Task 2's Step 1, all passing.
- [ ] **Step 3: Commit:** "Android: PostProcessingPage's favourite edits through editOrRefuse".

### Task 4: Whole-branch checks

- [ ] **Step 1: Run** the CI tasks, and the quick tier from `git worktree add --detach /tmp/goose-latest HEAD`, with
  the emusync AAR copied in. Expected: all pass, none skipped beyond the BIOS tests.
