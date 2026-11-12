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

extern "C" {

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
    const JNIUtil::LocalRef<jstring> uri(env, static_cast<jstring>(env->GetObjectArrayElement(tree_uris, i)));
    if (ClearException(env))
      return;

    if (uri)
      uris.push_back(FromJString(env, uri.get()));
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

NATIVE_LIBRARY_FUNCTION(jboolean, isVulkanFailed)(JNIEnv*, jobject)
{
  return AndroidHost::IsVulkanFailed() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
