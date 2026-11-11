# One save list for the Saves page and the slot panel

Batch C, part 2, of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, findings
3.2 and 4.1).

## Why

The library's Saves page (`app/SavesPage.kt`, `ui/SavesScreen.kt`) and the in-game slot panel (the slot part of
`app/InGameMenu.kt`, and `ui/SlotPanel.kt`) list the same saves, written twice:

- the game's own saves, the ones filed under its other discs ("Other saves"), and the shared slots;
- each saved row's picture, read after the lists, the open tab's first;
- a shared slot's game, read before acting on it;
- the delete: ask, delete by the state the question named, read the lists again, focus the row after;
- the same messages ("Couldn't delete that save: …", "Couldn't read that save.");
- on screen, the same rows, empty lines, "Other saves" heading and focus rules.

Fixes have already had to land twice, and the copies have drifted. **The goal is one copy, so a fix lands once and the
two screens can't drift again.** The user's choice.

The slot panel's own state has the second problem the review names (3.2): whether it's busy, and which question it
asks, are five independent fields (`busy`, `removing`, `replacing`, `deleting`, `switching`), though only one can hold
at a time, so every action re-derives "idle" by hand, unevenly.

## Goals

1. **The two copies agree before they're merged.** The user's choice: the Saves page gets the slot panel's behaviour
   where they've drifted, and a fix neither has goes on both. Each is its own test-first commit, on each screen it
   changes, made before the move:
   - **A delete under way:** the page is busy, shows "Deleting…" (as the slot panel does, under the error line), and no
     pick, delete or tab switch starts until it's done. Today the deleted row stays pickable until the re-read lands.
   - **Busy until the lists are read again, on both.** The user's choice. The slot panel today stops being busy once
     the save is gone, before the re-read, so the deleted row is pickable there for a moment too. On both screens,
     "Deleting…" now lasts until the re-read's lists are in (or the delete fails), and the deleted row is never
     pickable.
   - **The row check before asking:** a delete is asked only for a row the lists still have, saved.
   - **One picture read at a time, on both:** a re-read after a delete stops the picture reads of the read before it.
     Today both screens reuse their counter for the re-read, so both loops run and pictures are read twice.
   - **A tab switch clears the row to focus, on the slot panel too,** as the Saves page does now. *(My call, as the one
     that reads right: a focus target from the other tab means nothing.)*
   - Back on the Saves page doesn't wait out a delete: leaving loses nothing, as the delete still completes. *(My
     call: the slot panel waits because it shows how a save or load went, which the page has no need to.)*
2. **One `SaveListModel`** in `app/`, used by both owners, holding what they share: the three lists, the picture reads,
   the shared slot's game read, the delete (with its busy state and question), and the row to focus. It has its own
   counter for dropping stale reads, so nothing else in the in-game menu (opening codes, discs, achievements) drops or
   is dropped by its reads.
3. **One `SaveRows` composable** in `ui/`, drawing the rows, "Other saves", the empty lines and the focus rules for both
   screens.
4. **`SlotQuestion`**: the slot panel's own question is one sealed value, `Replace(slot)` or `Switch(entry)`, or none.
   What it has under way is one value too, Saving or Loading, or none. The delete, its question and "Deleting…" are
   the model's (goal 2). So the panel is idle when it and the model both are: one property, not five checks, and B
   still can't close it mid-delete. *(My calls: the review names `SlotQuestion`; `busy` with `removing` is the same
   problem; and one owner for the delete, so no two copies of its state can disagree.)*
5. **One copy each of `UNREADABLE` and `HARDCORE_LOAD`.** `HARDCORE_LOAD` stays where `GameStarts` has it.

## Non-goals

- **Codes and achievements models**, and the in-game menu's other panels' generation counter. The user's choice: a
  follow-up. They keep sharing `panelId` among themselves; only the save list leaves it.
- **What the slot panel alone does stays in `InGameMenu`:** save, load, replace, the switch to another game from a
  shared slot, undo load, the hotkeys, the last-used slot's focus, and Save mode's empty slots.
- **No other visible change** than goal 1's (which includes "Deleting…" lasting until the re-read on the slot panel). Same layout, words, keys and focus order on both screens.
- The settings pages and the native file split, batch C's other parts.

## Anti-goals

These would count as failure even if both screens work:

- **A model that knows which screen it's on** (an `inGame` flag, or branches on its owner). What differs between the
  owners is passed in: the mode (Save lists empty slots; only Load lists "Other saves"), the game, and what a pick does.
- **A stale read that lands.** A read for a page or panel that has closed, or been re-read, must still be dropped, as
  now. Most of these screens' past bugs were stale reads and lost focus.
- **A drift fix mixed into the move.** The fixes come first, each with the test that failed without it; the move then
  changes no behaviour, which the existing tests show.
- **The focus going somewhere new.** After a delete, a cancelled question, a tab switch or a re-read, the focus lands
  where it does today.

## How it's shaped

My calls as the designer. A builder may adjust names, not the shape.

- **`SaveListModel`** is a plain class, main thread only, as `SavesPage` is, with its reads on the `game` dispatcher
  through `SaveReader` and `GameCoreApi`. It exposes its state as one immutable value (Compose reads it through the
  owner, as now), and takes:
  - the game (its key, path and discs) and the mode, when it opens;
  - an `onChange` or a `mutableStateOf` the owner reads; the builder picks.
- **The error line** is one value, the model's, on both screens. The owners put their own failures in it (hardcore,
  a refused start, a failed save or load, a game not in the library), and whatever clears it today still does: asking
  to delete, a tab switch, a pick. *(My call.)*
- **Owners:**
  - `SavesPage` becomes a thin owner: it opens the model in Load, and keeps the hardcore check and what a pick does
    (start the game, or the shared slot's game, through `play`).
  - `InGameMenu`'s slot panel keeps `SlotPanel` as its state, holding the model's state plus what's its own (mode,
    `focus` and `sharedFocus` from the last-used slot, the `SlotQuestion`, what's under way, `error`).
- **The picture loop** is a `Job` the model cancels before the next read, rather than a counter check alone.
- **`SaveRows`** takes the lists, the tab, the row to focus, whether rows are pickable, and the pick and delete
  callbacks. It draws the rows and "Other saves". The empty lines are a small shared composable each owner places
  where it shows them today (above the slot panel's list, inside the page's scroll). Its owners keep their frames (the page's `Column` with title, tabs and key hints; the side panel's
  `SidePanel`), their busy and error lines, and their own questions.
- **Commits, in order:**
  1. Goal 1's fixes, one commit each, each test-first, on each screen it changes.
  2. `SlotQuestion` and the work value in `InGameMenu`, with the existing tests kept.
  3. `SaveListModel`, test-first in `SaveListModelTest`, then both owners moved onto it.
  4. `SaveRows`, both screens moved onto it.
  5. The constants.

## Tests

- **JVM:**
  - a `SaveListModelTest` for what the model decides: the lists per mode, the picture order (open tab first), a stale
    read dropped, the delete (busy, re-read, focus after; the error and its focus), the row check, the shared slot's
    game read, and the picture loop stopped by a re-read;
  - each goal 1 fix's test in `SavesPageTest`;
  - `InGameMenuTest` (134 tests) and `SavesPageTest` stay green, changed only where the state's shape changes (the
    question and the work value), never in what they expect to happen.
- **Instrumented** (the `ui` package, `-Pgoose.orchestrator=false`, on the emulator): `SavesScreenTest` and
  `GameMenuTest` pass, plus a `SavesScreenTest` case for "Deleting…" on the page.
- **CI:** `assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs` green.
- **On the Thor, by the user:** delete a save from the Saves page and from the slot panel, and check where the focus
  lands. Both lists can run past one screen there.

## Constraints

- Kotlin and Compose in `app/` and `ui/`, with the project's ktlint rules and KDoc style. Comments say why.
- `docs/design-system.md` is binding: `SaveRows` uses the components the rows use now.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.

## Left to the builder

- File layout (`SaveListModel.kt`, and whether `SaveRows` sits in `SlotPanel.kt`, `SavesScreen.kt` or its own file).
- How the owners read the model's state, within the shape above.
- How the fakes are built, and the tests' names.
