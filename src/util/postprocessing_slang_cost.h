// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <optional>
#include <string>
#include <string_view>

/// How heavy a slang preset is likely to be, from its structure only (never by running it), for the shader browsers.
namespace PostProcessing::Slang {

enum class Cost : u8
{
  Light,
  Heavy,
  VeryHeavy,
};

struct CostEstimate
{
  u32 passes;
  /// Pixels its passes draw, in 1920x1080 screens, from a 320x240 picture onto a 1920x1080 screen.
  float screens;
  Cost cost;
};

/// The label for a score of screens + 0.1 x passes: light below 5, heavy below 10, very heavy from 10. From the Thor's
/// measurements: uborder (1 pass, 1.0 screens) and Mega Bezel Potato (10, 2.3) hold full speed, Mega Bezel standard
/// (30, 9.0) runs at 2.5 VPS.
Cost CostOf(u32 passes, float screens);

/// Reads the preset and its #reference chain (later files win) as text: its passes, and the size each draws at
/// (scale_type and scale per pass; a last pass without a scale type draws at the screen's size). None when the file
/// can't be read.
std::optional<CostEstimate> EstimateCost(const std::string& path);

/// EstimateCost()'s label for the preset at path, kept in memory by path (thread-safe).
std::optional<Cost> GetCostForPath(const std::string& path);

/// The label for a stage's preset name (FindPreset()); none when it isn't found or can't be read.
std::optional<Cost> GetCostForName(std::string_view name);

/// Forgets the kept labels, for when shader packs are installed, updated or removed.
void ClearCostCache();

/// "light", "heavy" or "very heavy", translated.
const char* GetCostLabel(Cost cost);

} // namespace PostProcessing::Slang
