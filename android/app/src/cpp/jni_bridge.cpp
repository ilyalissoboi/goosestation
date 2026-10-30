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
#include <iterator>
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
jmethodID s_on_binding_captured = nullptr;
jclass s_game_entry_class = nullptr;
jmethodID s_game_entry_constructor = nullptr;
jclass s_bios_image_class = nullptr;
jmethodID s_bios_image_constructor = nullptr;
jclass s_pad_binding_class = nullptr;
jmethodID s_pad_binding_constructor = nullptr;
jobject s_pad_binding_kinds[3] = {}; // PadBindingKind's Button, Axis and HalfAxis (global references)

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

void AndroidHost::NotifyBindingCaptured(std::string_view binding)
{
  CallCallback(s_on_binding_captured, binding);
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
  s_on_binding_captured = env->GetMethodID(s_callbacks_class, "onBindingCaptured", "(Ljava/lang/String;)V");
  if (!s_on_game_started || !s_on_game_stopped || !s_on_error || !s_on_binding_captured)
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

  jclass bios_image_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/BiosImage");
  if (!bios_image_class)
  {
    ClearException(env);
    return JNI_ERR;
  }

  s_bios_image_class = static_cast<jclass>(env->NewGlobalRef(bios_image_class));
  env->DeleteLocalRef(bios_image_class);
  s_bios_image_constructor =
    env->GetMethodID(s_bios_image_class, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
  if (!s_bios_image_constructor)
  {
    ClearException(env);
    return JNI_ERR;
  }

  jclass pad_binding_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/PadBinding");
  if (!pad_binding_class)
  {
    ClearException(env);
    return JNI_ERR;
  }

  s_pad_binding_class = static_cast<jclass>(env->NewGlobalRef(pad_binding_class));
  env->DeleteLocalRef(pad_binding_class);
  s_pad_binding_constructor =
    env->GetMethodID(s_pad_binding_class, "<init>",
                     "(Ljava/lang/String;Ljava/lang/String;Lio/github/ilyalissoboi/goosestation/core/PadBindingKind;"
                     "Ljava/lang/String;)V");
  if (!s_pad_binding_constructor)
  {
    ClearException(env);
    return JNI_ERR;
  }

  jclass pad_binding_kind_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/PadBindingKind");
  if (!pad_binding_kind_class)
  {
    ClearException(env);
    return JNI_ERR;
  }

  // In AndroidHost::PadBindingKind's order.
  static constexpr const char* PAD_BINDING_KIND_NAMES[] = {"Button", "Axis", "HalfAxis"};
  static_assert(std::size(PAD_BINDING_KIND_NAMES) == std::size(s_pad_binding_kinds));
  for (size_t i = 0; i < std::size(PAD_BINDING_KIND_NAMES); i++)
  {
    jfieldID field = env->GetStaticFieldID(pad_binding_kind_class, PAD_BINDING_KIND_NAMES[i],
                                           "Lio/github/ilyalissoboi/goosestation/core/PadBindingKind;");
    jobject kind = field ? env->GetStaticObjectField(pad_binding_kind_class, field) : nullptr;
    if (!kind)
    {
      ClearException(env);
      env->DeleteLocalRef(pad_binding_kind_class);
      return JNI_ERR;
    }

    s_pad_binding_kinds[i] = env->NewGlobalRef(kind);
    env->DeleteLocalRef(kind);
  }

  env->DeleteLocalRef(pad_binding_kind_class);

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

NATIVE_LIBRARY_FUNCTION(jstring, getStringSetting)(JNIEnv* env, jobject, jstring section, jstring key,
                                                  jstring default_value)
{
  const std::string section_str = FromJString(env, section);
  const std::string key_str = FromJString(env, key);
  const std::string default_str = FromJString(env, default_value);
  const std::string value = AndroidHost::GetStringSetting(section_str.c_str(), key_str.c_str(), default_str.c_str());

  // Kotlin takes the result as never null: if it can't be made, the default comes back.
  jstring result = ToJString(env, value);
  return result ? result : default_value;
}

NATIVE_LIBRARY_FUNCTION(void, setStringSetting)(JNIEnv* env, jobject, jstring section, jstring key, jstring value)
{
  const std::string section_str = FromJString(env, section);
  const std::string key_str = FromJString(env, key);
  if (!value)
  {
    AndroidHost::SetStringSetting(section_str.c_str(), key_str.c_str(), nullptr);
    return;
  }

  const std::string value_str = FromJString(env, value);
  AndroidHost::SetStringSetting(section_str.c_str(), key_str.c_str(), value_str.c_str());
}

NATIVE_LIBRARY_FUNCTION(jint, getIntSetting)(JNIEnv* env, jobject, jstring section, jstring key, jint default_value)
{
  const std::string section_str = FromJString(env, section);
  const std::string key_str = FromJString(env, key);
  return AndroidHost::GetIntSetting(section_str.c_str(), key_str.c_str(), default_value);
}

NATIVE_LIBRARY_FUNCTION(void, setIntSetting)(JNIEnv* env, jobject, jstring section, jstring key, jint value)
{
  const std::string section_str = FromJString(env, section);
  const std::string key_str = FromJString(env, key);
  AndroidHost::SetIntSetting(section_str.c_str(), key_str.c_str(), value);
}

NATIVE_LIBRARY_FUNCTION(void, applySettings)(JNIEnv*, jobject)
{
  AndroidHost::ApplySettings();
}

NATIVE_LIBRARY_FUNCTION(jboolean, isVulkanFailed)(JNIEnv*, jobject)
{
  return AndroidHost::IsVulkanFailed() ? JNI_TRUE : JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getAspectRatioNames)(JNIEnv* env, jobject)
{
  std::vector<std::string> names;
  names.reserve(static_cast<size_t>(DisplayAspectRatio::Count));
  for (u32 i = 0; i < static_cast<u32>(DisplayAspectRatio::Count); i++)
    names.emplace_back(Settings::GetDisplayAspectRatioName(static_cast<DisplayAspectRatio>(i)));

  // Kotlin takes the array as never null: a failure gives an empty one.
  jobjectArray result = ToJStringArray(env, names);
  if (!result)
  {
    result = env->NewObjectArray(0, s_string_class, nullptr);
    ClearException(env);
  }

  return result;
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

NATIVE_LIBRARY_FUNCTION(jobjectArray, getPadBindings)(JNIEnv* env, jobject, jstring descriptor)
{
  const std::optional<std::vector<AndroidHost::PadBinding>> bindings =
    AndroidHost::GetPadBindings(FromJString(env, descriptor));
  if (!bindings.has_value())
    return nullptr;

  jobjectArray result = env->NewObjectArray(static_cast<jsize>(bindings->size()), s_pad_binding_class, nullptr);
  if (!result)
  {
    ClearException(env);
    return nullptr;
  }

  for (size_t i = 0; i < bindings->size(); i++)
  {
    const AndroidHost::PadBinding& binding = (*bindings)[i];
    jstring name = ToJString(env, binding.name);
    jstring display_name = ToJString(env, binding.display_name);
    jstring value = binding.binding.has_value() ? ToJString(env, binding.binding.value()) : nullptr;
    jobject entry = nullptr;
    if (name && display_name && (value || !binding.binding.has_value()))
    {
      entry = env->NewObject(s_pad_binding_class, s_pad_binding_constructor, name, display_name,
                             s_pad_binding_kinds[static_cast<size_t>(binding.kind)], value);
      if (ClearException(env))
        entry = nullptr;
    }

    for (jobject ref : {static_cast<jobject>(name), static_cast<jobject>(display_name), static_cast<jobject>(value)})
    {
      if (ref)
        env->DeleteLocalRef(ref);
    }

    if (!entry)
    {
      ERROR_LOG("Failed to pass Port 1's input '{}' to Java.", binding.name);
      env->DeleteLocalRef(result);
      return nullptr;
    }

    env->SetObjectArrayElement(result, static_cast<jsize>(i), entry);
    env->DeleteLocalRef(entry);
  }

  return result;
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

NATIVE_LIBRARY_FUNCTION(void, startBindingCapture)(JNIEnv* env, jobject, jstring descriptor)
{
  AndroidHost::StartBindingCapture(FromJString(env, descriptor));
}

NATIVE_LIBRARY_FUNCTION(void, cancelBindingCapture)(JNIEnv*, jobject)
{
  AndroidHost::CancelBindingCapture();
}

} // extern "C"
