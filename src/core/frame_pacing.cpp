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
                                        Common::Timer::Value predicted_frame_time, Common::Timer::Value margin,
                                        Common::Timer::Value last_wake)
{
  if (timeline.period == 0 || refreshes_per_frame == 0)
    return Wake{now, 0};

  const s64 period = static_cast<s64>(timeline.period);

  // A frame longer than its refreshes can't be helped by starting earlier: never plan further out than that, and
  // never wait for it either (the game runs as fast as it can, as unlocked).
  const s64 slot = static_cast<s64>(refreshes_per_frame) * period;
  const bool overlong = (static_cast<s64>(predicted_frame_time + margin) >= slot);
  const s64 frame_time = std::min(static_cast<s64>(predicted_frame_time + margin), slot);

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

  Common::Timer::Value wake = static_cast<Common::Timer::Value>(deadline - frame_time);
  if (overlong)
    wake = (last_wake != 0) ? std::max(now, last_wake + static_cast<Common::Timer::Value>(slot)) : now;
  return Wake{wake, static_cast<Common::Timer::Value>(deadline)};
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

void FramePacing::CutoffTracker::Report(Common::Timer::Value start_to_screen, s64 offset,
                                        Common::Timer::Value period, bool ran_long)
{
  static constexpr u32 MIN_JUDGED = 16;

  const s64 p = static_cast<s64>(period);
  if (p <= 0)
    return;

  const s64 max_offset = p * 9 / 10;
  const s64 min_offset = -p / 2;
  if (m_count == 0 && !m_has_ceiling)
    m_offset = min_offset; // start on the safe side, and creep up to the cutoff
  const auto ms = [](double v) { return static_cast<s64>(Common::Timer::ConvertMillisecondsToValue(v)); };

  // Where the frame landed against the deadline grid, whatever its own offset: steady while frames are on time, a
  // refresh more when one is late (over half a refresh above the window's 10th percentile).
  const Common::Timer::Value landed =
    static_cast<Common::Timer::Value>(std::max<s64>(static_cast<s64>(start_to_screen) + offset, 0));
  bool missed = false;
  if (m_count >= MIN_JUDGED)
  {
    std::array<Common::Timer::Value, WINDOW> sorted;
    std::copy_n(m_times.begin(), m_count, sorted.begin());
    const u32 index = (m_count - 1) / 10;
    std::nth_element(sorted.begin(), sorted.begin() + index, sorted.begin() + m_count);
    missed = (static_cast<s64>(landed) > static_cast<s64>(sorted[index]) + p / 2);
  }
  m_times[m_next] = landed;
  m_next = (m_next + 1) % WINDOW;
  m_count = std::min(m_count + 1, WINDOW);

  if (ran_long)
  {
    // Late because the frame itself ran long: the aim was fine.
  }
  else if (missed && m_in_flight > 0)
  {
    // On its way when the last step back was taken; still missing after that, further back.
    if (--m_in_flight == 0)
    {
      m_offset -= ms(STEP_BACK_MS);
      m_in_flight = IN_FLIGHT_FRAMES;
    }
  }
  else if (missed)
  {
    // The cutoff, or near it: rest below it from now on (not while starting, when nothing has settled).
    if (m_count == WINDOW)
    {
      const s64 below = std::max<s64>(m_offset - ms(CEILING_MARGIN_MS), min_offset);
      m_ceiling = m_has_ceiling ? std::min(m_ceiling, below) : below;
      m_has_ceiling = true;
    }
    m_offset -= ms(STEP_BACK_MS);
    m_in_flight = IN_FLIGHT_FRAMES;
    m_since_miss = 0;
  }
  else
  {
    m_in_flight = 0;
    if (m_has_ceiling && ++m_since_miss >= RELAX_FRAMES)
    {
      m_ceiling += ms(CEILING_MARGIN_MS);
      m_since_miss = 0;
    }

    const s64 limit = m_has_ceiling ? std::min(m_ceiling, max_offset) : max_offset;
    if (m_offset < limit)
      m_offset = std::min(m_offset + ms(m_has_ceiling ? PROBE_MS_PER_FRAME : SEARCH_MS_PER_FRAME), limit);
  }

  m_offset = std::clamp<s64>(m_offset, min_offset, max_offset);
  if (m_has_ceiling)
    m_ceiling = std::clamp<s64>(m_ceiling, min_offset, max_offset);
}

void FramePacing::CutoffTracker::Reset()
{
  m_offset = 0;
  m_ceiling = 0;
  m_has_ceiling = false;
  m_in_flight = 0;
  m_since_miss = 0;
  m_count = 0;
  m_next = 0;
}

float FramePacing::RequestedFrameRate(float console_hz)
{
  return (console_hz > 0.0f) ? console_hz : 0.0f;
}

bool FramePacing::VSyncWhileFastForwarding(bool vsync, bool fast_forwarding)
{
  return (vsync && !fast_forwarding);
}

u32 FramePacing::LockDebounce::Update(u32 wanted)
{
  if (wanted == m_current)
  {
    m_held = 0;
    return m_current;
  }

  if (wanted != m_candidate)
  {
    m_candidate = wanted;
    m_held = 0;
  }
  if (++m_held >= HOLD_FRAMES)
  {
    m_current = wanted;
    m_held = 0;
  }
  return m_current;
}

void FramePacing::LockDebounce::Reset()
{
  m_current = 0;
  m_candidate = 0;
  m_held = 0;
}

void FramePacing::PeriodEstimator::Add(Common::Timer::Value gap)
{
  if (gap == 0)
    return;

  if (m_period == 0)
  {
    m_period = gap;
    return;
  }

  const s64 diff = static_cast<s64>(gap) - static_cast<s64>(m_period);
  if (std::abs(diff) <= static_cast<s64>(m_period / 4))
  {
    m_period = (m_period * 7 + gap) / 8;
    m_other_count = 0;
    return;
  }

  // Far from the period: a skipped tick, or the start of a new rate if more like it follow.
  const bool like_the_last = (m_other_count > 0 &&
                              std::abs(static_cast<s64>(gap) - static_cast<s64>(m_other_first)) <=
                                static_cast<s64>(m_other_first / 10));
  if (!like_the_last)
  {
    m_other_first = gap;
    m_other_sum = 0;
    m_other_count = 0;
  }
  m_other_sum += gap;
  if (++m_other_count >= NEW_RATE_TICKS)
  {
    m_period = m_other_sum / m_other_count;
    m_other_count = 0;
  }
}

void FramePacing::PeriodEstimator::Reset()
{
  m_period = 0;
  m_other_count = 0;
}

bool FramePacing::VSyncWanted(DisplayVSyncMode mode, bool automatic_allowed, bool has_present_wait)
{
  switch (mode)
  {
    case DisplayVSyncMode::On:
      return true;
    case DisplayVSyncMode::Automatic:
      return automatic_allowed && has_present_wait;
    default:
      return false;
  }
}

bool FramePacing::FifoForLock(bool vsync_wanted, bool sync_to_host, bool has_present_wait, float refresh_hz,
                              float console_hz)
{
  return vsync_wanted && sync_to_host && has_present_wait && RefreshesPerFrame(refresh_hz, console_hz) > 0;
}

void FramePacing::PresentWaitDepth::Report(Common::Timer::Value shown_interval, Common::Timer::Value period,
                                           u32 refreshes)
{
  if (shown_interval == 0)
  {
    m_pause = TIMEOUT_PAUSE;
    return;
  }

  const u32 frame = ++m_frames;
  if (frame <= JUDGE_AFTER)
    return;

  if (m_depth == 1)
  {
    // Waiting for the frame before: try the frame just presented again once the hold is over.
    if (++m_held >= m_hold)
    {
      m_depth = 0;
      m_held = 0;
      m_trying = 1;
      m_last_miss = 0;
    }
    return;
  }

  // Missed: shown more than half a refresh after its refreshes.
  if (shown_interval * 2 > (refreshes * 2 + 1) * period)
  {
    if (m_last_miss != 0 && frame - m_last_miss <= MISS_WINDOW)
    {
      if (m_trying != 0)
        m_hold = std::min(m_hold * 2, MAX_HOLD);
      m_depth = 1;
      m_held = 0;
      m_trying = 0;
      m_last_miss = 0;
      return;
    }
    m_last_miss = frame;
  }

  // A try that held for a window: the next fall back starts from the first hold again.
  if (m_trying != 0 && ++m_trying > MISS_WINDOW)
  {
    m_trying = 0;
    m_hold = FIRST_HOLD;
  }
}

void FramePacing::PresentWaitDepth::NotWaited()
{
  if (m_pause > 0)
    m_pause--;
}

u32 FramePacing::PresentWaitDepth::Depth() const
{
  return (m_pause > 0) ? NO_WAIT : m_depth;
}

void FramePacing::PresentWaitDepth::Reset()
{
  *this = PresentWaitDepth();
}

void FramePacing::PresentTimeline::Add(Common::Timer::Value shown_time, float reported_hz)
{
  if (m_last_shown != 0 && shown_time > m_last_shown && reported_hz > 0.0f)
  {
    const Common::Timer::Value interval = shown_time - m_last_shown;
    const double reported_period = Common::Timer::ConvertSecondsToValue(1.0 / static_cast<double>(reported_hz));
    const double refreshes = std::round(static_cast<double>(interval) / reported_period);
    if (refreshes >= 1.0 && refreshes <= 4.0 &&
        std::abs(static_cast<double>(interval) - refreshes * reported_period) <= reported_period / 4.0)
    {
      m_intervals[m_next] = Interval{interval, static_cast<u32>(refreshes)};
      m_next = (m_next + 1) % WINDOW;
      m_count = std::min(m_count + 1, WINDOW);
    }
  }
  m_last_shown = std::max(m_last_shown, shown_time);
}

bool FramePacing::PresentTimeline::Get(Common::Timer::Value now, DisplayTimeline* out) const
{
  if (m_count < MIN_INTERVALS || m_last_shown == 0 ||
      (now > m_last_shown && Common::Timer::ConvertValueToMilliseconds(now - m_last_shown) > STALE_MS))
  {
    return false;
  }

  Common::Timer::Value time = 0;
  u32 refreshes = 0;
  for (u32 i = 0; i < m_count; i++)
  {
    time += m_intervals[i].time;
    refreshes += m_intervals[i].refreshes;
  }

  out->vsync_time = m_last_shown;
  out->period = time / refreshes;
  out->deadline_lead = out->period / 2;
  return true;
}

void FramePacing::PresentTimeline::Reset()
{
  *this = PresentTimeline();
}
