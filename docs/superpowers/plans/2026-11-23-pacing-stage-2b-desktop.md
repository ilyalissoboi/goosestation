# Frame pacing, stage 2b (desktop) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On desktop, vsync on by default wherever a present wait keeps at most one frame queued, locked to the
screen so input-to-screen lag is no higher than today's vsync-off default.

**Architecture:**
- **The wait.** Each device that can wait on its presents (Vulkan `VK_KHR_present_wait`, a DXGI frame-latency
  waitable on D3D12 and on D3D11's flip model, Metal's presented handlers) gets `HasPresentWait()` and
  `WaitForPresent()`. `GPUThreadCommands::HandlePresent` calls the wait after the present while vsync is FIFO, so the
  emulation thread reads input only once the frame is shown. It waits for the frame just presented, or for the one
  before while frames keep missing (`FramePacing::PresentWaitDepth`).
- **The timeline.** The times the waits return make a refresh timeline (`FramePacing::PresentTimeline`), which feeds
  2a's screen lock in place of Android's Choreographer.
- **The setting.** Vsync becomes Automatic/On/Off (`[Display] VSyncMode`). Automatic is on only where the device has
  a wait, and off on Android. Sync to host becomes the default on desktop too.

**Tech Stack:** C++20 core (`src/core`, `src/util`); Vulkan, DXGI 1.3 (`IDXGISwapChain2`), Metal (Objective-C++);
Qt widgets, Big Picture (`fullscreen_ui.cpp`), Kotlin settings; gtest (`src/common-tests`); regtest; MSBuild over
SSH (`pcsx2-win`).

**Spec:** `docs/superpowers/specs/2026-11-20-gpu-thread-and-pacing-design.md`: "Pacing (stage 2)", "Stage 2, revised
(2026-11-22)", and "Stage 2b, decided (2026-11-23)". The last overrides the others where they differ.

## Global Constraints

- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7`; PCSX2 for ideas only.
- **No added input lag at the defaults:** measured against each platform's default today (desktop: vsync off).
- **Nothing drawn differently:** any renderer, GPU thread on or off.
- **No crash or hang** around fullscreen, resizing, changing renderer or resolution, minimizing, or stopping a game.
- **Unchanged at their defaults:** MoltenVK and OpenGL (no present wait), and Android (Automatic is off there; the
  2a lock paces).
- **The existing pacing settings stay:** optimal frame pacing, pre-frame sleep, skip duplicate frames, sync to host
  refresh rate, and vsync (now three-way).
- **Windows** over SSH: `pcsx2-win`, `E:\work\goosestation`, a bundle from `b4d03c344`, a worktree with a junction to
  `deps-x64`, MSBuild. Its regtest has no window. The on-screen check there is the user's own.
- **Commits:** messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; the user reviews each
  message.

## Review Focus

1. **A window that stops presenting** (minimized, fully covered, on a hidden Space): the first wait times out, and
   waits pause for a while instead of every frame costing a timeout. No hang at shutdown. Pinned:
   `PresentWaitDepth.ATimeoutPausesWaits` (Task 2); the Mac run's minimize check (Task 9).
2. **A swap chain made again** (resize, fullscreen, vsync mode change, renderer change): Vulkan present IDs belong to
   one swap chain, so a wait never asks for an ID the new one won't present. The timeline and depth start over.
   Pinned: `PresentTimeline.ResetForgetsTheOldScreen` (Task 2); the resize and fullscreen checks (Task 9).
3. **Fast-forward and turbo on and off:** vsync goes off (no waits, the lock lets go) and comes back without a burst
   of frames. Pinned: `FramePacing.VSyncIsOffWhileFastForwarding` (already there); the Mac toggle check (Task 9).
4. **A screen that isn't a multiple of the console's rate** (144 Hz, PAL on 60): no lock. Automatic vsync uses
   today's mode choice (Mailbox where there is one), and waits run only in FIFO. Pinned:
   `FramePacing.FifoOnlyWhereTheLockCanRun` (Task 2).
5. **Paused and menus:** Big Picture and the pause menu present through the same path with waits; they stay
   responsive, and the lock lets go while paused. Pinned: the Mac pause check (Task 9).

---
### Task 1: Before numbers and the measurement method

**Files:** none in the repo. Throwaway: `/tmp/gt2b-measure.patch` (applied to both builds, never committed), the
`/tmp/gt-tools` scripts. Notes: `pacing-2b-baseline.md` in the shared notes folder.

- [ ] **Step 1: Write the measurement patch.**
  - In `MetalDevice::EndPresent`, add a presented handler on each drawable (`addPresentedHandler`).
  - Bring `presentedTime` onto Common::Timer's clock: `now − (CACurrentMediaTime() − presentedTime)`.
  - Log one line a second: frames shown; the median and p95 of input poll → shown, the poll time taken from the
    present command (`cmd->input_poll_time`) through a global; how many frames were held over their refreshes
    (interval > 1.5 × the expected one); the screen's period.
- [ ] **Step 2: Build the base** (`claude/gpu-thread-pacing` at this branch's base) with the patch: Qt and regtest
  (`build-2b-base`).
- [ ] **Step 3: Measure** with the Qt app on Vagrant Story state 1, 60 s per run, 2 interleaved rounds:
  - Metal at the defaults (vsync off), and with vsync on + sync to host (today's opt-in);
  - per run: lag, seconds even, VPS min;
  - MoltenVK and OpenGL at the defaults: VPS only.
  - Write down which screen the window was on and its rate.
- [ ] **Step 4: Regtest** at the base, 900 frames each on Metal, MoltenVK and OpenGL (`batch.sh`): the frame dumps to
  compare later. Fast-forward timing with `timing2.sh`.
- [ ] **Step 5: On Windows**, build the base Release and run regtest 900 frames on D3D11, D3D12, Vulkan and OpenGL
  (`batch.ps1`), plus `win-timing2.ps1`.
- [ ] **Step 6:** Write the numbers into `pacing-2b-baseline.md`. No commit.

### Task 2: The pacing maths

**Files:**
- Modify: `src/core/types.h` (the enum), `src/core/frame_pacing.h`, `src/core/frame_pacing.cpp`
- Test: `src/common-tests/frame_pacing_tests.cpp`

**Interfaces:**
- Produces:
  ```cpp
  enum class DisplayVSyncMode : u8 { Automatic, On, Off, Count };   // types.h

  namespace FramePacing {
  /// Whether vsync is wanted: On yes, Off no, Automatic where it's allowed (desktop) and the device has a present wait.
  bool VSyncWanted(DisplayVSyncMode mode, bool automatic_allowed, bool has_present_wait);

  /// Whether presents block on the screen (FIFO) rather than today's choice: vsync wanted, sync to host on, a present
  /// wait, and a screen whose rate is a multiple of the console's (RefreshesPerFrame > 0).
  bool FifoForLock(bool vsync_wanted, bool sync_to_host, bool has_present_wait, float refresh_hz, float console_hz);

  /// Which present to wait for: 0 the one just presented, 1 the one before; NO_WAIT for a while after a timeout.
  class PresentWaitDepth {
  public:
    static constexpr u32 NO_WAIT = 0xFFFFFFFFu;
    static constexpr u32 JUDGE_AFTER = 10;    // frames not judged after a reset
    static constexpr u32 MISS_WINDOW = 120;   // two misses within this many frames at 0: go to 1
    static constexpr u32 FIRST_HOLD = 600;    // frames at 1 before trying 0 again
    static constexpr u32 MAX_HOLD = 9600;     // doubled on each failed try, up to this
    static constexpr u32 TIMEOUT_PAUSE = 60;  // frames without waits after a timeout
    /// A frame's wait came back: shown_interval since the last frame shown (0: the wait timed out), period the
    /// refresh, refreshes those the frame should take (1, or the lock's n).
    void Report(Common::Timer::Value shown_interval, Common::Timer::Value period, u32 refreshes);
    u32 Depth() const;
    void Reset();
  };

  /// The screen's refreshes, from the times presents were shown.
  class PresentTimeline {
  public:
    static constexpr u32 WINDOW = 60;
    static constexpr u32 MIN_INTERVALS = 8;
    void Add(Common::Timer::Value shown_time, float reported_hz);
    /// vsync_time the latest shown time; period the median of the intervals, each divided by its whole number of
    /// refreshes (1-4 of the reported period, within 10%); deadline_lead half a period. False with fewer than
    /// MIN_INTERVALS intervals, or nothing shown for 100 ms.
    bool Get(Common::Timer::Value now, DisplayTimeline* out) const;
    void Reset();
  };
  }
  ```
- A miss: `shown_interval > (refreshes + 0.5) × period`.
- A failed try: two misses within `MISS_WINDOW` frames of going back to 0. That doubles the hold.
- `MISS_WINDOW` clean frames at 0 put the hold back to `FIRST_HOLD`.

- [ ] **Step 1: Write the failing tests** (`FramePacing.*`, `PresentWaitDepth.*`, `PresentTimeline.*`):
  - `VSyncWantedFollowsTheMode`: (On, false, false) → true; (Off, true, true) → false; (Automatic, true, true) → true;
    (Automatic, true, false) → false; (Automatic, false, true) → false.
  - `FifoOnlyWhereTheLockCanRun`: (true, true, true, 60, 59.826) → true; the same at 120 → true; at 144 → false; with
    PAL (60, 50) → false; without a wait → false; sync off → false; vsync not wanted → false.
  - `PresentWaitDepth.StartsAtTheFrameJustPresented`: `Depth() == 0`.
  - `PresentWaitDepth.TwoMissesWaitForTheFrameBefore`: at 16.67 ms, after `JUDGE_AFTER` frames on time, two intervals
    of 33.3 ms within 120 frames → 1; one alone → still 0.
  - `PresentWaitDepth.ItTriesAgainAfterItsHold`: at 1, `FIRST_HOLD` frames on time → 0.
  - `PresentWaitDepth.AFailedTryDoublesTheHold`: two misses right after trying → 1, then 0 again only after
    2 × `FIRST_HOLD`, never past `MAX_HOLD`.
  - `PresentWaitDepth.TheLocksRefreshesAreNotMisses`: refreshes 2, intervals of 33.3 ms → stays 0.
  - `PresentWaitDepth.ATimeoutPausesWaits`: `Report(0, …)` → `NO_WAIT` for `TIMEOUT_PAUSE` frames, then the depth
    before it.
  - `PresentTimeline.SixtyHertzWithJitter`: shown every 16.683 ms ± 0.3 ms, reported 60 → period within 0.05 ms of
    16.683, `vsync_time` the last, lead half the period.
  - `PresentTimeline.TwoRefreshesAFrameAt120`: shown every 16.667 ms, reported 120 → period 8.333 ms ± 0.05.
  - `PresentTimeline.ARepeatedFrameDoesntStretchThePeriod`: one interval of 33.3 among 16.7 ones → still 16.7.
  - `PresentTimeline.StaleAfter100ms` and `TooFewIntervalsIsNone`.
  - `PresentTimeline.ResetForgetsTheOldScreen`.
- [ ] **Step 2:** Run `build2/bin/common-tests --gtest_filter='FramePacing.*:PresentWaitDepth.*:PresentTimeline.*'`.
  Expected: FAIL to compile.
- [ ] **Step 3:** Implement them in `frame_pacing.{h,cpp}`.
- [ ] **Step 4:** Run the same filter, then the whole of common-tests. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 3: The setting, and sync to host on desktop

**Files:**
- Modify: `src/core/settings.h`, `src/core/settings.cpp` (load, save, names; `DEFAULT_SYNC_TO_HOST_REFRESH_RATE` true
  on every platform), `src/duckstation-qt/qthost.cpp` (`QtHost::MigrateSettings` calls the migration)
- Create: `src/common-tests/settings_vsync_tests.cpp` (in `src/common-tests/CMakeLists.txt` and the vcxproj + filters)

**Interfaces:**
- Produces:
  - The field: `DisplayVSyncMode display_vsync_mode = DisplayVSyncMode::Automatic;` (replacing `display_vsync`).
  - The names: `Settings::ParseDisplayVSyncMode(const char*)`, `GetDisplayVSyncModeName`,
    `GetDisplayVSyncModeDisplayName`, as the other enums have them.
    - Names: `"Automatic"`, `"On"`, `"Off"`.
    - Display names: `"Automatic (where it adds no lag)"`, `"On"`, `"Off"`.
  - The key: `[Display] VSyncMode`. Without it, `[Display] VSync = true` reads On, and anything else Automatic.
  - The old key is no longer written.
  - `bool Settings::ApplyDesktopPacingDefaults(SettingsInterface& si)`: with `[Main] DesktopPacingDefaultsVersion`
    below 1, it sets `[Main] SyncToHostRefreshRate = true` and the marker, and returns true (changed).
- Ruling: a per-game file with only the old `VSync` key is read through the same fallback, but only where no layer
  has `VSyncMode`. A per-game `VSync = true` under a global `VSyncMode` is lost once. Cost if wrong: one game's vsync
  to set again.

- [ ] **Step 1: Write the failing tests** (`SettingsVSync.*`, on a `MemorySettingsInterface`):
  - `AutomaticByDefault`.
  - `AnOldTrueReadsOn`, `AnOldFalseReadsAutomatic`, `TheModeWinsOverTheOldKey`.
  - `SaveWritesTheModeOnly`.
  - `NamesRoundTrip`.
  - `SyncToHostIsOnByDefault`.
  - `DesktopDefaultsTurnSyncOnOnce`: a file with sync off → on, marker 1, returns true. A second call changes nothing
    and returns false. A file with the marker and sync off stays off.
- [ ] **Step 2:** Run `--gtest_filter='SettingsVSync.*'`. Expected: FAIL to compile.
- [ ] **Step 3:** Implement. Every `display_vsync` reader moves to the mode: `system.cpp` (3613, 3751, the settings
  change checks at 4767 and 4798) through Task 4's `IsVSyncWanted()`. For this task only, a stand-in that treats
  Automatic as off keeps the behaviour unchanged.
- [ ] **Step 4:** Run the filter, the whole of common-tests, and build Qt and regtest. Expected: PASS, and builds.
- [ ] **Step 5:** Commit.

### Task 4: Present waits in the core, and the lock on desktop

**Files:**
- Modify: `src/util/gpu_device.h` (the API), `src/util/gpu_thread.{h,cpp}` (`DeviceInfo::has_present_wait`,
  published), `src/core/gpu_thread_commands.{h,cpp}` (`HandlePresent`, `TrackCutoff`, the present timeline),
  `src/core/system.cpp` (`IsVSyncWanted`, `GetEffectiveVSyncMode`, the timeline lookup)
- Test: `src/common-tests/gpu_thread_tests.cpp` (the info's new field), Task 2's tests

**Interfaces:**
- Consumes: Task 2's `PresentWaitDepth`, `PresentTimeline`, `VSyncWanted` and `FifoForLock`; Task 3's
  `display_vsync_mode`.
- Produces:
  ```cpp
  // GPUDevice
  /// Whether presents can be waited on until the screen shows them.
  virtual bool HasPresentWait() const { return false; }
  /// Waits until the present frames_back before the last one (0: the last) is shown, up to timeout; when it was
  /// shown (Common::Timer's clock; the time the wait returned where the API gives none), or 0 on a timeout.
  virtual Common::Timer::Value WaitForPresent(u32 frames_back, Common::Timer::Value timeout) { return 0; }

  // GPUThreadCommands
  /// The refresh timeline the waits found; false without one (no wait, not FIFO, stale).
  bool GetPresentTimeline(DisplayTimeline* timeline);
  ```
- `HandlePresent` waits after `EndPresent` when all of these hold:
  - the frame was presented;
  - it isn't an explicit present (optimal frame pacing throttles itself);
  - vsync is FIFO;
  - the device has a wait;
  - `Depth()` isn't `NO_WAIT`.
- The wait:
  - Timeout: 100 ms.
  - Its time goes to `PresentWaitDepth::Report`, to `PresentTimeline::Add` (the published refresh rate as the
    reported one), and to the cutoff tracker. That gives the tracker the waited present's own `PresentTarget`, kept by
    the GPU thread's own count of presents.
  - All of it before `FramePresented()`.
  - Android's `FetchPresentFeedback` path stays as it is for devices without a wait.
- Device or swap chain changes (`PublishDeviceInfo` with a new surface, size or vsync mode) reset the depth, the
  timeline and the cutoff tracker.
- `system.cpp`:
  - `IsVSyncWanted()` = `VSyncWanted(mode, automatic_allowed = !__ANDROID__, info.has_present_wait)`.
  - `GetEffectiveVSyncMode` returns FIFO when `FifoForLock(...)`; otherwise today's choice.
  - Each `Host::GetDisplayTimeline` use (`TimelineRefreshes`, `UpdateSpeedLimiterState`, `SleepToDisplayDeadline`)
    goes through `GetTimeline()`: the host's, or else `GPUThreadCommands::GetPresentTimeline`.
- Ruling: no failing test for the wiring itself. It needs a device that waits (a fake `GPUDevice` has about 60 pure
  virtuals). It's checked by Task 2's maths, by regtest showing nothing changed while no device waits yet, and by the
  Mac runs. Cost if wrong: a wiring bug found later, in Task 5's Mac run.

- [ ] **Step 1: Write the failing test** `GPUThreadTest.WithoutADeviceTheInfoSaysSo`: it gains
  `EXPECT_FALSE(info.has_present_wait)`. Expected: FAIL to compile.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run common-tests. Then Mac regtest 900 frames on Metal, MoltenVK and OpenGL against Task 1's dumps
  (`compare.sh`). Expected: PASS, 0 frames differ.
- [ ] **Step 4:** Commit.

### Task 5: Metal's presented handlers

**Files:** Modify `src/util/metal_device.h`, `src/util/metal_device.mm`

**Interfaces:**
- Consumes: Task 4's `HasPresentWait` and `WaitForPresent`.
- `EndPresent`:
  - Numbers each drawable (a present count).
  - Adds a presented handler before `presentDrawable:`. The handler stores the drawable's shown time, converted as in
    Task 1, and its number, under a mutex, and notifies a condition variable.
  - A `presentedTime` of 0 (the drawable was dropped) still counts as presented, at the handler's own time.
- `WaitForPresent(k, timeout)`:
  - Waits on the condition variable until the shown number is at least `count − k`, or the timeout.
  - Returns the waited drawable's shown time, or 0.
- `HasPresentWait()`: true while there's a layer.
- `maximumDrawableCount` stays at 3, so waiting for the frame before still leaves a drawable free.

- [ ] **Step 1: Write the failing check.** Run the Qt app on Metal at the defaults with the measurement patch.
  - Expected before the change: the log says vsync is off and nothing locks.
  - With it: `Locked to the screen's refreshes` in the log, and vsync FIFO.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run the same 60 s run.
  - Expected: locked.
  - Lag no higher than Task 1's Metal default.
  - Seconds even ≥ 95%.
  - VPS min ≥ 59.8 (NTSC).
- [ ] **Step 4:** Run Metal regtest against Task 1's dumps. Expected: 0 frames differ.
- [ ] **Step 5:** Commit.

### Task 6: Vulkan's present_id and present_wait

**Files:** Modify `src/util/vulkan_device.{h,cpp}` (extensions, features, `DoPresent`, the wait),
`src/util/vulkan_entry_points.inl` (`vkWaitForPresentKHR`), `src/util/vulkan_swap_chain.{h,cpp}` (IDs per swap
chain)

**Interfaces:**
- Consumes: Task 4's API.
- Enabling:
  - Ask for `VK_KHR_present_id` and `VK_KHR_present_wait` when both are present.
  - Enable `VkPhysicalDevicePresentIdFeaturesKHR::presentId` and `VkPhysicalDevicePresentWaitFeaturesKHR::presentWait`
    when they're reported.
  - Set `m_optional_extensions.vk_khr_present_wait`.
- Each present:
  - Chains `VkPresentIdKHR`, beside `VK_GOOGLE_display_timing`'s struct where both are there.
  - The ID comes from the swap chain's own counter, starting at 1 for each swap chain made.
- `WaitForPresent(k, timeout)`: `vkWaitForPresentKHR(swap_chain, last_id − k, ns)`. `VK_TIMEOUT` returns 0. An
  out-of-date result returns 0 and leaves the swap chain to the next present.
- `HasPresentWait()`: the extension, a swap chain, and no present thread (`m_present_thread` not joinable). Ruling, as
  2a's feedback: none while the present thread presents. Cost if wrong: no wait with threaded presentation (off on
  desktop by default).
- Ruling: with both present waits and display timing, the wait's time is the one used. The 2a feedback fetch is
  skipped when the device has a wait.

- [ ] **Step 1: Check the build on the Mac.** MoltenVK has no `present_wait`, so it's unchanged.
  - Expected: MoltenVK's log has no present wait.
  - Regtest MoltenVK has 0 frames differ.
  - The Qt app on MoltenVK at the defaults has vsync off and isn't locked, as in Task 1.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run Step 1's checks again; build Windows Release (Task 10's worktree).
  - Expected: they pass, and it builds.
  - On the RTX 4090, the device log names `VK_KHR_present_wait` (seen from a regtest run's log).
- [ ] **Step 4:** Commit.

### Task 7: D3D12 and D3D11 frame-latency waitables

**Files:** Modify `src/util/d3d12_device.{h,cpp}`, `src/util/d3d11_device.{h,cpp}`; a shared helper in
`src/util/d3d_common.{h,cpp}` if both use it the same way

**Interfaces:**
- Consumes: Task 4's API.
- Making the swap chain:
  - On the flip model (always on D3D12; D3D11 where `m_using_flip_model_swap_chain`), create it and resize its
    buffers with `DXGI_SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT`.
  - Get `IDXGISwapChain2`, its waitable (`GetFrameLatencyWaitableObject`, closed with the swap chain) and
    `SetMaximumFrameLatency(1)`.
  - Blit model, or no `IDXGISwapChain2`: no wait.
- `WaitForPresent(k, timeout)`:
  - Sets the maximum latency to `1 + k` when it changes.
  - Then `WaitForSingleObjectEx(waitable, ms, TRUE)`; returns the time it returned, or 0 on `WAIT_TIMEOUT`.
- Ruling: the waitable flag stops DXGI's own throttle on `Present`. So outside FIFO (vsync off, Mailbox, fast-forward)
  each present still waits on the object, at a maximum latency of 3 and the same timeout. That keeps the queue as
  bounded as DXGI kept it before, and costs nothing when frames leave at once. Cost if wrong: a slower fast-forward,
  seen in Task 10's timing.
- `HasPresentWait()`: the waitable exists.

- [ ] **Step 1:** Implement both. (There's no failing test first: Windows can't present over SSH. It's checked by the
  build, regtest and the user's on-screen check.)
- [ ] **Step 2:** Build Windows Release; run regtest D3D11 and D3D12 900 frames against Task 1's dumps, and
  `win-timing2.ps1`. Expected: 0 differ, and fast-forward within run-to-run noise of Task 1.
- [ ] **Step 3:** Commit.

### Task 8: The three-way row in Qt, Big Picture and Android

**Files:**
- Modify (Qt): `src/duckstation-qt/emulationsettingswidget.ui` (the `vsync` checkbox becomes a combo box),
  `src/duckstation-qt/emulationsettingswidget.cpp` (`BindWidgetToEnumSetting`, the tooltip, and
  `updateSkipDuplicateFramesEnabled`: vsync counts as on unless Off)
- Modify (Big Picture): `src/core/fullscreen_ui.cpp` (3602–3630: `DrawEnumSetting`, and the skip-duplicates
  condition)
- Modify (Android):
  - `android/app/src/cpp/jni_settings.cpp`: the `DisplayVSyncMode` list.
  - `NameList.kt`: `VSyncMode("DisplayVSyncMode")`.
  - `EmulationSettings.kt`: `VSYNC` becomes `NameSetting("Display", "VSyncMode", "Automatic", NameList.VSyncMode)`.
  - `EmulationScreen.kt`: a `GameNames` row in place of the switch.
  - The Android UI tests that name the row (`OrientationLayoutTest.kt:934`).
- Test:
  - `android/app/src/test/.../settings/EmulationSettingsTest.kt`: `vsyncIsAutomaticByDefault`.
  - `android/app/src/androidTest/.../core/SettingsSetupTest.kt`: `aFileWithTheOldVSyncKeyReadsOn`.
- Copy: Android's text for the row is "Automatic leaves it off: the screen lock paces frames here. On matches every
  frame to the screen and can add latency." Qt and Big Picture's text is "Automatic turns it on where the renderer
  can wait for each frame to be shown, with no added latency (Vulkan with present wait, D3D12, D3D11, Metal). On
  turns it on everywhere, and can add latency." Fullscreen UI strings go through `FSUI_CSTR`, as their neighbours do.

- [ ] **Step 1: Write the failing tests** (the two above). Run them: `testDebugUnitTest --tests
  '*EmulationSettingsTest*'`, and `SettingsSetupTest` by class on goose-test-1. Expected: FAIL.
- [ ] **Step 2:** Implement the three UIs.
- [ ] **Step 3:** Run the tests. Expected: PASS.
  - Android CI line: `assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs`.
  - Build Qt and open the Emulation page by hand on the Mac: three choices, Automatic selected.
  - Big Picture's Emulation page shows the same.
- [ ] **Step 4:** Commit.

### Task 9: Measured on the Mac, and the edges

**Files:** none in the repo (notes, ledger).

- [ ] **Step 1: Interleaved runs.** Base and branch, both with the measurement patch, 2 rounds (the second
  reversed), 20 s cool-downs, 60 s runs, Vagrant Story state 1, at the defaults:
  - Metal at 1×, and at 8× with 4× MSAA and CRT-Lottes (the heavy case);
  - MoltenVK and OpenGL at 1×;
  - per run: lag, seconds even, frames held over their refreshes, VPS min, the depth's switches (from the log).
- [ ] **Step 2: The other screen.** Repeat Metal 1× on the other screen (the built-in at its ProMotion rate, or the
  external one).
- [ ] **Step 3: The edges, on Metal, by hand with the Qt app:**
  - minimize and restore (Review Focus 1: no crawl, no hang);
  - resize and fullscreen on and off (2);
  - fast-forward on and off three times (3: VPS up when on, locked again after);
  - pause and the pause menu (5);
  - stop the game while running;
  - a PAL game or a 50 Hz state if there's one in `~/work/goose-test-data` (4: no lock).
- [ ] **Step 4: Fast-forward and frames.** `timing2.sh` (fast-forward VPS no lower than Task 1) and regtest
  `compare.sh` on all three renderers. Expected: 0 differ.
- [ ] **Step 5:** Write the numbers into the notes.

### Task 10: Windows

**Files:** none in the repo.

- [ ] **Step 1: Build.** Bundle the branch from `b4d03c344`; make a worktree on `pcsx2-win` with the `deps-x64`
  junction; build Release (Qt and regtest) with MSBuild.
- [ ] **Step 2: Regtest.** 900 frames on D3D11, D3D12, Vulkan and OpenGL against Task 1's dumps; `win-timing2.ps1`.
  Expected: 0 differ, and fast-forward within noise.
- [ ] **Step 3: The user's on-screen check.** Write a short list for the PR, for D3D11, D3D12 and Vulkan, at the
  defaults:
  - the log line `Locked to the screen's refreshes`;
  - no tearing;
  - the speed at 100%;
  - fast-forward goes faster;
  - minimize and restore, fullscreen on and off;
  - lag that feels no worse than vsync Off;
  - OpenGL: vsync off at Automatic.
- [ ] **Step 4:** Remove the worktree.

### Task 11: Docs, the Android tiers, the PR

**Files:** Modify `docs/roadmap.md` (item 22: 2b done, with the numbers), the desktop docs that describe vsync if
any (`grep -rn "VSync" docs/*.md`), `docs/android.md` (the row's text)

- [ ] **Step 1:** Run the quick tier from a detached worktree, then the full tier. Expected: green (read the XML).
- [ ] **Step 2:** Run a whole-branch review by a fresh reviewer, and fix what it finds.
- [ ] **Step 3:** Update the docs. Commit.
- [ ] **Step 4:** Push. Open the PR against master (PR 140 merged on 2026-11-23), with the
  before-and-after tables and Task 10's checklist. Bind it.
