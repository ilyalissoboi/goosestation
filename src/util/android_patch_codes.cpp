// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "android_patch_codes.h"

#include <algorithm>
#include <set>
#include <utility>

namespace AndroidPatchCodes {

static constexpr std::string_view HEADER =
  "# GooseStation: the patch codes you switched on or off for this game. The app writes this file.\n";

std::vector<Switch> Parse(std::string_view text)
{
  std::vector<Switch> switches;
  size_t pos = 0;
  while (pos < text.size())
  {
    size_t end = text.find('\n', pos);
    if (end == std::string_view::npos)
      end = text.size();
    std::string_view line = text.substr(pos, end - pos);
    pos = end + 1;
    if (!line.empty() && line.back() == '\r')
      line.remove_suffix(1);

    const size_t first = line.find('\t');
    const size_t second = (first == std::string_view::npos) ? first : line.find('\t', first + 1);
    if (second == std::string_view::npos || line.find('\t', second + 1) != std::string_view::npos)
      continue;

    const std::string_view state = line.substr(0, first);
    const std::string_view name = line.substr(second + 1);
    if ((state != "on" && state != "off") || name.empty())
      continue;

    switches.push_back(Switch{std::string(line.substr(first + 1, second - first - 1)), std::string(name), state == "on"});
  }

  return switches;
}

std::string Format(std::span<const Switch> switches)
{
  const auto writable = [](std::string_view text) { return text.find_first_of("\t\r\n") == std::string_view::npos; };

  std::string text(HEADER);
  for (const Switch& sw : switches)
  {
    if (sw.name.empty() || !writable(sw.group) || !writable(sw.name))
      continue;

    text.append(sw.on ? "on" : "off").append("\t").append(sw.group).append("\t").append(sw.name).append("\n");
  }

  return text;
}

std::string SafeFileName(std::string_view name)
{
  std::string result(name);
  for (char& c : result)
  {
    if (static_cast<unsigned char>(c) < 0x20 || std::string_view("<>:\"/\\|?*").find(c) != std::string_view::npos)
      c = '_';
  }

  return result;
}

std::string FileName(std::string_view key)
{
  return SafeFileName(key) + ".patchcodes";
}

std::vector<Entry> Combine(std::span<const Listed> player, std::span<const Listed> database,
                           std::span<const Switch> switches)
{
  std::vector<Entry> entries;
  std::set<std::pair<std::string, std::string>> seen;
  const auto add = [&](std::span<const Listed> codes, bool from_player) {
    for (size_t i = 0; i < codes.size(); i++)
    {
      const Listed& code = codes[i];
      if (!seen.emplace(code.group, code.name).second)
        continue;

      // Only a player's file can mark a code on; the database's start off.
      bool on = from_player && code.marked_on;
      const auto sw = std::find_if(switches.begin(), switches.end(), [&code](const Switch& s) {
        return s.group == code.group && s.name == code.name;
      });
      if (sw != switches.end())
        on = sw->on;
      entries.push_back(Entry{from_player, i, on && !code.manual});
    }
  };

  add(player, true);
  add(database, false);
  return entries;
}

std::vector<Switch> Remember(std::span<const Switch> old, std::span<const State> codes)
{
  std::vector<Switch> switches;
  for (const Switch& sw : old)
  {
    const bool listed = std::any_of(codes.begin(), codes.end(), [&sw](const State& code) {
      return code.group == sw.group && code.name == sw.name;
    });
    if (!listed)
      switches.push_back(sw);
  }

  for (const State& code : codes)
  {
    if (code.on != code.default_on)
      switches.push_back(Switch{code.group, code.name, code.on});
  }

  return switches;
}

} // namespace AndroidPatchCodes
