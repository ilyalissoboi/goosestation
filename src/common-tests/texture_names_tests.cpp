// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/texture_names.h"

#include "common/file_system.h"
#include "common/path.h"
#include "common/string_util.h"

#include <gtest/gtest.h>

using namespace TextureNames;

TEST(TextureNames, AVRAMWriteNameKeepsLeadingZeros)
{
  Name name = {};
  name.kind = Kind::VRAMWrite;
  name.texture_hash_high = 0x0000000012345678ull;
  name.texture_hash = 0x00000000000000ABull;
  EXPECT_EQ(Format(name), "vram-write-000000001234567800000000000000AB");

  const std::optional<Name> parsed = Parse("vram-write-000000001234567800000000000000AB");
  ASSERT_TRUE(parsed.has_value());
  EXPECT_EQ(parsed->kind, Kind::VRAMWrite);
  EXPECT_EQ(parsed->texture_hash_high, 0x0000000012345678ull);
  EXPECT_EQ(parsed->texture_hash, 0x00000000000000ABull);
}

TEST(TextureNames, ParsesAnUploadName)
{
  static constexpr const char* text = "texupload-P4-20D7201241D7412C-16F3DC9AA8F748C5-64x256-88-0-104x109-P0-15";
  const std::optional<Name> name = Parse(text);
  ASSERT_TRUE(name.has_value());
  EXPECT_EQ(name->kind, Kind::TexUpload);
  EXPECT_EQ(name->mode, Mode::P4);
  EXPECT_EQ(name->texture_hash, 0x20D7201241D7412Cull);
  EXPECT_EQ(name->palette_hash, 0x16F3DC9AA8F748C5ull);
  EXPECT_EQ(name->source_width, 64);
  EXPECT_EQ(name->source_height, 256);
  EXPECT_EQ(name->offset_x, 88);
  EXPECT_EQ(name->offset_y, 0);
  EXPECT_EQ(name->width, 104);
  EXPECT_EQ(name->height, 109);
  EXPECT_EQ(name->palette_min, 0);
  EXPECT_EQ(name->palette_max, 15);
  EXPECT_EQ(Format(name.value()), text);
}

TEST(TextureNames, ParsesAPageNameWithoutAPalette)
{
  static constexpr const char* text = "texpage-STC16-B87CDEEF156A8B44-256x256-0-0-225x193";
  const std::optional<Name> name = Parse(text);
  ASSERT_TRUE(name.has_value());
  EXPECT_EQ(name->kind, Kind::TexPage);
  EXPECT_EQ(name->mode, Mode::STC16);
  EXPECT_FALSE(HasPalette(name->mode));
  EXPECT_TRUE(IsSemiTransparent(name->mode));
  EXPECT_EQ(name->texture_hash, 0xB87CDEEF156A8B44ull);
  EXPECT_EQ(name->width, 225);
  EXPECT_EQ(name->height, 193);
  EXPECT_EQ(Format(name.value()), text);
}

TEST(TextureNames, EveryFontPackNameRoundTrips)
{
  const std::optional<std::string> list =
    FileSystem::ReadFileToString(Path::Combine(GOOSESTATION_TEST_DATA_DIR, "texture-names/vs-font-pack.txt").c_str());
  ASSERT_TRUE(list.has_value());

  u32 count = 0;
  for (const std::string_view line : StringUtil::SplitString(list.value(), '\n'))
  {
    if (line.empty())
      continue;

    const std::optional<Name> name = Parse(line);
    ASSERT_TRUE(name.has_value()) << line;
    EXPECT_EQ(name->kind, Kind::TexPage) << line;
    EXPECT_EQ(name->mode, Mode::STP4) << line;
    EXPECT_EQ(name->source_width, 64) << line;
    EXPECT_EQ(name->source_height, 256) << line;
    EXPECT_EQ(name->palette_min, 0) << line;
    EXPECT_EQ(name->palette_max, 15) << line;
    EXPECT_EQ(Format(name.value()), line);
    count++;
  }
  EXPECT_EQ(count, 161u);
}

TEST(TextureNames, ParseRejectsMalformedNames)
{
  for (const char* text : {
         "texupload-P4-20D7201241D7412-16F3DC9AA8F748C5-64x256-88-0-104x109-P0-15",  // 15 digits
         "texupload-P4-20D7201241D7412G-16F3DC9AA8F748C5-64x256-88-0-104x109-P0-15", // G in a hash
         "texupload-P4-20D7201241D7412C-16F3DC9AA8F748C5-64x256-88-0-104x109",       // no palette range
         "texupload-P4-20D7201241D7412C-64x256-88-0-104x109-P0-15",                  // no palette hash
         "texupload-C16-20D7201241D7412C-16F3DC9AA8F748C5-64x256-88-0-104x109-P0-15", // palette on C16
         "texupload-P16-20D7201241D7412C-16F3DC9AA8F748C5-64x256-88-0-104x109-P0-15", // unknown mode
         "texupload-P4-20D7201241D7412C-16F3DC9AA8F748C5-64x256-88-0-0x109-P0-15",    // zero width
         "texupload-P4-20D7201241D7412C-16F3DC9AA8F748C5-64x256-88-0-104x109-P15-0",  // min above max
         "texupload-P4-20D7201241D7412C-16F3DC9AA8F748C5-64x256-88-0-104x109-P0-16",  // past 4-bit's range
         "texupload-P8-20D7201241D7412C-16F3DC9AA8F748C5-64x256-88-0-104x109-P0-256", // past 8-bit's range
         "texupload-P4-20D7201241D7412C-16F3DC9AA8F748C5-64x256-88-0-104x109-P0-15-1", // a field too many
         "vram-write-000000001234567800000000000000A",                                // 31 digits
         "texture-P4-20D7201241D7412C-16F3DC9AA8F748C5-64x256-88-0-104x109-P0-15",    // unknown kind
       })
  {
    EXPECT_FALSE(Parse(text).has_value()) << text;
  }
}

TEST(TextureNames, ImageExtensions)
{
  EXPECT_TRUE(IsImageExtension("png"));
  EXPECT_TRUE(IsImageExtension("JPG"));
  EXPECT_TRUE(IsImageExtension("WebP"));
  EXPECT_FALSE(IsImageExtension("txt"));
  EXPECT_FALSE(IsImageExtension(""));
}
