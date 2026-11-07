// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

// The runtime-independent part of librashader's C API. Each renderer's glue defines its own LIBRA_RUNTIME_* macro
// before including this, for the filter chain functions.
#include "librashader.h"

#include <string>

class Error;

/// librashader, opened at run time (it's MPL-2.0 and never linked). Adapted from the PCSX2 fork's
/// GS/ShaderChain/LibrashaderLoader.
namespace Librashader {

struct Availability
{
  bool available = false;
  std::string reason;
};

struct CommonFunctions
{
  PFN_libra_instance_abi_version abi_version = nullptr;
  PFN_libra_instance_api_version api_version = nullptr;
  PFN_libra_error_errno error_errno = nullptr;
  PFN_libra_error_write error_write = nullptr;
  PFN_libra_error_free_string error_free_string = nullptr;
  PFN_libra_error_free error_free = nullptr;
  PFN_libra_preset_ctx_create preset_ctx_create = nullptr;
  PFN_libra_preset_ctx_free preset_ctx_free = nullptr;
  PFN_libra_preset_ctx_set_core_name preset_ctx_set_core_name = nullptr;
  PFN_libra_preset_ctx_set_runtime preset_ctx_set_runtime = nullptr;
  PFN_libra_preset_create_with_options preset_create_with_options = nullptr;
  PFN_libra_preset_free preset_free = nullptr;
  PFN_libra_preset_get_runtime_params preset_get_runtime_params = nullptr;
  PFN_libra_preset_free_runtime_params preset_free_runtime_params = nullptr;
};

/// Opens the library at GetDefaultLibraryPath() on the first call (thread-safe) and keeps the result.
const Availability& GetAvailability();

/// The runtime-independent entry points. Only valid when GetAvailability().available.
const CommonFunctions& Common();

/// A symbol for a renderer's glue, or null when the library isn't available.
void* GetSymbol(const char* name);

/// Looks up a renderer's entry point; says which one is missing when it isn't there.
bool ResolveSymbol(const char* name, void** out, Error* error);
template<typename T>
inline bool ResolveSymbol(const char* name, T* out, Error* error)
{
  void* ptr = nullptr;
  const bool ret = ResolveSymbol(name, &ptr, error);
  *out = reinterpret_cast<T>(ptr);
  return ret;
}

/// librashader's text for an error, which is then freed. "unknown librashader error" for null.
std::string DescribeAndFreeError(libra_error_t error);

/// Where the library is looked for: GOOSESTATION_LIBRASHADER_PATH when set; else librashader.dll beside the program
/// (Windows), the bundle's Frameworks/librashader.dylib (macOS), or librashader.so beside the program, falling back to
/// the bare name for the system's search (the AppImage's and Flatpak's library paths, Android's APK).
std::string GetDefaultLibraryPath();

/// Opens the library at path and checks it, without touching the global state. For tests.
Availability LoadFromPath(const std::string& path);

/// Parses a preset, with core name "GooseStation" and the given runtime (unless LIBRA_PRESET_CTX_RUNTIME_NONE) for
/// its wildcards. On success *out owns the preset (free it with Common().preset_free, or hand it to a chain).
bool LoadPreset(const std::string& path, LIBRA_PRESET_CTX_RUNTIME runtime, libra_shader_preset_t* out, Error* error);

} // namespace Librashader
