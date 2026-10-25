// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "memcard_sync.h"

#include "common/types.h"

#include <vector>

// What the memory card sync settings pages show, without the UI: Big Picture's page uses these, and they are
// unit-tested. They match the Qt page (MemoryCardSyncWidget).

namespace MemcardSync {

/// Which status line the settings page shows, most important first.
enum class StatusKind : u8
{
  Unavailable, ///< The emusync library is missing.
  Applying,    ///< Settings are being applied: "Connecting to the storage...".
  StartError,  ///< The last start failed: its error.
  Off,         ///< Sync is switched off.
  Incomplete,  ///< Switched on, but not running: settings are missing.
  Status,      ///< Running: GetStatus().
  Idle,        ///< Running, with no status yet.
};

/// The status line for the state of sync. Don't ask IsRunning() while `applying`: a start holds it for seconds.
StatusKind GetStatusKind(bool available, bool applying, bool has_start_error, bool enabled, bool running,
                         bool has_status);

/// One choice for a conflict: keep this device's version, a remote one, or decide later.
struct ConflictChoice
{
  bool keep_local;
  u32 remote_index;
  bool later;

  bool operator==(const ConflictChoice&) const = default;
};

/// The choices for a conflict, in order: this device's version, each remote version, decide later.
std::vector<ConflictChoice> GetConflictChoices(const Conflict& conflict);

/// The index in GetConflictChoices() of the newest version, preselected as sync chose before there was a choice.
/// A version only wins when it is strictly newer, so a tie keeps this device's.
size_t GetDefaultConflictChoice(const Conflict& conflict);

/// The number of blocks a save takes on a card, from the size emusync stores (whole 8 KiB blocks and a small header).
u32 GetSaveBlockCount(u64 size);

} // namespace MemcardSync
