<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Full-screen bezels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A slang stage recognised as a bezel draws over the whole screen (window, screenshot, capture) with the game
picture as its source and the game's aspect ratio in librashader's frame options, so libretro's bezel presets fill the
side bars as they're made to; non-4:3 pictures fall back to drawing into the picture (except presets known to honour
the aspect); a per-stage "Draw over the whole screen" switch on Qt, Big Picture and Android; a light/heavy/very heavy
cost label beside every slang preset in the shader browser.

**Architecture:**
- **The rule is pure code.** `postprocessing_slang_picture.h` (no GPU) gains the placement rule: which stages want the
  whole screen (a `bezel` folder in the name, or the stage's `WholeScreen` key), the 4:3 test, the presets known to
  honour the aspect, and where the next stage's picture is after a whole-screen stage.
- **One stage changes how it draws.** `SlangShader::Apply` keeps today's path, byte for byte, for every stage that
  draws into the picture (frame options still `nullptr`). A whole-screen stage feeds the preset the picture at the
  console's resolution as today, but runs it into a texture the target's size, with a `SlangFrameOptions`
  (aspect ratio; rotation 0) passed to the renderer's glue, and copies that over the whole target.
- **The chain follows.** `Chain::Apply` asks each stage whether it drew the whole screen; the stages after it get the
  whole target as their picture and `final_rect`, so a GLSL or CRT stage after a bezel doesn't blank the bars.
- **The presenter tells the stage the aspect.** `PictureInfo` gains `aspect_ratio` (the display aspect after
  rotation; 0 for the internal chain, which never draws whole-screen), set from `GPUDisplayParams::display_aspect_ratio`.
- **Cost is a parse.** `postprocessing_slang_cost.{h,cpp}` reads a `.slangp` and its `#reference` chain as text and
  sums passes and pixels drawn at 1920×1080 from a 320×240 source; the browsers ask for a label per listed preset,
  cached by path.

**Tech Stack:** C++20 (`src/util`, `src/core`), gtest (`src/common-tests`, including the opt-in GPU tests:
`GOOSESTATION_GPU_TEST_API` = Metal/Vulkan/OpenGL/OpenGLES/D3D11/D3D12 with `GOOSESTATION_LIBRASHADER_PATH`),
librashader 0.12 C API (`dep/librashader/include/librashader.h`, API 5), Qt 6 widgets, Dear ImGui (Big Picture),
Kotlin/Compose + JNI (Android), the Android test emulators and the AYN Thor.

**Spec:** `docs/superpowers/specs/2026-11-28-full-screen-bezels-design.md`.

## Decisions for the user (recommendation first in each)

1. **How a whole-screen stage reaches librashader.**
   - **A. Only whole-screen stages change (recommended).** The stage's result texture becomes the target's size and
     its glue gets frame options (`aspect_ratio`, `rotation` 0, librashader's defaults for the rest); a stage drawing
     into the picture still passes `nullptr`, so CRT presets and every in-picture stage run exactly today's calls.
     The chain is made with `original_aspect_uniforms` on only for stages that want the whole screen (they're rebuilt
     when the switch changes). Con: two paths in `Apply`.
   - B. Every slang stage gets frame options and original-aspect uniforms. Pro: one path. Con: koko-aio, scanline-classic
     and subframe-bfi drawn into the picture would read a real aspect instead of their 4:3 fallback, a change to a
     non-bezel effect (an anti-goal).
2. **Recognising a bezel.**
   - **A. A path component named `bezel` (any case) anywhere in the stage name, plus the switch (recommended).**
     Covers `shaders_slang/bezel/…` from the pack and `bezel/…` in the shaders folder; nothing else in the pack is a
     bezel (no preset outside `bezel/` references one) and the pack has no declaration convention, so no declaration
     is invented. Con: a player's own bezel preset saved elsewhere needs the switch.
   - B. Also recognise bezel families by their parameters (`HSM_*`, `fr_*`, `BEZEL_*`) after loading. Pro: catches
     copies. Con: koko-aio's CRT-only presets share its bezel parameters, so they'd go whole-screen wrongly.
3. **"4:3" and the aspect fallback.**
   - **A. Within 2% of 4:3 (recommended)**, from `display_aspect_ratio` (Auto with small crops still counts; 16:9
     patches, 16:9/19:9/20:9, Match Window and PAR 1:1 on wide modes fall back). Presets known to honour the aspect: a
     list of name prefixes, starting with `bezel/koko-aio/` (reads `OriginalAspect` under
     `_HAS_ORIGINALASPECT_UNIFORMS`) and only after Task 11 shows it places a 16:9 picture unsqueezed; scanline-classic
     joins if the same check passes. Con: a hand list.
   - B. Scan each preset's shader sources for `_HAS_ORIGINALASPECT_UNIFORMS` at load. Pro: general. Con: reading and
     following every `#include` at load, and reading the define isn't the same as placing the picture right.
4. **Rotation.**
   - **A. Rotation 0 always, with the turned picture's aspect (recommended).** The picture already reaches the stage
     turned (the presenter draws it rotated; a rotated chain never reads the game's own frame), and librashader's
     `rotation` turns the whole output, which would turn the bezel too. So 90°/270° give a 3:4 aspect and fall back
     to drawing into the picture (the design accepts this), 180° stays 4:3 and draws over the whole screen, and a
     preset honouring the aspect gets 0.75.
   - B. Pass the display rotation. Con: the TV frame itself would lie on its side.
5. **The switch.**
   - **A. Key `WholeScreen` in the stage's section, `true`/`false`, absent meaning automatic (recommended).** Every UI
     shows the effective value (the key, else the folder rule) as a checkbox row first among the stage's options;
     changing it writes the key; Qt's Reset to defaults removes it (Big Picture and Android have no per-stage reset). Favourites and copies carry it with the stage's other
     keys. Con: a parameter called `WholeScreen` in some preset would clash (none in the pack).
   - B. A three-way choice (Automatic / On / Off). Con: one more state for players to understand.
6. **The placement rows.**
   - **A. A note, rows left working (recommended).** When the display chain in effect (the game's own, else the
     global one) is enabled and has a slang stage whose effective switch is on, the Scaling, Alignment and Stretch
     Vertically rows show "A bezel draws over the whole screen: this applies only to games it falls back for (not
     4:3)." (Qt: the rows' tooltip and a line under the group; Big Picture: the rows' summaries; Android: the rows'
     summaries). They stay editable, since a non-4:3 game still uses them.
   - B. Disable the rows. Con: wrong for the fallback.
7. **The cost formula.**
   - **A. score = pixels drawn (in 1080p screens) + 0.1 × passes; light below 5, heavy below 10, very heavy from 10
     (recommended).** Estimated with a 320×240 source and a 1920×1080 viewport, `#reference` followed (later files win),
     textures not counted. From the design's measurements: uborder 1.0 + 0.1 = 1.1 light, Potato 2.3 + 1.0 = 3.3 light,
     koko-aio 2.1 + 1.7 = 3.8 light, crt-guest-advanced 3.0 + 1.2 = 4.2 light, crt-royale 5.3 + 1.2 = 6.5 heavy, Mega
     Bezel standard 9.0 + 3.0 = 12.0 very heavy, Smooth-Advanced 13.4 + 4.8 = 18.2 very heavy. Worked out per preset the
     browser lists (a folder at a time; search results too), cached in memory by path.
   - B. Pixels only. Con: Mega Bezel's cost on the Thor (50× Potato for 4× the pixels) is more about its passes.
8. **Frames on desktop.**
   - **A. Offscreen frames on a real device through the opt-in GPU tests (recommended).** A dump test (Task 1, added
     before any behaviour change) renders a list of presets and GLSL/ReShade stages through `PostProcessing::Chain`
     at several picture placements (integer scaling larger than the target, a crop, a 180° and a 90° turned picture)
     and writes raw frames; run at that commit and at the end on the Mac (Metal, MoltenVK) and `pcsx2-win` (D3D11,
     D3D12, Vulkan, OpenGL), compared byte for byte. Bezel frames from Vagrant Story's picture (`shots/shot_0.png`
     from the design's Thor run, cropped to its 4:3 area) saved as PNGs for the PR and the user. Android's FrameReader
     test has a real surface (emulators, the Thor). Con: Qt's window itself isn't captured (screen capture isn't
     permitted on this Mac; Windows is reached over SSH only).
   - B. Run the Qt app and take its own screenshots by hand. Con: not repeatable, and no before/after comparison.

## Global Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`; libretro's presets run as downloaded, never shipped.
- One stage, one PR; `docs/roadmap.md` item 30 reworded to what's built; item 29's "won't implement" ships with it.
- **No warnings while playing**; the cost label only in the browser, never by running a preset.
- **No cost when no bezel stage is in the chain**: no new textures, chain rebuilds or frame options for in-picture
  stages; non-bezel stages' calls unchanged.
- The OSD, Big Picture and achievement overlays draw on top; screenshots and captures with post-processing include
  the bezel.
- Copy: the switch is "Draw over the whole screen"; labels "light", "heavy", "very heavy".
- Commit messages in the repo's style (`Area: what changed`), ending with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; each shown to the user after the commit.
- Thor: adb `64dc3c35`, the `.debug` app and `/sdcard/GooseTest` only; never the release app or the user's data folder;
  cleaned up afterwards. Windows: `ssh -o HostName=192.168.68.66 -o HostKeyAlias=192.168.68.64 pcsx2-win`; copy
  dependency folders, never junction them.
- Android test tiers from a detached worktree (copy `android/local.properties` and the emusync AAR in);
  `run-native-tests.sh` with `ANDROID_SERIAL=emulator-5580`; read the result XMLs, not Gradle's last line.

## Review Focus

1. **A game that changes aspect mid-play** (a 4:3 menu, a 16:9 patch toggled from the pause menu, a 24-bit FMV with
   "force 4:3"): the stage switches between whole-screen and in-picture on the next frame without a chain rebuild and
   without the previous frame's bars left behind (Task 3: `ThePlacementFollowsTheAspectEachFrame` GPU test).
2. **A stage after a bezel**: a GLSL stage, a CRT slang preset and a ReShade shader after a whole-screen stage keep the
   bezel in the bars (Task 4: `AStageAfterABezelKeepsTheBars`).
3. **A window or screenshot of another shape** (a portrait window, a 4:3 window, Android's letterboxed emulator, a
   screenshot at the game's own size): the preset gets that target as its viewport and nothing is drawn off it
   (Task 3: `AWholeScreenStageFillsAnyTargetShape` with a 48×64 target).
4. **The switch flipped while a game runs**, both ways, on a preset with the folder rule and one without: the chain is
   rebuilt once with or without original-aspect uniforms, and the parameter values survive (Task 2:
   `TheSwitchRebuildsTheChainKeepingParameters`).
5. **Broken presets for the cost label**: a missing `#reference` target, a reference loop, `shaders` missing or not a
   number, a scale of 0 or negative, absurd sizes → a label still comes back (or none for an unreadable file), never a
   hang or a crash (Task 6: `BrokenPresetsStillGetALabelOrNone`).

---

### Task 0: Baselines and scratch

**Files:** none committed. Scratch in `/tmp/goose-fsbezel/` (already holds `BezelMeasureTest.kt`, `cost.py` and the
design's `shots/` copied from `/tmp/goose-bezel`).

- [ ] **Step 1:** Remove the design session's scratch: `git worktree remove --force /tmp/goose-bezel/src` (from the main
  checkout), keep `/tmp/goose-bezel/slang` until Task 12 then `rm -rf /tmp/goose-bezel`; leave the Thor's
  `/sdcard/GooseTest`, `.debug.test` APK and `cache/bezel-shots` for Task 12's reuse and its cleanup.
- [ ] **Step 2:** Build this branch's `common-tests` on the Mac (`build/`, with `librashader.dylib` from the app bundle
  and the `.metallib` files as in earlier slang work) and run the existing `SlangGPU.*` under Metal and Vulkan. Expected:
  all pass (the reference point for Task 1's dump).

### Task 1: A frame dump before anything changes

**Files:**
- Modify: `src/util/postprocessing.h:143-190` (`Chain` gains a test seam), `src/util/postprocessing.cpp`
- Modify: `src/common-tests/postprocessing_slang_gpu_tests.cpp` (use `GPUTestDevice` so D3D11/D3D12 run; new dump test)

**Interfaces — Produces:**
- `void Chain::SetStagesForTest(std::vector<std::unique_ptr<Shader>> stages);` (enables the chain; for tests only).
  Tests construct `SlangShader`, `GLSLShader` and `ReShadeFXShader` directly, as `postprocessing_gpu_tests.cpp` does.

- [ ] **Step 1:** `SlangGPU` uses `GPUTestDevice::Create` (so `GOOSESTATION_GPU_TEST_API=D3D11|D3D12` work).
- [ ] **Step 2:** Add `SlangGPU.DumpChainFrames`: skipped unless `GOOSESTATION_GPU_TEST_DUMP` (a folder) is set; stages
  from `GOOSESTATION_GPU_TEST_PRESETS` (slang, `|`-separated) plus a GLSL and a ReShade shader written by the test;
  input a 320×240 picture made from `GOOSESTATION_GPU_TEST_PICTURE` (raw RGBA) or a generated pattern; target 256×144;
  placements: centred 4:3 (192×144 at x 32), integer-scaled larger than the target (320×240 at (−32, −48)), cropped
  (180×120 at (38, 12)), turned 180° (same rect, `game_texture` null) and 90° (108×144, native 240×320). Each
  stage alone and the whole list as one chain (through `SetStagesForTest` + `CheckTargets` + `Apply`), each frame
  written as `<api>-<case>.rgba`.
- [ ] **Step 3:** Run it on the Mac (Metal, Vulkan) with crt-geom, crt-guest-advanced, crt-royale, ntsc-256px-svideo,
  `bezel/Mega_Bezel/Presets/MBZ__5__POTATO.slangp`, `bezel/uborder/uborder-bezel-reflections.slangp`,
  `bezel/koko-aio/koko-aio-ng.slangp`; keep the dump as `/tmp/goose-fsbezel/before/`. Same on `pcsx2-win` (D3D11,
  D3D12, Vulkan, OpenGL) into `C:\goose-fsbezel\before\`.
- [ ] **Step 4: Commit** (`Tests: a frame dump of the display chain's stages, for before and after`).

### Task 2: The placement rule and the switch

**Files:**
- Modify: `src/util/postprocessing_slang_picture.h`, `src/util/postprocessing_shader.{h,cpp}` (a hook for stage keys),
  `src/util/postprocessing_shader_slang.{h,cpp}`, `src/util/postprocessing.{h,cpp}` (config helpers)
- Test: `src/common-tests/postprocessing_slang_tests.cpp`

**Interfaces — Produces:**
- In `PostProcessing::Slang`: `bool IsBezelName(std::string_view name);` (a `/`- or `\`-separated component equal to
  `bezel`, any case); `bool HonoursAspect(std::string_view name);` (a component sequence `bezel/koko-aio`, any case,
  anywhere in the name; the list may grow in Task 11); `bool IsFourThree(float aspect);` (`std::abs(aspect * 3 / 4 - 1)
  <= 0.02f`); `bool WantsWholeScreen(std::string_view name, std::optional<bool> key);` (key, else `IsBezelName`);
  `bool DrawsWholeScreen(bool wants, bool honours_aspect, float aspect);` (wants && aspect > 0 && (honours ||
  IsFourThree(aspect))).
- `static constexpr const char* PostProcessing::Config::WHOLE_SCREEN_KEY = "WholeScreen";`
  `std::optional<bool> Config::GetStageWholeScreen(const SettingsInterface&, const char* section, u32 index);`
  `void Config::SetStageWholeScreen(SettingsInterface&, const char* section, u32 index, std::optional<bool>);`
  `bool Config::StageWantsWholeScreen(const SettingsInterface&, const char* section, u32 index);` (slang stages only)
  `bool Config::ChainHasWholeScreenStage(const SettingsInterface&, const char* section);` (enabled and any stage wants)
- `virtual void Shader::LoadStageSettings(const SettingsInterface& si, const char* section) {}` called at the end of
  `Shader::LoadOptions`; `SlangShader` reads `WholeScreen` there.
- `bool SlangShader::WantsWholeScreen() const;` When it changes after the chain exists, the chain is dropped and
  `CompilePipeline` rebuilds it (all parameters pushed again).

- [ ] **Step 1: Write the failing tests** (`SlangPlacement.*`, `SlangStage.*`):
  - `BezelNamesAreByFolder`: `shaders_slang/bezel/Mega_Bezel/Presets/MBZ__5__POTATO.slangp`, `bezel/x.slangp`,
    `Bezel\x.slangp` → true; `crt/crt-royale.slangp`, `bezels/x.slangp`, `mybezel.slangp`, `crt/bezel.slangp` → false.
  - `KokoHonoursTheAspect`: `shaders_slang/bezel/koko-aio/koko-aio-ng.slangp` true; Mega Bezel and uborder false.
  - `FourThreeWithinTwoPercent`: 4/3, 1.31, 1.36 true; 1.30, 1.37, 16/9, 0.75, 0 false.
  - `TheKeyOverridesTheFolder`: `WantsWholeScreen("crt/x.slangp", true)` true; `("bezel/x.slangp", false)` false;
    `("bezel/x.slangp", nullopt)` true.
  - `NonFourThreeFallsBackUnlessHonoured`: `DrawsWholeScreen(true, false, 16/9.f)` false, `(true, true, 16/9.f)` true,
    `(true, false, 4/3.f)` true, `(false, true, 4/3.f)` false, `(true, true, 0)` false.
  - `TheSwitchIsAKeyInTheStagesSection`: `SetStageWholeScreen(si, "PostProcessing", 0, false)` writes
    `[PostProcessing/Stage1] WholeScreen = false`; `nullopt` deletes it; `CopyChain` carries it.
  - `ChainHasWholeScreenStageNeedsAnEnabledChain`: a chain of `crt/x.slangp` + `bezel/y.slangp` enabled → true;
    disabled → false; `bezel/y.slangp` with the key false → false; a GLSL stage with the key true → false.
  - `TheSwitchRebuildsTheChainKeepingParameters` (Review Focus 4, native part): a `SlangShader` loaded from a
    hand-written preset reads `WholeScreen` through `LoadOptions`; `WantsWholeScreen()` follows the key both ways and
    `GetParametersToPush()` still lists changed parameters after a flip.
- [ ] **Step 2: Run** `build/bin/common-tests --gtest_filter='SlangPlacement*:SlangStage*:PostProcessingConfig*'`.
  Expected: FAIL (no `IsBezelName`).
- [ ] **Step 3: Implement** the functions above.
- [ ] **Step 4: Run** the filter and the whole `common-tests`. Expected: PASS.
- [ ] **Step 5: Commit** (`Post-processing: which slang stages want the whole screen, and the stage's switch`).

### Task 3: The whole-screen draw

**Files:**
- Modify: `src/util/gpu_device.h:477-488` (`SlangChain`), `src/util/gpu_device.cpp:1435` and the five glues
  (`vulkan_slang.cpp`, `metal_slang.mm`, `opengl_slang.cpp`, `d3d11_slang.cpp`, `d3d12_slang.cpp`),
  `src/util/librashader_loader.{h,cpp}` (`LoadPreset` takes the original-aspect flag),
  `src/util/postprocessing.h:94-112` (`PictureInfo`), `src/util/postprocessing_shader_slang.{h,cpp}`,
  `src/core/gpu_presenter.cpp:359-507`
- Test: `src/common-tests/postprocessing_slang_gpu_tests.cpp`

**Interfaces:**
- Consumes: Task 2's `DrawsWholeScreen`, `HonoursAspect`, `SlangShader::WantsWholeScreen()`.
- Produces:
  - `struct SlangFrameOptions { float aspect_ratio = 0.0f; u32 rotation = 0; };` in `gpu_device.h`.
  - `virtual bool SlangChain::Frame(GPUTexture* input, GPUTexture* output, u64 frame_count,
    const SlangFrameOptions* options, Error* error) = 0;` — `nullptr` passes `nullptr` to librashader as today;
    otherwise the runtime's `frame_*_opt_t` with `version = LIBRASHADER_CURRENT_VERSION`, `frame_direction = 1`,
    `total_subframes = 1`, `current_subframe = 1`, `frames_per_second = 1`, `brightness_nits = 200`, the two given
    fields, the rest zero.
  - `std::unique_ptr<SlangChain> GPUDevice::CreateSlangChain(const std::string& preset_path, bool original_aspect,
    Error* error);` and `Librashader::LoadPreset(path, runtime, bool original_aspect_uniforms, out, error)`.
  - `PictureInfo::aspect_ratio` (`float`, 0 = unknown): the picture's display aspect as shown, inverted for a 90°/270°
    rotation. Set by `GPUPresenter::RenderDisplay` for the display chain only.
  - `virtual bool Shader::DrewWholeScreen() const { return false; }` — `SlangShader` returns whether its last `Apply`
    drew over the whole target.

- [ ] **Step 1: Write the failing GPU tests** (64×48 target, picture 48×32 at (8, 8) as the file's other tests; a
  hand-written `bezel/frame.slangp` whose single pass is `scale_type = viewport` and paints magenta where
  `vTexCoord` is outside the middle 4:3 box and the `Source` sample inside it; a `bezel/koko-aio/aspect.slangp` (so `HonoursAspect` lists it) writing
  `global.OriginalAspect` into red as `aspect / 2`, under `#ifdef _HAS_ORIGINALASPECT_UNIFORMS`, else 0):
  - `ABezelDrawsOverTheWholeScreen`: aspect 4/3 → pixel (2, 2) magenta (today: green input), middle pixel shaded.
  - `ABezelReceivesTheAspect`: aspect 4/3 → red 170 ± 3 (4/3 ÷ 2 = 0.667); aspect 16/9 (honoured, so still whole-screen)
    → red 227 ± 3.
  - `ANonFourThreePictureFallsBackIntoThePicture`: aspect 16/9 → (2, 2) is the green input, the picture shaded inside
    its rect only.
  - `AStageDrawingIntoThePictureIsUnchanged`: `crt/gain.slangp` with aspect 4/3 → identical pixels to aspect 0, and
    `DrewWholeScreen()` false.
  - `AWholeScreenStageFillsAnyTargetShape` (Review Focus 3): a 48×64 target → every corner magenta.
  - `ThePlacementFollowsTheAspectEachFrame` (Review Focus 1): frames at 4/3, 16/9, 4/3 → whole, picture (corners the
    green input, not magenta left over), whole; the chain object kept (the glue's create count unchanged).
  - `TheSwitchOffKeepsABezelInThePicture`: `bezel/frame.slangp` with `WholeScreen = false` → corners green.
  - `TheGamesOwnFrameFeedsAWholeScreenBezelUncropped`: `game_texture` given, picture 80×64 larger than the target →
    the bezel's centre shows the game's centre row (not a cropped part).
- [ ] **Step 2: Run** `GOOSESTATION_GPU_TEST_API=Metal … build/bin/common-tests --gtest_filter='SlangGPU*'`. Expected:
  the new tests FAIL (corners green), the old ones PASS.
- [ ] **Step 3: Implement.** In `SlangShader::Apply`: `whole = DrawsWholeScreen(WantsWholeScreen(),
  HonoursAspect(m_name), picture.aspect_ratio)`. Whole: the source is `game_view` whole when reading the game's frame
  (native = `picture.native_*`), else `CropToTarget`'s visible part as today; `m_result` is `target_width ×
  target_height`; `m_chain->Frame(..., &options, ...)` with `{picture.aspect_ratio, 0}`; output copies `m_result`
  over the whole target (flipped as today for `!rows_top_first`), no input copy first. In-picture: today's code
  unchanged, `options` `nullptr`. `CompilePipeline` creates the chain with `original_aspect = WantsWholeScreen()`.
  `GPUPresenter::RenderDisplay`: `picture.aspect_ratio = rotated_quarter ? 1 / display_aspect_ratio :
  display_aspect_ratio` (0 when it's not positive).
- [ ] **Step 4: Run** the GPU tests on Metal, Vulkan (MoltenVK) and OpenGL where available; whole `common-tests`.
  Expected: PASS; D3D11/D3D12 compile on `pcsx2-win` and run there in Task 11.
- [ ] **Step 5: Commit** (`Post-processing: a bezel stage draws over the whole screen`).

### Task 4: Stages after a bezel

**Files:**
- Modify: `src/util/postprocessing_slang_picture.h` (the next stage's picture), `src/util/postprocessing.cpp`
  (`Chain::Apply`)
- Test: `src/common-tests/postprocessing_slang_tests.cpp`, `src/common-tests/postprocessing_slang_gpu_tests.cpp`

**Interfaces — Produces:**
- `struct NextStage { GSVector4i final_rect; GSVector4i picture_rect; PictureSize native; };`
  `NextStage AfterWholeScreen(u32 target_width, u32 target_height);` (all three the whole target; native = the
  target's size, so a later slang stage reads the bezel 1:1).

- [ ] **Step 1: Write the failing tests**: native `SlangPlacement.AfterABezelTheNextStageHasTheWholeTarget`; GPU
  `AStageAfterABezelKeepsTheBars` (Review Focus 2): chain `bezel/frame.slangp` → GLSL gain → `crt/gain.slangp` →
  corners magenta × gains, not black or green; `ANonBezelChainIsUnchanged`: GLSL → `crt/gain.slangp` gives the same
  pixels as before this task (asserted values).
- [ ] **Step 2: Run** them. Expected: FAIL (corners black from the GLSL stage's viewport).
- [ ] **Step 3: Implement** in `Chain::Apply`: after a stage whose `DrewWholeScreen()` is true, `final_rect`,
  `stage_picture.rect` and `stage_picture.native_*` become `AfterWholeScreen`'s, `stage_picture.aspect_ratio` 0 (a
  second bezel then draws into the whole screen it was given, never squeezing the first).
- [ ] **Step 4: Run** native and GPU tests (Metal, Vulkan). Expected: PASS.
- [ ] **Step 5: Commit** (`Post-processing: stages after a bezel work on the whole screen it drew`).

### Task 5: koko-aio's white picture

**Files:** decided by the investigation (likely `src/util/postprocessing_shader_slang.cpp` or a glue file).

- [ ] **Step 1:** `superpowers:systematic-debugging`: run `bezel/koko-aio/koko-aio-ng.slangp` through
  `SlangGPU.PresetsFromAPackRun` and the dump test on Metal and Vulkan, in-picture and whole-screen, and record what
  comes out; then bisect the preset (its passes one at a time through a trimmed copy in scratch) to the pass that turns
  white, and compare what it reads (`OriginalHistory`, feedback, `FinalViewportSize`, its LUTs' formats and mipmaps,
  `float_framebuffer`) with what we give. Hypotheses to rule in or out in order: the small in-picture viewport (fixed by
  Task 3 if whole-screen is right), missing original-aspect uniforms (fixed by Task 3), a frame-option default
  (`frame_direction` 0 when options are passed), a texture format we hand librashader (RGBA8 vs sRGB), mipmapped LUTs.
- [ ] **Step 2:** If the cause is ours: a failing GPU test that reproduces it with a hand-written preset (named after
  the cause), the fix, PASS, **commit** (`Slang: <what was wrong>`). If it's koko-aio's or librashader's: the finding
  written into the PR and the design's results, no code.

### Task 6: The cost label

**Files:**
- Create: `src/util/postprocessing_slang_cost.{h,cpp}` (and in `src/util/CMakeLists.txt`, `util.vcxproj`,
  `.vcxproj.filters`, Android's native build list if it lists files)
- Test: `src/common-tests/postprocessing_slang_cost_tests.cpp` (new; `CMakeLists.txt`, `.vcxproj`, `.filters`)

**Interfaces — Produces:**
- `namespace PostProcessing::Slang { enum class Cost : u8 { Light, Heavy, VeryHeavy };`
  `struct CostEstimate { u32 passes; float screens; Cost cost; };`
  `std::optional<CostEstimate> EstimateCost(const std::string& path);` (none when the file can't be read)
  `std::optional<Cost> GetCostForName(std::string_view name);` (through `FindPreset`, cached in memory by resolved path,
  thread-safe; the cache is cleared by `ClearCostCache()`, called when shader packs are installed or removed)
  `const char* GetCostLabel(Cost cost);` → `TRANSLATE_NOOP`'d `"light"`, `"heavy"`, `"very heavy"`. }`
- Parse rules: `key = value` with optional quotes, `#`/`//` comments outside quotes; `#reference "path"` lines
  (relative to the file holding them) read first, depth ≤ 16 and no file twice; the referencing file's keys win.
  Per pass `i`: `scale_type{_x,_y}i` (`source` default, `viewport`, `absolute`), `scale{_x,_y}i` (default 1); a last
  pass with no scale type draws at the viewport. Source 320×240, viewport 1920×1080; sizes clamped to 1–16384; at most
  256 passes counted. `screens = Σ w·h / (1920·1080)`; score `screens + 0.1 × passes`; light < 5, heavy < 10.

- [ ] **Step 1: Write the failing tests** (presets written by hand in a temp folder):
  - `OnePassAtTheViewportIsLight`: 1 pass `scale_type0 = viewport` → passes 1, screens 1.0, Light.
  - `SourceScaledPassesFollowThePreviousPass`: pass 0 `source` ×2, pass 1 `source` ×2, pass 2 none → sizes 640×480,
    1280×960, 1920×1080 → screens ≈ 0.148 + 0.593 + 1.0.
  - `AbsoluteAndPerAxisScales`: `scale_type_x0 = absolute`, `scale_x0 = 800`, `scale_type_y0 = viewport`,
    `scale_y0 = 0.5` → 800×540.
  - `ReferencesAreFollowedAndTheReferrerWins`: `a.slangp` `#reference "base/b.slangp"` with `scale0 = 3`; `b` has
    `shaders = 2`, `scale0 = 1` → passes 2, pass 0 at 960×720.
  - `ThresholdsFromTheThorMeasurements`: 10 passes at 2.3 screens Light; 17 at 2.1 Light; 12 at 5.3 Heavy; 30 at 9.0
    VeryHeavy (through a helper `Cost CostOf(u32 passes, float screens)` exposed for this).
  - `BrokenPresetsStillGetALabelOrNone` (Review Focus 5): missing reference target (counted without it), a loop
    (`a` → `b` → `a`), `shaders = x`, `scale0 = -2`, `scale0 = 1e9` → a label, finishing; a missing file → none.
  - `GetCostForNameCachesByPath`: the second call after deleting the file still answers; after `ClearCostCache()` none.
  - With `GOOSESTATION_SLANG_PACK` set (opt-in, skipped otherwise): Potato and uborder Light, `MBZ__3__STD` and
    `MBZ__0__SMOOTH-ADV` VeryHeavy (the design's success check), and every preset in the pack labelled in under 5 s.
- [ ] **Step 2: Run** `--gtest_filter='SlangCost*'`. Expected: FAIL (no header).
- [ ] **Step 3: Implement** per the interfaces.
- [ ] **Step 4: Run** the filter (with `GOOSESTATION_SLANG_PACK=/tmp/goose-bezel/slang`) and all `common-tests`.
  Expected: PASS; time for the whole pack recorded.
- [ ] **Step 5: Commit** (`Slang: a light, heavy or very heavy label from each preset's structure`).

### Task 7: Qt

**Files:**
- Modify: `src/duckstation-qt/postprocessingsettingswidget.{h,cpp}` (`updateButtonsAndConfigPane` :172-210, its early
  return for no options :203-204; `onResetDefaultsClicked` :342-366; `createUi` :368-526),
  `src/duckstation-qt/shaderbrowserdialog.cpp` (`buildModel` :101-156, columns :61),
  `src/duckstation-qt/graphicssettingswidget.{h,cpp,ui}` (notes beside Scaling :190-202 and Screen Position :332-381)

**Interfaces — Consumes:** Task 2's `Config::GetStageWholeScreen`/`SetStageWholeScreen`/`StageWantsWholeScreen`/
`ChainHasWholeScreenStage`; Task 6's `GetCostForName`/`GetCostLabel`.

- [ ] **Step 1: Implement the switch**: for a slang stage, a `QCheckBox` "Draw over the whole screen" as the config
  pane's first row (the pane is built even when the preset has no parameters), checked from `StageWantsWholeScreen`,
  tooltip "Lets a bezel preset draw around the game over the whole screen. On by itself for presets in a bezel folder.
  Games that aren't 4:3 are drawn into the picture instead."; a change writes `SetStageWholeScreen` and commits as an
  option does. Reset to defaults also removes the key.
- [ ] **Step 2: Implement the label**: a third column "Cost" in the browser (empty for GLSL and ReShade), filled for
  the rows being built (`buildModel` builds every leaf: cost estimated there, on the dialog's thread; Task 6's timing
  says whether that's quick enough for the whole pack, otherwise filled when a folder is expanded).
- [ ] **Step 3: Implement the note**: a word-wrapped label under Scaling (Rendering tab) and one under Screen
  Position/Stretch Vertically (Advanced tab), as `shaderCompileThreadsNote` is, with Decision 6's text; shown from
  `GraphicsSettingsWidget::showEvent` when `ChainHasWholeScreenStage` holds for the effective chain (the game's
  interface if `HasOwnChain`, else the base layer).
- [ ] **Step 4: Check** the Qt build compiles on the Mac and on `pcsx2-win`; run it on the Mac offscreen
  (`QT_QPA_PLATFORM=offscreen`, a scratch `HOME`) with a small harness that opens the post-processing page with a
  `bezel/frame.slangp` stage and grabs the widgets (`QWidget::grab`) to PNGs: the checkbox shows checked, the browser
  shows "light", the Graphics page shows both notes. PNGs into the shared notes folder.
- [ ] **Step 5: Commit** (`Qt: the whole-screen switch, cost labels and notes on the placement rows`).

### Task 8: Big Picture

**Files:** Modify `src/core/fullscreen_ui.cpp` (`DrawPostProcessingSettingsPage` options loop :6802-7025;
`DrawShaderBrowser` row :5660-5674; `DrawDisplaySettingsPage` Scaling :5120-5124, Screen Position :5237-5241, Stretch
:5292-5295; `PostProcessingStageInfo` :225-228 and `PopulatePostProcessingChain` :5545-5557).

- [ ] **Step 1: Implement**: `PostProcessingStageInfo` gains `bool is_slang` and `bool whole_screen` (from
  `StageWantsWholeScreen`); a slang stage's first row is a `ToggleButton` "Draw over the whole screen" (summary as Qt's
  tooltip), writing `SetStageWholeScreen` then `SetSettingsChanged`; the browser row's summary becomes
  "Slang · light" (and "Slang · light · <folder>" in search results); the three placement rows' summaries become
  Decision 6's note when `ChainHasWholeScreenStage` holds for the effective chain.
- [ ] **Step 2: Check** in the Qt build's Big Picture on the Mac offscreen if the harness from Task 7 can drive it,
  otherwise build-only, said so in the PR.
- [ ] **Step 3: Commit** (`Big Picture: the whole-screen switch, cost labels and notes on the placement rows`).

### Task 9: Android native

**Files:**
- Modify: `android/app/src/cpp/android_host.h:119-150` (`PostProcessingStage` gains `bool whole_screen`,
  `bool slang`), `android/app/src/cpp/android_host_game_files.cpp` (`ReadStages` :325-349,
  `GetPostProcessingShaders` :296-322, a setter beside `SetPostProcessingOption` :393-417),
  `android/app/src/cpp/jni_game_files.cpp` (`ToStageInfo` :231-264, `getPostProcessingShaders` :266-275, new setter),
  `android/app/src/cpp/jni_classes.cpp:785-792` (the `StageInfo` constructor's signature),
  `android/app/src/main/java/.../core/{PostProcessingInfo,NativeLibrary}.kt`, `app/CoreApi.kt` (:127-178, :348-400)
- Test: `android/app/src/androidTest/java/.../core/PostProcessingAccessTest.kt`

**Interfaces — Produces:**
- `StageInfo(shader, kind, options, error, wholeScreen: Boolean)`; `getPostProcessingShaders()` returns name, kind,
  cost triples (cost `""` for non-slang or unknown); Kotlin `Shader(name, kind, cost: String)`.
- `NativeLibrary.setPostProcessingWholeScreen(gameKey: String?, index: Int, on: Boolean): Boolean` (the chain's layer
  as `setPostProcessingOption` picks it); `NativeLibrary.chainDrawsOverWholeScreen(gameKey: String?): Boolean`.

- [ ] **Step 1: Write the failing instrumented tests** in `PostProcessingAccessTest`: `aBezelStageDrawsOverTheWholeScreenByItsFolder`
  (a `bezel/x.slangp` stage reads `wholeScreen` true, `crt/x.slangp` false), `theWholeScreenSwitchIsWrittenToTheStage`
  (set false → the stage's section has `WholeScreen = false`, read back false; a game's chain writes its own file),
  `slangPresetsCarryACostLabel` (a one-pass viewport preset → `"light"`), `theChainSaysWhenABezelDrawsOverTheWholeScreen`.
- [ ] **Step 2: Run** the class on `emulator-5580`. Expected: FAIL (no such JNI).
- [ ] **Step 3: Implement** per the interfaces.
- [ ] **Step 4: Run** the class. Expected: PASS. `./gradlew assembleDebug`.
- [ ] **Step 5: Commit** (`Android: the whole-screen switch and cost labels through JNI`).

### Task 10: Android screens

**Files:**
- Modify: `settings/PostProcessingSettings.kt` (`OptionRow` :89-124, `optionRows()` :136-152), `app/PostProcessingPage.kt`
  (model :63-219, a `setWholeScreen` beside `setOption` :497-516), `ui/PostProcessingScreen.kt` (`OptionRows`
  :482-519, browser rows :357-364 and :397-406), `app/PageValues.kt` (:151-185, `PageExtras` :41-58),
  `ui/GraphicsScreen.kt:223-226`
- Test: `android/app/src/test/.../settings/PostProcessingSettingsTest.kt`, `app/PostProcessingPageTest.kt`,
  `app/PageValuesTest.kt`, `app/FakeCoreApi.kt`; `androidTest/.../ui/{PostProcessingScreenTest,GraphicsScreenTest,
  SamplePostProcessing}.kt`

- [ ] **Step 1: Write the failing tests**: `optionRows()` of a slang stage starts with a toggle row "Draw over the whole
  screen" (none for GLSL); `PostProcessingPageTest.theWholeScreenSwitchWritesAndReadsBack` (optimistic, then the core's
  answer); `PageValuesTest` reads `bezelWholeScreen` for Graphics only; `PostProcessingScreenTest.aSlangPresetShowsItsCost`
  ("Slang · light"); `GraphicsScreenTest.thePlacementRowsSayABezelDrawsOverTheWholeScreen`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest` and the two UI classes on `emulator-5580`. Expected: FAIL.
- [ ] **Step 3: Implement**: a new `OptionRow.WholeScreen(on)` drawn as a `SwitchRow` with the note; browser rows
  `"${kind.label} · $cost"` when there's a cost; the three Graphics rows get Decision 6's note through `PageExtras`.
- [ ] **Step 4: Run** unit tests, the UI classes, `FocusAuditTest`, `OrientationLayoutTest`, `ktlintCheck`,
  `checkNoWindowDialogs`. Expected: PASS.
- [ ] **Step 5: Commit** (`Android: the whole-screen switch, cost labels and notes on the placement rows`).

### Task 11: Desktop frames, Windows, and which presets honour the aspect

**Files:** `src/util/postprocessing_slang_picture.h` (the `HonoursAspect` list, if it changes) and its test.

- [ ] **Step 1:** Rerun Task 1's dump (after) on the Mac (Metal, Vulkan) and `pcsx2-win` (D3D11, D3D12, Vulkan,
  OpenGL), with `common-tests` built there (`src\common-tests\common-tests.vcxproj -p:SolutionDir=<tree>\`); compare
  every non-bezel case byte for byte with `before/` (expected: equal); bezel cases differ only as intended.
- [ ] **Step 2:** Bezel frames at 1920×1080 from Vagrant Story's picture (`shots/shot_0.png` cropped to its 4:3 area,
  scaled to 320×240 with `sips`, raw RGBA) for Potato, uborder, koko-aio and Mega Bezel standard, at 4:3 and 16:9, on
  every renderer; PNGs into the shared notes folder for the PR. Check by numbers: at 4:3 the side bars aren't black;
  at 16:9 Potato and uborder fall back (bars as the input), koko-aio's picture keeps 16:9 (its game area's measured
  width/height within 2% of 16:9) — only then does koko-aio stay in `HonoursAspect`; scanline-classic is tried the same
  way and added only if it passes.
- [ ] **Step 3:** If the list changed: its test updated, **commit** (`Slang: the bezel presets that honour the aspect`).

### Task 12: Android emulators and the Thor

**Files:** `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/core/GameCallsTest.kt`
(a whole-screen frame test).

- [ ] **Step 1: Write the failing test** `aBezelStageDrawsOverTheWholeScreen` beside
  `aSlangStageDrawsThePictureTheRightWayUp`: a hand-written `bezel/frame.slangp` (as Task 3's) in the test's shaders
  folder, the padtest disc, a 1920×1080 `FrameReader` → a pixel at (20, 540) magenta; with `WholeScreen = false` → black.
  `@BootsGames`, so it reruns on OpenGL ES.
- [ ] **Step 2: Run** it on `emulator-5580` and `emulator-5582` from a detached worktree. Expected: PASS (it fails at
  the commit before Task 3, checked once by building that commit).
- [ ] **Step 3: Quick tier** while building; **full tier** before the PR; `run-native-tests.sh` with
  `ANDROID_SERIAL=emulator-5580`; `./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck
  checkNoWindowDialogs`.
- [ ] **Step 4: The Thor:** the design's `BezelMeasureTest` (scratch, never committed) reworked to boot Vagrant Story's
  state per preset (none, Potato, uborder, koko-aio, Mega Bezel standard) at 1920×1080 on Vulkan, log VPS and GPU per
  second, save frames. Expected: Potato and uborder hold 60 VPS and fill the 16:9 screen. Then clean up:
  `adb uninstall io.github.ilyalissoboi.goosestation.debug.test`, `rm -rf /sdcard/GooseTest`,
  `run-as io.github.ilyalissoboi.goosestation.debug rm -rf cache/bezel-shots`; `rm -rf /tmp/goose-bezel`.
- [ ] **Step 5: Commit** (`Android: a frame test for a bezel over the whole screen`).

### Task 13: Docs, roadmap, the PR

**Files:** `docs/roadmap.md` (item 30), the user docs that describe post-processing (`docs/android.md`'s shader
section and the desktop equivalent, whichever holds the stage options), the design's "Measured" section (results).

- [ ] **Step 1:** Item 30 reworded to what was built (whole-screen bezels, the fallback, the switch, the labels, the
  Thor numbers, koko-aio's finding); docs gain the switch and the labels.
- [ ] **Step 2:** Every tier green: `common-tests` on the Mac and Windows, the GPU tests per renderer, Android full
  tier XMLs, native tests on the emulator, CI's Android checks.
- [ ] **Step 3: Commit** (`Docs: roadmap item 30 done, bezels in the user docs`).
- [ ] **Step 4:** Push with the `ilyalissoboi` token, open the PR (each success check and its result, the frames, what
  the user should judge), then `superpowers:requesting-code-review`.
