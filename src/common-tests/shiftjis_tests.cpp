// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/shiftjis.h"
#include <gtest/gtest.h>

#include <cstdlib>
#include <string>
#include <vector>

// Decodes with sjis2utf8, which reads two bytes at a time and edits its input in place.
static std::string Decode(const std::string& sjis)
{
  std::vector<char> buffer(sjis.begin(), sjis.end());
  buffer.push_back(0);
  buffer.push_back(0);
  char* utf8 = sjis2utf8(buffer.data());
  std::string result(utf8);
  std::free(utf8);
  return result;
}

TEST(ShiftJIS, EncodesAsciiFullWidth)
{
  ASSERT_EQ(utf82sjis("Ab1"), std::string("\x82\x60\x82\x82\x82\x50"));
  ASSERT_EQ(utf82sjis(" "), std::string("\x81\x40"));
}

TEST(ShiftJIS, EncodesJapanese)
{
  ASSERT_EQ(utf82sjis("セーブ"), std::string("\x83\x5A\x81\x5B\x83\x75"));
}

TEST(ShiftJIS, RejectsUnencodable)
{
  ASSERT_FALSE(utf82sjis("\xF0\x9F\x98\x80").has_value()); // emoji
  ASSERT_FALSE(utf82sjis("\xC3\xA9").has_value());         // é
  ASSERT_FALSE(utf82sjis("\xEF\xBF\xBD").has_value());     // U+FFFD
  ASSERT_FALSE(utf82sjis("\xC3").has_value());             // truncated UTF-8
}

TEST(ShiftJIS, RoundTripsLettersDigitsSpace)
{
  const std::optional<std::string> sjis = utf82sjis("FINAL FANTASY VII 2");
  ASSERT_TRUE(sjis.has_value());
  ASSERT_EQ(Decode(*sjis), "FINAL FANTASY VII 2");
}

TEST(ShiftJIS, EncodingIsStableForAllPrintableAscii)
{
  for (char c = 0x20; c <= 0x7E; c++)
  {
    const std::optional<std::string> sjis = utf82sjis(std::string(1, c));
    ASSERT_TRUE(sjis.has_value()) << "character 0x" << std::hex << static_cast<int>(c);

    // The decoder shows full-width backslash and tilde as the single bytes 0x5C and 0x7E, which Shift-JIS reads as
    // the yen sign and overline; those encode back to the same single bytes.
    if (c == '\\' || c == '~')
      continue;

    ASSERT_EQ(utf82sjis(Decode(*sjis)), sjis) << "character 0x" << std::hex << static_cast<int>(c);
  }
}

TEST(ShiftJIS, BackslashAndTildeAreFullWidth)
{
  ASSERT_EQ(utf82sjis("\\"), std::string("\x81\x5F"));
  ASSERT_EQ(utf82sjis("~"), std::string("\x81\x60"));
}

TEST(ShiftJIS, EmptyInput)
{
  ASSERT_EQ(utf82sjis(""), std::string());
}

TEST(ShiftJIS, RoundTripsPunctuationInText)
{
  // The decoder reads two bytes at a time, so one single-byte character would misread everything after it.
  for (const char* text : {"Mega-Man X's \"1\"", "Lv-10", "It's", "A+B=C (100%) #1 & @2, <ok>; [x] {y} $5 ^_^"})
  {
    const std::optional<std::string> sjis = utf82sjis(text);
    ASSERT_TRUE(sjis.has_value()) << text;
    ASSERT_EQ(Decode(*sjis), text);
  }
}

TEST(ShiftJIS, FullWidthBarAndGraveStayStable)
{
  // The decoder shows these full-width, which encode back to the same codes.
  const std::optional<std::string> sjis = utf82sjis("|z| `q`");
  ASSERT_TRUE(sjis.has_value());
  ASSERT_EQ(Decode(*sjis), "\xEF\xBD\x9Cz\xEF\xBD\x9C \xEF\xBD\x80q\xEF\xBD\x80");
  ASSERT_EQ(utf82sjis(Decode(*sjis)), sjis);
}

TEST(ShiftJIS, PrintableAsciiIsAlwaysTwoBytes)
{
  for (char c = 0x20; c <= 0x7E; c++)
    EXPECT_EQ(utf82sjis(std::string(1, c))->size(), 2u) << "character '" << c << "'";
}
