<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# The audio stream, stage 1 (no more clicks) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every gap in the output is faded rather than cut, a jump drops the stale audio, overflows are dropped from
the producer's side, and the stream counts its underruns, overflows and fades where the per-second line and the
on-screen latency statistics show them.

**Architecture:**
- **One gain envelope on the consumer** (`ReadFrames`, the backend's callback thread). The stream is in one of three
  states: *filling* (silent, nothing consumed, until the restart level is buffered), *playing* (fading in, then full),
  or *fading out* (to a gap). The fade is a raised cosine over 4 ms (176 frames at 44.1 kHz). Running out of data in
  the middle of a fade-out holds the last frame played, so a fade-out always has its full length.
- **Jumps are a request, not a write.** `EmptyBuffer()` (emulation thread) records "everything before this write
  position is stale" in one 64-bit atomic (a generation and a position). It also clears the producer's own state:
  SoundTouch, the staging chunk, surround and the tempo averaging. The consumer sees the request at its next
  callback, fades out over the stale frames, moves its own read position to the recorded one, and acknowledges. The
  emulation thread never writes `m_rpos`.
- **Overflows on the producer.** Near full, the producer fades out the frames it writes. It then drops frames until
  the ring has drained to half, and fades back in. `StretchOverrun`'s write to `m_rpos` goes. The stretcher's reset
  count becomes atomic.
- **Pause fades first.** The base `SetPaused` empties the stream with a fade and waits (bounded) for the
  acknowledgement, then calls the backend's new `PauseDevice`. Nothing queued before a pause plays after it.
- **System calls `EmptyBuffer()` at each jump:** state load, rewind start, reset, fast-forward or turbo switched off,
  and before a stream is destroyed (settings change, shutdown, via `SetPaused(true)`).

**Tech Stack:** C++20 (`src/util/audio_stream.*`, the AAudio, Cubeb and SDL backends, `src/core/system.cpp`,
`spu.cpp`); gtest (`src/common-tests`, also run on Android by `android/scripts/run-native-tests.sh`); ThreadSanitizer
(Mac clang); regtest; the Android instrumented tiers; the Thor.

**Spec:** `docs/superpowers/specs/2026-11-24-audio-stream-design.md` (Stage 1, Success, Anti-goals, Constraints).

## Global Constraints

- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7`, whose audio rework came after it (its commit titles
  and user docs only). PCSX2 (https://github.com/ilyalissoboi/pcsx2) for ideas only, none copied.
- **Anti-goals:** no worse sound in ordinary play; no more sound-to-picture lag in ordinary play; fades never swallow
  sounds that should be heard; no crash, hang or lasting silence after a device change or a pause and resume.
- **Stage 2 is left alone:** no growing buffer, no stretching off the emulation thread, no device rate, no new
  fast-forward sound. SoundTouch's tempo control (`UpdateStretchTempo`) is unchanged apart from the atomic reset count.
- **Settings:** the `[Audio]` keys and meanings stay. This stage adds none.
- **Desktop and Android.** Thor: adb `64dc3c35`, the `.debug` app and `/sdcard/GooseTest` only. Never the release
  app (plain ID, never uninstalled) or the user's data folder. Clean up afterwards. The measurement test stays out of
  the repository.
- **Android tiers** run from a detached worktree (copy `android/local.properties` and the emusync AAR in): quick
  while building, full before the PR. Read the XML results.
- **Commits:** one per task, the message shown after each, ending
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. The user reviews every message.

## Fixed values (the builder's calls, from the design)

| Name (in `AudioStream`) | Value | Why |
|---|---|---|
| `FADE_MS` | 4 ms; raised cosine `g(k) = 0.5 − 0.5·cos(π·k/N)` | Long enough not to click, short enough to swallow nothing audible. Retuned only by the user's listening. |
| `VOLUME_RAMP_MS` | 10 ms for a full-scale change, linear | A volume step a callback becomes a ramp. |
| restart level | `target / 2` (25 ms desktop, 50 ms Android), clamped to `[CHUNK_SIZE, size − high-water − 2·CHUNK_SIZE]` | Today's 2–4 ms restarts stop again at once. In ordinary play the stretcher's target sets the fill, so lag is unchanged. |
| overflow high-water | `fade frames + 2·CHUNK_SIZE` free | Room for a whole fade-out. |
| overflow resume | the ring drained to `size / 2` | Hysteresis. |
| `PAUSE_FADE_TIMEOUT_MS` | 100 | Covers one fade plus a 960-frame callback with room; a stream with no callbacks costs at most this. |

## Interfaces (`src/util/audio_stream.h`)

```cpp
struct Counters { u32 underruns, overflows, fades, clears, cuts; };  // AudioStream::Counters
Counters GetAndResetCounters();   // any thread; relaxed exchanges
void EmptyBuffer();               // emulation thread: request a faded clear (reworked, today unused)
void SetPaused(bool paused);      // no longer virtual: fade, wait, then PauseDevice()
void SetOutputVolume(u32 volume); // now ramps; m_volume becomes std::atomic<u32>
protected:
virtual void PauseDevice(bool paused);  // backends override (was their SetPaused); sets m_paused
void ReadFrames(SampleType* samples, u32 num_frames);  // unchanged signature
```

- `underruns`: gaps from running out (playing → fading out). Not counted while the volume is 0 (the producer
  writes nothing when muted).
- `overflows`: producer overflow events (not frames).
- `fades`: fade-outs started, for any cause.
- `clears`: `EmptyBuffer()` calls applied.
- `cuts`: fade-outs that couldn't finish (a pause or teardown whose wait timed out). Meant to be 0.

## Review Focus

1. **Small and large buffers in each stretch mode** (`BufferMS` 10…500; Off, Resample, TimeStretch): the restart
   level is always reachable, so the stream never sticks in filling. Pinned: `AudioStream.TinyBufferStillPlays`
   (Task 2), `AudioStream.OverflowResumesInEveryMode` (Task 5).
2. **Mute and back** (volume 0, the fast-forward volume, the volume hotkeys): the producer stops writing at 0, which
   counts no underrun. Back at 100, the stream fades in, with no lasting silence. Pinned:
   `AudioStream.MuteAndUnmuteFadesBackIn` (Task 3).
3. **A pause with no callbacks running** (a device gone, AAudio's reopen failed, the backend already stopped):
   `SetPaused(true)` returns within the timeout, counts a cut, and resuming plays again. Pinned:
   `AudioStream.PauseWithNoConsumerReturnsAfterTimeout` (Task 6).
4. **A clear racing the consumer** (the callback already read past the clear point): old audio is never played
   twice, and the read position never moves backwards. Pinned: `AudioStream.ConcurrentClearsNeverReplayOldAudio`
   (Task 4, under ThreadSanitizer in Task 8).
5. **Jumps in a row** (state loads spammed, fast-forward tapped, rewind held): one fade-out, then the stream recovers.
   The silence doesn't grow, and clears never stack up. Pinned: `AudioStream.RepeatedClearsRecover` (Task 4).

---

### Task 1: Desktop before-numbers (scratch, no commit)

The Thor's before-numbers are the design's table, measured at `79f7e79fe`, this branch's base. Desktop has none yet.

**Files:** none in the repo. Throwaway: `/tmp/goose-audio-desktop.patch` (`/tmp/goose-audio`'s counters plus a
regtest scenario driver), notes in `audio-stage1-baseline.md` in the shared notes folder.

- [ ] **Step 1: The driver.** In a scratch copy of `regtest_host.cpp`, a per-frame hook runs one scenario from an
  environment variable. The scenarios are the Thor's: `play` (state 1, 20 s), `states` (six loads 4 s apart),
  `ff` (on 2 s / off 3 s, five times), `intro` (fresh boot, 60 s), plus `pause` (pause 1 s / run 3 s, five times)
  and `reset` (three resets 5 s apart). Run with `-setting Main/EmulationSpeed=1 -setting Audio/Backend=Cubeb` (or
  `SDL`), with Vagrant Story and its states from `~/work/goose-test-data`.
- [ ] **Step 2: Mac at the base** (`79f7e79fe` + the patch, `build-audio-base`): every scenario on Cubeb and SDL,
  two rounds. Record underruns, overflow skips and drops, refill waits, and seconds with an underrun.
- [ ] **Step 3: Windows at the base.** Same patch, Cubeb (WASAPI) and SDL, over SSH. The connection is confirmed with
  the user first (the pacing plans used `pcsx2-win`, `E:\work\goosestation`, MSBuild).
- [ ] **Step 4:** Write the numbers in the notes file.

### Task 2: The consumer's fades, underruns and counters

**Files:**
- Modify: `src/util/audio_stream.h`, `src/util/audio_stream.cpp` (`ReadFrames` 286-415, `AllocateBuffer` 478-501)
- Create: `src/common-tests/audio_stream_tests.cpp` (added to `src/common-tests/CMakeLists.txt`)

**Interfaces:** Produces `Counters`, `GetAndResetCounters()`, the fade curve (`m_fade_curve`, `FADE_MS`), the
consumer state (`enum class PlayState : u8 { Filling, Playing, FadingOut }`, consumer-only), `m_restart_frames`, and
the last output frame (`std::array<float, MAX_OUTPUT_CHANNELS> m_last_frame`).

The test helper (in the test file): `class FakeAudioStream : public AudioStream` calls
`BaseInitialize(&StereoSampleReaderImpl)` in its constructor and exposes `Read(s16*, u32)` (→ `ReadFrames`). It
records `PauseDevice` calls. A `Write(stream, frames)` helper goes through `BeginWrite`/`EndWrite`. Signals: a
100 Hz sine at amplitude 10000, whose largest natural step is about 142. Steps are checked with `MaxStep(out)`, the
largest |difference| between neighbouring frames, which must stay ≤ 300; a hard cut shows a step of thousands. The
mode is Off and the rate 44.1 kHz unless a test says otherwise.

- [ ] **Step 1: Write the failing tests.**
  - `StartsSilentAndFadesIn`: with less than the restart level written, a read is all zeros and consumes nothing
    (`GetBufferedFramesRelaxed()` unchanged). With more than the restart level written, the first 176 frames rise
    from 0 and `MaxStep ≤ 300`.
  - `SteadyPlayIsBitExact`: once the fade-in is done, the output equals the input sample for sample.
  - `UnderrunFadesOut`: a read wanting more than is buffered gives the data with its last 176 frames faded to 0,
    then zeros. `MaxStep ≤ 300`; counters `underruns 1, fades 1`.
  - `UnderrunWithFewFramesLeftHoldsTheLastFrame`: 40 frames left. The fade still spans 176 frames (40 of data, then
    the last frame held), and `MaxStep ≤ 300`.
  - `RestartsOnlyOnceRefilled`: after an underrun, less than the restart level gives silence; more fades in.
  - `TinyBufferStillPlays`: `BufferMS` 10 in Off and Resample. Writing and reading in turn reaches full gain.
  - `CountersResetWhenRead`: a second `GetAndResetCounters()` is all zeros.
- [ ] **Step 2: Run** `cmake --build build2 --target common-tests && build2/bin/common-tests --gtest_filter='AudioStream.*'`.
  Expected: build failure (no `Counters`, no `PauseDevice`).
- [ ] **Step 3: Implement.**
  - `ReadFrames` becomes the state machine above, and the nearest-neighbour stretch goes. Gain is applied in float
    with clamping. While playing at full gain and volume 100, the samples aren't touched (bit-exact).
  - `m_filling` goes. `StretchUnderrun()` is still called on an underrun.
  - The restart level and the fade curve are set in `AllocateBuffer`.
  - `PauseDevice`: declare it, with a base body that sets `m_paused` (Task 6 wires it in).
- [ ] **Step 4: Run** the filter, then all of common-tests. Expected: PASS.
- [ ] **Step 5: Commit** (`Audio: fade out at an underrun and back in once refilled`).

### Task 3: Volume ramps

**Files:** `src/util/audio_stream.h/.cpp` (`SetOutputVolume` 573, `EndWrite` 671, the volume block in `ReadFrames`);
tests in `audio_stream_tests.cpp`.

**Interfaces:** `m_volume` becomes `std::atomic<u32>` (the target, read by `EndWrite` and `GetOutputVolume()`). The
consumer adds `float m_current_volume`.

- [ ] **Step 1: Failing tests.**
  - `VolumeChangeRamps`: playing at 100, then `SetOutputVolume(50)`. The output reaches half over 220 frames (half
    of the 10 ms full-scale ramp) with `MaxStep ≤ 300`, and stays at half.
  - `MuteAndUnmuteFadesBackIn`: `SetOutputVolume(0)` ramps the buffered audio down. Writes are dropped and the
    underrun that follows counts no underrun. `SetOutputVolume(100)` and fresh writes fade back in.
- [ ] **Step 2: Run** the filter. Expected: FAIL (a step, an underrun counted).
- [ ] **Step 3: Implement.** The ramp moves at most `1 / (rate · VOLUME_RAMP_MS / 1000)` a frame toward the target.
  A volume that isn't 100 and isn't changing keeps today's SIMD multiply.
- [ ] **Step 4: Run** the filter and common-tests. Expected: PASS.
- [ ] **Step 5: Commit** (`Audio: ramp volume changes`).

### Task 4: A faded clear (`EmptyBuffer`) and a silent null stream

**Files:** `src/util/audio_stream.h/.cpp` (`EmptyBuffer` 516-535, `CreateNullStream` 61-72); tests.

**Interfaces:** `std::atomic<u64> m_clear_request` (generation in the high 32 bits, write position in the low 32),
`std::atomic<u32> m_cleared_generation` (the consumer's acknowledgement, used by Task 6), and
`u32 m_seen_clear_generation` (consumer-only).

- [ ] **Step 1: Failing tests.**
  - `EmptyBufferFadesOutAndDropsStaleAudio`: a sine is playing. `EmptyBuffer()`, then a different signal (a 300 Hz
    sine) is written. Reading gives the old sine fading out over ≤ 176 frames, silence, then the new one fading in.
    Nothing of the old signal comes after the fade (checked against both signals). `MaxStep ≤ 300`. Counters:
    `fades 1, clears 1, underruns 0`.
  - `EmptyBufferWhileFillingIsImmediate`: it is acknowledged at the next read, with no fade counted.
  - `RepeatedClearsRecover`: ten `EmptyBuffer()` calls between reads, then writes. One fade, and full gain within
    restart level + 176 frames of the last write.
  - `ConcurrentClearsNeverReplayOldAudio`: TimeStretch off. A producer thread writes frames whose left channel is
    `index mod 30000` and right channel is 30000, at 3× real time, calling `EmptyBuffer()` at random. A consumer
    thread reads 256 frames each 2 ms, for 2 s. Wherever right > 1000, `round(30000 · left / right)` never goes
    backwards (except at the 30000 wrap). This recovers the index through any fade.
  - `NullStreamDiscards`: after writes to `CreateNullStream`, `GetBufferedFramesRelaxed() == 0` and the counters are
    zero.
- [ ] **Step 2: Run** the filter. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - The producer resets SoundTouch, the staging position, surround (`m_expander->Flush()`), the overflow state
    (Task 5) and the tempo averaging (`m_stretch_reset = STRETCH_RESET_THRESHOLD`). It then publishes
    `{++generation, wpos}` with release.
  - The consumer loads the request first in each callback. A new generation makes it fade out over the stale frames
    between `rpos` and the recorded position, holding the last frame if they run short. It then sets `rpos` to the
    recorded position only if that lies within `[rpos, wpos]`; otherwise the stale part is already played. It goes
    to filling and stores the generation in `m_cleared_generation`.
  - The null stream discards in `EndWrite` (its own flag).
- [ ] **Step 4: Run** the filter and common-tests. Expected: PASS.
- [ ] **Step 5: Commit** (`Audio: empty the stream with a fade, from the emulation thread`).

### Task 5: Overflows dropped from the producer, the stretcher's state made safe

**Files:** `src/util/audio_stream.h/.cpp` (`InternalWriteFrames` 422-462, `StretchOverrun` 905-913 removed,
`m_stretch_reset` 285 and 787-903); tests.

**Interfaces:** the producer-only `enum class WriteState : u8 { Normal, FadingOut, Dropping, FadingIn }` and
`u32 m_write_fade_pos`; `std::atomic<u32> m_stretch_reset`.

- [ ] **Step 1: Failing tests.**
  - `OverflowFadesOutDropsAndFadesIn`: with no reads, the sine is written past full. Reading everything back gives
    the sine fading to 0 at the end, with `overflows 1, fades 1`. A read to below half, more writes, then reading
    everything fades in again, with `MaxStep ≤ 300` overall.
  - `OverflowResumesInEveryMode`: Off, Resample and TimeStretch, `BufferMS` 10 and 100. Writing at 3× while
    reading at 1× never sticks: some full-gain output in every second.
  - `TimeStretchUnderConcurrencyStaysConsistent`: TimeStretch, a producer at 4× and 0.5× in turn, a consumer at
    1×, for 3 s. No crash, and `rpos` only moves on the consumer (checked under ThreadSanitizer in Task 8).
- [ ] **Step 2: Run** the filter. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - The producer enters fading out when free space would fall below the high-water mark. It counts an overflow and a
    fade, and calls `m_stretch_reset.fetch_add(1)`.
  - Dropping lasts until the ring has drained to half, then it fades in.
  - `UpdateStretchTempo` reads the reset count once (`load`) and stores 0 where it reset. `StretchUnderrun` uses
    `fetch_add`.
- [ ] **Step 4: Run** the filter and common-tests. Expected: PASS.
- [ ] **Step 5: Commit** (`Audio: drop overflows from the producer's side with a fade`).

### Task 6: Pause with a fade, in every backend

**Files:** `src/util/audio_stream.h/.cpp` (`SetPaused` 568, `SetStretchMode` 546-566 removed),
`aaudio_audio_stream.cpp` (`SetPaused` → `PauseDevice`), `cubeb_audio_stream.cpp:259`, `sdl_audio_stream.cpp:134`,
`src/core/system.cpp:4680-4681` (the redundant `SetStretchMode` call: a stretch mode change already recreates the
stream, and its buffer swap raced AAudio's asynchronous pause); tests.

**Interfaces:** `SetPaused(true)`: if not already paused, `EmptyBuffer()`, then poll `m_cleared_generation` each
1 ms for up to `PAUSE_FADE_TIMEOUT_MS` (a timeout counts a cut), then `PauseDevice(true)`. `SetPaused(false)` is
`PauseDevice(false)`. The backends keep setting `m_paused` themselves (AAudio under `m_stream_mutex`, for its reopen
thread).

- [ ] **Step 1: Failing tests.**
  - `PauseFadesOutWaitsAndClears`: a consumer thread is reading. `SetPaused(true)` returns within 100 ms, and
    `PauseDevice(true)` is called only after the fade's acknowledgement. Once resumed, with a new signal written,
    nothing of the old one plays.
  - `PauseWithNoConsumerReturnsAfterTimeout`: no reads. It returns in under 150 ms with `cuts 1`. Resuming and
    reading plays the new writes.
  - `PauseWhilePausedIsANoOp`: no second wait or `PauseDevice` call.
- [ ] **Step 2: Run** the filter. Expected: FAIL.
- [ ] **Step 3: Implement**, then build Qt and regtest on the Mac, and the Android native library
  (`./gradlew assembleDebug`).
- [ ] **Step 4: Run** the filter and common-tests. Expected: PASS.
- [ ] **Step 5: Commit** (`Audio: fade out before pausing the device`).

### Task 7: The jumps in System, and the stream's teardown

TDD skipped: one-line call sites in `System` and `SPU`, which have no unit harness. Proven by the counters in
Task 9's runs: one clear for each load, reset, rewind start and fast-forward end.

**Files:** `src/core/system.cpp` (`LoadStateFromBuffer` ~2946, `ResetSystem` after `InternalReset()` 1580,
`SetRewinding(true)` when not already rewinding ~5193, `SetFastForwardEnabled` 3854, `SetTurboEnabled` 3868),
`src/core/spu.cpp` (`RecreateOutputStream` 498, `Shutdown` 522).

- [ ] **Step 1:** `SPU::GetOutputStream()->EmptyBuffer()` at a state load (before `DoState`, so the loaded state's
  audio is the new audio), a reset, and a rewind's start.
- [ ] **Step 2:** The same when fast-forward or turbo turns **off**, so the sped-up tail is dropped and the tempo
  averaging starts over. Turning it on keeps the stream (no gap). If Task 9 still shows two gaps per cycle, find the
  cause with `superpowers:systematic-debugging` before changing anything (a stall on the switch, for example, is
  outside the stream).
- [ ] **Step 3:** `SetPaused(true)` on the old stream before it is destroyed in `RecreateOutputStream` and
  `Shutdown` (it fades). The new stream starts in filling, so it fades in.
- [ ] **Step 4:** Build Qt, regtest and Android. Run common-tests and a Mac regtest of 900 frames on Metal at
  Cubeb, with no crash.
- [ ] **Step 5: Commit** (`Core: empty the audio stream at each jump`).

### Task 8: The counters on screen and in the per-second line

TDD skipped for the formatting (two format strings). The counts themselves are tested in Tasks 2–6.

**Files:** `src/core/system.cpp` (`UpdatePerformanceCounters` 3517, `FormatLatencyStats` 3583-3597).

- [ ] **Step 1:** Each second, `UpdatePerformanceCounters` takes `SPU::GetOutputStream()->GetAndResetCounters()`
  into `s_audio_counters`. The per-second line gains a tail after `Lat:` (earlier fields keep their places), ending
  `Lat: 12.34ms Audio: U 0 O 0 F 1 C 1 X 0`.
- [ ] **Step 2:** `FormatLatencyStats` shows the last second's: `AL: 45ms (U 0, O 0, F 1)`.
- [ ] **Step 3:** Check on the Mac's Qt app (latency statistics on) and in the Thor's logcat.
- [ ] **Step 4: ThreadSanitizer:** common-tests (`build-tsan` with `BUILD_TESTS=ON`, filter `AudioStream.*`, five
  repeats), and regtest with Cubeb for 1800 frames with the `states` and `ff` driver from Task 1. Expected: no
  report from `audio_stream.cpp` or the backends.
- [ ] **Step 5: Commit** (`Core: audio underruns, overflows and fades in the statistics`).

### Task 9: After-numbers and the test tiers

**Files:** none in the repo (scratch: the Thor's `AudioMeasureTest` from `/tmp/goose-audio`, reading the new `Audio:`
tail; the Task 1 driver).

- [ ] **Step 1: Thor.** All the design's scenarios, plus `pause` (pause 1 s / run 3 s ×5) and `reset`, at
  Vulkan 3× and OpenGL ES 8× heavy, two interleaved rounds. Success:
  - ordinary play: 0 underruns and 0 fades;
  - `cuts 0` everywhere, and one clear for each load, reset, pause and fast-forward end;
  - the fast-forward scenario: at most 5 fades (one per on/off cycle);
  - underruns not above the design's table.
  Afterwards uninstall `.debug.test` and remove the scratch from the app's cache. `/sdcard/GooseTest` stays, as it
  was before this session.
- [ ] **Step 2: Mac and Windows.** Task 1's scenarios on the built branch, compared: no worse, `cuts 0`.
- [ ] **Step 3: Android quick tier** during the build-up, then the **full tier**, both from a detached worktree.
  Also CI's Gradle line (`assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`)
  and `android/scripts/run-native-tests.sh`. Expected: green, with the XML read.
- [ ] **Step 4:** Write the before-and-after table into the notes file.

### Task 10: Roadmap and PR

**Files:** `docs/roadmap.md` (item 24).

- [ ] **Step 1:** Item 24 reads "stage 1 done (#PR): …; stage 2 to come", in the roadmap's style.
- [ ] **Step 2: Commit** (`Docs: audio stream stage 1 on the roadmap`), push, and open the PR. Its body has the
  before-and-after counts per scenario and platform, the fixed values above, and the listening checks left to the
  user: ordinary play unchanged, nothing swallowed after a load, headphones and Bluetooth, pause and resume. Then run
  `superpowers:requesting-code-review`.
