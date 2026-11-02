// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "http_downloader_android.h"
#include "jni_util.h"

#include "common/log.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <unordered_map>

Log_SetChannel(HTTPDownloader);

namespace {
// Cached at JNI_OnLoad.
jclass s_bridge_class = nullptr;
jmethodID s_start = nullptr;
jmethodID s_cancel = nullptr;

constexpr const char* BRIDGE_CLASS = "io/github/ilyalissoboi/goosestation/core/HttpBridge";

// The downloaders alive, by id, so an answer for one that's gone is dropped rather than used after it's freed.
std::mutex s_live_mutex;
std::unordered_map<jlong, HTTPDownloaderAndroid*> s_live;

// Downloaders' and requests' ids. Never reused, unlike addresses: an answer that comes late can't reach another.
std::atomic<jlong> s_next_id{1};
} // namespace

std::unique_ptr<HTTPDownloader> HTTPDownloader::Create(std::string user_agent)
{
  if (!s_bridge_class)
  {
    ERROR_LOG("HttpBridge isn't loaded, so there are no HTTP downloads.");
    return nullptr;
  }

  return std::make_unique<HTTPDownloaderAndroid>(std::move(user_agent));
}

bool HTTPDownloaderAndroid::LoadJNIReferences(JNIEnv* env)
{
  jclass bridge_class = env->FindClass(BRIDGE_CLASS);
  if (!bridge_class)
  {
    JNIUtil::ClearException(env);
    return false;
  }

  // A failed lookup leaves an exception pending, so the first one to fail ends the chain.
  const bool found =
    (s_start = env->GetStaticMethodID(bridge_class, "start", "(JJLjava/lang/String;[BLjava/lang/String;I)V")) &&
    (s_cancel = env->GetStaticMethodID(bridge_class, "cancel", "(JJ)V"));
  JNIUtil::ClearException(env);

  if (found)
    s_bridge_class = static_cast<jclass>(env->NewGlobalRef(bridge_class));
  env->DeleteLocalRef(bridge_class);
  return found;
}

HTTPDownloaderAndroid::HTTPDownloaderAndroid(std::string user_agent)
  : m_user_agent(std::move(user_agent)), m_id(s_next_id.fetch_add(1))
{
  std::unique_lock lock(s_live_mutex);
  s_live.emplace(m_id, this);
}

HTTPDownloaderAndroid::~HTTPDownloaderAndroid()
{
  {
    std::unique_lock lock(s_live_mutex);
    s_live.erase(m_id);
  }

  // Anything still out is cancelled; its answer, if one comes, finds this downloader gone.
  std::unique_lock lock(m_pending_http_request_lock);
  for (Request* req : m_pending_http_requests)
    CloseRequest(req);
  m_pending_http_requests.clear();
}

void HTTPDownloaderAndroid::Complete(jlong downloader, jlong request, s32 status, std::string content_type,
                                     Request::Data body)
{
  std::unique_lock lock(s_live_mutex);
  const auto it = s_live.find(downloader);
  if (it == s_live.end())
    return;

  HTTPDownloaderAndroid* self = it->second;
  std::unique_lock results_lock(self->m_results_mutex);
  self->m_results.push_back(Result{request, status, std::move(content_type), std::move(body)});
}

HTTPDownloader::Request* HTTPDownloaderAndroid::InternalCreateRequest()
{
  AndroidRequest* req = new AndroidRequest();
  req->id = s_next_id.fetch_add(1);
  return req;
}

void HTTPDownloaderAndroid::InternalPollRequests()
{
  // Called with m_pending_http_request_lock held.
  std::vector<Result> results;
  {
    std::unique_lock lock(m_results_mutex);
    results.swap(m_results);
  }

  for (Result& result : results)
  {
    const auto it = std::find_if(m_pending_http_requests.begin(), m_pending_http_requests.end(),
                                 [&result](const Request* req) {
                                   return static_cast<const AndroidRequest*>(req)->id == result.request;
                                 });
    if (it == m_pending_http_requests.end())
      continue;

    Request* req = *it;
    if (req->state.load() != Request::State::Started)
      continue;

    req->status_code = result.status;
    req->content_type = std::move(result.content_type);
    req->data = std::move(result.body);
    req->state.store(Request::State::Complete);
  }
}

bool HTTPDownloaderAndroid::StartRequest(HTTPDownloader::Request* req)
{
  ScopedJNIEnv env;
  bool started = false;
  if (env)
  {
    jstring jurl = JNIUtil::ToJString(env.get(), req->url);
    jstring jagent = JNIUtil::ToJString(env.get(), m_user_agent);
    jbyteArray jpost = nullptr;
    if (req->type == Request::Type::Post)
    {
      jpost = env->NewByteArray(static_cast<jsize>(req->post_data.size()));
      if (jpost)
      {
        env->SetByteArrayRegion(jpost, 0, static_cast<jsize>(req->post_data.size()),
                                reinterpret_cast<const jbyte*>(req->post_data.data()));
      }
    }

    if (jurl && jagent && (req->type != Request::Type::Post || jpost))
    {
      const jint timeout_ms = static_cast<jint>(std::max(1.0f, std::ceil(m_timeout * 1000.0f)));
      req->state.store(Request::State::Started);
      env->CallStaticVoidMethod(s_bridge_class, s_start, m_id, static_cast<AndroidRequest*>(req)->id, jurl, jpost,
                                jagent, timeout_ms);
      started = !JNIUtil::ClearException(env.get());
    }

    if (jpost)
      env->DeleteLocalRef(jpost);
    if (jagent)
      env->DeleteLocalRef(jagent);
    if (jurl)
      env->DeleteLocalRef(jurl);
  }

  if (!started)
  {
    ERROR_LOG("Failed to start an HTTP request.");
    req->callback(HTTP_STATUS_ERROR, std::string(), Request::Data());
    delete static_cast<AndroidRequest*>(req);
    return false;
  }

  return true;
}

void HTTPDownloaderAndroid::CloseRequest(HTTPDownloader::Request* req)
{
  // A request that never started, or whose answer is in, has nothing running in Kotlin.
  const Request::State state = req->state.load();
  if (state == Request::State::Started || state == Request::State::Receiving || state == Request::State::Cancelled)
  {
    ScopedJNIEnv env;
    if (env)
    {
      env->CallStaticVoidMethod(s_bridge_class, s_cancel, m_id, static_cast<AndroidRequest*>(req)->id);
      JNIUtil::ClearException(env.get());
    }
  }

  // An answer already in for it is no one's now.
  {
    const jlong id = static_cast<AndroidRequest*>(req)->id;
    std::unique_lock lock(m_results_mutex);
    std::erase_if(m_results, [id](const Result& result) { return result.request == id; });
  }

  delete static_cast<AndroidRequest*>(req);
}
