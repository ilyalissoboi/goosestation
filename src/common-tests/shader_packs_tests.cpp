// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "shader_pack_test_utils.h"

#include "core/shader_packs.h"

#include "util/http_downloader.h"

#include "common/error.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <functional>
#include <map>
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
  EXPECT_EQ(ShaderPacks::DescribeFailure(false, lola, ShaderPacks::STATUS_TOO_LARGE),
            "The download for lola-retro is larger than 1 GB.");
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

using ShaderPackTest::MakeZip;
using ShaderPackTest::RecordingProgress;
using ShaderPackTest::ZipEntry;

namespace {

// Serves canned answers: a URL's status and body, or a zip file it copies to the download's path. It records every
// call, can cancel the progress at a URL, and fails a download past its cap as the real ones do.
class FakeTransport final : public ShaderPacks::Transport
{
public:
  struct Answer
  {
    s32 status = 200;
    std::string body; ///< for Get
    std::string file; ///< for Download: a file to copy
    bool cancel = false; ///< cancel the progress during this request (it then reports cancelled)
  };

  std::map<std::string, Answer> answers;
  std::vector<std::string> calls;
  std::function<void(const std::string& url)> on_download;
  bool too_large = false; ///< every download goes past the cap

  s32 Get(const std::string& url, std::string* body, ProgressCallback* progress) override
  {
    calls.push_back("GET " + url);
    const auto it = answers.find(url);
    if (it == answers.end())
      return 404;
    if (it->second.cancel)
    {
      static_cast<RecordingProgress*>(progress)->Cancel();
      return HTTPDownloader::HTTP_STATUS_CANCELLED;
    }
    *body = it->second.body;
    return it->second.status;
  }

  s32 Download(const std::string& url, const std::string& path, u64 max_size, ProgressCallback* progress,
               Error* error) override
  {
    calls.push_back("DOWNLOAD " + url);
    if (on_download)
      on_download(path);
    const auto it = answers.find(url);
    if (it == answers.end())
      return 404;
    if (it->second.status != 200)
      return it->second.status;
    // A partial file is left behind, as a real transfer that stopped would leave one.
    FileSystem::WriteStringToFile(path.c_str(), "partial");
    if (it->second.cancel)
    {
      static_cast<RecordingProgress*>(progress)->Cancel();
      return HTTPDownloader::HTTP_STATUS_CANCELLED;
    }
    const std::optional<std::string> data = FileSystem::ReadFileToString(it->second.file.c_str());
    if (!data.has_value())
      return HTTPDownloader::HTTP_STATUS_ERROR;
    if (too_large || data->size() > max_size)
      return ShaderPacks::STATUS_TOO_LARGE;
    FileSystem::WriteStringToFile(path.c_str(), data.value());
    return 200;
  }
};

constexpr const char* LIBRETRO_BRANCH = "https://api.github.com/repos/libretro/slang-shaders/branches/master";
constexpr const char* CRISIS_RELEASE = "https://api.github.com/repos/RetroCrisis/Retro-Crisis-GDV-NTSC/releases/latest";
constexpr const char* LOLA_RELEASE = "https://api.github.com/repos/zoran123456/lola-retro/releases/latest";
constexpr const char* SHA = "3685eda59ffd2ebe1b12cc4640581dc23552ce88";

std::string LibretroArchiveUrl()
{
  return fmt::format("https://github.com/libretro/slang-shaders/archive/{}.zip", SHA);
}

// Answers for libretro (one preset and one shader) and Retro Crisis (one preset), with their zips under work.
void ServeLibretroAndCrisis(FakeTransport& transport, const TempDirectory& work)
{
  ASSERT_TRUE(MakeZip(work.Get("libretro.zip"), {{fmt::format("slang-shaders-{}/", SHA), ""},
                                                 {fmt::format("slang-shaders-{}/crt/a.slangp", SHA), "a"},
                                                 {fmt::format("slang-shaders-{}/crt/shaders/a.slang", SHA), "s"}}));
  ASSERT_TRUE(MakeZip(work.Get("crisis.zip"), {{"retro crisis/1080p Flat/rc.slangp", "rc"},
                                               {"__MACOSX/retro crisis/._rc.slangp", "junk"}}));
  transport.answers[LIBRETRO_BRANCH] = {200, fmt::format(R"({{"name":"master","commit":{{"sha":"{}"}}}})", SHA)};
  transport.answers[LibretroArchiveUrl()] = {200, {}, work.Get("libretro.zip")};
  transport.answers[CRISIS_RELEASE] = {200, ReleaseJson("20260927", {{"Retro.Crisis.GDV-NTSC.2026.09.27.zip", 336313}})};
  transport.answers["https://github.com/o/r/releases/download/20260927/Retro.Crisis.GDV-NTSC.2026.09.27.zip"] = {
    200, {}, work.Get("crisis.zip")};
}

std::vector<ShaderPacks::InstallResult> InstallIds(const TempDirectory& root, FakeTransport& transport,
                                                   std::vector<std::string> ids, ProgressCallback* progress = nullptr)
{
  return ShaderPacks::Install(root.GetPath(), ids, transport, progress);
}

bool AnyPartFile(const TempDirectory& root)
{
  FileSystem::FindResultsArray results;
  FileSystem::FindFiles(root.GetPath().c_str(), "*.part", FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_RECURSIVE |
                                                            FILESYSTEM_FIND_HIDDEN_FILES, &results);
  return !results.empty();
}

} // namespace

TEST(ShaderPacks, InstallsDependenciesFirst)
{
  const TempDirectory root, work;
  FakeTransport transport;
  ServeLibretroAndCrisis(transport, work);

  const auto results = InstallIds(root, transport, {"retro-crisis-gdv-ntsc"});
  ASSERT_EQ(results.size(), 2u);
  EXPECT_EQ(results[0].id, "shaders_slang");
  EXPECT_TRUE(results[0].success) << results[0].message;
  EXPECT_EQ(results[1].id, "retro-crisis-gdv-ntsc");
  EXPECT_TRUE(results[1].success) << results[1].message;
  EXPECT_EQ(transport.calls.front(), std::string("GET ") + LIBRETRO_BRANCH);

  EXPECT_EQ(root.Read("shaders_slang/crt/a.slangp"), "a");
  EXPECT_EQ(root.Read("shaders_slang/crt/shaders/a.slang"), "s");
  EXPECT_EQ(root.Read("shaders_slang/retro crisis/1080p Flat/rc.slangp"), "rc");
  EXPECT_FALSE(FileSystem::DirectoryExists(root.Get("shaders_slang/retro crisis/__MACOSX").c_str()));

  const std::optional<InstalledPack> libretro = ShaderPacks::ReadMarker(root.GetPath(), "shaders_slang");
  ASSERT_TRUE(libretro.has_value());
  EXPECT_EQ(libretro->version, "3685eda59ffd");
  EXPECT_EQ(libretro->source_url, LibretroArchiveUrl());
  EXPECT_EQ(libretro->files, (std::vector<std::string>{"shaders_slang/crt/a.slangp", "shaders_slang/crt/shaders/a.slang"}));
  EXPECT_EQ(libretro->archive_size, FileSystem::GetPathFileSize(work.Get("libretro.zip").c_str()));
  EXPECT_EQ(libretro->installed_at.size(), 20u);
  const std::optional<InstalledPack> crisis = ShaderPacks::ReadMarker(root.GetPath(), "retro-crisis-gdv-ntsc");
  ASSERT_TRUE(crisis.has_value());
  EXPECT_EQ(crisis->version, "20260927");
  EXPECT_EQ(crisis->files, (std::vector<std::string>{"shaders_slang/retro crisis/1080p Flat/rc.slangp"}));
  EXPECT_FALSE(AnyPartFile(root));
}

TEST(ShaderPacks, AnUpdateRemovesThePreviousFilesAfterTheCheck)
{
  const TempDirectory root, work;
  FakeTransport transport;
  ServeLibretroAndCrisis(transport, work);
  Install(root, MakeInstalled("shaders_slang", "000000000000", {"shaders_slang/old.slang"}));

  // A download that isn't a zip leaves the previous version as it was.
  work.Write("bad.zip", "<html>not a zip</html>");
  transport.answers[LibretroArchiveUrl()].file = work.Get("bad.zip");
  auto results = InstallIds(root, transport, {"shaders_slang"});
  ASSERT_EQ(results.size(), 1u);
  EXPECT_FALSE(results[0].success);
  EXPECT_EQ(results[0].message, "The download for libretro slang shaders isn't a zip archive.");
  EXPECT_TRUE(root.Exists("shaders_slang/old.slang"));
  EXPECT_EQ(ShaderPacks::ReadMarker(root.GetPath(), "shaders_slang")->version, "000000000000");

  transport.answers[LibretroArchiveUrl()].file = work.Get("libretro.zip");
  RecordingProgress progress;
  results = InstallIds(root, transport, {"shaders_slang"}, &progress);
  ASSERT_TRUE(results[0].success) << results[0].message;
  EXPECT_FALSE(root.Exists("shaders_slang/old.slang"));
  EXPECT_TRUE(root.Exists("shaders_slang/crt/a.slangp"));
  EXPECT_EQ(ShaderPacks::ReadMarker(root.GetPath(), "shaders_slang")->version, "3685eda59ffd");
  EXPECT_NE(std::find(progress.statuses.begin(), progress.statuses.end(), "Removing the previous libretro slang shaders…"),
            progress.statuses.end());
}

TEST(ShaderPacks, CancelDuringTheDownloadKeepsThePreviousVersion)
{
  const TempDirectory root, work;
  FakeTransport transport;
  ServeLibretroAndCrisis(transport, work);
  Install(root, MakeInstalled("shaders_slang", "000000000000", {"shaders_slang/old.slang"}));
  transport.answers[LibretroArchiveUrl()].cancel = true;

  RecordingProgress progress;
  const auto results = InstallIds(root, transport, {"shaders_slang", "lola-retro"}, &progress);
  ASSERT_EQ(results.size(), 1u);
  EXPECT_TRUE(results[0].cancelled);
  EXPECT_EQ(results[0].message, "Cancelled.");
  EXPECT_TRUE(root.Exists("shaders_slang/old.slang"));
  EXPECT_EQ(ShaderPacks::ReadMarker(root.GetPath(), "shaders_slang")->version, "000000000000");
  EXPECT_FALSE(AnyPartFile(root));
  // The cancelled pack stops the rest: lola-retro was never asked for.
  EXPECT_EQ(std::count(transport.calls.begin(), transport.calls.end(), std::string("GET ") + LOLA_RELEASE), 0);
}

TEST(ShaderPacks, AFailedDownloadLeavesNoPartFile)
{
  const TempDirectory root, work;
  FakeTransport transport;
  ServeLibretroAndCrisis(transport, work);
  // A stale part file from an earlier run goes too.
  root.Write(".shaderpacks/shaders_slang.zip.part", "stale");
  bool stale_gone = false;
  transport.on_download = [&root, &stale_gone](const std::string&) {
    stale_gone = !root.Exists(".shaderpacks/shaders_slang.zip.part");
  };

  const std::pair<s32, std::string> cases[] = {
    {HTTPDownloader::HTTP_STATUS_ERROR, "Couldn't reach GitHub: check the connection."},
    {404, "Downloading libretro slang shaders failed (HTTP 404)."},
  };
  for (const auto& [status, message] : cases)
  {
    transport.answers[LibretroArchiveUrl()].status = status;
    const auto results = InstallIds(root, transport, {"shaders_slang"});
    ASSERT_EQ(results.size(), 1u);
    EXPECT_FALSE(results[0].success);
    EXPECT_EQ(results[0].message, message);
    EXPECT_FALSE(AnyPartFile(root)) << status;
  }
  EXPECT_TRUE(stale_gone);
  EXPECT_FALSE(ShaderPacks::ReadMarker(root.GetPath(), "shaders_slang").has_value());

  // Past the cap, the player is told so.
  transport.answers[LibretroArchiveUrl()] = {200, {}, work.Get("libretro.zip")};
  transport.too_large = true;
  const auto results = InstallIds(root, transport, {"shaders_slang"});
  ASSERT_EQ(results.size(), 1u);
  EXPECT_EQ(results[0].message, "The download for libretro slang shaders is larger than 1 GB.");
  EXPECT_FALSE(AnyPartFile(root));
}

TEST(ShaderPacks, OfflineAndRateLimitedChecksSayWhy)
{
  const TempDirectory root;
  FakeTransport transport;
  transport.answers[LIBRETRO_BRANCH] = {0, ""};
  transport.answers[LOLA_RELEASE] = {403, R"({"message":"API rate limit exceeded"})"};

  Error error;
  EXPECT_FALSE(ShaderPacks::ResolveLatest(Pack("shaders_slang"), transport, nullptr, &error).has_value());
  EXPECT_EQ(error.GetDescription(), "Couldn't reach GitHub: check the connection.");
  EXPECT_FALSE(ShaderPacks::ResolveLatest(Pack("lola-retro"), transport, nullptr, &error).has_value());
  EXPECT_EQ(error.GetDescription(), "GitHub's rate limit was reached: try again later.");

  const auto results = InstallIds(root, transport, {"shaders_slang", "lola-retro"});
  ASSERT_EQ(results.size(), 2u);
  EXPECT_EQ(results[0].message, "Couldn't reach GitHub: check the connection.");
  EXPECT_EQ(results[1].message, "GitHub's rate limit was reached: try again later.");
  EXPECT_FALSE(results[0].cancelled);
  EXPECT_FALSE(ShaderPacks::ReadMarker(root.GetPath(), "lola-retro").has_value());
}

TEST(ShaderPacks, ADependencyThatFailsSkipsItsDependent)
{
  const TempDirectory root, work;
  FakeTransport transport;
  ServeLibretroAndCrisis(transport, work);
  transport.answers[LIBRETRO_BRANCH].status = 500;

  const auto results = InstallIds(root, transport, {"retro-crisis-gdv-ntsc"});
  ASSERT_EQ(results.size(), 2u);
  EXPECT_EQ(results[0].message, "Checking libretro slang shaders failed (HTTP 500).");
  EXPECT_FALSE(results[1].success);
  EXPECT_EQ(results[1].message, "Needs libretro slang shaders, which didn't install.");
  EXPECT_EQ(std::count(transport.calls.begin(), transport.calls.end(), std::string("GET ") + CRISIS_RELEASE), 0);
}

TEST(ShaderPacks, TheMarkerBeforeExtractionListsThePlannedFiles)
{
  const TempDirectory root, work;
  FakeTransport transport;
  ServeLibretroAndCrisis(transport, work);

  // Reads the marker as extraction starts, as an install killed at that moment would leave it.
  class Watcher final : public RecordingProgress
  {
  public:
    explicit Watcher(const TempDirectory& root) : m_root(root) {}
    std::optional<InstalledPack> seen;
    void SetStatusText(std::string_view text) override
    {
      RecordingProgress::SetStatusText(text);
      if (text == "Extracting libretro slang shaders…")
        seen = ShaderPacks::ReadMarker(m_root.GetPath(), "shaders_slang");
    }

  private:
    const TempDirectory& m_root;
  };

  Watcher watcher(root);
  const auto results = InstallIds(root, transport, {"shaders_slang"}, &watcher);
  ASSERT_TRUE(results[0].success) << results[0].message;
  ASSERT_TRUE(watcher.seen.has_value());
  EXPECT_EQ(watcher.seen->version, "");
  EXPECT_EQ(watcher.seen->files,
            (std::vector<std::string>{"shaders_slang/crt/a.slangp", "shaders_slang/crt/shaders/a.slang"}));
  EXPECT_EQ(ShaderPacks::GetPackStatus(watcher.seen, std::nullopt).state, PackState::Incomplete);
  EXPECT_EQ(watcher.statuses.front(), "Checking libretro slang shaders…");
  EXPECT_NE(std::find(watcher.statuses.begin(), watcher.statuses.end(), "Downloading libretro slang shaders…"),
            watcher.statuses.end());
}

TEST(ShaderPacks, ARefusedArchiveChangesNothing)
{
  const TempDirectory root, work;
  FakeTransport transport;
  ServeLibretroAndCrisis(transport, work);
  Install(root, MakeInstalled("shaders_slang", "000000000000", {"shaders_slang/old.slang"}));
  ASSERT_TRUE(MakeZip(work.Get("evil.zip"), {{"top/first.slang", "1"}, {"top/../../escape.slang", "2"}}));
  transport.answers[LibretroArchiveUrl()].file = work.Get("evil.zip");

  const auto results = InstallIds(root, transport, {"shaders_slang"});
  ASSERT_EQ(results.size(), 1u);
  EXPECT_FALSE(results[0].success);
  EXPECT_EQ(results[0].message, "Refusing unsafe archive entry 'top/../../escape.slang'.");
  EXPECT_TRUE(root.Exists("shaders_slang/old.slang"));
  EXPECT_FALSE(root.Exists("shaders_slang/first.slang"));
  EXPECT_EQ(ShaderPacks::ReadMarker(root.GetPath(), "shaders_slang")->version, "000000000000");
  EXPECT_FALSE(AnyPartFile(root));
}

TEST(ShaderPacks, AnAbortedExtractionIsIncomplete)
{
  const TempDirectory root, work;
  FakeTransport transport;
  ServeLibretroAndCrisis(transport, work);

  RecordingProgress progress;
  progress.cancel_at = 2; // after the archive's folder entry and its first file
  const auto results = InstallIds(root, transport, {"shaders_slang"}, &progress);
  ASSERT_EQ(results.size(), 1u);
  EXPECT_TRUE(results[0].cancelled);
  const std::optional<InstalledPack> marker = ShaderPacks::ReadMarker(root.GetPath(), "shaders_slang");
  ASSERT_TRUE(marker.has_value());
  EXPECT_EQ(marker->version, "");
  EXPECT_EQ(marker->files, (std::vector<std::string>{"shaders_slang/crt/a.slangp"}));
  EXPECT_EQ(ShaderPacks::GetPackStatus(marker, std::nullopt).state, PackState::Incomplete);
  EXPECT_FALSE(AnyPartFile(root));

  // Removing it deletes what landed.
  Error error;
  ASSERT_TRUE(ShaderPacks::Uninstall(root.GetPath(), "shaders_slang", &error)) << error.GetDescription();
  EXPECT_FALSE(FileSystem::DirectoryExists(root.Get("shaders_slang").c_str()));
}

TEST(ShaderPacks, LolaRetroSkipsHdr)
{
  const TempDirectory root, work;
  FakeTransport transport;
  ASSERT_TRUE(MakeZip(work.Get("lola.zip"), {{"lola-retro/curved/a.slangp", "a"},
                                             {"lola-retro/hdr/curved/a.slangp", "hdr"},
                                             {"lola-retro/shaders/crt.slang", "s"}}));
  transport.answers[LOLA_RELEASE] = {200, ReleaseJson("v1.19", {{"lola-retro-86box-v1.19.zip", 1},
                                                                {"lola-retro-v1.19.zip", 2}})};
  transport.answers["https://github.com/o/r/releases/download/v1.19/lola-retro-v1.19.zip"] = {200, {},
                                                                                             work.Get("lola.zip")};

  const auto results = InstallIds(root, transport, {"lola-retro"});
  ASSERT_TRUE(results[0].success) << results[0].message;
  EXPECT_TRUE(root.Exists("lola-retro/curved/a.slangp"));
  EXPECT_TRUE(root.Exists("lola-retro/shaders/crt.slang"));
  EXPECT_FALSE(FileSystem::DirectoryExists(root.Get("lola-retro/hdr").c_str()));
  EXPECT_EQ(ShaderPacks::ReadMarker(root.GetPath(), "lola-retro")->files,
            (std::vector<std::string>{"lola-retro/curved/a.slangp", "lola-retro/shaders/crt.slang"}));
}

TEST(ShaderPacks, SizesAreWordedForPeople)
{
  EXPECT_EQ(ShaderPacks::FormatSize(0), "");
  EXPECT_EQ(ShaderPacks::FormatSize(512), "1 KB");
  EXPECT_EQ(ShaderPacks::FormatSize(48424), "48 KB");
  EXPECT_EQ(ShaderPacks::FormatSize(370341), "362 KB");
  EXPECT_EQ(ShaderPacks::FormatSize(55018852), "52.5 MB");
  EXPECT_EQ(ShaderPacks::FormatSize(1073741824), "1024.0 MB");
}

// An update cut off while it removes the previous version reads as incomplete, and Remove finds every file.
TEST(ShaderPacks, TheMarkerBeforeRemovalListsThePreviousFiles)
{
  const TempDirectory root, work;
  FakeTransport transport;
  ServeLibretroAndCrisis(transport, work);
  Install(root, MakeInstalled("shaders_slang", "000000000000", {"shaders_slang/old.slang"}));

  class Watcher final : public RecordingProgress
  {
  public:
    explicit Watcher(const TempDirectory& root) : m_root(root) {}
    std::optional<InstalledPack> seen;
    void SetStatusText(std::string_view text) override
    {
      RecordingProgress::SetStatusText(text);
      if (text == "Removing the previous libretro slang shaders…")
        seen = ShaderPacks::ReadMarker(m_root.GetPath(), "shaders_slang");
    }

  private:
    const TempDirectory& m_root;
  };

  Watcher watcher(root);
  const auto results = InstallIds(root, transport, {"shaders_slang"}, &watcher);
  ASSERT_TRUE(results[0].success) << results[0].message;
  ASSERT_TRUE(watcher.seen.has_value());
  EXPECT_EQ(watcher.seen->version, "");
  for (const char* file : {"shaders_slang/old.slang", "shaders_slang/crt/a.slangp", "shaders_slang/crt/shaders/a.slang"})
  {
    EXPECT_NE(std::find(watcher.seen->files.begin(), watcher.seen->files.end(), file), watcher.seen->files.end())
      << file;
  }
}

TEST(ShaderPacks, CancelJustBeforeRemovalKeepsThePreviousVersion)
{
  const TempDirectory root, work;
  FakeTransport transport;
  ServeLibretroAndCrisis(transport, work);
  Install(root, MakeInstalled("shaders_slang", "000000000000", {"shaders_slang/old.slang"}));

  class CancelOnRemoval final : public RecordingProgress
  {
  public:
    void SetStatusText(std::string_view text) override
    {
      RecordingProgress::SetStatusText(text);
      if (text.starts_with("Removing the previous"))
        Cancel();
    }
  };

  CancelOnRemoval progress;
  const auto results = InstallIds(root, transport, {"shaders_slang"}, &progress);
  ASSERT_EQ(results.size(), 1u);
  EXPECT_TRUE(results[0].cancelled);
  EXPECT_TRUE(root.Exists("shaders_slang/old.slang"));
  EXPECT_FALSE(root.Exists("shaders_slang/crt/a.slangp"));
  const std::optional<InstalledPack> marker = ShaderPacks::ReadMarker(root.GetPath(), "shaders_slang");
  ASSERT_TRUE(marker.has_value());
  EXPECT_EQ(marker->version, "000000000000");
  EXPECT_EQ(marker->files, (std::vector<std::string>{"shaders_slang/old.slang"}));
  EXPECT_FALSE(AnyPartFile(root));
}

TEST(ShaderPacks, AnIncompleteDependencyIsInstalledAgain)
{
  const TempDirectory root;
  Install(root, MakeInstalled("shaders_slang", "", {"shaders_slang/half.slang"}));
  EXPECT_EQ(ShaderPacks::ExpandDependencies(root.GetPath(), std::vector<std::string>{"retro-crisis-gdv-ntsc"}),
            (std::vector<std::string>{"shaders_slang", "retro-crisis-gdv-ntsc"}));
}
