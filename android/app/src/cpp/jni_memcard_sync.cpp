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

namespace {

// values[value], or null, logged, for a value it has none for: a C++ enum that grew without its Kotlin twin (one the
// build can't check, as MemcardSync::StatusKind, which desktop shares, has no Count).
template<size_t N, typename E>
jobject EnumValue(jobject (&values)[N], E value, const char* what)
{
  const size_t index = static_cast<size_t>(value);
  if (index < N)
    return values[index];
  ERROR_LOG("No {} for value {}.", what, index);
  return nullptr;
}

} // namespace

extern "C" {

NATIVE_LIBRARY_FUNCTION(jboolean, isMemcardSyncAvailable)(JNIEnv*, jobject)
{
  return MemcardSyncBridge::IsAvailable() ? JNI_TRUE : JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(jobject, getMemcardSyncState)(JNIEnv* env, jobject)
{
  const MemcardSyncBridge::State state = MemcardSyncBridge::GetState();
  const std::string* strings[] = {&state.remote_uri, &state.access_key_id, &state.device_name,
                                  &state.default_device_name, &state.start_error, &state.status};
  jstring jstrings[std::size(strings)] = {};
  jobject ret = nullptr;
  jobject kind = EnumValue(s_sync_status_kinds, state.kind, "SyncStatusKind");
  jobject activity = EnumValue(s_sync_activities, state.activity, "SyncActivity");
  bool made = kind && activity;
  for (size_t i = 0; i < std::size(strings); i++)
    made = made && (jstrings[i] = ToJString(env, *strings[i])) != nullptr;
  if (made)
  {
    ret = env->NewObject(s_memcard_sync_state_class, s_memcard_sync_state_constructor, kind,
                         static_cast<jboolean>(state.enabled),
                         static_cast<jboolean>(state.running), static_cast<jboolean>(state.applying), jstrings[0],
                         jstrings[1], jstrings[2], jstrings[3], static_cast<jboolean>(state.has_secret),
                         static_cast<jboolean>(state.has_passphrase), static_cast<jboolean>(state.passphrase_recorded),
                         jstrings[4], jstrings[5], activity,
                         static_cast<jint>(state.conflicts));
  }
  for (jstring str : jstrings)
  {
    if (str)
      env->DeleteLocalRef(str);
  }
  return Made(env, ret, "getMemcardSyncState");
}

NATIVE_LIBRARY_FUNCTION(void, applyMemcardSync)(JNIEnv*, jobject)
{
  MemcardSyncBridge::Apply();
}

namespace {

// A SyncVersion, or null (with no exception pending) if it can't be made.
jobject ToSyncVersion(JNIEnv* env, const MemcardSyncBridge::Version& v)
{
  jstring device = ToJString(env, v.device);
  jobject ret = device ? env->NewObject(s_sync_version_class, s_sync_version_constructor, device,
                                        static_cast<jlong>(v.modified_unix_ms), static_cast<jboolean>(v.time_known),
                                        static_cast<jint>(v.blocks), static_cast<jboolean>(v.deleted)) :
                         nullptr;
  if (!ret)
    ClearException(env);
  if (device)
    env->DeleteLocalRef(device);
  return ret;
}

// A SyncConflict, or null (with no exception pending) if it can't be made.
jobject ToSyncConflict(JNIEnv* env, const MemcardSyncBridge::Conflict& c)
{
  const JNIUtil::LocalRef<jstring> name(env, ToJString(env, c.name));
  const JNIUtil::LocalRef<jobject> local(env, c.local.has_value() ? ToSyncVersion(env, c.local.value()) : nullptr);
  const JNIUtil::LocalRef<jobjectArray> remote(
    env, env->NewObjectArray(static_cast<jsize>(c.remote.size()), s_sync_version_class, nullptr));
  jobject ret = nullptr;
  bool made = name && remote && (local || !c.local.has_value());
  for (size_t i = 0; made && i < c.remote.size(); i++)
  {
    const JNIUtil::LocalRef<jobject> v(env, ToSyncVersion(env, c.remote[i]));
    made = static_cast<bool>(v);
    if (v)
      env->SetObjectArrayElement(remote.get(), static_cast<jsize>(i), v.get());
  }
  if (made)
  {
    ret = env->NewObject(s_sync_conflict_class, s_sync_conflict_constructor, static_cast<jlong>(c.id), name.get(),
                         local.get(), remote.get(), static_cast<jint>(c.default_choice));
  }
  if (!ret)
    ClearException(env);
  return ret;
}

} // namespace

NATIVE_LIBRARY_FUNCTION(jobjectArray, getMemcardSyncConflicts)(JNIEnv* env, jobject)
{
  const std::vector<MemcardSyncBridge::Conflict> conflicts = MemcardSyncBridge::GetConflicts();
  jobjectArray ret = env->NewObjectArray(static_cast<jsize>(conflicts.size()), s_sync_conflict_class, nullptr);
  if (!ret)
    return Made(env, ret, "getMemcardSyncConflicts");
  for (size_t i = 0; i < conflicts.size(); i++)
  {
    const JNIUtil::LocalRef<jobject> c(env, ToSyncConflict(env, conflicts[i]));
    if (!c)
    {
      env->DeleteLocalRef(ret);
      return Made<jobjectArray>(env, nullptr, "getMemcardSyncConflicts");
    }
    env->SetObjectArrayElement(ret, static_cast<jsize>(i), c.get());
  }
  return ret;
}

NATIVE_LIBRARY_FUNCTION(void, resolveMemcardSyncConflict)(JNIEnv*, jobject, jlong id, jboolean keep_local,
                                                          jint remote_index)
{
  MemcardSyncBridge::ResolveConflict(static_cast<u64>(id), keep_local == JNI_TRUE,
                                     static_cast<u32>(std::max<jint>(remote_index, 0)));
}

NATIVE_LIBRARY_FUNCTION(jboolean, memcardSyncHoldCard)(JNIEnv* env, jobject, jstring relative_path, jobject action)
{
  // An exception from the action is taken off while the card is let go, which may call into Java (sync says it
  // changed), then thrown again, so it reaches the caller.
  jthrowable thrown = nullptr;
  const bool held = MemcardSyncBridge::HoldCard(FromJString(env, relative_path), [env, action, &thrown]() {
    env->CallVoidMethod(action, s_runnable_run);
    if ((thrown = env->ExceptionOccurred()) != nullptr)
      env->ExceptionClear();
  });
  if (thrown)
  {
    env->Throw(thrown);
    env->DeleteLocalRef(thrown);
  }
  return held ? JNI_TRUE : JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(void, memcardCardOpenedForTest)(JNIEnv* env, jobject, jstring path)
{
  MemcardSyncBridge::CardOpenedForTest(FromJString(env, path));
}

NATIVE_LIBRARY_FUNCTION(void, memcardCardWrittenForTest)(JNIEnv* env, jobject, jstring path)
{
  MemcardSyncBridge::CardWrittenForTest(FromJString(env, path));
}

NATIVE_LIBRARY_FUNCTION(void, memcardCardClosedForTest)(JNIEnv* env, jobject, jstring path)
{
  MemcardSyncBridge::CardClosedForTest(FromJString(env, path));
}

NATIVE_LIBRARY_FUNCTION(jboolean, writeMemoryCardSaveForTest)(JNIEnv* env, jobject, jstring path, jstring name,
                                                              jbyteArray data)
{
  std::vector<u8> bytes(static_cast<size_t>(env->GetArrayLength(data)));
  env->GetByteArrayRegion(data, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<jbyte*>(bytes.data()));
  return MemcardSyncBridge::WriteSaveForTest(FromJString(env, path), FromJString(env, name), bytes) ? JNI_TRUE :
                                                                                                       JNI_FALSE;
}

NATIVE_LIBRARY_FUNCTION(jbyteArray, readMemoryCardSaveForTest)(JNIEnv* env, jobject, jstring path, jstring name)
{
  return JNIUtil::ToJByteArrayOrNull(env,
                                     MemcardSyncBridge::ReadSaveForTest(FromJString(env, path), FromJString(env, name)));
}

NATIVE_LIBRARY_FUNCTION(void, memcardSyncNow)(JNIEnv*, jobject)
{
  MemcardSyncBridge::SyncNow();
}

NATIVE_LIBRARY_FUNCTION(void, setMemcardSyncBackgrounded)(JNIEnv*, jobject, jboolean backgrounded)
{
  MemcardSyncBridge::SetBackgrounded(backgrounded == JNI_TRUE);
}

NATIVE_LIBRARY_FUNCTION(void, setMemcardSyncPeriodForTest)(JNIEnv*, jobject, jlong ms)
{
  MemcardSyncBridge::SetPeriodicSyncForTest(static_cast<u32>(std::max<jlong>(ms, 0)));
}

} // extern "C"
