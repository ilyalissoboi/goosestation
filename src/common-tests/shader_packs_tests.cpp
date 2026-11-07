// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/shader_packs.h"

#include "util/http_downloader.h"

#include "common/error.h"

#include <gtest/gtest.h>

#include <string>
#include <vector>

using ShaderPacks::PackInfo;
using ShaderPacks::ResolvedVersion;
using ShaderPacks::VersionSource;

namespace {

const PackInfo& Pack(std::string_view id)
{
  const PackInfo* pack = ShaderPacks::FindPack(id);
  EXPECT_NE(pack, nullptr) << id;
  return *pack;
}

std::vector<std::string> Skips(const PackInfo& pack)
{
  return std::vector<std::string>(pack.skip.begin(), pack.skip.end());
}

// A releases/latest answer with the given assets (name, size), in order.
std::string ReleaseJson(std::string_view tag, const std::vector<std::pair<std::string, u64>>& assets)
{
  std::string json = fmt::format(R"({{"tag_name":"{}","name":"Release","prerelease":false,"assets":[)", tag);
  for (size_t i = 0; i < assets.size(); i++)
  {
    json += fmt::format(R"({}{{"name":"{}","size":{},"uploader":{{"login":"someone","id":1}},)"
                        R"("browser_download_url":"https://github.com/o/r/releases/download/{}/{}"}})",
                        i ? "," : "", assets[i].first, assets[i].second, tag, assets[i].first);
  }
  return json + "]}";
}

} // namespace

TEST(ShaderPacks, TheTableIsTheDesignsFourPacks)
{
  ASSERT_EQ(ShaderPacks::GetPacks().size(), 4u);

  const PackInfo& libretro = Pack("shaders_slang");
  EXPECT_STREQ(libretro.name, "libretro slang shaders");
  EXPECT_STREQ(libretro.repo, "libretro/slang-shaders");
  EXPECT_EQ(libretro.source, VersionSource::BranchHead);
  EXPECT_STREQ(libretro.branch, "master");
  EXPECT_EQ(libretro.strip, 1u);
  EXPECT_STREQ(libretro.install_dir, "shaders_slang");
  EXPECT_TRUE(Skips(libretro).empty());
  EXPECT_EQ(libretro.needs, nullptr);
  EXPECT_STREQ(libretro.licence, "Each shader's own (see its file)");

  const PackInfo& crisis = Pack("retro-crisis-gdv-ntsc");
  EXPECT_STREQ(crisis.name, "Retro Crisis GDV-NTSC");
  EXPECT_STREQ(crisis.repo, "RetroCrisis/Retro-Crisis-GDV-NTSC");
  EXPECT_EQ(crisis.source, VersionSource::LatestRelease);
  EXPECT_EQ(crisis.asset_prefix, nullptr);
  EXPECT_EQ(crisis.asset_exclude, nullptr);
  EXPECT_EQ(crisis.strip, 1u);
  EXPECT_STREQ(crisis.install_dir, "shaders_slang/retro crisis");
  EXPECT_STREQ(crisis.needs, "shaders_slang");
  EXPECT_STREQ(crisis.licence, "GPL-3.0");

  const PackInfo& satpixie = Pack("satpixie-crt");
  EXPECT_STREQ(satpixie.name, "satpixie CRT");
  EXPECT_STREQ(satpixie.repo, "Conkwer/satpixie-crt-shader");
  EXPECT_EQ(satpixie.source, VersionSource::LatestRelease);
  EXPECT_STREQ(satpixie.asset_exclude, "variants");
  EXPECT_EQ(satpixie.strip, 4u);
  EXPECT_STREQ(satpixie.install_dir, "shaders_slang");
  EXPECT_EQ(satpixie.needs, nullptr);
  EXPECT_STREQ(satpixie.licence, "MIT or public domain");

  const PackInfo& lola = Pack("lola-retro");
  EXPECT_STREQ(lola.name, "lola-retro");
  EXPECT_STREQ(lola.repo, "zoran123456/lola-retro");
  EXPECT_EQ(lola.source, VersionSource::LatestRelease);
  EXPECT_STREQ(lola.asset_prefix, "lola-retro-v");
  EXPECT_EQ(lola.strip, 1u);
  EXPECT_STREQ(lola.install_dir, "lola-retro");
  EXPECT_EQ(Skips(lola), (std::vector<std::string>{"hdr/"}));
  EXPECT_STREQ(lola.licence, "MIT");

  for (const PackInfo& pack : ShaderPacks::GetPacks())
  {
    EXPECT_NE(std::string_view(pack.description), "") << pack.id;
    for (const char c : std::string_view(pack.id))
      EXPECT_TRUE((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') << pack.id;
    if (pack.needs)
      EXPECT_NE(ShaderPacks::FindPack(pack.needs), nullptr) << pack.id;
  }
  EXPECT_EQ(ShaderPacks::FindPack("nonexistent"), nullptr);
}

TEST(ShaderPacks, VersionUrlsFollowGitHubsApi)
{
  EXPECT_EQ(ShaderPacks::GetVersionUrl(Pack("shaders_slang")),
            "https://api.github.com/repos/libretro/slang-shaders/branches/master");
  EXPECT_EQ(ShaderPacks::GetVersionUrl(Pack("lola-retro")),
            "https://api.github.com/repos/zoran123456/lola-retro/releases/latest");
}

TEST(ShaderPacks, ABranchAnswerGivesTheShortShaAndTheArchiveUrl)
{
  const std::string json =
    R"({"name":"master","commit":{"sha":"3685eda59ffd2ebe1b12cc4640581dc23552ce88","url":"x"},"protected":true})";
  ResolvedVersion version;
  Error error;
  ASSERT_TRUE(ShaderPacks::ParseBranchJson(json, Pack("shaders_slang"), &version, &error)) << error.GetDescription();
  EXPECT_EQ(version.version, "3685eda59ffd");
  EXPECT_EQ(version.download_url,
            "https://github.com/libretro/slang-shaders/archive/3685eda59ffd2ebe1b12cc4640581dc23552ce88.zip");
  EXPECT_EQ(version.size, 0u);
}

TEST(ShaderPacks, AReleaseAnswerPicksTheMatchingZip)
{
  const std::string json = ReleaseJson("20260122", {{"satpixie-crt-shader-variants-20260122.zip", 339087},
                                                    {"notes.txt", 10},
                                                    {"satpixie-crt-shader-20260122.zip", 48424}});
  ResolvedVersion version;
  Error error;
  ASSERT_TRUE(ShaderPacks::ParseReleaseJson(json, Pack("satpixie-crt"), &version, &error)) << error.GetDescription();
  EXPECT_EQ(version.version, "20260122");
  EXPECT_EQ(version.download_url,
            "https://github.com/o/r/releases/download/20260122/satpixie-crt-shader-20260122.zip");
  EXPECT_EQ(version.size, 48424u);
}

TEST(ShaderPacks, LolaRetroTakesOnlyItsOwnZip)
{
  const std::string json = ReleaseJson("v1.19", {{"lola-retro-86box-v1.19.zip", 153229},
                                                 {"lola-retro-amiberry-v1.19.zip", 160527},
                                                 {"lola-retro-kodi-v1.19.zip", 337108},
                                                 {"lola-retro-v1.19.zip", 370341}});
  ResolvedVersion version;
  Error error;
  ASSERT_TRUE(ShaderPacks::ParseReleaseJson(json, Pack("lola-retro"), &version, &error)) << error.GetDescription();
  EXPECT_EQ(version.version, "v1.19");
  EXPECT_TRUE(version.download_url.ends_with("/lola-retro-v1.19.zip")) << version.download_url;
  EXPECT_EQ(version.size, 370341u);

  const std::string only_86box = ReleaseJson("v1.19", {{"lola-retro-86box-v1.19.zip", 153229}});
  EXPECT_FALSE(ShaderPacks::ParseReleaseJson(only_86box, Pack("lola-retro"), &version, &error));
  EXPECT_EQ(error.GetDescription(), "Release v1.19 has no matching .zip file.");
}

TEST(ShaderPacks, MalformedAnswersFail)
{
  ResolvedVersion version;
  Error error;
  EXPECT_FALSE(ShaderPacks::ParseReleaseJson("not json", Pack("lola-retro"), &version, &error));
  EXPECT_FALSE(ShaderPacks::ParseReleaseJson(R"({"assets":[]})", Pack("lola-retro"), &version, &error));
  EXPECT_FALSE(ShaderPacks::ParseReleaseJson(R"([1,2])", Pack("lola-retro"), &version, &error));
  EXPECT_FALSE(ShaderPacks::ParseBranchJson("{", Pack("shaders_slang"), &version, &error));
  EXPECT_FALSE(ShaderPacks::ParseBranchJson(R"({"name":"master"})", Pack("shaders_slang"), &version, &error));
  EXPECT_FALSE(ShaderPacks::ParseBranchJson(R"({"commit":{"sha":""}})", Pack("shaders_slang"), &version, &error));
  EXPECT_FALSE(
    ShaderPacks::ParseBranchJson(R"({"commit":{"sha":"../../etc/passwd"}})", Pack("shaders_slang"), &version, &error));
}

TEST(ShaderPacks, FailuresAreDescribed)
{
  const PackInfo& lola = Pack("lola-retro");
  EXPECT_EQ(ShaderPacks::DescribeFailure(true, lola, 0), "Couldn't reach GitHub: check the connection.");
  EXPECT_EQ(ShaderPacks::DescribeFailure(false, lola, HTTPDownloader::HTTP_STATUS_ERROR),
            "Couldn't reach GitHub: check the connection.");
  EXPECT_EQ(ShaderPacks::DescribeFailure(true, lola, HTTPDownloader::HTTP_STATUS_TIMEOUT),
            "GitHub took too long to answer: try again later.");
  EXPECT_EQ(ShaderPacks::DescribeFailure(false, lola, HTTPDownloader::HTTP_STATUS_CANCELLED), "Cancelled.");
  EXPECT_EQ(ShaderPacks::DescribeFailure(true, lola, 403), "GitHub's rate limit was reached: try again later.");
  EXPECT_EQ(ShaderPacks::DescribeFailure(true, lola, 429), "GitHub's rate limit was reached: try again later.");
  EXPECT_EQ(ShaderPacks::DescribeFailure(false, lola, 403), "Downloading lola-retro failed (HTTP 403).");
  EXPECT_EQ(ShaderPacks::DescribeFailure(true, lola, 404), "Checking lola-retro failed (HTTP 404).");
}
