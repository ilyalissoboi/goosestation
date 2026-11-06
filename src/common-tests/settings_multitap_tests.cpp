// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/controller.h"
#include "core/settings.h"

#include "common/memory_settings_interface.h"
#include "common/small_string.h"

#include <gtest/gtest.h>

#include <array>
#include <ostream>

// Prints a pad type by name in failure messages.
void PrintTo(ControllerType type, std::ostream* os)
{
  const Controller::ControllerInfo* info = Controller::GetControllerInfo(type);
  *os << (info ? info->name : "?");
}

namespace {

using PadTypes = std::array<ControllerType, NUM_CONTROLLER_AND_CARD_PORTS>;

constexpr ControllerType D = ControllerType::DigitalController;
constexpr ControllerType N = ControllerType::None;

// Loads settings where every pad's section names a digital controller, so a pad that Load forces to None stands out.
// Pads are 1A, 2A, 1B, 1C, 1D, 2B, 2C, 2D.
PadTypes LoadPadTypes(MultitapMode mode)
{
  MemorySettingsInterface si;
  si.SetStringValue("ControllerPorts", "MultitapMode", Settings::GetMultitapModeName(mode));
  for (u32 i = 0; i < NUM_CONTROLLER_AND_CARD_PORTS; i++)
    si.SetStringValue(TinyString::from_format("Pad{}", i + 1), "Type", "DigitalController");

  Settings settings;
  settings.Load(si, si);
  return settings.controller_types;
}

bool Port1(MultitapMode mode)
{
  Settings settings;
  settings.multitap_mode = mode;
  return settings.IsPort1MultitapEnabled();
}

bool Port2(MultitapMode mode)
{
  Settings settings;
  settings.multitap_mode = mode;
  return settings.IsPort2MultitapEnabled();
}

} // namespace

TEST(SettingsMultitap, PortEnabledFollowsMode)
{
  EXPECT_FALSE(Port1(MultitapMode::Disabled));
  EXPECT_FALSE(Port2(MultitapMode::Disabled));
  EXPECT_TRUE(Port1(MultitapMode::Port1Only));
  EXPECT_FALSE(Port2(MultitapMode::Port1Only));
  EXPECT_FALSE(Port1(MultitapMode::Port2Only));
  EXPECT_TRUE(Port2(MultitapMode::Port2Only));
  EXPECT_TRUE(Port1(MultitapMode::BothPorts));
  EXPECT_TRUE(Port2(MultitapMode::BothPorts));
}

TEST(SettingsMultitap, LoadDisabledKeepsOnlyPortA)
{
  EXPECT_EQ(LoadPadTypes(MultitapMode::Disabled), (PadTypes{D, D, N, N, N, N, N, N}));
}

TEST(SettingsMultitap, LoadPort1OnlyKeepsPort1Slots)
{
  EXPECT_EQ(LoadPadTypes(MultitapMode::Port1Only), (PadTypes{D, D, D, D, D, N, N, N}));
}

TEST(SettingsMultitap, LoadPort2OnlyKeepsPort2Slots)
{
  EXPECT_EQ(LoadPadTypes(MultitapMode::Port2Only), (PadTypes{D, D, N, N, N, D, D, D}));
}

TEST(SettingsMultitap, LoadBothPortsKeepsEverySlot)
{
  EXPECT_EQ(LoadPadTypes(MultitapMode::BothPorts), (PadTypes{D, D, D, D, D, D, D, D}));
}
