// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// The JNI side of io.github.ilyalissoboi.goosestation.core.NativeLibrary, and the calls back into NativeCallbacks.

#include "android_host.h"
#include "content_bridge.h"
#include "jni_util.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"

#include <android/native_window_jni.h>
#include <jni.h>

#include <mutex>
#include <optional>
#include <string>

Log_SetChannel(JNIBridge);

using JNIUtil::ClearException;
using JNIUtil::FromJString;
using JNIUtil::ToJString;

namespace {
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

AndroidContentProvider s_content_provider;

// Calls a NativeCallbacks method on the registered object, from any thread.
void CallCallback(jmethodID method, std::optional<std::string_view> arg)
{
  ScopedJNIEnv env;
  if (!env)
    return;

  jobject callbacks = nullptr;
  {
    std::unique_lock lock(s_callbacks_mutex);
    if (s_callbacks)
      callbacks = env->NewLocalRef(s_callbacks);
  }

  if (!callbacks)
    return;

  if (arg.has_value())
  {
    jstring jarg = ToJString(env.get(), arg.value());
    if (jarg)
    {
      env->CallVoidMethod(callbacks, method, jarg);
      ClearException(env.get());
      env->DeleteLocalRef(jarg);
    }
  }
  else
  {
    env->CallVoidMethod(callbacks, method);
    ClearException(env.get());
  }

  env->DeleteLocalRef(callbacks);
}
} // namespace

void AndroidHost::AttachCurrentThreadToJVM(const char* name)
{
  JavaVM* const vm = JNIUtil::GetJavaVM();
  if (!vm || s_attached_env)
    return;

  JavaVMAttachArgs args = {JNI_VERSION_1_6, name, nullptr};
  JNIEnv* env = nullptr;
  if (vm->AttachCurrentThread(&env, &args) != JNI_OK)
  {
    ERROR_LOG("Failed to attach '{}' to the JVM.", name);
    return;
  }

  s_attached_env = env;
}

void AndroidHost::DetachCurrentThreadFromJVM()
{
  JavaVM* const vm = JNIUtil::GetJavaVM();
  if (!vm || !s_attached_env)
    return;

  vm->DetachCurrentThread();
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

  if (!AndroidContentProvider::LoadJNIReferences(env))
    return JNI_ERR;

  JNIUtil::SetJavaVM(vm);
  FileSystem::SetContentProvider(&s_content_provider);
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

NATIVE_LIBRARY_FUNCTION(void, releaseAllInputs)(JNIEnv*, jobject)
{
  AndroidHost::QueueReleaseAllInputs();
}

} // extern "C"
