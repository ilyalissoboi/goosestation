// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "memcard_sync_ui.h"

#include <algorithm>
#include <limits>

MemcardSync::StatusKind MemcardSync::GetStatusKind(bool available, bool applying, bool has_start_error, bool enabled,
                                                   bool running, bool has_status)
{
  if (!available)
    return StatusKind::Unavailable;
  if (applying)
    return StatusKind::Applying;
  if (has_start_error)
    return StatusKind::StartError;
  if (!enabled)
    return StatusKind::Off;
  if (!running)
    return StatusKind::Incomplete;
  return has_status ? StatusKind::Status : StatusKind::Idle;
}

std::vector<MemcardSync::ConflictChoice> MemcardSync::GetConflictChoices(const Conflict& conflict)
{
  std::vector<ConflictChoice> choices;
  choices.reserve(conflict.remote.size() + 2);
  choices.push_back({true, 0, false});
  for (u32 i = 0; i < static_cast<u32>(conflict.remote.size()); i++)
    choices.push_back({false, i, false});
  choices.push_back({false, 0, true});
  return choices;
}

size_t MemcardSync::GetDefaultConflictChoice(const Conflict& conflict)
{
  s64 newest = conflict.local.has_value() ? conflict.local->modified_unix_ms : std::numeric_limits<s64>::min();
  size_t index = 0;
  for (size_t i = 0; i < conflict.remote.size(); i++)
  {
    if (conflict.remote[i].modified_unix_ms > newest)
    {
      newest = conflict.remote[i].modified_unix_ms;
      index = i + 1;
    }
  }
  return index;
}

u32 MemcardSync::GetSaveBlockCount(u64 size)
{
  return std::max(static_cast<u32>(size / 8192), 1u);
}
