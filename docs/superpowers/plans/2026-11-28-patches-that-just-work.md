<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Patches that just work Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Widescreen, frame-rate and display-mode codes become **patches**: their own per-game list on Qt, Big Picture
and Android, apart from cheats and not behind desktop's "Enable Cheats", each setting what it needs (aspect ratio,
widescreen rendering off, an overclock) as a temporary game-only override, one choice per patch group, and files in the
newer community format importable with their options and overrides.

**Architecture:**
- **Marks beside the database.** `data/resources/chtdb_patches.txt`, one tab-separated line per patch code:
  `serial<TAB>Group\Name<TAB>kind and needs` (`widescreen 16:9`, `widescreen 16:10 choice=Select Widescreen Type`,
  `framerate overclock=160`, `framerate`, `displaymode`). It is the output of `scripts/generate_patch_marks.py`, which
  reads `chtdb.txt`, picks codes by name (widescreen, 16-9, 16:10, 21:9, Vert-, Hor+, 60 FPS, FPS unlock/uncap, NTSC
  mode, PAL2NTSC, black bars), drops a hand-checked exclusion list (Harold's "Password: Widetv", "Trophy Room", "Uncapped
  Fireball", "Uncap weapon speed", "Slowmotion '20 FPS'", "Turbo mode", "Increase Track Detail" …), marks `Select …`
  groups and hand-listed alternatives (King's Field II's 30 and 60 fps uncaps) as choices, and takes overclocks from the
  names ("160 Overclock", "130% overclock") or from the measured table (Task 11). `chtdb.txt` is never rewritten.
- **A code knows more.** `CheatCode` gains the newer format's fields (author, details, options, a range, its `?`-nibble
  lines, the chosen value, override keys kept verbatim, notes on what was ignored) and two flags: `patch` (a patch, not
  a cheat) and `Type::Assembly` (listed, never applied). Our `.cht` writer and reader carry them as `#` lines, so an
  imported file survives Qt's editor and export. `CheatList::Format::INI` reads the newer format.
- **`Patches::PatchList`** (`src/core/patches.{h,cpp}`) is the per-game list every UI shows: patches grouped into
  choices, each with a **saved** choice (what the switches say) and a **running** one (what applies now). Switching to
  a choice runs at once unless the running choice's codes don't restore themselves (`CheatCode::RestoresOnDisable()`:
  every write is `A7`), in which case the UI asks first and either resets (`Restart()` after `System::ResetSystem()`)
  or leaves it for the next start. The running choices' combined needs go into `g_settings` in `System::LoadSettings`,
  after the game database's, through `PatchList::ApplyOverrides(Settings&)`; nothing writes them anywhere.
- **Where on/off lives.** Desktop: `[Patches]` in `gamesettings/<serial>.ini`, only differences from each code's
  default (its `.cht` marker): `On = Group\Name`, `Off = Group\Name` and `Value = Group\Name<TAB>value` string lists;
  the section goes when empty, and the file with it when nothing else is left (`QtHost::SaveGameSettings`'s rule).
  Android: the existing `cheats/<key>.patchcodes`, with an optional fourth field for the value.
- **Loading.** Desktop (`#ifndef __ANDROID__`): `System::UpdateRunningGame` builds the list from the database for the
  serial plus the patches in `cheats/<Title>.cht`, restores the switches from the game settings layer, then applies
  settings. Android: `GameCodes` keeps building its combined list (every disc, the player's files), splits it with the
  same classifier, and hands the patches to `System::SetPatchList`, the cheats to `System::SetCheatList` as today.
  The cheat list skips codes marked `patch` (kept in it only so Qt's `.cht` save keeps them).

**Tech Stack:** C++20 (`src/core`), gtest (`src/common-tests`), Python 3 (the marks generator), Qt 6 widgets, Dear ImGui
(Big Picture), Kotlin/Compose (Android), `goosestation-regtest` (Mac; `pcsx2-win` D3D11), Android emulators and the
AYN Thor.

**Spec:** `docs/superpowers/specs/2026-11-28-patches-that-just-work-design.md`.

## Decisions (made with the user on 2026-11-28: 1 A, 2 A, 3 A, 4 A, 5 A, 6 A, native execution)

1. **The annotation layer's form.**
   - **A. A generated, committed file plus its generator (recommended).** `chtdb_patches.txt` is data (reviewable line
     by line, about 560 lines), and `generate_patch_marks.py` holds the name rules, the exclusions and the measured
     overclocks, so it can be re-run and every hand call is in one place. A test checks each line names a real
     `chtdb.txt` code. Con: two artifacts to keep in step (the test catches drift).
   - B. Name rules in C++ at load, plus a small exceptions file. Pro: tiny. Con: classification hides in code and isn't
     "keyed by serial and code name" as the design asks; a rule change silently reclassifies.
   - C. A hand-written file only. Con: 560 lines typed by hand, no record of how they were chosen.
2. **Overrides against the player's own settings.**
   - **A. The patch sets what it needs when it starts; a later hand change wins; an overclock never goes down
     (recommended).** A player's 200% stays when a patch wants 160%. Pro: the picture is right however the player had
     set the ratio. Con: differs from the newer format's own rule.
   - B. The newer format's rule: the ratio only when it's Auto, the overclock only when none is set. Con: a player with
     4:3 set by hand gets a squashed widescreen picture, the design's main complaint.
3. **Where Qt's Patches page sits.**
   - **A. A Patches page in game properties (running or not), and System › Patches… opening it for the running game
     (recommended).** One place, per game, works before booting. Con: a dialog to switch one patch in game.
   - B. A System › Patches submenu of checkable actions only. Con: nothing before the game boots; choices and values
     don't fit a menu.
   - C. Both. Con: two UIs to keep in step.
4. **Android's split.**
   - **A. One side panel with two tabs, Patches then Cheats (`PanelTabs`), from the menu row renamed "Patches & cheats"
     (recommended).** Pro: the menu keeps its length (the Thor's small screen); one focus path. Con: cheats a tab away.
   - B. Two menu rows, "Patches" and "Cheats". Con: a longer menu; the focus audit and layout tests change more.
5. **"Disable all enhancements".**
   - **A. Patches are off with it, as cheats are (recommended).** It already forces the overclock off, so a 60 fps patch
     would run without its overclock (an anti-goal). Con: none found.
   - B. Patches stay on. Con: frame-rate patches misbehave.
6. **Values for imported cheats.** The newer format lets any code have options.
   - **A. Values wherever a code shows: patches on every UI, cheats on Qt's cheat manager, Big Picture's cheat list and
     Android's Cheats tab (recommended).** One `CheatCode::SetOptionValue` path. Con: three more value pickers.
   - B. Patches only; a cheat with options keeps its default value. Con: the player can't pick the value they imported
     it for.

Execution: native, in one session.

## Global Constraints

- Clean room: nothing from DuckStation's code or data after `a5bf1ba5cee7`; the newer format from its public
  description only; unlicensed sources (GameHacking, PeterDelta) never committed; the newer-format test fixture written
  by hand.
- Patches are widescreen, frame-rate and display-mode codes (16:9 and other ratios, 60 fps and uncaps, NTSC mode on PAL
  discs, removing black bars); everything else stays a cheat.
- The bundled `chtdb.txt` is not rewritten.
- Overrides are temporary and game-only: never written into the player's settings, gone when the patch is off or the
  game ends.
- No patch or cheat in hardcore; on desktop no cheat without "Enable Cheats"; Android gets no new switch.
- Cheats under `Select …` groups are left as they are; only patch groups become one choice.
- On/off never in the player's `.cht` files nor the global settings.
- One stage, one PR; every commit message shown to the user after the commit (commit as you go).
- Thor: adb `64dc3c35`, the `.debug` app and `/sdcard/GooseTest` only; never the release app or the user's data
  folder; cleaned up afterwards. Windows: `pcsx2-win`, library `F:\games\emulated\psx\`, BIOS
  `C:\Users\Ilya\Documents\DuckStation\bios\SCPH1001.BIN`, copies to scratch only; never junction shared dependency
  folders between worktrees.

## Review Focus

1. **Names that break INI or tab lines**: a code named with `=`, `;`, `#`, a leading space, `\` in the name part, or
   non-ASCII survives `[Patches]` and `.patchcodes` round trips exactly (Task 5 and Task 9 tests).
2. **Bad option data**: a value outside its range, an `Option` value too wide for its `?` nibbles, `?` in two separate
   runs on a line, or options on a code with no `?` → the code is listed, can't be switched on, and says why (Task 1).
3. **"Next start" that never comes as a boot**: a reset, a disc change to another serial and a state load while a patch
   is pending off; the pending choice takes over at the reset and at the game change; a state load changes no switch
   and no override (Task 4 for `Restart()`, Task 6 regtest run with `-statefile`).
4. **Two patches with needs at once** (a widescreen and a 60 fps patch, or two widescreen codes from different groups):
   the ratio from the widescreen one, the overclock the larger, widescreen rendering off if either asks; switching one
   off leaves the other's needs (Task 5).
5. **The same name on two discs**: Android's "(Disc 2)" naming and the marks keyed by each disc's own serial classify
   each disc's code by its serial (Task 9).

---

### Task 0: Baselines

**Files:** none committed (scratch `/tmp/goose-patches/`, on Windows `C:\goose-patches\`).

- [ ] **Step 1: Build master's regtest** in a detached worktree of `master` on the Mac and on `pcsx2-win` (copy deps,
  never junction them; see the memory notes on regtest renderer checks and the library pass).
- [ ] **Step 2: Frames, patches off**: the library games the database covers (Vagrant Story, Legend of Dragoon,
  Breath of Fire III and IV, Crash 1–3, Crash 2 JP, Persona 2 EP, Colony Wars Red Sun, Alundra 2, Arc the Lad, Parasite
  Eve II, King's Field II), 3600 frames, dump every 300, Mac Metal and Windows D3D11. Saved as the "off" baseline.
- [ ] **Step 3: Test scenes for the frame-rate games**: a gameplay save state each for Crash Warped, Parasite Eve II
  and King's Field II, made with master's Qt build on `pcsx2-win` in a scratch data folder (or the game's attract demo
  where it reaches gameplay unattended). Recorded in the shared notes folder with their frame numbers.

### Task 1: The newer format and our `.cht` extras

**Files:**
- Modify: `src/core/cheats.h:14-203`, `src/core/cheats.cpp` (parsers :204-330, `DetectFileFormat` :600, `SaveToPCSXRFile`
  :674, `Apply`, type names :2771)
- Test: `src/common-tests/cheats_format_tests.cpp` (new; `CMakeLists.txt`, `.vcxproj`, `.filters`)

**Interfaces — Produces:**
- `CheatCode::Type::Assembly`; `CheatCode::Activation` gains nothing (`StartFrame` is read as `EndFrame`, with a note).
- `struct CheatCode::Option { std::string name; u32 value; }`; fields `std::string author; std::string details;
  std::vector<Option> options; std::optional<std::pair<u32, u32>> option_range; std::vector<std::string> raw_lines;
  u32 option_value = 0; std::vector<std::pair<std::string, std::string>> overrides; std::vector<std::string> notes;
  bool patch = false;`
- `bool CheatCode::HasOptions() const` (options or a range); `bool CheatCode::CanEnable() const` (Gameshark, has
  instructions, and a valid value when it has options); `bool CheatCode::SetOptionValue(u32 value, Error* error)`
  (rebuilds `instructions` from `raw_lines`).
- `CheatList::Format::INI`; `bool CheatList::LoadFromINIString(const std::string& str)`.

- [ ] **Step 1: Write the failing tests** (fixture text written by hand in the test):
  - `INIReadsEntries`: `[Movement\Moon Jump]` with `Type = Gameshark`, `Activation = Manual`, `Author = A`,
    `Description = Hold X`, two code lines → group `Movement`, name `Moon Jump`, manual, author, details, 2 instructions.
  - `INIOptionsReplaceNibbles`: `Option = Slow:1`, `Option = Fast:0x20`, line `80001234 00??` → value 1 gives
    `80001234 0001`; `SetOptionValue(0x20)` gives `0020`.
  - `INIShiftsValueToItsNibbles`: `80001234 ??00` with `OptionRange = 0:255`, value `0b101` → `0500`.
  - `INIRejectsBadValues`: value 300 in range `0:255` → false, error set; `?` in two runs (`8000123? 00?0`) → code
    listed, `CanEnable()` false, a note; options without any `?` → same.
  - `INIOverridesMarkPatch`: `OverrideAspectRatio = 16:9`, `OverrideCPUOverclock = 150`, `DisableWidescreenRendering =
    true` → `patch` true, the three kept in `overrides`; `OverrideRAMSize = 8MB` kept, a note "ignored".
  - `INIUnknownAndIgnore`: `Foo = Bar` kept with a note; `Ignore = true` entry left out; `Type = Assembly` listed,
    `CanEnable()` false, note.
  - `DetectsINI`: text with `[Name]` then `Type = Gameshark` → `Format::INI`; a PCSXR file with `#group=` lines →
    `Format::PCSXR` as before.
  - `ChtRoundTripKeepsExtras`: an INI-loaded list saved with `SaveToPCSXRFile` and read back with `LoadFromPCSXRString`
    has equal author, details, options, range, raw lines, overrides and `patch`.
  - `ApplySkipsPatchesAndAssembly`: a `CheatList` with an enabled patch and an enabled cheat writing different RAM
    addresses applies only the cheat (RAM via the test's memory stub, as `pgxp_tests` does).
- [ ] **Step 2: Run** `build/bin/common-tests --gtest_filter='CheatsFormat*'`. Expected: FAIL (no `Format::INI`).
- [ ] **Step 3: Implement.** INI reading per the public description: `;`/`#` comments; `[Name]` with `\` grouping;
  `Key = Value` lines until the first code line; values decimal, `0x`, `0b`. Our `.cht` gains `#author=`, `#details=`,
  `#option=Name:Value`, `#optionrange=min:max`, `#override=Key=Value`, `#patch`, and (with options or Assembly) the raw
  lines verbatim. `CheatList::Apply`, `GetEnabledCodeCount` and `ApplyCode` skip `patch` and non-`CanEnable()` codes.
- [ ] **Step 4: Run** the filter again and the whole `common-tests`. Expected: PASS.
- [ ] **Step 5: Commit** (`Cheats: read the newer community format, keep its extras in our .cht`).

### Task 2: Marks and what a code restores

**Files:** Create `src/core/patches.h`, `src/core/patches.cpp` (add to `src/core/CMakeLists.txt`, `core.vcxproj`,
`.filters`, and the test project); Modify `src/core/cheats.{h,cpp}`; Test `src/common-tests/patches_tests.cpp` (new).

**Interfaces — Produces:**
- `enum class Patches::Kind : u8 { Widescreen, FrameRate, DisplayMode, Other };`
- `struct Patches::Needs { u16 aspect_numerator = 0; u16 aspect_denominator = 0; bool disable_widescreen_rendering =
  false; u32 overclock_percent = 0; bool may_need_overclock = false; }` (0 leaves a setting alone).
- `struct Patches::Mark { Kind kind; Needs needs; std::string choice; }` (`choice`: the title of the alternatives it's
  one of, or empty).
- `class Patches::Marks { static Marks Parse(std::string_view text, Error* error); const Mark* Find(std::string_view
  serial, std::string_view group, std::string_view name) const; size_t Count() const; }`
- `const Patches::Marks& Patches::BundledMarks();` (reads `chtdb_patches.txt` once).
- `Patches::Needs Patches::NeedsFromOverrides(const CheatCode& code);`
- `std::optional<Patches::Mark> Patches::Classify(std::string_view serial, CheatCode& code, const Marks& marks);` —
  sets `code.patch` from a mark, override keys, or a `#patch` line; returns the mark (from overrides: kind `Other`,
  `Widescreen` if it sets a ratio, `FrameRate` if it sets an overclock).
- `bool CheatCode::RestoresOnDisable() const;` — at least one `A7`, and every other instruction a Nop or a condition.

- [ ] **Step 1: Write the failing tests**: `MarksParse` (`widescreen 16:10` → 16/10 and widescreen off; `framerate
  overclock=160`; `framerate` → `may_need_overclock`; `choice=Select Widescreen Type`; group `Ungrouped` matches an
  empty group; a bad line skipped with the error naming its line number); `MarksFindBySerialAndName`;
  `ClassifyByOverrides`; `RestoresOnlyWithA7` (King's Field II's `A7019656 14402400` lines → true; Crash Warped's
  `Widescreen 16-9` plain `80…` writes → false; an `A7` plus a `30` write → false; `D0` then `A7` → true).
- [ ] **Step 2: Run** `--gtest_filter='Patches*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** `Needs` for kind `Widescreen` always has `disable_widescreen_rendering`; its ratio defaults
  to 16:9.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** (`Patches: marks beside the cheat database, and which codes restore themselves`).

### Task 3: The marks file

**Files:** Create `scripts/generate_patch_marks.py`, `data/resources/chtdb_patches.txt`; Test
`src/common-tests/patches_tests.cpp`. Add `chtdb_patches.txt` wherever `chtdb.txt` is packaged (CMake resource copy,
Android assets, Windows `.vcxproj` copy step): grep for `chtdb.txt` and add beside each.

- [ ] **Step 1: Write the failing tests** (`GOOSESTATION_TEST_RESOURCES_DIR`): `BundledMarksNameRealCodes` (every line's
  serial and `Group\Name` is a code `CheatList::LoadFromPackage(serial)` returns); `BundledMarksCoverLibrary` (a
  widescreen mark for each library game's serial: SLUS-01040 Vagrant Story, SCUS-94491 Legend of Dragoon disc 1,
  SLUS-00422 Breath of Fire III, SLUS-01324 Breath of Fire IV, SCUS-94900 Crash, SCUS-94154 Crash 2, SCPS-10047 Crash 2
  JP as a choice, SCUS-94244 Crash Warped, SLUS-01158 Persona 2 EP, SLUS-00866 Colony Wars Red Sun, SLUS-01017
  Alundra 2 (Vert-), SLUS-01224 Arc the Lad (16:10); frame-rate marks for SCUS-94244, SLUS-01042 and SLUS-00255,
  King's Field II's two as one choice); `BundledMarksSkipCheats` (no mark for "Harold's 2 Codes",
  "Trophy Room", "Uncapped Fireball", "Slowmotion '20 FPS'", "Turbo mode"); `SelectCheatGroupsUntouched` (a `Select
  Level` group has no marks).
- [ ] **Step 2: Run.** Expected: FAIL (no file).
- [ ] **Step 3: Write the generator and run it** (`python3 -I scripts/generate_patch_marks.py data/resources/chtdb.txt
  > data/resources/chtdb_patches.txt`). The file starts with a `;` header saying what it is and that it's generated.
  Read every generated line once by hand and add exclusions to the script for any cheat it caught; note the count.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** (`Patches: mark the database's widescreen, frame-rate and display-mode codes`), with the counts
  (widescreen, frame rate, display mode, choices) in the message.

### Task 4: The patch list

**Files:** Modify `src/core/patches.{h,cpp}`; Test `src/common-tests/patches_tests.cpp`.

**Interfaces — Produces:**
- `struct Patches::Choice { CheatCode code; Needs needs; };`
- `struct Patches::Patch { std::string group; std::string title; Kind kind; std::vector<Choice> choices; s32 saved =
  -1; s32 running = -1; }` (`title`: the code's name, or the choice title; `-1` is off).
- `struct Patches::Switch { std::string code; bool on; std::string value; }` (`code` is `Group\Name`, or `Name` without a
  group; `value` an option's name or a number, empty for none).
- `class Patches::PatchList`:
  - `static PatchList Build(std::vector<std::pair<CheatCode, Mark>> codes);` — codes in list order; codes sharing a
    non-empty `Mark::choice` become one patch in first-seen order; a code's `enabled` (its file's marker) is its
    default.
  - `size_t Count() const; const Patch& Get(size_t i) const;`
  - `void Restore(std::span<const Switch> switches);` — `saved` and `running` from defaults then switches (a choice
    patch with two codes on keeps the first); values restored by option name, or number in range.
  - `std::vector<Switch> Switches() const;` — differences from defaults only, in list order.
  - `bool NeedsRestart(size_t index, s32 choice) const;` — the running choice is on, differs, and doesn't restore.
  - `void Set(size_t index, s32 choice);` — `saved = choice`; when `!NeedsRestart`, also `running = choice`, calling
    the old code's `ApplyOnDisable()`.
  - `bool SetValue(size_t index, s32 choice, u32 value, Error* error);`
  - `void Restart();` — `running = saved` everywhere (a reset, a game change, a boot).
  - `Needs RunningNeeds() const;` — ratio from the first running patch with one, overclock the largest,
    widescreen rendering off if any asks.
  - `void ApplyFrame() const;` — the running choices' end-frame codes (`CanEnable()` only).

- [ ] **Step 1: Write the failing tests**: `BuildGroupsChoices` (Crash 2 JP's two `Select Widescreen Type` codes → one
  patch, two choices); `OnlyOneChoiceOn` (`Set(0, 1)` after `Set(0, 0)` → `saved == 1`, never both);
  `RestoringPatchSwitchesOffAtOnce` (`A7` code: `Set(i, -1)` → `running == -1`, no `NeedsRestart`); `PlainPatchWaits`
  (plain write: `NeedsRestart(i, -1)`; after `Set` `saved == -1`, `running == 0`; `Restart()` → `running == -1`);
  `SwitchingOnIsImmediate`; `SwitchesOnlyDifferences` (default-on code switched off → one `on=false` switch; switched
  back → none); `RestoreKeepsOldOnState` (a `.cht` marker `[*Widescreen 16-9]` with no switches → running choice 0);
  `RunningNeedsCombine` (Review Focus 4); `ValueRestoredByName`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** (`Patches: a per-game list with one choice per group and switching off that waits`).

### Task 5: Overrides and the switches' store

**Files:** Modify `src/core/patches.{h,cpp}`; Test `src/common-tests/patches_tests.cpp`.

**Interfaces — Produces:**
- `void Patches::PatchList::ApplyOverrides(Settings& settings);` — keeps, per need, the value it found before
  overriding; when a later load finds a different value there, the player changed it, and it stops overriding that
  setting until the patch is off. Ratio 16:9 → `DisplayAspectRatio::R16_9`, others → `Custom` with numerator and
  denominator; widescreen off → `gpu_widescreen_hack = false`; overclock → enable and `SetCPUOverclockPercent(max(
  player's, patch's))`, then `UpdateOverclockActive()`.
- `bool Patches::Allowed(const Settings& settings, bool hardcore);` — false in hardcore or with
  `disable_all_enhancements` (Decision 5).
- `std::vector<Patches::Switch> Patches::ReadSwitches(const SettingsInterface& si);` and
  `void Patches::WriteSwitches(SettingsInterface& si, std::span<const Switch> switches);` (`[Patches]` `On`/`Off`/
  `Value` lists; the section removed when empty).
- `bool Patches::SaveSwitchesForSerial(std::string_view serial, std::span<const Switch> switches, Error* error);` —
  under `System::GetGameSettingsFileMutex()`, loads the game's ini, writes, removes empty sections, deletes the file
  when empty.

- [ ] **Step 1: Write the failing tests**: `OverridesWidescreen` (Auto and widescreen hack on → 16:9, hack off);
  `Overrides16By10` (Custom 16:10); `OverclockNeverLowers` (player 200, patch 160 → 200); `HandChangeWins` (after
  apply, a load with the player's 4:3 → stays 4:3 until the patch is off); `OverridesNeverWrite` (a
  `MemorySettingsInterface` loaded into `Settings`, overrides applied, the interface's contents unchanged);
  `NotAllowedInHardcoreOrWithoutEnhancements`; `SwitchesRoundTripAwkwardNames` (Review Focus 1);
  `SaveSwitchesByteIdentical` (a temp game ini with other keys: switch on, then off → bytes equal to before; no ini
  before → none after).
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** (`Patches: game-only overrides, and switches kept in the game's settings`).

### Task 6: The running game (desktop core, regtest)

**Files:** Modify `src/core/system.{h,cpp}` (`LoadSettings` :1308-1331, `ResetSystem` :1573, `DestroySystem` :2131,
`FrameDone` :2203, `UpdateRunningGame` :4521-4525, cheat functions :5968-6125), `src/duckstation-regtest/
regtest_host.cpp` (arguments :567-715).

**Interfaces — Produces** (`system.h`):
- `Patches::PatchList* System::GetPatchList();` `void System::SetPatchList(std::unique_ptr<Patches::PatchList> list);`
  (applies settings when the needs change).
- `bool System::PatchNeedsReset(u32 index, s32 choice);`
- `void System::SetPatch(u32 index, s32 choice, bool reset_now);` — runtime only: `PatchList::Set`, then
  `ApplySettings(false)` if the running needs changed, then `ResetSystem()` and `Restart()` when `reset_now`; the
  caller keeps the switches.
- `bool System::SetPatchValue(u32 index, s32 choice, u32 value);`
- `bool System::LoadPatchList();` (desktop: database + `cheats/<Title>.cht` patches, switches from the game layer;
  nothing in hardcore or when not `Allowed`).
- Regtest: `-patch "<title>[=<choice>]"` (repeatable), `-patch-off <frame>`, `-patch-reset` (with `-patch-off`, reset
  instead of next start), a per-600-frame log line `Game frames: N in 600` (internal frame number deltas), and a log
  line after each settings change: `Patch overrides: aspect 16:9, widescreen rendering off, overclock 160%` (or
  `none`).

- [ ] **Step 1: The failing check**: regtest on Crash Warped with `-patch "Widescreen 16-9"` exits with "unknown
  argument". Expected now: fails.
- [ ] **Step 2: Implement.** Order in `UpdateRunningGame`: game settings layer → `LoadPatchList()` → `ApplySettings`
  → cheats. `LoadSettings` applies `PatchList::ApplyOverrides` after the game database, before
  `FixIncompatibleSettings`, when `Allowed`. `FrameDone` applies patches before cheats. `ResetSystem` and a game
  change call `Restart()`; `DestroySystem` drops the list. `LoadCheatList` and `LoadCheatListFromDatabase` classify,
  so the cheat list carries patches marked and skipped. Regtest `-patch` turns on `ApplyGameSettings`; once the
  serial is known (its `Host::OnGameChanged`) it writes the switches through `SaveSwitchesForSerial` into the dump
  folder's `gamesettings`, then `System::LoadPatchList()` and `ApplySettings`; `-patch-off` goes through
  `System::SetPatch` and `SaveSwitchesForSerial`, as the UIs do.
- [ ] **Step 3: Run** the regtest: Crash Warped with the patch → `Patch overrides: aspect 16:9, widescreen rendering
  off` and frames that differ from the baseline; `-patch-off 1800` with a plain-write patch → the overrides stay to
  the end; with `-patch-reset` → `none` after the reset; the dump folder's `gamesettings` ends as it began (absent). Without `-patch`: frames
  byte-identical to Task 0's baseline. With `-statefile` (Task 0's Warped state) and the 60 fps patch: overclock as the
  patch says, no switch changed (Review Focus 3). `common-tests` passes.
- [ ] **Step 4: Commit** (`System: patches run per game, with their overrides, apart from cheats`).

### Task 7: Qt

**Files:** Create `src/duckstation-qt/patchessettingswidget.{h,cpp}` (and in `CMakeLists.txt`, `.vcxproj`, `.filters`);
Modify `settingswindow.{h,cpp}:56-170` (page, per game only), `mainwindow.{cpp,ui}` (System › Patches…, beside
:1017-1110), `cheatmanagerwindow.cpp` (tree skips `patch` codes; Import reads `Format::INI`; values, Decision 6).

- [ ] **Step 1: Implement the page**: a tree by group; a plain patch is a checkbox, a choice patch a combo (Off and its
  choices), a code with options a second combo or a spin box; notes ("May need an overclock", ignored keys) as the
  row's tooltip and a muted line; Import (newer format or `.cht`) merges into `cheats/<Title>.cht` marking each entry
  `#patch`. Writes through the dialog's settings interface (`WriteSwitches`, then the dialog's save-and-reload); when
  the game is running with this serial, also `System::SetPatch` on the CPU thread, asking first ("Reset now" / "At next
  start" / Cancel) when `PatchNeedsReset`. Disabled with a line saying why in hardcore.
- [ ] **Step 2: Check by hand** on the Mac: Crash Warped (scratch copy of a library disc) — switch Widescreen on (16:9
  at once, Graphics page's aspect ratio unchanged in the ini), off (asks), "At next start" (stays 16:9), reset (4:3);
  Enable Cheats off → the cheat manager hidden, patches still work. Screenshots in the shared notes folder.
- [ ] **Step 3: Commit** (`Qt: a Patches page in game properties`).

### Task 8: Big Picture

**Files:** Modify `src/core/fullscreen_ui.cpp` (`SettingsPage` :198, `DoCheatsMenu` :1371-1411, pause menu :7138-7143,
translation strings at the end).

- [ ] **Step 1: Implement**: pause menu "Patches" (enabled with a serial and not hardcore, whatever "Enable Cheats" says)
  opening a choice dialog of patches; a choice patch opens a second dialog (Off and its choices), a code with options a
  value dialog; the reset question as a three-way dialog. Switches saved with `SaveSwitchesForSerial`. Per game:
  `SettingsPage::Patches` in game settings only, writing through the page's settings interface. "Cheat List" skips
  patches and offers values (Decision 6). Big Picture imports nothing today, so it gains no import (the design's
  "where it imports"); a file imported in Qt or copied into `cheats/` shows here.
- [ ] **Step 2: Check by hand** with the Qt build's Big Picture on the Mac, the same steps as Task 7's.
- [ ] **Step 3: Commit** (`Big Picture: patches from the pause menu and per game`).

### Task 9: Android native

**Files:** Modify `src/util/android_patch_codes.{h,cpp}` (`Switch` gains `std::string value`; fourth field),
`android/app/src/cpp/game_codes.{h,cpp}`, `android_host_states.cpp:413-460`, the JNI file that exports
`getPatchCodes`; Test `src/common-tests/android_patch_codes_tests.cpp`.

**Interfaces — Produces:**
- `AndroidPatchCodes::Listed` gains `std::string serial` (the disc's, for marks); `Parse`/`Format` keep three-field
  lines readable and write the fourth only with a value.
- `GameCodes::Patch { std::string group; std::string title; std::vector<std::string> choices; s32 saved; s32 running;
  std::vector<std::string> option_names; std::optional<std::pair<u32,u32>> range; u32 value; std::string note; }`;
  `std::vector<GameCodes::Patch> GameCodes::Patches();` `bool GameCodes::PatchNeedsReset(u32, s32);`
  `std::optional<std::string> GameCodes::SetPatch(u32 index, s32 choice, bool reset_now);`
  `std::optional<std::string> GameCodes::SetValue(bool patch, u32 index, s32 choice, u32 value);`
- JNI (`NativeLibrary.kt`): `getPatches(): Array<Patch>?`, `patchNeedsReset(index, choice)`, `setPatch(index, choice,
  resetNow): String?`, `setCodeValue(patch, index, choice, value): String?`; `getPatchCodes` and friends now list cheats
  only.

- [ ] **Step 1: Write the failing tests**: `FourthFieldRoundTrip`, `ThreeFieldLinesStillRead`, `AwkwardNames` (Review
  Focus 1), `EachDiscClassifiedBySerial` (Review Focus 5, through the classification `Combine` now passes on).
- [ ] **Step 2: Run** `--gtest_filter='AndroidPatchCodes*'`. Expected: FAIL.
- [ ] **Step 3: Implement**: `Load` splits the combined list by `Patches::Classify` with each code's disc serial (the
  player's codes with the game key); patches to `System::SetPatchList`, cheats as today; the `.patchcodes` file keeps
  both. Hardcore drops both. Player files also read from `cheats/<serial>.cht` for each disc (the newer format's
  naming).
- [ ] **Step 4: Run** the tests, `./gradlew assembleDebug`. Expected: PASS, builds.
- [ ] **Step 5: Commit** (`Android: patches apart from cheats in the core, kept in .patchcodes`).

### Task 10: Android panel

**Files:** Modify `android/app/src/main/java/io/github/ilyalissoboi/goosestation/core/{PatchCode,NativeLibrary}.kt`,
`app/{GameCoreApi,PatchCodesModel,PatchCodeList,InGameMenu}.kt`, `ui/{PatchCodePanel,GameMenu}.kt`; Tests
`android/app/src/test/.../app/{PatchCodesModelTest,PatchCodeListTest,InGameMenuTest,FakeGameCore}.kt`,
`android/app/src/androidTest/.../ui/{GameMenuTest,FocusAuditTest,OrientationLayoutTest}.kt`.

- [ ] **Step 1: Write the failing unit tests**: `PatchCodesModelTest` — the panel opens on the Patches tab; a choice row
  sets one choice; switching off a waiting patch shows the question, "At next start" keeps it shown as "Off at next
  start", "Reset now" calls `setPatch(…, true)`; a refusal puts the row back; values. `InGameMenuTest` — the row is
  "Patches & cheats", off in hardcore.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest`. Expected: FAIL.
- [ ] **Step 3: Implement**: `PanelTabs` Patches | Cheats; a plain patch a `SwitchRow`, a choice patch and a code with
  options a `CyclerRow`/`ChoiceList` row; the question with `Question` (B gives "At next start", the safe answer);
  empty Patches tab: "No patches for this game."
- [ ] **Step 4: Run** unit tests, `ktlintCheck`, `checkNoWindowDialogs`, and the quick tier from a detached worktree
  (`GameMenuTest`, `FocusAuditTest`, `OrientationLayoutTest` updated). Expected: PASS.
- [ ] **Step 5: Commit** (`Android: the panel splits into Patches and Cheats`).

### Task 11: Overclocks and the library check

**Files:** Modify `scripts/generate_patch_marks.py` (measured table), `data/resources/chtdb_patches.txt`.

- [ ] **Step 1: Measure** on `pcsx2-win` with the branch's regtest, from Task 0's scenes: each frame-rate patch at
  overclocks 100, 110, … 300; the lowest where every `Game frames` line reads at least 594 of 600 (99%; 297 for the 30
  fps uncap), across 3600 frames. Record the table in the shared notes folder.
- [ ] **Step 2: Write** the measured overclocks into the generator's table, regenerate, run `Patches*` tests.
- [ ] **Step 3: Library check** (Mac Metal and Windows D3D11): each game with its widescreen patch on → the log says
  16:9 (16:10 for Arc the Lad) with widescreen rendering off, and the frames differ from the patch-off baseline; one
  frame per game looked at beside the baseline for double widening (round things stay round); each frame-rate patch
  holds its rate at the measured overclock; patch-off runs equal the baseline. Results in the notes folder; anything
  wrong goes back to the marks. How it looks in play stays the user's check.
- [ ] **Step 4: Commit** (`Patches: measured overclocks for the library's frame-rate patches`).

### Task 12: Imports, the emulators and the Thor

- [ ] **Step 1: Newer-format check**: PeterDelta's Xenogears and Chrono Cross files fetched on the test machines only
  (scratch, never committed), imported in Qt and dropped into the Thor's `/sdcard/GooseTest/cheats/` as
  `<serial>.cht`: they load, show their options, and apply (regtest and the Thor).
- [ ] **Step 2: Android full tier** from a detached worktree (copy the emusync AAR and `local.properties`; core tests
  with the orchestrator; read the XMLs); native tests on the emulator with `ANDROID_SERIAL=emulator-5580`.
- [ ] **Step 3: The Thor** (`.debug` app, `/sdcard/GooseTest` scratch data with Crash Warped copied in): an
  instrumented `.debug` test switches Widescreen on and off (both answers) and the 60 fps patch on; checks the
  `.patchcodes` and game settings files byte-identical after off, and the overclock gone after the game ends. Then
  uninstall `.debug.test`, remove `/sdcard/GooseTest`.

### Task 13: Docs, roadmap, the PR

**Files:** `docs/roadmap.md` (item 28), `docs/android.md` (Patch codes section :1247-1262), `docs/patches.md` (new:
patches, the marks file, the newer format as read, where on/off is kept), `CONTRIBUTORS.md` if the marks file needs a
line.

- [ ] **Step 1: Docs**: item 28 reworded to what was built (counts, the measured overclocks, what's import-only);
  `android.md` and `patches.md` as above.
- [ ] **Step 2: Every tier**: `common-tests` (Mac, Windows), `./gradlew assembleDebug assembleDebugAndroidTest
  testDebugUnitTest ktlintCheck checkNoWindowDialogs`, the full tier's XMLs, regtest frames.
- [ ] **Step 3: Commit** (`Docs: roadmap item 28 done, patches in the user docs`), clean up scratch worktrees and
  `/tmp/goose-patches`.
- [ ] **Step 4: Push and open the PR** with each success check and its result; then `superpowers:requesting-code-review`.
  Playing with patches and judging how they look are the user's checks.
