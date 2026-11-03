// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "achievements_offline.h"
#include "achievements_offline_store.h"

#include "common/log.h"
#include "common/string_util.h"

#include <memory>
#include <optional>

Log_SetChannel(Achievements);

namespace AchievementsOffline {
namespace {

std::unique_ptr<Store> s_store;
Callbacks s_callbacks;

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

  // rcheevos's first retry is immediate, and too soon to carry "o": it's the record that's there already.
  std::optional<Record> record = RecordFor(form);
  if (!record.has_value() || FindWaiting(record.value()))
    return;

  record->time = now;
  record->title = title;
  DEV_LOG("Keeping {} {} for {} until the server has it.",
          (record->kind == Record::Kind::Unlock) ? "achievement" : "leaderboard", record->id, record->game_hash);
  s_store->Add(std::move(record.value()));
  Changed();
}

void OnAnswer(const std::map<std::string, std::string>& form, s32 status, std::string_view body)
{
  if (!s_store)
    return;

  const std::optional<Record> request = RecordFor(form);
  if (!request.has_value())
    return;

  const Record* found = FindWaiting(request.value());
  if (!found)
    return;

  const Record record = *found;
  const char* what = (record.kind == Record::Kind::Unlock) ? "achievement" : "leaderboard";
  switch (Classify(record.kind, status, body))
  {
    case Outcome::Delivered:
      DEV_LOG("The server has {} {}.", what, record.id);
      s_store->Remove(record);
      break;

    case Outcome::Refused:
      WARNING_LOG("The server refused {} {}; it's dropped.", what, record.id);
      s_store->Remove(record);
      if (s_callbacks.refused)
        s_callbacks.refused(record.title);
      break;

    case Outcome::Keep:
      s_store->MarkWaited(record);
      break;
  }
  Changed();
}

} // namespace AchievementsOffline
