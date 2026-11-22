// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#include "texture_replacements.h"
#include "gpu.h"
#include "gpu_types.h"
#include "texture_names.h"
#include "host.h"
#include "settings.h"

#include "common/bitutils.h"
#include "common/error.h"
#include "common/file_system.h"
#include "common/hash_combine.h"
#include "common/log.h"
#include "common/path.h"
#include "common/string_util.h"
#include "common/timer.h"

#include "util/imgui_manager.h"

#include "IconsFontAwesome5.h"
#include "fmt/format.h"
#include "xxhash.h"
#if defined(CPU_ARCH_X86) || defined(CPU_ARCH_X64)
#include "xxh_x86dispatch.h"
#endif

#include <algorithm>
#include <array>
#include <tuple>
#include <unordered_map>
#include <vector>

Log_SetChannel(TextureReplacements);

namespace TextureReplacements {

static std::string GetVRAMWriteKey(u64 high, u64 low);
static std::string GetVRAMWriteDumpFilename(u32 width, u32 height, const void* pixels);
static void PreloadTextures();

namespace {
struct CachedImage
{
  ReplacementImage image;
  u64 last_used;
};
} // namespace

static std::string s_game_id;
static std::string s_pack_folder;
static TexturePackConfig s_config;
static ReplacementIndex s_index;
static std::unordered_map<std::string, CachedImage> s_image_cache;
static size_t s_image_cache_bytes = 0;
static u64 s_image_cache_counter = 0;
static u32 s_reload_count = 0;

} // namespace TextureReplacements

static bool IsInFolder(std::string_view path, std::string_view folder)
{
  return path.length() > folder.length() && path.starts_with(folder) &&
         (path[folder.length()] == '/' || path[folder.length()] == '\\');
}

/// The rect of a name fits its source: its size in words times the texels in a word.
static bool NameFitsItsSource(const TextureNames::Name& name)
{
  if (name.kind == TextureNames::Kind::VRAMWrite)
    return true;

  static constexpr std::array<u32, 3> texels_per_word = {{4, 2, 1}};
  const u32 source_texels = name.source_width * texels_per_word[static_cast<u8>(TextureNames::GetTextureMode(name.mode))];
  return (static_cast<u32>(name.offset_x) + name.width <= source_texels &&
          static_cast<u32>(name.offset_y) + name.height <= name.source_height);
}

static bool NameLess(const TextureReplacements::TextureReplacement& lhs, const TextureReplacements::TextureReplacement& rhs)
{
  return std::tie(lhs.name.kind, lhs.name.mode, lhs.name.texture_hash) <
         std::tie(rhs.name.kind, rhs.name.mode, rhs.name.texture_hash);
}

std::string TextureReplacements::GetVRAMWriteKey(u64 high, u64 low)
{
  return fmt::format("{:016X}{:016X}", high, low);
}

bool TextureReplacements::ReplacementIndex::AddTexture(const TextureNames::Name& name, std::string path)
{
  if (!NameFitsItsSource(name))
  {
    WARNING_LOG("Skipping '{}': its rect is outside its source.", Path::GetFileName(path));
    return false;
  }

  if (name.kind == TextureNames::Kind::VRAMWrite)
  {
    const auto [it, inserted] = m_vram_writes.emplace(GetVRAMWriteKey(name.texture_hash_high, name.texture_hash), path);
    if (!inserted)
      WARNING_LOG("Duplicate VRAM write replacement: '{}' and '{}'", it->second, path);
    return inserted;
  }

  m_textures.push_back(TextureReplacement{name, std::move(path)});
  return true;
}

void TextureReplacements::ReplacementIndex::Clear()
{
  m_textures.clear();
  m_vram_writes.clear();
}

void TextureReplacements::ReplacementIndex::Build(const std::string& game_dir, const TexturePackConfig& config)
{
  Clear();

  const std::string dumps_dir = Path::Combine(game_dir, "dumps");
  FileSystem::FindResultsArray files;
  FileSystem::FindFiles(game_dir.c_str(), "*", FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_RECURSIVE, &files);

  for (FILESYSTEM_FIND_DATA& fd : files)
  {
    if ((fd.Attributes & FILESYSTEM_FILE_ATTRIBUTE_DIRECTORY) || IsInFolder(fd.FileName, dumps_dir) ||
        !TextureNames::IsImageExtension(Path::GetExtension(fd.FileName)))
    {
      continue;
    }

    const std::string_view title = Path::GetFileTitle(fd.FileName);
    const std::optional<TextureNames::Name> name = TextureNames::Parse(title);
    if (name.has_value())
      AddTexture(name.value(), std::move(fd.FileName));
    else if (title.starts_with("texupload-") || title.starts_with("texpage-") || title.starts_with("vram-write-"))
      WARNING_LOG("Skipping '{}': not a well-formed texture name.", Path::GetFileName(fd.FileName));
  }

  for (const auto& [alias, file] : config.aliases)
  {
    const std::optional<TextureNames::Name> name = TextureNames::Parse(alias);
    if (!name.has_value())
    {
      WARNING_LOG("Skipping alias '{}': it isn't a texture name.", alias);
      continue;
    }

    std::string path = Path::Combine(game_dir, file);
    if (!FileSystem::FileExists(path.c_str()) || !TextureNames::IsImageExtension(Path::GetExtension(path)))
    {
      WARNING_LOG("Skipping alias '{}': '{}' isn't an image in the game's folder.", alias, file);
      continue;
    }

    AddTexture(name.value(), std::move(path));
  }

  // The first of two files with one name wins, whatever lies between them; then sorted by key.
  std::unordered_map<std::string, std::string> seen;
  std::erase_if(m_textures, [&seen](const TextureReplacement& tr) {
    const auto [it, inserted] = seen.emplace(TextureNames::Format(tr.name), tr.path);
    if (!inserted)
      WARNING_LOG("Duplicate texture replacement: '{}' and '{}'", it->second, tr.path);
    return !inserted;
  });
  std::stable_sort(m_textures.begin(), m_textures.end(), NameLess);

  INFO_LOG("Found {} texture and {} VRAM write replacements in '{}'", m_textures.size(), m_vram_writes.size(),
           game_dir);
}

const std::string* TextureReplacements::ReplacementIndex::FindVRAMWrite(u64 high, u64 low) const
{
  const auto it = m_vram_writes.find(GetVRAMWriteKey(high, low));
  return (it != m_vram_writes.end()) ? &it->second : nullptr;
}

std::span<const TextureReplacements::TextureReplacement>
TextureReplacements::ReplacementIndex::FindUploadReplacements(TextureNames::Mode mode, u64 texture_hash) const
{
  TextureReplacement key = {};
  key.name.kind = TextureNames::Kind::TexUpload;
  key.name.mode = mode;
  key.name.texture_hash = texture_hash;
  const auto [first, last] = std::equal_range(m_textures.begin(), m_textures.end(), key, NameLess);
  return std::span<const TextureReplacement>(first, last);
}

std::vector<std::string> TextureReplacements::ReplacementIndex::GetAllPaths() const
{
  std::vector<std::string> paths;
  paths.reserve(m_textures.size() + m_vram_writes.size());
  for (const TextureReplacement& tr : m_textures)
    paths.push_back(tr.path);
  for (const auto& it : m_vram_writes)
    paths.push_back(it.second);
  std::sort(paths.begin(), paths.end());
  paths.erase(std::unique(paths.begin(), paths.end()), paths.end());
  return paths;
}

std::span<const TextureReplacements::TextureReplacement>
TextureReplacements::ReplacementIndex::GetPageReplacements(TextureNames::Mode mode) const
{
  const auto less = [](const TextureReplacement& lhs, const TextureReplacement& rhs) {
    return std::tie(lhs.name.kind, lhs.name.mode) < std::tie(rhs.name.kind, rhs.name.mode);
  };
  TextureReplacement key = {};
  key.name.kind = TextureNames::Kind::TexPage;
  key.name.mode = mode;
  const auto [first, last] = std::equal_range(m_textures.begin(), m_textures.end(), key, less);
  return std::span<const TextureReplacement>(first, last);
}

bool TextureReplacements::HasGameConfig(const std::string& game_dir)
{
  return FileSystem::FileExists(Path::Combine(game_dir, "config.yaml").c_str()) ||
         FileSystem::FileExists(Path::Combine(game_dir, "config.yml").c_str());
}

TexturePackConfig TextureReplacements::LoadGameConfig(const std::string& pack_dir, const std::string& local_dir,
                                                      const TexturePackConfig& base)
{
  return (pack_dir == local_dir || HasGameConfig(pack_dir) || !HasGameConfig(local_dir)) ?
           LoadGameConfig(pack_dir, base) :
           LoadGameConfig(local_dir, base);
}

void TextureReplacements::SetPackFolder(std::string folder)
{
  s_pack_folder = std::move(folder);
}

TexturePackConfig TextureReplacements::LoadGameConfig(const std::string& game_dir, const TexturePackConfig& base)
{
  for (const char* file : {"config.yaml", "config.yml"})
  {
    const std::string path = Path::Combine(game_dir, file);
    const std::optional<std::string> yaml = FileSystem::ReadFileToString(path.c_str());
    if (!yaml.has_value())
      continue;

    TexturePackConfig config = base;
    Error error;
    if (!config.LoadYAML(yaml.value(), &error))
    {
      ERROR_LOG("Not using '{}': {}", path, error.GetDescription());
      return base;
    }

    INFO_LOG("Using '{}'", path);
    return config;
  }

  return base;
}

void TextureReplacements::SetGameID(std::string game_id)
{
  if (s_game_id == game_id)
    return;

  s_game_id = game_id;
  Reload();
}

std::string TextureReplacements::GetGameDirectory()
{
  if (s_game_id.empty())
    return {};
  return Path::Combine(s_pack_folder.empty() ? EmuFolders::Textures : s_pack_folder, s_game_id);
}

std::string TextureReplacements::GetLocalGameDirectory()
{
  return s_game_id.empty() ? std::string() : Path::Combine(EmuFolders::Textures, s_game_id);
}

std::string TextureReplacements::GetTextureDumpDirectory()
{
  return s_game_id.empty() ? std::string() : Path::Combine(GetLocalGameDirectory(), "dumps");
}

const TexturePackConfig& TextureReplacements::GetConfig()
{
  return s_config;
}

const TextureReplacements::ReplacementIndex& TextureReplacements::GetIndex()
{
  return s_index;
}

const TextureReplacements::ReplacementImage* TextureReplacements::GetTextureImage(const std::string& path)
{
  auto it = s_image_cache.find(path);
  if (it != s_image_cache.end())
  {
    it->second.last_used = ++s_image_cache_counter;
    return &it->second.image;
  }

  RGBA8Image image;
  if (!image.LoadFromFile(path.c_str()))
  {
    ERROR_LOG("Failed to load '{}'", Path::GetFileName(path));
    return nullptr;
  }

  const size_t bytes = static_cast<size_t>(image.GetWidth()) * image.GetHeight() * sizeof(u32);
  const size_t limit = static_cast<size_t>(s_config.max_replacement_cache_vram_usage_mb) * 1024 * 1024;
  while (!s_image_cache.empty() && s_image_cache_bytes + bytes > limit)
  {
    auto oldest = s_image_cache.begin();
    for (auto cur = s_image_cache.begin(); cur != s_image_cache.end(); ++cur)
    {
      if (cur->second.last_used < oldest->second.last_used)
        oldest = cur;
    }
    s_image_cache_bytes -= static_cast<size_t>(oldest->second.image.GetWidth()) * oldest->second.image.GetHeight() *
                           sizeof(u32);
    s_image_cache.erase(oldest);
  }

  DEV_LOG("Loaded '{}': {}x{}", Path::GetFileName(path), image.GetWidth(), image.GetHeight());
  s_image_cache_bytes += bytes;
  it = s_image_cache.emplace(path, CachedImage{std::move(image), ++s_image_cache_counter}).first;
  return &it->second.image;
}

const TextureReplacements::ReplacementImage* TextureReplacements::GetVRAMReplacement(u32 width, u32 height,
                                                                                     const void* pixels)
{
  if (!g_gpu_settings.texture_replacements.enable_vram_write_replacements)
    return nullptr;

  const XXH128_hash_t hash = XXH3_128bits(pixels, width * height * sizeof(u16));
  const std::string* path = s_index.FindVRAMWrite(hash.high64, hash.low64);
  return path ? GetTextureImage(*path) : nullptr;
}

void TextureReplacements::DumpVRAMWrite(u32 width, u32 height, const void* pixels)
{
  const std::string filename = GetVRAMWriteDumpFilename(width, height, pixels);
  if (filename.empty())
    return;

  RGBA8Image image;
  image.SetSize(width, height);

  const u16* src_pixels = reinterpret_cast<const u16*>(pixels);

  for (u32 y = 0; y < height; y++)
  {
    for (u32 x = 0; x < width; x++)
    {
      image.SetPixel(x, y, VRAMRGBA5551ToRGBA8888(*src_pixels));
      src_pixels++;
    }
  }

  if (g_settings.texture_replacements.dump_vram_write_force_alpha_channel)
  {
    for (u32 y = 0; y < height; y++)
    {
      for (u32 x = 0; x < width; x++)
        image.SetPixel(x, y, image.GetPixel(x, y) | 0xFF000000u);
    }
  }

  INFO_LOG("Dumping {}x{} VRAM write to '{}'", width, height, Path::GetFileName(filename));
  if (!image.SaveToFile(filename.c_str())) [[unlikely]]
    ERROR_LOG("Failed to dump {}x{} VRAM write to '{}'", width, height, filename);
}

void TextureReplacements::Shutdown()
{
  s_image_cache.clear();
  s_image_cache_bytes = 0;
  s_index.Clear();
  s_config = TexturePackConfig();
  s_game_id.clear();
}

std::string TextureReplacements::GetVRAMWriteDumpFilename(u32 width, u32 height, const void* pixels)
{
  if (s_game_id.empty())
    return {};

  const XXH128_hash_t hash = XXH3_128bits(pixels, width * height * sizeof(u16));
  const std::string dump_directory(Path::Combine(EmuFolders::Dumps, Path::Combine("textures", s_game_id)));
  TextureNames::Name name = {};
  name.kind = TextureNames::Kind::VRAMWrite;
  name.texture_hash = hash.low64;
  name.texture_hash_high = hash.high64;
  std::string filename(Path::Combine(dump_directory, fmt::format("{}.png", TextureNames::Format(name))));

  if (FileSystem::FileExists(filename.c_str()))
    return {};

  if (!FileSystem::EnsureDirectoryExists(dump_directory.c_str(), false))
    return {};

  return filename;
}

u32 TextureReplacements::GetReloadCount()
{
  return s_reload_count;
}

void TextureReplacements::Reload()
{
  s_reload_count++;
  const std::string game_dir = GetGameDirectory();
  s_config = game_dir.empty() ? g_settings.texture_replacements.config :
                                LoadGameConfig(game_dir, GetLocalGameDirectory(), g_settings.texture_replacements.config);

  s_index.Clear();
  if (!game_dir.empty() && g_settings.texture_replacements.AnyReplacementsEnabled())
    s_index.Build(game_dir, s_config);

  // Drop images no longer in the index.
  const std::vector<std::string> paths = s_index.GetAllPaths();
  for (auto it = s_image_cache.begin(); it != s_image_cache.end();)
  {
    if (std::find(paths.begin(), paths.end(), it->first) == paths.end())
    {
      s_image_cache_bytes -= static_cast<size_t>(it->second.image.GetWidth()) * it->second.image.GetHeight() *
                             sizeof(u32);
      it = s_image_cache.erase(it);
    }
    else
    {
      ++it;
    }
  }

  if (g_settings.texture_replacements.preload_textures)
    PreloadTextures();

  // A reload with nothing to say takes down the last one's notice, which no longer holds.
  if (std::optional<std::string> notice = GetLoadNotice())
    Host::AddIconOSDMessage("TextureReplacementsLoad", ICON_FA_IMAGES, std::move(*notice), Host::OSD_INFO_DURATION);
  else
    Host::RemoveKeyedOSDMessage("TextureReplacementsLoad");
}

std::optional<std::string> TextureReplacements::FormatLoadNotice(const LoadNoticeState& state)
{
  if (state.game_id.empty() || (!state.texture_replacements && !state.vram_write_replacements))
    return std::nullopt;

  const size_t textures =
    (state.texture_replacements && state.texture_cache && state.hardware_renderer) ? state.textures : 0;
  const size_t vram_writes = (state.vram_write_replacements && state.hardware_renderer) ? state.vram_writes : 0;
  if (textures > 0 || vram_writes > 0)
  {
    const std::string texture_part =
      (textures == 1) ? TRANSLATE_STR("TextureReplacements", "1 replacement") :
                        fmt::format(TRANSLATE_FS("TextureReplacements", "{} replacements"), textures);
    const std::string vram_part =
      (vram_writes == 1) ? TRANSLATE_STR("TextureReplacements", "1 VRAM write replacement") :
                           fmt::format(TRANSLATE_FS("TextureReplacements", "{} VRAM write replacements"), vram_writes);
    if (textures > 0 && vram_writes > 0)
      return fmt::format(TRANSLATE_FS("TextureReplacements", "Texture pack: {} and {}"), texture_part, vram_part);
    if (textures == 0 && state.textures > 0 && state.texture_replacements && !state.texture_cache)
    {
      // Its VRAM writes apply, but its textures are switched on without the cache they need.
      return fmt::format(
        TRANSLATE_FS("TextureReplacements", "Texture pack: {}; its {} texture replacements need the texture cache"),
        vram_part, state.textures);
    }
    return fmt::format(TRANSLATE_FS("TextureReplacements", "Texture pack: {}"),
                       (textures > 0) ? texture_part : vram_part);
  }

  if (state.textures == 0 && state.vram_writes == 0)
  {
    return fmt::format(TRANSLATE_FS("TextureReplacements", "No texture pack found in {}"), state.folder);
  }

  // Found, but nothing applies: the first reason that fits.
  if (state.textures > 0 && state.texture_replacements && !state.texture_cache)
    return TRANSLATE_STR("TextureReplacements", "Texture pack found, but it needs the texture cache");
  if (!state.hardware_renderer)
    return TRANSLATE_STR("TextureReplacements", "Texture pack found, but it needs a hardware renderer");
  if (state.textures > 0 && !state.texture_replacements)
    return TRANSLATE_STR("TextureReplacements", "Texture pack found, but it needs texture replacements on");
  return TRANSLATE_STR("TextureReplacements", "Texture pack found, but it needs VRAM write replacements on");
}

bool TextureReplacements::IsUsingHardwareRenderer()
{
  // The renderer that started, which is software after a fallback or the software rendering hotkey; the configured
  // one before a GPU exists.
  return g_gpu ? g_gpu->IsHardwareRenderer() : !g_settings.IsUsingSoftwareRenderer();
}

std::optional<std::string> TextureReplacements::GetLoadNotice()
{
  // The folder as the player knows it: the data folder's textures/ (Android's is always that, through content://),
  // else a textures folder of its own, named in full.
  std::string folder;
  if (!s_pack_folder.empty())
  {
    folder = Path::Combine("textures", s_game_id);
  }
  else
  {
    const std::string game_dir = Path::Combine(EmuFolders::Textures, s_game_id);
    const std::string relative = Path::MakeRelative(game_dir, EmuFolders::DataRoot);
    folder = (Path::IsAbsolute(relative) || relative.starts_with("..")) ? game_dir : relative;
  }

  const Settings::TextureReplacementSettings& tr = g_settings.texture_replacements;
  return FormatLoadNotice(LoadNoticeState{.game_id = s_game_id,
                                          .folder = folder,
                                          .texture_replacements = tr.enable_texture_replacements,
                                          .vram_write_replacements = tr.enable_vram_write_replacements,
                                          .texture_cache = g_settings.gpu_texture_cache,
                                          .hardware_renderer = IsUsingHardwareRenderer(),
                                          .textures = s_index.GetTextureCount(),
                                          .vram_writes = s_index.GetVRAMWriteCount()});
}

void TextureReplacements::PreloadTextures()
{
  static constexpr float UPDATE_INTERVAL = 1.0f;

  const std::vector<std::string> paths = s_index.GetAllPaths();
  Common::Timer last_update_time;
  u32 num_loaded = 0;
  for (const std::string& path : paths)
  {
    if (last_update_time.GetTimeSeconds() >= UPDATE_INTERVAL)
    {
      Host::DisplayLoadingScreen("Preloading replacement textures...", 0, static_cast<int>(paths.size()),
                                 static_cast<int>(num_loaded));
      last_update_time.Reset();
    }

    GetTextureImage(path);
    num_loaded++;
  }

  INFO_LOG("Preloaded {} replacement images ({} MB)", s_image_cache.size(), s_image_cache_bytes / (1024 * 1024));
}
