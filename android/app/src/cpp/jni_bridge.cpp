// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// The JNI side of io.github.ilyalissoboi.goosestation.core.NativeLibrary, and the calls back into NativeCallbacks.

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

namespace {
// Cached at JNI_OnLoad.
jclass s_string_class = nullptr;
jclass s_callbacks_class = nullptr;
jmethodID s_on_game_started = nullptr;
jmethodID s_on_game_stopped = nullptr;
jmethodID s_on_error = nullptr;
jmethodID s_on_binding_captured = nullptr;
jmethodID s_on_hotkey = nullptr;
jmethodID s_on_rumble = nullptr;
jmethodID s_on_osd_message = nullptr;
jmethodID s_on_fast_forward_changed = nullptr;
jmethodID s_on_pad_kind_changed = nullptr;
jmethodID s_on_achievements_token = nullptr;
jmethodID s_store_memcard_sync_secret = nullptr;
jmethodID s_load_memcard_sync_secret = nullptr;
jmethodID s_has_memcard_sync_secret = nullptr;
jmethodID s_on_memcard_sync_changed = nullptr;
jmethodID s_on_achievements_changed = nullptr;
jmethodID s_on_achievements_login_expired = nullptr;
jmethodID s_on_achievements_sent = nullptr;
jmethodID s_on_achievement_refused = nullptr;
jmethodID s_on_achievement_notification = nullptr;
jmethodID s_on_achievement_toast = nullptr;
jmethodID s_on_achievement_indicator = nullptr;
jmethodID s_on_achievement_image = nullptr;
jmethodID s_on_play_sound = nullptr;
jclass s_achievements_account_class = nullptr;
jmethodID s_achievements_account_constructor = nullptr;
jclass s_achievements_queue_class = nullptr;
jmethodID s_achievements_queue_constructor = nullptr;
jclass s_game_achievements_class = nullptr;
jmethodID s_game_achievements_constructor = nullptr;
jclass s_achievement_class = nullptr;
jmethodID s_achievement_constructor = nullptr;
jclass s_achievement_section_class = nullptr;
jmethodID s_achievement_section_constructor = nullptr;
jclass s_leaderboard_class = nullptr;
jmethodID s_leaderboard_constructor = nullptr;
jclass s_leaderboard_entry_class = nullptr;
jmethodID s_leaderboard_entry_constructor = nullptr;
jclass s_leaderboard_page_class = nullptr;
jmethodID s_leaderboard_page_constructor = nullptr;
jmethodID s_on_leaderboard = nullptr;
jclass s_argb_image_class = nullptr;
jmethodID s_argb_image_constructor = nullptr;
jclass s_game_entry_class = nullptr;
jmethodID s_game_entry_constructor = nullptr;
jclass s_game_disc_class = nullptr;
jmethodID s_game_disc_constructor = nullptr;
jclass s_save_state_preview_class = nullptr;
jmethodID s_save_state_preview_constructor = nullptr;
jclass s_patch_code_class = nullptr;
jmethodID s_patch_code_constructor = nullptr;
jclass s_patch_codes_class = nullptr;
jmethodID s_patch_codes_constructor = nullptr;
jclass s_bios_image_class = nullptr;
jmethodID s_bios_image_constructor = nullptr;
jclass s_pad_binding_class = nullptr;
jclass s_shader_option_info_class = nullptr;
jmethodID s_shader_option_info_constructor = nullptr;
jclass s_stage_info_class = nullptr;
jmethodID s_stage_info_constructor = nullptr;
jclass s_favourite_info_class = nullptr;
jmethodID s_favourite_info_constructor = nullptr;
jmethodID s_pad_binding_constructor = nullptr;
jobject s_pad_binding_kinds[3] = {}; // PadBindingKind's Button, Axis and HalfAxis (global references)
jclass s_memcard_sync_state_class = nullptr;
jmethodID s_memcard_sync_state_constructor = nullptr;
jobject s_sync_status_kinds[7] = {}; // SyncStatusKind's values, in MemcardSync::StatusKind's order (global references)
jobject s_sync_activities[7] = {};   // SyncActivity's values, in MemcardSync::Activity's order (global references)
jclass s_sync_version_class = nullptr;
jmethodID s_sync_version_constructor = nullptr;
jclass s_sync_conflict_class = nullptr;
jmethodID s_sync_conflict_constructor = nullptr;
jmethodID s_runnable_run = nullptr;
jclass s_memcard_file_class = nullptr;
jmethodID s_memcard_file_constructor = nullptr;
jobject s_memcard_kinds[3] = {}; // MemcardKind's values, in MemcardEditorBridge::CardKind's order (global references)
jclass s_memcard_save_class = nullptr;
jmethodID s_memcard_save_constructor = nullptr;
jclass s_memcard_contents_class = nullptr;
jmethodID s_memcard_contents_constructor = nullptr;
jclass s_memcard_edit_class = nullptr;
jmethodID s_memcard_edit_constructor = nullptr;
jclass s_memcard_rename_check_class = nullptr;
jmethodID s_memcard_rename_check_constructor = nullptr;
jclass s_memcard_write_class = nullptr;
jmethodID s_memcard_write_constructor = nullptr;
jobject s_memcard_write_outcomes[4] = {}; // MemcardWriteOutcome's values, in WriteOutcome's order (global references)

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

// The registered NativeCallbacks as a local reference, or null with none.
jobject GetCallbacks(JNIEnv* env)
{
  std::unique_lock lock(s_callbacks_mutex);
  return s_callbacks ? env->NewLocalRef(s_callbacks) : nullptr;
}

// An ArgbImage of width x height RGBA8 pixels (0xAABBGGRR), or null (with no exception pending) if it can't be made.
// Each becomes 0xAARRGGBB, as Bitmap.createBitmap() takes it; opaque forces alpha to 0xFF.
jobject ToArgbImage(JNIEnv* env, const u32* rgba, u32 width, u32 height, bool opaque)
{
  const size_t count = static_cast<size_t>(width) * height;
  std::vector<jint> argb(count);
  for (size_t i = 0; i < count; i++)
  {
    const u32 p = rgba[i];
    const u32 a = opaque ? 0xFF000000u : (p & 0xFF000000u);
    argb[i] = static_cast<jint>(a | ((p & 0xFFu) << 16) | (p & 0xFF00u) | ((p >> 16) & 0xFFu));
  }

  jintArray pixels = env->NewIntArray(static_cast<jsize>(count));
  if (!pixels)
  {
    ClearException(env);
    return nullptr;
  }

  env->SetIntArrayRegion(pixels, 0, static_cast<jsize>(count), argb.data());
  jobject image = env->NewObject(s_argb_image_class, s_argb_image_constructor, static_cast<jint>(width),
                                 static_cast<jint>(height), pixels);
  env->DeleteLocalRef(pixels);
  if (ClearException(env))
    return nullptr;

  return image;
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
// A PadBinding[] of bindings, or null (with no exception pending) for none or if it can't be made.
jobjectArray ToPadBindingArray(JNIEnv* env, const std::optional<std::vector<AndroidHost::PadBinding>>& bindings)
{
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
    jstring category = ToJString(env, binding.category);
    jobject entry = nullptr;
    if (name && display_name && category && (value || !binding.binding.has_value()))
    {
      entry = env->NewObject(s_pad_binding_class, s_pad_binding_constructor, name, display_name,
                             s_pad_binding_kinds[static_cast<size_t>(binding.kind)], value, category);
      if (ClearException(env))
        entry = nullptr;
    }

    for (jobject ref : {static_cast<jobject>(name), static_cast<jobject>(display_name), static_cast<jobject>(value),
                        static_cast<jobject>(category)})
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

void AndroidHost::NotifyGameStarted(std::string_view title, std::string_view serial, bool start_paused,
                                    bool pause_on_disconnect)
{
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject callbacks = GetCallbacks(env.get());
  if (!callbacks)
    return;

  jstring jtitle = ToJString(env.get(), title);
  jstring jserial = ToJString(env.get(), serial);
  if (jtitle && jserial)
  {
    env->CallVoidMethod(callbacks, s_on_game_started, jtitle, jserial, start_paused ? JNI_TRUE : JNI_FALSE,
                        pause_on_disconnect ? JNI_TRUE : JNI_FALSE);
    ClearException(env.get());
  }

  if (jserial)
    env->DeleteLocalRef(jserial);
  if (jtitle)
    env->DeleteLocalRef(jtitle);
  env->DeleteLocalRef(callbacks);
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

void AndroidHost::NotifyRumble(s32 device_id, float large, float small)
{
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject callbacks = GetCallbacks(env.get());
  if (!callbacks)
    return;

  env->CallVoidMethod(callbacks, s_on_rumble, static_cast<jint>(device_id), large, small);
  ClearException(env.get());
  env->DeleteLocalRef(callbacks);
}

void AndroidHost::NotifyHotkey(std::string_view name)
{
  CallCallback(s_on_hotkey, name);
}

void AndroidHost::NotifyOsdMessage(std::string_view key, std::string_view text, float seconds, const u32* image_pixels,
                                   u32 image_width, u32 image_height)
{
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject callbacks = GetCallbacks(env.get());
  if (!callbacks)
    return;

  jstring jkey = ToJString(env.get(), key);
  jstring jtext = ToJString(env.get(), text);
  jobject icon = image_pixels ? ToArgbImage(env.get(), image_pixels, image_width, image_height, false) : nullptr;
  if (jkey && jtext)
  {
    env->CallVoidMethod(callbacks, s_on_osd_message, jkey, jtext, static_cast<jfloat>(seconds), icon);
    ClearException(env.get());
  }

  if (icon)
    env->DeleteLocalRef(icon);
  if (jtext)
    env->DeleteLocalRef(jtext);
  if (jkey)
    env->DeleteLocalRef(jkey);
  env->DeleteLocalRef(callbacks);
}

void AndroidHost::NotifyAchievementsToken(std::optional<std::string_view> token)
{
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject callbacks = GetCallbacks(env.get());
  if (!callbacks)
    return;

  jstring jtoken = token.has_value() ? ToJString(env.get(), token.value()) : nullptr;
  if (!token.has_value() || jtoken)
  {
    env->CallVoidMethod(callbacks, s_on_achievements_token, jtoken);
    ClearException(env.get());
  }

  if (jtoken)
    env->DeleteLocalRef(jtoken);
  env->DeleteLocalRef(callbacks);
}

namespace {
/// Java strings for a callback's arguments, deleted with this. ok() is false if one didn't convert.
class LocalStrings
{
public:
  LocalStrings(JNIEnv* env, std::initializer_list<std::string_view> strings) : m_env(env)
  {
    for (std::string_view str : strings)
      m_strings.push_back(ToJString(env, str));
  }

  ~LocalStrings()
  {
    for (jstring str : m_strings)
    {
      if (str)
        m_env->DeleteLocalRef(str);
    }
  }

  bool ok() const
  {
    return std::all_of(m_strings.begin(), m_strings.end(), [](jstring str) { return str != nullptr; });
  }

  jstring operator[](size_t index) const { return m_strings[index]; }

private:
  JNIEnv* m_env;
  std::vector<jstring> m_strings;
};

/// Runs call(env, callbacks) if the app has set its callbacks, on any thread.
template<typename F>
void WithCallbacks(F&& call)
{
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject callbacks = GetCallbacks(env.get());
  if (!callbacks)
    return;

  call(env.get(), callbacks);
  ClearException(env.get());
  env->DeleteLocalRef(callbacks);
}
} // namespace

bool AndroidHost::StoreMemcardSyncSecret(std::string_view name, std::optional<std::string_view> value)
{
  ScopedJNIEnv env;
  if (!env)
    return false;

  jobject callbacks = GetCallbacks(env.get());
  if (!callbacks)
    return false;

  bool stored = false;
  jstring jname = ToJString(env.get(), name);
  jstring jvalue = value.has_value() ? ToJString(env.get(), value.value()) : nullptr;
  if (jname && (!value.has_value() || jvalue))
  {
    stored = env->CallBooleanMethod(callbacks, s_store_memcard_sync_secret, jname, jvalue) == JNI_TRUE;
    if (ClearException(env.get()))
      stored = false;
  }
  else
  {
    ClearException(env.get());
  }

  if (jvalue)
    env->DeleteLocalRef(jvalue);
  if (jname)
    env->DeleteLocalRef(jname);
  env->DeleteLocalRef(callbacks);
  return stored;
}

std::optional<std::string> AndroidHost::LoadMemcardSyncSecret(std::string_view name)
{
  ScopedJNIEnv env;
  if (!env)
    return std::nullopt;

  jobject callbacks = GetCallbacks(env.get());
  if (!callbacks)
    return std::nullopt;

  std::optional<std::string> value;
  jstring jname = ToJString(env.get(), name);
  if (jname)
  {
    jstring jvalue = static_cast<jstring>(env->CallObjectMethod(callbacks, s_load_memcard_sync_secret, jname));
    if (!ClearException(env.get()) && jvalue)
      value = FromJString(env.get(), jvalue);
    if (jvalue)
      env->DeleteLocalRef(jvalue);
    env->DeleteLocalRef(jname);
  }
  else
  {
    ClearException(env.get());
  }

  env->DeleteLocalRef(callbacks);
  return value;
}

void AndroidHost::NotifyMemcardSyncChanged()
{
  CallCallback(s_on_memcard_sync_changed, std::nullopt);
}

bool AndroidHost::HasMemcardSyncSecret(std::string_view name)
{
  ScopedJNIEnv env;
  if (!env)
    return false;

  jobject callbacks = GetCallbacks(env.get());
  if (!callbacks)
    return false;

  bool has = false;
  jstring jname = ToJString(env.get(), name);
  if (jname)
  {
    has = env->CallBooleanMethod(callbacks, s_has_memcard_sync_secret, jname) == JNI_TRUE;
    if (ClearException(env.get()))
      has = false;
    env->DeleteLocalRef(jname);
  }
  else
  {
    ClearException(env.get());
  }

  env->DeleteLocalRef(callbacks);
  return has;
}

void AndroidHost::NotifyAchievementsChanged()
{
  WithCallbacks([](JNIEnv* env, jobject callbacks) { env->CallVoidMethod(callbacks, s_on_achievements_changed); });
}

void AndroidHost::NotifyAchievementsLoginExpired()
{
  WithCallbacks(
    [](JNIEnv* env, jobject callbacks) { env->CallVoidMethod(callbacks, s_on_achievements_login_expired); });
}

void AndroidHost::NotifyAchievementsSent(u32 count)
{
  WithCallbacks([count](JNIEnv* env, jobject callbacks) {
    env->CallVoidMethod(callbacks, s_on_achievements_sent, static_cast<jint>(count));
  });
}

void AndroidHost::NotifyAchievementRefused(std::string_view title)
{
  WithCallbacks([&](JNIEnv* env, jobject callbacks) {
    const LocalStrings strings(env, {title});
    if (strings.ok())
      env->CallVoidMethod(callbacks, s_on_achievement_refused, strings[0]);
  });
}

void AndroidHost::NotifyAchievementNotification(std::string_view key, std::string_view title, std::string_view text,
                                                std::string_view image_path, float seconds, s32 points, bool offline)
{
  WithCallbacks([&](JNIEnv* env, jobject callbacks) {
    const LocalStrings strings(env, {key, title, text, image_path});
    if (strings.ok())
    {
      env->CallVoidMethod(callbacks, s_on_achievement_notification, strings[0], strings[1], strings[2], strings[3],
                          static_cast<jfloat>(seconds), static_cast<jint>(points), static_cast<jboolean>(offline));
    }
  });
}

void AndroidHost::NotifyAchievementToast(std::string_view title, std::string_view message, float seconds)
{
  WithCallbacks([&](JNIEnv* env, jobject callbacks) {
    const LocalStrings strings(env, {title, message});
    if (strings.ok())
      env->CallVoidMethod(callbacks, s_on_achievement_toast, strings[0], strings[1], static_cast<jfloat>(seconds));
  });
}

void AndroidHost::NotifyAchievementIndicator(s32 kind, u32 id, bool shown, std::string_view text,
                                             std::string_view badge_path)
{
  WithCallbacks([&](JNIEnv* env, jobject callbacks) {
    const LocalStrings strings(env, {text, badge_path});
    if (strings.ok())
    {
      env->CallVoidMethod(callbacks, s_on_achievement_indicator, static_cast<jint>(kind), static_cast<jint>(id),
                          static_cast<jboolean>(shown), strings[0], strings[1]);
    }
  });
}

void AndroidHost::NotifyAchievementImage(std::string_view path)
{
  WithCallbacks([&](JNIEnv* env, jobject callbacks) {
    const LocalStrings strings(env, {path});
    if (strings.ok())
      env->CallVoidMethod(callbacks, s_on_achievement_image, strings[0]);
  });
}

namespace {
/// A LeaderboardEntry[] of entries, or nullptr (with no exception pending) if one can't be made.
jobjectArray ToEntryArray(JNIEnv* env, const std::vector<AchievementsBridge::LeaderboardEntry>& entries)
{
  jobjectArray ret = env->NewObjectArray(static_cast<jsize>(entries.size()), s_leaderboard_entry_class, nullptr);
  if (!ret)
  {
    ClearException(env);
    return nullptr;
  }

  for (size_t i = 0; i < entries.size(); i++)
  {
    const LocalStrings strings(env, {entries[i].user, entries[i].score});
    jobject entry = strings.ok() ? env->NewObject(s_leaderboard_entry_class, s_leaderboard_entry_constructor,
                                                  static_cast<jint>(entries[i].rank), strings[0], strings[1]) :
                                   nullptr;
    if (!entry)
    {
      ClearException(env);
      env->DeleteLocalRef(ret);
      return nullptr;
    }
    env->SetObjectArrayElement(ret, static_cast<jsize>(i), entry);
    env->DeleteLocalRef(entry);
  }

  return ret;
}
} // namespace

void AndroidHost::NotifyLeaderboard(s32 request, const AchievementsBridge::LeaderboardPage* page,
                                    std::string_view error)
{
  WithCallbacks([&](JNIEnv* env, jobject callbacks) {
    jobject jpage = nullptr;
    if (page)
    {
      jobjectArray top = ToEntryArray(env, page->top);
      jobjectArray around = top ? ToEntryArray(env, page->around) : nullptr;
      if (top && around)
      {
        jpage = env->NewObject(s_leaderboard_page_class, s_leaderboard_page_constructor,
                               static_cast<jint>(page->total), top, around, static_cast<jint>(page->you_index));
        if (!jpage)
          ClearException(env);
      }
      if (around)
        env->DeleteLocalRef(around);
      if (top)
        env->DeleteLocalRef(top);
    }

    const LocalStrings message(env, {page && jpage ? std::string_view() : error});
    env->CallVoidMethod(callbacks, s_on_leaderboard, static_cast<jint>(request), jpage,
                        (page && jpage) ? nullptr : message[0]);
    if (jpage)
      env->DeleteLocalRef(jpage);
  });
}

void AndroidHost::NotifyPlaySound(const char* path)
{
  WithCallbacks([&](JNIEnv* env, jobject callbacks) {
    const LocalStrings strings(env, {path});
    if (strings.ok())
      env->CallVoidMethod(callbacks, s_on_play_sound, strings[0]);
  });
}

void AndroidHost::NotifyFastForwardChanged(bool enabled)
{
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject callbacks = GetCallbacks(env.get());
  if (!callbacks)
    return;

  env->CallVoidMethod(callbacks, s_on_fast_forward_changed, static_cast<jboolean>(enabled));
  ClearException(env.get());
  env->DeleteLocalRef(callbacks);
}

void AndroidHost::NotifyPadKindChanged(s32 kind)
{
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject callbacks = GetCallbacks(env.get());
  if (!callbacks)
    return;

  env->CallVoidMethod(callbacks, s_on_pad_kind_changed, static_cast<jint>(kind));
  ClearException(env.get());
  env->DeleteLocalRef(callbacks);
}

namespace {
// Global references to the constants called names of the enum class_name, in that order, in out.
template<size_t N>
bool LoadEnumValues(JNIEnv* env, const char* class_name, const char* const (&names)[N], jobject (&out)[N])
{
  jclass cls = env->FindClass(class_name);
  if (!cls)
  {
    ClearException(env);
    return false;
  }

  const std::string signature = std::string("L") + class_name + ";";
  for (size_t i = 0; i < N; i++)
  {
    jfieldID field = env->GetStaticFieldID(cls, names[i], signature.c_str());
    jobject value = field ? env->GetStaticObjectField(cls, field) : nullptr;
    if (!value)
    {
      ClearException(env);
      env->DeleteLocalRef(cls);
      return false;
    }

    out[i] = env->NewGlobalRef(value);
    env->DeleteLocalRef(value);
  }

  env->DeleteLocalRef(cls);
  return true;
}
} // namespace

// Each of an enum's values, its settings.ini name then its display name, through the core's own functions.
template<typename T>
static void AppendSettingNames(std::vector<std::string>& names, const char* (*name)(T), const char* (*display)(T))
{
  for (u32 i = 0; i < static_cast<u32>(T::Count); i++)
  {
    names.emplace_back(name(static_cast<T>(i)));
    names.emplace_back(display(static_cast<T>(i)));
  }
}

// Kotlin takes these arrays as never null: a failure gives an empty one.
static jobjectArray ToNonNullStringArray(JNIEnv* env, const std::vector<std::string>& strings)
{
  jobjectArray result = ToJStringArray(env, strings);
  if (!result)
  {
    result = env->NewObjectArray(0, s_string_class, nullptr);
    ClearException(env);
  }

  return result;
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
  s_on_game_started =
    env->GetMethodID(s_callbacks_class, "onGameStarted", "(Ljava/lang/String;Ljava/lang/String;ZZ)V");
  s_on_game_stopped = env->GetMethodID(s_callbacks_class, "onGameStopped", "()V");
  s_on_error = env->GetMethodID(s_callbacks_class, "onError", "(Ljava/lang/String;)V");
  s_on_binding_captured = env->GetMethodID(s_callbacks_class, "onBindingCaptured", "(Ljava/lang/String;)V");
  s_on_hotkey = env->GetMethodID(s_callbacks_class, "onHotkey", "(Ljava/lang/String;)V");
  s_on_rumble = env->GetMethodID(s_callbacks_class, "onRumble", "(IFF)V");
  s_on_osd_message =
    env->GetMethodID(s_callbacks_class, "onOsdMessage",
                     "(Ljava/lang/String;Ljava/lang/String;FLio/github/ilyalissoboi/goosestation/core/ArgbImage;)V");
  s_on_fast_forward_changed = env->GetMethodID(s_callbacks_class, "onFastForwardChanged", "(Z)V");
  s_on_pad_kind_changed = env->GetMethodID(s_callbacks_class, "onPadKindChanged", "(I)V");
  s_on_achievements_token = env->GetMethodID(s_callbacks_class, "onAchievementsToken", "(Ljava/lang/String;)V");
  s_store_memcard_sync_secret =
    env->GetMethodID(s_callbacks_class, "storeMemcardSyncSecret", "(Ljava/lang/String;Ljava/lang/String;)Z");
  s_load_memcard_sync_secret =
    env->GetMethodID(s_callbacks_class, "loadMemcardSyncSecret", "(Ljava/lang/String;)Ljava/lang/String;");
  s_has_memcard_sync_secret = env->GetMethodID(s_callbacks_class, "hasMemcardSyncSecret", "(Ljava/lang/String;)Z");
  s_on_memcard_sync_changed = env->GetMethodID(s_callbacks_class, "onMemcardSyncChanged", "()V");
  s_on_achievements_changed = env->GetMethodID(s_callbacks_class, "onAchievementsChanged", "()V");
  s_on_achievements_login_expired = env->GetMethodID(s_callbacks_class, "onAchievementsLoginExpired", "()V");
  s_on_achievements_sent = env->GetMethodID(s_callbacks_class, "onAchievementsSent", "(I)V");
  s_on_achievement_refused = env->GetMethodID(s_callbacks_class, "onAchievementRefused", "(Ljava/lang/String;)V");
  s_on_achievement_notification =
    env->GetMethodID(s_callbacks_class, "onAchievementNotification",
                     "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;FIZ)V");
  s_on_achievement_toast =
    env->GetMethodID(s_callbacks_class, "onAchievementToast", "(Ljava/lang/String;Ljava/lang/String;F)V");
  s_on_achievement_indicator =
    env->GetMethodID(s_callbacks_class, "onAchievementIndicator", "(IIZLjava/lang/String;Ljava/lang/String;)V");
  s_on_achievement_image = env->GetMethodID(s_callbacks_class, "onAchievementImage", "(Ljava/lang/String;)V");
  s_on_play_sound = env->GetMethodID(s_callbacks_class, "onPlaySound", "(Ljava/lang/String;)V");
  s_on_leaderboard =
    env->GetMethodID(s_callbacks_class, "onLeaderboard",
                     "(ILio/github/ilyalissoboi/goosestation/core/LeaderboardPage;Ljava/lang/String;)V");
  if (!s_on_game_started || !s_on_game_stopped || !s_on_error || !s_on_binding_captured || !s_on_hotkey || !s_on_rumble ||
      !s_on_osd_message ||
      !s_on_fast_forward_changed || !s_on_pad_kind_changed || !s_on_achievements_token || !s_on_achievements_changed ||
      !s_on_achievements_login_expired || !s_on_achievements_sent || !s_on_achievement_refused ||
      !s_on_achievement_notification || !s_on_achievement_toast ||
      !s_on_achievement_indicator || !s_on_achievement_image || !s_on_play_sound || !s_on_leaderboard)
  {
    ClearException(env);
    return JNI_ERR;
  }

  jclass argb_image_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/ArgbImage");
  if (!argb_image_class)
  {
    ClearException(env);
    return JNI_ERR;
  }

  s_argb_image_class = static_cast<jclass>(env->NewGlobalRef(argb_image_class));
  env->DeleteLocalRef(argb_image_class);
  s_argb_image_constructor = env->GetMethodID(s_argb_image_class, "<init>", "(II[I)V");
  if (!s_argb_image_constructor)
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
                     "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJLjava/lang/String;"
                     "[Lio/github/ilyalissoboi/goosestation/core/GameDisc;ZJJI)V");
  if (!s_game_entry_constructor)
  {
    ClearException(env);
    return JNI_ERR;
  }

  jclass game_disc_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/GameDisc");
  if (!game_disc_class)
  {
    ClearException(env);
    return JNI_ERR;
  }

  s_game_disc_class = static_cast<jclass>(env->NewGlobalRef(game_disc_class));
  env->DeleteLocalRef(game_disc_class);
  s_game_disc_constructor =
    env->GetMethodID(s_game_disc_class, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
  if (!s_game_disc_constructor)
  {
    ClearException(env);
    return JNI_ERR;
  }

  for (const auto& [name, signature, cls, constructor] :
       {std::tuple("io/github/ilyalissoboi/goosestation/core/PatchCode", "(ILjava/lang/String;Ljava/lang/String;ZZ)V",
                   &s_patch_code_class, &s_patch_code_constructor),
        std::tuple("io/github/ilyalissoboi/goosestation/core/PatchCodes",
                   "(Ljava/lang/String;[Lio/github/ilyalissoboi/goosestation/core/PatchCode;)V", &s_patch_codes_class,
                   &s_patch_codes_constructor),
        std::tuple("io/github/ilyalissoboi/goosestation/core/AchievementsAccount",
                   "(ILjava/lang/String;Ljava/lang/String;II)V", &s_achievements_account_class,
                   &s_achievements_account_constructor),
        std::tuple("io/github/ilyalissoboi/goosestation/core/AchievementsQueue", "(I[Ljava/lang/String;[I)V",
                   &s_achievements_queue_class, &s_achievements_queue_constructor),
        std::tuple("io/github/ilyalissoboi/goosestation/core/GameAchievements", "(IIIIIZZZZI)V",
                   &s_game_achievements_class, &s_game_achievements_constructor),
        std::tuple("io/github/ilyalissoboi/goosestation/core/Achievement",
                   "(ILjava/lang/String;Ljava/lang/String;ILjava/lang/String;JLjava/lang/String;F)V",
                   &s_achievement_class, &s_achievement_constructor),
        std::tuple("io/github/ilyalissoboi/goosestation/core/AchievementSection",
                   "(ILjava/lang/String;[Lio/github/ilyalissoboi/goosestation/core/Achievement;)V",
                   &s_achievement_section_class, &s_achievement_section_constructor),
        std::tuple("io/github/ilyalissoboi/goosestation/core/Leaderboard", "(ILjava/lang/String;Ljava/lang/String;)V",
                   &s_leaderboard_class, &s_leaderboard_constructor),
        std::tuple("io/github/ilyalissoboi/goosestation/core/LeaderboardEntry",
                   "(ILjava/lang/String;Ljava/lang/String;)V", &s_leaderboard_entry_class,
                   &s_leaderboard_entry_constructor),
        std::tuple("io/github/ilyalissoboi/goosestation/core/LeaderboardPage",
                   "(I[Lio/github/ilyalissoboi/goosestation/core/LeaderboardEntry;"
                   "[Lio/github/ilyalissoboi/goosestation/core/LeaderboardEntry;I)V",
                   &s_leaderboard_page_class, &s_leaderboard_page_constructor)})
  {
    jclass local = env->FindClass(name);
    if (!local)
    {
      ClearException(env);
      return JNI_ERR;
    }

    *cls = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    *constructor = env->GetMethodID(*cls, "<init>", signature);
    if (!*constructor)
    {
      ClearException(env);
      return JNI_ERR;
    }
  }

  jclass save_state_preview_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/SaveStatePreview");
  if (!save_state_preview_class)
  {
    ClearException(env);
    return JNI_ERR;
  }

  s_save_state_preview_class = static_cast<jclass>(env->NewGlobalRef(save_state_preview_class));
  env->DeleteLocalRef(save_state_preview_class);
  s_save_state_preview_constructor = env->GetMethodID(s_save_state_preview_class, "<init>",
                                                      "(Lio/github/ilyalissoboi/goosestation/core/ArgbImage;ILjava/lang/String;"
                                                      "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
  if (!s_save_state_preview_constructor)
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

  jclass shader_option_info_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/ShaderOptionInfo");
  jclass stage_info_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/StageInfo");
  if (!shader_option_info_class || !stage_info_class)
  {
    ClearException(env);
    return JNI_ERR;
  }
  s_shader_option_info_class = static_cast<jclass>(env->NewGlobalRef(shader_option_info_class));
  env->DeleteLocalRef(shader_option_info_class);
  s_stage_info_class = static_cast<jclass>(env->NewGlobalRef(stage_info_class));
  env->DeleteLocalRef(stage_info_class);
  s_shader_option_info_constructor =
    env->GetMethodID(s_shader_option_info_class, "<init>",
                     "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II[F[F[F[F[F"
                     "[Ljava/lang/String;)V");
  s_stage_info_constructor =
    env->GetMethodID(s_stage_info_class, "<init>",
                     "(Ljava/lang/String;Ljava/lang/String;[Lio/github/ilyalissoboi/goosestation/core/ShaderOptionInfo;"
                     "Ljava/lang/String;)V");
  if (!s_shader_option_info_constructor || !s_stage_info_constructor)
  {
    ClearException(env);
    return JNI_ERR;
  }

  jclass favourite_info_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/FavouriteInfo");
  if (!favourite_info_class)
  {
    ClearException(env);
    return JNI_ERR;
  }
  s_favourite_info_class = static_cast<jclass>(env->NewGlobalRef(favourite_info_class));
  env->DeleteLocalRef(favourite_info_class);
  s_favourite_info_constructor =
    env->GetMethodID(s_favourite_info_class, "<init>", "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V");
  if (!s_favourite_info_constructor)
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
                     "Ljava/lang/String;Ljava/lang/String;)V");
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

  static constexpr const char* SYNC_STATUS_KIND_NAMES[] = {"Unavailable", "Applying", "StartError", "Off",
                                                           "Incomplete",  "Status",   "Idle"};
  static constexpr const char* SYNC_ACTIVITY_NAMES[] = {"None",   "Syncing", "Synced",      "Offline",
                                                        "Failed", "Locked",  "AuthRequired"};
  if (!LoadEnumValues(env, "io/github/ilyalissoboi/goosestation/core/SyncStatusKind", SYNC_STATUS_KIND_NAMES,
                      s_sync_status_kinds) ||
      !LoadEnumValues(env, "io/github/ilyalissoboi/goosestation/core/SyncActivity", SYNC_ACTIVITY_NAMES,
                      s_sync_activities))
  {
    return JNI_ERR;
  }

  jclass memcard_sync_state_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/MemcardSyncState");
  if (!memcard_sync_state_class)
  {
    ClearException(env);
    return JNI_ERR;
  }

  s_memcard_sync_state_class = static_cast<jclass>(env->NewGlobalRef(memcard_sync_state_class));
  env->DeleteLocalRef(memcard_sync_state_class);
  s_memcard_sync_state_constructor =
    env->GetMethodID(s_memcard_sync_state_class, "<init>",
                     "(Lio/github/ilyalissoboi/goosestation/core/SyncStatusKind;ZZZLjava/lang/String;Ljava/lang/String;"
                     "Ljava/lang/String;Ljava/lang/String;ZZZLjava/lang/String;Ljava/lang/String;"
                     "Lio/github/ilyalissoboi/goosestation/core/SyncActivity;I)V");
  if (!s_memcard_sync_state_constructor)
  {
    ClearException(env);
    return JNI_ERR;
  }

  jclass sync_version_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/SyncVersion");
  jclass sync_conflict_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/SyncConflict");
  jclass runnable_class = env->FindClass("java/lang/Runnable");
  if (!sync_version_class || !sync_conflict_class || !runnable_class)
  {
    ClearException(env);
    return JNI_ERR;
  }

  s_sync_version_class = static_cast<jclass>(env->NewGlobalRef(sync_version_class));
  s_sync_conflict_class = static_cast<jclass>(env->NewGlobalRef(sync_conflict_class));
  s_sync_version_constructor = env->GetMethodID(s_sync_version_class, "<init>", "(Ljava/lang/String;JZIZ)V");
  s_sync_conflict_constructor =
    env->GetMethodID(s_sync_conflict_class, "<init>",
                     "(JLjava/lang/String;Lio/github/ilyalissoboi/goosestation/core/SyncVersion;"
                     "[Lio/github/ilyalissoboi/goosestation/core/SyncVersion;I)V");
  s_runnable_run = env->GetMethodID(runnable_class, "run", "()V");
  env->DeleteLocalRef(sync_version_class);
  env->DeleteLocalRef(sync_conflict_class);
  env->DeleteLocalRef(runnable_class);
  if (!s_sync_version_constructor || !s_sync_conflict_constructor || !s_runnable_run)
  {
    ClearException(env);
    return JNI_ERR;
  }

  static constexpr const char* MEMCARD_KIND_NAMES[] = {"Game", "Shared", "Other"};
  if (!LoadEnumValues(env, "io/github/ilyalissoboi/goosestation/core/MemcardKind", MEMCARD_KIND_NAMES, s_memcard_kinds))
    return JNI_ERR;
  jclass memcard_file_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/MemcardFile");
  if (!memcard_file_class)
  {
    ClearException(env);
    return JNI_ERR;
  }
  s_memcard_file_class = static_cast<jclass>(env->NewGlobalRef(memcard_file_class));
  env->DeleteLocalRef(memcard_file_class);
  s_memcard_file_constructor =
    env->GetMethodID(s_memcard_file_class, "<init>",
                     "(Ljava/lang/String;Ljava/lang/String;Lio/github/ilyalissoboi/goosestation/core/MemcardKind;Z)V");
  if (!s_memcard_file_constructor)
  {
    ClearException(env);
    return JNI_ERR;
  }

  static constexpr const char* MEMCARD_WRITE_OUTCOME_NAMES[] = {"Saved", "Changed", "Busy", "Failed"};
  if (!LoadEnumValues(env, "io/github/ilyalissoboi/goosestation/core/MemcardWriteOutcome", MEMCARD_WRITE_OUTCOME_NAMES,
                      s_memcard_write_outcomes))
  {
    return JNI_ERR;
  }
  // The memory card editor's classes, each with the constructor native code makes it with.
  const auto load_class = [env](const char* name, const char* signature, jclass* cls, jmethodID* constructor) {
    jclass local = env->FindClass(name);
    if (local)
    {
      *cls = static_cast<jclass>(env->NewGlobalRef(local));
      env->DeleteLocalRef(local);
      *constructor = env->GetMethodID(*cls, "<init>", signature);
    }
    if (!local || !*constructor)
    {
      ClearException(env);
      return false;
    }
    return true;
  };
  if (!load_class("io/github/ilyalissoboi/goosestation/core/MemcardSave",
                  "(ILjava/lang/String;Ljava/lang/String;IZLio/github/ilyalissoboi/goosestation/core/ArgbImage;)V",
                  &s_memcard_save_class, &s_memcard_save_constructor) ||
      !load_class("io/github/ilyalissoboi/goosestation/core/MemcardContents",
                  "(I[Lio/github/ilyalissoboi/goosestation/core/MemcardSave;)V", &s_memcard_contents_class,
                  &s_memcard_contents_constructor) ||
      !load_class("io/github/ilyalissoboi/goosestation/core/MemcardEdit", "([BLjava/lang/String;)V",
                  &s_memcard_edit_class, &s_memcard_edit_constructor) ||
      !load_class("io/github/ilyalissoboi/goosestation/core/MemcardRenameCheck", "(IIZZZ)V",
                  &s_memcard_rename_check_class, &s_memcard_rename_check_constructor) ||
      !load_class("io/github/ilyalissoboi/goosestation/core/MemcardWrite",
                  "(Lio/github/ilyalissoboi/goosestation/core/MemcardWriteOutcome;Ljava/lang/String;)V",
                  &s_memcard_write_class, &s_memcard_write_constructor))
  {
    return JNI_ERR;
  }

  if (!AndroidContentProvider::LoadJNIReferences(env) || !HTTPDownloaderAndroid::LoadJNIReferences(env))
    return JNI_ERR;

  JNIUtil::SetJavaVM(vm);
  FileSystem::SetContentProvider(&s_content_provider);
  PlatformMisc::SetSoundPlayer(&AndroidHost::NotifyPlaySound);
  return JNI_VERSION_1_6;
}

#define NATIVE_LIBRARY_FUNCTION(ret, name) \
  JNIEXPORT ret JNICALL Java_io_github_ilyalissoboi_goosestation_core_NativeLibrary_##name

NATIVE_LIBRARY_FUNCTION(void, setShaderPacksFolder)(JNIEnv* env, jobject, jstring path)
{
  AndroidHost::SetShaderPacksFolder(FromJString(env, path));
}

NATIVE_LIBRARY_FUNCTION(jboolean, initialize)(JNIEnv* env, jobject, jstring data_root, jstring resources_root,
                                               jstring bios_dir, jstring install_id, jstring achievements_token,
                                               jstring achievements_folder, jstring device_name)
{
  Error error;
  if (!AndroidHost::Initialize(FromJString(env, data_root), FromJString(env, resources_root),
                               FromJString(env, bios_dir), FromJString(env, install_id),
                               achievements_token ? FromJString(env, achievements_token) : std::string(),
                               FromJString(env, achievements_folder), FromJString(env, device_name), &error))
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

NATIVE_LIBRARY_FUNCTION(jfloat, getFloatSetting)(JNIEnv* env, jobject, jstring section, jstring key,
                                                jfloat default_value)
{
  const std::string section_str = FromJString(env, section);
  const std::string key_str = FromJString(env, key);
  return AndroidHost::GetFloatSetting(section_str.c_str(), key_str.c_str(), default_value);
}

NATIVE_LIBRARY_FUNCTION(void, setFloatSetting)(JNIEnv* env, jobject, jstring section, jstring key, jfloat value)
{
  const std::string section_str = FromJString(env, section);
  const std::string key_str = FromJString(env, key);
  AndroidHost::SetFloatSetting(section_str.c_str(), key_str.c_str(), value);
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getSettingNames)(JNIEnv* env, jobject, jstring kind)
{
  using Append = void (*)(std::vector<std::string>&);
  static const std::unordered_map<std::string_view, Append> lists = {
    {"ConsoleRegion",
     [](auto& n) { AppendSettingNames(n, &Settings::GetConsoleRegionName, &Settings::GetConsoleRegionDisplayName); }},
    {"CPUExecutionMode",
     [](auto& n) {
       AppendSettingNames(n, &Settings::GetCPUExecutionModeName, &Settings::GetCPUExecutionModeDisplayName);
     }},
    {"GPURenderer",
     [](auto& n) { AppendSettingNames(n, &Settings::GetRendererName, &Settings::GetRendererDisplayName); }},
    {"GPUTextureFilter",
     [](auto& n) { AppendSettingNames(n, &Settings::GetTextureFilterName, &Settings::GetTextureFilterDisplayName); }},
    {"GPUDownsampleMode",
     [](auto& n) { AppendSettingNames(n, &Settings::GetDownsampleModeName, &Settings::GetDownsampleModeDisplayName); }},
    {"GPULineDetectMode",
     [](auto& n) {
       AppendSettingNames(n, &Settings::GetLineDetectModeName, &Settings::GetLineDetectModeDisplayName);
     }},
    {"DisplayAspectRatio",
     [](auto& n) {
       AppendSettingNames(n, &Settings::GetDisplayAspectRatioName, &Settings::GetDisplayAspectRatioDisplayName);
     }},
    {"DisplayDeinterlacingMode",
     [](auto& n) {
       AppendSettingNames(n, &Settings::GetDisplayDeinterlacingModeName,
                          &Settings::GetDisplayDeinterlacingModeDisplayName);
     }},
    {"DisplayCropMode",
     [](auto& n) { AppendSettingNames(n, &Settings::GetDisplayCropModeName, &Settings::GetDisplayCropModeDisplayName); }},
    {"DisplayScalingMode",
     [](auto& n) { AppendSettingNames(n, &Settings::GetDisplayScalingName, &Settings::GetDisplayScalingDisplayName); }},
    {"DisplayAlignment",
     [](auto& n) {
       AppendSettingNames(n, &Settings::GetDisplayAlignmentName, &Settings::GetDisplayAlignmentDisplayName);
     }},
    {"DisplayRotation",
     [](auto& n) {
       AppendSettingNames(n, &Settings::GetDisplayRotationName, &Settings::GetDisplayRotationDisplayName);
     }},
    {"DisplayScreenshotMode",
     [](auto& n) {
       AppendSettingNames(n, &Settings::GetDisplayScreenshotModeName, &Settings::GetDisplayScreenshotModeDisplayName);
     }},
    {"DisplayScreenshotFormat",
     [](auto& n) {
       AppendSettingNames(n, &Settings::GetDisplayScreenshotFormatName,
                          &Settings::GetDisplayScreenshotFormatDisplayName);
     }},
    {"AudioStretchMode",
     [](auto& n) { AppendSettingNames(n, &AudioStream::GetStretchModeName, &AudioStream::GetStretchModeDisplayName); }},
    {"MemoryCardType",
     [](auto& n) { AppendSettingNames(n, &Settings::GetMemoryCardTypeName, &Settings::GetMemoryCardTypeDisplayName); }},
    {"ControllerType",
     [](auto& n) {
       // Not those that need a pointer, which Android has none of.
       for (auto& [name, display_name] : Controller::GetControllerTypeNames())
       {
         const Controller::ControllerInfo* info = Controller::GetControllerInfo(name);
         if (!info || info->type == ControllerType::GunCon || info->type == ControllerType::Justifier ||
             info->type == ControllerType::PlayStationMouse)
         {
           continue;
         }

         n.push_back(std::move(name));
         n.push_back(std::move(display_name));
       }
     }},
    {"MultitapMode",
     [](auto& n) { AppendSettingNames(n, &Settings::GetMultitapModeName, &Settings::GetMultitapModeDisplayName); }},
    {"SaveStateCompressionMode",
     [](auto& n) {
       AppendSettingNames(n, &Settings::GetSaveStateCompressionModeName,
                          &Settings::GetSaveStateCompressionModeDisplayName);
     }},
    {"CPUFastmemMode",
     [](auto& n) {
       AppendSettingNames(n, &Settings::GetCPUFastmemModeName, &Settings::GetCPUFastmemModeDisplayName);
     }},
    // LOGLEVEL has no Count of its own.
    {"LogLevel",
     [](auto& n) {
       for (u32 i = 0; i < static_cast<u32>(LOGLEVEL_COUNT); i++)
       {
         n.emplace_back(Settings::GetLogLevelName(static_cast<LOGLEVEL>(i)));
         n.emplace_back(Settings::GetLogLevelDisplayName(static_cast<LOGLEVEL>(i)));
       }
     }},
  };

  std::vector<std::string> names;
  if (const auto it = lists.find(FromJString(env, kind)); it != lists.end())
    it->second(names);

  return ToNonNullStringArray(env, names);
}

// A game's own settings, by its key (AndroidHost::GetGameBoolSetting() and the rest).

NATIVE_LIBRARY_FUNCTION(jboolean, hasGameSetting)(JNIEnv* env, jobject, jstring key, jstring section, jstring name)
{
  return AndroidHost::HasGameSetting(FromJString(env, key), FromJString(env, section).c_str(),
                                     FromJString(env, name).c_str()) ?
           JNI_TRUE :
           JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(jboolean, getGameBoolSetting)(JNIEnv* env, jobject, jstring key, jstring section,
                                                      jstring name, jboolean default_value)
{
  return AndroidHost::GetGameBoolSetting(FromJString(env, key), FromJString(env, section).c_str(),
                                         FromJString(env, name).c_str(), default_value == JNI_TRUE) ?
           JNI_TRUE :
           JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(jint, getGameIntSetting)(JNIEnv* env, jobject, jstring key, jstring section, jstring name,
                                                 jint default_value)
{
  return AndroidHost::GetGameIntSetting(FromJString(env, key), FromJString(env, section).c_str(),
                                        FromJString(env, name).c_str(), default_value);
}

NATIVE_LIBRARY_FUNCTION(jfloat, getGameFloatSetting)(JNIEnv* env, jobject, jstring key, jstring section,
                                                     jstring name, jfloat default_value)
{
  return AndroidHost::GetGameFloatSetting(FromJString(env, key), FromJString(env, section).c_str(),
                                          FromJString(env, name).c_str(), default_value);
}

NATIVE_LIBRARY_FUNCTION(jstring, getGameStringSetting)(JNIEnv* env, jobject, jstring key, jstring section,
                                                       jstring name, jstring default_value)
{
  return ToJString(env, AndroidHost::GetGameStringSetting(FromJString(env, key), FromJString(env, section).c_str(),
                                                          FromJString(env, name).c_str(),
                                                          FromJString(env, default_value).c_str()));
}

NATIVE_LIBRARY_FUNCTION(void, setGameBoolSetting)(JNIEnv* env, jobject, jstring key, jstring section, jstring name,
                                                  jboolean value)
{
  AndroidHost::SetGameBoolSetting(FromJString(env, key), FromJString(env, section).c_str(),
                                  FromJString(env, name).c_str(), value == JNI_TRUE);
}

NATIVE_LIBRARY_FUNCTION(void, setGameIntSetting)(JNIEnv* env, jobject, jstring key, jstring section, jstring name,
                                                 jint value)
{
  AndroidHost::SetGameIntSetting(FromJString(env, key), FromJString(env, section).c_str(),
                                 FromJString(env, name).c_str(), value);
}

NATIVE_LIBRARY_FUNCTION(void, setGameFloatSetting)(JNIEnv* env, jobject, jstring key, jstring section, jstring name,
                                                   jfloat value)
{
  AndroidHost::SetGameFloatSetting(FromJString(env, key), FromJString(env, section).c_str(),
                                   FromJString(env, name).c_str(), value);
}

NATIVE_LIBRARY_FUNCTION(void, setGameStringSetting)(JNIEnv* env, jobject, jstring key, jstring section, jstring name,
                                                    jstring value)
{
  const std::string value_str = value ? FromJString(env, value) : std::string();
  AndroidHost::SetGameStringSetting(FromJString(env, key), FromJString(env, section).c_str(),
                                    FromJString(env, name).c_str(), value ? value_str.c_str() : nullptr);
}

NATIVE_LIBRARY_FUNCTION(void, deleteGameSetting)(JNIEnv* env, jobject, jstring key, jstring section, jstring name)
{
  AndroidHost::DeleteGameSetting(FromJString(env, key), FromJString(env, section).c_str(),
                                 FromJString(env, name).c_str());
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getGameSettingKeys)(JNIEnv* env, jobject, jstring key)
{
  std::vector<std::string> flat;
  for (auto& [section, name] : AndroidHost::GetGameSettingKeys(FromJString(env, key)))
  {
    flat.push_back(std::move(section));
    flat.push_back(std::move(name));
  }
  return ToNonNullStringArray(env, flat);
}

NATIVE_LIBRARY_FUNCTION(void, copyGlobalSettingsToGame)(JNIEnv* env, jobject, jstring key)
{
  AndroidHost::CopyGlobalSettingsToGame(FromJString(env, key));
}

NATIVE_LIBRARY_FUNCTION(void, clearGameSettings)(JNIEnv* env, jobject, jstring key)
{
  AndroidHost::ClearGameSettings(FromJString(env, key));
}

NATIVE_LIBRARY_FUNCTION(jstring, getGameInputProfile)(JNIEnv* env, jobject, jstring key)
{
  const std::optional<std::string> profile = AndroidHost::GetGameInputProfile(FromJString(env, key));
  return profile.has_value() ? ToJString(env, profile.value()) : nullptr;
}

NATIVE_LIBRARY_FUNCTION(void, updateGameControllers)(JNIEnv* env, jobject, jstring key)
{
  AndroidHost::UpdateGameControllers(FromJString(env, key));
}

NATIVE_LIBRARY_FUNCTION(jboolean, applyGameSettings)(JNIEnv* env, jobject, jstring key)
{
  return AndroidHost::ApplyGameSettings(FromJString(env, key)) ? JNI_TRUE : JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getSharedCardNames)(JNIEnv* env, jobject)
{
  return ToNonNullStringArray(env, AndroidHost::GetSharedCardNames());
}

NATIVE_LIBRARY_FUNCTION(jlongArray, getRewindMemoryUsage)(JNIEnv* env, jobject, jint slots)
{
  const auto [ram, vram] = AndroidHost::GetRewindMemoryUsage(static_cast<u32>(std::max<jint>(slots, 0)));
  const jlong values[] = {static_cast<jlong>(ram), static_cast<jlong>(vram)};
  jlongArray result = env->NewLongArray(2);
  if (!result)
  {
    ClearException(env);
    return env->NewLongArray(0);
  }

  env->SetLongArrayRegion(result, 0, 2, values);
  return result;
}

NATIVE_LIBRARY_FUNCTION(void, applySettings)(JNIEnv*, jobject)
{
  AndroidHost::ApplySettings();
}

NATIVE_LIBRARY_FUNCTION(jintArray, downloadCovers)(JNIEnv* env, jobject, jobjectArray jsources, jboolean use_serial,
                                                    jobject listener)
{
  std::vector<std::string> sources;
  const jsize count = jsources ? env->GetArrayLength(jsources) : 0;
  for (jsize i = 0; i < count; i++)
  {
    jstring source = static_cast<jstring>(env->GetObjectArrayElement(jsources, i));
    sources.push_back(FromJString(env, source));
    env->DeleteLocalRef(source);
  }

  const AndroidCoverDownloads::Summary summary =
    CoverDownloadsAndroid::Download(env, sources, use_serial == JNI_TRUE, listener);
  const jint values[] = {static_cast<jint>(summary.total),       static_cast<jint>(summary.found),
                         static_cast<jint>(summary.missing),     static_cast<jint>(summary.unreachable),
                         static_cast<jint>(summary.unsaved),     summary.cancelled ? 1 : 0,
                         static_cast<jint>(summary.games)};
  constexpr jsize fields = static_cast<jsize>(std::size(values));
  jintArray result = env->NewIntArray(fields);
  if (!result)
  {
    ClearException(env);
    return env->NewIntArray(0);
  }
  env->SetIntArrayRegion(result, 0, fields, values);
  return result;
}

// A streamed download's progress, for the ProgressCallback at progress: false once it's cancelled.
NATIVE_LIBRARY_FUNCTION(jboolean, downloadProgress)(JNIEnv*, jobject, jlong progress, jlong done, jlong total)
{
  ProgressCallback* callback = reinterpret_cast<ProgressCallback*>(progress);
  if (!callback)
    return JNI_TRUE;
  callback->SetProgressRange((total > 0) ? static_cast<u32>(std::min<jlong>(total, UINT32_MAX)) : 0);
  callback->SetProgressValue(static_cast<u32>(std::clamp<jlong>(done, 0, UINT32_MAX)));
  return callback->IsCancelled() ? JNI_FALSE : JNI_TRUE;
}

NATIVE_LIBRARY_FUNCTION(void, httpDone)(JNIEnv* env, jobject, jlong downloader, jlong request, jint status,
                                        jstring content_type, jbyteArray body)
{
  HTTPDownloader::Request::Data data;
  if (body)
  {
    data.resize(static_cast<size_t>(env->GetArrayLength(body)));
    if (!data.empty())
      env->GetByteArrayRegion(body, 0, static_cast<jsize>(data.size()), reinterpret_cast<jbyte*>(data.data()));
  }

  HTTPDownloaderAndroid::Complete(downloader, request, static_cast<s32>(status), FromJString(env, content_type),
                                  std::move(data));
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, httpTestFetch)(JNIEnv* env, jobject, jstring url, jstring post,
                                                     jfloat timeout_seconds)
{
  // For HttpDownloaderTest only: one request through the core's downloader, waited for here.
  s32 status = HTTPDownloader::HTTP_STATUS_ERROR;
  std::string type;
  std::string text;
  if (std::unique_ptr<HTTPDownloader> downloader = HTTPDownloader::Create("GooseStation test"))
  {
    downloader->SetTimeout(timeout_seconds);
    auto callback = [&](s32 code, const std::string& content_type, HTTPDownloader::Request::Data data) {
      status = code;
      type = content_type;
      text.assign(data.begin(), data.end());
    };
    if (post)
      downloader->CreatePostRequest(FromJString(env, url), FromJString(env, post), std::move(callback));
    else
      downloader->CreateRequest(FromJString(env, url), std::move(callback));
    downloader->WaitForAllRequests();
  }

  const std::string values[] = {std::to_string(status), type, text};
  jobjectArray ret = env->NewObjectArray(static_cast<jsize>(std::size(values)), s_string_class, nullptr);
  if (!ret)
  {
    ClearException(env);
    return nullptr;
  }

  for (size_t i = 0; i < std::size(values); i++)
  {
    jstring value = ToJString(env, values[i]);
    env->SetObjectArrayElement(ret, static_cast<jsize>(i), value);
    if (value)
      env->DeleteLocalRef(value);
  }

  return ret;
}

NATIVE_LIBRARY_FUNCTION(jintArray, httpTestFetchQueued)(JNIEnv* env, jobject, jobjectArray urls,
                                                         jfloat timeout_seconds)
{
  // For HttpDownloaderTest only: GETs [urls] through the core's downloader one at a time, so each after the first
  // waits in its queue, and returns their statuses.
  const jsize count = env->GetArrayLength(urls);
  std::vector<jint> statuses(static_cast<size_t>(count), HTTPDownloader::HTTP_STATUS_ERROR);
  if (std::unique_ptr<HTTPDownloader> downloader = HTTPDownloader::Create("GooseStation test"))
  {
    downloader->SetTimeout(timeout_seconds);
    downloader->SetMaxActiveRequests(1);
    for (jsize i = 0; i < count; i++)
    {
      jstring url = static_cast<jstring>(env->GetObjectArrayElement(urls, i));
      downloader->CreateRequest(FromJString(env, url),
                                [&statuses, i](s32 code, const std::string&, HTTPDownloader::Request::Data) {
                                  statuses[static_cast<size_t>(i)] = code;
                                });
      env->DeleteLocalRef(url);
    }
    downloader->WaitForAllRequests();
  }

  jintArray ret = env->NewIntArray(count);
  if (!ret)
  {
    ClearException(env);
    return nullptr;
  }
  env->SetIntArrayRegion(ret, 0, count, statuses.data());
  return ret;
}

NATIVE_LIBRARY_FUNCTION(jobject, getAchievementsAccount)(JNIEnv* env, jobject)
{
  const AchievementsBridge::Account account = AchievementsBridge::GetAccount();
  const LocalStrings strings(env, {account.username, account.display_name});
  if (!strings.ok())
    return nullptr;

  jobject ret = env->NewObject(s_achievements_account_class, s_achievements_account_constructor,
                               static_cast<jint>(account.state), strings[0], strings[1],
                               static_cast<jint>(account.score), static_cast<jint>(account.softcore_score));
  if (!ret)
    ClearException(env);
  return ret;
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
    return nullptr;
  }
  env->SetIntArrayRegion(count_array, 0, static_cast<jsize>(counts.size()), counts.data());

  jobject ret = env->NewObject(s_achievements_queue_class, s_achievements_queue_constructor,
                               static_cast<jint>(queue.own), user_array, count_array);
  if (!ret)
    ClearException(env);
  env->DeleteLocalRef(user_array);
  env->DeleteLocalRef(count_array);
  return ret;
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
  if (!ret)
    ClearException(env);
  return ret;
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getAchievementSections)(JNIEnv* env, jobject)
{
  const std::vector<AchievementsBridge::AchievementSection> sections = AchievementsBridge::GetAchievementSections();
  jobjectArray ret = env->NewObjectArray(static_cast<jsize>(sections.size()), s_achievement_section_class, nullptr);
  if (!ret)
  {
    ClearException(env);
    return nullptr;
  }

  for (size_t i = 0; i < sections.size(); i++)
  {
    const AchievementsBridge::AchievementSection& section = sections[i];
    jobjectArray items = env->NewObjectArray(static_cast<jsize>(section.items.size()), s_achievement_class, nullptr);
    if (!items)
    {
      ClearException(env);
      return nullptr;
    }

    for (size_t j = 0; j < section.items.size(); j++)
    {
      const AchievementsBridge::AchievementItem& item = section.items[j];
      const LocalStrings strings(env, {item.title, item.description, item.badge_path, item.progress});
      if (!strings.ok())
        continue;

      jobject cheevo = env->NewObject(s_achievement_class, s_achievement_constructor, static_cast<jint>(item.id),
                                      strings[0], strings[1], static_cast<jint>(item.points), strings[2],
                                      static_cast<jlong>(item.unlocked_at), strings[3],
                                      static_cast<jfloat>(item.percent));
      if (!cheevo)
      {
        ClearException(env);
        continue;
      }
      env->SetObjectArrayElement(items, static_cast<jsize>(j), cheevo);
      env->DeleteLocalRef(cheevo);
    }

    const LocalStrings label(env, {section.label});
    jobject jsection = label.ok() ? env->NewObject(s_achievement_section_class, s_achievement_section_constructor,
                                                    static_cast<jint>(section.bucket), label[0], items) :
                                    nullptr;
    if (jsection)
    {
      env->SetObjectArrayElement(ret, static_cast<jsize>(i), jsection);
      env->DeleteLocalRef(jsection);
    }
    else
    {
      ClearException(env);
    }
    env->DeleteLocalRef(items);
  }

  return ret;
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getLeaderboards)(JNIEnv* env, jobject)
{
  const std::vector<AchievementsBridge::LeaderboardItem> boards = AchievementsBridge::GetLeaderboards();
  jobjectArray ret = env->NewObjectArray(static_cast<jsize>(boards.size()), s_leaderboard_class, nullptr);
  if (!ret)
  {
    ClearException(env);
    return nullptr;
  }

  for (size_t i = 0; i < boards.size(); i++)
  {
    const LocalStrings strings(env, {boards[i].title, boards[i].description});
    jobject board = strings.ok() ? env->NewObject(s_leaderboard_class, s_leaderboard_constructor,
                                                  static_cast<jint>(boards[i].id), strings[0], strings[1]) :
                                   nullptr;
    if (!board)
    {
      ClearException(env);
      return nullptr;
    }
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

NATIVE_LIBRARY_FUNCTION(jboolean, isVulkanFailed)(JNIEnv*, jobject)
{
  return AndroidHost::IsVulkanFailed() ? JNI_TRUE : JNI_FALSE;
}

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
  bool made = true;
  for (size_t i = 0; i < std::size(strings); i++)
    made = made && (jstrings[i] = ToJString(env, *strings[i])) != nullptr;
  if (made)
  {
    ret = env->NewObject(s_memcard_sync_state_class, s_memcard_sync_state_constructor,
                         s_sync_status_kinds[static_cast<size_t>(state.kind)], static_cast<jboolean>(state.enabled),
                         static_cast<jboolean>(state.running), static_cast<jboolean>(state.applying), jstrings[0],
                         jstrings[1], jstrings[2], jstrings[3], static_cast<jboolean>(state.has_secret),
                         static_cast<jboolean>(state.has_passphrase), static_cast<jboolean>(state.passphrase_recorded),
                         jstrings[4], jstrings[5], s_sync_activities[state.activity],
                         static_cast<jint>(state.conflicts));
  }
  if (!ret)
    ClearException(env);
  for (jstring str : jstrings)
  {
    if (str)
      env->DeleteLocalRef(str);
  }
  return ret;
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
  {
    ClearException(env);
    return nullptr;
  }
  for (size_t i = 0; i < conflicts.size(); i++)
  {
    jobject c = ToSyncConflict(env, conflicts[i]);
    if (!c)
    {
      env->DeleteLocalRef(ret);
      return nullptr;
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
  // An exception from the action stays pending, so it reaches the caller once the card is let go.
  return MemcardSyncBridge::HoldCard(FromJString(env, relative_path),
                                     [env, action]() { env->CallVoidMethod(action, s_runnable_run); }) ?
           JNI_TRUE :
           JNI_FALSE;
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
  {
    ClearException(env);
    return nullptr;
  }

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
  {
    ClearException(env);
    return nullptr;
  }

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

// A chain call's key: null for settings.ini.
static std::optional<std::string> ChainKey(JNIEnv* env, jstring key)
{
  return key ? std::optional<std::string>(FromJString(env, key)) : std::nullopt;
}

// A value's components as floats, whatever the option's type.
static jfloatArray ToOptionValues(JNIEnv* env, const PostProcessing::ShaderOption& option,
                                  const PostProcessing::ShaderOption::ValueVector& value)
{
  const jsize size = static_cast<jsize>(option.vector_size);
  std::array<float, PostProcessing::ShaderOption::MAX_VECTOR_COMPONENTS> floats = {};
  for (jsize i = 0; i < size; i++)
  {
    floats[i] = (option.type == PostProcessing::ShaderOption::Type::Float) ? value[i].float_value :
                                                                              static_cast<float>(value[i].int_value);
  }
  jfloatArray result = env->NewFloatArray(size);
  if (result)
    env->SetFloatArrayRegion(result, 0, size, floats.data());
  return result;
}

static jobject ToShaderOptionInfo(JNIEnv* env, const PostProcessing::ShaderOption& option)
{
  int type;
  switch (option.type)
  {
    case PostProcessing::ShaderOption::Type::Bool:
      type = 0;
      break;
    case PostProcessing::ShaderOption::Type::Int:
      type = 1;
      break;
    default:
      type = 2;
      break;
  }
  jstring name = ToJString(env, option.name);
  jstring ui_name = ToJString(env, option.ui_name);
  jstring category = ToJString(env, option.category);
  jstring tooltip = ToJString(env, option.tooltip);
  jfloatArray defaults = ToOptionValues(env, option, option.default_value);
  jfloatArray mins = ToOptionValues(env, option, option.min_value);
  jfloatArray maxes = ToOptionValues(env, option, option.max_value);
  jfloatArray steps = ToOptionValues(env, option, option.step_value);
  jfloatArray values = ToOptionValues(env, option, option.value);
  jobjectArray choices = ToNonNullStringArray(env, option.choice_options);
  jobject result = nullptr;
  if (name && ui_name && category && tooltip && defaults && mins && maxes && steps && values && choices)
  {
    result = env->NewObject(s_shader_option_info_class, s_shader_option_info_constructor, name, ui_name, category,
                            tooltip, static_cast<jint>(type), static_cast<jint>(option.vector_size), defaults, mins,
                            maxes, steps, values, choices);
    if (ClearException(env))
      result = nullptr;
  }
  for (jobject local : {static_cast<jobject>(name), static_cast<jobject>(ui_name), static_cast<jobject>(category),
                        static_cast<jobject>(tooltip), static_cast<jobject>(defaults), static_cast<jobject>(mins),
                        static_cast<jobject>(maxes), static_cast<jobject>(steps), static_cast<jobject>(values),
                        static_cast<jobject>(choices)})
  {
    if (local)
      env->DeleteLocalRef(local);
  }
  return result;
}

static jobject ToStageInfo(JNIEnv* env, const AndroidHost::PostProcessingStage& stage)
{
  std::vector<const PostProcessing::ShaderOption*> options;
  for (const PostProcessing::ShaderOption& option : stage.options)
    options.push_back(&option);

  jobjectArray option_array =
    env->NewObjectArray(static_cast<jsize>(options.size()), s_shader_option_info_class, nullptr);
  if (!option_array)
  {
    ClearException(env);
    return nullptr;
  }
  for (size_t i = 0; i < options.size(); i++)
  {
    jobject option = ToShaderOptionInfo(env, *options[i]);
    if (!option)
    {
      env->DeleteLocalRef(option_array);
      return nullptr;
    }
    env->SetObjectArrayElement(option_array, static_cast<jsize>(i), option);
    env->DeleteLocalRef(option);
  }

  jstring shader = ToJString(env, stage.shader);
  jstring kind = ToJString(env, stage.kind);
  jstring error = ToJString(env, stage.error);
  jobject result = nullptr;
  if (shader && kind && error)
  {
    result = env->NewObject(s_stage_info_class, s_stage_info_constructor, shader, kind, option_array, error);
    if (ClearException(env))
      result = nullptr;
  }
  if (shader)
    env->DeleteLocalRef(shader);
  if (kind)
    env->DeleteLocalRef(kind);
  if (error)
    env->DeleteLocalRef(error);
  env->DeleteLocalRef(option_array);
  return result;
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getPostProcessingShaders)(JNIEnv* env, jobject)
{
  std::vector<std::string> flat;
  for (auto& [name, kind] : AndroidHost::GetPostProcessingShaders())
  {
    flat.push_back(std::move(name));
    flat.push_back(std::move(kind));
  }
  return ToNonNullStringArray(env, flat);
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

NATIVE_LIBRARY_FUNCTION(jobjectArray, getPostProcessingStages)(JNIEnv* env, jobject, jstring key, jstring section)
{
  const std::vector<AndroidHost::PostProcessingStage> stages =
    AndroidHost::GetPostProcessingStages(ChainKey(env, key), FromJString(env, section).c_str());
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(stages.size()), s_stage_info_class, nullptr);
  if (!result)
  {
    ClearException(env);
    return nullptr;
  }
  for (size_t i = 0; i < stages.size(); i++)
  {
    jobject stage = ToStageInfo(env, stages[i]);
    if (!stage)
      return nullptr;
    env->SetObjectArrayElement(result, static_cast<jsize>(i), stage);
    env->DeleteLocalRef(stage);
  }
  return result;
}

NATIVE_LIBRARY_FUNCTION(jstring, addPostProcessingStage)(JNIEnv* env, jobject, jstring key, jstring section,
                                                          jstring shader)
{
  const std::string error =
    AndroidHost::AddPostProcessingStage(ChainKey(env, key), FromJString(env, section).c_str(), FromJString(env, shader));
  return error.empty() ? nullptr : ToJString(env, error);
}

NATIVE_LIBRARY_FUNCTION(void, removePostProcessingStage)(JNIEnv* env, jobject, jstring key, jstring section, jint index)
{
  if (index >= 0)
    AndroidHost::RemovePostProcessingStage(ChainKey(env, key), FromJString(env, section).c_str(),
                                           static_cast<u32>(index));
}

NATIVE_LIBRARY_FUNCTION(void, movePostProcessingStage)(JNIEnv* env, jobject, jstring key, jstring section, jint index,
                                                        jboolean down)
{
  if (index >= 0)
    AndroidHost::MovePostProcessingStage(ChainKey(env, key), FromJString(env, section).c_str(),
                                         static_cast<u32>(index), down);
}

NATIVE_LIBRARY_FUNCTION(void, setPostProcessingOption)(JNIEnv* env, jobject, jstring key, jstring section, jint index,
                                                        jstring name, jfloatArray values)
{
  if (index < 0 || !values)
    return;

  const jsize size = std::min<jsize>(env->GetArrayLength(values), PostProcessing::ShaderOption::MAX_VECTOR_COMPONENTS);
  std::array<float, PostProcessing::ShaderOption::MAX_VECTOR_COMPONENTS> floats = {};
  env->GetFloatArrayRegion(values, 0, size, floats.data());
  AndroidHost::SetPostProcessingOption(ChainKey(env, key), FromJString(env, section).c_str(), static_cast<u32>(index),
                                       FromJString(env, name), std::span<const float>(floats.data(), size));
}

NATIVE_LIBRARY_FUNCTION(void, copyPostProcessingChain)(JNIEnv* env, jobject, jstring key, jstring section)
{
  AndroidHost::CopyPostProcessingChain(FromJString(env, key), FromJString(env, section).c_str());
}

NATIVE_LIBRARY_FUNCTION(void, clearPostProcessingChain)(JNIEnv* env, jobject, jstring key, jstring section)
{
  AndroidHost::ClearPostProcessingChain(FromJString(env, key), FromJString(env, section).c_str());
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, getPostProcessingFavourites)(JNIEnv* env, jobject)
{
  const std::vector<PostProcessing::Favourites::Info> favourites = AndroidHost::GetPostProcessingFavourites();
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(favourites.size()), s_favourite_info_class, nullptr);
  if (!result)
  {
    ClearException(env);
    return nullptr;
  }
  for (size_t i = 0; i < favourites.size(); i++)
  {
    jstring name = ToJString(env, favourites[i].name);
    jobjectArray shaders = ToNonNullStringArray(env, favourites[i].shaders);
    jobjectArray missing = ToNonNullStringArray(env, favourites[i].missing);
    jobject favourite =
      (name && shaders && missing) ? env->NewObject(s_favourite_info_class, s_favourite_info_constructor, name, shaders,
                                                    missing) :
                                     nullptr;
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(shaders);
    env->DeleteLocalRef(missing);
    if (!favourite)
    {
      ClearException(env);
      return nullptr;
    }
    env->SetObjectArrayElement(result, static_cast<jsize>(i), favourite);
    env->DeleteLocalRef(favourite);
  }
  return result;
}

// Each favourite edit gives null, or the core's message.
static jstring FavouriteResult(JNIEnv* env, const std::string& error)
{
  return error.empty() ? nullptr : ToJString(env, error);
}

NATIVE_LIBRARY_FUNCTION(jstring, savePostProcessingFavourite)(JNIEnv* env, jobject, jstring key, jstring name,
                                                               jboolean replace)
{
  return FavouriteResult(env, AndroidHost::SavePostProcessingFavourite(ChainKey(env, key), FromJString(env, name),
                                                                       replace));
}

NATIVE_LIBRARY_FUNCTION(jstring, applyPostProcessingFavourite)(JNIEnv* env, jobject, jstring key, jstring name)
{
  return FavouriteResult(env, AndroidHost::ApplyPostProcessingFavourite(ChainKey(env, key), FromJString(env, name)));
}

NATIVE_LIBRARY_FUNCTION(jstring, renamePostProcessingFavourite)(JNIEnv* env, jobject, jstring from, jstring to)
{
  return FavouriteResult(env, AndroidHost::RenamePostProcessingFavourite(FromJString(env, from), FromJString(env, to)));
}

NATIVE_LIBRARY_FUNCTION(void, movePostProcessingFavourite)(JNIEnv* env, jobject, jstring name, jboolean down)
{
  AndroidHost::MovePostProcessingFavourite(FromJString(env, name), down);
}

NATIVE_LIBRARY_FUNCTION(jstring, deletePostProcessingFavourite)(JNIEnv* env, jobject, jstring name)
{
  return FavouriteResult(env, AndroidHost::DeletePostProcessingFavourite(FromJString(env, name)));
}

NATIVE_LIBRARY_FUNCTION(jboolean, pressHotkeyForTest)(JNIEnv* env, jobject, jstring name)
{
  return AndroidHost::PressHotkeyForTest(FromJString(env, name));
}

NATIVE_LIBRARY_FUNCTION(jboolean, isFastmemAvailable)(JNIEnv* env, jobject)
{
  return AndroidHost::IsFastmemAvailable();
}

NATIVE_LIBRARY_FUNCTION(jboolean, postProcessingUsesOpenGLES)(JNIEnv* env, jobject, jstring key)
{
  return AndroidHost::PostProcessingUsesOpenGLES(ChainKey(env, key));
}

NATIVE_LIBRARY_FUNCTION(jboolean, isPostProcessingActiveForTest)(JNIEnv* env, jobject, jstring section)
{
  return AndroidHost::IsPostProcessingActiveForTest(FromJString(env, section));
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
  {
    ClearException(env);
    return nullptr;
  }
  for (size_t i = 0; i < cards.size(); i++)
  {
    jobject card = ToMemcardFile(env, cards[i]);
    if (!card)
    {
      env->DeleteLocalRef(ret);
      return nullptr;
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
  return ToMemcardEdit(env, MemcardEditorBridge::CopySave(FromJByteArray(env, from), static_cast<u32>(first_block),
                                                          FromJByteArray(env, to), replace == JNI_TRUE));
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
  if (!ret)
    ClearException(env);
  return ret;
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
  return ToMemcardEdit(env, MemcardEditorBridge::ImportSave(FromJByteArray(env, card), FromJString(env, file_name),
                                                            FromJByteArray(env, data)));
}

NATIVE_LIBRARY_FUNCTION(jobject, importMemcard)(JNIEnv* env, jobject, jstring file_name, jbyteArray data)
{
  return ToMemcardEdit(env, MemcardEditorBridge::ImportCard(FromJString(env, file_name), FromJByteArray(env, data)));
}

NATIVE_LIBRARY_FUNCTION(jobject, exportMemcardSave)(JNIEnv* env, jobject, jbyteArray card, jint first_block)
{
  return ToMemcardEdit(env,
                       MemcardEditorBridge::ExportSave(FromJByteArray(env, card), static_cast<u32>(first_block)));
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
  if (!ret)
    ClearException(env);
  if (error)
    env->DeleteLocalRef(error);
  return ret;
}

} // extern "C"
