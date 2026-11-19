# DuckStation features added after the fork

GooseStation is based on DuckStation commit `a5bf1ba5cee7` (2024-09-01), the last one published
under the GPLv3. DuckStation has been developed under a non-free licence since then (PolyForm
Strict, then CC BY-NC-ND 4.0), so none of that code can be used here. This list describes, by
feature, what has been added there since, so we can decide what to build ourselves.

**Where it comes from.** Only descriptions: DuckStation's commit titles from 2024-09-01 to
2026-11-20 (about 5,250 commits, read through the GitHub API as text; the last 420, from
2026-10-23, added on 2026-11-20) and the feature list in its README. No post-fork source code was
read to write it.

**Clean-room rule for implementing any of it.** Whoever implements a feature here works from
behaviour, documentation and our own design, never from DuckStation's code after
`a5bf1ba5cee7`: no reading it, no copying it, no porting patches from it. Code from other
projects is fine under their licences (libretro shaders, SDL, RetroAchievements' rcheevos,
…). When in doubt, describe the behaviour in an issue first and implement from that.

The order within each group is roughly by how much a player would notice it. [What players notice
most](#what-players-notice-most) ranks the gaps that remain across all groups. Rows marked **Done**
have been built here, our own way. The **Priority**
column is a first proposal, to be settled once the fork builds and has emusync: **P1** worth
doing soon, **P2** worth doing, **P3** only if someone wants it.

## Rendering and graphics

| Feature | What it does | Priority |
|---|---|---|
| Hardware texture cache and per-texture replacement | Tracks textures in VRAM so they can be filtered as a whole, dumped and replaced one by one with high-resolution packs. The base can already dump and replace whole VRAM uploads (backgrounds and the like, `TextureReplacements`); this goes to individual textures. Upstream keeps fixing it (VRAM write splitting, palette hashing, `MaxVRAMWriteSplits`, a TIM name script). | **Done** (roadmap item 20, #124–#131); filtering whole textures through the cache is still open (P2) |
| Slang shader presets (RetroArch `.slangp`) | Loads RetroArch's slang presets as post-processing, alongside GLSL and ReShade FX. (The fork's sister projects already use librashader for this.) | **Done** (roadmap item 17, #56–#60) |
| GPU work on its own thread | Moves the hardware renderer's backend work off the emulation thread ("Move backend work off CPU thread", December 2024). Here only the software renderer can use a thread (`[GPU] UseThread`). | P1 |
| Frame presentation and pacing | Present skipping rewritten, optimal frame pacing on by default (off here), the throttler locked to vblank, late frames dropped, presenting between frames, syncing to host refresh rates that are a multiple of the console's, vsync off while fast-forwarding, Vulkan `present_wait`, a D3D12 frame-latency waitable. | P1 |
| Multi-threaded shader and pipeline compilation | Compiles shaders and pipelines on several threads (September 2026), cutting first-load stutter. | P1 |
| Upscaling defaults and seams | Nearest sprite filtering by default (February 2025), and "Disable Upscaled Direct Textures" (September 2026), for fewer seams and artefacts in 2D when filtering or upscaling. | P2 |
| More texture filters | Scale2x/Scale3x, MMPX (and "mmpx adv"), hybrid bilinear, extra smoothing filters, and filtering of framebuffer uploads. | P2 |
| Display scaling options | Lanczos scaling, separate scaling for FMVs, fine crop controls, an "uncorrected borders" crop mode. | P2 |
| Border overlays / bezels | Images drawn around the game picture, with a selectable list and user overlay presets. | P2 |
| Automatic internal resolution | A rewritten automatic resolution choice. | P2 |
| True Color (Full) dithering mode, scaled interlacing | Rendering options for colour depth and interlaced games. | P2 |
| PGXP improvements | PGXP for lines, unaligned loads/stores, better depth normalisation; since October 2026 more precise CPU mode (shifts, ADD/SUB, ANDI/ORI, MTC2/LWC2, GPF/GPL, MVMVA), reciprocal Z in the depth buffer, depth biased by draw order, an option to normalise depth by the GTE matrix scale, depth kept across clears. | P2 |
| Mid-frame scanout | Emulates games that change the display mid-frame (per-game trait). | P2 |
| Vectorised, multi-threaded software renderer | SIMD (AVX2 on x64) rasteriser, made the default. | P2 |
| Free camera | Moves the 3D camera through the game's geometry (GTE). | P3 |
| Debug rendering options | Disable texturing/lighting, vertex colour cropping. | P3 |
| Renderer infrastructure | Compute shaders (also on OpenGL and Metal since October 2026), SPIR-V ingestion, mipmaps, compressed textures, recovery from a lost device, pre-rotated swap chains (Android), GLES 3.0 support, Metal pipeline cache and timed present, VRAM readback encoded by compute. Mostly groundwork for the items above and for Android. | P2 |
| 2 MB VRAM mode | An optional doubled VRAM, for homebrew and mods. | P3 |

## Emulation and accuracy

| Feature | What it does | Priority |
|---|---|---|
| Accuracy fixes across CPU, GPU, CD-ROM, SPU, MDEC, DMA, timers, pad | Hundreds of individual fixes; they can't be listed one by one from titles. Since October 2026 a rework of GPU timing (measured command costs, a real 16-word FIFO, CRTC and interlace timing), CD-ROM timing (speed changes and seeks against hardware), SPU DMA and noise, and CPU exception order, which let upstream drop several per-game hacks; and a "reset the SPU on game start" option. We find and fix our own as games show them, with regression tests. | P1 (ongoing) |
| Game database updates | Per-game fixes and traits, hashes, metadata, localised and sort titles, controller support info. The data in the base is GPLv3; the later database is not and must be rebuilt from our own testing and public sources (redump.org). | P1 (ongoing) |
| New controllers | JogCon, Densha de Go controller with axes, mouse in more games, right stick as shoulder or trigger buttons. (NeGcon rumble and a Pop'n controller mode for the digital pad are in the base already.) | P2 |
| CD-ROM options | Maximum read speedup, no speedup during FMVs or CD audio, "Fast Forward While Loading", a physical disc read-speed override, disc lid control, switch to next disc on stop, SubQ skew, CloneCD (`.ccd`) and Mode 1 images, WAVE cue sheets, PPF undo data. The loading ones shorten every load. | P1 (loading), P2 (the rest) |
| Fast boot variants | "Fast forward boot" and another fast-boot type. | P2 |
| Expanded RAM | 4 to 16 MB of RAM, with "force mapping of all installed RAM", for mods and homebrew. | P3 |
| Parallel port flash cartridge | Basic emulation of PIO flash cartridges, and an option for the PCSX expansion region. | P3 |
| Loading `.CPE` and ELF executables | Booting homebrew executables directly. | P3 |
| LoongArch64 recompiler, ARM recompiler improvements | More host CPUs for the JIT, better ARM32/ARM64 code. | P3 (ARM64 P2 for Android) |

## Save states, memory cards and system

| Feature | What it does | Priority |
|---|---|---|
| Memory card improvements | Game icon shown while saving, renaming saves in the editor, icon extraction, multitap memory cards in the UI, fast-forwarding memory card access. | **Done** in part (the icon and renaming, roadmap item 11); multitap cards and fast-forwarding access open (P2); icon extraction dropped |
| Settings for whole disc sets | One configuration for every disc of a multi-disc game, custom titles for disc sets, and resetting a set's play time. The base already merges disc sets in the game list and can share one memory card between a set's discs. | P2 |
| Save state improvements | XZ compression, taints (cheats, memory card "time travel") recorded in states, rewritten memory save states, a "low VRAM" rewind mode, runahead for analog input only. | P2 |
| Background task threads | Saving states, screenshots and dumps off the emulation thread. | P2 |
| Audio stream rework | Fades around underruns, more buffering when emulation can't keep up, stretching on the audio thread, better tempo control (November 2026): fewer crackles on slow devices. | P1 |
| Live region change, BIOS installer | Changing the console region without restarting; installing a BIOS when one is missing. | P2 |
| Media capture improvements | File name formats, size shown in the OSD. | P3 |

## Achievements (RetroAchievements)

| Feature | What it does | Priority |
|---|---|---|
| Achievement UI | Progress indicators and their position and scale, pinning measured achievements, leaderboard trackers in the pause menu, unlock popups with points and categories, subsets; progress rings in the Qt game list. | P2 |
| Offline progress and hash library | Tracking progress offline; achievement info in the game list. | P2 (unlocks kept and sent later, and games started offline, are done our own way on desktop and Android: README, "RetroAchievements without a connection") |
| Encrypted login token | The token is no longer stored in plain text in the settings file. | **Done** our own way: kept out of `settings.ini` in `secrets.ini` (roadmap item 7), and in the Keystore on Android |
| Registering the emulator | Not an upstream change, but a gap players see: RetroAchievements doesn't know GooseStation, so it's treated as an unknown emulator (its "Warning: Unknown Emulator" achievement unlocks). | P1 (roadmap Later list) |

## Big Picture / fullscreen UI

| Feature | What it does | Priority |
|---|---|---|
| Themes and backgrounds | Several themes, an automatic theme following the desktop UI, user-selectable and shader backgrounds. | P2 |
| On-screen keyboard | Text entry with a controller. | P3 (the platforms' own keyboards are used instead; see [roadmap](roadmap.md) item 14) |
| Game list improvements | Search, compact mode, game icons, cover download, setting a cover, editing titles and serials, generated art for missing covers. (Android's library has its own search and cover download.) | P2 |
| Save-state selector and touch | A redesigned save-state selector (large preview, slot list), and touch scrolling with inertia. | P2 |
| Transitions, sound effects, polish | Fade/zoom/slide transitions, navigation sounds, drop shadows, rounding, blur. | P3 |
| Controller ergonomics | PlayStation button glyphs, swapping confirm/cancel, vibration motor binding, images in controller settings, "Bind All Buttons". | P2 |

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
| SDL3 | Input moved to SDL3: better controller support, sensors (accelerometer) mapping, touchpad as pointer. | **Done** as a drop-in replacement ([roadmap](roadmap.md) item 15); sensors and the touchpad not planned, since PS1 games have no use for them |
| Input options | Disabling background input, pressure and deadzone in macros, inverting axes, raw input hotplugging, preventing opposing directions, resuming when a controller reconnects after a disconnect pause. | P2 |

Input profiles and per-game controller settings are in the base already, so they are not listed.

## Platform and build

| Feature | What it does | Priority |
|---|---|---|
| Updated dependencies | Newer Qt, SDL3, newer compilers. Upgrading our own dependencies is our work, not a port. | **Done** (roadmap item 13), and ongoing |
| Packaging | Windows installer, RPM/Arch packaging, Linux ARM32/ARM64 AppImages. (The AppImage is in the base already; GooseStation also builds Windows ARM64.) | P2 |
| Android | DuckStation's Android app is closed source. GooseStation's Android frontend is our own, written independently (see the project plan). | separate track |

## What players notice most

The gaps that remain, ranked by what an ordinary player notices while playing (2026-11-20). Items
already built here are left out.

**Most players, most games**

1. Accuracy and timing fixes: games that glitch, hang or misbehave. Ours to find, game by game.
2. Per-game fixes in the game database: games that need a setting changed by hand (roadmap item 18).
3. Smoothness: GPU work on its own thread, the frame pacing rewrite, multi-threaded pipeline
   compilation (most noticeable on multi-core handhelds).
4. The audio stream rework: fewer crackles and dropouts on slow devices.
5. Loading: maximum read speedup, none during FMVs or CD audio, fast-forward while loading.

**Players who use enhancements**

6. PGXP improvements: less wobble and z-fighting when upscaled.
7. Upscaling defaults: automatic resolution, nearest sprite filtering, no upscaled direct textures.
8. Widescreen and 60 fps patches with options (cheat and patch management), with our own patches.
9. More texture filters, Lanczos and FMV scaling, border overlays.

**Particular groups**

10. RetroAchievements: registering the emulator, and the richer achievement UI.
11. Big Picture: game list search and covers, the save-state selector, themes, touch.
12. Controllers and input options: JogCon, Densha de Go, reconnect handling.
13. Expanded RAM and 2 MB VRAM, for mods and homebrew.

**Little effect on play:** importing from a DuckStation install, Flathub, multitap memory cards in
the editors, Big Picture's own on-screen keyboard, Discord, the debugger, the ISO browser, Qt themes.

## Not wanted

- DuckStation's update server, Discord rich presence tied to its accounts, and anything that
  depends on its infrastructure.
- Surround sound expansion was removed upstream; the base still has it and can keep it.
