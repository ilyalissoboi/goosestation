// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "shader_pack_test_utils.h"

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

using ShaderPacks::InstalledPack;
using ShaderPacks::PackState;
using ShaderPackTest::TempDirectory;

namespace {

InstalledPack MakeInstalled(std::string id, std::string version, std::vector<std::string> files)
{
  InstalledPack pack;
  pack.id = std::move(id);
  pack.version = std::move(version);
  pack.source_url = "https://github.com/o/r/archive/x.zip";
  pack.installed_at = "2026-11-07T08:00:00Z";
  pack.files = std::move(files);
  return pack;
}

// Writes the marker and every file it lists (each holding its own path).
void Install(const TempDirectory& root, const InstalledPack& pack)
{
  for (const std::string& file : pack.files)
    root.Write(file, file);
  Error error;
  ASSERT_TRUE(ShaderPacks::WriteMarker(root.GetPath(), pack, &error)) << error.GetDescription();
}

ResolvedVersion Latest(std::string version)
{
  ResolvedVersion latest;
  latest.version = std::move(version);
  latest.download_url = "https://example.invalid/x.zip";
  return latest;
}

} // namespace

TEST(ShaderPacks, MarkerRoundTrip)
{
  const TempDirectory root;
  InstalledPack pack = MakeInstalled("lola-retro", "v1.19", {"lola-retro/curved/a.slangp", "lola-retro/LICENSE"});
  pack.archive_size = 370341;
  Error error;
  ASSERT_TRUE(ShaderPacks::WriteMarker(root.GetPath(), pack, &error)) << error.GetDescription();
  EXPECT_EQ(ShaderPacks::GetMarkerPath(root.GetPath(), "lola-retro"),
            Path::Combine(root.GetPath(), ".shaderpacks/lola-retro.json"));
  EXPECT_TRUE(root.Exists(".shaderpacks/lola-retro.json"));

  const std::optional<InstalledPack> read = ShaderPacks::ReadMarker(root.GetPath(), "lola-retro");
  ASSERT_TRUE(read.has_value());
  EXPECT_EQ(read->id, "lola-retro");
  EXPECT_EQ(read->version, "v1.19");
  EXPECT_EQ(read->source_url, pack.source_url);
  EXPECT_EQ(read->installed_at, pack.installed_at);
  EXPECT_EQ(read->archive_size, 370341u);
  EXPECT_EQ(read->files, pack.files);

  // An incomplete install: no version, nothing landed yet.
  const InstalledPack empty = MakeInstalled("satpixie-crt", "", {});
  ASSERT_TRUE(ShaderPacks::WriteMarker(root.GetPath(), empty, &error));
  const std::optional<InstalledPack> read_empty = ShaderPacks::ReadMarker(root.GetPath(), "satpixie-crt");
  ASSERT_TRUE(read_empty.has_value());
  EXPECT_EQ(read_empty->version, "");
  EXPECT_TRUE(read_empty->files.empty());
  EXPECT_EQ(read_empty->archive_size, 0u);
}

TEST(ShaderPacks, MarkersDropUnsafePaths)
{
  const std::optional<InstalledPack> pack = ShaderPacks::ParseMarker(
    R"({"id":"x","version":"1","files":["ok/a.slang","","/abs","a\\b","C:/x","a/../b","../x","ok/./b",7]})");
  ASSERT_TRUE(pack.has_value());
  EXPECT_EQ(pack->files, (std::vector<std::string>{"ok/a.slang"}));
}

TEST(ShaderPacks, MalformedMarkersReadAsNotInstalled)
{
  EXPECT_FALSE(ShaderPacks::ParseMarker("{").has_value());
  EXPECT_FALSE(ShaderPacks::ParseMarker(R"({"version":"1","files":[]})").has_value());
  EXPECT_FALSE(ShaderPacks::ParseMarker(R"({"id":"x","files":[]})").has_value());
  EXPECT_FALSE(ShaderPacks::ParseMarker(R"({"id":"x","version":"1"})").has_value());
  EXPECT_FALSE(ShaderPacks::ParseMarker(R"({"id":"x","version":"1","files":"a"})").has_value());

  const TempDirectory root;
  EXPECT_FALSE(ShaderPacks::ReadMarker(root.GetPath(), "lola-retro").has_value());
  root.Write(".shaderpacks/lola-retro.json", "garbage");
  EXPECT_FALSE(ShaderPacks::ReadMarker(root.GetPath(), "lola-retro").has_value());
}

TEST(ShaderPacks, ExpandDependenciesPutsLibretroFirstOnlyWhenMissing)
{
  const TempDirectory root;
  const auto expand = [&root](std::vector<std::string> ids) {
    return ShaderPacks::ExpandDependencies(root.GetPath(), ids);
  };

  EXPECT_EQ(expand({"retro-crisis-gdv-ntsc"}), (std::vector<std::string>{"shaders_slang", "retro-crisis-gdv-ntsc"}));
  EXPECT_EQ(expand({"retro-crisis-gdv-ntsc", "shaders_slang"}),
            (std::vector<std::string>{"shaders_slang", "retro-crisis-gdv-ntsc"}));
  EXPECT_EQ(expand({"satpixie-crt", "bogus", "satpixie-crt", "lola-retro"}),
            (std::vector<std::string>{"satpixie-crt", "lola-retro"}));

  // Installed, even with its files deleted by hand: it isn't added again.
  Install(root, MakeInstalled("shaders_slang", "3685eda59ffd", {}));
  EXPECT_EQ(expand({"retro-crisis-gdv-ntsc"}), (std::vector<std::string>{"retro-crisis-gdv-ntsc"}));
  // Asked for by name, it's updated as asked.
  EXPECT_EQ(expand({"shaders_slang", "retro-crisis-gdv-ntsc"}),
            (std::vector<std::string>{"shaders_slang", "retro-crisis-gdv-ntsc"}));
}

TEST(ShaderPacks, UninstallDeletesListedFilesOnly)
{
  const TempDirectory root;
  Install(root, MakeInstalled("lola-retro", "v1.19",
                              {"lola-retro/curved/a.slangp", "lola-retro/shaders/s.slang", "lola-retro/a/b/c/deep"}));
  root.Write("lola-retro/mine.slangp", "the player's own");

  Error error;
  ASSERT_TRUE(ShaderPacks::Uninstall(root.GetPath(), "lola-retro", &error)) << error.GetDescription();
  EXPECT_FALSE(root.Exists("lola-retro/curved/a.slangp"));
  EXPECT_FALSE(root.Exists("lola-retro/shaders/s.slang"));
  EXPECT_FALSE(root.Exists("lola-retro/a/b/c/deep"));
  EXPECT_TRUE(root.Exists("lola-retro/mine.slangp"));
  EXPECT_FALSE(FileSystem::DirectoryExists(root.Get("lola-retro/curved").c_str()));
  EXPECT_FALSE(FileSystem::DirectoryExists(root.Get("lola-retro/a").c_str()));
  EXPECT_FALSE(root.Exists(".shaderpacks/lola-retro.json"));
  EXPECT_FALSE(ShaderPacks::ReadMarker(root.GetPath(), "lola-retro").has_value());

  EXPECT_FALSE(ShaderPacks::Uninstall(root.GetPath(), "lola-retro", &error));
  EXPECT_EQ(error.GetDescription(), "lola-retro isn't installed.");
}

TEST(ShaderPacks, RemovingAPackKeepsFilesAnotherPackLists)
{
  const TempDirectory root;
  Install(root, MakeInstalled("shaders_slang", "3685eda59ffd", {"shaders_slang/crt/x.slang", "shaders_slang/crt/l.slang"}));
  Install(root, MakeInstalled("satpixie-crt", "20260122",
                              {"shaders_slang/crt/x.slang", "shaders_slang/crt/satpixie-crt.slangp"}));

  Error error;
  ASSERT_TRUE(ShaderPacks::Uninstall(root.GetPath(), "satpixie-crt", &error)) << error.GetDescription();
  EXPECT_FALSE(root.Exists("shaders_slang/crt/satpixie-crt.slangp"));
  EXPECT_TRUE(root.Exists("shaders_slang/crt/x.slang"));
  EXPECT_TRUE(root.Exists("shaders_slang/crt/l.slang"));
  EXPECT_TRUE(ShaderPacks::ReadMarker(root.GetPath(), "shaders_slang").has_value());
}

TEST(ShaderPacks, PruneLeavesFoldersWithOtherFiles)
{
  const TempDirectory root;
  Install(root, MakeInstalled("lola-retro", "v1.19", {"lola-retro/flat/a.slangp", "lola-retro/lite/b.slangp"}));
  root.Write("lola-retro/flat/keep.txt", "the player's note");

  const std::optional<InstalledPack> pack = ShaderPacks::ReadMarker(root.GetPath(), "lola-retro");
  ASSERT_TRUE(pack.has_value());
  ShaderPacks::RemoveFiles(root.GetPath(), pack.value());
  EXPECT_TRUE(root.Exists("lola-retro/flat/keep.txt"));
  EXPECT_FALSE(FileSystem::DirectoryExists(root.Get("lola-retro/lite").c_str()));
  EXPECT_TRUE(FileSystem::DirectoryExists(root.Get("lola-retro").c_str()));

  // A pack whose files are all gone takes its emptied install folder with it.
  Install(root, MakeInstalled("satpixie-crt", "20260122", {"shaders_slang/crt/s.slangp"}));
  const std::optional<InstalledPack> satpixie = ShaderPacks::ReadMarker(root.GetPath(), "satpixie-crt");
  ASSERT_TRUE(satpixie.has_value());
  ShaderPacks::RemoveFiles(root.GetPath(), satpixie.value());
  EXPECT_FALSE(FileSystem::DirectoryExists(root.Get("shaders_slang").c_str()));
}

TEST(ShaderPacks, StatusFollowsMarkerAndCheck)
{
  using ShaderPacks::GetPackStatus;
  const InstalledPack v118 = MakeInstalled("lola-retro", "v1.18", {});
  const InstalledPack incomplete = MakeInstalled("lola-retro", "", {});

  EXPECT_EQ(GetPackStatus(std::nullopt, Latest("v1.19")).state, PackState::NotInstalled);
  EXPECT_EQ(GetPackStatus(v118, Latest("v1.18")).state, PackState::Installed);
  EXPECT_EQ(GetPackStatus(v118, Latest("v1.19")).state, PackState::UpdateAvailable);
  EXPECT_EQ(GetPackStatus(incomplete, Latest("v1.19")).state, PackState::Incomplete);
  EXPECT_EQ(GetPackStatus(incomplete, std::nullopt).state, PackState::Incomplete);
  EXPECT_TRUE(GetPackStatus(v118, Latest("v1.19")).checked);

  EXPECT_EQ(ShaderPacks::DescribePackStatus(GetPackStatus(std::nullopt, Latest("v1.19"))), "Not installed");
  EXPECT_EQ(ShaderPacks::DescribePackStatus(GetPackStatus(v118, Latest("v1.18"))), "Installed v1.18");
  EXPECT_EQ(ShaderPacks::DescribePackStatus(GetPackStatus(v118, Latest("v1.19"))), "Update available: v1.18 → v1.19");
  EXPECT_EQ(ShaderPacks::DescribePackStatus(GetPackStatus(incomplete, Latest("v1.19"))), "Incomplete: install it again");
}

TEST(ShaderPacks, StatusWithoutACheckSaysSo)
{
  using ShaderPacks::GetPackStatus;
  const ShaderPacks::PackStatus installed =
    GetPackStatus(MakeInstalled("shaders_slang", "3685eda59ffd", {}), std::nullopt);
  EXPECT_EQ(installed.state, PackState::Installed);
  EXPECT_FALSE(installed.checked);
  EXPECT_EQ(ShaderPacks::DescribePackStatus(installed), "Installed 3685eda59ffd (couldn't check for updates)");
  EXPECT_EQ(ShaderPacks::DescribePackStatus(GetPackStatus(std::nullopt, std::nullopt)), "Not installed");
}
