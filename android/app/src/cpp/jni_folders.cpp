// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

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

#include "jni_classes.h"

using JNIUtil::ClearException;

using JNIUtil::FromJString;

using JNIUtil::ToJString;

extern "C" {
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

} // extern "C"
