<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Slang shader presets, stage 1: librashader and the slang stage — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every build GooseStation ships carries librashader, loaded at run time, and a RetroArch `.slangp` preset is
one more kind of post-processing stage in both chains, on every renderer librashader supports, fed the game's
picture at the console's resolution; Big Picture's post-processing page gains the internal chain and per-game chains.

**Architecture:** `src/util/librashader_loader` opens the library once and says why when it can't. A
`PostProcessing::SlangShader` stage parses its preset for its parameters (float options) and, on a device, asks
`GPUDevice::CreateSlangChain()` for a per-backend chain (Vulkan, D3D11, D3D12, Metal, OpenGL; each glue a friend of
its device, in its own file). Each frame the stage cuts the picture out of its input (or, first in the display chain,
takes the game's own frame), scales it to the console's resolution, runs the preset into a picture-sized texture of
its own, and draws that where the picture sits. librashader only ever touches textures the stage owns, never the
swap chain. Every dependency script builds librashader with Rust; packaging puts it beside the program.

**Tech Stack:** C++20 core (`src/util`, `src/core`), librashader 0.12.1 C API (Rust, `cargo`), CMake and MSBuild,
Qt 6, Dear ImGui (Big Picture), Kotlin/Jetpack Compose over JNI, gtest (`common-tests`), JUnit and AndroidX tests,
Flatpak (`org.freedesktop.Sdk.Extension.rust-stable`).

**Spec:** `docs/superpowers/specs/2026-11-07-slang-shaders-design.md` ("A slang preset is a stage", "librashader in
every build", "Big Picture catches up", stage 1 of "Stages").

## Global Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7` (it later added slang support of its own; don't look at
  it). The PCSX2 fork (`github.com/ilyalissoboi/pcsx2`, GPL, the user's) may be adapted: its
  `pcsx2/GS/ShaderChain/LibrashaderLoader.*`, `GSDevice{VK,11,12,MTL}` glue, `cmake/FindLibrashader.cmake`, deps scripts.
- librashader **0.12.1** (tag `librashader-v0.12.1`; C ABI 2, API 5 — the header is byte-identical to 0.12.0's; 0.12.1
  fixes the wildcard context in `libra_preset_create_with_options`), built with Rust **1.88** (its MSRV), crate
  `librashader-capi`, `--profile optimized --no-default-features --features <runtimes>`. Never `runtime-d3d9`.
- Runtimes per build: Windows `runtime-vulkan,runtime-d3d11,runtime-d3d12,runtime-opengl`; macOS
  `runtime-vulkan,runtime-metal` (no OpenGL); Linux AppImage and Flatpak `runtime-vulkan,runtime-opengl`; Android
  `runtime-vulkan,runtime-opengl`.
- Loaded at run time, never linked. The header (MIT) is vendored under `dep/librashader/`; the library (MPL-2.0),
  and on Windows DXC (`dxcompiler.dll`, `dxil.dll`, which librashader's D3D12 runtime imports at load time), are
  listed in the third-party notices.
- The options pointer passed to `libra_preset_create_with_options` is never null (`libra_preset_opt_t` with
  `version = LIBRASHADER_CURRENT_VERSION`); the context is consumed on success and freed by us on failure.
- A stage's name is the preset's path relative to the shaders folder (`EmuFolders::Shaders`), `/` separators, with
  its extension: `shaders_slang/crt/crt-royale.slangp`. A name that is empty, absolute or has a `..` component never
  loads.
- Copy, exact: a stage with no library says **"Slang presets aren't available in this build: {reason}."**; a renderer
  without the glue says **"Slang presets aren't supported by the {renderer} renderer."**; a preset that fails says
  **"Slang preset {name} failed: {librashader's text}"** once, until the stage or the preset changes. The add-shader
  lists show a preset as **"{name} [Slang]"**.
- Keys stay desktop's: `[PostProcessing]` / `[InternalPostProcessing]` `Enabled`, `StageCount`, `[<chain>/StageN]
  ShaderName` and one key per parameter (the `#pragma parameter` name), written by `PostProcessing::Config`.
- Nothing stops building or starting because librashader is missing: every platform's build and start works without
  it (slang presets are then unlisted and say why).
- Android: `docs/design-system.md` binding; any new or changed page stays in `FocusAuditTest` and
  `OrientationLayoutTest`; emulators only; the AYN Thor only with the user's go-ahead, and no scratch memory cards in
  its data folder. Android-only core code behind `__ANDROID__` or in `android/`.
- `gh` as ilyalissoboi (`GH_TOKEN=$(gh auth token --user ilyalissoboi)`), never the GitHub MCP; push with a
  credential helper giving username ilyalissoboi and that token. Work on branch `claude/slang-shaders-stage1` (from
  `origin/claude/slang-shaders` 6af099b22; another worktree holds the local `claude/slang-shaders`) and push it to
  `origin/claude/slang-shaders`. Commit each task when it's done and show its message after. Commit messages end
  with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Found in the code

- **The chains** (`src/util/postprocessing.cpp`): `TryLoadingShader` tries ReShade then GLSL, in the shaders folder
  then the resources; `Config::AddStage` loads the shader with `only_config` (metadata only) before writing it.
  `Chain::Apply` (`:666`) ping-pongs two target-sized textures; a stage's `Apply` gets `final_rect` (the display
  rect, padding included), never where the picture itself sits. `Chain::CheckTargets` (`:613`) compiles every stage,
  and on a failure says "Failed to compile post-processing shader '…'" and turns the chain off; `RenderDisplay` calls
  it every frame, so a stage that keeps failing is retried on every settings change. `Shader::LoadOptions` calls
  `OnOptionChanged` for each changed value, on the settings path (`Chain::UpdateSettings`), without reloading the
  stage — the hook for live parameters.
- **`GPU::RenderDisplay`** (`src/core/gpu.cpp:1988`): the internal chain runs on the display texture (whose view is
  the whole texture there) with native size `m_crtc_state.display_width/height`; the display texture is then drawn
  (scaling mode, rotation) at `draw_rect` into the display chain's input, which runs with `final_rect =
  display_rect`. The console's picture without black bars is `m_crtc_state.display_vram_width/height`
  (`gpu.cpp:788-832`); `display_width/height` include the padding. On OpenGL (`UsesLowerLeftOrigin()`), callers flip
  rects themselves (`GPUDevice::FlipToLowerLeft`). Rotation swaps the draw rect's size (`gpu.cpp:2436`).
- **Backends:** the handles librashader needs are public (`VulkanDevice::GetVulkanInstance/Device/PhysicalDevice`,
  `GetCurrentCommandBuffer`, `VulkanTexture::GetImage/GetVkFormat/TransitionToLayout/OverrideImageLayout`;
  `D3D11Device::GetD3DContext`, `D3D11Texture::GetD3DSRV/GetRTVOrDSV`; `D3D12Device::GetCommandList`,
  `D3D12Texture::GetResource/TransitionToState`; `MetalTexture::GetMTLTexture`; `OpenGLTexture::GetGLId`,
  `OpenGLDevice::IsGLES`), but the state resets are private (`VulkanDevice::EndRenderPass/InvalidateCachedState`,
  `D3D12Device::EndRenderPass/InvalidateCachedState`, `MetalDevice::EndAnyEncoding/m_render_cmdbuf`), and D3D11 and
  OpenGL have none (their caches: `D3D11Device::m_current_*`, `OpenGLDevice::m_last_*`, `m_current_*`). So each glue
  is a friend class of its device, in a file of its own, and D3D11 and OpenGL gain an "invalidate cached state" of
  their own.
- **librashader 0.12's OpenGL runtime knows GLSL ES** (`librashader-runtime-gl/src/util.rs`, `Glsl300Es`…`320Es`),
  so the OpenGL ES spike may pass.
- **librashader's D3D12 runtime imports `dxcompiler.dll` at load time** (the fork's finding): without it beside the
  program the whole library fails to load, Vulkan and D3D11 included. GooseStation doesn't ship DXC today; its
  static build (`runtime-d3d12-static`) is x64 only and downloads binaries during the build, so the Windows scripts
  take the `Microsoft.Direct3D.DXC` NuGet package (as the fork does) and ship `dxcompiler.dll` and `dxil.dll`.
- **Tests:** `src/common-tests` (gtest, links `core`, host stubs in `core_host_stubs.cpp`; also a `.vcxproj`). The
  Windows builds are MSBuild (`duckstation.sln`, `rolling-release.yml:87,182`): new sources go into the
  `.vcxproj`/`.filters` files too.
- **Local tools:** Rust 1.88 and stable with the `aarch64-apple-darwin`, `aarch64-linux-android`,
  `x86_64-linux-android` targets and `cargo-ndk`; the NDK at
  `/opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370`. Windows and Linux can't be built or run here:
  CI builds them (`workflow_dispatch` on the branch, with the user's word) and they're unchecked at run time
  unless the user runs them.

- **emusync is the pattern to follow:** a Rust library loaded at run time with a vendored header
  (`dep/emusync/`), opened from candidates beside the program then by bare name (`src/core/memcard_sync.cpp:250-269`),
  copied beside the program or into the bundle's `Frameworks` by `CMakeModules/EmuSync.cmake:82-99`, into the
  AppImage's `usr/bin` by hand (`scripts/appimage/make-appimage.sh:149-153`), into the Windows output by
  `dep/emusync/emusync.props`. It differs in one way: it's downloaded prebuilt; nothing here builds Rust yet.
- **Dependency scripts** (`scripts/deps/build-dependencies-*`): pinned versions at the top, SHA-256 checked
  downloads, one block per dependency into `INSTALLDIR`. Windows ARM64 cross-compiles from x64
  (`vcvarsamd64_arm64.bat`, `INSTALLDIR=dep\msvc\deps-arm64`); macOS builds some dependencies per architecture and
  merges them with `merge_binaries`/`lipo` (`build-dependencies-mac.sh:7-28`), deployment target 14.4; Android builds
  arm64-v8a, API 29, into `~/work/goosestation-deps-android`, with its shared libraries listed in
  `android/app/build.gradle.kts:25-26` and staged into the APK's `jniLibs` (`:165-169`, `:297-301`).
- **CI** (`.github/workflows/rolling-release.yml`): Windows x64/ARM64 by MSBuild on `windows-2025-vs2026`, deps cached
  on the `.bat`'s hash, DLLs copied by `src/util/util.props:29-56` (`DepsDLLs`); Linux on `ubuntu-22.04`, cache on
  `build-dependencies-linux.sh`, AppImage by `make-appimage.sh` (dlopen'd libraries in `MANUAL_LIBS`, `:58-63`); the
  Flatpak on the `kde-6.11` container, **cache key `hashFiles('scripts/flatpak/**/*.yaml')`** (`:324`, so a JSON
  cargo-sources file must widen it), manifest `scripts/flatpak/io.github.ilyalissoboi.GooseStation.yaml` with only
  the `llvm21` SDK extension; macOS on `macos-15`, universal, `-DENABLE_OPENGL=OFF`. Android
  (`.github/workflows/android.yml`) runs `assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs` with the
  deps cached on the Android script's hash. No workflow installs Rust; the GitHub runner images carry `rustup`.
- **Third-party notices:** one file, `data/resources/thirdparty.html` (an `<h3>` and a `<pre>` per entry), shown by
  Qt's About dialog and shipped (unshown) in the APK. emusync has no entry today (left as it is: not this task's).
- **The UIs:** Qt lists `GetAvailableShaderNames()` in a menu and draws float options as sliders with
  `step_value` (`postprocessingsettingswidget.cpp:164-195, 442-464`); both chains, global and per game, work today.
  Big Picture's page (`fullscreen_ui.cpp:4914-5281`) is the display chain only (`section` hard-coded, `Enabled`
  hard-coded to `"PostProcessing"`), and `per_game_pages` (`:2881-2884`) leaves it out; its "Use Global Setting"
  pattern is `DrawToggleSetting`'s tri-state (`:1904-1931`) and the controller page's copy-on-first-use
  (`:3565-3573, 3685-3709`); edits reach the core through `SetSettingsChanged` → `System::ApplySettings` /
  `ReloadGameSettings` → `PostProcessing::UpdateSettings` (`system.cpp:4526`). Android's list loads each shader's
  metadata (`android_host.cpp:915-939`) only to drop ones that don't load (crt-hyllian-sinc); its kinds come from
  file lookups (`ShaderKind`, `:901-913`); Kotlin's `ShaderKind { GLSL, ReShade }` drops any other kind
  (`settings/PostProcessingSettings.kt:29-38`, `app/PostProcessingPage.kt:385-387`) and `offered()` hides ReShade
  under OpenGL ES (`:69-72`); copying the global chain into a game's file is `AndroidHost::CopyPostProcessingChain`
  (`android_host.cpp:1028-1058`).
- **Android tests that read frames:** `androidTest/…/core/FrameReader.kt` (a surface whose frames a test reads);
  `run-instrumented-tests.sh full` reruns game-booting classes under OpenGL ES (SwiftShader).

## Design calls (builder's calls the spec leaves open)

1. **The stage owns its textures.** librashader renders from a native-size texture into a picture-size texture,
   both the stage's; the stage then draws the result where the picture sits, through `GPUDevice` like any other
   stage. No swap chain image ever reaches librashader, so the glue is small and the last stage can present.
2. **The stage's output outside the picture** is its input there (a stage after a border shader keeps the border);
   first in the display chain, reading the game's frame, it's the clear colour, as today.
3. **The game's own frame only when it can be used as is:** first in the display chain, with rotation Normal.
   Rotated, the stage cuts the (rotated) picture out of its input instead, at the console's size turned to match.
4. **Scaling to the console's resolution:** a box filter when the picture is an exact integer multiple (2× and up)
   in both directions, otherwise bilinear; both are the stage's own small shaders through `ShaderGen`.
5. **Parameters live:** `OnOptionChanged` marks a parameter; the next `Apply` sets the marked ones on the chain
   (`libra_*_filter_chain_set_param`). A new chain starts with every option's current value.
6. **A failed preset latches** in the stage object: `CompilePipeline` fails at once, with no new message, until the
   stage is replaced (its name changes) or the shaders are reloaded. A frame that fails draws the picture unshaded
   for that frame, says so once, and latches too, so the game never stops presenting.
7. **The library is found** beside the program on Windows (`librashader.dll`), in the bundle's
   `Contents/Frameworks/librashader.dylib` on macOS, by name (`librashader.so`) on Linux and Android (the AppImage's
   and Flatpak's library paths, the APK's native library directory); `GOOSESTATION_LIBRASHADER_PATH` overrides all
   (tests, developers).

## Review Focus

1. **Rotation** 90°/270° with a slang stage first in the display chain: the picture comes out upright, at the
   console's size turned to match (Task 4's `NativeSize` test; Task 5's `RenderDisplay` rule, checked by hand in
   Task 6).
2. **A picture that isn't an integer multiple** (resolution scale 3× of a 368-wide mode, a PAL game, a cropped
   overscan mode): bilinear scaling, no seams or offset (Task 4's factor tests use 1104×720 → 368×240 and
   1000×750 → 320×240).
3. **The picture rect is empty or off the target** (window minimised, a 0×0 CRTC mode during a mode change): the
   stage passes its input through and never asks librashader for a 0-sized texture (Task 4's empty-rect tests).
4. **Thousands of presets in the shaders folder**: listing them never parses one (Task 2's listing test counts no
   loads; Android's list reads kinds from names, Task 10).
5. **A stage name from another machine** (Windows backslashes, `..`, an absolute path from a hand-edited ini): it
   never loads outside the shaders folder (Task 2's name tests).

---

## Tasks

### Task 1: The vendored header and the run-time loader

**Files:**
- Create: `dep/librashader/include/librashader.h` (verbatim from tag `librashader-v0.12.1`),
  `dep/librashader/LICENSE` (librashader's MIT header licence text from the tag), `dep/librashader/README.md`
  (what it is, the tag, "the library itself is MPL-2.0, built by the deps scripts, loaded at run time", how to bump).
- Create: `src/util/librashader_loader.h`, `src/util/librashader_loader.cpp`
- Modify: `dep/CMakeLists.txt` (an `INTERFACE` target `librashader-headers` with the include dir),
  `src/util/CMakeLists.txt`, `src/util/util.vcxproj(.filters)` (sources, include dir)
- Test: `src/common-tests/librashader_loader_tests.cpp` (+ `CMakeLists.txt`, `common-tests.vcxproj(.filters)`)

**Interfaces:**
- Produces (namespace `Librashader`, adapting the fork's `ShaderChain` loader):
  - `struct Availability { bool available = false; std::string reason; };`
  - `struct CommonFunctions` — exactly the fork's fields (`abi_version` … `preset_free_runtime_params`).
  - `const Availability& GetAvailability();` — loads once (`std::call_once`), logs the result.
  - `const CommonFunctions& Common();`, `void* GetSymbol(const char* name);`
  - `std::string DescribeAndFreeError(libra_error_t error);` — "unknown librashader error" for null.
  - `std::string GetDefaultLibraryPath();` — design call 7.
  - `Availability LoadFromPath(const std::string& path);` — no global state; for tests.
  - `bool LoadPreset(const std::string& path, LIBRA_PRESET_CTX_RUNTIME runtime, libra_shader_preset_t* out,
    Error* error);` — the context/options dance in one place (core name `"GooseStation"`; the runtime set unless
    `LIBRA_PRESET_CTX_RUNTIME_NONE`), used by the stage and every glue.

- [ ] **Step 1: Write the failing tests**

```cpp
TEST(LibrashaderLoader, MissingLibraryIsUnavailableWithReason)
{
  const Librashader::Availability a = Librashader::LoadFromPath("/nonexistent/librashader.so");
  EXPECT_FALSE(a.available);
  EXPECT_NE(a.reason.find("/nonexistent/librashader.so"), std::string::npos);
}
TEST(LibrashaderLoader, WrongLibraryIsUnavailableBecauseSymbolsAreMissing)
{
  // A library every platform has: libz / zlib1.dll / libSystem; reason names libra_instance_abi_version.
}
TEST(LibrashaderLoader, EmptyPathIsUnavailable) { EXPECT_EQ(LoadFromPath("").reason, "librashader path is empty"); }
TEST(LibrashaderLoader, DefaultPathHasPlatformFileName) { /* ends with librashader.dll / .dylib / .so */ }
TEST(LibrashaderLoader, EnvironmentOverrideWinsOverDefaultPath) { /* GOOSESTATION_LIBRASHADER_PATH */ }
TEST(LibrashaderLoader, DescribeNullErrorDoesNotCrash) { EXPECT_EQ(DescribeAndFreeError(nullptr), "unknown librashader error"); }
TEST(LibrashaderLoader, LoadsTheBuiltLibraryWhenPresent)
{
  // GTEST_SKIP() unless GOOSESTATION_LIBRASHADER_PATH is set; then available, empty reason.
}
```

- [ ] **Step 2: Run them to see them fail** — `cmake --build build --target common-tests && build/bin/common-tests
  --gtest_filter='LibrashaderLoader.*'`: FAIL to compile (no `librashader_loader.h`).
- [ ] **Step 3: Vendor the header and write the loader** (the fork's `LibrashaderLoader.cpp`, renamed; the Windows
  failure reason says "librashader.dll also needs dxcompiler.dll and dxil.dll beside the program"; an ABI mismatch
  says "librashader ABI {} does not match the expected ABI {}"; a missing symbol "librashader is missing symbol {}").
- [ ] **Step 4: Run them** — all pass (the last skipped).
- [ ] **Step 5: Commit** — "Util: load librashader at run time, and vendor its C header".

### Task 2: Preset names, lookup and listing

**Files:**
- Create: `src/util/postprocessing_shader_slang.h`, `src/util/postprocessing_shader_slang.cpp` (the name
  helpers now; the stage in Task 5)
- Modify: `src/util/postprocessing.cpp` (`GetAvailableShaderNames`, `TryLoadingShader`)
- Test: `src/common-tests/postprocessing_slang_tests.cpp`

**Interfaces:**
- Produces (namespace `PostProcessing::Slang`):
  - `bool IsPresetName(std::string_view name);` — ends with `.slangp`, case-insensitive.
  - `std::string ResolvePresetPath(std::string_view shaders_root, std::string_view name);` — absolute path, or
    empty when the name is empty, absolute (`/…`, `C:…`, `\\…`) or has a `..` component; `\` treated as `/`.
  - `std::vector<std::string> FindPresets(std::string_view shaders_root);` — relative, `/` separators, sorted,
    skipping anything under `__MACOSX` and files or folders starting with `.`; reads directory entries only.
- `GetAvailableShaderNames()` appends `{"{name} [Slang]", name}` for each, only when
  `Librashader::GetAvailability().available`. `TryLoadingShader` sends a preset name to `SlangShader` (Task 5)
  before trying ReShade or GLSL; with no library it fails with Global Constraints' "aren't available" text.

- [ ] **Step 1: Write the failing tests**

```cpp
TEST(SlangNames, PresetNamesAreRecognisedByExtension)
{ EXPECT_TRUE(IsPresetName("crt/crt-royale.slangp")); EXPECT_TRUE(IsPresetName("A.SLANGP"));
  EXPECT_FALSE(IsPresetName("crt/crt-royale")); EXPECT_FALSE(IsPresetName("x.slang")); }
TEST(SlangNames, ResolveStaysInsideTheShadersFolder)
{ EXPECT_EQ(ResolvePresetPath(root, "shaders_slang/crt/a.slangp"), Path::Combine(root, "shaders_slang/crt/a.slangp"));
  EXPECT_EQ(ResolvePresetPath(root, "shaders_slang\\crt\\a.slangp"), Path::Combine(root, "shaders_slang/crt/a.slangp"));
  for (auto bad : {"", "../a.slangp", "x/../../a.slangp", "/etc/a.slangp", "C:/a.slangp", "\\\\srv\\a.slangp"})
    EXPECT_EQ(ResolvePresetPath(root, bad), ""); }
TEST(SlangNames, FindPresetsListsRelativeSortedPathsAndSkipsJunk)
{ // temp dir: b/z.slangp, a/y.slangp, a/y.slang, __MACOSX/a/._y.slangp, .hidden/q.slangp, a/.x.slangp
  EXPECT_EQ(FindPresets(tmp), (std::vector<std::string>{"a/y.slangp", "b/z.slangp"})); }
TEST(SlangNames, FindPresetsNeverReadsAPreset)
{ // a .slangp containing garbage bytes is still listed (nothing parses it)
}
TEST(SlangNames, AvailableShaderNamesListPresetsOnlyWithTheLibrary)
{ // EmuFolders::Shaders = tmp with one preset; without GOOSESTATION_LIBRASHADER_PATH: no "[Slang]" entry;
  // GTEST_SKIP the "with" half unless the library is loadable; then {"a/y.slangp [Slang]", "a/y.slangp"} present. }
TEST(SlangNames, AddingAPresetWithoutTheLibrarySaysWhy)
{ // Config::AddStage(si, DISPLAY_CHAIN_SECTION, "a/y.slangp", &error) == false (library unavailable),
  // error.GetDescription() starts with "Slang presets aren't available in this build: ". }
```

- [ ] **Step 2: Run them to see them fail** (`--gtest_filter='SlangNames.*'`, missing header).
- [ ] **Step 3: Write the helpers and wire `GetAvailableShaderNames` / `TryLoadingShader`.**
- [ ] **Step 4: Run them** — pass; then the whole `common-tests` run passes.
- [ ] **Step 5: Commit** — "Util: slang preset names, their lookup and the add-shader list".

### Task 3: The macOS dependency build, and the library in the bundle

(Early, because Tasks 5–8 need a real library to test against on this Mac.)

**Files:**
- Modify: `scripts/deps/build-dependencies-mac.sh`
- Create: `CMakeModules/Librashader.cmake` (`find_library` in the prefix; `librashader_deploy_runtime(target)` modelled
  on `emusync_deploy_runtime`: the bundle's `Frameworks` on macOS, beside the program elsewhere, `install()` under
  `ALLOW_INSTALL`; a missing library is a `message(STATUS …)`, never an error)
- Modify: `CMakeLists.txt` (`include(Librashader)`), `src/duckstation-qt/CMakeLists.txt` (deploy call beside
  emusync's), `.github/workflows/rolling-release.yml` (macOS: `rustup toolchain install 1.88 --profile minimal
  --target x86_64-apple-darwin,aarch64-apple-darwin` before the deps step)

**Interfaces:**
- Produces: `$INSTALLDIR/lib/librashader.dylib` (universal, install name `@rpath/librashader.dylib`), found by
  Task 1's default path in `GooseStation.app/Contents/Frameworks/`.

- [ ] **Step 1: Add the block.** Variables `LIBRASHADER=0.12.1`, `LIBRASHADER_RUST=1.88` at the top. `git clone
  --depth 1 --branch librashader-v$LIBRASHADER` (as SPIRV-Cross is taken), then for each of
  `x86_64-apple-darwin aarch64-apple-darwin`: `rustup run $LIBRASHADER_RUST cargo build -p librashader-capi
  --profile optimized --no-default-features --features runtime-vulkan,runtime-metal --target <t>`; `lipo -create` the
  two `liblibrashader_capi.dylib` into `$INSTALLDIR/lib/librashader.dylib`; `install_name_tool -id
  @rpath/librashader.dylib`. A missing `rustup` stops the script with "rustup not found: install Rust from
  https://rustup.rs".
- [ ] **Step 2: Run the block alone** into `~/work/goosestation-deps` (a scratch copy of just the block, so the rest
  isn't rebuilt): `lipo -info ~/work/goosestation-deps/lib/librashader.dylib` → "x86_64 arm64";
  `otool -D` → `@rpath/librashader.dylib`.
- [ ] **Step 3: Configure and build** `cmake -B build -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo -DBUILD_TESTS=ON
  -DCMAKE_PREFIX_PATH=$HOME/work/goosestation-deps && cmake --build build`:
  `build/bin/GooseStation.app/Contents/Frameworks/librashader.dylib` exists; then
  `GOOSESTATION_LIBRASHADER_PATH=…/librashader.dylib build/bin/common-tests --gtest_filter='Librashader*:Slang*'`
  passes with nothing skipped. A configure with the library moved away still builds (status line, no error).
- [ ] **Step 4: Commit** — "Build: librashader in the macOS dependencies and the app bundle".

### Task 4: The picture maths

**Files:**
- Create: `src/util/postprocessing_slang_picture.h` (header-only, no GPU types beyond `GSVector4i`)
- Test: `src/common-tests/postprocessing_slang_tests.cpp`

**Interfaces:**
- Produces (namespace `PostProcessing::Slang`):
  - `struct PictureSize { u32 width; u32 height; };`
  - `PictureSize NativeSize(u32 crtc_vram_width, u32 crtc_vram_height, bool rotated_quarter);` — swapped when
    rotated 90°/270°; zero stays zero.
  - `u32 BoxFactor(GSVector4i src, PictureSize native);` — N when `src` is exactly N× `native` in both directions
    and N ≥ 2, else 0 (bilinear).
  - `GSVector4i ClampToTarget(GSVector4i rect, u32 target_width, u32 target_height);` — the intersection; may be
    empty.
  - `bool IsUsable(GSVector4i rect, PictureSize native);` — non-empty rect and non-zero native size.

- [ ] **Step 1: Write the failing tests**

```cpp
TEST(SlangPicture, NativeSizeIsTheConsolePictureTurnedWithTheDisplay)
{ EXPECT_EQ(NativeSize(320, 240, false), (PictureSize{320, 240})); EXPECT_EQ(NativeSize(320, 240, true), (PictureSize{240, 320})); }
TEST(SlangPicture, BoxFactorOnlyForExactIntegerMultiples)
{ EXPECT_EQ(BoxFactor(GSVector4i(0, 0, 1280, 960), {320, 240}), 4u);
  EXPECT_EQ(BoxFactor(GSVector4i(16, 8, 16 + 640, 8 + 480), {320, 240}), 2u);
  EXPECT_EQ(BoxFactor(GSVector4i(0, 0, 320, 240), {320, 240}), 0u);
  EXPECT_EQ(BoxFactor(GSVector4i(0, 0, 1104, 720), {368, 240}), 3u);
  EXPECT_EQ(BoxFactor(GSVector4i(0, 0, 1000, 750), {320, 240}), 0u);
  EXPECT_EQ(BoxFactor(GSVector4i(0, 0, 1280, 720), {320, 240}), 0u); }
TEST(SlangPicture, ClampKeepsThePictureOnTheTarget)
{ EXPECT_TRUE(ClampToTarget(GSVector4i(-10, 0, 100, 50), 64, 64).eq(GSVector4i(0, 0, 64, 50)));
  EXPECT_TRUE(ClampToTarget(GSVector4i(100, 100, 200, 200), 64, 64).rempty()); }
TEST(SlangPicture, EmptyRectsAndZeroSizesAreNotUsable)
{ EXPECT_FALSE(IsUsable(GSVector4i(0, 0, 0, 0), {320, 240})); EXPECT_FALSE(IsUsable(GSVector4i(0, 0, 64, 64), {0, 240}));
  EXPECT_TRUE(IsUsable(GSVector4i(0, 0, 64, 64), {320, 240})); }
```

- [ ] **Step 2: Run them to see them fail.** - [ ] **Step 3: Write the header.** - [ ] **Step 4: Run them, pass.**
- [ ] **Step 5: Commit** — "Util: the slang stage's picture maths".

### Task 5: The slang stage, and the chain's picture

**Files:**
- Modify: `src/util/postprocessing_shader_slang.{h,cpp}` (the stage), `src/util/postprocessing_shader.h`,
  `postprocessing_shader_glsl.{h,cpp}`, `postprocessing_shader_fx.{h,cpp}` (the widened `Apply`),
  `src/util/postprocessing.{h,cpp}` (`Chain::Apply`, `Chain::FirstStageReadsGamePicture`), `src/util/gpu_device.{h,cpp}`
  (`CreateSlangChain`), `src/util/shadergen.{h,cpp}` (the box shader), `src/core/gpu.cpp` (`RenderDisplay`)
- Test: `src/common-tests/postprocessing_slang_tests.cpp`

**Interfaces:**
- Consumes: Task 1's `Librashader::LoadPreset`, Task 2's names, Task 4's maths.
- Produces:
  - `src/util/postprocessing.h`:
    ```cpp
    struct PictureInfo
    {
      GSVector4i rect;               // where the picture sits in the input and output (device coordinates)
      u32 native_width, native_height; // the console's picture (Task 4's NativeSize)
      GPUTexture* game_texture;      // the game's own frame; set only for the display chain's first stage
      GSVector4i game_view;          // its view rect, in texels
    };
    ```
    `Shader::Apply(…existing…, const PictureInfo& picture)`; GLSL and ReShade ignore it.
    `Chain::Apply(…existing…, const PictureInfo& picture)` passes `game_texture` to stage 0 only.
    `bool Chain::FirstStageReadsGamePicture() const;` — active and stage 0 is a `SlangShader`.
  - `src/util/gpu_device.h`:
    ```cpp
    class SlangChain
    {
    public:
      virtual ~SlangChain();
      virtual void SetParameter(const char* name, float value) = 0;
      virtual bool Frame(GPUTexture* input, GPUTexture* output, u64 frame_count, Error* error) = 0;
    };
    virtual std::unique_ptr<SlangChain> GPUDevice::CreateSlangChain(const std::string& preset_path, Error* error);
    ```
    The default fails with "Slang presets aren't supported by the {RenderAPIToString} renderer."
  - `ShaderGen::GenerateBoxDownsampleFragmentShader(u32 factor)` — `u_src_rect` like the copy shader, averages
    factor×factor texels; integer literals typed for GLSL ES (the #53 lesson).
  - `class SlangShader final : public Shader` — `bool LoadFromFile(std::string name, std::string path, bool
    only_config, Error* error)`; options from `libra_preset_get_runtime_params` as `Type::Float`, `vector_size 1`,
    `name`, `ui_name = description`, default/min/max/step from the parameter, in preset order;
    `WantsDepthBuffer() == false`; design calls 1–6. When `CompilePipeline` fails (no glue for this renderer, or
    the preset), the stage posts its own OSD message (key `"PostProcessSlang"`, Global Constraints' text) before
    returning false, so the chain's generic "Failed to compile…" isn't the only word.
- `RenderDisplay`: both chains get a `PictureInfo` (internal: rect = the whole view, native =
  `display_vram_width/height`; display: rect = `real_draw_rect`, native from `NativeSize(…, rotated)`). When
  `DisplayChain.FirstStageReadsGamePicture()` and rotation is Normal, the display chain gets `game_texture`/`game_view`
  and the display texture isn't drawn into the chain's input (the input is still cleared).

- [ ] **Step 1: Write the failing tests** (no GPU: `g_gpu_device` is null in `common-tests`)

```cpp
TEST(SlangStage, OptionsAreThePresetsParameters)
{ // GTEST_SKIP without the library. A temp preset (the fork's TEST_GAIN/TEST_FLAG shader):
  // Config::GetShaderOptions("p/test.slangp") -> 2 options, [0] name "TEST_GAIN", ui_name "Test gain",
  // type Float, vector_size 1, default 1.0, min 0.0, max 2.0, step 0.1; [1] "TEST_FLAG" 0/0/1/1. }
TEST(SlangStage, StoredOptionsComeBackAsValues)
{ // AddStage + SetStageOption(TEST_GAIN = 1.5) -> GetStageOptions(...)[0].value[0].float_value == 1.5f,
  // and the ini has [PostProcessing/Stage1] ShaderName = p/test.slangp, TEST_GAIN = 1.5. }
TEST(SlangStage, ABrokenPresetFailsWithLibrashadersText)
{ // preset naming a missing .slang: AddStage false, error mentions the missing file's name. }
TEST(SlangStage, ChangedOptionsAreMarkedForTheRunningChain)
{ // SlangShader loaded with only_config=false and no device: LoadOptions with TEST_GAIN=1.5 marks exactly
  // TEST_GAIN (exposed for tests as GetPendingParameters()). }
```

- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Write the stage, `PictureInfo` plumbing, `CreateSlangChain`'s default and the box shader.**
- [ ] **Step 4: Run them, pass; whole `common-tests` passes; `duckstation-qt` builds.**
- [ ] **Step 5: Check by hand on this Mac (Metal, before its glue: the "isn't supported by the Metal renderer"
  message appears once, the chain turns off, the game keeps presenting).**
- [ ] **Step 6: Commit** — "Util, core: a slang preset is a post-processing stage, fed the game's picture".

### Task 6: Metal glue

**Files:**
- Create: `src/util/metal_slang.mm` (`class MetalSlangChain final : public SlangChain`, `#define LIBRA_RUNTIME_METAL`)
- Modify: `src/util/metal_device.h` (`friend class MetalSlangChain;`, `CreateSlangChain` override),
  `src/util/CMakeLists.txt`

**Interfaces:** Consumes Task 1's loader and `LoadPreset(…, LIBRA_PRESET_CTX_RUNTIME_METAL, …)` (each glue re-parses its preset with its runtime set, so
`$VID-DRV$`-style wildcards resolve), Task 5's
`SlangChain`. Each glue keeps its `PFN_libra_*_filter_chain_{create,frame,set_param,free}` from
`Librashader::GetSymbol`, resolved once; a missing one fails `CreateSlangChain` with "librashader is missing symbol
{}".

- [ ] **Step 1: Write it** (the fork's `GSDeviceMTL` glue): create with `m_queue`; `Frame`: `EndAnyEncoding()`,
  `libra_mtl_filter_chain_frame(&chain, m_render_cmdbuf, frame_count, input, output, &viewport{0,0,w,h}, nullptr,
  nullptr)`, then `SubmitCommandBuffer()` (librashader recycles per-frame resources on its own ring; the fork found a
  frame must end a batch); free waits for the GPU (`WaitForPreviousCommandBuffers` or equivalent) first.
- [ ] **Step 2: Check by hand** (Metal, a scratch portable data folder, padtest at 4× resolution; the test preset
  of Task 5 plus `crt/crt-royale.slangp` and `ntsc/ntsc-256px-svideo.slangp` from a scratch clone of
  `libretro/slang-shaders` in its shaders folder): the picture is shaded, upright, in place; changing `TEST_GAIN` in
  Qt's stage options changes it at once without a reload; a broken preset says "Slang preset … failed: …" once; a
  preset second in the chain after `crt-lottes [GLSL]` and first in the internal chain both run. Screenshots in
  `/tmp/slang-checks/`.
- [ ] **Step 3: Commit** — "Util: slang presets under Metal".

### Task 7: Vulkan glue

**Files:**
- Create: `src/util/vulkan_slang.cpp` (`class VulkanSlangChain`, `#define LIBRA_RUNTIME_VULKAN`)
- Modify: `src/util/vulkan_device.h` (friend, override), `src/util/CMakeLists.txt`, `src/util/util.vcxproj(.filters)`

- [ ] **Step 1: Write it** (the fork's `GSDeviceVK` glue): `libra_device_vk_t{physical_device, instance, device,
  queue = m_graphics_queue, entry = vkGetInstanceProcAddr}`; `frames_in_flight = NUM_COMMAND_BUFFERS`;
  `EndRenderPass()` before create (it submits its own LUT uploads) and before each frame; input
  `TransitionToLayout(ShaderReadOnly)`, output `TransitionToLayout(ColorAttachment)`; after the frame
  `OverrideImageLayout(ColorAttachment)` then `InvalidateCachedState()`; free after
  `SubmitCommandBuffer(true)` when a command buffer is open.
- [ ] **Step 2: Check by hand on this Mac under Vulkan (MoltenVK)**, as Task 6's step 2.
- [ ] **Step 3: Commit** — "Util: slang presets under Vulkan".

### Task 8: Android's librashader, in the APK

**Files:**
- Modify: `scripts/deps/build-dependencies-android.sh`, `android/app/build.gradle.kts` (`librashader.so` in
  `depsSharedLibraries`), `.github/workflows/android.yml` (`rustup toolchain install 1.88 --profile minimal --target
  aarch64-linux-android` before the deps step), `docs/android.md` (the deps script's paragraph: Rust is needed)

- [ ] **Step 1: Add the block** without `cargo-ndk` (CI shouldn't install it): the NDK's clang as linker and C/C++
  compiler (`CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER`, `CC_aarch64_linux_android`, `CXX_aarch64_linux_android` =
  `$NDK/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android29-clang(++)`, `AR_aarch64_linux_android` =
  `llvm-ar`), **`CXXSTDLIB_aarch64_linux_android=c++_static`** (no `libc++_shared.so` to ship), `RUSTFLAGS="-C
  link-arg=-Wl,-soname,librashader.so -C link-arg=-Wl,-z,max-page-size=16384"`, features
  `runtime-vulkan,runtime-opengl`, `--target aarch64-linux-android`; install as `$INSTALLDIR/lib/librashader.so`.
- [ ] **Step 2: Run it**, then check: `llvm-readelf -d librashader.so` has SONAME `librashader.so` and no
  `libc++_shared.so` among NEEDED; `llvm-readelf -l` LOAD alignment 0x4000.
- [ ] **Step 3: Build and test:** `./gradlew assembleDebug` (the APK lists `lib/arm64-v8a/librashader.so`:
  `unzip -l`), `run-instrumented-tests.sh quick` green, and a `LibrashaderLoader` native test through
  `android/scripts/run-native-tests.sh --gtest_filter='Librashader*:Slang*'` with the library pushed beside
  `common-tests` (the script pushes the deps' shared libraries; add it there) passes unskipped.
- [ ] **Step 4: Commit** — "Android: librashader in the dependencies and the APK".

### Task 9: OpenGL glue, and the OpenGL ES spike

**Files:**
- Create: `src/util/opengl_slang.cpp` (`class OpenGLSlangChain`, `#define LIBRA_RUNTIME_OPENGL`)
- Modify: `src/util/opengl_device.{h,cpp}` (friend, override, `void InvalidateCachedState();` — sets every
  `m_last_*`/`m_current_*` cache to "unknown" and rebinds the device's VAO, so the next draw re-applies all state),
  `src/util/CMakeLists.txt`, `util.vcxproj(.filters)`
- Test (Android, instrumented): `androidTest/…/core/SlangFrameTest.kt` (`@BootsGames`)

**Interfaces:** the GL loader is a static thunk to `m_gl_context->GetProcAddress`; `glsl_version` 330 on desktop,
`0` (detected by librashader) on ES; `use_dsa = false`; `disable_cache = false`.

- [ ] **Step 1: Write the orientation test first** — `SlangFrameTest`: padtest running into a `FrameReader`, a
  display chain of one slang stage, the preset `orientation.slangp` (pushed into the test data folder's `shaders/`:
  one pass painting red where `vTexCoord.y < 0.5`, blue elsewhere, with `scale_type0 = viewport`): the top quarter
  of the frame's picture is red and the bottom quarter blue. `@Test aSlangStageRunsUnderVulkan` and, in the full
  tier's OpenGL ES rerun, `aSlangStageRunsUnderOpenGLES` (skipped when the core says slang isn't supported there).
- [ ] **Step 2: Run it** — fails (no GL glue; on Vulkan it passes once Task 7 is in, which tells the test is right).
- [ ] **Step 3: Write the glue.** GPUDevice's GL textures are stored top row first (the UV-quad shader flips
  `v_pos.y` on GL), but librashader's GL runtime treats texel row 0 as the bottom, so presets that aren't
  symmetric (bezels, LUTs, masks with a phase) would run upside down: on GL the stage's downsample writes the native
  texture flipped (`u_uv_min/max` swapped in y), and `Frame` passes `mvp` = `{1,0,0,0, 0,-1,0,0, 0,0,1,0, 0,0,0,1}`
  so the output lands top row first again. After the frame: `InvalidateCachedState()`.
- [ ] **Step 4: The spike** — run `SlangFrameTest` on the OpenGL ES emulator (SwiftShader), with both orientation
  and `crt/crt-royale.slangp`. **If it passes**, slang stays on under OpenGL ES. **If librashader can't run there**
  (shader compile or missing GL ES entry points), `OpenGLDevice::CreateSlangChain` fails under ES with "Slang presets
  aren't supported by the OpenGL ES renderer." and Task 10 hides slang presets under OpenGL ES; either way the
  result, with librashader's text, goes into `docs/android.md` and the plan's ledger.
- [ ] **Step 5: Run the test and the quick tier. Desktop OpenGL on this Mac:** a local-only librashader build with
  `runtime-opengl` added (never shipped: macOS builds without OpenGL) and a `-DENABLE_OPENGL=ON` build, then Task 6's
  checks under OpenGL (macOS's GL 4.1 core); Windows and Linux OpenGL are built by CI only.
- [ ] **Step 6: Commit** — "Util: slang presets under OpenGL, and what OpenGL ES does with them".

### Task 10: Android lists and labels slang presets

**Files:**
- Modify: `android/app/src/cpp/android_host.{h,cpp}` (`ShaderKind`: a preset name is `"Slang"`;
  `GetPostProcessingShaders` skips `ShaderLoads` for presets; `GetPostProcessingStages` also passes the load error),
  `android/app/src/cpp/jni_bridge.cpp` (`StageInfo` gains `error`), `core/PostProcessingInfo.kt`
  (`StageInfo.error: String?`), `settings/PostProcessingSettings.kt` (`ShaderKind.Slang`; `offered()` hides `Slang`
  under OpenGL ES only if Task 9 gated it; `Stage.problem()` returns the core's text for a stage that doesn't load
  when it has one, else `STAGE_NOT_FOUND`), `app/FakeCoreApi.kt`, `ui/SamplePostProcessing.kt`
- Test: `test/…/settings/PostProcessingSettingsTest.kt`, `test/…/app/PostProcessingPageTest.kt`,
  `androidTest/…/core/PostProcessingAccessTest.kt`, `androidTest/…/ui/PostProcessingScreenTest.kt`

- [ ] **Step 1: Write the failing tests**
  - JVM: `aSlangPresetIsOfferedWithItsKind` (catalogue `"crt/royale.slangp" to "Slang"` → `Shader(…, Slang)`);
    `aSlangStageWithoutTheLibrarySaysWhy` (`StageInfo(kind = "", error = "Slang presets aren't available in this
    build: …")` → `problem()` is that text); if Task 9 gated ES, `slangIsntOfferedOnOpenGLES`.
  - Instrumented, real JNI: `aSlangPresetIsListedWithoutLoading` (a garbage `junk.slangp` in `shaders/` is listed
    with kind `Slang`; adding it fails with librashader's text); `aSlangStagesOptionsAreItsParameters` (the test
    preset's `TEST_GAIN` option: Float, 0..2, step 0.1); `aSlangParameterIsWrittenInTheCoresFormat`
    (`TEST_GAIN = 1.5` in the ini).
  - Screen: the stage row's summary reads "Slang · 2 options".
- [ ] **Step 2: Run them** (JVM: `./gradlew testDebugUnitTest`; instrumented: quick tier) — fail.
- [ ] **Step 3: Implement.** The Add a shader page, the stage page and the chains page are unchanged in layout, so
  `FocusAuditTest`/`OrientationLayoutTest` keep their entries; add one sample Slang stage to
  `SamplePostProcessing.kt` so both audits cover a slang stage's page.
- [ ] **Step 4: Run them, pass;** `./gradlew assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs`
  green; quick tier green.
- [ ] **Step 5: Commit** — "Android: slang presets in the add-shader list, listed without loading".

### Task 11: Windows: librashader, DXC, and the D3D11 and D3D12 glue

**Files:**
- Modify: `scripts/deps/build-dependencies-windows-x64.bat`, `build-dependencies-windows-arm64.bat`
  (`--target aarch64-pc-windows-msvc` under the script's `vcvarsamd64_arm64`), `src/util/util.props` (`DepsDLLs`:
  `librashader.dll`, `dxcompiler.dll`, `dxil.dll`; the include dir), `.github/workflows/rolling-release.yml` (both
  Windows jobs: `rustup toolchain install 1.88 --profile minimal` with `--target aarch64-pc-windows-msvc` for ARM64)
- Create: `src/util/d3d11_slang.cpp`, `src/util/d3d12_slang.cpp`
- Modify: `src/util/d3d11_device.{h,cpp}` (friend, override, `void InvalidateCachedState();` — every `m_current_*`
  to null/zero so the next draw re-binds targets, shaders, states, topology, input layout, buffers and samplers),
  `src/util/d3d12_device.h` (friend, override), `util.vcxproj(.filters)`

- [ ] **Step 1: The deps blocks** (as the fork's `build-dependencies.bat:551-563`, with `runtime-opengl` added and
  `--target` for ARM64): `librashader_capi.dll` → `%INSTALLDIR%\bin\librashader.dll` (+ `.pdb`); DXC from the NuGet
  package `Microsoft.Direct3D.DXC` 1.9.2609.5 (the fork's version; SHA-256 re-checked on download), its
  `build\native\bin\{x64,arm64}\dxcompiler.dll` and `dxil.dll` into `%INSTALLDIR%\bin`.
- [ ] **Step 2: The glue** (the fork's `GSDevice11`/`GSDevice12`): D3D11 unbinds render targets and SRVs before the
  frame, passes the immediate context (`nullptr`), SRV of the input, RTV of the output, then
  `InvalidateCachedState()`; D3D12 `EndRenderPass()`, transitions input to `PIXEL_SHADER_RESOURCE` and output to
  `RENDER_TARGET`, `libra_image_d3d12_t` as `LIBRA_D3D12_IMAGE_TYPE_RESOURCE`, then `InvalidateCachedState()` and
  `SetDescriptorHeaps` with the device's two heaps (they're set only when a command list starts).
- [ ] **Step 3: Verify** — this Mac can't build Windows: after the user's word, `gh workflow run
  rolling-release.yml --ref claude/slang-shaders` and both Windows jobs pass; the zips contain `librashader.dll`,
  `dxcompiler.dll`, `dxil.dll`. Run-time checks on Windows are the user's (D3D11, D3D12, Vulkan, OpenGL).
- [ ] **Step 4: Commit** — "Windows: librashader and DXC in the dependencies, slang presets under D3D11 and D3D12".

### Task 12: Linux AppImage

**Files:**
- Modify: `scripts/deps/build-dependencies-linux.sh` (block; `runtime-vulkan,runtime-opengl`; SONAME as Android's),
  `scripts/appimage/make-appimage.sh` (copy `$BUILDDIR/bin/librashader.so` into `usr/bin`, as emusync is),
  `rolling-release.yml` (Linux job: `rustup toolchain install 1.88 --profile minimal`)

- [ ] **Step 1: Write the block and the copy.** - [ ] **Step 2: Verify through CI** (the dispatched run): the
  AppImage job passes and `--appimage-extract` lists `usr/bin/librashader.so`.
- [ ] **Step 3: Commit** — "Linux: librashader in the dependencies and the AppImage".

### Task 13: Flatpak

**Files:**
- Create: `scripts/flatpak/modules/28-librashader.yaml`, `scripts/flatpak/modules/28-librashader-cargo-sources.json`
  (generated by `flatpak-builder-tools`' `cargo/flatpak-cargo-generator.py` from the tag's `Cargo.lock`, in a venv;
  its header comment in the module names the command)
- Modify: the manifest (`sdk-extensions` + `org.freedesktop.Sdk.Extension.rust-stable`; the module after
  `27-lunasvg`), `rolling-release.yml:324` (cache key `hashFiles('scripts/flatpak/**')`)

- [ ] **Step 1: The module:** `buildsystem: simple`; `build-options: append-path: /usr/lib/sdk/rust-stable/bin`,
  `env: CARGO_HOME: /run/build/librashader/cargo`; sources: the tag's git (url, tag, commit) and the JSON;
  `cargo --offline build -p librashader-capi --profile optimized --no-default-features --features
  runtime-vulkan,runtime-opengl` with the SONAME flag; `install -Dm755 target/optimized/liblibrashader_capi.so
  /app/lib/librashader.so`.
- [ ] **Step 2: Lint here** (`flatpak-builder-lint manifest`, if installable; else in CI), **verify through CI**:
  the Flatpak job passes, `/app/lib/librashader.so` is in the bundle.
- [ ] **Step 3: Commit** — "Flatpak: librashader as a module of its own, with vendored cargo sources".

### Task 14: Big Picture: both chains, and post-processing for a game

**Files:**
- Modify: `src/util/postprocessing.{h,cpp}` (`void Config::CopyChain(const SettingsInterface& from,
  SettingsInterface& to, const char* section);` — clears `to`'s chain, then copies `Enabled`, `StageCount` and each
  stage section), `android/app/src/cpp/android_host.cpp` (`CopyPostProcessingChain` calls it), `src/core/fullscreen_ui.cpp`
- Test: `src/common-tests/postprocessing_config_tests.cpp`

**Interfaces:** the page draws a "Display chain"/"Internal chain" choice (`s_postprocessing_chain`, default
Display) at the top; `PopulatePostProcessingChain(bsi, section)` also runs when a game's settings open and when the
chain choice changes.

- [ ] **Step 1: Write the failing tests** — `CopyChainCopiesStagesAndOptions` (two stages with options, `Enabled`
  true → identical sections in `to`, a stale `Stage3` in `to` gone); `CopyChainOfAnEmptyChainClearsIt`.
- [ ] **Step 2: Run them, fail.** - [ ] **Step 3: Implement** `CopyChain`, switch Android to it (its JVM and
  `PostProcessingGameTest`/`GameCallsTest` keep passing), then the page: the chain choice; `section` from it
  (`Enabled` too); `SettingsPage::PostProcessing` in `per_game_pages`; for a game, the chain's "Use Global Setting /
  On / Off" (`DrawToggleSetting` tri-state on `Enabled`): turning it On/Off when the game's file has neither
  `Enabled` nor `StageCount` copies the global chain first (`CopyChain`), and "Use Global Setting" with stages
  asks first ("Use the global chain? This game's own stages are removed.") then clears the game's chain; while a
  game's chain is the global one, the stages and options are hidden behind "This game uses the global chain."
- [ ] **Step 4: Run** `common-tests` and **check by hand** (Big Picture on this Mac, keyboard and a controller if
  one's attached: both chains globally; a game's own chain, its copy from the global one, back to global; a slang
  stage's options; nothing changed in the other pages).
- [ ] **Step 5: Commit** — "Big Picture: the internal chain, and post-processing in a game's settings".

### Task 15: Notices and docs

**Files:**
- Modify: `data/resources/thirdparty.html` (librashader, MPL-2.0 text; its C header, MIT; on Windows DXC — its
  NuGet package's licence files), `docs/android.md` (Post-processing: slang presets, where they go for now (the data
  folder's `shaders/`, mirrored — packs are stage 2), what runs under Vulkan and OpenGL ES; Known limits; results
  tables), `docs/roadmap.md` (item 17: "stage 1 done (#NN): …; stages 2 and 3 to come")

- [ ] **Step 1: Write them.** - [ ] **Step 2: Commit** — "Docs: slang presets, stage 1, and librashader's licences".

### Task 16: Verification and the branch

- [ ] `common-tests` all green on this Mac (with and without `GOOSESTATION_LIBRASHADER_PATH`); `duckstation-qt`
  builds; Big Picture and Qt checks of Tasks 6, 7, 9, 14 re-run on the final branch.
- [ ] Android: `./gradlew assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs`; full tier in the
  background (`GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh full`), result XML
  read (count, zero failures, nothing skipped that shouldn't be).
- [ ] CI on the branch (the user's word: `gh workflow run`): every job green, artifacts carry the library.
- [ ] The Thor (Adreno), only with the user's go-ahead: slang stages under Vulkan (and OpenGL ES if it's on) at
  full speed, no corruption; if the frame tears or hangs, add a command buffer submit after the chain's frame
  behind `__ANDROID__` (ARMSX2's finding) and recheck. The Thor is restored after.
- [ ] Push `claude/slang-shaders-stage1` to `origin/claude/slang-shaders`; `superpowers:finishing-a-development-branch`;
  a PR to `master` only on the user's word.
