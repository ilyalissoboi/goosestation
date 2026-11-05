// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "memcard_editor_bridge.h"
#include "android_host.h"
#include "emu_thread.h"
#include "foreign_settings.h"
#include "memcard_sync_bridge.h"

#include "core/game_database.h"
#include "core/host.h"
#include "core/memory_card_helpers.h"
#include "core/memory_card_image.h"
#include "core/settings.h"
#include "core/system.h"

#include "util/android_memcards.h"
#include "util/ini_settings_interface.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/path.h"
#include "common/string_util.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <memory>

namespace MemcardEditorBridge {
namespace {

constexpr const char* SECTION = "MemoryCards";

// The cards listed so far, and their paths.
class CardList
{
public:
  bool Has(const std::string& path) const
  {
    return std::any_of(m_cards.begin(), m_cards.end(), [&path](const CardFile& c) { return c.path == path; });
  }

  void Add(std::string path, std::string name, CardKind kind)
  {
    if (path.empty() || Has(path))
      return;
    const bool exists = FileSystem::FileExists(path.c_str());
    m_cards.push_back(CardFile{std::move(path), std::move(name), kind, exists});
  }

  std::vector<CardFile> Take() { return std::move(m_cards); }

  std::vector<CardFile>& Cards() { return m_cards; }

private:
  std::vector<CardFile> m_cards;
};

std::string InMemoryCardFolder(std::string path)
{
  return Path::IsAbsolute(path) ? path : Path::Combine(EmuFolders::MemoryCards, path);
}

MemoryCardType BaseType(u32 slot)
{
  const MemoryCardType default_type =
    (slot == 0) ? Settings::DEFAULT_MEMORY_CARD_1_TYPE : Settings::DEFAULT_MEMORY_CARD_2_TYPE;
  const TinyString key = TinyString::from_format("Card{}Type", slot + 1);
  return Settings::ParseMemoryCardTypeName(
           Host::GetBaseTinyStringSettingValue(SECTION, key, Settings::GetMemoryCardTypeName(default_type)))
    .value_or(default_type);
}

// The shared card of slot, as the boot code resolves it (Settings::GetSharedMemoryCardPath()): the game's own
// settings' path if they have one, else settings.ini's, else shared_card_N.mcd.
std::string SharedPath(std::string_view serial, u32 slot)
{
  const TinyString key = TinyString::from_format("Card{}Path", slot + 1);
  std::string path = Host::GetBaseStringSettingValue(SECTION, key, "");
  if (!serial.empty())
  {
    std::string ini_path = System::GetGameSettingsPath(serial);
    if (FileSystem::FileExists(ini_path.c_str()))
    {
      INISettingsInterface ini(std::move(ini_path));
      if (ini.Load() && ini.ContainsValue(SECTION, key))
        path = ini.GetStringValue(SECTION, key, path.c_str());
    }
  }
  return InMemoryCardFolder(path.empty() ? Settings::GetDefaultSharedMemoryCardName(slot) : std::move(path));
}

// Whether a playlist's cards are named after its title, as the boot code has it: the game's own settings' value, or
// else settings.ini's (true by default).
bool UsePlaylistTitle(std::string_view serial)
{
  const bool global = Host::GetBaseBoolSettingValue(SECTION, "UsePlaylistTitle", true);
  std::string ini_path = System::GetGameSettingsPath(serial);
  if (!FileSystem::FileExists(ini_path.c_str()))
    return global;
  INISettingsInterface ini(std::move(ini_path));
  return ini.Load() ? ini.GetBoolValue(SECTION, "UsePlaylistTitle", global) : global;
}

std::string SharedName(const std::string& path, u32 slot)
{
  if (Path::GetFileName(path) == Settings::GetDefaultSharedMemoryCardName(slot))
    return fmt::format("Shared card {}", slot + 1);
  return AndroidMemcards::OtherCardName(Path::GetFileName(path));
}

// The card game boots with in slot: System::GetGameMemoryCardPath()'s, but for a playlist named apart from the
// database, the boot code's own choice. Empty for a game with no card of its own; a shared one goes to shared.
std::string GameCardPath(const GameCard& game, u32 slot, std::vector<std::pair<std::string, u32>>* shared)
{
  MemoryCardType type;
  std::string path = System::GetGameMemoryCardPath(game.serial, game.boot_path, slot, &type);
  if (type == MemoryCardType::Shared)
  {
    shared->emplace_back(SharedPath(game.serial, slot), slot);
    return {};
  }
  if (type != MemoryCardType::PerGame && type != MemoryCardType::PerGameTitle &&
      type != MemoryCardType::PerGameFileTitle)
  {
    return {};
  }

  if (game.playlist && type == MemoryCardType::PerGameTitle)
  {
    if (const GameDatabase::Entry* entry = GameDatabase::GetEntryForSerial(game.serial))
    {
      const std::string playlist_title(Path::GetFileTitle(FileSystem::GetDisplayNameFromPath(game.boot_path)));
      const std::string database_card = Settings::GetGameMemoryCardPath(Path::SanitizeFileName(entry->title), slot);
      if (const std::optional<std::string> title = AndroidMemcards::PlaylistCardTitle(
            playlist_title, entry->title, FileSystem::FileExists(database_card.c_str()),
            UsePlaylistTitle(game.serial)))
      {
        path = Settings::GetGameMemoryCardPath(Path::SanitizeFileName(title.value()), slot);
      }
    }
  }
  return path;
}

} // namespace
} // namespace MemcardEditorBridge

std::vector<MemcardEditorBridge::CardFile> MemcardEditorBridge::ListCards(std::span<const GameCard> games)
{
  std::vector<CardFile> ret;
  EmuThread::RunOnThread(
    [games, &ret]() {
      CardList list;
      std::vector<std::pair<std::string, u32>> shared;
      for (const GameCard& game : games)
      {
        for (u32 slot = 0; slot < 2; slot++)
        {
          std::string path = GameCardPath(game, slot, &shared);
          list.Add(std::move(path), (slot == 0) ? game.title : fmt::format("{} (card 2)", game.title),
                   CardKind::Game);
        }
      }

      for (u32 slot = 0; slot < 2; slot++)
      {
        const std::string path = SharedPath({}, slot);
        const bool used = std::any_of(shared.begin(), shared.end(), [&path](const auto& s) { return s.first == path; });
        if (used || BaseType(slot) == MemoryCardType::Shared || FileSystem::FileExists(path.c_str()))
          list.Add(path, SharedName(path, slot), CardKind::Shared);
      }
      for (const auto& [path, slot] : shared)
        list.Add(path, SharedName(path, slot), CardKind::Shared);

      // The others, by name.
      CardList other_list;
      const auto add_other = [&list, &other_list](std::string path) {
        if (list.Has(path) || !FileSystem::FileExists(path.c_str()))
          return;
        std::string name = AndroidMemcards::OtherCardName(Path::GetFileName(path));
        other_list.Add(std::move(path), std::move(name), CardKind::Other);
      };
      if (const std::optional<std::vector<std::string>> paths = AndroidHost::GetMemoryCardPaths(); paths.has_value())
      {
        for (const std::string& relative : paths.value())
        {
          if (!relative.empty() && !relative.ends_with('/'))
            add_other(Path::Combine(EmuFolders::DataRoot, relative));
        }
      }
      FileSystem::FindResultsArray files;
      FileSystem::FindFiles(EmuFolders::MemoryCards.c_str(), "*.mcd", FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_HIDDEN_FILES,
                            &files);
      for (const FILESYSTEM_FIND_DATA& file : files)
        add_other(file.FileName);

      ret = list.Take();
      std::vector<CardFile>& sorted = other_list.Cards();
      std::sort(sorted.begin(), sorted.end(), [](const CardFile& a, const CardFile& b) {
        return StringUtil::Strcasecmp(a.name.c_str(), b.name.c_str()) < 0;
      });
      ret.insert(ret.end(), std::make_move_iterator(sorted.begin()), std::make_move_iterator(sorted.end()));
    },
    true);
  return ret;
}

namespace MemcardEditorBridge {
namespace {

// Runs f on the CPU thread, waiting for it, and returns what it returned.
template<typename F>
auto OnCPUThread(F&& f) -> decltype(f())
{
  decltype(f()) ret{};
  EmuThread::RunOnThread([&ret, &f]() { ret = f(); }, true);
  return ret;
}

// The card in bytes, or null unless it's a card image.
std::unique_ptr<MemoryCardImage::DataArray> ToCard(std::span<const u8> bytes)
{
  if (bytes.size() != MemoryCardImage::DATA_SIZE)
    return nullptr;
  auto card = std::make_unique<MemoryCardImage::DataArray>();
  std::memcpy(card->data(), bytes.data(), bytes.size());
  if (!MemoryCardImage::IsValid(*card))
    return nullptr;
  return card;
}

std::vector<u8> ToBytes(const MemoryCardImage::DataArray& card)
{
  return std::vector<u8>(card.begin(), card.end());
}

// The save at first_block, deleted or not.
std::optional<MemoryCardImage::FileInfo> FindSave(const MemoryCardImage::DataArray& card, u32 first_block)
{
  for (MemoryCardImage::FileInfo& fi : MemoryCardImage::EnumerateFiles(card, true))
  {
    if (fi.first_block == first_block)
      return std::move(fi);
  }
  return std::nullopt;
}

constexpr const char* NOT_A_CARD = "This isn't a memory card.";
constexpr const char* NO_SUCH_SAVE = "That save is no longer on the card.";

Edit Failed(std::string error)
{
  return Edit{std::nullopt, std::move(error)};
}

Edit Done(const MemoryCardImage::DataArray& card)
{
  return Edit{ToBytes(card), {}};
}

// The live saves' file names on card, but the one at first_block.
std::vector<std::string> OtherNames(const MemoryCardImage::DataArray& card, u32 first_block)
{
  std::vector<std::string> names;
  for (const MemoryCardImage::FileInfo& fi : MemoryCardImage::EnumerateFiles(card, false))
  {
    if (fi.first_block != first_block)
      names.push_back(fi.filename);
  }
  return names;
}

RenameCheck Check(const MemoryCardImage::DataArray& card, const MemoryCardImage::FileInfo& fi,
                  const std::optional<std::string>& title, const std::optional<std::string>& name,
                  std::array<u8, MemoryCardHelpers::TITLE_SIZE>* title_sjis)
{
  RenameCheck check{};
  const std::span<const u8, MemoryCardHelpers::TITLE_SIZE> stored = MemoryCardImage::GetRawTitle(card, fi);
  const MemoryCardHelpers::TitleStyle style = MemoryCardHelpers::GetTitleStyle(stored);
  check.single_byte_title = (style == MemoryCardHelpers::TitleStyle::SingleByte);
  if (title.has_value())
  {
    check.title_error = static_cast<u32>(MemoryCardHelpers::EncodeTitle(title.value(), title_sjis, style));
    check.title_not_shown_exactly = !MemoryCardHelpers::IsTitleShownExactly(stored);
  }
  if (name.has_value())
  {
    const std::vector<std::string> others = OtherNames(card, fi.first_block);
    const std::vector<std::string_view> other_views(others.begin(), others.end());
    check.name_error = static_cast<u32>(MemoryCardHelpers::CheckFileName(name.value(), other_views));
    check.name_not_standard = !MemoryCardHelpers::IsStandardFileName(name.value());
  }
  return check;
}

std::optional<std::vector<u8>> ReadFile(const std::string& path)
{
  FileSystem::ManagedCFilePtr fp = FileSystem::OpenManagedCFile(path.c_str(), "rb");
  if (!fp)
    return std::nullopt;
  std::vector<u8> bytes(READ_LIMIT);
  bytes.resize(std::fread(bytes.data(), 1, bytes.size(), fp.get()));
  return bytes;
}

} // namespace
} // namespace MemcardEditorBridge

std::optional<std::vector<u8>> MemcardEditorBridge::ReadCard(const std::string& path)
{
  return OnCPUThread([&path]() { return ReadFile(path); });
}

std::optional<MemcardEditorBridge::Contents> MemcardEditorBridge::GetContents(std::span<const u8> bytes)
{
  return OnCPUThread([bytes]() -> std::optional<Contents> {
    const std::unique_ptr<MemoryCardImage::DataArray> card = ToCard(bytes);
    if (!card)
      return std::nullopt;
    Contents contents{MemoryCardImage::GetFreeBlockCount(*card), {}};
    for (MemoryCardImage::FileInfo& fi : MemoryCardImage::EnumerateFiles(*card, true))
    {
      Save save{fi.first_block, std::move(fi.filename), std::move(fi.title), fi.num_blocks, fi.deleted, {}};
      if (!fi.icon_frames.empty())
        save.icon.assign(std::begin(fi.icon_frames[0].pixels), std::end(fi.icon_frames[0].pixels));
      contents.saves.push_back(std::move(save));
    }
    return contents;
  });
}

std::vector<u8> MemcardEditorBridge::Format()
{
  return OnCPUThread([]() {
    auto card = std::make_unique<MemoryCardImage::DataArray>();
    MemoryCardImage::Format(card.get());
    return ToBytes(*card);
  });
}

MemcardEditorBridge::Edit MemcardEditorBridge::CopySave(std::span<const u8> from_bytes, u32 from_block,
                                                        std::span<const u8> to_bytes, bool replace)
{
  return OnCPUThread([&]() {
    const std::unique_ptr<MemoryCardImage::DataArray> from = ToCard(from_bytes);
    const std::unique_ptr<MemoryCardImage::DataArray> to = ToCard(to_bytes);
    if (!from || !to)
      return Failed(NOT_A_CARD);
    const std::optional<MemoryCardImage::FileInfo> fi = FindSave(*from, from_block);
    if (!fi.has_value() || fi->deleted)
      return Failed(NO_SUCH_SAVE);

    Error error;
    std::vector<u8> data;
    if (!MemoryCardImage::ReadFile(*from, fi.value(), &data, &error))
      return Failed(error.GetDescription());
    for (const MemoryCardImage::FileInfo& existing : MemoryCardImage::EnumerateFiles(*to, false))
    {
      if (existing.filename != fi->filename)
        continue;
      if (!replace)
        return Failed(fmt::format("This card already has a save named {}.", existing.filename));
      MemoryCardImage::DeleteFile(to.get(), existing, true);
    }
    if (!MemoryCardImage::WriteFile(to.get(), fi->filename, data, &error))
      return Failed(error.GetDescription());
    return Done(*to);
  });
}

std::optional<std::vector<u8>> MemcardEditorBridge::DeleteSave(std::span<const u8> bytes, u32 first_block,
                                                               bool permanently)
{
  return OnCPUThread([&]() -> std::optional<std::vector<u8>> {
    const std::unique_ptr<MemoryCardImage::DataArray> card = ToCard(bytes);
    const std::optional<MemoryCardImage::FileInfo> fi = card ? FindSave(*card, first_block) : std::nullopt;
    if (!fi.has_value() || !MemoryCardImage::DeleteFile(card.get(), fi.value(), permanently))
      return std::nullopt;
    return ToBytes(*card);
  });
}

std::optional<std::vector<u8>> MemcardEditorBridge::UndeleteSave(std::span<const u8> bytes, u32 first_block)
{
  return OnCPUThread([&]() -> std::optional<std::vector<u8>> {
    const std::unique_ptr<MemoryCardImage::DataArray> card = ToCard(bytes);
    const std::optional<MemoryCardImage::FileInfo> fi = card ? FindSave(*card, first_block) : std::nullopt;
    if (!fi.has_value() || !MemoryCardImage::UndeleteFile(card.get(), fi.value()))
      return std::nullopt;
    return ToBytes(*card);
  });
}

MemcardEditorBridge::RenameCheck MemcardEditorBridge::CheckRename(std::span<const u8> bytes, u32 first_block,
                                                                  const std::optional<std::string>& title,
                                                                  const std::optional<std::string>& name)
{
  return OnCPUThread([&]() {
    const std::unique_ptr<MemoryCardImage::DataArray> card = ToCard(bytes);
    const std::optional<MemoryCardImage::FileInfo> fi = card ? FindSave(*card, first_block) : std::nullopt;
    if (!fi.has_value())
      return RenameCheck{};
    std::array<u8, MemoryCardHelpers::TITLE_SIZE> title_sjis;
    return Check(*card, fi.value(), title, name, &title_sjis);
  });
}

std::optional<std::vector<u8>> MemcardEditorBridge::Rename(std::span<const u8> bytes, u32 first_block,
                                                           const std::optional<std::string>& title,
                                                           const std::optional<std::string>& name)
{
  return OnCPUThread([&]() -> std::optional<std::vector<u8>> {
    const std::unique_ptr<MemoryCardImage::DataArray> card = ToCard(bytes);
    const std::optional<MemoryCardImage::FileInfo> fi = card ? FindSave(*card, first_block) : std::nullopt;
    if (!fi.has_value() || fi->deleted)
      return std::nullopt;
    std::array<u8, MemoryCardHelpers::TITLE_SIZE> title_sjis;
    const RenameCheck check = Check(*card, fi.value(), title, name, &title_sjis);
    if (check.title_error != 0 || check.name_error != 0)
      return std::nullopt;
    if (title.has_value())
      MemoryCardImage::SetTitle(card.get(), fi.value(), title_sjis);
    if (name.has_value() && !MemoryCardImage::SetFileName(card.get(), fi.value(), name.value()))
      return std::nullopt;
    return ToBytes(*card);
  });
}

MemcardEditorBridge::Edit MemcardEditorBridge::ImportSave(std::span<const u8> bytes, const std::string& file_name,
                                                          std::span<const u8> data)
{
  return OnCPUThread([&]() {
    const std::unique_ptr<MemoryCardImage::DataArray> card = ToCard(bytes);
    if (!card)
      return Failed(NOT_A_CARD);
    Error error;
    if (!MemoryCardImage::ImportSave(card.get(), file_name.c_str(), data, &error))
      return Failed(error.GetDescription());
    return Done(*card);
  });
}

MemcardEditorBridge::Edit MemcardEditorBridge::ImportCard(const std::string& file_name, std::span<const u8> data)
{
  return OnCPUThread([&]() {
    auto card = std::make_unique<MemoryCardImage::DataArray>();
    MemoryCardImage::Format(card.get());
    Error error;
    if (!MemoryCardImage::ImportCard(card.get(), file_name.c_str(), data, &error))
      return Failed(error.GetDescription());
    return Done(*card);
  });
}

MemcardEditorBridge::Edit MemcardEditorBridge::ExportSave(std::span<const u8> bytes, u32 first_block)
{
  return OnCPUThread([&]() {
    const std::unique_ptr<MemoryCardImage::DataArray> card = ToCard(bytes);
    const std::optional<MemoryCardImage::FileInfo> fi = card ? FindSave(*card, first_block) : std::nullopt;
    if (!fi.has_value() || fi->deleted)
      return Failed(NO_SUCH_SAVE);
    Error error;
    std::vector<u8> mcs;
    if (!MemoryCardImage::ExportSave(*card, fi.value(), &mcs, &error))
      return Failed(error.GetDescription());
    return Edit{std::move(mcs), {}};
  });
}

bool MemcardEditorBridge::IsKnownToSync(const std::string& path)
{
  return MemcardSyncBridge::IsCardKnown(path);
}

MemcardEditorBridge::WriteResult MemcardEditorBridge::WriteCard(const std::string& path, std::span<const u8> card,
                                                                const std::optional<std::span<const u8>>& expected,
                                                                bool force)
{
  WriteResult result{WriteOutcome::Failed, {}};
  const auto write = [&]() {
    if (!force)
    {
      const std::optional<std::vector<u8>> current = ReadFile(path);
      const bool same = current.has_value() == expected.has_value() &&
                        (!current.has_value() || std::equal(current->begin(), current->end(), expected->begin(),
                                                            expected->end()));
      if (!same)
      {
        result.outcome = WriteOutcome::Changed;
        return;
      }
    }
    Error error;
    if (!FileSystem::EnsureDirectoryExists(std::string(Path::GetDirectory(path)).c_str(), false, &error) ||
        !FileSystem::WriteAtomicRenamedFile(path.c_str(), card.data(), card.size(), &error))
    {
      result.error = error.GetDescription();
      return;
    }
    result.outcome = WriteOutcome::Saved;
  };

  // Off the CPU thread, as the mirror's holds are: a hold can wait for emusync.
  const std::optional<std::string> relative = AndroidHost::RelativeToDataRoot(path);
  if (relative.has_value() && IsKnownToSync(path))
  {
    if (!MemcardSyncBridge::HoldCard(relative.value(), write))
      return WriteResult{WriteOutcome::Busy, {}};
  }
  else
  {
    write();
  }
  return result;
}
