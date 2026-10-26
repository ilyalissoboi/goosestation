// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "fallback_fonts.h"

#include "common/path.h"
#include "common/string_util.h"

#include <algorithm>

#ifdef _WIN32
#include "common/windows_headers.h"
#endif

namespace FallbackFonts {
namespace {

// The order matters: the first font with a glyph draws it, so Japanese shapes come before Chinese ones.
#if defined(_WIN32)
constexpr const char* SYSTEM_FONTS[] = {
  "YuGothR.ttc",  // Yu Gothic, Japanese
  "meiryo.ttc",   // Meiryo, Japanese
  "msgothic.ttc", // MS Gothic, Japanese
  "malgun.ttf",   // Malgun Gothic, Korean
  "msyh.ttc",     // Microsoft YaHei, Simplified Chinese
};
#elif defined(__APPLE__)
constexpr const char* SYSTEM_FONTS[] = {
  "/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc",    // Hiragino Sans, Japanese
  "/System/Library/Fonts/Supplemental/AppleGothic.ttf", // Korean
  "/System/Library/Fonts/Hiragino Sans GB.ttc",         // Simplified Chinese
};
#elif defined(__ANDROID__)
constexpr const char* SYSTEM_FONTS[] = {
  "/system/fonts/NotoSansCJK-Regular.ttc", // face 0 is Japanese
};
#else
constexpr const char* SYSTEM_FONTS[] = {
  "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",              // Debian, Ubuntu
  "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",                   // Arch
  "/usr/share/fonts/google-noto-sans-cjk-fonts/NotoSansCJK-Regular.ttc", // Fedora
  "/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc",            // older Fedora
  "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
};
#endif

#ifdef _WIN32
std::string GetSystemFontsDirectory()
{
  wchar_t buf[MAX_PATH];
  const UINT len = GetWindowsDirectoryW(buf, static_cast<UINT>(std::size(buf)));
  const std::string windows_dir =
    (len > 0 && len < std::size(buf)) ? StringUtil::WideStringToUTF8String(std::wstring_view(buf, len)) : "C:\\Windows";
  return Path::Combine(windows_dir, "Fonts");
}
#endif

} // namespace
} // namespace FallbackFonts

std::vector<FallbackFonts::Candidate> FallbackFonts::GetCandidates(std::string_view user_fonts_dir)
{
  std::vector<Candidate> ret;
  for (const char* name : {"NotoSansJP-Regular.ttf", "NotoSansKR-Regular.ttf", "NotoSansSC-Regular.ttf"})
    ret.push_back(Candidate{Path::Combine(user_fonts_dir, name), 0});

#ifdef _WIN32
  const std::string fonts_dir = GetSystemFontsDirectory();
  for (const char* name : SYSTEM_FONTS)
    ret.push_back(Candidate{Path::Combine(fonts_dir, name), 0});
#else
  for (const char* path : SYSTEM_FONTS)
    ret.push_back(Candidate{path, 0});
#endif

  return ret;
}

size_t FallbackFonts::AddMissingCodepoints(std::vector<char32_t>& codepoints, std::string_view text,
                                           const std::function<bool(char32_t)>& has_glyph, size_t max_count)
{
  size_t added = 0;
  for (size_t offset = 0; offset < text.size() && codepoints.size() < max_count;)
  {
    char32_t ch;
    offset += StringUtil::DecodeUTF8(text, offset, &ch);
    if (ch < 0x20 || ch == 0x7F || has_glyph(ch))
      continue;

    const auto it = std::lower_bound(codepoints.begin(), codepoints.end(), ch);
    if (it != codepoints.end() && *it == ch)
      continue;

    codepoints.insert(it, ch);
    added++;
  }

  return added;
}
