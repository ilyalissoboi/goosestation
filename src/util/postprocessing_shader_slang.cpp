// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "postprocessing_shader_slang.h"

#include "common/file_system.h"
#include "common/path.h"
#include "common/string_util.h"

#include <algorithm>

namespace PostProcessing::Slang {

static constexpr std::string_view PRESET_EXTENSION = ".slangp";

// Splits on '/' and '\'.
static std::vector<std::string_view> SplitComponents(std::string_view path)
{
  std::vector<std::string_view> ret;
  size_t start = 0;
  while (start <= path.size())
  {
    size_t end = path.find_first_of("/\\", start);
    if (end == std::string_view::npos)
      end = path.size();
    ret.push_back(path.substr(start, end - start));
    start = end + 1;
  }
  return ret;
}

} // namespace PostProcessing::Slang

bool PostProcessing::Slang::IsPresetName(std::string_view name)
{
  return name.size() > PRESET_EXTENSION.size() &&
         StringUtil::EqualNoCase(name.substr(name.size() - PRESET_EXTENSION.size()), PRESET_EXTENSION);
}

std::string PostProcessing::Slang::ResolvePresetPath(std::string_view shaders_root, std::string_view name)
{
  if (name.empty() || name[0] == '/' || name[0] == '\\' || (name.size() >= 2 && name[1] == ':'))
    return {};

  std::string relative;
  for (const std::string_view component : SplitComponents(name))
  {
    if (component == "..")
      return {};
    if (!relative.empty())
      relative.push_back('/');
    relative.append(component);
  }

  return Path::Combine(shaders_root, relative);
}

std::vector<std::string> PostProcessing::Slang::FindPresets(std::string_view shaders_root)
{
  std::vector<std::string> ret;

  FileSystem::FindResultsArray results;
  FileSystem::FindFiles(std::string(shaders_root).c_str(), "*.slangp",
                        FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_RECURSIVE | FILESYSTEM_FIND_RELATIVE_PATHS, &results);

  for (FILESYSTEM_FIND_DATA& fd : results)
  {
    const std::vector<std::string_view> components = SplitComponents(fd.FileName);
    if (std::any_of(components.begin(), components.end(), [](std::string_view c) {
          return c.empty() || c[0] == '.' || c == "__MACOSX";
        }))
    {
      continue;
    }

    std::string name = std::move(fd.FileName);
#ifdef _WIN32
    // Stage names use '/' on every platform, so a chain from Windows works elsewhere.
    StringUtil::ReplaceAll(&name, '\\', '/');
#endif
    ret.push_back(std::move(name));
  }

  std::sort(ret.begin(), ret.end());
  return ret;
}
