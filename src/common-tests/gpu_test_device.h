// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// A real GPU device, offscreen, for the opt-in GPU tests: GOOSESTATION_GPU_TEST_API picks the renderer (Metal, Vulkan,
// OpenGL, OpenGLES, D3D11, D3D12); without it, the tests skip (CI runs none).

#pragma once

#include "util/gpu_device.h"

#include "common/error.h"

#include <cstdlib>
#include <cstring>
#include <optional>
#include <string>

namespace GPUTestDevice {

inline std::optional<RenderAPI> API()
{
  const char* name = std::getenv("GOOSESTATION_GPU_TEST_API");
  if (!name)
    return std::nullopt;
  static constexpr std::pair<const char*, RenderAPI> names[] = {
    {"Metal", RenderAPI::Metal},     {"Vulkan", RenderAPI::Vulkan}, {"OpenGL", RenderAPI::OpenGL},
    {"OpenGLES", RenderAPI::OpenGLES}, {"D3D11", RenderAPI::D3D11},  {"D3D12", RenderAPI::D3D12},
  };
  for (const auto& [n, api] : names)
  {
    if (std::strcmp(name, n) == 0)
      return api;
  }
  return std::nullopt;
}

/// Makes g_gpu_device for the chosen renderer, with the shader and pipeline caches in cache_path (none if empty).
inline bool Create(std::string_view cache_path, Error* error)
{
  const std::optional<RenderAPI> api = API();
  if (!api.has_value())
    return false;
  g_gpu_device = GPUDevice::CreateDeviceForAPI(api.value());
  if (!g_gpu_device)
  {
    Error::SetStringView(error, "No device for this renderer in this build.");
    return false;
  }
  // A device that failed to create is still destroyed by Destroy().
  return g_gpu_device->Create({}, cache_path, 1, false, GPUVSyncMode::Disabled, false, false, std::nullopt, {}, error);
}

inline void Destroy()
{
  if (g_gpu_device)
  {
    g_gpu_device->Destroy();
    g_gpu_device.reset();
  }
}

} // namespace GPUTestDevice
