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
#include <span>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

Log_SetChannel(JNIBridge);

using JNIUtil::ClearException;

using JNIUtil::FromJString;

using JNIUtil::ToJString;

// The JNI side of io.github.ilyalissoboi.goosestation.core.NativeLibrary, and the calls back into NativeCallbacks.

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
// SyncActivity's values, in MemcardSync::Activity's order (global references). Sized by the C++ enum, so a value added
// there and not in SYNC_ACTIVITY_NAMES (JNI_OnLoad asserts one name for each) fails the build.
jobject s_sync_activities[static_cast<size_t>(MemcardSync::Activity::Count)] = {};
jclass s_sync_version_class = nullptr;
jmethodID s_sync_version_constructor = nullptr;
jclass s_sync_conflict_class = nullptr;
jmethodID s_sync_conflict_constructor = nullptr;
jmethodID s_runnable_run = nullptr;
jclass s_memcard_file_class = nullptr;
jmethodID s_memcard_file_constructor = nullptr;
// MemcardKind's values, in MemcardEditorBridge::CardKind's order (global references), sized by it, as above.
jobject s_memcard_kinds[static_cast<size_t>(MemcardEditorBridge::CardKind::Count)] = {};
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
// MemcardWriteOutcome's values, in WriteOutcome's order (global references), sized by it, as above.
jobject s_memcard_write_outcomes[static_cast<size_t>(MemcardEditorBridge::WriteOutcome::Count)] = {};

// The app's NativeCallbacks (a global reference), or null.
std::mutex s_callbacks_mutex;
jobject s_callbacks = nullptr;

namespace {

// Set on the thread AttachCurrentThreadToJVM() attached.
thread_local JNIEnv* s_attached_env = nullptr;

} // namespace

AndroidContentProvider s_content_provider;

namespace {

// The registered NativeCallbacks as a local reference, or null with none.
jobject GetCallbacks(JNIEnv* env)
{
  std::unique_lock lock(s_callbacks_mutex);
  return s_callbacks ? env->NewLocalRef(s_callbacks) : nullptr;
}

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

/// Runs call(env, callbacks) and returns what it returned, if the app has set its callbacks, on any thread; fallback
/// without them, or if the call threw (its exception cleared).
template<typename T, typename F>
T WithCallbacksOr(T fallback, F&& call)
{
  ScopedJNIEnv env;
  if (!env)
    return fallback;

  jobject callbacks = GetCallbacks(env.get());
  if (!callbacks)
    return fallback;

  T result = call(env.get(), callbacks);
  if (ClearException(env.get()))
    result = std::move(fallback);
  env->DeleteLocalRef(callbacks);
  return result;
}

// Calls a NativeCallbacks method that takes nothing, or one string, on the registered object, from any thread.
void CallCallback(jmethodID method, std::optional<std::string_view> arg)
{
  WithCallbacks([&](JNIEnv* env, jobject callbacks) {
    if (!arg.has_value())
    {
      env->CallVoidMethod(callbacks, method);
      return;
    }
    const LocalStrings strings(env, {arg.value()});
    if (strings.ok())
      env->CallVoidMethod(callbacks, method, strings[0]);
  });
}

} // namespace

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
  jobjectArray result =
    JNIUtil::ToObjectArray(env, s_string_class, strings,
                           [](JNIEnv* env, const std::string& str) -> jobject { return ToJString(env, str); });
  if (!result)
    ClearException(env);
  return result;
}
// A PadBinding[] of bindings, or null (with no exception pending) for none or if it can't be made.
jobjectArray ToPadBindingArray(JNIEnv* env, const std::optional<std::vector<AndroidHost::PadBinding>>& bindings)
{
  if (!bindings.has_value())
    return nullptr;

  jobjectArray result = JNIUtil::ToObjectArray(
    env, s_pad_binding_class, bindings.value(), [](JNIEnv* env, const AndroidHost::PadBinding& binding) -> jobject {
      const JNIUtil::LocalRef<jstring> name(env, ToJString(env, binding.name));
      const JNIUtil::LocalRef<jstring> display_name(env, ToJString(env, binding.display_name));
      const JNIUtil::LocalRef<jstring> value(
        env, binding.binding.has_value() ? ToJString(env, binding.binding.value()) : nullptr);
      const JNIUtil::LocalRef<jstring> category(env, ToJString(env, binding.category));
      jobject entry = nullptr;
      if (name && display_name && category && (value || !binding.binding.has_value()))
      {
        entry = env->NewObject(s_pad_binding_class, s_pad_binding_constructor, name.get(), display_name.get(),
                               s_pad_binding_kinds[static_cast<size_t>(binding.kind)], value.get(), category.get());
        if (ClearException(env))
          entry = nullptr;
      }

      if (!entry)
        ERROR_LOG("Failed to pass the input '{}' to Java.", binding.name);
      return entry;
    });
  if (!result)
    ClearException(env);
  return result;
}

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
  WithCallbacks([&](JNIEnv* env, jobject callbacks) {
    const LocalStrings strings(env, {title, serial});
    if (strings.ok())
    {
      env->CallVoidMethod(callbacks, s_on_game_started, strings[0], strings[1], start_paused ? JNI_TRUE : JNI_FALSE,
                          pause_on_disconnect ? JNI_TRUE : JNI_FALSE);
    }
  });
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
  WithCallbacks([&](JNIEnv* env, jobject callbacks) {
    env->CallVoidMethod(callbacks, s_on_rumble, static_cast<jint>(device_id), large, small);
  });
}

void AndroidHost::NotifyHotkey(std::string_view name)
{
  CallCallback(s_on_hotkey, name);
}

void AndroidHost::NotifyOsdMessage(std::string_view key, std::string_view text, float seconds, const u32* image_pixels,
                                   u32 image_width, u32 image_height)
{
  WithCallbacks([&](JNIEnv* env, jobject callbacks) {
    const LocalStrings strings(env, {key, text});
    if (!strings.ok())
      return;
    jobject icon = image_pixels ? ToArgbImage(env, image_pixels, image_width, image_height, false) : nullptr;
    env->CallVoidMethod(callbacks, s_on_osd_message, strings[0], strings[1], static_cast<jfloat>(seconds), icon);
    if (icon)
      env->DeleteLocalRef(icon);
  });
}

void AndroidHost::NotifyAchievementsToken(std::optional<std::string_view> token)
{
  WithCallbacks([&](JNIEnv* env, jobject callbacks) {
    // Null clears the stored token.
    jstring jtoken = token.has_value() ? ToJString(env, token.value()) : nullptr;
    if (token.has_value() && !jtoken)
      return;
    env->CallVoidMethod(callbacks, s_on_achievements_token, jtoken);
    if (jtoken)
      env->DeleteLocalRef(jtoken);
  });
}

bool AndroidHost::StoreMemcardSyncSecret(std::string_view name, std::optional<std::string_view> value)
{
  return WithCallbacksOr(false, [&](JNIEnv* env, jobject callbacks) {
    // Null deletes the secret.
    jstring jname = ToJString(env, name);
    jstring jvalue = value.has_value() ? ToJString(env, value.value()) : nullptr;
    bool stored = false;
    if (jname && (!value.has_value() || jvalue))
      stored = env->CallBooleanMethod(callbacks, s_store_memcard_sync_secret, jname, jvalue) == JNI_TRUE;
    for (jstring ref : {jvalue, jname})
    {
      if (ref)
        env->DeleteLocalRef(ref);
    }
    return stored;
  });
}

std::optional<std::string> AndroidHost::LoadMemcardSyncSecret(std::string_view name)
{
  return WithCallbacksOr(std::optional<std::string>(), [&](JNIEnv* env, jobject callbacks) {
    std::optional<std::string> value;
    jstring jname = ToJString(env, name);
    if (!jname)
      return value;
    jstring jvalue = static_cast<jstring>(env->CallObjectMethod(callbacks, s_load_memcard_sync_secret, jname));
    // Nothing is read with an exception pending: WithCallbacksOr gives nothing for it.
    if (jvalue && !env->ExceptionCheck())
      value = FromJString(env, jvalue);
    if (jvalue)
      env->DeleteLocalRef(jvalue);
    env->DeleteLocalRef(jname);
    return value;
  });
}

void AndroidHost::NotifyMemcardSyncChanged()
{
  CallCallback(s_on_memcard_sync_changed, std::nullopt);
}

bool AndroidHost::HasMemcardSyncSecret(std::string_view name)
{
  return WithCallbacksOr(false, [&](JNIEnv* env, jobject callbacks) {
    jstring jname = ToJString(env, name);
    if (!jname)
      return false;
    const bool has = env->CallBooleanMethod(callbacks, s_has_memcard_sync_secret, jname) == JNI_TRUE;
    env->DeleteLocalRef(jname);
    return has;
  });
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
  jobjectArray ret = JNIUtil::ToObjectArray(
    env, s_leaderboard_entry_class, entries,
    [](JNIEnv* env, const AchievementsBridge::LeaderboardEntry& entry) -> jobject {
      const LocalStrings strings(env, {entry.user, entry.score});
      jobject made = strings.ok() ? env->NewObject(s_leaderboard_entry_class, s_leaderboard_entry_constructor,
                                                   static_cast<jint>(entry.rank), strings[0], strings[1]) :
                                    nullptr;
      if (!made)
        ClearException(env);
      return made;
    });
  if (!ret)
    ClearException(env);
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
  WithCallbacks([enabled](JNIEnv* env, jobject callbacks) {
    env->CallVoidMethod(callbacks, s_on_fast_forward_changed, static_cast<jboolean>(enabled));
  });
}

void AndroidHost::NotifyPadKindChanged(s32 kind)
{
  WithCallbacks([kind](JNIEnv* env, jobject callbacks) {
    env->CallVoidMethod(callbacks, s_on_pad_kind_changed, static_cast<jint>(kind));
  });
}

namespace {

// A class to load into a global reference and, unless constructor is null, the constructor native code makes it with.
struct ClassLookup
{
  const char* name;
  jclass* cls;
  const char* constructor;
  jmethodID* constructor_id;
};

// A method of a class already loaded.
struct MethodLookup
{
  const char* name;
  const char* signature;
  jmethodID* id;
};

// The constants called names of the enum class_name, in the C++ enum's order, each into a global reference in out.
struct EnumLookup
{
  const char* class_name;
  std::span<const char* const> names;
  std::span<jobject> out;
};

// Each loader checks every lookup as it makes it. The first that finds nothing is logged, its exception cleared, and
// the loader returns false there, with the lookups after it untouched.

bool LoadClasses(JNIEnv* env, std::span<const ClassLookup> classes)
{
  for (const ClassLookup& lookup : classes)
  {
    jclass local = env->FindClass(lookup.name);
    if (!local)
    {
      ERROR_LOG("JNI_OnLoad: couldn't find the class {}.", lookup.name);
      ClearException(env);
      return false;
    }

    *lookup.cls = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!lookup.constructor)
      continue;

    *lookup.constructor_id = env->GetMethodID(*lookup.cls, "<init>", lookup.constructor);
    if (!*lookup.constructor_id)
    {
      ERROR_LOG("JNI_OnLoad: couldn't find {}'s constructor {}.", lookup.name, lookup.constructor);
      ClearException(env);
      return false;
    }
  }

  return true;
}

// class_name names cls in the log.
bool LoadMethods(JNIEnv* env, jclass cls, const char* class_name, std::span<const MethodLookup> methods)
{
  for (const MethodLookup& lookup : methods)
  {
    *lookup.id = env->GetMethodID(cls, lookup.name, lookup.signature);
    if (!*lookup.id)
    {
      ERROR_LOG("JNI_OnLoad: couldn't find {}.{}{}.", class_name, lookup.name, lookup.signature);
      ClearException(env);
      return false;
    }
  }

  return true;
}

bool LoadEnums(JNIEnv* env, std::span<const EnumLookup> enums)
{
  for (const EnumLookup& lookup : enums)
  {
    const JNIUtil::LocalRef<jclass> cls(env, env->FindClass(lookup.class_name));
    if (!cls)
    {
      ERROR_LOG("JNI_OnLoad: couldn't find the enum {}.", lookup.class_name);
      ClearException(env);
      return false;
    }

    const std::string signature = std::string("L") + lookup.class_name + ";";
    for (size_t i = 0; i < lookup.names.size(); i++)
    {
      jfieldID field = env->GetStaticFieldID(cls.get(), lookup.names[i], signature.c_str());
      jobject value = field ? env->GetStaticObjectField(cls.get(), field) : nullptr;
      if (!value)
      {
        ERROR_LOG("JNI_OnLoad: couldn't find {}.{}.", lookup.class_name, lookup.names[i]);
        ClearException(env);
        return false;
      }

      lookup.out[i] = env->NewGlobalRef(value);
      env->DeleteLocalRef(value);
    }
  }

  return true;
}

// For the lookup test natives: [loaded (1 or 0), rows filled].
jintArray LookupResult(JNIEnv* env, bool loaded, size_t filled, const char* call)
{
  jintArray ret = env->NewIntArray(2);
  if (ret)
  {
    const jint values[] = {loaded ? 1 : 0, static_cast<jint>(filled)};
    env->SetIntArrayRegion(ret, 0, 2, values);
  }
  return Made(env, ret, call);
}

} // namespace

// Kotlin takes these arrays as never null: a failure gives an empty one.
jobjectArray ToNonNullStringArray(JNIEnv* env, const std::vector<std::string>& strings)
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

  // This runs inside System.loadLibrary, before AndroidHost::Initialize: without this, a failed lookup's log line would
  // go nowhere. Initialize's own call then finds it already on.
  Log::SetDebugOutputParams(true);

  // Each class, with the constructor native code makes it with.
  static constexpr ClassLookup CLASSES[] = {
    ClassLookup{"java/lang/String", &s_string_class, nullptr, nullptr},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/NativeCallbacks", &s_callbacks_class, nullptr, nullptr},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/ArgbImage", &s_argb_image_class, "(II[I)V",
                &s_argb_image_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/GameEntry", &s_game_entry_class,
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJLjava/lang/String;"
                "[Lio/github/ilyalissoboi/goosestation/core/GameDisc;ZJJI)V",
                &s_game_entry_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/GameDisc", &s_game_disc_class,
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V", &s_game_disc_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/PatchCode", &s_patch_code_class,
                "(ILjava/lang/String;Ljava/lang/String;ZZ)V", &s_patch_code_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/PatchCodes", &s_patch_codes_class,
                "(Ljava/lang/String;[Lio/github/ilyalissoboi/goosestation/core/PatchCode;)V",
                &s_patch_codes_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/AchievementsAccount", &s_achievements_account_class,
                "(ILjava/lang/String;Ljava/lang/String;II)V", &s_achievements_account_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/AchievementsQueue", &s_achievements_queue_class,
                "(I[Ljava/lang/String;[I)V", &s_achievements_queue_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/GameAchievements", &s_game_achievements_class,
                "(IIIIIZZZZI)V", &s_game_achievements_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/Achievement", &s_achievement_class,
                "(ILjava/lang/String;Ljava/lang/String;ILjava/lang/String;JLjava/lang/String;F)V",
                &s_achievement_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/AchievementSection", &s_achievement_section_class,
                "(ILjava/lang/String;[Lio/github/ilyalissoboi/goosestation/core/Achievement;)V",
                &s_achievement_section_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/Leaderboard", &s_leaderboard_class,
                "(ILjava/lang/String;Ljava/lang/String;)V", &s_leaderboard_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/LeaderboardEntry", &s_leaderboard_entry_class,
                "(ILjava/lang/String;Ljava/lang/String;)V", &s_leaderboard_entry_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/LeaderboardPage", &s_leaderboard_page_class,
                "(I[Lio/github/ilyalissoboi/goosestation/core/LeaderboardEntry;[Lio/github/ilyalissoboi/goosestation/"
                "core/LeaderboardEntry;I)V",
                &s_leaderboard_page_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/SaveStatePreview", &s_save_state_preview_class,
                "(Lio/github/ilyalissoboi/goosestation/core/ArgbImage;ILjava/lang/String;Ljava/lang/String;Ljava/lang/"
                "String;Ljava/lang/String;)V",
                &s_save_state_preview_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/BiosImage", &s_bios_image_class,
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", &s_bios_image_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/ShaderOptionInfo", &s_shader_option_info_class,
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II[F[F[F[F[F"
                "[Ljava/lang/String;)V",
                &s_shader_option_info_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/StageInfo", &s_stage_info_class,
                "(Ljava/lang/String;Ljava/lang/String;[Lio/github/ilyalissoboi/goosestation/core/"
                "ShaderOptionInfo;Ljava/lang/String;)V",
                &s_stage_info_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/FavouriteInfo", &s_favourite_info_class,
                "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V", &s_favourite_info_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/PadBinding", &s_pad_binding_class,
                "(Ljava/lang/String;Ljava/lang/String;Lio/github/ilyalissoboi/goosestation/core/PadBindingKind;Ljava/"
                "lang/String;Ljava/lang/String;)V",
                &s_pad_binding_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/MemcardSyncState", &s_memcard_sync_state_class,
                "(Lio/github/ilyalissoboi/goosestation/core/SyncStatusKind;ZZZLjava/lang/String;Ljava/lang/"
                "String;Ljava/lang/String;Ljava/lang/String;ZZZ"
                "Ljava/lang/String;Ljava/lang/String;Lio/github/ilyalissoboi/goosestation/core/SyncActivity;I)V",
                &s_memcard_sync_state_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/SyncVersion", &s_sync_version_class,
                "(Ljava/lang/String;JZIZ)V", &s_sync_version_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/SyncConflict", &s_sync_conflict_class,
                "(JLjava/lang/String;Lio/github/ilyalissoboi/goosestation/core/SyncVersion;[Lio/github/ilyalissoboi/"
                "goosestation/core/SyncVersion;I)V",
                &s_sync_conflict_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/MemcardFile", &s_memcard_file_class,
                "(Ljava/lang/String;Ljava/lang/String;Lio/github/ilyalissoboi/goosestation/core/MemcardKind;Z)V",
                &s_memcard_file_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/MemcardSave", &s_memcard_save_class,
                "(ILjava/lang/String;Ljava/lang/String;IZLio/github/ilyalissoboi/goosestation/core/ArgbImage;)V",
                &s_memcard_save_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/MemcardContents", &s_memcard_contents_class,
                "(I[Lio/github/ilyalissoboi/goosestation/core/MemcardSave;)V", &s_memcard_contents_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/MemcardEdit", &s_memcard_edit_class,
                "([BLjava/lang/String;)V", &s_memcard_edit_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/MemcardRenameCheck", &s_memcard_rename_check_class,
                "(IIZZZ)V", &s_memcard_rename_check_constructor},
    ClassLookup{"io/github/ilyalissoboi/goosestation/core/MemcardWrite", &s_memcard_write_class,
                "(Lio/github/ilyalissoboi/goosestation/core/MemcardWriteOutcome;Ljava/lang/String;)V",
                &s_memcard_write_constructor},
  };

  // NativeCallbacks' methods, called from native code.
  static constexpr MethodLookup CALLBACKS[] = {
    MethodLookup{"onGameStarted", "(Ljava/lang/String;Ljava/lang/String;ZZ)V", &s_on_game_started},
    MethodLookup{"onGameStopped", "()V", &s_on_game_stopped},
    MethodLookup{"onError", "(Ljava/lang/String;)V", &s_on_error},
    MethodLookup{"onBindingCaptured", "(Ljava/lang/String;)V", &s_on_binding_captured},
    MethodLookup{"onHotkey", "(Ljava/lang/String;)V", &s_on_hotkey},
    MethodLookup{"onRumble", "(IFF)V", &s_on_rumble},
    MethodLookup{"onOsdMessage",
                 "(Ljava/lang/String;Ljava/lang/String;FLio/github/ilyalissoboi/goosestation/core/ArgbImage;)V",
                 &s_on_osd_message},
    MethodLookup{"onFastForwardChanged", "(Z)V", &s_on_fast_forward_changed},
    MethodLookup{"onPadKindChanged", "(I)V", &s_on_pad_kind_changed},
    MethodLookup{"onAchievementsToken", "(Ljava/lang/String;)V", &s_on_achievements_token},
    MethodLookup{"storeMemcardSyncSecret", "(Ljava/lang/String;Ljava/lang/String;)Z", &s_store_memcard_sync_secret},
    MethodLookup{"loadMemcardSyncSecret", "(Ljava/lang/String;)Ljava/lang/String;", &s_load_memcard_sync_secret},
    MethodLookup{"hasMemcardSyncSecret", "(Ljava/lang/String;)Z", &s_has_memcard_sync_secret},
    MethodLookup{"onMemcardSyncChanged", "()V", &s_on_memcard_sync_changed},
    MethodLookup{"onAchievementsChanged", "()V", &s_on_achievements_changed},
    MethodLookup{"onAchievementsLoginExpired", "()V", &s_on_achievements_login_expired},
    MethodLookup{"onAchievementsSent", "(I)V", &s_on_achievements_sent},
    MethodLookup{"onAchievementRefused", "(Ljava/lang/String;)V", &s_on_achievement_refused},
    MethodLookup{"onAchievementNotification",
                 "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;FIZ)V",
                 &s_on_achievement_notification},
    MethodLookup{"onAchievementToast", "(Ljava/lang/String;Ljava/lang/String;F)V", &s_on_achievement_toast},
    MethodLookup{"onAchievementIndicator", "(IIZLjava/lang/String;Ljava/lang/String;)V", &s_on_achievement_indicator},
    MethodLookup{"onAchievementImage", "(Ljava/lang/String;)V", &s_on_achievement_image},
    MethodLookup{"onPlaySound", "(Ljava/lang/String;)V", &s_on_play_sound},
    MethodLookup{"onLeaderboard", "(ILio/github/ilyalissoboi/goosestation/core/LeaderboardPage;Ljava/lang/String;)V",
                 &s_on_leaderboard},
  };

  // Each enum's constants, in its C++ enum's order.
  static constexpr const char* PAD_BINDING_KIND_NAMES[] = {"Button", "Axis", "HalfAxis"};
  static constexpr const char* SYNC_STATUS_KIND_NAMES[] = {"Unavailable", "Applying", "StartError", "Off",
                                                           "Incomplete",  "Status",   "Idle"};
  static constexpr const char* SYNC_ACTIVITY_NAMES[] = {"None",   "Syncing", "Synced",      "Offline",
                                                        "Failed", "Locked",  "AuthRequired"};
  static constexpr const char* MEMCARD_KIND_NAMES[] = {"Game", "Shared", "Other"};
  static constexpr const char* MEMCARD_WRITE_OUTCOME_NAMES[] = {"Saved", "Changed", "Busy", "Failed"};
  static_assert(std::size(PAD_BINDING_KIND_NAMES) == std::size(s_pad_binding_kinds));
  static_assert(std::size(SYNC_STATUS_KIND_NAMES) == std::size(s_sync_status_kinds));
  static_assert(std::size(SYNC_ACTIVITY_NAMES) == std::size(s_sync_activities));
  static_assert(std::size(MEMCARD_KIND_NAMES) == std::size(s_memcard_kinds));
  static_assert(std::size(MEMCARD_WRITE_OUTCOME_NAMES) == std::size(s_memcard_write_outcomes));
  const EnumLookup enums[] = {
    EnumLookup{"io/github/ilyalissoboi/goosestation/core/PadBindingKind", PAD_BINDING_KIND_NAMES, s_pad_binding_kinds},
    EnumLookup{"io/github/ilyalissoboi/goosestation/core/SyncStatusKind", SYNC_STATUS_KIND_NAMES, s_sync_status_kinds},
    EnumLookup{"io/github/ilyalissoboi/goosestation/core/SyncActivity", SYNC_ACTIVITY_NAMES, s_sync_activities},
    EnumLookup{"io/github/ilyalissoboi/goosestation/core/MemcardKind", MEMCARD_KIND_NAMES, s_memcard_kinds},
    EnumLookup{"io/github/ilyalissoboi/goosestation/core/MemcardWriteOutcome", MEMCARD_WRITE_OUTCOME_NAMES,
               s_memcard_write_outcomes},
  };

  static constexpr MethodLookup RUNNABLE[] = {MethodLookup{"run", "()V", &s_runnable_run}};

  if (!LoadClasses(env, CLASSES) ||
      !LoadMethods(env, s_callbacks_class, "io/github/ilyalissoboi/goosestation/core/NativeCallbacks", CALLBACKS) ||
      !LoadEnums(env, enums))
  {
    return JNI_ERR;
  }

  // Runnable is only needed for run(): its class stays a local reference.
  const JNIUtil::LocalRef<jclass> runnable_class(env, env->FindClass("java/lang/Runnable"));
  if (!runnable_class)
  {
    ERROR_LOG("JNI_OnLoad: couldn't find the class java/lang/Runnable.");
    ClearException(env);
    return JNI_ERR;
  }
  if (!LoadMethods(env, runnable_class.get(), "java/lang/Runnable", RUNNABLE))
    return JNI_ERR;

  if (!AndroidContentProvider::LoadJNIReferences(env) || !HTTPDownloaderAndroid::LoadJNIReferences(env))
    return JNI_ERR;

  JNIUtil::SetJavaVM(vm);
  FileSystem::SetContentProvider(&s_content_provider);
  PlatformMisc::SetSoundPlayer(&AndroidHost::NotifyPlaySound);
  return JNI_VERSION_1_6;
}

// For JniLookupsTest only: LoadClasses() over names, each with the constructor signature in constructors (null, read
// as "", for none). A row is filled when its class is, and its constructor if it has one.
NATIVE_LIBRARY_FUNCTION(jintArray, classLookupsForTest)
(JNIEnv* env, jobject, jobjectArray jnames, jobjectArray jconstructors)
{
  const std::vector<std::string> names = JNIUtil::FromJStringArray(env, jnames);
  const std::vector<std::string> constructors = JNIUtil::FromJStringArray(env, jconstructors);
  std::vector<jclass> classes(names.size(), nullptr);
  std::vector<jmethodID> ids(names.size(), nullptr);
  std::vector<ClassLookup> rows;
  for (size_t i = 0; i < names.size(); i++)
  {
    const bool has_constructor = i < constructors.size() && !constructors[i].empty();
    rows.push_back({names[i].c_str(), &classes[i], has_constructor ? constructors[i].c_str() : nullptr, &ids[i]});
  }

  const bool loaded = LoadClasses(env, rows);
  size_t filled = 0;
  for (size_t i = 0; i < rows.size(); i++)
    filled += (classes[i] && (!rows[i].constructor || ids[i])) ? 1 : 0;
  for (jclass cls : classes)
  {
    if (cls)
      env->DeleteGlobalRef(cls);
  }
  return LookupResult(env, loaded, filled, "classLookupsForTest");
}

// For JniLookupsTest only: LoadMethods() over names and signatures on the class class_name.
NATIVE_LIBRARY_FUNCTION(jintArray, methodLookupsForTest)
(JNIEnv* env, jobject, jstring jclass_name, jobjectArray jnames, jobjectArray jsignatures)
{
  const std::string class_name = FromJString(env, jclass_name);
  const std::vector<std::string> names = JNIUtil::FromJStringArray(env, jnames);
  const std::vector<std::string> signatures = JNIUtil::FromJStringArray(env, jsignatures);
  const JNIUtil::LocalRef<jclass> cls(env, env->FindClass(class_name.c_str()));
  if (!cls)
  {
    ClearException(env);
    return LookupResult(env, false, 0, "methodLookupsForTest");
  }

  std::vector<jmethodID> ids(names.size(), nullptr);
  std::vector<MethodLookup> rows;
  for (size_t i = 0; i < names.size() && i < signatures.size(); i++)
    rows.push_back({names[i].c_str(), signatures[i].c_str(), &ids[i]});

  const bool loaded = LoadMethods(env, cls.get(), class_name.c_str(), rows);
  const size_t filled = static_cast<size_t>(std::count_if(ids.begin(), ids.end(), [](jmethodID id) { return id; }));
  return LookupResult(env, loaded, filled, "methodLookupsForTest");
}

// For JniLookupsTest only: LoadEnums() of the enum class_name's constants names.
NATIVE_LIBRARY_FUNCTION(jintArray, enumLookupsForTest)(JNIEnv* env, jobject, jstring jclass_name, jobjectArray jnames)
{
  const std::string class_name = FromJString(env, jclass_name);
  const std::vector<std::string> names = JNIUtil::FromJStringArray(env, jnames);
  std::vector<const char*> name_ptrs;
  for (const std::string& name : names)
    name_ptrs.push_back(name.c_str());
  std::vector<jobject> values(names.size(), nullptr);

  const EnumLookup lookup{class_name.c_str(), name_ptrs, values};
  const bool loaded = LoadEnums(env, std::span<const EnumLookup>(&lookup, 1));
  size_t filled = 0;
  for (jobject value : values)
  {
    if (value)
    {
      filled++;
      env->DeleteGlobalRef(value);
    }
  }
  return LookupResult(env, loaded, filled, "enumLookupsForTest");
}

} // extern "C"
