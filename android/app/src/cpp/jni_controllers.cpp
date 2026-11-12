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

} // extern "C"
