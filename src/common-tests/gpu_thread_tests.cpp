// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/gpu_thread.h"

#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <mutex>
#include <thread>
#include <vector>

namespace {

constexpr u8 RECORD = GPUThreadQueue::FIRST_USER_TYPE;
constexpr u8 SLOW = GPUThreadQueue::FIRST_USER_TYPE + 1;
constexpr u8 PRESENT = GPUThreadQueue::FIRST_USER_TYPE + 2;

struct RecordCommand : GPUThreadCommand
{
  u32 value;
};

std::mutex s_lock;
std::vector<u32> s_record;
std::vector<std::thread::id> s_record_threads;
std::atomic<u32> s_slow_done{0};
std::atomic_bool s_hold_present{false};

void TestHandler(const GPUThreadCommand* cmd)
{
  switch (cmd->type)
  {
    case RECORD:
    {
      std::unique_lock lock(s_lock);
      s_record.push_back(static_cast<const RecordCommand*>(cmd)->value);
      s_record_threads.push_back(std::this_thread::get_id());
    }
    break;

    case SLOW:
      std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<const RecordCommand*>(cmd)->value));
      s_slow_done.fetch_add(1);
      break;

    case PRESENT:
      while (s_hold_present.load())
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
      GPUThread::FramePresented();
      break;

    default:
      FAIL() << "unknown command " << static_cast<u32>(cmd->type);
  }
}

void Push(u8 type, u32 value)
{
  RecordCommand* cmd = static_cast<RecordCommand*>(GPUThread::AllocateCommand(type, sizeof(RecordCommand)));
  cmd->value = value;
  GPUThread::PushCommand(cmd);
}

std::vector<u32> Recorded()
{
  std::unique_lock lock(s_lock);
  return s_record;
}

class GPUThreadTest : public ::testing::Test
{
protected:
  void SetUp() override
  {
    s_record.clear();
    s_record_threads.clear();
    s_slow_done.store(0);
    s_hold_present.store(false);
    GPUThread::SetCommandHandler(&TestHandler);
  }

  void TearDown() override
  {
    s_hold_present.store(false);
    GPUThread::Stop();
    GPUThread::SetRecycleHookForTest(nullptr);
  }
};

} // namespace

TEST_F(GPUThreadTest, InlineModeRunsACommandAtOnce)
{
  ASSERT_TRUE(GPUThread::Start(false));
  EXPECT_FALSE(GPUThread::IsUsingThread());
  EXPECT_TRUE(GPUThread::IsOnThread());
  EXPECT_EQ(GPUThread::GetThread(), nullptr);
  Push(RECORD, 7);
  EXPECT_EQ(Recorded(), std::vector<u32>{7});
  EXPECT_EQ(s_record_threads[0], std::this_thread::get_id());
}

TEST_F(GPUThreadTest, ThreadedCommandsRunInOrderOnTheThread)
{
  ASSERT_TRUE(GPUThread::Start(true));
  EXPECT_TRUE(GPUThread::IsUsingThread());
  EXPECT_FALSE(GPUThread::IsOnThread());
  ASSERT_NE(GPUThread::GetThread(), nullptr);
  for (u32 i = 0; i < 1000; i++)
    Push(RECORD, i);
  GPUThread::Sync(false);

  const std::vector<u32> recorded = Recorded();
  ASSERT_EQ(recorded.size(), 1000u);
  for (u32 i = 0; i < 1000; i++)
    EXPECT_EQ(recorded[i], i);
  for (const std::thread::id id : s_record_threads)
    EXPECT_NE(id, std::this_thread::get_id());
}

TEST_F(GPUThreadTest, RunOnThreadAndWaitSeesEarlierCommands)
{
  ASSERT_TRUE(GPUThread::Start(true));
  Push(SLOW, 20);
  Push(RECORD, 1);
  size_t seen = 0;
  bool on_thread = false;
  GPUThread::RunOnThreadAndWait([&]() {
    seen = Recorded().size();
    on_thread = GPUThread::IsOnThread();
  });
  EXPECT_EQ(seen, 1u);
  EXPECT_TRUE(on_thread);
}

TEST_F(GPUThreadTest, SyncReturnsAfterEverythingBefore)
{
  ASSERT_TRUE(GPUThread::Start(true));
  for (u32 i = 0; i < 5; i++)
    Push(SLOW, 5);
  GPUThread::Sync(true);
  EXPECT_EQ(s_slow_done.load(), 5u);
}

TEST_F(GPUThreadTest, RunOnThreadKeepsItsPlaceInTheStream)
{
  ASSERT_TRUE(GPUThread::Start(true));
  Push(RECORD, 1);
  GPUThread::RunOnThread([]() {
    std::unique_lock lock(s_lock);
    s_record.push_back(2);
  });
  Push(RECORD, 3);
  GPUThread::Sync(false);
  EXPECT_EQ(Recorded(), (std::vector<u32>{1, 2, 3}));
}

TEST_F(GPUThreadTest, APriorityClosureRunsBeforeQueuedWork)
{
  ASSERT_TRUE(GPUThread::Start(true));
  for (u32 i = 0; i < 100; i++)
    Push(SLOW, 2);

  u32 done_when_run = 0;
  std::thread other([&]() {
    GPUThread::RunOnThreadPriority([&]() { done_when_run = s_slow_done.load(); }, true);
  });
  other.join();
  EXPECT_LT(done_when_run, 99u);
  GPUThread::Sync(false);
  EXPECT_EQ(s_slow_done.load(), 100u);
}

TEST_F(GPUThreadTest, APriorityClosureRunsWhileIdle)
{
  ASSERT_TRUE(GPUThread::Start(true));
  GPUThread::Sync(false);
  std::this_thread::sleep_for(std::chrono::milliseconds(20)); // past the spin: asleep

  std::atomic_bool ran{false};
  std::thread::id ran_on;
  GPUThread::RunOnThreadPriority(
    [&]() {
      ran_on = std::this_thread::get_id();
      ran.store(true);
    },
    true);
  EXPECT_TRUE(ran.load());
  EXPECT_NE(ran_on, std::this_thread::get_id());
}

TEST_F(GPUThreadTest, ASecondFrameWaitsForTheFirst)
{
  ASSERT_TRUE(GPUThread::Start(true));
  s_hold_present.store(true);
  GPUThread::FramePushed();
  Push(PRESENT, 0);
  GPUThread::FramePushed();
  Push(PRESENT, 0);

  std::atomic_bool returned{false};
  std::thread waiter([&]() {
    GPUThread::WaitForQueuedFrames(1);
    returned.store(true);
  });
  std::this_thread::sleep_for(std::chrono::milliseconds(30));
  EXPECT_FALSE(returned.load());
  EXPECT_EQ(GPUThread::GetQueuedFrames(), 2u);

  s_hold_present.store(false);
  waiter.join();
  EXPECT_TRUE(returned.load());
  EXPECT_LE(GPUThread::GetQueuedFrames(), 1u);
}

TEST_F(GPUThreadTest, ZeroAheadWaitsForThePresent)
{
  ASSERT_TRUE(GPUThread::Start(true));
  Push(SLOW, 30);
  GPUThread::FramePushed();
  Push(PRESENT, 0);
  GPUThread::WaitForQueuedFrames(0);
  EXPECT_EQ(s_slow_done.load(), 1u);
  EXPECT_EQ(GPUThread::GetQueuedFrames(), 0u);
}

TEST_F(GPUThreadTest, MaxFramesAheadIsOneUnlessVSyncBlocks)
{
  EXPECT_EQ(GPUThread::MaxFramesAhead(false), 1u);
  EXPECT_EQ(GPUThread::MaxFramesAhead(true), 0u);
}

TEST_F(GPUThreadTest, StopWhileTheThreadIsBusyReturns)
{
  ASSERT_TRUE(GPUThread::Start(true));
  Push(SLOW, 200);
  const auto start = std::chrono::steady_clock::now();
  GPUThread::Stop();
  const auto elapsed = std::chrono::steady_clock::now() - start;
  EXPECT_EQ(s_slow_done.load(), 1u);
  EXPECT_LT(elapsed, std::chrono::seconds(1));
  EXPECT_FALSE(GPUThread::IsUsingThread());
}

// Android's surface changes come from the UI thread whenever they like, also while the thread stops and starts again
// (a device made again, the emulation thread shutting down): each one runs, once, and its caller returns.
TEST_F(GPUThreadTest, APriorityClosureRacingStopAndStartRunsOnce)
{
  std::atomic_bool done{false};
  std::atomic<u32> sent{0};
  std::atomic<u32> ran{0};
  std::thread ui([&]() {
    while (!done.load())
    {
      GPUThread::RunOnThreadPriority([&]() { ran.fetch_add(1); }, true);
      sent.fetch_add(1);
    }
  });

  for (u32 i = 0; i < 300; i++)
  {
    ASSERT_TRUE(GPUThread::Start((i % 3) != 0));
    Push(RECORD, i);
    GPUThread::Stop();
  }

  done.store(true);
  ui.join();
  EXPECT_EQ(ran.load(), sent.load());
}

TEST_F(GPUThreadTest, AForeignTextureDeleteWaitsForQueuedWork)
{
  ASSERT_TRUE(GPUThread::Start(true));
  static std::atomic<u32> slow_done_at_recycle{0};
  static std::atomic_bool recycled_on_thread{false};
  slow_done_at_recycle.store(0);
  GPUThread::SetRecycleHookForTest([](GPUTexture*) {
    slow_done_at_recycle.store(s_slow_done.load());
    recycled_on_thread.store(GPUThread::IsOnThread());
  });

  Push(SLOW, 20);
  GPUThread::TextureDeleter()(reinterpret_cast<GPUTexture*>(static_cast<uintptr_t>(0x1000)));
  GPUThread::Sync(false);
  EXPECT_EQ(slow_done_at_recycle.load(), 1u);
  EXPECT_TRUE(recycled_on_thread.load());
}

TEST_F(GPUThreadTest, WithoutADeviceTheInfoSaysSo)
{
  ASSERT_TRUE(GPUThread::Start(true));
  GPUThread::RunOnThreadAndWait(&GPUThread::PublishDeviceInfo);
  const GPUThread::DeviceInfo info = GPUThread::GetDeviceInfo();
  EXPECT_FALSE(info.has_device);
  EXPECT_EQ(info.window_width, 0);
}
