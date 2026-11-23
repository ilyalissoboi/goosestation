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
  EXPECT_EQ(FramePacing::RefreshesPerFrame(118.36f, NTSC), 2u); // the Thor's 120 Hz panel, measured
  EXPECT_EQ(FramePacing::RefreshesPerFrame(59.77f, NTSC), 1u);  // and its 60 Hz
}

TEST(FramePacing, RefreshesPerFrameIsZeroWithoutOne)
{
  EXPECT_EQ(FramePacing::RefreshesPerFrame(60.0f, 50.0f), 0u);
  EXPECT_EQ(FramePacing::RefreshesPerFrame(144.0f, NTSC), 0u);
  EXPECT_EQ(FramePacing::RefreshesPerFrame(0.0f, NTSC), 0u);
  EXPECT_EQ(FramePacing::RefreshesPerFrame(60.0f, 0.0f), 0u);
  EXPECT_EQ(FramePacing::RefreshesPerFrame(300.0f, NTSC), 0u); // five refreshes a frame is past the limit
  EXPECT_EQ(FramePacing::RefreshesPerFrame(117.0f, NTSC), 0u); // 2.2% off two refreshes
  EXPECT_EQ(FramePacing::RefreshesPerFrame(61.3f, NTSC), 0u);  // 2.5% off one
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
    const double offset_ms = static_cast<double>(tracker.SignedOffset()) / Ms(1.0);
    crossed = offset_ms > cutoff_ms - (crossed ? hysteresis_ms : 0.0);
    in_flight.emplace_back(base_ms - offset_ms + (crossed ? PERIOD_MS : 0.0), offset_ms);
    if (static_cast<int>(in_flight.size()) > lag)
    {
      tracker.Report(Ms(in_flight.front().first), static_cast<s64>(in_flight.front().second * Ms(1.0)), Ms(PERIOD_MS),
                     ran_long);
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

TEST(FramePacing, CutoffTrackerStartsHalfARefreshBeforeTheDeadline)
{
  // On the safe side, to creep up to the cutoff from below.
  FramePacing::CutoffTracker tracker;
  tracker.Report(Ms(30.0), 0, Ms(PERIOD_MS), false);
  EXPECT_LE(tracker.SignedOffset(), -static_cast<s64>(Ms(PERIOD_MS / 2)) + static_cast<s64>(Ms(0.1)));
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
  tracker.Report(Ms(30.0 - ToMs(settled) + PERIOD_MS), static_cast<s64>(settled), Ms(PERIOD_MS), true);
  EXPECT_GE(tracker.Offset(), settled);
}

TEST(FramePacing, CutoffTrackerDoesNotHoldWhileItStarts)
{
  // The lock's first frames come out of a state load or a boot: a miss among them doesn't stop the search.
  FramePacing::CutoffTracker tracker;
  tracker.Report(Ms(60.0), 0, Ms(PERIOD_MS), false);
  const s64 start = tracker.SignedOffset();
  Screen screen{8.0};
  screen.Run(tracker, 2 * FramePacing::CutoffTracker::WINDOW);
  EXPECT_GT(tracker.SignedOffset() - start, static_cast<s64>(Ms(5.0)));
}

TEST(FramePacing, CutoffTrackerIsNotFooledByAnEarlyFrame)
{
  FramePacing::CutoffTracker tracker;
  Screen screen{8.0};
  screen.Run(tracker, 60 * 60);
  const auto settled = tracker.Offset();
  // One frame shown a refresh sooner than its usual: the frames after it, at their usual time, aren't late.
  tracker.Report(Ms(10.0), static_cast<s64>(settled), Ms(PERIOD_MS), false);
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
  Common::Timer::Value last = 0, last_wake = 0;
  for (int i = 0; i < 60; i++)
  {
    const FramePacing::Wake w = FramePacing::NextWake(Screen60(), now, 1, last, Ms(17.0), Ms(2.0), last_wake);
    EXPECT_EQ(w.wake_time, now) << "frame " << i;
    last = w.deadline;
    last_wake = w.wake_time;
    now += Ms(17.0);
  }
}

TEST(FramePacing, AnOverlongPlanStillRunsNoFasterThanItsRefreshes)
{
  // Planned long (the present's own wait counted in), but the frames themselves take 5 ms: they mustn't start more
  // often than their refreshes, or the game runs fast.
  Common::Timer::Value now = Ms(100);
  Common::Timer::Value last = 0, last_wake = 0;
  for (int i = 0; i < 60; i++)
  {
    const FramePacing::Wake w = FramePacing::NextWake(Screen60(), now, 1, last, Ms(17.0), Ms(2.0), last_wake);
    if (i > 0)
      EXPECT_GE(ToMs(w.wake_time - last_wake), 1000.0 / 60.0 - 0.01) << "frame " << i;
    last_wake = w.wake_time;
    last = w.deadline;
    now = w.wake_time + Ms(5.0);
  }
}

TEST(FramePacing, TheLockChangesOnlyOnceTheChangeHolds)
{
  FramePacing::LockDebounce lock;
  for (int i = 0; i < FramePacing::LockDebounce::HOLD_FRAMES - 1; i++)
    EXPECT_EQ(lock.Update(2), 0u); // not yet: wanted for fewer than HOLD_FRAMES frames
  EXPECT_EQ(lock.Update(2), 2u);

  // One odd frame from the timeline (a skipped tick, a stale reading) changes nothing.
  EXPECT_EQ(lock.Update(0), 2u);
  EXPECT_EQ(lock.Update(1), 2u);
  EXPECT_EQ(lock.Update(2), 2u);

  // A real change (the screen to 60 Hz) does, once it has held.
  u32 now = 2;
  for (int i = 0; i < FramePacing::LockDebounce::HOLD_FRAMES; i++)
    now = lock.Update(1);
  EXPECT_EQ(now, 1u);

  lock.Reset();
  EXPECT_EQ(lock.Update(1), 0u);
}

TEST(FramePacing, CutoffTrackerGoesBeforeTheDeadlineWhenTheScreenNeedsIt)
{
  // A screen (the Thor at 120 Hz) that only takes frames done 3 ms before the timeline's deadline.
  FramePacing::CutoffTracker tracker;
  Screen screen{-3.0, 1.0};
  screen.Run(tracker, 60 * 30);
  const int missed = screen.Run(tracker, 60 * 60);
  EXPECT_LE(tracker.SignedOffset(), -static_cast<s64>(Ms(3.0)));
  EXPECT_GE(tracker.SignedOffset(), -static_cast<s64>(Ms(PERIOD_MS)) / 2);
  EXPECT_LE(missed, 10);
}

TEST(FramePacing, ThePeriodSkipsAMissedTickButFollowsANewRate)
{
  FramePacing::PeriodEstimator period;
  for (int i = 0; i < 20; i++)
    period.Add(Ms(1000.0 / 120.0));
  EXPECT_NEAR(ToMs(period.Period()), 1000.0 / 120.0, 0.01);

  // One tick skipped: twice the gap once, no change.
  period.Add(Ms(2000.0 / 120.0));
  EXPECT_NEAR(ToMs(period.Period()), 1000.0 / 120.0, 0.01);

  // The screen drops to 60 Hz and stays there: the new rate is taken once it has held.
  for (int i = 0; i < FramePacing::PeriodEstimator::NEW_RATE_TICKS; i++)
    period.Add(Ms(1000.0 / 60.0));
  EXPECT_NEAR(ToMs(period.Period()), 1000.0 / 60.0, 0.05);

  // And back up to 120 Hz just as surely.
  for (int i = 0; i < FramePacing::PeriodEstimator::NEW_RATE_TICKS; i++)
    period.Add(Ms(1000.0 / 120.0));
  EXPECT_NEAR(ToMs(period.Period()), 1000.0 / 120.0, 0.05);
}

TEST(FramePacing, TheWakeKnowsItsRefresh)
{
  // Due 4 ms before the refresh it's for: 112.67 is the deadline of the refresh at 116.67.
  const FramePacing::Wake w = FramePacing::NextWake(Screen60(), Ms(101), 1, 0, Ms(3), Ms(1));
  EXPECT_NEAR(ToMs(w.refresh), ToMs(w.deadline) + 4.0, 0.001);
  EXPECT_NEAR(ToMs(w.refresh), 116.667, 0.01);

  EXPECT_EQ(FramePacing::NextWake(DisplayTimeline{}, Ms(50), 1, 0, Ms(3), Ms(1)).refresh, 0u);
}

TEST(FramePacing, TwoRefreshesAFrameAskNotToBeShownEarly)
{
  // 120 Hz: a frame on two (or more) refreshes may be shown no sooner than half a refresh before its own.
  const double p = 1000.0 / 120.0;
  EXPECT_NEAR(ToMs(FramePacing::DesiredPresent(Ms(500), Ms(p), 2)), 500.0 - p / 2, 0.001);
  EXPECT_NEAR(ToMs(FramePacing::DesiredPresent(Ms(500), Ms(p), 3)), 500.0 - p / 2, 0.001);
  EXPECT_EQ(FramePacing::DesiredPresent(Ms(500), Ms(p), 1), 0u); // one refresh a frame: no request
  EXPECT_EQ(FramePacing::DesiredPresent(0, Ms(p), 2), 0u);       // no refresh known
}

TEST(FramePacing, VSyncWantedFollowsTheMode)
{
  EXPECT_TRUE(FramePacing::VSyncWanted(DisplayVSyncMode::On, false, false));
  EXPECT_FALSE(FramePacing::VSyncWanted(DisplayVSyncMode::Off, true, true));
  EXPECT_TRUE(FramePacing::VSyncWanted(DisplayVSyncMode::Automatic, true, true));
  EXPECT_FALSE(FramePacing::VSyncWanted(DisplayVSyncMode::Automatic, true, false));
  EXPECT_FALSE(FramePacing::VSyncWanted(DisplayVSyncMode::Automatic, false, true));
}

TEST(FramePacing, FifoOnlyWhereTheLockCanRun)
{
  EXPECT_TRUE(FramePacing::FifoForLock(true, true, true, 60.0f, NTSC));
  EXPECT_TRUE(FramePacing::FifoForLock(true, true, true, 120.0f, NTSC));
  EXPECT_FALSE(FramePacing::FifoForLock(true, true, true, 144.0f, NTSC));
  EXPECT_FALSE(FramePacing::FifoForLock(true, true, true, 60.0f, 50.0f));
  EXPECT_FALSE(FramePacing::FifoForLock(true, true, false, 60.0f, NTSC));
  EXPECT_FALSE(FramePacing::FifoForLock(true, false, true, 60.0f, NTSC));
  EXPECT_FALSE(FramePacing::FifoForLock(false, true, true, 60.0f, NTSC));
}

namespace {
const Common::Timer::Value P60 = Ms(1000.0 / 60.0);

void OnTime(FramePacing::PresentWaitDepth& d, u32 frames, u32 refreshes = 1)
{
  for (u32 i = 0; i < frames; i++)
    d.Report(P60 * refreshes, P60, refreshes);
}

void Miss(FramePacing::PresentWaitDepth& d)
{
  d.Report(P60 * 2, P60, 1);
}

// Past its first frames, which aren't judged.
FramePacing::PresentWaitDepth Settled()
{
  FramePacing::PresentWaitDepth d;
  OnTime(d, FramePacing::PresentWaitDepth::JUDGE_AFTER);
  return d;
}
} // namespace

TEST(PresentWaitDepth, StartsAtTheFrameJustPresented)
{
  FramePacing::PresentWaitDepth d;
  EXPECT_EQ(d.Depth(), 0u);
}

TEST(PresentWaitDepth, TwoMissesWaitForTheFrameBefore)
{
  FramePacing::PresentWaitDepth d = Settled();
  Miss(d);
  OnTime(d, 50);
  EXPECT_EQ(d.Depth(), 0u); // one miss alone
  Miss(d);
  EXPECT_EQ(d.Depth(), 1u);

  FramePacing::PresentWaitDepth far = Settled();
  Miss(far);
  OnTime(far, FramePacing::PresentWaitDepth::MISS_WINDOW + 1);
  Miss(far);
  EXPECT_EQ(far.Depth(), 0u); // too far apart
}

TEST(PresentWaitDepth, TheFirstFramesAreNotJudged)
{
  FramePacing::PresentWaitDepth d;
  Miss(d);
  Miss(d);
  EXPECT_EQ(d.Depth(), 0u);
}

TEST(PresentWaitDepth, ItTriesAgainAfterItsHold)
{
  FramePacing::PresentWaitDepth d = Settled();
  Miss(d);
  Miss(d);
  ASSERT_EQ(d.Depth(), 1u);
  OnTime(d, FramePacing::PresentWaitDepth::FIRST_HOLD - 1);
  EXPECT_EQ(d.Depth(), 1u);
  OnTime(d, 1);
  EXPECT_EQ(d.Depth(), 0u);
}

TEST(PresentWaitDepth, AFailedTryDoublesTheHold)
{
  using D = FramePacing::PresentWaitDepth;
  D d = Settled();
  Miss(d);
  Miss(d);
  OnTime(d, D::FIRST_HOLD);
  ASSERT_EQ(d.Depth(), 0u);

  u32 hold = D::FIRST_HOLD;
  for (u32 attempt = 0; attempt < 6; attempt++)
  {
    // The try fails at once: back to the frame before, for twice as long, never past MAX_HOLD.
    Miss(d);
    Miss(d);
    ASSERT_EQ(d.Depth(), 1u);
    hold = std::min(hold * 2, D::MAX_HOLD);
    OnTime(d, hold - 1);
    EXPECT_EQ(d.Depth(), 1u) << "attempt " << attempt;
    OnTime(d, 1);
    EXPECT_EQ(d.Depth(), 0u) << "attempt " << attempt;
  }
}

TEST(PresentWaitDepth, ATryThatHoldsResetsTheHold)
{
  using D = FramePacing::PresentWaitDepth;
  D d = Settled();
  Miss(d);
  Miss(d);
  OnTime(d, D::FIRST_HOLD);
  Miss(d);
  Miss(d); // failed: the hold doubles
  OnTime(d, D::FIRST_HOLD * 2);
  ASSERT_EQ(d.Depth(), 0u);
  OnTime(d, D::MISS_WINDOW); // this try holds
  Miss(d);
  Miss(d);
  OnTime(d, D::FIRST_HOLD);
  EXPECT_EQ(d.Depth(), 0u); // back to the first hold
}

TEST(PresentWaitDepth, TheLocksRefreshesAreNotMisses)
{
  FramePacing::PresentWaitDepth d = Settled();
  OnTime(d, 200, 2);
  EXPECT_EQ(d.Depth(), 0u);
  d.Report(P60 * 3, P60, 2);
  d.Report(P60 * 3, P60, 2);
  EXPECT_EQ(d.Depth(), 1u);
}

TEST(PresentWaitDepth, ATimeoutPausesWaits)
{
  using D = FramePacing::PresentWaitDepth;
  D d = Settled();
  d.Report(0, P60, 1);
  for (u32 i = 0; i < D::TIMEOUT_PAUSE; i++)
  {
    ASSERT_EQ(d.Depth(), D::NO_WAIT) << i;
    d.NotWaited(false);
  }
  EXPECT_EQ(d.Depth(), 0u);
}

TEST(PresentWaitDepth, RepeatedTimeoutsPauseLonger)
{
  // A window that never presents (minimized, a locked screen) costs a timeout ever more rarely.
  using D = FramePacing::PresentWaitDepth;
  D d = Settled();
  u32 pause = D::TIMEOUT_PAUSE;
  for (u32 timeout = 0; timeout < 10; timeout++)
  {
    d.Report(0, P60, 1);
    for (u32 i = 0; i < pause; i++)
    {
      ASSERT_EQ(d.Depth(), D::NO_WAIT) << "timeout " << timeout << " frame " << i;
      d.NotWaited(false);
    }
    EXPECT_EQ(d.Depth(), 0u) << "timeout " << timeout;
    pause = std::min(pause * 2, D::MAX_TIMEOUT_PAUSE);
  }
}

TEST(PresentWaitDepth, AShownPresentEndsThePause)
{
  using D = FramePacing::PresentWaitDepth;
  D d = Settled();
  d.Report(0, P60, 1);
  d.NotWaited(false);
  ASSERT_EQ(d.Depth(), D::NO_WAIT);
  d.NotWaited(true);
  EXPECT_EQ(d.Depth(), 0u);

  // And the next timeout pauses for the first pause again.
  d.Report(0, P60, 1);
  for (u32 i = 0; i < D::TIMEOUT_PAUSE; i++)
    d.NotWaited(false);
  EXPECT_EQ(d.Depth(), 0u);
}

TEST(PresentWaitDepth, AWaitThatReturnsResetsTheBackoff)
{
  using D = FramePacing::PresentWaitDepth;
  D d = Settled();
  for (u32 timeout = 0; timeout < 2; timeout++)
  {
    d.Report(0, P60, 1);
    while (d.Depth() == D::NO_WAIT)
      d.NotWaited(false);
  }
  OnTime(d, 1);
  d.Report(0, P60, 1);
  for (u32 i = 0; i < D::TIMEOUT_PAUSE; i++)
    d.NotWaited(false);
  EXPECT_EQ(d.Depth(), 0u);
}

TEST(PresentWaitDepth, ResetStartsOver)
{
  FramePacing::PresentWaitDepth d = Settled();
  Miss(d);
  Miss(d);
  ASSERT_EQ(d.Depth(), 1u);
  d.Reset();
  EXPECT_EQ(d.Depth(), 0u);
}

namespace {
// Presents shown from 100 ms on, every interval_ms (with each frame's offset from offsets_ms, cycled).
FramePacing::PresentTimeline ShownEvery(double interval_ms, float reported_hz, u32 count,
                                        std::vector<double> offsets_ms = {0.0})
{
  FramePacing::PresentTimeline t;
  for (u32 i = 0; i < count; i++)
    t.Add(Ms(100.0 + i * interval_ms + offsets_ms[i % offsets_ms.size()]), reported_hz);
  return t;
}
} // namespace

TEST(PresentTimeline, SixtyHertzWithJitter)
{
  const FramePacing::PresentTimeline t = ShownEvery(16.683, 60.0f, 40, {0.0, 0.3, 0.0, -0.3});
  DisplayTimeline tl;
  const Common::Timer::Value last = Ms(100.0 + 39 * 16.683 - 0.3);
  ASSERT_TRUE(t.Get(last + Ms(1), &tl));
  EXPECT_NEAR(ToMs(tl.period), 16.683, 0.05);
  EXPECT_EQ(tl.vsync_time, last);
  EXPECT_NEAR(ToMs(tl.deadline_lead), ToMs(tl.period) / 2, 0.001);
}

TEST(PresentTimeline, TwoRefreshesAFrameAt120)
{
  const FramePacing::PresentTimeline t = ShownEvery(1000.0 / 60.0, 120.0f, 40);
  DisplayTimeline tl;
  ASSERT_TRUE(t.Get(Ms(100.0 + 39 * 1000.0 / 60.0 + 1), &tl));
  EXPECT_NEAR(ToMs(tl.period), 1000.0 / 120.0, 0.05);
}

TEST(PresentTimeline, ARepeatedFrameDoesntStretchThePeriod)
{
  FramePacing::PresentTimeline t;
  double time = 100.0;
  for (u32 i = 0; i < 40; i++)
  {
    time += (i == 20) ? 2000.0 / 60.0 : 1000.0 / 60.0;
    t.Add(Ms(time), 60.0f);
  }
  DisplayTimeline tl;
  ASSERT_TRUE(t.Get(Ms(time + 1), &tl));
  EXPECT_NEAR(ToMs(tl.period), 1000.0 / 60.0, 0.05);
}

TEST(PresentTimeline, StaleAfter100ms)
{
  const FramePacing::PresentTimeline t = ShownEvery(1000.0 / 60.0, 60.0f, 40);
  const double last = 100.0 + 39 * 1000.0 / 60.0;
  DisplayTimeline tl;
  EXPECT_TRUE(t.Get(Ms(last + 99), &tl));
  EXPECT_FALSE(t.Get(Ms(last + 101), &tl));
}

TEST(PresentTimeline, TooFewIntervalsIsNone)
{
  const FramePacing::PresentTimeline t =
    ShownEvery(1000.0 / 60.0, 60.0f, FramePacing::PresentTimeline::MIN_INTERVALS);
  DisplayTimeline tl;
  EXPECT_FALSE(t.Get(Ms(100.0 + FramePacing::PresentTimeline::MIN_INTERVALS * 1000.0 / 60.0), &tl));
  const FramePacing::PresentTimeline enough =
    ShownEvery(1000.0 / 60.0, 60.0f, FramePacing::PresentTimeline::MIN_INTERVALS + 1);
  EXPECT_TRUE(enough.Get(Ms(100.0 + FramePacing::PresentTimeline::MIN_INTERVALS * 1000.0 / 60.0), &tl));
}

TEST(PresentTimeline, ResetForgetsTheOldScreen)
{
  FramePacing::PresentTimeline t = ShownEvery(1000.0 / 60.0, 60.0f, 40);
  t.Reset();
  DisplayTimeline tl;
  EXPECT_FALSE(t.Get(Ms(100.0 + 39 * 1000.0 / 60.0 + 1), &tl));
}

TEST(FramePacing, TimelineFromFeedbackOnlyWithoutFifo)
{
  // Metal: presents unsynced, its presented handlers' times make the timeline.
  EXPECT_TRUE(FramePacing::TimelineFromFeedback(false, true, true));
  // Blocking presents: the waits make it.
  EXPECT_FALSE(FramePacing::TimelineFromFeedback(true, true, true));
  // MoltenVK, Linux's Vulkan: feedback, but they don't lock from it: unchanged.
  EXPECT_FALSE(FramePacing::TimelineFromFeedback(false, false, true));
  // D3D, Vulkan without display timing: nothing to make it from.
  EXPECT_FALSE(FramePacing::TimelineFromFeedback(false, true, false));
}

TEST(FramePacing, SyncToHostNeedsVSyncOnDesktop)
{
  EXPECT_FALSE(FramePacing::SyncsSpeedToHost(true, false, true));
  EXPECT_TRUE(FramePacing::SyncsSpeedToHost(true, true, true));
  EXPECT_TRUE(FramePacing::SyncsSpeedToHost(true, false, false)); // Android, as before
  EXPECT_FALSE(FramePacing::SyncsSpeedToHost(false, true, true));
  EXPECT_FALSE(FramePacing::SyncsSpeedToHost(false, true, false));
}

TEST(PresentTimeline, AVariableRefreshScreenHasNone)
{
  // ProMotion with unsynced presents: frames shown at 80, 60 and 48 Hz steps, not on whole refreshes of 120.
  FramePacing::PresentTimeline t;
  const double steps[] = {12.5, 16.667, 20.833, 16.667, 12.5, 25.0};
  double time = 100.0;
  for (u32 i = 0; i < 60; i++)
  {
    time += steps[i % std::size(steps)];
    t.Add(Ms(time), 120.0f);
  }
  DisplayTimeline tl;
  EXPECT_FALSE(t.Get(Ms(time + 1), &tl));
}

TEST(PresentTimeline, AFewOddIntervalsStillMakeOne)
{
  // A fixed 60 Hz screen with the odd late callback: still a timeline.
  FramePacing::PresentTimeline t;
  double time = 100.0;
  for (u32 i = 0; i < 60; i++)
  {
    time += (i % 20 == 7) ? 12.0 : 1000.0 / 60.0;
    t.Add(Ms(time), 60.0f);
  }
  DisplayTimeline tl;
  ASSERT_TRUE(t.Get(Ms(time + 1), &tl));
  EXPECT_NEAR(ToMs(tl.period), 1000.0 / 60.0, 0.05);
}
