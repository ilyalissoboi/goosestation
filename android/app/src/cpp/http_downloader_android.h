// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "util/http_downloader.h"

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

  /// HttpBridge's answer for request [request] of downloader [downloader], from any thread. A downloader that's
  /// gone, or a request it no longer has, is ignored.
  static void Complete(jlong downloader, jlong request, s32 status, std::string content_type, Request::Data body);

protected:
  Request* InternalCreateRequest() override;
  void InternalPollRequests() override;
  bool StartRequest(HTTPDownloader::Request* request) override;
  void CloseRequest(HTTPDownloader::Request* request) override;

private:
  struct Result
  {
    jlong request;
    s32 status;
    std::string content_type;
    Request::Data body;
  };

  std::string m_user_agent;

  std::mutex m_results_mutex;
  std::vector<Result> m_results;
};
