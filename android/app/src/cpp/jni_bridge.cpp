// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// The JNI side of io.github.ilyalissoboi.goosestation.core.NativeLibrary, and the calls back into NativeCallbacks.

#include "android_host.h"

#include "common/error.h"
#include "common/log.h"
#include "common/string_util.h"

#include <android/native_window_jni.h>
#include <jni.h>

#include <mutex>
#include <optional>
#include <string>
#include <vector>

Log_SetChannel(JNIBridge);

namespace {
JavaVM* s_vm = nullptr;

// Cached at JNI_OnLoad.
jclass s_callbacks_class = nullptr;
jmethodID s_on_game_started = nullptr;
jmethodID s_on_game_stopped = nullptr;
jmethodID s_on_error = nullptr;

// The app's NativeCallbacks (a global reference), or null.
std::mutex s_callbacks_mutex;
jobject s_callbacks = nullptr;

// Set on the thread AttachCurrentThreadToJVM() attached.
thread_local JNIEnv* s_attached_env = nullptr;

void ClearException(JNIEnv* env)
{
  if (env->ExceptionCheck())
  {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

// Java strings are UTF-16; JNI's own UTF conversions use modified UTF-8, which differs for NUL and characters
// outside the BMP, so convert by hand.
std::string FromJString(JNIEnv* env, jstring str)
{
  std::string ret;
  if (!str)
    return ret;

  const jsize length = env->GetStringLength(str);
  const jchar* chars = env->GetStringChars(str, nullptr);
  if (!chars)
  {
    ClearException(env);
    return ret;
  }

  ret.reserve(static_cast<size_t>(length));
  for (jsize i = 0; i < length; i++)
  {
    char32_t ch = chars[i];
    if (ch >= 0xD800 && ch <= 0xDBFF && (i + 1) < length && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF)
    {
      ch = 0x10000 + ((ch - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
      i++;
    }
    else if (ch >= 0xD800 && ch <= 0xDFFF)
    {
      ch = 0xFFFD; // unpaired surrogate
    }
    StringUtil::EncodeAndAppendUTF8(ret, ch);
  }

  env->ReleaseStringChars(str, chars);
  return ret;
}

jstring ToJString(JNIEnv* env, std::string_view str)
{
  std::vector<jchar> chars;
  chars.reserve(str.size());
  for (size_t offset = 0; offset < str.size();)
  {
    char32_t ch;
    offset += StringUtil::DecodeUTF8(str, offset, &ch);
    if (ch > 0x10FFFF)
      ch = 0xFFFD;

    if (ch >= 0x10000)
    {
      ch -= 0x10000;
      chars.push_back(static_cast<jchar>(0xD800 + (ch >> 10)));
      chars.push_back(static_cast<jchar>(0xDC00 + (ch & 0x3FF)));
    }
    else
    {
      chars.push_back(static_cast<jchar>(ch));
    }
  }

  static constexpr jchar empty = 0;
  jstring ret = env->NewString(chars.empty() ? &empty : chars.data(), static_cast<jsize>(chars.size()));
  if (!ret)
    ClearException(env);
  return ret;
}

// Calls a NativeCallbacks method on the registered object, from any thread.
void CallCallback(jmethodID method, std::optional<std::string_view> arg)
{
  if (!s_vm)
    return;

  JNIEnv* env = nullptr;
  bool attached_here = false;
  const jint res = s_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  if (res == JNI_EDETACHED)
  {
    if (s_vm->AttachCurrentThread(&env, nullptr) != JNI_OK)
    {
      ERROR_LOG("Failed to attach a thread to the JVM for a callback.");
      return;
    }
    attached_here = true;
  }
  else if (res != JNI_OK)
  {
    return;
  }

  jobject callbacks = nullptr;
  {
    std::unique_lock lock(s_callbacks_mutex);
    if (s_callbacks)
      callbacks = env->NewLocalRef(s_callbacks);
  }

  if (callbacks)
  {
    if (arg.has_value())
    {
      jstring jarg = ToJString(env, arg.value());
      if (jarg)
      {
        env->CallVoidMethod(callbacks, method, jarg);
        ClearException(env);
        env->DeleteLocalRef(jarg);
      }
    }
    else
    {
      env->CallVoidMethod(callbacks, method);
      ClearException(env);
    }

    env->DeleteLocalRef(callbacks);
  }

  if (attached_here)
    s_vm->DetachCurrentThread();
}
} // namespace

void AndroidHost::AttachCurrentThreadToJVM(const char* name)
{
  if (!s_vm || s_attached_env)
    return;

  JavaVMAttachArgs args = {JNI_VERSION_1_6, name, nullptr};
  JNIEnv* env = nullptr;
  if (s_vm->AttachCurrentThread(&env, &args) != JNI_OK)
  {
    ERROR_LOG("Failed to attach '{}' to the JVM.", name);
    return;
  }

  s_attached_env = env;
}

void AndroidHost::DetachCurrentThreadFromJVM()
{
  if (!s_vm || !s_attached_env)
    return;

  s_vm->DetachCurrentThread();
  s_attached_env = nullptr;
}

void AndroidHost::NotifyGameStarted(std::string_view title)
{
  CallCallback(s_on_game_started, title);
}

void AndroidHost::NotifyGameStopped()
{
  CallCallback(s_on_game_stopped, std::nullopt);
}

void AndroidHost::NotifyError(std::string_view message)
{
  CallCallback(s_on_error, message);
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*)
{
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
    return JNI_ERR;

  jclass callbacks_class = env->FindClass("io/github/ilyalissoboi/goosestation/core/NativeCallbacks");
  if (!callbacks_class)
  {
    ClearException(env);
    return JNI_ERR;
  }

  s_callbacks_class = static_cast<jclass>(env->NewGlobalRef(callbacks_class));
  env->DeleteLocalRef(callbacks_class);
  s_on_game_started = env->GetMethodID(s_callbacks_class, "onGameStarted", "(Ljava/lang/String;)V");
  s_on_game_stopped = env->GetMethodID(s_callbacks_class, "onGameStopped", "()V");
  s_on_error = env->GetMethodID(s_callbacks_class, "onError", "(Ljava/lang/String;)V");
  if (!s_on_game_started || !s_on_game_stopped || !s_on_error)
  {
    ClearException(env);
    return JNI_ERR;
  }

  s_vm = vm;
  return JNI_VERSION_1_6;
}

#define NATIVE_LIBRARY_FUNCTION(ret, name) \
  JNIEXPORT ret JNICALL Java_io_github_ilyalissoboi_goosestation_core_NativeLibrary_##name

NATIVE_LIBRARY_FUNCTION(jboolean, initialize)(JNIEnv* env, jobject, jstring data_root, jstring resources_root)
{
  std::string data_root_str = FromJString(env, data_root);
  std::string resources_root_str = FromJString(env, resources_root);

  Error error;
  if (!AndroidHost::Initialize(std::move(data_root_str), std::move(resources_root_str), &error))
  {
    ERROR_LOG("Initialization failed: {}", error.GetDescription());
    AndroidHost::NotifyError(error.GetDescription());
    return JNI_FALSE;
  }

  return JNI_TRUE;
}

NATIVE_LIBRARY_FUNCTION(void, setCallbacks)(JNIEnv* env, jobject, jobject callbacks)
{
  jobject new_callbacks = callbacks ? env->NewGlobalRef(callbacks) : nullptr;
  jobject old_callbacks;
  {
    std::unique_lock lock(s_callbacks_mutex);
    old_callbacks = s_callbacks;
    s_callbacks = new_callbacks;
  }

  if (old_callbacks)
    env->DeleteGlobalRef(old_callbacks);
}

NATIVE_LIBRARY_FUNCTION(void, startGame)(JNIEnv* env, jobject, jstring path)
{
  AndroidHost::StartGame(FromJString(env, path));
}

NATIVE_LIBRARY_FUNCTION(void, shutdownGame)(JNIEnv*, jobject)
{
  AndroidHost::ShutdownGame();
}

NATIVE_LIBRARY_FUNCTION(void, setPaused)(JNIEnv*, jobject, jboolean paused)
{
  AndroidHost::SetPaused(paused == JNI_TRUE);
}

NATIVE_LIBRARY_FUNCTION(void, surfaceChanged)(JNIEnv* env, jobject, jobject surface, jint width, jint height,
                                              jfloat scale)
{
  ANativeWindow* window = nullptr;
  if (surface)
  {
    window = ANativeWindow_fromSurface(env, surface);
    ClearException(env);
    if (!window)
      ERROR_LOG("ANativeWindow_fromSurface() failed, treating the surface as destroyed.");
  }

  AndroidHost::SurfaceChanged(window, width, height, scale);
}

NATIVE_LIBRARY_FUNCTION(jboolean, onKey)(JNIEnv*, jobject, jint device_id, jint keycode, jboolean pressed)
{
  return AndroidHost::QueueKeyEvent(device_id, keycode, pressed == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
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

} // extern "C"
