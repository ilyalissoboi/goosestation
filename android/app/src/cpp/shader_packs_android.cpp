// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Android's shader pack transport: the version checks go through HTTPDownloader, and the archive streams to its file
// through HttpBridge, rather than being held in memory (the libretro pack is tens of megabytes).

#include "http_downloader_android.h"

#include "core/host.h"
#include "core/shader_packs.h"

#include <memory>

namespace {

class AndroidTransport final : public ShaderPacks::Transport
{
public:
  explicit AndroidTransport(std::unique_ptr<ShaderPacks::Transport> http) : m_http(std::move(http)) {}

  s32 Get(const std::string& url, std::string* body, ProgressCallback* progress) override
  {
    return m_http->Get(url, body, progress);
  }

  s32 Download(const std::string& url, const std::string& path, u64 max_size, ProgressCallback* progress,
               Error*) override
  {
    // An idle timeout: each read waits at most this long.
    static constexpr u32 TIMEOUT_MS = 30000;
    return HTTPDownloaderAndroid::StreamToFile(url, path, Host::GetHTTPUserAgent(), TIMEOUT_MS, max_size, progress);
  }

private:
  std::unique_ptr<ShaderPacks::Transport> m_http;
};

} // namespace

std::unique_ptr<ShaderPacks::Transport> ShaderPacks::CreateTransport()
{
  std::unique_ptr<Transport> http = CreateHTTPTransport();
  if (!http)
    return {};
  return std::make_unique<AndroidTransport>(std::move(http));
}
