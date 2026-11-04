// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "memcard_editor_bridge.h"
#include "android_host.h"
#include "emu_thread.h"

#include "core/game_database.h"
#include "core/host.h"
#include "core/settings.h"
#include "core/system.h"

#include "util/android_memcards.h"
#include "util/ini_settings_interface.h"

#include "common/file_system.h"
#include "common/path.h"
#include "common/string_util.h"

#include <algorithm>

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
            Host::GetBaseBoolSettingValue(SECTION, "UsePlaylistTitle", true)))
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
