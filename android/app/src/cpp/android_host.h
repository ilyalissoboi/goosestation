// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <utility>
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

/// Reads settings.ini again (after the app replaced it), adapts it if it's from another install, and applies it. Each
/// connected controller the file leaves with no binding of its own gets its defaults. Blocks until the CPU thread has
/// done so.
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

/// The folder the BIOS images are read from, EmuFolders::Bios: Initialize()'s bios_dir, canonicalized, whatever
/// settings.ini says. From any thread. nullopt before Initialize() succeeded.
std::optional<std::string> GetBIOSDirectory();

/// Scans the game folders: GameList::Refresh(). Blocks, on the calling thread, which should be the JVM's; one refresh
/// runs at a time. Does nothing before Initialize() succeeded.
void RefreshGameList(bool invalidate_cache);

/// Boots a game, once the app has given a surface, from resume_serial's resume state if that isn't empty.
/// NotifyGameStarted() reports that it booted. Every call ends with one NotifyGameStopped(): when the game stops, or
/// straight away if it fails to boot (after NotifyError()), is refused, or is cancelled by ShutdownGame() or another
/// StartGame() before it boots.
void StartGame(std::string path, std::string resume_serial);

/// Shuts the running game down, or cancels a boot still waiting for a surface. With save_resume_state, a game with a
/// serial first saves its resume state, if [Main] SaveStateOnExit is on; a failure is reported with NotifyError(), and
/// the game stops all the same.
void ShutdownGame(bool save_resume_state);

/// A picture from the core: width x height RGBA8 pixels.
struct Picture
{
  u32 width;
  u32 height;
  std::vector<u32> pixels;
};

/// The in-game menu's calls. Each runs on the CPU thread; the first five block until it has.

/// The modified times (Unix seconds) of serial's save states, 0 for none: [0] the resume state, [n] slot n, for
/// System::PER_GAME_SAVE_STATE_SLOTS slots. All 0 for an empty serial, or before Initialize() succeeded.
std::vector<s64> GetSaveStateTimes(std::string serial);

/// The screenshot inside serial's state in slot (-1 for the resume state); nullopt if there's none or it can't be read.
std::optional<Picture> GetSaveStateScreenshot(std::string serial, s32 slot);

/// Saves the running game to slot, or loads slot (-1 for the resume state) into it. nullopt when it worked, else why
/// not: no game, no serial, or the core's error.
std::optional<std::string> SaveState(s32 slot);
std::optional<std::string> LoadState(s32 slot);

/// Port 1's analog mode: -1 if its controller isn't a DualShock (AnalogController) or no game runs, 0 digital, 1
/// analog.
s32 GetAnalogMode();

/// These queue and return.
void ResetGame();
void SetFastForward(bool enabled);
/// Presses Port 1's Analog button, as the controller's own does, if it's a DualShock.
void ToggleAnalogMode();
void SaveScreenshot();

void SetPaused(bool paused);

/// A new, resized or destroyed (nullptr) surface. Takes over one reference to the window, and returns once the GPU
/// device has stopped using the old one.
void SurfaceChanged(ANativeWindow* window, s32 width, s32 height, float scale);

/// Controller input, from any thread. It's queued and passed to the input source on the CPU thread; events for a
/// device only count once its connection is queued. Returns whether the key goes to the core: every key but Back and
/// Mode, which open the menu.
bool QueueKeyEvent(s32 device_id, s32 keycode, bool pressed);
/// Several keys of one device, (keycode, pressed) in order, queued at once: the CPU thread passes them on in one batch,
/// with no frame between them. Filtered as QueueKeyEvent() does.
void QueueKeyEvents(s32 device_id, std::span<const std::pair<s32, bool>> keys);
void QueueAxisEvent(s32 device_id, s32 axis, float value);

/// A controller is Android-<index> to the core, with the index its descriptor has in [Android] ControllerDescriptors,
/// so it keeps its bindings from one run to the next. A descriptor not there yet is added, and its default bindings
/// are added to Port 1's; one seen before gets nothing added, unless no [Pad1] key has a binding of its own. The table
/// is only appended to, so an index never changes in this process: ReloadSettings() writes it back. A settings.ini
/// adapted (AdaptForeignSettings()) or reset has none.
void QueueDeviceConnected(s32 device_id, std::string name, std::string descriptor);
void QueueDeviceDisconnected(s32 device_id);

enum class PadBindingKind : u8
{
  Button,
  Axis,
  HalfAxis,
};

/// One of Port 1's inputs, and the binding one controller has for it, without its Android-<index>/ prefix (Button96,
/// +Axis23), if it has one.
struct PadBinding
{
  std::string name;         // the [Pad1] key
  std::string display_name; // the core's, untranslated
  PadBindingKind kind;
  std::optional<std::string> binding;
};

/// Each controller's own bindings on Port 1, by its descriptor. They run on the CPU thread and block; the two that
/// change bindings save settings.ini and apply them (System::ReloadInputBindings()). For a descriptor not in the table
/// (one never connected), or before Initialize() succeeded, they do nothing and GetPadBindings() gives nullopt.

/// Port 1's controller type's buttons and axes (Button, Axis and HalfAxis), in the core's order, each with the
/// controller's first binding for it.
std::optional<std::vector<PadBinding>> GetPadBindings(std::string descriptor);

/// The controller's place in the descriptor table, as its Android-<index> devices are numbered, which stays the same
/// from one run to the next; nullopt for one never connected, or before Initialize() succeeded.
std::optional<u32> GetControllerIndex(std::string descriptor);

/// Replaces the controller's bindings for Port 1's input name with binding, or with none if it's empty. Other
/// controllers' stay as they are. An input Port 1's type doesn't have is refused.
void SetPadBinding(std::string descriptor, std::string name, std::string binding);

/// Drops the controller's bindings from every [Pad1] key, then adds its defaults, as a new controller gets them.
void ResetPadBindings(std::string descriptor);

/// Captures the next input from the controller with descriptor, for binding: the first BindingCapture takes. It's
/// reported with NotifyBindingCaptured(), without the Android-<index>/ prefix (Button99, +Axis23, -Axis0), and the
/// capture ends. Until then every input event goes to the capture, so no game or hotkey sees it. A capture already
/// running is replaced. A descriptor not in the table (one never connected), or a call before Initialize() succeeded,
/// does nothing. Runs on the CPU thread and blocks.
void StartBindingCapture(std::string descriptor);

/// Ends the capture running, if any, without a report: once it returns, NotifyBindingCaptured() isn't called for it.
/// Runs on the CPU thread and blocks.
void CancelBindingCapture();

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
void NotifyGameStarted(std::string_view title, std::string_view serial);
void NotifyGameStopped();
void NotifyError(std::string_view message);
void NotifyBindingCaptured(std::string_view binding);

/// An on-screen message for the app to show, or a keyed one to take away (seconds 0): the core's text, with its icon
/// characters gone, and the first frame of its image, if it has one (RGBA8, width x height pixels).
void NotifyOsdMessage(std::string_view key, std::string_view text, float seconds, const u32* image_pixels,
                      u32 image_width, u32 image_height);

/// Fast forward was turned on or off, by the menu or a hotkey.
void NotifyFastForwardChanged(bool enabled);

} // namespace AndroidHost
