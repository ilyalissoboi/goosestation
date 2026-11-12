// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "android_host_state.h"

#include "android_host.h"
#include "achievements_bridge.h"
#include "emu_thread.h"
#include "foreign_settings.h"
#include "game_codes.h"
#include "core/achievements.h"
#include "core/achievements_offline.h"
#include "core/analog_controller.h"
#include "core/controller.h"
#include "core/fullscreen_ui.h"
#include "core/game_database.h"
#include "core/game_list.h"
#include "core/host.h"
#include "core/memcard_sync.h"
#include "core/memory_card.h"
#include "core/pad.h"
#include "core/settings.h"
#include "core/system.h"
#include "util/android_game_settings.h"
#include "util/android_input_keys.h"
#include "util/android_input_source.h"
#include "util/gpu_device.h"
#include "util/imgui_fullscreen.h"
#include "util/imgui_manager.h"
#include "util/ini_settings_interface.h"
#include "util/input_manager.h"
#include "util/postprocessing.h"
#include "util/postprocessing_shader_slang.h"
#include "util/window_info.h"
#include "common/memory_settings_interface.h"
#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/path.h"
#include "common/small_string.h"
#include "common/string_util.h"
#include "fmt/format.h"
#include <android/native_window.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <optional>
#include <utility>
#include <vector>

Log_SetChannel(AndroidHost);

namespace AndroidHost {

using AndroidInputKeys::DEVICE_PREFIX;

using AndroidInputKeys::ParseDeviceIndex;

} // namespace AndroidHost

// The game settings files' edits, one at a time (the core's favourite hotkeys among them), so two never interleave a
// load and a save: System::GetGameSettingsFileMutex(), taken before the settings lock.

static std::string GameSettingsPath(const std::string& key)
{
  // EmuFolders::Update() changes the folder under the lock.
  const auto lock = Host::GetSettingsLock();
  return System::GetGameSettingsPath(key);
}

// The key's file as it is now; empty when there's none, or it can't be read.
static std::unique_ptr<INISettingsInterface> LoadGameSettings(const std::string& key)
{
  auto si = std::make_unique<INISettingsInterface>(GameSettingsPath(key));
  if (FileSystem::FileExists(si->GetFileName().c_str()))
  {
    Error error;
    if (!si->Load(&error))
      WARNING_LOG("Can't read {}: {}", si->GetFileName(), error.GetDescription());
  }
  return si;
}

// Saves si, or deletes its file once it holds nothing, as desktop does. Takes it: its destructor saves it while it has
// unsaved changes, which would write an empty file again after the delete.
static void SaveGameSettings(std::unique_ptr<INISettingsInterface> si)
{
  si->RemoveEmptySections();
  Error error;
  if (!si->IsEmpty())
  {
    FileSystem::EnsureDirectoryExists(std::string(Path::GetDirectory(si->GetFileName())).c_str(), false);
    if (!si->Save(&error))
      ERROR_LOG("Failed to save {}: {}", si->GetFileName(), error.GetDescription());
    return;
  }

  const std::string path = si->GetFileName();
  si.reset();
  if (FileSystem::FileExists(path.c_str()) && !FileSystem::DeleteFile(path.c_str(), &error))
    ERROR_LOG("Failed to delete {}: {}", path, error.GetDescription());
}

template<typename T, typename Read>
static T ReadGameSetting(const std::string& key, T default_value, const Read& read)
{
  if (!AndroidHost::s_initialized || key.empty())
    return default_value;

  std::unique_lock lock(System::GetGameSettingsFileMutex());
  const std::unique_ptr<INISettingsInterface> si = LoadGameSettings(key);
  return read(*si);
}

template<typename Edit>
static void EditGameSettings(const std::string& key, const Edit& edit)
{
  if (!AndroidHost::s_initialized || key.empty())
    return;

  std::unique_lock lock(System::GetGameSettingsFileMutex());
  std::unique_ptr<INISettingsInterface> si = LoadGameSettings(key);
  edit(*si);
  SaveGameSettings(std::move(si));
}

bool AndroidHost::HasGameSetting(const std::string& key, const char* section, const char* name)
{
  return ReadGameSetting(key, false, [&](const INISettingsInterface& si) { return si.ContainsValue(section, name); });
}

bool AndroidHost::GetGameBoolSetting(const std::string& key, const char* section, const char* name, bool default_value)
{
  return ReadGameSetting(key, default_value, [&](const INISettingsInterface& si) {
    return si.GetBoolValue(section, name, default_value);
  });
}

s32 AndroidHost::GetGameIntSetting(const std::string& key, const char* section, const char* name, s32 default_value)
{
  return ReadGameSetting(key, default_value, [&](const INISettingsInterface& si) {
    return si.GetIntValue(section, name, default_value);
  });
}

float AndroidHost::GetGameFloatSetting(const std::string& key, const char* section, const char* name,
                                       float default_value)
{
  return ReadGameSetting(key, default_value, [&](const INISettingsInterface& si) {
    return si.GetFloatValue(section, name, default_value);
  });
}

std::string AndroidHost::GetGameStringSetting(const std::string& key, const char* section, const char* name,
                                              const char* default_value)
{
  return ReadGameSetting(key, std::string(default_value), [&](const INISettingsInterface& si) {
    return si.GetStringValue(section, name, default_value);
  });
}

void AndroidHost::SetGameBoolSetting(const std::string& key, const char* section, const char* name, bool value)
{
  EditGameSettings(key, [&](INISettingsInterface& si) { si.SetBoolValue(section, name, value); });
}

void AndroidHost::SetGameIntSetting(const std::string& key, const char* section, const char* name, s32 value)
{
  EditGameSettings(key, [&](INISettingsInterface& si) { si.SetIntValue(section, name, value); });
}

void AndroidHost::SetGameFloatSetting(const std::string& key, const char* section, const char* name, float value)
{
  EditGameSettings(key, [&](INISettingsInterface& si) { si.SetFloatValue(section, name, value); });
}

void AndroidHost::SetGameStringSetting(const std::string& key, const char* section, const char* name,
                                       const char* value)
{
  EditGameSettings(key, [&](INISettingsInterface& si) {
    if (value)
      si.SetStringValue(section, name, value);
    else
      si.DeleteValue(section, name);
  });
}

void AndroidHost::DeleteGameSetting(const std::string& key, const char* section, const char* name)
{
  EditGameSettings(key, [&](INISettingsInterface& si) { si.DeleteValue(section, name); });
}

std::vector<std::pair<std::string, std::string>> AndroidHost::GetGameSettingKeys(const std::string& key)
{
  return ReadGameSetting(key, std::vector<std::pair<std::string, std::string>>(), [](const INISettingsInterface& si) {
    std::vector<std::pair<std::string, std::string>> keys;
    for (const std::string& section : si.GetSectionList())
    {
      for (auto& [name, value] : si.GetKeyValueList(section.c_str()))
      {
        // A list's key once, for all its values.
        if (std::find(keys.begin(), keys.end(), std::pair(section, name)) == keys.end())
          keys.emplace_back(section, std::move(name));
      }
    }
    return keys;
  });
}

void AndroidHost::CopyGlobalSettingsToGame(const std::string& key)
{
  EditGameSettings(key, [](INISettingsInterface& si) {
    Settings global;
    {
      const auto lock = Host::GetSettingsLock();
      global.Load(*AndroidHost::s_base_settings_interface, *AndroidHost::s_base_settings_interface);
    }
    global.Save(si, true);
  });
}

void AndroidHost::ClearGameSettings(const std::string& key)
{
  EditGameSettings(key, [](INISettingsInterface& si) { si.Clear(); });
}

// A chain's layer: settings.ini under the settings lock, saved after an edit, or the key's game file.
template<typename Edit>
static void EditChainLayer(const std::optional<std::string>& key, const Edit& edit)
{
  if (!AndroidHost::s_initialized)
    return;

  if (key.has_value())
  {
    EditGameSettings(*key, [&edit](INISettingsInterface& si) { edit(si); });
    return;
  }

  const auto lock = Host::GetSettingsLock();
  edit(*AndroidHost::s_base_settings_interface);
  Error error;
  if (!AndroidHost::s_base_settings_interface->Save(&error))
    ERROR_LOG("Failed to save settings: {}", error.GetDescription());
}

template<typename T, typename Read>
static T ReadChainLayer(const std::optional<std::string>& key, T default_value, const Read& read)
{
  if (!AndroidHost::s_initialized)
    return default_value;

  if (key.has_value())
    return ReadGameSetting(*key, std::move(default_value), [&read](const INISettingsInterface& si) { return read(si); });

  const auto lock = Host::GetSettingsLock();
  return read(*AndroidHost::s_base_settings_interface);
}

// The kind of the file PostProcessing::TryLoadingShader() takes for name, in its order: a slang preset by its name,
// then in the data folder's shaders and the app's packs, ReShade before GLSL, then the resources' ReShade shader, else
// GLSL (dolphinfx when it's under dolphinfx/), as GetAvailableShaders() names them.
static const char* ShaderKind(const std::string& name)
{
  using PostProcessing::ShaderKind;
  if (PostProcessing::Slang::IsPresetName(name))
    return PostProcessing::GetShaderKindName(ShaderKind::Slang);
  const std::string fx = fmt::format("reshade" FS_OSPATH_SEPARATOR_STR "Shaders" FS_OSPATH_SEPARATOR_STR "{}.fx", name);
  const ShaderKind glsl = name.starts_with("dolphinfx/") ? ShaderKind::DolphinFX : ShaderKind::GLSL;
  for (const std::string* root : {static_cast<const std::string*>(&EmuFolders::Shaders), &EmuFolders::GetShaderPacksFolder()})
  {
    if (FileSystem::FileExists(Path::Combine(*root, fx).c_str()))
      return PostProcessing::GetShaderKindName(ShaderKind::ReShade);
    if (FileSystem::FileExists(Path::Combine(*root, fmt::format("{}.glsl", name)).c_str()))
      return PostProcessing::GetShaderKindName(glsl);
  }
  if (FileSystem::FileExists(Path::Combine(EmuFolders::Resources, Path::Combine("shaders", fx)).c_str()))
    return PostProcessing::GetShaderKindName(ShaderKind::ReShade);
  return PostProcessing::GetShaderKindName(glsl);
}

// Whether the shader's metadata loads: Config::AddStage loads it first, and fails when it doesn't.
static bool ShaderLoads(const std::string& name, Error* error)
{
  MemorySettingsInterface scratch;
  return PostProcessing::Config::AddStage(scratch, PostProcessing::Config::DISPLAY_CHAIN_SECTION, name, error);
}

std::vector<std::pair<std::string, std::string>> AndroidHost::GetPostProcessingShaders()
{
  std::vector<std::pair<std::string, std::string>> shaders;
  if (!s_initialized)
    return shaders;

  // On the CPU thread: the shader folders are rewritten there (EmuFolders::Update()), and ShaderLoads() reads the GPU
  // device. The Post-processing page is in the library, or under the in-game menu, with the game paused.
  EmuThread::RunOnThread(
    [&shaders]() {
      for (const PostProcessing::AvailableShader& available : PostProcessing::GetAvailableShaders())
      {
        const std::string& name = available.name;
        // A pack has thousands of slang presets: they're offered by name, and one that doesn't load says so when
        // added.
        Error error;
        if (!PostProcessing::Slang::IsPresetName(name) && !ShaderLoads(name, &error))
        {
          INFO_LOG("Not offering shader '{}': {}", name, error.GetDescription());
          continue;
        }
        shaders.emplace_back(name, PostProcessing::GetShaderKindName(available.kind));
      }
    },
    true);
  return shaders;
}

// A chain layer's stages, each with its kind and options, or why it doesn't load. On the CPU thread.
static std::vector<AndroidHost::PostProcessingStage> ReadStages(const std::optional<std::string>& key,
                                                               const char* section)
{
  return ReadChainLayer(key, std::vector<AndroidHost::PostProcessingStage>(), [section](const SettingsInterface& si) {
    std::vector<AndroidHost::PostProcessingStage> stages;
    const u32 count = PostProcessing::Config::GetStageCount(si, section);
    for (u32 i = 0; i < count; i++)
    {
      AndroidHost::PostProcessingStage stage;
      stage.shader = PostProcessing::Config::GetStageShaderName(si, section, i);
      Error error;
      if (!stage.shader.empty() && ShaderLoads(stage.shader, &error))
      {
        stage.kind = ShaderKind(stage.shader);
        stage.options = PostProcessing::Config::GetStageOptions(si, section, i);
      }
      else
      {
        stage.error = error.GetDescription();
      }
      stages.push_back(std::move(stage));
    }
    return stages;
  });
}

std::vector<AndroidHost::PostProcessingStage> AndroidHost::GetPostProcessingStages(const std::optional<std::string>& key,
                                                                                const char* section)
{
  if (!s_initialized)
    return {};

  // On the CPU thread, as GetPostProcessingShaders(): ShaderLoads() and ShaderKind() read the shader folders.
  return EmuThread::Call([&]() { return ReadStages(key, section); });
}

std::string AndroidHost::AddPostProcessingStage(const std::optional<std::string>& key, const char* section,
                                                const std::string& shader)
{
  std::string result = s_initialized ? std::string() : std::string("GooseStation hasn't started.");
  EditChainLayer(key, [&](SettingsInterface& si) {
    Error error;
    if (!PostProcessing::Config::AddStage(si, section, shader, &error))
    {
      result = error.GetDescription();
      if (result.empty())
        result = fmt::format("The shader '{}' couldn't be loaded.", shader);
    }
  });
  return result;
}

void AndroidHost::RemovePostProcessingStage(const std::optional<std::string>& key, const char* section, u32 index)
{
  EditChainLayer(key, [&](SettingsInterface& si) { PostProcessing::Config::RemoveStage(si, section, index); });
}

void AndroidHost::MovePostProcessingStage(const std::optional<std::string>& key, const char* section, u32 index,
                                          bool down)
{
  EditChainLayer(key, [&](SettingsInterface& si) {
    if (down)
      PostProcessing::Config::MoveStageDown(si, section, index);
    else
      PostProcessing::Config::MoveStageUp(si, section, index);
  });
}

void AndroidHost::SetPostProcessingOption(const std::optional<std::string>& key, const char* section, u32 index,
                                          const std::string& name, std::span<const float> values)
{
  EditChainLayer(key, [&](SettingsInterface& si) {
    if (index >= PostProcessing::Config::GetStageCount(si, section))
      return;

    for (PostProcessing::ShaderOption& option : PostProcessing::Config::GetStageOptions(si, section, index))
    {
      if (option.name != name)
        continue;

      const u32 count = std::min<u32>(option.vector_size, static_cast<u32>(values.size()));
      for (u32 i = 0; i < count; i++)
      {
        if (option.type == PostProcessing::ShaderOption::Type::Float)
          option.value[i].float_value = values[i];
        else
          option.value[i].int_value = static_cast<s32>(std::lround(values[i]));
      }
      PostProcessing::Config::SetStageOption(si, section, index, option);
      return;
    }
  });
}

// Copies settings.ini's section chain into key's own file, over the one it had.
void AndroidHost::CopyPostProcessingChain(const std::string& key, const char* section)
{
  if (!s_initialized || key.empty())
    return;

  // settings.ini's chain, taken under the settings lock before the game's file is opened.
  MemorySettingsInterface global_chain;
  {
    const auto lock = Host::GetSettingsLock();
    PostProcessing::Config::CopyChain(*AndroidHost::s_base_settings_interface, global_chain, section);
  }

  EditGameSettings(key, [&](INISettingsInterface& si) { PostProcessing::Config::CopyChain(global_chain, si, section); });
}

void AndroidHost::ClearPostProcessingChain(const std::string& key, const char* section)
{
  EditGameSettings(key, [section](INISettingsInterface& si) { PostProcessing::Config::DeleteChain(si, section); });
}

// The favourites' folder, which EmuFolders::Update() changes under the settings lock.
static std::string FavouritesFolder()
{
  const auto lock = Host::GetSettingsLock();
  return EmuFolders::PostProcessing;
}

// Runs edit on settings.ini, which holds the favourites' order, under the settings lock, then saves it.
template<typename Edit>
static auto EditFavouritesOrder(const Edit& edit)
{
  const auto lock = Host::GetSettingsLock();
  const auto result = edit(EmuFolders::PostProcessing, *AndroidHost::s_base_settings_interface);
  Error error;
  if (!AndroidHost::s_base_settings_interface->Save(&error))
    ERROR_LOG("Failed to save settings: {}", error.GetDescription());
  return result;
}

static std::string FailureText(bool done, const Error& error)
{
  return done ? std::string() : error.GetDescription();
}

std::vector<PostProcessing::Favourites::Info> AndroidHost::GetPostProcessingFavourites()
{
  if (!s_initialized)
    return {};

  const auto lock = Host::GetSettingsLock();
  return PostProcessing::Favourites::List(EmuFolders::PostProcessing, *s_base_settings_interface);
}

std::string AndroidHost::SavePostProcessingFavourite(const std::optional<std::string>& key, const std::string& name,
                                                     bool replace)
{
  if (!s_initialized)
    return "GooseStation hasn't started.";

  // The chain the game runs: its file's when it has one of its own, else settings.ini's (taken below, under the lock).
  static constexpr const char* DISPLAY = PostProcessing::Config::DISPLAY_CHAIN_SECTION;
  MemorySettingsInterface chain;
  const bool own = key.has_value() && ReadGameSetting(*key, false, [&chain](const INISettingsInterface& si) {
                     if (!PostProcessing::Config::HasOwnChain(si, DISPLAY))
                       return false;
                     PostProcessing::Config::CopyChain(si, chain, DISPLAY);
                     return true;
                   });

  Error error;
  const bool saved = EditFavouritesOrder([&](const std::string& folder, SettingsInterface& base) {
    return PostProcessing::Favourites::Save(folder, base, name, own ? chain : base, replace, &error);
  });
  return FailureText(saved, error);
}

std::string AndroidHost::ApplyPostProcessingFavourite(const std::optional<std::string>& key, const std::string& name)
{
  if (!s_initialized)
    return "GooseStation hasn't started.";

  const std::string folder = FavouritesFolder();
  Error error;
  bool applied = false;
  EditChainLayer(key, [&](SettingsInterface& si) {
    applied = PostProcessing::Favourites::Apply(folder, name, si, &error);
  });
  return FailureText(applied, error);
}

std::string AndroidHost::RenamePostProcessingFavourite(const std::string& from, const std::string& to)
{
  if (!s_initialized)
    return "GooseStation hasn't started.";

  Error error;
  const bool renamed = EditFavouritesOrder([&](const std::string& folder, SettingsInterface& base) {
    return PostProcessing::Favourites::Rename(folder, base, from, to, &error);
  });
  return FailureText(renamed, error);
}

void AndroidHost::MovePostProcessingFavourite(const std::string& name, bool down)
{
  if (!s_initialized)
    return;

  EditFavouritesOrder([&](const std::string& folder, SettingsInterface& base) {
    PostProcessing::Favourites::Move(folder, base, name, down);
    return true;
  });
}

std::string AndroidHost::DeletePostProcessingFavourite(const std::string& name)
{
  if (!s_initialized)
    return "GooseStation hasn't started.";

  Error error;
  const bool deleted = EditFavouritesOrder([&](const std::string& folder, SettingsInterface& base) {
    return PostProcessing::Favourites::Delete(folder, base, name, &error);
  });
  return FailureText(deleted, error);
}

bool AndroidHost::PressHotkeyForTest(const std::string& name)
{
  bool found = false;
  EmuThread::RunOnThread(
    [&name, &found]() {
      for (const HotkeyInfo* hotkey : InputManager::GetHotkeyList())
      {
        if (name != hotkey->name)
          continue;
        hotkey->handler(1);
        hotkey->handler(0);
        found = true;
        break;
      }
    },
    true);
  // What the hotkey queued on the CPU thread (the queue runs in order), done before this returns.
  EmuThread::RunOnThread([]() {}, true);
  return found;
}

bool AndroidHost::PostProcessingUsesOpenGLES(const std::optional<std::string>& key)
{
  if (!s_initialized)
    return false;

  bool running = false;
  bool gles = false;
  EmuThread::RunOnThread(
    [&running, &gles]() {
      if (System::IsValid() && g_gpu_device)
      {
        running = true;
        gles = (g_gpu_device->GetRenderAPI() == RenderAPI::OpenGLES);
      }
    },
    true);
  if (running)
    return gles;

  // The game's own renderer, when its file sets one, as the core layers it at the game's boot; else settings.ini's.
  std::string name;
  if (key.has_value())
    name = ReadGameSetting(*key, std::string(), [](const INISettingsInterface& si) { return si.GetStringValue("GPU", "Renderer"); });
  if (name.empty())
  {
    const auto lock = Host::GetSettingsLock();
    name = AndroidHost::s_base_settings_interface->GetStringValue("GPU", "Renderer",
                                                                  Settings::GetRendererName(Settings::DEFAULT_GPU_RENDERER));
  }
  const GPURenderer renderer = Settings::ParseRendererName(name.c_str()).value_or(Settings::DEFAULT_GPU_RENDERER);
  // Once Vulkan has failed, every device is OpenGL ES until the app restarts.
  return AndroidHost::s_vulkan_failed.load() || !IsVulkanRenderer(renderer);
}

bool AndroidHost::IsFastmemAvailable()
{
  return sysconf(_SC_PAGESIZE) <= static_cast<long>(HOST_PAGE_SIZE);
}

bool AndroidHost::IsPostProcessingActiveForTest(const std::string& section)
{
  bool active = false;
  EmuThread::RunOnThread(
    [&section, &active]() {
      if (System::IsValid())
      {
        active = (section == PostProcessing::Config::INTERNAL_CHAIN_SECTION) ? PostProcessing::InternalChain.IsActive() :
                                                                               PostProcessing::DisplayChain.IsActive();
      }
    },
    true);
  return active;
}

std::optional<std::string> AndroidHost::GetGameInputProfile(const std::string& key)
{
  return ReadGameSetting(key, std::optional<std::string>(), [](const INISettingsInterface& si) {
    std::optional<std::string> profile;
    if (!si.GetBoolValue("ControllerPorts", "UseGameSettingsForController", false))
    {
      if (std::string name = si.GetStringValue("ControllerPorts", "InputProfileName"); !name.empty())
        profile = std::move(name);
    }
    return profile;
  });
}

bool AndroidHost::ApplyGameSettings(const std::string& key)
{
  if (!s_initialized || key.empty())
    return false;

  const std::string path = GameSettingsPath(key);
  bool applied = false;
  EmuThread::RunOnThread(
    [&path, &applied]() {
      if (!System::IsValid() || !s_running_game.has_value() || GameSettingsPath(s_running_game->key) != path)
        return;

      System::ReloadGameSettings(false);
      System::ReloadInputBindings();
      applied = true;
    },
    true);
  return applied;
}

void AndroidHost::UpdateGameControllers(const std::string& key)
{
  if (!s_initialized || key.empty())
    return;

  // Its controller settings count while it has any, as Android writes only those (never bindings).
  EditGameSettings(key, [](INISettingsInterface& si) {
    if (AndroidGameSettings::HasControllerKeys(si))
      si.SetBoolValue("ControllerPorts", "UseGameSettingsForController", true);
    else
      si.DeleteValue("ControllerPorts", "UseGameSettingsForController");
  });

  // The types it sets: each controller on such a port gets the type's default bindings where it has none.
  std::array<const Controller::ControllerInfo*, AndroidInputKeys::NUM_PADS> types = {};
  ReadGameSetting(key, 0, [&types](const INISettingsInterface& si) {
    if (!si.GetBoolValue("ControllerPorts", "UseGameSettingsForController", false))
      return 0;
    for (u32 pad = 0; pad < types.size(); pad++)
    {
      const std::string type = si.GetStringValue(Controller::GetSettingsSection(pad).c_str(), "Type");
      types[pad] = type.empty() ? nullptr : Controller::GetControllerInfo(type);
    }
    return 0;
  });

  EmuThread::RunOnThread(
    [&types]() {
      u32 added = 0;
      {
        const auto lock = Host::GetSettingsLock();
        SettingsInterface& si = *s_base_settings_interface;
        for (u32 index = 0; index < s_descriptors.size(); index++)
        {
          const u32 pad = GetPadOfIndex(si, index);
          added += AddDefaultBindings(si, index, pad, types[pad]);
        }

        Error error;
        if (added > 0 && !si.Save(&error))
          ERROR_LOG("Failed to save settings: {}", error.GetDescription());
      }

      if (added > 0)
        System::ReloadInputBindings();
    },
    true);
}
