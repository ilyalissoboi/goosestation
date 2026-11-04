// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "memcard_sync_bridge.h"

#include "core/memcard_sync.h"

bool MemcardSyncBridge::IsAvailable()
{
  return MemcardSync::IsAvailable();
}
