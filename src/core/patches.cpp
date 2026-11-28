// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "patches.h"
#include "host.h"
#include "settings.h"
#include "system.h"

#include "util/ini_settings_interface.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/path.h"
#include "common/settings_interface.h"
#include "common/string_util.h"

#include "fmt/format.h"

#include <algorithm>
#include <charconv>
#include <mutex>

Log_SetChannel(Patches);

namespace Patches {

// The core's group for codes without one.
static constexpr std::string_view UNGROUPED = "Ungrouped";

static std::optional<u32> ParseUnsigned(std::string_view str)
{
  u32 value = 0;
  const auto [ptr, ec] = std::from_chars(str.data(), str.data() + str.size(), value);
  if (ec != std::errc() || ptr != str.data() + str.size())
    return std::nullopt;
  return value;
}

// `N:M`, both above zero and fitting u16.
static bool ParseRatio(std::string_view str, u16* numerator, u16* denominator)
{
  const size_t colon = str.find(':');
  if (colon == std::string_view::npos)
    return false;
  const std::optional<u32> n = ParseUnsigned(StringUtil::StripWhitespace(str.substr(0, colon)));
  const std::optional<u32> d = ParseUnsigned(StringUtil::StripWhitespace(str.substr(colon + 1)));
  if (!n.has_value() || !d.has_value() || n.value() == 0 || d.value() == 0 || n.value() > 0xFFFF ||
      d.value() > 0xFFFF)
  {
    return false;
  }
  *numerator = static_cast<u16>(n.value());
  *denominator = static_cast<u16>(d.value());
  return true;
}

std::string CodeKey(std::string_view group, std::string_view name)
{
  if (group.empty() || group == UNGROUPED)
    return std::string(name);
  return fmt::format("{}\\{}", group, name);
}

static std::string MapKey(std::string_view serial, std::string_view code_key)
{
  return fmt::format("{}\t{}", serial, code_key);
}

// A line's third field: the kind, then a ratio, `overclock=N` and `choice=<title>` (the rest of the line).
static std::optional<Mark> ParseMarkField(std::string_view field)
{
  field = StringUtil::StripWhitespace(field);
  const size_t space = field.find(' ');
  const std::string_view kind = field.substr(0, space);
  std::string_view rest = (space == std::string_view::npos) ? std::string_view() : field.substr(space + 1);

  Mark mark;
  bool measured_none = false;
  if (kind == "widescreen")
  {
    mark.kind = Kind::Widescreen;
    mark.needs.disable_widescreen_rendering = true;
    mark.needs.aspect_numerator = 16;
    mark.needs.aspect_denominator = 9;
  }
  else if (kind == "framerate")
  {
    mark.kind = Kind::FrameRate;
  }
  else if (kind == "displaymode")
  {
    mark.kind = Kind::DisplayMode;
  }
  else
  {
    return std::nullopt;
  }

  while (!(rest = StringUtil::StripWhitespace(rest)).empty())
  {
    if (rest.starts_with("choice="))
    {
      mark.choice = std::string(StringUtil::StripWhitespace(rest.substr(7)));
      if (mark.choice.empty())
        return std::nullopt;
      break;
    }

    const size_t next = rest.find(' ');
    const std::string_view token = rest.substr(0, next);
    rest = (next == std::string_view::npos) ? std::string_view() : rest.substr(next + 1);
    if (token == "overclock=none")
    {
      // Measured: it holds its rate without one.
      measured_none = true;
    }
    else if (token.starts_with("overclock="))
    {
      const std::optional<u32> percent = ParseUnsigned(token.substr(10));
      if (!percent.has_value() || percent.value() < 10 || percent.value() > 1000)
        return std::nullopt;
      mark.needs.overclock_percent = percent.value();
    }
    else if (mark.kind == Kind::Widescreen &&
             ParseRatio(token, &mark.needs.aspect_numerator, &mark.needs.aspect_denominator))
    {
    }
    else
    {
      return std::nullopt;
    }
  }

  mark.needs.may_need_overclock = (mark.kind == Kind::FrameRate && mark.needs.overclock_percent == 0 && !measured_none);
  return mark;
}

Marks Marks::Parse(std::string_view text, Error* error)
{
  Marks marks;
  bool reported = false;
  u32 line_number = 0;
  while (!text.empty())
  {
    const size_t eol = text.find('\n');
    std::string_view line = text.substr(0, eol);
    text = (eol == std::string_view::npos) ? std::string_view() : text.substr(eol + 1);
    line_number++;

    if (!line.empty() && line.back() == '\r')
      line.remove_suffix(1);
    if (StringUtil::StripWhitespace(line).empty() || line.front() == ';')
      continue;

    const size_t tab1 = line.find('\t');
    const size_t tab2 = (tab1 == std::string_view::npos) ? tab1 : line.find('\t', tab1 + 1);
    std::optional<Mark> mark;
    if (tab2 != std::string_view::npos && tab1 > 0 && tab2 > tab1 + 1)
      mark = ParseMarkField(line.substr(tab2 + 1));
    if (!mark.has_value())
    {
      if (!reported)
      {
        Error::SetStringFmt(error, "chtdb_patches.txt line {} can't be read: '{}'", line_number, line);
        reported = true;
      }
      WARNING_LOG("Skipping patch mark line {}: '{}'", line_number, line);
      continue;
    }

    marks.m_marks.insert_or_assign(MapKey(line.substr(0, tab1), line.substr(tab1 + 1, tab2 - tab1 - 1)),
                                   std::move(mark.value()));
  }

  return marks;
}

const Mark* Marks::Find(std::string_view serial, std::string_view group, std::string_view name) const
{
  const auto it = m_marks.find(MapKey(serial, CodeKey(group, name)));
  return (it != m_marks.end()) ? &it->second : nullptr;
}

const Marks& BundledMarks()
{
  static const Marks s_marks = []() {
    const std::optional<std::string> text = Host::ReadResourceFileToString("chtdb_patches.txt", false);
    if (!text.has_value())
    {
      WARNING_LOG("chtdb_patches.txt is missing: no database code is a patch.");
      return Marks();
    }

    Error error;
    Marks marks = Marks::Parse(text.value(), &error);
    if (error.IsValid())
      ERROR_LOG("{}", error.GetDescription());
    INFO_LOG("{} patch marks.", marks.Count());
    return marks;
  }();
  return s_marks;
}

Needs NeedsFromOverrides(const CheatCode& code)
{
  Needs needs;
  for (const auto& [key, value] : code.overrides)
  {
    if (StringUtil::EqualNoCase(key, "OverrideAspectRatio"))
    {
      if (!ParseRatio(value, &needs.aspect_numerator, &needs.aspect_denominator))
        needs.aspect_numerator = needs.aspect_denominator = 0;
    }
    else if (StringUtil::EqualNoCase(key, "OverrideCPUOverclock"))
    {
      const std::optional<u32> percent = ParseUnsigned(StringUtil::StripWhitespace(value));
      needs.overclock_percent = (percent.has_value() && percent.value() >= 10 && percent.value() <= 1000) ?
                                  percent.value() :
                                  0;
    }
    else if (StringUtil::EqualNoCase(key, "DisableWidescreenRendering"))
    {
      needs.disable_widescreen_rendering = StringUtil::EqualNoCase(StringUtil::StripWhitespace(value), "true");
    }
  }
  return needs;
}

std::optional<Mark> Classify(std::string_view serial, CheatCode& code, const Marks& marks)
{
  if (const Mark* mark = marks.Find(serial, code.group, code.description))
  {
    code.patch = true;
    return *mark;
  }

  if (!code.patch)
    return std::nullopt;

  Mark mark;
  mark.needs = NeedsFromOverrides(code);
  if (mark.needs.aspect_numerator != 0)
  {
    // A widescreen patch: the emulator's own widescreen rendering would widen the picture twice.
    mark.kind = Kind::Widescreen;
    mark.needs.disable_widescreen_rendering = true;
  }
  else if (mark.needs.overclock_percent != 0)
    mark.kind = Kind::FrameRate;
  return mark;
}

PatchList PatchList::Build(std::vector<std::pair<CheatCode, Mark>> codes)
{
  PatchList list;
  std::unordered_map<std::string, size_t> by_choice; // choice title -> patch index
  for (auto& [code, mark] : codes)
  {
    size_t index;
    if (!mark.choice.empty())
    {
      if (const auto it = by_choice.find(mark.choice); it != by_choice.end())
      {
        index = it->second;
      }
      else
      {
        index = list.m_patches.size();
        by_choice.emplace(mark.choice, index);
        Patch& patch = list.m_patches.emplace_back();
        patch.kind = mark.kind;
        patch.title = mark.choice;
        // A Select group's title is the group: it isn't shown twice.
        if (code.group != UNGROUPED && code.group != mark.choice)
          patch.group = code.group;
        list.m_defaults.emplace_back();
      }
    }
    else
    {
      index = list.m_patches.size();
      Patch& patch = list.m_patches.emplace_back();
      patch.kind = mark.kind;
      patch.title = code.description;
      if (code.group != UNGROUPED)
        patch.group = code.group;
      list.m_defaults.emplace_back();
    }

    Patch& patch = list.m_patches[index];
    Defaults& defaults = list.m_defaults[index];
    const s32 choice = static_cast<s32>(patch.choices.size());
    if (code.enabled && defaults.choice < 0 && code.CanEnable() && !code.IsManuallyActivated())
      defaults.choice = choice;
    code.enabled = false;
    defaults.values.push_back(code.option_value);
    patch.choices.push_back(Choice{std::move(code), mark.needs});
  }

  for (size_t i = 0; i < list.m_patches.size(); i++)
    list.m_patches[i].saved = list.m_patches[i].running = list.m_defaults[i].choice;
  return list;
}

// A switch's value: an option's name, else a number.
static std::optional<u32> ValueFromText(const CheatCode& code, std::string_view text)
{
  for (const CheatCode::Option& option : code.options)
  {
    if (option.name == text)
      return option.value;
  }
  return ParseUnsigned(text);
}

static std::string ValueToText(const CheatCode& code)
{
  for (const CheatCode::Option& option : code.options)
  {
    if (option.value == code.option_value)
      return option.name;
  }
  return fmt::format("{}", code.option_value);
}

void PatchList::Restore(std::span<const Switch> switches)
{
  std::vector<bool> switched_on(m_patches.size(), false);
  for (size_t i = 0; i < m_patches.size(); i++)
  {
    Patch& patch = m_patches[i];
    patch.saved = m_defaults[i].choice;
    for (size_t c = 0; c < patch.choices.size(); c++)
      patch.choices[c].code.SetOptionValue(m_defaults[i].values[c], nullptr);
  }

  for (const Switch& sw : switches)
  {
    for (size_t i = 0; i < m_patches.size(); i++)
    {
      Patch& patch = m_patches[i];
      for (size_t c = 0; c < patch.choices.size(); c++)
      {
        CheatCode& code = patch.choices[c].code;
        if (CodeKey(code.group, code.description) != sw.code)
          continue;

        const s32 choice = static_cast<s32>(c);
        if (sw.on && !switched_on[i] && CanChoose(i, choice))
        {
          patch.saved = choice;
          switched_on[i] = true;
        }
        else if (!sw.on && patch.saved == choice)
        {
          patch.saved = -1;
        }

        if (!sw.value.empty() && code.HasOptions())
        {
          if (const std::optional<u32> value = ValueFromText(code, sw.value))
            code.SetOptionValue(value.value(), nullptr);
        }
      }
    }
  }

  for (Patch& patch : m_patches)
    patch.running = patch.saved;
}

std::vector<Switch> PatchList::Switches() const
{
  std::vector<Switch> switches;
  for (size_t i = 0; i < m_patches.size(); i++)
  {
    const Patch& patch = m_patches[i];
    for (size_t c = 0; c < patch.choices.size(); c++)
    {
      const CheatCode& code = patch.choices[c].code;
      const bool on = (patch.saved == static_cast<s32>(c));
      const bool default_on = (m_defaults[i].choice == static_cast<s32>(c));
      const bool value_changed = code.HasOptions() && code.option_value != m_defaults[i].values[c];
      if (on != default_on || value_changed)
        switches.push_back({CodeKey(code.group, code.description), on, value_changed ? ValueToText(code) : ""});
    }
  }
  return switches;
}

bool PatchList::NeedsRestart(size_t index, s32 choice) const
{
  const Patch& patch = m_patches[index];
  return (patch.running >= 0 && patch.running != choice &&
          !patch.choices[static_cast<size_t>(patch.running)].code.RestoresOnDisable());
}

bool PatchList::CanChoose(size_t index, s32 choice) const
{
  const Patch& patch = m_patches[index];
  if (choice < 0)
    return true;
  if (static_cast<size_t>(choice) >= patch.choices.size())
    return false;
  const CheatCode& code = patch.choices[static_cast<size_t>(choice)].code;
  return code.CanEnable() && !code.IsManuallyActivated();
}

void PatchList::KeepRunningFrom(const PatchList& old)
{
  for (Patch& patch : m_patches)
  {
    for (const Patch& was : old.m_patches)
    {
      if (was.title != patch.title || was.running < 0)
        continue;
      const CheatCode& running = was.choices[static_cast<size_t>(was.running)].code;
      for (size_t c = 0; c < patch.choices.size(); c++)
      {
        if (CodeKey(patch.choices[c].code.group, patch.choices[c].code.description) ==
            CodeKey(running.group, running.description))
        {
          patch.running = static_cast<s32>(c);
        }
      }
    }
  }
}

const CheatCode* PatchList::Set(size_t index, s32 choice)
{
  Patch& patch = m_patches[index];
  if (!CanChoose(index, choice))
    return nullptr;
  patch.saved = choice;
  if (NeedsRestart(index, choice))
    return nullptr;

  const s32 old = patch.running;
  patch.running = choice;
  return (old >= 0 && old != choice) ? &patch.choices[static_cast<size_t>(old)].code : nullptr;
}

std::vector<const CheatCode*> PatchList::Follow(std::span<const Switch> switches)
{
  PatchList wanted = *this;
  wanted.Restore(switches);

  std::vector<const CheatCode*> undo;
  for (size_t i = 0; i < m_patches.size(); i++)
  {
    Patch& patch = m_patches[i];
    for (size_t c = 0; c < patch.choices.size(); c++)
    {
      const CheatCode& want = wanted.m_patches[i].choices[c].code;
      if (want.HasOptions() && want.option_value != patch.choices[c].code.option_value)
        patch.choices[c].code.SetOptionValue(want.option_value, nullptr);
    }

    if (wanted.m_patches[i].saved != patch.saved)
    {
      if (const CheatCode* code = Set(i, wanted.m_patches[i].saved))
        undo.push_back(code);
    }
  }
  return undo;
}

bool PatchList::SetValue(size_t index, s32 choice, u32 value, Error* error)
{
  Patch& patch = m_patches[index];
  if (choice < 0 || static_cast<size_t>(choice) >= patch.choices.size())
  {
    Error::SetStringView(error, "There's no such choice.");
    return false;
  }
  return patch.choices[static_cast<size_t>(choice)].code.SetOptionValue(value, error);
}

void PatchList::Restart()
{
  for (Patch& patch : m_patches)
    patch.running = patch.saved;
}

Needs PatchList::RunningNeeds() const
{
  Needs needs;
  for (size_t i = 0; i < m_patches.size(); i++)
  {
    const Patch& patch = m_patches[i];
    if (patch.running < 0 || !CanChoose(i, patch.running))
      continue;

    const Needs& each = patch.choices[static_cast<size_t>(patch.running)].needs;
    if (needs.aspect_numerator == 0 && each.aspect_numerator != 0)
    {
      needs.aspect_numerator = each.aspect_numerator;
      needs.aspect_denominator = each.aspect_denominator;
    }
    needs.disable_widescreen_rendering |= each.disable_widescreen_rendering;
    needs.overclock_percent = std::max(needs.overclock_percent, each.overclock_percent);
    needs.may_need_overclock |= each.may_need_overclock;
  }
  return needs;
}

void PatchList::ApplyFrame() const
{
  for (const Patch& patch : m_patches)
  {
    if (patch.running < 0)
      continue;

    const CheatCode& code = patch.choices[static_cast<size_t>(patch.running)].code;
    if (code.CanEnable() && !code.IsManuallyActivated())
      code.Apply();
  }
}

void PatchList::ApplyOverrides(Settings& settings)
{
  const Needs needs = RunningNeeds();

  if (needs.aspect_numerator == 0)
  {
    m_found.aspect.reset();
    m_found.aspect_player = false;
  }
  else
  {
    const auto current = std::make_tuple(settings.display_aspect_ratio, settings.display_aspect_ratio_custom_numerator,
                                         settings.display_aspect_ratio_custom_denominator);
    if (!m_found.aspect.has_value())
      m_found.aspect = current;
    else if (m_found.aspect.value() != current)
      m_found.aspect_player = true;

    if (!m_found.aspect_player)
    {
      if (needs.aspect_numerator == 16 && needs.aspect_denominator == 9)
      {
        settings.display_aspect_ratio = DisplayAspectRatio::R16_9;
      }
      else
      {
        settings.display_aspect_ratio = DisplayAspectRatio::Custom;
        settings.display_aspect_ratio_custom_numerator = needs.aspect_numerator;
        settings.display_aspect_ratio_custom_denominator = needs.aspect_denominator;
      }
    }
  }

  if (!needs.disable_widescreen_rendering)
  {
    m_found.widescreen.reset();
    m_found.widescreen_player = false;
  }
  else
  {
    const bool current = settings.gpu_widescreen_hack;
    if (!m_found.widescreen.has_value())
      m_found.widescreen = current;
    else if (m_found.widescreen.value() != current)
      m_found.widescreen_player = true;

    if (!m_found.widescreen_player)
      settings.gpu_widescreen_hack = false;
  }

  if (needs.overclock_percent == 0)
  {
    m_found.overclock.reset();
    m_found.overclock_player = false;
  }
  else
  {
    const auto current = std::make_tuple(static_cast<bool>(settings.cpu_overclock_enable),
                                         settings.cpu_overclock_numerator, settings.cpu_overclock_denominator);
    if (!m_found.overclock.has_value())
      m_found.overclock = current;
    else if (m_found.overclock.value() != current)
      m_found.overclock_player = true;

    if (!m_found.overclock_player)
    {
      const u32 players = settings.cpu_overclock_enable ? settings.GetCPUOverclockPercent() : 100;
      if (needs.overclock_percent > players)
      {
        settings.cpu_overclock_enable = true;
        settings.SetCPUOverclockPercent(needs.overclock_percent);
      }
      settings.UpdateOverclockActive();
    }
  }
}

std::string CheatFileForTitle(std::string_view title)
{
  return title.empty() ? std::string() : Path::Combine(EmuFolders::Cheats, fmt::format("{}.cht", title));
}

std::unique_ptr<PatchList> LoadForGame(std::string_view serial, std::string_view title)
{
  if (serial.empty())
    return nullptr;

  std::vector<std::pair<CheatCode, Mark>> codes;
  std::vector<std::string> keys;
  const auto add = [serial, &codes, &keys](const CheatList& list) {
    for (u32 i = 0; i < list.GetCodeCount(); i++)
    {
      CheatCode code = list.GetCode(i);
      const std::optional<Mark> mark = Classify(serial, code, BundledMarks());
      std::string key = CodeKey(code.group, code.description);
      if (!mark.has_value() || std::find(keys.begin(), keys.end(), key) != keys.end())
        continue;
      keys.push_back(std::move(key));
      codes.emplace_back(std::move(code), mark.value());
    }
  };

  if (const std::string filename = CheatFileForTitle(title); !filename.empty() && FileSystem::FileExists(filename.c_str()))
  {
    CheatList player;
    if (player.LoadFromFile(filename.c_str(), CheatList::Format::Autodetect))
      add(player);
  }
  if (CheatList database; database.LoadFromPackage(std::string(serial)))
    add(database);

  if (codes.empty())
    return nullptr;

  auto list = std::make_unique<PatchList>(PatchList::Build(std::move(codes)));
  list->Restore(ReadSwitchesForSerial(serial));
  return list;
}

bool Allowed(const Settings& settings, bool hardcore)
{
  return !hardcore && !settings.disable_all_enhancements;
}

static constexpr const char* SECTION = "Patches";

// A key as an ini value: one the ini would trim, or that starts with a quote, goes in quotes.
static std::string QuoteKey(std::string_view key)
{
  const bool quote = !key.empty() && (std::isspace(static_cast<unsigned char>(key.front())) ||
                                      std::isspace(static_cast<unsigned char>(key.back())) || key.front() == '"');
  return quote ? fmt::format("\"{}\"", key) : std::string(key);
}

static std::string UnquoteKey(std::string_view value)
{
  if (value.size() >= 2 && value.front() == '"' && value.back() == '"')
    value = value.substr(1, value.size() - 2);
  return std::string(value);
}

std::vector<Switch> ReadSwitches(const SettingsInterface& si)
{
  std::vector<Switch> switches;
  for (const char* list : {"On", "Off"})
  {
    for (const std::string& key : si.GetStringList(SECTION, list))
      switches.push_back({UnquoteKey(key), list[1] == 'n', std::string()});
  }

  for (const std::string& entry : si.GetStringList(SECTION, "Value"))
  {
    const size_t tab = entry.rfind('\t');
    if (tab == std::string::npos)
      continue;
    const std::string key = UnquoteKey(std::string_view(entry).substr(0, tab));
    for (Switch& sw : switches)
    {
      if (sw.code == key)
        sw.value = entry.substr(tab + 1);
    }
  }
  return switches;
}

void WriteSwitches(SettingsInterface& si, std::span<const Switch> switches)
{
  std::vector<std::string> on, off, values;
  for (const Switch& sw : switches)
  {
    (sw.on ? on : off).push_back(QuoteKey(sw.code));
    if (!sw.value.empty())
      values.push_back(fmt::format("{}\t{}", QuoteKey(sw.code), sw.value));
  }

  si.ClearSection(SECTION);
  if (on.empty() && off.empty())
  {
    si.RemoveSection(SECTION);
    return;
  }
  if (!on.empty())
    si.SetStringList(SECTION, "On", on);
  if (!off.empty())
    si.SetStringList(SECTION, "Off", off);
  if (!values.empty())
    si.SetStringList(SECTION, "Value", values);
}

std::string DescribeNeeds(const Needs& needs)
{
  std::string text;
  const auto add = [&text](std::string part) {
    if (!text.empty())
      text += ", ";
    text += part;
  };
  if (needs.aspect_numerator != 0)
    add(fmt::format("aspect {}:{}", needs.aspect_numerator, needs.aspect_denominator));
  if (needs.disable_widescreen_rendering)
    add("widescreen rendering off");
  if (needs.overclock_percent != 0)
    add(fmt::format("overclock {}%", needs.overclock_percent));
  if (needs.may_need_overclock)
    add("may need an overclock");
  return text.empty() ? std::string("none") : text;
}

std::vector<Switch> ReadSwitchesForSerial(std::string_view serial)
{
  const std::string path = System::GetGameSettingsPath(serial);
  std::unique_lock lock(System::GetGameSettingsFileMutex());
  if (!FileSystem::FileExists(path.c_str()))
    return {};

  INISettingsInterface ini(path);
  if (!ini.Load())
  {
    WARNING_LOG("Couldn't read the patch switches in '{}'.", Path::GetFileName(path));
    return {};
  }
  return ReadSwitches(ini);
}

bool SaveSwitchesForSerial(std::string_view serial, std::span<const Switch> switches, Error* error)
{
  const std::string path = System::GetGameSettingsPath(serial);
  std::unique_lock lock(System::GetGameSettingsFileMutex());

  const bool exists = FileSystem::FileExists(path.c_str());
  if (!exists && switches.empty())
    return true;

  if (!exists && !FileSystem::EnsureDirectoryExists(EmuFolders::GameSettings.c_str(), true, error))
    return false;

  INISettingsInterface ini(path);
  if (exists && !ini.Load(error))
    return false;

  WriteSwitches(ini, switches);
  ini.RemoveEmptySections();
  if (ini.IsEmpty())
  {
    // Nothing left to write: as it goes, the interface mustn't write the file again.
    ini.MarkClean();
    return FileSystem::DeleteFile(path.c_str(), error);
  }

  return ini.Save(error);
}

} // namespace Patches
