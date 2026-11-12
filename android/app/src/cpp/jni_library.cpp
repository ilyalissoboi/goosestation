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

Log_SetChannel(JNIBridge);

using JNIUtil::ClearException;

using JNIUtil::FromJString;

using JNIUtil::ToJString;

extern "C" {

NATIVE_LIBRARY_FUNCTION(void, refreshGameList)(JNIEnv*, jobject, jboolean invalidate_cache)
{
  // A refresh sees the folders as they are now, not as ContentBridge listed them a few seconds ago.
  s_content_provider.InvalidateListings();
  AndroidHost::RefreshGameList(invalidate_cache == JNI_TRUE);
}

// Each playlist's entries, by path, with the modified time of the file they were read from.
static std::mutex s_playlist_cache_mutex;
static std::unordered_map<std::string, std::pair<std::time_t, std::vector<std::string>>> s_playlist_cache;

// The discs of a game, as Java GameDisc objects: null if one couldn't be made (the exception cleared).
static jobjectArray ToGameDiscs(JNIEnv* env, const std::vector<AndroidDiscGroups::Disc>& discs)
{
  jobjectArray result = JNIUtil::ToObjectArray(
    env, s_game_disc_class, discs, [](JNIEnv* env, const AndroidDiscGroups::Disc& disc) -> jobject {
      const JNIUtil::LocalRef<jstring> path(env, ToJString(env, disc.path));
      const JNIUtil::LocalRef<jstring> serial(env, ToJString(env, disc.serial));
      const JNIUtil::LocalRef<jstring> title(env, ToJString(env, disc.title));
      jobject entry = nullptr;
      if (path && serial && title)
      {
        entry = env->NewObject(s_game_disc_class, s_game_disc_constructor, path.get(), serial.get(), title.get(),
                               static_cast<jint>(disc.number));
        if (ClearException(env))
          entry = nullptr;
      }
      return entry;
    });
  if (!result)
    ClearException(env);
  return result;
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getGameList)(JNIEnv* env, jobject)
{
  struct File
  {
    const char* region;
    s64 total_played_time;
    s64 last_played_time;
    std::time_t modified_time;
    s64 file_size;
    u64 uncompressed_size;
    GameList::EntryType type;
  };

  struct Game
  {
    AndroidDiscGroups::Game game;
    const char* region;
    s64 total_played_time;
    s64 last_played_time;
    std::string cover_path;
    s64 file_size;
    u64 uncompressed_size;
    GameList::EntryType type;
  };

  // Copied out under the lock; the playlists are read, the covers looked up, and the Java objects made, without it.
  std::vector<AndroidDiscGroups::ListedFile> listed;
  std::vector<File> files;
  {
    const auto lock = GameList::GetLock();
    const u32 count = GameList::GetEntryCount();
    listed.reserve(count);
    files.reserve(count);
    for (u32 i = 0; i < count; i++)
    {
      const GameList::Entry* entry = GameList::GetEntryByIndex(i);
      if (entry->IsDiscSet())
        continue;

      AndroidDiscGroups::ListedFile file;
      file.type = (entry->type == GameList::EntryType::Disc)     ? AndroidDiscGroups::FileType::Disc :
                  (entry->type == GameList::EntryType::Playlist) ? AndroidDiscGroups::FileType::Playlist :
                                                                   AndroidDiscGroups::FileType::Other;
      file.path = entry->path;
      file.serial = entry->serial;
      file.title = entry->title;
      file.disc_set_name = entry->disc_set_name;
      file.disc_set_index = entry->disc_set_index;
      file.known = !entry->serial.empty() && GameDatabase::GetEntryForSerial(entry->serial) != nullptr;
      listed.push_back(std::move(file));
      files.push_back({Settings::GetDiscRegionName(entry->region), static_cast<s64>(entry->total_played_time),
                       static_cast<s64>(entry->last_played_time), entry->last_modified_time, entry->file_size,
                       entry->uncompressed_size, entry->GetSortType()});
    }
  }

  // A playlist's entries, read once per version of the file, as the game list's own cache keeps what it scanned.
  {
    std::unique_lock lock(s_playlist_cache_mutex);
    for (size_t i = 0; i < listed.size(); i++)
    {
      AndroidDiscGroups::ListedFile& file = listed[i];
      if (file.type != AndroidDiscGroups::FileType::Playlist)
        continue;

      const auto cached = s_playlist_cache.find(file.path);
      if (cached != s_playlist_cache.end() && cached->second.first == files[i].modified_time)
      {
        file.playlist_paths = cached->second.second;
        continue;
      }

      if (const std::optional<std::string> text = FileSystem::ReadFileToString(file.path.c_str()); text.has_value())
      {
        file.playlist_paths = AndroidDiscGroups::ParsePlaylist(file.path, text.value());
        s_playlist_cache[file.path] = {files[i].modified_time, file.playlist_paths};
      }
    }
  }

  std::vector<AndroidDiscGroups::Game> grouped = AndroidDiscGroups::GroupFiles(listed);
  AndroidHost::SetListedGames(grouped);

  std::vector<Game> games;
  games.reserve(grouped.size());
  for (AndroidDiscGroups::Game& game : grouped)
  {
    const File& first = files[game.files.front()];
    Game shown{std::move(game), first.region, 0, 0, std::string(), 0, 0, first.type};
    // A playlist is one entry for the core, its sub-images' sizes summed into it; any other game sums its files', as
    // desktop sums a disc set's.
    for (const size_t index : shown.game.files)
    {
      if (shown.game.playlist && listed[index].type != AndroidDiscGroups::FileType::Playlist)
        continue;

      shown.file_size += files[index].file_size;
      shown.uncompressed_size += files[index].uncompressed_size;
      if (shown.game.playlist)
      {
        shown.type = files[index].type;
        break;
      }
    }

    // Play time is kept per serial, so a playlist and its first disc, which share one, count once.
    std::vector<std::string_view> counted;
    for (const size_t index : shown.game.files)
    {
      const std::string_view serial = listed[index].serial;
      if (std::find(counted.begin(), counted.end(), serial) != counted.end())
        continue;

      counted.push_back(serial);
      shown.total_played_time += files[index].total_played_time;
      shown.last_played_time = std::max(shown.last_played_time, files[index].last_played_time);
    }
    games.push_back(std::move(shown));
  }

  for (Game& game : games)
    game.cover_path = CoverDownloadsAndroid::FindLibraryCover(game.game);

  std::stable_sort(games.begin(), games.end(), [](const Game& a, const Game& b) {
    return StringUtil::Strcasecmp(a.game.title.c_str(), b.game.title.c_str()) < 0;
  });

  // Kotlin takes the array as never null: a failure gives an empty one.
  const auto empty_result = [env]() {
    jobjectArray empty = env->NewObjectArray(0, s_game_entry_class, nullptr);
    ClearException(env);
    return empty;
  };

  jobjectArray result = JNIUtil::ToObjectArray(env, s_game_entry_class, games, [](JNIEnv* env, const Game& game) -> jobject {
    const JNIUtil::LocalRef<jstring> path(env, ToJString(env, game.game.path));
    const JNIUtil::LocalRef<jstring> title(env, ToJString(env, game.game.title));
    const JNIUtil::LocalRef<jstring> serial(env, ToJString(env, game.game.key));
    const JNIUtil::LocalRef<jstring> region(env, ToJString(env, game.region));
    const JNIUtil::LocalRef<jstring> cover_path(env,
                                                game.cover_path.empty() ? nullptr : ToJString(env, game.cover_path));
    const JNIUtil::LocalRef<jobjectArray> discs(env, ToGameDiscs(env, game.game.discs));
    jobject entry = nullptr;
    if (path && title && serial && region && discs && (cover_path || game.cover_path.empty()))
    {
      entry = env->NewObject(s_game_entry_class, s_game_entry_constructor, path.get(), title.get(), serial.get(),
                             region.get(), static_cast<jlong>(game.total_played_time),
                             static_cast<jlong>(game.last_played_time), cover_path.get(), discs.get(),
                             static_cast<jboolean>(game.game.playlist), static_cast<jlong>(game.file_size),
                             static_cast<jlong>(game.uncompressed_size), static_cast<jint>(game.type));
      if (ClearException(env))
        entry = nullptr;
    }

    if (!entry)
      ERROR_LOG("Failed to pass the game list entry '{}' to Java.", game.game.path);
    return entry;
  });
  if (!result)
  {
    // Only an array that couldn't be made leaves its exception pending: an entry clears its own.
    if (ClearException(env))
      ERROR_LOG("Failed to allocate the game list's {} entries.", games.size());
    return empty_result();
  }

  return result;
}

NATIVE_LIBRARY_FUNCTION(void, setCallbacks)(JNIEnv* env, jobject, jobject callbacks)
{
  jobject new_callbacks = callbacks ? env->NewGlobalRef(callbacks) : nullptr;
  jobject old_callbacks;
  {
    std::unique_lock lock(s_callbacks_mutex);
    old_callbacks = s_callbacks;
    s_callbacks = new_callbacks;
  }

  if (old_callbacks)
    env->DeleteGlobalRef(old_callbacks);
}

} // extern "C"
