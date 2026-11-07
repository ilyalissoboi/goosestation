// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "util/http_downloader.h"

class ProgressCallback;

#include <jni.h>

#include <mutex>
#include <string>
#include <vector>

/// The core's HTTPDownloader on Android: each request runs in Kotlin (HttpBridge, over HttpURLConnection), which
/// reports back through Complete(). Results wait in a list until the core's next PollRequests() picks them up.
class HTTPDownloaderAndroid final : public HTTPDownloader
{
public:
  explicit HTTPDownloaderAndroid(std::string user_agent);
  ~HTTPDownloaderAndroid() override;

  /// Caches HttpBridge's class and methods. Called by JNI_OnLoad.
  static bool LoadJNIReferences(JNIEnv* env);

  /// Streams url to the file at path on the calling thread, through HttpBridge.download: progress gets the bytes
  /// received (and the length, when known) and stops it when cancelled. The status, or HTTP_STATUS_ERROR when the
  /// bridge can't be reached. The file may be left behind on a failure.
  static s32 StreamToFile(const std::string& url, const std::string& path, const std::string& user_agent,
                          u32 timeout_ms, u64 max_bytes, ProgressCallback* progress);

  /// HttpBridge's answer for request [request] of downloader [downloader] (their ids, never reused), from any thread.
  /// A downloader that's gone, or a request it no longer has, is ignored.
  static void Complete(jlong downloader, jlong request, s32 status, std::string content_type, Request::Data body);

protected:
  Request* InternalCreateRequest() override;
  void InternalPollRequests() override;
  bool StartRequest(HTTPDownloader::Request* request) override;
  void CloseRequest(HTTPDownloader::Request* request) override;

private:
  struct AndroidRequest : HTTPDownloader::Request
  {
    jlong id;
  };

  struct Result
  {
    jlong request;
    s32 status;
    std::string content_type;
    Request::Data body;
  };

  std::string m_user_agent;
  jlong m_id;

  std::mutex m_results_mutex;
  std::vector<Result> m_results;
};
