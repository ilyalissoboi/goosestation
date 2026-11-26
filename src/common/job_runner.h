// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "types.h"

#include <functional>

namespace Threading {

/// Runs job(i) for every i in [0, count) on (threads - 1) worker threads and the calling thread, and returns once every
/// started job has finished. A job that returns false stops any further jobs from starting, and makes this return
/// false. on_progress(done) is called on the calling thread only, as jobs finish, and once at the end. With one thread,
/// the jobs run in order on the calling thread.
bool RunJobs(u32 count, u32 threads, const std::function<bool(u32)>& job,
             const std::function<void(u32)>& on_progress = {});

} // namespace Threading
