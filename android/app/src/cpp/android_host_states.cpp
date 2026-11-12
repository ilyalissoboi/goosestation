// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "android_host_state.h"

#include "android_host.h"
#include "achievements_bridge.h"
#include "emu_thread.h"
#include "foreign_settings.h"
#include "game_codes.h"
#include "core/achievements.h"
#include "core/achievements_offline.h"
#include "core/analog_controller.h"
#include "core/controller.h"
#include "core/fullscreen_ui.h"
#include "core/game_database.h"
#include "core/game_list.h"
#include "core/host.h"
#include "core/memcard_sync.h"
#include "core/memory_card.h"
#include "core/pad.h"
#include "core/settings.h"
#include "core/system.h"
#include "util/android_game_settings.h"
#include "util/android_input_keys.h"
#include "util/android_input_source.h"
#include "util/gpu_device.h"
#include "util/imgui_fullscreen.h"
#include "util/imgui_manager.h"
#include "util/ini_settings_interface.h"
#include "util/input_manager.h"
#include "util/postprocessing.h"
#include "util/postprocessing_shader_slang.h"
#include "util/window_info.h"
#include "common/memory_settings_interface.h"
#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/path.h"
#include "common/small_string.h"
#include "common/string_util.h"
#include "fmt/format.h"
#include <android/native_window.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <optional>
#include <utility>
#include <vector>

Log_SetChannel(AndroidHost);

namespace AndroidHost {
using AndroidInputKeys::DEVICE_PREFIX;

using AndroidInputKeys::ParseDeviceIndex;

} // namespace AndroidHost

std::vector<s64> AndroidHost::GetSaveStateTimes(std::string serial)
{
  std::vector<s64> times(System::PER_GAME_SAVE_STATE_SLOTS + 1, 0);
  if (!s_initialized || serial.empty())
    return times;

  EmuThread::RunOnThread(
    [&serial, &times]() {
      for (s32 slot = 0; slot <= System::PER_GAME_SAVE_STATE_SLOTS; slot++)
      {
        // [0] is the resume state, slot -1 to the core.
        const std::optional<SaveStateInfo> info = System::GetSaveStateInfo(serial.c_str(), (slot == 0) ? -1 : slot);
        if (info.has_value())
          times[static_cast<size_t>(slot)] = static_cast<s64>(info->timestamp);
      }
    },
    true);
  return times;
}

std::vector<s64> AndroidHost::GetSharedStateTimes()
{
  std::vector<s64> times(System::GLOBAL_SAVE_STATE_SLOTS + 1, 0);
  if (!s_initialized)
    return times;

  EmuThread::RunOnThread(
    [&times]() {
      for (s32 slot = 1; slot <= System::GLOBAL_SAVE_STATE_SLOTS; slot++)
      {
        FILESYSTEM_STAT_DATA sd;
        if (FileSystem::StatFile(System::GetGlobalSaveStateFileName(slot).c_str(), &sd))
          times[static_cast<size_t>(slot)] = static_cast<s64>(sd.ModificationTime);
      }
    },
    true);
  return times;
}

// A state's file: shared slot slot, or key's (-1 for the resume state). Empty for an empty key that isn't shared.
static std::string StatePath(const std::string& key, s32 slot, bool shared)
{
  if (shared)
    return System::GetGlobalSaveStateFileName(slot);
  return key.empty() ? std::string() : System::GetGameSaveStateFileName(key, slot);
}

// The state's header, nullopt when there's no file or it can't be read (the core reports that as a zero time).
std::optional<ExtendedSaveStateInfo> ReadStateInfo(const std::string& path)
{
  if (path.empty() || !FileSystem::FileExists(path.c_str()))
    return std::nullopt;
  std::optional<ExtendedSaveStateInfo> info = System::GetExtendedSaveStateInfo(path.c_str());
  if (!info.has_value() || info->timestamp == 0)
    return std::nullopt;
  return info;
}

std::optional<AndroidHost::SaveStatePreview> AndroidHost::GetStatePreview(std::string key, s32 slot, bool shared,
                                                                         std::string game_path)
{
  if (!s_initialized)
    return std::nullopt;

  std::optional<SaveStatePreview> result;
  EmuThread::RunOnThread(
    [&]() {
      const std::optional<ExtendedSaveStateInfo> info = ReadStateInfo(StatePath(key, slot, shared));
      if (!info.has_value())
        return;

      SaveStatePreview preview{std::nullopt, 0, info->serial, info->title, {}, {}};
      if (info->screenshot.IsValid())
      {
        const RGBA8Image& image = info->screenshot;
        const u32* pixels = image.GetPixels();
        preview.picture = Picture{image.GetWidth(), image.GetHeight(),
                                  std::vector<u32>(pixels, pixels + image.GetWidth() * image.GetHeight())};
      }

      // The game it holds: the running one, else the listed game at game_path, else any listed game.
      const auto match = [&info](const AndroidDiscGroups::Game& game) {
        return AndroidDiscGroups::MatchStateGame(game, info->serial, info->title, info->media_path,
                                                 info->media_subimage_index);
      };
      const auto take = [&preview](const AndroidDiscGroups::Game& game, u32 disc) {
        // Only a multi-disc game's rows say their disc.
        preview.disc = (game.discs.size() > 1) ? disc : 0;
        preview.game_path = game.path;
        preview.game_title = game.title;
      };
      std::optional<u32> disc;
      if (s_running_game.has_value() && (disc = match(*s_running_game)).has_value())
      {
        take(*s_running_game, disc.value());
      }
      else
      {
        std::unique_lock lock(s_listed_games_mutex);
        const auto named = std::find_if(s_listed_games.begin(), s_listed_games.end(),
                                        [&game_path](const AndroidDiscGroups::Game& g) { return g.path == game_path; });
        if (named != s_listed_games.end() && (disc = match(*named)).has_value())
        {
          take(*named, disc.value());
        }
        else
        {
          for (const AndroidDiscGroups::Game& game : s_listed_games)
          {
            if ((disc = match(game)).has_value())
            {
              take(game, disc.value());
              break;
            }
          }
        }
      }
      result = std::move(preview);
    },
    true);
  return result;
}

std::optional<AndroidHost::SaveStatePreview> AndroidHost::GetSaveStatePreview(std::string serial, s32 slot)
{
  if (serial.empty())
    return std::nullopt;
  return GetStatePreview(std::move(serial), slot, false, std::string());
}

/// Runs action(path, error) on the CPU thread for key's slot file (shared: the shared slot's) in the running game: the
/// reason it couldn't, or nullopt.
template<typename Action>
static std::optional<std::string> RunOnSlot(const std::string& key, s32 slot, bool shared, const Action& action)
{
  if (!AndroidHost::s_initialized)
    return std::string(NO_GAME_RUNNING);

  std::optional<std::string> result;
  EmuThread::RunOnThread(
    [&key, slot, shared, &action, &result]() {
      if (!System::IsValid())
      {
        result = NO_GAME_RUNNING;
        return;
      }

      if (key.empty() && !shared)
      {
        result = NO_SERIAL;
        return;
      }

      Error error;
      const std::string path = StatePath(key, slot, shared);
      if (!action(path, &error))
        result = error.GetDescription().empty() ? std::string("Unknown error.") : error.GetDescription();
    },
    true);
  return result;
}

// A slot save keeps the state it replaces as a .bak when asked, as desktop's do; the resume state never does.
static bool SaveSlotState(const std::string& path, Error* error)
{
  return System::SaveState(path.c_str(), error, g_settings.create_save_state_backups);
}

std::optional<std::string> AndroidHost::SaveState(std::string key, s32 slot)
{
  return RunOnSlot(key, slot, false, SaveSlotState);
}

std::optional<std::string> AndroidHost::SaveSharedState(s32 slot)
{
  return RunOnSlot(std::string(), slot, true, SaveSlotState);
}

std::optional<std::string> AndroidHost::LoadSharedState(s32 slot)
{
  return RunOnSlot(std::string(), slot, true, [](const std::string& path, Error* error) {
    if (Achievements::IsHardcoreModeActive())
    {
      Error::SetStringView(error, "Loading states is off in hardcore mode.");
      return false;
    }

    if (!FileSystem::FileExists(path.c_str()))
    {
      Error::SetStringView(error, "There's no save in this slot.");
      return false;
    }

    // Only the running game's: loading another game's state would run that game on this one's disc and key. The app
    // starts the other game from it instead.
    const std::optional<ExtendedSaveStateInfo> info = ReadStateInfo(path);
    if (!info.has_value())
    {
      Error::SetStringView(error, "Couldn't read that save.");
      return false;
    }
    if (!s_running_game.has_value() ||
        !AndroidDiscGroups::MatchStateGame(*s_running_game, info->serial, info->title, info->media_path,
                                           info->media_subimage_index)
           .has_value())
    {
      Error::SetStringView(error, "That save is from another game.");
      return false;
    }

    return System::LoadState(path.c_str(), error, true);
  });
}

std::optional<std::string> AndroidHost::DeleteSaveState(std::string key, s32 slot, bool shared)
{
  if (!s_initialized)
    return std::string(NO_GAME_RUNNING);

  std::optional<std::string> result;
  EmuThread::RunOnThread(
    [&]() {
      const std::string path = StatePath(key, slot, shared);
      if (path.empty())
      {
        result = NO_SERIAL;
        return;
      }
      if (!FileSystem::FileExists(path.c_str()))
      {
        result = "There's no save in this slot.";
        return;
      }

      Error error;
      if (!FileSystem::DeleteFile(path.c_str(), &error))
      {
        ERROR_LOG("Failed to delete '{}': {}", Path::GetFileName(path), error.GetDescription());
        result = error.GetDescription().empty() ? std::string("Unknown error.") : error.GetDescription();
        return;
      }
      INFO_LOG("Deleted '{}'.", Path::GetFileName(path));

      // The backup a save kept of the state it replaced goes with it, as it's that slot's too.
      const std::string backup = Path::ReplaceExtension(path, "bak");
      if (FileSystem::FileExists(backup.c_str()) && !FileSystem::DeleteFile(backup.c_str(), &error))
        ERROR_LOG("Failed to delete '{}': {}", Path::GetFileName(backup), error.GetDescription());
    },
    true);
  return result;
}

std::optional<std::string> AndroidHost::LoadState(std::string key, s32 slot)
{
  return RunOnSlot(key, slot, false, [](const std::string& path, Error* error) {
    // The core refuses too, but reports success: the app would take the load as done.
    if (Achievements::IsHardcoreModeActive())
    {
      Error::SetStringView(error, "Loading states is off in hardcore mode.");
      return false;
    }

    // LoadState() doesn't say when the file is missing in so many words.
    if (!FileSystem::FileExists(path.c_str()))
    {
      Error::SetStringView(error, "There's no save in this slot.");
      return false;
    }

    // With the core's undo state: a load that fails part-way puts the game back where it was. Its disc is found by
    // Host::ResolveSaveStateMedia().
    return System::LoadState(path.c_str(), error, true);
  });
}

// The running game's disc in the drive, by number, 0 if none. CPU thread.
static u32 CurrentDiscNumber()
{
  if (!System::IsValid() || !AndroidHost::s_running_game.has_value())
    return 0;

  const AndroidDiscGroups::Game& game = AndroidHost::s_running_game.value();
  if (game.playlist)
    return System::HasMediaSubImages() ? (System::GetMediaSubImageIndex() + 1) : 0;

  const std::string media = System::GetMediaFileName();
  const auto disc = std::find_if(game.discs.begin(), game.discs.end(),
                                 [&media](const AndroidDiscGroups::Disc& d) { return d.path == media; });
  return (disc != game.discs.end()) ? disc->number : 0;
}

u32 AndroidHost::GetCurrentDisc()
{
  if (!s_initialized)
    return 0;

  return EmuThread::Call(0u, []() { return CurrentDiscNumber(); });
}

std::optional<std::string> AndroidHost::ChangeDisc(u32 number)
{
  if (!s_initialized)
    return std::string(NO_GAME_RUNNING);

  std::optional<std::string> result;
  EmuThread::RunOnThread(
    [number, &result]() {
      if (!System::IsValid() || !s_running_game.has_value())
      {
        result = NO_GAME_RUNNING;
        return;
      }

      const AndroidDiscGroups::Game& game = s_running_game.value();
      const auto disc = std::find_if(game.discs.begin(), game.discs.end(),
                                     [number](const AndroidDiscGroups::Disc& d) { return d.number == number; });
      if (game.discs.size() < 2 || disc == game.discs.end())
      {
        result = "This game has one disc.";
        return;
      }

      if (CurrentDiscNumber() == number)
        return;

      if (!FileSystem::FileExists(disc->path.c_str()))
      {
        result = fmt::format("Disc {} isn't in your library any more.", number);
        return;
      }

      const bool inserted =
        game.playlist ? System::SwitchMediaSubImage(number - 1) : System::InsertMedia(disc->path.c_str());
      if (!inserted)
        result = fmt::format("Couldn't insert disc {}.", number);
      else
        INFO_LOG("Disc {} of '{}' is in.", number, game.title);
    },
    true);
  return result;
}

AndroidHost::PatchCodes AndroidHost::GetPatchCodes()
{
  PatchCodes result;
  if (!s_initialized)
    return result;

  EmuThread::RunOnThread(
    [&result]() {
      if (!System::IsValid() || !s_running_game.has_value())
        return;

      result.file_name = GameCodes::AddFileName();
      for (const GameCodes::Code& code : GameCodes::List())
        result.codes.push_back({code.group, code.name, code.manual, code.on});
    },
    true);
  return result;
}

std::optional<std::string> AndroidHost::SetPatchCode(u32 index, bool on)
{
  if (!s_initialized)
    return std::string(NO_GAME_RUNNING);

  std::optional<std::string> result;
  EmuThread::RunOnThread(
    [index, on, &result]() {
      result = System::IsValid() ? GameCodes::SetOn(index, on) : std::optional<std::string>(NO_GAME_RUNNING);
    },
    true);
  return result;
}

std::optional<std::string> AndroidHost::ApplyPatchCode(u32 index)
{
  if (!s_initialized)
    return std::string(NO_GAME_RUNNING);

  std::optional<std::string> result;
  EmuThread::RunOnThread(
    [index, &result]() {
      result = System::IsValid() ? GameCodes::ApplyOnce(index) : std::optional<std::string>(NO_GAME_RUNNING);
    },
    true);
  return result;
}

bool AndroidHost::MoveSaveState(std::string from_serial, s32 from_slot, std::string to_serial, s32 to_slot)
{
  if (!s_initialized || from_serial.empty() || to_serial.empty())
    return false;

  bool moved = false;
  EmuThread::RunOnThread(
    [&]() {
      const std::string from = System::GetGameSaveStateFileName(from_serial, from_slot);
      const std::string to = System::GetGameSaveStateFileName(to_serial, to_slot);
      if (!FileSystem::FileExists(from.c_str()) || FileSystem::FileExists(to.c_str()))
        return;

      Error error;
      moved = FileSystem::RenamePath(from.c_str(), to.c_str(), &error);
      if (moved)
        INFO_LOG("Moved '{}' to '{}'.", Path::GetFileName(from), Path::GetFileName(to));
      else
        ERROR_LOG("Failed to move '{}' to '{}': {}", Path::GetFileName(from), Path::GetFileName(to),
                  error.GetDescription());
    },
    true);
  return moved;
}

std::optional<std::string> AndroidHost::SaveResumeState(std::string key)
{
  if (!s_initialized)
    return std::nullopt;

  std::optional<std::string> result;
  EmuThread::RunOnThread(
    [&key, &result]() {
      // A game with no ID (a PSF, the BIOS alone) has nowhere to keep one, and none could be offered. A PS-EXE, or a
      // disc the database doesn't know and with no serial of its own, has the core's content-hash ID (HASH-…), which
      // keys its states as a serial does.
      if (!System::IsValid() || !g_settings.save_state_on_exit || key.empty())
        return;

      Error error;
      const std::string path = System::GetGameSaveStateFileName(key, -1);
      if (!System::SaveState(path.c_str(), &error, false))
      {
        ERROR_LOG("Failed to save the resume state: {}", error.GetDescription());
        result = error.GetDescription().empty() ? std::string("Unknown error.") : error.GetDescription();
      }
    },
    true);
  return result;
}

bool AndroidHost::IsSavingMemoryCards()
{
  if (!s_initialized)
    return false;

  return EmuThread::Call(false, []() { return System::IsValid() && System::IsSavingMemoryCards(); });
}

s32 AndroidHost::GetAnalogMode()
{
  if (!s_initialized)
    return -1;

  s32 mode = -1;
  EmuThread::RunOnThread(
    [&mode]() {
      const Controller* controller = System::IsValid() ? System::GetController(0) : nullptr;
      if (controller && controller->GetType() == ControllerType::AnalogController)
        mode = controller->InAnalogMode() ? 1 : 0;
    },
    true);
  return mode;
}

void AndroidHost::ResetGame()
{
  EmuThread::RunOnThread(
    []() {
      if (System::IsValid())
        System::ResetSystem();
    },
    false);
}

void AndroidHost::SetFastForward(bool enabled)
{
  EmuThread::RunOnThread(
    [enabled]() {
      // Hardcore mode allows no fast forward; the core doesn't refuse it.
      if (!enabled || !Achievements::IsHardcoreModeActive())
        System::SetFastForwardEnabled(enabled);
    },
    false);
}

void AndroidHost::ToggleAnalogMode()
{
  EmuThread::RunOnThread(&PressAnalogButton, false);
}

void AndroidHost::SaveScreenshot()
{
  EmuThread::RunOnThread(
    []() {
      if (System::IsValid())
        System::SaveScreenshot();
    },
    false);
}
