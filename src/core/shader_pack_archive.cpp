// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "shader_pack_archive.h"

#include "util/host.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/minizip_helpers.h"
#include "common/path.h"
#include "common/progress_callback.h"

#include "fmt/format.h"

#include <functional>
#include <memory>

Log_SetChannel(ShaderPacks);

namespace ShaderPackArchive {
namespace {

struct UnzCloser
{
  void operator()(unzFile zf) const { unzClose(zf); }
};
using ManagedUnzFile = std::unique_ptr<std::remove_pointer_t<unzFile>, UnzCloser>;

struct EntryInfo
{
  std::string name;
  u64 size;
  bool symlink;
};

ManagedUnzFile Open(const std::string& zip_path, Error* error)
{
  ManagedUnzFile zf(MinizipHelpers::OpenUnzFile(zip_path.c_str()));
  if (!zf || unzGoToFirstFile(zf.get()) != UNZ_OK)
  {
    Error::SetStringView(error, TRANSLATE_SV("ShaderPacks", "The download isn't a zip archive."));
    return {};
  }
  return zf;
}

// Calls fn for each entry in the archive's order, stopping when it returns false. False when fn stopped or the
// archive's directory can't be read.
bool ForEachEntry(unzFile zf, Error* error, const std::function<bool(u32 index, const EntryInfo& entry)>& fn)
{
  for (u32 index = 0;; index++)
  {
    unz_file_info64 info;
    if (unzGetCurrentFileInfo64(zf, &info, nullptr, 0, nullptr, 0, nullptr, 0) != UNZ_OK)
    {
      Error::SetStringFmt(error, TRANSLATE_FS("ShaderPacks", "Failed to read archive entry {}."), index);
      return false;
    }

    EntryInfo entry;
    entry.name.resize(info.size_filename);
    if (info.size_filename > 0 &&
        unzGetCurrentFileInfo64(zf, &info, entry.name.data(), static_cast<uLong>(entry.name.size()), nullptr, 0,
                                nullptr, 0) != UNZ_OK)
    {
      Error::SetStringFmt(error, TRANSLATE_FS("ShaderPacks", "Failed to read archive entry {}."), index);
      return false;
    }
    entry.size = info.uncompressed_size;
    // A Unix mode sits in the external attributes' high half; S_IFLNK is 0120000.
    entry.symlink = ((info.external_fa >> 16) & 0170000u) == 0120000u;

    if (!fn(index, entry))
      return false;

    const int res = unzGoToNextFile(zf);
    if (res == UNZ_END_OF_LIST_OF_FILE)
      return true;
    if (res != UNZ_OK)
    {
      Error::SetStringFmt(error, TRANSLATE_FS("ShaderPacks", "Failed to read archive entry {}."), index + 1);
      return false;
    }
  }
}

bool StartsWithSkip(std::string_view relative, std::span<const char* const> skip)
{
  for (const char* prefix : skip)
  {
    if (relative.starts_with(prefix))
      return true;
  }
  return false;
}

} // namespace

EntryDisposition TransformEntryName(std::string_view entry, u32 strip, std::span<const char* const> skip,
                                    std::string* relative, Error* error)
{
  if (entry.empty())
    return EntryDisposition::Skip;

  // Anything that could escape the destination is refused before the components are looked at.
  if (entry.front() == '/' || entry.find('\\') != std::string_view::npos || (entry.size() >= 2 && entry[1] == ':'))
  {
    Error::SetStringFmt(error, TRANSLATE_FS("ShaderPacks", "Refusing unsafe archive entry '{}'."), entry);
    return EntryDisposition::Reject;
  }

  const bool is_directory = (entry.back() == '/');
  std::vector<std::string_view> parts;
  size_t start = 0;
  while (start <= entry.size())
  {
    const size_t end = entry.find('/', start);
    const std::string_view part =
      entry.substr(start, (end == std::string_view::npos) ? std::string_view::npos : (end - start));
    if (part == "..")
    {
      Error::SetStringFmt(error, TRANSLATE_FS("ShaderPacks", "Refusing unsafe archive entry '{}'."), entry);
      return EntryDisposition::Reject;
    }
    if (!part.empty() && part != ".")
      parts.push_back(part);
    if (end == std::string_view::npos)
      break;
    start = end + 1;
  }

  if (is_directory || parts.size() <= strip)
    return EntryDisposition::Skip;
  for (const std::string_view part : parts)
  {
    if (part == "__MACOSX")
      return EntryDisposition::Skip;
  }
  if (parts.back() == ".DS_Store")
    return EntryDisposition::Skip;

  relative->clear();
  for (size_t i = strip; i < parts.size(); i++)
  {
    if (i > strip)
      relative->push_back('/');
    relative->append(parts[i]);
  }
  return StartsWithSkip(*relative, skip) ? EntryDisposition::Skip : EntryDisposition::Extract;
}

bool ListFiles(const std::string& zip_path, u32 strip, std::span<const char* const> skip,
               std::vector<std::string>* out, Error* error)
{
  out->clear();
  const ManagedUnzFile zf = Open(zip_path, error);
  if (!zf)
    return false;

  std::string relative;
  return ForEachEntry(zf.get(), error, [&](u32, const EntryInfo& entry) {
    switch (TransformEntryName(entry.name, strip, skip, &relative, error))
    {
      case EntryDisposition::Reject:
        return false;
      case EntryDisposition::Extract:
        if (!entry.symlink)
          out->push_back(relative);
        return true;
      case EntryDisposition::Skip:
      default:
        return true;
    }
  });
}

bool Extract(const std::string& zip_path, const std::string& dest, u32 strip, std::span<const char* const> skip,
             ProgressCallback* progress, std::vector<std::string>* written, Error* error, u64 max_entry,
             u64 max_total)
{
  const ManagedUnzFile zf = Open(zip_path, error);
  if (!zf)
    return false;

  unz_global_info64 global;
  if (unzGetGlobalInfo64(zf.get(), &global) != UNZ_OK)
  {
    Error::SetStringView(error, TRANSLATE_SV("ShaderPacks", "The download isn't a zip archive."));
    return false;
  }
  if (progress)
  {
    progress->SetProgressRange(static_cast<u32>(global.number_entry));
    progress->SetProgressValue(0);
  }

  if (!FileSystem::EnsureDirectoryExists(dest.c_str(), true, error))
    return false;

  // Every output path is checked against the destination once more, in the form Canonicalize gives.
  std::string dest_prefix = Path::Canonicalize(dest);
  if (!dest_prefix.empty() && dest_prefix.back() != '/' && dest_prefix.back() != '\\')
    dest_prefix.push_back(FS_OSPATH_SEPARATOR_CHARACTER);

  std::string relative;
  std::vector<u8> buffer;
  u64 total = 0;
  return ForEachEntry(zf.get(), error, [&](u32 index, const EntryInfo& entry) {
    if (progress && progress->IsCancelled())
    {
      Error::SetStringView(error, TRANSLATE_SV("ShaderPacks", "Cancelled."));
      return false;
    }

    const EntryDisposition disposition = TransformEntryName(entry.name, strip, skip, &relative, error);
    if (disposition == EntryDisposition::Reject)
      return false;
    if (disposition == EntryDisposition::Skip || entry.symlink)
    {
      if (progress)
        progress->SetProgressValue(index + 1);
      return true;
    }

    // The size is the archive's word, so it's capped before anything is allocated for it.
    if (entry.size > max_entry || entry.size > max_total - total)
    {
      Error::SetStringFmt(error, TRANSLATE_FS("ShaderPacks", "Archive entry '{}' is too large ({} bytes)."),
                          entry.name, entry.size);
      return false;
    }
    total += entry.size;

    const std::string out_path = Path::Combine(dest, relative);
    if (!Path::Canonicalize(out_path).starts_with(dest_prefix))
    {
      Error::SetStringFmt(error, TRANSLATE_FS("ShaderPacks", "Refusing unsafe archive entry '{}'."), entry.name);
      return false;
    }

    if (unzOpenCurrentFile(zf.get()) != UNZ_OK)
    {
      Error::SetStringFmt(error, TRANSLATE_FS("ShaderPacks", "Failed to read archive entry '{}'."), entry.name);
      return false;
    }
    buffer.resize(static_cast<size_t>(entry.size));
    u64 got = 0;
    while (got < entry.size)
    {
      const int read = unzReadCurrentFile(zf.get(), buffer.data() + got,
                                          static_cast<unsigned>(std::min<u64>(entry.size - got, 1024 * 1024)));
      if (read <= 0)
        break;
      got += static_cast<u64>(read);
    }
    // An entry holding more than its header says is refused, as one over the cap would be.
    u8 extra;
    const bool longer = (got == entry.size) && unzReadCurrentFile(zf.get(), &extra, 1) > 0;
    const bool crc_ok = (unzCloseCurrentFile(zf.get()) == UNZ_OK);
    if (longer)
    {
      Error::SetStringFmt(error, TRANSLATE_FS("ShaderPacks", "Archive entry '{}' is too large ({} bytes)."),
                          entry.name, entry.size);
      return false;
    }
    if (got != entry.size || !crc_ok)
    {
      Error::SetStringFmt(error, TRANSLATE_FS("ShaderPacks", "Failed to read archive entry '{}'."), entry.name);
      return false;
    }

    const std::string parent(Path::GetDirectory(out_path));
    if (!parent.empty() && !FileSystem::EnsureDirectoryExists(parent.c_str(), true, error))
      return false;
    if (!FileSystem::WriteBinaryFile(out_path.c_str(), buffer.data(), buffer.size(), error))
      return false;

    written->push_back(relative);
    if (progress)
      progress->SetProgressValue(index + 1);
    return true;
  });
}

} // namespace ShaderPackArchive
