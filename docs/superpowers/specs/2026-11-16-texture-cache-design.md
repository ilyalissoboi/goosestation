<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Hardware texture cache and per-texture replacement — design

Roadmap item 20 (upstream P1). Agreed in conversation on 2026-11-16. Calls made while writing this up, not yet
confirmed, are marked *(writer's call)*; calls the builder may make are marked *(builder's call)*.

## Intent

GooseStation can only replace whole uploads to VRAM (backgrounds, full-screen images). Most high-resolution PS1
packs replace individual textures, and nearly all published ones are made for DuckStation's hardware texture cache,
which DuckStation added after our fork point. This adds **a hardware texture cache that tracks textures in VRAM so
they can be dumped and replaced one by one**, built **to play the packs people already publish** *(the player's
choice)*, on **desktop and Android, on every hardware renderer** *(the player's choice)*, with dumping so new packs
can be made.

## Clean room

- **Nothing from DuckStation's code after `a5bf1ba5cee7`**, its texture cache included (commit `e06f1f1002`, "GPU: Add
  hardware texture cache", and everything around it). Not its source, diffs or quoted snippets.
- **Allowed sources** *(the player's choice)*: DuckStation's user-facing documentation (the wiki page "Texture
  Replacement", https://github.com/stenzek/duckstation/wiki/Texture-Replacement, and the description of its
  "Texture Replacement 2.0" pull request, #3244, without its code), pack creators' guides, frontends' settings
  templates, and the files inside released packs (names, folders, `config.yaml`). Anything they don't say is worked
  out on our side from dumps and packs.
- **A current DuckStation release may be run as a black box** *(the player's choice)*: installed as released, never
  its code, running games the user owns to collect its dump names, which become the reference and the test fixtures
  for every kind of name and mode.
- **Off limits as source-derived** (found while researching): psxrecomp's `DUCKSTATION_TEXTURE_FORMAT.md`, TombaRecomp's
  `config.yaml.example` comments, faligame/Parasite-Eve-2-HD-Remaster's naming code, and the hash scripts in
  damon-daemon/sangokushi-koumeiden-hd-textures. Anything else that turns out to be derived from DuckStation's code
  is off limits the same way.

## What exists

- **VRAM write replacement** (`src/core/texture_replacements.{h,cpp}`, `[TextureReplacements]`
  `EnableVRAMWriteReplacements`, `PreloadTextures`, `DumpVRAMWrites`, thresholds; pre-fork): dumps and replaces whole
  VRAM writes, named `vram-write-<32 hex>`, hashed with `XXH3_128bits` over the write's 16-bit pixels
  (`texture_replacements.cpp:180`). Replacements are searched recursively over all of `textures/<serial>/`
  (`:169, 260`); dumps go to `dump/textures/<serial>/` (`EmuFolders::Dumps`, `:174`). **A bug:** the name is formatted
  with `{:08X}{:08X}` over two u64s (`:86`), so a half with a leading zero comes out short and `ParseString` (which
  wants 32 digits) can't read about one dump in eight back; it needs `{:016X}`. Settings rows exist on Qt, Big Picture (`fullscreen_ui.cpp:5000-5013`) and
  Android (settings stage 1).
- **The hardware renderer** (`src/core/gpu_hw.{h,cpp}`, `gpu_hw_shadergen.*`) samples textures straight from its VRAM
  copy by page and palette; there's no per-texture tracking. It keeps **no CPU copy of VRAM during play**:
  `UpdateVRAM`, `FillVRAM` and `CopyVRAM` (`gpu_hw.cpp:3044-3330`) don't update `g_vram`, only readbacks do, so the
  cache keeps what it hashes and dumps itself *(builder's design)*.
- **Android:** the data folder's `textures/` is mirrored into the app's storage (`mirror/DataLayout.kt:134`); the core
  reads `content://` documents but never writes through them (`src/common/file_system.cpp:52-65`).
- **The public pack format** (from the allowed sources; the research notes are in this conversation's record and
  are to be written into `docs/texture-replacement.md`):
  - folders `textures/<serial>/dumps/` and `textures/<serial>/replacements/`, and a per-game `config.yaml` with
    documented keys (DumpTexturePages, DumpFullTexturePages, DumpC16Textures, ReducePaletteRange,
    ConvertCopiesToWrites, MaxVRAMWriteSplits, MaxVRAMWriteCoalesceWidth/Height, the dump thresholds, MaxHashCacheEntries,
    MaxHashCacheVRAMUsageMB, MaxReplacementCacheVRAMUsage, ReplacementScaleLinearFilter, and `Aliases`, a map from a
    source name to a replacement file); some packs ship it as `config.yml`;
  - names such as `texupload-P4-<16 hex>-<16 hex>-64x256-0-192-64x64-P0-14`: mode, texture hash, palette hash, size
    of the original upload, the sub-texture's offset and size (in its expanded, 16-bit form), and the palette range
    hashed; direct-colour textures have no palette fields; modes P4, P8, C16 and semi-transparent STP4, STP8, STC16;
    `texpage-…` names in texture-page dump mode; legacy `vram-write-<32 hex>`;
  - the hash covers the whole original upload, so editing a name's offset or size crops what's replaced; with
    ReducePaletteRange only the used part of the palette is hashed;
  - PNG, JPG or WebP at any scale; a page mixing scales is drawn at the largest; alpha below 255 means
    semi-transparent, and dumps of STP modes invert the PS1's blend bit into alpha and back;
  - global keys seen in frontends: `[GPU] EnableTextureCache`, `[TextureReplacements] EnableTextureReplacements`,
    `AlwaysTrackUploads`, `DumpTextures`, `DumpReplacedTextures`, `DumpTextureForceAlphaChannel`, the cache limits
    and thresholds.
  - **Not public:** the hash function and the exact bytes hashed (index layout, row stride, re-hashing after splits,
    the palette's bytes), and the lookup rules for cropped names and aliases.

## Goals

### The cache

- **A hardware texture cache** in the hardware renderer: it tracks each upload to VRAM and the textures drawn from it
  (by page, mode and palette), so a texture can be dumped once and replaced wherever it's drawn, and invalidated when
  VRAM under it changes *(builder's design, from behaviour and our own work)*.
- **On every hardware renderer** *(the player's choice)*: Vulkan, D3D11, D3D12, Metal, OpenGL and OpenGL ES; the
  software renderer is unaffected. It's off unless turned on (`[GPU] EnableTextureCache`).
- **Limits:** the cache's entry and memory limits (the documented keys and their defaults), with lower defaults where
  a device has less GPU memory *(builder's call how; Android, the Thor)*.

### Dumping and replacing

- **Dumping** writes each new texture once to `textures/<serial>/dumps/`, with the documented options
  (thresholds, texture-page mode, C16, force alpha, dump replaced textures) and the folder's `config.yaml` created on
  the first dump.
- **Replacing** loads `textures/<serial>/replacements/` (PNG, JPG, WebP; any scale; aliases; preloading) and draws a
  texture's replacement wherever the texture is drawn, cropped as its name says, scaled and blended as described
  above.
- **The legacy `vram-write-` replacements keep working**, alongside.
- **Settings** use the keys packs and frontends already use (above), so a pack's `config.yaml` and a shared
  `settings.ini` mean the same here *(writer's call)*.

### Compatible names: the hash, worked out by experiment

*(The player's choice: work it out, with our own hashes as the fallback.)*

- The builder dumps textures from **Vagrant Story (USA, SLUS-01040)**, which the user owns, with the cache built, and
  compares the dump's names against the **public Vagrant Story HD dialogue font pack**
  (https://github.com/diodiogod/vagrant-story-hd-dialogue-font: `textures/SLUS-01040/`, 161 `texpage-STP4-…` PNGs and a
  `config.yaml` with DumpTexturePages on). Fields other than the hashes (mode, sizes, offsets, palette range) are
  checked first; the hashes are then tried with **xxHash variants** (the base already hashes VRAM writes with
  `XXH3_128bits`; 16 hex digits suggests a 64-bit variant) over the plausible byte layouts of an upload's indices and
  its palette.
- **A bounded effort** *(writer's call: about two days of experiment)*.
- **Compatibility is claimed per kind of name, only where checked** *(writer's call)*: each kind (`texpage-` and
  `texupload-`, each mode, palette ranges, cropping) is "compatible" only once our names match reference names for
  it. A kind that matches loads packs as published; a kind that can't be checked or doesn't match uses **our own
  hashes**, with the same folders, name layout and `config.yaml`, so only its hashes differ and a converter could
  follow. `docs/texture-replacement.md` lists which kinds are compatible and how each was checked.
- **The references** *(the player's choice)*: dumps from a current DuckStation release run as a black box, on games the
  user owns, for each kind of name and mode (`texupload-` and `texpage-`; P4, P8, C16 and the ST modes; palette ranges;
  cropped sub-textures), plus the public Vagrant Story font pack. The fixtures keep only names and the dumped images
  of our own runs.
- **Running the games** *(the player's choice)*: the user puts the Vagrant Story (USA) image in the test data folder
  (`~/work/goose-test-data/games/`, beside the BIOS), and the builder boots it; the intro's dialogue appears without
  input, which covers the font pack. Other games for other modes the same way, from the user's own images.
- Every rule found is written into `docs/texture-replacement.md` with how it was established (dump against pack), never
  as taken from elsewhere.

### Where packs live on Android

*(The player's choice.)*

- **Packs are read straight from the data folder**: the mirror stops copying `textures/` except `textures/*/dumps/`,
  so neither a per-texture pack nor a legacy `vram-write-` pack (which sits directly in `textures/<serial>/`, and can
  run to gigabytes) is copied file by file or stored twice *(writer's call)*. On Android the core searches for
  replacements in the data folder's `textures/<serial>/` through `content://`, as on desktop it searches the local
  one; preloading is recommended there in the docs.
- **The search skips `dumps/`**, so our own dumps are never loaded back as replacements *(writer's call)*.
- **Dumps are written to the app's own storage** (the core can't write through `content://`) and the mirror pushes
  `textures/*/dumps/` to the data folder as usual. Legacy `vram-write-` dumps stay in `dump/textures/<serial>/`, as
  today.
- **`config.yaml`** *(writer's call)*: a pack's own, in the data folder, is what the core reads (through `content://`
  on Android). When a game has none, the first dump writes one in the app's storage and the mirror pushes it once to
  the data folder; it's never pulled back over a pack's.
- On desktop, packs sit in the data folder's `textures/` as on DuckStation.

### Settings on every UI

- **Qt, Big Picture and Android** gain the cache's rows in their existing texture replacement sections, globally and
  per game where the others are: enable the cache, enable texture replacements, dump textures (and its options),
  preload, and the cache limits on the advanced side *(builder's call on which rows are advanced)*. Android's are
  controller-first, per the design system.

## Non-goals

- Filtering whole textures through the cache (a later item).
- A pack downloader, a pack converter, or a texture editor.
- The software renderer.

## Anti-goals

- Anything from DuckStation's code after the fork, or from documents derived from it.
- A texture drawn wrong (stale after VRAM changes, cropped or blended wrong) with the cache on, in games without a
  pack: the cache must be invisible until a replacement applies.
- A frame-time cost the player feels with the cache on and no pack (measured on desktop and the Thor).
- A large pack copied through Android's mirror.

## Constraints

- Tests for everything that doesn't need a GPU: name parsing and formatting, `config.yaml` reading, alias lookup,
  hashing (with dumps we make ourselves), cropping maths; GPU paths checked on each renderer by hand, and by the
  existing renderer tests where they reach.
- Android: `docs/design-system.md` binding for the settings rows; the quick and full tiers before the PR; the Thor
  checks (Vagrant Story with the font pack, memory and frame time) are the user's, with their go-ahead.
- Stages, each its own plan and PR *(writer's call)*: (1) the cache with dumping and replacing on desktop's renderers,
  the hash experiment, and the legacy name formatting fix; (2) the remaining renderers and Android (OpenGL ES,
  storage, limits); (3) the settings on Qt, Big Picture and Android, and `docs/texture-replacement.md`.
- **Windows and Linux renderers** *(the player's choice)*: D3D11, D3D12, Vulkan and OpenGL on Windows are checked on the
  user's Windows machine over SSH (the builder asks the user for its connection the first time); Linux is covered by CI
  building it, with a by-hand check later. Metal, MoltenVK and OpenGL are checked on the dev Mac, OpenGL ES and Vulkan
  on the Android emulators and, with the user's go-ahead, the Thor.
- `docs/roadmap.md` (item 20) is updated. The user reviews every commit message.

## Left to the builder

The cache's design (how uploads and textures are tracked and invalidated), the shader and renderer changes, the
memory defaults per platform, the experiment's method within its bound, and the order within each stage.

## Appendix: the public research (2026-11-16)

From the allowed sources only (links as found); nothing source-derived.

- **Official page:** https://github.com/stenzek/duckstation/wiki/Texture-Replacement (last edited 2024-11-27), the
  same text as the "How to use / filename / Options" sections of PR #3244 (merged 2024-09-29). Steps: enable the
  texture cache (Graphics → Texture Replacements), turn on dumping, play, look in `textures/<SERIAL>/dumps`, copy
  textures to `replacements`, enable texture replacements.
- **Global defaults seen in a frontend's template** (RetroBat's DuckStation `settings.ini`): `[GPU] EnableTextureCache`;
  `[TextureReplacements]` EnableTextureReplacements, EnableVRAMWriteReplacements, AlwaysTrackUploads, PreloadTextures,
  DumpVRAMWrites, DumpTextures false; DumpReplacedTextures true; DumpTextureForceAlphaChannel false;
  DumpVRAMWriteForceAlphaChannel true; ReducePaletteRange true; MaxHashCacheEntries 1200; MaxHashCacheVRAMUsageMB
  2048; MaxReplacementCacheVRAMUsage 512; texture dump thresholds 16×16; VRAM write dump thresholds 128×128.
- **Example names:** `texupload-P4-20D7201241D7412C-16F3DC9AA8F748C5-64x256-88-0-104x109-P0-15.png` (Silent Hill, from
  a third-party port's modding guide); `texpage-P8-0D42A71461011F2F-6B6F7ABE48E905CD-128x256-0-0-66x65-P0-255`,
  `texpage-STC16-B87CDEEF156A8B44-256x256-0-0-225x193` and `vram-write-11C332717832FF3F8B380AB104261EB3` (PR #3244's
  comments); `texpage-STP4-065D13C54C34C0FB-69491D7E26769824-64x256-228-0-28x16-P0-15.png` (the Vagrant Story pack).
- **The Vagrant Story pack's `config.yaml`:** DumpTexturePages true, thresholds of 1, ReducePaletteRange false; its
  INSTALL.md notes Android's ZIP importer wants `textures/` at the top and rejects `config.yaml`.
- **Other packs:** Tomba 2 multiregion (51,122 textures, 2.23 GB, ships `config.yml`), archive.org's "DuckStation HD
  Texture Packs" (now unavailable) and IceBullet's Dino Crisis and Silent Hill packs.
- **Behaviour in the developer's own words (PR #3244):** the hash covers the whole original upload ("adjusting the
  size in the filename only affects what is replaced"); with ReducePaletteRange "we only hash the 'used' part" of the
  palette; alpha below 255 is semi-transparent; STP modes invert the blend bit into alpha when dumping and back when
  loading; a page mixing scales draws at the largest, with ReplacementScaleLinearFilter for the stretch; aliases can
  point duplicates at one image.
