// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

/// Memory card sync for the app (core/memcard_sync.h): whether it can run, its state, and what the settings page and
/// the mirror ask of it.
namespace MemcardSyncBridge {

/// Whether the emusync library loaded. From any thread.
bool IsAvailable();

} // namespace MemcardSyncBridge
