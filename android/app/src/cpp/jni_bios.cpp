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

extern "C" {

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

} // extern "C"
