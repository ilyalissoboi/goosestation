// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "cdrom_timing.h"
#include "system.h"

#include "util/cd_image.h"

#include <algorithm>
#include <cmath>

CDROMTiming::ReadPace CDROMTiming::GetReadPace(u32 read_speedup, bool double_speed, bool streaming,
                                               TickCount ticks_per_second)
{
  if (!double_speed)
    return {ticks_per_second / 75, false};

  if (!streaming)
  {
    if (read_speedup == READ_SPEEDUP_MAXIMUM)
      return {ticks_per_second / 150, true};
    if (read_speedup > 1)
      return {ticks_per_second / static_cast<TickCount>(150 * read_speedup), false};
  }

  return {ticks_per_second / 150, false};
}

TickCount CDROMTiming::GetSeekTicks(const SeekParams& p)
{
  const u32 current_lba = p.motor_on ? p.current_lba : 0;
  const u32 lba_diff = (p.new_lba > current_lba) ? (p.new_lba - current_lba) : (current_lba - p.new_lba);

  // If we're behind the current sector, and within a small distance, the mech just waits for the sector to come up by
  // reading normally (or apparently moves the lens according to some?). This timing is actually needed for
  // Transformers - Beast Wars Transmetals, it gets very unstable during loading if seeks are too fast, so instant
  // seeks keep it.
  const bool short_forward = (current_lba < p.new_lba && lba_diff < 10);
  const u32 ticks_per_sector = p.double_speed ? static_cast<u32>(System::MASTER_CLOCK / 150) :
                                                static_cast<u32>(System::MASTER_CLOCK / 75);
  if (p.seek_speedup == 0)
  {
    return short_forward ? static_cast<TickCount>(ticks_per_sector * std::min<u32>(5u, lba_diff)) : SEEK_MIN_TICKS;
  }

  u32 ticks = p.motor_on ? 0 : static_cast<u32>(p.spin_up_ticks);

  float seconds;
  if (short_forward)
  {
    ticks += ticks_per_sector * std::min<u32>(5u, lba_diff);
    seconds = 0.0f;
  }
  else if (lba_diff < 7200)
  {
    // Not sled. The point at which we switch from faster to slower seeks varies across the disc. Around ~60 distance
    // towards the end, but ~330 at the beginning. Likely based on sectors per track, so we use a logarithmic curve.
    const u32 switch_point = static_cast<u32>(
      330.0f +
      (-63.1333f * std::log(std::clamp(static_cast<float>(current_lba) / static_cast<float>(CDImage::FRAMES_PER_MINUTE),
                                       1.0f, 72.0f))));
    seconds = (lba_diff < switch_point) ? 0.05f : 0.1f;
  }
  else
  {
    // Sled seek. Minimum of approx. 200ms, up to 900ms or so. Mapped to a linear and logarithmic component, because
    // there is a fixed cost which ramps up quickly, but the very slow sled seeks are only when doing a full disc sweep.
    constexpr float SLED_FIXED_COST = 0.05f;
    constexpr float SLED_VARIABLE_COST = 0.9f - SLED_FIXED_COST;
    constexpr float LOG_WEIGHT = 0.4f;
    constexpr float MAX_SLED_LBA = static_cast<float>(72 * CDImage::FRAMES_PER_MINUTE);
    seconds =
      SLED_FIXED_COST +
      (((SLED_VARIABLE_COST * (std::log(static_cast<float>(lba_diff)) / std::log(MAX_SLED_LBA)))) * LOG_WEIGHT) +
      ((SLED_VARIABLE_COST * (lba_diff / MAX_SLED_LBA)) * (1.0f - LOG_WEIGHT));
  }

  ticks += static_cast<u32>(seconds * static_cast<float>(System::MASTER_CLOCK));
  ticks += static_cast<u32>(p.pending_change_ticks);

  if (p.seek_speedup > 1)
    ticks = std::max<u32>(ticks / p.seek_speedup, SEEK_MIN_TICKS);

  return static_cast<TickCount>(ticks);
}

TickCount CDROMTiming::GetFixedWaitTicks(TickCount normal_ticks, u32 seek_speedup, bool short_drive_waits)
{
  return (seek_speedup == 0 && short_drive_waits) ? (normal_ticks / SHORT_WAIT_DIVISOR) : normal_ticks;
}

void CDROMTiming::StreamDetector::Reset(GlobalTicks now)
{
  NoteSign(now);
}

void CDROMTiming::StreamDetector::NoteSign(GlobalTicks now)
{
  m_last_sign = now;
  m_has_sign = true;
}

bool CDROMTiming::StreamDetector::IsStreaming(GlobalTicks now, bool mode_bits, TickCount ticks_per_second) const
{
  if (mode_bits)
    return true;

  const GlobalTicks hold = static_cast<GlobalTicks>(STREAM_HOLD_SECONDS * static_cast<float>(ticks_per_second));
  return m_has_sign && now >= m_last_sign && (now - m_last_sign) < hold;
}

bool CDROMTiming::LoadFastForward::Update(bool allowed, bool cd_loading, GlobalTicks now, TickCount ticks_per_second)
{
  if (!allowed)
  {
    m_active = false;
  }
  else if (cd_loading)
  {
    m_active = true;
    m_last_loading = now;
  }
  else if (m_active)
  {
    const GlobalTicks hold = static_cast<GlobalTicks>(LOAD_FF_HOLD_SECONDS * static_cast<float>(ticks_per_second));
    m_active = (now >= m_last_loading && (now - m_last_loading) < hold);
  }

  return m_active;
}
