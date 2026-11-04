// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "core/memcard_sync_ui.h"

#include "common/types.h"

#include <functional>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

/// Memory card sync for the app (core/memcard_sync.h): whether it can run, its state, and what the settings page and
/// the mirror ask of it.
namespace MemcardSyncBridge {

/// Whether the emusync library loaded. From any thread.
bool IsAvailable();

/// Sync's settings and state, for the page and the library's badge.
struct State
{
  MemcardSync::StatusKind kind;
  bool enabled;
  bool running;
  bool applying;
  std::string remote_uri;
  std::string access_key_id;
  std::string device_name;
  std::string default_device_name;
  bool has_secret;
  bool has_passphrase;
  bool passphrase_recorded; // [MemoryCardSync] PassphraseStored, which a file from desktop sets
  std::string start_error;
  std::string status;
  u8 activity; // MemcardSync::Activity
  u32 conflicts;
};

/// The state now. From any thread: it never waits for a start in progress.
State GetState();

/// MemcardSync::ApplySettingsAsync(), with the settings as they are now. Returns at once.
void Apply();

/// MemcardSync::SyncNow(): a full sync, queued. Returns at once.
void SyncNow();

/// One device's version of a save in a conflict, as the conflict screen shows it.
struct Version
{
  std::string device;
  s64 modified_unix_ms;
  bool time_known; // a time that can be real (MemcardSync::GetLocalTime())
  u32 blocks;
  bool deleted;
};

/// A save changed both here and on another device: its name in a list (with the game's title, if the game database
/// knows the serial), this device's version, the others', and the choice preselected (MemcardSync::GetConflictChoices()'s
/// order: this device's, each remote one, decide later).
struct Conflict
{
  u64 id;
  std::string name;
  std::optional<Version> local;
  std::vector<Version> remote;
  u32 default_choice;
};

/// The conflicts waiting for the player, oldest first. On the CPU thread (the game database), and waits for it.
std::vector<Conflict> GetConflicts();

/// MemcardSync::ResolveConflict(). Returns at once.
void ResolveConflict(u64 id, bool keep_local, u32 remote_index);

/// MemcardSync::WithCardHeld() for the card at relative_path, under EmuFolders::DataRoot as the core makes its card
/// paths.
bool HoldCard(std::string_view relative_path, const std::function<void()>& action);

/// MemcardSync::SetAppBackgrounded(), queued on the CPU thread, so it comes after the card writes queued there
/// (AndroidHost::SetPaused()'s flush). Returns at once.
void SetBackgrounded(bool backgrounded);

/// Tests: MemcardSync::SetPeriodicSyncForTest().
void SetPeriodicSyncForTest(u32 ms);

/// Tests: the card hooks, as a game's card calls them, on the card at path.
void CardOpenedForTest(std::string_view path);
void CardWrittenForTest(std::string_view path);
void CardClosedForTest(std::string_view path);

/// Tests: writes data as the save name on the card at path (formatted if there's no file), replacing a save of that
/// name. False if it couldn't.
bool WriteSaveForTest(std::string_view path, std::string_view name, std::span<const u8> data);

/// Tests: the save name's data on the card at path, or nullopt.
std::optional<std::vector<u8>> ReadSaveForTest(std::string_view path, std::string_view name);

} // namespace MemcardSyncBridge
