<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Shader and pipeline compilation on several threads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cold pipeline passes much shorter on every renderer. Shaders and pipelines are built on a worker pool
(Vulkan, D3D12, D3D11, Metal), or queued through `KHR_parallel_shader_compile` on OpenGL. Duplicates are built once,
the caches stay safe and stable, and the Vulkan and D3D12 caches are saved after each pass. Post-processing stops
rebuilding on every resize. A "Shader compile threads" setting (Automatic; 1 is today) comes with a fixed list and a
run-time guard. The game still waits for every pipeline.

**Architecture:**
- **Describe, then build.** `GPU_HW::CompilePipelines` keeps its loops. They no longer create anything: each
  `CreateShader` becomes `batch.AddShader(...)`, which returns a handle, and each `CreatePipeline` becomes
  `batch.AddPipeline(config, shader handles, &slot)`. A new `GPUPipelineBatch` (`src/util`) removes duplicates as it
  goes: shaders with the same stage, language, entry point and source, and pipelines whose whole config matches, with
  shader handles in place of pointers. Then `Build` makes every unique shader and then every unique pipeline, in two
  phases, and fills the slots. One pipeline can fill many slots, so `GPU_HW`'s pipeline members become raw
  `GPUPipeline*`, and the objects are owned by a `std::vector<std::unique_ptr<GPUPipeline>> m_pipeline_storage`.
- **The pool.** `Threading::RunJobs(count, threads, job, on_progress)` (`src/common/job_runner.h`) runs jobs from an
  atomic index on `threads - 1` workers plus the calling thread, and stops handing out jobs after a failure. Progress
  is reported only on the calling thread (the GPU thread, which draws the loading screen). With `threads == 1`, every
  job runs in order on the caller, which is today's pass. Workers are started for each call with a 4 MiB stack
  (glslang recurses) and joined before it returns.
- **The device's batch API.** `GPUDevice::CreateShaders` and `GPUDevice::CreatePipelines` take spans. Workers never
  touch the shader cache: the caller looks up every shader first, the workers create from binary or source, and the
  caller then inserts the new binaries in request order. So the file's order doesn't depend on which worker finished
  first. Everything a worker made is destroyed on the caller, since every backend defers destruction through
  GPU-thread queues. The backends' shared maps get a lock: Vulkan's render passes, D3D11's state objects, Metal's
  depth states. Shaderc and spirv-cross are loaded once, under a lock. D3D12 names a pipeline with its render-pass
  flags when they're set, so two configs can't load the same name at once. `OpenGLDevice` overrides
  `CreatePipelines`. With the extension and more than one thread, it queues every compile and link first and checks
  them after. Program binaries go to its cache in request order. Without the extension, it builds in order as today.
- **Threads.** `[GPU] ShaderCompileThreads` (0 = Automatic, the default; 1–16) is resolved in
  `GPUDevice::GetCompileThreads(setting)`:
  - an explicit number stands;
  - Automatic gives 1 when the fixed list or the guard names this renderer and driver;
  - otherwise Automatic follows `ShaderCompileThreads::GetAutomatic(cores, memory)`.

  The pure parts live in `src/util/shader_compile_threads.h/.cpp`: the rule, the fixed list's matcher, and the guard.
  The guard keeps a `shader_compile_pending` marker and a `shader_compile_blocked` record in the cache folder.
- **Caches saved as they grow.** `GPUDevice::SavePipelineCacheIfGrown()` runs after each pass. It compares only the
  serialised size with the last write (cheap), writes atomically when the size has grown, and remembers the new size
  and hash. The full hash check at shutdown is unchanged.
- **Post-processing.** `Chain::CheckTargets` recompiles a stage only when the format changed, or when the size
  changed and `Shader::CompileDependsOnSize()` (true only for ReShade FX). Otherwise it calls only `ResizeOutput`. FX
  builds its passes through `GPUPipelineBatch`.

**Tech Stack:** C++20 (`src/common`, `src/util`, `src/core`), gtest (`src/common-tests`; the opt-in real-GPU tests with
`GOOSESTATION_GPU_TEST_API`), ThreadSanitizer (Mac clang), Qt 6, Dear ImGui (Big Picture), Kotlin/Compose (Android),
`goosestation-regtest` (the Mac and `pcsx2-win`), an instrumented `.debug` test on the Thor (scratch, never committed).

**Spec:** `docs/superpowers/specs/2026-11-26-parallel-pipeline-compilation-design.md`.

## Decisions (made with the user on 2026-11-26: 1 A, 2 A, 3 A, 4 A, 5 A, native execution)

1. **The shape of the pass.**
   - **A. Describe, then build in two phases: all shaders, then all pipelines (recommended).** The batch above.
     - *Pros:* duplicate removal is one generic rule (equal keys), so nothing can be drawn differently by a missed
       case. The pass's loops barely change. The batch is testable without a GPU. The same batch serves FX.
     - *Cons:* the pipeline phase waits for the last shader. Every shader stays alive until the pipelines exist (a few
       MB at most).
   - **B. Futures from today's calls.** `CreateShader` and `CreatePipeline` return at once, and a pipeline starts as
     soon as its shaders are done.
     - *Pros:* no barrier between the phases.
     - *Cons:* a dependency scheduler in `GPUDevice`, and every caller changes. Duplicates still need a key, and OpenGL
       can't take part in it.
2. **Shaderc on the workers** (the design: "run once per worker, builder's call how").
   - **A. One compiler shared by the workers (recommended).** `shaderc.h` says that `shaderc_compile_into_spv` "may be
     safely called from multiple threads", and spirv-cross already makes a context per call.
     - *Pros:* no extra memory, nothing to set up per pass.
     - *Cons:* it relies on shaderc's documented guarantee (TSan checks it, Task 4).
   - **B. One compiler per worker (`thread_local`).**
     - *Pros:* nothing shared.
     - *Cons:* glslang's tables are created again for every worker and every pass.
3. **Shader-cache safety.**
   - **A. The caller reads every entry first and writes the new ones afterwards, in request order (recommended).**
     - *Pros:* `GPUShaderCache` stays single-threaded. The file is byte-identical whatever the timing, which the
       design asks for.
     - *Cons:* reading every cache entry up front costs a little (they are small reads).
   - **B. A mutex inside `GPUShaderCache`.**
     - *Pros:* fewer lines.
     - *Cons:* entries land in completion order, so two cold runs give different files. Lookups serialise on the lock.
4. **OpenGL's program-binary index written after each pass too.** Today the index and footer are written only at
   shutdown, so a run Android kills loses every GL binary. The design names only Vulkan and D3D12.
   - **A. Yes (recommended):** one `WritePipelineCacheIndex()` after a pass that appended, the same rule as Vulkan.
   - **B. No:** stay with the design's scope, and leave it as a follow-up.
5. **Where the Android row lives.**
   - **A. Advanced, in a new "Graphics" section (recommended).** The setting is base-only (the device, not the game).
     `GraphicsSettings.all` also feeds the per-game page.
   - **B. Graphics → Rendering**, and excluded from the game page.

## Found before planning (2026-11-26)

- **What isn't safe across threads today:**
  - `GPUShaderCache` (one `FILE*` with seek and read, an unordered map, `Clear`/`Close` mid-use);
  - the lazy `dyn_libs::OpenShaderc`/`OpenSpirvCross`;
  - Vulkan's `m_render_pass_cache` (used without dynamic rendering, and also by the GPU thread's framebuffers);
  - D3D11's four state maps (`d3d11_pipeline.cpp:142-329`);
  - Metal's `m_depth_states` (`metal_device.mm:695-724`);
  - D3D12's same-name `LoadGraphicsPipeline`, which the docs forbid from two threads. `GetPipelineName` leaves out
    `render_pass_flags`.
  - Destruction: Vulkan's, D3D12's and Metal's pipelines (Metal's shaders too) go through unsynchronised deferred
    queues, and `D3D11Pipeline::~` unbinds GPU-thread state. Every object is therefore destroyed on the caller.
- **Safe already:** `vkCreateGraphicsPipelines` with the shared cache (created without the externally-synchronised
  flag), `vkCreateShaderModule`, `D3DCompile`, the D3D11 and D3D12 device creates (D3D11 isn't single-threaded),
  `MTLDevice`'s `new…` calls (each path already has its own `@autoreleasepool`), per-call spirv-cross contexts, and
  the read-only pipeline layouts and root signatures.
- **OpenGL:** `CreateShaderFromSource` makes no GL calls (it compiles at the first link). `CompileProgram` and
  `CreateProgramFromPipelineCache` read the status straight after `glCompileShader`, `glLinkProgram` and
  `glProgramBinary`, and `AddToPipelineCache` calls `glGetProgramBinary` straight after the link: these are the
  points to defer. The program cache already shares a program between pipelines with the same sources and layout, so
  duplicate removal gains GL little.
- **Post-processing:** GLSL's pipeline depends only on the format. Slang already ignores size changes. FX bakes
  `BUFFER_WIDTH`/`HEIGHT` into its source, and makes 2 shaders and 1 pipeline per pass.
- **Unrelated, left alone:**
  - `OpenSpirvCross`'s failure path calls `CloseShaderc` (`gpu_device.cpp:~1421`);
  - Metal's extra `retain` on libraries and functions (`metal_device.mm:637`);
  - `desc.colorAttachments[0]` in a loop (`metal_device.mm:801`).

  These are listed in the PR as follow-ups.
- **The design's open item.** With the heavier settings on OpenGL ES, a mid-game change logged no pass. Task 11 checks
  this, from the log of the same scenario.

## Global Constraints

- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7` (its parallel compilation came later). Its commit titles
  and user docs only. PCSX2 (the user's fork) for ideas only, none of its code.
- **Anti-goals:** a crash or hang from a driver that can't take several threads; Android killing the app for memory
  during a pass; a broken cache, or one rewritten on every run; anything drawn differently from today.
- **Non-goals:** starting the game before every pipeline exists, building on first use, changing what shaders compute,
  precompiling unseen games or settings.
- **The game waits for every pipeline.** `CompilePipelines` returns only once all are built.
- **One stage, one PR.** `docs/roadmap.md` item 23 is updated in it.
- **Thor:** adb `64dc3c35`, the `.debug` app and `/sdcard/GooseTest` only. Never the release app (plain ID, never
  uninstalled) or the user's data folder. Settings with semicolons passed to `am instrument` are quoted for the
  device's shell. Clean up afterwards, including the design's scratch data. The measurement test stays out of the repo.
- **Android tiers** run from a detached worktree with the emusync AAR copied in: quick while building, full before the
  PR. `run-native-tests.sh` needs `ANDROID_SERIAL=emulator-5580` and `ANDROID_NDK_HOME`. Before pushing, run CI's line:
  `assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`.
- **Windows:** `pcsx2-win`, a scratch worktree, regtest (`-dumpdir` must exist; junction the resources).
- **Commits:** one per task, the message shown after each, ending
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Fixed values (the builder's calls; retuned only by measurement, Task 11)

| Name | Value | Why |
|---|---|---|
| `ShaderCompileThreads::MAX_THREADS` | 16 | The setting's top; more gains nothing for ~400 jobs. |
| `ShaderCompileThreads::MAX_AUTOMATIC` | 8 | Automatic's ceiling before the Thor sweep. |
| `ShaderCompileThreads::BYTES_PER_THREAD` | 1 GiB | Automatic gives at most one thread per GiB of RAM, keeping peak memory well under the 150 MB budget on small devices. |
| Automatic rule | `clamp(min(cores, memory / BYTES_PER_THREAD, MAX_AUTOMATIC), 1, MAX_AUTOMATIC)` | Placeholder shape; Task 11 sets the numbers from a 1/2/4/6/8 sweep on the Thor (time and PSS). |
| Worker stack | 4 MiB | glslang and drivers' compilers recurse deeply; secondary threads default to 512 KiB on macOS. |
| Fixed list | empty until something misbehaves | Entries come only from what Tasks 11–12 see, each with the renderer, vendor, driver version and what happened. |

## Interfaces

```cpp
// src/common/job_runner.h
namespace Threading {
// Runs job(i) for i in [0, count) on (threads - 1) workers plus the caller; stops issuing jobs once one returns false.
// on_progress(done) runs on the caller only. Returns false if any job failed. threads == 1: in order, on the caller.
bool RunJobs(u32 count, u32 threads, const std::function<bool(u32)>& job,
             const std::function<void(u32)>& on_progress = {});
}
// src/util/shader_compile_threads.h
namespace ShaderCompileThreads {
u32 GetAutomatic(u32 cores, u64 memory_bytes);
u64 GetPhysicalMemory();                                    // sysctl / sysconf / GlobalMemoryStatusEx
std::optional<std::string> FindKnownBad(RenderAPI api, std::string_view driver_info,
                                        std::span<const KnownBadDriver> list = KNOWN_BAD);
class Guard { public:
  Guard(std::string cache_dir, std::string driver_key, std::string driver_label);
  std::optional<std::string> GetBlockReason() const;        // a past pass on this driver never finished
  void BeginPass(); void EndPass();                         // writes / deletes the marker
  void ClearBlock();                                        // the player chose a number
  static std::optional<std::string> ReadNote(std::string_view cache_dir); // for the settings rows
};
}
// src/util/gpu_device.h
struct GPUDevice::ShaderRequest { GPUShaderStage stage; GPUShaderLanguage language; std::string_view source;
                                  const char* entry_point = "main"; };
bool GPUDevice::CreateShaders(std::span<const ShaderRequest> in, std::span<std::unique_ptr<GPUShader>> out,
                              u32 threads, const std::function<void(u32)>& on_progress, Error* error);
virtual bool GPUDevice::CreatePipelines(std::span<const GPUPipeline::GraphicsConfig> in,
                                        std::span<std::unique_ptr<GPUPipeline>> out, u32 threads,
                                        const std::function<void(u32)>& on_progress, Error* error);
virtual bool GPUDevice::SupportsParallelCompile() const;    // false: the pool isn't used (OpenGL decides itself)
u32 GPUDevice::GetCompileThreads(u8 setting);               // resolves Automatic; logs the reason once
const std::string& GPUDevice::GetCompileThreadsNote();      // "" or why Automatic is 1, for the rows
void GPUDevice::SavePipelineCacheIfGrown();
// src/util/gpu_pipeline_batch.h
class GPUPipelineBatch { public:
  using Handle = u32; static constexpr Handle NONE = ~0u;
  Handle AddShader(GPUShaderStage, GPUShaderLanguage, std::string source, const char* entry = "main");
  void AddPipeline(const GPUPipeline::GraphicsConfig& cfg, Handle vs, Handle fs, Handle gs, GPUPipeline** slot);
  u32 GetUniqueShaderCount() const; u32 GetUniquePipelineCount() const; u32 GetSlotCount() const;
  bool Build(GPUDevice& device, u32 threads, const std::function<void(u32 done, u32 total)>& progress, Error* error);
  std::vector<std::unique_ptr<GPUPipeline>> TakePipelines();  // owners; slots point into them
};
// src/util/postprocessing_shader.h
virtual bool PostProcessing::Shader::CompileDependsOnSize() const;  // false; FX: true
```

Settings: `[GPU] ShaderCompileThreads` (u8, default 0 = Automatic, 1–16), base-only (saved inside `!ignore_base`),
read live from `g_gpu_settings` at each pass (no device restart). Log line after every pass:
`Built {} shaders and {} pipelines ({} duplicate shaders, {} duplicate pipelines skipped) in {:.1f} ms on {} threads`.

## Review Focus

1. **A driver that crashes or hangs only with several threads.** The next start must use 1 under Automatic and say why
   in the log and the row. A driver update, or the player choosing a number, lifts the block. Pinned:
   `ShaderCompileGuard.AnUnfinishedPassBlocksTheSameDriver`, `ADriverChangeLiftsTheBlock` and
   `AChosenNumberClearsTheBlock` (Task 3).
2. **A pass that fails part-way** (a bad shader, a driver error): the same error as today (the first failing request in
   order, not whichever worker lost), every object already made destroyed on the GPU thread, and no worker left
   running. Pinned: `JobRunner.StopsIssuingAfterAFailureAndReportsIt` (Task 2) and
   `ShaderBatch.TheFirstFailureInOrderIsReported` (Task 4).
3. **A cache whose binary the driver now rejects** (driver update). As today, the cache is cleared and the shaders
   rebuilt from source, with nothing half-written. Pinned: `ShaderBatch.ARejectedBinaryClearsTheCacheAndRebuilds`
   (Task 4).
4. **A warm run must not rewrite anything:** the same shader cache bytes, "Skipping updating pipeline cache", and no
   save after a pass. Pinned: `PipelineBatchGPU.AWarmRunRewritesNothing` (Task 7), on each API the Mac and Windows
   have.
5. **A window resize with a GLSL or Slang chain**, and an FX resize, present the same frame after as before. GLSL and
   Slang build nothing on a resize, and FX builds only at the new size. Pinned: `PostProcessingGPU.AResizeRebuildsOnlyFX`
   (Task 9).

---

### Task 1: Before-numbers and the comma-operator fix

**Files:** Modify: `src/core/gpu_hw.cpp:1638`. Scratch: `/tmp/goose-pipe` (master plus the design's timers and
`PipeMeasureTest`), notes `parallel-pipelines-baseline.md` in the shared notes folder.

TDD skipped for the fix: a misplaced parenthesis on a path that needs a failing driver. The diff shows it.

- [ ] **Step 1: Desktop baseline.** Build `/tmp/goose-pipe` regtest on the Mac and in a scratch worktree on
  `pcsx2-win`. Vagrant Story, 600 frames, the design's two setting sets (defaults; texture cache + PGXP depth), each
  with an empty cache folder and again warm, 3×. Renderers: Mac Metal, Vulkan (MoltenVK) and OpenGL; Windows D3D11,
  D3D12, Vulkan and OpenGL. Record `PIPEMEASURE` and the frame dumps. The dumps are the reference for "drawn the same".
- [ ] **Step 2: Fix** `= CreatePipeline(plconfig), error)` to `= CreatePipeline(plconfig, error))`.
- [ ] **Step 3: Build** the Mac regtest. Vagrant Story's 600 frames match Step 1's dumps.
- [ ] **Step 4: Commit** (`GPU: report a failure to create the VRAM copy pipeline`).

### Task 2: The job runner

**Files:** Create: `src/common/job_runner.h/.cpp`, `src/common-tests/job_runner_tests.cpp`. Modify: the CMake and
vcxproj lists of common and common-tests.

**Interfaces:** produces `Threading::RunJobs`.

- [ ] **Step 1: Write the failing tests.**
  - `JobRunner.EveryJobRunsOnce`: 1,000 jobs and threads 1, 2 and 8. Each index is hit exactly once (an atomic
    counter per index).
  - `JobRunner.OneThreadRunsInOrderOnTheCaller`: the recorded indices are 0..n-1, all on the caller's thread id.
  - `JobRunner.ProgressOnlyOnTheCaller`: every `on_progress` call is on the caller. The values rise, and the last one
    is `count`.
  - `JobRunner.StopsIssuingAfterAFailureAndReportsIt`: job 10 of 1,000 returns false (threads 4). `RunJobs` returns
    false, fewer than 1,000 jobs started, and every started job finished before the return.
  - `JobRunner.ZeroJobs`: returns true and calls nothing.
- [ ] **Step 2: Run** `build/bin/common-tests --gtest_filter=JobRunner.*`. Expected: fails to compile.
- [ ] **Step 3: Implement.** An atomic next index and an atomic failed flag. Workers are `Threading::Thread`s with a
  4 MiB stack, started per call. The caller takes jobs too, and calls `on_progress` after each of its own jobs and
  once more after the join. The join happens before the return.
- [ ] **Step 4: Run** the tests, then again in a ThreadSanitizer build of common-tests
  (`-DCMAKE_CXX_FLAGS=-fsanitize=thread`, a separate build dir `build-tsan`). Expected: PASS, no TSan report.
- [ ] **Step 5: Commit** (`Common: a job runner that spreads work over worker threads and the caller`).

### Task 3: The thread count: setting, Automatic rule, fixed list and guard

**Files:** Create: `src/util/shader_compile_threads.h/.cpp`, `src/common-tests/shader_compile_threads_tests.cpp`.
Modify: `src/core/settings.h/.cpp` (beside `gpu_disable_shader_cache`; Save inside `!ignore_base`),
`src/util/gpu_device.h/.cpp` (`GetCompileThreads`, `GetCompileThreadsNote`; the guard is made in `Create` from the
cache path and `RenderAPIToString(api) + GetDriverInfo()`, plus the macOS version for Metal), CMake/vcxproj lists.

**Interfaces:** produces the `ShaderCompileThreads` namespace, `g_settings.gpu_shader_compile_threads`, and
`GPUDevice::GetCompileThreads`/`GetCompileThreadsNote`.

- [ ] **Step 1: Write the failing tests.**
  - `ShaderCompileThreads.AutomaticRule`: (8 cores, 12 GiB) gives 8. (8, 3 GiB) gives 3. (2, 16 GiB) gives 2.
    (1, 0) gives 1. (64 cores, 64 GiB) gives `MAX_AUTOMATIC`.
  - `ShaderCompileThreads.KnownBadMatchesApiAndDriverText`: an injected entry
    `{RenderAPI::Vulkan, "Adreno", "512.7", "test"}` matches Vulkan text containing both strings. It doesn't match
    OpenGL, or Vulkan with another version.
  - `ShaderCompileGuard.AnUnfinishedPassBlocksTheSameDriver`: `BeginPass` without `EndPass`, then a new `Guard` on the
    same folder and key, has a block reason naming the driver label.
  - `ShaderCompileGuard.AFinishedPassLeavesNoBlock`.
  - `ShaderCompileGuard.ADriverChangeLiftsTheBlock`: another key gives no reason, and the stale record is removed.
  - `ShaderCompileGuard.AChosenNumberClearsTheBlock`: after `ClearBlock`, a new `Guard` has no reason, and `ReadNote`
    is empty.
  - `ShaderCompileGuard.NoCacheFolderMeansNoGuard`: an empty folder is never blocked and writes nothing.
  - `GPUSettings.ShaderCompileThreadsRoundTrips`: the default is 0. 4 saves and loads. 99 loads as `MAX_THREADS`.
- [ ] **Step 2: Run** them. Expected: fails to compile.
- [ ] **Step 3: Implement.**
  - The marker holds the key. A marker found at construction turns into the blocked record (key + label), then is
    deleted.
  - `GetCompileThreads(setting)`:
    - a setting of 1 or more: `ClearBlock()`, then `min(setting, MAX_THREADS)`;
    - 0: 1 if the guard or the fixed list gives a reason (logged once, kept for the note), else `GetAutomatic`.
  - `GetPhysicalMemory` per platform.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** (`GPU: a shader compile threads setting, with Automatic, a list of drivers and a guard`).

### Task 4: Shaders built in parallel, the cache written in order

**Files:** Modify: `src/util/gpu_device.h/.cpp` (`CreateShaders`; a mutex in `dyn_libs::OpenShaderc`/
`OpenSpirvCross`), `src/util/gpu_shader_cache.h/.cpp` only if a helper is needed. Test:
`src/common-tests/gpu_shader_batch_tests.cpp`. For the logic without a device, `CreateShaders` delegates to
`GPUShaderBatch::Run(GPUShaderCache&, requests, out, threads, from_binary, from_source, progress, error)` in
`src/util/gpu_shader_batch.h/.cpp`, with the two creators as `std::function`s.

**Interfaces:** consumes `Threading::RunJobs`. Produces `GPUDevice::CreateShaders`.

- [ ] **Step 1: Write the failing tests** (a real `GPUShaderCache` in a temp folder, fake creators that sleep a random
  0–2 ms and return a fake shader plus a binary of the source's bytes):
  - `ShaderBatch.TheCacheFileIsTheSameWhateverTheTiming`: 200 requests at threads 1, then (fresh folder) threads 8
    three times. The `.idx`/`.bin` files are byte-identical each time.
  - `ShaderBatch.AWarmRunUsesBinariesAndWritesNothing`: the second run calls only `from_binary`, and the files' bytes
    and sizes are unchanged.
  - `ShaderBatch.TheFirstFailureInOrderIsReported`: requests 30 and 60 fail (threads 8). The error is 30's message,
    and every shader made is in `out` for the caller to destroy (none destroyed on a worker: the fake's destructor
    records its thread).
  - `ShaderBatch.ARejectedBinaryClearsTheCacheAndRebuilds`: on a warm cache, `from_binary` fails for one request. The
    cache is cleared, that shader is built from source, and at the end the cache holds the new binaries in request
    order.
- [ ] **Step 2: Run** them. Expected: fails to compile.
- [ ] **Step 3: Implement.** Phase 1 on the caller: `GetCacheKey` + `Lookup` for every request. Phase 2: `RunJobs`.
  Phase 3 on the caller: `Clear` if any binary was rejected, then `Insert` the new binaries in request order. Without
  an open cache, it skips phases 1 and 3.
- [ ] **Step 4: GPU test** `ShaderBatchGPU.ParallelShadersMatchSerial` (opt-in, in the existing GPU-test style):
  `GPU_HW_ShaderGen`'s batch fragment shaders for the defaults, built at threads 1 and 8, give equal cached binaries.
  Run it with `GOOSESTATION_GPU_TEST_API=Vulkan` and `Metal` in the TSan build. Expected: PASS, no TSan report.
- [ ] **Step 5: Commit** (`GPU: build shaders on several threads, with the shader cache written in order`).

### Task 5: The pipeline batch, duplicates once, and thread-safe backends

**Files:**
- Create: `src/util/gpu_pipeline_batch.h/.cpp`, `src/common-tests/gpu_pipeline_batch_tests.cpp`,
  `src/common-tests/gpu_pipeline_batch_gpu_tests.cpp`.
- Modify:
  - `gpu_device.h/.cpp` (`CreatePipelines` default; `SupportsParallelCompile`);
  - `vulkan_device.h/.cpp` (a mutex in `GetRenderPass`/`CreateCachedRenderPass`);
  - `d3d11_pipeline.cpp` / `d3d11_device.h` (one mutex over the four state maps);
  - `metal_device.mm/.h` (a mutex over `m_depth_states`);
  - `d3d12_pipeline.cpp:80-108` (`render_pass_flags` hashed into the name only when non-zero, so existing names stay);
  - the GPU-test fixture's API list gains D3D11 and D3D12 on Windows.

**Interfaces:** consumes `CreateShaders` and `RunJobs`. Produces `GPUPipelineBatch` and `GPUDevice::CreatePipelines`.

- [ ] **Step 1: Write the failing tests** (no device; `AddShader`/`AddPipeline` and the counts):
  - `PipelineBatch.SameSourceIsOneShader`: the same stage and source twice gives one handle. A different stage, entry
    point or one byte of source gives a new one.
  - `PipelineBatch.EqualConfigsAreOnePipeline`: two equal configs with the same handles give one unique pipeline and
    two slots.
  - `PipelineBatch.AnyFieldMakesItDifferent`, parameterised over each field: layout, primitive, every vertex
    attribute and the stride, rasterization, depth, blend (incl. the constant), each shader handle, each colour
    format, the depth format, samples, per-sample shading, render-pass flags. Changing that field alone gives a
    second pipeline.
  - `PipelineBatch.ShaderPointersInTheConfigAreIgnored`: only handles count.
- [ ] **Step 2: Run** them. Expected: fails to compile.
- [ ] **Step 3: Implement the batch.**
  - The key is a byte string of every field, with shader handles in place of pointers and the attributes copied out.
  - `Build`:
    - `CreateShaders` for the unique shaders;
    - fill the configs' pointers, then `CreatePipelines(threads)`;
    - write the slots;
    - the shaders are destroyed on the caller at the end.
  - `CreatePipelines`' default is `RunJobs` over `CreatePipeline`, with threads forced to 1 when
    `!SupportsParallelCompile()`. Vulkan, D3D11, D3D12 and Metal return true.
- [ ] **Step 4: GPU tests.** `PipelineBatchGPU.ParallelPipelinesDrawTheSame`: 64 pipelines (blend and depth variants
  of a coloured-quad shader). Each is drawn into a 16×16 target and read back, for a build at threads 1 and at 8. The
  pixels are equal. Run on Mac Metal and Vulkan (TSan build), and on Windows D3D11 and D3D12 (a normal build).
  Expected: PASS, no TSan report.
- [ ] **Step 5: Commit** (`GPU: a pipeline batch that builds each distinct pipeline once, on several threads`).

### Task 6: The hardware renderer's pass through the batch

**Files:** Modify: `src/core/gpu_hw.h:383-462` (members to `GPUPipeline*` plus `m_pipeline_storage`),
`src/core/gpu_hw.cpp:153-184` (the tracker takes done/total), `:1179-1898` (`CompilePipelines`), `:1900+`
(`DestroyPipelines`), and every `.get()` on those members.

**Interfaces:** consumes `GPUPipelineBatch`, `GetCompileThreads` and the guard (`BeginPass`/`EndPass` when Automatic
gave more than 1).

- [ ] **Step 1:** Replace each `CreateShader` with `AddShader` (the local shader arrays hold handles) and each
  `CreatePipeline` with `AddPipeline(..., &slot)`. Move the `GL_OBJECT_NAME`s after `Build`. Samplers stay as they
  are. Then `Build` with `GetCompileThreads(g_gpu_settings.gpu_shader_compile_threads)`, take the pipelines into
  `m_pipeline_storage`, and log the counts line. TDD: the batch's own tests cover the logic. Here the check is that
  every frame is identical (Step 2).
- [ ] **Step 2: Mac regtest**, Metal and Vulkan, threads 1 and Automatic, both setting sets: every frame identical to
  Task 1's dumps. Record the counts line (unique and duplicate numbers per setting set).
- [ ] **Step 3: Commit** (`GPU: build the hardware renderer's pipelines once each, on several threads`).

### Task 7: Pipeline caches saved as they grow

**Files:** Modify: `src/util/gpu_device.h/.cpp:459-488` (factor the write out of `CloseShaderCache`;
`SavePipelineCacheIfGrown`), `vulkan_device.cpp:2315` / `d3d12_device.cpp:320` (a size-only query:
`virtual std::optional<size_t> GetPipelineCacheSize()`), `src/core/gpu_hw.cpp` (call it after `Build`), and,
if Decision 4 is A, `opengl_pipeline.cpp:995-1046` (`WritePipelineCacheIndex()`, shared with `ClosePipelineCache`).
Test: `gpu_pipeline_batch_gpu_tests.cpp`.

- [ ] **Step 1: Write the failing GPU tests** (a device with a temp cache folder):
  - `PipelineBatchGPU.ACacheIsOnDiskBeforeShutdown`: build 64 pipelines and call `SavePipelineCacheIfGrown`. The
    `<api>_pipelines.bin` exists and decompresses. Then skip `Destroy` (the device is leaked on purpose, as a killed
    run is), and a new device on the same folder logs a warm read.
  - `PipelineBatchGPU.AWarmRunRewritesNothing`: run 1 is cold, run 2 builds the same batch. Both cache files keep their
    bytes and modification times, and run 2's shutdown logs "Skipping updating pipeline cache".
- [ ] **Step 2: Run** on Mac Vulkan. Expected: the first fails (no file before shutdown).
- [ ] **Step 3: Implement.** After a write, remember the size and the hash. The write is atomic
  (`CompressToFile(..., true)`). If the write measures above 50 ms on the Thor in Task 11, it moves to a background
  thread, joined before the next save and at shutdown.
- [ ] **Step 4: Run** on Mac Vulkan and Metal (Metal has no driver cache: no file, no write), and on Windows D3D12
  (and GL if Decision 4 is A). Expected: PASS.
- [ ] **Step 5: Commit** (`GPU: save the Vulkan and D3D12 pipeline caches after each pass that added to them`).

### Task 8: OpenGL through the parallel-compile extension

**Files:** Modify: `src/util/opengl_device.h/.cpp:347-523` (detect `GLAD_GL_KHR_parallel_shader_compile` or `_ARB_`,
then log it), `src/util/opengl_pipeline.h/.cpp` (split `OpenGLShader::Compile` into queue and finish; split
`CompileProgram` and `CreateProgramFromPipelineCache` into issue and resolve; `CreatePipelines` override). Test:
`gpu_pipeline_batch_gpu_tests.cpp` (OpenGL).

- [ ] **Step 1: Write the failing GPU test** `PipelineBatchGPU.OpenGLBatchMatchesSerial`: the Task 5 draw test at
  threads 1 and 8 on OpenGL. The pixels are equal, and the program-binary file has the same entries in the same
  order. On the Mac (no extension) this pins the serial fallback. On Windows GL it pins the queued path.
- [ ] **Step 2: Run** it on Windows GL. Expected: fails until the override exists (threads 8 must take the queued
  path, which the test checks through the "parallel compile" log line).
- [ ] **Step 3: Implement** `OpenGLDevice::CreatePipelines`.
  - With the extension and threads above 1, `glMaxShaderCompilerThreadsKHR(threads)`. Then, in request order, one
    pending entry per distinct program key:
    - a disk entry: `glProgramBinary`;
    - otherwise: queue the shaders' compiles, then create, attach, bind and `glLinkProgram`, without querying.
  - Then resolve in request order:
    - check the status and log the failure as today;
    - a rejected binary goes to the serial path after `DiscardPipelineCache`, as today;
    - `PostLinkProgram`, `AddToPipelineCache`, then emplace;
    - create the VAOs and pipelines.
  - Without the extension, or with threads at 1, it is today's loop.
- [ ] **Step 4: Run** on Windows GL and Mac GL. Expected: PASS. Then the Mac regtest on OpenGL: frames identical to
  Task 1.
- [ ] **Step 5: Commit** (`OpenGL: queue compiles and links when the driver compiles in parallel`).

### Task 9: Post-processing builds less on a resize

**Files:** Modify: `src/util/postprocessing.cpp:656-707`, `postprocessing_shader.h`, `postprocessing_shader_fx.cpp:
1304-1469` (its passes through `GPUPipelineBatch`; `CompileDependsOnSize` true), and the GLSL/Slang shaders
(nothing to override). Test: `src/common-tests/postprocessing_gpu_tests.cpp` (opt-in), with a counter
`GPUDevice::GetStatistics().num_pipelines_created` (atomic, added in `CreatePipeline`'s callers).

- [ ] **Step 1: Write the failing GPU test** `PostProcessingGPU.AResizeRebuildsOnlyFX`. A chain of one GLSL stage and
  one small FX stage (written in the test, with two passes and one size-dependent macro use) is set up at 64×48 and
  then resized to 96×72:
  - the GLSL stage's pipeline count is unchanged;
  - the FX stage's count rises by exactly its pass count;
  - a format change rebuilds both;
  - the frame after the resize equals a chain built fresh at 96×72.
- [ ] **Step 2: Run** on Mac Metal. Expected: FAIL (GLSL rebuilds today).
- [ ] **Step 3: Implement.**
  - `CheckTargets` keeps the old format and size, and per stage calls `CompilePipeline` only when
    `format != old || (size != old && CompileDependsOnSize())`, then `ResizeOutput`.
  - FX gathers its `get_shader` and `CreatePipeline` calls into one batch, built with `GetCompileThreads`. Duplicate
    VS entry points are built once.
- [ ] **Step 4: Run** on Mac Metal, Vulkan and OpenGL. Expected: PASS.
- [ ] **Step 5: Commit** (`Post-processing: rebuild only what a resize changes, and build FX passes in parallel`).

### Task 10: The rows on Qt, Big Picture and Android

**Files:**
- Qt: `src/duckstation-qt/graphicssettingswidget.ui` (Advanced tab, Display Options grid), its `.cpp` (bind with
  `BindWidgetToIntSetting(sif, m_ui.shaderCompileThreads, "GPU", "ShaderCompileThreads", 0)`; help text).
- Big Picture: `src/core/fullscreen_ui.cpp` (Display page's Advanced heading; `DrawIntListSetting`).
- Android:
  - `settings/AdvancedSettings.kt` (`IntSetting("GPU", "ShaderCompileThreads", 0)` in `all`, not in `GAME`);
  - `ui/AdvancedScreen.kt` (a "Graphics" section; a `NumberScale.Range(0, 16)` with `0 -> "Automatic"`);
  - `app/PageValues.kt` plus a JNI `getShaderCompileThreadsNote()` in `jni_settings.cpp`.
- Tests: `SettingsAccessTest` (a new `advancedDefaultsMatchTheCore`, if Advanced has none yet), a new
  `AdvancedSettingsTest.kt` (key and default), and an `AdvancedScreenTest` case for the note.

**Interfaces:** consumes `ShaderCompileThreads::ReadNote(EmuFolders::Cache)`, `GetAutomatic`, `GetPhysicalMemory`.

The row reads "Shader compile threads". The choices are "Automatic (N)", where N is this device's rule, then "1 (one
at a time, as before)", "2" … "16". The note is "Automatic uses 1 on <driver>: a build with several threads didn't
finish last time" when blocked, and otherwise "How many threads build shaders and pipelines when a game starts or a
graphics setting changes".

- [ ] **Step 1: Write the failing Android tests:**
  - `AdvancedSettingsTest.shaderCompileThreadsIsAutomaticByDefault`;
  - `AdvancedScreenTest.aBlockedDriverIsExplainedInTheRow` (a fake note);
  - `SettingsAccessTest.advancedDefaultsMatchTheCore` (the key is written by the core, with the same default).
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest` and the single androidTest classes. Expected: FAIL.
- [ ] **Step 3: Implement** the three UIs.
- [ ] **Step 4: Run** the tests, then build Qt and look at the row (screenshot), and Big Picture in the regtest-free
  Qt build. Expected: PASS, and the row is shown on each.
- [ ] **Step 5: Commit** (`Settings: the shader compile threads row on Qt, Big Picture and Android`).

### Task 11: The Thor: sweep, tuning, the after-numbers

**Files:** scratch only (`PipeMeasureTest` from `/tmp/goose-pipe`, extended). Commits only if the constants or the
fixed list change.

- [ ] **Step 1:** Extend `PipeMeasureTest`:
  - read the counts line;
  - sample `Debug.getMemoryInfo` total PSS every 50 ms during the pass, and record the peak;
  - add a run that kills the process right after the pass (`Process.killProcess` from a second instrumentation), then
    starts warm.
- [ ] **Step 2: Sweep** threads 1, 2, 4, 6 and 8 on Vulkan and OpenGL ES, both setting sets, cold, 3× interleaved
  with 20 s cool-downs. Set `MAX_AUTOMATIC`/`BYTES_PER_THREAD` at the knee, inside the 150 MB PSS budget. If OpenGL ES
  gains nothing, say so, and keep the extension path (it costs nothing).
- [ ] **Step 3: After-numbers**, interleaved with the design's before build: the four scenarios cold and warm, boot to
  game start, the mid-game change (5×, 4× MSAA), and peak PSS. Check the killed run's next start is warm.
- [ ] **Step 4:** If Task 7's save exceeds 50 ms, move it to a background thread (with its own commit). Any driver
  that misbehaves goes into the fixed list (with its own commit, and the evidence in the message).
- [ ] **Step 5:** Check the design's open OpenGL ES item and write it up in the notes.

### Task 12: Verification

- [ ] **Step 1:** common-tests on the Mac, normal and TSan (with the GPU tests on Vulkan and Metal), and on Windows
  (GPU tests on D3D11, D3D12, Vulkan and OpenGL).
- [ ] **Step 2: Regtest frames.** Mac (Metal, MoltenVK, OpenGL) and Windows (D3D11, D3D12, Vulkan, OpenGL), threads
  Automatic and 1, both setting sets, plus two more library discs. Every frame is identical to Task 1's master dumps.
  Also record cold and warm `PIPEMEASURE`/counts against Task 1.
- [ ] **Step 3: Android.** The quick tier while building (after Tasks 6, 8 and 10), and the full tier now, from a
  detached worktree. CI's Gradle line passes. `run-native-tests.sh` passes.
- [ ] **Step 4: Clean up the Thor and the Mac.**
  - Thor: uninstall `…debug.test` and `…debug` (only the `.debug` app), then `rm -rf /sdcard/GooseTest`.
  - Mac: remove `/tmp/goose-pipe` with `git worktree remove`, and the scratch worktree on `pcsx2-win`.

### Task 13: Docs and the PR

- [ ] **Step 1:** `docs/roadmap.md` item 23 rewritten as done: what changed, the Thor's before and after, and the
  desktop numbers.
- [ ] **Step 2:** Commit (`Docs: roadmap item 23 done`). Then `superpowers:requesting-code-review` on the branch,
  address the findings, push, and open the PR. The PR carries the before and after tables, the counts per setting set,
  peak PSS, the warm/killed-run checks, what OpenGL got, and the follow-ups found in passing.
