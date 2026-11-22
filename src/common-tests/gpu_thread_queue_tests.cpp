// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/gpu_thread_queue.h"

#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <random>
#include <thread>
#include <vector>

namespace {

constexpr u32 SMALL_RING = 64 * 1024;
constexpr u8 TEST_TYPE = GPUThreadQueue::FIRST_USER_TYPE;

// A command carrying its index and a byte pattern derived from it, filling the rest of its size.
struct TestCommand : GPUThreadCommand
{
  u32 index;
  u8 pattern[0];
};

u32 PatternSize(const GPUThreadCommand* cmd)
{
  return cmd->size - static_cast<u32>(sizeof(TestCommand));
}

void PushTestCommand(GPUThreadQueue& queue, u32 index, u32 size)
{
  TestCommand* cmd = static_cast<TestCommand*>(queue.Allocate(TEST_TYPE, size));
  cmd->index = index;
  for (u32 i = 0; i < PatternSize(cmd); i++) // the size as allocated, rounded up
    cmd->pattern[i] = static_cast<u8>(index + i);
  queue.Push();
}

bool PatternIsIntact(const TestCommand* cmd)
{
  for (u32 i = 0; i < PatternSize(cmd); i++)
  {
    if (cmd->pattern[i] != static_cast<u8>(cmd->index + i))
      return false;
  }
  return true;
}

} // namespace

TEST(GPUThreadQueue, CommandsArriveInOrderWithTheirPayloads)
{
  GPUThreadQueue queue(4 * 1024 * 1024); // holds all 1,000 at once: nothing drains while they go in
  std::mt19937 rng(1);
  std::vector<u32> sizes;
  for (u32 i = 0; i < 1000; i++)
  {
    const u32 size = std::uniform_int_distribution<u32>(sizeof(TestCommand), 4096)(rng);
    sizes.push_back(size);
    PushTestCommand(queue, i, size);
  }

  u32 next = 0;
  bool intact = true;
  EXPECT_TRUE(queue.Drain([&](const GPUThreadCommand* cmd) {
    ASSERT_EQ(cmd->type, TEST_TYPE);
    const TestCommand* tcmd = static_cast<const TestCommand*>(cmd);
    EXPECT_EQ(tcmd->index, next);
    EXPECT_GE(cmd->size, sizes[next]); // rounded up to the alignment
    intact &= PatternIsIntact(tcmd);
    next++;
  }));
  EXPECT_EQ(next, 1000u);
  EXPECT_TRUE(intact);
  EXPECT_TRUE(queue.IsEmpty());
  EXPECT_FALSE(queue.Drain([](const GPUThreadCommand*) {}));
}

TEST(GPUThreadQueue, ACommandNeverStraddlesTheEnd)
{
  GPUThreadQueue queue(SMALL_RING);
  const u8* const start = queue.GetBufferForTest();
  const u8* const end = start + SMALL_RING;

  // Leaves 40 bytes before the end, then asks for more than that, many times round.
  u32 seen = 0;
  bool inside = true;
  for (u32 round = 0; round < 50; round++)
  {
    PushTestCommand(queue, round * 2, SMALL_RING / 2 - 40);
    PushTestCommand(queue, round * 2 + 1, 64);
    queue.Drain([&](const GPUThreadCommand* cmd) {
      const u8* p = reinterpret_cast<const u8*>(cmd);
      inside &= (p >= start && p + cmd->size <= end);
      inside &= PatternIsIntact(static_cast<const TestCommand*>(cmd));
      seen++;
    });
  }
  EXPECT_TRUE(inside);
  EXPECT_EQ(seen, 100u);
}

TEST(GPUThreadQueue, AWrapWithTheReaderAtTheStartIsNotLost)
{
  GPUThreadQueue queue(SMALL_RING);

  // The reader stays at 0 while the writer reaches the end: the next command can't wrap onto the reader.
  std::thread producer([&queue]() {
    for (u32 i = 0; i < 4; i++)
      PushTestCommand(queue, i, SMALL_RING / 4 - 64);
    PushTestCommand(queue, 4, 1024); // must wait for the reader, not overwrite position 0
  });

  std::this_thread::sleep_for(std::chrono::milliseconds(50));
  std::vector<u32> seen;
  while (seen.size() < 5)
  {
    queue.Drain([&](const GPUThreadCommand* cmd) {
      const TestCommand* tcmd = static_cast<const TestCommand*>(cmd);
      EXPECT_TRUE(PatternIsIntact(tcmd));
      seen.push_back(tcmd->index);
    });
  }
  producer.join();
  EXPECT_EQ(seen, (std::vector<u32>{0, 1, 2, 3, 4}));
  EXPECT_TRUE(queue.IsEmpty());
}

TEST(GPUThreadQueue, AFullRingWaitsForTheReader)
{
  GPUThreadQueue queue(SMALL_RING);
  constexpr u32 SIZE = 1024;
  constexpr u32 COUNT = (SMALL_RING * 4) / SIZE;

  std::thread producer([&queue]() {
    for (u32 i = 0; i < COUNT; i++)
      PushTestCommand(queue, i, SIZE);
  });

  u32 next = 0;
  bool in_order = true;
  while (next < COUNT)
  {
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
    queue.Drain([&](const GPUThreadCommand* cmd) {
      in_order &= (static_cast<const TestCommand*>(cmd)->index == next++);
    });
  }
  producer.join();
  EXPECT_TRUE(in_order);
  EXPECT_GT(queue.GetProducerWaits(), 0u);
}

TEST(GPUThreadQueue, NothingIsLostUnderLoad)
{
  GPUThreadQueue queue(SMALL_RING);
  constexpr u32 COUNT = 1000000;

  u64 produced = 0;
  std::thread producer([&queue, &produced]() {
    std::mt19937 rng(2);
    for (u32 i = 0; i < COUNT; i++)
    {
      const u32 size = std::uniform_int_distribution<u32>(sizeof(TestCommand), 512)(rng);
      PushTestCommand(queue, i, size);
      produced += i;
    }
  });

  u32 count = 0;
  u64 consumed = 0;
  bool in_order = true;
  while (count < COUNT)
  {
    if (!queue.WaitForWork(100000))
      break;
    queue.Drain([&](const GPUThreadCommand* cmd) {
      const TestCommand* tcmd = static_cast<const TestCommand*>(cmd);
      in_order &= (tcmd->index == count) && PatternIsIntact(tcmd);
      consumed += tcmd->index;
      count++;
    });
  }
  producer.join();
  EXPECT_EQ(count, COUNT);
  EXPECT_EQ(consumed, produced);
  EXPECT_TRUE(in_order);
}

TEST(GPUThreadQueue, ShutdownWakesASleepingReader)
{
  GPUThreadQueue queue(SMALL_RING);
  std::atomic_bool returned{false};
  bool result = true;
  std::thread consumer([&]() {
    result = queue.WaitForWork(0);
    returned.store(true);
  });

  std::this_thread::sleep_for(std::chrono::milliseconds(20));
  EXPECT_FALSE(returned.load());
  const auto start = std::chrono::steady_clock::now();
  queue.Shutdown();
  consumer.join();
  EXPECT_LT(std::chrono::steady_clock::now() - start, std::chrono::milliseconds(100));
  EXPECT_FALSE(result);
}

TEST(GPUThreadQueue, APushWakesASleepingReader)
{
  GPUThreadQueue queue(SMALL_RING);
  bool result = false;
  std::thread consumer([&]() { result = queue.WaitForWork(0); });

  std::this_thread::sleep_for(std::chrono::milliseconds(20));
  PushTestCommand(queue, 0, 64);
  consumer.join();
  EXPECT_TRUE(result);
}

namespace {
void CommitTestCommand(GPUThreadQueue& queue, u32 index, u32 size)
{
  TestCommand* cmd = static_cast<TestCommand*>(queue.Allocate(TEST_TYPE, size));
  cmd->index = index;
  for (u32 i = 0; i < PatternSize(cmd); i++)
    cmd->pattern[i] = static_cast<u8>(index + i);
  queue.Commit();
}
} // namespace

// A committed command waits, with the others behind it, for the producer to publish them: the reader sees nothing
// until then.
TEST(GPUThreadQueue, ACommittedCommandWaitsForThePublish)
{
  GPUThreadQueue queue(SMALL_RING);
  CommitTestCommand(queue, 0, 64);
  CommitTestCommand(queue, 1, 64);
  EXPECT_TRUE(queue.IsEmpty());
  EXPECT_FALSE(queue.Drain([](const GPUThreadCommand*) {}));

  queue.Publish();
  u32 next = 0;
  EXPECT_TRUE(queue.Drain([&](const GPUThreadCommand* cmd) {
    EXPECT_EQ(static_cast<const TestCommand*>(cmd)->index, next++);
  }));
  EXPECT_EQ(next, 2u);
}

// Commands go out on their own once a batch of them is waiting, so the reader works alongside the producer.
TEST(GPUThreadQueue, CommittedCommandsPublishThemselvesPastABatch)
{
  GPUThreadQueue queue(SMALL_RING);
  u32 committed = 0;
  while (queue.IsEmpty())
  {
    ASSERT_LT(committed, GPUThreadQueue::PUBLISH_BYTES / 64 + 1);
    CommitTestCommand(queue, committed++, 64);
  }
  EXPECT_GE(queue.GetPendingBytes(), GPUThreadQueue::PUBLISH_BYTES);
}

// A producer that only commits still gets room: a full ring publishes what it holds before it waits.
TEST(GPUThreadQueue, AFullRingPublishesBeforeItWaits)
{
  GPUThreadQueue queue(SMALL_RING);
  constexpr u32 SIZE = 24;
  constexpr u32 COUNT = (SMALL_RING * 8) / SIZE;
  std::atomic_bool done{false};

  std::thread producer([&]() {
    for (u32 i = 0; i < COUNT; i++)
      CommitTestCommand(queue, i, SIZE);
    queue.Publish();
    done.store(true);
  });

  u32 next = 0;
  bool in_order = true;
  const auto start = std::chrono::steady_clock::now();
  while (next < COUNT && std::chrono::steady_clock::now() - start < std::chrono::seconds(10))
  {
    queue.Drain([&](const GPUThreadCommand* cmd) {
      in_order &= (static_cast<const TestCommand*>(cmd)->index == next++) &&
                  PatternIsIntact(static_cast<const TestCommand*>(cmd));
    });
  }
  ASSERT_EQ(next, COUNT);
  producer.join();
  EXPECT_TRUE(in_order);
  EXPECT_TRUE(done.load());
}
