<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Patches that just work — design

Roadmap item 28 (patch codes with options, from the upstream gaps players notice most, `docs/upstream-features.md`
tier 8), reshaped in conversation on 2026-11-28. Calls made while writing this up, not yet confirmed, are marked
*(writer's call)*; calls the builder may make are marked *(builder's call)*.

## Intent

**Public patches, on any game they exist for** *(the player's choice)*: widescreen, frame-rate and display-mode
patches should **just work** when a player switches them on *(the player's choice)*. GooseStation already ships many
(the pre-fork cheat database has widescreen codes for 461 games and 60 fps or frame-rate codes for 46, 15 of them in
the user's library), but they don't work out of the box: a widescreen code needs the display set to 16:9 and the
emulator's own widescreen setting off, by hand; a 60 fps code usually needs a CPU overclock; on desktop they're mixed
with cheats behind "Enable Cheats"; and "Select widescreen type" groups let two choices be on at once. A value picker
(the roadmap's "options") adds little on its own, since hardly any shippable patch uses one; it comes with the import
of the newer community format. On **Qt, Big Picture and Android** *(the player's choice)*.

## What was found (2026-11-28)

### Public sources

*(Research notes, not legal advice.)*

| Source | Coverage | Licence | Use |
|---|---|---|---|
| **`data/resources/chtdb.txt`** (already shipped; Pugsy's conversion of his MAME cheat files, added 2020-11-29, last changed 2024-04-15, before the fork) | 9,837 games; widescreen for 461, 60 fps or frame-rate uncaps for 46 | No licence text of its own; contributed by pull request into the GPL-3.0 repository, so reasonably GPL-3.0; credited in `CONTRIBUTORS.md` | Ship, as today |
| libretro-database `cht/Sony - PlayStation` | 2,738 files; widescreen in 23, about 20 real 60 fps | CC BY-SA 4.0 since 2024-08-25 (GPL-3.0-compatible one way); a bulk import from GameHacking.org on 2026-11-11 may not have been libretro's to relicense; three files are tagged DuckStation | Not shipped; importable (already) |
| GameHacking.org | 449,332 PS1 codes (December 2024 copy) | None | Import only |
| PeterDelta/PCSX2 on GitHub (`.Playstation PS1/`) | about 65 titles, mostly PAL: 60 fps, widescreen, NTSC mode; in DuckStation's newer format | No licence file | Import only |
| DuckStation's later patch and cheat database | — | Off limits (clean room) | Never |

### The user's library (US unless noted; the bundled database unless noted)

- **Widescreen**: Vagrant Story, Legend of Dragoon, Breath of Fire III and IV, Crash Bandicoot 1–3 (Crash 2 JP has a
  Hor+/Vert- choice), Persona 2 Eternal Punishment, Colony Wars Red Sun, Alundra 2 (Vert-), Arc the Lad (16:10).
- **Frame rate**: Crash Warped (60 fps, two variants), Parasite Eve II (60 fps), King's Field II (30 and 60 fps
  uncaps); Armored Core (an FPS unlock, Europe only).
- **Import only (PeterDelta)**: 60 fps for Xenogears and Chrono Cross (disc 1).
- **No widescreen or 60 fps anywhere** for Final Fantasy VIII and IX, Legend of Mana, Grandia, Lunar 1 and 2, the Tales
  games, and most of the rest.

### What exists in the code

From a map of the code (2026-11-28; line numbers as of `ad67f916d`):

- **One list of cheats** (`cheats.h:14-203`): a `CheatCode` has a group, a description, GameShark instructions, an
  activation (`Manual` once, or `EndFrame` every frame) and an enabled flag; no value or option. Codes include A7
  "write if equal, with restore", which the database's header describes as "for 16:9 cheats to go back to 4:3 when
  disabled"; `ApplyOnDisable` (`cheats.cpp:2671`) undoes some codes when switched off.
- **Parsers** (`cheats.cpp`): PCSX-R `.cht` (:204, with `#group`, `#type`, `#activation` lines), libretro `.cht` (:328),
  ePSXe (:422), autodetect (:600), the database (`LoadFromPackage`, :693, `; [ Title … ]` headers, `:SERIAL` lines,
  `#Group\Name`). Text after a colon in a name is a note (Android shows it as one).
- **The database's choice groups**: some games offer a choice as a group, `#Select Widescreen Type\Widescreen 16-9 / (Vert-)
  / (Vert- Adjusted)` (`chtdb.txt:4428-4434`), `#Select Widescreen\16-9` (:11628); nothing stops two being on.
- **No separate patch concept**: no `patches/` folder or settings section, nothing applied on boot. Desktop: one
  `CheatList` (`system.cpp:368`) loaded from `cheats/<Game Title>.cht` and the database when `[Console] EnableCheats`
  is on (`system.cpp:4523-4525, 5968-6016`); on/off is written into that `.cht` (`SetCheatCodeState`, :6073). Hardcore
  forces cheats off (`settings.cpp:866-875`), as does "disable all enhancements" (:763).
- **Android owns its list** (`android/app/src/cpp/game_codes.{h,cpp}`; the host forces `enable_cheats` off,
  `android_host_host.cpp:85-88`): it merges the player's `.cht` files and the database's codes for every disc
  (`AndroidPatchCodes::Combine`, `src/util/android_patch_codes.h:61-69`), keeps on/off in `cheats/<key>.patchcodes`
  (one `on|off<TAB>group<TAB>name` line per change), applies after each game change, and drops the list in hardcore.
  Its in-game "Patch codes" panel (`ui/PatchCodePanel.kt`) shows groups, switches and apply rows.
- **UIs**: Qt's cheat manager (`cheatmanagerwindow.cpp`, a tree by group with import, export and an editor); Big
  Picture's flat "Cheat List" from the pause menu (`fullscreen_ui.cpp:1371-1411`, needs `EnableCheats`); Android's
  panel. None chooses a value.
- **Widescreen and frame rate**: `[GPU] WidescreenHack` makes the GTE use the display's aspect ratio (`gte.cpp:232-240`);
  the `ToggleWidescreen` hotkey flips it and sets 16:9 (`system.cpp:6127-6162`); the database trait `DisableWidescreen`
  turns it off. **Nothing links a code to the display**: a widescreen code leaves the aspect ratio as it was. There's
  no frame-rate setting; the CPU overclock is `[CPU] OverclockEnable`/`OverclockNumerator`/`OverclockDenominator`.

### The newer community format (from its public description only)

DuckStation's later cheat-format description (`cheat-format.md` in its public cheat repository, read for the format
only, never its data): INI-style entries, `[Name]` then keys — `Type = Gameshark|Assembly`, `Activation =
Manual|EndFrame|StartFrame`, `Author`, `Description`, `Option = Name:Value` (repeatable, a choice) and
`OptionRange = min:max` (a number), whose value replaces `??` nibbles in the code; patch-only overrides such as
`OverrideAspectRatio`, `OverrideCPUOverclock` and `DisableWidescreenRendering`. Community authors (PeterDelta) publish
in it.

## Goals

### Patches as their own list

- **Patches are widescreen, frame-rate and display-mode codes** *(the player's choice)*: 16:9 and other ratios, 60 fps
  and frame-rate uncaps, NTSC mode on PAL discs, removing black bars. Everything else (infinite health, items, debug
  menus, skipping FMVs) stays a cheat.
- **Which database codes are patches** is decided by **a small annotation layer GooseStation keeps beside the bundled
  database** *(the player's choice)*: a file of our own *(builder's call on its name and format)*, keyed by serial and
  code name, starting from the codes' names (the widescreen, 16-9, 16-10, Vert-, 60 FPS, uncap, NTSC-mode and
  black-bar names) and checked by hand on the library. It also says what each patch needs (below). **The bundled
  database itself isn't rewritten** *(writer's call)*. Imported files in the newer format mark their own patches.
- **Patches are always available per game**, on Qt (a Patches page of its own per game *(builder's call where: game
  properties, the Cheats menu, or both)*), Big Picture (a Patches list from the pause menu and per game) and Android
  (its existing panel, split into Patches and Cheats) *(the player's choice)*, **not behind "Enable Cheats"**, which
  keeps gating cheats only, on desktop. **Android gets no new switch** *(the player's choice)*: it has no "Enable
  Cheats" today (its panel shows every code), and splitting the panel leaves nothing players use there behind a switch.
  **Patches and cheats stay off in hardcore** everywhere.
- **Where on/off is kept**: per game, in the game's settings on desktop and in the existing `.patchcodes` file on
  Android *(builder's call on desktop's key)*; never in the player's `.cht` files, nor in the global settings.

### Patches that set what they need

- **A patch switched on sets what it needs for the running game** *(the player's choice)*: a widescreen patch the
  display's aspect ratio (16:9, or 16:10 for a 16:10 code) and the emulator's widescreen setting off, so the picture
  isn't widened twice; a frame-rate patch the CPU overclock it needs (from the annotation, or the newer format's
  `OverrideCPUOverclock`; see "Overclocks" below). These are **temporary, game-only overrides**, like the game database's: they're undone when
  the patch is switched off or the game ends, and **never written into the player's settings** *(the player's
  choice)*. A setting the player changes by hand while a patch is on wins until the patch is switched off
  *(writer's call)*.
- **Switching a patch off while the game runs** *(the player's choice)*: a patch that undoes itself (its codes restore,
  as `A7` and `ApplyOnDisable` do) switches off at once, with its overrides. Most widescreen codes don't (about 160 of
  the database's roughly 526 use `A7`; the rest are plain writes), so switching one of those off asks: **reset the game
  now, or apply at the next start**. Its 16:9 and overclock stay until then, so the picture never comes out squashed.
  Switching a patch on applies at once.
- **Overclocks for frame-rate patches** *(the player's choice)*: for the library's frame-rate games (Crash Warped,
  Parasite Eve II, King's Field II), the builder measures on the Windows machine the lowest overclock that holds a
  steady 60 fps (or the patch's rate) in a test scene, and the annotation records it. Outside the library, an overclock
  the code's name gives (King's Field II "160 Overclock", Bust A Move "130%") is used; a frame-rate patch without one
  leaves the overclock to the player, with a note on the patch saying it may need one. **Widescreen patches are all
  marked from their names** (they only set the aspect ratio and the widescreen setting).
- **Choice groups become one choice, for patches only** *(writer's call, from "one choice per group")*: patches under a
  `Select …` group, and other patch groups the annotation marks as alternatives, show as one patch with a choice, and
  only one can be on. **Cheats under `Select …` groups (about 7,600: levels, items, scores) are left as they are.**
- **Codes already switched on that become patches** *(writer's call)*: they keep their on state (from desktop's
  `cheats/<Title>.cht` and Android's `.patchcodes`), so a working widescreen doesn't vanish, and they leave the cheat
  list, so they're never applied twice; from then on they bring their 16:9 and overclock.

### The newer format

- **Files in the newer community format can be imported** (Qt's import, Android's own `cheats/` folder as today, and
  Big Picture where it imports), read from the format's public description *(the player's choice)*: `[Name]` entries,
  `Type = Gameshark` (assembly-type entries are listed but left off, with a note *(writer's call)*), `Activation`,
  `Author`, `Description`, `Option` and `OptionRange` (shown as a choice or a number, the chosen value replacing `??`
  nibbles), and the overrides above (`OverrideAspectRatio`, `OverrideCPUOverclock`, `DisableWidescreenRendering`),
  which mark the entry as a patch. Keys it doesn't understand are kept and ignored, with a note.
- **Choices are remembered** with on/off, by the option's name *(writer's call)*.

## Success

*(The player's choice: public patches just work on any game they exist for, tested on the library; the measures are the
writer's calls.)*

- On the 15 library games the bundled database covers, from the Windows machine's library: each widescreen patch,
  switched on, gives a 16:9 (or 16:10) picture that isn't stretched or widened twice, without the player touching a
  setting; switched off, the picture and every setting are exactly as before (frames compared with the regression
  tests where they can be *(builder's call how patches are switched on in a run)*).
- Each frame-rate patch runs the game at its frame rate with the overclock applied, and the overclock is gone when it's
  switched off or the game ends.
- A choice group shows as one patch, and only one choice can be on.
- An imported newer-format file (written for the test from the format description, and PeterDelta's Xenogears and
  Chrono Cross files used only on the test machines, never shipped) loads, shows its options, and applies them.
- Nothing leaks: after any patch on and off (with the reset or at the next start, where it asks), and after a game
  ends, the global and per-game settings files are byte-identical to before; no patch or cheat in hardcore; on desktop,
  no cheat without "Enable Cheats".
- Frame-rate patches in the library hold their rate at the measured overclock.
- The same on Qt, Big Picture and Android (Android on the emulators and the Thor).

## Non-goals

- Writing or shipping widescreen or 60 fps patches of our own for games no public source covers (they can be added
  later, through the annotation layer or our own files, like the game database).
- Shipping libretro's, GameHacking's or PeterDelta's patches; assembly-type patches; a code search or a patch editor
  beyond what Qt has.

## Anti-goals

*(The player's choice: any of these makes it a failure.)*

- **A patch leaving its settings behind**: 16:9, the overclock or the widescreen setting still changed after the patch
  is off or the game ends, or written into the player's settings.
- **A patch active in hardcore mode.**
- **A cheat available without "Enable Cheats"** (on desktop; Android has no such switch).
- **A game hanging or misbehaving** because a patch's overclock or settings were applied wrongly.
- Anything from DuckStation's code or data after `a5bf1ba5cee7`.

## Constraints

- **Clean room**: nothing from DuckStation's code or data after `a5bf1ba5cee7`; its later cheat-format description
  (user documentation) only, for the newer format. Unlicensed sources (GameHacking, PeterDelta) are import-only and
  never committed; a test fixture in the newer format is written by hand.
- **One stage, one PR** *(writer's call)*; `docs/roadmap.md` (item 28, reworded to what's built) and the user docs
  updated.
- **Tests**: native tests for the annotation layer, the newer format's parser (options, ranges, overrides, unknown keys),
  the overrides applied and undone (no settings written), choice groups, and the hardcore and "Enable Cheats" rules;
  Android's quick tier while building and the full tier before the PR; regression-test frames on the Mac and Windows.
- **Games**: the user's library on the Windows machine (SSH `pcsx2-win`, `F:\\games\\emulated\\psx\\`, US BIOS
  `C:\\Users\\Ilya\\Documents\\DuckStation\\bios\\SCPH1001.BIN`); copies go to scratch folders only.
- **The Thor** *(the player's choice)*: the building session may run checks whenever it's connected (adb `64dc3c35`),
  with the `.debug` app and scratch data in `/sdcard/GooseTest` only (library games copied there, never into the
  user's data folder), never the release app (the plain ID, never uninstalled), and cleans up afterwards. Playing with
  patches on, and judging how they look, are the user's checks.
- The user reviews every commit message.

## Left to the builder

The annotation layer's name, format and how it's built from the names; desktop's per-game key; where Qt's Patches page
sits; how a choice group is shown on each UI; how overrides are applied and layered with the game database's; how
patches are switched on in a regression run; and the order of implementation.
