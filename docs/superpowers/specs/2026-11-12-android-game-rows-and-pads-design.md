# Game-aware rows and pads: design

Batch E, part 1, of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`): two rows of
section 4.4, plus the row-naming item of section 5. Part 2, the "latest read" helper and `PostProcessingPage`'s refusal
helper, is a pure refactor in a separate design.

## Why

**The game-aware rows** (`android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/PageRows.kt`) are seven
near-copies:
- `Switch`;
- `Number`, once for an int setting and once for a float;
- `Names` and `Choices`;
- `GroupChoices` and `GroupNumber`.

Each one decides for itself what a game's page adds: the "Use global" choice, the "Set for this game" mark, the
"Applies when the game next starts" note, and `enabled`. The copies have drifted:
- `GroupChoices` and `GroupNumber` pass `note` as it is, never through `gameNote`, so a grouped row of a next-start
  setting would lose the note;
- `GroupChoices` has no `enabled`;
- `Names` calls `offered()` twice.

No grouped row covers a next-start setting today, and none passes `enabled`, so this is drift waiting to bite, not a
bug anyone sees now. The names (`Switch`, `Number`, `Names`, `Choices`) also read like `kotlin.Number` and clash with
Material's `Switch` (section 5).

**The multitap pads** are laid out in several places:
- the desktop order, twice: `ControllersPage.PORT_ORDER`, and `GameControllers.pads`' `"BothPorts"` list;
- a pad's label, twice and correctly: `GameControllers.label(pad, multitap)` and `ControllersPage.portLabel(pad)`;
- the same label, four times wrong: `"Port ${pad + 1}"` in `MainActivity` (×2) and in `ControllersScreen`. Under
  multitap, that calls pad 2 "Port 3", where the core and the rest of the app say "Port 1B".

## Goal

- One implementation behind the game-aware rows, so every row gets the game's page treatment the same way.
- The rows renamed so they can't be mistaken for Kotlin's or Material's names.
- One `Pads` object for the order and the labels, used everywhere.

## Decided

- **Two PRs** for batch E. This one may change what's shown, where it fixes drift. The next one is a pure move.
- **Merge and rename the rows, in one go** (your answer).
  - The new names are `GameSwitch`, `GameNumber` (both overloads), `GameNames`, `GameChoices`, `GameGroupChoices`
    and `GameGroupNumber` *(my call: the names)*.
  - Every call site moves to the new name.
  - The shared implementation is private to `PageRows.kt`.
- **What the rows do is unchanged for every single-setting row, on both pages.** The merge may change only the two
  grouped rows, in these two ways:
  - their note goes through `gameNote` over all their settings: the next-start note shows when any of them is a
    next-start setting and a game is running;
  - both take `enabled` and pass it on, as the single-setting rows do.
- **`PostProcessingScreen`'s "Use global / On / Off" switch is left as it is.** You chose not to fold that screen in.
- **`Pads`** lives in `settings/PortSettings.kt`, beside `PortSettings.MULTITAP`:
  - `ORDER`: `0, 2, 3, 4, 1, 5, 6, 7`;
  - `of(multitap)`: today's `GameControllers.pads`;
  - `label(pad, multitap)`: today's `GameControllers.label`;
  - `label(pad)`: today's `portLabel`. Pads 0 and 1 are "Port 1" and "Port 2", 2 to 4 are "Port 1B" to "Port 1D", and
    5 to 7 are "Port 2B" to "Port 2D". It's for where the multitap mode isn't known.

  `ControllersPage`, `GameSettingsPages`, `MainActivity` and `ControllersScreen` all use it. *(My call: the fallbacks
  in `MainActivity` and `ControllersScreen` use `label(pad)` even where the mode is known, as `ControllersPage` does
  for the ports it no longer offers. A not-offered pad 2 is then "Port 1B", never "Port 1".)* The old helpers and the
  `"Port ${pad + 1}"` fallbacks go.
- **The one visible change:** a port the core doesn't offer (yet) is labelled as `Pads.label(pad)` says, so "Port 1B"
  where it said "Port 3". Ports 1 and 2 still say "Port 1" and "Port 2".

## Tests

- **JVM, a new `PadsTest`:**
  - `ORDER`;
  - `of(...)` for each multitap mode;
  - `label(pad, mode)` for every pad each mode offers: a tapped port's pads are "Port 1A" to "Port 1D", an untapped
    one's pad is "Port 1". *(My call: a pad its mode doesn't offer isn't pinned. Today it gets a label that clashes with
    pads 0 and 1, and nothing asks for one.)*
  - `label(pad)` for pads 0 to 7.

  The existing tests of `GameControllers.label`, `pads` and `portLabel`, if any, move onto `Pads`.
- **Compose (instrumented, `ui` package), new tests for the rows** in the existing `GameRowsTest` *(my call)*, which
  already covers the shared rows on a game's page:
  - a `GameGroupChoices` and a `GameGroupNumber` over a next-start setting, on a running game's page, show the note;
  - each, with `enabled = false`, can't be changed;
  - a `GameChoices` on the global page and on a game's page shows the same choices and note as before. This pins the
    merge for single-setting rows.
- **The existing settings UI tests pass unchanged,** apart from the renames. That's every `ui` test class touching
  these rows (`GameSettingsTest`, `SettingsScreensTest` and the like).
- **The controllers UI tests** that cover a not-offered port expect "Port 1B". Where none covers it, one is added.
- **The quick tier** on both test emulators, and the CI tasks.

## Constraints

- `docs/design-system.md` is binding: nothing about how a row looks changes.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- ktlint, and the repo's KDoc style: a KDoc sits directly above its declaration.

## Not doing

- `PostProcessingScreen`'s switch.
- The "latest read" helper and the refusal helper (part 2).
- Changing which pads a multitap mode offers, or the labels the core gives.

## Left to the builder

- The shared implementation's shape: one choice-row function and one number-row function over a list of settings,
  with `value` and `write`, is the obvious one.
- How the new `GameRowsTest` cases set up a running game's page (`LocalGameContext`). `NumberRow` already takes
  `enabled`, so the grouped number row passes it on.
- The order of commits. A sensible default: `Pads` with `PadsTest`, then the rows merge and rename.
