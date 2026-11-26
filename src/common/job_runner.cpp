// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "job_runner.h"
#include "threading.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <vector>

namespace {

// Shader compilers recurse deeply, and secondary threads get small stacks by default (512 KiB on macOS).
constexpr u32 WORKER_STACK_SIZE = 4 * 1024 * 1024;

struct JobState
{
  const std::function<bool(u32)>* job;
  u32 count;
  std::atomic<u32> next{0};
  std::atomic<u32> done{0};
  std::atomic<bool> failed{false};

  std::mutex lock;
  u32 running_workers = 0;
  std::condition_variable job_finished;

  // Runs one job if any are left: returns false once there are none, or a job has failed.
  bool RunOne()
  {
    if (failed.load(std::memory_order_acquire))
      return false;

    const u32 index = next.fetch_add(1, std::memory_order_relaxed);
    if (index >= count)
      return false;

    if (!(*job)(index))
      failed.store(true, std::memory_order_release);

    done.fetch_add(1, std::memory_order_acq_rel);
    return true;
  }

  void WorkerLoop()
  {
    while (RunOne())
      job_finished.notify_one();

    std::unique_lock lk(lock);
    running_workers--;
    job_finished.notify_one();
  }
};

} // namespace

bool Threading::RunJobs(u32 count, u32 threads, const std::function<bool(u32)>& job,
                        const std::function<void(u32)>& on_progress)
{
  if (count == 0)
    return true;

  JobState state;
  state.job = &job;
  state.count = count;

  std::vector<Threading::Thread> workers;
  const u32 num_workers = std::min(std::max(threads, 1u), count) - 1;
  workers.reserve(num_workers);
  for (u32 i = 0; i < num_workers; i++)
  {
    Threading::Thread& worker = workers.emplace_back();
    worker.SetStackSize(WORKER_STACK_SIZE);
    {
      std::unique_lock lk(state.lock);
      state.running_workers++;
    }
    if (!worker.Start([&state]() { state.WorkerLoop(); }))
    {
      // Fewer workers just means more for the others.
      std::unique_lock lk(state.lock);
      state.running_workers--;
      workers.pop_back();
      break;
    }
  }

  // The caller takes jobs too, reporting progress after each.
  while (state.RunOne())
  {
    if (on_progress)
      on_progress(state.done.load(std::memory_order_acquire));
  }

  // Then waits for the workers' last jobs, still reporting progress as they finish.
  {
    std::unique_lock lk(state.lock);
    while (state.running_workers > 0)
    {
      state.job_finished.wait_for(lk, std::chrono::milliseconds(50));
      if (on_progress)
      {
        lk.unlock();
        on_progress(state.done.load(std::memory_order_acquire));
        lk.lock();
      }
    }
  }
  for (Threading::Thread& worker : workers)
    worker.Join();

  if (on_progress)
    on_progress(state.done.load(std::memory_order_acquire));

  return !state.failed.load(std::memory_order_acquire);
}
