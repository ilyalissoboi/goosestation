<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android in-game menu, part 2: discs and patch codes — design

Part 2 of the Android in-game menu (roadmap item 16), after part 1
([2026-10-31-android-in-game-menu-design.md](2026-10-31-android-in-game-menu-design.md), merged in #33, with the
choice lists and PlayStation glyphs of #34). Agreed in conversation on 2026-11-01. Calls made while writing this up,
not yet confirmed, are marked *(writer's call)*; calls the builder may make are marked *(builder's call)*.

## Intent

Part 1 gave the menu save and load state, a resume state, fast forward, analog mode, screenshot, reset and exit.
Part 2 adds the two remaining items the player asked for that don't need the network:

1. **Change disc**, with a multi-disc game shown as one game in the library.
2. **Patch codes** (cheats), switched on and off per game from the menu.

Part 3 (RetroAchievements) stays as outlined in part 1's design.

## Goals

### Multi-disc games in the library

- **A multi-disc game is one tile** (and one list row) in the library. Its discs are found by, in order:
  1. **the game database's disc sets** (`GameDatabase::Entry::disc_set_name` / `disc_set_serials`, which the core's
     `GameList` already turns into `DiscSet` entries; today `getGameList` in `android/app/src/cpp/jni_bridge.cpp`
     skips them, so each disc shows on its own);
  2. **an M3U playlist** whose entries are those discs: the playlist and its discs are the same game, shown once;
  3. **file names**, for games the database doesn't know: files in the same games folder whose names are the same
     once a disc marker is removed. Markers, matched regardless of case, with or without the brackets:
     `(Disc N)`, `[Disc N]`, `Disc N`, `(Disc N of M)`, `[Disc N of M]`, `Disc N of M`, and the same with `CD N`
     *(writer's call to include CD)*. N and M are digits (1–9 at least); a separator before a bare `Disc N` (space,
     `-`, `_`) is part of the marker. Two files with the same name and disc number aren't grouped (they're different
     dumps).
- **The tile** shows the game's title without the disc marker (the database's `disc_set_name` when it has one), and
  disc 1's cover *(writer's call)*. Its details are as today, with play time summed over the discs.
- **Starting the tile** boots the disc the player last played in that game, or disc 1 *(the player's choice)*.
- **Save states and the resume state belong to the game, not to one disc** *(the player's choice)*. Today the core
  keys them by the running disc's serial (`System::GetGameSaveStateFileName` with `s_running_game_serial`, which
  `InsertMedia` and a cross-disc state load change), so each disc has its own `<serial>_resume.sav` and slots. On
  Android they're keyed by **the game's key** instead:
  - a database disc set: its first disc's serial (the one `GetGameSettingsPath` already uses);
  - an M3U: its first entry's serial;
  - discs grouped by name: disc 1's serial, or its content-hash ID when it has no serial *(writer's call)*.
  The Android host passes the game's key wherever part 1 passed the running serial (slots, the resume state on Exit
  game, the resume prompt, last-used slots). The resume state on exit is written by the host under the game's key
  rather than through `ShutdownSystem(true)`, whose path uses the running disc *(writer's call; the core isn't
  changed for desktop)*.
- **Migration, once per game, when it's first seen grouped:** each disc's existing states are moved under the game's
  key. **Nothing is lost:** when two discs both have a resume state or the same slot, the newest keeps it and the
  others move to the lowest free slots; if there aren't enough free slots, the extras stay where they are, and Load
  state shows them in an "Other saves" section below the slots *(writer's call on the overflow)*. Last-used slots move
  with them. The migration is recorded so it doesn't run twice, and runs off the UI thread.
- **Desktop shares the data folder** and keys by the running disc, so it lists Android's game states under disc 1.
  That's accepted *(the player's choice)*.
- **Loading a save state or the resume state picks the right disc automatically** *(the player's requirement)*. The
  state records the media path, which may be from another folder pick, another device or desktop; so the host
  resolves it **by the disc's identity**, not the path: it reads the state's disc (its serial, or its number in the
  set), finds that disc in the library, and loads the state with that disc's current path. Only when the disc isn't
  in the library does the load fail *(writer's call)*.
- **Slot rows show the disc** ("Disc 2") under the time when the game has more than one disc *(writer's call)*.
- Discs that go missing (a file deleted or moved) aren't offered; if the disc a state needs isn't in the library,
  loading it fails with a `Danger` notice naming the disc, and the game stays where it was *(writer's call)*. The
  core's own behaviour (quietly keeping the current disc when the state's path won't open) must not happen on
  Android.

### Change disc

- A **Change disc** row sits after Load state, shown only for a multi-disc game, with its value at the row's end:
  "Disc 1 of 2" *(the player's choice of layout)*.
- It opens a panel beside the menu, as Save and Load state do: one row per disc, "Disc 1" to "Disc N" with the
  disc's own title or serial as a detail *(writer's call)*, the current disc marked (`beak-soft` and "Current").
- Picking another disc swaps it (for an M3U, `System::SwitchMediaSubImage`; otherwise `System::InsertMedia` with the
  disc's path), closes the menu and resumes the game, with **"Disc 2 inserted"** in the on-screen message layer.
  Picking the current disc does nothing but close the panel *(writer's call)*.
- The disc you switch to becomes the game's "last played disc" for the next start.
- B and Back return to the menu row, as the slot panels do. `KeyHints`: A Insert, B Back *(writer's call)*.

### Patch codes

- A **Patch codes** row sits after Screenshot, shown for every game *(writer's call)*.
- It opens a panel beside the menu with the game's codes, **grouped by the database's groups** (a group's name as a
  section heading, in `Eyebrow` style; codes with no group under the game's title or "Other" *(builder's call)*).
- **Each code is a switch row** (`SwitchRow`) with its name and, when the database gives one, its description as the
  row's note. **A manual code** (activation `Manual`: applied once each time) is a row with an **Apply** action
  instead, which applies it and shows "Applied: <name>" *(writer's call on the wording)*.
- **One list for the game** *(the player's choice)*, kept across disc swaps and cross-disc state loads (the core
  drops and reloads its list in `UpdateRunningGame`; on Android the host keeps the game's list and sets it again).
- **Where codes come from** *(the player's choice: no in-app editor)*:
  - the bundled database (`data/resources/chtdb.txt`): **the codes of every disc in the set, combined** (the database
    files Final Fantasy VII's codes under disc 1 only, and Final Fantasy VIII's under each disc), without duplicates
    *(writer's call: duplicates are the same group and name)*;
  - the player's own `.cht` files in the data folder's `cheats` folder: **`<game title>.cht`**, where the game title is
    the one the tile shows (the set's name, or the title without the disc marker), plus any per-disc files desktop
    already made (`<disc title>.cht`), read but never written *(writer's call: so codes a desktop player set up per
    disc aren't lost)*.
  - Combined by group and name; a player's file wins over the database for the codes it names.
- **The player's files are never rewritten** *(writer's call)*. The core's `SetCheatCodeState` saves the whole list
  back as a PCSXR file (`SaveCheatList`), which would rewrite a hand-made file and freeze the database's codes; the
  Android host doesn't use that path. **Which codes are on** is kept in a file of the app's own in the data folder
  (mirrored), keyed by the game's key *(builder's call on its name and format)*.
- **A change takes effect at once** while the menu is open, and switching codes doesn't close the menu *(writer's
  call: you often switch several)*.
- **A code a player's file marks as on** (`[*Name]`, as desktop writes) starts on, since the player switched it on
  there *(writer's call)*. Database codes start off.
- **The database never gives a description or a manual (Apply) code**; notes are part of some names after a colon
  ("Infinite HP Slot 1:Turn Off When Out of Battle"), which the row shows as the name and its note *(writer's call)*.
  Apply rows come only from players' files.
- **No separate on/off gate:** only the codes the player switches on apply. On Android the core's
  `Console/EnableCheats` setting is always on while a game runs *(the player's choice)*; nothing is switched on by
  default.
- **A game with no codes** still shows the row; the panel says "No patch codes for this game." and names the exact
  file to add: "Add `<game title>.cht` to the cheats folder in your data folder." *(writer's call)*.
- Long code names and notes wrap (the design system's 1.3× text rule), two lines each at most, then an ellipsis; the
  panel scrolls, keeping 4 dp around the focus ring *(changed during the build: some of the database's notes run to
  hundreds of characters, and a fully wrapped one was taller than the panel at 640 × 360 with text at 1.3×)*.

## Non-goals

- RetroAchievements (part 3), and hardcore mode's limits on codes and states (part 3).
- An in-app code editor, importing codes from other formats in the app, or a global cheats setting.
- Changing how the desktop app groups discs.
- Disc swapping while the menu is closed (a hotkey for the next disc) *(writer's call; a candidate for Hotkeys later)*.

## Anti-goals

- A save state or resume state that silently loads the wrong disc, or a disc group that merges two different games.
- A save, resume state or play time lost because the game's identity changed with grouping: states made in part 1
  under one disc's serial must still be found *(the builder checks how part 1 keyed them and migrates or looks up both
  if needed)*.
- Codes applied that the player didn't switch on.
- Anything part 1's anti-goals forbid: input leaking, the UI waiting on the core, window dialogs, breaking the design
  system.

## Constraints

As part 1: the design system is binding (tokens, components, `focusRing`, `KeyHints`, no window dialogs, the focus
and layout tests); core calls on the CPU thread through the host's queue; JNI in the existing pattern; no desktop
behaviour change; clean room; the user reviews every commit message. Disc grouping by name is pure logic and gets unit
tests (each marker form, case, separators, non-matches such as "Discworld" or "Disc Jockey", same-number duplicates).

## Left to the builder

Where grouping happens (native, from `GameList`'s `DiscSet` entries plus the name rule, or in Kotlin over the game
list) and how a group crosses JNI; how the last played disc is remembered; the patch code panel's exact layout within
the design system; the order of implementation.
