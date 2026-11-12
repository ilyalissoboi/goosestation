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

static std::optional<std::string> s_pending_boot_path;

static AndroidDiscGroups::Game s_pending_game;     // the game the pending boot is for

static u32 s_pending_playlist_index = 0;          // the pending boot's sub-image, for a playlist

static std::string s_pending_boot_state; // the save state the pending boot starts from, if any

} // namespace AndroidHost

void AndroidHost::SetListedGames(std::vector<AndroidDiscGroups::Game> games)
{
  std::unique_lock lock(s_listed_games_mutex);
  s_listed_games = std::move(games);
}

std::vector<AndroidDiscGroups::Game> AndroidHost::GetListedGames()
{
  std::unique_lock lock(s_listed_games_mutex);
  return s_listed_games;
}

void AndroidHost::StartGame(std::string path, u32 disc, bool resume)
{
  StartGame(std::move(path), disc, resume ? std::optional<BootState>(BootState{std::string(), -1, false}) : std::nullopt);
}

void AndroidHost::StartGameFromState(std::string path, std::string key, s32 slot, bool shared)
{
  StartGame(std::move(path), 1, BootState{std::move(key), slot, shared});
}

void AndroidHost::StartGame(std::string path, u32 disc, std::optional<BootState> from)
{
  // Every StartGame() ends with one NotifyGameStopped(): once the game has stopped, or straight away when it fails to
  // boot, is refused or is cancelled before it boots. The app can't tell which game a late callback was for otherwise.
  if (!s_initialized)
  {
    NotifyError("The core isn't initialized.");
    NotifyGameStopped();
    return;
  }

  // The game as the library listed it, copied: a rescan during the game doesn't change it. A file the list doesn't
  // have is a game of its own, keyed by the core's serial once it boots.
  AndroidDiscGroups::Game game;
  {
    std::unique_lock lock(s_listed_games_mutex);
    const auto it = std::find_if(s_listed_games.begin(), s_listed_games.end(),
                                 [&path](const AndroidDiscGroups::Game& listed) { return listed.path == path; });
    if (it != s_listed_games.end())
      game = *it;
  }
  if (game.path.empty())
    game.path = path;

  EmuThread::RunOnThread(
    [game = std::move(game), disc, from = std::move(from)]() mutable {
      // A running game isn't replaced: the app shuts it down first. One that's stopping (shut down from inside a
      // frame, and only destroyed once System::Execute() returns) is waited for.
      const System::State state = System::GetState();
      if (state != System::State::Shutdown && state != System::State::Stopping)
      {
        WARNING_LOG("A game is already running, not starting '{}'.", Path::GetFileName(game.path));
        NotifyError("A game is already running.");
        NotifyGameStopped();
        return;
      }

      if (s_pending_boot_path.has_value())
      {
        INFO_LOG("Replacing the boot of '{}'.", Path::GetFileName(s_pending_boot_path.value()));
        NotifyGameStopped();
      }

      // A state to boot from: the resume state (the game's key, slot -1), another of the game's, or a shared slot.
      const bool resume = from.has_value() && !from->shared && from->key.empty() && from->slot < 0;
      const std::string state_key = (from.has_value() && from->key.empty()) ? game.key : (from ? from->key : "");
      std::string boot_state;
      if (from.has_value() && from->shared)
        boot_state = System::GetGlobalSaveStateFileName(from->slot);
      else if (from.has_value() && !state_key.empty())
        boot_state = System::GetGameSaveStateFileName(state_key, from->slot);
      if (from.has_value() && !resume && !FileSystem::FileExists(boot_state.c_str()))
      {
        NotifyError("Couldn't start from that save: There's no save in this slot.");
        NotifyGameStopped();
        return;
      }

      // A shared slot is the game's only if its state says so: it may have been replaced since the app read it. A file
      // the library doesn't list has no identity until it boots (the app only starts listed games from a state).
      if (from.has_value() && from->shared && (!game.key.empty() || !game.discs.empty()))
      {
        const std::optional<ExtendedSaveStateInfo> info = ReadStateInfo(boot_state);
        if (!info.has_value() ||
            !AndroidDiscGroups::MatchStateGame(game, info->serial, info->title, info->media_path,
                                               info->media_subimage_index)
               .has_value())
        {
          NotifyError("Couldn't start from that save: it's another game's.");
          NotifyGameStopped();
          return;
        }
      }

      // It boots on the disc it was saved on, found by its identity.
      if (!boot_state.empty() && !game.discs.empty())
      {
        const std::optional<ExtendedSaveStateInfo> info = System::GetExtendedSaveStateInfo(boot_state.c_str());
        if (info.has_value() && !info->media_path.empty())
        {
          const std::optional<u32> number =
            AndroidDiscGroups::FindStateDisc(game, info->serial, info->media_path, info->media_subimage_index);
          if (!number.has_value())
          {
            NotifyError(fmt::format("{}: {}", resume ? "Couldn't resume" : "Couldn't start from that save",
                                    MissingDiscMessage(info->media_path)));
            NotifyGameStopped();
            return;
          }
          disc = number.value();
        }
      }

      // The disc to boot: a playlist's sub-image, or that disc's own file.
      const auto chosen = std::find_if(game.discs.begin(), game.discs.end(),
                                       [disc](const AndroidDiscGroups::Disc& d) { return d.number == disc; });
      const AndroidDiscGroups::Disc* boot_disc =
        (chosen != game.discs.end()) ? &*chosen : (game.discs.empty() ? nullptr : &game.discs.front());
      s_pending_playlist_index = (game.playlist && boot_disc) ? (boot_disc->number - 1) : 0;
      s_pending_boot_path = (game.playlist || !boot_disc) ? game.path : boot_disc->path;
      s_pending_boot_state = std::move(boot_state);
      s_pending_game = std::move(game);
      if (state == System::State::Stopping)
        INFO_LOG("Waiting for the last game to stop to start '{}'.", Path::GetFileName(s_pending_boot_path.value()));
      else if (!s_window)
        INFO_LOG("Waiting for a surface to start '{}'.", Path::GetFileName(s_pending_boot_path.value()));

      // The CPU thread's loop boots it once this batch of work is done.
    },
    false);
}

void AndroidHost::BootPendingGame()
{
  // The GPU device is only ever created on a window (a context created surfaceless might not take one later), and a
  // game only boots once the last one is destroyed. A game shut down from inside a frame stays Stopping until
  // System::Execute() returns, so Shutdown also means the core isn't executing.
  if (!s_pending_boot_path.has_value() || !s_window || System::GetState() != System::State::Shutdown)
    return;

  s_boot_path = std::move(s_pending_boot_path.value());
  s_pending_boot_path.reset();

  // Nothing held before the boot carries over into the game, nor stops its first press from counting.
  QueueReleaseAllInputs();

  // A sign-in that failed for want of a connection is tried again, so this game's set can load.
  Achievements::RetryAndroidSignIn();

  SystemBootParameters parameters(s_boot_path);
  parameters.save_state = std::move(s_pending_boot_state);
  // A boot from a state while hardcore mode would start asks Host::ConfirmMessage(), which says yes here: hardcore would
  // be turned off without the player's say. The app doesn't offer it (GameStarts); this makes sure.
  if (!parameters.save_state.empty() && AchievementsBridge::HardcoreWouldStart())
  {
    WARNING_LOG("Not resuming: hardcore mode would start.");
    parameters.save_state.clear();
  }
  parameters.media_playlist_index = s_pending_playlist_index;
  s_pending_boot_state.clear();
  s_pending_playlist_index = 0;
  s_running_game = std::move(s_pending_game);
  s_pending_game = {};
  // Every disc of the library's game takes the game's own settings from one file, under its key, as its states.
  System::SetGameSettingsSerial(s_running_game->key);
  // Built before the boot, whose UpdateRunningGame() gives it to the core (Host::OnGameChanged()).
  GameCodes::Load(s_running_game.value());
  if (parameters.save_state.empty())
    INFO_LOG("Starting '{}'.", s_boot_path);
  else
    INFO_LOG("Starting '{}' from '{}'.", s_boot_path, Path::GetFileName(parameters.save_state));

  Error error;
  if (!System::BootSystem(std::move(parameters), &error))
  {
    ERROR_LOG("Failed to start '{}': {}", s_boot_path, error.GetDescription());
    NotifyError(fmt::format("Failed to start {}: {}", Path::GetFileName(s_boot_path), error.GetDescription()));
    s_running_game.reset();
    System::SetGameSettingsSerial({});
    NotifyGameStopped();
  }
}

void AndroidHost::ShutdownGame()
{
  EmuThread::RunOnThread(
    []() {
      if (s_pending_boot_path.has_value())
      {
        INFO_LOG("Cancelling the boot of '{}'.", Path::GetFileName(s_pending_boot_path.value()));
        s_pending_boot_path.reset();
        s_pending_boot_state.clear();
        s_pending_game = {};
        NotifyGameStopped();
      }

      if (System::IsValid())
        System::ShutdownSystem(false);
    },
    false);
}

void AndroidHost::SetPaused(bool paused)
{
  EmuThread::RunOnThread(
    [paused]() {
      if (!System::IsValid())
        return;

      System::PauseSystem(paused);

      // A paused system's memory cards never reach their delayed save, and the app may be killed in the background:
      // write what the game saved now.
      if (paused)
      {
        for (u32 slot = 0; slot < NUM_CONTROLLER_AND_CARD_PORTS; slot++)
        {
          if (MemoryCard* card = Pad::GetMemoryCard(slot))
            card->Flush();
        }
      }
    },
    false);
}
