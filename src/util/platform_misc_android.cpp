// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "platform_misc.h"

#include "common/error.h"

#include <cerrno>
#include <csignal>
#include <unistd.h>

bool PlatformMisc::InitializeSocketSupport(Error* error)
{
  // A write to a closed socket would otherwise kill the app; the socket code checks for EPIPE instead.
  if (std::signal(SIGPIPE, SIG_IGN) == SIG_ERR)
  {
    Error::SetErrno(error, "signal(SIGPIPE, SIG_IGN) failed: ", errno);
    return false;
  }

  return true;
}

void PlatformMisc::SuspendScreensaver()
{
  // No screensaver inhibit: keeping the screen on is up to the app.
}

void PlatformMisc::ResumeScreensaver()
{
}

size_t PlatformMisc::GetRuntimePageSize()
{
  const long res = sysconf(_SC_PAGESIZE);
  return (res > 0) ? static_cast<size_t>(res) : 0;
}

static void (*s_sound_player)(const char* path) = nullptr;

void PlatformMisc::SetSoundPlayer(void (*play)(const char* path))
{
  s_sound_player = play;
}

bool PlatformMisc::PlaySoundAsync(const char* path)
{
  // The app plays it, if it has said how (SetSoundPlayer()).
  if (!s_sound_player)
    return false;

  s_sound_player(path);
  return true;
}
