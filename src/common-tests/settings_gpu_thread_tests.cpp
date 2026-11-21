// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/settings.h"
#include "core/system.h"

#include "common/memory_settings_interface.h"

#include <gtest/gtest.h>

// Off by default on the renderers that measured slower with it than without (docs/roadmap.md, item 21).
TEST(SettingsGPUThread, TheDefaultFollowsTheRenderer)
{
#ifdef ENABLE_OPENGL
  EXPECT_TRUE(Settings::GetDefaultGPUThread(GPURenderer::HardwareOpenGL));
#endif
#ifdef __APPLE__
  EXPECT_TRUE(Settings::GetDefaultGPUThread(GPURenderer::HardwareMetal));
#endif
#ifdef _WIN32
  EXPECT_FALSE(Settings::GetDefaultGPUThread(GPURenderer::HardwareD3D11));
  EXPECT_FALSE(Settings::GetDefaultGPUThread(GPURenderer::HardwareD3D12));
#endif
#if defined(ENABLE_VULKAN) && !defined(__ANDROID__)
  EXPECT_FALSE(Settings::GetDefaultGPUThread(GPURenderer::HardwareVulkan));
#endif
  // Automatic and Software go by the device Automatic makes.
  EXPECT_EQ(Settings::GetDefaultGPUThread(GPURenderer::Automatic),
            Settings::GetDefaultGPUThread(Settings::GetAutomaticRenderer()));
}

TEST(SettingsGPUThread, UnsetItFollowsTheRendererLoaded)
{
#if defined(ENABLE_OPENGL) && defined(ENABLE_VULKAN)
  for (const GPURenderer renderer : {GPURenderer::HardwareOpenGL, GPURenderer::HardwareVulkan})
  {
    MemorySettingsInterface si;
    si.SetStringValue("GPU", "Renderer", Settings::GetRendererName(renderer));
    Settings settings;
    settings.gpu_use_gpu_thread = !Settings::GetDefaultGPUThread(renderer);
    settings.Load(si, si);
    EXPECT_EQ(settings.gpu_use_gpu_thread, Settings::GetDefaultGPUThread(renderer)) << Settings::GetRendererName(renderer);
  }
#endif
}

// A new settings file (the first run, or a reset) gets the default renderer's.
TEST(SettingsGPUThread, TheDefaultsWrittenFollowTheDefaultRenderer)
{
  MemorySettingsInterface si;
  System::SetDefaultSettings(si);
  EXPECT_EQ(si.GetBoolValue("GPU", "UseGPUThread", !Settings::GetDefaultGPUThread(Settings::DEFAULT_GPU_RENDERER)),
            Settings::GetDefaultGPUThread(Settings::DEFAULT_GPU_RENDERER));
}

TEST(SettingsGPUThread, SavedAndLoaded)
{
  Settings settings;
  settings.gpu_use_gpu_thread = false;
  MemorySettingsInterface saved;
  settings.Save(saved, false);
  EXPECT_FALSE(saved.GetBoolValue("GPU", "UseGPUThread", true));

  Settings loaded;
  loaded.Load(saved, saved);
  EXPECT_FALSE(loaded.gpu_use_gpu_thread);
}
