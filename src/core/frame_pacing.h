// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/timer.h"
#include "common/types.h"

#include <algorithm>
#include <array>

/// The screen's refreshes, as the host last saw them: one refresh's time, the time between them, and how long before
/// a refresh a frame must be queued to be shown on it. Zero period: no timeline.
struct DisplayTimeline
{
  Common::Timer::Value vsync_time = 0;
  Common::Timer::Value period = 0;
  Common::Timer::Value deadline_lead = 0;
};

/// Frames locked to the screen's refreshes (docs/superpowers/specs/2026-11-20-gpu-thread-and-pacing-design.md, stage 2).
namespace FramePacing {

/// How many refreshes each console frame takes when the screen's rate is a whole multiple of the console's (within
/// 0.5%), up to four; 0 when it isn't (PAL on 60 Hz, 144 Hz).
u32 RefreshesPerFrame(float refresh_hz, float console_hz);

/// The game's speed that puts one frame on every refreshes_per_frame refreshes.
float LockedSpeed(float refresh_hz, float console_hz, u32 refreshes_per_frame);

struct Wake
{
  Common::Timer::Value wake_time; // start the next frame then
  Common::Timer::Value deadline;  // it's due then (0 without a timeline)
};

/// When to start the next frame: the first deadline at least refreshes_per_frame refreshes after last_deadline (0
/// for none) that can still be made, the frame taking predicted_frame_time plus margin (at most its refreshes). A
/// frame that takes its refreshes or more starts now (waiting would only halve the game's speed), but no sooner than
/// its refreshes after last_wake (the last frame's start; 0 for none), so it never runs fast. Without a timeline, now.
Wake NextWake(const DisplayTimeline& timeline, Common::Timer::Value now, u32 refreshes_per_frame,
              Common::Timer::Value last_deadline, Common::Timer::Value predicted_frame_time,
              Common::Timer::Value margin, Common::Timer::Value last_wake = 0);

/// How long the next frame will take, to be ready by its deadline: the 90th percentile of the last 120 frames, so a
/// rare long one (a shader compile) costs that frame alone, not every frame's lag. Frames over a second are skipped.
class FrameTimePredictor
{
public:
  static constexpr u32 WINDOW = 120;

  void Add(Common::Timer::Value frame_time);
  Common::Timer::Value Predicted() const;
  void Reset();

private:
  std::array<Common::Timer::Value, WINDOW> m_times = {};
  u32 m_count = 0;
  u32 m_next = 0;
};

/// Where the screen really takes frames: how long after the timeline's deadline to aim, from each frame's time from
/// its start (just after its input was read) to the screen. That time falls by what a frame starts later, until it
/// misses the screen's real cutoff and jumps by a refresh: judged on the time plus the frame's own offset, which is
/// steady while frames are on time (late: more than half a refresh above the 10th percentile of the window).
/// The tracker starts half a refresh before the deadline (the safe side) and creeps later until a frame misses, remembers a ceiling 1.5 ms below that, and steps 3 ms back (the
/// Thor's compositor only takes frames on time again from well below where they started to miss), further while they
/// stay late; then it creeps back up to the ceiling and rests there. Three minutes without a miss raise the ceiling 1.5 ms,
/// in case the screen allows more now. The frames already on their way when a miss comes back count with it. Not
/// judged while its first frames come in, nor for a frame whose own work ran over its plan. Between half a refresh
/// before the deadline (a screen that takes frames only before it: the Thor at 120 Hz) and 0.9 of a refresh after.
class CutoffTracker
{
public:
  static constexpr double PROBE_MS_PER_FRAME = 0.01;
  static constexpr double SEARCH_MS_PER_FRAME = 0.05; // until the first miss
  static constexpr double CEILING_MARGIN_MS = 1.5;
  static constexpr double STEP_BACK_MS = 3.0;
  static constexpr u32 IN_FLIGHT_FRAMES = 8;
  static constexpr u32 RELAX_FRAMES = 3 * 60 * 60;
  static constexpr u32 WINDOW = 240;

  /// A frame shown start_to_screen after it started, aimed offset past the deadline; ran_long: its own work took
  /// longer than planned.
  void Report(Common::Timer::Value start_to_screen, s64 offset, Common::Timer::Value period,
              bool ran_long);

  /// How far past the timeline's deadline to aim; negative: before it.
  s64 SignedOffset() const { return m_offset; }
  /// The same, not below the deadline.
  Common::Timer::Value Offset() const { return static_cast<Common::Timer::Value>(std::max<s64>(m_offset, 0)); }
  void Reset();

private:
  s64 m_offset = 0;
  s64 m_ceiling = 0;
  bool m_has_ceiling = false;
  u32 m_in_flight = 0;
  u32 m_since_miss = 0;
  std::array<Common::Timer::Value, WINDOW> m_times = {};
  u32 m_count = 0;
  u32 m_next = 0;
};

/// The frame rate to ask of the screen for the running game: the console's (59.83 Hz NTSC, 50 PAL), 0 for none.
float RequestedFrameRate(float console_hz);

/// Whether vsync stays on: not while fast-forwarding (or in turbo), so fast-forward isn't held to the screen.
bool VSyncWhileFastForwarding(bool vsync, bool fast_forwarding);

/// The refreshes per frame the lock uses, from what the timeline says each frame: a change counts once it has held
/// for HOLD_FRAMES frames, so one odd tick (skipped, stale, a stray period) doesn't drop and relock it.
class LockDebounce
{
public:
  static constexpr u32 HOLD_FRAMES = 30;

  u32 Update(u32 wanted);
  u32 Current() const { return m_current; }
  void Reset();

private:
  u32 m_current = 0;
  u32 m_candidate = 0;
  u32 m_held = 0;
};

/// Whether frames lock to the screen: sync to host refresh rate is on, the host has a timeline, the screen's rate is a
/// multiple of the console's, the game runs at normal speed, and nothing (pause, frame step, rewind) interrupts it.
bool ShouldLock(bool sync_to_host, bool has_timeline, u32 refreshes_per_frame, float target_speed, bool interrupted);

} // namespace FramePacing
