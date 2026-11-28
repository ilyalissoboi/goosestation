// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "game_codes.h"

#include "core/achievements.h"
#include "core/cheats.h"
#include "core/patches.h"
#include "core/settings.h"
#include "core/system.h"

#include "util/android_patch_codes.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/path.h"
#include "common/string_util.h"

#include "fmt/format.h"

#include <algorithm>
#include <map>
#include <memory>

Log_SetChannel(GameCodes);

namespace GameCodes {

// The core's name for a code without a group.
static constexpr std::string_view UNGROUPED = "Ungrouped";

static CheatList s_codes;              // the game's cheats, as the core is given them
static std::vector<bool> s_default;    // whether each cheat starts on without the app's file
static std::vector<u32> s_default_value; // each cheat's value as read
static std::unique_ptr<Patches::PatchList> s_patches;  // the game's patches, as the app's file has them
static std::map<std::string, bool> s_patch_default_on; // by code key: whether a patch code starts on
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

// A value's text, as the app's file keeps it: an option's name, else the number.
static std::string ValueText(const CheatCode& code)
{
  for (const CheatCode::Option& option : code.options)
  {
    if (option.value == code.option_value)
      return option.name;
  }
  return fmt::format("{}", code.option_value);
}

static std::optional<u32> ValueFromText(const CheatCode& code, std::string_view text)
{
  for (const CheatCode::Option& option : code.options)
  {
    if (option.name == text)
      return option.value;
  }
  return StringUtil::FromChars<u32>(text);
}

// Each code of the files, in order: the game's own file first, then each disc's, then the newer format's by serial,
// each path read once.
static std::vector<CheatCode> ReadPlayerFiles(const AndroidDiscGroups::Game& game)
{
  std::vector<std::string> titles = {game.title};
  for (const AndroidDiscGroups::Disc& disc : game.discs)
    titles.push_back(disc.title);
  for (const AndroidDiscGroups::Disc& disc : game.discs)
    titles.push_back(disc.serial);
  titles.push_back(game.key);

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

// A code of the database, with the disc and serial whose block it came from.
struct DatabaseCode
{
  CheatCode code;
  u32 disc;
  std::string serial;
};

// Each disc's codes from the database, in disc order.
static std::vector<DatabaseCode> ReadDatabase(const AndroidDiscGroups::Game& game)
{
  std::vector<std::pair<std::string, u32>> serials;
  const auto add = [&serials](const std::string& serial, u32 number) {
    const bool listed = std::any_of(serials.begin(), serials.end(), [&serial](const auto& s) { return s.first == serial; });
    if (!serial.empty() && !listed)
      serials.emplace_back(serial, number);
  };
  for (const AndroidDiscGroups::Disc& disc : game.discs)
    add(disc.serial, disc.number);
  add(game.key, 1);

  std::vector<DatabaseCode> codes;
  for (const auto& [serial, number] : serials)
  {
    CheatList package;
    if (!package.LoadFromPackage(serial))
      continue;

    for (u32 i = 0; i < package.GetCodeCount(); i++)
      codes.push_back({package.GetCode(i), number, serial});
  }

  return codes;
}

// A code's instructions as text, to tell codes apart, without the ones that do nothing: where the database files one
// block under several serials, the core reads the next serial's line into the first code as a no-op instruction for
// all but the last serial, which would make that code look different on each disc.
static std::string InstructionsOf(const CheatCode& code)
{
  std::string text;
  for (const CheatCode::Instruction& inst : code.instructions)
  {
    if (inst.code != CheatCode::InstructionCode::Nop)
      text += fmt::format("{:08X} {:08X}\n", inst.first, inst.second);
  }
  return text;
}

static AndroidPatchCodes::Listed ToListed(const CheatCode& code, u32 disc)
{
  return {GroupOf(code), code.description, code.enabled, code.IsManuallyActivated(), InstructionsOf(code), disc};
}

// Whether a player's code is a patch: by the marks of any of the game's serials, or its own file.
static std::optional<Patches::Mark> ClassifyPlayers(const AndroidDiscGroups::Game& game, CheatCode& code)
{
  std::vector<std::string_view> serials = {game.key};
  for (const AndroidDiscGroups::Disc& disc : game.discs)
    serials.push_back(disc.serial);
  for (const std::string_view serial : serials)
  {
    if (serial.empty())
      continue;
    if (const Patches::Mark* mark = Patches::BundledMarks().Find(serial, code.group, code.description))
    {
      code.patch = true;
      return *mark;
    }
  }
  return Patches::Classify(game.key, code, Patches::Marks());
}

// Every code's state for the app's file: the cheats as they stand (cheat index at on/value when given), and the
// patches as patches has them.
static std::vector<AndroidPatchCodes::State> StatesOf(const Patches::PatchList* patches, std::optional<u32> cheat_index,
                                                      bool cheat_on)
{
  std::vector<AndroidPatchCodes::State> states;
  for (u32 i = 0; i < s_codes.GetCodeCount(); i++)
  {
    const CheatCode& code = s_codes.GetCode(i);
    const bool on = (cheat_index == i) ? cheat_on : code.enabled;
    const std::string value = (code.HasOptions() && code.option_value != s_default_value[i]) ? ValueText(code) : "";
    states.push_back({GroupOf(code), code.description, s_default[i], on, value});
  }

  if (patches)
  {
    std::map<std::string, std::string> values;
    for (const Patches::Switch& sw : patches->Switches())
      values[sw.code] = sw.value;
    for (size_t p = 0; p < patches->Count(); p++)
    {
      const Patches::Patch& patch = patches->Get(p);
      for (size_t c = 0; c < patch.choices.size(); c++)
      {
        const CheatCode& code = patch.choices[c].code;
        const std::string key = Patches::CodeKey(code.group, code.description);
        states.push_back({GroupOf(code), code.description, s_patch_default_on[key], patch.saved == static_cast<s32>(c),
                          values[key]});
      }
    }
  }
  return states;
}

// Writes the app's file whole, then renames it over the old one (the mirror never copies a .goosetmp file).
static std::optional<std::string> WriteSwitches(const std::vector<AndroidPatchCodes::Switch>& switches)
{
  if (s_key.empty())
    return std::nullopt;

  Error error;
  const std::string path = SwitchesPath();
  const std::string temp = path + ".goosetmp";
  if (!FileSystem::EnsureDirectoryExists(EmuFolders::Cheats.c_str(), false, &error) ||
      !FileSystem::WriteStringToFile(temp.c_str(), AndroidPatchCodes::Format(switches), &error) ||
      !FileSystem::RenamePath(temp.c_str(), path.c_str(), &error))
  {
    ERROR_LOG("Failed to save '{}': {}", Path::GetFileName(path), error.GetDescription());
    FileSystem::DeleteFile(temp.c_str());
    return fmt::format("It couldn't be saved: {}", error.GetDescription());
  }
  return std::nullopt;
}

static Values ValuesOf(const CheatCode& code)
{
  Values values;
  for (const CheatCode::Option& option : code.options)
    values.options.emplace_back(option.name, option.value);
  values.range = code.option_range;
  values.value = code.option_value;
  return values;
}

void Load(const AndroidDiscGroups::Game& game)
{
  Clear();
  s_key = game.key;
  s_title = game.title;

  const std::vector<CheatCode> player = ReadPlayerFiles(game);
  const std::vector<DatabaseCode> database = ReadDatabase(game);
  if (!s_key.empty())
  {
    if (const std::optional<std::string> text = FileSystem::ReadFileToString(SwitchesPath().c_str()))
      s_switches = AndroidPatchCodes::Parse(text.value());
  }

  std::vector<AndroidPatchCodes::Listed> player_listed;
  for (const CheatCode& code : player)
    player_listed.push_back(ToListed(code, 0));
  std::vector<AndroidPatchCodes::Listed> database_listed;
  for (const DatabaseCode& code : database)
    database_listed.push_back(ToListed(code.code, code.disc));

  std::vector<std::pair<CheatCode, Patches::Mark>> patch_codes;
  for (const AndroidPatchCodes::Entry& entry : AndroidPatchCodes::Combine(player_listed, database_listed, s_switches))
  {
    CheatCode code = entry.from_player ? player[entry.index] : database[entry.index].code;
    const AndroidPatchCodes::Listed& listed =
      entry.from_player ? player_listed[entry.index] : database_listed[entry.index];
    // A database code that differs between discs is shown and switched by its disc's name for it.
    code.description = entry.name;
    const bool default_on = entry.from_player && listed.marked_on && !listed.manual;

    // A patch, by the marks of the disc it came from (a player's code, of any of the game's discs).
    const std::optional<Patches::Mark> mark =
      entry.from_player ? ClassifyPlayers(game, code) :
                          Patches::Classify(database[entry.index].serial, code, Patches::BundledMarks());
    if (mark.has_value())
    {
      code.enabled = default_on;
      s_patch_default_on[Patches::CodeKey(code.group, code.description)] = default_on;
      patch_codes.emplace_back(std::move(code), mark.value());
      continue;
    }

    code.enabled = entry.on;
    s_default_value.push_back(code.option_value);
    // A value the player chose, by the option's name or as a number.
    const auto sw = std::find_if(s_switches.begin(), s_switches.end(), [&code](const AndroidPatchCodes::Switch& s) {
      return s.group == GroupOf(code) && s.name == code.description && !s.value.empty();
    });
    if (sw != s_switches.end() && code.HasOptions())
    {
      if (const std::optional<u32> value = ValueFromText(code, sw->value))
        code.SetOptionValue(value.value(), nullptr);
    }
    s_codes.AddCode(std::move(code));
    s_default.push_back(default_on);
  }

  if (!patch_codes.empty())
  {
    s_patches = std::make_unique<Patches::PatchList>(Patches::PatchList::Build(std::move(patch_codes)));
    std::vector<Patches::Switch> switches;
    for (const AndroidPatchCodes::Switch& sw : s_switches)
      switches.push_back({Patches::CodeKey(sw.group, sw.name), sw.on, sw.value});
    s_patches->Restore(switches);
  }

  INFO_LOG("{} patches and {} cheats for '{}' ({} codes from the player's files), {} cheats on.",
           s_patches ? s_patches->Count() : 0, s_codes.GetCodeCount(), s_title, player.size(),
           s_codes.GetEnabledCodeCount());
}

void Apply()
{
  if (System::IsShutdown())
    return;

  // None applies in hardcore mode (Host::OnAchievementsHardcoreModeChanged() applies them once it's off).
  if (Achievements::IsHardcoreModeActive())
  {
    System::SetCheatList(nullptr);
    System::SetPatchList(nullptr);
    return;
  }

  if (s_codes.GetCodeCount() > 0)
    System::SetCheatList(std::make_unique<CheatList>(s_codes));
  if (s_patches)
  {
    // Every start runs the saved choices.
    auto patches = std::make_unique<Patches::PatchList>(*s_patches);
    patches->Restart();
    System::SetPatchList(std::move(patches));
  }
}

std::vector<Code> List()
{
  std::vector<Code> codes;
  for (u32 i = 0; i < s_codes.GetCodeCount(); i++)
  {
    const CheatCode& code = s_codes.GetCode(i);
    codes.push_back({GroupOf(code), code.description, code.IsManuallyActivated(), code.enabled, ValuesOf(code)});
  }
  return codes;
}

std::vector<Patch> ListPatches()
{
  // The running game's, which knows what waits for the next start; else the app's.
  const Patches::PatchList* list = System::IsValid() ? System::GetPatchList() : nullptr;
  if (!list)
    list = s_patches.get();
  if (!list)
    return {};

  std::vector<Patch> patches;
  for (size_t i = 0; i < list->Count(); i++)
  {
    const Patches::Patch& patch = list->Get(i);
    const Patches::Choice& shown = patch.choices[static_cast<size_t>(std::max<s32>(patch.saved, 0))];

    std::vector<std::string> notes;
    if (shown.needs.overclock_percent != 0)
      notes.push_back(fmt::format("Runs with a {}% CPU overclock.", shown.needs.overclock_percent));
    else if (shown.needs.may_need_overclock)
      notes.push_back("May need a CPU overclock to hold its frame rate.");
    if (!shown.code.details.empty())
      notes.push_back(shown.code.details);
    for (const std::string& note : shown.code.notes)
      notes.push_back(note);

    Patch row;
    row.group = patch.group;
    row.title = patch.title;
    for (const Patches::Choice& choice : patch.choices)
      row.choices.push_back(choice.code.description);
    row.saved = patch.saved;
    row.running = patch.running;
    row.values = ValuesOf(shown.code);
    row.note = StringUtil::JoinString(notes.begin(), notes.end(), ' ');
    row.can_enable = shown.code.CanEnable();
    patches.push_back(std::move(row));
  }
  return patches;
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
  if (on && !code.CanEnable())
    return std::string("This code can't be switched on.");

  // Kept in the app's file first, so a switch that can't be kept doesn't happen: the panel shows it as it is.
  std::vector<AndroidPatchCodes::Switch> switches =
    AndroidPatchCodes::Remember(s_switches, StatesOf(s_patches.get(), index, on));
  if (std::optional<std::string> error = WriteSwitches(switches))
    return error;
  if (!s_key.empty())
    s_switches = std::move(switches);

  code.enabled = on;
  // The core's copy undoes a code switched off, once.
  if (CheatList* live = System::GetCheatList(); live && index < live->GetCodeCount())
    live->SetCodeEnabled(index, on);
  else if (on)
    Apply();

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
  if (Achievements::IsHardcoreModeActive())
    return std::string("Patch codes are off in hardcore mode.");
  if (!code.CanEnable())
    return std::string("This code can't be applied.");

  code.Apply();
  return std::nullopt;
}

bool PatchNeedsReset(u32 index, s32 choice)
{
  return System::IsValid() && System::PatchNeedsReset(index, choice);
}

std::optional<std::string> SetPatch(u32 index, s32 choice, bool reset_now)
{
  if (!s_patches || index >= s_patches->Count())
    return std::string("There's no such patch.");
  if (Achievements::IsHardcoreModeActive())
    return std::string("Patches are off in hardcore mode.");

  const Patches::Patch& patch = s_patches->Get(index);
  if (choice < -1 || choice >= static_cast<s32>(patch.choices.size()))
    return std::string("There's no such choice.");
  if (choice >= 0 && !patch.choices[static_cast<size_t>(choice)].code.CanEnable())
    return std::string("This patch can't be switched on.");

  // Kept in the app's file first: a change that can't be kept doesn't happen.
  Patches::PatchList next = *s_patches;
  next.Set(index, choice);
  if (reset_now)
    next.Restart();
  std::vector<AndroidPatchCodes::Switch> switches =
    AndroidPatchCodes::Remember(s_switches, StatesOf(&next, std::nullopt, false));
  if (std::optional<std::string> error = WriteSwitches(switches))
    return error;
  if (!s_key.empty())
    s_switches = std::move(switches);
  s_patches = std::make_unique<Patches::PatchList>(std::move(next));

  if (System::IsValid())
    System::SetPatch(index, choice, reset_now);
  return std::nullopt;
}

std::optional<std::string> SetValue(bool patch, u32 index, s32 choice, u32 value)
{
  Error error;
  if (patch)
  {
    if (!s_patches || index >= s_patches->Count())
      return std::string("There's no such patch.");

    Patches::PatchList next = *s_patches;
    if (!next.SetValue(index, choice, value, &error))
      return error.GetDescription();
    std::vector<AndroidPatchCodes::Switch> switches =
      AndroidPatchCodes::Remember(s_switches, StatesOf(&next, std::nullopt, false));
    if (std::optional<std::string> write_error = WriteSwitches(switches))
      return write_error;
    if (!s_key.empty())
      s_switches = std::move(switches);
    s_patches = std::make_unique<Patches::PatchList>(std::move(next));
    if (System::IsValid())
      System::SetPatchValue(index, choice, value, nullptr);
    return std::nullopt;
  }

  if (index >= s_codes.GetCodeCount())
    return std::string("There's no such patch code.");
  CheatCode& code = s_codes.GetCode(index);
  const u32 old = code.option_value;
  if (!code.SetOptionValue(value, &error))
    return error.GetDescription();

  std::vector<AndroidPatchCodes::Switch> switches =
    AndroidPatchCodes::Remember(s_switches, StatesOf(s_patches.get(), std::nullopt, false));
  if (std::optional<std::string> write_error = WriteSwitches(switches))
  {
    code.SetOptionValue(old, nullptr);
    return write_error;
  }
  if (!s_key.empty())
    s_switches = std::move(switches);
  if (CheatList* live = System::GetCheatList(); live && index < live->GetCodeCount())
    live->GetCode(index).SetOptionValue(value, nullptr);
  return std::nullopt;
}

void Clear()
{
  // CheatList can't be assigned (its instructions can't), so it's emptied as the core empties one.
  while (s_codes.GetCodeCount() > 0)
    s_codes.RemoveCode(s_codes.GetCodeCount() - 1);
  s_default.clear();
  s_default_value.clear();
  s_patches.reset();
  s_patch_default_on.clear();
  s_switches.clear();
  s_key.clear();
  s_title.clear();
}

} // namespace GameCodes
