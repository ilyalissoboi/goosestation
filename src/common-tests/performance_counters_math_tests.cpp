// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/performance_counters_math.h"

#include "common/timer.h"

#include <gtest/gtest.h>

namespace {
Common::Timer::Value Ms(double ms)
{
  return Common::Timer::ConvertNanosecondsToValue(ms * 1000000.0);
}
} // namespace

TEST(PerformanceCountersMath, ActiveTimeRunsToThePresent)
{
  EXPECT_EQ(PerformanceCountersMath::ActiveFrameTime(Ms(100), Ms(105), Ms(109)), Ms(9));
}

TEST(PerformanceCountersMath, ActiveTimeWithoutAPresentIsThePush)
{
  EXPECT_EQ(PerformanceCountersMath::ActiveFrameTime(Ms(100), Ms(105), 0), Ms(5));
}

TEST(PerformanceCountersMath, APresentFromAnEarlierFrameDoesntShortenIt)
{
  // The last present the GPU thread finished belongs to the frame before: the push is all that's known.
  EXPECT_EQ(PerformanceCountersMath::ActiveFrameTime(Ms(100), Ms(105), Ms(95)), Ms(5));
}

TEST(PerformanceCountersMath, AGarbageGPUTimeIsDropped)
{
  EXPECT_FALSE(PerformanceCountersMath::IsPlausibleGPUTime(1e13f, 16.7f));
  EXPECT_TRUE(PerformanceCountersMath::IsPlausibleGPUTime(12.0f, 16.7f));
  EXPECT_FALSE(PerformanceCountersMath::IsPlausibleGPUTime(-1.0f, 16.7f));
}
