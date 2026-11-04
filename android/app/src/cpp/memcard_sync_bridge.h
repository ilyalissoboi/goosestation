// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "core/memcard_sync_ui.h"

#include "common/types.h"

#include <string>

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

} // namespace MemcardSyncBridge
