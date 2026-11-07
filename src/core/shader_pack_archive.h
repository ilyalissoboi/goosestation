// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <span>
#include <string>
#include <string_view>
#include <vector>

class Error;
class ProgressCallback;

/// Extracting a downloaded shader pack's zip, adapting the PCSX2 fork's ShaderPackArchive: nothing is written outside
/// the destination, and the archive's own sizes are capped before anything is read.
namespace ShaderPackArchive {

static constexpr u64 MAX_ENTRY_SIZE = 64ull * 1024 * 1024;
static constexpr u64 MAX_TOTAL_SIZE = 1024ull * 1024 * 1024;

enum class EntryDisposition
{
  Extract, ///< write the file at *relative
  Skip, ///< a folder, junk (__MACOSX, .DS_Store), nothing left after the strip, or under a skipped path
  Reject, ///< an unsafe name: the whole extraction stops
};

/// Applies the strip and the skip and reject rules to an entry's name. relative gets '/'-separated components.
EntryDisposition TransformEntryName(std::string_view entry, u32 strip, std::span<const char* const> skip,
                                    std::string* relative, Error* error);

/// Whether the file opens as a zip.
bool IsZip(const std::string& zip_path);

/// The files extraction would write, in order, from the archive's central directory only. Fails when the file isn't
/// a zip or has a rejected entry.
bool ListFiles(const std::string& zip_path, u32 strip, std::span<const char* const> skip,
               std::vector<std::string>* out, Error* error);

/// Extracts the archive into dest. Stops at a rejected or oversized entry, a failed write, or a cancel; written always
/// lists the files that landed, in order, relative to dest. Symbolic links are skipped. progress may be null; its
/// range is the entry count.
bool Extract(const std::string& zip_path, const std::string& dest, u32 strip, std::span<const char* const> skip,
             ProgressCallback* progress, std::vector<std::string>* written, Error* error,
             u64 max_entry = MAX_ENTRY_SIZE, u64 max_total = MAX_TOTAL_SIZE);

} // namespace ShaderPackArchive
