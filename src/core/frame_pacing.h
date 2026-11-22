// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/timer.h"
#include "common/types.h"

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
/// for none) that can still be made, the frame taking predicted_frame_time plus margin. Without a timeline, now.
Wake NextWake(const DisplayTimeline& timeline, Common::Timer::Value now, u32 refreshes_per_frame,
              Common::Timer::Value last_deadline, Common::Timer::Value predicted_frame_time,
              Common::Timer::Value margin);

} // namespace FramePacing
