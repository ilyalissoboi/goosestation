// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/android_disc_groups.h"

#include "common/path.h"

#include <gtest/gtest.h>

#include <string>
#include <string_view>
#include <vector>

using AndroidDiscGroups::Disc;
using AndroidDiscGroups::FileType;
using AndroidDiscGroups::FindStateDisc;
using AndroidDiscGroups::MatchStateGame;
using AndroidDiscGroups::Game;
using AndroidDiscGroups::GroupFiles;
using AndroidDiscGroups::ListedFile;
using AndroidDiscGroups::ParseDiscMarker;
using AndroidDiscGroups::ParsePlaylist;
using AndroidDiscGroups::StripDiscMarker;

namespace {

constexpr const char* FOLDER = "content://com.android.externalstorage.documents/tree/primary%3APS1/";

ListedFile DiscFile(std::string name, std::string serial, bool known = false)
{
  ListedFile file;
  file.type = FileType::Disc;
  file.path = std::string(FOLDER) + name;
  file.serial = std::move(serial);
  file.title = std::string(Path::GetFileTitle(name));
  file.known = known;
  return file;
}

ListedFile SetDisc(std::string name, std::string serial, s32 index)
{
  ListedFile file = DiscFile(std::move(name), std::move(serial), true);
  file.title = "Ace Combat 3 - Electrosphere (Japan) (Disc " + std::to_string(index + 1) + ")";
  file.disc_set_name = "Ace Combat 3 - Electrosphere (Japan)";
  file.disc_set_index = index;
  return file;
}

std::vector<std::string> DiscSerials(const Game& game)
{
  std::vector<std::string> serials;
  for (const Disc& disc : game.discs)
    serials.push_back(disc.serial);
  return serials;
}

} // namespace

TEST(AndroidDiscGroups, DiscMarkerForms)
{
  for (const std::string_view title :
       {"Foo (Disc 2)", "Foo [Disc 2]", "Foo Disc 2", "Foo - Disc 2", "Foo_Disc_2", "Foo (Disc 2 of 3)",
        "Foo [Disc 2 of 3]", "Foo Disc 2 of 3", "Foo (CD 2)", "Foo CD2", "Foo (disc 2)", "Foo (DISC 2)"})
  {
    const auto marker = ParseDiscMarker(title);
    ASSERT_TRUE(marker.has_value()) << title;
    EXPECT_EQ(marker->base, "Foo") << title;
    EXPECT_EQ(marker->number, 2u) << title;
  }

  EXPECT_EQ(ParseDiscMarker("FOO (DISC 2)")->base, "FOO");
}

TEST(AndroidDiscGroups, DiscMarkerInTheMiddle)
{
  const auto marker = ParseDiscMarker("Foo (Disc 1) (USA)");
  ASSERT_TRUE(marker.has_value());
  EXPECT_EQ(marker->base, "Foo (USA)");
  EXPECT_EQ(marker->number, 1u);
}

TEST(AndroidDiscGroups, DiscMarkerNonMatches)
{
  for (const std::string_view title : {"Discworld", "Discworld II", "Disc Jockey", "ABCD 2", "Foo (Disc)",
                                       "Foo (Disc 0)", "Foo (Disc 2", "Foo (Disc 2]", "Foo"})
  {
    EXPECT_FALSE(ParseDiscMarker(title).has_value()) << title;
  }
}

TEST(AndroidDiscGroups, StripDiscMarkerKeepsOtherTitles)
{
  EXPECT_EQ(StripDiscMarker("Foo (Disc 1)"), "Foo");
  EXPECT_EQ(StripDiscMarker("Discworld"), "Discworld");
}

TEST(AndroidDiscGroups, PlaylistEntries)
{
  const std::string m3u = std::string(FOLDER) + "AC3/ac3.m3u";
  const std::vector<std::string> entries =
    ParsePlaylist(m3u, "# comment\n\n  Disc 1.chd  \r\n/sdcard/x.chd\n");
  ASSERT_EQ(entries.size(), 2u);
  EXPECT_EQ(entries[0], std::string(FOLDER) + "AC3/Disc 1.chd");
  EXPECT_EQ(entries[1], "/sdcard/x.chd");
}

TEST(AndroidDiscGroups, GroupFilesDatabaseSet)
{
  const std::vector<ListedFile> files = {SetDisc("AC3 (Disc 2).chd", "SLPS-02021", 1),
                                         SetDisc("AC3 (Disc 1).chd", "SLPS-02020", 0)};
  const std::vector<Game> games = GroupFiles(files);
  ASSERT_EQ(games.size(), 1u);
  EXPECT_EQ(games[0].key, "SLPS-02020");
  EXPECT_EQ(games[0].title, "Ace Combat 3 - Electrosphere (Japan)");
  EXPECT_EQ(games[0].path, files[1].path);
  EXPECT_FALSE(games[0].playlist);
  EXPECT_EQ(DiscSerials(games[0]), (std::vector<std::string>{"SLPS-02020", "SLPS-02021"}));
  EXPECT_EQ(games[0].discs[0].number, 1u);
  EXPECT_EQ(games[0].discs[1].number, 2u);
  EXPECT_EQ(games[0].files.size(), 2u);
}

TEST(AndroidDiscGroups, GroupFilesSetNeedsDiscOne)
{
  const std::vector<ListedFile> files = {SetDisc("AC3 (Disc 2).chd", "SLPS-02021", 1),
                                         SetDisc("AC3 (Disc 3).chd", "SLPS-02022", 2)};
  EXPECT_EQ(GroupFiles(files).size(), 2u);
}

TEST(AndroidDiscGroups, GroupFilesSetKeepsASecondCopyApart)
{
  const std::vector<ListedFile> files = {SetDisc("AC3 (Disc 1).chd", "SLPS-02020", 0),
                                         SetDisc("AC3 (Disc 2).chd", "SLPS-02021", 1),
                                         SetDisc("AC3 (Disc 1).cue", "SLPS-02020", 0)};
  const std::vector<Game> games = GroupFiles(files);
  ASSERT_EQ(games.size(), 2u);
  EXPECT_EQ(games[0].discs.size() + games[1].discs.size(), 3u);
}

TEST(AndroidDiscGroups, GroupFilesPlaylistTakesItsDiscs)
{
  std::vector<ListedFile> files = {SetDisc("AC3 (Disc 1).chd", "SLPS-02020", 0),
                                   SetDisc("AC3 (Disc 2).chd", "SLPS-02021", 1)};
  ListedFile playlist;
  playlist.type = FileType::Playlist;
  playlist.path = std::string(FOLDER) + "AC3.m3u";
  playlist.serial = "SLPS-02020";
  playlist.title = "AC3";
  playlist.known = true;
  playlist.playlist_paths = {files[0].path, files[1].path};
  files.push_back(playlist);

  const std::vector<Game> games = GroupFiles(files);
  ASSERT_EQ(games.size(), 1u);
  EXPECT_TRUE(games[0].playlist);
  EXPECT_EQ(games[0].path, playlist.path);
  EXPECT_EQ(games[0].key, "SLPS-02020");
  EXPECT_EQ(games[0].title, "AC3");
  EXPECT_EQ(DiscSerials(games[0]), (std::vector<std::string>{"SLPS-02020", "SLPS-02021"}));
  EXPECT_EQ(games[0].files.size(), 3u);
}

TEST(AndroidDiscGroups, GroupFilesPlaylistEntryNotListed)
{
  ListedFile playlist;
  playlist.type = FileType::Playlist;
  playlist.path = std::string(FOLDER) + "Foo.m3u";
  playlist.serial = "SLUS-00001";
  playlist.title = "Foo";
  playlist.playlist_paths = {std::string(FOLDER) + "sub/Foo (Disc 1).chd"};

  const std::vector<Game> games = GroupFiles(std::vector<ListedFile>{playlist});
  ASSERT_EQ(games.size(), 1u);
  ASSERT_EQ(games[0].discs.size(), 1u);
  EXPECT_EQ(games[0].discs[0].serial, "");
  EXPECT_EQ(games[0].discs[0].title, "Foo (Disc 1)");
}

TEST(AndroidDiscGroups, GroupFilesByName)
{
  const std::vector<ListedFile> files = {DiscFile("Foo (Disc 2).chd", "SLUS-90002"),
                                         DiscFile("Foo (Disc 1).chd", "SLUS-90001")};
  const std::vector<Game> games = GroupFiles(files);
  ASSERT_EQ(games.size(), 1u);
  EXPECT_EQ(games[0].title, "Foo");
  EXPECT_EQ(games[0].key, "SLUS-90001");
  EXPECT_EQ(games[0].path, files[1].path);
  EXPECT_EQ(DiscSerials(games[0]), (std::vector<std::string>{"SLUS-90001", "SLUS-90002"}));
}

TEST(AndroidDiscGroups, GroupFilesByNameNeedsOneFolder)
{
  const std::vector<ListedFile> files = {DiscFile("a/Foo (Disc 1).chd", "SLUS-90001"),
                                         DiscFile("b/Foo (Disc 2).chd", "SLUS-90002")};
  EXPECT_EQ(GroupFiles(files).size(), 2u);
}

TEST(AndroidDiscGroups, GroupFilesByNameIgnoresCase)
{
  const std::vector<ListedFile> files = {DiscFile("Foo (Disc 1).chd", "SLUS-90001"),
                                         DiscFile("FOO (disc 2).chd", "SLUS-90002")};
  EXPECT_EQ(GroupFiles(files).size(), 1u);
}

TEST(AndroidDiscGroups, GroupFilesByNameMixedTags)
{
  const std::vector<ListedFile> files = {DiscFile("Foo (Disc 1) (USA).chd", "SLUS-90001"),
                                         DiscFile("Foo (USA) (Disc 2).chd", "SLUS-90002")};
  const std::vector<Game> games = GroupFiles(files);
  ASSERT_EQ(games.size(), 1u);
  EXPECT_EQ(games[0].title, "Foo (USA)");
}

TEST(AndroidDiscGroups, GroupFilesNameRepeatsStayApart)
{
  const std::vector<ListedFile> files = {DiscFile("Foo (Disc 1).chd", "SLUS-90001"),
                                         DiscFile("Foo (Disc 1).cue", "SLUS-90001"),
                                         DiscFile("Foo (Disc 2).chd", "SLUS-90002")};
  const std::vector<Game> games = GroupFiles(files);
  ASSERT_EQ(games.size(), 3u);
  EXPECT_EQ(games[0].title, "Foo (Disc 1)");
}

TEST(AndroidDiscGroups, GroupFilesKnownDiscsAreNotNameGrouped)
{
  const std::vector<ListedFile> files = {DiscFile("Foo (Disc 1).chd", "SLUS-90001", true),
                                         DiscFile("Foo (Disc 2).chd", "SLUS-90002", true)};
  EXPECT_EQ(GroupFiles(files).size(), 2u);
}

TEST(AndroidDiscGroups, GroupFilesKeyIsTheHashWithoutASerial)
{
  const std::vector<ListedFile> files = {DiscFile("Foo (Disc 2).iso", "HASH-00000000000000B2"),
                                         DiscFile("Foo (Disc 1).iso", "HASH-00000000000000A1")};
  const std::vector<Game> games = GroupFiles(files);
  ASSERT_EQ(games.size(), 1u);
  EXPECT_EQ(games[0].key, "HASH-00000000000000A1");
}

TEST(AndroidDiscGroups, GroupFilesOthersAreSingle)
{
  ListedFile exe;
  exe.type = FileType::Other;
  exe.path = std::string(FOLDER) + "padtest.psexe";
  exe.serial = "HASH-0123456789ABCDEF";
  exe.title = "padtest (Disc 1)";

  const std::vector<Game> games = GroupFiles(std::vector<ListedFile>{exe, DiscFile("Bar.chd", "SLUS-00002")});
  ASSERT_EQ(games.size(), 2u);
  EXPECT_EQ(games[0].title, "padtest (Disc 1)");
  EXPECT_TRUE(games[0].discs.empty());
  EXPECT_EQ(games[0].key, exe.serial);
  ASSERT_EQ(games[1].discs.size(), 1u);
  EXPECT_EQ(games[1].discs[0].number, 1u);
  EXPECT_EQ(games[1].title, "Bar");
}

TEST(AndroidDiscGroups, GroupFilesStandsForEveryFileOnce)
{
  std::vector<ListedFile> files = {SetDisc("AC3 (Disc 1).chd", "SLPS-02020", 0),
                                   SetDisc("AC3 (Disc 2).chd", "SLPS-02021", 1),
                                   DiscFile("Foo (Disc 1).chd", "SLUS-90001"),
                                   DiscFile("Foo (Disc 2).chd", "SLUS-90002"), DiscFile("Bar.chd", "SLUS-00002")};
  std::vector<int> seen(files.size(), 0);
  for (const Game& game : GroupFiles(files))
  {
    for (size_t index : game.files)
      seen[index]++;
  }
  EXPECT_EQ(seen, (std::vector<int>(files.size(), 1)));
}

namespace {

Game TwoDiscs(bool playlist)
{
  Game game;
  game.key = "SCUS-94163";
  game.playlist = playlist;
  game.path = std::string(FOLDER) + (playlist ? "ff7.m3u" : "ff7 (disc 1).chd");
  game.discs = {{std::string(FOLDER) + "ff7 (disc 1).chd", "SCUS-94163", "FF7 (Disc 1)", 1},
                {std::string(FOLDER) + "ff7 (disc 2).chd", "SCUS-94164", "FF7 (Disc 2)", 2}};
  return game;
}

} // namespace

TEST(AndroidDiscGroups, FindStateDiscBySubimage)
{
  // An M3U state names the boot disc's serial, whichever disc it was on.
  EXPECT_EQ(FindStateDisc(TwoDiscs(true), "SCUS-94163", "/home/a/FF7.M3U", 1), 2u);
  EXPECT_FALSE(FindStateDisc(TwoDiscs(true), "SCUS-94163", "/home/a/FF7.m3u", 5).has_value());
}

TEST(AndroidDiscGroups, FindStateDiscBySerial)
{
  EXPECT_EQ(FindStateDisc(TwoDiscs(false), "SCUS-94164", "content://elsewhere/tree/x/renamed.chd", 0), 2u);
}

TEST(AndroidDiscGroups, FindStateDiscByFileName)
{
  EXPECT_EQ(FindStateDisc(TwoDiscs(false), "", "/home/a/FF7 (Disc 2).CHD", 0), 2u);
}

TEST(AndroidDiscGroups, FindStateDiscNone)
{
  EXPECT_FALSE(FindStateDisc(TwoDiscs(false), "SLUS-00001", "/home/a/other.chd", 0).has_value());
}

TEST(AndroidDiscGroups, FindStateDiscSerialOnTwoDiscs)
{
  Game game = TwoDiscs(false);
  game.discs[1].serial = game.discs[0].serial;
  EXPECT_EQ(FindStateDisc(game, "SCUS-94163", "/home/a/ff7 (disc 2).chd", 0), 2u);
  EXPECT_FALSE(FindStateDisc(game, "SCUS-94163", "/home/a/unknown.chd", 0).has_value());
}

namespace {

Game Exe()
{
  Game game;
  game.key = "HASH-1";
  game.title = "padtest";
  game.path = std::string(FOLDER) + "padtest.psexe";
  return game;
}

} // namespace

TEST(AndroidDiscGroups, MatchStateGamePlaylistByName)
{
  EXPECT_EQ(MatchStateGame(TwoDiscs(true), "", "/home/a/FF7.M3U", 1), 2u);
  EXPECT_FALSE(MatchStateGame(TwoDiscs(true), "", "/home/a/Other.m3u", 1).has_value());
}

TEST(AndroidDiscGroups, MatchStateGameByKey)
{
  EXPECT_EQ(MatchStateGame(Exe(), "HASH-1", "/somewhere/else.psexe", 0), 0u);
  // A playlist state names the boot disc's serial, the key, whatever the playlist's name now.
  EXPECT_EQ(MatchStateGame(TwoDiscs(true), "SCUS-94163", "/home/a/Renamed.m3u", 1), 2u);
}

TEST(AndroidDiscGroups, MatchStateGameByDiscSerial)
{
  EXPECT_EQ(MatchStateGame(TwoDiscs(false), "SCUS-94164", "content://elsewhere/tree/x/renamed.chd", 0), 2u);
}

TEST(AndroidDiscGroups, MatchStateGameByFileName)
{
  EXPECT_EQ(MatchStateGame(TwoDiscs(false), "", "/elsewhere/FF7 (Disc 2).CHD", 0), 2u);
  EXPECT_EQ(MatchStateGame(Exe(), "", "/elsewhere/PADTEST.psexe", 0), 0u);
}

TEST(AndroidDiscGroups, MatchStateGameOtherGame)
{
  EXPECT_FALSE(MatchStateGame(TwoDiscs(false), "SLUS-00001", "/g/crash.chd", 0).has_value());
  EXPECT_FALSE(MatchStateGame(Exe(), "SLUS-00001", "/g/crash.chd", 0).has_value());
  EXPECT_FALSE(MatchStateGame(Exe(), "", "", 0).has_value());
}

TEST(AndroidDiscGroups, MatchStateGameBiosAlone)
{
  EXPECT_EQ(MatchStateGame(Game{}, "", "", 0), 0u);
  EXPECT_FALSE(MatchStateGame(Game{}, "SLUS-00001", "/g/crash.chd", 0).has_value());
}

TEST(AndroidDiscGroups, MatchStateGameFileNameNeedsNoContradictingSerial)
{
  // Another game's state whose disc file happens to share a name ("disc1.chd" in a folder per game) isn't this one's.
  EXPECT_FALSE(MatchStateGame(TwoDiscs(false), "SLUS-00001", "/elsewhere/FF7 (Disc 2).CHD", 0).has_value());
  EXPECT_FALSE(MatchStateGame(Exe(), "HASH-2", "/elsewhere/padtest.psexe", 0).has_value());
  // A game the app knows no serial of goes by the file name, whatever the state recorded.
  Game unknown = Exe();
  unknown.key.clear();
  EXPECT_EQ(MatchStateGame(unknown, "HASH-2", "/elsewhere/padtest.psexe", 0), 0u);
}
