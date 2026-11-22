// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/frame_pacing.h"

#include "common/timer.h"

#include <gtest/gtest.h>

#include <cmath>
#include <utility>
#include <vector>

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

TEST(FramePacing, ThePredictionIgnoresARareSpike)
{
  FramePacing::FrameTimePredictor predictor;
  for (int i = 0; i < 119; i++)
    predictor.Add(Ms(2.0));
  predictor.Add(Ms(12.0)); // a shader compile, a state load
  EXPECT_NEAR(ToMs(predictor.Predicted()), 2.0, 0.01);
}

TEST(FramePacing, ThePredictionFollowsFramesThatGetLonger)
{
  FramePacing::FrameTimePredictor predictor;
  for (int i = 0; i < 120; i++)
    predictor.Add(Ms(2.0));
  for (int i = 0; i < 30; i++)
    predictor.Add(Ms(6.0)); // a heavier scene: a quarter of the frames now
  EXPECT_NEAR(ToMs(predictor.Predicted()), 6.0, 0.01);
}

TEST(FramePacing, ThePredictionIsNothingWithoutFramesAndSkipsTheAbsurd)
{
  FramePacing::FrameTimePredictor predictor;
  EXPECT_EQ(predictor.Predicted(), 0u);
  predictor.Add(Ms(3.0));
  predictor.Add(Ms(5000.0)); // a frame that never started (the first, after a load)
  EXPECT_NEAR(ToMs(predictor.Predicted()), 3.0, 0.01);
  predictor.Reset();
  EXPECT_EQ(predictor.Predicted(), 0u);
}

namespace {
constexpr double PERIOD_MS = 1000.0 / 60.0;

// A screen that takes a frame started up to cutoff_ms past the timeline's deadline at the next refresh: its
// start-to-screen time falls as the offset grows, then jumps by a refresh. Once late, frames stay late until they
// start hysteresis_ms earlier than the cutoff (as the Thor does). Feedback comes lag frames late.
struct Screen
{
  double cutoff_ms;
  double hysteresis_ms = 3.0;
  double base_ms = 30.0;
  bool crossed = false;
  std::vector<std::pair<double, double>> in_flight; // start-to-screen times not yet reported, and their offsets
  int lag = 4;

  bool Present(FramePacing::CutoffTracker& tracker, bool ran_long = false)
  {
    const double offset_ms = ToMs(tracker.Offset());
    crossed = offset_ms > cutoff_ms - (crossed ? hysteresis_ms : 0.0);
    in_flight.emplace_back(base_ms - offset_ms + (crossed ? PERIOD_MS : 0.0), offset_ms);
    if (static_cast<int>(in_flight.size()) > lag)
    {
      tracker.Report(Ms(in_flight.front().first), Ms(in_flight.front().second), Ms(PERIOD_MS), ran_long);
      in_flight.erase(in_flight.begin());
    }
    return crossed;
  }

  int Run(FramePacing::CutoffTracker& tracker, int frames)
  {
    int missed = 0;
    for (int i = 0; i < frames; i++)
      missed += Present(tracker) ? 1 : 0;
    return missed;
  }
};
} // namespace

TEST(FramePacing, CutoffTrackerStartsAtTheDeadline)
{
  FramePacing::CutoffTracker tracker;
  EXPECT_EQ(tracker.Offset(), 0u);
}

TEST(FramePacing, CutoffTrackerFindsTheCutoffAndMissesRarely)
{
  // The Thor: frames can start up to 11 ms past the timeline's deadline and still make their refresh.
  FramePacing::CutoffTracker tracker;
  Screen screen{11.0};
  screen.Run(tracker, 60 * 60);                     // a minute to find it
  const int missed = screen.Run(tracker, 60 * 120); // then two more
  EXPECT_GE(ToMs(tracker.Offset()), 8.5);
  EXPECT_LE(ToMs(tracker.Offset()), 11.0);
  EXPECT_LE(missed, 20); // it rests below the cutoff: a crossing only when it looks again, each minute
}

TEST(FramePacing, CutoffTrackerTakesFramesInFlightAsOneMiss)
{
  FramePacing::CutoffTracker tracker;
  Screen screen{8.0};
  screen.lag = 6;
  screen.Run(tracker, 60 * 60);
  // However many late frames were already queued when the first came back, one step back, not a run of them.
  EXPECT_GE(ToMs(tracker.Offset()), 5.0);
}

TEST(FramePacing, CutoffTrackerBacksOffWhenTheCutoffMovesEarlier)
{
  FramePacing::CutoffTracker tracker;
  Screen screen{8.0};
  screen.Run(tracker, 60 * 60);
  screen.cutoff_ms = 4.0; // a busier compositor
  const int missed = screen.Run(tracker, 60 * 10);
  EXPECT_LE(ToMs(tracker.Offset()), 4.0);
  EXPECT_LE(missed, 30); // steps back, each with the frames already on their way
}

TEST(FramePacing, CutoffTrackerStaysInItsBounds)
{
  FramePacing::CutoffTracker tracker;
  Screen screen{50.0};
  screen.Run(tracker, 60 * 600); // never a miss: no more than 0.9 of a refresh
  EXPECT_LE(ToMs(tracker.Offset()), 0.9 * PERIOD_MS + 0.01);

  screen.cutoff_ms = 0.0; // the screen takes frames only up to the deadline itself
  screen.hysteresis_ms = 0.0;
  const int missed = screen.Run(tracker, 60 * 120);
  EXPECT_LE(ToMs(tracker.Offset()), 0.1);
  EXPECT_LE(missed, 70); // the whole way down once, then a frame or so each time it looks again
}

TEST(FramePacing, CutoffTrackerIgnoresAFrameThatRanLong)
{
  FramePacing::CutoffTracker tracker;
  Screen screen{8.0};
  screen.Run(tracker, 60 * 60);
  const auto settled = tracker.Offset();
  // A frame that took far longer than planned (a shader compile) is late whatever the offset: no lesson there.
  tracker.Report(Ms(30.0 - ToMs(settled) + PERIOD_MS), settled, Ms(PERIOD_MS), true);
  EXPECT_GE(tracker.Offset(), settled);
}

TEST(FramePacing, CutoffTrackerDoesNotHoldWhileItStarts)
{
  // The lock's first frames come out of a state load or a boot: a miss among them doesn't stop the search.
  FramePacing::CutoffTracker tracker;
  tracker.Report(Ms(60.0), 0, Ms(PERIOD_MS), false);
  Screen screen{8.0};
  screen.Run(tracker, 2 * FramePacing::CutoffTracker::WINDOW);
  EXPECT_GT(ToMs(tracker.Offset()), 1.0);
}

TEST(FramePacing, CutoffTrackerIsNotFooledByAnEarlyFrame)
{
  FramePacing::CutoffTracker tracker;
  Screen screen{8.0};
  screen.Run(tracker, 60 * 60);
  const auto settled = tracker.Offset();
  // One frame shown a refresh sooner than its usual: the frames after it, at their usual time, aren't late.
  tracker.Report(Ms(10.0), settled, Ms(PERIOD_MS), false);
  screen.Run(tracker, 60);
  EXPECT_GE(tracker.Offset() + Ms(1.6), settled);
}


TEST(FramePacing, TheConsolesRateIsAskedOfTheScreen)
{
  EXPECT_FLOAT_EQ(FramePacing::RequestedFrameRate(59.826f), 59.826f);
  EXPECT_FLOAT_EQ(FramePacing::RequestedFrameRate(50.0f), 50.0f);
  EXPECT_FLOAT_EQ(FramePacing::RequestedFrameRate(0.0f), 0.0f);  // no game: no request
  EXPECT_FLOAT_EQ(FramePacing::RequestedFrameRate(-1.0f), 0.0f);
}

TEST(FramePacing, VSyncIsOffWhileFastForwarding)
{
  EXPECT_TRUE(FramePacing::VSyncWhileFastForwarding(true, false));
  EXPECT_FALSE(FramePacing::VSyncWhileFastForwarding(true, true)); // fast-forward or turbo: not held to the screen
  EXPECT_FALSE(FramePacing::VSyncWhileFastForwarding(false, false));
  EXPECT_FALSE(FramePacing::VSyncWhileFastForwarding(false, true));
}

TEST(FramePacing, FramesLongerThanTheirRefreshesNeverWait)
{
  // A device that can't quite hold full speed: 17 ms of work a frame at 60 Hz. Unlocked it runs at 59 frames a
  // second; locked it mustn't sleep a refresh away after each frame and run at 30.
  Common::Timer::Value now = Ms(100);
  Common::Timer::Value last = 0;
  for (int i = 0; i < 60; i++)
  {
    const FramePacing::Wake w = FramePacing::NextWake(Screen60(), now, 1, last, Ms(17.0), Ms(2.0));
    EXPECT_EQ(w.wake_time, now) << "frame " << i;
    last = w.deadline;
    now += Ms(17.0);
  }
}
