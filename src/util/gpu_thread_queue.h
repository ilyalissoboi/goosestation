// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <atomic>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>

#ifdef _MSC_VER
#pragma warning(push)
#pragma warning(disable : 4324) // structure was padded due to alignment specifier
#endif

/// The header of every command in a GPUThreadQueue. size includes the header and is a multiple of ALIGNMENT.
struct GPUThreadCommand
{
  u32 size;
  u8 type;
  u8 params;
  u16 reserved;
};
static_assert(sizeof(GPUThreadCommand) == 8);

/// A ring of variable-size commands with one producer thread and one consumer thread. A command never straddles the
/// end of the ring; a full ring makes the producer wait for the consumer.
class GPUThreadQueue
{
public:
  static constexpr u8 TYPE_WRAPAROUND = 0;  // internal: the rest of the ring is unused, carry on from the start
  static constexpr u8 FIRST_USER_TYPE = 16; // 1..15 are GPUThread's own
  static constexpr u32 ALIGNMENT = 8;

  /// capacity is a power of two, at least 64 KiB.
  explicit GPUThreadQueue(u32 capacity);
  ~GPUThreadQueue();

  /// Producer: room for a command of size bytes (header included), its header filled. Waits while the ring is full.
  /// size is at most half the capacity.
  void* Allocate(u8 type, u32 size);

  /// Producer: publishes the last Allocate()'s command, and wakes a sleeping consumer.
  void Push();

  /// Consumer: hands every command published so far to handler, in order. False if there were none.
  bool Drain(const std::function<void(const GPUThreadCommand*)>& handler);

  /// Consumer: returns once there is work or WakeConsumer() was called, spinning for up to spin_ns before sleeping.
  /// False only after Shutdown() with nothing left.
  bool WaitForWork(u64 spin_ns);

  /// Wakes a sleeping consumer for good; WaitForWork() then returns false once the ring is empty.
  void Shutdown();

  /// Wakes a sleeping consumer without anything to do, so it can look at work it keeps elsewhere.
  void WakeConsumer();

  bool IsEmpty() const;
  u32 GetPendingBytes() const;

  /// How many times Allocate() has had to wait for room.
  u64 GetProducerWaits() const { return m_producer_waits.load(std::memory_order_relaxed); }

  const u8* GetBufferForTest() const { return m_buffer.get(); }

private:
  bool HasRoom(u32 read, u32 write, u32 size) const;
  void WakeProducer();

  std::unique_ptr<u8[]> m_buffer;
  u32 m_capacity;

  // Producer only.
  u32 m_pending_end = 0;

  alignas(64) std::atomic<u32> m_read{0};
  alignas(64) std::atomic<u32> m_write{0};

  alignas(64) std::atomic_bool m_consumer_sleeping{false};
  std::atomic_bool m_producer_waiting{false};
  std::atomic_bool m_shutdown{false};
  std::atomic_bool m_wake_requested{false};
  std::atomic<u64> m_producer_waits{0};

  std::mutex m_mutex;
  std::condition_variable m_consumer_cv;
  std::condition_variable m_producer_cv;
};

#ifdef _MSC_VER
#pragma warning(pop)
#endif
