<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Slang shader presets — design

Roadmap item 17. Agreed in conversation on 2026-11-07. Calls made while writing this up, not yet confirmed, are
marked *(writer's call)*; calls the builder may make are marked *(builder's call)*.

## Intent

RetroArch's slang presets (`.slangp`: CRT, NTSC, handheld-screen and other multi-pass shaders, most famously the
libretro pack, Retro Crisis and lola-retro) are the shaders players know. GooseStation's post-processing runs GLSL,
ReShade `.fx` and dolphinfx shaders only. This brings slang presets to **desktop (Qt and Big Picture) and Android**
through **librashader**, with in-app pack downloads, a shader browser and favourites, so a player can go from a fresh
install to a working CRT preset without leaving the app.

The reference is the user's PCSX2 fork (`github.com/ilyalissoboi/pcsx2`): its librashader shader chain, pack
downloader, preset picker and parameter designs (`docs/superpowers/specs/2026-09-12-…` and `2026-09-13-…` there).
GooseStation follows it for the rendering glue and pack management, but **not** its model of one active preset
*(the player's choice)*: here a slang preset is a stage of the existing chains.

## What exists

- **The core's post-processing** (`src/util/postprocessing*.{h,cpp}`): two chains of stages, the display chain
  (`[PostProcessing]`, over the window-sized picture, `gpu.cpp:2026-2150`) and the internal chain
  (`[InternalPostProcessing]`, at the internal resolution, `gpu.cpp:1995-2020`). A stage is a
  `PostProcessing::Shader` (GLSL, ReShade FX; dolphinfx loads as GLSL), with `ShaderOption`s (bool, int, float, up to 4
  components, categories) stored beside it (`[PostProcessing/Stage1] ShaderName` and its options). `Config::AddStage`,
  `RemoveStage`, `MoveStageUp/Down`, `SetStageOption` edit chains; `GetAvailableShaderNames` lists shaders.
- **UIs for the chains:** Qt (`postprocessingsettingswidget.cpp`, `postprocessingchainconfigwidget.ui`: a tab per
  chain, an add-shader list, a stage's options) and Android (the Post-processing page from settings stage 4,
  `docs/android.md`, "Post-processing") edit both chains, globally and per game. Big Picture's post-processing page
  (`fullscreen_ui.cpp:4917`) edits only the global display chain: it isn't among the per-game pages
  (`fullscreen_ui.cpp:2881-2884`). Android's add-shader list loads every shader's metadata before offering it
  (`android/app/src/cpp/android_host.cpp:915-937`), far too slow for thousands of presets.
- **Hotkeys:** `TogglePostProcessing`, `ToggleInternalPostProcessing`, `ReloadPostProcessingShaders`
  (`src/core/hotkeys.cpp:419-436`); Android offers the core's hotkeys as Select + button chords (settings stage 2).
- **Downloads and archives:** `src/util/http_downloader` (curl, WinHTTP; Android's own,
  `android/app/src/cpp/http_downloader_android.cpp`), minizip in `dep/minizip`, rapidyaml (which reads JSON) in
  `dep/rapidyaml`. There's no libzip or rapidjson, which the fork used *(the builder uses what's here or adds them;
  builder's call)*.
- **Builds:** dependency scripts `scripts/deps/build-dependencies-{windows-x64,windows-arm64,mac,linux,android}.*`;
  the Flatpak's dependencies are manifest modules built without network (`scripts/flatpak/…yaml`, its CI cache keyed
  on them, `rolling-release.yml:324`). CI builds Windows x64 and ARM64, macOS as a universal binary with
  `-DENABLE_OPENGL=OFF` (`rolling-release.yml:385`), the Linux AppImage and Flatpak, and Android (arm64-v8a only,
  `build.gradle.kts:77`). No Rust anywhere yet.
- **Android's HTTP bridge** (`HttpBridge.kt:110`) reads a whole response into memory with no progress.
- **Android storage:** the data folder is mirrored file by file through the Storage Access Framework
  (`mirror/DataLayout.kt`, `shaders/` among the mirrored folders); OpenGL ES is the fallback renderer, under which
  ReShade shaders aren't offered today.
- **The PCSX2 fork's findings worth keeping:** librashader 0.12.0 (C ABI 2, API 5, MSRV Rust 1.88), loaded at run
  time and refused on an ABI mismatch; the options pointer to `libra_preset_create_with_options` must not be null;
  per-backend glue (Vulkan render pass end and layout transitions, D3D11 state-cache resync, D3D12 descriptor heaps
  re-bound and `dxcompiler.dll`, Metal encoders flushed); a preset that fails latches until it changes; ARMSX2 needed
  a command buffer submit after each chain frame on Adreno (the Thor's GPU); the libretro pack is ~3,000 presets, so a
  flat list is unusable.

## Goals

### A slang preset is a stage

- **One more kind of stage** in both chains *(the player's choice)*, beside GLSL, ReShade and dolphinfx: a
  `PostProcessing::Shader` that runs a `.slangp` through librashader. It's added, moved, removed, saved per game and
  toggled exactly as other stages are, and the chains' keys stay desktop's.
- **Its name** is the preset's path relative to the shaders folder, with its extension
  (`ShaderName = shaders_slang/crt/crt-royale.slangp`), so it can't clash with a GLSL name *(writer's call)*.
- **Its options are the preset's parameters** (`#pragma parameter`: name, description, initial, minimum, maximum,
  step), as float `ShaderOption`s, edited by the existing option UIs and stored beside the stage like any other
  stage's options. A changed parameter reaches a running chain without rebuilding it *(builder's call how)*.
- **Its picture** *(the player's choice, as the fork)*: the game's picture at the console's resolution (the CRTC's
  display size, 320×240 say), with no black bars; the preset's result is drawn where the picture sits in the stage's
  output. **First in the display chain, the stage reads the game's own frame** (the display texture and its view,
  after the internal chain), not the window-sized picture, so it isn't resampled up and then down *(writer's call,
  following the player's "as the fork"; this widens `Shader::Apply` and `RenderDisplay`'s plumbing)*. After other
  stages, it cuts the picture out of its input and scales it down. Stages after it get the whole output, as today. In
  the internal chain the same applies, at the internal chain's output size.
- **On every renderer librashader supports** *(the player's choice: every build)*: Vulkan, D3D11, D3D12, Metal and
  desktop OpenGL (the fork has no OpenGL glue; ARMSX2's OpenGL port is the reference). **OpenGL ES** gets a spike
  first: if librashader's OpenGL runtime can't run there, slang stages aren't offered under it, and one already in a
  chain says why and turns the chain off, as ReShade stages do under OpenGL ES today. The software renderer presents
  through a hardware device, so it's covered. (macOS ships without OpenGL, so there it's Vulkan through MoltenVK and
  Metal.)
- **When librashader is missing or refused** (not built, ABI mismatch, missing symbol), slang presets aren't listed
  and a slang stage in a chain says "Slang presets aren't available in this build: <reason>." Everything else
  renders as before.
- A preset that fails to load or compile shows an on-screen message with librashader's text once, and its chain
  falls back as a failing stage does today; it isn't retried until the stage or the preset changes.

### librashader in every build

- **Built from source with Rust in each build's dependency script** *(the player's choice: every build GooseStation
  ships)*: Windows x64 and ARM64, macOS, the Linux AppImage, the Flatpak (with the Rust SDK extension) and Android
  (arm64-v8a, as the app is, packaged in the APK). The Flatpak gets a librashader module of its own, with the Rust SDK
  extension and its cargo sources vendored (offline build); macOS builds both architectures and merges them. Pinned to a librashader tag (0.12.0 or newer at build time; *builder's
  call*), with each build's runtimes only (`runtime-vulkan`, `-d3d11`, `-d3d12`, `-metal`, `-opengl` as the platform
  has them).
- **Loaded at run time**, never linked: the library is MPL-2.0 and its header (MIT) is vendored under `dep/`, with
  both licences in the third-party notices.
- CI keeps building every target; the dependency caches rebuild when the scripts change.

### Big Picture catches up

**Big Picture gains the internal chain and post-processing in a game's settings** *(the player's choice)*, as Qt and
Android have them: its post-processing page edits both chains, and the page joins its per-game pages (each chain the
game's own or the global one, as the core takes it). So a Steam Deck player never has to leave Big Picture, and when
a favourite hotkey changes a game's own chain, Big Picture's page shows it. Built in stage 1.

### The shader browser

- **Adding a stage opens a browser** *(the player's choice: the fork's picker, extended)*: a tree of everything that
  can be a stage, from every shader folder (the bundled resources, the shaders folder, and on Android the app's own
  packs), by folder, each entry with its kind (GLSL, ReShade, dolphinfx, slang). A search box filters by path ("crt"
  shows every preset or shader whose path contains it, with their folders open). It opens on the last folder used
  *(writer's call)*.
- It **replaces the add-shader step** on Qt, Big Picture and Android; the rest of each chain editor stays as it is
  *(writer's call on the extent)*. Shaders a renderer can't run aren't offered, as today.
- On Big Picture and Android it's controller-first: folders open with A, B goes up a level, search uses the
  platform's keyboard (Big Picture's text entry, Android's), and focus is always visible.

### Shader packs

- **Download, update and remove packs from inside the app** *(the player's choice: all of the fork's phase 2)*, with
  the fork's behaviour: a fixed pack table; the latest version from GitHub's API (a branch's head commit, or the
  latest release's matching zip); download, check the archive, remove the previous version's files, extract with the
  fork's path hardening and size caps, and a marker (`.shaderpacks/<id>.json`) listing exactly the files written, so
  updating or removing a pack touches only its own files; dependencies installed first; cancel at any step; offline
  and rate-limit messages; each pack's licence shown, with "Third-party content under its own licences."
- **The packs:**

| Pack | Repository | Version | Strip | Installed under | Needs |
|---|---|---|---|---|---|
| libretro slang shaders | `libretro/slang-shaders` | head of `master` | 1 | `shaders_slang` | |
| Retro Crisis GDV-NTSC | `RetroCrisis/Retro-Crisis-GDV-NTSC` | latest release | 1 | `shaders_slang/retro crisis` | libretro |
| satpixie CRT | `Conkwer/satpixie-crt-shader` | latest release, not the `variants` zip | 4 | `shaders_slang` | |
| lola-retro *(the player's choice)* | `zoran123456/lola-retro` | latest release's `lola-retro-v<version>.zip` (not the 86Box, Amiberry or Kodi zips) | 1 | `lola-retro` *(writer's call)* | |

  lola-retro (MIT) is self-contained: its presets (`curved/`, `flat/`, `lite/`, `super-lite/`, `systems/`) refer to
  its own `shaders/`. Its `hdr/` presets expect HDR output, which GooseStation lacks, so they're left out of the
  install *(writer's call)*. Retro Crisis's zip carries `__MACOSX/` and `.DS_Store`, which extraction skips.
- **Where packs go:** on desktop, the shaders folder (`EmuFolders::Shaders`). **On Android, the app's own storage,
  never the data folder** *(the player's choice)*: fast, and never mirrored. The app searches both its own packs and
  the data folder's `shaders/` (the player's own shaders); a stage's name is looked up in the data folder's
  `shaders/` first, then the app's packs *(writer's call)*. A pack lives on each device separately: **Android's mirror
  skips every file a pack marker in the data folder's `shaders/.shaderpacks/` lists, and the markers** *(writer's
  call)*, so a desktop's packs (the libretro pack is about 140 MB) never come through the mirror; Android installs
  its own.
- **Android downloads stream to a file** with progress and cancel, rather than through today's in-memory bridge
  *(builder's call how)*.
- **Where it opens:** a "Download shader packs" entry on each post-processing page (Qt's as a dialog, Big Picture's
  and Android's as a page). Android and Big Picture show each pack's status, size once known, and progress, with
  Install, Update, Remove and Cancel.

### Favourites: saved chains

- **A favourite is a saved display chain** *(the player's choice)*: the whole chain, stages and their options, under
  a name ("CRT royale + border", "Sharp LCD").
- **Kept as one file each in a new `postprocessing/` folder in the data folder** *(the player's choice)*, holding the
  chain in the same form as `settings.ini`'s `[PostProcessing]` section and its stage sections; the list's order is
  kept in `settings.ini` *(builder's call on the key)*. A file can be copied to share a chain; desktop and Android see
  the same favourites through the data folder; the Android mirror learns the new folder.
- **On every UI** (Qt, Big Picture, Android): **Save as favourite** (from the current display chain, asking a name),
  **apply** a favourite to the display chain (asking first when that replaces stages), **rename**, **reorder** and
  **delete** (asking first). A favourite whose shader isn't installed here says which, and applies anyway, as a chain
  with a missing shader does today *(writer's call)*.
- **Hotkeys:** **Next favourite** and **Previous favourite** replace the running game's display chain with the next
  or previous saved one, wrapping, with a message naming it ("Post-processing: CRT royale + border."). They write the
  layer that holds the display chain: the game's file when the game has its own display chain, otherwise
  `settings.ini` *(writer's call)*, so the settings pages follow the picture. With no favourites, the message says so.
  They join the core's hotkeys, so Android offers them as Select + button chords.

### Stages

*(Writer's call.)* Each its own plan and PR, building on the one before:

1. **librashader and the slang stage:** every build's dependency script and CI, the run-time loader, the stage on
   every renderer (the OpenGL ES spike first, then the Thor's Adreno check with the user's go-ahead), parameters as
   options, per-game chains, and Big Picture's internal and per-game chains. Until stage 2, slang presets join the existing add-shader lists as they are, listed
   without loading each preset (only when one is added).
2. **The shader browser and the pack downloader**, on Qt, Big Picture and Android.
3. **Favourites and their hotkeys**, on Qt, Big Picture and Android.

## Non-goals

- The fork's one-active-preset model, its favourites list of presets, and its preset-cycling hotkeys.
- Packs beyond the four, user-added pack sources or a remote manifest.
- Editing `.slangp` files, or saving parameters back into them; RetroArch's own parameter files (`.slangp`
  overrides).
- Slang shaders on OpenGL ES if the spike shows librashader can't run there.

## Anti-goals

- A slang preset that silently does nothing: missing library, an unsupported renderer, a failed preset and a missing
  pack each say why.
- A build that stops building, or a platform that stops starting, because librashader isn't there.
- A pack update or removal that deletes files it didn't write.
- A download writing outside the shaders folder (path traversal) or filling the disk (size caps).
- Hundreds of megabytes of packs pushed through Android's mirror.
- A chain, favourite or `settings.ini` desktop and Android read differently.
- Breaking the design system or controller-first rules on Android, or Big Picture's controller use.

## Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7` (DuckStation later added its own slang support; don't
  look at it). The PCSX2 fork's code is GPL and the user's, and may be adapted where it fits *(writer's call)*.
- Desktop and Android share the core's code; Android-only code behind `__ANDROID__` or in `android/`.
- Android: `docs/design-system.md` binding, new pages in `FocusAuditTest` and `OrientationLayoutTest`, the quick tier
  while building and the full tier before each PR; checks on the Thor are the user's, with their go-ahead.
- Tests for every part that doesn't need a GPU or network (pack table, version parsing, archive hardening, markers,
  favourites files, picture cropping maths), as the fork has; network and GPU checked by hand.
- `docs/android.md`, the roadmap (item 17) and the third-party notices are updated with each stage. The user reviews
  every commit message.

## Left to the builder

How the slang stage reaches each backend's native handles through `GPUDevice`; the librashader pin and each
platform's build flags; the order within each stage; the browser's exact layout on each UI; how parameter changes
reach a running chain; which archive and JSON libraries the downloader uses.
