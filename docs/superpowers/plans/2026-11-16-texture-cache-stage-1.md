# Hardware texture cache, stage 1 (desktop) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On desktop's hardware renderers, a texture cache that dumps textures one by one and draws per-texture
replacements from packs in the published layout, with the legacy `vram-write-` path fixed and kept, and the hash
worked out by experiment against a black-box DuckStation release, every rule written down.

**Architecture:** A GPU-independent `GPUTextureCache` keeps its own copy of VRAM (the hardware renderer keeps none),
which VRAM writes, fills and copies keep exact and GPU draws mark stale on an 8×8-word block grid; it records uploads,
hashes what draws sample, decides dumps and finds replacements in an index built from `textures/<serial>/`. For a
draw whose page, mode and palette have a replacement, `GPU_HW` composes a *source*: an RGBA8 texture of the
256×256-texel page at the replacements' scale (the page decoded, then each replacement drawn into its rect), and
draws the batch with a new `Replaced` texture mode that samples it. Draws without a replacement take today's path
untouched. The experiment runs early, on raw uploads from our own run against the black box's dump names, so the hash
is settled before names are built on it.

**Tech Stack:** C++20, CMake/Ninja (desktop), MSBuild (`duckstation.sln`, Windows), googletest (`common-tests`),
xxHash (vendored, `dep/xxhash`), rapidyaml (vendored, already linked into `core`), the existing `RGBA8Image`
(PNG/JPG/WebP), `duckstation-regtest` as the headless harness.

**Spec:** `docs/superpowers/specs/2026-11-16-texture-cache-design.md` (stage 1 of its Constraints; read its Clean
room section and Appendix first).

## Global Constraints

- **Clean room:** nothing from DuckStation's code after `a5bf1ba5cee7` (source, diffs, snippets), nor from the four
  off-limits sources the design names, nor anything else found to derive from that code. Allowed: the wiki page
  "Texture Replacement", PR #3244's description (no code), creators' guides, frontends' settings templates, released
  packs' files, and a current DuckStation release run as a black box (its outputs: dump names, dumped images, the
  `config.yaml` and `settings.ini` it writes). Never open its install's scripts or resources beyond running it.
- **Black box:** DuckStation "Latest Rolling Release" (`latest`, published 2026-11-07, `duckstation-mac-release.zip`),
  installed under `~/work/goose-test-data/duckstation-ref/`, with its own data folder there, never the user's.
- **Layout:** packs at `textures/<serial>/` (`EmuFolders::Textures`); dumps to `textures/<serial>/dumps/`;
  replacements searched recursively under `textures/<serial>/` except `textures/<serial>/dumps/`; per-game
  `textures/<serial>/config.yaml`, else `config.yml`. Legacy `vram-write-` dumps stay in
  `dump/textures/<serial>/` (`EmuFolders::Dumps`).
- **Keys:** `[GPU] EnableTextureCache`; `[TextureReplacements] EnableTextureReplacements`, `AlwaysTrackUploads`,
  `DumpTextures`, `DumpReplacedTextures`, `DumpTextureForceAlphaChannel`, plus the existing
  `EnableVRAMWriteReplacements`, `PreloadTextures`, `DumpVRAMWrites`, `DumpVRAMWriteForceAlphaChannel` and thresholds.
  `config.yaml` keys: `DumpTexturePages`, `DumpFullTexturePages`, `DumpC16Textures`, `ReducePaletteRange`,
  `ConvertCopiesToWrites`, `MaxVRAMWriteSplits`, `MaxVRAMWriteCoalesceWidth`, `MaxVRAMWriteCoalesceHeight`,
  `DumpTextureWidthThreshold`, `DumpTextureHeightThreshold`, `DumpVRAMWriteWidthThreshold`,
  `DumpVRAMWriteHeightThreshold`, `MaxHashCacheEntries`, `MaxHashCacheVRAMUsageMB`, `MaxReplacementCacheVRAMUsage`,
  `ReplacementScaleLinearFilter`, `Aliases`. Defaults seen in RetroBat's template: DumpTextures false,
  DumpReplacedTextures true, DumpTextureForceAlphaChannel false, DumpVRAMWriteForceAlphaChannel true,
  ReducePaletteRange true, MaxHashCacheEntries 1200, MaxHashCacheVRAMUsageMB 2048, MaxReplacementCacheVRAMUsage 512,
  texture dump thresholds 16×16, VRAM write dump thresholds 128×128. Defaults for keys not in that template come from
  the `config.yaml` the black box writes (Task 1); where it shows none, ours, written down.
- **Images:** PNG, JPG, WebP (`png`, `jpg`, `webp`, case-insensitive), any scale.
- **Off unless turned on:** with `EnableTextureCache` off nothing changes: no shadow VRAM, no extra pipelines, no hooks
  beyond a null check. The software renderer is untouched.
- **Invisible without a pack:** with the cache on and no replacement applying, frames are identical to the cache off
  (checked by frame dumps in Task 10).
- **Rules written down:** every rule found or decided goes into `docs/texture-replacement.md`, section "How names and
  hashes work here", each with how it was established ("black-box dump vs. our run, <date>", "font pack names",
  "our choice"). Never phrased as taken from DuckStation's code. Stage 3 adds the user guide around it.
- **Fixtures:** the repo keeps names only (reference names, pack names) and synthetic data. Game images and raw VRAM
  bytes from games stay in `~/work/goose-test-data/texture-refs/` (outside the repo); the tests that read them are
  opt-in through `GOOSE_TEST_DATA` and skip without it.
- **New files** carry `// SPDX-FileCopyrightText: 2026 GooseStation contributors` / `// SPDX-License-Identifier:
  GPL-3.0`, and are added to `src/core/CMakeLists.txt` and `core.vcxproj` (+ `.filters`), tests to
  `src/common-tests/CMakeLists.txt` and `common-tests.vcxproj` (+ `.filters`).
- **Build and test:** `cmake --build build --target common-tests duckstation-regtest duckstation-qt` then
  `build/bin/common-tests` (all pass; the baseline today passes with the Slang skips).
- **The experiment is bounded at about two days** in total (Tasks 3 and 12). At the bound, kinds not matched ship
  our own hashes.
- Commit each task; messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; show each message
  after committing. No PR until the user says so. Push with the ilyalissoboi credential helper.
- Out of scope: OpenGL ES and Android (stage 2); settings rows on Qt, Big Picture and Android, and the user guide
  (stage 3); filtering whole textures; a converter.

## Review Focus

1. **A texture overwritten in place** (same VRAM address, new contents, e.g. VS rewriting its dialogue page) must stop
   drawing the old replacement at the next draw. Pinned in Task 8 (`AWriteBumpsTheGenerationOfEveryPageItTouches`)
   and Task 9 (`ASourceIsLookedUpAgainAfterItsPageChanges`), and seen in Task 11 Step 5.
2. **Writes, fills and copies that wrap past VRAM's right or bottom edge** keep the shadow exact and mark the right
   blocks. Pinned in Task 8 (`AWriteThatWrapsLandsOnBothEdges`, `ACopyThatWrapsReadsAndWritesWrapped`).
3. **Masked writes** (check mask / set mask) keep the shadow exact. Pinned in Task 8
   (`ACheckedWriteSkipsMaskedPixels`, `ASetMaskWriteSetsBit15`).
4. **A malformed or impossible pack name** (a rect past the source's expanded size, zero size, bad hex, an alias to a
   missing file) is skipped with a warning, never a crash or a wrong draw. Pinned in Task 4 (`ParseRejectsMalformedNames`) and
   Task 6 (`AnAliasToAMissingFileIsSkipped`, `ARectPastTheSourceIsSkipped`).
5. **A replacement whose scale isn't uniform or would exceed the device's texture size** draws at the largest
   whole-number scale that fits. Pinned in Task 7 (`TheSourceScaleIsTheLargestThatFits`).

---

### Task 1: The black box's references

No repo code. Collects what everything after it is checked against.

**Files:**
- Create: `docs/texture-replacement.md` (title, a one-paragraph stub saying stage 3 adds the guide, and the section
  "How names and hashes work here" with the rules this task establishes)
- Outside the repo: `~/work/goose-test-data/duckstation-ref/` (the install and its data folder),
  `~/work/goose-test-data/texture-refs/SLUS-01040/<run>/` (each run's `dumps/`, its `config.yaml`, a `run.md` with
  the settings and how far it ran)

- [ ] **Step 1: Install the release.** Download `duckstation-mac-release.zip` from the `latest` release of
  `stenzek/duckstation` with `gh release download`, unzip into a new empty folder under `duckstation-ref/`. Find how
  it takes a separate data folder from its own `-help` output and the wiki (portable mode); point it there, never at
  `~/Library/Application Support/DuckStation`. Put the BIOS from `~/work/goose-test-data/bios/` in its `bios/`.
- [ ] **Step 2: Settings.** Write its `settings.ini` before the first launch: setup wizard done, Metal, 1× resolution,
  `[GPU] EnableTextureCache = true`, `[TextureReplacements] DumpTextures = true`, fast boot, no achievements, no
  update check. Launch once, quit, and keep the `settings.ini` it writes back: its `[TextureReplacements]` key list
  and defaults go into the rules section.
- [ ] **Step 3: Runs.** Boot `Vagrant Story (USA).chd` with `-batch` and let the intro play through its first dialogue
  (about 3 minutes; no input needed). Four runs, each into its own `texture-refs` folder: (a) defaults (texupload
  names, ReducePaletteRange on); (b) ReducePaletteRange off; (c) the font pack's `config.yaml` (DumpTexturePages on,
  thresholds 1, ReducePaletteRange off); (d) as (c) with DumpFullTexturePages on. Copy each run's `dumps/` and the
  `config.yaml` it wrote on the first dump.
- [ ] **Step 4: Read the outputs** with a script in `/tmp/tc-tools/` (Python, run with `-I`), and write down:
  - the name kinds and modes seen, and which fields vary together (texture hash vs. rect; palette hash vs. range);
  - the dumped image's size against the name's rect (the sub-rect only, or the whole upload/page), and whether the
    same texture is dumped again with a different rect;
  - the alpha of the dumped pixels: for each mode, the values seen and how they line up with the decoded texel
    (transparent `0x0000`, bit 15 set, bit 15 clear), in ST and non-ST modes, with and without force-alpha;
  - the defaults in the `config.yaml` it writes (values only; our comments are our own);
  - whether any of run (c)'s names equal the font pack's 161 names (a direct hit settles texpage compatibility early).
- [ ] **Step 5: Check the hypotheses later tasks rest on**, each marked confirmed or revised in the rules section:
  dumps go once per texture; a texture's key is (kind, mode, texture hash, palette hash, palette range); the rect in a
  name is in expanded texels, the size field in VRAM words; ST modes come from draws with semi-transparency on.
  Where a hypothesis is revised, amend the later task it feeds (Tasks 7–9) in this plan before starting them.
- [ ] **Step 6: Commit** `docs/texture-replacement.md` (and any plan amendments).

### Task 2: The headless harness

`duckstation-regtest` is how this plan sees frames and dumps (screen capture isn't permitted on this Mac). TDD
skipped (a harness's argument parsing; checked by running it).

**Files:**
- Modify: `src/duckstation-regtest/regtest_host.cpp` (usage text `:520-535`, argument loop `:560-630`)

- [ ] **Step 1: Add `-setting <Section>/<Key>=<Value>`**, repeatable, applied to `s_base_settings_interface` after
  the defaults. Packs need no option: `EmuFolders::Textures` follows the data root, which `SetNewDataRoot` sets to
  `<dumpdir>/<title>`; say so in the usage text.
- [ ] **Step 2: Check a hardware renderer runs headless:** `build/bin/duckstation-regtest -renderer Metal -frames 600
  -dumpdir /tmp/tc-run -dumpinterval 300 -- ~/work/goose-test-data/games/"Vagrant Story (USA).chd"` (BIOS linked into
  the data root's `bios/`) writes two frame PNGs that show the game. Repeat with `-renderer Vulkan` and `OpenGL`.
  Record the timing line it prints (frames and wall time) for Task 13.
- [ ] **Step 3: Commit.**

### Task 3: The hash experiment, part 1 (uploads and palettes)

Bounded: with Task 12, about two days. Experiment code stays out of the repo (a local, uncommitted patch and
`/tmp/tc-tools/`); only the result and the rules are committed.

**Files:**
- Modify: `docs/texture-replacement.md` (the hash rules, or "no match: our own")
- Local only: a patch that makes `GPU::FinishVRAMWrite` (`gpu_commands.cpp:535`) write each upload's raw 16-bit words
  as `upload-<n>-<x>-<y>-<w>x<h>.bin` into `/tmp/tc-raw/`; `/tmp/tc-tools/hashsearch.cpp`, built against
  `dep/xxhash`

- [ ] **Step 1: Capture.** With the patch, run regtest over the same stretch of the intro as Task 1's run (a) and (b).
- [ ] **Step 2: Search texture hashes.** For each reference `texupload-` name, take our uploads of the name's size and
  try, until one reproduces the name's texture hash: XXH3_64bits, XXH64 (seed 0), the low and high halves of
  XXH3_128bits; over the upload's words row by row (width × 2 bytes per row, little-endian), over the expanded indices
  (one byte per index for P4/P8), and over the sub-rect alone. Extend the layouts only from what matches partially
  (e.g. the same function matching some sizes).
- [ ] **Step 3: Search palette hashes.** For each reference palette hash, with its range `P<min>-<max>`, try the same
  functions over CLUT entries `[min, max]` (2 bytes each) taken from our uploads that hold the palette (16- or
  256-wide rows), with ReducePaletteRange off (run (b), full ranges) first, then on (run (a)).
- [ ] **Step 4: Write down the result** per kind: the function and byte layout that matched, the number of names
  checked and matched, and the date; or "no match after <layouts tried>: our own hash (XXH3_64bits over the rows)".
  This fixes `HashTextureWords` and `HashPalette` in Task 7.
- [ ] **Step 5: Commit** the doc only. Keep the patch and tools in `/tmp/tc-tools/` for Task 12.

### Task 4: Texture names, and the legacy name fix

**Files:**
- Create: `src/core/texture_names.h`, `src/core/texture_names.cpp`
- Modify: `src/core/texture_replacements.cpp` (drop `VRAMReplacementHash::ToString/ParseString`, `:84-100`, and
  `ParseReplacementFilename`, `:217-242`, for the new functions)
- Test: `src/common-tests/texture_names_tests.cpp`; fixture `src/common-tests/data/texture-names/vs-font-pack.txt`
  (the font pack's 161 file names, one per line)

**Interfaces:**
- Produces (namespace `TextureNames`):
  - `enum class Kind : u8 { VRAMWrite, TexUpload, TexPage };`
  - `enum class Mode : u8 { P4, P8, C16, STP4, STP8, STC16 };` with `bool IsSemiTransparent(Mode)`,
    `bool HasPalette(Mode)`, `GPUTextureMode GetTextureMode(Mode)`, `Mode MakeMode(GPUTextureMode, bool st)`
  - `struct Name { Kind kind; Mode mode; u64 texture_hash; u64 texture_hash_high; u64 palette_hash; u16
    source_width, source_height; u16 offset_x, offset_y, width, height; u16 palette_min, palette_max; }` — sizes of the
    source in VRAM words, the rect in expanded texels; `texture_hash_high` and the 32-digit form for `VRAMWrite` only
    (first 16 digits high, as today); palette fields only where `HasPalette`
  - `std::optional<Name> Parse(std::string_view file_title)` (no extension)
  - `std::string Format(const Name&)` (no extension)
  - `bool IsImageExtension(std::string_view ext)` (`png`, `jpg`, `webp`, any case)

- [ ] **Step 1: Write the failing tests** in `texture_names_tests.cpp`:
  - `AVRAMWriteNameKeepsLeadingZeros`: `Format` of `VRAMWrite` with high `0x0000000012345678`, low
    `0x00000000000000AB` is `vram-write-000000001234567800000000000000AB`, and `Parse` of it gives the same values
    (today's `{:08X}` gives 18 digits, which can't be read back).
  - `ParsesAnUploadName`: `texupload-P4-20D7201241D7412C-16F3DC9AA8F748C5-64x256-88-0-104x109-P0-15` gives TexUpload,
    P4, both hashes, 64×256, offset 88,0, 104×109, palette 0–15; `Format` gives the same string.
  - `ParsesAPageNameWithoutAPalette`: `texpage-STC16-B87CDEEF156A8B44-256x256-0-0-225x193` gives TexPage, STC16, no
    palette; round-trips.
  - `EveryFontPackNameRoundTrips`: each of the 161 names in the fixture (extension stripped) parses as TexPage STP4
    64×256 with palette 0–15 and formats back to itself.
  - `ParseRejectsMalformedNames`: 15 hex digits, `G` in a hash, a missing field, a palette field on C16, none on P4,
    an unknown mode `P16`, zero width, `P15-0` (min above max), `P0-16` on P4 and `P0-256` on P8 all give
    `std::nullopt`.
- [ ] **Step 2: Run** `build/bin/common-tests --gtest_filter='TextureNames*'`; expect a build failure (no header).
- [ ] **Step 3: Implement** `texture_names.{h,cpp}` (parse with `StringUtil::FromChars<u64>(…, 16)`, format with
  `{:016X}`), and switch `texture_replacements.cpp` to it.
- [ ] **Step 4: Run** the filter; expect PASS. Run all of `common-tests`; expect PASS.
- [ ] **Step 5: Write down** the name layout and the legacy fix in the rules section; **commit.**

### Task 5: `config.yaml` and the settings keys

**Files:**
- Create: `src/core/texture_pack_config.h`, `src/core/texture_pack_config.cpp`
- Modify: `src/core/settings.h:249-265` (`TextureReplacementSettings`), `src/core/settings.cpp:437-446, 686-694, 750`,
  and `[GPU] EnableTextureCache` beside the other GPU keys (`:188-200`, its save counterpart)
- Test: `src/common-tests/texture_pack_config_tests.cpp`, `src/common-tests/settings_texture_cache_tests.cpp`
  (following `settings_multitap_tests.cpp`)

**Interfaces:**
- Produces:
  - `struct TexturePackConfig` with one member per `config.yaml` key (Global Constraints), named in snake case
    (`dump_texture_pages`, …, `max_replacement_cache_vram_usage_mb`, `replacement_scale_linear_filter`), the defaults
    there and from Task 1, and `std::vector<std::pair<std::string, std::string>> aliases` (source name without
    extension → file name);
  - `bool TexturePackConfig::LoadYAML(std::string_view yaml, Error* error)` — overlays only the keys present; unknown
    keys are ignored with a warning; a value of the wrong type fails with the key's name;
  - `std::string TexturePackConfig::ToYAML() const` — every key with its value and a one-line comment of our own;
  - `void TexturePackConfig::Load(const SettingsInterface& si)` / `Save(SettingsInterface& si) const` under
    `[TextureReplacements]` with the same key names;
  - in `Settings`: `bool gpu_texture_cache` (`[GPU] EnableTextureCache`, default false); in
    `TextureReplacementSettings`: `enable_texture_replacements`, `always_track_uploads`, `dump_textures`,
    `dump_replaced_textures`, `dump_texture_force_alpha_channel`, and `TexturePackConfig config`;
    `AnyReplacementsEnabled()` covers both kinds; `bool ShouldTrackTextures(bool cache_enabled) const` = cache on and
    (replacements on or dumping or `always_track_uploads`).

- [ ] **Step 1: Write the failing tests:**
  - `TexturePackConfig.TheFontPacksConfigLoads`: the pack's six-line `config.yaml` (as a string in the test) sets
    pages on, full pages off, thresholds 1, ReducePaletteRange off, linear filter off, and leaves the rest at defaults.
  - `TexturePackConfig.AliasesLoad`: an `Aliases:` map of two entries gives both pairs, in file order.
  - `TexturePackConfig.AWrongTypeNamesTheKey`: `DumpTexturePages: [1]` fails with an error containing
    `DumpTexturePages`.
  - `TexturePackConfig.WritingThenReadingKeepsEveryValue`: a config with every member changed from its default goes
    through `ToYAML` and `LoadYAML` unchanged.
  - `SettingsTextureCache.KeysLoadFromTheirSections`: an INI with `[GPU] EnableTextureCache = true` and each new
    `[TextureReplacements]` key set to a non-default loads into `Settings`; saving writes the same keys back.
- [ ] **Step 2: Run** `--gtest_filter='TexturePackConfig*:SettingsTextureCache*'`; expect a build failure.
- [ ] **Step 3: Implement** with `ryml` as `game_database.cpp` uses it; settings as the neighbouring keys do; the
  "disable all enhancements" block at `settings.cpp:750` also turns off `gpu_texture_cache` and
  `enable_texture_replacements`.
- [ ] **Step 4: Run** the filter, then all of `common-tests`; expect PASS.
- [ ] **Step 5: Write down** the keys, their defaults and where each came from; **commit.**

### Task 6: The replacement index

**Files:**
- Modify: `src/core/texture_replacements.h`, `src/core/texture_replacements.cpp` (`FindTextures` `:244-278`,
  `GetSourceDirectory`, `Reload` `:198-208`, `PreloadTextures`, `LoadTexture`)
- Test: `src/common-tests/texture_replacements_tests.cpp` (temporary folders as `file_system_tests.cpp` makes them)

**Interfaces:**
- Consumes: `TextureNames::Parse`, `IsImageExtension` (Task 4); `TexturePackConfig` (Task 5).
- Produces (namespace `TextureReplacements`):
  - `struct TextureReplacement { TextureNames::Name name; std::string path; };`
  - `class ReplacementIndex { public: void Build(const std::string& game_dir, const TexturePackConfig& config);
    const std::string* FindVRAMWrite(u64 high, u64 low) const; std::span<const TextureReplacement>
    FindTextures(TextureNames::Kind, TextureNames::Mode, u64 texture_hash, u64 palette_hash, u16 palette_min, u16
    palette_max) const; size_t GetTextureCount() const; size_t GetVRAMWriteCount() const; };` — one key may hold
    several replacements (different rects of one texture);
  - `std::optional<TexturePackConfig> LoadGameConfig(const std::string& game_dir, const TexturePackConfig& base)` —
    reads `config.yaml`, else `config.yml`, over the global settings' config;
  - `const TexturePackConfig& GetConfig()`, `const ReplacementIndex& GetIndex()` for the running game;
  - `const ReplacementImage* GetTextureImage(const std::string& path)` (the existing image cache, now bounded by
    `MaxReplacementCacheVRAMUsage`, least recently used out first);
  - `std::string GetTextureDumpDirectory()` = `textures/<serial>/dumps`.

- [ ] **Step 1: Write the failing tests:**
  - `AGameFolderIsIndexed`: files `replacements/a.png` named as a texpage, `sub/b.webp` as a texupload,
    `vram-write-….jpg` at the top, and `notes.txt` give 2 textures and 1 VRAM write, each found by its key.
  - `DumpsAreNeverLoadedBack`: a valid name under `dumps/` isn't indexed.
  - `AnAliasPointsANameAtAFile`: `Aliases: {texpage-…-A: replacements/shared.png}` makes the alias's name find
    `shared.png`, resolved from the game folder.
  - `AnAliasToAMissingFileIsSkipped`: logged and not indexed.
  - `ARectPastTheSourceIsSkipped`: a P4 name with source 64×256 and rect `250-0-16x16` (past 256 texels) isn't
    indexed.
  - `TwoRectsOfOneTextureAreBothFound`: two names differing only in rect come back together from `FindTextures`.
  - `AConfigYmlIsReadWhenThereIsNoYaml`.
- [ ] **Step 2: Run** `--gtest_filter='TextureReplacementIndex*'`; expect a build failure.
- [ ] **Step 3: Implement.** Keep the legacy `vram-write-` lookup on the index; the duplicate warning stays.
- [ ] **Step 4: Run** the filter, then all; expect PASS.
- [ ] **Step 5: Write down** the search rules (folders, skip `dumps/`, alias resolution, duplicates); **commit.**

### Task 7: Texture data: hashing, palette ranges, decoding, crop and scale maths

**Files:**
- Create: `src/core/gpu_texture_data.h`, `src/core/gpu_texture_data.cpp`
- Test: `src/common-tests/gpu_texture_data_tests.cpp` (synthetic VRAM: a `std::vector<u16>(VRAM_WIDTH * VRAM_HEIGHT)`)

**Interfaces:**
- Consumes: `TextureNames::Name` (Task 4); Task 3's hash rules; Task 1's alpha rules.
- Produces (namespace `GPUTextureData`; every `vram` argument is 1024×512 words, stride `VRAM_WIDTH`; rects are
  `GSVector4i` left/top/right/bottom, never wrapping):
  - `u64 HashTextureWords(const u16* vram, GSVector4i word_rect)` — Task 3's rule (or ours);
  - `u64 HashPalette(const u16* vram, GPUTexturePaletteReg palette, u16 min, u16 max)`;
  - `std::pair<u16, u16> GetUsedPaletteRange(const u16* vram, GSVector4i word_rect, GPUTextureMode mode)`;
  - `u32 TexelToSourceRGBA(u16 texel)` — draw-time encoding in a source: `0x0000` → `0x00000000`; bit 15 set →
    alpha `0x80`; otherwise alpha `0xFF`; colour from `VRAMRGBA5551ToRGBA8888`;
  - `u32 TexelToDumpRGBA(u16 texel, bool semitransparent_mode, bool force_alpha)` — Task 1's dump rule;
  - `void DecodeTexture(const u16* vram, u32 page_x, u32 page_y, GSVector4i texel_rect, GPUTextureMode mode,
    GPUTexturePaletteReg palette, u32 (*encode)(u16), RGBA8Image* out)` (and an overload taking the dump rule's flags);
  - `std::optional<GSVector4i> GetPageRect(const TextureNames::Name& name, GSVector4i source_word_rect, u32 page_x,
    u32 page_y, GPUTextureMode mode)` — the name's rect, from the source's expanded texels to the page's texels;
    `std::nullopt` if it falls outside the page;
  - `u32 GetSourceScale(std::span<const std::pair<GSVector4i, std::pair<u32, u32>>> rects_and_image_sizes, u32
    max_texture_size)` — the largest of `ceil(image / rect)` over both axes and all entries, at least 1, at most
    `max_texture_size / 256`.

- [ ] **Step 1: Write the failing tests:**
  - `HashesFollowTheRule`: for a synthetic 64×256 block of words `i * 0x9E37`, `HashTextureWords` equals the chosen
    xxHash call over the chosen byte layout computed in the test directly (pins the layout, not just stability);
    likewise `HashPalette` over 16 entries.
  - `TheUsedRangeOfAP4TextureIsItsSmallestAndLargestIndex`: indices 3..11 give `{3, 11}`; P8 with 0 and 200 gives
    `{0, 200}`.
  - `DecodingAP4TextureAppliesItsPalette`, `…AP8Texture…`, `…AC16Texture…`: known texels at known positions,
    including the nibble order of P4 (low nibble first) and P8's two indices per word.
  - `SourceAlphaEncodesTransparentAndSemiTransparent`: `0x0000` → alpha 0, `0x8000` → alpha `0x80` (black, opaque
    or semi), `0x7FFF` → alpha `0xFF`.
  - `DumpAlphaFollowsTheReferences`: Task 1's observed values for each mode, ST and not, force alpha on and off.
  - `AnUploadsRectLandsInItsPage`: a P4 upload at words (16, 0) of page (0, 0) and name rect `8-4-16x16` gives page
    texels (72, 4)–(88, 20); a rect past the page's 256 texels gives `std::nullopt`.
  - `TheSourceScaleIsTheLargestThatFits`: rects 16×16 with images 128×128 and 32×48 give 8; with max texture size
    1024 the same give 4; an image smaller than its rect gives 1; a 3.5× image gives 4.
- [ ] **Step 2: Run** `--gtest_filter='GPUTextureData*'`; expect a build failure.
- [ ] **Step 3: Implement.** Use `XXH3_…` from `xxhash.h` as `texture_replacements.cpp` does.
- [ ] **Step 4: Run** the filter, then all; expect PASS.
- [ ] **Step 5: Write down** the decoding, alpha, range and scale rules; **commit.**

### Task 8: The tracker: shadow VRAM, blocks and uploads

**Files:**
- Create: `src/core/gpu_texture_cache.h`, `src/core/gpu_texture_cache.cpp`
- Test: `src/common-tests/gpu_texture_cache_tests.cpp`

**Interfaces:**
- Consumes: `TexturePackConfig` (Task 5: `max_vram_write_splits`, `max_vram_write_coalesce_width/height`,
  `convert_copies_to_writes`); `GPUTextureData::HashTextureWords` (Task 7).
- Produces: `class GPUTextureCache` (one per `GPU_HW`, CPU only, no `GPUDevice` calls):
  - `explicit GPUTextureCache(const TexturePackConfig& config);`
  - `void Reset(const u16* vram);` — shadow = `vram`, every block known, no uploads, every generation bumped;
  - `void Write(u32 x, u32 y, u32 width, u32 height, const u16* data, bool set_mask, bool check_mask);`
  - `void Fill(u32 x, u32 y, u32 width, u32 height, u16 color, bool interlaced);`
  - `void Copy(u32 src_x, u32 src_y, u32 dst_x, u32 dst_y, u32 width, u32 height, bool set_mask, bool check_mask);`
  - `void Draw(GSVector4i rect);` — rendered by the GPU: its blocks become unknown, uploads under it are cut;
  - `bool IsKnown(GSVector4i word_rect) const;` `const u16* GetVRAM() const;`
  - `struct Upload { GSVector4i rect; u64 hash; };` `const Upload* FindUpload(GSVector4i word_rect);` (the upload
    containing the rect; hash computed on first use);
  - `u32 GetPageGeneration(u32 page) const;` (page = `x / 64 + (y / 256) * 16`, 32 of them; bumped by any change
    touching the page).
- Rules (ours unless Task 1 or 12 says otherwise; write each down): blocks are 8×8 words, kept as two `u64` masks
  per block row; a block becomes known only when a write, fill or copy from known blocks covers it whole; a write
  with check mask, an interlaced fill, or a copy from unknown blocks leaves its blocks unknown; each write without
  check mask records an upload, except one that wraps; a write directly below an upload with the same left and
  width coalesces with it while the result fits `MaxVRAMWriteCoalesceWidth/Height`; anything changing part of an
  upload removes it, or, while `MaxVRAMWriteSplits` allows, leaves its uncovered remainder (up to four rects) as
  uploads hashed over their own words; with `ConvertCopiesToWrites`, a copy whose source lies in one upload records
  its destination as an upload.

- [ ] **Step 1: Write the failing tests:** `AFreshCacheKnowsAllOfVRAM`; `ADrawMakesItsBlocksUnknown`;
  `AWriteOverAFullBlockMakesItKnownAgain`; `AWriteOverPartOfADrawnBlockLeavesItUnknown`;
  `AWriteThatWrapsLandsOnBothEdges` (a 16×16 write at (1016, 504) lands in all four corners, and records no upload);
  `ACheckedWriteSkipsMaskedPixels`; `ASetMaskWriteSetsBit15`; `AFillIsKnown`; `AnInterlacedFillIsUnknown`;
  `ACopyThatWrapsReadsAndWritesWrapped`; `ACopyFromADrawnAreaIsUnknown`; `AnUploadIsFoundByARectInsideIt`;
  `ARectAcrossTwoUploadsFindsNone`; `AnUploadOverwrittenInPartIsForgotten` (splits 0);
  `SplitsKeepTheRestAsUploads` (splits 1: a 64×64 upload with its middle 16×16 overwritten leaves four uploads);
  `StackedWritesCoalesce` (four 64×64 writes down a column with coalesce height 256 give one 64×256 upload);
  `ACopyOfAnUploadBecomesAnUploadWhenConverting`; `AWriteBumpsTheGenerationOfEveryPageItTouches` (a write across the
  boundary of pages 0 and 1 bumps both, and not page 2).
- [ ] **Step 2: Run** `--gtest_filter='GPUTextureCache*'`; expect a build failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the filter, then all; expect PASS.
- [ ] **Step 5: Write down** the tracking rules; **commit.**

### Task 9: Which texture a draw samples: dumping and lookups

**Files:**
- Modify: `src/core/gpu_texture_cache.h`, `src/core/gpu_texture_cache.cpp`
- Test: `src/common-tests/gpu_texture_cache_tests.cpp` (dumps into a temporary folder)

**Interfaces:**
- Consumes: Tasks 4–8.
- Produces, on `GPUTextureCache`:
  - `struct DrawKey { u8 page; GPUTextureMode mode; u16 palette_bits; bool semitransparent; };` (`page` as above;
    `mode` with `Reserved_Direct16Bit` folded into `Direct16Bit`);
  - `void AddDrawnTexture(const DrawKey& key, GSVector4i texel_rect);` — page texels, texture window applied; only
    called while dumping;
  - `void EndFrame();` — writes the frame's pending dumps;
  - `struct Placement { GSVector4i page_rect; std::string path; u32 image_width, image_height; };`
    `struct Source { std::vector<Placement> placements; u32 scale; u64 id; };`
  - `const Source* LookupSource(const DrawKey& key, u32 max_texture_size);` — `nullptr` when nothing applies; the
    answer (either way) is kept per key until a generation of the page or the palette's page changes; at most
    `MaxHashCacheEntries` kept, least recently used out first; `id` changes whenever the answer does;
  - `void SetDumpDirectory(std::string dir, std::string config_path);` (empty: no dumping).
- Rules (Task 1 confirms or revises; write each down): in upload mode a draw's texture is the upload containing the
  draw's word rect, and nothing if none does (not dumped, not replaced); in page mode (`DumpTexturePages`) it is the
  page, hashed per Task 1/3's finding (whole page or the drawn rect); either needs every block it reads and its
  palette's known; its name's palette range is the used range with `ReducePaletteRange`, else the mode's full range;
  each name is dumped once per session, with the union of the rects drawn from it in the frame it was first drawn (the
  whole page with `DumpFullTexturePages`), skipped if the file exists, if below the thresholds (in texels), if C16
  without `DumpC16Textures`, or if it has a replacement without `DumpReplacedTextures`; the first dump writes
  `config.yaml` (`TexturePackConfig::ToYAML`) when the game has neither config file; lookups gather the replacements
  of every upload in the page (upload mode) or of the page (page mode) and place them with `GetPageRect`.

- [ ] **Step 1: Write the failing tests:** `ADrawFromAnUploadIsDumpedOnceAtTheEndOfTheFrame` (one PNG named by
  `TextureNames::Format`, nothing before `EndFrame`, nothing new on a second frame);
  `TheDumpsRectIsTheUnionDrawnThatFrame`; `ADrawAcrossTwoUploadsIsNotDumpedInUploadMode`; `PageModeNamesThePage`;
  `FullPagesDumpTheWholePage`; `SmallTexturesBelowTheThresholdAreNotDumped`; `C16IsDumpedOnlyWhenAsked`;
  `ReplacedTexturesAreDumpedOnlyWhenAsked`; `TheFirstDumpWritesAConfig`; `AnExistingConfigIsNotOverwritten`;
  `ATextureOnUnknownBlocksIsNeitherDumpedNorLookedUp`; `TheReducedRangeNamesTheUsedIndices`;
  `ALookupFindsAReplacementAndPlacesIt` (an index built from a temporary folder holding a name for an upload at words
  (16, 0) gives one placement at page texels (64, 0)…); `ALookupWithNoReplacementIsNull`;
  `ASourceIsLookedUpAgainAfterItsPageChanges` (same key, a write into the page: a new answer, and `nullptr` once the
  texture under the name is gone); `APaletteChangeIsANewLookup`; `TheLookupCacheHoldsAtMostItsLimit`.
- [ ] **Step 2: Run** `--gtest_filter='GPUTextureCache*'`; expect the new tests to fail to build.
- [ ] **Step 3: Implement.** Dumps are written synchronously in `EndFrame` with `RGBA8Image::SaveToFile`.
- [ ] **Step 4: Run** the filter, then all; expect PASS.
- [ ] **Step 5: Write down** the identification and dump rules; **commit.**

### Task 10: The cache in the hardware renderer: tracking and dumping

**Files:**
- Modify: `src/core/gpu_hw.h`, `src/core/gpu_hw.cpp` — `Initialize` (`:238`), `Reset` (`:284`), `DoState`
  (`:304-376`), `UpdateSettings` (`:387`), `AddDrawnRectangle`/`AddUnclampedDrawnRectangle` (`:727-740`), `FillVRAM`
  (`:3044`), `UpdateVRAM` (`:3157`), `CopyVRAM` (`:3263`), `DispatchRenderCommand` (`:3378`), `LoadVertices` (UV
  rects, `:2334`), `UpdateDisplay` (frame end, `:3600`)
- Modify: `src/core/system.cpp` (`:1804`, `:4128`, `:4507-4511`: reload on the new keys), `src/core/hotkeys.cpp:510`

**Interfaces:**
- Consumes: `GPUTextureCache` (Tasks 8–9), `TextureReplacements::GetConfig`, `GetTextureDumpDirectory` (Task 6).
- Produces: `std::unique_ptr<GPUTextureCache> m_texture_cache` in `GPU_HW`, present only when
  `g_settings.texture_replacements.ShouldTrackTextures(g_settings.gpu_texture_cache)`.

TDD: the logic is Tasks 4–9's; this task is wiring, checked by runs.

- [ ] **Step 1: Wire it.** Create the cache in `Initialize` and when `UpdateSettings` turns it on, seeding the shadow
  with a full `ReadVRAM` then `Reset(g_vram)`; `Reset(g_vram)` after a state load too (the state's VRAM is exact;
  uploads are forgotten, written down). Forward writes, fills and copies with their mask flags; drawn rectangles to
  `Draw`; while dumping, each textured primitive's UV rect (computed as `ComputePolygonUVLimits` does, texture window
  applied, as `CheckForTexPageOverlap` does) to `AddDrawnTexture`; `EndFrame` from `UpdateDisplay`.
- [ ] **Step 2: Dumps appear.** `duckstation-regtest -renderer Metal -frames 10800 -setting GPU/EnableTextureCache=true
  -setting TextureReplacements/DumpTextures=true` over Vagrant Story writes dumps under
  `<dumpdir>/<title>/textures/SLUS-01040/dumps/` and a `config.yaml`; their kinds, sizes and rects look like Task 1's
  run (a) (compare the name lists with a script; record the counts).
- [ ] **Step 3: Invisible.** Frame dumps every 300 frames with the cache on (dumping) and off are byte-identical
  (`cmp`), on Metal.
- [ ] **Step 4: Commit.**

### Task 11: Drawing replacements

**Files:**
- Modify: `src/core/gpu_hw.h` (`BatchTextureMode` gains `Replaced` before `MaxCount`, so `NUM_TEXTURE_MODES` is 8;
  `BatchConfig` gains `const GPUTexture* source`), `src/core/gpu_hw.cpp` (`CompilePipelines` `:940`,
  `DispatchRenderCommand`, `FlushRender` `:3549`, `DrawBatchVertices`), `src/core/gpu_hw_shadergen.{h,cpp}`
  (`GenerateBatchVertexShader` `:60`, `GenerateBatchFragmentShader` `:698`, a source blit shader)
- Modify: `src/core/texture_replacements.cpp` (`PreloadTextures` covers texture replacements)

**Interfaces:**
- Consumes: `GPUTextureCache::LookupSource`, `Source`, `Placement` (Task 9); `TexelToSourceRGBA`, `DecodeTexture`
  (Task 7); `TextureReplacements::GetTextureImage` (Task 6).
- Produces, in `GPU_HW`: `GPUTexture* GetReplacementSource(const GPUTextureCache::Source& source)` — the composed
  texture for `source.id`, made on first use and kept in an LRU bounded by `MaxHashCacheVRAMUsageMB`.

TDD: shaders and GPU composition have no harness here; the CPU side is Task 9's. Checked by frame dumps.

- [ ] **Step 1: Compose sources.** For a new `id`: decode the page at 1× with `TexelToSourceRGBA` into an
  `RGBA8Image`, upload it, draw it nearest into an RGBA8 render target of `256 × scale` square, then draw each
  placement's image into `page_rect × scale` with the linear sampler if `ReplacementScaleLinearFilter`, else nearest.
  New pipeline `m_texture_source_blit_pipeline` (the VRAM replacement write shader's shape, RGBA8 target, no blend,
  no depth), compiled only with the cache on.
- [ ] **Step 2: Replaced batches.** With the cache on, `DispatchRenderCommand` looks the source up whenever the page,
  palette or semi-transparency changes or the page's generation moves; a found source makes the batch's texture mode
  `Replaced` (sprites too) and a different source than the batch's flushes it. `FlushRender` binds the source at slot
  0 for a `Replaced` batch and the VRAM read texture back after. Replaced pipelines are compiled only with the cache
  on (all `NUM_TEXTURE_MODES` loops skip `Replaced` otherwise).
- [ ] **Step 3: The shader.** `REPLACED`: texture coordinates stay in page texels (not scaled, as palette modes);
  UV limits as palette modes, extended by one texel at the upper edge; the texel is sampled from the source at
  `(ApplyTextureWindow(floor(coords)) + frac(coords)) / 256` with `SAMPLE_TEXTURE`; alpha 0 is a transparent texel,
  alpha below 1 sets the semi-transparency bit (`< 255`, the published rule), otherwise clear; texture filtering
  enhancements (bilinear, JINC2, xBR) don't apply to replaced batches. Write each rule down.
- [ ] **Step 4: Check with our own pack.** From Task 10's dumps, copy three to `replacements/`, scaled 4× and drawn
  over with a solid mark (a script with Pillow in `/tmp/tc-tools/`), one renamed to a smaller rect (a crop) and one
  through an alias; regtest frame dumps on Metal show the marks at the right places and the crop only over its rect;
  without the pack the frames match Task 10's cache-off frames.
- [ ] **Step 5: Overwrites.** Over VS's dialogue (its font page is rewritten as text changes), the marked glyph
  textures show only while their text is on screen (frame dumps across two dialogue lines).
- [ ] **Step 6: Preloading.** With `PreloadTextures` on, Step 4's pack loads at boot (the log's count) and draws the
  same frames.
- [ ] **Step 7: Run** all of `common-tests`; expect PASS. **Commit.**

### Task 12: The hash experiment, part 2 (pages), and the references as a test

Shares Task 3's two-day bound.

**Files:**
- Create: `src/common-tests/texture_cache_reference_tests.cpp` (opt-in: reads `$GOOSE_TEST_DATA/texture-refs/`, skips
  without it)
- Create: `src/common-tests/data/texture-names/SLUS-01040-reference-<run>.txt` (the black box's dump names from Task
  1, one per line; names only)
- Modify: `src/core/gpu_texture_data.cpp` (only if the experiment changes a rule), `docs/texture-replacement.md`

- [ ] **Step 1: Pages.** Compare Task 10's page-mode names (run with the font pack's `config.yaml`) against Task 1's
  run (c) and the font pack: fields first, then hashes. If the hashes differ, capture our page words (local patch, as
  in Task 3) and search page-wide and rect-only layouts with Task 3's functions.
- [ ] **Step 2: The reference test.** `TextureCacheReference.OurNamesMatchTheReferences`: for each kind marked
  compatible in the rules section, every reference name of that kind appears among our dump names saved by Task 10's
  run for the same stretch (kept in `$GOOSE_TEST_DATA/texture-refs/SLUS-01040/ours/`). Run it with
  `GOOSE_TEST_DATA=~/work/goose-test-data build/bin/common-tests --gtest_filter='TextureCacheReference*'`; expect PASS,
  and SKIPPED without the variable.
- [ ] **Step 3: The font pack.** With the pack's `textures/SLUS-01040/` in the regtest data root and
  EnableTextureReplacements on, frame dumps over the intro's dialogue show the HD font, if page names are compatible;
  if not, say so in the rules and the roadmap.
- [ ] **Step 4: Write down** per kind (texupload and texpage; each mode seen; palette ranges; crops) "compatible,
  checked by <how>", or "our own hashes", and what other games would be needed for the kinds VS doesn't show (ask
  the user which they own). **Commit.**

### Task 13: Renderers and frame time

- [ ] **Step 1: The Mac.** Metal, Vulkan (MoltenVK) and OpenGL with regtest: Task 11 Step 4's own pack and Task 12
  Step 3's font pack, frame dumps checked; then the Qt app (`build/bin/GooseStation.app`) on each, with and without a
  pack, for the user to look at (no screen capture here): ask them to. The legacy path still works: a `vram-write-`
  dump from `DumpVRAMWrites`, marked and put back with `EnableVRAMWriteReplacements`, shows in the frame dumps with
  the cache on and off.
- [ ] **Step 2: Windows.** Ask the user for the Windows machine's SSH connection and whether it has a build setup;
  build there (`msbuild duckstation.sln -p:Platform=x64;Configuration=ReleaseLTCG-Clang`, as CI does), or, with the
  user's go-ahead, take CI's Windows artifact. Run regtest on D3D11, D3D12, Vulkan and OpenGL with the own pack and the
  font pack; bring the frame dumps back and check them.
- [ ] **Step 3: Frame time.** On Metal and Vulkan here and D3D11 on Windows: regtest over 3600 frames of the intro,
  three runs each with the cache off, on without a pack, and on with dumping, at 1× and 4×; median wall time per
  frame. Record the table in the rules section; a difference over 5% with the cache on and no pack is a finding to fix
  before going on (profile it, `superpowers:systematic-debugging`).
- [ ] **Step 4: Commit** the recorded results.

### Task 14: Docs, roadmap, finishing

- [ ] **Step 1:** Re-read every changed file; tighten. For the cache files, a `code-simplifier:code-simplifier` pass.
- [ ] **Step 2:** `docs/texture-replacement.md`: every rule from Tasks 1–13 is there with how it was established;
  the compatibility table per kind; what stages 2 and 3 add.
- [ ] **Step 3:** `docs/roadmap.md` item 20: stage 1 done on desktop (which kinds are compatible), stages 2 and 3
  left.
- [ ] **Step 4:** Build `common-tests duckstation-regtest duckstation-qt`; run `build/bin/common-tests`; expect PASS.
  Commit; push `claude/texture-cache`.
- [ ] **Step 5:** `superpowers:finishing-a-development-branch`; a PR to `master` only when the user says so.
