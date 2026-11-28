// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "cheats.h"
#include "types.h"

#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <tuple>
#include <unordered_map>
#include <utility>
#include <vector>

class Error;
class SettingsInterface;
struct Settings;

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

/// One of a patch's choices: its code and what the code needs while it runs.
struct Choice
{
  CheatCode code;
  Needs needs;
};

/// A patch as the lists show it: under group (empty for none), named title (its code's name, or its choices' title),
/// with one choice, or several of which only one can be on. saved is the choice the game's switches say (-1: off);
/// running the one applied now, which differs from saved until the next start when the running one can't be undone.
struct Patch
{
  std::string group;
  std::string title;
  Kind kind = Kind::Other;
  std::vector<Choice> choices;
  s32 saved = -1;
  s32 running = -1;
};

/// A code switched on or off, or given a value, where that differs from its default: by its key (CodeKey()); value is
/// an option's name or a number, empty for none.
struct Switch
{
  std::string code;
  bool on;
  std::string value;
};

/// A game's patches. Building it applies nothing: the caller applies the running choices each frame (ApplyFrame()),
/// undoes a code switched off at once (Set()'s result), and puts RunningNeeds() into the game's settings.
class PatchList
{
public:
  /// The list from a game's patch codes, in order, with their marks: codes sharing a choice title are one patch, at the
  /// first one's place. A code's file marker (enabled) is its default.
  static PatchList Build(std::vector<std::pair<CheatCode, Mark>> codes);

  size_t Count() const { return m_patches.size(); }
  const Patch& Get(size_t index) const { return m_patches[index]; }

  /// Sets every patch's choice from its default, then switches (a patch with two choices switched on takes the
  /// first), and the codes' values from switches by an option's name or a number.
  void Restore(std::span<const Switch> switches);

  /// The switches that keep this list: one for each code whose state or value differs from its default, in order.
  std::vector<Switch> Switches() const;

  /// Whether changing patch index to choice (-1: off) must wait for the next start: the running choice is on, isn't
  /// choice, and can't be undone (CheatCode::RestoresOnDisable()).
  bool NeedsRestart(size_t index, s32 choice) const;

  /// Changes patch index to choice: saved at once; running too unless NeedsRestart(). Returns the code switched off at
  /// once, for the caller to undo (CheatCode::ApplyOnDisable()) in a running game, or null.
  const CheatCode* Set(size_t index, s32 choice);

  /// Puts value into choice's code of patch index (see CheatCode::SetOptionValue()).
  bool SetValue(size_t index, s32 choice, u32 value, Error* error);

  /// The next start (a boot, a reset, a game change): every patch runs its saved choice.
  void Restart();

  /// What the running choices need together: the first ratio, the largest overclock, widescreen rendering off if any
  /// asks.
  Needs RunningNeeds() const;

  /// Applies the running choices' codes that apply every frame.
  void ApplyFrame() const;

  /// Puts RunningNeeds() into settings, the game's settings as just loaded, never written anywhere: the ratio (16:9,
  /// or a custom one), widescreen rendering off, an overclock never below the player's. A need finds a setting as the
  /// player had it the first time; when a later load finds the player changed it, the player's value wins until the
  /// need goes.
  void ApplyOverrides(Settings& settings);

private:
  // What each need found before it first overrode, and whether the player has since changed it.
  struct Found
  {
    std::optional<std::tuple<DisplayAspectRatio, u16, u16>> aspect;
    bool aspect_player = false;
    std::optional<bool> widescreen;
    bool widescreen_player = false;
    std::optional<std::tuple<bool, u32, u32>> overclock;
    bool overclock_player = false;
  };

  struct Defaults
  {
    s32 choice = -1;
    std::vector<u32> values; // each choice's code's value as built
  };

  std::vector<Patch> m_patches;
  std::vector<Defaults> m_defaults;
  Found m_found;
};

/// Whether patches run at all: not in hardcore mode, nor with "disable all enhancements" (which turns the overclock
/// off that frame-rate patches need).
bool Allowed(const Settings& settings, bool hardcore);

/// The switches in a game's settings: `[Patches]` lists `On` and `Off` of code keys, and `Value` of
/// `key<TAB>value`.
std::vector<Switch> ReadSwitches(const SettingsInterface& si);

/// Writes switches into si's `[Patches]`, replacing what it had; with none, the section goes.
void WriteSwitches(SettingsInterface& si, std::span<const Switch> switches);

/// Writes switches into serial's game settings file (System::GetGameSettingsPath()), removing empty sections, and the
/// file when nothing is left in it.
bool SaveSwitchesForSerial(std::string_view serial, std::span<const Switch> switches, Error* error);

} // namespace Patches
