<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Hardware texture cache, stage 3: settings and the user guide — design

Roadmap item 20, stage 3 of 3. The parent design is `docs/superpowers/specs/2026-11-16-texture-cache-design.md`
("Settings on every UI", Constraints); stages 1 (#124) and 2 (#127), the live-VRAM redesign (#128, #129) are merged.
Agreed in conversation on 2026-11-18. Calls made while writing this up are marked *(writer's call)*; calls the builder
may make are marked *(builder's call)*; everything else is the player's.

## Intent

The texture cache, dumping and replacing work on every hardware renderer on desktop and Android, but only by editing
`settings.ini`. This stage puts them in the three frontends' settings (Qt, Big Picture, Android), tells the player
when a game starts whether a pack was found, and writes the guide a player or pack maker follows.

**Success:** a player who has never edited a settings file copies the Vagrant Story HD font pack into the data
folder's `textures/`, turns on the cache and replacements (globally or for that one game) on any frontend, starts the
game, sees a notice that the pack was found, and plays with the pack's font; a pack maker on desktop dumps textures,
redraws some and plays them back, following only the guide.

## Who the settings serve

- **Players, on all three frontends:** the cache switch, the replacement switches, preload, and the cache's limits.
- **Pack makers, mostly on desktop:** every dumping option on Qt and Big Picture; on Android one **Dump textures**
  switch (its dumps already reach the data folder through the mirror).

## The rows

Each frontend keeps its existing texture replacement section (Qt: Graphics → **Texture Replacements** tab; Big Picture:
Graphics → **Texture Replacements** heading; Android: Graphics → **Texture replacement** section) and gains rows, in
this order. Every row is also in per-game settings, where it can follow the global value, as the section's rows are
today. The mockup agreed in conversation (rows as in the table, names close to it) is the reference *(row wording is
the builder's call, keeping the meaning)*.

Steps follow DuckStation's published guide: **the cache and texture replacements are separate switches**, and
replacements need the cache.

| Row | Key | Default | Qt | Big Picture | Android |
|---|---|---|---|---|---|
| Enable texture cache | `[GPU] EnableTextureCache` | off | new | new | new |
| Enable texture replacements (needs the cache) | `[TextureReplacements] EnableTextureReplacements` | off | new | new | new |
| Enable VRAM write replacements | `EnableVRAMWriteReplacements` | off | exists | exists | exists |
| Preload replacement textures | `PreloadTextures` | off | exists | exists | exists |
| Use old MDEC routines | `[Hacks] UseOldMDECRoutines` | off | exists | exists | — |
| Dump textures (needs the cache) | `DumpTextures` | off | new | new | new |
| Dump replaced textures (needs dumping) | `DumpReplacedTextures` | on | new | new | — |
| Force dump alpha channel (needs dumping) | `DumpTextureForceAlphaChannel` | off | new | new | — |
| VRAM write dumping, its alpha, its size thresholds | `DumpVRAMWrites`, … | as today | exists | exists (no thresholds) | — |
| **Limits** (an advanced group): hash cache entries | `MaxHashCacheEntries` | 1200 | new | new | new |
| hash cache memory (MB) | `MaxHashCacheVRAMUsageMB` | 2048 desktop, 512 Android | new | new | new |
| replacement cache memory (MB) | `MaxReplacementCacheVRAMUsage` | 512 desktop, 256 Android | new | new | new |
| Always track uploads (in the limits group) | `AlwaysTrackUploads` | off | new | new | — |

- **A row that needs another** is greyed out on Qt until that one is on (as Qt's VRAM write rows are today), and
  hidden on Big Picture and Android until it is (as Android's Preload row is today) *(writer's call, from what each
  frontend already does)*.
- **The limits' defaults shown are the running platform's own** (the core's `TexturePackConfig` defaults), since
  `settings.ini` leaves them out when they're the default (stage 2).
- **Descriptions** say what the player needs: the cache works on the hardware renderers only; replacements come from
  the data folder's `textures/<serial>/`; preloading loads the pack at the game's start and holds it within the
  replacement cache memory (worth saying on Android, where that's 256 MB); dumps go to `textures/<serial>/dumps/` (on
  Android, sent to the data folder).
- **Not settings anywhere:** a pack's `config.yaml` options (page mode, dump thresholds, palette range, splits and
  coalescing, linear filtering, aliases) stay in the pack's own file, which the first dump writes with every key
  commented out.
- **Android** follows `docs/design-system.md` (`SwitchRow`, `NumberRow`, `SettingsSection`; controller-first); the
  limits are number rows with presets *(builder's call on the presets)*.

## The notice

When a game starts (or replacements are reloaded) with **texture replacements or VRAM write replacements on**, the core
shows a short on-screen notice through its own OSD, so every frontend gets it:

- **It reports what will actually be drawn**, counting only the kinds of replacement that apply: "Texture pack: 161
  replacements" (with the VRAM write count when there are any).
- **When something stops a pack it found from applying, it says why**, one reason, the first that applies:
  - texture replacements in the folder, but the cache is off: "…needs the texture cache";
  - the software renderer: "…needs a hardware renderer";
  - only VRAM write replacements on, and the folder has texture replacements only (as the Vagrant Story font pack):
    "…needs texture replacements on".
- **None found:** "No texture pack found in textures/SLUS-01040" (the folder the core reads: the data folder's on
  Android).

Nothing is shown with both replacement switches off, so players without packs never see it *(writer's call)*. It's
raised where the core knows the renderer and the settings in effect (the game's start, and when a change to those
settings reloads replacements), once per such event, never per frame *(where exactly is the builder's call)*. The
wording and duration are the builder's call within the above. The guide's troubleshooting follows the notice: each
message, what it means and what to do.

## The guide

At the top of `docs/texture-replacement.md`, before the reference (which stays as it is), linked from the README's
feature list:

**Using texture packs**
- What a pack is (a folder of images named after the textures they replace); packs made for DuckStation work, and
  which kinds of names are checked (a link to "Which names are compatible").
- Finding a game's serial (the game list, its properties) and where its pack goes: desktop, the data folder's
  `textures/<serial>/`; Android, the same folder in the app's data folder, read in place and never copied into the
  app's storage.
- Turning it on, globally or for one game: the cache, then texture replacements; when to preload; the memory limits
  on Android.
- What each notice means and what to do about it; troubleshooting beyond it: a ZIP unpacked one folder too deep
  (shows as "no texture pack found"), the wrong serial, `config.yml` (read too) and a pack folder the Android app
  can't read (the data folder's grant).

**Making a pack**
- Dumping: turning it on, where dumps land (on Android, sent to the data folder), copying the textures you redraw into
  `replacements/` at any scale.
- A table of the `config.yaml` options, aliases, and a short guide to reading a name, linking to the reference for
  the details.

The guide describes what the app does, checked against it; nothing taken from other emulators' code (the clean room
of the parent design holds).

## Non-goals

- `config.yaml`'s options as settings, a pack browser or downloader, an importer, an "open textures folder" button.
- New behaviour in the cache itself (the notice aside).

## Anti-goals

- A row whose setting does nothing, or does something its label doesn't say (a per-game row that ignores the game's
  value, a default shown that isn't the platform's).
- The notice appearing for players who haven't turned replacements on, or repeating during play.
- A guide step that doesn't work as written on any of the three frontends.

## Constraints

- Tests for the non-UI parts (the notice's text and when it's shown, for each case above; the Android settings' keys
  and defaults, as the existing `GraphicsSettings` tests do); Android's rows in Compose tests as the other settings
  pages are.
- Android: the design system's rows, the quick and full device tiers before the PR, CI's Android line locally; any
  Thor check only with the player's go-ahead.
- Qt and Big Picture checked on the Mac (and the Windows machine over SSH where it helps); the builder says how each
  frontend was checked.
- `docs/roadmap.md` item 20 is updated when the stage is done; the player reviews every commit message.

## Left to the builder

Row wording within the meaning above, the Android presets for the limits, where the notice is raised in the core,
the guide's exact text, and the order of work.
