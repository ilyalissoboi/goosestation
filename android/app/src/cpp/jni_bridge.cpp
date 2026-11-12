// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

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

#include "jni_classes.h"

using JNIUtil::ClearException;

using JNIUtil::FromJString;

using JNIUtil::ToJString;

extern "C" {
NATIVE_LIBRARY_FUNCTION(jboolean, isMemcardSyncAvailable)(JNIEnv*, jobject)
{
  return MemcardSyncBridge::IsAvailable() ? JNI_TRUE : JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(jobject, getMemcardSyncState)(JNIEnv* env, jobject)
{
  const MemcardSyncBridge::State state = MemcardSyncBridge::GetState();
  const std::string* strings[] = {&state.remote_uri, &state.access_key_id, &state.device_name,
                                  &state.default_device_name, &state.start_error, &state.status};
  jstring jstrings[std::size(strings)] = {};
  jobject ret = nullptr;
  jobject kind = EnumValue(s_sync_status_kinds, state.kind, "SyncStatusKind");
  jobject activity = EnumValue(s_sync_activities, state.activity, "SyncActivity");
  bool made = kind && activity;
  for (size_t i = 0; i < std::size(strings); i++)
    made = made && (jstrings[i] = ToJString(env, *strings[i])) != nullptr;
  if (made)
  {
    ret = env->NewObject(s_memcard_sync_state_class, s_memcard_sync_state_constructor, kind,
                         static_cast<jboolean>(state.enabled),
                         static_cast<jboolean>(state.running), static_cast<jboolean>(state.applying), jstrings[0],
                         jstrings[1], jstrings[2], jstrings[3], static_cast<jboolean>(state.has_secret),
                         static_cast<jboolean>(state.has_passphrase), static_cast<jboolean>(state.passphrase_recorded),
                         jstrings[4], jstrings[5], activity,
                         static_cast<jint>(state.conflicts));
  }
  for (jstring str : jstrings)
  {
    if (str)
      env->DeleteLocalRef(str);
  }
  return Made(env, ret, "getMemcardSyncState");
}

NATIVE_LIBRARY_FUNCTION(void, applyMemcardSync)(JNIEnv*, jobject)
{
  MemcardSyncBridge::Apply();
}

namespace {
// A SyncVersion, or null (with no exception pending) if it can't be made.
jobject ToSyncVersion(JNIEnv* env, const MemcardSyncBridge::Version& v)
{
  jstring device = ToJString(env, v.device);
  jobject ret = device ? env->NewObject(s_sync_version_class, s_sync_version_constructor, device,
                                        static_cast<jlong>(v.modified_unix_ms), static_cast<jboolean>(v.time_known),
                                        static_cast<jint>(v.blocks), static_cast<jboolean>(v.deleted)) :
                         nullptr;
  if (!ret)
    ClearException(env);
  if (device)
    env->DeleteLocalRef(device);
  return ret;
}

// A SyncConflict, or null (with no exception pending) if it can't be made.
jobject ToSyncConflict(JNIEnv* env, const MemcardSyncBridge::Conflict& c)
{
  jstring name = ToJString(env, c.name);
  jobject local = c.local.has_value() ? ToSyncVersion(env, c.local.value()) : nullptr;
  jobjectArray remote = env->NewObjectArray(static_cast<jsize>(c.remote.size()), s_sync_version_class, nullptr);
  jobject ret = nullptr;
  bool made = name && remote && (local || !c.local.has_value());
  for (size_t i = 0; made && i < c.remote.size(); i++)
  {
    jobject v = ToSyncVersion(env, c.remote[i]);
    made = v != nullptr;
    if (v)
    {
      env->SetObjectArrayElement(remote, static_cast<jsize>(i), v);
      env->DeleteLocalRef(v);
    }
  }
  if (made)
  {
    ret = env->NewObject(s_sync_conflict_class, s_sync_conflict_constructor, static_cast<jlong>(c.id), name, local,
                         remote, static_cast<jint>(c.default_choice));
  }
  if (!ret)
    ClearException(env);
  for (jobject ref : {static_cast<jobject>(name), local, static_cast<jobject>(remote)})
  {
    if (ref)
      env->DeleteLocalRef(ref);
  }
  return ret;
}

} // namespace

NATIVE_LIBRARY_FUNCTION(jobjectArray, getMemcardSyncConflicts)(JNIEnv* env, jobject)
{
  const std::vector<MemcardSyncBridge::Conflict> conflicts = MemcardSyncBridge::GetConflicts();
  jobjectArray ret = env->NewObjectArray(static_cast<jsize>(conflicts.size()), s_sync_conflict_class, nullptr);
  if (!ret)
    return Made(env, ret, "getMemcardSyncConflicts");
  for (size_t i = 0; i < conflicts.size(); i++)
  {
    jobject c = ToSyncConflict(env, conflicts[i]);
    if (!c)
    {
      env->DeleteLocalRef(ret);
      return Made<jobjectArray>(env, nullptr, "getMemcardSyncConflicts");
    }
    env->SetObjectArrayElement(ret, static_cast<jsize>(i), c);
    env->DeleteLocalRef(c);
  }
  return ret;
}

NATIVE_LIBRARY_FUNCTION(void, resolveMemcardSyncConflict)(JNIEnv*, jobject, jlong id, jboolean keep_local,
                                                          jint remote_index)
{
  MemcardSyncBridge::ResolveConflict(static_cast<u64>(id), keep_local == JNI_TRUE,
                                     static_cast<u32>(std::max<jint>(remote_index, 0)));
}

NATIVE_LIBRARY_FUNCTION(jboolean, memcardSyncHoldCard)(JNIEnv* env, jobject, jstring relative_path, jobject action)
{
  // An exception from the action is taken off while the card is let go, which may call into Java (sync says it
  // changed), then thrown again, so it reaches the caller.
  jthrowable thrown = nullptr;
  const bool held = MemcardSyncBridge::HoldCard(FromJString(env, relative_path), [env, action, &thrown]() {
    env->CallVoidMethod(action, s_runnable_run);
    if ((thrown = env->ExceptionOccurred()) != nullptr)
      env->ExceptionClear();
  });
  if (thrown)
  {
    env->Throw(thrown);
    env->DeleteLocalRef(thrown);
  }
  return held ? JNI_TRUE : JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(void, memcardCardOpenedForTest)(JNIEnv* env, jobject, jstring path)
{
  MemcardSyncBridge::CardOpenedForTest(FromJString(env, path));
}

NATIVE_LIBRARY_FUNCTION(void, memcardCardWrittenForTest)(JNIEnv* env, jobject, jstring path)
{
  MemcardSyncBridge::CardWrittenForTest(FromJString(env, path));
}

NATIVE_LIBRARY_FUNCTION(void, memcardCardClosedForTest)(JNIEnv* env, jobject, jstring path)
{
  MemcardSyncBridge::CardClosedForTest(FromJString(env, path));
}

NATIVE_LIBRARY_FUNCTION(jboolean, writeMemoryCardSaveForTest)(JNIEnv* env, jobject, jstring path, jstring name,
                                                              jbyteArray data)
{
  std::vector<u8> bytes(static_cast<size_t>(env->GetArrayLength(data)));
  env->GetByteArrayRegion(data, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<jbyte*>(bytes.data()));
  return MemcardSyncBridge::WriteSaveForTest(FromJString(env, path), FromJString(env, name), bytes) ? JNI_TRUE :
                                                                                                       JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(jbyteArray, readMemoryCardSaveForTest)(JNIEnv* env, jobject, jstring path, jstring name)
{
  const std::optional<std::vector<u8>> data =
    MemcardSyncBridge::ReadSaveForTest(FromJString(env, path), FromJString(env, name));
  if (!data.has_value())
    return nullptr;
  jbyteArray ret = env->NewByteArray(static_cast<jsize>(data->size()));
  if (!ret)
  {
    ClearException(env);
    return nullptr;
  }
  env->SetByteArrayRegion(ret, 0, static_cast<jsize>(data->size()), reinterpret_cast<const jbyte*>(data->data()));
  return ret;
}

NATIVE_LIBRARY_FUNCTION(void, memcardSyncNow)(JNIEnv*, jobject)
{
  MemcardSyncBridge::SyncNow();
}

NATIVE_LIBRARY_FUNCTION(void, setMemcardSyncBackgrounded)(JNIEnv*, jobject, jboolean backgrounded)
{
  MemcardSyncBridge::SetBackgrounded(backgrounded == JNI_TRUE);
}

NATIVE_LIBRARY_FUNCTION(void, setMemcardSyncPeriodForTest)(JNIEnv*, jobject, jlong ms)
{
  MemcardSyncBridge::SetPeriodicSyncForTest(static_cast<u32>(std::max<jlong>(ms, 0)));
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getMemoryCardPaths)(JNIEnv* env, jobject)
{
  const std::optional<std::vector<std::string>> paths = AndroidHost::GetMemoryCardPaths();
  return paths.has_value() ? ToJStringArray(env, paths.value()) : nullptr;
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getDataFolders)(JNIEnv* env, jobject)
{
  const std::optional<std::vector<std::string>> folders = AndroidHost::GetDataFolders();
  return folders.has_value() ? ToJStringArray(env, folders.value()) : nullptr;
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getDataFiles)(JNIEnv* env, jobject)
{
  const std::optional<std::vector<std::string>> files = AndroidHost::GetDataFiles();
  return files.has_value() ? ToNonNullStringArray(env, files.value()) : nullptr;
}

NATIVE_LIBRARY_FUNCTION(jstring, getCacheFolder)(JNIEnv* env, jobject)
{
  const std::optional<std::string> folder = AndroidHost::GetCacheFolder();
  return folder.has_value() ? ToJString(env, folder.value()) : nullptr;
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, importBios)(JNIEnv* env, jobject, jstring source_tree_uri, jstring dest_dir)
{
  Error error;
  const std::optional<std::vector<std::string>> descriptions =
    BIOSImport::Import(s_content_provider, FromJString(env, source_tree_uri), FromJString(env, dest_dir), &error);
  if (!descriptions.has_value())
  {
    ERROR_LOG("BIOS import failed: {}", error.GetDescription());
    return nullptr;
  }

  jobjectArray result = env->NewObjectArray(static_cast<jsize>(descriptions->size()), s_string_class, nullptr);
  if (!result)
  {
    ClearException(env);
    return nullptr;
  }

  for (size_t i = 0; i < descriptions->size(); i++)
  {
    jstring description = ToJString(env, (*descriptions)[i]);
    if (!description)
    {
      env->DeleteLocalRef(result);
      return nullptr;
    }

    env->SetObjectArrayElement(result, static_cast<jsize>(i), description);
    env->DeleteLocalRef(description);
  }

  return result;
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getBiosImages)(JNIEnv* env, jobject)
{
  const std::optional<std::string> dir = AndroidHost::GetBIOSDirectory();
  const std::vector<BIOSImport::Image> images =
    dir.has_value() ? BIOSImport::ListImages(dir.value()) : std::vector<BIOSImport::Image>();

  // Kotlin takes the array as never null: a failure gives an empty one.
  const auto empty_result = [env]() {
    jobjectArray empty = env->NewObjectArray(0, s_bios_image_class, nullptr);
    ClearException(env);
    return empty;
  };

  jobjectArray result = env->NewObjectArray(static_cast<jsize>(images.size()), s_bios_image_class, nullptr);
  if (!result)
  {
    ClearException(env);
    return empty_result();
  }

  for (size_t i = 0; i < images.size(); i++)
  {
    const BIOSImport::Image& image = images[i];
    jstring file_name = ToJString(env, image.file_name);
    jstring description = ToJString(env, image.description);
    jstring region = ToJString(env, image.region);
    jobject entry = nullptr;
    if (file_name && description && region)
    {
      entry = env->NewObject(s_bios_image_class, s_bios_image_constructor, file_name, description, region);
      if (ClearException(env))
        entry = nullptr;
    }

    for (jobject ref : {static_cast<jobject>(file_name), static_cast<jobject>(description),
                        static_cast<jobject>(region)})
    {
      if (ref)
        env->DeleteLocalRef(ref);
    }

    if (!entry)
    {
      ERROR_LOG("Failed to pass the BIOS image '{}' to Java.", image.file_name);
      env->DeleteLocalRef(result);
      return empty_result();
    }

    env->SetObjectArrayElement(result, static_cast<jsize>(i), entry);
    env->DeleteLocalRef(entry);
  }

  return result;
}

NATIVE_LIBRARY_FUNCTION(jstring, getAutoBios)(JNIEnv* env, jobject, jstring region)
{
  const std::optional<std::string> dir = AndroidHost::GetBIOSDirectory();
  if (!dir.has_value())
    return nullptr;

  const std::optional<std::string> description = BIOSImport::FindAutomaticImage(FromJString(env, region), dir.value());
  return description.has_value() ? ToJString(env, description.value()) : nullptr;
}

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
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(discs.size()), s_game_disc_class, nullptr);
  if (!result)
  {
    ClearException(env);
    return nullptr;
  }

  for (size_t i = 0; i < discs.size(); i++)
  {
    const AndroidDiscGroups::Disc& disc = discs[i];
    jstring path = ToJString(env, disc.path);
    jstring serial = ToJString(env, disc.serial);
    jstring title = ToJString(env, disc.title);
    jobject entry = nullptr;
    if (path && serial && title)
    {
      entry = env->NewObject(s_game_disc_class, s_game_disc_constructor, path, serial, title,
                             static_cast<jint>(disc.number));
      if (ClearException(env))
        entry = nullptr;
    }

    for (jobject ref : {static_cast<jobject>(path), static_cast<jobject>(serial), static_cast<jobject>(title)})
    {
      if (ref)
        env->DeleteLocalRef(ref);
    }

    if (!entry)
    {
      env->DeleteLocalRef(result);
      return nullptr;
    }

    env->SetObjectArrayElement(result, static_cast<jsize>(i), entry);
    env->DeleteLocalRef(entry);
  }

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

  jobjectArray result = env->NewObjectArray(static_cast<jsize>(games.size()), s_game_entry_class, nullptr);
  if (!result)
  {
    ClearException(env);
    ERROR_LOG("Failed to allocate the game list's {} entries.", games.size());
    return empty_result();
  }

  for (size_t i = 0; i < games.size(); i++)
  {
    const Game& game = games[i];
    jstring path = ToJString(env, game.game.path);
    jstring title = ToJString(env, game.game.title);
    jstring serial = ToJString(env, game.game.key);
    jstring region = ToJString(env, game.region);
    jstring cover_path = game.cover_path.empty() ? nullptr : ToJString(env, game.cover_path);
    jobjectArray discs = ToGameDiscs(env, game.game.discs);
    jobject entry = nullptr;
    if (path && title && serial && region && discs && (cover_path || game.cover_path.empty()))
    {
      entry = env->NewObject(s_game_entry_class, s_game_entry_constructor, path, title, serial, region,
                             static_cast<jlong>(game.total_played_time), static_cast<jlong>(game.last_played_time),
                             cover_path, discs, static_cast<jboolean>(game.game.playlist),
                             static_cast<jlong>(game.file_size), static_cast<jlong>(game.uncompressed_size),
                             static_cast<jint>(game.type));
      if (ClearException(env))
        entry = nullptr;
    }

    for (jobject ref : {static_cast<jobject>(path), static_cast<jobject>(title), static_cast<jobject>(serial),
                        static_cast<jobject>(region), static_cast<jobject>(cover_path), static_cast<jobject>(discs)})
    {
      if (ref)
        env->DeleteLocalRef(ref);
    }

    if (!entry)
    {
      ERROR_LOG("Failed to pass the game list entry '{}' to Java.", game.game.path);
      env->DeleteLocalRef(result);
      return empty_result();
    }

    env->SetObjectArrayElement(result, static_cast<jsize>(i), entry);
    env->DeleteLocalRef(entry);
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

NATIVE_LIBRARY_FUNCTION(void, startGame)(JNIEnv* env, jobject, jstring path, jint disc, jboolean resume)
{
  AndroidHost::StartGame(FromJString(env, path), static_cast<u32>(std::max(disc, 0)), resume == JNI_TRUE);
}

NATIVE_LIBRARY_FUNCTION(void, startGameFromState)

(JNIEnv* env, jobject, jstring path, jstring key, jint slot, jboolean shared)
{
  AndroidHost::StartGameFromState(FromJString(env, path), FromJString(env, key), static_cast<s32>(slot),
                                  shared == JNI_TRUE);
}

NATIVE_LIBRARY_FUNCTION(void, shutdownGame)(JNIEnv*, jobject)
{
  AndroidHost::ShutdownGame();
}

NATIVE_LIBRARY_FUNCTION(jlongArray, getSaveStateTimes)(JNIEnv* env, jobject, jstring serial)
{
  const std::vector<s64> times = AndroidHost::GetSaveStateTimes(FromJString(env, serial));
  jlongArray result = env->NewLongArray(static_cast<jsize>(times.size()));
  if (!result)
    return Made(env, result, "getSaveStateTimes");

  std::vector<jlong> values(times.begin(), times.end());
  env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
  return result;
}

// A SaveStatePreview for Kotlin, or null.
static jobject ToSaveStatePreview(JNIEnv* env, const std::optional<AndroidHost::SaveStatePreview>& preview)
{
  if (!preview.has_value())
    return nullptr;

  jobject picture = nullptr;
  if (preview->picture.has_value())
  {
    const AndroidHost::Picture& image = preview->picture.value();
    picture = ToArgbImage(env, image.pixels.data(), image.width, image.height, true);
    if (!picture)
      return nullptr;
  }

  jstring serial = ToJString(env, preview->serial);
  jstring title = ToJString(env, preview->title);
  jstring game_path = ToJString(env, preview->game_path);
  jstring game_title = ToJString(env, preview->game_title);
  jobject result = nullptr;
  if (serial && title && game_path && game_title)
  {
    result = env->NewObject(s_save_state_preview_class, s_save_state_preview_constructor, picture,
                            static_cast<jint>(preview->disc), serial, title, game_path, game_title);
  }
  if (ClearException(env))
    result = nullptr;
  for (jobject local : {picture, static_cast<jobject>(serial), static_cast<jobject>(title),
                        static_cast<jobject>(game_path), static_cast<jobject>(game_title)})
  {
    if (local)
      env->DeleteLocalRef(local);
  }
  return result;
}

NATIVE_LIBRARY_FUNCTION(jobject, getSaveStatePreview)(JNIEnv* env, jobject, jstring serial, jint slot)
{
  return ToSaveStatePreview(env, AndroidHost::GetSaveStatePreview(FromJString(env, serial), static_cast<s32>(slot)));
}

NATIVE_LIBRARY_FUNCTION(jobject, getStatePreview)

(JNIEnv* env, jobject, jstring key, jint slot, jboolean shared, jstring game_path)
{
  return ToSaveStatePreview(env, AndroidHost::GetStatePreview(FromJString(env, key), static_cast<s32>(slot),
                                                              shared == JNI_TRUE, FromJString(env, game_path)));
}

NATIVE_LIBRARY_FUNCTION(jlongArray, getSharedStateTimes)(JNIEnv* env, jobject)
{
  const std::vector<s64> times = AndroidHost::GetSharedStateTimes();
  jlongArray result = env->NewLongArray(static_cast<jsize>(times.size()));
  if (!result)
    return Made(env, result, "getSharedStateTimes");

  std::vector<jlong> values(times.begin(), times.end());
  env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
  return result;
}

// The reason a save or load failed, or null when it worked.
static jstring ToResult(JNIEnv* env, const std::optional<std::string>& error)
{
  return error.has_value() ? ToJString(env, error.value()) : nullptr;
}

NATIVE_LIBRARY_FUNCTION(jstring, saveSharedState)(JNIEnv* env, jobject, jint slot)
{
  return ToResult(env, AndroidHost::SaveSharedState(static_cast<s32>(slot)));
}

NATIVE_LIBRARY_FUNCTION(jstring, loadSharedState)(JNIEnv* env, jobject, jint slot)
{
  return ToResult(env, AndroidHost::LoadSharedState(static_cast<s32>(slot)));
}

NATIVE_LIBRARY_FUNCTION(jstring, deleteSaveState)(JNIEnv* env, jobject, jstring key, jint slot, jboolean shared)
{
  return ToResult(env, AndroidHost::DeleteSaveState(FromJString(env, key), static_cast<s32>(slot), shared == JNI_TRUE));
}

NATIVE_LIBRARY_FUNCTION(jstring, saveState)(JNIEnv* env, jobject, jstring key, jint slot)
{
  return ToResult(env, AndroidHost::SaveState(FromJString(env, key), static_cast<s32>(slot)));
}

NATIVE_LIBRARY_FUNCTION(jstring, loadState)(JNIEnv* env, jobject, jstring key, jint slot)
{
  return ToResult(env, AndroidHost::LoadState(FromJString(env, key), static_cast<s32>(slot)));
}

NATIVE_LIBRARY_FUNCTION(jint, getCurrentDisc)(JNIEnv*, jobject)
{
  return static_cast<jint>(AndroidHost::GetCurrentDisc());
}

NATIVE_LIBRARY_FUNCTION(jstring, changeDisc)(JNIEnv* env, jobject, jint number)
{
  return ToResult(env, AndroidHost::ChangeDisc(static_cast<u32>(std::max(number, 0))));
}

NATIVE_LIBRARY_FUNCTION(jobject, getPatchCodes)(JNIEnv* env, jobject)
{
  const AndroidHost::PatchCodes codes = AndroidHost::GetPatchCodes();
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(codes.codes.size()), s_patch_code_class, nullptr);
  if (!array)
  {
    ClearException(env);
    return nullptr;
  }

  for (size_t i = 0; i < codes.codes.size(); i++)
  {
    const AndroidHost::PatchCode& code = codes.codes[i];
    jstring group = ToJString(env, code.group);
    jstring name = ToJString(env, code.name);
    jobject entry = nullptr;
    if (group && name)
    {
      entry = env->NewObject(s_patch_code_class, s_patch_code_constructor, static_cast<jint>(i), group, name,
                             static_cast<jboolean>(code.manual), static_cast<jboolean>(code.on));
      if (ClearException(env))
        entry = nullptr;
    }

    for (jobject ref : {static_cast<jobject>(group), static_cast<jobject>(name)})
    {
      if (ref)
        env->DeleteLocalRef(ref);
    }

    if (!entry)
    {
      env->DeleteLocalRef(array);
      return nullptr;
    }

    env->SetObjectArrayElement(array, static_cast<jsize>(i), entry);
    env->DeleteLocalRef(entry);
  }

  jstring file_name = ToJString(env, codes.file_name);
  jobject result = file_name ? env->NewObject(s_patch_codes_class, s_patch_codes_constructor, file_name, array) : nullptr;
  if (ClearException(env))
    result = nullptr;
  if (file_name)
    env->DeleteLocalRef(file_name);
  env->DeleteLocalRef(array);
  return result;
}

NATIVE_LIBRARY_FUNCTION(jstring, setPatchCode)(JNIEnv* env, jobject, jint index, jboolean on)
{
  return ToResult(env, AndroidHost::SetPatchCode(static_cast<u32>(std::max(index, 0)), on == JNI_TRUE));
}

NATIVE_LIBRARY_FUNCTION(jstring, applyPatchCode)(JNIEnv* env, jobject, jint index)
{
  return ToResult(env, AndroidHost::ApplyPatchCode(static_cast<u32>(std::max(index, 0))));
}

NATIVE_LIBRARY_FUNCTION(jboolean, moveSaveState)(JNIEnv* env, jobject, jstring from_serial, jint from_slot,
                                                 jstring to_serial, jint to_slot)
{
  return AndroidHost::MoveSaveState(FromJString(env, from_serial), static_cast<s32>(from_slot),
                                    FromJString(env, to_serial), static_cast<s32>(to_slot));
}

NATIVE_LIBRARY_FUNCTION(jstring, saveResumeState)(JNIEnv* env, jobject, jstring key)
{
  return ToResult(env, AndroidHost::SaveResumeState(FromJString(env, key)));
}

NATIVE_LIBRARY_FUNCTION(jboolean, isSavingMemoryCards)(JNIEnv*, jobject)
{
  return AndroidHost::IsSavingMemoryCards();
}

NATIVE_LIBRARY_FUNCTION(jint, getAnalogMode)(JNIEnv*, jobject)
{
  return AndroidHost::GetAnalogMode();
}

NATIVE_LIBRARY_FUNCTION(void, resetGame)(JNIEnv*, jobject)
{
  AndroidHost::ResetGame();
}

NATIVE_LIBRARY_FUNCTION(void, setFastForward)(JNIEnv*, jobject, jboolean enabled)
{
  AndroidHost::SetFastForward(enabled);
}

NATIVE_LIBRARY_FUNCTION(void, toggleAnalogMode)(JNIEnv*, jobject)
{
  AndroidHost::ToggleAnalogMode();
}

NATIVE_LIBRARY_FUNCTION(void, setTouchPad)(JNIEnv*, jobject, jint buttons, jfloat left_x, jfloat left_y, jfloat right_x,
                                           jfloat right_y)
{
  AndroidHost::QueueTouchPad(TouchPad::State{static_cast<u32>(buttons), {left_x, left_y, right_x, right_y}});
}

NATIVE_LIBRARY_FUNCTION(void, queueTouchPadsForTest)(JNIEnv* env, jobject, jintArray buttons)
{
  std::vector<u32> states(static_cast<size_t>(env->GetArrayLength(buttons)));
  if (!states.empty())
    env->GetIntArrayRegion(buttons, 0, static_cast<jsize>(states.size()), reinterpret_cast<jint*>(states.data()));
  AndroidHost::QueueTouchPadsForTest(states);
}

NATIVE_LIBRARY_FUNCTION(jstring, decodeUtf8ForTest)(JNIEnv* env, jobject, jbyteArray bytes)
{
  std::string text(static_cast<size_t>(env->GetArrayLength(bytes)), '\0');
  if (!text.empty())
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<jbyte*>(text.data()));
  return Made(env, ToJString(env, text), "decodeUtf8ForTest");
}

NATIVE_LIBRARY_FUNCTION(jint, takeTouchPressesForTest)(JNIEnv*, jobject)
{
  return static_cast<jint>(AndroidHost::TakeTouchPressesForTest());
}

NATIVE_LIBRARY_FUNCTION(void, setPictureAtTop)(JNIEnv*, jobject, jboolean top)
{
  AndroidHost::SetPictureAtTop(top);
}

NATIVE_LIBRARY_FUNCTION(void, saveScreenshot)(JNIEnv*, jobject)
{
  AndroidHost::SaveScreenshot();
}

NATIVE_LIBRARY_FUNCTION(void, setPaused)(JNIEnv*, jobject, jboolean paused)
{
  AndroidHost::SetPaused(paused == JNI_TRUE);
}

NATIVE_LIBRARY_FUNCTION(void, surfaceChanged)(JNIEnv* env, jobject, jobject surface, jint width, jint height,
                                              jfloat scale, jfloat refresh_rate)
{
  ANativeWindow* window = nullptr;
  if (surface)
  {
    window = ANativeWindow_fromSurface(env, surface);
    ClearException(env);
    if (!window)
      ERROR_LOG("ANativeWindow_fromSurface() failed, treating the surface as destroyed.");
  }

  AndroidHost::SurfaceChanged(window, width, height, scale, refresh_rate);
}

NATIVE_LIBRARY_FUNCTION(jfloat, getSurfaceRefreshRateForTest)(JNIEnv*, jobject)
{
  return AndroidHost::GetSurfaceRefreshRateForTest();
}

NATIVE_LIBRARY_FUNCTION(jboolean, onKey)(JNIEnv*, jobject, jint device_id, jint keycode, jboolean pressed)
{
  return AndroidHost::QueueKeyEvent(device_id, keycode, pressed == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(void, onKeys)(JNIEnv* env, jobject, jint device_id, jintArray keycodes, jbooleanArray pressed)
{
  const jsize count = std::min(env->GetArrayLength(keycodes), env->GetArrayLength(pressed));
  std::vector<jint> codes(static_cast<size_t>(count));
  std::vector<jboolean> downs(static_cast<size_t>(count));
  env->GetIntArrayRegion(keycodes, 0, count, codes.data());
  env->GetBooleanArrayRegion(pressed, 0, count, downs.data());
  if (ClearException(env))
    return;

  std::vector<std::pair<s32, bool>> keys;
  keys.reserve(codes.size());
  for (size_t i = 0; i < codes.size(); i++)
    keys.emplace_back(codes[i], downs[i] != JNI_FALSE);
  AndroidHost::QueueKeyEvents(device_id, keys);
}

NATIVE_LIBRARY_FUNCTION(void, onAxis)(JNIEnv*, jobject, jint device_id, jint axis, jfloat value)
{
  AndroidHost::QueueAxisEvent(device_id, axis, value);
}

NATIVE_LIBRARY_FUNCTION(void, onDeviceConnected)(JNIEnv* env, jobject, jint device_id, jstring name,
                                                 jstring descriptor)
{
  AndroidHost::QueueDeviceConnected(device_id, FromJString(env, name), FromJString(env, descriptor));
}

NATIVE_LIBRARY_FUNCTION(void, onDeviceDisconnected)(JNIEnv*, jobject, jint device_id)
{
  AndroidHost::QueueDeviceDisconnected(device_id);
}

NATIVE_LIBRARY_FUNCTION(void, releaseAllInputs)(JNIEnv*, jobject)
{
  AndroidHost::QueueReleaseAllInputs();
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getPadBindings)(JNIEnv* env, jobject, jstring descriptor)
{
  return ToPadBindingArray(env, AndroidHost::GetPadBindings(FromJString(env, descriptor)));
}

NATIVE_LIBRARY_FUNCTION(jstring, getPortType)(JNIEnv* env, jobject, jint pad)
{
  if (pad < 0)
    return nullptr;

  const std::optional<std::string> type = AndroidHost::GetPortType(static_cast<u32>(pad));
  return type.has_value() ? ToJString(env, type.value()) : nullptr;
}

NATIVE_LIBRARY_FUNCTION(jint, getRumbleStrength)(JNIEnv* env, jobject, jstring descriptor)
{
  const std::optional<u32> percent = AndroidHost::GetRumbleStrength(FromJString(env, descriptor));
  return percent.has_value() ? static_cast<jint>(percent.value()) : -1;
}

NATIVE_LIBRARY_FUNCTION(void, setRumbleStrength)(JNIEnv* env, jobject, jstring descriptor, jint percent)
{
  AndroidHost::SetRumbleStrength(FromJString(env, descriptor), static_cast<u32>(std::clamp<jint>(percent, 0, 100)));
}

NATIVE_LIBRARY_FUNCTION(void, setPadVibrationForTest)(JNIEnv*, jobject, jint pad, jfloat large, jfloat small)
{
  if (pad >= 0)
    AndroidHost::SetPadVibrationForTest(static_cast<u32>(pad), large, small);
}

NATIVE_LIBRARY_FUNCTION(jboolean, undoLoadState)(JNIEnv*, jobject)
{
  return AndroidHost::UndoLoadState() ? JNI_TRUE : JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(jboolean, canUndoLoadState)(JNIEnv*, jobject)
{
  return AndroidHost::CanUndoLoadState() ? JNI_TRUE : JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(void, setPortType)(JNIEnv* env, jobject, jint pad, jstring type)
{
  if (pad >= 0)
    AndroidHost::SetPortType(static_cast<u32>(pad), FromJString(env, type));
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getTypeInputs)(JNIEnv* env, jobject, jstring type)
{
  // What a macro can press: the type's buttons and axes, never a pointer or a motor.
  std::vector<std::string> pairs;
  const Controller::ControllerInfo* info = Controller::GetControllerInfo(FromJString(env, type));
  for (const Controller::ControllerBindingInfo& bi :
       info ? info->bindings : std::span<const Controller::ControllerBindingInfo>())
  {
    if (bi.type == InputBindingInfo::Type::Button || bi.type == InputBindingInfo::Type::Axis ||
        bi.type == InputBindingInfo::Type::HalfAxis)
    {
      pairs.emplace_back(bi.name);
      pairs.push_back(Host::TranslateToString(info->name, bi.display_name));
    }
  }

  return ToNonNullStringArray(env, pairs);
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getTypeSettings)(JNIEnv* env, jobject, jstring type)
{
  // TypeSetting.parse()'s list: 11 strings a setting.
  std::vector<std::string> flat;
  const Controller::ControllerInfo* info = Controller::GetControllerInfo(FromJString(env, type));
  for (const SettingInfo& si : info ? info->settings : std::span<const SettingInfo>())
  {
    const char* kind;
    switch (si.type)
    {
      case SettingInfo::Type::Boolean:
        kind = "Bool";
        break;
      case SettingInfo::Type::Integer:
        kind = "Int";
        break;
      case SettingInfo::Type::IntegerList:
        kind = "IntList";
        break;
      case SettingInfo::Type::Float:
        kind = "Float";
        break;
      case SettingInfo::Type::String:
      case SettingInfo::Type::Path:
      default:
        kind = "String";
        break;
    }

    std::string options;
    for (const char** option = si.options; option && *option; option++)
    {
      if (!options.empty())
        options += '\n';
      options += Host::TranslateToString(info->name, *option);
    }

    const auto str = [](const char* value) { return std::string(value ? value : ""); };
    flat.push_back(kind);
    flat.push_back(str(si.name));
    flat.push_back(Host::TranslateToString(info->name, si.display_name));
    flat.push_back(si.description ? Host::TranslateToString(info->name, si.description) : std::string());
    flat.push_back(str(si.default_value));
    flat.push_back(str(si.min_value));
    flat.push_back(str(si.max_value));
    flat.push_back(str(si.step_value));
    flat.push_back(str(si.format));
    flat.push_back(fmt::format("{}", si.multiplier));
    flat.push_back(std::move(options));
  }

  return ToNonNullStringArray(env, flat);
}

NATIVE_LIBRARY_FUNCTION(jint, getControllerPort)(JNIEnv* env, jobject, jstring descriptor)
{
  const std::optional<u32> pad = AndroidHost::GetControllerPort(FromJString(env, descriptor));
  return pad.has_value() ? static_cast<jint>(pad.value()) : -1;
}

NATIVE_LIBRARY_FUNCTION(void, setControllerPort)(JNIEnv* env, jobject, jstring descriptor, jint pad)
{
  if (pad >= 0)
    AndroidHost::SetControllerPort(FromJString(env, descriptor), static_cast<u32>(pad));
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getOfferedPorts)(JNIEnv* env, jobject)
{
  std::vector<std::string> pairs;
  for (auto& [pad, label] : AndroidHost::GetOfferedPorts())
  {
    pairs.push_back(std::to_string(pad));
    pairs.push_back(std::move(label));
  }

  return ToNonNullStringArray(env, pairs);
}

NATIVE_LIBRARY_FUNCTION(jfloat, getPadBindStateForTest)(JNIEnv* env, jobject, jint pad, jstring name)
{
  return (pad >= 0) ? AndroidHost::GetPadBindStateForTest(static_cast<u32>(pad), FromJString(env, name)) : 0.0f;
}

NATIVE_LIBRARY_FUNCTION(void, setListedKeyForTest)(JNIEnv* env, jobject, jstring path, jstring key)
{
  AndroidHost::SetListedKeyForTest(FromJString(env, path), FromJString(env, key));
}

namespace {
// A check's or an install's progress, reported to the Kotlin PackProgress it was called with, on the calling thread.
class JavaPackProgress final : public ProgressCallback
{
public:
  JavaPackProgress(JNIEnv* env, jobject listener) : m_env(env), m_listener(listener)
  {
    jclass cls = env->GetObjectClass(listener);
    m_status = env->GetMethodID(cls, "status", "(Ljava/lang/String;)V");
    m_progress = env->GetMethodID(cls, "progress", "(JJ)V");
    m_cancelled = env->GetMethodID(cls, "cancelled", "()Z");
    env->DeleteLocalRef(cls);
    JNIUtil::ClearException(env);
  }

  bool IsCancelled() const override
  {
    if (!m_cancelled)
      return false;
    const jboolean cancelled = m_env->CallBooleanMethod(m_listener, m_cancelled);
    return !JNIUtil::ClearException(m_env) && cancelled == JNI_TRUE;
  }

  void SetStatusText(std::string_view text) override
  {
    ProgressCallback::SetStatusText(text);
    m_reported_percent = -1;
    if (!m_status)
      return;
    jstring jtext = JNIUtil::ToJString(m_env, text);
    if (jtext)
    {
      m_env->CallVoidMethod(m_listener, m_status, jtext);
      JNIUtil::ClearException(m_env);
      m_env->DeleteLocalRef(jtext);
    }
  }

  void SetProgressRange(u32 range) override
  {
    ProgressCallback::SetProgressRange(range);
    Report();
  }

  void SetProgressValue(u32 value) override
  {
    ProgressCallback::SetProgressValue(value);
    Report();
  }

private:
  // A known range is reported as its percentage changes (extracting thousands of files); bytes as they come.
  void Report()
  {
    if (!m_progress)
      return;
    if (m_progress_range > 1)
    {
      const s32 percent = static_cast<s32>((static_cast<u64>(m_progress_value) * 100) / m_progress_range);
      if (percent == m_reported_percent)
        return;
      m_reported_percent = percent;
    }
    m_env->CallVoidMethod(m_listener, m_progress, static_cast<jlong>(m_progress_value),
                          static_cast<jlong>((m_progress_range > 1) ? m_progress_range : 0));
    JNIUtil::ClearException(m_env);
  }

  JNIEnv* m_env;
  jobject m_listener;
  jmethodID m_status = nullptr;
  jmethodID m_progress = nullptr;
  jmethodID m_cancelled = nullptr;
  s32 m_reported_percent = -1;
};

} // namespace

NATIVE_LIBRARY_FUNCTION(jobjectArray, getShaderPacks)(JNIEnv* env, jobject)
{
  const std::string& root = EmuFolders::GetShaderPacksFolder();
  std::vector<std::string> flat;
  for (const ShaderPacks::PackInfo& pack : ShaderPacks::GetPacks())
  {
    const std::optional<ShaderPacks::InstalledPack> installed = ShaderPacks::ReadMarker(root, pack.id);
    flat.emplace_back(pack.id);
    flat.emplace_back(pack.name);
    flat.emplace_back(pack.description);
    flat.emplace_back(pack.licence);
    flat.emplace_back(pack.needs ? pack.needs : "");
    flat.emplace_back((installed.has_value() && !installed->version.empty()) ? installed->version : "");
    flat.emplace_back((installed.has_value() && installed->version.empty()) ? "1" : "0");
    flat.emplace_back(installed.has_value() ? ShaderPacks::FormatSize(installed->archive_size) : "");
  }
  return ToNonNullStringArray(env, flat);
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, checkShaderPacks)(JNIEnv* env, jobject, jobject listener)
{
  JavaPackProgress progress(env, listener);
  const std::unique_ptr<ShaderPacks::Transport> transport = ShaderPacks::CreateTransport();
  std::vector<std::string> flat;
  for (const ShaderPacks::PackInfo& pack : ShaderPacks::GetPacks())
  {
    Error error;
    std::optional<ShaderPacks::ResolvedVersion> version;
    if (!transport)
      error.SetStringView(ShaderPacks::DescribeFailure(true, pack, HTTPDownloader::HTTP_STATUS_ERROR));
    else if (!progress.IsCancelled())
      version = ShaderPacks::ResolveLatest(pack, *transport, &progress, &error);
    flat.emplace_back(pack.id);
    flat.emplace_back(version.has_value() ? version->version : "");
    flat.emplace_back(version.has_value() ? ShaderPacks::FormatSize(version->size) : "");
    flat.emplace_back(version.has_value() ? "" : error.GetDescription());
  }
  return ToNonNullStringArray(env, flat);
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, installShaderPacks)(JNIEnv* env, jobject, jobjectArray jids, jobject listener)
{
  std::vector<std::string> ids;
  const jsize count = jids ? env->GetArrayLength(jids) : 0;
  for (jsize i = 0; i < count; i++)
  {
    jstring jid = static_cast<jstring>(env->GetObjectArrayElement(jids, i));
    ids.push_back(FromJString(env, jid));
    env->DeleteLocalRef(jid);
  }

  JavaPackProgress progress(env, listener);
  std::vector<std::string> flat;
  const std::unique_ptr<ShaderPacks::Transport> transport = ShaderPacks::CreateTransport();
  if (!transport)
  {
    for (const std::string& id : ids)
    {
      const ShaderPacks::PackInfo* pack = ShaderPacks::FindPack(id);
      flat.insert(flat.end(), {id, "0", "0",
                               pack ? ShaderPacks::DescribeFailure(false, *pack, HTTPDownloader::HTTP_STATUS_ERROR) :
                                      std::string()});
    }
    return ToNonNullStringArray(env, flat);
  }

  for (const ShaderPacks::InstallResult& result :
       ShaderPacks::Install(EmuFolders::GetShaderPacksFolder(), ids, *transport, &progress))
  {
    flat.insert(flat.end(), {result.id, result.success ? "1" : "0", result.cancelled ? "1" : "0", result.message});
  }
  return ToNonNullStringArray(env, flat);
}

namespace {
// For ShaderPacksInstallTest only: the platform's transport, with GitHub's addresses moved to a server on the device.
class RebasedTransport final : public ShaderPacks::Transport
{
public:
  RebasedTransport(std::unique_ptr<ShaderPacks::Transport> inner, std::string base)
    : m_inner(std::move(inner)), m_base(std::move(base))
  {
  }

  s32 Get(const std::string& url, std::string* body, ProgressCallback* progress) override
  {
    return m_inner->Get(Rebase(url), body, progress);
  }

  s32 Download(const std::string& url, const std::string& path, u64 max_size, ProgressCallback* progress,
               Error* error) override
  {
    return m_inner->Download(Rebase(url), path, max_size, progress, error);
  }

private:
  std::string Rebase(const std::string& url) const
  {
    for (const auto& [from, to] : {std::pair<std::string_view, std::string_view>{"https://api.github.com/", "/api/"},
                                   std::pair<std::string_view, std::string_view>{"https://github.com/", "/gh/"}})
    {
      if (url.starts_with(from))
        return fmt::format("{}{}{}", m_base, to, url.substr(from.size()));
    }
    return url;
  }

  std::unique_ptr<ShaderPacks::Transport> m_inner;
  std::string m_base;
};

} // namespace

NATIVE_LIBRARY_FUNCTION(jobjectArray, shaderPacksTestInstall)(JNIEnv* env, jobject, jstring base, jobjectArray jids,
                                                              jobject listener)
{
  std::vector<std::string> ids;
  const jsize count = jids ? env->GetArrayLength(jids) : 0;
  for (jsize i = 0; i < count; i++)
  {
    jstring jid = static_cast<jstring>(env->GetObjectArrayElement(jids, i));
    ids.push_back(FromJString(env, jid));
    env->DeleteLocalRef(jid);
  }

  JavaPackProgress progress(env, listener);
  std::vector<std::string> flat;
  if (std::unique_ptr<ShaderPacks::Transport> inner = ShaderPacks::CreateTransport())
  {
    RebasedTransport transport(std::move(inner), FromJString(env, base));
    for (const ShaderPacks::InstallResult& result :
         ShaderPacks::Install(EmuFolders::GetShaderPacksFolder(), ids, transport, &progress))
    {
      flat.insert(flat.end(), {result.id, result.success ? "1" : "0", result.cancelled ? "1" : "0", result.message});
    }
  }
  return ToNonNullStringArray(env, flat);
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, shaderPackMarkerFiles)(JNIEnv* env, jobject, jstring json)
{
  const std::optional<ShaderPacks::InstalledPack> marker = ShaderPacks::ParseMarker(FromJString(env, json));
  return marker.has_value() ? ToNonNullStringArray(env, marker->files) : nullptr;
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, shaderPackIds)(JNIEnv* env, jobject)
{
  std::vector<std::string> ids;
  for (const ShaderPacks::PackInfo& pack : ShaderPacks::GetPacks())
    ids.emplace_back(pack.id);
  return ToNonNullStringArray(env, ids);
}

NATIVE_LIBRARY_FUNCTION(jstring, getShadersFolder)(JNIEnv* env, jobject)
{
  const std::optional<std::string> folder = AndroidHost::GetShadersFolder();
  return folder.has_value() ? JNIUtil::ToJString(env, folder.value()) : nullptr;
}

NATIVE_LIBRARY_FUNCTION(jstring, removeShaderPack)(JNIEnv* env, jobject, jstring id)
{
  Error error;
  if (ShaderPacks::Uninstall(EmuFolders::GetShaderPacksFolder(), FromJString(env, id), &error))
    return nullptr;
  return JNIUtil::ToJString(env, error.GetDescription());
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, describeShaderPack)(JNIEnv* env, jobject, jstring id, jstring latest)
{
  const std::optional<ShaderPacks::InstalledPack> installed =
    ShaderPacks::ReadMarker(EmuFolders::GetShaderPacksFolder(), FromJString(env, id));
  std::optional<ShaderPacks::ResolvedVersion> version;
  if (latest)
    version = ShaderPacks::ResolvedVersion{FromJString(env, latest), std::string(), 0};
  const ShaderPacks::PackStatus status = ShaderPacks::GetPackStatus(installed, version);
  static constexpr const char* STATES[] = {"NotInstalled", "Installed", "UpdateAvailable", "Incomplete"};
  return ToNonNullStringArray(env, {STATES[static_cast<size_t>(status.state)], ShaderPacks::DescribePackStatus(status)});
}

NATIVE_LIBRARY_FUNCTION(jboolean, reloadPostProcessingShaders)(JNIEnv* env, jobject)
{
  return AndroidHost::ReloadPostProcessingShaders();
}

NATIVE_LIBRARY_FUNCTION(jstring, getRunningSettingForTest)(JNIEnv* env, jobject, jstring section, jstring key)
{
  return ToJString(env, AndroidHost::GetRunningSettingForTest(FromJString(env, section), FromJString(env, key)));
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getMacroBindings)(JNIEnv* env, jobject, jstring descriptor)
{
  return ToPadBindingArray(env, AndroidHost::GetMacroBindings(FromJString(env, descriptor)));
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getHotkeyBindings)(JNIEnv* env, jobject, jstring descriptor)
{
  return ToPadBindingArray(env, AndroidHost::GetHotkeyBindings(FromJString(env, descriptor)));
}

NATIVE_LIBRARY_FUNCTION(void, setHotkeyBinding)(JNIEnv* env, jobject, jstring descriptor, jstring name, jstring button)
{
  AndroidHost::SetHotkeyBinding(FromJString(env, descriptor), FromJString(env, name),
                                button ? FromJString(env, button) : std::string());
}

NATIVE_LIBRARY_FUNCTION(jint, getControllerIndex)(JNIEnv* env, jobject, jstring descriptor)
{
  const std::optional<u32> index = AndroidHost::GetControllerIndex(FromJString(env, descriptor));
  return index.has_value() ? static_cast<jint>(index.value()) : -1;
}

NATIVE_LIBRARY_FUNCTION(void, setPadBinding)(JNIEnv* env, jobject, jstring descriptor, jstring name, jstring binding)
{
  // A null binding is an empty one: none.
  AndroidHost::SetPadBinding(FromJString(env, descriptor), FromJString(env, name), FromJString(env, binding));
}

NATIVE_LIBRARY_FUNCTION(void, resetPadBindings)(JNIEnv* env, jobject, jstring descriptor)
{
  AndroidHost::ResetPadBindings(FromJString(env, descriptor));
}

NATIVE_LIBRARY_FUNCTION(void, startBindingCapture)(JNIEnv* env, jobject, jstring descriptor, jboolean select_chord)
{
  AndroidHost::StartBindingCapture(FromJString(env, descriptor), select_chord);
}

NATIVE_LIBRARY_FUNCTION(void, cancelBindingCapture)(JNIEnv*, jobject)
{
  AndroidHost::CancelBindingCapture();
}

namespace {
// Element i of a String[], as UTF-8.
std::string StringAt(JNIEnv* env, jobjectArray array, jsize i)
{
  jstring str = static_cast<jstring>(env->GetObjectArrayElement(array, i));
  std::string ret = FromJString(env, str);
  if (str)
    env->DeleteLocalRef(str);
  return ret;
}

// A MemcardFile, or null (with no exception pending) if it can't be made.
jobject ToMemcardFile(JNIEnv* env, const MemcardEditorBridge::CardFile& card)
{
  jstring path = ToJString(env, card.path);
  jstring name = ToJString(env, card.name);
  jobject ret = (path && name) ? env->NewObject(s_memcard_file_class, s_memcard_file_constructor, path, name,
                                                s_memcard_kinds[static_cast<size_t>(card.kind)],
                                                static_cast<jboolean>(card.exists)) :
                                 nullptr;
  if (!ret)
    ClearException(env);
  for (jstring ref : {path, name})
  {
    if (ref)
      env->DeleteLocalRef(ref);
  }
  return ret;
}

} // namespace

NATIVE_LIBRARY_FUNCTION(jobjectArray, getMemcardFiles)(JNIEnv* env, jobject, jobjectArray titles, jobjectArray serials,
                                                      jobjectArray boot_paths, jbooleanArray playlists)
{
  const jsize count = env->GetArrayLength(titles);
  std::vector<jboolean> playlist(static_cast<size_t>(count));
  env->GetBooleanArrayRegion(playlists, 0, count, playlist.data());
  std::vector<MemcardEditorBridge::GameCard> games;
  games.reserve(static_cast<size_t>(count));
  for (jsize i = 0; i < count; i++)
  {
    games.push_back(MemcardEditorBridge::GameCard{StringAt(env, titles, i), StringAt(env, serials, i),
                                                  StringAt(env, boot_paths, i), playlist[i] == JNI_TRUE});
  }

  const std::vector<MemcardEditorBridge::CardFile> cards = MemcardEditorBridge::ListCards(games);
  jobjectArray ret = env->NewObjectArray(static_cast<jsize>(cards.size()), s_memcard_file_class, nullptr);
  if (!ret)
    return Made(env, ret, "getMemcardFiles");
  for (size_t i = 0; i < cards.size(); i++)
  {
    jobject card = ToMemcardFile(env, cards[i]);
    if (!card)
    {
      env->DeleteLocalRef(ret);
      return Made<jobjectArray>(env, nullptr, "getMemcardFiles");
    }
    env->SetObjectArrayElement(ret, static_cast<jsize>(i), card);
    env->DeleteLocalRef(card);
  }
  return ret;
}

namespace {
std::vector<u8> FromJByteArray(JNIEnv* env, jbyteArray array)
{
  std::vector<u8> bytes(array ? static_cast<size_t>(env->GetArrayLength(array)) : 0);
  if (!bytes.empty())
    env->GetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<jbyte*>(bytes.data()));
  return bytes;
}

// A byte[] of bytes, or null (with no exception pending) if it can't be made.
jbyteArray ToJByteArray(JNIEnv* env, std::span<const u8> bytes)
{
  jbyteArray ret = env->NewByteArray(static_cast<jsize>(bytes.size()));
  if (!ret)
  {
    ClearException(env);
    return nullptr;
  }
  env->SetByteArrayRegion(ret, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
  return ret;
}

jbyteArray ToJByteArrayOrNull(JNIEnv* env, const std::optional<std::vector<u8>>& bytes)
{
  return bytes.has_value() ? ToJByteArray(env, std::span<const u8>(bytes.value())) : nullptr;
}

std::optional<std::string> FromOptionalJString(JNIEnv* env, jstring str)
{
  return str ? std::optional<std::string>(FromJString(env, str)) : std::nullopt;
}

// A MemcardSave, or null (with no exception pending) if it can't be made.
jobject ToMemcardSave(JNIEnv* env, const MemcardEditorBridge::Save& save)
{
  using MemoryCardImage::ICON_HEIGHT;
  using MemoryCardImage::ICON_WIDTH;
  jstring file_name = ToJString(env, save.file_name);
  jstring title = ToJString(env, save.title);
  jobject icon = save.icon.size() == ICON_WIDTH * ICON_HEIGHT ?
                   ToArgbImage(env, save.icon.data(), ICON_WIDTH, ICON_HEIGHT, false) :
                   nullptr;
  jobject ret = (file_name && title) ?
                  env->NewObject(s_memcard_save_class, s_memcard_save_constructor, static_cast<jint>(save.first_block),
                                 file_name, title, static_cast<jint>(save.blocks), static_cast<jboolean>(save.deleted),
                                 icon) :
                  nullptr;
  if (!ret)
    ClearException(env);
  for (jobject ref : {static_cast<jobject>(file_name), static_cast<jobject>(title), icon})
  {
    if (ref)
      env->DeleteLocalRef(ref);
  }
  return ret;
}

// A MemcardEdit, or null (with no exception pending) if it can't be made.
jobject ToMemcardEdit(JNIEnv* env, const MemcardEditorBridge::Edit& edit)
{
  jbyteArray card = ToJByteArrayOrNull(env, edit.card);
  jstring error = ToJString(env, edit.error);
  jobject ret = (error && (card || !edit.card.has_value())) ?
                  env->NewObject(s_memcard_edit_class, s_memcard_edit_constructor, card, error) :
                  nullptr;
  if (!ret)
    ClearException(env);
  for (jobject ref : {static_cast<jobject>(card), static_cast<jobject>(error)})
  {
    if (ref)
      env->DeleteLocalRef(ref);
  }
  return ret;
}

} // namespace

NATIVE_LIBRARY_FUNCTION(jbyteArray, readMemcard)(JNIEnv* env, jobject, jstring path)
{
  return ToJByteArrayOrNull(env, MemcardEditorBridge::ReadCard(FromJString(env, path)));
}

NATIVE_LIBRARY_FUNCTION(jobject, getMemcardContents)(JNIEnv* env, jobject, jbyteArray card)
{
  const std::optional<MemcardEditorBridge::Contents> contents =
    MemcardEditorBridge::GetContents(FromJByteArray(env, card));
  if (!contents.has_value())
    return nullptr;

  jobjectArray saves = env->NewObjectArray(static_cast<jsize>(contents->saves.size()), s_memcard_save_class, nullptr);
  if (!saves)
  {
    ClearException(env);
    return nullptr;
  }
  for (size_t i = 0; i < contents->saves.size(); i++)
  {
    jobject save = ToMemcardSave(env, contents->saves[i]);
    if (!save)
    {
      env->DeleteLocalRef(saves);
      return nullptr;
    }
    env->SetObjectArrayElement(saves, static_cast<jsize>(i), save);
    env->DeleteLocalRef(save);
  }
  jobject ret = env->NewObject(s_memcard_contents_class, s_memcard_contents_constructor,
                               static_cast<jint>(contents->free_blocks), saves);
  if (!ret)
    ClearException(env);
  env->DeleteLocalRef(saves);
  return ret;
}

NATIVE_LIBRARY_FUNCTION(jbyteArray, formatMemcard)(JNIEnv* env, jobject)
{
  const std::vector<u8> card = MemcardEditorBridge::Format();
  return ToJByteArray(env, std::span<const u8>(card));
}

NATIVE_LIBRARY_FUNCTION(jobject, copyMemcardSave)(JNIEnv* env, jobject, jbyteArray from, jint first_block,
                                                 jbyteArray to, jboolean replace)
{
  const MemcardEditorBridge::Edit edit = MemcardEditorBridge::CopySave(
    FromJByteArray(env, from), static_cast<u32>(first_block), FromJByteArray(env, to), replace == JNI_TRUE);
  return Made(env, ToMemcardEdit(env, edit), "copyMemcardSave");
}

NATIVE_LIBRARY_FUNCTION(jbyteArray, deleteMemcardSave)(JNIEnv* env, jobject, jbyteArray card, jint first_block,
                                                      jboolean permanently)
{
  return ToJByteArrayOrNull(env, MemcardEditorBridge::DeleteSave(FromJByteArray(env, card), static_cast<u32>(first_block),
                                                           permanently == JNI_TRUE));
}

NATIVE_LIBRARY_FUNCTION(jbyteArray, undeleteMemcardSave)(JNIEnv* env, jobject, jbyteArray card, jint first_block)
{
  return ToJByteArrayOrNull(env,
                      MemcardEditorBridge::UndeleteSave(FromJByteArray(env, card), static_cast<u32>(first_block)));
}

NATIVE_LIBRARY_FUNCTION(jobject, checkMemcardRename)(JNIEnv* env, jobject, jbyteArray card, jint first_block,
                                                    jstring title, jstring file_name)
{
  const MemcardEditorBridge::RenameCheck check =
    MemcardEditorBridge::CheckRename(FromJByteArray(env, card), static_cast<u32>(first_block),
                                     FromOptionalJString(env, title), FromOptionalJString(env, file_name));
  jobject ret = env->NewObject(s_memcard_rename_check_class, s_memcard_rename_check_constructor,
                               static_cast<jint>(check.title_error), static_cast<jint>(check.name_error),
                               static_cast<jboolean>(check.single_byte_title),
                               static_cast<jboolean>(check.title_not_shown_exactly),
                               static_cast<jboolean>(check.name_not_standard));
  return Made(env, ret, "checkMemcardRename");
}

NATIVE_LIBRARY_FUNCTION(jbyteArray, renameMemcardSave)(JNIEnv* env, jobject, jbyteArray card, jint first_block,
                                                      jstring title, jstring file_name)
{
  return ToJByteArrayOrNull(env, MemcardEditorBridge::Rename(FromJByteArray(env, card), static_cast<u32>(first_block),
                                                       FromOptionalJString(env, title),
                                                       FromOptionalJString(env, file_name)));
}

NATIVE_LIBRARY_FUNCTION(jobject, importMemcardSave)(JNIEnv* env, jobject, jbyteArray card, jstring file_name,
                                                   jbyteArray data)
{
  const MemcardEditorBridge::Edit edit =
    MemcardEditorBridge::ImportSave(FromJByteArray(env, card), FromJString(env, file_name), FromJByteArray(env, data));
  return Made(env, ToMemcardEdit(env, edit), "importMemcardSave");
}

NATIVE_LIBRARY_FUNCTION(jobject, importMemcard)(JNIEnv* env, jobject, jstring file_name, jbyteArray data)
{
  const MemcardEditorBridge::Edit edit =
    MemcardEditorBridge::ImportCard(FromJString(env, file_name), FromJByteArray(env, data));
  return Made(env, ToMemcardEdit(env, edit), "importMemcard");
}

NATIVE_LIBRARY_FUNCTION(jobject, exportMemcardSave)(JNIEnv* env, jobject, jbyteArray card, jint first_block)
{
  const MemcardEditorBridge::Edit edit =
    MemcardEditorBridge::ExportSave(FromJByteArray(env, card), static_cast<u32>(first_block));
  return Made(env, ToMemcardEdit(env, edit), "exportMemcardSave");
}

NATIVE_LIBRARY_FUNCTION(jboolean, isMemcardKnownToSync)(JNIEnv* env, jobject, jstring path)
{
  return MemcardEditorBridge::IsKnownToSync(FromJString(env, path)) ? JNI_TRUE : JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(jobject, writeMemcard)(JNIEnv* env, jobject, jstring path, jbyteArray card,
                                              jbyteArray expected, jboolean force)
{
  const std::vector<u8> expected_bytes = FromJByteArray(env, expected);
  const MemcardEditorBridge::WriteResult result = MemcardEditorBridge::WriteCard(
    FromJString(env, path), FromJByteArray(env, card),
    expected ? std::optional<std::span<const u8>>(expected_bytes) : std::nullopt, force == JNI_TRUE);
  jstring error = ToJString(env, result.error);
  jobject ret = error ? env->NewObject(s_memcard_write_class, s_memcard_write_constructor,
                                       s_memcard_write_outcomes[static_cast<size_t>(result.outcome)], error) :
                        nullptr;
  if (error)
    env->DeleteLocalRef(error);
  return Made(env, ret, "writeMemcard");
}

} // extern "C"
