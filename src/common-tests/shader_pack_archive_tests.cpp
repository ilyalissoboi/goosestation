// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "shader_pack_test_utils.h"

#include "core/shader_pack_archive.h"

#include "common/error.h"

#include <gtest/gtest.h>

#include <array>

using ShaderPackArchive::EntryDisposition;
using ShaderPackTest::MakeZip;
using ShaderPackTest::RecordingProgress;
using ShaderPackTest::TempDirectory;
using ShaderPackTest::ZipEntry;

namespace {

constexpr std::span<const char* const> NO_SKIP;
constexpr std::array<const char*, 1> HDR_SKIP = {"hdr/"};

EntryDisposition Transform(std::string_view entry, u32 strip, std::string* relative,
                           std::span<const char* const> skip = NO_SKIP)
{
  Error error;
  return ShaderPackArchive::TransformEntryName(entry, strip, skip, relative, &error);
}

} // namespace

TEST(ShaderPackArchive, TransformEntryNameRules)
{
  std::string rel;
  EXPECT_EQ(Transform("top/crt/a.slangp", 1, &rel), EntryDisposition::Extract);
  EXPECT_EQ(rel, "crt/a.slangp");
  EXPECT_EQ(Transform("a/b/c/d/crt/x.slang", 4, &rel), EntryDisposition::Extract);
  EXPECT_EQ(rel, "crt/x.slang");
  EXPECT_EQ(Transform("top/./crt//a.slangp", 1, &rel), EntryDisposition::Extract);
  EXPECT_EQ(rel, "crt/a.slangp");
  EXPECT_EQ(Transform("top/", 1, &rel), EntryDisposition::Skip);
  EXPECT_EQ(Transform("top/crt/", 1, &rel), EntryDisposition::Skip);
  EXPECT_EQ(Transform("a/b/c/d", 4, &rel), EntryDisposition::Skip);
  EXPECT_EQ(Transform("top/__MACOSX/crt/._a.slangp", 1, &rel), EntryDisposition::Skip);
  EXPECT_EQ(Transform("__MACOSX/retro crisis/._a.slangp", 1, &rel), EntryDisposition::Skip);
  EXPECT_EQ(Transform("top/crt/.DS_Store", 1, &rel), EntryDisposition::Skip);
  EXPECT_EQ(Transform("", 1, &rel), EntryDisposition::Skip);
  EXPECT_EQ(Transform("top/../evil.slang", 1, &rel), EntryDisposition::Reject);
  EXPECT_EQ(Transform("top/crt/../../../evil.slang", 1, &rel), EntryDisposition::Reject);
  EXPECT_EQ(Transform("/abs/evil.slang", 1, &rel), EntryDisposition::Reject);
  EXPECT_EQ(Transform("top\\crt\\a.slangp", 1, &rel), EntryDisposition::Reject);
  EXPECT_EQ(Transform("C:/evil.slang", 0, &rel), EntryDisposition::Reject);

  EXPECT_EQ(Transform("lola-retro/hdr/curved/a.slangp", 1, &rel, HDR_SKIP), EntryDisposition::Skip);
  EXPECT_EQ(Transform("lola-retro/hdrx/a.slangp", 1, &rel, HDR_SKIP), EntryDisposition::Extract);
  EXPECT_EQ(rel, "hdrx/a.slangp");
  EXPECT_EQ(Transform("lola-retro/shaders/crt-hdr.slang", 1, &rel, HDR_SKIP), EntryDisposition::Extract);

  Error error;
  EXPECT_EQ(ShaderPackArchive::TransformEntryName("top/../evil.slang", 1, NO_SKIP, &rel, &error),
            EntryDisposition::Reject);
  EXPECT_EQ(error.GetDescription(), "Refusing unsafe archive entry 'top/../evil.slang'.");
}

TEST(ShaderPackArchive, ExtractStripsSkipsAndRecordsFiles)
{
  const TempDirectory tmp;
  const std::string zip = tmp.Get("rc.zip");
  ASSERT_TRUE(MakeZip(zip, {{"retro crisis/", ""},
                            {"retro crisis/.DS_Store", "junk"},
                            {"retro crisis/1080p Flat/a.slangp", "shaders = 0\n"},
                            {"retro crisis/1080p Flat/deeper/b.slangp", "shaders = 1\n"},
                            {"__MACOSX/retro crisis/._a.slangp", "junk"}}));

  const std::string dest = tmp.Get("out/shaders_slang/retro crisis");
  std::vector<std::string> written;
  Error error;
  RecordingProgress progress;
  ASSERT_TRUE(ShaderPackArchive::Extract(zip, dest, 1, NO_SKIP, &progress, &written, &error))
    << error.GetDescription();
  EXPECT_EQ(written, (std::vector<std::string>{"1080p Flat/a.slangp", "1080p Flat/deeper/b.slangp"}));
  EXPECT_EQ(tmp.Read("out/shaders_slang/retro crisis/1080p Flat/a.slangp"), "shaders = 0\n");
  EXPECT_EQ(tmp.Read("out/shaders_slang/retro crisis/1080p Flat/deeper/b.slangp"), "shaders = 1\n");
  EXPECT_FALSE(tmp.Exists("out/shaders_slang/retro crisis/.DS_Store"));
  EXPECT_FALSE(tmp.Exists("out/__MACOSX"));
  EXPECT_EQ(progress.GetRange(), 5u);
  EXPECT_EQ(progress.GetValue(), 5u);
}

TEST(ShaderPackArchive, ExtractAbortsOnTraversalAndKeepsWrittenList)
{
  const TempDirectory tmp;
  const std::string zip = tmp.Get("evil.zip");
  ASSERT_TRUE(MakeZip(zip, {{"top/first.slang", "1"}, {"top/../../escaped.slang", "2"}, {"top/third.slang", "3"}}));

  std::vector<std::string> written;
  Error error;
  EXPECT_FALSE(ShaderPackArchive::Extract(zip, tmp.Get("dest"), 1, NO_SKIP, nullptr, &written, &error));
  EXPECT_EQ(written, (std::vector<std::string>{"first.slang"}));
  EXPECT_EQ(error.GetDescription(), "Refusing unsafe archive entry 'top/../../escaped.slang'.");
  EXPECT_FALSE(tmp.Exists("escaped.slang"));
  EXPECT_FALSE(tmp.Exists("dest/third.slang"));
}

TEST(ShaderPackArchive, ExtractRejectsAnOversizedEntryAndAnOversizedTotal)
{
  const TempDirectory tmp;
  const std::string big = tmp.Get("big.zip");
  ASSERT_TRUE(MakeZip(big, {{"top/small.slang", "12345"}, {"top/big.slang", std::string(11, 'x')}}));
  std::vector<std::string> written;
  Error error;
  EXPECT_FALSE(ShaderPackArchive::Extract(big, tmp.Get("a"), 1, NO_SKIP, nullptr, &written, &error, 10, 25));
  EXPECT_EQ(written, (std::vector<std::string>{"small.slang"}));
  EXPECT_EQ(error.GetDescription(), "Archive entry 'top/big.slang' is too large (11 bytes).");

  const std::string many = tmp.Get("many.zip");
  ASSERT_TRUE(MakeZip(many, {{"t/1", std::string(8, 'a')},
                             {"t/2", std::string(8, 'b')},
                             {"t/3", std::string(8, 'c')},
                             {"t/4", std::string(8, 'd')}}));
  written.clear();
  EXPECT_FALSE(ShaderPackArchive::Extract(many, tmp.Get("b"), 1, NO_SKIP, nullptr, &written, &error, 10, 25));
  EXPECT_EQ(written, (std::vector<std::string>{"1", "2", "3"}));
  EXPECT_EQ(error.GetDescription(), "Archive entry 't/4' is too large (8 bytes).");
}

TEST(ShaderPackArchive, SymbolicLinksAreSkipped)
{
  const TempDirectory tmp;
  const std::string zip = tmp.Get("links.zip");
  ASSERT_TRUE(MakeZip(zip, {{"top/real.slang", "real"}, {"top/link.slang", "/etc/passwd", true}}));

  std::vector<std::string> written;
  Error error;
  ASSERT_TRUE(ShaderPackArchive::Extract(zip, tmp.Get("dest"), 1, NO_SKIP, nullptr, &written, &error))
    << error.GetDescription();
  EXPECT_EQ(written, (std::vector<std::string>{"real.slang"}));
  EXPECT_FALSE(tmp.Exists("dest/link.slang"));

  std::vector<std::string> listed;
  ASSERT_TRUE(ShaderPackArchive::ListFiles(zip, 1, NO_SKIP, &listed, &error));
  EXPECT_EQ(listed, written);
}

TEST(ShaderPackArchive, ListFilesMatchesWhatExtractWrites)
{
  const TempDirectory tmp;
  const std::string zip = tmp.Get("lola.zip");
  ASSERT_TRUE(MakeZip(zip, {{"lola-retro/", ""},
                            {"lola-retro/LICENSE", "MIT"},
                            {"lola-retro/curved/a.slangp", "a"},
                            {"lola-retro/hdr/curved/a.slangp", "hdr"},
                            {"lola-retro/shaders/crt.slang", "s"}}));

  std::vector<std::string> listed, written;
  Error error;
  ASSERT_TRUE(ShaderPackArchive::ListFiles(zip, 1, HDR_SKIP, &listed, &error)) << error.GetDescription();
  ASSERT_TRUE(ShaderPackArchive::Extract(zip, tmp.Get("dest"), 1, HDR_SKIP, nullptr, &written, &error))
    << error.GetDescription();
  EXPECT_EQ(listed, (std::vector<std::string>{"LICENSE", "curved/a.slangp", "shaders/crt.slang"}));
  EXPECT_EQ(written, listed);
  EXPECT_FALSE(tmp.Exists("dest/hdr/curved/a.slangp"));

  const std::string evil = tmp.Get("evil.zip");
  ASSERT_TRUE(MakeZip(evil, {{"top/ok", "1"}, {"top/../x", "2"}}));
  EXPECT_FALSE(ShaderPackArchive::ListFiles(evil, 1, NO_SKIP, &listed, &error));
}

TEST(ShaderPackArchive, ANonZipFails)
{
  const TempDirectory tmp;
  tmp.Write("not.zip", "<html>rate limited</html>");
  std::vector<std::string> listed, written;
  Error error;
  EXPECT_FALSE(ShaderPackArchive::ListFiles(tmp.Get("not.zip"), 1, NO_SKIP, &listed, &error));
  EXPECT_FALSE(ShaderPackArchive::Extract(tmp.Get("not.zip"), tmp.Get("dest"), 1, NO_SKIP, nullptr, &written, &error));
  EXPECT_FALSE(ShaderPackArchive::ListFiles(tmp.Get("missing.zip"), 1, NO_SKIP, &listed, &error));
  EXPECT_TRUE(written.empty());
}

TEST(ShaderPackArchive, CancelStopsBetweenEntries)
{
  const TempDirectory tmp;
  const std::string zip = tmp.Get("three.zip");
  ASSERT_TRUE(MakeZip(zip, {{"t/1", "a"}, {"t/2", "b"}, {"t/3", "c"}}));

  RecordingProgress progress;
  progress.cancel_at = 1;
  std::vector<std::string> written;
  Error error;
  EXPECT_FALSE(ShaderPackArchive::Extract(zip, tmp.Get("dest"), 1, NO_SKIP, &progress, &written, &error));
  EXPECT_EQ(written, (std::vector<std::string>{"1"}));
  EXPECT_EQ(error.GetDescription(), "Cancelled.");
}
