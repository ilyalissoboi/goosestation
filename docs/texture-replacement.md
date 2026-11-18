<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Texture replacement

GooseStation's hardware renderers can dump the textures a game draws and replace them one by one from texture packs
in the layout packs are published in. The guide comes first: playing with a pack, then making one. The reference
after it says how every rule was established.

## Using texture packs

### What a pack is

A texture pack is a folder of images, each named after the texture it replaces, with an optional `config.yaml` of
settings for the game. Packs made for DuckStation's texture replacement work here: the kinds of names checked against
DuckStation are listed in [Which names are compatible](#which-names-are-compatible). Older packs of `vram-write-`
images (whole backgrounds) work too.

### Where a pack goes

Each game's pack goes in a folder named after the game's serial, such as `SLUS-01040` for Vagrant Story (USA). The
serial is shown with the game in the game list and in the game's properties or settings.

- **Desktop:** the data folder's `textures/<serial>/` (the data folder is the user directory, see
  [User Directories](../README.md#user-directories)). For example `textures/SLUS-01040/config.yaml` and
  `textures/SLUS-01040/replacements/…`.
- **Android:** the same `textures/<serial>/` inside the app's data folder (Settings → Folders). The app reads the pack
  there through the folder's permission, in place: a pack is never copied into the app's own storage, however large.

Unpack the pack so that its own `config.yaml` (if it has one) and its image folders sit directly in
`textures/<serial>/`: a ZIP often holds a `textures/` folder of its own, which goes over the data folder's.

### Turning it on

In **Settings → Graphics**, under **Texture Replacements** (Qt: its tab; Big Picture and Android: its section), or in
one game's settings to use the pack for that game alone:

1. Turn on **Enable Texture Cache** (Android: **Texture cache**). It works with the hardware renderers only (Vulkan,
   OpenGL, OpenGL ES, D3D11, D3D12, Metal), not the software renderer.
2. Turn on **Enable Texture Replacements** (Android: **Texture replacements**), shown or enabled once the cache is on.
3. For a pack of `vram-write-` images, turn on **Enable VRAM Write Replacement** instead (or as well).
4. Optionally, **Preload Replacement Textures** loads the whole pack when the game starts rather than each image the
   first time it's drawn, so there's no pause the first time a replaced texture appears. On Android this is worth
   turning on for packs that fit the **replacement cache memory** (256 MB by default there, 512 MB on desktop, under
   the limits): a larger pack keeps only what fits and loads the rest again as it's drawn.

### The notice

When a game starts with either replacement switch on, and whenever those settings or the renderer change, a notice
says what was found:

| Notice | What it means |
| --- | --- |
| Texture pack: 161 replacements | The pack is in use; the count is the images that apply (and its VRAM write replacements, if any). |
| No texture pack found in textures/SLUS-01040 | The game's folder has no replacement images: check the folder's name is the game's serial, that the images aren't one folder too deep, and on Android that the app still has the data folder's permission (Settings → Folders). |
| Texture pack found, but it needs the texture cache | Turn on the texture cache, globally or in the game's settings. |
| Texture pack found, but it needs a hardware renderer | The software renderer can't replace textures: pick another renderer. |
| Texture pack found, but it needs texture replacements on | The pack has texture replacements, and only VRAM write replacements are on. |
| Texture pack found, but it needs VRAM write replacements on | The pack has only `vram-write-` images, and VRAM write replacements are off. |

Nothing is shown while both replacement switches are off.

### When a pack still doesn't show

- A pack may replace only some textures (the Vagrant Story HD dialogue font pack replaces the menus' and dialogue's
  letters, not the world); the count says how many images apply, not how many are on screen.
- A pack's `config.yml` is read like a `config.yaml`. A `config.yaml` with an error is ignored, with the error in the
  log, and the pack is used with the global settings.
- A game's own settings override the global ones: a game set to texture cache Off stays off.

## Making a pack

### Dumping textures

1. Turn on the texture cache, then **Enable Texture Dumping** (Big Picture: **Dump Textures**; Android: **Dump
   textures**), for the game you're working on.
2. Play: each texture drawn for the first time is written to `textures/<serial>/dumps/` as a PNG named after it. On
   Android the dumps are written to the app's storage and sent to the data folder's `textures/<serial>/dumps/`.
   By default textures are named, and dumped, by the upload that brought them into VRAM, so start dumping before the
   scene you want loads: a save state's textures are already in VRAM and aren't dumped until the game uploads them
   again. With `DumpTexturePages` (below) textures are dumped from the pages they're drawn from, a save state's too.
3. The first dump also writes `textures/<serial>/config.yaml` (when the game has none) with every option commented
   out, and an empty `replacements/` folder.
4. Redraw the textures you want, at any scale (2×, 4×, 8×; the image's own size decides), keep their names, and put
   them in `textures/<serial>/replacements/` (or any folder under `textures/<serial>/` but `dumps/`). Turn on texture
   replacements to see them.

On desktop, **Dump Replaced Textures** (on by default) also dumps textures the pack already replaces, and **Force
Alpha Channel** writes dumps fully opaque. Dumping is for the game you're making a pack for: it writes many files.

### config.yaml

A pack's `config.yaml` sets options for its game, over the global settings; lines left out or commented out keep the
global values. The options:

| Option | What it does |
| --- | --- |
| `DumpTexturePages` | Name textures by the page and rect they're drawn from, instead of by the upload they came from (`texpage-` names instead of `texupload-`). |
| `DumpFullTexturePages` | In page mode, dump and replace whole 256×256 pages instead of the rects drawn. |
| `DumpC16Textures` | Dump direct-colour (16-bit) textures too. |
| `ReducePaletteRange` | Name and hash only the part of a palette a texture uses (on by default). |
| `ConvertCopiesToWrites` | Track VRAM copies out of a tracked upload as uploads of their own. |
| `MaxVRAMWriteSplits` | How many times a partly overwritten upload keeps its remaining parts as uploads. |
| `MaxVRAMWriteCoalesceWidth`, `MaxVRAMWriteCoalesceHeight` | The widest and tallest upload joined to the one directly above it. |
| `DumpTextureWidthThreshold`, `DumpTextureHeightThreshold` | Textures narrower or shorter than this many texels aren't dumped (16). |
| `DumpVRAMWriteWidthThreshold`, `DumpVRAMWriteHeightThreshold` | The same for `vram-write-` dumps (128). |
| `MaxHashCacheEntries`, `MaxHashCacheVRAMUsageMB`, `MaxReplacementCacheVRAMUsage` | The cache's limits, as in the settings. |
| `ReplacementScaleLinearFilter` | Filter replacements linearly when they're scaled to fit their page. |
| `Aliases` | A map of texture names to files, so one image can stand in for several names: `name: file` lines, indented under `Aliases:`. |

Set the options before dumping: page mode and palette range change the names dumps get.

### Reading a name

`texupload-P4-B5E04041144CABCE-D7ADDCD353C6521A-64x256-96-192-32x63-P0-15` is, in order: how the texture was found
(`texupload-` from an upload, `texpage-` from a page), its mode (`P4` 4-bit, `P8` 8-bit, `C16` direct colour; `ST`
in front when drawn semi-transparent), the texture's hash and the palette's hash, the source's size in VRAM words,
the drawn rect (its position and size in texels), and the palette range hashed. Renaming a file to a smaller rect
inside the same source replaces just that part. [Names](#names) and [Hashes](#hashes) below have the details.

## Reference: how names and hashes work here

Every rule below says how it was established. "Black box" means a released DuckStation build, run as published on
games the user owns, whose dump names and dumped images were compared with what our own run of the same game gives;
nothing here is taken from any emulator's source code. "Ours" marks a rule we chose where nothing could be observed.

The black box: DuckStation 0.1-12640-gbb4e9ac5e (its "Latest Rolling Release", 2026-11-07), macOS, Metal, 1×, fast
boot. The game: Vagrant Story (USA, SLUS-01040), its opening up to the first dialogue, no input. Runs on 2026-11-16:
(a) defaults, (b) ReducePaletteRange off, (c) the Vagrant Story HD dialogue font pack's `config.yaml` (page mode,
thresholds of 1, ReducePaletteRange off), (d) as (c) with DumpFullTexturePages on. The names and images stay
outside the repository.

### Folders and settings

- Dumps go to `textures/<serial>/dumps/`; on the first dump the black box also creates `textures/<serial>/replacements/`
  and writes `textures/<serial>/config.yaml` with every key commented out (black box, run (a)).
- The global keys and their defaults, from the `settings.ini` the black box writes on first launch:
  `[GPU] EnableTextureCache = false`; `[TextureReplacements]` `AlwaysTrackUploads = false`,
  `ConvertCopiesToWrites = false`, `DumpC16Textures = false`, `DumpFullTexturePages = false`,
  `DumpReplacedTextures = true`, `DumpTextureForceAlphaChannel = false`, `DumpTextureHeightThreshold = 16`,
  `DumpTexturePages = false`, `DumpTextureWidthThreshold = 16`, `DumpTextures = false`,
  `DumpVRAMWriteForceAlphaChannel = true`, `DumpVRAMWriteHeightThreshold = 128`, `DumpVRAMWriteWidthThreshold = 128`,
  `DumpVRAMWrites = false`, `EnableTextureReplacements = false`, `EnableVRAMWriteReplacements = false`,
  `MaxHashCacheEntries = 1200`, `MaxHashCacheVRAMUsageMB = 2048`, `MaxReplacementCacheVRAMUsage = 512`,
  `MaxVRAMWriteCoalesceHeight = 0`, `MaxVRAMWriteCoalesceWidth = 0`, `MaxVRAMWriteSplits = 0`,
  `PreloadTextures = false`, `ReducePaletteRange = true`, `ReplacementScaleLinearFilter = false`. The per-game
  `config.yaml` uses the same names (without `EnableTextureCache`, `EnableTextureReplacements`, `AlwaysTrackUploads`
  and the dump switches) plus `Aliases`.

### Names

- Upload mode (the default): `texupload-<mode>-<texture hash>-<palette hash>-<W>x<H>-<X>-<Y>-<w>x<h>-P<min>-<max>`.
  Page mode (`DumpTexturePages`): the same with `texpage-`. Direct-colour modes have no palette hash and no `P` field.
  Hashes are 16 upper-case hex digits (black box, runs (a)–(d); the font pack's 161 names).
- Modes seen: `P4`, `P8`, `STP4`, `STP8` (runs (a)–(d)); `C16` and `STC16` are in published names but weren't drawn in
  this stretch. `ST` marks a texture drawn with semi-transparency on: the same upload and palette appear as `P4` and
  `STP4` (run (a)).
- `<W>x<H>` is the source's size in VRAM words: the upload's size in upload mode (uploads of 64×256, 64×255, 64×224,
  64×48, 32×128 seen, each equal to the upload our run makes); the page's in page mode: 64×256 for 4-bit, 128×256 for
  8-bit (runs (c), (d)).
- `<X>-<Y>-<w>x<h>` is the drawn rect in texels of the expanded texture (a 64-word 4-bit upload is 256 texels wide:
  rects reach `192-128-64x127`). The dumped image is that rect alone, its size equal to `<w>x<h>` (all runs).
- One name per drawn rect, not per texture: the same upload and palette are dumped again for each different rect
  drawn from them, e.g. `…-4F4E4B2AD5F5EED6-B7328F90543715BF-64x256-128-128-64x127` and `…-192-128-64x127`
  (run (a)).
- With `DumpFullTexturePages` the rect is the whole page, `0-0-256x256`, cut at VRAM's right edge (`0-0-128x256` for
  an 8-bit page starting 64 words from the edge) (run (d)).
- `P<min>-<max>` is the palette range: the full range (`P0-15`, `P0-255`) with ReducePaletteRange off (run (b)); the
  used range otherwise, e.g. `P0-11`, `P1-5`, `P1-255` (run (a)).
- Legacy names: `vram-write-<32 hex digits>` (the high 64 bits of the hash first). GooseStation wrote one in eight of
  these names short before stage 1 (a formatting bug); they are now always 32 digits.

### Hashes

- **Upload-mode texture hash: XXH3_64bits over the upload's 16-bit words as the game sent them**, row after row, each
  word little-endian, no seed. Checked: all 32 texupload names of run (a) and all 32 of run (b) equal the hash of
  one of our run's uploads of the same size (black box vs. our run, 2026-11-16).
- The texture hash covers the whole upload whatever rect is drawn, so it doesn't depend on the rect or the palette
  range: the same hash appears with several rects and palettes, and with ReducePaletteRange on and off (runs (a),
  (b)).
- **Page-mode texture hash: XXH3_64bits over the VRAM words under the drawn rect**, row after row (the rect's texels
  turned into words: four texels a word for 4-bit, two for 8-bit). Checked for 103 of run (c)'s 211 names, the ones
  whose rect lies within a single upload of our run; the others need the whole of VRAM at the time of the draw and
  are checked with the cache (stage 1, Task 12). Two empty 4×1 rects in different places share one hash, which is the
  hash of one zero word (run (c)).
- **Palette hash: XXH3_64bits over `max − min + 1` consecutive 16-bit palette entries starting at the palette's first
  entry.** For the full range and for used ranges starting at 0 these are entries `min`…`max`; for used ranges
  starting above 0 (`P1-5`, `P1-22`, `P1-231`, `P1-255`) it is the first `max − min + 1` entries, not `min`…`max`.
  Checked: 26 of 26 palette hashes of run (a) and 22 of 22 of run (b) equal the hash of entries in our run's uploads,
  palettes taken at 16-entry boundaries (black box vs. our run, 2026-11-16). That the start is the palette's own first
  entry (from the draw's palette register) is checked with the cache (Task 12).

### Dumped images

- Alpha (all of runs (a)'s 32 images decoded against our uploads and palettes): a texel of `0x0000` has alpha 0 in
  every mode; any other texel has alpha 255 in modes without `ST`; in `ST` modes a texel with bit 15 set has alpha 143
  and one with bit 15 clear alpha 255.
- Colour: each 5-bit channel becomes 8 bits rounded, `(c × 255 + 15) / 31` (3 → 25, 16 → 132, 31 → 255), not by
  repeating bits (run (a)).

### The Vagrant Story HD dialogue font pack

- Played with its own files and `config.yaml`, from save states (2026-11-17): in the command menu 39 of its names are
  placed and the menu's letters and the HUD's `HP`/`MP` labels are drawn from it; elsewhere 19; in a cutscene none,
  since cutscene dialogue is drawn as one composed texture (below). Its names are found with nothing converted, so its
  `texpage-STP4` names agree with ours.
- Its 161 names are all `texpage-STP4-…-64x256-…-P0-15`, at 8× their rect (the pack's files). None of them appear
  among the black box's dumps of the opening, even over 8 minutes in the pack's own page mode (runs (c) and a longer
  rerun): the opening draws no glyph-sized rects, and its dialogue text seems to be put together by the game into the
  dialogue box's texture. 4 of the pack's 21 palette hashes do appear there. The pack's glyphs are drawn in menus and
  during play, which a run without input doesn't reach; checking the pack needs such a scene.

### Where packs are read and dumps written

All ours.

- Packs are read from the *pack folder*: on desktop the data folder's `textures/`; on Android the data folder's own
  `textures/`, read in place through `content://` (the app's storage holds no copy). Dumps, and the `config.yaml` a
  first dump writes, go to the *local* `textures/` (on desktop the same folder; on Android the app's storage, which
  the mirror sends to the data folder).
- A game's config is the pack folder's `config.yaml` (else `config.yml`); only when the pack has none is a local one,
  written by a first dump, read. A first dump writes no config when the pack has one.
- An unreadable pack folder (a revoked grant, a removed card) gives no replacements, and the game plays.
- Android's mirror, which keeps the app's copy of the data folder in step, copies nothing under `textures/` but a
  game's `dumps/` (sent to the data folder, never taken from it) and a first dump's `config.yaml` (sent once, only
  while the data folder has none, never taken back). It doesn't even list a pack's own folders, so a pack of tens of
  thousands of files costs a mirror run nothing. Copies of packs an earlier build pulled into the app's storage are
  dropped from it (never from the data folder). Legacy `vram-write-` dumps in `dump/textures/` are mirrored as before.
- Checked on the Android emulators (2026-11-17, `TextureCacheTest`) on Vulkan (the host's GPU) and OpenGL ES 3.1
  (ANGLE on SwiftShader), with the Vagrant Story HD font pack read in place from a `content://` data folder at the
  command menu: the pack changes the menu's frame; the cache tracking VRAM with nothing to replace leaves frames as
  they are without it; dumps land in the app's storage and nothing of the pack is copied there.
- Checked on the AYN Thor (Snapdragon 8 Gen 2, Adreno 740, 2026-11-17) with `TextureCacheTest` on Vulkan and OpenGL
  ES 3.2: all three pass, and the font pack's smooth glyphs replace the menu's pixel font. Measured at the command menu
  (state 2, 4× resolution, 20 s each, the core's per-second counters), the pack read from a `content://` tree:
  - The core's thread costs 1.5–2.4 points more with the cache on (31–33% Vulkan, 40–42% OpenGL ES, of one core); the
    GPU's time is unchanged (3.4% and 2.7%); steady frames stay at 16.7 ms, the slowest under 19 ms, in every case.
  - The pack's first frame takes 89 ms on Vulkan (106 ms OpenGL ES), while its images load; 69 ms with the same pack
    in local files, so most of it is decoding and uploading, which preloading moves to the boot: 25 ms (33 ms) then,
    the 161 images (128 MB) preloaded in under half a second.
  - Memory: the pack adds about 55 MB to the app's PSS and 18 MB of graphics memory on Vulkan; preloaded, about
    135 MB. Tracking without a pack adds nothing measurable.
  - Not covered: the system's own storage provider (the test's tree is the test app's provider; the user's data folder
    is reached through Android's external storage provider, which may be slower), and packs larger than the budget.
- Android's defaults for the memory budgets are smaller (ours): `MaxHashCacheVRAMUsageMB` 512 (desktop 2048) and
  `MaxReplacementCacheVRAMUsage` 256 (desktop 512). A value in the settings or a pack's `config.yaml` wins. The
  settings file is shared through the data folder, so these two keys are written to it only when they differ from the
  platform's default, and a default another platform wrote is removed (ours). Large packs are best preloaded on
  Android (`PreloadTextures`), since images read through the data folder's grant load more slowly than local files;
  what's preloaded is held within `MaxReplacementCacheVRAMUsage`, so a pack larger than that budget reloads images
  the budget let go of.

### config.yaml

- Read from `textures/<serial>/config.yaml`, else `config.yml`. Keys it sets override the global settings of the same
  name for that game; keys it leaves out (or comments out) keep the global values (ours).
- Read as flat YAML: `Key: value` lines, `#` comments, quoted values, and the `Aliases:` map of indented
  `name: file` lines, where a file may also be on the next, more indented line after `name: |`. Booleans are
  `true`/`false` (also `yes`/`no`, `on`/`off`, `1`/`0`); numbers are whole and not negative. A known key with a value
  of the wrong type stops the file being used, with an error naming the key; unknown keys and other lines are skipped
  with a warning (ours).
- The `config.yaml` written beside a game's first dump has every key commented out, with our own comments (ours).

### Where replacements are found

- Every image (`png`, `jpg`, `webp`) under `textures/<serial>/` whose file name, without its extension, is a texture or
  VRAM write name, at any depth, except under `textures/<serial>/dumps/`, so dumps are never loaded back (ours).
- Each `Aliases` entry: a texture name and a file, the file relative to `textures/<serial>/`. An alias whose name
  isn't a texture name, or whose file isn't an image there, is skipped with a warning (ours).
- A name whose rect reaches past its source (wider than the source's words times its texels per word: 4 for 4-bit, 2
  for 8-bit, 1 for direct colour, or taller than the source) is skipped with a warning (ours).
- Two files with the same name: the first found is used, with a warning (ours).
- Loaded images are kept up to `MaxReplacementCacheVRAMUsage` megabytes (4 bytes a pixel), the least recently used
  dropped first; `PreloadTextures` loads them all when the game starts (ours).

### Decoding, ranges and scale

- A texture's texels are read from its page: 4-bit pages hold four indices a word, lowest nibble first; 8-bit pages
  two, low byte first; direct-colour pages one colour a word. Palette entries are read from the palette's first entry
  on, wrapping at VRAM's right edge (ours, from how the console reads them).
- The used palette range is the smallest and largest index among the drawn rect's texels (black box: one upload is
  named `P0-11` for one rect and `P0-15` for others, run (a)).
- A page-mode rect hashes the words its texels touch: texel `x` of a 4-bit page is in word `x / 4`, so texels 1 to 5
  hash words 0 and 1 (ours, consistent with run (c)'s `4x1` rects hashing one word). A rect whose words would pass
  VRAM's right edge isn't hashed (ours).
- An upload-mode name's rect is placed in its page by the upload's position: an upload 16 words into a 4-bit page
  starts 64 texels in (ours, from the name layout).
- A page with replacements is drawn at one whole-number scale: the largest, over its replacements and both axes, of
  the image's size over the rect's, rounded up, and no more than the GPU's largest texture allows for 256 texels
  (ours, after the published "a page mixing scales is drawn at the largest").
- Texels without a replacement are read from VRAM as by any draw, so a `0x0000` texel stays transparent and bit 15
  stays the semi-transparency bit (ours).

### Tracking VRAM

All ours: the hardware renderer keeps VRAM on the GPU only, so the cache keeps a copy of its own to hash and dump.

- The copy follows every write from the CPU, fill and copy exactly, masks and wrapping at VRAM's edges included.
  What the GPU renders isn't in it: those areas are *unknown*, in blocks of 8×8 words, and nothing on an unknown
  block is dumped or replaced.
- A block becomes known again when a write, fill or copy from known blocks covers all of it. A write or copy with the
  mask check on never makes a block known; an interlaced fill, or a copy from unknown blocks, makes its blocks
  unknown.
- Each write from the CPU without the mask check is an *upload*, unless it wraps at VRAM's edge. A write directly
  below an upload with the same left and right edges joins it while the joined upload fits
  `MaxVRAMWriteCoalesceWidth` × `MaxVRAMWriteCoalesceHeight` (0 by default: never).
- Anything that changes part of an upload ends it. While its splits are below `MaxVRAMWriteSplits` (0 by default),
  the parts it leaves untouched (above, below, left and right of the change) stay uploads of their own, hashed over
  their own words.
- With `ConvertCopiesToWrites`, a copy whose source lies in one upload makes its destination an upload too.
- An upload's hash is taken over the cache's copy when first needed, which is the upload's words as written (with bit
  15 set if the write set it).
- Each 64×256-word page carries a count that changes whenever anything in it changes, so whatever was looked up for
  it is looked up again.
- After a save state loads, the copy is the state's VRAM, all known, with no uploads until the game uploads again.
  States kept in memory (rewind, runahead) don't carry VRAM for the CPU, so after one of those the cache reads VRAM
  back from the GPU first.
- The cache exists only while `[GPU] EnableTextureCache` is on and something uses it: replacements the game has (not
  merely `EnableTextureReplacements` with an empty folder), dumping, or `AlwaysTrackUploads`. Turning it on mid-game,
  or adding a pack and reloading, reads VRAM back from the GPU to start from.

### What a draw samples, and dumping

- A textured draw samples one page with one mode and palette, semi-transparent or not; its rect is the texels it
  reads, through the texture window: the bounds over every windowed coordinate, so a draw that crosses a window's wrap
  covers the whole tile (ours).
- A draw's rect (black box, worked out from 1.56 million draws of our run against run (b)'s names, 2026-11-16): a
  polygon covers its texture coordinates up to the largest one *inclusive*, so its rect ends one past it; a sprite
  ends at its own edge. Left and right widen to whole VRAM words (4 texels for 4-bit, 2 for 8-bit), and the rect is
  cut to the upload (or page).
- Rects collect per *run*: consecutive textured draws with one page, mode, palette and semi-transparency. Each
  texture's rects within a run join into one; a run's rect joins the texture's earlier rects that it overlaps (rects
  that only touch stay apart). A texture is dumped when its upload, page or palette is about to change, and whatever
  is left when dumping stops (black box: its dumps appear during play, long before it was stopped; the rest ours).
- With these rules 22 of run (a)'s 32 names and 23 of run (b)'s come out identical here; the rest differ in their
  rects (two textures the black box joins and we keep apart, or the reverse), which looks like where runs end. This
  matters only for dumps: a pack's names are found by their hashes and their own rects, whatever rects our dumps use.
- Upload mode: the texture is the upload holding all the words under the rect; a rect over two uploads, or over
  words that aren't an upload, is neither dumped nor replaced by upload names (ours, consistent with run (a)).
- Page mode: the texture is the rect itself, hashed over its words; the name's size is the page's (64, 128 or 256
  words for 4-bit, 8-bit and direct colour, cut at VRAM's right edge) (black box, runs (c), (d)).
- Neither is dumped or replaced while any block under the rect, or under the palette, is unknown (ours).
- A name is dumped once per session, at the end of the frame it is first drawn in, unless the file exists, the rect
  is smaller than `DumpTextureWidthThreshold` × `DumpTextureHeightThreshold` texels, it is direct colour without
  `DumpC16Textures`, or it has a replacement and `DumpReplacedTextures` is off. The image is decoded as it was when
  drawn (black box for the one-file-per-rect rule, run (a); ours for the rest).
- The first dump creates `replacements/` and, when the game has neither `config.yaml` nor `config.yml`, writes the
  `config.yaml` template (black box, run (a); our template).

### Looking replacements up

All ours.

- For a page, mode, palette and semi-transparency, the replacements are: upload names whose hash is that of an
  upload in the page, with its size, whose palette hash matches the palette at the name's range, placed where the
  name's rect falls in the page; and page names of the page's size whose palette hash matches and whose rect's words
  hash to the name's hash. Both kinds are used whatever the dump mode.
- The answer, replacements or none, is kept until something changes in the page (all the 64-word columns an 8-bit or
  direct page spans) or in its palette's page: a write, fill or copy, an upload cut (every page it spans), or a GPU
  draw that makes blocks unknown for the first time (drawing again over unknown blocks changes nothing). At most
  `MaxHashCacheEntries` combinations are kept, the least recently used dropped first.
- An answer's id is a hash of everything the page as drawn shows (its words, its palette, its replacements' rects and
  files), so looking up again after a change elsewhere, a state load or a rewind finds the same page already drawn.

### Drawing replacements

All ours.

- A page with replacements is drawn into a texture of its own that holds only the replacements: cleared, then each
  replacement image drawn over its rect at the page's scale, with linear filtering if `ReplacementScaleLinearFilter`
  is on, else nearest. Its alpha marks what covers each texel: 0 nothing, 85 a transparent replacement pixel, 170 a
  semi-transparent one, 255 an opaque one.
- Draws of that page, mode, palette and semi-transparency read this texture where a replacement covers the texel, and
  VRAM everywhere else, exactly as they would without replacements (the same texel, palette lookup and UV clamp).
  What the GPU rendered into the page therefore shows as it is, and texels outside the replacements are the GPU's
  own. They read the texture at the draw's own texture coordinates, with the texture window applied to the texel and
  the part within the texel kept, so a 4× replacement shows its own detail at 4× resolution; nearest, as filtering
  would blur the coverage into its neighbours.
- A replacement pixel with alpha 0 is transparent; alpha below 255 sets the texel's semi-transparency bit (the
  published rule, "alpha below 255 means semi-transparent"); 255 is opaque. Opaque black stays opaque.
- The texture filtering enhancement (bilinear, JINC2, xBR) doesn't apply to these draws; the replacement already is
  the higher resolution.
- One composed page is kept for each page, mode, palette and semi-transparency, replaced when what it shows changes,
  within `MaxHashCacheVRAMUsageMB` (the least recently used dropped first); a page that can't be created isn't tried
  again until what it shows changes. A page's scale is lowered until it takes at most a quarter of that budget. They
  are dropped whenever replacements reload.
- Each real mode has replaced pipelines of its own (4-bit, 8-bit and direct, each with a sprite twin), compiled only
  with the cache on: 13 texture modes with sprite shaders (7 with the cache off, 9 before this change), 7 without
  (4 and 5).
- VRAM uploads turn each 5-bit colour into `(c × 255 + 15) / 31` in integers and write that over 255, which every
  GPU stores exactly, instead of `c / 31`, which a GPU may store one step off. Fills already did. Frames on the Mac,
  whose GPU rounded exactly before, are byte-identical before and after (Metal, Vulkan, OpenGL; 1× and 4×).
- Replaced draws pick the same texel as other draws (rounding at 1× or with forced rounding, as the renderer does,
  and never for sprites, which have pipelines of their own) and the position within it. Checked 2026-11-17 over
  Vagrant Story's opening (12,000 frames, frames and VRAM every 300) with packs of our own 1× dumps: the upload-mode
  dumps (39), the same cut to their top halves (so draws sample covered and uncovered texels), 160 page-mode dumps
  and 18 direct-colour (`C16`) page dumps: frames and VRAM byte-identical to the cache off at 1× and 4× on Metal,
  Vulkan (MoltenVK) and OpenGL. The marked pack draws the same frames as before this change; a marked `C16` pack
  shows its marks where the title menu's direct-colour texture is drawn, at 4×.
- Checked the same way on Windows (RTX 4090, MSVC Release build), on D3D11, D3D12, Vulkan and OpenGL at 1× and 4×:
  all four packs draw frames and VRAM byte-identical to the cache off. Before this change and the exact uploads, the
  upload-mode pack differed in 29 of those 80 frames and VRAM dumps on all four. The exact uploads change frames with
  the cache off there too: by 1 of 255 on the display, and by one 5-bit step in a few hundred VRAM pixels the GPU drew
  from slightly different texels. With D3D11's and D3D12's debug layers on, both packs run to the end; Vulkan's
  validation layers aren't installed on either machine.
- The same holds with PGXP on (which clamps texture coordinates, so the replaced shaders' clamped variant runs), on
  the Mac's three renderers and Windows' four at 1× and 4×; with forced rounding, true colour off, 4× MSAA and the
  PGXP depth buffer on Metal and Vulkan at 4×; and with ROV blending (accurate blending, true colour off) on Windows'
  Vulkan, after a fix to how Vulkan binds the sets around a pushed texture set (without it, 30 of 80 frames differed
  there). With sprite texture filtering on, replaced sprites draw unfiltered, as above, so frames differ from the
  cache off.
- Android: stage 2's `TextureCacheTest` passes on the emulators on Vulkan and OpenGL ES (3 of 3 each, the font pack
  drawing in the menu). Byte-for-byte checks there need the regression test runner, which doesn't build for Android
  (the app provides some of the core's host functions).
- Not seen yet: a scene where a replaced page's GPU-rendered texels reach the screen. Vagrant Story's opening and the
  three save states at hand have replaced pages with rendered blocks, but no frame or VRAM dump differs even with
  the cache's copy of those blocks overwritten, so no draw there samples them.
- Checked by hand (stage 1, 2026-11-16) on Metal, with a pack made from our own dumps of Vagrant Story's opening at
  4× with a marked border: the marks appear where those textures are drawn on Metal, Vulkan (MoltenVK) and OpenGL
  (identical frames), a name with a smaller rect replaces only that rect, an alias draws its file, rewritten dialogue
  pages never show an old replacement, preloading draws the same frames, and with no pack frames are identical with
  the cache on and off.

### Which names are compatible

Checked on 2026-11-16 against the black box's names from Vagrant Story's opening (runs (a)–(c)). "As a pack" means its
dumped images were put in `replacements/` under their names and counted as placed during our run of the same stretch.

| Kind | Status | How it was checked |
| --- | --- | --- |
| `texupload-` P4, P8, STP4, STP8, full and reduced palette ranges | **Compatible** | All 64 names' texture and palette hashes reproduced by our functions from our run's uploads (`TextureCacheReference.OurHashesAreTheReferencesHashes`); as a pack, 30 of 32 placed (the other 2 aren't drawn the same way in our run) |
| `texpage-` P4, P8, STP4, STP8 | **Compatible** | 103 of 211 hashes reproduced from single uploads; as a pack, 145 of 211 placed; the rest are animated (one rect with 23 hashes) or 4×1 strips of changing data, whose contents depend on CD timing |
| `texupload-`/`texpage-` C16, STC16 | Our hashes, same rule | Not drawn in the opening; assumed to follow the same rule, unchecked |
| Cropped names (a smaller rect than dumped) | Works here | Checked with our own dumps renamed (Task 11); the black box's behaviour not checked |
| `vram-write-` | Unchanged | Pre-existing; names now always 32 digits |
| The Vagrant Story HD dialogue font pack | **Works** | As published (its 161 files and `config.yaml`), from save states of the user's game (2026-11-17): in the command menu 39 of its names are placed and the menu's letters and the HUD's labels draw from the pack at 8×; in another scene 19; a cutscene's dialogue draws its text as one composed texture, which the pack doesn't cover (none placed, as in the opening) |

### Cost

Measured 2026-11-16 on an M2 Pro with `goosestation-regtest` over Vagrant Story's first 12,000 frames, uncapped, three
runs each, median of its "average frame time" (the emulation thread with the renderer, no window; the GPU isn't
waited for each frame, so this shows the CPU side the cache adds):

| Renderer | Scale | Cache off | Cache on, no pack | Cache on, dumping |
| --- | --- | --- | --- | --- |
| Metal | 1× | 0.35 ms | 0.36 ms (+2.9%) | 0.40 ms (+14%) |
| Metal | 4× | 0.39 ms | 0.40 ms (+2.6%) | 0.44 ms (+13%) |
| Vulkan (MoltenVK) | 1× | 0.30 ms | 0.31 ms (+3.3%) | 0.35 ms (+17%) |
| Vulkan (MoltenVK) | 4× | 0.32 ms | 0.32 ms (+0%) | 0.36 ms (+13%) |

| D3D11 (Windows, RTX 4090, MSVC build) | 1× | 0.30 ms | 0.30 ms (no cache: nothing to replace) | — |

The differences with no pack are one step of the measurement's 0.01 ms resolution. On D3D11 the cache first cost
0.02 ms a frame (+7%) with replacements on and an empty folder; since then the cache isn't created until the game has
replacements, so that case costs nothing (0.30 and 0.31 ms medians, within the resolution). With our marked pack on
D3D11: 0.36 ms (+20%). With a pack (218 of our own dumps
at 4×, crops and an alias) on Metal at 1×: 0.39 ms against 0.34 ms off (+15%).

With replaced pages drawn over live VRAM (2026-11-17, same machine and stretch, three runs each, no frame dumps): our
39 upload-mode dumps at 1× cost 0.36 ms a frame against 0.34 ms with the cache off on Metal at 1× (+6%), 0.39 against
0.37 ms at 4× (+5%). The replaced pipelines (above) are compiled at start when the cache is on: on Metal, with the
driver's own shader cache warm, 1.7 s against 0.8 s with the cache off (1.1 s with the cache on before this change);
the first start after the shaders changed took 12 s, the driver compiling every new shader once.
