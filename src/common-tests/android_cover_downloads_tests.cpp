// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/android_cover_downloads.h"

#include "common/path.h"

#include <gtest/gtest.h>

#include <algorithm>
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

const std::vector<u8> JPEG = {0xff, 0xd8, 0xff, 0xe0, 0, 0x10};
const std::vector<u8> PNG = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0};
const std::vector<u8> WEBP = {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
const std::vector<u8> HTML = {'<', '!', 'd', 'o', 'c', 't', 'y', 'p', 'e', '>'};

Response Image(std::vector<u8> data = JPEG)
{
  return Response{200, std::move(data)};
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
      return (it != answers.end()) ? it->second : Response{404, HTML};
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
  // By the image's own bytes, whatever the server called it.
  EXPECT_EQ(CoverExtension(JPEG), "jpg");
  EXPECT_EQ(CoverExtension(PNG), "png");
  EXPECT_EQ(CoverExtension(WEBP), "webp");
  EXPECT_EQ(CoverExtension(HTML), std::nullopt);
  EXPECT_EQ(CoverExtension(std::vector<u8>{'G', 'I', 'F', '8', '9', 'a'}), std::nullopt);
  EXPECT_EQ(CoverExtension(std::vector<u8>{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'}), std::nullopt);
  EXPECT_EQ(CoverExtension(std::vector<u8>{0xff, 0xd8}), std::nullopt);
  EXPECT_EQ(CoverExtension(std::vector<u8>{}), std::nullopt);
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

TEST(AndroidCoverDownloads, CoverFileNameIsPortable)
{
  // Names the data folder's storage and Windows can hold, whatever the platform this runs on.
  EXPECT_EQ(CoverFileName(Game("SLUS-00001", "What?"), false, "jpg"), "SLUS-00001.jpg");
  EXPECT_EQ(CoverFileName(Game("SLUS-00001", "Say \"Hi\""), false, "jpg"), "SLUS-00001.jpg");
  EXPECT_EQ(CoverFileName(Game("SLUS-00001", "A<B>C|D"), false, "jpg"), "SLUS-00001.jpg");
  EXPECT_EQ(CoverFileName(Game("SLUS-00001", "Back\\slash"), false, "jpg"), "SLUS-00001.jpg");
  EXPECT_EQ(CoverFileName(Game("SLUS-00001", "Ends with a dot."), false, "jpg"), "SLUS-00001.jpg");
  EXPECT_EQ(CoverFileName(Game("SLUS-00001", "Ends with a space "), false, "jpg"), "SLUS-00001.jpg");
  EXPECT_EQ(CoverFileName(Game("SLUS-00001", "Tab\there"), false, "jpg"), "SLUS-00001.jpg");
  EXPECT_EQ(CoverFileName(Game("SLUS-00001", "Crash Bandicoot 3 - Warped (USA) [!]"), false, "jpg"),
            "Crash Bandicoot 3 - Warped (USA) [!].jpg");
}

TEST(AndroidCoverDownloads, CoverFileNameNoSerial)
{
  EXPECT_EQ(CoverFileName(Game("", "Demo"), true, "png"), "Demo.png");
  EXPECT_EQ(CoverFileName(Game("", "A:B", "ab"), true, "png"), "ab.png");
  EXPECT_EQ(CoverFileName(Game("", "A:B", ""), true, "png"), std::nullopt);
  EXPECT_EQ(CoverFileName(Game("", "", ""), false, "png"), std::nullopt);
}

TEST(AndroidCoverDownloads, CoverFileNameHashKeyIsNoSerial)
{
  // The core's ID for a file without a serial: saved by the title, as a game without a serial is.
  EXPECT_EQ(CoverFileName(Game("HASH-74D2E3F1A0B9C8D7", "GAME C"), true, "png"), "GAME C.png");
  EXPECT_EQ(CoverFileName(Game("HASH-74D2E3F1A0B9C8D7", "A:B", ""), true, "png"), "HASH-74D2E3F1A0B9C8D7.png");
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
  fake.answers["http://a/A.jpg"] = Image(HTML);
  fake.answers["http://b/A.jpg"] = Image(PNG);
  const std::vector<Target> targets = {Game("A", "A")};
  const std::vector<std::string> sources = {"http://a/${serial}.jpg", "http://b/${serial}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  EXPECT_EQ(summary.found, 1u);
  EXPECT_EQ(fake.saved, (std::vector<std::string>{"A.png"}));
}

TEST(AndroidCoverDownloads, RunSkipsAnEmptyAnswer)
{
  FakeHooks fake;
  fake.answers["http://a/A.jpg"] = Response{200, {}};
  const std::vector<Target> targets = {Game("A", "A")};
  const std::vector<std::string> sources = {"http://a/${serial}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  EXPECT_EQ(summary.missing, 1u);
  EXPECT_TRUE(fake.saved.empty());
}

TEST(AndroidCoverDownloads, RunSkipsASourceWhoseVariableIsEmpty)
{
  FakeHooks fake;
  const std::vector<Target> targets = {Game("", "Demo", "demo", ""), Game("HASH-74D2E3F1A0B9C8D7", "Other")};
  const std::vector<std::string> sources = {"http://a/${serial}.jpg", "http://b/${filetitle}.jpg",
                                            "http://c/${title}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  // No serial (or only the core's HASH- ID), no file title: only the title source is asked.
  EXPECT_EQ(fake.fetched, (std::vector<std::string>{"http://c/Demo.jpg", "http://c/Other.jpg"}));
  EXPECT_EQ(summary.missing, 2u);
}

TEST(AndroidCoverDownloads, RunWithNoSourceForAGameCountsItMissing)
{
  FakeHooks fake;
  const std::vector<Target> targets = {Game("", "Demo")};
  const std::vector<std::string> sources = {"http://a/${serial}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  EXPECT_TRUE(fake.fetched.empty());
  EXPECT_EQ(summary.missing, 1u);
  EXPECT_EQ(summary.unreachable, 0u);
}

TEST(AndroidCoverDownloads, RunDropsASourceThatStopsAnswering)
{
  FakeHooks fake;
  std::vector<Target> targets;
  for (int i = 1; i <= 5; i++)
  {
    targets.push_back(Game("G" + std::to_string(i), "G"));
    fake.answers["http://a/G" + std::to_string(i) + ".jpg"] = Response{-2, {}};
  }
  const std::vector<std::string> sources = {"http://a/${serial}.jpg", "http://b/${serial}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  // Three games in a row without an answer from a, then only b is asked; b answers (404), so they had none.
  EXPECT_EQ(std::count_if(fake.fetched.begin(), fake.fetched.end(),
                          [](const std::string& url) { return url.starts_with("http://a/"); }),
            3);
  EXPECT_EQ(summary.missing, 5u);
  EXPECT_EQ(summary.unreachable, 0u);
}

TEST(AndroidCoverDownloads, RunWithEverySourceDroppedCountsTheRestUnreachable)
{
  FakeHooks fake;
  std::vector<Target> targets;
  for (int i = 1; i <= 5; i++)
  {
    targets.push_back(Game("G" + std::to_string(i), "G"));
    fake.answers["http://a/G" + std::to_string(i) + ".jpg"] = Response{-1, {}};
  }
  const std::vector<std::string> sources = {"http://a/${serial}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  EXPECT_EQ(fake.fetched.size(), 3u);
  EXPECT_EQ(summary.unreachable, 5u);
  EXPECT_EQ(summary.total, 5u);
}

TEST(AndroidCoverDownloads, AnAnswerResetsASourcesCount)
{
  FakeHooks fake;
  std::vector<Target> targets;
  for (int i = 1; i <= 6; i++)
  {
    targets.push_back(Game("G" + std::to_string(i), "G"));
    // Two without an answer, then one that answers (404), then two more: never three in a row.
    if (i != 3)
      fake.answers["http://a/G" + std::to_string(i) + ".jpg"] = Response{-1, {}};
  }
  const std::vector<std::string> sources = {"http://a/${serial}.jpg"};

  AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  EXPECT_EQ(fake.fetched.size(), 6u);
}

TEST(AndroidCoverDownloads, RunCountsTheLibrarysGames)
{
  FakeHooks fake;
  fake.covered = {0, 1};
  const std::vector<Target> targets = {Game("A", "A"), Game("B", "B")};
  const std::vector<std::string> sources = {"http://a/${serial}.jpg"};

  EXPECT_EQ(AndroidCoverDownloads::Run(targets, sources, true, fake.Make()).games, 2u);
  EXPECT_EQ(AndroidCoverDownloads::Run({}, sources, true, fake.Make()).games, 0u);
}

TEST(AndroidCoverDownloads, RunCountsUnreachable)
{
  {
    FakeHooks fake;
    fake.answers["http://a/A.jpg"] = Response{-1, {}};
    fake.answers["http://a/B.jpg"] = Response{-2, {}};
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
    fake.answers["http://a/A.jpg"] = Response{-1, {}};
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

TEST(AndroidCoverDownloads, RunRechecksBeforeSaving)
{
  FakeHooks fake;
  fake.answers["http://a/A.jpg"] = Image();
  // A cover comes for index 0 while its request is out: asked at the count, before the fetch, and before the save.
  fake.covered_after = {{0, 2}};
  const std::vector<Target> targets = {Game("A", "A")};
  const std::vector<std::string> sources = {"http://a/${serial}.jpg"};

  const Summary summary = AndroidCoverDownloads::Run(targets, sources, true, fake.Make());

  EXPECT_EQ(summary.total, 1u);
  EXPECT_EQ(summary.found, 0u);
  EXPECT_EQ(summary.unsaved, 0u);
  EXPECT_EQ(fake.fetched.size(), 1u);
  EXPECT_TRUE(fake.saved.empty());
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
    fake.answers["http://a/A.jpg"] = Response{-3, {}};
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
