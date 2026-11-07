// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

class Error;
class ProgressCallback;

/// Downloads, updates and removes the four RetroArch slang shader packs, adapting the PCSX2 fork's ShaderPacks.
/// Every function taking a root works under that folder (EmuFolders::GetShaderPacksFolder() in the app, a temporary
/// one in tests).
namespace ShaderPacks {

enum class VersionSource
{
  BranchHead, ///< the branch's head commit; the archive is github.com/<repo>/archive/<sha>.zip
  LatestRelease, ///< the latest release's tag; the archive is its first matching .zip
};

struct PackInfo
{
  const char* id; ///< file-name safe, the marker's name
  const char* name;
  const char* description;
  const char* licence;
  const char* repo; ///< "owner/name" on GitHub
  VersionSource source;
  const char* branch; ///< BranchHead only
  const char* asset_prefix; ///< LatestRelease: the asset's name starts with this (null: any)
  const char* asset_exclude; ///< LatestRelease: an asset whose name contains this is passed over (null: none)
  u32 strip; ///< leading path components dropped from every archive entry
  const char* install_dir; ///< under the root, '/' separators
  std::span<const char* const> skip; ///< entries whose path (after the strip) starts with one of these aren't installed
  const char* needs; ///< the id of a pack installed first, or null
};

std::span<const PackInfo> GetPacks();
const PackInfo* FindPack(std::string_view id);

struct ResolvedVersion
{
  std::string version;
  std::string download_url;
  u64 size = 0; ///< the archive's size when GitHub says (a release's asset), else 0
};

/// The GitHub API address that answers with the pack's current version.
std::string GetVersionUrl(const PackInfo& pack);

/// Reads a GitHub branches/<branch> answer: the version is the head commit's first 12 characters.
bool ParseBranchJson(std::string_view json, const PackInfo& pack, ResolvedVersion* out, Error* error);

/// Reads a GitHub releases/latest answer: the tag, and the first .zip asset the pack's filters let through.
bool ParseReleaseJson(std::string_view json, const PackInfo& pack, ResolvedVersion* out, Error* error);

/// What to tell the player when a request for the pack ended with status (an HTTP status, or HTTPDownloader's
/// negative codes, 0 for a transport failure, or STATUS_TOO_LARGE): checking is the version check, otherwise the
/// download.
std::string DescribeFailure(bool checking, const PackInfo& pack, s32 status);

/// What a pack's marker (<root>/.shaderpacks/<id>.json) records: exactly the files its install wrote.
struct InstalledPack
{
  std::string id;
  std::string version; ///< empty while an install is under way or after one that stopped part-way
  std::string source_url;
  std::string installed_at; ///< UTC, YYYY-MM-DDTHH:MM:SSZ
  u64 archive_size = 0;
  std::vector<std::string> files; ///< relative to the root, '/' separators
};

std::string GetMarkerPath(std::string_view root, std::string_view id);

/// A marker's contents; nullopt without an id, a version or a file list. Unsafe paths (empty, absolute, '\\', a
/// drive letter, "." or ".." components) are dropped from the list.
std::optional<InstalledPack> ParseMarker(std::string_view json);
std::string FormatMarker(const InstalledPack& pack);

std::optional<InstalledPack> ReadMarker(std::string_view root, std::string_view id);
bool WriteMarker(std::string_view root, const InstalledPack& pack, Error* error);

/// The order to install the packs in: a missing dependency (one with no marker) first; duplicates and unknown ids
/// dropped.
std::vector<std::string> ExpandDependencies(std::string_view root, std::span<const std::string> ids);

/// Deletes the pack's files, except those another marker under root also lists, then the folders left empty under
/// its install folder (deepest first, the install folder itself last).
void RemoveFiles(std::string_view root, const InstalledPack& pack);

/// RemoveFiles, then the marker. Fails when the pack isn't installed.
bool Uninstall(std::string_view root, std::string_view id, Error* error);

enum class PackState
{
  NotInstalled,
  Installed,
  UpdateAvailable,
  Incomplete,
};

struct PackStatus
{
  PackState state = PackState::NotInstalled;
  std::string installed; ///< the installed version, if any
  std::string latest; ///< the latest version, when the check worked
  bool checked = false; ///< whether the latest version is known
};

/// The pack's state from its marker and the version check (nullopt when it hasn't worked).
PackStatus GetPackStatus(const std::optional<InstalledPack>& installed, const std::optional<ResolvedVersion>& latest);

/// The words every UI shows for the status.
std::string DescribePackStatus(const PackStatus& status);

/// A size as the UIs show it: "48 KB" below a megabyte (rounded up), "52.5 MB" from one; empty for 0 (unknown).
std::string FormatSize(u64 bytes);

/// The largest archive a download may bring.
static constexpr u64 MAX_DOWNLOAD_SIZE = 1024ull * 1024 * 1024;

/// A Transport's status for a download that went past its cap.
static constexpr s32 STATUS_TOO_LARGE = -100;

/// How the packs reach the network: desktop's goes through HTTPDownloader, Android's streams through its own bridge,
/// and the tests serve canned answers. Each returns the HTTP status, or HTTPDownloader's negative codes (0 for a
/// transport failure).
class Transport
{
public:
  virtual ~Transport();

  /// A small answer (GitHub's API), into memory.
  virtual s32 Get(const std::string& url, std::string* body, ProgressCallback* progress) = 0;

  /// The archive, into the file at path, its size in bytes reported through progress. Past max_size it stops and
  /// returns STATUS_TOO_LARGE. The file may be left for the caller to delete; error may say what failed.
  virtual s32 Download(const std::string& url, const std::string& path, u64 max_size, ProgressCallback* progress,
                       Error* error) = 0;
};

/// The platform's transport: on desktop CreateHTTPTransport()'s; Android's streams its downloads to the file.
std::unique_ptr<Transport> CreateTransport();

/// A transport through HTTPDownloader, holding each body in memory; null when no downloader can be made.
std::unique_ptr<Transport> CreateHTTPTransport();

/// Checks the pack's latest version.
std::optional<ResolvedVersion> ResolveLatest(const PackInfo& pack, Transport& transport, ProgressCallback* progress,
                                             Error* error);

struct InstallResult
{
  std::string id;
  bool success = false;
  bool cancelled = false;
  std::string message; ///< what went wrong, empty on success
};

/// Installs or updates the packs, dependencies first, under root. For each: check, download to
/// <root>/.shaderpacks/<id>.zip.part, check the archive, remove the previous version, record the files it will
/// write (an incomplete marker), extract, record what was written. A cancel stops the rest.
std::vector<InstallResult> Install(std::string_view root, std::span<const std::string> ids, Transport& transport,
                                   ProgressCallback* progress);

} // namespace ShaderPacks
