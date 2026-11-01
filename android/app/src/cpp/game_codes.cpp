// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "game_codes.h"

#include "core/cheats.h"
#include "core/settings.h"
#include "core/system.h"

#include "util/android_patch_codes.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/path.h"

#include "fmt/format.h"

#include <algorithm>
#include <memory>

Log_SetChannel(GameCodes);

namespace GameCodes {

// The core's name for a code without a group.
static constexpr std::string_view UNGROUPED = "Ungrouped";

static CheatList s_codes;           // the game's list, as the core is given it
static std::vector<bool> s_default; // whether each code starts on without the app's file
static std::vector<AndroidPatchCodes::Switch> s_switches; // the app's file, as last read or written
static std::string s_key;
static std::string s_title;

static std::string GroupOf(const CheatCode& code)
{
  return (code.group == UNGROUPED) ? std::string() : code.group;
}

static std::string SwitchesPath()
{
  return Path::Combine(EmuFolders::Cheats, AndroidPatchCodes::FileName(s_key));
}

// Each code of the files, in order: the game's own file first, then each disc's, each path read once.
static std::vector<CheatCode> ReadPlayerFiles(const AndroidDiscGroups::Game& game)
{
  std::vector<std::string> titles = {game.title};
  for (const AndroidDiscGroups::Disc& disc : game.discs)
    titles.push_back(disc.title);

  std::vector<std::string> paths;
  for (const std::string& title : titles)
  {
    if (title.empty())
      continue;

    for (const std::string& name : {title, AndroidPatchCodes::SafeFileName(title)})
    {
      std::string path = Path::Combine(EmuFolders::Cheats, fmt::format("{}.cht", name));
      if (std::find(paths.begin(), paths.end(), path) == paths.end())
        paths.push_back(std::move(path));
    }
  }

  std::vector<CheatCode> codes;
  for (const std::string& path : paths)
  {
    if (!FileSystem::FileExists(path.c_str()))
      continue;

    CheatList file;
    if (!file.LoadFromFile(path.c_str(), CheatList::Format::Autodetect))
    {
      WARNING_LOG("Couldn't read the patch codes in '{}'.", Path::GetFileName(path));
      continue;
    }

    INFO_LOG("{} patch codes from '{}'.", file.GetCodeCount(), Path::GetFileName(path));
    for (u32 i = 0; i < file.GetCodeCount(); i++)
      codes.push_back(file.GetCode(i));
  }

  return codes;
}

// Each disc's codes from the database, in disc order.
static std::vector<CheatCode> ReadDatabase(const AndroidDiscGroups::Game& game)
{
  std::vector<std::string> serials = {game.key};
  for (const AndroidDiscGroups::Disc& disc : game.discs)
  {
    if (std::find(serials.begin(), serials.end(), disc.serial) == serials.end())
      serials.push_back(disc.serial);
  }

  std::vector<CheatCode> codes;
  for (const std::string& serial : serials)
  {
    CheatList package;
    if (serial.empty() || !package.LoadFromPackage(serial))
      continue;

    for (u32 i = 0; i < package.GetCodeCount(); i++)
      codes.push_back(package.GetCode(i));
  }

  return codes;
}

static std::vector<AndroidPatchCodes::Listed> ToListed(const std::vector<CheatCode>& codes)
{
  std::vector<AndroidPatchCodes::Listed> listed;
  listed.reserve(codes.size());
  for (const CheatCode& code : codes)
    listed.push_back({GroupOf(code), code.description, code.enabled, code.IsManuallyActivated()});
  return listed;
}

void Load(const AndroidDiscGroups::Game& game)
{
  Clear();
  s_key = game.key;
  s_title = game.title;

  const std::vector<CheatCode> player = ReadPlayerFiles(game);
  const std::vector<CheatCode> database = ReadDatabase(game);
  if (!s_key.empty())
  {
    if (const std::optional<std::string> text = FileSystem::ReadFileToString(SwitchesPath().c_str()))
      s_switches = AndroidPatchCodes::Parse(text.value());
  }

  const std::vector<AndroidPatchCodes::Listed> player_listed = ToListed(player);
  const std::vector<AndroidPatchCodes::Listed> database_listed = ToListed(database);
  for (const AndroidPatchCodes::Entry& entry : AndroidPatchCodes::Combine(player_listed, database_listed, s_switches))
  {
    CheatCode code = entry.from_player ? player[entry.index] : database[entry.index];
    const AndroidPatchCodes::Listed& listed =
      entry.from_player ? player_listed[entry.index] : database_listed[entry.index];
    code.enabled = entry.on;
    s_codes.AddCode(std::move(code));
    s_default.push_back(entry.from_player && listed.marked_on && !listed.manual);
  }

  INFO_LOG("{} patch codes for '{}' ({} from the player's files), {} on.", s_codes.GetCodeCount(), s_title,
           player.size(), s_codes.GetEnabledCodeCount());
}

void Apply()
{
  if (System::IsShutdown() || s_codes.GetCodeCount() == 0)
    return;

  System::SetCheatList(std::make_unique<CheatList>(s_codes));
}

std::vector<Code> List()
{
  std::vector<Code> codes;
  for (u32 i = 0; i < s_codes.GetCodeCount(); i++)
  {
    const CheatCode& code = s_codes.GetCode(i);
    codes.push_back({GroupOf(code), code.description, code.IsManuallyActivated(), code.enabled});
  }
  return codes;
}

std::string AddFileName()
{
  return fmt::format("{}.cht", AndroidPatchCodes::SafeFileName(s_title));
}

std::optional<std::string> SetOn(u32 index, bool on)
{
  if (index >= s_codes.GetCodeCount())
    return std::string("There's no such patch code.");

  CheatCode& code = s_codes.GetCode(index);
  if (code.IsManuallyActivated())
    return std::string("This code is applied by hand.");
  if (code.enabled == on)
    return std::nullopt;

  code.enabled = on;
  // The core's copy undoes a code switched off, once.
  if (CheatList* live = System::GetCheatList(); live && index < live->GetCodeCount())
    live->SetCodeEnabled(index, on);
  else if (on)
    Apply();

  if (s_key.empty())
    return std::nullopt;

  std::vector<AndroidPatchCodes::State> states;
  for (u32 i = 0; i < s_codes.GetCodeCount(); i++)
  {
    const CheatCode& each = s_codes.GetCode(i);
    states.push_back({GroupOf(each), each.description, s_default[i], each.enabled});
  }
  s_switches = AndroidPatchCodes::Remember(s_switches, states);

  // Written whole, then renamed over the old one: the mirror never copies a .goosetmp file.
  Error error;
  const std::string path = SwitchesPath();
  const std::string temp = path + ".goosetmp";
  if (!FileSystem::EnsureDirectoryExists(EmuFolders::Cheats.c_str(), false, &error) ||
      !FileSystem::WriteStringToFile(temp.c_str(), AndroidPatchCodes::Format(s_switches), &error) ||
      !FileSystem::RenamePath(temp.c_str(), path.c_str(), &error))
  {
    ERROR_LOG("Failed to save '{}': {}", Path::GetFileName(path), error.GetDescription());
    return fmt::format("Couldn't keep it for next time: {}", error.GetDescription());
  }

  return std::nullopt;
}

std::optional<std::string> ApplyOnce(u32 index)
{
  if (index >= s_codes.GetCodeCount())
    return std::string("There's no such patch code.");

  const CheatCode& code = s_codes.GetCode(index);
  if (!code.IsManuallyActivated())
    return std::string("This code isn't applied by hand.");
  if (!System::IsValid())
    return std::string("No game is running.");

  code.Apply();
  return std::nullopt;
}

void Clear()
{
  // CheatList can't be assigned (its instructions can't), so it's emptied as the core empties one.
  while (s_codes.GetCodeCount() > 0)
    s_codes.RemoveCode(s_codes.GetCodeCount() - 1);
  s_default.clear();
  s_switches.clear();
  s_key.clear();
  s_title.clear();
}

} // namespace GameCodes
