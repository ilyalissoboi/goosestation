// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/android_cover_downloads.h"

#include "common/path.h"

#include <gtest/gtest.h>

#include <map>
#include <set>
#include <string>
#include <vector>

using AndroidCoverDownloads::CoverExtension;
using AndroidCoverDownloads::CoverFileName;
using AndroidCoverDownloads::ExpandSource;
using AndroidCoverDownloads::HasVariable;
using AndroidCoverDownloads::Hooks;
using AndroidCoverDownloads::Response;
using AndroidCoverDownloads::Summary;
using AndroidCoverDownloads::Target;

namespace {

Target Game(std::string key, std::string title, std::string path_title = "", std::string display_title = "")
{
  return Target{std::move(key), std::move(title), std::move(path_title), std::move(display_title)};
}

Response Image(std::string type = "image/jpeg")
{
  return Response{200, std::move(type), {0xff, 0xd8, 0xff}};
}

// Answers each URL from a map (404 for any other), and records what the loop asked and did.
struct FakeHooks
{
  std::map<std::string, Response> answers;
  std::set<size_t> covered;
  // An index whose cover appears once the loop has asked about it this many times.
  std::map<size_t, int> covered_after;
  std::map<size_t, int> asked;
  std::vector<std::string> fetched;
  std::vector<std::string> saved;
  std::vector<std::vector<u32>> progress;
  std::vector<size_t> found;
  bool save_fails = false;
  int cancel_after_fetches = -1;

  Hooks Make()
  {
    Hooks hooks;
    hooks.has_cover = [this](size_t index) {
      const int times = ++asked[index];
      const auto after = covered_after.find(index);
      return covered.contains(index) || (after != covered_after.end() && times > after->second);
    };
    hooks.fetch = [this](const std::string& url) {
      fetched.push_back(url);
      const auto it = answers.find(url);
      return (it != answers.end()) ? it->second : Response{404, "text/html", {'n', 'o'}};
    };
    hooks.save = [this](size_t, const std::string& file_name, std::span<const u8>) -> std::optional<std::string> {
      if (save_fails)
        return std::nullopt;
      saved.push_back(file_name);
      return "covers/" + file_name;
    };
    hooks.progress = [this](size_t index, u32 position, u32 total) {
      progress.push_back({static_cast<u32>(index), position, total});
    };
    hooks.found = [this](size_t index, const std::string&) { found.push_back(index); };
    hooks.cancelled = [this]() {
      return cancel_after_fetches >= 0 && static_cast<int>(fetched.size()) >= cancel_after_fetches;
    };
    return hooks;
  }
};

} // namespace

TEST(AndroidCoverDownloads, HasVariable)
{
  EXPECT_TRUE(HasVariable("https://example.com/${serial}.jpg"));
  EXPECT_TRUE(HasVariable("https://example.com/${title}.jpg"));
  EXPECT_TRUE(HasVariable("https://example.com/${filetitle}.jpg"));
  EXPECT_FALSE(HasVariable("https://example.com/cover.jpg"));
  EXPECT_FALSE(HasVariable("https://example.com/${Serial}.jpg"));
  EXPECT_FALSE(HasVariable(""));
}

TEST(AndroidCoverDownloads, ExpandSourceEncodesEachVariable)
{
  EXPECT_EQ(ExpandSource("http://h/${serial}/${title}/${filetitle}.jpg",
                         Game("SCUS-94900", "Crash Bandicoot: Warped", "x", "Crash 3 (USA)")),
            "http://h/SCUS-94900/" + Path::URLEncode("Crash Bandicoot: Warped") + "/" +
              Path::URLEncode("Crash 3 (USA)") + ".jpg");
  EXPECT_EQ(ExpandSource("http://h/${serial}-${serial}", Game("A", "")), "http://h/A-A");
}

TEST(AndroidCoverDownloads, CoverExtension)
{
  EXPECT_EQ(CoverExtension("image/png", "http://h/a.jpg"), "png");
  EXPECT_EQ(CoverExtension("image/jpeg", "http://h/a.png"), "jpg");
  EXPECT_EQ(CoverExtension("image/webp", "http://h/a"), "webp");
  EXPECT_EQ(CoverExtension("", "http://h/a.webp?x=1"), "webp");
  EXPECT_EQ(CoverExtension("application/octet-stream", "http://h/a.PNG"), "png");
  EXPECT_EQ(CoverExtension("", "http://h/a.jpeg#top"), "jpeg");
  EXPECT_EQ(CoverExtension("", "http://h/a"), "jpg");
  EXPECT_EQ(CoverExtension("", "http://h.example.com/"), "jpg");
  EXPECT_EQ(CoverExtension("image/gif", "http://h/a.gif"), "jpg");
  EXPECT_EQ(CoverExtension("image/png; charset=binary", "http://h/a"), "png");
  EXPECT_EQ(CoverExtension("text/html", "http://h/a.jpg"), std::nullopt);
  EXPECT_EQ(CoverExtension("text/html; charset=utf-8", "http://h/a.jpg"), std::nullopt);
}

TEST(AndroidCoverDownloads, CoverFileNameBySerial)
{
  EXPECT_EQ(CoverFileName(Game("SCUS-94900", "Crash"), true, "jpg"), "SCUS-94900.jpg");
  EXPECT_EQ(CoverFileName(Game("SCUS-94900", "Crash"), false, "jpg"), "Crash.jpg");
}

TEST(AndroidCoverDownloads, CoverFileNameUnsafeTitle)
{
  // As the core's GetNewCoverImagePathForEntry: a title that can't be a file name saves by serial.
  EXPECT_EQ(CoverFileName(Game("SCPS-10091", "Ape Escape: Special?"), false, "jpg"), "SCPS-10091.jpg");
}

TEST(AndroidCoverDownloads, CoverFileNameNoSerial)
{
  EXPECT_EQ(CoverFileName(Game("", "Demo"), true, "png"), "Demo.png");
  EXPECT_EQ(CoverFileName(Game("", "A:B", "ab"), true, "png"), "ab.png");
  EXPECT_EQ(CoverFileName(Game("", "A:B", ""), true, "png"), std::nullopt);
  EXPECT_EQ(CoverFileName(Game("", "", ""), false, "png"), std::nullopt);
}

TEST(AndroidCoverDownloads, RunTriesSourcesInOrder)
{
  FakeHooks fake;
  fake.answers["http://b/SCUS-94900.jpg"] = Image();
  const std::vector<Target> targets = {Game("SCUS-94900", "Crash")};
  const std::vector<std::string> sources = {"http://a/${serial}.jpg", "http://nothing/cover.jpg",
                                            "http://b/${serial}.jpg", "http://c/${serial}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  EXPECT_EQ(summary.total, 1u);
  EXPECT_EQ(summary.found, 1u);
  EXPECT_EQ(summary.missing, 0u);
  EXPECT_FALSE(summary.cancelled);
  EXPECT_EQ(fake.fetched, (std::vector<std::string>{"http://a/SCUS-94900.jpg", "http://b/SCUS-94900.jpg"}));
  EXPECT_EQ(fake.saved, (std::vector<std::string>{"SCUS-94900.jpg"}));
  EXPECT_EQ(fake.found, (std::vector<size_t>{0}));
}

TEST(AndroidCoverDownloads, RunSkipsGamesWithACover)
{
  FakeHooks fake;
  fake.covered = {0};
  const std::vector<Target> targets = {Game("A", "A"), Game("B", "B")};
  const std::vector<std::string> sources = {"http://a/${serial}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  EXPECT_EQ(summary.total, 1u);
  EXPECT_EQ(summary.missing, 1u);
  EXPECT_EQ(fake.fetched, (std::vector<std::string>{"http://a/B.jpg"}));
  EXPECT_EQ(fake.progress, (std::vector<std::vector<u32>>{{1, 1, 1}}));
}

TEST(AndroidCoverDownloads, RunSkipsANonImageAnswer)
{
  FakeHooks fake;
  fake.answers["http://a/A.jpg"] = Image("text/html");
  fake.answers["http://b/A.jpg"] = Image("image/png");
  const std::vector<Target> targets = {Game("A", "A")};
  const std::vector<std::string> sources = {"http://a/${serial}.jpg", "http://b/${serial}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  EXPECT_EQ(summary.found, 1u);
  EXPECT_EQ(fake.saved, (std::vector<std::string>{"A.png"}));
}

TEST(AndroidCoverDownloads, RunSkipsAnEmptyAnswer)
{
  FakeHooks fake;
  fake.answers["http://a/A.jpg"] = Response{200, "image/jpeg", {}};
  const std::vector<Target> targets = {Game("A", "A")};
  const std::vector<std::string> sources = {"http://a/${serial}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  EXPECT_EQ(summary.missing, 1u);
  EXPECT_TRUE(fake.saved.empty());
}

TEST(AndroidCoverDownloads, RunCountsUnreachable)
{
  {
    FakeHooks fake;
    fake.answers["http://a/A.jpg"] = Response{-1, "", {}};
    fake.answers["http://a/B.jpg"] = Response{-2, "", {}};
    const std::vector<Target> targets = {Game("A", "A"), Game("B", "B")};
    const std::vector<std::string> sources = {"http://a/${serial}.jpg"};

    const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

    EXPECT_EQ(summary.total, 2u);
    EXPECT_EQ(summary.unreachable, 2u);
    EXPECT_EQ(summary.missing, 0u);
  }
  {
    // One source answered: the game had none there.
    FakeHooks fake;
    fake.answers["http://a/A.jpg"] = Response{-1, "", {}};
    const std::vector<Target> targets = {Game("A", "A")};
    const std::vector<std::string> sources = {"http://a/${serial}.jpg", "http://b/${serial}.jpg"};

    const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

    EXPECT_EQ(summary.missing, 1u);
    EXPECT_EQ(summary.unreachable, 0u);
  }
}

TEST(AndroidCoverDownloads, RunRechecksBeforeEachGame)
{
  FakeHooks fake;
  fake.answers["http://a/A.jpg"] = Image();
  fake.answers["http://a/B.jpg"] = Image();
  // Index 1 has no cover when the loop counts, and one when it comes to it.
  fake.covered_after = {{1, 1}};
  const std::vector<Target> targets = {Game("A", "A"), Game("B", "B")};
  const std::vector<std::string> sources = {"http://a/${serial}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  EXPECT_EQ(summary.total, 2u);
  EXPECT_EQ(summary.found, 1u);
  EXPECT_EQ(fake.fetched, (std::vector<std::string>{"http://a/A.jpg"}));
}

TEST(AndroidCoverDownloads, RunStopsWhenCancelled)
{
  {
    FakeHooks fake;
    fake.cancel_after_fetches = 1;
    const std::vector<Target> targets = {Game("A", "A"), Game("B", "B")};
    const std::vector<std::string> sources = {"http://a/${serial}.jpg", "http://b/${serial}.jpg"};

    const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

    EXPECT_TRUE(summary.cancelled);
    EXPECT_EQ(fake.fetched.size(), 1u);
    EXPECT_EQ(summary.missing, 0u);
  }
  {
    FakeHooks fake;
    fake.answers["http://a/A.jpg"] = Response{-3, "", {}};
    const std::vector<Target> targets = {Game("A", "A"), Game("B", "B")};
    const std::vector<std::string> sources = {"http://a/${serial}.jpg", "http://b/${serial}.jpg"};

    const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

    EXPECT_TRUE(summary.cancelled);
    EXPECT_EQ(fake.fetched.size(), 1u);
  }
}

TEST(AndroidCoverDownloads, RunCountsUnsaved)
{
  FakeHooks fake;
  fake.save_fails = true;
  fake.answers["http://a/A.jpg"] = Image();
  fake.answers["http://b/A.jpg"] = Image();
  const std::vector<Target> targets = {Game("A", "A")};
  const std::vector<std::string> sources = {"http://a/${serial}.jpg", "http://b/${serial}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  EXPECT_EQ(summary.unsaved, 1u);
  EXPECT_EQ(fake.fetched.size(), 1u);
  EXPECT_TRUE(fake.found.empty());
}

TEST(AndroidCoverDownloads, RunCountsAGameWithoutAFileNameAsUnsaved)
{
  FakeHooks fake;
  fake.answers["http://a/" + Path::URLEncode("A:B") + ".jpg"] = Image();
  const std::vector<Target> targets = {Game("", "A:B")};
  const std::vector<std::string> sources = {"http://a/${title}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  EXPECT_EQ(summary.unsaved, 1u);
  EXPECT_TRUE(fake.saved.empty());
}
