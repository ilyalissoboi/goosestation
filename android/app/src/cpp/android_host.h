// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <string>
#include <string_view>

class Error;
struct ANativeWindow;

/// The Android host: the core's Host:: functions, the settings, the game's window and the input queue. The JNI bridge
/// calls the functions below from the app's threads; the core itself only runs on the CPU thread (emu_thread.h).
namespace AndroidHost {

/// Sets the folders, loads settings.ini (defaults when it's missing or from another version) and starts the CPU
/// thread. Calling it again once it succeeded does nothing and returns true.
bool Initialize(std::string data_root, std::string resources_root, Error* error);

/// Boots a game, once the app has given a surface. NotifyGameStarted() reports that it booted. Every call ends with
/// one NotifyGameStopped(): when the game stops, or straight away if it fails to boot (after NotifyError()), is
/// refused, or is cancelled by ShutdownGame() or another StartGame() before it boots.
void StartGame(std::string path);

/// Shuts the running game down, or cancels a boot still waiting for a surface.
void ShutdownGame();

void SetPaused(bool paused);

/// A new, resized or destroyed (nullptr) surface. Takes over one reference to the window, and returns once the GPU
/// device has stopped using the old one.
void SurfaceChanged(ANativeWindow* window, s32 width, s32 height, float scale);

/// Controller input, from any thread. It's queued and passed to the input source on the CPU thread; events for a
/// device only count once its connection is queued. Returns whether the key goes to the emulated pad.
bool QueueKeyEvent(s32 device_id, s32 keycode, bool pressed);
void QueueAxisEvent(s32 device_id, s32 axis, float value);
void QueueDeviceConnected(s32 device_id, std::string name, std::string descriptor);
void QueueDeviceDisconnected(s32 device_id);

/// Lets go of every controller input, from any thread, in order with the events queued before it. For when the app
/// stops getting a device's events (focus lost, in the background) and when a game boots or stops.
void QueueReleaseAllInputs();

/// CPU thread: boots the game StartGame() left pending, once there's a surface and the last game is destroyed. The
/// thread's loop calls it whenever System::Execute() has returned or its work is done, never from inside a blocking
/// call, so the app's thread isn't held up by a boot.
void BootPendingGame();

/// CPU thread: passes the queued input to the Android input source.
void ProcessInputQueue();

// Implemented by jni_bridge.cpp.

/// Attaches the calling thread (the CPU thread) to the JVM for the rest of its life, or detaches it.
void AttachCurrentThreadToJVM(const char* name);
void DetachCurrentThreadFromJVM();

/// Calls the app's NativeCallbacks, from any thread. Does nothing while the app has registered none.
void NotifyGameStarted(std::string_view title);
void NotifyGameStopped();
void NotifyError(std::string_view message);

} // namespace AndroidHost
