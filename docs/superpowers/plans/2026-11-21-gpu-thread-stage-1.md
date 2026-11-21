# GPU thread, stage 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every hardware renderer's host work (draws, VRAM uploads, fills, copies, post-processing, the OSD, ImGui and
Big Picture drawing, presenting) runs on a GPU thread of its own that owns the device whatever the renderer, at most
one frame behind the emulation thread, behind a "Use a GPU thread" switch that is on by default, on desktop (Qt, Big
Picture) and Android.

**Architecture:** The emulation thread keeps everything the PS1 sees (GP0/GP1 parsing, timing, ticks, PGXP lookups,
GPUSTAT, the software renderer and its readback copy) and hands the rest over as self-contained commands in one
single-producer ring, `GPUThreadQueue`, drained by `GPUThread`. The hardware renderer is split along that line: a
front-end (`GPU_HWFrontend`, a `GPU`) builds commands and counts ticks exactly as today; a back-end (`GPU_HW`, no
longer a `GPU`) batches and draws on the GPU thread. Display, deinterlacing, post-processing, screenshots and
presenting move out of `GPU` into a `GPUPresenter` on the GPU thread; ImGui is still built on the emulation thread
and its draw data is copied into the present command. Anything that needs the GPU's answer (readbacks, states,
screenshots, settings that rebuild the framebuffer, device creation, texture creation for the UI) is a closure run
on the GPU thread and waited for. With the switch off, the same commands run at once on the emulation thread, so
"off" is today's order of work.

**Tech Stack:** C++20 (`src/util`, `src/core`), CMake/Ninja (Mac, Linux, Android NDK), MSBuild (`duckstation.sln`,
Windows), googletest (`src/common-tests`, also run on Android by `android/scripts/run-native-tests.sh`), Qt 6
widgets, Big Picture's ImGui, Kotlin/Compose with JUnit and instrumented tests, `duckstation-regtest` as the headless
frame harness, ThreadSanitizer (Mac clang) for the regtest.

**Spec:** `docs/superpowers/specs/2026-11-20-gpu-thread-and-pacing-design.md` (stage 1; read "What exists" first).

## Global Constraints

- **Clean room:** nothing from DuckStation's code after `a5bf1ba5cee7` (its GPU thread rewrite came after it), only
  its commit titles and user docs. PCSX2's MTGS (https://github.com/ilyalissoboi/pcsx2) for ideas only, no code.
- **Every hardware renderer, desktop and Android:** Vulkan, OpenGL, OpenGL ES, D3D11, D3D12, Metal; Qt and Big
  Picture alike. Stage 2 (pacing) is out of scope: no change to `Throttle`, pacing defaults or present waits.
- **The device belongs to the GPU thread** whatever the renderer, including the software renderer: created,
  recreated (renderer or resolution change, Android's Vulkan-to-GLES fallback), resized, made surfaceless and
  destroyed there. Nothing calls `g_gpu_device` from another thread; what others need comes from
  `GPUThread::GetDeviceInfo()`.
- **At most one frame ahead:** the emulation thread never polls input for frame N+1 while more than one present
  (frame N's) is unfinished; with a blocking vsync mode, not while any is (Task 3's `MaxFramesAhead`).
- **Unchanged results:** readbacks, states, screenshots and every frame drawn are identical with the switch off and
  on, and identical to `master` with it off (regtest frame dumps compared byte for byte).
- **Switch:** `[GPU] UseGPUThread`, default `true` (`Settings::gpu_use_gpu_thread`), label "Use a GPU thread", in the
  advanced part of graphics settings on Qt, Big Picture and Android. Off runs everything on the emulation thread.
  A renderer measured slower with it on ships with it off by default (Task 14), and the PR says which and why.
- **The software renderer's own thread** (`[GPU] UseThread`) and its ring (`GPUBackend`) stay as they are.
- **Vulkan's present thread** (`[GPU] ThreadedPresentation`) is not used while the GPU thread is on; with the GPU
  thread off it behaves as today.
- **Android surface changes** reach the GPU thread directly (Task 3's priority closures), so `SurfaceChanged` never
  waits longer than today.
- **Thor:** adb `64dc3c35`, the `.debug` app and `/sdcard/GooseTest` only; never the release app (plain ID) or the
  user's data folder; cleaned up afterwards (test APK uninstalled, `/sdcard/GooseTest` and the app's cache removed).
  The measurement test stays out of the repository.
- **Windows:** `ssh pcsx2-win` (confirm with the user before first use); Linux by CI.
- **Android checks:** quick tier while building, full tier before the PR, both from a detached worktree
  (`docs/android.md`, emusync AAR copied in); CI's line (`assembleDebug assembleDebugAndroidTest testDebugUnitTest
  ktlintCheck checkNoWindowDialogs`) before pushing.
- Commit each task, message shown after, ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; PR body
  ends `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

## Approaches considered (for the user's review)

1. **Split at the PS1 command level (chosen).** The emulation thread parses GP0 and sends self-contained draw/VRAM
   commands; the renderer runs on the GPU thread. *Pros:* moves all host rendering (the measured ≈60% at 8× GLES:
   `FlushRender`, `DispatchRenderCommand`, the driver, presenting) off the emulation thread; one owner for the device;
   the software renderer already works this way. *Cons:* the largest change (`gpu_hw.cpp` split, display code moved);
   every piece of `GPU` state the renderer reads must travel in commands; risk of subtle differences, guarded by
   byte-for-byte regtest dumps.
2. **Record device calls and replay them on the GPU thread.** `GPU_HW` stays put; a proxy device queues its calls.
   *Pros:* little renderer change. *Cons:* only the driver part moves (batching and vertex building, ≈25%, stay);
   every device API needs a deferred twin, mapped vertex buffers need staging, object creation needs answers now; GL
   context ownership gets harder, not easier. Unlikely to reach +20% fast-forward.
3. **Re-parse GP0 on the GPU thread** (send raw words; a mirror `GPU_HW` parses them again). *Pros:* `gpu_hw.cpp`
   nearly unchanged. *Cons:* two parsers that must never diverge, PGXP needs a side channel anyway, timing work
   duplicated; fragile.

Within approach 1, for ImGui: **(a, chosen)** build the UI on the emulation thread as today and copy its draw data
into the present command; or (b) run Big Picture and the OSD on the GPU thread, which would put most of
`fullscreen_ui.cpp` (9,654 lines that call into `System`) on another thread. For the frames-ahead limit: **(chosen)**
one frame ahead, zero when the vsync mode blocks (so a blocking present can't add a frame of lag); or always one
(simpler, may add up to a frame with FIFO vsync); or always zero (no overlap between presenting and the next frame).

## Threading rules (every task)

- Only the emulation thread pushes commands. The Qt UI thread and Android's JNI thread reach the GPU thread through
  the emulation thread (`Host::RunOnCPUThread`), except Android's surface changes (priority closures).
- Code on the GPU thread reads `g_gpu_settings` (its copy, Task 6), never `g_settings`.
- The GPU thread touches the ImGui context only inside a closure the emulation thread is waiting for
  (`RunOnThreadAndWait`): pipeline compile progress and post-processing load progress (the loading screen) qualify.
- A texture the UI holds is released through `GPUThread::TextureDeleter`, after the frames already queued.
- Shared state the GPU thread reads while running (texture replacement index, post-processing config) changes only
  inside a closure run on the GPU thread.

## Review Focus

1. **Switching apps on Android while a heavy frame is mid-draw** (8×, MSAA, CRT-Lottes): `SurfaceChanged(null)`
   returns only once the device has let go of the old window, and the game draws again on return. Pinned in Task 13
   (`switchingAppsMidFrameKeepsDrawing`) and Task 3 (`APriorityClosureRunsBeforeQueuedWork`).
2. **A renderer change, or the switch flipped, mid-game with a frame queued:** no crash, the new device made on the
   GPU thread, the picture back. Pinned in Task 13 (`switchingRendererWithTheThreadOnKeepsDrawing`,
   `flippingTheSwitchMidGameKeepsTheFrame`).
3. **GP0 C0h readbacks with a frame queued** (games reading VRAM back, software readbacks off): the CPU gets VRAM as
   drawn up to that command. Pinned in Task 8 (regtest `-dumpvram` every frame, on = off = master).
4. **A save state made with the thread on** while a frame is queued: the same state as with it off, and it loads
   either way. Pinned in Task 13 (`aStateSavedWithTheThreadLoadsWithoutIt`).
5. **Stopping a game or quitting while the GPU thread waits in a blocking present** (FIFO vsync, hidden window):
   no hang. Pinned in Task 3 (`StopWhileTheThreadIsBusyReturns`) and Task 13 (`stoppingWhilePresentingDoesNotHang`).

---
### Task 1: Before numbers

No code. Measured at `master` (`b4d03c344`), kept in the notes folder (`gpu-thread-stage1-notes.md`) for the PR.

- [ ] **Step 1: Mac** (Metal, Vulkan through MoltenVK, OpenGL; `docs`'s regtest recipe in memory
  `regtest-renderer-checks`): `duckstation-regtest -renderer <r> -statefile SLUS-01040_{1,2,resume}.sav -frames 1800
  -dumpinterval 60 -setting GPU/ResolutionScale={1,3,8}` on Vagrant Story. Keep the dumps (the "nothing drawn
  differently" reference) and the wall time per run (the regtest doesn't throttle: wall time is speed).
- [ ] **Step 2: Windows** (`ssh pcsx2-win`, confirmed with the user): the same on D3D11, D3D12, Vulkan, OpenGL.
- [ ] **Step 3: Thor, on its own screen:** a throwaway instrumented test in the `.debug` app (outside the repo, in
  `/tmp/goose-perf`) opens a full-screen `SurfaceView` on the main display, hands its surface to the core, loads a
  state and logs the per-second line (memory `thor-performance-measurement`). Cases: (a) OpenGL ES, 8×, 4× MSAA,
  CRT-Lottes, normal speed, 30 s; (b) unlimited speed at 1× and 3×, Vulkan and OpenGL ES, 30 s each; (c) normal speed
  1×, Vulkan, frame times on the screen from `dumpsys SurfaceFlinger --latency <layer>`. Clean up the Thor after.

### Task 2: The command ring

**Files:**
- Create: `src/util/gpu_thread_queue.h`, `src/util/gpu_thread_queue.cpp`
- Modify: `src/util/CMakeLists.txt`, `src/util/util.vcxproj`, `src/util/util.vcxproj.filters`
- Test: `src/common-tests/gpu_thread_queue_tests.cpp` (+ `src/common-tests/CMakeLists.txt`)

**Interfaces:**
- Produces:
  ```cpp
  struct GPUThreadCommand { u32 size; u8 type; u8 params; u16 reserved; }; // size includes this header
  class GPUThreadQueue
  {
  public:
    static constexpr u8 TYPE_WRAPAROUND = 0;  // internal
    static constexpr u8 FIRST_USER_TYPE = 16; // 1..15 are GPUThread's own (Task 3)
    explicit GPUThreadQueue(u32 capacity);    // a power of two, at least 64 KiB
    void* Allocate(u8 type, u32 size);        // producer: 8-byte aligned, blocks (waking the consumer) while full
    void Push();                              // producer: publishes the last Allocate(), wakes a sleeping consumer
    bool Drain(const std::function<void(const GPUThreadCommand*)>& handler); // consumer: all published, in order;
                                              // false if none
    bool WaitForWork(u64 spin_ns);            // consumer: spins, then sleeps until Push() or Shutdown(); false on
                                              // Shutdown with nothing left
    void Shutdown();
    bool IsEmpty() const;
    u32 GetPendingBytes() const;
  };
  ```
- A command never straddles the end: `Allocate` writes a wraparound marker when the rest won't fit, and never lets the
  write position catch the read position (the old `GPUBackend` ring loses a wrap when the reader sits at 0; this one
  must not).

- [ ] **Step 1: Write the failing tests** (`TEST(GPUThreadQueue, …)`, a consumer `std::thread` where needed):
  `CommandsArriveInOrderWithTheirPayloads` (1,000 commands of 8–4,096 bytes, each carrying its index and a byte
  pattern; all seen once, in order); `ACommandNeverStraddlesTheEnd` (64 KiB ring, sizes that leave 40 bytes before
  the end: every handler pointer + size is within the buffer); `AWrapWithTheReaderAtTheStartIsNotLost` (fill to the
  end with the reader at 0, then drain: the next command after the wrap arrives); `AFullRingWaitsForTheReader`
  (producer allocates 4× the capacity while the consumer drains slowly: every command arrives, the producer was
  seen blocked); `NothingIsLostUnderLoad` (10⁶ random-size commands, checksum equal on both sides);
  `ShutdownWakesASleepingReader` (`WaitForWork` returns false within 100 ms of `Shutdown()`).
- [ ] **Step 2: Run** `build/bin/common-tests --gtest_filter='GPUThreadQueue*'`; expect a build failure.
- [ ] **Step 3: Implement** the ring: read/write positions as `std::atomic<u32>` on their own cache lines,
  acquire/release; the sleeping consumer and a full producer wait on a `std::mutex`/`std::condition_variable` pair
  with a "sleeping" flag so `Push` only locks when someone sleeps.
- [ ] **Step 4: Run** the filter, then all of `common-tests`; expect PASS. Then once under ThreadSanitizer
  (`-DCMAKE_CXX_FLAGS=-fsanitize=thread`, a throwaway build dir): no reports.
- [ ] **Step 5: Commit** ("Util: a single-producer command ring for the GPU thread").

### Task 3: The GPU thread

**Files:**
- Create: `src/util/gpu_thread.h`, `src/util/gpu_thread.cpp`
- Modify: `src/util/gpu_device.h`, `src/util/gpu_device.cpp` (owner-thread checks; `PooledTextureDeleter` routes
  through `GPUThread`), build files as Task 2
- Test: `src/common-tests/gpu_thread_tests.cpp`

**Interfaces:**
- Consumes: Task 2.
- Produces (namespace `GPUThread`):
  ```cpp
  bool Start(bool use_thread);       // use_thread false: inline mode, commands run in PushCommand on the caller
  void Stop();                       // runs what's queued, then joins
  bool IsUsingThread();
  bool IsOnThread();                 // the device's owner: the GPU thread, or (inline) the thread that called Start
  const Threading::Thread* GetThread(); // nullptr inline
  void* AllocateCommand(u8 type, u32 size);
  void PushCommand(void* cmd);
  using CommandHandler = void (*)(const GPUThreadCommand* cmd);
  void SetCommandHandler(CommandHandler handler); // types >= GPUThreadQueue::FIRST_USER_TYPE
  void RunOnThread(std::function<void()> fn);           // in order with commands
  void RunOnThreadAndWait(std::function<void()> fn);    // in order; returns after fn ran
  void RunOnThreadPriority(std::function<void()> fn, bool wait); // any thread; runs at the next command boundary,
                                                                 // ahead of queued work, or while idle
  void Sync(bool spin);                                 // returns once everything pushed before it ran
  // Frames.
  void FramePushed();                // emulation thread, with each present command
  void FramePresented();             // GPU thread, after that present
  u32 GetQueuedFrames();
  void WaitForQueuedFrames(u32 max_queued);
  constexpr u32 MaxFramesAhead(bool vsync_blocking) { return vsync_blocking ? 0 : 1; }
  // What other threads may know about the device.
  struct DeviceInfo
  {
    bool has_device = false, has_surface = false;
    RenderAPI render_api = RenderAPI::None;
    s32 window_width = 0, window_height = 0;
    float window_scale = 1.0f, refresh_rate = 0.0f;
    GPUTexture::Format window_format = GPUTexture::Format::Unknown;
    bool lower_left_origin = false, explicit_present = false, vsync_blocking = false;
    bool exclusive_fullscreen = false, gpu_timing = false;
    u32 max_texture_size = 0, max_multisamples = 1;
  };
  DeviceInfo GetDeviceInfo();        // any thread, a copy under a mutex
  void PublishDeviceInfo();          // GPU thread, from g_gpu_device (or "no device")
  struct TextureDeleter { void operator()(GPUTexture* tex) const; }; // recycles on the GPU thread, after queued work
  using TexturePtr = std::unique_ptr<GPUTexture, TextureDeleter>;
  TexturePtr CreateTexture(u32 width, u32 height, GPUTexture::Format format, const void* data, u32 pitch); // waits
  ```
- `GPUDevice` in debug builds asserts `GPUThread::IsOnThread()` in `CreateTexture`, `FetchTexture`, `BeginPresent`,
  `SetPipeline`, `Draw`, `DrawIndexed`, `MapVertexBuffer` and `RecycleTexture`.

- [ ] **Step 1: Write the failing tests** (`TEST(GPUThread, …)`, a test handler, no device):
  `InlineModeRunsACommandAtOnce`; `ThreadedCommandsRunInOrderOnTheThread` (handler records `std::this_thread::get_id`
  and order); `RunOnThreadAndWaitSeesEarlierCommands`; `SyncReturnsAfterEverythingBefore`;
  `APriorityClosureRunsBeforeQueuedWork` (100 slow commands queued, a priority closure from another thread runs
  before the 100th); `APriorityClosureRunsWhileIdle`; `ASecondFrameWaitsForTheFirst` (`FramePushed` ×2 with the first
  present held by the handler: `WaitForQueuedFrames(1)` blocks until `FramePresented`); `ZeroAheadWaitsForThePresent`
  (`WaitForQueuedFrames(0)` returns only after `FramePresented`); `MaxFramesAheadIsOneUnlessVSyncBlocks`
  (`MaxFramesAhead(false) == 1`, `MaxFramesAhead(true) == 0`); `StopWhileTheThreadIsBusyReturns` (a handler that
  sleeps 200 ms: `Stop()` returns after it, within 1 s); `AForeignTextureDeleteWaitsForQueuedWork` (a fake recycle
  hook: deleting from the test thread runs after the commands pushed before it).
- [ ] **Step 2: Run** `build/bin/common-tests --gtest_filter='GPUThread.*'`; expect a build failure.
- [ ] **Step 3: Implement** on the Task 2 ring: own types `Sync = 1`, `RunClosure = 2` (a heap
  `std::function` pointer in the payload), `RecycleTexture = 3`; the loop drains, checks the priority list (a
  mutex-guarded deque and an atomic count) between commands, spins 1 ms then sleeps. Frames: an atomic counter and a
  condition variable. `TextureDeleter` and `PooledTextureDeleter` run at once when `IsOnThread()`, else push
  `RecycleTexture`.
- [ ] **Step 4: Run** the filter and all of `common-tests` (and under ThreadSanitizer as Task 2); expect PASS.
- [ ] **Step 5: Commit** ("Util: the GPU thread, its closures, frames and device info").

### Task 4: The device's life on the GPU thread

Still inline (`GPUThread::Start(false)` everywhere); the thread itself is turned on in Task 10.

**Files:**
- Modify: `src/core/host.cpp` (`CreateGPUDevice`, `UpdateDisplayWindow`, `ResizeDisplayWindow`,
  `ReleaseGPUDevice`), `src/core/system.cpp` (`CPUThreadInitialize`/`CPUThreadShutdown` start and stop
  `GPUThread`; `CreateGPU`, `RecreateGPU`, `UpdateDisplayVSync`, `UpdateSpeedLimiterState`, `SaveStateToBuffer`,
  `StartMediaCapture` read `GetDeviceInfo()`), `src/core/gte.cpp:246`, `src/core/settings.cpp:1433`,
  `src/util/imgui_manager.cpp` (window size and scale), `src/core/imgui_overlays.cpp:381,412`,
  `src/duckstation-qt/qthost.cpp` (1036, 1849, 2099), `src/duckstation-qt/mainwindow.cpp` (486, 1971),
  `android/app/src/cpp/android_host_game_files.cpp:577`, `android/app/src/cpp/android_host_settings.cpp:304`,
  `src/util/postprocessing_shader_fx.cpp:41,68` (render API from `GetDeviceInfo()` for config-only loads)

**Interfaces:**
- Consumes: Task 3.
- Produces: `Host::CreateGPUDevice`, `Host::ReleaseGPUDevice`, `Host::UpdateDisplayWindow`,
  `Host::ResizeDisplayWindow` keep their signatures, called on the emulation thread; each runs its device part with
  `GPUThread::RunOnThreadAndWait` and then `PublishDeviceInfo()` there, and its ImGui/InputManager part
  (`ImGuiManager::WindowResized`, `InputManager::SetDisplayWindowSize`) back on the calling thread from the
  published info. Android's Vulkan-to-GLES fallback in `CreateGPU` happens inside one wait.

- [ ] **Step 1: TDD skipped** (wiring: no new behaviour, and the device needs a GPU); checked by runs.
- [ ] **Step 2: Move** the device calls as listed; replace every emulation-, Qt- and JNI-side `g_gpu_device->…`
  query in the files above with the matching `GetDeviceInfo()` field, and `g_gpu_device` null checks off the
  device's thread with `GetDeviceInfo().has_device`.
- [ ] **Step 3: Check:** `grep -n "g_gpu_device" src/core src/duckstation-qt android/app/src/cpp` leaves only
  GPU-side files (`gpu*.cpp`, `host.cpp`'s closures, `imgui_overlays.cpp`'s loading screen until Task 5); desktop
  build, `common-tests`, and Task 1's Mac regtest runs (Metal, MoltenVK, OpenGL) give dumps identical to the before
  dumps; the Qt app boots a game, goes fullscreen and back, and closes.
- [ ] **Step 4: Commit** ("Core: the device is made, changed and released through the GPU thread").

### Task 5: ImGui's frames and the UI's textures

**Files:**
- Create: `src/util/imgui_draw_copy.h`, `src/util/imgui_draw_copy.cpp`
- Modify: `src/util/gpu_device.h/.cpp` (`RenderImGui(const ImDrawData* draw_data)`; `UpdateImGuiFontTexture` takes
  the atlas pixels), `src/util/imgui_manager.cpp` (fonts, OSD message and software cursor textures as
  `GPUThread::TexturePtr`), `src/util/imgui_fullscreen.cpp` (`CreateTextureFromImage`, `UploadTexture` and the cache
  through `GPUThread::CreateTexture` / `TextureDeleter`), `src/core/fullscreen_ui.cpp` (statics 261-267, save-state
  previews 569/7239/7249/7267), `src/core/imgui_overlays.cpp` (`SaveStateSelectorUI` 717-958;
  `Host::DisplayLoadingScreen` 87-166), `src/core/achievements.cpp:441`
- Test: `src/common-tests/imgui_draw_copy_tests.cpp`

**Interfaces:**
- Produces:
  ```cpp
  class ImGuiDrawCopy
  {
  public:
    static std::unique_ptr<ImGuiDrawCopy> Capture(const ImDrawData& draw_data); // deep copy of every list
    const ImDrawData* Get() const;
  };
  ```
- `Host::DisplayLoadingScreen` builds its frame as today and then, on the emulation thread, pushes it as a present
  (Task 9's `Present` with no display) and waits; on the GPU thread (pipeline compile and post-processing progress,
  the emulation thread waiting) it renders and presents directly.

- [ ] **Step 1: Write the failing tests** (`TEST(ImGuiDrawCopy, …)`, an ImGui context made in the test, no device):
  `ACopyHasEveryListsVerticesIndicesAndCommands` (two windows with text and an image: counts, `TextureId`s,
  `ClipRect`s and every vertex equal to the source); `ACopyOutlivesTheNextFrame` (`NewFrame`/`Render` again with
  other content: the copy still holds the first frame); `AnEmptyFrameCopiesAsEmpty`.
- [ ] **Step 2: Run** `build/bin/common-tests --gtest_filter='ImGuiDrawCopy*'`; expect a build failure.
- [ ] **Step 3: Implement** `Capture` with `ImDrawList::CloneOutput()` per list, owning the clones; then move the
  UI's texture holders to `GPUThread::TexturePtr` (or `std::shared_ptr` with `TextureDeleter`) and their creation to
  `GPUThread::CreateTexture`; font atlas updates send a copy of the atlas pixels to the GPU thread and wait.
- [ ] **Step 4: Run** the filter and all of `common-tests`; expect PASS. Check by runs: Big Picture opens, shows
  covers, achievements badges and state previews, the loading screen shows while shaders compile; no debug
  owner-thread assertion.
- [ ] **Step 5: Commit** ("Util: ImGui's draw data copied for the GPU thread, UI textures made and freed there").

### Task 6: The presenter and the GPU thread's settings

**Files:**
- Create: `src/core/gpu_presenter.h`, `src/core/gpu_presenter.cpp` (moved from `gpu.cpp`: display texture state,
  `Deinterlace*`, `ApplyChromaSmoothing`, `CompileDisplayPipelines`, `RenderDisplay`, `PresentDisplay`,
  `RenderScreenshotToBuffer`, `RenderScreenshotToFile`, `WriteDisplayTextureToFile`, `SendDisplayToMediaCapture`)
- Modify: `src/core/gpu.h/.cpp` (keeps `CalculateDrawRect` callers through the presenter's static; gains
  `GetDisplayParams()`), `src/core/gpu_sw.h/.cpp` (`UpdateDisplay` copies out into a command payload),
  `src/core/settings.h/.cpp` (`g_gpu_settings`), `src/core/system.cpp` (`PostProcessing::Initialize/Shutdown`,
  `ReloadShaders` through `RunOnThreadAndWait`; screenshots, media capture start/stop and frames on the GPU thread),
  `src/core/hotkeys.cpp:501`, `src/duckstation-qt/qthost.cpp:1343,1355`, `src/core/fullscreen_ui.cpp:6300`,
  `android/app/src/cpp/android_host_controllers.cpp:731`, build files
- Test: `src/common-tests/gpu_presenter_tests.cpp`

**Interfaces:**
- Produces:
  ```cpp
  struct GPUDisplayParams // a snapshot of what the screen shows, taken by the front-end at UpdateDisplay
  {
    u16 display_width, display_height, display_origin_left, display_origin_top;
    u16 display_vram_left, display_vram_top, display_vram_width, display_vram_height;
    u16 display_vram_start_x;              // CRTC regs.X, for 24-bit
    float display_aspect_ratio;            // GPU::ComputeDisplayAspectRatio()
    bool display_disabled, is_24bit, interlaced, vertical_resolution;
    u8 interlaced_field;
  };
  class GPUPresenter
  {
  public:
    static void CalculateDrawRect(const GPUDisplayParams& params, const Settings& settings, s32 window_width,
                                  s32 window_height, bool apply_rotation, bool apply_aspect_ratio,
                                  GSVector4i* display_rect, GSVector4i* draw_rect);
    bool Initialize(Error* error);
    void SetDisplayParams(const GPUDisplayParams& params);
    void SetDisplayTexture(GPUTexture* texture, GPUTexture* depth, s32 x, s32 y, s32 width, s32 height);
    void ClearDisplayTexture();
    bool Deinterlace(u32 field, u32 line_skip);
    bool ApplyChromaSmoothing();
    bool PresentDisplay();                 // BeginPresent and draw; Task 9 adds ImGui and EndPresent around it
    bool RenderScreenshotToBuffer(u32 width, u32 height, const GSVector4i display_rect, const GSVector4i draw_rect,
                                  bool postfx, std::vector<u32>* out_pixels, u32* out_stride,
                                  GPUTexture::Format* out_format);
    // RenderScreenshotToFile, WriteDisplayTextureToFile, SendDisplayToMediaCapture: today's signatures.
  };
  extern std::unique_ptr<GPUPresenter> g_gpu_presenter; // GPU thread only
  extern Settings g_gpu_settings;                         // GPU thread's copy
  void System::PushGPUSettings(); // pushes a copy of g_settings (heap, freed by the GPU thread); called at the end
                                  // of System::CheckForSettingsChanges, at GPU creation and by every hotkey that
                                  // edits g_settings (hotkeys.cpp 57, 438, 532, 553, 585)
  ```
- Command `UpdateSWDisplay` (software renderer): the copied-out pixels, their format, size, and the display params.

- [ ] **Step 1: Write the failing tests** (`TEST(GPUPresenterDrawRect, …)`): the expected rects first taken from
  today's `GPU::CalculateDrawRect` (a one-off throwaway harness at `master` printing them, not committed):
  `NTSC320x240InA1920x1080WindowIsPillarboxed`, `PAL368x288StretchIsTheWholeWindow`, `IntegerScalingRoundsDown`,
  `Rotate90SwapsTheWindow`, `ShowVRAMUsesTheWholeVRAM`, `CropModeAllBordersKeepsTheOrigin`, each asserting both
  rects equal the recorded ones.
- [ ] **Step 2: Run** `build/bin/common-tests --gtest_filter='GPUPresenterDrawRect*'`; expect a build failure.
- [ ] **Step 3: Implement** the move; the front-end's `UpdateDisplay` pushes `GPUDisplayParams` and the renderer's
  display update; the software renderer's copy-out writes straight into the command payload (no extra copy).
  Lightgun coordinate maths use `CalculateDrawRect` with `GetDeviceInfo()`'s window size.
- [ ] **Step 4: Run** the filter, all of `common-tests`, and Task 1's regtest runs: dumps identical (Software too:
  `-renderer Software` 1800 frames, dumps identical to `master`'s); screenshots (`System::SaveScreenshot`) identical.
- [ ] **Step 5: Commit** ("Core: display, deinterlacing and post-processing in a presenter on the GPU thread").

### Task 7: The hardware renderer, split

**Files:**
- Create: `src/core/gpu_hw_frontend.h`, `src/core/gpu_hw_frontend.cpp` (`GPU_HWFrontend final : public GPU`)
- Modify: `src/core/gpu_hw.h/.cpp` (`GPU_HW` becomes the back-end, not a `GPU`), `src/core/gpu_types.h` (new
  commands), `src/core/gpu.cpp` (`CreateHardwareRenderer`), `src/core/gpu_commands.cpp` (state-change events),
  build files

**Interfaces:**
- Consumes: Tasks 3 and 6.
- The front-end keeps, from today's `GPU_HW`: the tick counting and culling-for-ticks of `LoadVertices` (with the
  PGXP-adjusted positions, as today), PGXP lookups, polyline assembly, and the software readback renderer
  (`m_sw_renderer`, fed directly on the emulation thread, so software readbacks never wait for the GPU thread).
- Every front-end `FlushRender()` becomes a `FlushRender` command at the same point in the stream.
- Draw commands carry `GPUBackendDrawCommand`'s fields plus `u8 events` — what the front-end set since its last
  command: `EVENT_PAGE_CHANGED = 1`, `EVENT_WINDOW_CHANGED = 2`, `EVENT_AREA_CHANGED = 4` (gpu.cpp 228, 358-360,
  1705, 1725, 1747; gpu_commands.cpp 202, 254, 276). The back-end ORs them into its own sticky flags and clears them
  exactly where `GPU_HW` does today, so batching, dirty-rect checks and the scissor follow today's order.
- Produces (`gpu_types.h`):
  ```cpp
  struct GPUBackendDrawPrecisePolygonCommand : public GPUBackendDrawCommand
  {
    u8 num_vertices;
    bool valid_w; // PGXP's w for every vertex
    struct Vertex { float x, y, w; s32 native_x, native_y; u32 color; u16 texcoord; };
    Vertex vertices[0];
  };
  struct GPUBackendSetDrawingAreaCommand { /* existing */ GSVector4i clamped_area; }; // added field
  struct GPUBackendUpdateDisplayCommand : public GPUThreadCommand { GPUDisplayParams params; bool show_vram; };
  ```
  and core command types (`GPUThreadQueue::FIRST_USER_TYPE` onward): `FlushRender`, `FillVRAM`, `UpdateVRAM` (data
  copied), `CopyVRAM`, `SetDrawingArea`, `DrawPrecisePolygon`, `DrawRectangle`, `DrawLine`, `UpdateDisplay`,
  `UpdateSWDisplay`, `UpdateSettings`, `Present`, `SubmitPresent` (the last two used from Task 9).
- `GPU_HW` (back-end) public: `bool Initialize(Error* error)`, `void Reset(bool clear_vram)`,
  `void HandleCommand(const GPUThreadCommand* cmd)`, `void UpdateSettings(const Settings& old_settings)`,
  `void UpdateResolutionScale(const GPUDisplayParams& params)`, `void RestoreDeviceContext()`,
  `u32 GetResolutionScale() const`; global `std::unique_ptr<GPU_HW> g_gpu_hw` (GPU thread only). After any
  non-renderer work on the GPU thread (present, closures) the dispatcher calls `RestoreDeviceContext()` before the
  next renderer command. The front-end's effective resolution (`GetEffectiveDisplayResolution`) reads an atomic the
  back-end publishes when its scale changes.

- [ ] **Step 1: TDD skipped** (a behaviour-preserving split of GPU code that needs a device); checked by runs.
- [ ] **Step 2: Split** as above; `GPU_HW`'s reads of `m_GPUSTAT`, `m_draw_mode`, `m_drawing_offset`,
  `m_clamped_drawing_area`, `m_crtc_state`, `m_render_command`, `m_fifo` and `m_blit_buffer` come from the command
  being handled; `g_settings` reads become `g_gpu_settings`.
- [ ] **Step 3: Check:** Task 1's regtest runs on Metal, MoltenVK and OpenGL at 1×, 3×, 8× (and with PGXP, PGXP
  depth buffer, line detection, MSAA 4×, the texture cache with the test pack, software readbacks on): every dump
  identical to `master`'s, and the same frame count reached (timing unchanged).
- [ ] **Step 4: Commit** ("GPU/HW: a front-end on the emulation thread, the renderer behind it").

### Task 8: Readbacks, states and settings through the stream

**Files:**
- Modify: `src/core/gpu_hw_frontend.cpp`, `src/core/gpu_hw.cpp` (`ReadVRAM`, `DoState`, `UpdateSettings`,
  `UpdateTextureCache`), `src/core/system.cpp` (memory states 4757-4863: `vram_texture` as
  `GPUThread::TexturePtr`; `DumpVRAM`), `src/core/texture_replacements.cpp` (`Reload` and `PreloadTextures` run on
  the GPU thread), `src/core/gpu.cpp` (`DumpVRAMToFile`)

**Interfaces:**
- GP0 C0h without software readbacks: the front-end flushes, then `RunOnThreadAndWait` runs the back-end's
  `ReadVRAM(rect)` into `g_vram`; with software readbacks it only syncs the software renderer, as today.
- Saving: a VRAM readback into `g_vram` and the CLUT, waited for; host-texture states (rewind, runahead) copy into
  their texture inside one wait. Loading: the state's VRAM goes in an `UpdateVRAM`-like `LoadVRAM` command (a copy,
  so the software readback thread can't race it); host-texture loads inside one wait.
- Framebuffer rebuilds (resolution, MSAA, accurate blending, PGXP depth) and the texture cache's reseeds read back
  into a buffer of the back-end's own, not `g_vram` (which the software readback renderer may be ahead on).
- Settings: the front-end's `UpdateSettings(old)` pushes `UpdateSettings` with the old and new copies; the back-end
  applies them where `GPU_HW::UpdateSettings` did.

- [ ] **Step 1: TDD skipped** (wiring to device work); checked by runs.
- [ ] **Step 2: Implement** as above.
- [ ] **Step 3: Check** (Mac, three renderers): `-dumpvram` every 60 frames on a readback-heavy run (Vagrant Story's
  states and the CD's opening, software readbacks off and on): VRAM dumps identical to `master`'s; rewind and
  runahead (`Main/RewindEnable=true`, `Main/RunaheadFrameCount=2`) runs: dumps identical to `master`'s; in the Qt
  app, resolution and MSAA changed mid-game by hotkey and a state saved, loaded, and loaded under Software.
- [ ] **Step 4: Commit** ("GPU/HW: readbacks, states and settings changes wait for the GPU thread").

### Task 9: The frame's end through the stream

**Files:**
- Modify: `src/core/system.cpp` (`FrameDone` 2091-2290, `PresentDisplay` 5808, `InvalidateDisplay` 5855,
  `UpdatePerformanceCounters`' GPU time), `src/core/gpu_presenter.cpp`, `src/duckstation-qt/qthost.cpp:1838-1850`
  (the paused loop), `src/duckstation-regtest/regtest_host.cpp:362` (frame dumps as GPU-thread work, `Sync` before
  exit), `src/util/gpu_device.h/.cpp` (`ThrottlePresentation` becomes a static sleep the emulation thread can run
  from `DeviceInfo::refresh_rate`)

**Interfaces:**
- Produces:
  ```cpp
  struct GPUThreadPresentCommand : public GPUThreadCommand
  {
    ImGuiDrawCopy* ui;                    // owned by the command
    Common::Timer::Value input_poll_time; // when this frame's input was polled (Task 12)
    bool show_display;                    // false: UI only (loading screen, paused Big Picture without a game)
    bool explicit_present;                // optimal pacing on Vulkan/D3D12: EndPresent(true), SubmitPresent later
    bool allow_skip;                      // the GPU thread may skip when the device says so (ShouldSkipPresentingFrame)
  };
  ```
- `System::PresentDisplay` builds the UI as today, captures it, pushes `Present` and calls `FramePushed()`; the GPU
  thread flushes the batch, presents, renders the UI copy, ends the present, adds the device's GPU time to an atomic
  the counters read, and calls `FramePresented()`. `FrameDone` calls
  `GPUThread::WaitForQueuedFrames(MaxFramesAhead(GetDeviceInfo().vsync_blocking))` just before it pumps messages and
  polls input. Explicit present: `Present{explicit_present}`, `Throttle()`, then `SubmitPresent`, as today's order.

- [ ] **Step 1: TDD skipped** (the frames-ahead rule itself is pinned in Task 3); checked by runs.
- [ ] **Step 2: Implement** as above; `RestoreDeviceContext` calls in `System` and `hotkeys.cpp` become no-ops
  (the dispatcher restores, Task 7).
- [ ] **Step 3: Check:** regtest dumps identical to `master`'s on the three Mac renderers and Software; the Qt app:
  game, pause (Big Picture's pause menu draws), resume, fast-forward, optimal frame pacing on (Vulkan via MoltenVK),
  a screenshot, media capture of 10 s plays back.
- [ ] **Step 4: Commit** ("Core: frames are presented by the GPU thread's stream").

### Task 10: The thread itself

**Files:**
- Modify: `src/core/system.cpp` (`GPUThread::Start(g_settings.gpu_use_gpu_thread)`; a change of the switch
  recreates the device like a renderer change, `RecreateGPU(renderer, true)`), `src/core/host.cpp` (device created
  with `threaded_presentation = gpu_threaded_presentation && !gpu_use_gpu_thread`),
  `android/app/src/cpp/android_host_input.cpp` (`SurfaceChanged` → `RunOnThreadPriority(SetWindow, true)`; the
  window bookkeeping read by `Host::AcquireRenderWindow` lives on the GPU thread; the emulation thread is told after
  through `Host::RunOnCPUThread`: `ImGuiManager::WindowResized`, `InputManager::SetDisplayWindowSize`,
  `System::UpdateSpeedLimiterState`, `InvalidateDisplay` when paused, `HostDisplayResized`),
  `android/app/src/cpp/emu_thread.cpp`, `src/duckstation-qt/qthost.cpp`, `src/core/settings.h/.cpp`
  (`gpu_use_gpu_thread`, `[GPU] UseGPUThread`, default `true`)
- Test: `src/common-tests/settings_gpu_thread_tests.cpp`

- [ ] **Step 1: Write the failing tests:** `TEST(SettingsGPUThread, OnByDefault)` (an empty ini loads
  `gpu_use_gpu_thread == true`); `TEST(SettingsGPUThread, SavedAndLoaded)` (false round-trips through `Save`/`Load`).
- [ ] **Step 2: Run** `build/bin/common-tests --gtest_filter='SettingsGPUThread*'`; expect a build failure.
- [ ] **Step 3: Implement** the key and the start; fix what the thread shows (debug owner-thread assertions, the
  ThreadSanitizer regtest: `-fsanitize=thread`, Metal and OpenGL, 1800 frames, PGXP on, software readbacks on).
- [ ] **Step 4: Check:** `common-tests`; regtest with `-setting GPU/UseGPUThread=true` and `=false` on Metal,
  MoltenVK, OpenGL, Software: dumps identical to each other and to `master`'s; no ThreadSanitizer report from our
  code; the Qt app with the thread on: fullscreen and back, resize, render-to-main toggled, renderer changed
  Metal → Vulkan → OpenGL → Software → Metal mid-game, resolution changed, stop game, quit — no hang or crash.
- [ ] **Step 5: Commit** ("Core: the GPU thread, on by default").

### Task 11: The switch in the UIs

**Files:**
- Modify: `src/duckstation-qt/graphicssettingswidget.ui` (a `useGPUThread` checkbox "Use a GPU thread" in the
  advanced group beside `threadedPresentation`), `src/duckstation-qt/graphicssettingswidget.cpp` (bound with
  `Settings` default; its tooltip; `threadedPresentation` greyed out while it's on), `src/core/fullscreen_ui.cpp`
  (row beside 4924, `ThreadedPresentation` hidden while on), Android:
  `settings/GraphicsSettings.kt` (`GPU_THREAD = BoolSetting("GPU", "UseGPUThread", true)`), the Graphics page's
  advanced section (row "Use a GPU thread", `docs/design-system.md` rows; Threaded presentation hidden while on),
  per-game where the section's rows are
- Test: `android/app/src/test/.../GraphicsSettingsTest.kt`, the Graphics page's instrumented test

- [ ] **Step 1: Write the failing tests:** JUnit `theGPUThreadIsOnByDefault` (`GPU_THREAD.default == true`, key
  `UseGPUThread`); Compose `theGPUThreadRowIsInAdvanced` (row present, toggles `[GPU] UseGPUThread`);
  `threadedPresentationHidesWhileTheGPUThreadIsOn`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest` and the page's class on emulator-5554; expect failures.
- [ ] **Step 3: Implement** the three rows.
- [ ] **Step 4: Run** the tests again (PASS), the desktop build, and look at Qt's and Big Picture's rows.
- [ ] **Step 5: Commit** ("Settings: Use a GPU thread, on Qt, Big Picture and Android").

### Task 12: The counters

**Files:**
- Modify: `src/core/system.cpp` (`UpdatePerformanceCounters`, `ResetPerformanceCounters`,
  `AccumulatePreFrameSleepTime`), `src/core/system.h` (`GetGPUThreadUsage()`, `GetGPUThreadAverageTime()`,
  `GetInputLatency()`), `src/core/imgui_overlays.cpp:361-367` (a "GPU Thread" line in the performance overlay),
  `src/util/opengl_device.cpp:819-890` (timer queries), `src/core/performance_counters_math.h` (new, pure)
- Test: `src/common-tests/performance_counters_math_tests.cpp`

**Interfaces:**
- Produces (namespace `PerformanceCountersMath`):
  ```cpp
  // A frame's active time for the pre-frame sleep's budget: until its present finished, not until it was pushed.
  Common::Timer::Value ActiveFrameTime(Common::Timer::Value frame_start, Common::Timer::Value pushed,
                                       Common::Timer::Value presented); // presented 0: not known yet -> pushed
  // A GPU time sample to keep: false for one the queries can't have measured.
  bool IsPlausibleGPUTime(float gpu_ms, float wall_ms);                 // 0 <= gpu_ms <= 4 * wall_ms
  ```
- The per-second verbose line becomes `FPS: … VPS: … CPU: … GPUThread: … GPU: … Lat: …ms Average: … Min: … Max:`,
  `Lat` the mean of (present finished − input polled) over the second's presented frames; `GPU` shows only plausible
  samples (else `-`), and the OSD hides GPU % while the last second had none.

- [ ] **Step 1: Write the failing tests:** `ActiveTimeRunsToThePresent` (start 0, pushed 5 ms, presented 9 ms →
  9 ms); `ActiveTimeWithoutAPresentIsThePush`; `AGarbageGPUTimeIsDropped` (`IsPlausibleGPUTime(1e13f, 16.7f)` false,
  `(12.0f, 16.7f)` true, `(-1.0f, 16.7f)` false).
- [ ] **Step 2: Run** `build/bin/common-tests --gtest_filter='PerformanceCountersMath*'`; expect a build failure.
- [ ] **Step 3: Implement**; GPU thread CPU time from `GPUThread::GetThread()->GetCPUTime()`; the GL fix: read a
  query only when `GL_QUERY_RESULT_AVAILABLE`, drop the batch when `GL_GPU_DISJOINT_EXT` is set, and filter with
  `IsPlausibleGPUTime`.
- [ ] **Step 4: Run** the filter and `common-tests` (PASS); the Qt overlay shows both threads.
- [ ] **Step 5: Commit** ("Core: the GPU thread's counters, input latency and a trustworthy GPU time").

### Task 13: Android's tests with the thread off and on

**Files:**
- Modify: `android/app/src/androidTest/.../core/TestRenderer.kt` (a `gooseGPUThread` argument, `true`/`false`,
  written as `[GPU] UseGPUThread` before each boot), `android/scripts/run-instrumented-tests.sh` (full: the
  `@BootsGames` classes once more with `gooseGPUThread=false` on goose-arm64)
- Create: `android/app/src/androidTest/.../core/GPUThreadTest.kt` (`@BootsGames`, padtest's disc or the test
  states where `GOOSE_TEST_DATA` has them, through `FrameReader`)

- [ ] **Step 1: Write the tests:** `theThreadDrawsTheSameFrames` (frame 600 read with the thread off, then on,
  `FrameReader` difference 0); `switchingAppsMidFrameKeepsDrawing` (surface destroyed and given back 20 times while
  running: frames keep arriving, no crash); `resizingTheSurfaceKeepsDrawing` (640×360 ↔ 1280×720 ten times, as a
  rotation does); `pausingAndResumingKeepsTheFrame` (paused frame equals the frame before; resumed frames arrive);
  `switchingRendererWithTheThreadOnKeepsDrawing` (Vulkan → OpenGL ES → Software → Vulkan mid-game);
  `flippingTheSwitchMidGameKeepsTheFrame` (off → on → off: frames identical to before);
  `aStateSavedWithTheThreadLoadsWithoutIt` (save at frame 300 with on, load with off: the next frame equals the one
  after loading with on); `stoppingWhilePresentingDoesNotHang` (stop within 5 s at 8× with MSAA).
- [ ] **Step 2: Run** `GPUThreadTest` on emulator-5554 (and goose-test-2 under OpenGL ES); fix what fails.
- [ ] **Step 3: Run** the quick tier from a detached worktree; read the XML results (memory `device-test-runs`).
- [ ] **Step 4: Commit** ("Android: tests with the GPU thread off and on, around the surface's life").

### Task 14: Measurements, and the defaults they decide

- [ ] **Step 1: Mac** (Task 1's runs, thread on and off): dumps identical to `master`'s; wall time per renderer and
  scale. **Windows** the same on D3D11, D3D12, Vulkan, OpenGL.
- [ ] **Step 2: Thor** (Task 1's test, thread on and off): (a) every second of 30 at ≥ 59.8 VPS; (b) unlimited speed
  at 1× and 3× ≥ 1.2 × the before number, Vulkan and OpenGL ES; (c) `Lat` with the thread on ≤ off, at defaults,
  Vulkan and OpenGL ES; plus the per-second CPU lines for the PR. Clean up the Thor.
- [ ] **Step 3: Any renderer slower with the thread on** (wall time worse than off beyond run-to-run noise, measured
  three times): `Settings::GetDefaultGPUThread(GPURenderer)` returns false for it, `Load` uses it when the key is
  absent, the three UIs' defaults follow it, with a test (`TEST(SettingsGPUThread, <Renderer>DefaultsOff)`) and the
  reason in the PR. If none is slower, skip this step and say so.
- [ ] **Step 4: Android full tier** from a detached worktree, CI's Gradle line, `run-native-tests.sh`.
- [ ] **Step 5: Commit** any default change ("Settings: the GPU thread off by default on <renderer>").

### Task 15: Docs and the PR

**Files:**
- Modify: `docs/roadmap.md` (item 21: stage 1 done, with the numbers; stage 2 next), the user-facing settings docs
  that list the advanced graphics rows (`docs/android.md` if it lists them)

- [ ] **Step 1: Write** the roadmap entry and the docs line ("Use a GPU thread: draws and presents on a thread of its
  own; off runs everything on the emulation thread as before").
- [ ] **Step 2: Whole-branch review** (fresh reviewer on the branch against the design and this plan); fix what it
  finds.
- [ ] **Step 3: Commit** ("Docs: the GPU thread, stage 1, in the roadmap"), push, and open the PR with the before
  and after tables (Thor, Mac, Windows), the identity checks and any renderer left off by default.
