// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "gpu_thread_queue.h"

#include "common/align.h"
#include "common/assert.h"
#include "common/timer.h"

#include <chrono>

// Positions run from 0 to capacity - 1. The ring is empty when read == write, so a command may never move write onto
// read: there is always at least one free byte between the writer and the reader.

GPUThreadQueue::GPUThreadQueue(u32 capacity) : m_buffer(std::make_unique<u8[]>(capacity)), m_capacity(capacity)
{
  Assert(capacity >= 64 * 1024 && (capacity & (capacity - 1)) == 0);
}

GPUThreadQueue::~GPUThreadQueue() = default;

bool GPUThreadQueue::HasRoom(u32 read, u32 write, u32 size) const
{
  if (write + size <= m_capacity)
  {
    const u32 end = write + size;
    if (read > write)
      return (end < read);

    // An end at the capacity moves write to 0, which must not be where the reader is.
    return (end < m_capacity || read != 0);
  }

  // The command goes at the start, after a wraparound marker at write: the reader must be in [size + 1, write].
  return (read <= write && read > size);
}

void* GPUThreadQueue::Allocate(u8 type, u32 size)
{
  size = Common::AlignUpPow2(size, ALIGNMENT);
  DebugAssert(size >= sizeof(GPUThreadCommand) && size <= m_capacity / 2);

  const u32 write = m_write.load(std::memory_order_relaxed);
  if (!HasRoom(m_read.load(std::memory_order_acquire), write, size))
  {
    m_producer_waits.fetch_add(1, std::memory_order_relaxed);
    WakeConsumer();

    std::unique_lock lock(m_mutex);
    m_producer_waiting.store(true, std::memory_order_seq_cst);
    m_producer_cv.wait(lock, [this, write, size]() {
      return HasRoom(m_read.load(std::memory_order_seq_cst), write, size);
    });
    m_producer_waiting.store(false, std::memory_order_relaxed);
  }

  u32 start = write;
  if (write + size > m_capacity)
  {
    GPUThreadCommand* marker = reinterpret_cast<GPUThreadCommand*>(&m_buffer[write]);
    marker->size = m_capacity - write;
    marker->type = TYPE_WRAPAROUND;
    marker->params = 0;
    marker->reserved = 0;
    start = 0;
  }

  m_pending_end = (start + size) & (m_capacity - 1);

  GPUThreadCommand* cmd = reinterpret_cast<GPUThreadCommand*>(&m_buffer[start]);
  cmd->size = size;
  cmd->type = type;
  cmd->params = 0;
  cmd->reserved = 0;
  return cmd;
}

void GPUThreadQueue::Push()
{
  m_write.store(m_pending_end, std::memory_order_seq_cst);
  if (m_consumer_sleeping.load(std::memory_order_seq_cst))
  {
    std::unique_lock lock(m_mutex);
    m_consumer_cv.notify_one();
  }
}

bool GPUThreadQueue::Drain(const std::function<void(const GPUThreadCommand*)>& handler)
{
  u32 read = m_read.load(std::memory_order_relaxed);
  const u32 write = m_write.load(std::memory_order_acquire);
  if (read == write)
    return false;

  while (read != write)
  {
    const GPUThreadCommand* cmd = reinterpret_cast<const GPUThreadCommand*>(&m_buffer[read]);
    if (cmd->type == TYPE_WRAPAROUND)
    {
      read = 0;
      continue;
    }

    handler(cmd);
    read = (read + cmd->size) & (m_capacity - 1);
    m_read.store(read, std::memory_order_seq_cst);
    if (m_producer_waiting.load(std::memory_order_seq_cst))
      WakeProducer();
  }

  return true;
}

bool GPUThreadQueue::WaitForWork(u64 spin_ns)
{
  if (!IsEmpty())
    return true;

  if (spin_ns > 0)
  {
    const Common::Timer::Value start = Common::Timer::GetCurrentValue();
    while (Common::Timer::ConvertValueToNanoseconds(Common::Timer::GetCurrentValue() - start) < spin_ns)
    {
      if (!IsEmpty())
        return true;
      if (m_wake_requested.exchange(false, std::memory_order_acq_rel))
        return true;
      if (m_shutdown.load(std::memory_order_relaxed))
        return !IsEmpty();
    }
  }

  std::unique_lock lock(m_mutex);
  m_consumer_sleeping.store(true, std::memory_order_seq_cst);
  m_consumer_cv.wait(lock, [this]() {
    return !IsEmpty() || m_shutdown.load(std::memory_order_seq_cst) ||
           m_wake_requested.load(std::memory_order_seq_cst);
  });
  m_consumer_sleeping.store(false, std::memory_order_relaxed);
  m_wake_requested.store(false, std::memory_order_relaxed);
  return !IsEmpty() || !m_shutdown.load(std::memory_order_relaxed);
}

void GPUThreadQueue::Shutdown()
{
  std::unique_lock lock(m_mutex);
  m_shutdown.store(true, std::memory_order_seq_cst);
  m_consumer_cv.notify_one();
}

void GPUThreadQueue::WakeConsumer()
{
  std::unique_lock lock(m_mutex);
  m_wake_requested.store(true, std::memory_order_seq_cst);
  m_consumer_cv.notify_one();
}

void GPUThreadQueue::WakeProducer()
{
  std::unique_lock lock(m_mutex);
  m_producer_cv.notify_one();
}

bool GPUThreadQueue::IsEmpty() const
{
  return (m_read.load(std::memory_order_acquire) == m_write.load(std::memory_order_acquire));
}

u32 GPUThreadQueue::GetPendingBytes() const
{
  const u32 read = m_read.load(std::memory_order_acquire);
  const u32 write = m_write.load(std::memory_order_acquire);
  return (write >= read) ? (write - read) : (m_capacity - read + write);
}
