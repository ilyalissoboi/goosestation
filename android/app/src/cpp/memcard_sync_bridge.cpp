// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "memcard_sync_bridge.h"

#include "emu_thread.h"

#include "core/game_database.h"
#include "core/host.h"
#include "core/memcard_sync.h"
#include "core/memory_card_image.h"
#include "core/settings.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/path.h"

#include <algorithm>

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

static MemcardSyncBridge::Version ToVersion(const MemcardSync::ConflictVersion& v)
{
  return {v.device, v.modified_unix_ms, MemcardSync::GetLocalTime(v.modified_unix_ms).has_value(),
          MemcardSync::GetSaveBlockCount(v.size), v.deleted};
}

std::vector<MemcardSyncBridge::Conflict> MemcardSyncBridge::GetConflicts()
{
  std::vector<Conflict> conflicts;
  EmuThread::RunOnThread(
    [&conflicts]() {
      for (const MemcardSync::Conflict& c : MemcardSync::GetConflicts())
      {
        const GameDatabase::Entry* entry = c.serial.empty() ? nullptr : GameDatabase::GetEntryForSerial(c.serial);
        Conflict out;
        out.id = c.id;
        out.name = MemcardSync::GetConflictName(c, entry ? std::string_view(entry->title) : std::string_view());
        if (c.local.has_value())
          out.local = ToVersion(c.local.value());
        for (const MemcardSync::ConflictVersion& v : c.remote)
          out.remote.push_back(ToVersion(v));
        out.default_choice = static_cast<u32>(MemcardSync::GetDefaultConflictChoice(c));
        conflicts.push_back(std::move(out));
      }
    },
    true);
  return conflicts;
}

void MemcardSyncBridge::ResolveConflict(u64 id, bool keep_local, u32 remote_index)
{
  MemcardSync::ResolveConflict(id, keep_local, remote_index);
}

bool MemcardSyncBridge::HoldCard(std::string_view relative_path, const std::function<void()>& action)
{
  return MemcardSync::WithCardHeld(Path::Combine(EmuFolders::DataRoot, relative_path), action);
}

void MemcardSyncBridge::CardOpenedForTest(std::string_view path)
{
  MemcardSync::OnCardOpening(path);
}

void MemcardSyncBridge::CardWrittenForTest(std::string_view path)
{
  MemcardSync::OnCardWritten(path);
}

void MemcardSyncBridge::CardClosedForTest(std::string_view path)
{
  MemcardSync::OnCardClosed(path);
}

bool MemcardSyncBridge::WriteSaveForTest(std::string_view path, std::string_view name, std::span<const u8> data)
{
  const std::string file(path);
  MemoryCardImage::DataArray card;
  if (!FileSystem::FileExists(file.c_str()) || !MemoryCardImage::LoadFromFile(&card, file.c_str(), nullptr))
    MemoryCardImage::Format(&card);
  for (const MemoryCardImage::FileInfo& fi : MemoryCardImage::EnumerateFiles(card, false))
  {
    if (fi.filename == name)
      MemoryCardImage::DeleteFile(&card, fi, true);
  }
  Error error;
  if (!MemoryCardImage::WriteFile(&card, name, data, &error) ||
      !MemoryCardImage::SaveToFile(card, file.c_str(), &error))
  {
    return false;
  }
  return true;
}

std::optional<std::vector<u8>> MemcardSyncBridge::ReadSaveForTest(std::string_view path, std::string_view name)
{
  const std::string file(path);
  MemoryCardImage::DataArray card;
  if (!MemoryCardImage::LoadFromFile(&card, file.c_str(), nullptr))
    return std::nullopt;
  for (const MemoryCardImage::FileInfo& fi : MemoryCardImage::EnumerateFiles(card, false))
  {
    std::vector<u8> data;
    if (fi.filename == name && MemoryCardImage::ReadFile(card, fi, &data, nullptr))
      return data;
  }
  return std::nullopt;
}

void MemcardSyncBridge::SetBackgrounded(bool backgrounded)
{
  EmuThread::RunOnThread([backgrounded]() { MemcardSync::SetAppBackgrounded(backgrounded); }, false);
}
