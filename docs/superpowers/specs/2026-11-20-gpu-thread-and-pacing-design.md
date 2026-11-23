<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# GPU thread and frame pacing — design

Roadmap items 21 (GPU work on its own thread) and 22 (frame presentation and pacing), from the upstream gaps players
notice most (`docs/upstream-features.md`, "What players notice most", tier 3). Agreed in conversation on 2026-11-20.
Calls made while writing this up, not yet confirmed, are marked *(writer's call)*; calls the builder may make are
marked *(builder's call)*.

## Intent

The player's picture is the **AYN Thor** *(the player's choice)*. There, GooseStation holds full speed in ordinary
play, but two things fall short, both measured (below): frame times are uneven (nearly every second has a frame of
19–28 ms against 16.7 ms), and the heaviest settings on OpenGL ES drop below full speed, because the emulation thread
spends most of its time issuing draws and waiting on the driver. This **moves the hardware renderers' work onto a
GPU thread of its own and paces frames evenly**, on **desktop and Android, on every hardware renderer** *(the player's
choice)*, aimed at what was measured *(the player's choice)*.

## Measured on the Thor (2026-11-20)

Vagrant Story (USA) from its three test states (`~/work/goose-test-data/savestates/`), on the `.debug` build at
`b4d03c344`, drawing to a 1920×1080 `ImageReader` surface (not the screen), from a throwaway instrumented test; the
core's per-second line (`System::UpdatePerformanceCounters`) and simpleperf on the `CPU Thread`. The Thor is a
Snapdragon 8 Gen 2 (QCS8550); its main screen runs at 60 or 120 Hz (60 when measured).

- **Ordinary settings never drop a frame**: 59.8 VPS at 1×, 3×, 5× and 8× on Vulkan, and 1×, 5× and 8× on OpenGL ES.
  At normal speed the emulation thread uses 4–7% of a core on Vulkan, and 7% (1×) to 43–52% (8×) on OpenGL ES.
- **The heaviest setting drops on OpenGL ES**: 8×, 4× MSAA and the CRT-Lottes shader run at 57–59 VPS with frames up
  to 48 ms. Vulkan holds 60 there (GPU 67–81% busy).
- **Unlimited speed** runs 20–50× (1×–5×) and 9–22× (8×); the emulation thread is the limit at 1× and 3×, the GPU from
  5× up.
- **The emulation thread's time at 8×, by simpleperf** (its on-CPU samples): on OpenGL ES about 60% is host rendering
  (`GPU_HW::FlushRender` and `DispatchRenderCommand` ≈25%, presenting ≈20% (`eglSwapBuffers` 8%), Adreno's GLES driver
  20% overall), against about 20% emulating the PS1 (the recompiler's code) and 8% audio stretching (SoundTouch). On
  Vulkan with the heaviest setting, host rendering is about 25% (`FrameDone` 20%, `FlushRender` 5%), the rest
  emulation (≈40%) and audio stretching (10%).
- **Uneven frames at normal speed, whatever the settings**: the average frame is 16.72 ms, and nearly every second's
  slowest is 19–28 ms. Android never spin-waits in `System::Throttle` (it sleeps), and optimal frame pacing is off.
- **Found in passing:** OpenGL ES's GPU usage figure is garbage (values around 10^13 %), so the GL timer queries are
  wrong on Adreno; CPU % is a share of one core at whatever clock the governor chose, so it isn't work done.

## What exists

From a map of the code (2026-11-20; line numbers as of `b4d03c344`):

- **One thread does it all.** The emulation thread (Qt's `EmuThread::run`, `qthost.cpp:1789`; Android's
  `EmuThread::ThreadMain`, `android/app/src/cpp/emu_thread.cpp:175`) runs `System::Execute`, owns the `GPUDevice` for
  every API (created in `Host::CreateGPUDevice`, `host.cpp:317`, with ImGui; GL's context is current there), records
  the hardware renderer's draws as it executes GP0 commands, and presents in `System::FrameDone` (`system.cpp:2091`,
  `PresentDisplay` at 5808). Qt's paused loop presents Big Picture itself (`qthost.cpp:1838-1840`).
- **The software renderer's thread** (`[GPU] UseThread`, on by default): `GPUBackend` (`gpu_backend.{h,cpp}`) with a
  4 MB single-producer, single-consumer ring of variable-size commands, a spin-then-sleep consumer, and `Sync()`
  through a semaphore (every frame, readbacks, states). Its ring and wake/sleep machinery are generic; its commands are
  PS1 primitives, not device work. `GPU_HW` can already run it beside the hardware renderer for readbacks
  (`m_sw_renderer`).
- **The hardware renderer** (`gpu_hw.cpp`) writes vertices straight into the device's mapped vertex and index buffers
  (`MapGPUBuffer`, 2013-2038) and draws in `FlushRender` (3824); `RestoreDeviceContext` (556) rebinds its state after
  anything else used the device.
- **Points that need the host GPU's answer now:** VRAM-to-CPU copies (GP0 C0h, `ReadVRAM`, 3344, unless the software
  readback renderer is on); save states (`DoState`, 474, the CLUT read back; memory, rewind and runahead states copy
  VRAM into host textures, `system.cpp:4798-4863`); save-state screenshots (`RenderScreenshotToBuffer`,
  `gpu.cpp:2724`) and other screenshots and dumps; a resolution or MSAA change (631); seeding the texture cache
  (307, 548); media capture (desktop).
- **Others using the device on the emulation thread:** ImGui, the OSD and Big Picture (textures made on demand, font
  atlas rebuilds, state previews, the loading screen's own present); achievements' badges; post-processing (both chains,
  in `GPU::RenderDisplay`, shader compiles, librashader chains through `GPUDevice::CreateSlangChain`); texture
  replacements composed with device draws mid-batch (`GetReplacementSource`). About thirty files call `g_gpu_device`
  directly.
- **Window and surface changes** run on the emulation thread: Android's `SurfaceChanged` blocks the UI thread until
  the emulation thread's next pump runs `UpdateDisplayWindow` or `ResizeDisplayWindow`
  (`android_host_input.cpp:72-84`); a destroyed surface makes the device surfaceless; Vulkan falls back to GLES at
  device creation on Android (`system.cpp:2390-2397`).
- **Vulkan's present thread** (`[GPU] ThreadedPresentation`, on by default on Android only): moves the frame's submit
  and present to a worker (`vulkan_device.cpp:1506-1546`); bypassed for explicit present. Other APIs ignore it.
- **Pacing today** (`UpdateSpeedLimiterState`, 3454; `FrameDone`, 2187-2247; `Throttle`, 2299):
  `Display/OptimalFramePacing`, `PreFrameSleep`, `VSync`, `SkipPresentingDuplicateFrames` and
  `Main/SyncToHostRefreshRate` are all off by default, on every platform (`settings.cpp:148, 294-299`; the UIs'
  defaults in `fullscreen_ui.cpp:3615`, `emulationsettingswidget.cpp:21`, `EmulationSettings.kt:26-32`). Syncing to the
  host applies only for a refresh rate within ±5% of the console's at normal speed, and with vsync turns the throttler
  off so FIFO paces the game. Optimal pacing on Vulkan and D3D12 splits present and submit around the throttle
  (`explicit_present`). Fast-forward with vsync uses mailbox, or FIFO with presents skipped to the host's rate.
  `Throttle` spins only with optimal pacing, on desktop other than Linux. No `VK_KHR_present_wait`/`present_id`, no
  D3D12 frame-latency waitable or `SetMaximumFrameLatency`, Metal's `maximumDrawableCount` unset, and nothing on
  Android asks for a frame rate (no `Surface.setFrameRate`, Choreographer or `EGL_ANDROID_presentation_time`).
- **Counters:** CPU % is the emulation thread's CPU time over wall time; the software renderer's thread is counted
  apart; GPU % is the accumulated GPU time per present; the pre-frame sleep's budget comes from the same place.

## Goals

### The GPU thread (stage 1)

- **The hardware renderer's host work runs on a GPU thread** *(the player's choice: every hardware renderer, Vulkan,
  OpenGL, OpenGL ES, D3D11, D3D12, Metal; desktop, Qt and Big Picture alike, and Android)*: recording and submitting
  draws, VRAM uploads, fills and copies, post-processing (both chains, slang stages included), the OSD, ImGui and Big
  Picture drawing, and presenting. The emulation thread emulates and hands its work over through a command stream
  *(builder's design)*.
- **The device belongs to the GPU thread** *(writer's call)*: created, recreated (renderer or resolution changes, the
  Vulkan-to-GLES fallback), resized, made surfaceless and destroyed there, so a GL context is current on one thread
  only. What the UI and the emulation thread need from it (window size, refresh rate, texture handles for the OSD and
  Big Picture) reaches them through the thread, never by calling the device from elsewhere *(builder's call how)*.
- **At most one frame ahead** *(the player's choice)*: the emulation thread never gets more than one frame's work
  ahead of what's been presented, so input lag at the defaults is no worse than today.
- **Points that need the host GPU's answer** (the list above) wait for the GPU thread to reach them and answer, and
  are otherwise unchanged in what they return *(writer's call)*. Readbacks that games use (GP0 C0h) keep working, with
  the software readback renderer as today's faster option.
- **Vulkan's present thread on Android is folded into the GPU thread** *(writer's guess, not contradicted)*: with the
  GPU thread on, `ThreadedPresentation` has nothing left to do; with it off, today's behaviour stays.
- **The software renderer keeps its own thread** as it is *(writer's guess, not contradicted)*. The device still
  belongs to the GPU thread whatever the renderer *(writer's call)*: with the software renderer, its display upload,
  post-processing, the OSD and presenting move to the GPU thread too, so who owns the device never depends on the
  renderer or changes when it's switched mid-game.
- **Android's surface changes** go to the GPU thread without the UI thread waiting longer than it does today
  *(writer's call)*: pausing, resuming, rotating, switching apps, a new or destroyed surface.
- **A switch, on by default** *(the player's choice)*: "Use a GPU thread" in the advanced part of the graphics settings
  on Qt, Big Picture and Android (a new `[GPU]` key *(builder's call on the name)*); off runs everything on the
  emulation thread as today.
- **A renderer that comes out slower** with the thread on than today, in the before-and-after checks, **ships with it
  off by default** *(the player's choice)*: the switch still turns it on, the PR says which and why, and the other
  renderers don't wait for it.
- **The counters follow** *(writer's call)*: the per-second line reports the GPU thread's CPU use beside the emulation
  thread's (as the software renderer's thread is), GPU % stays the GPU's busy time, and the pre-frame sleep's budget
  allows for the thread. OpenGL ES's broken GPU figure on Adreno is fixed, or that figure hidden where the queries
  can't be trusted *(builder's call)*.

### Pacing (stage 2)

- **Even frame times by default** *(the player's choice: on by default)*: optimal frame pacing becomes the default
  wherever it's safe *(builder's call where, by measurement)*, and the throttle's wait is accurate on Android (it
  oversleeps today).
- **Who paces** *(writer's call)*: the emulation thread throttles, which spaces frames evenly ("optimal frame pacing"
  keeps its meaning); the GPU thread presents each frame as it arrives ("presenting between frames"), and where the API
  has a present wait, that wait is what holds the emulation thread to the screen.
- **With the GPU thread off** *(writer's call)*, pacing works the same: the throttle and the present waits are shared,
  and only the hand-off differs.
- **Locked to the screen's refresh rate** *(the player's choice)*: GooseStation asks for the refresh rate that suits
  the running game and locks the game's speed to it, so each frame lands on a refresh: on Android through the
  platform's frame-rate request for its surface *(builder's call which API)*, on desktop wherever the display allows.
  NTSC games (about 59.83 Hz) run at 60, or at 120 with each frame shown twice; refresh rates that are a multiple of the
  console's count, not only those within ±5% as today. PAL games (50 Hz) can't line up with a 60 or 120 Hz screen and
  get the most even spread there is *(writer's call)*. The player's choice of screen rate in Android's settings is
  asked for, not overridden; if the system refuses, pacing follows what the screen does.
- **Where it's on by default** *(the player's choice: only where it adds no lag)*: on Android, always (the screen syncs
  anyway). On desktop, vsync and the lock are on by default only where a present wait keeps at most one frame queued
  (Vulkan's `present_wait`, D3D12's waitable object, Metal's equivalent if it has one); elsewhere desktop keeps vsync
  off by default, as today. "No added lag" is measured against each platform's default today.
- **Vsync off while fast-forwarding** *(writer's call, from the roadmap)*, so fast-forward isn't held to the screen,
  and back on after.
- **Lower-latency present waits where the API has them** *(from the roadmap)*: Vulkan's `VK_KHR_present_wait` and
  `present_id` where the driver offers them, a D3D12 frame-latency waitable object with a maximum latency of one, and
  the equivalents the builder finds for Metal and the others; nothing changes where an API has none.
- **The existing pacing settings stay** *(the player's choice)*: Display's optimal frame pacing, vsync, pre-frame sleep,
  skip duplicate frames and sync to host refresh rate, in the advanced part of the display settings on Qt, Big Picture
  and Android, with the new defaults *(builder's call on any new row the refresh-rate lock needs)*.

## Success

On the Thor's own screen, not an offscreen surface *(the player's choice: games hold full speed where they drop
today; the measure agreed in conversation; the numbers and the split by stage are the writer's calls)*:

- **Stage 1:** the heavy OpenGL ES case (Vagrant Story, 8×, 4× MSAA, CRT-Lottes) holds 60 (at least 59.8 VPS in every
  second of a 30-second run); fast-forward (unlimited speed) runs at least 20% faster at 1× and 3×, where the
  emulation thread is the limit today; input lag no worse (measured, not felt, *(builder's call how)*).
- **Stage 2:** at normal speed on a 60 Hz screen, the slowest frame is at most 17.5 ms in 95% of seconds, from 19–28 ms
  today; at 120 Hz, each NTSC frame shown for two refreshes; input lag no worse.
- **Both stages**, before and after, on the Mac (Metal, MoltenVK, OpenGL) and the user's Windows machine (D3D11,
  D3D12, Vulkan, OpenGL): no renderer slower with the defaults than today (one that is gets the GPU thread off by
  default, above), and nothing drawn differently.

## Non-goals

- The software renderer's thread.
- Multi-threaded pipeline compilation (item 23), the audio stream rework (item 24, though SoundTouch's 8–10% of the
  emulation thread was seen here), faster loading (item 25).
- More frames ahead for smoothness (a setting for one or two frames of lag was offered and declined).
- Frame generation, interpolation or a variable refresh rate mode.

## Anti-goals

*(The player's choice: any of these makes it a failure, however smooth.)*

- **Added input lag at the default settings.**
- **Anything drawn differently from today**, on any renderer, with the GPU thread on or off (texture replacements,
  post-processing, the OSD, Big Picture, screenshots and state previews included).
- **A crash or hang around pausing, resuming, rotating or switching apps on Android**, or on desktop around
  fullscreen, resizing, changing renderer or resolution, and stopping a game.
- Anything from DuckStation's code after `a5bf1ba5cee7`.

## Constraints

- **Clean room**: nothing from DuckStation after `a5bf1ba5cee7`, whose own GPU thread and pacing rewrite (December 2024
  on) came after it; its commit titles and user documentation only. **PCSX2's GS thread (MTGS) for ideas only**
  *(the player's choice)*: how it queues work, limits how far ahead it runs and handles readbacks, from the user's
  PCSX2 fork (https://github.com/ilyalissoboi/pcsx2, GPL-3.0); GooseStation's own code, fitted to it, and no PCSX2 code
  copied.
- **Stages, each its own plan and PR** *(the player's choice)*: (1) the GPU thread on every renderer, desktop and
  Android, with its switch; (2) pacing on it. `docs/roadmap.md` (items 21 and 22) is updated with each.
- **Tests**: native tests for what needs no GPU (the command stream, the frames-ahead limit, the pacing maths and the
  refresh-rate choice) in `src/common-tests`; the renderer tests where they reach; the frame-for-frame comparisons the
  texture cache tests use (`FrameReader`), with the GPU thread off and on, on the emulators; Android's quick tier while
  building and the full tier before each PR.
- **The Thor** *(the player's choice)*: the building session may run measurements there whenever it's connected (adb
  `64dc3c35`), with the `.debug` app and scratch data in `/sdcard/GooseTest` only, never the release app (the plain
  ID, never uninstalled) or the user's data folder, cleaned up afterwards (the test APK uninstalled, `/sdcard/GooseTest`
  and the app's cache removed). The measurement test stays out of the repository. Frame times on the real screen need a
  way to read presents there *(builder's call: for example the system's per-layer present timestamps)*. Anything
  hands-on (feel, input lag, playing) is the user's check.
- **Windows** over SSH on the user's machine (the builder asks for its connection the first time); Linux by CI and a
  later check by hand; the Mac for Metal, MoltenVK and OpenGL.
- The user reviews every commit message.

## Left to the builder

The GPU thread's design (its command stream, how device work and the UI's device use reach it, how readbacks wait),
how the emulation thread is held to one frame ahead, which APIs pace how, the refresh-rate request on each platform,
the settings keys, the way frame times are read on the Thor's screen, and the order within each stage.

## Stage 2, revised (2026-11-22)

Measured again once stage 1 had landed, on the Thor's own screen (60 Hz, as the user has it): SurfaceFlinger's
present times for the game's surface (`dumpsys SurfaceFlinger --latency`), beside the core's per-second line.

- **The screen is already even.** In every case measured (Vulkan 1× and 3×, OpenGL ES 1× and the heavy 8×, 4× MSAA,
  CRT-Lottes case), every frame was shown for exactly one refresh, in all 30 seconds. The 19–28 ms above were the
  emulation thread's own frame intervals, measured offscreen: Mailbox (3 images, vsync off) absorbs them.
- **The price is lag.** A frame reaches the screen 36–37 ms after the GPU finishes it (median; 22 ms in the heavy
  case), because it lands at a random point of the refresh and waits for SurfaceFlinger's next latch.
- Today's settings: vsync (FIFO) adds about 20 ms of lag and no evenness; optimal frame pacing with pre-frame sleep
  takes 7 ms off on Vulkan (29.3 ms) but adds 16 ms on OpenGL ES, where the throttle's sleep falls between the input
  poll and the present (no explicit present there).
- The Thor's Vulkan driver has no `VK_KHR_present_wait`; it has `VK_GOOGLE_display_timing`. Android's Choreographer
  gives every refresh's deadline and expected present time (API 33; the refresh time and period from API 29).

So **stage 2 lowers the lag and keeps the screen even** *(the player's choice, 2026-11-22)*, and is split *(writer's
call, pending the player's yes)*:

- **2a, Android:** the emulation thread's frames locked to the screen's refresh timeline — it sleeps before a frame,
  not between the input poll and the present, so a frame is presented as soon as it's done, timed to land just before
  SurfaceFlinger's deadline; the game's speed locked to the refresh rate when it's a multiple of the console's (60, or
  120 with each frame shown twice); the console's frame rate asked of the platform for the surface; vsync off while
  fast-forwarding.
- **2b, desktop:** the present waits (Vulkan `present_wait`, D3D12 and D3D11 frame-latency waitables, Metal's
  presented handlers) and vsync on by default only where one keeps at most a frame queued — checked by hand on Windows,
  where SSH can't present.

**Success for 2a**, on the Thor's own screen at the defaults: still at least 95% of seconds with every frame shown for
one refresh (today 100%); input lag (the core's input poll to on screen) at least 10 ms lower than today on Vulkan and
OpenGL ES at 1×, and no higher in the heavy case; at 120 Hz, each NTSC frame shown for two refreshes; PAL games as
even as today; nothing drawn differently, and the Mac and Windows unchanged.

## Stage 2b, decided (2026-11-23)

Desktop present waits. Decided with the player on 2026-11-23; calls made while planning are marked *(builder's
call)*.

- **What the drivers have.**
  - The Windows machine is an RTX 4090 at 59 Hz, where Vulkan should have `VK_KHR_present_wait`.
  - D3D12 always presents on the flip model; D3D11 does unless `[Display] UseBlitSwapChain` is set, or the flip model
    failed to start.
  - Metal has presented handlers.
  - **MoltenVK has no `present_wait`**, only `VK_GOOGLE_display_timing`, which reports after the fact. **OpenGL has no
    present wait** on any platform. So on these two, vsync stays off by default.
- **The wait.** It runs where presents end, in `HandlePresent`, before the frame counts as presented, so the emulation
  thread reads input for the next frame only once it returns. It runs only while presents block on the screen
  (FIFO): the GPU thread on and off alike, and the same in every API.
- **What it waits for** *(the player's choice: adaptive)*:
  - The frame just presented is on screen before input is read for the next one (DXGI's maximum latency of 1).
  - While frames keep missing their refresh, it waits for the frame before instead (latency 2), and tries the lower
    one again later.
  - It judges from the times its own waits return. The rule is pure and tested natively.
- **Vsync becomes three-way: Automatic, On, Off** *(the player's choice)*, in `[Display] VSyncMode`, with Automatic the
  default.
  - **Automatic:** vsync on where the device has a present wait, off elsewhere. On Android, Automatic is off, as
    today: the screen lock (2a) paces there.
  - A file without the key reads `[Display] VSync = true` as On, and anything else as Automatic. `false` was the
    old default written everywhere, so it says nothing about the player's choice.
  - Qt, Big Picture and Android show the three choices.
- **Automatic syncs to the host too** *(the player's choice)*, unless the player turns sync to host off.
  - Desktop files store sync to host as `false`, the old default, so the desktop default becomes on, and existing
    desktop files have it turned on once *(builder's call, as 2a did on Android)*.
  - The "Sync to host" row stays; turning it off keeps Automatic's vsync without the sync.
- **The screen lock on desktop too** *(the player's choice, 2026-11-23)*.
  - **Why:** vsync with a wait alone reads input just after a refresh and shows the frame at the next one. That's
    about a refresh from input to screen, against about half a refresh with vsync off: roughly 8 ms more at 60 Hz.
  - **How:** where a present wait exists, the presents' own times give a refresh timeline (Metal's presented times;
    the times the Vulkan and DXGI waits return). 2a's lock runs from it: it sleeps before the frame, so the frame
    lands just before its refresh.
  - **Reused from 2a:** `NextWake`, the frame-time predictor and the cutoff tracker. The tracker learns where each
    screen really takes frames.
  - **Where:** while locked, vsync is FIFO whatever the screen's rate, so 120 Hz desktop screens lock at two refreshes
    a frame. MoltenVK and OpenGL get no timeline, so they're unchanged.
- **Success for 2b:**
  - On the Mac (Metal), measured from the presented handlers at the defaults: lag from input poll to screen no higher
    than the base branch's default (vsync off). Every frame shown for its refreshes (one at 60 Hz, two at 120) in at
    least 95% of seconds.
  - Mac and Windows, every renderer: nothing drawn differently (regtest frames), and fast-forward no slower.
  - MoltenVK and OpenGL unchanged at their defaults.
  - Windows' on-screen check (D3D11, D3D12, Vulkan) is the player's own.
