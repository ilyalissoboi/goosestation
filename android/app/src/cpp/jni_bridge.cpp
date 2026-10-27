// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// The JNI side of io.github.ilyalissoboi.goosestation.core.NativeLibrary, and the calls back into NativeCallbacks.

#include "android_host.h"
#include "bios_import.h"
#include "content_bridge.h"
#include "jni_util.h"

#include "core/game_list.h"
#include "core/settings.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/string_util.h"

#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

Log_SetChannel(JNIBridge);

using JNIUtil::ClearException;
using JNIUtil::FromJString;
using JNIUtil::ToJString;

namespace {
// Cached at JNI_OnLoad.
jclass s_string_class = nullptr;
jclass s_callbacks_class = nullptr;
jmethodID s_on_game_started = nullptr;
jmethodID s_on_game_stopped = nullptr;
jmethodID s_on_error = nullptr;
jclass s_game_entry_class = nullptr;
jmethodID s_game_entry_constructor = nullptr;

// The app's NativeCallbacks (a global reference), or null.
std::mutex s_callbacks_mutex;
jobject s_callbacks = nullptr;

// Set on the thread AttachCurrentThreadToJVM() attached.
thread_local JNIEnv* s_attached_env = nullptr;

AndroidContentProvider s_content_provider;

// Calls a NativeCallbacks method on the registered object, from any thread.
void CallCallback(jmethodID method, std::optional<std::string_view> arg)
{
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject callbacks = nullptr;
  {
    std::unique_lock lock(s_callbacks_mutex);
    if (s_callbacks)
      callbacks = env->NewLocalRef(s_callbacks);
  }

  if (!callbacks)
    return;

  if (arg.has_value())
  {
    jstring jarg = ToJString(env.get(), arg.value());
    if (jarg)
    {
      env->CallVoidMethod(callbacks, method, jarg);
      ClearException(env.get());
      env->DeleteLocalRef(jarg);
    }
  }
  else
  {
    env->CallVoidMethod(callbacks, method);
    ClearException(env.get());
  }

  env->DeleteLocalRef(callbacks);
}

// A String[] of strings, or null (with no exception pending) if it can't be made.
jobjectArray ToJStringArray(JNIEnv* env, const std::vector<std::string>& strings)
{
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(strings.size()), s_string_class, nullptr);
  if (!result)
  {
    ClearException(env);
    return nullptr;
  }

  for (size_t i = 0; i < strings.size(); i++)
  {
    jstring string = ToJString(env, strings[i]);
    if (!string)
    {
      env->DeleteLocalRef(result);
      return nullptr;
    }

    env->SetObjectArrayElement(result, static_cast<jsize>(i), string);
    env->DeleteLocalRef(string);
  }

  return result;
}
} // namespace

void AndroidHost::AttachCurrentThreadToJVM(const char* name)
{
  JavaVM* const vm = JNIUtil::GetJavaVM();
  if (!vm || s_attached_env)
    return;

  JavaVMAttachArgs args = {JNI_VERSION_1_6, name, nullptr};
  JNIEnv* env = nullptr;
  if (vm->AttachCurrentThread(&env, &args) != JNI_OK)
  {
    ERROR_LOG("Failed to attach '{}' to the JVM.", name);
    return;
  }

  s_attached_env = env;
}

void AndroidHost::DetachCurrentThreadFromJVM()
{
  JavaVM* const vm = JNIUtil::GetJavaVM();
  if (!vm || !s_attached_env)
    return;

  vm->DetachCurrentThread();
  s_attached_env = nullptr;
}

void AndroidHost::NotifyGameStarted(std::string_view title)
{
  CallCallback(s_on_game_started, title);
}

void AndroidHost::NotifyGameStopped()
{
  CallCallback(s_on_game_stopped, std::nullopt);
}

void AndroidHost::NotifyError(std::string_view message)
{
  CallCallback(s_on_error, message);
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*)
{
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
    return JNI_ERR;

  jclass string_class = env->FindClass("java/lang/String");
  if (!string_class)
  {
    ClearException(env);
    return JNI_ERR;
  }

  s_string_class = static_cast<jclass>(env->NewGlobalRef(string_class));
  env->DeleteLocalRef(string_class);

  jclass callbacks_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/NativeCallbacks");
  if (!callbacks_class)
  {
    ClearException(env);
    return JNI_ERR;
  }

  s_callbacks_class = static_cast<jclass>(env->NewGlobalRef(callbacks_class));
  env->DeleteLocalRef(callbacks_class);
  s_on_game_started = env->GetMethodID(s_callbacks_class, "onGameStarted", "(Ljava/lang/String;)V");
  s_on_game_stopped = env->GetMethodID(s_callbacks_class, "onGameStopped", "()V");
  s_on_error = env->GetMethodID(s_callbacks_class, "onError", "(Ljava/lang/String;)V");
  if (!s_on_game_started || !s_on_game_stopped || !s_on_error)
  {
    ClearException(env);
    return JNI_ERR;
  }

  jclass game_entry_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/GameEntry");
  if (!game_entry_class)
  {
    ClearException(env);
    return JNI_ERR;
  }

  s_game_entry_class = static_cast<jclass>(env->NewGlobalRef(game_entry_class));
  env->DeleteLocalRef(game_entry_class);
  s_game_entry_constructor =
    env->GetMethodID(s_game_entry_class, "<init>",
                     "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJLjava/lang/String;)V");
  if (!s_game_entry_constructor)
  {
    ClearException(env);
    return JNI_ERR;
  }

  if (!AndroidContentProvider::LoadJNIReferences(env))
    return JNI_ERR;

  JNIUtil::SetJavaVM(vm);
  FileSystem::SetContentProvider(&s_content_provider);
  return JNI_VERSION_1_6;
}

#define NATIVE_LIBRARY_FUNCTION(ret, name) \
  JNIEXPORT ret JNICALL Java_io_github_ilyalissoboi_goosestation_core_NativeLibrary_##name

NATIVE_LIBRARY_FUNCTION(jboolean, initialize)(JNIEnv* env, jobject, jstring data_root, jstring resources_root,
                                               jstring bios_dir, jstring install_id)
{
  Error error;
  if (!AndroidHost::Initialize(FromJString(env, data_root), FromJString(env, resources_root),
                               FromJString(env, bios_dir), FromJString(env, install_id), &error))
  {
    ERROR_LOG("Initialization failed: {}", error.GetDescription());
    AndroidHost::NotifyError(error.GetDescription());
    return JNI_FALSE;
  }

  return JNI_TRUE;
}

NATIVE_LIBRARY_FUNCTION(void, setGameFolders)(JNIEnv* env, jobject, jobjectArray tree_uris)
{
  std::vector<std::string> uris;
  const jsize count = tree_uris ? env->GetArrayLength(tree_uris) : 0;
  uris.reserve(static_cast<size_t>(count));
  for (jsize i = 0; i < count; i++)
  {
    jstring uri = static_cast<jstring>(env->GetObjectArrayElement(tree_uris, i));
    if (ClearException(env))
      return;

    if (uri)
    {
      uris.push_back(FromJString(env, uri));
      env->DeleteLocalRef(uri);
    }
  }

  AndroidHost::SetGameFolders(std::move(uris));
}

NATIVE_LIBRARY_FUNCTION(void, reloadSettings)(JNIEnv*, jobject)
{
  AndroidHost::ReloadSettings();
}

NATIVE_LIBRARY_FUNCTION(jboolean, getBoolSetting)(JNIEnv* env, jobject, jstring section, jstring key,
                                                  jboolean default_value)
{
  const std::string section_str = FromJString(env, section);
  const std::string key_str = FromJString(env, key);
  return AndroidHost::GetBoolSetting(section_str.c_str(), key_str.c_str(), default_value == JNI_TRUE) ? JNI_TRUE :
                                                                                                        JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(void, setBoolSetting)(JNIEnv* env, jobject, jstring section, jstring key, jboolean value)
{
  const std::string section_str = FromJString(env, section);
  const std::string key_str = FromJString(env, key);
  AndroidHost::SetBoolSetting(section_str.c_str(), key_str.c_str(), value == JNI_TRUE);
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

NATIVE_LIBRARY_FUNCTION(void, refreshGameList)(JNIEnv*, jobject, jboolean invalidate_cache)
{
  // A refresh sees the folders as they are now, not as ContentBridge listed them a few seconds ago.
  s_content_provider.InvalidateListings();
  AndroidHost::RefreshGameList(invalidate_cache == JNI_TRUE);
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getGameList)(JNIEnv* env, jobject)
{
  struct Game
  {
    std::string path;
    std::string title;
    std::string serial;
    const char* region;
    s64 total_played_time;
    s64 last_played_time;
    std::string cover_path;
  };

  // Copied out under the lock; the covers are looked up, and the Java objects made, without holding it.
  std::vector<Game> games;
  {
    const auto lock = GameList::GetLock();
    const u32 count = GameList::GetEntryCount();
    games.reserve(count);
    for (u32 i = 0; i < count; i++)
    {
      const GameList::Entry* entry = GameList::GetEntryByIndex(i);
      if (entry->IsDiscSet())
        continue;

      games.push_back({entry->path, entry->title, entry->serial, Settings::GetDiscRegionName(entry->region),
                       static_cast<s64>(entry->total_played_time), static_cast<s64>(entry->last_played_time),
                       std::string()});
    }
  }

  for (Game& game : games)
    game.cover_path = GameList::GetCoverImagePath(game.path, game.serial, game.title);

  std::stable_sort(games.begin(), games.end(), [](const Game& a, const Game& b) {
    return StringUtil::Strcasecmp(a.title.c_str(), b.title.c_str()) < 0;
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
    jstring path = ToJString(env, game.path);
    jstring title = ToJString(env, game.title);
    jstring serial = ToJString(env, game.serial);
    jstring region = ToJString(env, game.region);
    jstring cover_path = game.cover_path.empty() ? nullptr : ToJString(env, game.cover_path);
    jobject entry = nullptr;
    if (path && title && serial && region && (cover_path || game.cover_path.empty()))
    {
      entry = env->NewObject(s_game_entry_class, s_game_entry_constructor, path, title, serial, region,
                             static_cast<jlong>(game.total_played_time), static_cast<jlong>(game.last_played_time),
                             cover_path);
      if (ClearException(env))
        entry = nullptr;
    }

    for (jobject ref : {static_cast<jobject>(path), static_cast<jobject>(title), static_cast<jobject>(serial),
                        static_cast<jobject>(region), static_cast<jobject>(cover_path)})
    {
      if (ref)
        env->DeleteLocalRef(ref);
    }

    if (!entry)
    {
      ERROR_LOG("Failed to pass the game list entry '{}' to Java.", game.path);
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

NATIVE_LIBRARY_FUNCTION(void, startGame)(JNIEnv* env, jobject, jstring path)
{
  AndroidHost::StartGame(FromJString(env, path));
}

NATIVE_LIBRARY_FUNCTION(void, shutdownGame)(JNIEnv*, jobject)
{
  AndroidHost::ShutdownGame();
}

NATIVE_LIBRARY_FUNCTION(void, setPaused)(JNIEnv*, jobject, jboolean paused)
{
  AndroidHost::SetPaused(paused == JNI_TRUE);
}

NATIVE_LIBRARY_FUNCTION(void, surfaceChanged)(JNIEnv* env, jobject, jobject surface, jint width, jint height,
                                              jfloat scale)
{
  ANativeWindow* window = nullptr;
  if (surface)
  {
    window = ANativeWindow_fromSurface(env, surface);
    ClearException(env);
    if (!window)
      ERROR_LOG("ANativeWindow_fromSurface() failed, treating the surface as destroyed.");
  }

  AndroidHost::SurfaceChanged(window, width, height, scale);
}

NATIVE_LIBRARY_FUNCTION(jboolean, onKey)(JNIEnv*, jobject, jint device_id, jint keycode, jboolean pressed)
{
  return AndroidHost::QueueKeyEvent(device_id, keycode, pressed == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
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

} // extern "C"
