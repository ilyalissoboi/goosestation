// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "memcard_sync_bridge.h"

#include "core/host.h"
#include "core/memcard_sync.h"

bool MemcardSyncBridge::IsAvailable()
{
  return MemcardSync::IsAvailable();
}

MemcardSyncBridge::State MemcardSyncBridge::GetState()
{
  using namespace MemcardSync;
  State state;
  const bool available = IsAvailable();
  state.applying = available && IsApplying();
  // A start holds IsRunning() for seconds, and only happens while applying.
  state.running = available && !state.applying && IsRunning();
  state.enabled = Host::GetBaseBoolSettingValue(SECTION, "Enabled", false);
  state.remote_uri = Host::GetBaseStringSettingValue(SECTION, "RemoteURI", "");
  state.access_key_id = Host::GetBaseStringSettingValue(SECTION, "AccessKeyID", "");
  state.device_name = Host::GetBaseStringSettingValue(SECTION, "DeviceName", "");
  state.default_device_name = Host::GetMemcardSyncDefaultDeviceName();
  state.has_secret = HasSecret(SECRET_ACCESS_KEY);
  state.has_passphrase = HasSecret(ENCRYPTION_PASSPHRASE);
  state.passphrase_recorded = Host::GetBaseBoolSettingValue(SECTION, "PassphraseStored", false);
  state.start_error = GetLastStartError();
  state.status = GetStatus();
  state.activity = static_cast<u8>(GetActivity());
  state.conflicts = static_cast<u32>(GetConflicts().size());
  state.kind = GetStatusKind(available, state.applying, !state.start_error.empty(), state.enabled, state.running,
                             !state.status.empty());
  return state;
}

void MemcardSyncBridge::Apply()
{
  MemcardSync::ApplySettingsAsync();
}

void MemcardSyncBridge::SyncNow()
{
  MemcardSync::SyncNow();
}

void MemcardSyncBridge::SetPeriodicSyncForTest(u32 ms)
{
  MemcardSync::SetPeriodicSyncForTest(ms);
}
