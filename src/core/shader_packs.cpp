// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "shader_packs.h"

#include "util/host.h"
#include "util/http_downloader.h"

#include "common/error.h"
#include "common/json_reader.h"

#include "fmt/format.h"

#include <array>

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
    default:
      break;
  }

  // Unauthenticated calls to GitHub's API are limited per address, and it answers 403 or 429 past the limit.
  if (checking && (status == 403 || status == 429))
    return TRANSLATE_STR("ShaderPacks", "GitHub's rate limit was reached: try again later.");

  return checking ? fmt::format(TRANSLATE_FS("ShaderPacks", "Checking {} failed (HTTP {})."), pack.name, status) :
                    fmt::format(TRANSLATE_FS("ShaderPacks", "Downloading {} failed (HTTP {})."), pack.name, status);
}

} // namespace ShaderPacks
