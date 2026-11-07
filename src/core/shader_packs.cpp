// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "shader_packs.h"
#include "shader_pack_archive.h"

#include "util/host.h"
#include "util/http_downloader.h"

#include "core/host.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/json_reader.h"
#include "common/log.h"
#include "common/path.h"
#include "common/progress_callback.h"

#include "fmt/format.h"

#include <algorithm>
#include <array>
#include <ctime>

Log_SetChannel(ShaderPacks);

namespace ShaderPacks {
namespace {

constexpr std::array<const char*, 1> LOLA_RETRO_SKIP = {"hdr/"};

constexpr PackInfo s_packs[] = {
  {"shaders_slang", "libretro slang shaders",
   "RetroArch's slang shader collection: CRT, NTSC, scalers, handheld screens and more.",
   "Each shader's own (see its file)", "libretro/slang-shaders", VersionSource::BranchHead, "master", nullptr, nullptr,
   1, "shaders_slang", {}, nullptr},
  {"retro-crisis-gdv-ntsc", "Retro Crisis GDV-NTSC",
   "CRT presets built on Guest Advanced NTSC, by display resolution. Needs the libretro slang shaders.", "GPL-3.0",
   "RetroCrisis/Retro-Crisis-GDV-NTSC", VersionSource::LatestRelease, nullptr, nullptr, nullptr, 1,
   "shaders_slang/retro crisis", {}, "shaders_slang"},
  {"satpixie-crt", "satpixie CRT", "A lightweight CRT shader with ghosting and chroma options.", "MIT or public domain",
   "Conkwer/satpixie-crt-shader", VersionSource::LatestRelease, nullptr, nullptr, "variants", 4, "shaders_slang", {},
   nullptr},
  {"lola-retro", "lola-retro", "An analog CRT simulation: curved, flat, lite and per-system presets.", "MIT",
   "zoran123456/lola-retro", VersionSource::LatestRelease, nullptr, "lola-retro-v", nullptr, 1, "lola-retro",
   LOLA_RETRO_SKIP, nullptr},
};

constexpr const char* MARKER_DIR = ".shaderpacks";

// A relative path a marker may list: no empty, "." or ".." component, not absolute, no '\\' or drive letter.
bool IsSafeRelativePath(std::string_view path)
{
  if (path.empty() || path.front() == '/' || path.find('\\') != std::string_view::npos ||
      (path.size() >= 2 && path[1] == ':'))
  {
    return false;
  }

  size_t start = 0;
  while (start <= path.size())
  {
    const size_t end = path.find('/', start);
    const std::string_view part =
      path.substr(start, (end == std::string_view::npos) ? std::string_view::npos : (end - start));
    if (part.empty() || part == "." || part == "..")
      return false;
    if (end == std::string_view::npos)
      break;
    start = end + 1;
  }
  return true;
}

// Every file the markers under root list, but the one for except_id.
std::vector<std::string> FilesOfOtherPacks(std::string_view root, std::string_view except_id)
{
  std::vector<std::string> files;
  for (const PackInfo& pack : s_packs)
  {
    if (except_id == pack.id)
      continue;
    if (std::optional<InstalledPack> other = ReadMarker(root, pack.id); other.has_value())
      files.insert(files.end(), other->files.begin(), other->files.end());
  }
  std::sort(files.begin(), files.end());
  return files;
}

bool IsEmptyDirectory(const std::string& path)
{
  FileSystem::FindResultsArray results;
  FileSystem::FindFiles(path.c_str(), "*", FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_FOLDERS | FILESYSTEM_FIND_HIDDEN_FILES,
                        &results);
  return results.empty();
}

// Removes the empty folders below and including dir, deepest first.
void PruneEmptyDirectories(const std::string& dir)
{
  if (!FileSystem::DirectoryExists(dir.c_str()))
    return;

  FileSystem::FindResultsArray dirs;
  FileSystem::FindFiles(dir.c_str(), "*",
                        FILESYSTEM_FIND_RECURSIVE | FILESYSTEM_FIND_FOLDERS | FILESYSTEM_FIND_HIDDEN_FILES, &dirs);
  std::sort(dirs.begin(), dirs.end(), [](const FILESYSTEM_FIND_DATA& lhs, const FILESYSTEM_FIND_DATA& rhs) {
    return lhs.FileName.size() > rhs.FileName.size();
  });
  for (const FILESYSTEM_FIND_DATA& fd : dirs)
  {
    if ((fd.Attributes & FILESYSTEM_FILE_ATTRIBUTE_DIRECTORY) && IsEmptyDirectory(fd.FileName))
      FileSystem::DeleteDirectory(fd.FileName.c_str());
  }
  if (IsEmptyDirectory(dir))
    FileSystem::DeleteDirectory(dir.c_str());
}

bool IsHex(std::string_view text)
{
  for (const char c : text)
  {
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))
      return false;
  }
  return !text.empty();
}

} // namespace

std::span<const PackInfo> GetPacks()
{
  return s_packs;
}

const PackInfo* FindPack(std::string_view id)
{
  for (const PackInfo& pack : s_packs)
  {
    if (id == pack.id)
      return &pack;
  }
  return nullptr;
}

std::string GetVersionUrl(const PackInfo& pack)
{
  if (pack.source == VersionSource::BranchHead)
    return fmt::format("https://api.github.com/repos/{}/branches/{}", pack.repo, pack.branch);
  return fmt::format("https://api.github.com/repos/{}/releases/latest", pack.repo);
}

bool ParseBranchJson(std::string_view json, const PackInfo& pack, ResolvedVersion* out, Error* error)
{
  const std::optional<JsonReader::Value> doc = JsonReader::Parse(json);
  const JsonReader::Value* commit = doc.has_value() ? doc->Find("commit") : nullptr;
  const std::optional<std::string> sha = commit ? commit->StringAt("sha") : std::nullopt;
  // The sha goes into a URL and the marker, so it must be what a sha is.
  if (!sha.has_value() || sha->size() < 12 || !IsHex(sha.value()))
  {
    Error::SetStringView(error, TRANSLATE_SV("ShaderPacks", "GitHub's answer has no commit."));
    return false;
  }

  out->version = sha->substr(0, 12);
  out->download_url = fmt::format("https://github.com/{}/archive/{}.zip", pack.repo, sha.value());
  out->size = 0;
  return true;
}

bool ParseReleaseJson(std::string_view json, const PackInfo& pack, ResolvedVersion* out, Error* error)
{
  const std::optional<JsonReader::Value> doc = JsonReader::Parse(json);
  const std::optional<std::string> tag = doc.has_value() ? doc->StringAt("tag_name") : std::nullopt;
  if (!tag.has_value() || tag->empty())
  {
    Error::SetStringView(error, TRANSLATE_SV("ShaderPacks", "GitHub's answer has no release."));
    return false;
  }

  if (const JsonReader::Value* assets = doc->Find("assets"); assets && assets->type == JsonReader::Value::Type::Array)
  {
    for (const JsonReader::Value& asset : assets->items)
    {
      const std::optional<std::string> name = asset.StringAt("name");
      const std::optional<std::string> url = asset.StringAt("browser_download_url");
      if (!name.has_value() || !url.has_value() || !name->ends_with(".zip"))
        continue;
      if (pack.asset_prefix && !name->starts_with(pack.asset_prefix))
        continue;
      if (pack.asset_exclude && name->find(pack.asset_exclude) != std::string::npos)
        continue;

      out->version = tag.value();
      out->download_url = url.value();
      const std::optional<s64> size = asset.NumberAt("size");
      out->size = (size.has_value() && size.value() > 0) ? static_cast<u64>(size.value()) : 0;
      return true;
    }
  }

  Error::SetStringFmt(error, TRANSLATE_FS("ShaderPacks", "Release {} has no matching .zip file."), tag.value());
  return false;
}

std::string DescribeFailure(bool checking, const PackInfo& pack, s32 status)
{
  switch (status)
  {
    case 0:
    case HTTPDownloader::HTTP_STATUS_ERROR:
      return TRANSLATE_STR("ShaderPacks", "Couldn't reach GitHub: check the connection.");
    case HTTPDownloader::HTTP_STATUS_TIMEOUT:
      return TRANSLATE_STR("ShaderPacks", "GitHub took too long to answer: try again later.");
    case HTTPDownloader::HTTP_STATUS_CANCELLED:
      return TRANSLATE_STR("ShaderPacks", "Cancelled.");
    case STATUS_TOO_LARGE:
      return fmt::format(TRANSLATE_FS("ShaderPacks", "The download for {} is larger than 1 GB."), pack.name);
    default:
      break;
  }

  // Unauthenticated calls to GitHub's API are limited per address, and it answers 403 or 429 past the limit.
  if (checking && (status == 403 || status == 429))
    return TRANSLATE_STR("ShaderPacks", "GitHub's rate limit was reached: try again later.");

  return checking ? fmt::format(TRANSLATE_FS("ShaderPacks", "Checking {} failed (HTTP {})."), pack.name, status) :
                    fmt::format(TRANSLATE_FS("ShaderPacks", "Downloading {} failed (HTTP {})."), pack.name, status);
}

std::string GetMarkerPath(std::string_view root, std::string_view id)
{
  return Path::Combine(Path::Combine(root, MARKER_DIR), fmt::format("{}.json", id));
}

std::optional<InstalledPack> ParseMarker(std::string_view json)
{
  const std::optional<JsonReader::Value> doc = JsonReader::Parse(json);
  if (!doc.has_value())
    return std::nullopt;

  const std::optional<std::string> id = doc->StringAt("id");
  const std::optional<std::string> version = doc->StringAt("version");
  const JsonReader::Value* files = doc->Find("files");
  if (!id.has_value() || !version.has_value() || !files || files->type != JsonReader::Value::Type::Array)
    return std::nullopt;

  InstalledPack pack;
  pack.id = id.value();
  pack.version = version.value();
  pack.source_url = doc->StringAt("source_url").value_or(std::string());
  pack.installed_at = doc->StringAt("installed_at").value_or(std::string());
  const std::optional<s64> size = doc->NumberAt("archive_size");
  pack.archive_size = (size.has_value() && size.value() > 0) ? static_cast<u64>(size.value()) : 0;
  for (const JsonReader::Value& file : files->items)
  {
    if (file.type == JsonReader::Value::Type::String && IsSafeRelativePath(file.string))
      pack.files.push_back(file.string);
    else if (file.type == JsonReader::Value::Type::String)
      WARNING_LOG("Shader pack {}'s marker lists an unsafe path '{}', ignoring it.", pack.id, file.string);
  }
  return pack;
}

std::string FormatMarker(const InstalledPack& pack)
{
  std::string out = "{\"id\":";
  JsonReader::AppendString(&out, pack.id);
  out += ",\"version\":";
  JsonReader::AppendString(&out, pack.version);
  out += ",\"source_url\":";
  JsonReader::AppendString(&out, pack.source_url);
  out += ",\"installed_at\":";
  JsonReader::AppendString(&out, pack.installed_at);
  out += fmt::format(",\"archive_size\":{},\"files\":[", pack.archive_size);
  for (size_t i = 0; i < pack.files.size(); i++)
  {
    if (i > 0)
      out += ',';
    out += "\n";
    JsonReader::AppendString(&out, pack.files[i]);
  }
  out += "]}\n";
  return out;
}

std::optional<InstalledPack> ReadMarker(std::string_view root, std::string_view id)
{
  const std::string path = GetMarkerPath(root, id);
  const std::optional<std::string> text = FileSystem::ReadFileToString(path.c_str());
  if (!text.has_value())
    return std::nullopt;

  std::optional<InstalledPack> pack = ParseMarker(text.value());
  if (!pack.has_value() || pack->id != id)
  {
    WARNING_LOG("Shader pack marker {} can't be read: the pack counts as not installed.", path);
    return std::nullopt;
  }
  return pack;
}

bool WriteMarker(std::string_view root, const InstalledPack& pack, Error* error)
{
  const std::string dir = Path::Combine(root, MARKER_DIR);
  if (!FileSystem::EnsureDirectoryExists(dir.c_str(), true, error))
    return false;

  const std::string text = FormatMarker(pack);
  return FileSystem::WriteAtomicRenamedFile(GetMarkerPath(root, pack.id), text.data(), text.size(), error);
}

std::vector<std::string> ExpandDependencies(std::string_view root, std::span<const std::string> ids)
{
  std::vector<std::string> order;
  const auto add = [&order](std::string_view id) {
    if (std::find(order.begin(), order.end(), id) == order.end())
      order.emplace_back(id);
  };

  for (const std::string& id : ids)
  {
    const PackInfo* pack = FindPack(id);
    if (!pack)
    {
      WARNING_LOG("Ignoring unknown shader pack '{}'.", id);
      continue;
    }
    // A dependency that's missing, or installed part-way, is installed (again) first.
    if (pack->needs && FindPack(pack->needs))
    {
      const std::optional<InstalledPack> needed = ReadMarker(root, pack->needs);
      if (!needed.has_value() || needed->version.empty())
        add(pack->needs);
    }
    add(id);
  }
  return order;
}

void RemoveFiles(std::string_view root, const InstalledPack& pack)
{
  const std::vector<std::string> shared = FilesOfOtherPacks(root, pack.id);
  for (const std::string& file : pack.files)
  {
    if (std::binary_search(shared.begin(), shared.end(), file))
      continue;
    const std::string path = Path::Combine(root, file);
    if (FileSystem::FileExists(path.c_str()) && !FileSystem::DeleteFile(path.c_str()))
      WARNING_LOG("Couldn't delete {}.", path);
  }

  if (const PackInfo* info = FindPack(pack.id))
    PruneEmptyDirectories(Path::Combine(root, info->install_dir));
}

bool Uninstall(std::string_view root, std::string_view id, Error* error)
{
  const PackInfo* pack = FindPack(id);
  if (!pack)
  {
    Error::SetStringFmt(error, TRANSLATE_FS("ShaderPacks", "Unknown shader pack '{}'."), id);
    return false;
  }

  const std::optional<InstalledPack> installed = ReadMarker(root, id);
  if (!installed.has_value())
  {
    Error::SetStringFmt(error, TRANSLATE_FS("ShaderPacks", "{} isn't installed."), pack->name);
    return false;
  }

  RemoveFiles(root, installed.value());
  const std::string marker = GetMarkerPath(root, id);
  if (!FileSystem::DeleteFile(marker.c_str(), error))
    return false;

  INFO_LOG("Removed shader pack {} ({} files).", pack->name, installed->files.size());
  return true;
}

PackStatus GetPackStatus(const std::optional<InstalledPack>& installed, const std::optional<ResolvedVersion>& latest)
{
  PackStatus status;
  status.checked = latest.has_value();
  if (latest.has_value())
    status.latest = latest->version;
  if (!installed.has_value())
  {
    status.state = PackState::NotInstalled;
    return status;
  }

  status.installed = installed->version;
  if (installed->version.empty())
    status.state = PackState::Incomplete;
  else if (latest.has_value() && latest->version != installed->version)
    status.state = PackState::UpdateAvailable;
  else
    status.state = PackState::Installed;
  return status;
}

std::string FormatSize(u64 bytes)
{
  constexpr u64 MB = 1024 * 1024;
  if (bytes == 0)
    return {};
  if (bytes < MB)
    return fmt::format(TRANSLATE_FS("ShaderPacks", "{} KB"), (bytes + 1023) / 1024);
  return fmt::format(TRANSLATE_FS("ShaderPacks", "{:.1f} MB"), static_cast<double>(bytes) / static_cast<double>(MB));
}

std::string DescribePackStatus(const PackStatus& status)
{
  switch (status.state)
  {
    case PackState::Incomplete:
      return TRANSLATE_STR("ShaderPacks", "Incomplete: install it again");
    case PackState::UpdateAvailable:
      return fmt::format(TRANSLATE_FS("ShaderPacks", "Update available: {} → {}"), status.installed, status.latest);
    case PackState::Installed:
      return status.checked ?
               fmt::format(TRANSLATE_FS("ShaderPacks", "Installed {}"), status.installed) :
               fmt::format(TRANSLATE_FS("ShaderPacks", "Installed {} (couldn't check for updates)"), status.installed);
    case PackState::NotInstalled:
    default:
      return TRANSLATE_STR("ShaderPacks", "Not installed");
  }
}

Transport::~Transport() = default;

namespace {

bool IsCancelled(ProgressCallback* progress)
{
  return progress && progress->IsCancelled();
}

void SetStatus(ProgressCallback* progress, const std::string& text)
{
  if (progress)
    progress->SetStatusText(text);
}

std::string CurrentTimestamp()
{
  const std::time_t now = std::time(nullptr);
  std::tm tm = {};
#ifdef _WIN32
  gmtime_s(&tm, &now);
#else
  gmtime_r(&now, &tm);
#endif
  return fmt::format("{:04}-{:02}-{:02}T{:02}:{:02}:{:02}Z", tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour,
                     tm.tm_min, tm.tm_sec);
}

std::vector<std::string> UnderInstallDir(const PackInfo& pack, const std::vector<std::string>& files)
{
  std::vector<std::string> out;
  out.reserve(files.size());
  for (const std::string& file : files)
    out.push_back(fmt::format("{}/{}", pack.install_dir, file));
  return out;
}

// One pack's install, as Install() describes; result's message and cancelled say how it ended.
void InstallOne(std::string_view root, const PackInfo& pack, Transport& transport, ProgressCallback* progress,
                InstallResult* result)
{
  Error error;

  SetStatus(progress, fmt::format(TRANSLATE_FS("ShaderPacks", "Checking {}…"), pack.name));
  const std::optional<ResolvedVersion> version = ResolveLatest(pack, transport, progress, &error);
  if (!version.has_value())
  {
    result->message = error.GetDescription();
    result->cancelled = IsCancelled(progress);
    return;
  }

  const std::string marker_dir = Path::Combine(root, ".shaderpacks");
  const std::string part = Path::Combine(marker_dir, fmt::format("{}.zip.part", pack.id));
  if (!FileSystem::EnsureDirectoryExists(marker_dir.c_str(), true, &error))
  {
    result->message = error.GetDescription();
    return;
  }
  if (FileSystem::FileExists(part.c_str()))
    FileSystem::DeleteFile(part.c_str());

  // The part file never outlives this install, however it ends.
  struct PartFile
  {
    const std::string& path;
    ~PartFile()
    {
      if (FileSystem::FileExists(path.c_str()))
        FileSystem::DeleteFile(path.c_str());
    }
  } part_file{part};

  SetStatus(progress, fmt::format(TRANSLATE_FS("ShaderPacks", "Downloading {}…"), pack.name));
  const s32 status = transport.Download(version->download_url, part, MAX_DOWNLOAD_SIZE, progress, &error);
  if (status != 200 || IsCancelled(progress))
  {
    result->cancelled = (status == HTTPDownloader::HTTP_STATUS_CANCELLED) || IsCancelled(progress);
    result->message = result->cancelled ? DescribeFailure(false, pack, HTTPDownloader::HTTP_STATUS_CANCELLED) :
                      error.IsValid()   ? error.GetDescription() :
                                          DescribeFailure(false, pack, status);
    return;
  }

  const s64 archive_size = FileSystem::GetPathFileSize(part.c_str());
  if (!ShaderPackArchive::IsZip(part))
  {
    result->message = fmt::format(TRANSLATE_FS("ShaderPacks", "The download for {} isn't a zip archive."), pack.name);
    return;
  }

  // Nothing on disk changes until the archive is known to be good.
  std::vector<std::string> planned;
  if (!ShaderPackArchive::ListFiles(part, pack.strip, pack.skip, &planned, &error))
  {
    result->message = error.GetDescription();
    return;
  }

  // Recorded before anything changes, with no version: an install that never finishes (the app or the computer gone
  // mid-way) reads as incomplete, and removing it deletes what it wrote and what's left of the previous version.
  const std::optional<InstalledPack> previous = ReadMarker(root, pack.id);
  InstalledPack marker;
  marker.id = pack.id;
  marker.source_url = version->download_url;
  marker.installed_at = CurrentTimestamp();
  marker.archive_size = (archive_size > 0) ? static_cast<u64>(archive_size) : 0;
  marker.files = UnderInstallDir(pack, planned);
  if (previous.has_value())
  {
    std::vector<std::string> both = previous->files;
    both.insert(both.end(), marker.files.begin(), marker.files.end());
    std::sort(both.begin(), both.end());
    both.erase(std::unique(both.begin(), both.end()), both.end());
    marker.files = std::move(both);
  }
  if (!WriteMarker(root, marker, &error))
  {
    result->message = error.GetDescription();
    return;
  }

  if (previous.has_value())
  {
    SetStatus(progress, fmt::format(TRANSLATE_FS("ShaderPacks", "Removing the previous {}…"), pack.name));
    // A cancel up to here keeps the previous version as it was.
    if (IsCancelled(progress))
    {
      Error restore_error;
      if (!WriteMarker(root, previous.value(), &restore_error))
        WARNING_LOG("Couldn't restore shader pack {}'s marker: {}", pack.id, restore_error.GetDescription());
      result->cancelled = true;
      result->message = DescribeFailure(false, pack, HTTPDownloader::HTTP_STATUS_CANCELLED);
      return;
    }
    RemoveFiles(root, previous.value());
  }

  SetStatus(progress, fmt::format(TRANSLATE_FS("ShaderPacks", "Extracting {}…"), pack.name));
  std::vector<std::string> written;
  const bool extracted = ShaderPackArchive::Extract(part, Path::Combine(root, pack.install_dir), pack.strip, pack.skip,
                                                    progress, &written, &error);

  // Once finished, exactly what was written. An extraction that stopped keeps every file it planned (and the previous
  // version's): a write that failed part-way may have left its file, and Remove deletes only what the marker lists.
  if (extracted)
  {
    marker.version = version->version;
    marker.files = UnderInstallDir(pack, written);
  }
  Error marker_error;
  if (!WriteMarker(root, marker, &marker_error))
    WARNING_LOG("Couldn't record shader pack {}: {}", pack.id, marker_error.GetDescription());

  if (!extracted)
  {
    result->cancelled = IsCancelled(progress);
    result->message = error.GetDescription();
    return;
  }

  result->success = true;
  INFO_LOG("Installed shader pack {} {} ({} files).", pack.name, version->version, written.size());
}

// Forwards to the caller's progress, and cancels the request once it passes the cap.
class CappedProgress final : public ProgressCallback
{
public:
  CappedProgress(ProgressCallback* inner, u64 cap) : m_inner(inner), m_cap(cap) {}

  bool IsOver() const { return m_over; }
  bool IsCancelled() const override { return m_over || (m_inner && m_inner->IsCancelled()); }
  // Only the bytes received count: a backend may give an unknown length as the type's maximum.
  void SetProgressRange(u32 range) override
  {
    if (m_inner)
      m_inner->SetProgressRange(range);
  }
  void SetProgressValue(u32 value) override
  {
    m_over = m_over || (value > m_cap);
    if (m_inner)
      m_inner->SetProgressValue(value);
  }

private:
  ProgressCallback* m_inner;
  u64 m_cap;
  bool m_over = false;
};

// HTTPDownloader holds the body in memory, then it's written out.
class HTTPTransport final : public Transport
{
public:
  explicit HTTPTransport(std::unique_ptr<HTTPDownloader> http) : m_http(std::move(http)) {}

  s32 Get(const std::string& url, std::string* body, ProgressCallback* progress) override
  {
    s32 status = HTTPDownloader::HTTP_STATUS_ERROR;
    m_http->CreateRequest(
      url,
      [&status, body](s32 status_code, const std::string&, HTTPDownloader::Request::Data data) {
        status = status_code;
        body->assign(reinterpret_cast<const char*>(data.data()), data.size());
      },
      progress);
    m_http->WaitForAllRequests();
    return status;
  }

  s32 Download(const std::string& url, const std::string& path, u64 max_size, ProgressCallback* progress,
               Error* error) override
  {
    CappedProgress capped(progress, max_size);
    s32 status = HTTPDownloader::HTTP_STATUS_ERROR;
    HTTPDownloader::Request::Data body;
    m_http->CreateRequest(
      url,
      [&status, &body](s32 status_code, const std::string&, HTTPDownloader::Request::Data data) {
        status = status_code;
        body = std::move(data);
      },
      &capped);
    m_http->WaitForAllRequests();

    if (capped.IsOver() || body.size() > max_size)
      return STATUS_TOO_LARGE;
    if (status == 200 && !FileSystem::WriteBinaryFile(path.c_str(), body.data(), body.size(), error))
      return HTTPDownloader::HTTP_STATUS_ERROR;
    return status;
  }

private:
  std::unique_ptr<HTTPDownloader> m_http;
};

} // namespace

std::unique_ptr<Transport> CreateHTTPTransport()
{
  std::unique_ptr<HTTPDownloader> http = HTTPDownloader::Create(Host::GetHTTPUserAgent());
  if (!http)
    return {};
  return std::make_unique<HTTPTransport>(std::move(http));
}

#ifndef __ANDROID__
std::unique_ptr<Transport> CreateTransport()
{
  return CreateHTTPTransport();
}
#endif

std::optional<ResolvedVersion> ResolveLatest(const PackInfo& pack, Transport& transport, ProgressCallback* progress,
                                             Error* error)
{
  std::string body;
  const s32 status = transport.Get(GetVersionUrl(pack), &body, progress);
  if (status != 200 || IsCancelled(progress))
  {
    Error::SetString(error, DescribeFailure(true, pack,
                                            IsCancelled(progress) ? HTTPDownloader::HTTP_STATUS_CANCELLED : status));
    return std::nullopt;
  }

  ResolvedVersion version;
  const bool parsed = (pack.source == VersionSource::BranchHead) ? ParseBranchJson(body, pack, &version, error) :
                                                                   ParseReleaseJson(body, pack, &version, error);
  if (!parsed)
    return std::nullopt;
  return version;
}

std::vector<InstallResult> Install(std::string_view root, std::span<const std::string> ids, Transport& transport,
                                   ProgressCallback* progress)
{
  std::vector<InstallResult> results;
  std::vector<std::string> failed;
  if (progress)
    progress->SetCancellable(true);

  for (const std::string& id : ExpandDependencies(root, ids))
  {
    const PackInfo* pack = FindPack(id);
    if (!pack)
      continue;

    InstallResult& result = results.emplace_back();
    result.id = id;
    if (IsCancelled(progress))
    {
      result.cancelled = true;
      result.message = DescribeFailure(false, *pack, HTTPDownloader::HTTP_STATUS_CANCELLED);
      break;
    }

    if (pack->needs && std::find(failed.begin(), failed.end(), pack->needs) != failed.end())
    {
      const PackInfo* needs = FindPack(pack->needs);
      result.message =
        fmt::format(TRANSLATE_FS("ShaderPacks", "Needs {}, which didn't install."), needs ? needs->name : pack->needs);
      failed.push_back(id);
      continue;
    }

    InstallOne(root, *pack, transport, progress, &result);
    if (result.cancelled)
      break;
    if (!result.success)
      failed.push_back(id);
  }
  return results;
}

} // namespace ShaderPacks
