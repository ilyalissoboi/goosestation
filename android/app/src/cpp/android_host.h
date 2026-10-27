// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <optional>
#include <string>
#include <string_view>
#include <vector>

class Error;
struct ANativeWindow;

/// The Android host: the core's Host:: functions, the settings, the game's window and the input queue. The JNI bridge
/// calls the functions below from the app's threads; the core itself only runs on the CPU thread (emu_thread.h).
namespace AndroidHost {

/// Sets the folders, loads settings.ini (defaults when it's missing or from another version, adapted when it's from
/// another install: AdaptForeignSettings()) and starts the CPU thread. The BIOS images are read from bios_dir, whatever
/// settings.ini says. Calling it again once it succeeded does nothing and returns true.
bool Initialize(std::string data_root, std::string resources_root, std::string bios_dir, std::string install_id,
                Error* error);

/// The settings below are the base layer's, settings.ini, and are saved straight away. From any thread; they do
/// nothing (or return the default) before Initialize() succeeded.

/// Scans exactly these folders, and all below them, for games: [GameList] RecursivePaths, with no Paths.
void SetGameFolders(std::vector<std::string> tree_uris);

/// Reads settings.ini again (after the app replaced it), adapts it if it's from another install, and applies it.
/// Blocks until the CPU thread has done so.
void ReloadSettings();

bool GetBoolSetting(const char* section, const char* key, bool default_value);
void SetBoolSetting(const char* section, const char* key, bool value);
std::string GetStringSetting(const char* section, const char* key, const char* default_value);
/// Deletes the key if value is nullptr.
void SetStringSetting(const char* section, const char* key, const char* value);
s32 GetIntSetting(const char* section, const char* key, s32 default_value);
void SetIntSetting(const char* section, const char* key, s32 value);

/// Applies the settings: System::ApplySettings(false). Blocks until the CPU thread has done so.
void ApplySettings();

/// Whether the Vulkan device failed to start in this process, so games use OpenGL ES until the app restarts. From any
/// thread.
bool IsVulkanFailed();

/// Where the memory cards are, relative to the data root (RelativeToDataRoot()): EmuFolders::MemoryCards with a
/// trailing '/' (empty if it's the data root), then the card files: [MemoryCards] Card1Path and Card2Path from
/// settings.ini and from every EmuFolders::GameSettings *.ini (which override them for a game), resolved as the core
/// does, then, while a game runs, the cards it has open and the shared card paths its settings give; each if it's set,
/// elsewhere in the data root and not listed already. Blocks until the CPU thread has read the game's. nullopt before
/// Initialize() succeeded.
std::optional<std::vector<std::string>> GetMemoryCardPaths();

/// The folders the core keeps its data in, relative to the data root (RelativeToDataRoot()), each with a trailing '/'
/// (empty if it's the data root): every EmuFolders folder but the cache, in EmuFolders::LoadConfig()'s order, if it's
/// inside the data root. nullopt before Initialize() succeeded.
std::optional<std::vector<std::string>> GetDataFolders();

/// EmuFolders::Cache as GetDataFolders() gives a folder: nullopt if it's outside the data root, or the data root
/// itself, or before Initialize() succeeded.
std::optional<std::string> GetCacheFolder();

/// Scans the game folders: GameList::Refresh(). Blocks, on the calling thread, which should be the JVM's; one refresh
/// runs at a time. Does nothing before Initialize() succeeded.
void RefreshGameList(bool invalidate_cache);

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
