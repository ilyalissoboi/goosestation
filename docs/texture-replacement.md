<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Texture replacement

GooseStation's hardware renderers can dump the textures a game draws and replace them one by one from texture packs
in the layout packs are published in. This page is the reference for how that works. The user guide (turning it on,
installing a pack, making one) comes with the settings, in stage 3 of roadmap item 20.

## How names and hashes work here

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

- Its 161 names are all `texpage-STP4-…-64x256-…-P0-15`, at 8× their rect (the pack's files). None of them appear
  among the black box's dumps of the opening, even over 8 minutes in the pack's own page mode (runs (c) and a longer
  rerun): the opening draws no glyph-sized rects, and its dialogue text seems to be put together by the game into the
  dialogue box's texture. 4 of the pack's 21 palette hashes do appear there. The pack's glyphs are drawn in menus and
  during play, which a run without input doesn't reach; checking the pack needs such a scene.

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
- In the page as drawn, texels without a replacement keep their own colour, a `0x0000` texel stays transparent and
  bit 15 stays the semi-transparency bit (ours).

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
- The cache exists only while `[GPU] EnableTextureCache` is on and replacements, dumping or `AlwaysTrackUploads` are
  on; turning it on mid-game reads VRAM back from the GPU to start from.

### What a draw samples, and dumping

- A textured draw samples one page with one mode and palette, semi-transparent or not; its rect is the texels it
  reads, with the texture window applied (ours).
- **Not yet matched:** the black box's rects are larger than one primitive's: unions over several draws, with left
  and right edges rounded out to 32 texels (8 words) for 4-bit textures. Its texture and palette hashes do match ours
  for the same uploads (15 of run (a)'s 29 pairs drawn by our run of the same stretch). Until the rect rule is worked
  out, upload-mode names here differ from the black box's in their rect fields.
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
- The answer, replacements or none, is kept until anything in the page (all the 64-word columns an 8-bit or direct
  page spans) or in its palette's page changes, for at most `MaxHashCacheEntries` combinations, the least recently
  used dropped first.
