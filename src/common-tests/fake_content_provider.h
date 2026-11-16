// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#ifndef _WIN32

#include "common/error.h"
#include "common/file_system.h"

#include "fmt/format.h"

#include <cerrno>
#include <string>
#include <string_view>

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

/// Serves ROOT from a local directory, the way the Android provider serves a granted tree. Counts the calls made to
/// it, and can refuse everything, as a revoked grant does.
class FakeProvider final : public FileSystem::ContentProvider
{
public:
  static constexpr std::string_view ROOT = "content://fake/tree/root";

  explicit FakeProvider(std::string root) : m_root(std::move(root)) {}

  void SetServePipes(bool serve_pipes) { m_serve_pipes = serve_pipes; }
  void SetRefuseAll(bool refuse) { m_refuse = refuse; }

  u32 GetListCalls() const { return m_list_calls; }
  u32 GetStatCalls() const { return m_stat_calls; }
  u32 GetOpenCalls() const { return m_open_calls; }

  int OpenForReading(std::string_view uri, Error* error) override
  {
    m_open_calls++;
    std::string local;
    if (m_refuse || !ToLocal(uri, &local))
    {
      Error::SetErrno(error, m_refuse ? EACCES : ENOENT);
      return -1;
    }

    if (m_serve_pipes)
    {
      int fds[2];
      if (pipe(fds) != 0)
      {
        Error::SetErrno(error, errno);
        return -1;
      }
      close(fds[1]);
      return fds[0];
    }

    const int fd = open(local.c_str(), O_RDONLY);
    if (fd < 0)
      Error::SetErrno(error, errno);
    return fd;
  }

  bool Stat(std::string_view uri, FILESYSTEM_STAT_DATA* sd) override
  {
    m_stat_calls++;
    return !m_refuse && StatLocal(uri, sd);
  }

  bool ListDirectory(std::string_view uri, FileSystem::FindResultsArray* entries, Error* error) override
  {
    m_list_calls++;
    std::string local;
    DIR* dir = (!m_refuse && ToLocal(uri, &local)) ? opendir(local.c_str()) : nullptr;
    if (!dir)
    {
      Error::SetErrno(error, m_refuse ? EACCES : ENOTDIR);
      return false;
    }

    while (const struct dirent* ent = readdir(dir))
    {
      const std::string_view name = ent->d_name;
      if (name == "." || name == "..")
        continue;

      FILESYSTEM_STAT_DATA sd;
      if (!StatLocal(fmt::format("{}/{}", uri, name), &sd))
        continue;

      FILESYSTEM_FIND_DATA fd;
      fd.FileName = name;
      fd.CreationTime = sd.CreationTime;
      fd.ModificationTime = sd.ModificationTime;
      fd.Size = sd.Size;
      fd.Attributes = sd.Attributes;
      entries->push_back(std::move(fd));
    }

    closedir(dir);
    return true;
  }

private:
  bool ToLocal(std::string_view uri, std::string* local) const
  {
    if (!uri.starts_with(ROOT) || (uri.size() > ROOT.size() && uri[ROOT.size()] != '/'))
      return false;

    *local = m_root;
    local->append(uri.substr(ROOT.size()));
    return true;
  }

  // What a listing reads about each entry, which a real provider gets with the listing, not as calls of its own.
  bool StatLocal(std::string_view uri, FILESYSTEM_STAT_DATA* sd) const
  {
    std::string local;
    struct stat st;
    if (!ToLocal(uri, &local) || stat(local.c_str(), &st) != 0)
      return false;

    sd->CreationTime = st.st_ctime;
    sd->ModificationTime = st.st_mtime;
    sd->Size = S_ISREG(st.st_mode) ? st.st_size : 0;
    sd->Attributes = S_ISDIR(st.st_mode) ? FILESYSTEM_FILE_ATTRIBUTE_DIRECTORY : 0;
    return true;
  }

  std::string m_root;
  bool m_serve_pipes = false;
  bool m_refuse = false;
  u32 m_list_calls = 0;
  u32 m_stat_calls = 0;
  u32 m_open_calls = 0;
};

#endif
