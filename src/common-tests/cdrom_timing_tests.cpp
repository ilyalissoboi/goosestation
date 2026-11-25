// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/cdrom_timing.h"
#include "core/settings.h"
#include "core/system.h"

#include <gtest/gtest.h>

namespace {

constexpr TickCount TPS = static_cast<TickCount>(System::MASTER_CLOCK);

CDROMTiming::SeekParams Seek(u32 current, u32 target, bool double_speed = true, u32 speedup = 1)
{
  CDROMTiming::SeekParams p;
  p.current_lba = current;
  p.new_lba = target;
  p.motor_on = true;
  p.double_speed = double_speed;
  p.seek_speedup = speedup;
  return p;
}

} // namespace

TEST(CDROMTiming, ReadSpeedsAsToday)
{
  EXPECT_EQ(CDROMTiming::GetReadPace(1, true, false, TPS).interval, TPS / 150);
  EXPECT_EQ(CDROMTiming::GetReadPace(1, false, false, TPS).interval, TPS / 75);
  EXPECT_EQ(CDROMTiming::GetReadPace(4, true, false, TPS).interval, TPS / 600);
  EXPECT_EQ(CDROMTiming::GetReadPace(4, false, false, TPS).interval, TPS / 75);
  EXPECT_EQ(CDROMTiming::GetReadPace(4, true, true, TPS).interval, TPS / 150);
  EXPECT_FALSE(CDROMTiming::GetReadPace(4, true, false, TPS).paced_by_game);
}

TEST(CDROMTiming, MaximumCeilingIsNormalSpeed)
{
  const CDROMTiming::ReadPace max = CDROMTiming::GetReadPace(CDROMTiming::READ_SPEEDUP_MAXIMUM, true, false, TPS);
  EXPECT_EQ(max.interval, TPS / 150);
  EXPECT_TRUE(max.paced_by_game);

  const CDROMTiming::ReadPace single = CDROMTiming::GetReadPace(CDROMTiming::READ_SPEEDUP_MAXIMUM, false, false, TPS);
  EXPECT_EQ(single.interval, TPS / 75);
  EXPECT_FALSE(single.paced_by_game);

  const CDROMTiming::ReadPace streaming = CDROMTiming::GetReadPace(CDROMTiming::READ_SPEEDUP_MAXIMUM, true, true, TPS);
  EXPECT_EQ(streaming.interval, TPS / 150);
  EXPECT_FALSE(streaming.paced_by_game);

  // The floor caps Maximum at 32x.
  EXPECT_EQ(CDROMTiming::MAX_READ_FLOOR_TICKS, TPS / (150 * 32));
}

TEST(CDROMTiming, InstantSeekKeepsShortForwardTiming)
{
  EXPECT_EQ(CDROMTiming::GetSeekTicks(Seek(1000, 1004, true, 0)), 4 * (TPS / 150));
  EXPECT_EQ(CDROMTiming::GetSeekTicks(Seek(1000, 1004, false, 0)), 4 * (TPS / 75));
  EXPECT_EQ(CDROMTiming::GetSeekTicks(Seek(1000, 50000, true, 0)), CDROMTiming::SEEK_MIN_TICKS);
  EXPECT_EQ(CDROMTiming::GetSeekTicks(Seek(1000, 997, true, 0)), CDROMTiming::SEEK_MIN_TICKS);

  // A pending speed change or spin-up is skipped, as instant seeks did before.
  CDROMTiming::SeekParams p = Seek(1000, 50000, true, 0);
  p.pending_change_ticks = 20000000;
  EXPECT_EQ(CDROMTiming::GetSeekTicks(p), CDROMTiming::SEEK_MIN_TICKS);
}

TEST(CDROMTiming, SeekModelUnchanged)
{
  // From the model as it was at cc3b7a9c0: current, target, motor on, spin-up ticks, double speed, speedup, pending
  // speed change ticks, expected ticks.
  struct Case
  {
    u32 current, target;
    bool motor_on;
    TickCount spin_up;
    bool double_speed;
    u32 speedup;
    TickCount change, expected;
  };
  static constexpr Case cases[] = {
    {1000, 1004, true, 0, true, 1, 0, 903168},
    {1000, 1004, true, 0, false, 1, 0, 1806336},
    {1000, 1200, true, 0, true, 1, 0, 1693440},
    {1000, 2000, true, 0, true, 1, 0, 3386880},
    {200000, 200100, true, 0, true, 1, 0, 3386880},
    {1000, 50000, true, 0, true, 1, 0, 14106836},
    {300000, 100, true, 0, true, 1, 0, 29126952},
    {1000, 997, true, 0, true, 1, 0, 1693440},
    {5000, 6000, false, 33868800, true, 1, 0, 37255680},
    {1000, 2000, true, 0, true, 1, 20000000, 23386880},
    {1000, 50000, true, 0, true, 4, 0, 3526709},
    {1000, 1004, true, 0, true, 4, 0, 225792},
    {1000, 2000, true, 0, true, 10, 0, 338688},
    {300000, 100, true, 0, false, 2, 0, 14563476},
  };

  for (const Case& c : cases)
  {
    CDROMTiming::SeekParams p = Seek(c.current, c.target, c.double_speed, c.speedup);
    p.motor_on = c.motor_on;
    p.spin_up_ticks = c.spin_up;
    p.pending_change_ticks = c.change;
    EXPECT_EQ(CDROMTiming::GetSeekTicks(p), c.expected) << c.current << " -> " << c.target << " x" << c.speedup;
  }
}

TEST(CDROMTiming, FixedWaitsShortenOnlyWithInstantSeeks)
{
  const TickCount change = static_cast<TickCount>(0.6 * TPS);
  EXPECT_EQ(CDROMTiming::GetFixedWaitTicks(change, 0, true), change / CDROMTiming::SHORT_WAIT_DIVISOR);
  EXPECT_EQ(CDROMTiming::GetFixedWaitTicks(change, 1, true), change);
  EXPECT_EQ(CDROMTiming::GetFixedWaitTicks(change, 4, true), change);
  EXPECT_EQ(CDROMTiming::GetFixedWaitTicks(change, 0, false), change);
  EXPECT_EQ(CDROMTiming::SHORT_WAIT_DIVISOR, 10);
}

TEST(CDROMTiming, StreamHoldEndsAfterLastSign)
{
  CDROMTiming::StreamDetector detector;
  EXPECT_FALSE(detector.IsStreaming(1000, false, TPS));

  const GlobalTicks t = 10 * static_cast<GlobalTicks>(TPS);
  detector.NoteSign(t);
  EXPECT_TRUE(detector.IsStreaming(t + static_cast<GlobalTicks>(0.49 * TPS), false, TPS));
  EXPECT_FALSE(detector.IsStreaming(t + static_cast<GlobalTicks>(0.51 * TPS), false, TPS));

  // A later sign extends it.
  detector.NoteSign(t + static_cast<GlobalTicks>(0.4 * TPS));
  EXPECT_TRUE(detector.IsStreaming(t + static_cast<GlobalTicks>(0.85 * TPS), false, TPS));
  EXPECT_EQ(CDROMTiming::STREAM_HOLD_SECONDS, 0.5f);
}

TEST(CDROMTiming, ModeBitsAlwaysStream)
{
  CDROMTiming::StreamDetector detector;
  EXPECT_TRUE(detector.IsStreaming(1000, true, TPS));
  EXPECT_TRUE(detector.IsStreaming(100 * static_cast<GlobalTicks>(TPS), true, TPS));
}

TEST(CDROMTiming, ResetCountsAsStreaming)
{
  CDROMTiming::StreamDetector detector;
  const GlobalTicks now = 5 * static_cast<GlobalTicks>(TPS);
  detector.Reset(now);
  EXPECT_TRUE(detector.IsStreaming(now + static_cast<GlobalTicks>(0.49 * TPS), false, TPS));
  EXPECT_FALSE(detector.IsStreaming(now + static_cast<GlobalTicks>(0.51 * TPS), false, TPS));
}

TEST(CDROMTiming, LoadFastForwardHoldsAndReleases)
{
  CDROMTiming::LoadFastForward rule;
  const GlobalTicks t = 10 * static_cast<GlobalTicks>(TPS);
  const auto at = [t](double seconds) { return t + static_cast<GlobalTicks>(seconds * TPS); };

  EXPECT_FALSE(rule.Update(true, false, at(0.0), TPS));
  EXPECT_TRUE(rule.Update(true, true, at(0.0), TPS));
  EXPECT_TRUE(rule.Update(true, false, at(0.2), TPS)); // a gap between a load's bursts
  EXPECT_TRUE(rule.Update(true, true, at(0.3), TPS));
  EXPECT_TRUE(rule.Update(true, false, at(0.54), TPS));
  EXPECT_FALSE(rule.Update(true, false, at(0.56), TPS)); // 0.26 s after the last loading
  EXPECT_FALSE(rule.IsActive());
  EXPECT_EQ(CDROMTiming::LOAD_FF_HOLD_SECONDS, 0.25f);
}

TEST(CDROMTiming, LoadFastForwardNotAllowed)
{
  CDROMTiming::LoadFastForward rule;
  EXPECT_FALSE(rule.Update(false, true, 1000, TPS));
  EXPECT_TRUE(rule.Update(true, true, 2000, TPS));
  EXPECT_FALSE(rule.Update(false, true, 3000, TPS)); // off at once, even mid-load
  EXPECT_TRUE(rule.Update(true, true, 4000, TPS));
  rule.Reset();
  EXPECT_FALSE(rule.IsActive());
}

TEST(CDROMTiming, FastForwardLoadingDefaultsOff)
{
  EXPECT_FALSE(Settings().fast_forward_loading);
}
