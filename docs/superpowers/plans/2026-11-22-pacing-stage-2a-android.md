# Frame pacing, stage 2a (Android) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On Android, lower the lag from input to screen by at least 10 ms while every frame stays one refresh on screen.

**Architecture:** A vsync timeline (Choreographer) reaches the core through the host. When the screen's refresh is a
multiple of the console's, the emulation thread locks to it. It presents each frame as soon as it is done, then sleeps
before the next frame until a wake time timed so the frame lands just before SurfaceFlinger's deadline. The game's
speed is locked to the refresh, as sync-to-host already does. The maths is pure and tested natively. Without a
timeline (desktop, regtest, an old Android), today's paths run unchanged.

**Tech Stack:** C++20 core (`src/core`), Android NDK (`AChoreographer`, `ANativeWindow_setFrameRate`, loaded with
`dlsym`, minSdk 29), Kotlin settings (`android/app/src/main/java/.../settings`), gtest (`src/common-tests`), the
instrumented tiers.

**Spec:** `docs/superpowers/specs/2026-11-20-gpu-thread-and-pacing-design.md` — "Pacing (stage 2)", and "Stage 2,
revised (2026-11-22)", which overrides it where they differ.

## Global Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`; PCSX2 for ideas only.
- No added input lag at the defaults; nothing drawn differently, any renderer, GPU thread on or off.
- No crash or hang around Android pause, resume, rotate or app switch, or a surface lost mid-sleep.
- minSdk 29: API 30 (`ANativeWindow_setFrameRate`) and API 33 (`AChoreographer_postVsyncCallback`) through `dlsym`,
  with a fallback when they are missing.
- The player's screen-rate choice in Android's settings is asked, never overridden.
- Desktop behaviour is unchanged in 2a, except vsync off while fast-forwarding (all platforms).
- The Thor: the `.debug` app, scratch data in `/sdcard/GooseTest`, never the release app or the user's data folder,
  cleaned up after. Switching its screen to 120 Hz only with the user's OK, and back after.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; the user reviews each.

## Review Focus

1. A surface destroyed or resized while the emulation thread sleeps toward a deadline: it wakes, and nothing waits on
   a timeline that stopped (Choreographer stops ticking without a visible window).
2. The screen switching between 60 and 120 Hz mid-game (the system or the player): the lock follows the new period.
   It never puts two frames into one refresh.
3. A frame that runs past its deadline (heavy settings, a shader compile): the next one targets the next refresh. It
   never spins catching up, and the predicted frame time grows.
4. PAL (50 Hz) on a 60 Hz screen, or any rate with no multiple: no lock, today's throttle exactly.
5. Pause, the frame step, rewind, runahead and fast-forward: the lock lets go and comes back without a burst of frames.

---

### Task 1: Before numbers and the measurement method

**Files:** none in the repo. Throwaway: `/tmp/gt2-perf` (master + the GpuMeasureTest harness, its `set=` argument),
`/tmp/gt-tools/thor-screen.sh`, `screen-sum.sh`, `frame-times.py`. Notes: `pacing-stage2-baseline.md` in the shared
notes folder.

- [ ] **Step 1:** Record the baseline already taken (defaults, and vsync, sync-to-host, optimal pacing with pre-frame
  sleep, on Vulkan and OpenGL ES, 1× and heavy) in the notes and the ledger.
- [ ] **Step 2:** Define the lag measure the PR reports: core `Lat` (poll → present done) + median (GPU ready → on
  screen), from the same run. Note that the GPU's own render time between them is left out, and is the same before
  and after.
- [ ] **Step 3:** Add a state 2 and a resume run of the defaults, Vulkan and OpenGL ES, so later comparisons have three
  states. Expected: all even, lag 36–37 ms + Lat.

### Task 2: The pacing maths

**Files:**
- Create: `src/core/frame_pacing.h`, `src/core/frame_pacing.cpp` (listed in `src/core/CMakeLists.txt` and `src/core/core.vcxproj` + filters)
- Test: `src/common-tests/frame_pacing_tests.cpp` (in `src/common-tests/CMakeLists.txt`)

**Interfaces:**
- Produces:
  ```cpp
  struct DisplayTimeline            // a refresh the screen will show, as the host last saw it
  {
    Common::Timer::Value vsync_time;   // a past refresh
    Common::Timer::Value period;       // refresh period
    Common::Timer::Value deadline_lead; // how long before a refresh a buffer must be queued to make it
  };
  namespace FramePacing {
  u32 RefreshesPerFrame(float refresh_hz, float console_hz);   // n with refresh within ±0.5% of n×console, n ≤ 4; else 0
  float LockedSpeed(float refresh_hz, float console_hz, u32 n); // refresh / (n × console)
  struct Wake { Common::Timer::Value wake_time; Common::Timer::Value deadline; };
  Wake NextWake(const DisplayTimeline& tl, Common::Timer::Value now, u32 n, Common::Timer::Value last_deadline,
                Common::Timer::Value predicted_frame_time, Common::Timer::Value margin);
  }
  ```
  `NextWake` targets the first deadline that is at least n periods after `last_deadline`, and whose
  `deadline − predicted − margin` is not already past `now`. It never targets a refresh already used.

- [ ] **Step 1: Write the failing tests** (`FramePacing.*`):
  - `RefreshesPerFrame`: (60, 59.826) → 1; (120, 59.826) → 2; (59.94, 59.826) → 1; (60, 50) → 0; (144, 59.826) → 0;
    (100, 50) → 2; (0, 59.826) → 0.
  - `LockedSpeed(60, 59.826, 1)` ≈ 1.00291; `LockedSpeed(120, 59.826, 2)` ≈ 1.00291.
  - `NextWake`, 60 Hz (period 16.667 ms, lead 4 ms):
    - a short frame gets a wake just before its deadline;
    - a frame that ran late skips to the next refresh, never the one just missed;
    - n = 2 targets every second deadline;
    - `last_deadline` in the future is never targeted twice;
    - a 120 → 60 Hz period change mid-sequence stays in order.
- [ ] **Step 2:** Run `build2/bin/common-tests --gtest_filter='FramePacing.*'`. Expected: FAIL to compile (no `frame_pacing.h`).
- [ ] **Step 3:** Implement `frame_pacing.{h,cpp}`.
- [ ] **Step 4:** Run the same. Expected: PASS. Then the whole of common-tests.
- [ ] **Step 5:** Commit.

### Task 3: The vsync timeline on Android

**Files:**
- Create: `android/app/src/cpp/android_vsync.h`, `android/app/src/cpp/android_vsync.cpp` (in the app's CMake list)
- Modify: `src/core/host.h` (+ desktop stubs where `Host::` functions are defined: Qt, regtest), the Android host
- Test: `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/core/VsyncTimelineTest.kt`
  (+ a JNI test hook in the debug-only test bridge if one exists, else `NativeLibrary`'s test-only call)

**Interfaces:**
- Produces:
  - `bool Host::GetDisplayTimeline(DisplayTimeline* tl)`: false on desktop and when no tick has come for 100 ms
    (window gone).
  - Android: `AndroidVsync::Start()` and `Stop()`, a thread with its own `ALooper`. It reposts
    `AChoreographer_postVsyncCallback` (API 33: the next frame timeline's deadline and expected present) or
    `postFrameCallback64` (API 29–32: the refresh time; lead 0.5 × period). The latest value is kept under a mutex.
    Started with the core, stopped at shutdown.
- [ ] **Step 1: Write the failing test** `VsyncTimelineTest.theTimelineTicksAtTheScreensPeriod`:
  - on the emulator, the timeline is available within 200 ms of start;
  - its period is within 2% of `Display.getRefreshRate()`;
  - `vsync_time` advances across 0.5 s;
  - after `Stop()` it reports unavailable within 150 ms.
- [ ] **Step 2:** Run it by class on goose-test-1. Expected: FAIL (no native timeline).
- [ ] **Step 3:** Implement. `dlsym` from `libandroid.so`, with the API 29 fallback.
- [ ] **Step 4:** Run it. Expected: PASS. Then the Android CI line (assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs).
- [ ] **Step 5:** Commit.

### Task 4: The emulation thread locked to the timeline

**Files:**
- Modify: `src/core/system.cpp` (`UpdateSpeedLimiterState`, `FrameDone`, `Throttle`, the pre-frame sleep functions)
- Test: `src/common-tests/frame_pacing_tests.cpp` (the decision); `android/.../core/FramePacingTest.kt` (instrumented)

**Interfaces:**
- Consumes: `FramePacing::*`, `Host::GetDisplayTimeline`.
- Produces:
  - `bool FramePacing::ShouldLock(bool sync_to_host, bool has_timeline, u32 n, float target_speed, bool interrupted)`:
    sync to host is on, there is a timeline, n > 0, the target speed is 1.0, and nothing interrupts.
  - In `System`, `s_locked_to_display`:
    - When locked, `FrameDone` presents straight after the frame (no throttle before the present, no explicit split).
      It then sleeps until `NextWake(...).wake_time` before the input poll. The predicted frame time is the pre-frame
      sleep's `s_max_active_frame_time` plus `PreFrameSleepBuffer`.
    - The target speed is `LockedSpeed`, and the audio's nominal rate follows, as sync-to-host does today.
    - Unlocked: today's code, byte for byte.

- [ ] **Step 1: Write the failing tests:**
  - `FramePacing.ShouldLock`: the five cases, each condition false in turn.
  - `FramePacingTest.aLockedGameStaysAtTheScreensRate` (emulator, Vulkan, padtest or the VS menu state):
    - `SyncToHostRefreshRate` on;
    - over 10 s, VPS within 0.5% of the emulator display's refresh rate;
    - no frame interval over 1.5 periods, from the core's per-second Max.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Implement. The wake sleep uses `Common::Timer::SleepUntil(wake, false)` (never spins on Android). It
  returns early when the GPU thread reports the surface gone or the system pauses.
- [ ] **Step 4:** Run them, then regtest identity on the Mac (900 frames vs master: 0 differ, as there's no timeline)
  and common-tests. Expected: PASS, 0 differ.
- [ ] **Step 5:** Measure on the Thor (`set=Main/SyncToHostRefreshRate=true`), Vulkan and OpenGL ES 1×.
  Expected: even 30/30, lag at least 10 ms under Task 1's. If not, tune the margin and lead here and ledger each
  ruling.
- [ ] **Step 6:** Commit.

### Task 5: The console's frame rate asked of the platform

**Files:**
- Modify: `android/app/src/cpp/android_host_input.cpp` (the window set), `android/app/src/cpp/android_host.cpp`
  (game start, region change, stop)
- Test: `src/common-tests/frame_pacing_tests.cpp` (`FramePacing::RequestedFrameRate`)

**Interfaces:**
- Produces:
  - `float FramePacing::RequestedFrameRate(float console_hz)`: 59.826 → 59.826, 50 → 50, 0 → 0 (no request).
  - On Android, `ANativeWindow_setFrameRate(window, rate, ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)` (API
    30, `dlsym`): on every new window, when the throttle frequency changes, and with 0 at stop.
- [ ] **Step 1:** Write the failing `FramePacing.RequestedFrameRate` test. Run it. Expected: FAIL.
- [ ] **Step 2:** Implement both. Run common-tests. Expected: PASS.
- [ ] **Step 3:** On the Thor, check in `dumpsys SurfaceFlinger` that the layer's frame-rate vote is the game's
  (59.83). Expected: the screen stays at the user's 60.
- [ ] **Step 4:** Commit.

### Task 6: Defaults, the setting's text, and vsync off while fast-forwarding

**Files:**
- Modify:
  - `src/core/settings.cpp` (Android default for `Main/SyncToHostRefreshRate` on);
  - `android/.../settings/EmulationSettings.kt` (`SYNC_TO_HOST` default true);
  - the Emulation screen's row text ("Lock to the screen's refresh rate: frames land on each refresh, with less lag");
  - `src/core/system.cpp` (`GetEffectiveVSyncMode`).
- Test:
  - `android/.../settings/EmulationSettingsTest.kt`;
  - `src/common-tests/frame_pacing_tests.cpp`: `FramePacing::VSyncWhileFastForwarding(bool vsync, bool non_standard_speed)` → off when not at standard speed.

- [ ] **Step 1:** Write the failing tests:
  - `EmulationSettingsTest.syncToHostIsOnByDefault`;
  - `FramePacing.VSyncIsOffWhileFastForwarding`;
  - the Emulation screen's row test, for the new text.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Implement:
  - The Android default is set in `Settings` under `#ifdef __ANDROID__` (desktop keeps off).
  - Vsync is Disabled while fast-forwarding or in turbo, on every platform, and comes back on after.
- [ ] **Step 4:** Run them, the Android CI line and common-tests. Expected: PASS.
- [ ] **Step 5:** Commit.

### Task 7: The surface's life, and the lock's edges

**Files:**
- Test: `android/.../core/FramePacingTest.kt`
- Modify: as the tests find

- [ ] **Step 1: Write the tests** (emulator, sync to host on):
  - `aSurfaceGoneMidSleepDoesNotHang`: 20 destroy/recreate cycles, then frames come;
  - `pausingAndResumingTheLockedGameKeepsItsRate`;
  - `fastForwardLetsGoAndComesBack`: VPS above 2× while held, back within 0.5% of the refresh 2 s after;
  - `aPalGameIsNotLocked`: a PAL BIOS region or padtest forced to PAL, if the test data has one; else the
    `RefreshesPerFrame` unit case only, ledgered.
- [ ] **Step 2:** Run them on goose-test-1 and goose-test-2. Expected: any failure is a bug in Task 4. Fix it with the
  test as RED.
- [ ] **Step 3:** Run the quick tier from a detached worktree. Expected: green.
- [ ] **Step 4:** Commit.

### Task 8: Measured on the Thor

**Files:** none in the repo (notes, ledger, the PR).

- [ ] **Step 1:** Run master and the branch interleaved, 2 rounds (the second reversed), 20 s cool-downs:
  - Vulkan and OpenGL ES at 1×, states 1, 2 and resume;
  - the heavy OpenGL ES case;
  - per run: seconds even, frames held over one refresh, lag (Task 1's measure), VPS min.
- [ ] **Step 2:** With the user's OK, set the screen to 120 Hz for one Vulkan and one OpenGL ES run, then back to 60.
  Expected: each frame held two refreshes.
- [ ] **Step 3:** Fast-forward Vulkan 1× (VPS no lower than master's) and the Mac regtest timing, 1× (no slower).
- [ ] **Step 4:** Clean the Thor up (test APK, `/sdcard/GooseTest`, the app's cache).

### Task 9: Docs, the full tier, the PR

**Files:**
- Modify: `docs/roadmap.md` (item 22: 2a done, with the numbers; 2b next), `docs/android.md` (the Emulation row's
  default and text)

- [ ] **Step 1:** Run the full tier from a detached worktree. Expected: green.
- [ ] **Step 2:** Update the docs, then commit.
- [ ] **Step 3:** Push, open the PR with the before-and-after tables, and bind it.
