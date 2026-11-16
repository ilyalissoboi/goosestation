// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "texture_names.h"

#include "common/string_util.h"

#include "fmt/format.h"

#include <algorithm>
#include <array>
#include <charconv>
#include <vector>

namespace TextureNames {

static constexpr std::array<const char*, 6> s_mode_names = {{"P4", "P8", "C16", "STP4", "STP8", "STC16"}};
static constexpr std::string_view VRAM_WRITE_PREFIX = "vram-write-";

/// The whole of the text as a number, or nothing.
template<typename T>
static std::optional<T> ParseWhole(std::string_view text, int base)
{
  T value;
  const char* end = text.data() + text.length();
  const std::from_chars_result result = std::from_chars(text.data(), end, value, base);
  return (!text.empty() && result.ec == std::errc() && result.ptr == end) ? std::optional<T>(value) : std::nullopt;
}

static std::optional<u64> ParseHash(std::string_view text)
{
  return (text.length() == 16) ? ParseWhole<u64>(text, 16) : std::nullopt;
}

static std::optional<u16> ParseNumber(std::string_view text)
{
  return ParseWhole<u16>(text, 10);
}

static bool ParseSize(std::string_view text, u16* width, u16* height)
{
  const std::string_view::size_type x = text.find('x');
  if (x == std::string_view::npos)
    return false;

  const std::optional<u16> w = ParseNumber(text.substr(0, x));
  const std::optional<u16> h = ParseNumber(text.substr(x + 1));
  if (!w.has_value() || !h.has_value() || w.value() == 0 || h.value() == 0)
    return false;

  *width = w.value();
  *height = h.value();
  return true;
}

} // namespace TextureNames

bool TextureNames::IsSemiTransparent(Mode mode)
{
  return (mode >= Mode::STP4);
}

bool TextureNames::HasPalette(Mode mode)
{
  return (mode != Mode::C16 && mode != Mode::STC16);
}

GPUTextureMode TextureNames::GetTextureMode(Mode mode)
{
  return static_cast<GPUTextureMode>(static_cast<u8>(mode) % 3u);
}

TextureNames::Mode TextureNames::MakeMode(GPUTextureMode texture_mode, bool semitransparent)
{
  const u8 base = std::min<u8>(static_cast<u8>(texture_mode), static_cast<u8>(GPUTextureMode::Direct16Bit));
  return static_cast<Mode>(base + (semitransparent ? 3u : 0u));
}

std::optional<TextureNames::Name> TextureNames::Parse(std::string_view file_title)
{
  Name name = {};

  if (file_title.starts_with(VRAM_WRITE_PREFIX))
  {
    const std::string_view hash = file_title.substr(VRAM_WRITE_PREFIX.length());
    if (hash.length() != 32)
      return std::nullopt;

    const std::optional<u64> high = ParseHash(hash.substr(0, 16));
    const std::optional<u64> low = ParseHash(hash.substr(16));
    if (!high.has_value() || !low.has_value())
      return std::nullopt;

    name.kind = Kind::VRAMWrite;
    name.texture_hash_high = high.value();
    name.texture_hash = low.value();
    return name;
  }

  const std::vector<std::string_view> fields = StringUtil::SplitString(file_title, '-', false);
  if (fields.size() < 7)
    return std::nullopt;

  if (fields[0] == "texupload")
    name.kind = Kind::TexUpload;
  else if (fields[0] == "texpage")
    name.kind = Kind::TexPage;
  else
    return std::nullopt;

  u32 mode_index = 0;
  while (mode_index < s_mode_names.size() && fields[1] != s_mode_names[mode_index])
    mode_index++;
  if (mode_index == s_mode_names.size())
    return std::nullopt;
  name.mode = static_cast<Mode>(mode_index);

  const bool palette = HasPalette(name.mode);
  if (fields.size() != (palette ? 10u : 7u))
    return std::nullopt;

  u32 field = 2;
  const std::optional<u64> texture_hash = ParseHash(fields[field++]);
  if (!texture_hash.has_value())
    return std::nullopt;
  name.texture_hash = texture_hash.value();

  if (palette)
  {
    const std::optional<u64> palette_hash = ParseHash(fields[field++]);
    if (!palette_hash.has_value())
      return std::nullopt;
    name.palette_hash = palette_hash.value();
  }

  const std::optional<u16> offset_x = ParseNumber(fields[field + 1]);
  const std::optional<u16> offset_y = ParseNumber(fields[field + 2]);
  if (!ParseSize(fields[field], &name.source_width, &name.source_height) || !offset_x.has_value() ||
      !offset_y.has_value() || !ParseSize(fields[field + 3], &name.width, &name.height))
  {
    return std::nullopt;
  }
  name.offset_x = offset_x.value();
  name.offset_y = offset_y.value();
  field += 4;

  if (palette)
  {
    if (!fields[field].starts_with('P'))
      return std::nullopt;

    const std::optional<u16> palette_min = ParseNumber(fields[field].substr(1));
    const std::optional<u16> palette_max = ParseNumber(fields[field + 1]);
    const u16 last_index = (GetTextureMode(name.mode) == GPUTextureMode::Palette4Bit) ? 15 : 255;
    if (!palette_min.has_value() || !palette_max.has_value() || palette_min.value() > palette_max.value() ||
        palette_max.value() > last_index)
    {
      return std::nullopt;
    }

    name.palette_min = palette_min.value();
    name.palette_max = palette_max.value();
  }

  return name;
}

std::string TextureNames::Format(const Name& name)
{
  if (name.kind == Kind::VRAMWrite)
    return fmt::format("{}{:016X}{:016X}", VRAM_WRITE_PREFIX, name.texture_hash_high, name.texture_hash);

  const char* kind = (name.kind == Kind::TexUpload) ? "texupload" : "texpage";
  const char* mode = s_mode_names[static_cast<u8>(name.mode)];
  if (!HasPalette(name.mode))
  {
    return fmt::format("{}-{}-{:016X}-{}x{}-{}-{}-{}x{}", kind, mode, name.texture_hash, name.source_width,
                       name.source_height, name.offset_x, name.offset_y, name.width, name.height);
  }

  return fmt::format("{}-{}-{:016X}-{:016X}-{}x{}-{}-{}-{}x{}-P{}-{}", kind, mode, name.texture_hash, name.palette_hash,
                     name.source_width, name.source_height, name.offset_x, name.offset_y, name.width, name.height,
                     name.palette_min, name.palette_max);
}

bool TextureNames::IsImageExtension(std::string_view extension)
{
  for (const char* image_extension : {"png", "jpg", "webp"})
  {
    if (StringUtil::EqualNoCase(extension, image_extension))
      return true;
  }

  return false;
}
