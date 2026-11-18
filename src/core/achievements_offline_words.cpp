// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "achievements_offline_words.h"

#include "host.h"

#include "common/string_util.h"

#include "fmt/format.h"

namespace AchievementsOfflineWords {

std::string CardNote()
{
  return TRANSLATE_STR("Achievements", "Sent when you're online");
}

std::string OfflineStart()
{
  return TRANSLATE_STR("Achievements", "Offline: achievements are saved and sent later (casual mode)");
}

std::string LeaderboardsOffline()
{
  return TRANSLATE_STR("Achievements", "Not available offline");
}

std::string HardcoreStaysOff()
{
  return TRANSLATE_STR("Achievements", "Hardcore stays off: this game started offline.");
}

std::string NotConnected()
{
  return TRANSLATE_STR("Achievements", "Not connected. RetroAchievements is checked again shortly.");
}

std::string OfflineSignedIn(std::string_view user)
{
  return fmt::format(TRANSLATE_FS("Achievements", "Offline: signed in as {} until this game stops"), user);
}

std::string StatusBarOffline(std::string_view user)
{
  return fmt::format(TRANSLATE_FS("Achievements", "RA: Offline as {}"), user);
}

std::string ToSendSuffix(u32 count)
{
  return (count > 0) ? fmt::format(TRANSLATE_FS("Achievements", " · {} to send"), count) : std::string();
}

std::string KeptUntilSignIn(u32 count)
{
  if (count == 0)
    return {};
  return (count == 1) ? TRANSLATE_STR("Achievements", "1 unlock is kept and sent once you sign in again") :
                        fmt::format(TRANSLATE_FS("Achievements", "{} unlocks are kept and sent once you sign in again"),
                                    count);
}

std::string Sent(u32 count)
{
  return (count == 1) ? TRANSLATE_STR("Achievements", "1 unlock sent to RetroAchievements") :
                        fmt::format(TRANSLATE_FS("Achievements", "{} unlocks sent to RetroAchievements"), count);
}

std::string Refused(std::string_view title)
{
  return fmt::format(TRANSLATE_FS("Achievements", "Couldn't send '{}': RetroAchievements refused it"), title);
}

std::vector<std::string> AccountNotes(const Achievements::AccountStatus& status)
{
  std::vector<std::string> notes;
  if (status.offline_session)
    notes.push_back(OfflineSignedIn(status.user));
  else if (status.state == Achievements::SignInState::NotConnected)
    notes.push_back(NotConnected());

  for (const auto& [user, count] : status.waiting)
  {
    const bool own = (status.state != Achievements::SignInState::SignedOut && StringUtil::EqualNoCase(user, status.user));
    if (own)
    {
      notes.push_back((count == 1) ?
                        TRANSLATE_STR("Achievements", "1 unlock waiting to be sent") :
                        fmt::format(TRANSLATE_FS("Achievements", "{} unlocks waiting to be sent"), count));
    }
    else
    {
      notes.push_back(
        (count == 1) ?
          fmt::format(TRANSLATE_FS("Achievements", "1 unlock for {0} is kept until {0} signs in again"), user) :
          fmt::format(TRANSLATE_FS("Achievements", "{0} unlocks for {1} are kept until {1} signs in again"), count,
                      user));
    }
  }
  return notes;
}

} // namespace AchievementsOfflineWords
