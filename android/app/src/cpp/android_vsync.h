// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

struct DisplayTimeline;

/// The screen's refresh timeline, from Choreographer on a thread of its own (it needs a looper): each refresh's
/// expected present and deadline on Android 13 and up, the refresh time before that. Host::GetDisplayTimeline()
/// reads it.
namespace AndroidVsync {

/// Starts the thread, if it isn't running; false if Choreographer isn't there.
bool Start();

/// Stops it and forgets the timeline.
void Stop();

/// The latest refreshes, or false without a tick in the last 100 ms.
bool Get(DisplayTimeline* timeline);

} // namespace AndroidVsync
