<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# The audio stream, stage 2 (keeping up) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stretching runs on an audio thread of its own rather than the emulation thread. The buffer grows while
dropouts keep happening and shrinks back afterwards. Fast-forward fades to silence past a speed the stretching can't
follow.

**Architecture:**
- **Three threads, two rings.** The emulation thread's `EndWrite` copies each 64-frame chunk, raw 44.1 kHz stereo,
  into a new *input ring* and wakes the audio thread. The audio thread ("Audio Stretch") runs everything that
  `EndWrite` ran after the staging chunk: s16 to float, surround on desktop, SoundTouch, tempo control, and the
  overflow fade. It writes into the existing *output ring*. The callback keeps stage 1's envelope unchanged. Each ring
  has one producer and one consumer.
- **A clear crosses both rings.** `EmptyBuffer()` on the emulation thread publishes `{generation, input write
  position}` on the input ring. When the audio thread reaches the request, it drops the input before that position,
  clears its own state, and publishes the same generation on the output ring, where stage 1's consumer applies it and
  acknowledges. `SetPaused` waits on that acknowledgement, as it does now.
- **Tempo and growth are the audio thread's.** The fill it controls is input plus output, so the input ring's few
  milliseconds don't add lag. The target grows by 25 ms at each underrun, up to 100 ms, and shrinks by 10 ms after
  each 5 s without one.
- **Fast-forward's sound.** The audio thread estimates speed as input frames over the callback's frames, over the
  last 0.5 s of callbacks. Above `FAST_FORWARD_MUTE_SPEED` it clears the output once and drops input. Below
  `FAST_FORWARD_RESUME_SPEED` it writes again, and the consumer fades in.

**Tech Stack:** C++20 (`src/util/audio_stream.*`, `src/core/system.cpp`); `std::thread`, `std::condition_variable`;
SoundTouch 2.3.3 (unchanged); gtest (`src/common-tests`, also `run-native-tests.sh` on an emulator);
ThreadSanitizer; simpleperf on the Thor.

**Spec:** `docs/superpowers/specs/2026-11-24-audio-stream-design.md` (Stage 2, Success, Anti-goals, Constraints).
Decided with the user on 2026-11-25:
- **Stretching runs on its own thread.**
- **The device's own rate is dropped**, and the PR says why. On the Thor, AAudio stays in its normal mode at 48 kHz
  as at 44.1 (performance mode NONE, 960-frame bursts, 115–123 ms device latency). The likely cause is AYN's settings
  app (`com.odin.settings`), which attaches a global NXP equalizer to the output mix whenever playback starts. So the
  device rate brings no gain there, and SoundTouch's cubic conversion would replace Android's own resampler.

## Global Constraints

- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7`. PCSX2 for ideas only.
- **Anti-goals:** no worse sound in ordinary play (no new warbling); no more sound-to-picture lag in ordinary play;
  fades never swallow sounds; no crash, hang or lasting silence after a device change or a pause and resume.
- **Stage 1 stays as it is:** its fades, clears, pause, counters and the 25 tests keep passing through the new thread.
- **Settings:** the `[Audio]` keys stay. No new row; the thread is always on.
- **Desktop and Android.** Thor: adb `64dc3c35`, the `.debug` app and `/sdcard/GooseTest` only. Clean up afterwards.
- **Android tiers** run from a detached worktree: quick while building, full before the PR.
  `run-native-tests.sh` needs `ANDROID_SERIAL=emulator-5580` and `ANDROID_NDK_HOME`.
- **Windows:** `pcsx2-win`, a bundle and a scratch worktree, as in stage 1.
- **Commits:** one per task, the message shown after each, ending
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Fixed values (the builder's calls)

| Name (in `AudioStream`) | Value | Why |
|---|---|---|
| `INPUT_RING_MS` | 500 | Ample cover for an audio thread that stalls. Full counts as an overflow and clears the stream. |
| `WORKER_WAKE_FRAMES` | 128 (2.9 ms) | Wakes about 345 times a second, so the input waits about 1.5 ms on average. |
| `WORKER_WAIT_MS` | 5 | A timed wait, so a missed wake costs at most 5 ms. |
| `GROW_STEP_MS` / `MAX_EXTRA_MS` | 25 / 100 | The design's "about 100 ms beyond the usual buffer". |
| `SHRINK_STEP_MS` / `SHRINK_AFTER_MS` | 10 / 5000 | Back to the usual buffer about 50 s after the last dropout, in steps the tempo's hysteresis absorbs. |
| `FAST_FORWARD_MUTE_SPEED` / `FAST_FORWARD_RESUME_SPEED` | 4.0 / 3.5 | Past about 4× SoundTouch's output is choppy. Retuned only by the user's listening. |

## Interfaces (`src/util/audio_stream.h`)

```cpp
void SyncWorker();                         // tests: blocks until the audio thread has consumed all input
struct Counters { ...; u32 extra_ms = 0; }; // the buffer's current growth (a level, not reset)
```

- `EndWrite` (emulation thread) only copies a chunk into the input ring.
- `InternalWriteFrames`, `StretchWriteBlock`, `UpdateStretchTempo` and the producer half of `EmptyBuffer` move to the
  audio thread, as `WorkerProcess*` / `WorkerClear`.
- `SetNominalRate` stores an atomic float, which the audio thread applies.
- The null stream starts no thread.

## Review Focus

1. **Stream teardown with the thread mid-chunk** (settings change, shutdown, AAudio reopen): the thread is joined
   before the rings are freed, with no hang if it is waiting. Pinned: `AudioStream.DestroyWhileWorkingIsClean`
   (Task 2, under TSan).
2. **A pause while the thread is asleep, or while it is busy with a backlog:** the clear still reaches the callback
   within the timeout, and nothing from before the pause plays after it. Pinned: stage 1's pause tests running
   through the thread, plus `AudioStream.PauseWithABacklogDropsIt` (Task 2).
3. **The thread starved** (the Thor's little cores, a long stall): input overflow becomes a faded clear, never a
   splice, and the stream recovers. Pinned: `AudioStream.InputOverflowClears` (Task 2).
4. **Growth stays bounded and comes back down,** and never touches the target in Off or Resample modes. Pinned:
   `AudioStream.GrowthIsCappedAndShrinks` and `AudioStream.NoGrowthWithoutTimeStretch` (Task 3).
5. **Speed estimates while paused or muted:** frames that weren't asked for never count as speed. Pinned:
   `AudioStream.MutedStreamNeverFastForwardMutes` (Task 4).

---

### Task 1: Stage 2 before-numbers (scratch, no commit)

**Files:** none in the repo. Scratch: `/tmp/ga2-base` (master `40de280f2`, plus a `GOOSE2` per-second line with the
mean ring fill, AAudio's latency (frames written minus presented), xruns, rate, performance mode, burst and buffer
size). The Thor's measurement test and `sum2.py`. Notes: `audio-stage2-baseline.md` in the shared notes folder.

- [x] **Step 1: Thor profile:** simpleperf on the emulation thread in ordinary play (Vulkan 3×):
  `StretchWriteBlock` and its children take 12.8%, nearly all of it SoundTouch's overlap search.
- [x] **Step 2: Thor device probe.** The track is a normal (mixed) one at both 44.1 and 48 kHz, and the device rate
  is dropped (above).
- [x] **Step 3: Thor scenarios at master:** Vulkan 3× (all scenarios) and OpenGL ES 8× heavy (play, states, ff), two
  rounds. Recorded: U/F/C/X, the mean fill and the device latency per scenario.
- [x] **Step 4:** The Mac and Windows before-numbers are stage 1's final runs, at the same master. Write all of this
  in the notes.

### Task 2: The audio thread, the input ring, and clears through it

One task: until clears cross the thread, `EmptyBuffer` would touch SoundTouch from the emulation thread while the
audio thread uses it.

**Files:** `src/util/audio_stream.h/.cpp` (`EndWrite` 881, `StretchWriteBlock` 976, `InternalWriteFrames` 533,
`EmptyBuffer` 694, `SetPaused` 735, `AllocateBuffer` 636, `DestroyBuffer` 681, `CreateNullStream` 63, the destructor
57); tests.

**Interfaces:**
- Produces `SyncWorker()`, the input ring (`m_input`, `m_input_rpos`, `m_input_wpos`), the thread (`m_worker`, its
  condition variable, `m_worker_shutdown`, a test hold `m_worker_hold`) and `m_input_clear_request`
  (`std::atomic<u64>`: generation and input position).
- The audio thread publishes the same generation on stage 1's `m_clear_request`. `m_cleared_generation` and
  `SetPaused` stay as stage 1 has them.
- The test helper `Write()` calls `SyncWorker()` after its writes, so every stage 1 test runs through the thread.

- [ ] **Step 1: Failing tests.**
  - `EmulationThreadOnlyCopies`: TimeStretch, after `Write` and before `SyncWorker`, the output ring is unchanged
    and the input ring holds the frames. After `SyncWorker`, they're in the output ring.
  - `DestroyWhileWorkingIsClean`: a stream destroyed while a producer and a callback thread run; no crash, and no
    hang over 1 s.
  - `PauseWithABacklogDropsIt`: the thread is held with 300 ms queued. A pause, then a resume and a new signal: none
    of the backlog plays.
  - `InputOverflowClears`: the thread is held while 600 ms is written. The overflow counts once, and once released
    the output is a fade-out, silence and the newest audio fading in, with `MaxStep ≤ 300`.
  - `ClearGenerationsMatchAcrossRings`: ten clears in a row are each acknowledged with their own generation.
  - The 25 stage 1 tests, unchanged but for `Write`'s `SyncWorker()`.
- [ ] **Step 2: Run** `build2/bin/common-tests --gtest_filter='AudioStream.*'`. Expected: build failure (no
  `SyncWorker`).
- [ ] **Step 3: Implement.**
  - `EndWrite` copies to the input ring. When the ring is full, it counts an overflow and calls `EmptyBuffer()`.
  - `EmptyBuffer` (emulation thread) resets the staging chunk, publishes the input request and wakes the thread.
  - The thread waits on the condition variable (for `WORKER_WAKE_FRAMES` or `WORKER_WAIT_MS`). It processes every
    whole chunk as `EndWrite` did, and computes the tempo from input plus output fill.
  - On a new input generation, the thread drops input up to the position, then does stage 1's producer clear under
    the same generation.
  - `SetNominalRate` becomes atomic. `SetPaused`'s nothing-to-fade check also needs the input ring empty.
  - The destructor and `DestroyBuffer` stop and join the thread first. It is named "Audio Stretch".
- [ ] **Step 4: Run** the filter, all of common-tests, ThreadSanitizer's `AudioStream.*` five times, and the Mac
  regtest smoke run. Expected: PASS, with no reports.
- [ ] **Step 5: Commit** (`Audio: stretch on an audio thread of its own`).

### Task 3: The buffer grows while dropouts repeat

**Files:** `src/util/audio_stream.h/.cpp` (`UpdateStretchTempo` 1020, the restart level in `ReadFrames`); tests.

**Interfaces:** `std::atomic<u32> m_extra_target_frames` (written by the thread, read by the callback for the restart
level), `std::atomic<u32> m_underrun_total` (the callback's running count), and `Counters::extra_ms`.

- [ ] **Step 1: Failing tests.**
  - `BufferGrowsWhileUnderrunsRepeat`: TimeStretch, the producer at 0.7× for 10 s of output, so underruns repeat.
    `extra_ms` reaches 100 and no more, and underruns become rarer as it grows.
  - `GrowthIsCappedAndShrinks`: from +100 at 1×, it is back to 0 after about 50 s of output, in steps of at most
    10 ms.
  - `NoGrowthWithoutTimeStretch`: Off and Resample keep `extra_ms` at 0.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** the rule from the Architecture, with time counted in output frames from the callback (no
  wall clock). The target used is the target plus the extra. The restart level is half of that, within stage 1's cap.
- [ ] **Step 4: Run** the filter, common-tests and TSan. Expected: PASS.
- [ ] **Step 5: Commit** (`Audio: grow the buffer while dropouts repeat`).

### Task 4: Fast-forward past what stretching can follow fades to silence

**Files:** `src/util/audio_stream.h/.cpp` (the thread's loop); tests.

**Interfaces:** `std::atomic<u64> m_callback_frames` (the frames the callback asked for, its running total),
`bool m_speed_muted` (the thread's), and a `fast_forward_mutes` count in `Counters`.

- [ ] **Step 1: Failing tests.**
  - `VeryFastInputFadesToSilence`: TimeStretch, input at 6× the callback's frames for 2 s. It fades out once,
    `fast_forward_mutes 1`, then silence with no underruns counted. Back at 1×, it fades in within 0.5 s.
  - `ModerateFastForwardKeepsPlaying`: input at 3×: audible all the way, no mute.
  - `MutedStreamNeverFastForwardMutes`: volume 0, and no callbacks (the device paused), with input at 6×: no mute
    and no count.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement:**
  - The speed is the input frames over `m_callback_frames`, both counted over the last 0.5 s of callback frames.
  - The mute and resume speeds have hysteresis between them.
  - Muting is an output clear; while muted, input is dropped.
  - The speed is only judged while callbacks run, and never while the volume is 0.
- [ ] **Step 4: Run** the filter, common-tests and TSan. Expected: PASS.
- [ ] **Step 5: Commit** (`Audio: fade fast-forward out past what stretching can follow`).

### Task 5: The growth and mutes in the statistics

TDD skipped: format strings. The counts are tested in Tasks 3–4.

**Files:** `src/core/system.cpp` (the per-second line, `FormatLatencyStats`).

- [ ] **Step 1:** The per-second line's tail becomes `Audio: U 0 O 0 F 1 C 1 X 0 +25ms M 0`, with `+` the growth
  and `M` the fast-forward mutes.
- [ ] **Step 2:** The latency statistics become `AL: 70ms (+25, U 0, O 0, F 1)`.
- [ ] **Step 3:** Build, and check the Mac regtest log and one Thor frame.
- [ ] **Step 4: Commit** (`Core: the audio buffer's growth and fast-forward mutes in the statistics`).

### Task 6: After-numbers, review and the tiers

- [ ] **Step 1: Thor:** Task 1's scenarios, interleaved with the master build, two rounds each. Success (the design's
  stage 2):
  - the emulation thread's audio share at 3% or less (simpleperf, ordinary play);
  - heavy fast-forward with at most one faded gap at each switch;
  - six heavy state loads with at most one faded gap each;
  - the mean fill plus device latency in ordinary play no higher than master's;
  - `X 0`.
- [ ] **Step 2: Mac and Windows:** stage 1's scenarios, no worse than stage 1's final numbers.
- [ ] **Step 3:** The final whole-branch review (a fresh reviewer), its fix pass, then quick and full tiers, CI's
  Gradle line, `run-native-tests.sh`, and TSan on common-tests and regtest.
- [ ] **Step 4:** Clean up the Thor, Windows and the scratch worktrees.

### Task 7: Roadmap and PR

- [ ] **Step 1:** Roadmap item 24: stage 2 done, with the numbers, and the device-rate finding.
- [ ] **Step 2: Commit** (`Docs: audio stream stage 2 on the roadmap`), push, and open the PR. The body has
  before-and-after numbers and the device-rate finding. The listening checks are left to the user: ordinary play
  unchanged, fast-forward's sound and its mute speed, and heavy scenes.
