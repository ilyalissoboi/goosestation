// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "jni_classes.h"

#include "achievements_bridge.h"
#include "android_host.h"
#include "bios_import.h"
#include "content_bridge.h"
#include "cover_downloads_android.h"
#include "emu_thread.h"
#include "http_downloader_android.h"
#include "jni_util.h"
#include "memcard_editor_bridge.h"
#include "memcard_sync_bridge.h"
#include "core/game_database.h"
#include "core/game_list.h"
#include "core/memory_card_image.h"
#include "core/controller.h"
#include "core/host.h"
#include "core/settings.h"
#include "core/shader_packs.h"
#include "util/http_downloader.h"
#include "util/audio_stream.h"
#include "util/platform_misc.h"
#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/progress_callback.h"
#include "common/string_util.h"
#include <android/native_window_jni.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <iterator>
#include <mutex>
#include <optional>
#include <string>
#include <tuple>
#include <unordered_map>
#include <utility>
#include <vector>

using JNIUtil::ClearException;

using JNIUtil::FromJString;

using JNIUtil::ToJString;

extern "C" {

NATIVE_LIBRARY_FUNCTION(jobject, getAchievementsAccount)(JNIEnv* env, jobject)
{
  const AchievementsBridge::Account account = AchievementsBridge::GetAccount();
  const LocalStrings strings(env, {account.username, account.display_name});
  if (!strings.ok())
    return Made<jobject>(env, nullptr, "getAchievementsAccount");

  jobject ret = env->NewObject(s_achievements_account_class, s_achievements_account_constructor,
                               static_cast<jint>(account.state), strings[0], strings[1],
                               static_cast<jint>(account.score), static_cast<jint>(account.softcore_score));
  return Made(env, ret, "getAchievementsAccount");
}

NATIVE_LIBRARY_FUNCTION(jobject, getAchievementsQueue)(JNIEnv* env, jobject)
{
  const AchievementsBridge::Queue queue = AchievementsBridge::GetQueue();
  std::vector<std::string> users;
  std::vector<jint> counts;
  for (const auto& [user, count] : queue.others)
  {
    users.push_back(user);
    counts.push_back(static_cast<jint>(count));
  }

  jobjectArray user_array = ToJStringArray(env, users);
  jintArray count_array = env->NewIntArray(static_cast<jsize>(counts.size()));
  if (!user_array || !count_array)
  {
    ClearException(env);
    return Made<jobject>(env, nullptr, "getAchievementsQueue");
  }
  env->SetIntArrayRegion(count_array, 0, static_cast<jsize>(counts.size()), counts.data());

  jobject ret = env->NewObject(s_achievements_queue_class, s_achievements_queue_constructor,
                               static_cast<jint>(queue.own), user_array, count_array);
  env->DeleteLocalRef(user_array);
  env->DeleteLocalRef(count_array);
  return Made(env, ret, "getAchievementsQueue");
}

NATIVE_LIBRARY_FUNCTION(jstring, signIn)(JNIEnv* env, jobject, jstring username, jstring password)
{
  const std::optional<std::string> error =
    AchievementsBridge::SignIn(FromJString(env, username), FromJString(env, password));
  return error.has_value() ? ToJString(env, error.value()) : nullptr;
}

NATIVE_LIBRARY_FUNCTION(void, signOut)(JNIEnv*, jobject)
{
  AchievementsBridge::SignOut();
}

NATIVE_LIBRARY_FUNCTION(jobject, getGameAchievements)(JNIEnv* env, jobject)
{
  const AchievementsBridge::GameSummary game = AchievementsBridge::GetGameSummary();
  jobject ret = env->NewObject(s_game_achievements_class, s_game_achievements_constructor,
                               static_cast<jint>(game.status), static_cast<jint>(game.unlocked),
                               static_cast<jint>(game.total), static_cast<jint>(game.points),
                               static_cast<jint>(game.total_points), static_cast<jboolean>(game.has_achievements),
                               static_cast<jboolean>(game.has_leaderboards), static_cast<jboolean>(game.hardcore),
                               static_cast<jboolean>(game.offline), static_cast<jint>(game.waiting));
  return Made(env, ret, "getGameAchievements");
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getAchievementSections)(JNIEnv* env, jobject)
{
  const std::vector<AchievementsBridge::AchievementSection> sections = AchievementsBridge::GetAchievementSections();
  jobjectArray ret = env->NewObjectArray(static_cast<jsize>(sections.size()), s_achievement_section_class, nullptr);
  if (!ret)
    return Made(env, ret, "getAchievementSections");

  for (size_t i = 0; i < sections.size(); i++)
  {
    const AchievementsBridge::AchievementSection& section = sections[i];
    jobjectArray items = env->NewObjectArray(static_cast<jsize>(section.items.size()), s_achievement_class, nullptr);
    if (!items)
      return Made(env, items, "getAchievementSections");

    for (size_t j = 0; j < section.items.size(); j++)
    {
      const AchievementsBridge::AchievementItem& item = section.items[j];
      // One that can't be made fails them all: Kotlin's arrays have no gaps.
      const LocalStrings strings(env, {item.title, item.description, item.badge_path, item.progress});
      if (!strings.ok())
        return Made<jobjectArray>(env, nullptr, "getAchievementSections");

      jobject cheevo = env->NewObject(s_achievement_class, s_achievement_constructor, static_cast<jint>(item.id),
                                      strings[0], strings[1], static_cast<jint>(item.points), strings[2],
                                      static_cast<jlong>(item.unlocked_at), strings[3],
                                      static_cast<jfloat>(item.percent));
      if (!cheevo)
        return Made<jobjectArray>(env, nullptr, "getAchievementSections");
      env->SetObjectArrayElement(items, static_cast<jsize>(j), cheevo);
      env->DeleteLocalRef(cheevo);
    }

    const LocalStrings label(env, {section.label});
    jobject jsection = label.ok() ? env->NewObject(s_achievement_section_class, s_achievement_section_constructor,
                                                    static_cast<jint>(section.bucket), label[0], items) :
                                    nullptr;
    if (!jsection)
      return Made<jobjectArray>(env, nullptr, "getAchievementSections");
    env->SetObjectArrayElement(ret, static_cast<jsize>(i), jsection);
    env->DeleteLocalRef(jsection);
    env->DeleteLocalRef(items);
  }

  return ret;
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getLeaderboards)(JNIEnv* env, jobject)
{
  const std::vector<AchievementsBridge::LeaderboardItem> boards = AchievementsBridge::GetLeaderboards();
  jobjectArray ret = env->NewObjectArray(static_cast<jsize>(boards.size()), s_leaderboard_class, nullptr);
  if (!ret)
    return Made(env, ret, "getLeaderboards");

  for (size_t i = 0; i < boards.size(); i++)
  {
    const LocalStrings strings(env, {boards[i].title, boards[i].description});
    jobject board = strings.ok() ? env->NewObject(s_leaderboard_class, s_leaderboard_constructor,
                                                  static_cast<jint>(boards[i].id), strings[0], strings[1]) :
                                   nullptr;
    if (!board)
      return Made<jobjectArray>(env, nullptr, "getLeaderboards");
    env->SetObjectArrayElement(ret, static_cast<jsize>(i), board);
    env->DeleteLocalRef(board);
  }

  return ret;
}

NATIVE_LIBRARY_FUNCTION(void, fetchLeaderboard)(JNIEnv*, jobject, jint id, jint request)
{
  AchievementsBridge::FetchLeaderboard(static_cast<u32>(id), static_cast<s32>(request));
}

NATIVE_LIBRARY_FUNCTION(jboolean, isHardcoreActive)(JNIEnv*, jobject)
{
  bool active = false;
  EmuThread::RunOnThread([&active]() { active = AchievementsBridge::IsHardcoreActive(); }, true);
  return active ? JNI_TRUE : JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(jboolean, hardcoreWouldStart)(JNIEnv*, jobject)
{
  bool would = false;
  EmuThread::RunOnThread([&would]() { would = AchievementsBridge::HardcoreWouldStart(); }, true);
  return would ? JNI_TRUE : JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(void, setAchievementsServerForTest)(JNIEnv* env, jobject, jstring url)
{
  AchievementsBridge::SetServerForTest(url ? FromJString(env, url) : std::string());
}

NATIVE_LIBRARY_FUNCTION(void, setAchievementsGameHashForTest)(JNIEnv* env, jobject, jstring hash)
{
  Achievements::SetGameHashForTest(hash ? FromJString(env, hash) : std::string());
}

NATIVE_LIBRARY_FUNCTION(void, skipAchievementsRetryWaitNative)(JNIEnv*, jobject)
{
  AchievementsBridge::SkipRetryWaitForTest();
}

NATIVE_LIBRARY_FUNCTION(void, achievementsNetworkBack)(JNIEnv*, jobject)
{
  EmuThread::RunOnThread([]() { Achievements::OnAndroidNetworkBack(); }, false);
}

} // extern "C"
