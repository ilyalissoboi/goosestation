// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/timer.h"
#include "common/types.h"

#include <algorithm>

/// The arithmetic of the per-second counters (System::UpdatePerformanceCounters()) that a thread split changes.
namespace PerformanceCountersMath {

/// A frame's active time for the pre-frame sleep's budget: from its start until its present finished on the GPU
/// thread, not only until it was pushed. presented is the last finished present's time; one from before the push
/// (an earlier frame's, or 0: not known yet) leaves the push.
constexpr Common::Timer::Value ActiveFrameTime(Common::Timer::Value frame_start, Common::Timer::Value pushed,
                                               Common::Timer::Value presented)
{
  return std::max(pushed, presented) - frame_start;
}

/// A GPU time sample to keep: false for one the timer queries can't have measured (Adreno's GLES gives ~10^13).
constexpr bool IsPlausibleGPUTime(float gpu_ms, float wall_ms)
{
  return (gpu_ms >= 0.0f && gpu_ms <= 4.0f * wall_ms);
}

} // namespace PerformanceCountersMath
