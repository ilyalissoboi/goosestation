// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/settings.h"

#include "common/memory_settings_interface.h"

#include <gtest/gtest.h>

TEST(SettingsGPUThread, OnByDefault)
{
  MemorySettingsInterface si;
  Settings settings;
  settings.gpu_use_gpu_thread = false;
  settings.Load(si, si);
  EXPECT_TRUE(settings.gpu_use_gpu_thread);
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
