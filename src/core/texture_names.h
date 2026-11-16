// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "gpu_types.h"
#include "types.h"

#include <optional>
#include <string>
#include <string_view>

/// Names of dumped and replacement textures, in the published layout (docs/texture-replacement.md, "Names").
namespace TextureNames {

enum class Kind : u8
{
  VRAMWrite, // vram-write-<32 hex>
  TexUpload, // texupload-…
  TexPage,   // texpage-…
};

/// The texture's mode; ST marks a texture drawn with semi-transparency on.
enum class Mode : u8
{
  P4,
  P8,
  C16,
  STP4,
  STP8,
  STC16,
};

bool IsSemiTransparent(Mode mode);
bool HasPalette(Mode mode);
GPUTextureMode GetTextureMode(Mode mode);
Mode MakeMode(GPUTextureMode texture_mode, bool semitransparent);

struct Name
{
  Kind kind;
  Mode mode;

  /// The texture hash; for VRAMWrite, its low 64 bits.
  u64 texture_hash;

  /// VRAMWrite only: the high 64 bits of its hash.
  u64 texture_hash_high;

  /// Palette modes only.
  u64 palette_hash;

  /// The upload's or page's size in VRAM words.
  u16 source_width;
  u16 source_height;

  /// The rect, in texels of the expanded texture.
  u16 offset_x;
  u16 offset_y;
  u16 width;
  u16 height;

  /// Palette modes only: the palette range named.
  u16 palette_min;
  u16 palette_max;
};

/// Parses a file title (no extension); std::nullopt if it isn't a well-formed name.
std::optional<Name> Parse(std::string_view file_title);

/// Formats a name, without an extension.
std::string Format(const Name& name);

/// png, jpg or webp, in any case.
bool IsImageExtension(std::string_view extension);

} // namespace TextureNames
