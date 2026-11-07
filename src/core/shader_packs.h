// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <span>
#include <string>
#include <string_view>

class Error;

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
/// negative codes, or 0 for a transport failure): checking is the version check, otherwise the download.
std::string DescribeFailure(bool checking, const PackInfo& pack, s32 status);

} // namespace ShaderPacks
