<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android settings — design

Part of roadmap item 16 (the Android frontend). Agreed in conversation on 2026-11-05. Calls made while writing this
up, not yet confirmed, are marked *(writer's call)*; calls the builder may make are marked *(builder's call)*.

## Intent

The Android app has six settings pages (Controllers, Graphics, Achievements, Memory cards, BIOS, Folders) holding a
dozen of desktop's settings; everything else is changed by editing `settings.ini` by hand (`docs/android.md`,
"Changing settings"). This brings Android's settings to **everything desktop has that makes sense on Android**,
including **settings for each game** *(the player's choice)*, built in stages. A player who knows desktop, or follows
a guide written for it, finds each setting where desktop has it.

## What exists

- **The pages' pattern** (`app/SettingsPages.kt`, `settings/*Settings.kt`, `settings/CoreSettings.kt`): a data class
  per page with `load` and `save` (writing only what changed), reads on the core's dispatcher, then
  `NativeLibrary.applySettings()` (`System::ApplySettings` on the CPU thread). Values a page doesn't offer are kept and
  shown "(from settings.ini)". Rows are `CyclerRow`, `SwitchRow`, `NavRow` and `TextFieldRow`
  (`ui/components/SettingsRows.kt`, `docs/design-system.md`).
- **The bridge** (`android/app/src/cpp/android_host.cpp:512-579`, `jni_bridge.cpp:1158-1212`) reads and writes only
  the base layer (`settings.ini`), as bool, int and string, on the caller's thread (the app's core dispatcher) under
  the settings lock; only `applySettings` runs on the CPU thread. **There is no float**, which the speeds, rewind frequency,
  PGXP tolerance, deadzones and sensitivities need.
- **Settings for each game already load on Android**: `System::UpdateGameSettingsLayer` reads
  `gamesettings/<serial>.ini` (`system.cpp:1163-1171, 1384-1445`), and `gamesettings/` is mirrored. The serial is the
  set's first for a disc set the database knows, and otherwise the running disc's own (or its `HASH-…`,
  `system.cpp:4053, 4076`), so the discs of an M3U or a name group that the database doesn't know as a set each have
  their own file, unlike Android's tile, which keeps their saves under one key (`docs/android.md`, the library).
  A game's file that names `[ControllerPorts] InputProfileName` (without `UseGameSettingsForController`) loads that
  input profile from the mirrored `inputprofiles/` (`system.cpp:1408-1412`). Nothing on Android creates or edits them, and a page's change to `settings.ini` is silently overridden
  while a game whose file sets the same key runs.
- **Controller settings layers** (`System::GetControllerSettingsLayers`, `system.cpp:1276-1293`): the controller
  types, multitap and bindings come from the base layer plus either an input profile or, when the game's file says
  `[ControllerPorts] UseGameSettingsForController = true`, the game's file. The layers fall through key by key.
- **What the host forces** (`android_host.cpp:1982-2018`): cheats off (patch codes are the app's), status indicators
  off (the app draws its own), debug logging on, fastmem off on larger pages, OpenGL ES after a Vulkan failure.
  `foreign_settings.cpp` adapts a `settings.ini` from another install; it doesn't touch `gamesettings/` or
  `inputprofiles/`.
- **No effect on Android**: audio backends other than AAudio (OpenSLES is a stub that fails), audio driver and device,
  surround expansion (forced off), media capture, Discord, PINE, GDB, window and fullscreen settings, mouse and
  keyboard, light guns (no pointer source). Rumble: `AndroidInputSource` reports no motors
  (`src/util/android_input_source.cpp:271-291`) and the manifest has no `VIBRATE`.
- **Every connected controller drives Port 1** today (`android_host.cpp:1537-1601`); Port 2 and multitap have no UI
  and no host code. Default bindings are added for the base layer's Port 1 type only (`android_host.cpp:1525-1557`),
  and a type's bindings are `[PadN]` keys named after its inputs (`Cross`, `LLeft`; a NeGcon's `Steering`, `I`, `II`).
  The core reads one motor binding per port (`[PadN] LargeMotor`/`SmallMotor`/`Motor`, a single string,
  `input_manager.cpp:915-925`), and its only strength key is per port (`VibrationBias`).
- **Rows that need the app or host, not just a key:** pausing when a controller disconnects is the host's job (Qt's,
  `qthost.cpp:1977`; Android's `Host::OnInputDeviceDisconnected` is empty, `android_host.cpp:2372-2374`); Android
  always passes `false` for save state backups (`android_host.cpp:1000`); the app draws its own unlock card for a fixed
  5 s or 10 s (`AchievementOverlay.kt:127-128`) and its own message layer for the core's messages.
- **The in-game menu** (`ui/GameMenu.kt`) has actions only; settings pages open only from the library.

## Goals

### Which settings

Everything on desktop's pages (Big Picture's, plus Qt's extras) that has an effect on Android, by desktop's key and
values, so `settings.ini` and the games' files stay ones desktop reads. Left out, and kept as they are in the files:

- **desktop-only or without effect here** (above), and the core's hotkeys removed on Android;
- **developer tools** *(the player's choice)*: wireframe, the debug GPU device, VRAM write dumps and their thresholds,
  `[Hacks]`, the GPU's disable switches, TTY logging, `[PCDrv]`, the display offsets, the log filter;
- **input profiles** *(writer's call)*: desktop's bind PC devices that don't exist here;
- **Interface rows with no Android meaning** *(writer's call)*: confirm power off, inhibit screensaver, UI language and
  theme, and the game list's view and sort keys (Android's library has its own);
- **cheats** (`[Console] EnableCheats`): patch codes cover them.

**A row the app or host has to do the work for is built, not dropped** *(writer's call)*: pausing when a controller
disconnects pauses into the in-game menu, as Back does; save state backups pass the setting through; the unlock card
and leaderboard messages last as long as the durations say; show messages hides the app's message layer's core
messages. A row whose setting still turns out to have no effect on Android is dropped, not shown dead *(writer's
call)*.

### The pages

**Desktop's pages and names, long ones split into sections** *(the player's choice)*. The hub, in desktop's order
*(writer's call)*, with the existing pages where they are:

| Page | Sections and rows (`[Section] Key`) |
|---|---|
| **Interface** (new) | Pause on start (`[Main] StartPaused`), pause when a controller disconnects (`PauseOnControllerDisconnection`), save state on exit (`SaveStateOnExit`, which the resume prompt already reads), create save state backups (`CreateSaveStateBackups`). The last three need the app's work (below) |
| **Console** (new) | **Console:** region, 8 MB RAM, disable all enhancements. **CPU:** execution mode, overclocking and its percentage (`OverclockNumerator`/`Denominator`), recompiler ICache. **CD-ROM:** read speed-up, seek speed-up, readahead sectors, preload disc to RAM, apply image patches |
| **Emulation** (new) | **Speed:** emulation, fast-forward and turbo speeds. **Frame pacing:** VSync, sync to host refresh rate, optimal frame pacing, reduce input latency (`PreFrameSleep`) and its buffer, skip duplicate frames. **Rewind:** on, save frequency, save slots, with desktop's memory estimate. **Run-ahead** |
| **BIOS** | As today |
| **Graphics** | **Rendering:** renderer, internal resolution, downsampling and its scale, MSAA and per-sample shading, threaded presentation, threaded rendering (software). **Enhancements:** texture and sprite texture filtering, true colour, debanding, scaled dithering, accurate blending, widescreen rendering, round upscaled texture coordinates, line detection, force NTSC timings, disable interlacing, FMV chroma smoothing. **PGXP:** geometry correction, depth buffer and its clear threshold, perspective-correct textures and colours, culling correction, preserve projection precision, CPU mode, vertex cache, disable on 2D polygons, geometry tolerance. **Display:** aspect ratio (with Custom and its numerator and denominator), deinterlacing, crop, scaling, screen position, rotation, stretch vertically, force 4:3 for FMVs. **On-screen display** (as Qt, so a game can have its own): show messages, FPS, speed, resolution, CPU, GPU, GPU statistics, latency, frame times, controller input, and the OSD scale if it reaches what the app draws *(builder's call)*. **Screenshots:** size, format, quality. **Texture replacement:** VRAM write replacements, preload. Plus use old MDEC routines and software readbacks under Enhancements *(builder's call on placement)* |
| **Post-processing** (new, stage 4) | On, the shader chain (add from the shaders found, remove, move up and down, reload), each stage's options |
| **Audio** (new) | **Volume:** output volume (0–200 %), fast-forward volume (0–100 %), mute, mute CD audio. **Output:** stretch mode, buffer size, output latency, minimal output latency |
| **Controllers** | As today, plus the stage 2 additions below |
| **Memory cards** | Sync as today, Manage cards; plus **Cards:** card 1 and card 2 type (`[MemoryCards] Card1Type`/`Card2Type`), the shared card for each (`Card1Path`/`Card2Path`, among the data folder's cards), one card for all discs of a game (`UsePlaylistTitle`) |
| **Achievements** | As today, plus encore mode, spectator mode, test unofficial achievements, and the notification and leaderboard durations |
| **Advanced** (new, stage 4) | Save state compression, load devices from save states, region check, allow booting without an SBI file, recompiler memory exceptions, block linking, fast memory access, log level, log to file *(the player's choice: the useful ones only)* |
| **Folders** | As today |

Choice rows with many values (speeds, readahead, run-ahead, rewind slots) and number ranges (volume, latency,
overclock percentage, OSD scale, tolerances) need a **number row** the design system lacks: the value shown between
arrows like `CyclerRow`, left and right stepping by a set step (held, repeating), A opening a list of common values
*(writer's call; builder's call on the details, added to `docs/design-system.md` and the focus tests)*.

Settings the core refuses in hardcore mode (slow-down, rewind, run-ahead and the rest) show disabled with "Off in
hardcore mode", as the in-game menu does *(writer's call)*.

### Settings for each game

- **Where they open** *(the player's choice)*: from the game's tile in the library *(writer's call; builder's call on
  the button, shown in `KeyHints`)*, and from a new **Settings** entry in the **in-game menu**, for the running game
  only, each change applied straight away. The library's global settings stay locked while a game runs, as today, and
  the in-game Settings never reaches the global pages, Folders or the memory card editor.
- **Which pages:** a summary (the game's title, serial, region; **Copy global settings** and **Clear settings**, each
  asking first; Copy copies what desktop's does, `Settings::Save(si, true)`, which leaves out the keys desktop keeps
  global, such as the on-screen display and logging rows), then Console, Emulation, Graphics, Post-processing *(writer's call, as Qt)*, Audio, Controllers, Memory
  cards (card types and shared cards only), Achievements and Advanced, with the same rows as the global pages.
- **Each row can use the global setting**: a choice row's first value is "Use global (<its value>)"; an on/off row
  becomes a choice of "Use global (On)", On and Off *(writer's call)*. Choosing "Use global" deletes the key from the
  game's file; a file left empty is deleted.
- **Where they're kept** *(the player's choice)*: **one file for every disc of the tile, under the game's key**, as
  its save states are: `gamesettings/<key>.ini`. For a disc set the database knows, that's the core's own file (the
  set's first serial), shared with desktop. For an M3U or a name group the database doesn't know, an Android-only hook
  (behind `__ANDROID__`) has the core load the key's file for every disc of the tile; desktop reading the same data
  folder applies it to the key's disc only, as it would anyway. An existing file under another disc's serial of such a
  group is left alone *(writer's call)*.
- **In a game**, a change writes the game's file, reloads the game's layer and applies it. A setting the core only
  takes at the next boot says so in its note ("Applies when the game next starts") *(builder's call which)*.
- **Controllers for a game** *(the player's choice)*: the controller type for each port, multitap and each type's
  settings (deadzone, sensitivity and so on) can differ; the **bindings stay global**. Android writes
  `[ControllerPorts] UseGameSettingsForController = true` with only those keys, so the bindings fall through to
  `settings.ini`. **The bindings are kept for each controller type** *(writer's call)*: whenever a type is picked,
  globally or for a game, each controller on that port gets the type's default bindings where it has none, and the
  global Controllers page shows and remaps the bindings for any type in use (the port's own type first), so a game's
  NeGcon is never left unbound. A game's file from desktop that has its own bindings, or names an input profile, is
  handled as the builder finds safest *(builder's call)*: their `SDL-*` and keyboard bindings can't work here.

### Controllers

- **A port for each controller** *(the player's choice)*: the Controllers page gives each connected controller a
  **Port** (Port 1 by default, remembered for that controller with its descriptor), so a second player sets theirs to
  Port 2. With multitap on, ports 1A–1D and 2A–2D are offered *(builder's call on naming, matching desktop's)*. A
  controller's bindings follow it to its port's `[PadN]`. The port is global, never per game *(writer's call)*. The
  multitap ports are offered whenever multitap is on globally or in any game's file; a controller on a port the
  running game doesn't have drives nothing, and the game's start says so in a message ("Wireless Controller is on
  Port 1C, which this game doesn't use") *(writer's call)*.
- **Each port's controller type** (`[PadN] Type`), among the types that work without a pointer *(writer's call: not
  GunCon, Justifier or the mouse)*, and the type's settings from `Controller::ControllerInfo::settings` (deadzones,
  sensitivity, invert, analog on reset and so on).
- **Multitap** (`[ControllerPorts] MultitapMode`).
- **Macros** (`[PadN] MacroK…`), as desktop *(writer's call: "everything that makes sense")*.
- **The hotkeys the core keeps on Android**, grouped as desktop groups them, still as Select + button chords, except
  those that would go around the app's own handling *(writer's call)*: no Toggle pause (it would pause with no menu),
  Change disc or Reset (the menu has them, with their questions). The save state hotkeys (save and load the selected
  slot, the previous and next slot, undo load) are offered and go through the app's own save path, so states keep
  their screenshots, the game's key and the resume rules *(writer's call; builder's call how)*.
- **Rumble** *(the player's choice)*: on any controller whose motors Android exposes (the input device's
  `VibratorManager` on Android 12 and later, its `getVibrator()` before; never the phone's or handheld's own motor), with a **strength for
  each controller** *(the player's choice)*; a controller with no motors Android can drive doesn't rumble, and its
  strength row says so. Adds the `VIBRATE` permission. With several controllers on one port, **the one last used**
  rumbles *(writer's call; builder's call how, given the core's one motor binding per port)*. The strength is kept in
  `[Android]`, beside `ControllerDescriptors` *(writer's call)*: an Android-only section desktop ignores, as today.

### Stages

*(Writer's call, confirmed by the player's playback.)* Each stage is its own plan and PR:

1. **Global settings:** Interface, Console, Emulation, Graphics, Audio, the memory card slots and the Achievements
   additions, with the bridge's float and the number row.
2. **Controllers:** ports, types and their settings, multitap, macros, the remaining hotkeys, rumble.
3. **Settings for each game**, from the library and the in-game menu.
4. **Post-processing and Advanced.**

## Non-goals

- Desktop-only settings, developer tools, input profiles, cheats (above).
- On-screen touch controls; light guns.
- Changing desktop or Big Picture. (Big Picture's settings pages have bugs found while reading them: the depth clear
  threshold writes `[GPU] PGXPDepthBuffer`, the vertex cache row's default, the fast-forward volume default of 200 over
  a maximum of 100, and "Log To System Console"'s translated section name. They're a separate desktop fix; Android
  must not copy them.)
- Slang presets (roadmap item 17), which will add to the Post-processing page later.

## Anti-goals

- A setting shown that does nothing on Android.
- A `settings.ini` or game file desktop can't read, or one whose keys or values differ from desktop's.
- A game's setting hidden from the player: a game's pages mark each row that differs from the global setting, and its
  summary says how many do *(writer's call)*. (The global pages have no game in view: they're locked in a game and
  open from the library's top bar.)
- Changing global settings, folders or cards while a game runs.
- Breaking the design system or controller-first rules: everything reachable with the D-pad, focus always visible, no
  window dialogs.
- The UI thread waiting on the core.

## Constraints

- The design system ([`docs/design-system.md`](../../design-system.md)) is binding; new pages and rows go into
  `FocusAuditTest` and `OrientationLayoutTest` (`@LargeTest` where heavy), with screen tests driven by keys; the quick
  tier while building and the full tier before each PR (`android/scripts/run-instrumented-tests.sh`).
- Settings change through the core's settings interfaces over JNI, off the UI thread, as the bridge does today; values and names come from the
  core (`Settings::Get…Name`/`Parse…`), never copied lists, as `getAspectRatioNames` does.
- `foreign_settings.cpp` keeps adapting other installs' `settings.ini`; whether game files need the same is the
  builder's call (its device-specific keys are the renderer and bindings).
- No desktop behaviour change; clean room (nothing from DuckStation after `a5bf1ba5cee7`, nor its closed Android app);
  the user reviews every commit message. `docs/android.md` is updated with each stage.

## Left to the builder

Each page's exact rows and order within its sections, the number row's design, the button that opens a game's
settings from the library, how the bridge exposes the game layer, which settings need a restart, and the order within
each stage.
