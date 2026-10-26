// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/fallback_fonts.h"

#include "common/path.h"

#include <gtest/gtest.h>

namespace {
bool IsLatin(char32_t ch)
{
  return (ch < 0x80);
}

// gtest can't print char32_t.
std::vector<u32> AsU32(const std::vector<char32_t>& codepoints)
{
  return std::vector<u32>(codepoints.begin(), codepoints.end());
}
} // namespace

TEST(FallbackFonts, AddsOnlyMissingCodepoints)
{
  std::vector<char32_t> codepoints;
  ASSERT_EQ(FallbackFonts::AddMissingCodepoints(codepoints, "Saved 'エースコンバット' to 'x'.", IsLatin, 100), 8u);
  ASSERT_EQ(AsU32(codepoints), AsU32({U'エ', U'コ', U'ス', U'ッ', U'ト', U'バ', U'ン', U'ー'}));
}

TEST(FallbackFonts, KeepsCodepointsSortedAndUnique)
{
  std::vector<char32_t> codepoints;
  ASSERT_EQ(FallbackFonts::AddMissingCodepoints(codepoints, "３２１", IsLatin, 100), 3u);
  ASSERT_EQ(FallbackFonts::AddMissingCodepoints(codepoints, "１２３４", IsLatin, 100), 1u);
  ASSERT_EQ(AsU32(codepoints), AsU32({U'１', U'２', U'３', U'４'}));
  ASSERT_EQ(FallbackFonts::AddMissingCodepoints(codepoints, "４３", IsLatin, 100), 0u);
}

TEST(FallbackFonts, SkipsControlCharacters)
{
  std::vector<char32_t> codepoints;
  ASSERT_EQ(FallbackFonts::AddMissingCodepoints(codepoints, "a\nb\tc\r", [](char32_t) { return false; }, 100), 3u);
  ASSERT_EQ(AsU32(codepoints), AsU32({U'a', U'b', U'c'}));
}

TEST(FallbackFonts, StopsAtMaxCount)
{
  std::vector<char32_t> codepoints;
  ASSERT_EQ(FallbackFonts::AddMissingCodepoints(codepoints, "あいう", IsLatin, 2), 2u);
  ASSERT_EQ(AsU32(codepoints), AsU32({U'あ', U'い'}));
  ASSERT_EQ(FallbackFonts::AddMissingCodepoints(codepoints, "え", IsLatin, 2), 0u);
  ASSERT_EQ(codepoints.size(), 2u);
}

TEST(FallbackFonts, CandidatesStartWithDownloadedFonts)
{
  const std::string dir = Path::Combine("user", "fonts");
  const std::vector<FallbackFonts::Candidate> candidates = FallbackFonts::GetCandidates(dir);
  ASSERT_GE(candidates.size(), 4u);
  ASSERT_EQ(candidates[0].path, Path::Combine(dir, "NotoSansJP-Regular.ttf"));
  ASSERT_EQ(candidates[1].path, Path::Combine(dir, "NotoSansKR-Regular.ttf"));
  ASSERT_EQ(candidates[2].path, Path::Combine(dir, "NotoSansSC-Regular.ttf"));
  for (const FallbackFonts::Candidate& candidate : candidates)
    ASSERT_FALSE(candidate.path.empty());
}

#ifdef __ANDROID__
TEST(FallbackFonts, AndroidUsesSystemNotoCJK)
{
  const std::vector<FallbackFonts::Candidate> candidates = FallbackFonts::GetCandidates("fonts");
  ASSERT_EQ(candidates[3].path, "/system/fonts/NotoSansCJK-Regular.ttc");
  ASSERT_EQ(candidates[3].face_index, 0u);
}
#endif
