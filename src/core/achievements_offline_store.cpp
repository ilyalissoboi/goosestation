// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "achievements_offline_store.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/json_reader.h"
#include "common/log.h"
#include "common/path.h"
#include "common/string_util.h"

#include "rc_api_runtime.h"
#include "rc_api_user.h"
#include "rc_error.h"

#include <algorithm>
#include <cstdlib>

Log_SetChannel(Achievements);

namespace AchievementsOffline {
namespace {

constexpr s64 FORMAT_VERSION = 1;
constexpr const char* QUEUE_FILE = "queue.json";
constexpr const char* HASHES_FILE = "hashes.json";

using Json = JsonReader::Value;

std::optional<Json> ParseJson(std::string_view text)
{
  return JsonReader::Parse(text);
}

void AppendJsonString(std::string* out, std::string_view value)
{
  JsonReader::AppendString(out, value);
}

std::string LowerName(std::string_view user)
{
  // A file name for a user name: RetroAchievements' are letters and digits, anything else is replaced.
  std::string name;
  name.reserve(user.size());
  for (const char c : user)
  {
    const char lower = (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c;
    name.push_back(((lower >= 'a' && lower <= 'z') || (lower >= '0' && lower <= '9') || lower == '_' || lower == '-') ?
                     lower :
                     '_');
  }
  return name;
}

bool WriteFile(const std::string& path, std::string_view contents)
{
  Error error;
  if (!FileSystem::WriteAtomicRenamedFile(path, contents.data(), contents.size(), &error))
  {
    ERROR_LOG("Couldn't save {}: {}", Path::GetFileName(path), error.GetDescription());
    return false;
  }
  return true;
}

// Reads one of the store's files: its object, if it's there, reads as JSON and has this format's version. A file that
// is there but can't be read so is renamed <name>.damaged, replacing an older one.
// The name a damaged file is set aside as, beside any set aside before it: <name>.damaged, then .damaged-2, ...
std::string SetAsideName(const std::string& path)
{
  std::string name = path + ".damaged";
  for (u32 i = 2; FileSystem::FileExists(name.c_str()); i++)
    name = fmt::format("{}.damaged-{}", path, i);
  return name;
}

std::optional<Json> ReadFile(const std::string& path)
{
  if (!FileSystem::FileExists(path.c_str()))
    return std::nullopt;

  const std::optional<std::string> contents = FileSystem::ReadFileToString(path.c_str());
  std::optional<Json> json = contents.has_value() ? ParseJson(contents.value()) : std::nullopt;
  if (json.has_value() && json->type == Json::Type::Object && json->NumberAt("version") == FORMAT_VERSION)
    return json;

  const std::string damaged = SetAsideName(path);
  Error error;
  if (FileSystem::RenamePath(path.c_str(), damaged.c_str(), &error))
    WARNING_LOG("{} couldn't be read, so it was set aside as {}.", Path::GetFileName(path), Path::GetFileName(damaged));
  else
    ERROR_LOG("{} couldn't be read, nor set aside: {}", Path::GetFileName(path), error.GetDescription());
  return std::nullopt;
}

std::string BodyFile(std::string_view body)
{
  std::string out = fmt::format("{{\"version\":{},\"body\":", FORMAT_VERSION);
  AppendJsonString(&out, body);
  out.push_back('}');
  return out;
}

std::optional<std::string> ReadBodyFile(const std::string& path)
{
  const std::optional<Json> json = ReadFile(path);
  return json.has_value() ? json->StringAt("body") : std::nullopt;
}

bool IsRetryableStatus(s32 status)
{
  // As rcheevos's rc_client_should_retry(): the server, or what's in front of it, is busy or down.
  switch (status)
  {
    case 429:
    case 502:
    case 503:
    case 504:
    case 521:
    case 522:
    case 523:
    case 524:
    case 525:
      return true;
    default:
      return false;
  }
}

} // namespace

bool Record::IsSameAs(const Record& other) const
{
  if (kind != other.kind || id != other.id || hardcore != other.hardcore || !StringUtil::EqualNoCase(user, other.user))
    return false;
  return (kind == Kind::Unlock) || (score == other.score && time == other.time);
}

Store::Store(std::string folder) : m_folder(std::move(folder))
{
  Error error;
  if (!FileSystem::DirectoryExists(m_folder.c_str()) && !FileSystem::CreateDirectory(m_folder.c_str(), true, &error))
    ERROR_LOG("Couldn't create the offline achievements folder: {}", error.GetDescription());

  const std::string queue_path = Path::Combine(m_folder, QUEUE_FILE);
  bool skipped = false;
  if (const std::optional<Json> queue = ReadFile(queue_path); queue.has_value())
  {
    const Json* records = queue->Find("records");
    if (records && records->type == Json::Type::Array)
    {
      for (const Json& item : records->items)
      {
        const std::optional<std::string> kind = item.StringAt("kind");
        const std::optional<std::string> user = item.StringAt("user");
        const std::optional<std::string> hash = item.StringAt("hash");
        const std::optional<s64> id = item.NumberAt("id");
        const std::optional<bool> hardcore = item.BoolAt("hardcore");
        const std::optional<s64> score = item.NumberAt("score");
        const std::optional<s64> time = item.NumberAt("time");
        if (!kind.has_value() || (kind != "unlock" && kind != "entry") || !user.has_value() || !hash.has_value() ||
            !id.has_value() || id.value() <= 0 || id.value() > UINT32_MAX || !hardcore.has_value() ||
            !score.has_value() || !time.has_value())
        {
          WARNING_LOG("Skipping a queued record that can't be read.");
          skipped = true;
          continue;
        }

        m_records.push_back(Record{(kind == "unlock") ? Record::Kind::Unlock : Record::Kind::Entry,
                                   std::move(user.value()), std::move(hash.value()), static_cast<u32>(id.value()),
                                   hardcore.value(), static_cast<s32>(score.value()), time.value(),
                                   item.StringAt("title").value_or(std::string()), true});
      }
    }
  }

  // The next save drops a record that couldn't be read: the file as it was is kept beside it first.
  if (skipped)
  {
    const std::optional<std::string> contents = FileSystem::ReadFileToString(queue_path.c_str());
    const std::string copy = SetAsideName(queue_path);
    if (contents.has_value() && WriteFile(copy, contents.value()))
      WARNING_LOG("The queue as it was is kept as {}.", Path::GetFileName(copy));
  }

  LoadHashes();
}

bool Store::Add(Record record)
{
  if (std::any_of(m_records.begin(), m_records.end(), [&record](const Record& r) { return r.IsSameAs(record); }))
    return true;

  m_records.push_back(std::move(record));
  return SaveQueue();
}

bool Store::Remove(const Record& record)
{
  const auto it =
    std::find_if(m_records.begin(), m_records.end(), [&record](const Record& r) { return r.IsSameAs(record); });
  if (it == m_records.end())
    return true;

  m_records.erase(it);
  return SaveQueue();
}

bool Store::MarkWaited(const Record& record)
{
  const auto it =
    std::find_if(m_records.begin(), m_records.end(), [&record](const Record& r) { return r.IsSameAs(record); });
  if (it == m_records.end())
    return false;

  it->waited = true;
  return true;
}

u32 Store::CountFor(std::string_view user) const
{
  return static_cast<u32>(std::count_if(m_records.begin(), m_records.end(), [user](const Record& r) {
    return StringUtil::EqualNoCase(r.user, user);
  }));
}

std::vector<std::pair<std::string, u32>> Store::CountsByUser() const
{
  std::vector<std::pair<std::string, u32>> counts;
  for (const Record& record : m_records)
  {
    const auto it = std::find_if(counts.begin(), counts.end(),
                                 [&record](const auto& count) { return StringUtil::EqualNoCase(count.first, record.user); });
    if (it != counts.end())
      it->second++;
    else
      counts.emplace_back(record.user, 1);
  }
  return counts;
}

bool Store::SaveQueue()
{
  std::string out = fmt::format("{{\"version\":{},\"records\":[", FORMAT_VERSION);
  for (size_t i = 0; i < m_records.size(); i++)
  {
    const Record& record = m_records[i];
    if (i > 0)
      out.push_back(',');
    out.append(R"({"kind":)");
    AppendJsonString(&out, (record.kind == Record::Kind::Unlock) ? "unlock" : "entry");
    out.append(R"(,"user":)");
    AppendJsonString(&out, record.user);
    out.append(R"(,"hash":)");
    AppendJsonString(&out, record.game_hash);
    out.append(fmt::format(R"(,"id":{},"hardcore":{},"score":{},"time":{},"title":)", record.id,
                           record.hardcore ? "true" : "false", record.score, record.time));
    AppendJsonString(&out, record.title);
    out.push_back('}');
  }
  out.append("]}");
  return WriteFile(Path::Combine(m_folder, QUEUE_FILE), out);
}

std::string Store::UserPath(std::string_view directory, std::string_view user, std::string_view leaf) const
{
  return Path::Combine(Path::Combine(Path::Combine(m_folder, directory), LowerName(user)), leaf);
}

bool Store::SaveUser(const UserInfo& info)
{
  const std::string directory = Path::Combine(m_folder, "users");
  if (!FileSystem::EnsureDirectoryExists(directory.c_str(), true))
    return false;

  std::string out = fmt::format("{{\"version\":{},\"user\":", FORMAT_VERSION);
  AppendJsonString(&out, info.user);
  out.append(R"(,"display_name":)");
  AppendJsonString(&out, info.display_name);
  out.append(R"(,"avatar_url":)");
  AppendJsonString(&out, info.avatar_url);
  out.append(fmt::format(R"(,"score":{},"softcore_score":{}}})", info.score, info.softcore_score));
  return WriteFile(Path::Combine(directory, LowerName(info.user) + ".json"), out);
}

std::optional<UserInfo> Store::LoadUser(std::string_view user) const
{
  const std::optional<Json> json =
    ReadFile(Path::Combine(Path::Combine(m_folder, "users"), LowerName(user) + ".json"));
  if (!json.has_value())
    return std::nullopt;

  const std::optional<std::string> name = json->StringAt("user");
  const std::optional<s64> score = json->NumberAt("score");
  const std::optional<s64> softcore_score = json->NumberAt("softcore_score");
  if (!name.has_value() || !StringUtil::EqualNoCase(name.value(), user) || !score.has_value() ||
      !softcore_score.has_value())
  {
    return std::nullopt;
  }

  return UserInfo{name.value(), json->StringAt("display_name").value_or(name.value()),
                  json->StringAt("avatar_url").value_or(std::string()), static_cast<u32>(score.value()),
                  static_cast<u32>(softcore_score.value())};
}

void Store::LoadHashes()
{
  const std::optional<Json> json = ReadFile(Path::Combine(m_folder, HASHES_FILE));
  const Json* hashes = json.has_value() ? json->Find("hashes") : nullptr;
  if (!hashes || hashes->type != Json::Type::Object)
    return;

  for (size_t i = 0; i < hashes->names.size(); i++)
  {
    const Json& id = hashes->values[i];
    if (id.type == Json::Type::Number && id.number > 0 && id.number <= UINT32_MAX)
      m_hashes[hashes->names[i]] = static_cast<u32>(id.number);
  }
}

bool Store::SaveHash(std::string_view hash, u32 game_id)
{
  const auto it = m_hashes.find(std::string(hash));
  if (it != m_hashes.end() && it->second == game_id)
    return true;
  m_hashes[std::string(hash)] = game_id;

  std::string out = fmt::format("{{\"version\":{},\"hashes\":{{", FORMAT_VERSION);
  bool first = true;
  for (const auto& [name, id] : m_hashes)
  {
    if (!first)
      out.push_back(',');
    first = false;
    AppendJsonString(&out, name);
    out.append(fmt::format(":{}", id));
  }
  out.append("}}");
  return WriteFile(Path::Combine(m_folder, HASHES_FILE), out);
}

std::optional<u32> Store::GameIdFor(std::string_view hash) const
{
  const auto it = m_hashes.find(std::string(hash));
  return (it != m_hashes.end()) ? std::optional<u32>(it->second) : std::nullopt;
}

bool Store::SaveSet(u32 game_id, std::string_view body)
{
  const std::string directory = Path::Combine(m_folder, "sets");
  return FileSystem::EnsureDirectoryExists(directory.c_str(), true) &&
         WriteFile(Path::Combine(directory, fmt::format("{}.json", game_id)), BodyFile(body));
}

bool Store::HasSet(u32 game_id) const
{
  return FileSystem::FileExists(
    Path::Combine(Path::Combine(m_folder, "sets"), fmt::format("{}.json", game_id)).c_str());
}

std::optional<std::string> Store::LoadSet(u32 game_id) const
{
  return ReadBodyFile(Path::Combine(Path::Combine(m_folder, "sets"), fmt::format("{}.json", game_id)));
}

bool Store::SaveSession(std::string_view user, u32 game_id, std::string_view body)
{
  const std::string path = UserPath("sessions", user, fmt::format("{}.json", game_id));
  return FileSystem::EnsureDirectoryExists(std::string(Path::GetDirectory(path)).c_str(), true) &&
         WriteFile(path, BodyFile(body));
}

std::optional<std::string> Store::LoadSession(std::string_view user, u32 game_id) const
{
  return ReadBodyFile(UserPath("sessions", user, fmt::format("{}.json", game_id)));
}

std::map<std::string, std::string> ParseForm(std::string_view form)
{
  const auto decode = [](std::string_view text) {
    std::string out;
    out.reserve(text.size());
    for (size_t i = 0; i < text.size(); i++)
    {
      if (text[i] == '+')
      {
        out.push_back(' ');
      }
      else if (text[i] == '%' && i + 2 < text.size())
      {
        const std::optional<u32> value = StringUtil::FromChars<u32>(text.substr(i + 1, 2), 16);
        if (value.has_value())
        {
          out.push_back(static_cast<char>(value.value()));
          i += 2;
        }
        else
        {
          out.push_back('%');
        }
      }
      else
      {
        out.push_back(text[i]);
      }
    }
    return out;
  };

  std::map<std::string, std::string> fields;
  while (!form.empty())
  {
    const size_t end = form.find('&');
    const std::string_view pair = form.substr(0, end);
    const size_t equals = pair.find('=');
    if (equals != std::string_view::npos)
      fields[decode(pair.substr(0, equals))] = decode(pair.substr(equals + 1));
    if (end == std::string_view::npos)
      break;
    form.remove_prefix(end + 1);
  }
  return fields;
}

Outcome Classify(Record::Kind kind, s32 status, std::string_view body)
{
  if (status <= 0)
    return Outcome::Keep;

  rc_api_server_response_t server_response = {};
  server_response.body = body.data();
  server_response.body_length = body.size();
  server_response.http_status_code = status;

  int result;
  bool succeeded;
  if (kind == Record::Kind::Unlock)
  {
    rc_api_award_achievement_response_t response = {};
    result = rc_api_process_award_achievement_server_response(&response, &server_response);
    succeeded = (response.response.succeeded != 0);
    rc_api_destroy_award_achievement_response(&response);
  }
  else
  {
    rc_api_submit_lboard_entry_response_t response = {};
    result = rc_api_process_submit_lboard_entry_server_response(&response, &server_response);
    succeeded = (response.response.succeeded != 0);
    rc_api_destroy_submit_lboard_entry_response(&response);
  }

  // Success is delivered: an unlock the server already had comes back as success with a message.
  if (result == RC_OK && succeeded)
    return Outcome::Delivered;

  // Refused only when the server itself answers that it won't take this record: a JSON failure, not about the token
  // (a sign-in again sends it), and not a status rcheevos tries again. A page that isn't its answer (an outage, a
  // proxy, a portal) is kept: the queue is there for exactly what rcheevos gives up on.
  const std::optional<Json> json = ParseJson(body);
  if (!json.has_value() || json->type != Json::Type::Object || json->BoolAt("Success") != false)
    return Outcome::Keep;
  const std::string code = json->StringAt("Code").value_or(std::string());
  if (status == 401 || status == 403 || code == "invalid_credentials" || code == "expired_token" ||
      code == "access_denied" || IsRetryableStatus(status))
  {
    return Outcome::Keep;
  }
  return Outcome::Refused;
}

bool WillRcheevosRetry(Record::Kind kind, s32 status, std::string_view body)
{
  // ClientServerCall() hands rcheevos no answer as one to try again.
  if (status <= 0)
    return true;

  rc_api_server_response_t server_response = {};
  server_response.body = body.data();
  server_response.body_length = body.size();
  server_response.http_status_code = status;

  bool has_error;
  if (kind == Record::Kind::Unlock)
  {
    rc_api_award_achievement_response_t response = {};
    rc_api_process_award_achievement_server_response(&response, &server_response);
    has_error = (response.response.error_message != nullptr);
    rc_api_destroy_award_achievement_response(&response);
  }
  else
  {
    rc_api_submit_lboard_entry_response_t response = {};
    rc_api_process_submit_lboard_entry_server_response(&response, &server_response);
    has_error = (response.response.error_message != nullptr);
    rc_api_destroy_submit_lboard_entry_response(&response);
  }

  // As rc_client's award and entry callbacks: the server's own error on a status it doesn't retry ends the retries.
  return !(has_error && !IsRetryableStatus(status));
}

std::string BuildLoginAnswer(const UserInfo& info, std::string_view token)
{
  std::string out = R"({"Success":true,"User":)";
  AppendJsonString(&out, info.user);
  out.append(R"(,"DisplayName":)");
  AppendJsonString(&out, info.display_name);
  out.append(R"(,"Token":)");
  AppendJsonString(&out, token);
  out.append(fmt::format(R"(,"Score":{},"SoftcoreScore":{},"Messages":0,"AvatarUrl":)", info.score,
                         info.softcore_score));
  AppendJsonString(&out, info.avatar_url);
  out.push_back('}');
  return out;
}

std::optional<std::string> OfflineAnswer(const Store& store, const std::map<std::string, std::string>& form,
                                         std::string_view booting_hash, bool in_session, s64 now)
{
  const auto field = [&form](const char* name) {
    const auto it = form.find(name);
    return (it != form.end()) ? std::string_view(it->second) : std::string_view();
  };
  const std::string_view request = field("r");

  if (request == "login2")
  {
    // Only a sign-in with the token, for a game starting with its set saved.
    if (in_session || field("t").empty() || booting_hash.empty())
      return std::nullopt;
    const std::optional<u32> game_id = store.GameIdFor(booting_hash);
    if (!game_id.has_value() || !store.HasSet(game_id.value()))
      return std::nullopt;
    const std::optional<UserInfo> user = store.LoadUser(field("u"));
    if (!user.has_value())
      return std::nullopt;
    return BuildLoginAnswer(user.value(), field("t"));
  }

  if (request == "achievementsets")
  {
    const std::optional<u32> game_id =
      field("m").empty() ? StringUtil::FromChars<u32>(field("g")) : store.GameIdFor(field("m"));
    return game_id.has_value() ? store.LoadSet(game_id.value()) : std::nullopt;
  }

  if (request == "gameid")
  {
    const std::optional<u32> game_id = store.GameIdFor(field("m"));
    return game_id.has_value() ? std::optional<std::string>(fmt::format(R"({{"Success":true,"GameID":{}}})",
                                                                        game_id.value())) :
                                 std::nullopt;
  }

  if (request == "startsession")
  {
    const std::optional<u32> game_id = StringUtil::FromChars<u32>(field("g"));
    if (!game_id.has_value())
      return std::nullopt;

    std::vector<Record> waiting;
    for (const Record& record : store.Records())
    {
      if (record.kind == Record::Kind::Unlock && StringUtil::EqualNoCase(record.user, field("u")) &&
          store.GameIdFor(record.game_hash) == game_id)
      {
        waiting.push_back(record);
      }
    }
    return BuildSessionAnswer(store.LoadSession(field("u"), game_id.value()).value_or(std::string()), waiting, now);
  }

  return std::nullopt;
}

std::string BuildSessionAnswer(std::string_view saved, const std::vector<Record>& waiting, s64 now)
{
  std::vector<std::pair<u32, s64>> hardcore;
  std::vector<std::pair<u32, s64>> casual;
  if (!saved.empty())
  {
    rc_api_server_response_t server_response = {};
    server_response.body = saved.data();
    server_response.body_length = saved.size();
    server_response.http_status_code = 200;
    rc_api_start_session_response_t response = {};
    if (rc_api_process_start_session_server_response(&response, &server_response) == RC_OK &&
        response.response.succeeded)
    {
      for (u32 i = 0; i < response.num_hardcore_unlocks; i++)
        hardcore.emplace_back(response.hardcore_unlocks[i].achievement_id, response.hardcore_unlocks[i].when);
      for (u32 i = 0; i < response.num_unlocks; i++)
        casual.emplace_back(response.unlocks[i].achievement_id, response.unlocks[i].when);
    }
    rc_api_destroy_start_session_response(&response);
  }

  const auto add = [](std::vector<std::pair<u32, s64>>* list, const Record& record) {
    if (std::none_of(list->begin(), list->end(), [&record](const auto& unlock) { return unlock.first == record.id; }))
      list->emplace_back(record.id, record.time);
  };
  for (const Record& record : waiting)
  {
    if (record.kind != Record::Kind::Unlock)
      continue;
    if (record.hardcore)
      add(&hardcore, record);
    add(&casual, record);
  }

  const auto append = [](std::string* out, const std::vector<std::pair<u32, s64>>& list) {
    for (size_t i = 0; i < list.size(); i++)
      out->append(fmt::format(R"({}{{"ID":{},"When":{}}})", (i > 0) ? "," : "", list[i].first, list[i].second));
  };
  std::string out = R"({"Success":true,"HardcoreUnlocks":[)";
  append(&out, hardcore);
  out.append(R"(],"Unlocks":[)");
  append(&out, casual);
  out.append(fmt::format(R"(],"ServerNow":{}}})", now));
  return out;
}

} // namespace AchievementsOffline
