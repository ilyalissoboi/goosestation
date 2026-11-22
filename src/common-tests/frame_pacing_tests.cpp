// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/frame_pacing.h"

#include "common/timer.h"

#include <gtest/gtest.h>

#include <cmath>

namespace {
constexpr float NTSC = 59.826f;

Common::Timer::Value Ms(double ms)
{
  return Common::Timer::ConvertNanosecondsToValue(ms * 1000000.0);
}

double ToMs(Common::Timer::Value v)
{
  return Common::Timer::ConvertValueToNanoseconds(v) / 1000000.0;
}

// A 60 Hz screen with a refresh at 100 ms, buffers due 4 ms before each refresh: deadlines at 96, 112.67, 129.33 ...
DisplayTimeline Screen60()
{
  return DisplayTimeline{Ms(100), Ms(1000.0 / 60.0), Ms(4)};
}
} // namespace

TEST(FramePacing, RefreshesPerFrameFindsTheMultiple)
{
  EXPECT_EQ(FramePacing::RefreshesPerFrame(60.0f, NTSC), 1u);
  EXPECT_EQ(FramePacing::RefreshesPerFrame(120.0f, NTSC), 2u);
  EXPECT_EQ(FramePacing::RefreshesPerFrame(59.94f, NTSC), 1u);
  EXPECT_EQ(FramePacing::RefreshesPerFrame(100.0f, 50.0f), 2u);
  EXPECT_EQ(FramePacing::RefreshesPerFrame(240.0f, NTSC), 4u);
}

TEST(FramePacing, RefreshesPerFrameIsZeroWithoutOne)
{
  EXPECT_EQ(FramePacing::RefreshesPerFrame(60.0f, 50.0f), 0u);
  EXPECT_EQ(FramePacing::RefreshesPerFrame(144.0f, NTSC), 0u);
  EXPECT_EQ(FramePacing::RefreshesPerFrame(0.0f, NTSC), 0u);
  EXPECT_EQ(FramePacing::RefreshesPerFrame(60.0f, 0.0f), 0u);
  EXPECT_EQ(FramePacing::RefreshesPerFrame(300.0f, NTSC), 0u); // five refreshes a frame is past the limit
}

TEST(FramePacing, LockedSpeedRunsTheGameAtTheRefresh)
{
  EXPECT_NEAR(FramePacing::LockedSpeed(60.0f, NTSC, 1), 1.00291f, 0.00002f);
  EXPECT_NEAR(FramePacing::LockedSpeed(120.0f, NTSC, 2), 1.00291f, 0.00002f);
}

TEST(FramePacing, AShortFrameWakesJustBeforeItsDeadline)
{
  const FramePacing::Wake w = FramePacing::NextWake(Screen60(), Ms(101), 1, 0, Ms(3), Ms(1));
  EXPECT_NEAR(ToMs(w.deadline), 112.667, 0.01);
  EXPECT_NEAR(ToMs(w.wake_time), 108.667, 0.01);
}

TEST(FramePacing, ALateFrameTakesTheNextRefreshNotTheOneMissed)
{
  // The last frame was due at 112.67 and it's now 127: 129.33 can't be made with 4 ms to spare, so 146.
  const FramePacing::Wake w = FramePacing::NextWake(Screen60(), Ms(127), 1, Ms(112.667), Ms(3), Ms(1));
  EXPECT_NEAR(ToMs(w.deadline), 146.0, 0.01);
  EXPECT_NEAR(ToMs(w.wake_time), 142.0, 0.01);
}

TEST(FramePacing, TwoRefreshesAFrameTakeEverySecondDeadline)
{
  const FramePacing::Wake w = FramePacing::NextWake(Screen60(), Ms(113), 2, Ms(112.667), Ms(3), Ms(1));
  EXPECT_NEAR(ToMs(w.deadline), 146.0, 0.01);
}

TEST(FramePacing, ADeadlineAlreadyTargetedIsNeverTakenAgain)
{
  // Woken early: the deadline at 129.33 is the last frame's, so this one gets 146.
  const FramePacing::Wake w = FramePacing::NextWake(Screen60(), Ms(101), 1, Ms(129.333), Ms(3), Ms(1));
  EXPECT_NEAR(ToMs(w.deadline), 146.0, 0.01);
}

TEST(FramePacing, ARefreshRateChangeKeepsTheFramesInOrder)
{
  const DisplayTimeline at120{Ms(100), Ms(1000.0 / 120.0), Ms(2)};
  const FramePacing::Wake first = FramePacing::NextWake(at120, Ms(101), 1, 0, Ms(3), Ms(1));
  // The screen drops to 60 Hz, its refreshes on another phase.
  const DisplayTimeline at60{Ms(103), Ms(1000.0 / 60.0), Ms(4)};
  const FramePacing::Wake second = FramePacing::NextWake(at60, Ms(102), 1, first.deadline, Ms(3), Ms(1));
  EXPECT_GT(second.deadline, first.deadline);
  EXPECT_GE(ToMs(second.deadline - first.deadline), 8.0);
  EXPECT_GE(second.wake_time, Ms(102));
}

TEST(FramePacing, NoTimelineWakesNow)
{
  const FramePacing::Wake w = FramePacing::NextWake(DisplayTimeline{}, Ms(50), 1, 0, Ms(3), Ms(1));
  EXPECT_EQ(w.wake_time, Ms(50));
  EXPECT_EQ(w.deadline, 0u);
}

TEST(FramePacing, ShouldLockOnlyWhenEverythingAllows)
{
  EXPECT_TRUE(FramePacing::ShouldLock(true, true, 1, 1.0f, false));
  EXPECT_FALSE(FramePacing::ShouldLock(false, true, 1, 1.0f, false)); // sync to host off
  EXPECT_FALSE(FramePacing::ShouldLock(true, false, 1, 1.0f, false)); // no timeline
  EXPECT_FALSE(FramePacing::ShouldLock(true, true, 0, 1.0f, false));  // no multiple (PAL on 60 Hz)
  EXPECT_FALSE(FramePacing::ShouldLock(true, true, 1, 2.0f, false));  // fast-forward
  EXPECT_FALSE(FramePacing::ShouldLock(true, true, 1, 0.0f, false));  // unlimited
  EXPECT_FALSE(FramePacing::ShouldLock(true, true, 1, 1.0f, true));   // paused, stepping, rewinding
}

TEST(FramePacing, AnOverlongPredictionWaitsNoMoreThanItsRefreshes)
{
  // A frame time from a frame that never started (a state load, the first frame): at most the frame's own refreshes.
  const FramePacing::Wake w = FramePacing::NextWake(Screen60(), Ms(101), 1, 0, Ms(10000), Ms(1));
  EXPECT_GE(w.wake_time, Ms(101));
  EXPECT_LE(ToMs(w.deadline), 101.0 + 2 * (1000.0 / 60.0) + 1.0);
}
