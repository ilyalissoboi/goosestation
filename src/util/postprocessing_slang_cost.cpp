// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "postprocessing_slang_cost.h"
#include "host.h"
#include "postprocessing_shader_slang.h"

#include "common/file_system.h"
#include "common/path.h"
#include "common/string_util.h"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unordered_set>

namespace PostProcessing::Slang {
namespace {

constexpr float SOURCE_W = 320.0f, SOURCE_H = 240.0f;
constexpr float SCREEN_W = 1920.0f, SCREEN_H = 1080.0f;
constexpr u32 MAX_PASSES = 256;
constexpr u32 MAX_REFERENCE_DEPTH = 16;
constexpr float MAX_SIZE = 16384.0f;

using Keys = std::unordered_map<std::string, std::string>;

// The line up to a comment ('#' or "//") outside quotes.
std::string_view StripComment(std::string_view line)
{
  bool quoted = false;
  for (size_t i = 0; i < line.size(); i++)
  {
    if (line[i] == '"')
      quoted = !quoted;
    else if (!quoted && (line[i] == '#' || (line[i] == '/' && i + 1 < line.size() && line[i + 1] == '/')))
      return line.substr(0, i);
  }
  return line;
}

std::string_view Unquote(std::string_view value)
{
  value = StringUtil::StripWhitespace(value);
  if (value.size() >= 2 && value.front() == '"' && value.back() == '"')
    value = value.substr(1, value.size() - 2);
  return StringUtil::StripWhitespace(value);
}

// A preset file as read: the files it references (resolved) and its own keys, in order.
struct ParsedFile
{
  std::vector<std::string> references;
  std::vector<std::pair<std::string, std::string>> keys;
};

std::mutex s_cache_mutex;
std::unordered_map<std::string, std::optional<Cost>> s_cache;
// Files several presets reference (Mega Bezel's base presets, thousands of lines) are read once.
std::unordered_map<std::string, std::shared_ptr<const ParsedFile>> s_files;

std::shared_ptr<const ParsedFile> ParseFile(const std::string& path)
{
  {
    std::unique_lock lock(s_cache_mutex);
    if (const auto it = s_files.find(path); it != s_files.end())
      return it->second;
  }

  std::shared_ptr<ParsedFile> parsed;
  if (const std::optional<std::string> text = FileSystem::ReadFileToString(path.c_str()); text.has_value())
  {
    parsed = std::make_shared<ParsedFile>();
    const std::string dir(Path::GetDirectory(path));
    for (std::string_view line : StringUtil::SplitString(text.value(), '\n', false))
    {
      line = StringUtil::StripWhitespace(line);
      if (line.starts_with("#reference"))
      {
        const std::string_view target = Unquote(line.substr(10));
        if (!target.empty())
          parsed->references.push_back(Path::ToNativePath(Path::Combine(dir, target)));
        continue;
      }
      line = StripComment(line);
      const size_t eq = line.find('=');
      if (eq == std::string_view::npos)
        continue;
      const std::string_view key = StringUtil::StripWhitespace(line.substr(0, eq));
      if (!key.empty())
        parsed->keys.emplace_back(std::string(key), std::string(Unquote(line.substr(eq + 1))));
    }
  }

  std::unique_lock lock(s_cache_mutex);
  return s_files.emplace(path, std::move(parsed)).first->second;
}

// Reads path's keys into keys: its #reference files first (relative to it), then its own, which win. False when path
// can't be read.
bool ReadKeys(const std::string& path, u32 depth, std::unordered_set<std::string>* seen, Keys* keys)
{
  if (depth > MAX_REFERENCE_DEPTH || !seen->insert(path).second)
    return false;
  const std::shared_ptr<const ParsedFile> file = ParseFile(path);
  if (!file)
    return false;
  for (const std::string& reference : file->references)
    ReadKeys(reference, depth + 1, seen, keys);
  for (const auto& [key, value] : file->keys)
    (*keys)[key] = value;
  return true;
}

const std::string* Find(const Keys& keys, const std::string& key)
{
  const auto it = keys.find(key);
  return (it != keys.end()) ? &it->second : nullptr;
}

// A number as presets write it, whatever the C locale (Qt sets it from the environment on Linux); a leading number is
// enough ("2.0f"), and one that isn't finite is none.
float ParseFloat(const std::string* value, float fallback)
{
  if (!value || value->empty())
    return fallback;
  std::string_view end;
  const std::optional<float> f = StringUtil::FromChars<float>(*value, &end);
  return (f.has_value() && std::isfinite(f.value())) ? f.value() : fallback;
}

// The size a pass draws along one axis.
float PassSize(const std::string* type, float scale, float previous, float screen)
{
  float size;
  if (type && StringUtil::EqualNoCase(*type, "viewport"))
    size = screen * scale;
  else if (type && StringUtil::EqualNoCase(*type, "absolute"))
    size = scale;
  else
    size = previous * scale;
  return std::clamp(size, 1.0f, MAX_SIZE);
}

} // namespace
} // namespace PostProcessing::Slang

PostProcessing::Slang::Cost PostProcessing::Slang::CostOf(u32 passes, float screens)
{
  const float score = screens + 0.1f * static_cast<float>(passes);
  return (score < 5.0f) ? Cost::Light : ((score < 10.0f) ? Cost::Heavy : Cost::VeryHeavy);
}

std::optional<PostProcessing::Slang::CostEstimate> PostProcessing::Slang::EstimateCost(const std::string& path)
{
  Keys keys;
  std::unordered_set<std::string> seen;
  if (!ReadKeys(path, 0, &seen, &keys))
    return std::nullopt;

  u32 passes = 0;
  if (const std::string* count = Find(keys, "shaders"))
  {
    u32 n = 0;
    const char* end = count->data() + count->size();
    if (std::from_chars(count->data(), end, n).ec == std::errc())
      passes = std::min(n, MAX_PASSES);
  }

  float w = SOURCE_W, h = SOURCE_H, pixels = 0.0f;
  for (u32 i = 0; i < passes; i++)
  {
    const std::string n = std::to_string(i);
    const std::string* type = Find(keys, "scale_type" + n);
    const std::string* type_x = Find(keys, "scale_type_x" + n);
    const std::string* type_y = Find(keys, "scale_type_y" + n);
    if (!type_x)
      type_x = type;
    if (!type_y)
      type_y = type;
    const float scale = ParseFloat(Find(keys, "scale" + n), 1.0f);
    const float scale_x = ParseFloat(Find(keys, "scale_x" + n), scale);
    const float scale_y = ParseFloat(Find(keys, "scale_y" + n), scale);

    if (i + 1 == passes && !type_x && !type_y)
    {
      // A last pass without a scale type draws at the screen's size.
      w = SCREEN_W;
      h = SCREEN_H;
    }
    else
    {
      w = PassSize(type_x, scale_x, w, SCREEN_W);
      h = PassSize(type_y, scale_y, h, SCREEN_H);
    }
    pixels += w * h;
  }

  const float screens = pixels / (SCREEN_W * SCREEN_H);
  return CostEstimate{passes, screens, CostOf(passes, screens)};
}

std::optional<PostProcessing::Slang::Cost> PostProcessing::Slang::GetCostForPath(const std::string& path)
{
  {
    std::unique_lock lock(s_cache_mutex);
    if (const auto it = s_cache.find(path); it != s_cache.end())
      return it->second;
  }

  const std::optional<CostEstimate> estimate = EstimateCost(path);
  const std::optional<Cost> cost = estimate.has_value() ? std::optional<Cost>(estimate->cost) : std::nullopt;
  std::unique_lock lock(s_cache_mutex);
  s_cache.emplace(path, cost);
  return cost;
}

std::optional<PostProcessing::Slang::Cost> PostProcessing::Slang::GetCostForName(std::string_view name)
{
  if (!IsPresetName(name))
    return std::nullopt;
  const std::string path = FindPreset(name);
  return path.empty() ? std::nullopt : GetCostForPath(path);
}

void PostProcessing::Slang::ClearCostCache()
{
  std::unique_lock lock(s_cache_mutex);
  s_cache.clear();
  s_files.clear();
}

const char* PostProcessing::Slang::GetCostLabel(Cost cost)
{
  switch (cost)
  {
    case Cost::Light:
      return TRANSLATE("PostProcessing", "light");
    case Cost::Heavy:
      return TRANSLATE("PostProcessing", "heavy");
    case Cost::VeryHeavy:
    default:
      return TRANSLATE("PostProcessing", "very heavy");
  }
}
