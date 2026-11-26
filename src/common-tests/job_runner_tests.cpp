// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "common/job_runner.h"

#include <gtest/gtest.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

namespace {

TEST(JobRunner, EveryJobRunsOnce)
{
  static constexpr u32 COUNT = 1000;
  for (const u32 threads : {1u, 2u, 8u})
  {
    const std::unique_ptr<std::atomic<u32>[]> hits = std::make_unique<std::atomic<u32>[]>(COUNT);
    ASSERT_TRUE(Threading::RunJobs(COUNT, threads, [&hits](u32 i) {
      hits[i].fetch_add(1);
      return true;
    }));
    for (u32 i = 0; i < COUNT; i++)
      ASSERT_EQ(hits[i].load(), 1u) << "job " << i << " with " << threads << " threads";
  }
}

TEST(JobRunner, OneThreadRunsInOrderOnTheCaller)
{
  const std::thread::id caller = std::this_thread::get_id();
  std::vector<u32> order;
  bool all_on_caller = true;
  ASSERT_TRUE(Threading::RunJobs(50, 1, [&](u32 i) {
    order.push_back(i);
    all_on_caller &= (std::this_thread::get_id() == caller);
    return true;
  }));
  ASSERT_EQ(order.size(), 50u);
  for (u32 i = 0; i < 50; i++)
    EXPECT_EQ(order[i], i);
  EXPECT_TRUE(all_on_caller);
}

TEST(JobRunner, ProgressOnlyOnTheCaller)
{
  const std::thread::id caller = std::this_thread::get_id();
  std::vector<u32> values;
  bool all_on_caller = true;
  ASSERT_TRUE(Threading::RunJobs(
    200, 4,
    [](u32) {
      std::this_thread::yield();
      return true;
    },
    [&](u32 done) {
      values.push_back(done);
      all_on_caller &= (std::this_thread::get_id() == caller);
    }));
  ASSERT_FALSE(values.empty());
  EXPECT_TRUE(all_on_caller);
  for (size_t i = 1; i < values.size(); i++)
    EXPECT_LE(values[i - 1], values[i]);
  EXPECT_EQ(values.back(), 200u);
}

TEST(JobRunner, StopsIssuingAfterAFailureAndReportsIt)
{
  static constexpr u32 COUNT = 1000;
  std::atomic<u32> started{0};
  std::atomic<u32> finished{0};
  const bool result = Threading::RunJobs(COUNT, 4, [&](u32 i) {
    started.fetch_add(1);
    std::this_thread::sleep_for(std::chrono::microseconds(200));
    finished.fetch_add(1);
    return (i != 10);
  });
  EXPECT_FALSE(result);
  EXPECT_GT(started.load(), 10u);
  EXPECT_LT(started.load(), COUNT);
  EXPECT_EQ(started.load(), finished.load());
}

TEST(JobRunner, ZeroJobs)
{
  bool called = false;
  EXPECT_TRUE(Threading::RunJobs(
    0, 4,
    [&](u32) {
      called = true;
      return true;
    },
    [&](u32) { called = true; }));
  EXPECT_FALSE(called);
}

} // namespace
