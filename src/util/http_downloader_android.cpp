// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "http_downloader.h"

#include "common/log.h"

Log_SetChannel(HTTPDownloader);

std::unique_ptr<HTTPDownloader> HTTPDownloader::Create(std::string user_agent)
{
  // Create() has no Error parameter, so the reason goes to the log; callers report the null downloader.
  ERROR_LOG("HTTP downloads are not supported on Android yet.");
  return nullptr;
}
