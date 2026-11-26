// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <optional>
#include <span>
#include <string>
#include <string_view>

enum class RenderAPI : u32;

/// How many threads build shaders and pipelines: the Automatic rule, the drivers known to misbehave with more than
/// one, and a guard that notices a build with several threads that never finished.
namespace ShaderCompileThreads {

/// The setting's range: 0 is Automatic, 1 builds one at a time as before.
static constexpr u8 AUTOMATIC = 0;
static constexpr u8 MAX_THREADS = 16;

/// Automatic gives at most this many threads, and at most one per BYTES_PER_THREAD of memory.
static constexpr u32 MAX_AUTOMATIC = 8;
static constexpr u64 BYTES_PER_THREAD = 1024ull * 1024ull * 1024ull;

u32 GetAutomatic(u32 cores, u64 memory_bytes);

/// The machine's physical memory in bytes, or 0 if unknown.
u64 GetPhysicalMemory();

/// A renderer and driver that misbehave with more than one thread: every non-empty string must appear in the driver's
/// description.
struct KnownBadDriver
{
  RenderAPI api;
  const char* driver_contains;
  const char* version_contains;
  const char* reason;
};

/// The drivers this build knows to misbehave.
extern const std::span<const KnownBadDriver> KNOWN_BAD;

/// Why the driver gets one thread, if it is on the list.
std::optional<std::string> FindKnownBad(RenderAPI api, std::string_view driver_info,
                                        std::span<const KnownBadDriver> list = KNOWN_BAD);

/// The settings rows' note: why Automatic uses one thread here, from the fixed list (as the last device found) or the
/// guard's records in cache_dir. Empty when nothing holds Automatic back.
std::optional<std::string> GetNote(std::string_view cache_dir);

/// Set by the device when the fixed list names its driver, cleared otherwise.
void SetKnownBadNote(std::optional<std::string> note);

/// A marker written before a build with several threads and removed after it. A marker still there at the next start
/// (a crash, a hang the app was killed for, or a low-memory kill) blocks several threads under Automatic for that
/// driver, until the driver changes or the player picks a number.
class Guard
{
public:
  Guard() = default;
  Guard(std::string cache_dir, std::string_view api_name, std::string_view driver_key, std::string driver_label);

  std::optional<std::string> GetBlockReason() const;

  void BeginPass();
  void EndPass();
  void ClearBlock();

  /// The settings rows' note: why Automatic uses one thread, if a driver is blocked.
  static std::optional<std::string> ReadNote(std::string_view cache_dir);

private:
  std::string m_cache_dir;
  std::string m_api;
  std::string m_key;
  std::string m_label;
  bool m_blocked = false;
};

} // namespace ShaderCompileThreads
