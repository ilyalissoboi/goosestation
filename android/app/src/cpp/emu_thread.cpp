// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "emu_thread.h"
#include "android_host.h"

#include "core/system.h"

#include "common/error.h"
#include "common/log.h"
#include "common/threading.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <utility>
#include <vector>

Log_SetChannel(EmuThread);

namespace EmuThread {
static void ThreadMain();

// Without a game, the thread wakes at least this often to poll input and the core's background work.
static constexpr std::chrono::milliseconds IDLE_POLL_INTERVAL{100};

// The desktop's thread stack size, rather than bionic's 1 MB default.
static constexpr u32 STACK_SIZE = 8 * 1024 * 1024;

static Threading::Thread s_thread;
static thread_local bool s_is_cpu_thread = false;
static std::atomic_bool s_running{false};

static std::mutex s_mutex;
static std::condition_variable s_cv;
static std::vector<std::function<void()>> s_work;
static bool s_wake = false;

// Startup handshake between Start() and the thread.
static std::mutex s_startup_mutex;
static std::condition_variable s_startup_cv;
static bool s_startup_done = false;
static bool s_startup_result = false;
static Error s_startup_error;
} // namespace EmuThread

bool EmuThread::Start(Error* error)
{
  if (s_running.load(std::memory_order_acquire))
    return true;

  s_startup_done = false;
  s_thread.SetStackSize(STACK_SIZE);
  if (!s_thread.Start(ThreadMain))
  {
    Error::SetStringView(error, "Failed to start the CPU thread.");
    return false;
  }
  s_thread.Detach();

  std::unique_lock lock(s_startup_mutex);
  s_startup_cv.wait(lock, []() { return s_startup_done; });
  if (!s_startup_result && error)
    *error = s_startup_error;
  return s_startup_result;
}

bool EmuThread::IsOnThread()
{
  return s_is_cpu_thread;
}

void EmuThread::RunOnThread(std::function<void()> function, bool block)
{
  if (block && s_is_cpu_thread)
  {
    function();
    return;
  }

  if (!s_running.load(std::memory_order_acquire))
  {
    ERROR_LOG("The CPU thread isn't running, dropping a call to it.");
    return;
  }

  if (!block)
  {
    std::unique_lock lock(s_mutex);
    s_work.push_back(std::move(function));
    s_wake = true;
    s_cv.notify_one();
    return;
  }

  std::mutex done_mutex;
  std::condition_variable done_cv;
  bool done = false;
  {
    std::unique_lock lock(s_mutex);
    s_work.push_back([&function, &done_mutex, &done_cv, &done]() {
      function();
      std::unique_lock done_lock(done_mutex);
      done = true;
      done_cv.notify_one();
    });
    s_wake = true;
    s_cv.notify_one();
  }

  std::unique_lock done_lock(done_mutex);
  done_cv.wait(done_lock, [&done]() { return done; });
}

void EmuThread::Wake()
{
  std::unique_lock lock(s_mutex);
  s_wake = true;
  s_cv.notify_one();
}

void EmuThread::ProcessPendingWork()
{
  std::vector<std::function<void()>> work;
  {
    std::unique_lock lock(s_mutex);
    work.swap(s_work);
    s_wake = false;
  }

  for (std::function<void()>& function : work)
    function();

  AndroidHost::ProcessInputQueue();
}

void EmuThread::ThreadMain()
{
  s_is_cpu_thread = true;
  AndroidHost::AttachCurrentThreadToJVM("CPU Thread");

  Error error;
  bool started = false;
  if (System::Internal::PerformEarlyHardwareChecks(&error) && System::Internal::ProcessStartup(&error))
  {
    started = System::Internal::CPUThreadInitialize(&error);
    if (!started)
      System::Internal::ProcessShutdown();
  }

  if (started)
    s_running.store(true, std::memory_order_release);
  {
    std::unique_lock lock(s_startup_mutex);
    s_startup_done = true;
    s_startup_result = started;
    s_startup_error = std::move(error);
    s_startup_cv.notify_one();
  }

  if (!started)
  {
    AndroidHost::DetachCurrentThreadFromJVM();
    return;
  }

  // Like the Qt frontend's EmuThread::run(): a running game executes (and pumps this thread's work every frame);
  // otherwise the thread sleeps until there's work, and keeps polling input while paused or without a game, so the
  // input queue drains.
  for (;;)
  {
    if (System::IsRunning())
    {
      System::Execute();

      // A game shut down from inside a frame is only destroyed now, so a boot queued behind it can go ahead.
      AndroidHost::BootPendingGame();
      continue;
    }

    {
      std::unique_lock lock(s_mutex);
      s_cv.wait_for(lock, IDLE_POLL_INTERVAL, []() { return s_wake; });
    }

    ProcessPendingWork();
    AndroidHost::BootPendingGame();
    System::Internal::IdlePollUpdate();
  }
}
