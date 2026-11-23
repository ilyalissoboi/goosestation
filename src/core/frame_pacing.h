// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "types.h"

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
/// 2%: a panel's real rate can be a percent off its nominal one, the Thor's 120 Hz is 118.36), up to four; 0 when it
/// isn't (PAL on 60 Hz, 144 Hz).
u32 RefreshesPerFrame(float refresh_hz, float console_hz);

/// The game's speed that puts one frame on every refreshes_per_frame refreshes.
float LockedSpeed(float refresh_hz, float console_hz, u32 refreshes_per_frame);

struct Wake
{
  Common::Timer::Value wake_time; // start the next frame then
  Common::Timer::Value deadline;  // it's due then (0 without a timeline)
  Common::Timer::Value refresh;   // the timeline's expected present of the refresh it's due for (0 without one)
};

/// When to start the next frame: the first deadline at least refreshes_per_frame refreshes after last_deadline (0
/// for none) that can still be made, the frame taking predicted_frame_time plus margin (at most its refreshes). A
/// frame that takes its refreshes or more starts now (waiting would only halve the game's speed), but no sooner than
/// its refreshes after last_wake (the last frame's start; 0 for none), so it never runs fast. Without a timeline, now.
Wake NextWake(const DisplayTimeline& timeline, Common::Timer::Value now, u32 refreshes_per_frame,
              Common::Timer::Value last_deadline, Common::Timer::Value predicted_frame_time,
              Common::Timer::Value margin, Common::Timer::Value last_wake = 0);

/// The earliest a frame due for the refresh at refresh may be shown, when each frame takes two or more refreshes:
/// half a refresh before it, so one ready early waits for its refresh instead of taking the one before. 0 (no
/// request) on one refresh a frame, where it only adds lag, or without a refresh.
Common::Timer::Value DesiredPresent(Common::Timer::Value refresh, Common::Timer::Value period, u32 refreshes_per_frame);

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

/// The screen's refresh period from the gaps between its ticks: smoothed while they stay near it; a gap far from it
/// (a skipped tick) left out, unless gaps like it keep coming for NEW_RATE_TICKS ticks: then that's the new rate (the
/// screen went from 120 to 60 Hz, or back).
class PeriodEstimator
{
public:
  static constexpr u32 NEW_RATE_TICKS = 8;

  void Add(Common::Timer::Value gap);
  Common::Timer::Value Period() const { return m_period; }
  void Reset();

private:
  Common::Timer::Value m_period = 0;
  Common::Timer::Value m_other_sum = 0;
  Common::Timer::Value m_other_first = 0;
  u32 m_other_count = 0;
};

/// Whether vsync is wanted: On yes, Off no, Automatic where it's allowed (desktop) and the device has a present wait.
bool VSyncWanted(DisplayVSyncMode mode, bool automatic_allowed, bool has_present_wait);

/// Whether presents should block on the screen (FIFO) so the lock can run from the present waits: vsync wanted, sync
/// to host on, a present wait, and a screen whose rate is a multiple of the console's.
bool FifoForLock(bool vsync_wanted, bool sync_to_host, bool has_present_wait, float refresh_hz, float console_hz);

/// Whether the desktop's refresh timeline comes from the presents' feedback: while presents don't block (with FIFO
/// the waits make it), on devices that lock from unsynced feedback (Metal; not MoltenVK or Linux's Vulkan, whose
/// display timing is left as it was).
bool TimelineFromFeedback(bool fifo, bool locks_from_unsynced_feedback, bool has_present_feedback);

/// Whether sync to host refresh rate changes the game's speed to the screen's (within 5%): on desktop only while vsync
/// is on, as the setting says; on Android as before (the screen lock paces there).
bool SyncsSpeedToHost(bool sync_to_host, bool vsync_on, bool desktop);

/// Which present to wait for before the next frame reads its input: 0 the one just presented (at most one frame
/// queued), 1 the one before, while frames keep missing their refreshes (two misses within MISS_WINDOW frames). It
/// tries 0 again after a hold, which doubles each time the try fails at once. After a wait times out (a window that
/// isn't presenting: minimized, a locked screen), no waits for TIMEOUT_PAUSE frames, doubling with each timeout in a
/// row up to MAX_TIMEOUT_PAUSE; a present seen shown meanwhile (a poll that doesn't wait) ends the pause.
class PresentWaitDepth
{
public:
  static constexpr u32 NO_WAIT = 0xFFFFFFFFu;
  static constexpr u32 JUDGE_AFTER = 10;
  static constexpr u32 MISS_WINDOW = 120;
  static constexpr u32 FIRST_HOLD = 600;
  static constexpr u32 MAX_HOLD = 9600;
  static constexpr u32 TIMEOUT_PAUSE = 60;
  static constexpr u32 MAX_TIMEOUT_PAUSE = 3600;

  /// A frame's wait came back: shown_interval since the last frame shown (0: the wait timed out), period the
  /// refresh, refreshes those the frame should take (1, or the lock's n).
  void Report(Common::Timer::Value shown_interval, Common::Timer::Value period, u32 refreshes);
  /// A frame presented without a wait (NO_WAIT); shown: a poll found the screen showing presents again.
  void NotWaited(bool shown);
  u32 Depth() const;
  void Reset();

private:
  u32 m_depth = 0;
  u32 m_frames = 0;           // reported since the reset
  u32 m_last_miss = 0;        // frame of the last miss at depth 0, plus one (0: none)
  u32 m_hold = FIRST_HOLD;    // frames at depth 1 before trying 0
  u32 m_held = 0;             // frames at depth 1 so far
  u32 m_trying = 0;           // frames into a try at depth 0 (0: not trying)
  u32 m_pause = 0;            // frames left without waits
  u32 m_timeouts = 0;         // timeouts in a row
};

/// The screen's refreshes, from the times presents were shown: the latest as a refresh, the period as the window's
/// time over its refreshes (each interval a whole 1-4 of the reported period, within FIT of one; others left out), a
/// buffer due half a period before its refresh. None unless MIN_FIT_SHARE of the intervals fit: a variable refresh
/// screen (ProMotion with unsynced presents) shows frames between refreshes, and has no timeline to lock to.
class PresentTimeline
{
public:
  static constexpr u32 WINDOW = 60;
  static constexpr u32 MIN_INTERVALS = 8;
  static constexpr double STALE_MS = 100.0;
  static constexpr double FIT = 0.1;
  static constexpr double MIN_FIT_SHARE = 0.9;

  void Add(Common::Timer::Value shown_time, float reported_hz);
  /// False with fewer than MIN_INTERVALS intervals, or nothing shown for STALE_MS.
  bool Get(Common::Timer::Value now, DisplayTimeline* out) const;
  void Reset();

private:
  struct Interval
  {
    Common::Timer::Value time;
    u32 refreshes; // 0: it didn't fit
  };
  std::array<Interval, WINDOW> m_intervals = {};
  u32 m_count = 0;
  u32 m_next = 0;
  Common::Timer::Value m_last_shown = 0;
};

/// Whether frames lock to the screen: sync to host refresh rate is on, the host has a timeline, the screen's rate is a
/// multiple of the console's, the game runs at normal speed, and nothing (pause, frame step, rewind) interrupts it.
bool ShouldLock(bool sync_to_host, bool has_timeline, u32 refreshes_per_frame, float target_speed, bool interrupted);

} // namespace FramePacing
