// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

/// What RetroAchievements hasn't got yet, and what a game needs to start without a connection, kept in one folder:
///   queue.json                     the unlocks and leaderboard entries waiting to be sent
///   users/<user>.json              an account's display name, points and avatar, from its last sign-in
///   hashes.json                    each disc hash seen online, with its game's id
///   sets/<game id>.json            a game's achievementsets answer
///   sessions/<user>/<game id>.json an account's startsession answer for a game
/// No file holds a token: a record is rebuilt into a signed request when it's sent.
namespace AchievementsOffline {

struct Record
{
  enum class Kind : u8
  {
    Unlock,
    Entry, // a leaderboard entry
  };

  Kind kind;
  std::string user;
  std::string game_hash;
  u32 id; // the achievement's or the leaderboard's
  bool hardcore;
  s32 score; // an entry's; 0 for an unlock
  s64 time;  // when it happened, in Unix seconds
  std::string title;
  bool waited; // a send failed, or it was kept from an earlier run (not saved: every record loaded has waited)

  /// The same unlock (or entry) as other: kind, user (in any case), id and mode, and an entry's score and time.
  bool IsSameAs(const Record& other) const;
};

struct UserInfo
{
  std::string user;
  std::string display_name;
  std::string avatar_url;
  u32 score;
  u32 softcore_score;
};

class Store
{
public:
  /// Creates the folder if it isn't there, and loads the queue. A queue that can't be read is renamed
  /// queue.json.damaged, and the store starts empty.
  explicit Store(std::string folder);

  const std::vector<Record>& Records() const { return m_records; }

  /// Adds a record unless the same one is there, and saves the queue before returning: false if it couldn't be saved
  /// (the record is kept in memory, and saved with the next change).
  bool Add(Record record);

  /// Takes out the record that's the same as this one, and saves the queue.
  bool Remove(const Record& record);

  /// Marks the record that's the same as this one as having waited. In memory only.
  bool MarkWaited(const Record& record);

  u32 CountFor(std::string_view user) const;

  /// Each user with records (as first written), and how many, in the order of their first record.
  std::vector<std::pair<std::string, u32>> CountsByUser() const;

  bool SaveUser(const UserInfo& info);
  std::optional<UserInfo> LoadUser(std::string_view user) const;

  bool SaveHash(std::string_view hash, u32 game_id);
  std::optional<u32> GameIdFor(std::string_view hash) const;

  bool SaveSet(u32 game_id, std::string_view body);
  std::optional<std::string> LoadSet(u32 game_id) const;

  bool SaveSession(std::string_view user, u32 game_id, std::string_view body);
  std::optional<std::string> LoadSession(std::string_view user, u32 game_id) const;

private:
  bool SaveQueue();
  void LoadHashes();
  std::string UserPath(std::string_view directory, std::string_view user, std::string_view leaf) const;

  std::string m_folder;
  std::vector<Record> m_records;
  std::map<std::string, u32> m_hashes;
};

/// A request's form fields: "a=1&b=x%20y" gives a = "1" and b = "x y".
std::map<std::string, std::string> ParseForm(std::string_view form);

/// What a send's answer means for its record.
enum class Outcome : u8
{
  Delivered, // the server has it (accepted, or an unlock it already had)
  Keep,      // no answer, or one worth trying again
  Refused,   // the server won't take it
};
Outcome Classify(Record::Kind kind, s32 status, std::string_view body);

/// A login2 answer for the saved account, with the token the core holds in memory. Never stored.
std::string BuildLoginAnswer(const UserInfo& info, std::string_view token);

/// The saved startsession answer, with each waiting unlock added (a hardcore one to both lists) unless it's there,
/// and the server's time set to now. Without a saved answer (empty), one with only the waiting unlocks.
std::string BuildSessionAnswer(std::string_view saved, const std::vector<Record>& waiting, s64 now);

} // namespace AchievementsOffline
