// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// Android's stand-in for the fullscreen UI (android/app/src/cpp/fullscreen_ui_stubs.cpp), with the real
// ImGuiFullscreen. No GPU device is needed: a texture that fails to load is cached as the (empty) placeholder.

#include "core/fullscreen_ui.h"

#include "util/gpu_device.h"
#include "util/host.h"
#include "util/image.h"
#include "util/imgui_fullscreen.h"

#include <gtest/gtest.h>

// What imgui_fullscreen.cpp needs from the rest of util and from the host, with nothing to load.
std::unique_ptr<GPUDevice> g_gpu_device;

std::unique_ptr<GPUTexture> GPUDevice::FetchTexture(u32 width, u32 height, u32 layers, u32 levels, u32 samples,
                                                    GPUTexture::Type type, GPUTexture::Format format, const void* data,
                                                    u32 data_stride)
{
  return {};
}

void GPUDevice::RecycleTexture(std::unique_ptr<GPUTexture> texture)
{
}

std::optional<DynamicHeapArray<u8>> Host::ReadResourceFile(std::string_view filename, bool allow_override)
{
  return std::nullopt;
}

bool Host::ShouldPreferHostFileSelector()
{
  return false;
}

void Host::OpenHostFileSelectorAsync(std::string_view title, bool select_directory, FileSelectorCallback callback,
                                     FileSelectorFilters filters, std::string_view initial_directory)
{
}

RGBA8Image::RGBA8Image() = default;

RGBA8Image::RGBA8Image(RGBA8Image&& move) : Image(move)
{
}

RGBA8Image& RGBA8Image::operator=(RGBA8Image&& move)
{
  Image<u32>::operator=(move);
  return *this;
}

bool RGBA8Image::LoadFromFile(std::string_view filename, std::FILE* fp)
{
  return false;
}

bool RGBA8Image::LoadFromBuffer(std::string_view filename, const void* buffer, size_t buffer_size)
{
  return false;
}

// The loading screen's logo is cached against the GPU device it was made on, so FullscreenUI::Shutdown() has to drop
// it. A logo left over from an OpenGL ES device was drawn as a Vulkan texture by the next Vulkan device, and crashed.
TEST(FullscreenUIStubs, ShutdownReleasesCachedTextures)
{
  // InvalidateCachedTexture() says whether the cache held the texture (and drops it), so check it caches at all.
  ImGuiFullscreen::GetCachedTexture("images/placeholder.png");
  ASSERT_TRUE(ImGuiFullscreen::InvalidateCachedTexture("images/placeholder.png"));

  ImGuiFullscreen::GetCachedTexture("images/goosestation.png");

  FullscreenUI::Shutdown();

  EXPECT_FALSE(ImGuiFullscreen::InvalidateCachedTexture("images/goosestation.png"));
}
