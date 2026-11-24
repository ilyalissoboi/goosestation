<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# The audio stream for uneven devices — design

Roadmap item 24 (the audio stream rework, from the upstream gaps players notice most, `docs/upstream-features.md`
tier 4). Agreed in conversation on 2026-11-24. Calls made while writing this up, not yet confirmed, are marked
*(writer's call)*; calls the builder may make are marked *(builder's call)*.

## Intent

The player's picture is the **AYN Thor** *(the player's choice)*: crackles and dropouts. Measured (below), its sound
is already clean in ordinary play, even at the heaviest settings; it clicks and drops out where the audio is
interrupted: a fresh boot and loading, fast-forward switched on or off, state loads under load, and spells where
emulation can't hold full speed. Each of those is a hard cut, a splice or a burst of silence today. Separately,
stretching costs about 13% of the emulation thread. This **replaces the cuts with fades, lets the buffer grow while
emulation falls behind, and takes stretching off the emulation thread**, aimed at what was measured *(the player's
choice)*, on **desktop and Android** *(the player's choice)*.

## Measured on the Thor (2026-11-24)

Vagrant Story (USA) from its test states, `.debug` build at `79f7e79fe` (GPU thread and pacing in), offscreen
1920×1080 surface, defaults except as named; a throwaway instrumented test and per-second counters added to a scratch
copy only (underrun starts, partial-data stretches, refill-wait callbacks, overrun skips, the buffer's least fill,
AAudio's xrun count). The Thor's audio hardware runs at 48 kHz (AudioFlinger's mixer threads, 192-frame HAL period,
960-frame normal period); GooseStation's AAudio stream is 44.1 kHz with a 1,764-frame buffer.

| Scenario | Underruns | Seconds with one | Notes |
|---|---|---|---|
| Ordinary play, Vulkan 3× (two states, 20 s each) | 0 | 0 | least fill ≈ 2,400–3,500 frames |
| Ordinary play, OpenGL ES 8×, 4× MSAA, CRT-Lottes (58.8–59.8 VPS) | 0 | 0 | the buffer absorbs it |
| Six state loads 4 s apart, Vulkan 3× | 0 | 0 | |
| Six state loads, OpenGL ES 8× heavy | 7 | 6 | 9 refill-wait callbacks |
| A fresh boot and the intro (BIOS, loads, opening), 60 s | 1–2 | 1 | the CD reads block the emulation thread |
| Fast-forward on 2 s / off 3 s, five times, 3× (either renderer) | 8–9 | 5 | one or two per switch |
| The same, OpenGL ES 8× heavy (fast-forward dips to 46.6 VPS) | 29 | 13 of 24 | 128 refill-wait callbacks |

AAudio's own xrun count never rose. simpleperf on the emulation thread in ordinary play (Vulkan 3×): SoundTouch's
`StretchWriteBlock` and `soundtouch_putSamples` take about 13% of its on-CPU time, whether or not the tempo is changing.

## What exists

From a map of the code (2026-11-24, line numbers as of `79f7e79fe`):

- **Producer:** `SPU::Execute` (`spu.cpp:2327-2465`) writes 44.1 kHz stereo s16 in bursts through
  `AudioStream::BeginWrite`/`EndWrite` (`audio_stream.cpp:578, 668`), 64-frame chunks, on the emulation thread. With
  stretching, `EndWrite` converts to float and runs SoundTouch there (`StretchWriteBlock`, 762), then
  `InternalWriteFrames` into the ring.
- **The ring:** single producer, single consumer (`m_rpos`/`m_wpos`, `audio_stream.h:279-280`), sized in
  `AllocateBuffer` (478-501): `BufferMS` × 16 with TimeStretch (Android 100 ms → about 1.6 s of ring, target 100 ms;
  desktop 50 ms → about 0.8 s, target 50 ms).
- **Overrun** (`InternalWriteFrames`, 422-436): without stretching the chunk is dropped; with it, `StretchOverrun`
  (905-913) moves `m_rpos` forward 128 frames **from the producer** (a race with the callback, and a splice).
- **Underrun** (`ReadFrames`, 286-415, on the backend's callback thread): partial data is stretched across the
  callback by a nearest-neighbour step (aliased), no data is hard silence, and playback restarts after
  `buffer/400` frames (about 4 ms with TimeStretch). No fade anywhere; volume changes are a step per callback.
- **Stretching** (`UpdateStretchTempo`, 806-897; the approach PCSX2 used): tempo from fill over a dynamic target,
  averaged over 50 entries, square-rooted below 2, clamped; hysteresis turns it inactive within 1/1.04–1.04 and back
  on outside 1/1.2–1.2; five under- or overruns (`m_stretch_reset`, written from both threads, not atomic) reset the
  averaging. Modes `None`, `Resample`, `TimeStretch` (default everywhere); SoundTouch sequence 30, seek 20, overlap
  10 ms.
- **Nothing empties the stream** (`EmptyBuffer`, 516, has no callers): fast-forward switches (`UpdateSpeedLimiterState`,
  `system.cpp:3599`, sets the nominal rate; unlimited speed gives a nominal 1.0 and constant overruns), pause and
  resume (the old audio plays on resume), state loads, rewind and resets (the old tail plays first), settings changes
  that recreate the stream (a hard cut) and CD reads that block the emulation thread (`cdrom.cpp:1553, 2790, 3011`).
  Runahead mutes replayed frames through the null stream.
- **Backends:** AAudio on Android (`aaudio_audio_stream.cpp`: low-latency, shared, I16, a fixed 44,100 Hz, buffer
  max(2 bursts, `OutputLatencyMS` 20); a reopen thread for disconnects such as headphones and Bluetooth), Cubeb on
  desktop by default, SDL3; defaults `BufferMS` 50 (desktop) / 100 (Android), `OutputLatencyMS` 20,
  `FastForwardVolume` 100.
- **No counters:** under/overrun logs are `VERBOSE_LOG`/`DEBUG_LOG`, stretch statistics a disabled constant; the only
  fill figure is "AL:" in the on-screen latency statistics (`System::FormatLatencyStats`, 3583-3597;
  `Display/ShowLatencyStatistics`).

## Goals

### Stage 1: no more clicks

- **Fades instead of cuts** *(the player's choice)*: the output fades out before a gap and back in after it, over a
  few milliseconds *(builder's call how long, by listening and measurement)*, at every point that cuts today:
  underruns, a fresh boot and loading, state loads, rewind, resets, pause and resume, fast-forward switched on or off,
  a stream recreated by a settings change, and volume changes (ramped, not stepped) *(writer's call on the list, from
  the map)*.
- **Stale audio is cleared on a jump** *(the player's choice)*: after a state load, rewind, reset or resume, what was
  queued from before isn't played; the new audio fades in.
- **Overflows drop audio cleanly** *(writer's call)*: from the producer's side only, with a fade, never by moving the
  consumer's read position from the producer thread. The shared stretching state is made safe between the two threads.
- **The underrun path**: the aliasing nearest-neighbour stretch goes; a short gap is a fade out, silence and a fade
  in *(writer's call)*, and playback restarts only once enough is buffered not to stop again at once
  *(builder's call how much)*.
- **Counters that stay** *(the player's choice)*: underruns, overflows and fades per second in the log's per-second
  line, and beside "AL:" in the on-screen latency statistics, read by the build's own tests and measurements.

### Stage 2: keeping up

- **A buffer that grows while emulation falls behind** *(the player's choice)*: by up to about 100 ms beyond the usual
  buffer (100 ms on Android, 50 ms on desktop), only while dropouts keep happening, shrinking back once the game holds
  full speed *(builder's call on the rule)*.
- **Stretching off the emulation thread** *(the player's choice)*: the emulation thread hands raw samples over, and
  stretching runs elsewhere (the backend's callback thread or one of its own *(builder's call)*), or is skipped while
  the tempo isn't changing; the result sounds the same as today in ordinary play.
- **The device's own rate** *(the player's choice)*: where the output device runs at another rate (the Thor's 48 kHz),
  GooseStation opens the stream at that rate and converts from 44.1 kHz in its own stretching stage, so Android can
  use its low-latency path. **It's kept only if measured no worse** *(the player's choice)*, on desktop as on Android;
  otherwise the stream stays at 44.1 kHz and the rest of stage 2 doesn't depend on it. "No worse" *(writer's call)*:
  no more underruns or xruns in the scenarios below, sound-to-picture delay in ordinary play no higher, the audio
  threads' CPU no higher, and the conversion sounding as clean as Android's own resampler does today (the user's
  listening check; the builder may need a better resampler than SoundTouch's rate change with its anti-alias filter off,
  and a new dependency for it is the user's to approve) *(builder's call how)*.
- **Fast-forward's sound** *(the player's choice)*: sped up at normal pitch while stretching can keep up; past that
  (very high or unlimited speeds), faded to silence, and back in when fast-forward ends. `FastForwardVolume` still
  applies.
  - **"Can keep up" follows the speed actually reached, not the setting** *(writer's call)*: fast-forward is unlimited
    by default (`FastForwardSpeed` 0), but the heavy case reached about 1.9× on average with dips to 0.8×, so it plays
    stretched audio, and is the main test of the growing buffer and the tempo control. Only a speed the stretching can't
    follow (the threshold is the builder's call, by listening) fades out.

### Settings

- The existing `[Audio]` settings keep their keys and meanings on Qt, Big Picture and Android (stretch mode, buffer,
  output latency, volumes) *(writer's guess, not contradicted)*; any new row is the builder's call, in the advanced
  part of the audio settings.

## Success

On the Thor *(the player's choice: no audible crackles or dropouts in ordinary play, heavy scenes, loading and
fast-forward; less of the emulation thread on audio; the numbers are the writer's calls)*, with the scenarios above,
before and after:

- **Stage 1:** ordinary play still at zero underruns; no hard cut anywhere: every gap in every scenario is faded
  (the counters say so); no stale audio after a state load or a resume; fast-forward switched five times gives at most
  one faded gap per switch.
- **Stage 2:** the heavy fast-forward case plays stretched audio with one faded gap at its start and end at most, not
  a gap a second;
  six state loads under the heavy settings give at most one faded gap each; the emulation thread's audio share falls
  from about 13% to at most 3%; sound-to-picture delay in ordinary play no higher than today. If the device's own rate
  is kept, the Thor's stream runs at 48 kHz on Android's fast path (AudioFlinger lists it as a fast track); if it
  measures worse, the PR says so and the stream stays at 44.1 kHz.
- **Both stages:** the same scenarios no worse on the Mac (Cubeb, SDL) and the user's Windows machine; ordinary play's
  sound unchanged to the ear (the user's check) and by comparison of the output where it can be captured
  *(builder's call how)*.

## Non-goals

- Changing the SPU's emulation or its mixing.
- New audio backends, or removing one.
- Audio during runahead (still muted), or audio for frames that rewind skips.
- Faster loading itself (item 25).

## Anti-goals

*(The player's choice: any of these makes it a failure.)*

- **Worse sound in ordinary play**, such as new warbling from stretching.
- **Sound lagging the picture more than today in ordinary play.**
- **Fades swallowing sounds that should be heard**, such as the start of a sound just after a load.
- **A crash, hang or lasting silence after a device change** (headphones, Bluetooth, USB audio, an output switch) or
  after pausing and resuming the app.
- Anything from DuckStation's code after `a5bf1ba5cee7`.

## Constraints

- **Clean room**: nothing from DuckStation after `a5bf1ba5cee7`, whose audio rework came after it; its commit titles
  and user documentation only. **PCSX2 for ideas only** *(the player's choice)*: how its audio thread handles underruns,
  fades and stretching, from the user's fork (https://github.com/ilyalissoboi/pcsx2, GPL-3.0); GooseStation's own
  code, none copied.
- **Stages, each its own plan and PR** *(the player's choice)*: (1) the fades, clearing stale audio, clean overflows and
  the counters; (2) the growing buffer, stretching off the emulation thread, the device's own rate and fast-forward's
  sound. `docs/roadmap.md` (item 24) is updated with each.
- **Tests**: native tests in `src/common-tests` for the stream's logic without a device (fades at each cut, clearing,
  overflow handling, the buffer's growth rule, the counters) through a fake consumer; Android's quick tier while
  building and the full tier before each PR; ThreadSanitizer on the stream's threads.
- **The Thor** *(the player's choice)*: the building sessions may measure there whenever it's connected (adb
  `64dc3c35`), with the `.debug` app and scratch data in `/sdcard/GooseTest` only, never the release app (the plain
  ID, never uninstalled) or the user's data folder, and clean up afterwards; the measurement test stays out of the
  repository. Listening checks (how it sounds, fades swallowing anything, headphones and Bluetooth) are the user's.
- **Desktop**: the Mac (Cubeb, SDL) here; Windows over SSH on the user's machine for the counters, with listening the
  user's.
- The user reviews every commit message.

## Left to the builder

The fades' length and shape, how a jump clears the stream, where stretching runs, the buffer's growth rule, the
device-rate conversion, the counters' names and layout, and the order within each stage.
