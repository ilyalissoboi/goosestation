// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <functional>
#include <string>
#include <string_view>
#include <vector>

// Fonts for the glyphs the UI font lacks, such as the Japanese in a game's title or a save's name. ImGuiManager
// rasterizes only the code points that text on screen has needed, from the first of these fonts that has them.
namespace FallbackFonts {

/// A font file to try, and the face to use when it is a collection (.ttc).
struct Candidate
{
  std::string path;
  u32 face_index;
};

/// Returns the fonts to try, in order: the Noto fonts the Qt host downloads for a CJK language into user_fonts_dir,
/// then the system's own CJK fonts. The files may not exist.
std::vector<Candidate> GetCandidates(std::string_view user_fonts_dir);

/// Adds each code point of the UTF-8 text that has_glyph rejects to codepoints, which stays sorted and unique, while
/// it holds fewer than max_count. Control characters are skipped. Returns how many were added.
size_t AddMissingCodepoints(std::vector<char32_t>& codepoints, std::string_view text,
                            const std::function<bool(char32_t)>& has_glyph, size_t max_count);

} // namespace FallbackFonts
