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

NATIVE_LIBRARY_FUNCTION(void, setPaused)(JNIEnv*, jobject, jboolean paused)
{
  AndroidHost::SetPaused(paused == JNI_TRUE);
}

} // extern "C"
