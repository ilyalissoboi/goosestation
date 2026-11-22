// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "frame_pacing.h"

#include <algorithm>
#include <cmath>

u32 FramePacing::RefreshesPerFrame(float refresh_hz, float console_hz)
{
  static constexpr u32 MAX_REFRESHES = 4;
  static constexpr float TOLERANCE = 0.005f;

  if (refresh_hz <= 0.0f || console_hz <= 0.0f)
    return 0;

  const float ratio = refresh_hz / console_hz;
  const float n = std::round(ratio);
  if (n < 1.0f || n > static_cast<float>(MAX_REFRESHES) || std::abs(ratio / n - 1.0f) > TOLERANCE)
    return 0;

  return static_cast<u32>(n);
}

float FramePacing::LockedSpeed(float refresh_hz, float console_hz, u32 refreshes_per_frame)
{
  return refresh_hz / (static_cast<float>(refreshes_per_frame) * console_hz);
}

FramePacing::Wake FramePacing::NextWake(const DisplayTimeline& timeline, Common::Timer::Value now,
                                        u32 refreshes_per_frame, Common::Timer::Value last_deadline,
                                        Common::Timer::Value predicted_frame_time, Common::Timer::Value margin)
{
  if (timeline.period == 0 || refreshes_per_frame == 0)
    return Wake{now, 0};

  const s64 period = static_cast<s64>(timeline.period);

  // A frame longer than its refreshes can't be helped by starting earlier: never plan further out than that.
  const s64 frame_time = std::min(static_cast<s64>(predicted_frame_time + margin),
                                  static_cast<s64>(refreshes_per_frame) * period);

  // The earliest a deadline may be: made from now, and refreshes_per_frame refreshes on from the last one (less half
  // a refresh, for the timeline's own jitter or a change of rate).
  s64 earliest = static_cast<s64>(now) + frame_time;
  if (last_deadline != 0)
  {
    earliest = std::max(earliest, static_cast<s64>(last_deadline) +
                                    static_cast<s64>(refreshes_per_frame) * period - period / 2);
  }

  // Deadlines fall at vsync_time - deadline_lead + k * period: the first one at or after earliest.
  const s64 origin = static_cast<s64>(timeline.vsync_time) - static_cast<s64>(timeline.deadline_lead);
  const s64 diff = earliest - origin;
  const s64 k = (diff >= 0) ? ((diff + period - 1) / period) : -((-diff) / period);
  const s64 deadline = origin + k * period;

  return Wake{static_cast<Common::Timer::Value>(deadline - frame_time), static_cast<Common::Timer::Value>(deadline)};
}

bool FramePacing::ShouldLock(bool sync_to_host, bool has_timeline, u32 refreshes_per_frame, float target_speed,
                             bool interrupted)
{
  return (sync_to_host && has_timeline && refreshes_per_frame > 0 && target_speed == 1.0f && !interrupted);
}

void FramePacing::FrameTimePredictor::Add(Common::Timer::Value frame_time)
{
  if (frame_time > Common::Timer::ConvertSecondsToValue(1.0))
    return;

  m_times[m_next] = frame_time;
  m_next = (m_next + 1) % WINDOW;
  m_count = std::min(m_count + 1, WINDOW);
}

Common::Timer::Value FramePacing::FrameTimePredictor::Predicted() const
{
  if (m_count == 0)
    return 0;

  std::array<Common::Timer::Value, WINDOW> sorted;
  std::copy_n(m_times.begin(), m_count, sorted.begin());
  const u32 index = (m_count - 1) * 9 / 10;
  std::nth_element(sorted.begin(), sorted.begin() + index, sorted.begin() + m_count);
  return sorted[index];
}

void FramePacing::FrameTimePredictor::Reset()
{
  m_count = 0;
  m_next = 0;
}
