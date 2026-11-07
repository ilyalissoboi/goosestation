// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

// Helpers for the shader pack tests: a temporary folder, and zips written with minizip's writer (no committed
// binaries).

#include "common/file_system.h"
#include "common/path.h"
#include "common/progress_callback.h"

#include "zip.h"

#include <gtest/gtest.h>

#include <atomic>
#include <cstdlib>
#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace ShaderPackTest {

// A fresh directory under the system's temporary folder, deleted with its contents when the test ends.
class TempDirectory
{
public:
  TempDirectory()
  {
    static std::atomic<int> s_counter{0};
    std::error_code ec;
    const std::filesystem::path base = std::filesystem::temp_directory_path(ec);
    for (int attempt = 0; attempt < 100; attempt++)
    {
      std::filesystem::path p = base / ("common-tests-packs-" + std::to_string(std::rand()) + "-" +
                                        std::to_string(s_counter.fetch_add(1)));
      if (std::filesystem::create_directory(p, ec))
      {
        m_path = p.generic_string();
        break;
      }
    }
  }

  ~TempDirectory()
  {
    if (!m_path.empty())
      FileSystem::RecursiveDeleteDirectory(m_path.c_str());
  }

  const std::string& GetPath() const { return m_path; }
  std::string Get(std::string_view relative) const { return Path::Combine(m_path, relative); }

  // Writes a file at a relative path ('/' separators), making its folders.
  void Write(std::string_view relative, std::string_view contents) const
  {
    const std::string path = Get(relative);
    ASSERT_TRUE(FileSystem::EnsureDirectoryExists(std::string(Path::GetDirectory(path)).c_str(), true));
    ASSERT_TRUE(FileSystem::WriteStringToFile(path.c_str(), contents));
  }

  bool Exists(std::string_view relative) const { return FileSystem::FileExists(Get(relative).c_str()); }
  std::string Read(std::string_view relative) const
  {
    return FileSystem::ReadFileToString(Get(relative).c_str()).value_or("<missing>");
  }

private:
  std::string m_path;
};

struct ZipEntry
{
  std::string name; ///< a name ending in '/' is a directory entry
  std::string contents;
  bool symlink = false; ///< stored with a Unix symbolic link's mode; contents is the link's target
};

// Writes a zip of the entries, in order, at path; false if minizip fails.
inline bool MakeZip(const std::string& path, const std::vector<ZipEntry>& entries)
{
  zipFile zf = zipOpen64(path.c_str(), APPEND_STATUS_CREATE);
  if (!zf)
    return false;

  bool ok = true;
  for (const ZipEntry& entry : entries)
  {
    zip_fileinfo info = {};
    // Made by Unix (version made by is set by minizip), mode in the high 16 bits of the external attributes.
    const unsigned long mode = entry.symlink ? 0120777u : (entry.name.ends_with('/') ? 040755u : 0100644u);
    info.external_fa = mode << 16;
    ok = ok && zipOpenNewFileInZip64(zf, entry.name.c_str(), &info, nullptr, 0, nullptr, 0, nullptr, Z_DEFLATED,
                                     Z_DEFAULT_COMPRESSION, 0) == ZIP_OK;
    if (!entry.contents.empty())
    {
      ok = ok && zipWriteInFileInZip(zf, entry.contents.data(), static_cast<unsigned>(entry.contents.size())) ==
                   ZIP_OK;
    }
    ok = ok && zipCloseFileInZip(zf) == ZIP_OK;
  }
  return (zipClose(zf, nullptr) == ZIP_OK) && ok;
}

// A progress callback that records its status lines and turns cancelled once its value reaches cancel_at.
class RecordingProgress : public ProgressCallback
{
public:
  std::optional<u32> cancel_at;
  std::vector<std::string> statuses;

  bool IsCancelled() const override
  {
    return m_cancelled || (cancel_at.has_value() && m_progress_value >= cancel_at.value());
  }
  void SetStatusText(std::string_view text) override
  {
    statuses.emplace_back(text);
    ProgressCallback::SetStatusText(text);
  }
  void Cancel() { m_cancelled = true; }
  u32 GetValue() const { return m_progress_value; }
  u32 GetRange() const { return m_progress_range; }
};

} // namespace ShaderPackTest
