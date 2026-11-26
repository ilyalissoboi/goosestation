<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Shader and pipeline compilation on several threads — design

Roadmap item 23 (from the upstream gaps players notice most, `docs/upstream-features.md` tier 3). Agreed in
conversation on 2026-11-26. Calls made while writing this up, not yet confirmed, are marked *(writer's call)*; calls
the builder may make are marked *(builder's call)*.

## Intent

The player's picture is the **AYN Thor**: a slow start, or a hitch, the first time a game or a setting is seen *(the
player's choice)*. Measured (below), GooseStation builds every shader and pipeline up front, in one serial pass, while
both the emulation and GPU threads wait: a short pause at the defaults, but up to 4.8 s with the texture cache or the
PGXP depth buffer on, again after every app or driver update that empties the cache, and whenever a new graphics
setting is tried mid-game. This makes **cold builds much shorter, on every renderer** *(the player's choice)*, desktop
and Android, while the game still waits for every pipeline, so nothing is ever drawn with one missing *(the player's
choice)*.

## Measured on the Thor (2026-11-26)

Vagrant Story (USA), `.debug` build at `36e87c9b0`, 3×, offscreen 1920×1080 surface; the time of
`GPU_HW::CompilePipelines` and of the `GPUDevice::CreateShader` calls in it, added to a scratch copy only. "Empty"
deletes GooseStation's cache folder first; OpenGL ES isn't fully cold then, since Android keeps its own driver cache per
app.

| Renderer, settings | Pipeline pass, empty cache | Warm cache | Boot to game start, empty / warm |
|---|---|---|---|
| Vulkan, defaults (81 shaders) | 671 ms (358 ms of it in `CreateShader`) | 78 ms | 1.0 s / 0.39 s |
| OpenGL ES, defaults (208 shaders) | 627 ms | 95 ms | 1.45 s / 0.42 s |
| Vulkan, texture cache + PGXP depth (411 shaders) | 4,757 ms (2,498 ms shaders, the rest pipelines) | 287 ms | 5.1 s / 0.61 s |
| OpenGL ES, texture cache + PGXP depth (354 shaders) | 2,247 ms | 149 ms | 2.5 s / 0.52 s |

- **A mid-game setting change** (to 5× with 4× MSAA) rebuilds everything: 0.75 s (Vulkan) and 1.0 s (OpenGL ES) at the
  defaults the first time, 4.9 s on Vulkan with the heavier settings; 0.1–0.4 s once cached. (With the heavier
  settings on OpenGL ES, that change logged no pipeline pass at all; not followed up *(for the builder to check)*.)
- On Vulkan about half the cold time is shaderc (GLSL to SPIR-V), the rest the driver's `vkCreateGraphicsPipelines`;
  on OpenGL ES almost all of it is the driver's compile and link.

## What exists

From a map of the code (2026-11-26, line numbers as of `36e87c9b0`):

- **Everything up front, in one serial pass.** `GPU_HW::CompilePipelines` (`gpu_hw.cpp:1179-1898`) runs at
  `Initialize` (:261) and from `UpdateSettings` when shaders change (:650-676, :735-744): generate a shader, create
  it, create its pipelines, in nested loops. The draw indexes a fixed array (`m_batch_pipelines`, :2115; `gpu_hw.h:462`),
  so there's no on-demand path. At the defaults: 5 vertex and 64 fragment shaders and 640 batch pipelines on desktop
  (192 and 480 on Vulkan with framebuffer fetch), and about 14 utility pipelines. **Many are duplicates**: check-mask
  does nothing without shader blending, the transparency variants under "disabled" and "only opaque" are identical,
  and the dither loop runs with true colour on. Sprite modes (up to 7 texture modes), the texture cache (up to 13), the
  PGXP depth buffer (pipelines doubled), ROV depth (fragment shaders doubled) and shader blending multiply them;
  resolution, MSAA, filtering and most other settings change every shader's source, so all of them miss the cache.
- **A bug in passing:** `gpu_hw.cpp:1638` reads `= CreatePipeline(plconfig), error)`, a comma operator, so a failure
  creating the VRAM copy pipeline goes unnoticed.
- **The progress screen:** "Compiling Pipelines" (`ShaderCompileProgressTracker`, :153-184) shows only after 1 s, then
  every 0.1 s, through `Host::DisplayLoadingScreen` (`imgui_overlays.cpp:86-176`), drawn on the GPU thread while the
  emulation thread waits.
- **Threads:** the device belongs to the GPU thread (`CHECK_OWNER_THREAD`, `gpu_device.cpp:31-40`); `GPU_HW::Initialize`
  and `UpdateSettings` run under `RunOnThreadAndWait` (`gpu_hw_frontend.cpp:77-82, 233-234`), so the emulation thread
  waits for the whole pass. **Nothing compiles in parallel** anywhere.
- **Compilers:** one global shaderc compiler (`gpu_device.cpp:1347-1517`), spirv-cross for MSL, HLSL and GLSL
  (:1519+, :1782), `D3DCompile` for D3D.
- **The shader cache** (`gpu_shader_cache.cpp`): `<cache>/<api>_shaders.idx`/`.bin`, zstd entries keyed by stage,
  language and the source's MD5, version `SHADER_CACHE_VERSION` 18; entries appended and flushed at once; not
  thread-safe (one `FILE*`, an unordered map). Vulkan stores SPIR-V, D3D DXBC, Metal the MSL text (recompiled with
  `newLibraryWithSource` on every run), OpenGL nothing (it links at program creation).
- **Driver pipeline caches** (`<api>_pipelines.bin`): Vulkan's `VkPipelineCache` and D3D12's pipeline library are
  written only at `GPUDevice::Destroy` (`gpu_device.cpp:401-409, 459-488`, skipped when unchanged), so a run Android
  kills loses them; OpenGL program binaries are appended after each link (`opengl_pipeline.cpp:755-990`); none for
  D3D11 or Metal. A new or invalid shader cache deletes the pipeline cache too (:429-439).
- **Post-processing rebuilds during present** (`gpu_presenter.cpp:334, 366`) whenever the target's format or size
  changes (GLSL rebuilds its pipeline, ReShade FX recompiles the whole effect), with no progress: a window or output
  resize stutters. Slang presets use librashader's own cache.
- **Not thread-safe today:** the shader cache, the global shaderc compiler and spirv-cross, OpenGL's program cache and
  its disk append, Vulkan's render-pass cache (`GetRenderPass`).

## Goals

- **Shaders and pipelines built on several threads** *(the player's choice: the cold builds, on every renderer)*:
  Vulkan, D3D12, D3D11 and Metal compile shaders and create pipelines on a worker pool; OpenGL and OpenGL ES use
  `KHR_parallel_shader_compile` where the driver has it (compiles and links queued, their status read only once all
  are queued), not extra threads, since a GL context belongs to one thread *(writer's call)*. `dep/glad` already loads
  `GL_KHR_parallel_shader_compile` and `GL_ARB_parallel_shader_compile`; nothing uses them yet. **Where the extension is
  missing or doesn't help** (Apple's OpenGL 4.1 has none; whether Adreno's does anything is unmeasured), OpenGL gets
  the duplicate removal only: **no worker threads with shared contexts** *(writer's call: they're the riskiest part on
  mobile drivers, against the first anti-goal)*, and its targets below apply only where the extension helps. Shaderc
  and spirv-cross run once per worker *(builder's call how)*. The pass still finishes before the game draws anything
  *(the player's choice)*.
- **Duplicates built once** *(the player's choice)*: pipelines that would be identical are created once and shared,
  with every frame drawn exactly as today.
- **Caches safe and stable** *(writer's call)*: the shader cache, the driver caches and their files are safe across the
  workers, and their contents don't depend on which worker finished first, so an unchanged cache is never rewritten.
- **The Vulkan pipeline cache saved as it grows** *(writer's call)*: after a pass that added to it, not only at a clean
  shutdown, so a run Android kills keeps what it built; D3D12's pipeline library likewise.
- **Post-processing doesn't rebuild on every resize** *(writer's call)*: a size change reuses what it can (GLSL stages
  depend only on the format), and a rebuild that is needed goes through the same pool, with present still waiting for
  it. ReShade FX bakes the output size into its source (`postprocessing_shader_fx.cpp:402-405`), so it still rebuilds
  on a resize, only faster: no frames drawn with an old or missing effect, and nothing compiled at another size
  *(writer's call, from "nothing drawn differently" and the non-goals)*.
- **One advanced setting** *(the player's choice)*: "Shader compile threads" on Qt, Big Picture and Android (a new key
  *(builder's call)*), **Automatic by default**, picked from the device's cores and memory *(builder's call how)*; 1
  is today's single-threaded build. A renderer or driver found to misbehave gets 1 automatically, with the reason in the
  log, **found two ways** *(writer's call)*:
  - **a fixed list** of renderer, vendor and driver versions, from what the build sees misbehave on the Thor, the
    emulators, the Mac and Windows;
  - **a guard at run time**, for drivers nobody tested: a marker written before a pass on several threads and cleared
    after it; a pass that never cleared it (a crash, a hang the app was killed for, or a low-memory kill, which can't
    be told apart reliably) makes Automatic use 1 for that renderer and driver version from the next start, until the
    driver changes or the player sets a number themselves. The log and the setting's row say why.
- **The progress screen stays**, shorter *(writer's guess, not contradicted)*.
- The comma-operator bug at `gpu_hw.cpp:1638` is fixed in passing *(writer's call)*.

## Success

*(The player's choice: measured cold and warm, with nothing drawn differently and nothing missing; the numbers are the
writer's calls.)* On the Thor, before and after, the scenarios above:

- Vulkan with the texture cache and PGXP depth: the cold pass at most 1.5 s (from 4.8 s), and at the defaults at most
  half of today's cold time; OpenGL ES the same (at most 1.2 s from 2.2 s, half at the defaults) **if the driver's
  parallel compile helps**; otherwise whatever the duplicate removal gives, reported *(writer's call)*;
- warm passes and starts no slower than today, and a mid-game setting change shorter in the same proportion;
- peak memory during a cold pass (`Debug.getMemoryInfo`'s total PSS) at most 150 MB above today's, and no
  low-memory kill *(writer's call)*;
- an unchanged cache isn't rewritten ("Skipping updating pipeline cache"), and a Vulkan cache built in a run that's
  killed is there at the next start;
- on the Mac (Metal, MoltenVK, OpenGL) and the Windows machine (D3D11, D3D12, Vulkan, OpenGL), cold passes clearly
  shorter (OpenGL as above: on the Mac, by the duplicate removal only) and warm ones no slower, with every regression-test frame identical with the threads at Automatic and at 1.

## Non-goals

- Starting the game before every pipeline is built, or building pipelines on first use.
- Changing what the shaders compute.
- Precompiling for games or settings not yet seen.

## Anti-goals

*(The player's choice: any of these makes it a failure.)*

- **A crash or hang** from a driver that can't take several threads.
- **Android killing the app for memory** during a pass.
- **A broken cache, or one rewritten on every run.**
- **Anything drawn differently from today.**
- Anything from DuckStation's code after `a5bf1ba5cee7`.

## Constraints

- **Clean room**: nothing from DuckStation after `a5bf1ba5cee7`, whose parallel compilation came after it; its commit
  titles and user documentation only. **PCSX2 for ideas only** *(the player's choice)*: how it queues compiles, sizes
  its worker pool and keeps its caches safe across threads, from the user's fork
  (https://github.com/ilyalissoboi/pcsx2, GPL-3.0); GooseStation's own code, none copied.
- **One stage, one PR** *(the player's choice)*; `docs/roadmap.md` (item 23) updated.
- **Tests**: native tests for what needs no GPU (the duplicate detection, the cache's ordering and thread safety, the
  worker count rule) in `src/common-tests`; ThreadSanitizer on the pass where the platform allows; the regression tests'
  frames on the Mac and Windows with the threads at Automatic and at 1; Android's quick tier while building and the full
  tier before the PR.
- **The Thor** *(the player's choice)*: the building session may measure there whenever it's connected (adb
  `64dc3c35`), with the `.debug` app and scratch data in `/sdcard/GooseTest` only, never the release app (the plain ID,
  never uninstalled) or the user's data folder, and cleans up afterwards; the measurement test stays out of the
  repository. Playing and watching for glitches are the user's.
- **Desktop**: the Mac here; Windows over SSH on the user's machine (`pcsx2-win`).
- The user reviews every commit message.

## Left to the builder

The worker pool and how work is split, how duplicates are found, how the caches are made safe and ordered, the
Automatic rule, which drivers get one thread, how post-processing avoids rebuilding, and the order of implementation.
