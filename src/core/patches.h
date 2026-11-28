// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "cheats.h"
#include "types.h"

#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>

class Error;

/// Patches: the widescreen, frame-rate and display-mode codes, kept apart from cheats. Which of the bundled database's
/// codes are patches, and what each needs, comes from GooseStation's marks beside it (chtdb_patches.txt); a code read
/// from the newer community format marks itself with its override keys, and one of our .cht files with `#patch`.
namespace Patches {

enum class Kind : u8
{
  Widescreen,  // 16:9 and other ratios
  FrameRate,   // 60 fps and frame-rate uncaps
  DisplayMode, // NTSC mode on PAL discs, black bars removed
  Other,       // a patch its own file marks, without a kind it says
};

/// What a patch needs set for the running game while it's on: 0 leaves a setting as it is.
struct Needs
{
  u16 aspect_numerator = 0;
  u16 aspect_denominator = 0;
  bool disable_widescreen_rendering = false;
  u32 overclock_percent = 0;
  bool may_need_overclock = false; // a frame-rate patch without a known overclock
};

/// A code the marks name as a patch: its kind, its needs, and the title of the alternatives it's one of (empty for
/// none): codes of a game with the same choice title show as one patch, of which only one can be on.
struct Mark
{
  Kind kind = Kind::Other;
  Needs needs;
  std::string choice;
};

/// GooseStation's marks for the bundled database: one `serial<TAB>Group\Name<TAB>kind and needs` line per patch code,
/// the code's group and name as chtdb.txt has them (no group for the database's "Ungrouped").
class Marks
{
public:
  /// The marks in text. A line that can't be read is skipped, and the first such is error's (with its line number).
  static Marks Parse(std::string_view text, Error* error);

  /// The mark for serial's code, by its group (the core's "Ungrouped", or empty, for none) and name, or null.
  const Mark* Find(std::string_view serial, std::string_view group, std::string_view name) const;

  size_t Count() const { return m_marks.size(); }

private:
  std::unordered_map<std::string, Mark> m_marks; // by serial, a tab, and Group\Name
};

/// The bundled marks, chtdb_patches.txt, read once (none if it's missing).
const Marks& BundledMarks();

/// A code's name as the marks and the switches have it: `Group\Name`, or `Name` for a code without a group.
std::string CodeKey(std::string_view group, std::string_view name);

/// What a code's own override keys (the newer format's OverrideAspectRatio, OverrideCPUOverclock and
/// DisableWidescreenRendering) say it needs.
Needs NeedsFromOverrides(const CheatCode& code);

/// Whether serial's code is a patch, and its mark if so: from the marks, else from its own file (override keys or a
/// `#patch` line). Marks the code a patch (code.patch) when it is one.
std::optional<Mark> Classify(std::string_view serial, CheatCode& code, const Marks& marks);

} // namespace Patches
