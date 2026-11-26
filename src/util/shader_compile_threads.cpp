// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "shader_compile_threads.h"
#include "gpu_device.h"

#include "common/file_system.h"
#include "common/log.h"
#include "common/path.h"
#include "common/sha1_digest.h"
#include "common/string_util.h"

#include "fmt/format.h"

#include <algorithm>
#include <array>
#include <mutex>

#if defined(_WIN32)
#include "common/windows_headers.h"
#elif defined(__APPLE__)
#include <sys/sysctl.h>
#include <sys/types.h>
#else
#include <unistd.h>
#endif

Log_SetChannel(GPUDevice);

namespace ShaderCompileThreads {

// Renderer, driver text and version text that misbehaved with more than one thread, and what was seen.
static constexpr std::array<KnownBadDriver, 0> s_known_bad = {};
const std::span<const KnownBadDriver> KNOWN_BAD = s_known_bad;

static constexpr const char* PENDING_NAME = "shader_compile_pending";
static constexpr const char* BLOCKED_NAME = "shader_compile_blocked";

static std::string HashKey(std::string_view key)
{
  std::array<u8, SHA1Digest::DIGEST_SIZE> digest = SHA1Digest::GetDigest(key.data(), key.size());
  return SHA1Digest::DigestToString(digest);
}

// One blocked record per renderer, so switching renderer after a crash keeps the block.
static std::string BlockedPath(std::string_view cache_dir, std::string_view api)
{
  return Path::Combine(cache_dir, fmt::format("{}_{}", BLOCKED_NAME, api));
}

// A record is two lines: the hashed driver key, then the driver's label.
static std::optional<std::pair<std::string, std::string>> ReadRecord(const std::string& path)
{
  const std::optional<std::string> data = FileSystem::ReadFileToString(path.c_str());
  if (!data.has_value())
    return std::nullopt;

  const std::string_view text = data.value();
  const std::string_view::size_type newline = text.find('\n');
  if (newline == std::string_view::npos)
    return std::nullopt;

  return std::make_pair(std::string(text.substr(0, newline)),
                        std::string(StringUtil::StripWhitespace(text.substr(newline + 1))));
}

static std::mutex s_known_bad_note_lock;
static std::optional<std::string> s_known_bad_note;

static constexpr const char* BLOCK_REASON = "a build with several threads didn't finish last time";

} // namespace ShaderCompileThreads

u32 ShaderCompileThreads::GetAutomatic(u32 cores, u64 memory_bytes)
{
  const u64 by_memory = memory_bytes / BYTES_PER_THREAD;
  const u32 threads = static_cast<u32>(std::min<u64>({static_cast<u64>(cores), by_memory, MAX_AUTOMATIC}));
  return std::max(threads, 1u);
}

u64 ShaderCompileThreads::GetPhysicalMemory()
{
#if defined(_WIN32)
  MEMORYSTATUSEX status = {};
  status.dwLength = sizeof(status);
  return GlobalMemoryStatusEx(&status) ? static_cast<u64>(status.ullTotalPhys) : 0;
#elif defined(__APPLE__)
  u64 memory = 0;
  size_t size = sizeof(memory);
  return (sysctlbyname("hw.memsize", &memory, &size, nullptr, 0) == 0) ? memory : 0;
#else
  const long pages = sysconf(_SC_PHYS_PAGES);
  const long page_size = sysconf(_SC_PAGE_SIZE);
  return (pages > 0 && page_size > 0) ? static_cast<u64>(pages) * static_cast<u64>(page_size) : 0;
#endif
}

std::optional<std::string> ShaderCompileThreads::FindKnownBad(RenderAPI api, std::string_view driver_info,
                                                              std::span<const KnownBadDriver> list)
{
  for (const KnownBadDriver& entry : list)
  {
    if (entry.api != api)
      continue;
    if (entry.driver_contains && *entry.driver_contains && driver_info.find(entry.driver_contains) == std::string_view::npos)
      continue;
    if (entry.version_contains && *entry.version_contains &&
        driver_info.find(entry.version_contains) == std::string_view::npos)
      continue;

    return fmt::format("this driver is known to misbehave with several threads ({})", entry.reason);
  }

  return std::nullopt;
}

ShaderCompileThreads::Guard::Guard(std::string cache_dir, std::string_view api_name, std::string_view driver_key,
                                   std::string driver_label)
  : m_cache_dir(std::move(cache_dir)), m_api(api_name), m_key(HashKey(driver_key)), m_label(std::move(driver_label))
{
  if (m_cache_dir.empty())
    return;

  const std::string pending_path = Path::Combine(m_cache_dir, PENDING_NAME);

  // A marker left behind means the last build with several threads never finished: block that renderer's driver.
  // The marker is three lines: renderer, hashed driver key, driver label.
  if (const std::optional<std::string> pending = FileSystem::ReadFileToString(pending_path.c_str()))
  {
    const std::vector<std::string_view> lines = StringUtil::SplitString(pending.value(), '\n', true);
    if (lines.size() >= 3)
    {
      WARNING_LOG("The last shader build with several threads on {} didn't finish. Automatic uses one thread on it.",
                  lines[2]);
      FileSystem::WriteStringToFile(BlockedPath(m_cache_dir, lines[0]).c_str(),
                                    fmt::format("{}\n{}\n", lines[1], lines[2]));
    }
    FileSystem::DeleteFile(pending_path.c_str());
  }

  const std::string blocked_path = BlockedPath(m_cache_dir, m_api);
  if (const std::optional<std::pair<std::string, std::string>> blocked = ReadRecord(blocked_path))
  {
    if (blocked->first == m_key)
    {
      m_blocked = true;
    }
    else
    {
      INFO_LOG("The driver changed since several threads were blocked on {}: trying them again.", blocked->second);
      FileSystem::DeleteFile(blocked_path.c_str());
    }
  }
}

std::optional<std::string> ShaderCompileThreads::Guard::GetBlockReason() const
{
  if (!m_blocked)
    return std::nullopt;

  return fmt::format("{} on {}", BLOCK_REASON, m_label);
}

void ShaderCompileThreads::Guard::BeginPass()
{
  if (m_cache_dir.empty())
    return;

  const std::string path = Path::Combine(m_cache_dir, PENDING_NAME);
  FileSystem::WriteStringToFile(path.c_str(), fmt::format("{}\n{}\n{}\n", m_api, m_key, m_label));
}

void ShaderCompileThreads::Guard::EndPass()
{
  if (m_cache_dir.empty())
    return;

  const std::string path = Path::Combine(m_cache_dir, PENDING_NAME);
  FileSystem::DeleteFile(path.c_str());
}

void ShaderCompileThreads::Guard::ClearBlock()
{
  m_blocked = false;
  if (m_cache_dir.empty())
    return;

  const std::string path = BlockedPath(m_cache_dir, m_api);
  if (FileSystem::FileExists(path.c_str()))
  {
    INFO_LOG("Several shader compile threads were chosen: the block on {} is lifted.", m_label);
    FileSystem::DeleteFile(path.c_str());
  }
}

std::optional<std::string> ShaderCompileThreads::Guard::ReadNote(std::string_view cache_dir)
{
  if (cache_dir.empty())
    return std::nullopt;

  FileSystem::FindResultsArray files;
  FileSystem::FindFiles(std::string(cache_dir).c_str(), fmt::format("{}_*", BLOCKED_NAME).c_str(),
                        FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_SORT_BY_NAME, &files);
  std::string note;
  for (const FILESYSTEM_FIND_DATA& file : files)
  {
    const std::optional<std::pair<std::string, std::string>> blocked = ReadRecord(file.FileName);
    if (!blocked.has_value())
      continue;
    if (!note.empty())
      note += ' ';
    note += fmt::format("Automatic uses 1 on {}: {}.", blocked->second, BLOCK_REASON);
  }

  return note.empty() ? std::nullopt : std::optional<std::string>(std::move(note));
}

void ShaderCompileThreads::SetKnownBadNote(std::optional<std::string> note)
{
  std::unique_lock lock(s_known_bad_note_lock);
  s_known_bad_note = std::move(note);
}

std::optional<std::string> ShaderCompileThreads::GetNote(std::string_view cache_dir)
{
  {
    std::unique_lock lock(s_known_bad_note_lock);
    if (s_known_bad_note.has_value())
      return s_known_bad_note;
  }

  return Guard::ReadNote(cache_dir);
}
