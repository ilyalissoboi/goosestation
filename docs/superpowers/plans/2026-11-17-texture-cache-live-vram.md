# Replaced pages read live VRAM — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A draw from a page with replacements takes the replacements' texels from a texture that holds only the
replacements, and every other texel from live VRAM through the normal 4-bit, 8-bit or direct path. VRAM uploads
convert 5-bit colour to 8 bits exactly on every GPU.

**Architecture:** The composed page (`GPU_HW::GetReplacementSource`) stops decoding the page on the CPU. It's cleared
to 0 and each replacement is drawn into it by a new compose shader that writes a coverage marker in alpha. Replaced
batches get one texture mode per real mode (4-bit, 8-bit, direct, each with a sprite twin). Each is the normal mode's
fragment shader with a lookup into the composed page (slot 1, `samp1`) in front of it: where the marker says
"covered", the replacement's texel is used; elsewhere the normal path runs on the VRAM read texture (slot 0). These
pipelines use `GPUPipeline::Layout::MultiTextureAndUBO`. Separately, `RGBA5551ToRGBA8` in the VRAM write shader writes
`n/255` with `n = (c×255+15)/31`, which every GPU converts to `n` exactly.

**Tech Stack:** C++20, the renderer's shader generator (HLSL/GLSL/SPIR-V/MSL from one source), `goosestation-regtest`
frame dumps, googletest (`common-tests`), MSVC on `pcsx2-win`, Android NDK build (`android/scripts/configure-native.sh`).

**Spec:** this plan; the rules in `docs/texture-replacement.md` ("Drawing replacements"); stage 1's plan
`docs/superpowers/plans/2026-11-16-texture-cache-stage-1.md` (Tasks 11 and 13 for how frames were checked).

## Global Constraints

- **Clean room:** nothing from DuckStation's code after `a5bf1ba5cee7`, as in stage 1's plan.
- **Cache off:** no Replaced pipelines compiled, nothing else changes, except the exact-upload fix. On the Mac
  (exact GPUs) frames with the cache off are byte-identical before and after that fix, on Metal, Vulkan and OpenGL.
- **Identity:** a pack of our own 1× dumps draws frames byte-identical to the cache off, at 1× and 4×, on Metal,
  Vulkan (MoltenVK), OpenGL, D3D11, D3D12, Vulkan and OpenGL on Windows, and OpenGL ES and Vulkan on the Android
  emulators.
- **Coverage marker** (alpha of the composed page): uncovered 0, transparent 85, semi-transparent 170, opaque 255.
  The batch shader reads it nearest and classifies with thresholds 42.5, 127.5 and 212.5 (over 255). A replacement
  pixel's alpha maps as now: 0 is transparent, below 255 is semi-transparent, 255 is opaque. Opaque black stays opaque.
- **Filtering:** `ReplacementScaleLinearFilter` filters when composing only. Draws sample the composed page nearest.
  Texture filtering enhancements stay off for replaced batches.
- **Not changed:** `GPUTextureCache` (lookups, ids, invalidation), dumping, the legacy `vram-write-` path, the
  software renderer.
- **Tests:** shader behaviour has no unit-test harness, so TDD is skipped for shader and pipeline code (stated per
  task). Its red/green is regtest frame comparisons with fixed commands. `build/bin/common-tests` passes after every
  task.
- **Data:** game frames, dumps and packs stay outside the repo, under `~/work/goose-test-data/texture-live/`. Helper
  scripts go in `/tmp/tc-tools/`. Use `python3 -I` with Pillow for image diffs.
- **Commits:** one per task, message ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`, shown after
  committing. Work on this worktree's branch. No push, branch rename or PR until the user says so.
- **Stage 2** (`claude/texture-cache-stage-2`, another session) also edits `gpu_hw.cpp`. Whichever lands second
  rebases. Touch only the Replaced code and `RGBA5551ToRGBA8`.

## Review Focus

1. **Direct-colour (C16) replaced pages at 2× and above.** Coordinates arrive upscaled (`v_tex0 × scale`). The
   replacement lookup must divide by the scale and floor, as the direct path does, or replacements shift by a texel.
   Covered by Task 3 Step 6 (synthetic C16 check).
2. **The last texel of a UV-clamped draw.** The replacement lookup uses the extended clamp (replacement detail inside
   the last texel). The fallback must use the normal clamp, or uncovered edges differ from cache-off. Covered by the
   identity runs at 1× and 4× (Task 3 Step 5).
3. **Texture windows that wrap inside a replaced page.** Both lookups apply the window to the integer texel first.
   Covered by the identity runs (VS uses windows in its opening).
4. **A batch switching between replaced and normal pipelines (layout change mid-pass) on Vulkan/D3D12, with ROV or a
   feedback loop.** Slot 1 must be bound (or null) whenever a multi-texture pipeline draws. Covered by Task 5's
   D3D12/Vulkan runs and Task 3 Step 7 (Vulkan validation on the Mac).
5. **Pages with GPU-rendered blocks.** These are now drawn from VRAM. Covered by Task 1 Step 4 / Task 3 Step 5 (the
   live-content scene).

---

### Task 1: Harness, packs and baselines

No product code. Recreates what stage 1's `/tmp` tooling did (gone since).

**Files:** outside the repo only: `/tmp/tc-tools/rt.sh`, `/tmp/tc-tools/framediff.py`, `/tmp/tc-tools/mark.py`,
`~/work/goose-test-data/texture-live/`.

- [ ] **Step 1: Build.** Configure `build/` in this worktree as the main checkout's desktop build is configured
  (see `docs/`; deps at `goosestation-deps`). Build targets `common-tests duckstation-regtest` (binary `build/bin/goosestation-regtest`), run `build/bin/common-tests`,
  expect PASS.
- [ ] **Step 2: `rt.sh <renderer> <scale> <outdir> [settings…]`.** It runs regtest on `Vagrant Story (USA).chd` for
  12,000 frames, dumping every 300 frames (40 frames), with `GPU/ResolutionScale=<scale>`. It handles the stage-1
  quirks: `build/bin/resources` links to `data/resources`, regtest's `.metallib` sits in `build/bin`, and
  `DYLD_LIBRARY_PATH` is set to the deps' `lib`. `framediff.py <a> <b>` prints per frame the number of differing
  pixels and the largest channel difference, and exits 0 only if all frames are byte-identical.
- [ ] **Step 3: Packs.** Identity pack: one run with `GPU/EnableTextureCache=true`,
  `TextureReplacements/DumpTextures=true` at 1×, its dumps moved to `texture-live/packs/identity/replacements/`.
  Marked pack: `mark.py` takes three of those dumps, scales them 4× and draws a 2-texel magenta border on each. One
  is renamed to a smaller rect (a crop) and one is reached through an `Aliases` entry.
- [ ] **Step 4: The live-content scene.** Add a temporary, uncommitted log in `GetReplacementSource`: when a composed
  page's 256×256 rect has blocks where `!m_texture_cache->IsKnown(page word rect)`, log the frame number and the page.
  Run the identity pack, pick up to three logged frames where such a page is drawn, and add them as extra dump points
  (regtest `-dumpinterval` plus a `-statefile` near them if needed). If VS's opening has no such page, run from
  `SLUS-01040_1.sav` and `_2.sav` too. If none shows it there either, say so and ask the user for a scene. Revert
  the log.
- [ ] **Step 5: Baselines** at commit `6766c7ceb` with today's code, into `texture-live/base/`: cache off and identity
  pack, at 1× and 4×, on Metal, Vulkan and OpenGL, plus the marked pack on Metal at 1×. Expected, per the stage-1
  ledger: identity equals cache off on all three. Record the live-content frames' diff counts: these are the red
  for Task 3 (expected non-zero where the page shows GPU-rendered texels).
- [ ] **Step 6:** Write the commands and results to `texture-cache-live-vram-ledger.md` in the shared notes folder.
  No commit.

### Task 2: Exact 5-to-8-bit conversion on VRAM uploads

TDD skipped: shader-only, no harness. Red/green is Task 5's Windows identity counts (15/40 frames differ today).

**Files:**
- Modify: `src/core/gpu_hw_shadergen.cpp:39-47` (`RGBA5551ToRGBA8`)
- Modify: `docs/texture-replacement.md` ("Drawing replacements", the paragraph on Windows' 2/255)

- [ ] **Step 1:** `RGBA5551ToRGBA8` returns `float((c * 255u + 15u) / 31u) / 255.0` per colour channel (alpha as now),
  with a comment saying why: `c/31` lets D3D/Vulkan/GL GPUs land within 0.6 of a step, while `n/255` converts to `n`
  exactly. Same rounding as the CPU's `(c × 255 + 15) / 31`. Its one caller is the VRAM write shader (`:1470`).
  Check with `grep -n "/ 31.0" src/core/gpu_hw_shadergen.cpp` that no other path writes *uploaded* colours to VRAM.
  Leave the batch output (`o_col0.rgb /= 31.0`) as it is: rendered pixels are the same in both paths.
- [ ] **Step 2:** `rt.sh` cache off at 1× and 4× on Metal, Vulkan and OpenGL. `framediff.py` against Task 1's
  baselines: 0 differing frames everywhere (these GPUs already round exactly).
- [ ] **Step 3:** Docs: VRAM uploads convert exactly on every GPU (ours). Mark the "≤2/255 on Windows" note as
  pending Task 5. `common-tests` PASS. **Commit:** "GPU/HW: VRAM uploads convert 5-bit colour exactly on every GPU".

### Task 3: Replaced pages: replacements in their own texture, the rest from VRAM

TDD skipped: renderer and shader code without a unit harness. Red/green is Task 1 Step 5's live-content frames
(red) against Step 5 here (green), with the identity frames held at zero difference throughout.

**Files:**
- Modify: `src/core/gpu_hw.h` (`BatchTextureMode`, helpers, the `BatchConfig::source` comment,
  `m_texture_source_blit_pipeline` comment)
- Modify: `src/core/gpu_hw.cpp`: `s_batch_texture_modes` (`:57-60`), `IsTextureModeCompiled` (`:326-335`),
  `GetReplacementSource` (`:345-465`), shader/pipeline compile loops (`:1191-1500`), compose pipeline (`:1658-1672`),
  `DrawBatchVertices` (`:2052-2068`), `DispatchRenderCommand` lookup (`:3711-3720`), `FlushRender` binding
  (`:3855-3883`)
- Modify: `src/core/gpu_hw_shadergen.h/.cpp`: `GenerateBatchFragmentShader` (`:697-1080`), new
  `GenerateReplacementComposeFragmentShader`

**Interfaces:**
- Produces, `gpu_hw.h`:
  `enum class BatchTextureMode : u8 { Palette4Bit, Palette8Bit, Direct16Bit, Disabled, SpritePalette4Bit,
  SpritePalette8Bit, SpriteDirect16Bit, ReplacedPalette4Bit, ReplacedPalette8Bit, ReplacedDirect16Bit,
  SpriteReplacedPalette4Bit, SpriteReplacedPalette8Bit, SpriteReplacedDirect16Bit, MaxCount,
  SpriteStart = SpritePalette4Bit, ReplacedStart = ReplacedPalette4Bit, SpriteReplacedStart = SpriteReplacedPalette4Bit }`
  (`Replaced`/`SpriteReplaced` removed), plus `static constexpr` helpers in `GPU_HW`:
  `bool IsReplacedTextureMode(BatchTextureMode)`, `bool IsSpriteTextureMode(BatchTextureMode)`,
  `BatchTextureMode GetBaseTextureMode(BatchTextureMode)` (returns `Palette4Bit`/`Palette8Bit`/`Direct16Bit`, or
  `Disabled`). `m_batch.texture_mode` holds a non-sprite mode, as today: `ReplacedPalette4Bit`…`ReplacedDirect16Bit`.
  `DrawBatchVertices` adds 3 for sprites on replaced modes and 4 on normal modes.
- Produces, `gpu_hw_shadergen.h`: `GenerateBatchFragmentShader(render_mode, transparency, BatchTextureMode
  texture_mode /* base mode */, bool replaced, texture_filtering, uv_limits, …unchanged…)`;
  `std::string GenerateReplacementComposeFragmentShader()`.

- [ ] **Step 1: Modes.** Replace `Replaced`/`SpriteReplaced` with the six modes and names in
  `s_batch_texture_modes`. `IsTextureModeCompiled`: replaced modes need `m_replaced_pipelines`, sprite ones also
  `m_allow_sprite_mode`. Both compile loops get `sprite`, `palette`, the base mode and `replaced` from the helpers:
  - the vertex shader is the base mode's (palette or not, sprite or not);
  - `uv_limits` and `force_round_texcoords` are as for the base mode;
  - `layout` is `MultiTextureAndUBO` for replaced modes and `SingleTextureAndUBO` otherwise;
  - blending stays `textured && !replaced && IsBlendedTextureFiltering(…)`.

  `DispatchRenderCommand` makes `ReplacedStart + base` from the batch's real mode when a source is found.
  `FlushRender` and `NeedsShaderBlending`/`NeedsTwoPassRendering` treat replaced modes as textured, as now.
- [ ] **Step 2: The batch shader.** With `replaced`:
  - `DeclareTexture(ss, "samp1", 1)` and macro `REPLACED`; filtering forced to `Nearest`, as now.
  - `PALETTE`, `PALETTE_4_BIT`, `PALETTE_8_BIT` and the vertex interface are the base mode's. The old `REPLACED`
    branch inside `SampleFromVRAM` goes; `SampleFromVRAM` is the normal one, untouched.
  - New `bool SampleReplacement(float2 coords, out float4 texel)`. It computes the page texel and the position
    within it the way the base mode picks its texel. Palette modes: `FloatToIntegerCoords`, sub-texel as the old
    `REPLACED` code. Direct: at `UPSCALED`, `ncoords = coords / RESOLUTION_SCALE`, texel `floor(ncoords)`, sub-texel
    `frac(ncoords)`; at 1× as palette modes. Then it applies the texture window to the texel, samples `samp1` at
    `(texel + sub) / 256` with `SAMPLE_TEXTURE_LEVEL` and classifies the marker. Uncovered: return false. Transparent:
    `TRANSPARENT_PIXEL_COLOR`. Semi: `float4(rgb, 1.0)`. Opaque: `float4(rgb, 0.0)`, with the existing opaque-black
    nudge. Covered cases return true.
  - In `main`: with `UV_LIMITS`, the replacement lookup gets the old extended clamp (`+0.499` at 1×/forced rounding,
    `+0.999` otherwise, palette modes only; direct uses its normal clamp). The fallback gets exactly the clamp
    cache-off uses. `texcol = SampleReplacement(rc, t) ? t : SampleFromVRAM(v_texpage, nc)`; the rest of `main`
    unchanged.
- [ ] **Step 3: Compose shader.** `GenerateReplacementComposeFragmentShader`: the copy shader's interface
  (`u_src_rect` push constant, `samp0`, `v_tex0`). Output `rgb` sampled and alpha mapped to the marker: `a ≤ 0.5/255`
  becomes 85/255, `a < 254.5/255` becomes 170/255, otherwise 1. `m_texture_source_blit_pipeline` uses it (rename to
  `m_replacement_compose_pipeline`), RGBA8 target, no blend, `SingleTextureAndPushConstants`.
- [ ] **Step 4: Composing.** `GetReplacementSource`: drop the `DecodeTexture` and the page blit. Clear the target to
  0 and draw each placement with the compose pipeline: linear sampler if `ReplacementScaleLinearFilter`, else
  nearest. Scale, budget, LRU and failure handling unchanged. `FlushRender` binds
  `SetTextureSampler(1, m_batch.source, nearest)` for replaced batches and leaves slot 0 alone. After the draws it
  sets `SetTextureSampler(1, nullptr, nearest)` so a recycled page is never left bound. Check that D3D11, D3D12,
  Vulkan, Metal and OpenGL bind slot 1 for `MultiTextureAndUBO` pipelines. Metal and D3D11 bind by slot, and OpenGL
  sets `samp1` by name (`opengl_pipeline.cpp:445-451`); if one doesn't, fix it there.
- [ ] **Step 5: Frames (green).** `rt.sh` on Metal, Vulkan and OpenGL:
  - identity pack against cache off at 1× and 4×: 0 differing frames, including the live-content frames that
    differed in Task 1 Step 5;
  - the marked pack at 1× on Metal against Task 1's marked frames: differences only inside pages Task 1 Step 4 logged
    (GPU-rendered texels now live), none at the marks. View two of those frames side by side to confirm the marks
    are drawn and the rendered area shows its live content.
- [ ] **Step 6: Direct colour.** VS's opening draws no C16 page. Check C16 with a synthetic pack: a `texpage-C16`
  name made from a direct-mode draw's dump (`DumpC16Textures: true` in a scratch `config.yaml`) if VS has one in
  `SLUS-01040_1.sav`/`_2.sav`. Otherwise say so in the ledger and the docs as unchecked. If one exists, the identity
  frames hold at 1× and 4×.
- [ ] **Step 7: Validation.** One Vulkan run with `-setting GPU/UseDebugDevice=true` (validation layers via
  MoltenVK) and the identity pack: no new validation errors against a cache-off run. `common-tests` PASS.
- [ ] **Step 8: Commit:** "GPU/HW: replaced pages draw their replacements over live VRAM".

### Task 4: Rules and cost written down

**Files:** Modify `docs/texture-replacement.md` ("Drawing replacements", "Cost").

- [ ] **Step 1:** Rewrite "Drawing replacements" for the new design (ours). The page as drawn holds only the
  replacements, with a coverage marker. Other texels, rendered ones included, come from VRAM as for any draw.
  `ReplacementScaleLinearFilter` filters when composing. Uploads convert exactly. Remove the "Not handled yet"
  paragraph. Replace the identity sentence with Task 3 Step 5's results (Windows and Android follow in Tasks 5–6).
- [ ] **Step 2: Cost.** Pipelines with the cache on: count before (9 texture modes) and after (13) from the
  "Compiling Pipelines" progress total. Pipeline compile time on Metal (regtest runs with `GPU/DisableShaderCache=true`, so always cold) before and after. Frame time, Metal 1× and 4×, identity pack, three runs, median, as the existing table.
- [ ] **Step 3: Commit:** "Docs: replaced pages read live VRAM".

### Task 5: Windows (`pcsx2-win`)

- [ ] **Step 1:** Copy the branch to `pcsx2-win` (`ssh pcsx2-win`, the checkout stage 1 used; `git bundle` over scp
  if it has no remote access to this branch). Build Release with VS 2026 MSVC as stage 1 did, and copy the identity
  pack, the marked pack and the game.
- [ ] **Step 2:** regtest on D3D11, D3D12, Vulkan and OpenGL, 1× and 4×: cache off, identity, marked. Bring the frame
  dumps back. Expected: identity equals cache off, 0/40 on all four (it was 15/40 before Task 2); marked frames equal
  Metal's at the marks. Cache-off frames against the pre-Task-2 Windows build: changes ≤2/255 only (the exact
  conversion), counted and recorded.
- [ ] **Step 3:** D3D12 and Vulkan once each with the debug device: no new errors. Pipeline count and compile time on
  D3D11 with the cache on.
- [ ] **Step 4:** Docs: the Windows results replace the ≤2/255 paragraph. **Commit:** "Docs: replaced pages on
  Windows renderers".

### Task 6: Android emulators (OpenGL ES, Vulkan)

- [ ] **Step 1:** Start both test emulators (`android/scripts/start-test-emulators.sh`). Try a regtest build for
  Android the way `run-native-tests.sh` builds `common-tests` (`configure-native.sh`, target `duckstation-regtest`).
  Push it with the game, the identity pack and a data root to `/data/local/tmp`, and run it with `-renderer Vulkan`
  and `-renderer OpenGL` (GLES there).
- [ ] **Step 2:** If regtest can't get a GPU context there (Android has no headless GL without a surface), stop at
  one hour. Report which part failed and ask the user before writing an instrumented frame test (an opt-in `core/`
  test with `FrameReader`, the game and pack pushed to the app's files dir).
- [ ] **Step 3:** Frames at 1× and 4×: identity equals cache off on GLES and Vulkan. Docs updated. **Commit:**
  "Docs: replaced pages on Android's renderers". Record everything in the ledger.

### Task 7: Finish

- [ ] **Step 1:** Re-read every changed file; tighten. `code-simplifier:code-simplifier` over the `gpu_hw*` diff.
- [ ] **Step 2:** `superpowers:verification-before-completion`: build targets `common-tests duckstation-regtest
  duckstation-qt`, run `build/bin/common-tests` (PASS), and re-run Task 3 Step 5's identity check on Metal.
- [ ] **Step 3:** Report to the session that started this one, then `superpowers:finishing-a-development-branch`.
  Branch name, push and PR only when the user says so.
