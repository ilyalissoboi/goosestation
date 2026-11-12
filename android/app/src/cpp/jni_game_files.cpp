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
  return Made(env,
              ToJString(env, AndroidHost::GetGameStringSetting(FromJString(env, key), FromJString(env, section).c_str(),
                                                          FromJString(env, name).c_str(),
                                                          FromJString(env, default_value).c_str())),
              "getGameStringSetting");
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

} // extern "C"
