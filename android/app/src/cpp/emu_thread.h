// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include <functional>
#include <type_traits>
#include <utility>

class Error;

/// The CPU thread: the core runs here and nowhere else. Other threads hand it work through RunOnThread().
namespace EmuThread {

/// Starts the thread, which runs the core's startup (PerformEarlyHardwareChecks, ProcessStartup,
/// CPUThreadInitialize), and waits for it. On success the thread then runs games and posted work until the process
/// exits.
bool Start(Error* error);

bool IsOnThread();

/// Queues a function for the CPU thread. With block, waits until it has run; on the CPU thread itself, a blocking call
/// runs the function straight away.
void RunOnThread(std::function<void()> function, bool block);

/// Runs function on the CPU thread, waiting for it, and returns what it returned; fallback if the thread isn't running,
/// as RunOnThread() drops the call then. On the CPU thread itself it runs straight away.
template<typename T, typename F>
T Call(T fallback, F&& function)
{
  T result = std::move(fallback);
  RunOnThread([&result, &function]() { result = function(); }, true);
  return result;
}

/// Call() with the result type's empty value as the fallback.
template<typename F>
auto Call(F&& function) -> std::invoke_result_t<F>
{
  return Call(std::invoke_result_t<F>{}, std::forward<F>(function));
}

/// Wakes the thread while no game is running, so it processes queued work and input straight away.
void Wake();

/// CPU thread: runs the queued functions and passes the queued input on. A running game calls it every frame, through
/// Host::PumpMessagesOnCPUThread().
void ProcessPendingWork();

} // namespace EmuThread
