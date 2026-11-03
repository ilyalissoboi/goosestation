// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "achievements_offline.h"
#include "achievements_offline_store.h"

#include "util/http_downloader.h"

#include "common/log.h"
#include "common/string_util.h"

#include "rc_api_request.h"
#include "rc_api_runtime.h"
#include "rc_api_user.h"
#include "rc_error.h"

#include <algorithm>
#include <memory>
#include <optional>
#include <vector>

Log_SetChannel(Achievements);

namespace AchievementsOffline {
namespace {

std::unique_ptr<Store> s_store;
Callbacks s_callbacks;

// The records a request is out for, which nothing else sends: rcheevos retries its own for the rest of the run.
std::vector<Record> s_in_flight;

// For each user (lower case), how many records that waited have been delivered since the user last had none.
std::map<std::string, u32> s_sent;

// The running game started offline (InSession()).
bool s_in_session = false;

std::string_view Field(const std::map<std::string, std::string>& form, const char* name)
{
  const auto it = form.find(name);
  return (it != form.end()) ? std::string_view(it->second) : std::string_view();
}

template<typename T>
T Number(const std::map<std::string, std::string>& form, const char* name)
{
  return StringUtil::FromChars<T>(Field(form, name)).value_or(0);
}

/// The record an unlock or entry request is for, without its time or title: nullopt for any other request.
std::optional<Record> RecordFor(const std::map<std::string, std::string>& form)
{
  const std::string_view request = Field(form, "r");
  Record record = {};
  if (request == "awardachievement")
  {
    record.kind = Record::Kind::Unlock;
    record.id = Number<u32>(form, "a");
    record.hardcore = (Field(form, "h") == "1");
  }
  else if (request == "submitlbentry")
  {
    // Entries are only ever submitted in hardcore.
    record.kind = Record::Kind::Entry;
    record.id = Number<u32>(form, "i");
    record.hardcore = true;
    record.score = Number<s32>(form, "s");
  }
  else
  {
    return std::nullopt;
  }

  record.user = Field(form, "u");
  record.game_hash = Field(form, "m");
  if (record.id == 0 || record.user.empty())
    return std::nullopt;
  return record;
}

/// The waiting record a request is for: an unlock's is the same one; an entry's, the oldest with its board and score.
const Record* FindWaiting(const Record& request)
{
  for (const Record& record : s_store->Records())
  {
    if (request.kind == Record::Kind::Unlock ?
          record.IsSameAs(request) :
          (record.kind == Record::Kind::Entry && record.id == request.id && record.score == request.score &&
           StringUtil::EqualNoCase(record.user, request.user)))
    {
      return &record;
    }
  }
  return nullptr;
}

void Changed()
{
  if (s_callbacks.changed)
    s_callbacks.changed();
}

bool IsInFlight(const Record& record)
{
  return std::any_of(s_in_flight.begin(), s_in_flight.end(), [&record](const Record& r) { return r.IsSameAs(record); });
}

void SetInFlight(const Record& record, bool in_flight)
{
  std::erase_if(s_in_flight, [&record](const Record& r) { return r.IsSameAs(record); });
  if (in_flight)
    s_in_flight.push_back(record);
}

std::string LowerUser(std::string_view user)
{
  std::string lower(user);
  std::transform(lower.begin(), lower.end(), lower.begin(),
                 [](char c) { return (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c; });
  return lower;
}

/// What the server's answer means for a waiting record.
void Settle(const Record& record, Outcome outcome)
{
  const char* what = (record.kind == Record::Kind::Unlock) ? "achievement" : "leaderboard";
  switch (outcome)
  {
    case Outcome::Delivered:
    {
      DEV_LOG("The server has {} {}.", what, record.id);
      const std::string user = LowerUser(record.user);
      if (record.waited)
        s_sent[user]++;
      s_store->Remove(record);
      SetInFlight(record, false);
      if (const auto it = s_sent.find(user); it != s_sent.end() && s_store->CountFor(user) == 0)
      {
        const u32 count = it->second;
        s_sent.erase(it);
        if (s_callbacks.sent)
          s_callbacks.sent(count);
      }
      break;
    }

    case Outcome::Refused:
      WARNING_LOG("The server refused {} {}; it's dropped.", what, record.id);
      s_store->Remove(record);
      SetInFlight(record, false);
      if (s_callbacks.refused)
        s_callbacks.refused(record.title);
      break;

    case Outcome::Keep:
      s_store->MarkWaited(record);
      break;
  }
  Changed();
}

} // namespace

void Enable(std::string folder, Callbacks callbacks)
{
  s_store = std::make_unique<Store>(std::move(folder));
  s_callbacks = std::move(callbacks);
  INFO_LOG("Offline achievements are on: {} waiting.", s_store->Records().size());
}

bool IsEnabled()
{
  return static_cast<bool>(s_store);
}

void OnRequest(const std::map<std::string, std::string>& form, std::string_view title, s64 now)
{
  if (!s_store || form.contains("o"))
    return;

  std::optional<Record> record = RecordFor(form);
  if (!record.has_value())
    return;

  // rcheevos's first retry is immediate, and too soon to carry "o": it's the record that's there already. Either way,
  // rcheevos sends this one from now on.
  if (const Record* waiting = FindWaiting(record.value()))
  {
    SetInFlight(*waiting, true);
    return;
  }

  record->time = now;
  record->title = title;
  DEV_LOG("Keeping {} {} for {} until the server has it.",
          (record->kind == Record::Kind::Unlock) ? "achievement" : "leaderboard", record->id, record->game_hash);
  SetInFlight(record.value(), true);
  s_store->Add(std::move(record.value()));
  Changed();
}

/// Saves what a game's load got from the server, for a start without a connection: the set (under every disc hash seen
/// online, as the hash's game id), each hash's game, and the user's session.
void SaveForOffline(const std::map<std::string, std::string>& form, s32 status, std::string_view body)
{
  if (status != 200)
    return;

  rc_api_server_response_t server_response = {};
  server_response.body = body.data();
  server_response.body_length = body.size();
  server_response.http_status_code = status;

  const std::string_view request = Field(form, "r");
  const std::string_view hash = Field(form, "m");
  if (request == "achievementsets")
  {
    rc_api_fetch_game_sets_response_t response = {};
    if (rc_api_process_fetch_game_sets_server_response(&response, &server_response) == RC_OK &&
        response.response.succeeded && response.id != 0)
    {
      s_store->SaveSet(response.id, body);
      if (!hash.empty())
        s_store->SaveHash(hash, response.id);
    }
    rc_api_destroy_fetch_game_sets_response(&response);
  }
  else if (request == "gameid")
  {
    rc_api_resolve_hash_response_t response = {};
    if (rc_api_process_resolve_hash_server_response(&response, &server_response) == RC_OK &&
        response.response.succeeded && response.game_id != 0 && !hash.empty())
    {
      s_store->SaveHash(hash, response.game_id);
    }
    rc_api_destroy_resolve_hash_response(&response);
  }
  else if (request == "startsession")
  {
    rc_api_start_session_response_t response = {};
    const u32 game_id = Number<u32>(form, "g");
    if (rc_api_process_start_session_server_response(&response, &server_response) == RC_OK &&
        response.response.succeeded && game_id != 0 && !Field(form, "u").empty())
    {
      s_store->SaveSession(Field(form, "u"), game_id, body);
    }
    rc_api_destroy_start_session_response(&response);
  }
}

void OnAnswer(const std::map<std::string, std::string>& form, s32 status, std::string_view body)
{
  if (!s_store)
    return;

  SaveForOffline(form, status, body);

  const std::optional<Record> request = RecordFor(form);
  if (!request.has_value())
    return;

  const Record* found = FindWaiting(request.value());
  if (!found)
    return;

  const Record record = *found;
  Settle(record, Classify(record.kind, status, body));
}

void SendWaiting(HTTPDownloader& http, const rc_api_host_t* host, std::string_view user, std::string_view token,
                 s64 now)
{
  if (!s_store || user.empty() || token.empty())
    return;

  const std::string username(user);
  const std::string api_token(token);
  std::vector<Record> to_send;
  for (const Record& record : s_store->Records())
  {
    if (StringUtil::EqualNoCase(record.user, user) && !IsInFlight(record))
      to_send.push_back(record);
  }

  for (const Record& record : to_send)
  {
    // The server backdates by the record's age, up to two weeks; an older one is dated when it arrives.
    const u32 age = static_cast<u32>(std::clamp<s64>(now - record.time, 1, UINT32_MAX));
    rc_api_request_t request;
    int result;
    if (record.kind == Record::Kind::Unlock)
    {
      rc_api_award_achievement_request_t params = {};
      params.username = username.c_str();
      params.api_token = api_token.c_str();
      params.achievement_id = record.id;
      params.hardcore = record.hardcore ? 1 : 0;
      params.game_hash = record.game_hash.c_str();
      params.seconds_since_unlock = age;
      result = rc_api_init_award_achievement_request_hosted(&request, &params, host);
    }
    else
    {
      rc_api_submit_lboard_entry_request_t params = {};
      params.username = username.c_str();
      params.api_token = api_token.c_str();
      params.leaderboard_id = record.id;
      params.score = record.score;
      params.game_hash = record.game_hash.c_str();
      params.seconds_since_completion = age;
      result = rc_api_init_submit_lboard_entry_request_hosted(&request, &params, host);
    }
    if (result != RC_OK)
    {
      ERROR_LOG("Couldn't make the request for waiting {} {}.",
                (record.kind == Record::Kind::Unlock) ? "achievement" : "leaderboard", record.id);
      rc_api_destroy_request(&request);
      continue;
    }

    DEV_LOG("Sending waiting {} {}, from {} s ago.",
            (record.kind == Record::Kind::Unlock) ? "achievement" : "leaderboard", record.id, age);
    SetInFlight(record, true);
    http.CreatePostRequest(request.url, request.post_data,
                           [record](s32 status, const std::string& content_type, HTTPDownloader::Request::Data data) {
                             if (!s_store)
                               return;
                             SetInFlight(record, false);
                             const auto& records = s_store->Records();
                             const auto it = std::find_if(records.begin(), records.end(),
                                                          [&record](const Record& r) { return r.IsSameAs(record); });
                             if (it == records.end())
                               return;
                             const Record waiting = *it;
                             Settle(waiting,
                                    Classify(waiting.kind, status,
                                             std::string_view(reinterpret_cast<const char*>(data.data()),
                                                              data.size())));
                           });
    rc_api_destroy_request(&request);
  }
}

void ForgetInFlight()
{
  s_in_flight.clear();
}

bool InSession()
{
  return s_in_session;
}

void BeginSession()
{
  s_in_session = true;
}

void EndSession()
{
  s_in_session = false;
}

std::optional<std::string> AnswerOffline(const std::map<std::string, std::string>& form, std::string_view booting_hash,
                                         s64 now)
{
  return s_store ? OfflineAnswer(*s_store, form, booting_hash, s_in_session, now) : std::nullopt;
}

std::vector<std::pair<std::string, u32>> CountsByUser()
{
  return s_store ? s_store->CountsByUser() : std::vector<std::pair<std::string, u32>>();
}

u32 CountForGame(std::string_view user, std::string_view hash)
{
  if (!s_store || user.empty() || hash.empty())
    return 0;

  const std::optional<u32> game_id = s_store->GameIdFor(hash);
  return static_cast<u32>(
    std::count_if(s_store->Records().begin(), s_store->Records().end(), [&](const Record& record) {
      return StringUtil::EqualNoCase(record.user, user) &&
             (record.game_hash == hash || (game_id.has_value() && s_store->GameIdFor(record.game_hash) == game_id));
    }));
}

void RememberUser(std::string_view user, std::string_view display_name, std::string_view avatar_url, u32 score,
                  u32 softcore_score)
{
  if (s_store && !user.empty())
  {
    s_store->SaveUser(UserInfo{std::string(user), std::string(display_name), std::string(avatar_url), score,
                               softcore_score});
  }
}

} // namespace AchievementsOffline
