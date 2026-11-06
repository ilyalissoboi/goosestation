// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "librashader_loader.h"

#include "core/settings.h"

#include "common/dynamic_library.h"
#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/path.h"

#include "fmt/format.h"

#include <cstdlib>
#include <mutex>

Log_SetChannel(Librashader);

namespace {

struct LoadedLibrary
{
  DynamicLibrary lib;
  Librashader::CommonFunctions fns;
  Librashader::Availability avail;
};

template<typename T>
bool Resolve(DynamicLibrary& lib, const char* name, T* out, std::string* reason)
{
  if (lib.GetSymbol(name, out))
    return true;
  *reason = fmt::format("librashader is missing symbol {}", name);
  return false;
}

bool LoadInto(LoadedLibrary& l, const std::string& path)
{
  l.avail = {};
  if (path.empty())
  {
    l.avail.reason = "librashader path is empty";
    return false;
  }

  Error error;
  if (!l.lib.Open(path.c_str(), &error))
  {
    l.avail.reason = fmt::format("librashader could not be loaded from {}: {}", path, error.GetDescription());
#ifdef _WIN32
    // Its D3D12 runtime imports DXC at load time, so a missing DXC fails the whole library.
    l.avail.reason += " (librashader.dll also needs dxcompiler.dll and dxil.dll beside the program)";
#endif
    return false;
  }

  std::string& r = l.avail.reason;
  Librashader::CommonFunctions& f = l.fns;
  if (!Resolve(l.lib, "libra_instance_abi_version", &f.abi_version, &r) ||
      !Resolve(l.lib, "libra_instance_api_version", &f.api_version, &r))
  {
    l.lib.Close();
    return false;
  }

  const LIBRASHADER_ABI_VERSION abi = f.abi_version();
  if (abi != LIBRASHADER_CURRENT_ABI)
  {
    r = fmt::format("librashader ABI {} does not match the expected ABI {}", abi, LIBRASHADER_CURRENT_ABI);
    l.lib.Close();
    return false;
  }

  if (!Resolve(l.lib, "libra_error_errno", &f.error_errno, &r) ||
      !Resolve(l.lib, "libra_error_write", &f.error_write, &r) ||
      !Resolve(l.lib, "libra_error_free_string", &f.error_free_string, &r) ||
      !Resolve(l.lib, "libra_error_free", &f.error_free, &r) ||
      !Resolve(l.lib, "libra_preset_ctx_create", &f.preset_ctx_create, &r) ||
      !Resolve(l.lib, "libra_preset_ctx_free", &f.preset_ctx_free, &r) ||
      !Resolve(l.lib, "libra_preset_ctx_set_core_name", &f.preset_ctx_set_core_name, &r) ||
      !Resolve(l.lib, "libra_preset_ctx_set_runtime", &f.preset_ctx_set_runtime, &r) ||
      !Resolve(l.lib, "libra_preset_create_with_options", &f.preset_create_with_options, &r) ||
      !Resolve(l.lib, "libra_preset_free", &f.preset_free, &r) ||
      !Resolve(l.lib, "libra_preset_get_runtime_params", &f.preset_get_runtime_params, &r) ||
      !Resolve(l.lib, "libra_preset_free_runtime_params", &f.preset_free_runtime_params, &r))
  {
    l.lib.Close();
    return false;
  }

  l.avail.available = true;
  l.avail.reason.clear();
  return true;
}

LoadedLibrary& Global()
{
  static LoadedLibrary s_lib;
  return s_lib;
}

std::once_flag s_load_once;

} // namespace

std::string Librashader::GetDefaultLibraryPath()
{
  if (const char* env = std::getenv("GOOSESTATION_LIBRASHADER_PATH"); env && env[0] != '\0')
    return env;

#if defined(_WIN32)
  return Path::Combine(EmuFolders::AppRoot, "librashader.dll");
#elif defined(__APPLE__)
  std::string in_bundle = Path::Combine(EmuFolders::AppRoot, "../Frameworks/librashader.dylib");
  if (FileSystem::FileExists(in_bundle.c_str()))
    return in_bundle;
  return Path::Combine(EmuFolders::AppRoot, "librashader.dylib");
#else
  std::string beside = Path::Combine(EmuFolders::AppRoot, "librashader.so");
  if (!EmuFolders::AppRoot.empty() && FileSystem::FileExists(beside.c_str()))
    return beside;
  return "librashader.so";
#endif
}

Librashader::Availability Librashader::LoadFromPath(const std::string& path)
{
  LoadedLibrary local;
  LoadInto(local, path);
  return local.avail; // the library closes as local goes
}

const Librashader::Availability& Librashader::GetAvailability()
{
  std::call_once(s_load_once, []() {
    LoadedLibrary& g = Global();
    const std::string path = GetDefaultLibraryPath();
    if (LoadInto(g, path))
      INFO_LOG("librashader loaded from {} (ABI {}, API {})", path, g.fns.abi_version(), g.fns.api_version());
    else
      WARNING_LOG("Slang presets unavailable: {}", g.avail.reason);
  });
  return Global().avail;
}

const Librashader::CommonFunctions& Librashader::Common()
{
  GetAvailability();
  return Global().fns;
}

void* Librashader::GetSymbol(const char* name)
{
  if (!GetAvailability().available)
    return nullptr;
  return Global().lib.GetSymbolAddress(name);
}

std::string Librashader::DescribeAndFreeError(libra_error_t error)
{
  if (!error)
    return "unknown librashader error";

  const CommonFunctions& f = Common();
  if (!f.error_write)
    return "librashader error (library not loaded)";

  std::string result;
  char* text = nullptr;
  if (f.error_write(error, &text) == 0 && text)
  {
    result = text;
    f.error_free_string(&text);
  }
  else
  {
    result = fmt::format("librashader error code {}", static_cast<int>(f.error_errno(error)));
  }
  f.error_free(&error);
  return result;
}

bool Librashader::LoadPreset(const std::string& path, LIBRA_PRESET_CTX_RUNTIME runtime, libra_shader_preset_t* out,
                             Error* error)
{
  *out = nullptr;
  const Availability& avail = GetAvailability();
  if (!avail.available)
  {
    Error::SetStringView(error, avail.reason);
    return false;
  }

  const CommonFunctions& c = Common();
  libra_preset_ctx_t ctx = nullptr;
  // librashader honours (and frees) the context only when options are passed, so they must never be null.
  libra_preset_opt_t opt = {};
  opt.version = LIBRASHADER_CURRENT_VERSION;

  libra_error_t err = c.preset_ctx_create(&ctx);
  if (!err)
    err = c.preset_ctx_set_core_name(&ctx, "GooseStation");
  if (!err && runtime != LIBRA_PRESET_CTX_RUNTIME_NONE)
    err = c.preset_ctx_set_runtime(&ctx, runtime);
  if (!err)
    err = c.preset_create_with_options(path.c_str(), &ctx, &opt, out); // consumes ctx on success
  if (err)
  {
    Error::SetString(error, DescribeAndFreeError(err));
    if (ctx)
      c.preset_ctx_free(&ctx);
    *out = nullptr;
    return false;
  }
  return true;
}
