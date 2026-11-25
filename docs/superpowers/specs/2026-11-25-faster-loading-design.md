<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Faster loading — design

Roadmap item 25 (faster loading, from the upstream gaps players notice most, `docs/upstream-features.md` tier 5).
Agreed in conversation on 2026-11-25. Calls made while writing this up, not yet confirmed, are marked *(writer's
call)*; calls the builder may make are marked *(builder's call)*.

## Intent

The player's picture: **waiting through loads in ordinary play** (switching on, the disc starting, area transitions,
menus) *(the player's choice)*. Every CD speedup is off by default today, and the ones there are partial. This makes
**loads clearly shorter at the defaults, with every game still behaving and movies and music in time** *(the
player's choice)*, with all three parts of the roadmap item *(the player's choice)*: a maximum read speed, no speedup
while video or CD audio streams, and fast-forward while a game is loading; on **desktop (Qt and Big Picture) and
Android** *(the player's choice)*.

## Measured on the Thor (2026-11-25)

Vagrant Story (USA), a cold boot to its opening movie, `.debug` build at `cc3b7a9c0`, Vulkan, offscreen surface,
Android defaults (fast boot on) except as named; per-second CD counters added to a scratch copy only (data sectors
read sped up or not, XA-ADPCM and CD-DA sectors, sectors dropped because the game hadn't read the last one, seeks, the
emulation thread's waits on the image), and the time to the first XA sector (the movie's audio starting).

| Read | Seek | Switch-on to the movie | Data sectors before it | Dropped sectors per second of movie |
|---|---|---|---|---|
| normal | normal (today's default) | 30.6 s | 620 | ~19 |
| 2× | normal | 27.9 s | 620 | ~24 |
| 10× | normal | 26.3 s | 747 | ~28 |
| 10× | instant | 22.4 s | 750 | ~36 |

- **Most of the boot isn't loading**: about 1.5 MB is read before the movie; the rest is the game's logos and timed
  screens, which no speedup touches. Seeks save more than reads (4 s on top of 10× reads).
- **The image isn't the bottleneck**: the emulation thread waited on reads for about 0.1 s in all, even at 10×.
- **Speedups hurt the movie**: dropped sectors per second of movie roughly double. Whether that shows on screen
  wasn't checked.
- The full BIOS boot (fast boot off) never reached the movie in 75 s, probably the test data's Japanese BIOS
  (`ps-30j.bin`) meeting a US disc; not followed up.
- Only a cold boot was timed; area transitions and menus need button presses the test didn't send.

## What exists

From a map of the code (2026-11-25; line numbers as of `cc3b7a9c0`):

- **Settings** (`settings.h:191-198`, `settings.cpp:340-351, 612-619`): `[CDROM] ReadSpeedup` (default 1; any value up
  to 1 is normal speed, no "maximum"; the UIs offer up to 10), `SeekSpeedup` (default 1; 0 is instant), and
  `ReadaheadSectors` (8; 0 reads synchronously), `LoadImageToRAM`, `MuteCDAudio`, `RegionCheck`, `MechaconVersion`.
  `DisableAllEnhancements` forces both speedups to 1 (`settings.cpp:771-773`). Hardcore mode doesn't touch them.
  `[BIOS] PatchFastBoot`: off on desktop, on on Android (`settings.h:565-574`). Rows on Qt
  (`consolesettingswidget.*`, `biossettingswidget.cpp:23`), Big Picture (`fullscreen_ui.cpp:3427-3455, 3517-3540`)
  and Android (`ConsoleSettings.kt`, `ConsoleScreen.kt:74-105`, `BiosSettings.kt`).
- **Reads** (`GetTicksForRead`, `cdrom.cpp:1432-1440`): sped up only at double speed with the CD-DA and XA mode bits
  both clear. XA streams stay at normal speed; a `Play` issued with the CD-DA bit clear, and video (MDEC) streamed as
  plain data, are sped up. The interval is computed at `BeginReading`, `BeginPlaying`, a Setmode speed change and a
  clock change only, not when the XA or CD-DA bits change mid-read nor when the setting changes.
- **Seeks** (`GetTicksForSeek`, 1442-1526): instant returns a fixed 30,000 ticks (about 0.9 ms) and skips the model;
  otherwise spin-up, short seeks (kept for Transformers – Beast Wars Transmetals, 1477-1480), track and sled seeks,
  divided by the speedup. **Never scaled:** a speed change (0.6–0.7 s, 1531-1536), the TOC read (0.5 s, 1538), stop
  and spin-up.
- **Dropped sectors**: `ProcessDataSector` (3100-3125) overwrites a sector the game hasn't read ("previous sector
  dropped").
- **No CD state is public**: `IsReadingOrPlaying` and `IsSeeking` are private (`cdrom.cpp:283, 858`); nothing turns
  fast-forward on by itself (`SetFastForwardEnabled`, `system.cpp:3867-3895`; hardcore limits speeds,
  `settings.cpp:859-866`).
- **The game database has no CD-speed traits** (`game_database.h:30-58`). Its notes name problems: the PlayStation
  Picks (USA) demo of Star Wars: Rebel Assault 2 "can hang if speed up" (`gamedb.yaml:123428`); Medal of Honor –
  Underground (97248) and Rally Cross 2 (130111), BIOS sound looping "related with CD-ROM speeds", avoided with fast
  boot; SaGa Frontier (USA), "black screen if fast boot is used" (137738); Voice Idol Collection and The Lion King are
  timing-sensitive command cases (`cdrom.cpp:1576-1583, 2518`). LibCrypt (224 titles) reads subchannel data, not
  timing.
- **The async reader** (`cdrom_async_reader.cpp`): readahead up to `ReadaheadSectors`; `WaitForReadToComplete`
  blocks the emulation thread on a miss and warns past 1 ms.
- **Audio** (item 24): fast-forward past 4× fades to silence, and back in below 3.5×.

## Goals

- **A maximum read speed** *(the player's choice)*: a "Maximum" choice for `ReadSpeedup` on every UI, reading data as
  fast as the game takes it *(builder's call how: a tick floor, or as fast as readahead delivers)*.
- **Instant seeks done fully** *(writer's call, from the measurement)*: with instant seeks, the speed change, the TOC
  read, spin-up and stop delays also shrink where that's safe *(builder's call which and to what, by testing)*; the
  short-seek timing Transformers needs is kept. **These shorter fixed waits become part of the defaults only if they
  pass the compatibility pass** (Constraints); otherwise they ship as an option of their own, off by default *(the
  player's choice)*.
- **No speedup while video or CD audio streams** *(the player's choice)*: reads run at normal speed while XA audio,
  CD-DA, or video streamed as plain data is playing, judged from what the drive and the console are doing (not only
  the mode bits it's read with today) *(builder's call how; the MDEC's activity is one sign)*; the read interval is
  recomputed when that changes mid-read and when the setting changes. Movies drop no more sectors than at today's
  defaults.
- **Fast-forward while a game is loading** *(the player's choice)*: a setting, **off by default** *(the player's
  choice)*, on Qt, Big Picture and Android, that runs at the fast-forward speed while the CD reads data (not while it
  streams), back to normal once it has been idle a moment *(builder's call on the rule)*, with audio as item 24 left
  it. Not offered in hardcore mode, which doesn't allow fast-forward *(writer's call)*.
- **Game-database switches** *(the player's choice)*: traits that turn the read speedup, the seek speedup or fast boot
  off for a game (names *(builder's call)*), applied as the existing traits are, with the player told once when one
  applies *(writer's call)*. They start with the games the database's notes name (above) and `docs/gamedb.md` says how
  to add more from testing. **Medal of Honor – Underground and Rally Cross 2 are left out** until someone can test them
  *(writer's call)*: their notes say the BIOS sound loop is avoided with fast boot *on*, which none of these switches
  does, and neither game is in the test library.
- **New defaults** *(the player's choice; what's in them follows the compatibility pass)*: `ReadSpeedup` Maximum and
  `SeekSpeedup` instant (at least today's instant seek; the shorter fixed waits only if they pass), on every platform,
  with the streaming suspension and the database switches always in force; fast boot unchanged (on for Android, off for
  desktop); fast-forward while loading off. Existing settings files *(builder's call, as earlier items turned new
  defaults on once)*. The startup warning about speedups (`system.cpp:4986-5000`) goes for the defaults.
- **Hardcore** *(writer's call)*: the speedups stay allowed, as today; the builder checks RetroAchievements' public
  rules on load speedups, and if they say otherwise, the speedups go to normal in hardcore.

## Success

*(The player's choice: measured load times come down clearly at the defaults, with every game behaving and movies and
music in time; the numbers are the writer's calls.)* On the Thor, before (today's defaults) and after (the new
defaults):

- Vagrant Story's switch-on to its movie at least 25% shorter (from 30.6 s), and at least one area transition or
  menu load, driven by button presses *(builder's call how)*, measurably shorter;
- movies: dropped sectors per second no higher than today's ~19, XA sectors per second unchanged, and the movie's
  frames shown as before (compared frame by frame where they can be captured *(builder's call how)*);
- with fast-forward while loading on, the boot's loads shorter again, with no audio left muted after;
- the same on the Mac and on Windows for the counters; every game in the test data folder booting to its first
  screen and its movies at the new defaults.

## Non-goals

- Loading the whole image into memory by default, or faster storage.
- Skipping the game's own logos or timed screens.
- Faster loading of save states.

## Anti-goals

*(The player's choice: any of these, at the new defaults.)*

- **A game that worked before hanging, crashing or misbehaving.**
- **Movies or CD music out of time, or dropping more than today.**
- **RetroAchievements hardcore refusing or flagging a session** because of the speedups.
- Anything from DuckStation's code after `a5bf1ba5cee7`.

## Constraints

- **Clean room**: nothing from DuckStation after `a5bf1ba5cee7` (its own maximum speedup came after it); its commit
  titles and user documentation only. The game-database switches start only from our database's own notes and our
  testing.
- **One stage, one PR** *(writer's call: the roadmap sizes it small)*; `docs/roadmap.md` (item 25) and
  `docs/gamedb.md` updated.
- **Tests**: native tests for the read and seek timing at each setting, the suspension while streaming (XA, CD-DA,
  plain-data video), the recomputed interval, the database switches and fast-forward's rule, without a booted system
  where they can be *(builder's call)*; Android's quick tier while building and the full tier before the PR; the
  existing regression tests.
- **Games** *(the player's choice)*: Vagrant Story from the test data folder (`~/work/goose-test-data/`), and the user's
  own library on the Windows machine (SSH host `pcsx2-win`, `F:\games\emulated\psx\`): about 80 US discs, CHD,
  among them SaGa Frontier (USA), the only game the database's notes name that's there, and many with movies and CD
  audio (Final Fantasy VII–IX, Chrono Cross, Xenogears, Parasite Eve, Legend of Dragoon, Lunar). A US BIOS is there too
  (`C:\Users\Ilya\Documents\DuckStation\bios\SCPH1001.BIN`). The builder runs them there, or copies what it needs
  into the test data folder or the Thor's scratch folder; never into the Thor's data folder.
- **A compatibility pass** *(the player's choice: the defaults follow what was tested)*: every disc in that library
  boots at the new defaults, with fast boot on and off (the US BIOS), to its first screen and through its opening
  movie, against today's defaults, with no hang and the same frames where the timing allows a comparison
  *(builder's call how, automated)*; a game that fails gets a database switch, and a failure caused by the shorter
  fixed waits keeps them out of the defaults.
- **Vagrant Story's full-BIOS boot** *(writer's call)*: the build starts by finding why it didn't reach the movie in 75 s
  with `ps-30j.bin`; with the US BIOS that tells a region mismatch (out of scope) from a CD-timing fault (fixed here,
  since desktop boots through the full BIOS by default).
- **The Thor** *(the player's choice)*: the building session may measure there whenever it's connected (adb
  `64dc3c35`), with the `.debug` app and scratch data in `/sdcard/GooseTest` only, never the release app (the plain ID,
  never uninstalled) or the user's data folder, and cleans up afterwards; the measurement test stays out of the
  repository. Watching movies for glitches and playing the user's own games are the user's checks.
- **Desktop**: the Mac here; Windows over SSH on the user's machine.
- The user reviews every commit message.

## Left to the builder

How Maximum reads and the fuller instant seeks are timed, how streaming is detected, fast-forward's start and stop
rule, the traits' names, how existing settings files get the new defaults, how loads other than the boot are driven
and timed, and the order of implementation.
