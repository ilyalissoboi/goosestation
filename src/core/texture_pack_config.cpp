// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "texture_pack_config.h"

#include "common/error.h"
#include "common/log.h"
#include "common/settings_interface.h"
#include "common/string_util.h"

#include "fmt/format.h"

#include <algorithm>
#include <array>
#include <charconv>

Log_SetChannel(TexturePackConfig);

namespace {

struct BoolKey
{
  const char* name;
  bool TexturePackConfig::*member;
  const char* comment;
};

struct UIntKey
{
  const char* name;
  u32 TexturePackConfig::*member;
  const char* comment;
};

// Comments are ours, describing what GooseStation does with each key.
constexpr std::array<BoolKey, 6> BOOL_KEYS = {{
  {"DumpTexturePages", &TexturePackConfig::dump_texture_pages,
   "Name textures by the page and rect they're drawn from, instead of by the upload they came from."},
  {"DumpFullTexturePages", &TexturePackConfig::dump_full_texture_pages,
   "In page mode, dump and replace whole 256x256 pages instead of the rects drawn."},
  {"DumpC16Textures", &TexturePackConfig::dump_c16_textures, "Dump direct-colour (16-bit) textures too."},
  {"ReducePaletteRange", &TexturePackConfig::reduce_palette_range,
   "Name and hash only the part of a palette a texture uses."},
  {"ConvertCopiesToWrites", &TexturePackConfig::convert_copies_to_writes,
   "Track VRAM copies out of a tracked upload as uploads of their own."},
  {"ReplacementScaleLinearFilter", &TexturePackConfig::replacement_scale_linear_filter,
   "Filter replacements linearly when they're scaled to fit their page."},
}};

constexpr std::array<UIntKey, 10> UINT_KEYS = {{
  {"MaxVRAMWriteSplits", &TexturePackConfig::max_vram_write_splits,
   "How many times a partly overwritten upload keeps its remaining parts as uploads."},
  {"MaxVRAMWriteCoalesceWidth", &TexturePackConfig::max_vram_write_coalesce_width,
   "The widest upload that is joined to the one directly above it."},
  {"MaxVRAMWriteCoalesceHeight", &TexturePackConfig::max_vram_write_coalesce_height,
   "The tallest upload that is joined to the one directly above it."},
  {"DumpTextureWidthThreshold", &TexturePackConfig::texture_dump_width_threshold,
   "Textures narrower than this many texels aren't dumped."},
  {"DumpTextureHeightThreshold", &TexturePackConfig::texture_dump_height_threshold,
   "Textures shorter than this many texels aren't dumped."},
  {"DumpVRAMWriteWidthThreshold", &TexturePackConfig::vram_write_dump_width_threshold,
   "VRAM writes narrower than this aren't dumped as vram-write- files."},
  {"DumpVRAMWriteHeightThreshold", &TexturePackConfig::vram_write_dump_height_threshold,
   "VRAM writes shorter than this aren't dumped as vram-write- files."},
  {"MaxHashCacheEntries", &TexturePackConfig::max_hash_cache_entries,
   "How many looked-up textures are remembered."},
  {"MaxHashCacheVRAMUsageMB", &TexturePackConfig::max_hash_cache_vram_usage_mb,
   "Megabytes of GPU memory for pages drawn with replacements."},
  {"MaxReplacementCacheVRAMUsage", &TexturePackConfig::max_replacement_cache_vram_usage_mb,
   "Megabytes of memory for loaded replacement images."},
}};

constexpr const char* ALIASES_KEY = "Aliases";

std::string_view Trim(std::string_view text)
{
  while (!text.empty() && (text.front() == ' ' || text.front() == '\t'))
    text.remove_prefix(1);
  while (!text.empty() && (text.back() == ' ' || text.back() == '\t' || text.back() == '\r'))
    text.remove_suffix(1);
  return text;
}

/// A scalar: quotes removed, or a trailing " # comment" removed when unquoted.
std::string_view Scalar(std::string_view text)
{
  text = Trim(text);
  if (text.length() >= 2 && (text.front() == '"' || text.front() == '\'') && text.back() == text.front())
    return text.substr(1, text.length() - 2);

  const std::string_view::size_type comment = text.find(" #");
  return Trim((comment != std::string_view::npos) ? text.substr(0, comment) : text);
}

std::optional<bool> ParseBool(std::string_view text)
{
  for (const char* yes : {"true", "yes", "on", "1"})
  {
    if (StringUtil::EqualNoCase(text, yes))
      return true;
  }
  for (const char* no : {"false", "no", "off", "0"})
  {
    if (StringUtil::EqualNoCase(text, no))
      return false;
  }
  return std::nullopt;
}

std::optional<u32> ParseUInt(std::string_view text)
{
  u32 value;
  const char* end = text.data() + text.length();
  const std::from_chars_result result = std::from_chars(text.data(), end, value, 10);
  return (!text.empty() && result.ec == std::errc() && result.ptr == end) ? std::optional<u32>(value) : std::nullopt;
}

u32 Indent(std::string_view line)
{
  u32 indent = 0;
  while (indent < line.length() && (line[indent] == ' ' || line[indent] == '\t'))
    indent++;
  return indent;
}

/// "key: value" with the key's quotes removed; false if the line has no key.
bool SplitKeyValue(std::string_view line, std::string_view* key, std::string_view* value)
{
  line = Trim(line);
  std::string_view::size_type colon;
  if (!line.empty() && (line.front() == '"' || line.front() == '\''))
  {
    const std::string_view::size_type close = line.find(line.front(), 1);
    if (close == std::string_view::npos || close + 1 >= line.length() || line[close + 1] != ':')
      return false;
    *key = line.substr(1, close - 1);
    colon = close + 1;
  }
  else
  {
    colon = line.find(": ");
    if (colon == std::string_view::npos && line.ends_with(':'))
      colon = line.length() - 1;
    if (colon == std::string_view::npos)
      return false;
    *key = Trim(line.substr(0, colon));
  }

  *value = (colon + 1 < line.length()) ? line.substr(colon + 1) : std::string_view();
  return !key->empty();
}

} // namespace

bool TexturePackConfig::LoadYAML(std::string_view yaml, Error* error)
{
  // Editors may save UTF-8 with a byte order mark.
  if (yaml.starts_with("\xEF\xBB\xBF"))
    yaml.remove_prefix(3);

  // Split by hand: StringUtil::SplitString strips the indentation this needs.
  std::vector<std::string_view> lines;
  for (std::string_view::size_type start = 0; start < yaml.length();)
  {
    const std::string_view::size_type end = std::min(yaml.find('\n', start), yaml.length());
    lines.push_back(yaml.substr(start, end - start));
    start = end + 1;
  }
  bool in_aliases = false;

  for (size_t i = 0; i < lines.size(); i++)
  {
    const std::string_view line = lines[i];
    const std::string_view trimmed = Trim(line);
    if (trimmed.empty() || trimmed.front() == '#')
      continue;

    const u32 indent = Indent(line);
    std::string_view key, value;
    if (!SplitKeyValue(line, &key, &value))
    {
      WARNING_LOG("Skipping line {} of config.yaml: '{}'", i + 1, trimmed);
      continue;
    }

    if (indent > 0)
    {
      if (!in_aliases)
      {
        WARNING_LOG("Skipping indented line {} of config.yaml: '{}'", i + 1, trimmed);
        continue;
      }

      std::string_view target = Scalar(value);
      if (target == "|" || target == "|-" || target == ">")
      {
        // The value is the next, more indented, line.
        target = {};
        if (i + 1 < lines.size() && Indent(lines[i + 1]) > indent && !Trim(lines[i + 1]).empty())
          target = Scalar(lines[++i]);
      }

      if (target.empty())
      {
        WARNING_LOG("Alias '{}' on line {} of config.yaml has no file.", key, i + 1);
        continue;
      }

      aliases.emplace_back(std::string(key), std::string(target));
      continue;
    }

    in_aliases = false;
    if (key == ALIASES_KEY)
    {
      in_aliases = true;
      continue;
    }

    const std::string_view scalar = Scalar(value);
    bool known = false;
    for (const BoolKey& bk : BOOL_KEYS)
    {
      if (key != bk.name)
        continue;

      const std::optional<bool> parsed = ParseBool(scalar);
      if (!parsed.has_value())
      {
        Error::SetStringFmt(error, "config.yaml: {} wants true or false, not '{}'.", bk.name, scalar);
        return false;
      }

      this->*bk.member = parsed.value();
      known = true;
    }
    for (const UIntKey& uk : UINT_KEYS)
    {
      if (key != uk.name)
        continue;

      const std::optional<u32> parsed = ParseUInt(scalar);
      if (!parsed.has_value())
      {
        Error::SetStringFmt(error, "config.yaml: {} wants a whole number, not '{}'.", uk.name, scalar);
        return false;
      }

      this->*uk.member = parsed.value();
      known = true;
    }

    if (!known)
      WARNING_LOG("Skipping unknown key '{}' on line {} of config.yaml.", key, i + 1);
  }

  return true;
}

static std::string QuoteIfNeeded(std::string_view text)
{
  if (text.find_first_of(":#'\"") == std::string_view::npos && Trim(text) == text)
    return std::string(text);

  std::string quoted = "\"";
  quoted.append(text);
  quoted.push_back('"');
  return quoted;
}

std::string TexturePackConfig::ToYAML() const
{
  std::string yaml;
  for (const BoolKey& bk : BOOL_KEYS)
    yaml += fmt::format("# {}\n{}: {}\n\n", bk.comment, bk.name, (this->*bk.member) ? "true" : "false");
  for (const UIntKey& uk : UINT_KEYS)
    yaml += fmt::format("# {}\n{}: {}\n\n", uk.comment, uk.name, this->*uk.member);

  yaml += "# Texture names (no extension) that use another file, relative to this folder.\n";
  yaml += fmt::format("{}:\n", ALIASES_KEY);
  for (const auto& [name, file] : aliases)
    yaml += fmt::format("  {}: {}\n", QuoteIfNeeded(name), QuoteIfNeeded(file));

  return yaml;
}

std::string TexturePackConfig::GetTemplateYAML()
{
  const TexturePackConfig defaults;
  std::string yaml = "# GooseStation texture replacement settings for this game.\n"
                     "# A key set here overrides the global setting of the same name; commented keys use the\n"
                     "# global setting. See docs/texture-replacement.md.\n\n";
  for (const BoolKey& bk : BOOL_KEYS)
    yaml += fmt::format("# {}\n#{}: {}\n\n", bk.comment, bk.name, (defaults.*bk.member) ? "true" : "false");
  for (const UIntKey& uk : UINT_KEYS)
    yaml += fmt::format("# {}\n#{}: {}\n\n", uk.comment, uk.name, defaults.*uk.member);

  yaml += "# Texture names (no extension) that use another file, relative to this folder, e.g.\n"
          "#   texupload-P4-0123456789ABCDEF-FEDCBA9876543210-64x256-0-0-64x64-P0-15: shared.png\n";
  yaml += fmt::format("#{}:\n", ALIASES_KEY);
  return yaml;
}

void TexturePackConfig::Load(const SettingsInterface& si)
{
  const TexturePackConfig defaults;
  for (const BoolKey& bk : BOOL_KEYS)
    this->*bk.member = si.GetBoolValue("TextureReplacements", bk.name, defaults.*bk.member);
  for (const UIntKey& uk : UINT_KEYS)
    this->*uk.member = si.GetUIntValue("TextureReplacements", uk.name, defaults.*uk.member);
}

void TexturePackConfig::Save(SettingsInterface& si) const
{
  for (const BoolKey& bk : BOOL_KEYS)
    si.SetBoolValue("TextureReplacements", bk.name, this->*bk.member);
  for (const UIntKey& uk : UINT_KEYS)
    si.SetUIntValue("TextureReplacements", uk.name, this->*uk.member);
}
