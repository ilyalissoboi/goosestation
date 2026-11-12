// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

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

// For a native Kotlin declares never null: result, or, when it couldn't be made (out of memory, as a rule), null with
// an exception pending, which Kotlin throws at the call, rather than a null that fails later far from it: the one
// that stopped it, if it's still pending, else one naming the call.
template<typename T>
T Made(JNIEnv* env, T result, const char* call)
{
  if (result || env->ExceptionCheck())
    return result;
  if (jclass cls = env->FindClass("java/lang/IllegalStateException"))
  {
    env->ThrowNew(cls, fmt::format("{} couldn't make its result.", call).c_str());
    env->DeleteLocalRef(cls);
  }
  return nullptr;
}

extern "C" {

#define NATIVE_LIBRARY_FUNCTION(ret, name) \
  JNIEXPORT ret JNICALL Java_io_github_ilyalissoboi_goosestation_core_NativeLibrary_##name

} // extern "C"

extern jclass s_string_class;

extern jclass s_achievements_account_class;

extern jmethodID s_achievements_account_constructor;

extern jclass s_achievements_queue_class;

extern jmethodID s_achievements_queue_constructor;

extern jclass s_game_achievements_class;

extern jmethodID s_game_achievements_constructor;

extern jclass s_achievement_class;

extern jmethodID s_achievement_constructor;

extern jclass s_achievement_section_class;

extern jmethodID s_achievement_section_constructor;

extern jclass s_leaderboard_class;

extern jmethodID s_leaderboard_constructor;

extern jclass s_game_entry_class;

extern jmethodID s_game_entry_constructor;

extern jclass s_game_disc_class;

extern jmethodID s_game_disc_constructor;

extern jclass s_save_state_preview_class;

extern jmethodID s_save_state_preview_constructor;

extern jclass s_patch_code_class;

extern jmethodID s_patch_code_constructor;

extern jclass s_patch_codes_class;

extern jmethodID s_patch_codes_constructor;

extern jclass s_bios_image_class;

extern jmethodID s_bios_image_constructor;

extern jclass s_shader_option_info_class;

extern jmethodID s_shader_option_info_constructor;

extern jclass s_stage_info_class;

extern jmethodID s_stage_info_constructor;

extern jclass s_favourite_info_class;

extern jmethodID s_favourite_info_constructor;

extern jclass s_memcard_sync_state_class;

extern jmethodID s_memcard_sync_state_constructor;

extern jobject s_sync_status_kinds[7];

extern jobject s_sync_activities[static_cast<size_t>(MemcardSync::Activity::Count)];

extern jclass s_sync_version_class;

extern jmethodID s_sync_version_constructor;

extern jclass s_sync_conflict_class;

extern jmethodID s_sync_conflict_constructor;

extern jmethodID s_runnable_run;

extern jclass s_memcard_file_class;

extern jmethodID s_memcard_file_constructor;

extern jobject s_memcard_kinds[static_cast<size_t>(MemcardEditorBridge::CardKind::Count)];

extern jclass s_memcard_save_class;

extern jmethodID s_memcard_save_constructor;

extern jclass s_memcard_contents_class;

extern jmethodID s_memcard_contents_constructor;

extern jclass s_memcard_edit_class;

extern jmethodID s_memcard_edit_constructor;

extern jclass s_memcard_rename_check_class;

extern jmethodID s_memcard_rename_check_constructor;

extern jclass s_memcard_write_class;

extern jmethodID s_memcard_write_constructor;

extern jobject s_memcard_write_outcomes[static_cast<size_t>(MemcardEditorBridge::WriteOutcome::Count)];

extern std::mutex s_callbacks_mutex;

extern jobject s_callbacks;

extern AndroidContentProvider s_content_provider;

jobject ToArgbImage(JNIEnv* env, const u32* rgba, u32 width, u32 height, bool opaque);

jobjectArray ToJStringArray(JNIEnv* env, const std::vector<std::string>& strings);

jobjectArray ToPadBindingArray(JNIEnv* env, const std::optional<std::vector<AndroidHost::PadBinding>>& bindings);

jobjectArray ToNonNullStringArray(JNIEnv* env, const std::vector<std::string>& strings);
