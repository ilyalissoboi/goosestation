<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android save state management — design

Part of roadmap item 16 (the Android frontend's "Saved data" stage, which named save state management). Agreed in
conversation on 2026-11-08. Calls made while writing this up, not yet confirmed, are marked *(writer's call)*; calls
the builder may make are marked *(builder's call)*.

## Intent

The in-game menu saves and loads a game's slots, but a save can't be deleted, Undo load is only a hotkey, desktop's
global slots don't exist on Android, and a game's saves can only be reached by starting the game. This finishes
save states: **delete**, **Undo load** in the menu, desktop's **shared (global) slots**, and a game's **Saves from
its library tile** *(the player's choice: in the game and from the library)*.

## What exists

- **The in-game slot panel** (`ui/SlotPanel.kt`, `docs/android.md` "The in-game menu"): Save lists Slot 1 to 10, Load
  lists the slots that hold a save with **Resume** (the resume state) first and **Other saves** (states still under a
  disc's serial) after; screenshots, "Saved"/"Empty" and the time; replacing asks in the panel. States are the core's
  `savestates/<key>_<n>.sav` under the game's key, mirrored to the data folder. Multi-disc rows say their disc, and a
  state loads on its own disc.
- **The core:** `PER_GAME_SAVE_STATE_SLOTS` and `GLOBAL_SAVE_STATE_SLOTS` are 10 each (`system.h:85-86`); global slots
  are `savestates/savestate_<n>.sav` (`system.cpp:5324`); `CanUndoLoadState` / `UndoLoadState` (`system.h:376-382`).
  Backups (`CreateSaveStateBackups`, off by default on Android) keep a replaced slot as `<key>_<slot>.bak`.
- **Hotkeys** (settings stage 2) save, load, select slots and undo a load through the app's save path. Of the core's
  own save state hotkeys, only the selected-slot ones are compiled out on Android (`hotkeys.cpp:655-691`); the
  per-slot and global-slot ones (`LoadGameState1`…, `SaveGlobalState1`…) are compiled in, only hidden from the
  Controllers page by name (`android_host.cpp:138-143`, whose comment says the app has no global slots), and key
  states by the disc's serial, not the game's key (`hotkeys.cpp:79-80, 111-112`).
- **Leaving a game** (Exit game) saves the resume state when `SaveStateOnExit` is on (the default), asking first to
  replace one, and warns while a memory card is being written.
- **Booting from a state** exists (`SystemBootParameters.save_state`, `android_host.cpp:1614`); only the resume
  path uses it today.
- **Games without a key** (no serial and no hash: the BIOS alone, say) have no save states at all today
  (`android_host.cpp:1767-1771`).
- **The library:** A plays, Y (or a long press, by touch) opens the game's settings; the foot says "A Play · Y Game
  settings".
- Hardcore mode turns Load state off (`GameMenu.kt:382-384`, and the Load panel won't open). **Undo load isn't
  refused in hardcore today**: the hotkey (`InGameMenu.kt:834-858`), `AndroidHost::UndoLoadState`
  (`android_host.cpp:3205-3212`) and the core (`system.cpp:5061`) don't check, and the undo state survives the reset
  that turns hardcore on (`s_undo_load_state` is cleared only in `DestroySystem`, `system.cpp:1963`).

## Goals

### Tabs: this game and shared

- **Save state and Load state get two tabs** *(the player's choice)*: **This game** (today's slots, with Resume and
  Other saves in Load) and **Shared** (desktop's ten global slots), using the design system's `PanelTabs`, L1/R1
  switching, the focus starting on This game *(writer's call)*.
- **A shared slot shows the game it holds** (its title, or serial when the library doesn't know it) beside its
  screenshot and time, and its disc for a multi-disc game *(writer's call)*.
- **Saving to a shared slot** writes the running game, replacing after asking as today.
- **Loading a shared slot made in another game** *(the player's choice)* asks first ("Load Crash Bandicoot from shared
  slot 3? This game stops.", Cancel focused) *(writer's call on the wording)*, then **leaves the running game as Exit
  game does** (saving its resume state when that's on, with Exit's own question to replace one, and its memory card
  warning) *(writer's call)*, then starts that game from the state, found by the state's own disc identity as today's loads are. If
  that game isn't in the library, the load is refused with the game it needs ("Shared slot 3 needs Crash Bandicoot
  (SCUS-94900), which isn't in your library."). A shared slot made in the running game loads like any slot.
- Shared slots are the same files desktop uses, so a data folder shared with desktop shows the same ones.
- **A game without a key gets the Shared tab** *(writer's call: shared slots need no key, as on desktop)*: its This
  game tab says "This game can't keep save states of its own."; the Save and Load rows open on the Shared tab.

### Deleting a save

- **Delete** on a focused save in Load, and on the library's Saves page *(writer's call: Load, not Save, which keeps
  replace-only)*: a second button (X, shown in `KeyHints`) or a long press by touch. It asks first ("Delete the save
  in slot 3?", Cancel focused), in the panel. It works for slots, the resume state, Other saves and shared slots.
- A deleted save's backup (`.bak`) goes with it *(writer's call)*. The deletion reaches the data folder through the
  mirror, as any change does.
- In hardcore mode the in-game Load panel stays closed, as today, so Delete is reached from the library's Saves page
  *(writer's call)*.

### Undo load in the menu

- **Undo load**, a menu row after Load state *(writer's call)*, enabled only when the core can undo a load in this
  game (`CanUndoLoadState`); it goes back to the game saying "Undid the last load". Off in hardcore mode, as Load is,
  **and the Undo load hotkey and the native undo are refused in hardcore too** ("Loading a state is off in hardcore
  mode"), fixing today's gap *(writer's call)*; turning hardcore on drops any undo state.

### The core's own slot hotkeys

- The core's per-slot and global-slot hotkeys can't bypass the app's save path: on Android they're compiled out, as
  the selected-slot ones are, or routed through the app *(builder's call which)*, so a binding from a desktop
  `settings.ini` can't save under the disc's serial. The out-of-date comment is fixed.

### A game's Saves from the library

- **X on a tile opens the game's Saves** *(the player's choice)*; the library's foot reads "A Play · X Saves · Y Game
  settings". By touch, the long press offers **Saves** and **Game settings** *(the player's choice)*.
- **The Saves page** has the same two tabs and rows as the in-game Load panel (screenshots, times, discs), drawn as a
  page of its own in the app's window *(builder's call on the layout, within the design system)*.
- **A on a save starts the game from it**: the game boots on the state's disc and loads it; a shared slot made in
  another game starts that game, as above. With hardcore mode on, a save can't start the game ("Loading a state is
  off in hardcore mode"), and Delete still works.
- **Delete** as above. A game with no saves says "No saves yet for this game."; a game without a key shows only the
  Shared tab, with "This game can't keep save states of its own."

### What doesn't change

- **Saves have no names** *(writer's call)*: desktop names them only by slot, so naming them here would be invisible
  there. The known-limits line about renaming is dropped.
- The slot hotkeys keep working on this game's slots.

## Non-goals

- Naming saves; more than ten slots per game or ten shared slots.
- Syncing save states between devices (only memory cards sync).
- Importing or exporting save state files.

## Anti-goals

- A save deleted without asking, or the wrong save deleted (a list that moved under the focus).
- A shared slot from another game loading into the running one, or switching games without asking.
- Losing the running game's place without the question saying so.
- Breaking the design system or controller-first rules: everything reachable with the D-pad, focus always visible,
  no window dialogs, `KeyHints` on each panel and page.

## Constraints

- The design system ([`docs/design-system.md`](../../design-system.md)) is binding; the tabs, the Saves page and
  their questions go into `FocusAuditTest` and `OrientationLayoutTest`, with screen tests driven by keys; the quick
  tier while building and the full tier before the PR (`android/scripts/run-instrumented-tests.sh`).
- Save, load, delete and undo go through the core and the app's save path, off the UI thread; file names stay the
  core's, so desktop reads them.
- Test states are made by the tests, never a player's real saves; checks on the Thor are the user's, with their
  go-ahead.
- Android-only code in `android/`; no desktop change. Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor
  its closed Android app. `docs/android.md` and the roadmap (item 16) are updated. The user reviews every commit
  message.

## Left to the builder

How the Saves page and the tabs are laid out within the design system; how a game is started from a state (boot then
load, or the core's own boot-from-state); how a shared slot's game is identified from its file; the order of
implementation.
