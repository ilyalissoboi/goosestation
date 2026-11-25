<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Faster loading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Loads are clearly shorter at the defaults: a Maximum read speed and fuller instant seeks. Reads drop to normal
speed while XA, CD-DA or video streams. Fast-forward while loading is a setting, off by default. Game-database switches
cover the games that break. The new defaults are on Qt, Big Picture and Android.

**Architecture:**
- **The timing becomes pure functions.** They move into a new `cdrom_timing.h/.cpp` (namespace `CDROMTiming`), so
  common-tests can pin them without a booted system. `cdrom.cpp` calls them with its state.
- **Maximum is paced by the game.** `ReadSpeedup` 0 means Maximum (as `SeekSpeedup` 0 means instant). Sectors are
  scheduled at normal double speed. Once the game has taken the sector just delivered (its buffer read to the end and
  INT1 acknowledged), the next one is brought forward to `MAX_READ_FLOOR_TICKS` from then. So data comes as fast as
  the game takes it, never faster. A game that doesn't take a sector gets today's speed, so Maximum can't add dropped
  sectors.
- **Streaming is judged per sector.** A small `CDROMTiming::StreamDetector` takes signs from the drive and the console:
  - the CD-DA or XA mode bits (today's rule);
  - the drive playing;
  - an XA-ADPCM sector;
  - a data sector whose subheader has the real-time or video bit;
  - the MDEC starting a decode.

  Reads count as streaming until `STREAM_HOLD_SECONDS` after the last sign. The read interval is worked out again
  after every sector and when the settings change. Today that happens only at the start of a read.
- **Fuller instant seeks** are a setting of their own, `[CDROM] ShortDriveWaits`, which applies only with instant
  seeks. It shortens the speed change, the TOC read, spin-up and stop by `SHORT_WAIT_DIVISOR`. With instant seeks,
  short forward seeks keep their read-paced timing (Transformers – Beast Wars Transmetals); today, instant drops it.
  Whether `ShortDriveWaits` is on by default follows the compatibility pass (Task 8).
- **Fast-forward while loading** is a rule (`CDROMTiming::LoadFastForward`) checked in `System::FrameDone`. It holds a
  flag of its own, which `UpdateSpeedLimiterState` treats like fast-forward. The player's own fast-forward and turbo
  keep working as before.

**Tech Stack:** C++20 (`src/core`), gtest (`src/common-tests`, also `run-native-tests.sh` on an emulator), Qt 6,
Dear ImGui (Big Picture), Kotlin/Compose (Android), `goosestation-regtest` (the Mac and Windows), an instrumented
`.debug` test on the Thor (scratch, never committed).

**Spec:** `docs/superpowers/specs/2026-11-25-faster-loading-design.md`.

## Decisions (made with the user on 2026-11-25: 1 A, 2 A, native execution)

1. **How Maximum reads are timed.** The design leaves it to the builder.
   - **A. Paced by the game (recommended).** The next sector comes once the game has taken the last one, with a
     floor and today's speed as the ceiling.
     - *Pros:* as fast as each game's handler allows. A sector is never dropped because of the speedup, so a game
       whose handler is slow can't lose data.
     - *Cons:* two hooks (the buffer read to its end, the INT1 acknowledge). A game that takes only part of each
       sector gets no speedup.
   - **B. A fixed tick floor.** Maximum is a large multiplier, such as 32×, through today's formula.
     - *Pros:* one line, and the same mechanism as 2–10× today.
     - *Cons:* a game whose handler takes longer than the floor drops sectors mid-load. That is anti-goal 1 (a hang)
       and exactly what the Rebel Assault II note describes.
2. **Fast-forward while loading in hardcore.** The design keeps it out of hardcore because "hardcore doesn't allow
   fast-forward". That isn't so: RetroAchievements' FAQ says "the only exception to altering speed is fast-forward,
   which is allowed". This build lets hardcore fast-forward too, and only stops speeds below 1×
   (`settings.cpp:859-866`).
   - **A. Keep it out of hardcore (recommended).** This is what the design agreed. It is the safer reading of
     anti-goal 3, and the player can still fast-forward by hand.
   - **B. Allow it in hardcore**, like hand fast-forward.

## Found before planning (2026-11-25)

- **Vagrant Story's full-BIOS boot is a region mismatch, not CD timing.** Mac regtest, 6,000 frames, fast boot off:
  - With `ps-30j.bin` (SCPH-5500, Japanese), the BIOS rejects the US disc and sits at its own main menu (memory card
    and CD player) from frame 900 on.
  - With the US BIOS (SCPH-1001), it goes through the BIOS logo, Now Loading, Squaresoft and the opening movie (by
    frame 3,000) to the title.

  Nothing to fix here. The Thor's full-BIOS runs use the US BIOS from now on.
- **RetroAchievements and load speedups.** Their FAQ lists what hardcore forbids: save-state loads, cheats, rewind,
  slow motion and frame advance. It says nothing about load speed, so the speedups stay allowed in hardcore, as today.
- **The library** has 84 discs (Final Fantasy VII isn't among them). Crash Bandicoot, which streams level data while
  you play, is one of them: it matters for fast-forward while loading.

## Global Constraints

- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7`. Its commit titles and user documentation only. The
  database switches start only from our database's notes and our own testing.
- **Anti-goals** (at the new defaults):
  - a game that worked before hanging, crashing or misbehaving;
  - movies or CD music out of time, or dropping more sectors than today;
  - RetroAchievements hardcore refusing or flagging a session.
- **One stage, one PR.** `docs/roadmap.md` item 25 and `docs/gamedb.md` are updated in it.
- **Medal of Honor – Underground and Rally Cross 2 get no switch.**
- **Fast boot's defaults are unchanged:** on for Android, off for desktop. Fast-forward while loading is off by
  default.
- **Thor:** adb `64dc3c35`, the `.debug` app and `/sdcard/GooseTest` only. Never the release app or
  `/sdcard/GooseStation/data`. Clean up afterwards, including the design's scratch data. The measurement test stays
  out of the repo.
- **Android tiers** run from a detached worktree: quick while building, full before the PR. `run-native-tests.sh`
  needs `ANDROID_SERIAL=emulator-5580` and `ANDROID_NDK_HOME`. Before pushing, run CI's Gradle line:
  `assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`.
- **Windows:** `pcsx2-win`, a scratch worktree and regtest. The user's discs are read in place from
  `F:\games\emulated\psx\` and never copied anywhere.
- **Commits:** one per task, the message shown after each, ending
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Fixed values (the builder's calls; retuned only by measurement)

| Name (`CDROMTiming`) | Value | Why |
|---|---|---|
| `READ_SPEEDUP_MAXIMUM` | 0 | Mirrors `SeekSpeedup` 0 = instant. No UI offers 0 for reads today, so no file has it on purpose. |
| `MAX_READ_FLOOR_TICKS` | `MASTER_CLOCK / (150 * 32)` (7,056, 0.21 ms) | Gives the game's handler time to return before the next INT1. It also caps Maximum at 32×, which the async reader keeps up with (the design saw 0.1 s of waits in all at 10×). |
| `STREAM_HOLD_SECONDS` | 0.5 | Spans the gaps between a movie's interleaved signs (XA every 8 sectors, an MDEC decode at least every 1/15 s). It is short enough that a load right after a movie is sped up. |
| `SHORT_WAIT_DIVISOR` | 10 | A speed change becomes 60–70 ms, the TOC read 50 ms and spin-up 0.1 s. That keeps their order (each still longer than a seek's floor). The compatibility pass decides. |
| `LOAD_FF_HOLD_SECONDS` | 0.25 (emulated) | Fast-forward stays on between a load's bursts, and stops a quarter-second after the drive goes quiet. |

## Interfaces

```cpp
// src/core/cdrom_timing.h
namespace CDROMTiming {
struct ReadPace { TickCount interval; bool paced_by_game; };
ReadPace GetReadPace(u32 read_speedup, bool double_speed, bool streaming, TickCount ticks_per_second);
TickCount GetSeekTicks(const SeekParams& p);   // the model now in GetTicksForSeek, without the side effects
TickCount GetFixedWaitTicks(TickCount normal_ticks, u32 seek_speedup, bool short_drive_waits);
class StreamDetector { public: void Reset(GlobalTicks now); void NoteSign(GlobalTicks now);
                       bool IsStreaming(GlobalTicks now, bool mode_bits, TickCount ticks_per_second) const; };
class LoadFastForward { public: bool Update(bool allowed, bool cd_loading, GlobalTicks now, TickCount tps);
                        void Reset(); bool IsActive() const; };
}
// src/core/cdrom.h
struct CDROM::Counters { u32 data_fast, data_normal, xa, cdda, dropped, seeks; };
CDROM::Counters CDROM::TakeCounters();  // read and reset, once a second
bool CDROM::IsLoadingData();            // seeking, or reading data while not streaming
void CDROM::SpeedupSettingsChanged();   // recomputes the read interval now
// src/core/mdec.h
GlobalTicks MDEC::GetLastDecodeStartTick();
```

Settings: `[CDROM] ReadSpeedup` 0 = Maximum; new `[CDROM] ShortDriveWaits` (bool) and `[CDROM] FastForwardLoading`
(bool, default false). Traits: `DisableCDROMReadSpeedup`, `DisableCDROMSeekSpeedup`, `DisableFastBoot`.

## Review Focus

1. **A load right after a movie or CD track** must speed up again within `STREAM_HOLD_SECONDS`. A movie must never
   start at Maximum for more than its first sign. Pinned: `CDROMTiming.StreamHoldEndsAfterLastSign` (Task 4), and
   the per-second counters across Vagrant Story's movie (Task 9).
2. **A game that takes only part of each sector, or never acknowledges INT1**, must get today's speed, never a stall.
   Pinned: `CDROMTiming.MaximumCeilingIsNormalSpeed` (Task 3). The compatibility pass counts dropped sectors in loads
   (Task 8).
3. **A save state loaded mid-read or mid-movie:** no crash, and streaming is assumed for the hold so a movie can't
   run at Maximum. The save-state version is unchanged, since no new state is saved. Pinned:
   `CDROMTiming.ResetCountsAsStreaming` (Task 4), plus a Mac regtest from a state made mid-movie (Task 4, Step 5).
4. **The player's own fast-forward or turbo while fast-forward while loading is on:** neither switches the other off.
   The rule's flag is dropped on pause, shutdown and hardcore. Pinned: `CDROMTiming.LoadFastForwardHoldsAndReleases`
   and `LoadFastForwardNotAllowed` (Task 6), and a check of `UpdateSpeedLimiterState`'s target with both on.
5. **Existing settings files:** a value the player chose (2–10×, or a non-instant seek speed) survives the migration.
   Only the old defaults move, once. Pinned: `CDROMSettings.LoadingDefaultsMoveOnce` and `KeepsChosenSpeeds`
   (Task 9), and Android's `SettingsSetupTest` case.

---

### Task 1: Before-numbers (scratch, no commit)

**Files:** none in the repo. Scratch: `/tmp/goose-load` (master plus the design's counters, and `LoadMeasureTest`);
`/tmp/goose-load-parse.py`. Notes: `faster-loading-baseline.md` in the shared notes folder.

- [ ] **Step 1: Thor.** Copy the US BIOS into `/sdcard/GooseTest/bios`, with `LoadMeasureTest` pointed at it. Then
  extend the test with a menu load: once the boot's movie is skipped with Start, press Start on the title and time
  from the press to the end of the CD's burst (no data sector for 1 s). Run today's defaults, fast boot on and off,
  two rounds.
- [ ] **Step 2: Mac and Windows.** Regtest on Vagrant Story, at today's defaults with fast boot on and off and
  `Logging/LogLevel=Verbose`. Record the frame of the first XA sector, the data sectors before it, and the dropped
  sectors per second of movie. Also dump every frame through the movie, for Task 9's frame-by-frame comparison.

### Task 2: CD counters in the per-second statistics

TDD skipped: counters and a format string. Their values are checked against the design's scratch counters on the
same Vagrant Story run.

**Files:** `src/core/cdrom.h/.cpp` (`ProcessDataSector` 3100, `ProcessXAADPCMSector` 3407, `ProcessCDDASector`
3525, `CompleteSeek`); `src/core/system.cpp` (`UpdatePerformanceCounters`, the VERBOSE line).

**Interfaces:** produces `CDROM::Counters` and `CDROM::TakeCounters()`.

- [ ] **Step 1:** Count as the scratch diff does: data sectors at a sped-up interval and at the normal one, XA, CD-DA,
  dropped sectors and seeks.
- [ ] **Step 2:** The per-second line gains a tail after the audio fields, so the fields before it keep their places:
  `CD: D 12/0 X 0 A 0 drop 0 S 1` (sped-up/normal data, XA, CD-DA, dropped, seeks).
- [ ] **Step 3:** Mac regtest on Vagrant Story at today's defaults. The totals match Task 1's to the sector.
- [ ] **Step 4: Commit** (`CD-ROM: count sectors and seeks in the per-second statistics`).

### Task 3: The timing as pure functions, Maximum reads and fuller instant seeks

**Files:**
- Create: `src/core/cdrom_timing.h/.cpp`, `src/common-tests/cdrom_timing_tests.cpp`.
- Modify:
  - `src/core/cdrom.cpp`: `GetTicksForRead` 1432, `GetTicksForSeek` 1442, `GetTicksForStop` 1528,
    `GetTicksForSpeedChange` 1533, `GetTicksForTOCRead` 1540, `GetTicksForSpinUp` 1417;
    `CheckForSectorBufferReadComplete` 3617; the interrupt-flag write 1132.
  - `src/core/settings.h/.cpp`: `ShortDriveWaits` (default false here; Task 9 sets the default).
  - The CMake and vcxproj lists for core and common-tests.

**Interfaces:** produces `GetReadPace`, `GetSeekTicks`, `GetFixedWaitTicks`, `READ_SPEEDUP_MAXIMUM`,
`MAX_READ_FLOOR_TICKS`, `SHORT_WAIT_DIVISOR`, and `g_settings.cdrom_short_drive_waits`.

- [ ] **Step 1: Failing tests** (`CDROMTiming.*`, with `tps = MASTER_CLOCK`):
  - `ReadSpeedsAsToday`: speedup 1 gives `tps/150` at double speed and `tps/75` at single. Speedup 4 gives
    `tps/600` at double speed and normal at single. Streaming gives normal at any speedup.
  - `MaximumCeilingIsNormalSpeed`: Maximum gives `{tps/150, paced_by_game = true}` at double speed, and
    `{tps/75, false}` at single speed or while streaming.
  - `InstantSeekKeepsShortForwardTiming`: instant, from LBA 1000 to 1004 at double speed, gives `4 * tps/150`. From
    1000 to 50,000, or backwards by 3, it gives 30,000.
  - `SeekModelUnchanged`: at speedup 1 and 4, `GetSeekTicks` matches today's numbers for a table of (current,
    target, motor on/off, speed) cases. Generate the table from today's code before refactoring.
  - `FixedWaitsShortenOnlyWithInstantSeeks`: `GetFixedWaitTicks(0.6 s, 0, true)` is 0.06 s. It is unchanged with
    `(…, 1, true)` and with `(…, 0, false)`.
- [ ] **Step 2: Run** `build2/bin/common-tests --gtest_filter='CDROMTiming.*'`. Expected: build failure (no
  `cdrom_timing.h`).
- [ ] **Step 3: Implement.**
  - Move the seek model into `GetSeekTicks`, with `cdrom.cpp` keeping the position updates and the drive-state reads.
  - The four fixed waits go through `GetFixedWaitTicks`; `GetTicksForStop`'s motor-off 7,000 stays as it is.
  - Add `MaybeHurryNextSector()` in `cdrom.cpp`, called at the end of `CheckForSectorBufferReadComplete` and after
    an interrupt-flag write that clears everything. When all of these hold, it reschedules `s_drive_event` to
    `MAX_READ_FLOOR_TICKS` if that is sooner:
    - the drive is reading;
    - the pace is `paced_by_game`;
    - the buffer just written has been read to its end;
    - no async interrupt is pending.

    The period stays the ceiling.
- [ ] **Step 4: Run** the filter and all of common-tests. Then a Mac regtest on Vagrant Story at `ReadSpeedup=0`,
  `SeekSpeedup=0`, fast boot on. Expected: PASS. In the regtest, the first XA comes at an earlier frame than Task 1's,
  and the loads before the movie drop no sectors.
- [ ] **Step 5: Commit** (`CD-ROM: a Maximum read speed paced by the game, and fuller instant seeks`).

### Task 4: No speedup while XA, CD-DA or video streams

**Files:** `src/core/cdrom_timing.h/.cpp`; `src/core/cdrom.cpp` (`DoSectorRead` 3007, `ProcessDataSector`,
`ProcessXAADPCMSector`, `Reset`, `DoState`, `CPUClockChanged` 978); `src/core/mdec.h/.cpp` (`Execute` 410);
`src/core/system.cpp` (the settings-change block at 4820); tests.

**Interfaces:**
- Consumes `GetReadPace` (Task 3).
- Produces `StreamDetector`, `STREAM_HOLD_SECONDS`, `MDEC::GetLastDecodeStartTick()` and
  `CDROM::SpeedupSettingsChanged()`.

- [ ] **Step 1: Failing tests:**
  - `StreamHoldEndsAfterLastSign`: a sign at t, then streaming at t + 0.49 s and not at t + 0.51 s.
  - `ModeBitsAlwaysStream`: with the mode bits set, it streams with no sign ever noted.
  - `ResetCountsAsStreaming`: after `Reset(now)`, streaming until now + 0.5 s.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - A sign is noted for each of these: an XA-ADPCM sector; a data sector whose subheader has `realtime` or `video`
    set; the drive in `Playing`; `MDEC::GetLastDecodeStartTick()` newer than the last check.
  - Mode bits means `s_mode.cdda || s_mode.xa_enable`.
  - After each sector, `DoSectorRead` works out the pace again, and calls `SetInterval` (and `Schedule` for the next
    sector) only when it changes.
  - `Reset` and a state load call `Reset(now)`. `SpeedupSettingsChanged` (from `System`, when either speedup or
    `ShortDriveWaits` changes) and `CPUClockChanged` recompute the interval at once.
- [ ] **Step 4: Run** the filter and common-tests, then the Mac regtest on Vagrant Story at Maximum and instant.
  Expected: PASS. Through the movie the counters show `D n/0` turning to `0/n`, and the dropped sectors per second
  of movie are no higher than Task 1's (~19 on the Thor; whatever the Mac's baseline is).
- [ ] **Step 5:** Save a state mid-movie in the regtest (`-statefile`), and run from it. No crash, and the counters
  stay at normal speed through the rest of the movie.
- [ ] **Step 6: Commit** (`CD-ROM: no read speedup while XA, CD audio or video streams`).

### Task 5: Game-database switches

**Files:** `src/core/game_database.h` (Trait enum 29-58), `src/core/game_database.cpp` (`s_trait_names` 65,
`s_trait_display_names` 95, `ApplySettings` 386, cache version 40); `data/resources/gamedb.yaml` (SCUS-94960 at
123424, SCUS-94230 at 137733); `src/duckstation-qt/consolesettingswidget.cpp` and `biossettingswidget.cpp`
(`SetAvailability` from `hasGameTrait`); `src/common-tests/game_database_tests.cpp`.

**Interfaces:** produces the traits `DisableCDROMReadSpeedup`, `DisableCDROMSeekSpeedup` and `DisableFastBoot`.

- [ ] **Step 1: Failing tests:**
  - `CDSpeedTraitsApply`: an entry with all three, applied to settings at Maximum, instant and fast boot, gives read
    1, seek 1 and fast boot off. With OSD messages on, one compatibility message names all three.
  - `ShippedCDSpeedEntries`: the shipped yaml gives SCUS-94960 `DisableCDROMReadSpeedup` and
    `DisableCDROMSeekSpeedup`, and SCUS-94230 `DisableFastBoot`.
  - `NoSwitchForBIOSLoopGames`: the Medal of Honor – Underground and Rally Cross 2 entries have none of the three.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** Follow the pattern of the existing traits:
  - the OSD lines are "CD-ROM read speedup disabled.", "CD-ROM seek speedup disabled." and "Fast boot disabled.";
  - bump `GAME_DATABASE_CACHE_VERSION` to 15;
  - the yaml entries keep their comments;
  - Qt greys out the three rows when a game's trait applies.
- [ ] **Step 4: Run** the filter and common-tests. Expected: PASS.
- [ ] **Step 5: Commit** (`Game database: switches that turn CD speedups and fast boot off`).

### Task 6: Fast-forward while loading

**Files:** `src/core/cdrom_timing.h/.cpp`; `src/core/cdrom.h/.cpp` (`IsLoadingData`); `src/core/settings.h/.cpp`
(`FastForwardLoading`, and the hardcore block at 859 (hardcore, decision 2)); `src/core/system.cpp` (`FrameDone` 2173,
`UpdateSpeedLimiterState` 3612, the reset at 1949, pause); tests.

**Interfaces:**
- Consumes `StreamDetector` (Task 4).
- Produces `LoadFastForward`, `LOAD_FF_HOLD_SECONDS`, `g_settings.fast_forward_loading` and
  `System::IsLoadFastForwardActive()` (for the OSD).

- [ ] **Step 1: Failing tests:**
  - `LoadFastForwardHoldsAndReleases`: loading at t turns it on. It stays on through a 0.2 s gap, and is off 0.26 s
    after the last loading.
  - `LoadFastForwardNotAllowed`: with `allowed` false, it is never on, and turning `allowed` off while it's on turns
    it off at once.
  - `FastForwardLoadingDefaultsOff`: a fresh `Settings` has it off. Hardcore forces it off (decision 2).
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - `IsLoadingData()` is a seek in progress, or reading data while not streaming.
  - Each `FrameDone` calls `Update(setting && !hardcore, CDROM::IsLoadingData(), now, tps)`. On a change, it calls
    `UpdateSpeedLimiterState`. Its target speed is the fast-forward speed when the rule is on and neither the
    player's fast-forward nor turbo is.
  - Turning it off empties the SPU buffer, as `SetFastForwardEnabled(false)` does. The audio fade is item 24's.
  - Pause, shutdown and a settings change that disallows it reset the rule.
- [ ] **Step 4: Run** the filter and common-tests. Then a Mac regtest on Vagrant Story with it on, at a throttled
  speed (`Main/EmulationSpeed=1`, `FastForwardSpeed=0`). The boot reaches the first XA in less wall time than with
  it off, and the log shows it off through the movie.
- [ ] **Step 5: Commit** (`Core: fast-forward while a game is loading, off by default`).

### Task 7: The rows on Qt, Big Picture and Android, and the statistics

TDD for Android's settings model; the Qt and ImGui rows are bindings, checked by hand on the Mac.

**Files:**
- Qt: `consolesettingswidget.ui` 173 and 234, and `consolesettingswidget.cpp` 70-71 and 100-106. "Maximum" goes at
  index 0, so the read combo binds with value = index, as the seek combo does.
- Big Picture: `fullscreen_ui.cpp` 3440-3459 and 3517-3526 ("Maximum" first, option offset 0).
- Core: `imgui_overlays.cpp` 439 (`CDR=Max`); `system.cpp` `WarnAboutUnsafeSettings` 4986-5000 (the CD lines go).
- Android: `settings/ConsoleSettings.kt` 20-21 and 47-60, `ui/ConsoleScreen.kt` 75-90 (rows for Maximum, short
  drive waits and fast-forward while loading, the last hidden in hardcore (decision 2)), and tests in
  `ConsoleSettingsTest.kt` and `ConsoleScreenTest.kt`.

- [ ] **Step 1: Failing Android tests:**
  - `READ_SPEEDS` starts with 0 "Maximum".
  - the two new bools load and save under `[CDROM]`;
  - the screen shows the three rows, in its D-pad order.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest` and the `ConsoleScreenTest` class on an emulator. Expected:
  FAIL.
- [ ] **Step 3: Implement** all three UIs, with help text for each new row:
  - **Maximum:** "Reads data as fast as the game takes it. Movies and CD audio play at normal speed."
  - **Short drive waits:** "With instant seeks, also shortens spin-up, speed changes and the table-of-contents
    read."
  - **Fast-forward while loading:** "Runs at the fast-forward speed while the disc loads data, not while movies or
    music play. Games that stream data while you play will speed up too."
- [ ] **Step 4: Run** the Android tests and the quick tier (from a detached worktree). Check the Qt and Big Picture
  rows on the Mac. Expected: PASS.
- [ ] **Step 5: Commit** (`Settings: Maximum read speed, short drive waits and fast-forward while loading on every
  UI`).

### Task 8: The compatibility pass (Windows; results in the notes, no commit)

**Files:** scratch only. The branch's regtest is built on `pcsx2-win` in a scratch worktree. A PowerShell driver
and a Mac-side summariser live in `/tmp/fl-scripts`.

- [ ] **Step 1:** For each of the 84 discs, four runs in place from `F:\games\emulated\psx\`:
  - old defaults (1, 1, no short waits) and new (Maximum, instant, short waits on);
  - each with fast boot on and off, using `SCPH1001.BIN`;
  - 9,000 frames each, a dump every 300, `Main/ApplyGameSettings=true` so the switches apply, and the verbose
    per-second counters.
- [ ] **Step 2:** Flag a run when any of these holds:
  - its last 10 dumps are identical and the old run's aren't (a hang);
  - its last frame differs from the old run's and it reached the movie (XA, CD-DA or an MDEC sign) later or never;
  - it dropped sectors outside streaming;
  - the movie's dropped sectors per second are higher than the old run's.

  Look at each flagged pair's contact sheet.
- [ ] **Step 3:** For each failure, rerun with `ShortDriveWaits=false`. A failure that goes away with that keeps short
  waits out of the defaults. A failure that doesn't gets a database switch (Task 5's pattern, as a follow-up commit
  in Task 9).
- [ ] **Step 4:** Write the results table (disc × runs, flags, cause) in the notes.

### Task 9: The new defaults, on every platform and in existing files

**Files:**
- Core: `src/core/settings.h/.cpp` (defaults: read `READ_SPEEDUP_MAXIMUM`, seek 0, `ShortDriveWaits` per Task 8;
  `ApplyLoadingDefaults(SettingsInterface&)` next to `ApplyDesktopPacingDefaults` at 1525).
- Desktop: `src/duckstation-qt/qthost.cpp` `MigrateSettings` 709.
- Android: `android/app/src/cpp/android_host.cpp` `InitializeConfig` 264 (`[Android] LoadingDefaultsVersion`), and
  `ConsoleSettings.kt` defaults (mirroring the core's).
- Data: any switches Task 8 found go in `gamedb.yaml`.
- Tests: `src/common-tests/settings_cdrom_tests.cpp`, `SettingsSetupTest.kt`.

- [ ] **Step 1: Failing tests:**
  - `CDROMSettings.NewDefaults`: a fresh `Settings` has read 0, seek 0 and short waits per Task 8.
  - `LoadingDefaultsMoveOnce`: a file with read 1, seek 1 and no version becomes 0/0 with version 1. Run again,
    after the user sets 1/1, it stays 1/1.
  - `KeepsChosenSpeeds`: a file at read 4 and seek 2 keeps 4 and 2, and gets the version.
  - `SettingsSetupTest`: the same on Android, through `InitializeConfig`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** `[Main] LoadingDefaultsVersion` (desktop) and `[Android] LoadingDefaultsVersion` move
  only values still at the old default 1. Game ini files are left alone.
- [ ] **Step 4: Run** common-tests, the Android unit tests and `SettingsSetupTest`. Expected: PASS.
- [ ] **Step 5: Commit** (`Settings: Maximum reads and instant seeks by default`).

### Task 10: After-numbers, review and the tiers

- [ ] **Step 1: Thor.** Task 1's runs at the new defaults, interleaved with the master build, two rounds. Then once
  more with fast-forward while loading on. The design's Success, on the Thor:
  - Vagrant Story's switch-on to the movie at least 25% shorter than 30.6 s, and the menu load shorter;
  - dropped sectors per second of movie at no more than ~19, and XA sectors per second unchanged;
  - with fast-forward while loading on, shorter again, with sound back after (no `M` mutes left, audio counters
    clean).
- [ ] **Step 2: Mac and Windows.** Task 1's regtest runs at the new defaults: the same success numbers in frames.
  The movie's frames are compared with Task 1's dumps: every before-frame's hash appears in order in the after-run,
  allowing only the frames Task 1 itself lacks.
- [ ] **Step 3:** The final whole-branch review (a fresh reviewer), its fix pass, then:
  - common-tests, the Mac regtest smoke run and `run-native-tests.sh`;
  - CI's Gradle line;
  - the full tier from a detached worktree.
- [ ] **Step 4:** Clean up:
  - the Thor: the test APK, the `.debug` app's test data, all of `/sdcard/GooseTest`, including the design's data;
  - Windows: the scratch worktree and junctions;
  - the Mac: `/tmp/goose-load` and its worktree entry, `/tmp/fl-*` and the copied US BIOS.

### Task 11: Docs and PR

- [ ] **Step 1:** `docs/roadmap.md` item 25: done, with the numbers and the Vagrant Story finding. `docs/gamedb.md`:
  the three traits, and how to add one from testing (the counters to read, and the regtest pair to run).
- [ ] **Step 2: Commit** (`Docs: faster loading on the roadmap and in the game database guide`), push, and open the
  PR. The body has:
  - the before-and-after numbers;
  - the compatibility pass's table and what it decided for short waits;
  - the two decisions;
  - the user's checks: watching movies, and playing their games.
