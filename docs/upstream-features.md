# DuckStation features added after the fork

GooseStation is based on DuckStation commit `a5bf1ba5cee7` (2024-09-01), the last one published
under the GPLv3. DuckStation has been developed under a non-free licence since then (PolyForm
Strict, then CC BY-NC-ND 4.0), so none of that code can be used here. This list describes, by
feature, what has been added there since, so we can decide what to build ourselves.

**Where it comes from.** Only descriptions: DuckStation's commit titles from 2024-09-01 to
2026-10-23 (4,925 commits, read through the GitHub API as text) and the feature list in its
README. No post-fork source code was read to write it.

**Clean-room rule for implementing any of it.** Whoever implements a feature here works from
behaviour, documentation and our own design, never from DuckStation's code after
`a5bf1ba5cee7`: no reading it, no copying it, no porting patches from it. Code from other
projects is fine under their licences (libretro shaders, SDL, RetroAchievements' rcheevos,
…). When in doubt, describe the behaviour in an issue first and implement from that.

The order within each group is roughly by how much a player would notice it. The **Priority**
column is a first proposal, to be settled once the fork builds and has emusync: **P1** worth
doing soon, **P2** worth doing, **P3** only if someone wants it.

## Rendering and graphics

| Feature | What it does | Priority |
|---|---|---|
| Hardware texture cache and per-texture replacement | Tracks textures in VRAM so they can be filtered as a whole, dumped and replaced one by one with high-resolution packs. The base can already dump and replace whole VRAM uploads (backgrounds and the like, `TextureReplacements`); this goes to individual textures. | P1 |
| Slang shader presets (RetroArch `.slangp`) | Loads RetroArch's slang presets as post-processing, alongside GLSL and ReShade FX. (The fork's sister projects already use librashader for this.) | P1 |
| More texture filters | Scale2x/Scale3x, MMPX, hybrid bilinear, extra smoothing filters, and filtering of framebuffer uploads. | P2 |
| Display scaling options | Lanczos scaling, separate scaling for FMVs, fine crop controls, an "uncorrected borders" crop mode. | P2 |
| Border overlays / bezels | Images drawn around the game picture, with a selectable list. | P2 |
| Automatic internal resolution | A rewritten automatic resolution choice. | P2 |
| True Color (Full) dithering mode, scaled interlacing | Rendering options for colour depth and interlaced games. | P2 |
| PGXP improvements | PGXP for lines, unaligned loads/stores, better depth normalisation. | P2 |
| Mid-frame scanout | Emulates games that change the display mid-frame (per-game trait). | P2 |
| Vectorised, multi-threaded software renderer | SIMD (AVX2 on x64) rasteriser, made the default. | P2 |
| Free camera | Moves the 3D camera through the game's geometry (GTE). | P3 |
| Debug rendering options | Disable texturing/lighting, vertex colour cropping. | P3 |
| Renderer infrastructure | Compute shaders, SPIR-V ingestion, mipmaps, compressed textures, recovery from a lost device, pre-rotated swap chains (Android), GLES 3.0 support, Metal pipeline cache and timed present. Mostly groundwork for the items above and for Android. | P2 |

## Emulation and accuracy

| Feature | What it does | Priority |
|---|---|---|
| Accuracy fixes across CPU, GPU, CD-ROM, SPU, MDEC, DMA, timers, pad | Hundreds of individual fixes; they can't be listed one by one from titles. We find and fix our own as games show them, with regression tests. | P1 (ongoing) |
| Game database updates | Per-game fixes and traits, hashes, metadata, localised and sort titles, controller support info. The data in the base is GPLv3; the later database is not and must be rebuilt from our own testing and public sources (redump.org). | P1 (ongoing) |
| New controllers | JogCon, Pop'n Music controller, Densha de Go controller with axes, NeGcon rumble, mouse in more games, right stick as shoulder or trigger buttons. | P2 |
| CD-ROM options | Maximum read speedup, no speedup during FMVs, disc lid control, switch to next disc on stop, SubQ skew, CloneCD (`.ccd`) and Mode 1 images, WAVE cue sheets, PPF undo data. | P2 |
| Fast boot variants | "Fast forward boot" and another fast-boot type. | P2 |
| Parallel port flash cartridge | Basic emulation of PIO flash cartridges, and an option for the PCSX expansion region. | P3 |
| Loading `.CPE` and ELF executables | Booting homebrew executables directly. | P3 |
| LoongArch64 recompiler, ARM recompiler improvements | More host CPUs for the JIT, better ARM32/ARM64 code. | P3 (ARM64 P2 for Android) |

## Save states, memory cards and system

| Feature | What it does | Priority |
|---|---|---|
| Memory card improvements | Game icon shown while saving, renaming saves in the editor, icon extraction, multitap memory cards in the UI, fast-forwarding memory card access. | P1 (overlaps with emusync work) |
| Settings for whole disc sets | One configuration for every disc of a multi-disc game, custom titles for disc sets, and resetting a set's play time. The base already merges disc sets in the game list and can share one memory card between a set's discs. | P2 |
| Save state improvements | XZ compression, taints (cheats, memory card "time travel") recorded in states, rewritten memory save states, a "low VRAM" rewind mode, runahead for analog input only. | P2 |
| Background task threads | Saving states, screenshots and dumps off the emulation thread. | P2 |
| Live region change, BIOS installer | Changing the console region without restarting; installing a BIOS when one is missing. | P2 |
| Media capture improvements | File name formats, size shown in the OSD. | P3 |

## Achievements (RetroAchievements)

| Feature | What it does | Priority |
|---|---|---|
| Achievement UI | Progress indicators and their position and scale, pinning measured achievements, leaderboard trackers in the pause menu, unlock popups with points and categories, subsets. | P2 |
| Offline progress and hash library | Tracking progress offline; achievement info in the game list. | P2 |
| Encrypted login token | The token is no longer stored in plain text in the settings file. | P1 (security) |

## Big Picture / fullscreen UI

| Feature | What it does | Priority |
|---|---|---|
| Themes and backgrounds | Several themes, an automatic theme following the desktop UI, user-selectable and shader backgrounds. | P2 |
| On-screen keyboard | Text entry with a controller. | P1 (needed for an Android or handheld UI) |
| Game list improvements | Search, compact mode, game icons, cover download, setting a cover, editing titles and serials. | P2 |
| Transitions, sound effects, polish | Fade/zoom/slide transitions, navigation sounds, drop shadows, rounding, blur. | P3 |
| Controller ergonomics | PlayStation button glyphs, swapping confirm/cancel, vibration motor binding, images in controller settings. | P2 |

## Desktop (Qt) UI

| Feature | What it does | Priority |
|---|---|---|
| Memory card editor additions | Renaming saves, extracting icons. | P1 (alongside emusync) |
| Cheat and patch management | A rewritten cheat system: patches with options (widescreen, 60 fps and the like), assembly-style codes, search and sorting, and a much larger database. The base already has a cheat database (`data/resources/chtdb.txt`) and loads PCSX-R and libretro lists; upstream's later databases can't be used, so new entries would be ours. | P2 |
| Debugger tools | Memory editor, call stack and threads views, instruction patching, memory scanner "freeze", a separate TTY window, GDB server single-step and breakpoints. | P3 |
| ISO browser | Browsing a disc image and extracting files. | P3 |
| Setup wizard and BIOS installer | Graphics and achievements in the first-run wizard. | P2 |
| Themes and icons | More themes (dark variants, AMOLED, custom theme files), monochrome icons. | P3 |
| Updater without installing, Windows installer | Update checks that don't install; an installer and uninstaller. | P3 (our own updater instead) |

## Input

| Feature | What it does | Priority |
|---|---|---|
| SDL3 | Input moved to SDL3: better controller support, sensors (accelerometer) mapping, touchpad as pointer. | P1 (SDL3 also helps on Android) |
| Input options | Disabling background input, pressure and deadzone in macros, inverting axes, raw input hotplugging, preventing opposing directions. | P2 |
| Controller presets, per-game controller settings | Saving and applying controller setups. | P2 |

## Platform and build

| Feature | What it does | Priority |
|---|---|---|
| Updated dependencies | Newer Qt, SDL3, newer compilers. Upgrading our own dependencies is our work, not a port. | P1 |
| Packaging | AppImage, installer, RPM/Arch packaging. | P2 |
| Android | DuckStation's Android app is closed source. GooseStation's Android frontend is our own, written independently (see the project plan). | separate track |

## Not wanted

- DuckStation's update server, Discord rich presence tied to its accounts, and anything that
  depends on its infrastructure.
- Surround sound expansion was removed upstream; the base still has it and can keep it.
