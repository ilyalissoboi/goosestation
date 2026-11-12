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

NATIVE_LIBRARY_FUNCTION(jlongArray, getSaveStateTimes)(JNIEnv* env, jobject, jstring serial)
{
  return Made(env, JNIUtil::ToJLongArray(env, AndroidHost::GetSaveStateTimes(FromJString(env, serial))), "getSaveStateTimes");
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
  return Made(env, JNIUtil::ToJLongArray(env, AndroidHost::GetSharedStateTimes()), "getSharedStateTimes");
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

NATIVE_LIBRARY_FUNCTION(void, saveScreenshot)(JNIEnv*, jobject)
{
  AndroidHost::SaveScreenshot();
}

} // extern "C"
