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

NATIVE_LIBRARY_FUNCTION(jintArray, downloadCovers)(JNIEnv* env, jobject, jobjectArray jsources, jboolean use_serial,
                                                    jobject listener)
{
  std::vector<std::string> sources;
  const jsize count = jsources ? env->GetArrayLength(jsources) : 0;
  for (jsize i = 0; i < count; i++)
  {
    jstring source = static_cast<jstring>(env->GetObjectArrayElement(jsources, i));
    sources.push_back(FromJString(env, source));
    env->DeleteLocalRef(source);
  }

  const AndroidCoverDownloads::Summary summary =
    CoverDownloadsAndroid::Download(env, sources, use_serial == JNI_TRUE, listener);
  const jint values[] = {static_cast<jint>(summary.total),       static_cast<jint>(summary.found),
                         static_cast<jint>(summary.missing),     static_cast<jint>(summary.unreachable),
                         static_cast<jint>(summary.unsaved),     summary.cancelled ? 1 : 0,
                         static_cast<jint>(summary.games)};
  constexpr jsize fields = static_cast<jsize>(std::size(values));
  jintArray result = env->NewIntArray(fields);
  if (!result)
    return Made(env, result, "downloadCovers");
  env->SetIntArrayRegion(result, 0, fields, values);
  return result;
}

// A streamed download's progress, for the ProgressCallback at progress: false once it's cancelled.
NATIVE_LIBRARY_FUNCTION(jboolean, downloadProgress)(JNIEnv*, jobject, jlong progress, jlong done, jlong total)
{
  ProgressCallback* callback = reinterpret_cast<ProgressCallback*>(progress);
  if (!callback)
    return JNI_TRUE;
  callback->SetProgressRange((total > 0) ? static_cast<u32>(std::min<jlong>(total, UINT32_MAX)) : 0);
  callback->SetProgressValue(static_cast<u32>(std::clamp<jlong>(done, 0, UINT32_MAX)));
  return callback->IsCancelled() ? JNI_FALSE : JNI_TRUE;
}

NATIVE_LIBRARY_FUNCTION(void, httpDone)(JNIEnv* env, jobject, jlong downloader, jlong request, jint status,
                                        jstring content_type, jbyteArray body)
{
  HTTPDownloader::Request::Data data;
  if (body)
  {
    data.resize(static_cast<size_t>(env->GetArrayLength(body)));
    if (!data.empty())
      env->GetByteArrayRegion(body, 0, static_cast<jsize>(data.size()), reinterpret_cast<jbyte*>(data.data()));
  }

  HTTPDownloaderAndroid::Complete(downloader, request, static_cast<s32>(status), FromJString(env, content_type),
                                  std::move(data));
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, httpTestFetch)(JNIEnv* env, jobject, jstring url, jstring post,
                                                     jfloat timeout_seconds)
{
  // For HttpDownloaderTest only: one request through the core's downloader, waited for here.
  s32 status = HTTPDownloader::HTTP_STATUS_ERROR;
  std::string type;
  std::string text;
  if (std::unique_ptr<HTTPDownloader> downloader = HTTPDownloader::Create("GooseStation test"))
  {
    downloader->SetTimeout(timeout_seconds);
    auto callback = [&](s32 code, const std::string& content_type, HTTPDownloader::Request::Data data) {
      status = code;
      type = content_type;
      text.assign(data.begin(), data.end());
    };
    if (post)
      downloader->CreatePostRequest(FromJString(env, url), FromJString(env, post), std::move(callback));
    else
      downloader->CreateRequest(FromJString(env, url), std::move(callback));
    downloader->WaitForAllRequests();
  }

  const std::string values[] = {std::to_string(status), type, text};
  jobjectArray ret = env->NewObjectArray(static_cast<jsize>(std::size(values)), s_string_class, nullptr);
  if (!ret)
  {
    ClearException(env);
    return nullptr;
  }

  for (size_t i = 0; i < std::size(values); i++)
  {
    jstring value = ToJString(env, values[i]);
    env->SetObjectArrayElement(ret, static_cast<jsize>(i), value);
    if (value)
      env->DeleteLocalRef(value);
  }

  return ret;
}

NATIVE_LIBRARY_FUNCTION(jintArray, httpTestFetchQueued)(JNIEnv* env, jobject, jobjectArray urls,
                                                         jfloat timeout_seconds)
{
  // For HttpDownloaderTest only: GETs [urls] through the core's downloader one at a time, so each after the first
  // waits in its queue, and returns their statuses.
  const jsize count = env->GetArrayLength(urls);
  std::vector<jint> statuses(static_cast<size_t>(count), HTTPDownloader::HTTP_STATUS_ERROR);
  if (std::unique_ptr<HTTPDownloader> downloader = HTTPDownloader::Create("GooseStation test"))
  {
    downloader->SetTimeout(timeout_seconds);
    downloader->SetMaxActiveRequests(1);
    for (jsize i = 0; i < count; i++)
    {
      jstring url = static_cast<jstring>(env->GetObjectArrayElement(urls, i));
      downloader->CreateRequest(FromJString(env, url),
                                [&statuses, i](s32 code, const std::string&, HTTPDownloader::Request::Data) {
                                  statuses[static_cast<size_t>(i)] = code;
                                });
      env->DeleteLocalRef(url);
    }
    downloader->WaitForAllRequests();
  }

  jintArray ret = env->NewIntArray(count);
  if (!ret)
  {
    ClearException(env);
    return nullptr;
  }
  env->SetIntArrayRegion(ret, 0, count, statuses.data());
  return ret;
}

} // extern "C"
