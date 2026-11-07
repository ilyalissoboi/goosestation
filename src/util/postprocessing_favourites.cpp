// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "postprocessing_favourites.h"
#include "host.h"
#include "ini_settings_interface.h"
#include "postprocessing.h"

#include "common/error.h"
#include "common/memory_settings_interface.h"
#include "common/file_system.h"
#include "common/path.h"
#include "common/string_util.h"

#include "fmt/format.h"

#include <algorithm>
#include <memory>

namespace PostProcessing::Favourites {

static constexpr const char* DISPLAY = Config::DISPLAY_CHAIN_SECTION;

static std::string FilePath(const std::string& folder, std::string_view name)
{
  return Path::Combine(folder, fmt::format("{}.ini", name));
}

static bool LessNoCase(const std::string& lhs, const std::string& rhs)
{
  return StringUtil::Strcasecmp(lhs.c_str(), rhs.c_str()) < 0;
}

// Every favourite's name, in order: those order lists whose files exist (as the files spell them), then the rest.
static std::vector<std::string> Names(const std::string& folder, const SettingsInterface& order)
{
  std::vector<std::string> files;
  FileSystem::FindResultsArray results;
  FileSystem::FindFiles(folder.c_str(), "*.ini", FILESYSTEM_FIND_FILES, &results);
  for (const FILESYSTEM_FIND_DATA& fd : results)
    files.emplace_back(Path::GetFileTitle(fd.FileName));

  std::vector<std::string> names;
  for (const std::string& listed : order.GetStringList(ORDER_SECTION, ORDER_KEY))
  {
    const auto it = std::find_if(files.begin(), files.end(),
                                 [&listed](const std::string& file) { return StringUtil::EqualNoCase(file, listed); });
    if (it == files.end())
      continue;
    names.push_back(std::move(*it));
    files.erase(it);
  }

  std::sort(files.begin(), files.end(), LessNoCase);
  names.insert(names.end(), std::make_move_iterator(files.begin()), std::make_move_iterator(files.end()));
  return names;
}

// The listed name spelled exactly as name, else one equal to it ignoring case: where a folder tells case apart, two
// files can differ only in case, and each is its own.
static std::vector<std::string>::iterator FindName(std::vector<std::string>& names, std::string_view name)
{
  const auto exact = std::find(names.begin(), names.end(), name);
  if (exact != names.end())
    return exact;
  return std::find_if(names.begin(), names.end(),
                      [name](const std::string& listed) { return StringUtil::EqualNoCase(listed, name); });
}

static void WriteOrder(SettingsInterface& order, const std::vector<std::string>& names)
{
  order.SetStringList(ORDER_SECTION, ORDER_KEY, names);
}

static void SetMissing(Error* error, std::string_view name)
{
  Error::SetString(error, fmt::format(TRANSLATE_FS("PostProcessing", "There's no favourite named {}."), name));
}

static void SetExists(Error* error, std::string_view name)
{
  Error::SetString(error, fmt::format(TRANSLATE_FS("PostProcessing", "A favourite named {} already exists."), name));
}

// The favourite's file, read; null (error set) when there's no such favourite or it can't be read.
static std::unique_ptr<INISettingsInterface> LoadFavourite(const std::string& folder, std::string_view name,
                                                           Error* error)
{
  const MemorySettingsInterface no_order;
  std::vector<std::string> names = Names(folder, no_order);
  const auto it = FindName(names, StringUtil::StripWhitespace(name));
  if (it == names.end())
  {
    SetMissing(error, name);
    return {};
  }

  auto file = std::make_unique<INISettingsInterface>(FilePath(folder, *it));
  if (!file->Load(error))
    return {};
  return file;
}

// A display chain's stages, each its sorted keys and values.
static std::vector<std::vector<std::pair<std::string, std::string>>> Stages(const SettingsInterface& si)
{
  std::vector<std::vector<std::pair<std::string, std::string>>> stages;
  const u32 count = Config::GetStageCount(si, DISPLAY);
  for (u32 i = 0; i < count; i++)
  {
    std::vector<std::pair<std::string, std::string>> kvs = si.GetKeyValueList(Config::GetStageSection(DISPLAY, i).c_str());
    std::sort(kvs.begin(), kvs.end());
    stages.push_back(std::move(kvs));
  }
  return stages;
}

} // namespace PostProcessing::Favourites

bool PostProcessing::Favourites::CheckName(std::string_view name, std::string* trimmed, Error* error)
{
  const std::string_view stripped = StringUtil::StripWhitespace(name);
  if (stripped.empty())
  {
    Error::SetString(error, TRANSLATE_STR("PostProcessing", "A favourite needs a name."));
    return false;
  }

  constexpr std::string_view refused = "/\\:*?\"<>|";
  const bool bad_char = std::any_of(stripped.begin(), stripped.end(), [&refused](char ch) {
    const unsigned char uch = static_cast<unsigned char>(ch);
    return uch < 0x20 || uch == 0x7f || refused.find(ch) != std::string_view::npos;
  });
  if (stripped.front() == '.' || bad_char)
  {
    Error::SetString(error, TRANSLATE_STR("PostProcessing",
                                          "A favourite's name can't start with a dot or have / \\ : * ? \" < > | in it."));
    return false;
  }

  if (trimmed)
    trimmed->assign(stripped);
  return true;
}

std::vector<PostProcessing::Favourites::Info> PostProcessing::Favourites::List(const std::string& folder,
                                                                               const SettingsInterface& order)
{
  std::vector<Info> list;
  for (std::string& name : Names(folder, order))
  {
    Info info;
    INISettingsInterface file(FilePath(folder, name));
    file.Load();
    const u32 count = Config::GetStageCount(file, DISPLAY);
    for (u32 i = 0; i < count; i++)
    {
      std::string shader = Config::GetStageShaderName(file, DISPLAY, i);
      if (shader.empty())
        continue;
      if (!IsShaderInstalled(shader))
        info.missing.push_back(shader);
      info.shaders.push_back(std::move(shader));
    }
    info.name = std::move(name);
    list.push_back(std::move(info));
  }
  return list;
}

std::optional<std::string> PostProcessing::Favourites::Find(const std::string& folder, const SettingsInterface& order,
                                                            std::string_view name)
{
  std::vector<std::string> names = Names(folder, order);
  const auto it = FindName(names, StringUtil::StripWhitespace(name));
  if (it == names.end())
    return std::nullopt;
  return std::move(*it);
}

bool PostProcessing::Favourites::Save(const std::string& folder, SettingsInterface& order, std::string_view name,
                                      const SettingsInterface& chain, bool replace, Error* error)
{
  std::string trimmed;
  if (!CheckName(name, &trimmed, error))
    return false;

  std::vector<std::string> names = Names(folder, order);
  const auto existing = FindName(names, trimmed);
  if (existing != names.end())
  {
    if (!replace)
    {
      SetExists(error, trimmed);
      return false;
    }
    trimmed = *existing;
  }

  if (!FileSystem::EnsureDirectoryExists(folder.c_str(), true, error))
    return false;

  // A fresh file, never loaded: replacing a favourite leaves nothing of the old one.
  INISettingsInterface file(FilePath(folder, trimmed));
  Config::CopyChain(chain, file, DISPLAY);
  file.SetBoolValue(DISPLAY, "Enabled", true);
  Error save_error;
  if (!file.Save(&save_error))
  {
    Error::SetString(error, fmt::format(TRANSLATE_FS("PostProcessing", "Couldn't write {}: {}"), file.GetFileName(),
                                        save_error.GetDescription()));
    return false;
  }

  if (existing == names.end())
    names.push_back(std::move(trimmed));
  WriteOrder(order, names);
  return true;
}

bool PostProcessing::Favourites::Rename(const std::string& folder, SettingsInterface& order, std::string_view from,
                                        std::string_view to, Error* error)
{
  std::vector<std::string> names = Names(folder, order);
  const auto old = FindName(names, StringUtil::StripWhitespace(from));
  if (old == names.end())
  {
    SetMissing(error, from);
    return false;
  }

  std::string trimmed;
  if (!CheckName(to, &trimmed, error))
    return false;
  if (*old == trimmed)
    return true;

  const bool case_only = StringUtil::EqualNoCase(*old, trimmed);
  const auto other = FindName(names, trimmed);
  if (other != names.end() && other != old)
  {
    SetExists(error, trimmed);
    return false;
  }

  const std::string old_path = FilePath(folder, *old);
  const std::string new_path = FilePath(folder, trimmed);
  Error rename_error;
  bool renamed;
  if (case_only)
  {
    // A filesystem that ignores case sees both names as one file: through a name of its own.
    const std::string temp_path = Path::Combine(folder, fmt::format(".{}.ini.renaming", trimmed));
    renamed = FileSystem::RenamePath(old_path.c_str(), temp_path.c_str(), &rename_error);
    if (renamed && !FileSystem::RenamePath(temp_path.c_str(), new_path.c_str(), &rename_error))
    {
      // Back where it was, rather than hidden under the temporary name.
      FileSystem::RenamePath(temp_path.c_str(), old_path.c_str());
      renamed = false;
    }
  }
  else
  {
    renamed = FileSystem::RenamePath(old_path.c_str(), new_path.c_str(), &rename_error);
  }
  if (!renamed)
  {
    Error::SetString(error, fmt::format(TRANSLATE_FS("PostProcessing", "Couldn't rename {}: {}"), *old,
                                        rename_error.GetDescription()));
    return false;
  }

  *old = std::move(trimmed);
  WriteOrder(order, names);
  return true;
}

void PostProcessing::Favourites::Move(const std::string& folder, SettingsInterface& order, std::string_view name,
                                      bool down)
{
  std::vector<std::string> names = Names(folder, order);
  const auto it = FindName(names, name);
  if (it == names.end())
    return;

  const auto other = down ? std::next(it) : (it == names.begin() ? names.end() : std::prev(it));
  if (other == names.end())
    return;

  std::iter_swap(it, other);
  WriteOrder(order, names);
}

bool PostProcessing::Favourites::Delete(const std::string& folder, SettingsInterface& order, std::string_view name,
                                        Error* error)
{
  std::vector<std::string> names = Names(folder, order);
  const auto it = FindName(names, StringUtil::StripWhitespace(name));
  if (it == names.end())
  {
    SetMissing(error, name);
    return false;
  }

  Error delete_error;
  if (!FileSystem::DeleteFile(FilePath(folder, *it).c_str(), &delete_error))
  {
    Error::SetString(error, fmt::format(TRANSLATE_FS("PostProcessing", "Couldn't delete {}: {}"), *it,
                                        delete_error.GetDescription()));
    return false;
  }

  names.erase(it);
  WriteOrder(order, names);
  return true;
}

bool PostProcessing::Favourites::Apply(const std::string& folder, std::string_view name, SettingsInterface& to,
                                       Error* error)
{
  const std::unique_ptr<INISettingsInterface> file = LoadFavourite(folder, name, error);
  if (!file)
    return false;

  Config::CopyChain(*file, to, DISPLAY);
  to.SetBoolValue(DISPLAY, "Enabled", true);
  return true;
}

bool PostProcessing::Favourites::ChainsEqual(const SettingsInterface& a, const SettingsInterface& b)
{
  return Stages(a) == Stages(b);
}

std::optional<std::string> PostProcessing::Favourites::Step(const std::string& folder, const SettingsInterface& order,
                                                            const SettingsInterface& chain, std::string_view last,
                                                            bool forward)
{
  const std::vector<std::string> names = Names(folder, order);
  if (names.empty())
    return std::nullopt;

  const auto equals = [&folder, &chain](const std::string& name) {
    const std::unique_ptr<INISettingsInterface> file = LoadFavourite(folder, name, nullptr);
    return file && ChainsEqual(*file, chain);
  };

  const size_t count = names.size();
  std::optional<size_t> current;
  for (size_t i = 0; i < count && !last.empty(); i++)
  {
    if (StringUtil::EqualNoCase(names[i], last))
    {
      if (equals(names[i]))
        current = i;
      break;
    }
  }
  for (size_t i = 0; i < count && !current.has_value(); i++)
  {
    if (equals(names[i]))
      current = i;
  }

  if (!current.has_value())
    return forward ? names.front() : names.back();
  return names[forward ? ((*current + 1) % count) : ((*current + count - 1) % count)];
}

std::optional<std::string> PostProcessing::Favourites::StepChain(const std::string& folder, SettingsInterface& base,
                                                                 const SettingsInterface* game_layer,
                                                                 const std::string& game_file, std::string* last,
                                                                 bool forward, Error* error)
{
  const bool game = game_layer && !game_file.empty() && Config::HasOwnChain(*game_layer, DISPLAY);
  std::optional<std::string> next = Step(folder, base, game ? *game_layer : base, *last, forward);
  if (!next.has_value())
    return std::nullopt;

  if (game)
  {
    // The file as it is, not the layer, which a host may have adapted in memory.
    INISettingsInterface file(game_file);
    if (FileSystem::FileExists(game_file.c_str()) && !file.Load(error))
      return std::nullopt;
    if (!Apply(folder, *next, file, error))
      return std::nullopt;
    Error save_error;
    if (!file.Save(&save_error))
    {
      Error::SetString(error, fmt::format(TRANSLATE_FS("PostProcessing", "Couldn't write {}: {}"), game_file,
                                          save_error.GetDescription()));
      return std::nullopt;
    }
  }
  else if (!Apply(folder, *next, base, error))
  {
    return std::nullopt;
  }

  *last = *next;
  return next;
}

std::string PostProcessing::Favourites::Summary(const Info& info)
{
  if (!info.missing.empty())
    return fmt::format(TRANSLATE_FS("PostProcessing", "Not installed: {}"), fmt::join(info.missing, ", "));
  return fmt::format("{}", fmt::join(info.shaders, " · "));
}
