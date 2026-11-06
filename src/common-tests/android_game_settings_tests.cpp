// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/android_game_settings.h"

#include "common/memory_settings_interface.h"

#include <gtest/gtest.h>

#include <string>
#include <string_view>

using AndroidGameSettings::Adapt;
using AndroidGameSettings::HasControllerKeys;
using AndroidGameSettings::Usable;

namespace {

Usable MakeUsable()
{
  Usable usable;
  usable.pad_setting_keys = {"SteeringDeadzone", "AnalogDeadzone"};
  usable.renderers = {"Automatic", "Vulkan", "OpenGL", "Software"};
  usable.has_bios = [](std::string_view name) { return name == "a.bin"; };
  return usable;
}

} // namespace

TEST(AndroidGameSettings, KeepsTypesAndTypeSettingsDropsBindings)
{
  MemorySettingsInterface si;
  si.SetStringValue("Pad1", "Type", "NeGcon");
  si.SetFloatValue("Pad1", "SteeringDeadzone", 0.2f);
  si.SetStringValue("Pad1", "Cross", "SDL-0/A");
  si.SetStringValue("Pad1", "Macro1Binds", "Cross");
  si.SetStringValue("Pad8", "Circle", "Keyboard/X");

  Adapt(si, MakeUsable());

  EXPECT_EQ(si.GetStringValue("Pad1", "Type"), "NeGcon");
  EXPECT_TRUE(si.ContainsValue("Pad1", "SteeringDeadzone"));
  EXPECT_FALSE(si.ContainsValue("Pad1", "Cross"));
  EXPECT_FALSE(si.ContainsValue("Pad1", "Macro1Binds"));
  EXPECT_FALSE(si.ContainsValue("Pad8", "Circle"));
}

TEST(AndroidGameSettings, DropsTheInputProfileSourcesAndHotkeys)
{
  MemorySettingsInterface si;
  si.SetStringValue("ControllerPorts", "InputProfileName", "Desk");
  si.SetStringValue("ControllerPorts", "MultitapMode", "Port1Only");
  si.SetBoolValue("InputSources", "SDL", true);
  si.SetStringValue("Hotkeys", "FastForward", "Keyboard/Tab");

  Adapt(si, MakeUsable());

  EXPECT_FALSE(si.ContainsValue("ControllerPorts", "InputProfileName"));
  EXPECT_EQ(si.GetStringValue("ControllerPorts", "MultitapMode"), "Port1Only");
  EXPECT_FALSE(si.ContainsValue("InputSources", "SDL"));
  EXPECT_FALSE(si.ContainsValue("Hotkeys", "FastForward"));
}

TEST(AndroidGameSettings, DropsARendererAndroidLacks)
{
  MemorySettingsInterface d3d;
  d3d.SetStringValue("GPU", "Renderer", "D3D12");
  Adapt(d3d, MakeUsable());
  EXPECT_FALSE(d3d.ContainsValue("GPU", "Renderer"));

  MemorySettingsInterface vulkan;
  vulkan.SetStringValue("GPU", "Renderer", "Vulkan");
  Adapt(vulkan, MakeUsable());
  EXPECT_EQ(vulkan.GetStringValue("GPU", "Renderer"), "Vulkan");
}

TEST(AndroidGameSettings, DropsMissingBiosNamesAndTheFolder)
{
  MemorySettingsInterface si;
  si.SetStringValue("BIOS", "PathNTSCU", "a.bin");
  si.SetStringValue("BIOS", "PathPAL", "b.bin");
  si.SetStringValue("BIOS", "SearchDirectory", "bios");
  si.SetBoolValue("BIOS", "PatchFastBoot", true);

  Adapt(si, MakeUsable());

  EXPECT_EQ(si.GetStringValue("BIOS", "PathNTSCU"), "a.bin");
  EXPECT_FALSE(si.ContainsValue("BIOS", "PathPAL"));
  EXPECT_FALSE(si.ContainsValue("BIOS", "SearchDirectory"));
  EXPECT_TRUE(si.ContainsValue("BIOS", "PatchFastBoot"));
}

TEST(AndroidGameSettings, DropsAnAbsoluteCardFolder)
{
  MemorySettingsInterface absolute;
  absolute.SetStringValue("MemoryCards", "Directory", "/home/x/cards");
  Adapt(absolute, MakeUsable());
  EXPECT_FALSE(absolute.ContainsValue("MemoryCards", "Directory"));

  MemorySettingsInterface relative;
  relative.SetStringValue("MemoryCards", "Directory", "cards");
  Adapt(relative, MakeUsable());
  EXPECT_EQ(relative.GetStringValue("MemoryCards", "Directory"), "cards");
}

TEST(AndroidGameSettings, ControllerKeysIgnoreTheFlag)
{
  MemorySettingsInterface flags;
  flags.SetBoolValue("ControllerPorts", "UseGameSettingsForController", true);
  flags.SetBoolValue("ControllerPorts", "GameSettingsInitialized", true);
  EXPECT_FALSE(HasControllerKeys(flags));

  MemorySettingsInterface type = flags;
  type.SetStringValue("Pad2", "Type", "NeGcon");
  EXPECT_TRUE(HasControllerKeys(type));

  MemorySettingsInterface multitap;
  multitap.SetStringValue("ControllerPorts", "MultitapMode", "Port1Only");
  EXPECT_TRUE(HasControllerKeys(multitap));

  MemorySettingsInterface other;
  other.SetIntValue("GPU", "ResolutionScale", 4);
  EXPECT_FALSE(HasControllerKeys(other));
}
