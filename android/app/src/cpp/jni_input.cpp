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

NATIVE_LIBRARY_FUNCTION(void, setTouchPad)(JNIEnv*, jobject, jint buttons, jfloat left_x, jfloat left_y, jfloat right_x,
                                           jfloat right_y)
{
  AndroidHost::QueueTouchPad(TouchPad::State{static_cast<u32>(buttons), {left_x, left_y, right_x, right_y}});
}

NATIVE_LIBRARY_FUNCTION(void, queueTouchPadsForTest)(JNIEnv* env, jobject, jintArray buttons)
{
  std::vector<u32> states(static_cast<size_t>(env->GetArrayLength(buttons)));
  if (!states.empty())
    env->GetIntArrayRegion(buttons, 0, static_cast<jsize>(states.size()), reinterpret_cast<jint*>(states.data()));
  AndroidHost::QueueTouchPadsForTest(states);
}

NATIVE_LIBRARY_FUNCTION(jstring, decodeUtf8ForTest)(JNIEnv* env, jobject, jbyteArray bytes)
{
  std::string text(static_cast<size_t>(env->GetArrayLength(bytes)), '\0');
  if (!text.empty())
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<jbyte*>(text.data()));
  return Made(env, ToJString(env, text), "decodeUtf8ForTest");
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, stringArrayRoundTripForTest)(JNIEnv* env, jobject, jobjectArray strings)
{
  return Made(env, ToJStringArray(env, JNIUtil::FromJStringArray(env, strings)), "stringArrayRoundTripForTest");
}

NATIVE_LIBRARY_FUNCTION(jlongArray, longArrayRoundTripForTest)(JNIEnv* env, jobject, jlongArray values)
{
  std::vector<jlong> longs(static_cast<size_t>(env->GetArrayLength(values)));
  if (!longs.empty())
    env->GetLongArrayRegion(values, 0, static_cast<jsize>(longs.size()), longs.data());
  return Made(env, JNIUtil::ToJLongArray(env, std::vector<s64>(longs.begin(), longs.end())),
              "longArrayRoundTripForTest");
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, objectArrayForTest)
(JNIEnv* env, jobject, jint count, jint fail_at, jboolean throw_on_fail)
{
  std::vector<jint> items(static_cast<size_t>(std::max<jint>(count, 0)));
  for (size_t i = 0; i < items.size(); i++)
    items[i] = static_cast<jint>(i);

  jint calls = 0;
  return JNIUtil::ToObjectArray(env, s_string_class, items, [&](JNIEnv* env, jint item) -> jobject {
    const jint call = calls++;
    if (item != fail_at)
      return ToJString(env, fmt::format("{}@{}", item, call));
    if (throw_on_fail == JNI_TRUE)
    {
      if (jclass cls = env->FindClass("java/lang/IllegalArgumentException"))
      {
        env->ThrowNew(cls, fmt::format("item {}", item).c_str());
        env->DeleteLocalRef(cls);
      }
    }
    return nullptr;
  });
}

NATIVE_LIBRARY_FUNCTION(jint, takeTouchPressesForTest)(JNIEnv*, jobject)
{
  return static_cast<jint>(AndroidHost::TakeTouchPressesForTest());
}

NATIVE_LIBRARY_FUNCTION(void, setPictureAtTop)(JNIEnv*, jobject, jboolean top)
{
  AndroidHost::SetPictureAtTop(top);
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

} // extern "C"
