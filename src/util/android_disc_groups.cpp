// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "android_disc_groups.h"

#include "common/path.h"
#include "common/string_util.h"

#include <algorithm>
#include <map>
#include <utility>

namespace AndroidDiscGroups {

static bool IsDigit(char c)
{
  return (c >= '0' && c <= '9');
}

static bool IsAlnum(char c)
{
  return IsDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
}

static bool IsSpace(char c)
{
  return (c == ' ' || c == '\t' || c == '\r' || c == '\n');
}

static char Lower(char c)
{
  return (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c;
}

static std::string LowerCase(std::string_view text)
{
  std::string result(text);
  std::transform(result.begin(), result.end(), result.begin(), Lower);
  return result;
}

static bool StartsWithNoCase(std::string_view text, size_t pos, std::string_view word)
{
  return (text.size() - pos >= word.size()) && StringUtil::EqualNoCase(text.substr(pos, word.size()), word);
}

// Runs of spaces become one, and the ends are trimmed.
static std::string CollapseSpaces(std::string_view text)
{
  std::string result;
  for (const char c : text)
  {
    if (IsSpace(c))
    {
      if (!result.empty() && result.back() != ' ')
        result.push_back(' ');
    }
    else
    {
      result.push_back(c);
    }
  }

  if (!result.empty() && result.back() == ' ')
    result.pop_back();
  return result;
}

// Reads a number of one or two digits at pos, not followed by another digit: its value and its end.
static std::optional<std::pair<u32, size_t>> ReadNumber(std::string_view text, size_t pos)
{
  size_t end = pos;
  while (end < text.size() && IsDigit(text[end]) && end - pos < 3)
    end++;
  if (end == pos || end - pos > 2)
    return std::nullopt;

  u32 value = 0;
  for (size_t i = pos; i < end; i++)
    value = value * 10 + static_cast<u32>(text[i] - '0');
  return std::make_pair(value, end);
}

// The marker whose word starts at pos: the range it takes up in the title, and its number.
static std::optional<std::pair<std::pair<size_t, size_t>, u32>> MarkerAt(std::string_view text, size_t pos)
{
  size_t after;
  if (StartsWithNoCase(text, pos, "disc"))
    after = pos + 4;
  else if (StartsWithNoCase(text, pos, "cd"))
    after = pos + 2;
  else
    return std::nullopt;

  // What comes before: a bracket, or for the bare form, the start or a separator.
  size_t open = pos;
  while (open > 0 && text[open - 1] == ' ')
    open--;
  const bool bracketed = (open > 0 && (text[open - 1] == '(' || text[open - 1] == '['));
  if (!bracketed && pos > 0 && text[pos - 1] != ' ' && text[pos - 1] != '-' && text[pos - 1] != '_')
    return std::nullopt;

  if (after < text.size() && (text[after] == ' ' || text[after] == '_'))
    after++;
  const auto number = ReadNumber(text, after);
  if (!number.has_value() || number->first == 0)
    return std::nullopt;
  size_t end = number->second;

  // " of M"
  size_t of = end;
  while (of < text.size() && text[of] == ' ')
    of++;
  if (of > end && StartsWithNoCase(text, of, "of"))
  {
    size_t total = of + 2;
    const size_t spaces = total;
    while (total < text.size() && text[total] == ' ')
      total++;
    if (total > spaces)
    {
      if (const auto count = ReadNumber(text, total); count.has_value())
        end = count->second;
    }
  }

  if (bracketed)
  {
    const char close = (text[open - 1] == '(') ? ')' : ']';
    while (end < text.size() && text[end] == ' ')
      end++;
    if (end >= text.size() || text[end] != close)
      return std::nullopt;
    return std::make_pair(std::make_pair(open - 1, end + 1), number->first);
  }

  if (end < text.size() && IsAlnum(text[end]))
    return std::nullopt;

  // The separators before a bare marker go with it.
  size_t start = pos;
  while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '-' || text[start - 1] == '_'))
    start--;
  return std::make_pair(std::make_pair(start, end), number->first);
}

std::optional<DiscMarker> ParseDiscMarker(std::string_view file_title)
{
  for (size_t pos = file_title.size(); pos-- > 0;)
  {
    const auto marker = MarkerAt(file_title, pos);
    if (!marker.has_value())
      continue;

    const auto [start, end] = marker->first;
    std::string rest(file_title.substr(0, start));
    rest.push_back(' ');
    rest.append(file_title.substr(end));
    return DiscMarker{CollapseSpaces(rest), marker->second};
  }

  return std::nullopt;
}

std::string StripDiscMarker(std::string_view title)
{
  const std::optional<DiscMarker> marker = ParseDiscMarker(title);
  return marker.has_value() ? marker->base : std::string(title);
}

std::vector<std::string> ParsePlaylist(std::string_view m3u_path, std::string_view text)
{
  std::vector<std::string> entries;
  size_t pos = 0;
  while (pos <= text.size())
  {
    size_t end = text.find('\n', pos);
    if (end == std::string_view::npos)
      end = text.size();

    std::string_view line = text.substr(pos, end - pos);
    while (!line.empty() && IsSpace(line.front()))
      line.remove_prefix(1);
    while (!line.empty() && IsSpace(line.back()))
      line.remove_suffix(1);

    if (!line.empty() && line.front() != '#')
    {
      std::string entry = Path::ToNativePath(line);
      entries.push_back(Path::IsAbsolute(entry) ? std::move(entry) : Path::BuildRelativePath(m3u_path, entry));
    }

    pos = end + 1;
  }

  return entries;
}

// A path's file name, whichever separator the system that wrote it used.
static std::string_view FileName(std::string_view path)
{
  const size_t pos = path.find_last_of("/\\");
  return (pos == std::string_view::npos) ? path : path.substr(pos + 1);
}

std::vector<Game> GroupFiles(std::span<const ListedFile> files)
{
  std::vector<std::pair<size_t, Game>> games;
  std::vector<bool> used(files.size(), false);

  // Playlists, each taking the listed discs it names.
  for (size_t i = 0; i < files.size(); i++)
  {
    const ListedFile& playlist = files[i];
    if (playlist.type != FileType::Playlist)
      continue;

    Game game;
    game.key = playlist.serial;
    game.title = StripDiscMarker(playlist.title);
    game.path = playlist.path;
    game.playlist = true;
    game.files.push_back(i);
    used[i] = true;
    for (const std::string& entry : playlist.playlist_paths)
    {
      Disc disc{entry, std::string(), std::string(Path::GetFileTitle(entry)),
                static_cast<u32>(game.discs.size() + 1)};
      for (size_t j = 0; j < files.size(); j++)
      {
        if (files[j].type != FileType::Disc || files[j].path != entry)
          continue;

        disc.serial = files[j].serial;
        disc.title = files[j].title;
        if (!used[j])
        {
          used[j] = true;
          game.files.push_back(j);
        }
        break;
      }
      game.discs.push_back(std::move(disc));
    }
    games.emplace_back(i, std::move(game));
  }

  // Database sets, by the core's rule: disc 1 and another disc; a second file for one disc stays apart.
  std::map<std::string, std::vector<size_t>> sets;
  for (size_t i = 0; i < files.size(); i++)
  {
    if (!used[i] && files[i].type == FileType::Disc && !files[i].disc_set_name.empty() && files[i].disc_set_index >= 0)
      sets[files[i].disc_set_name].push_back(i);
  }
  for (const auto& [name, members] : sets)
  {
    std::map<s32, size_t> by_index;
    for (const size_t i : members)
      by_index.emplace(files[i].disc_set_index, i);
    if (by_index.size() < 2 || !by_index.contains(0))
      continue;

    Game game;
    game.key = files[by_index.at(0)].serial;
    game.title = name;
    game.path = files[by_index.at(0)].path;
    for (const auto& [index, i] : by_index)
    {
      game.discs.push_back(Disc{files[i].path, files[i].serial, files[i].title, static_cast<u32>(index + 1)});
      game.files.push_back(i);
      used[i] = true;
    }
    std::sort(game.files.begin(), game.files.end());
    games.emplace_back(game.files.front(), std::move(game));
  }

  // Discs the database doesn't know, by name: one folder, one base name ignoring case, no number twice.
  std::map<std::pair<std::string, std::string>, std::vector<std::pair<size_t, DiscMarker>>> names;
  for (size_t i = 0; i < files.size(); i++)
  {
    const ListedFile& file = files[i];
    if (used[i] || file.type != FileType::Disc || file.known)
      continue;

    std::optional<DiscMarker> marker = ParseDiscMarker(Path::GetFileTitle(file.path));
    if (marker.has_value())
    {
      names[{std::string(Path::GetDirectory(file.path)), LowerCase(marker->base)}].emplace_back(i,
                                                                                              std::move(*marker));
    }
  }
  for (auto& [where, members] : names)
  {
    std::sort(members.begin(), members.end(),
              [](const auto& a, const auto& b) { return a.second.number < b.second.number; });
    const bool repeats = std::adjacent_find(members.begin(), members.end(), [](const auto& a, const auto& b) {
                           return a.second.number == b.second.number;
                         }) != members.end();
    if (members.size() < 2 || repeats)
      continue;

    Game game;
    const ListedFile& lowest = files[members.front().first];
    game.key = lowest.serial;
    game.title = members.front().second.base;
    game.path = lowest.path;
    for (const auto& [i, marker] : members)
    {
      game.discs.push_back(Disc{files[i].path, files[i].serial, files[i].title, marker.number});
      game.files.push_back(i);
      used[i] = true;
    }
    std::sort(game.files.begin(), game.files.end());
    games.emplace_back(game.files.front(), std::move(game));
  }

  // Everything else, on its own.
  for (size_t i = 0; i < files.size(); i++)
  {
    if (used[i])
      continue;

    const ListedFile& file = files[i];
    Game game;
    game.key = file.serial;
    game.title = file.title;
    game.path = file.path;
    if (file.type == FileType::Disc)
      game.discs.push_back(Disc{file.path, file.serial, file.title, 1});
    game.files.push_back(i);
    games.emplace_back(i, std::move(game));
  }

  std::stable_sort(games.begin(), games.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
  std::vector<Game> result;
  result.reserve(games.size());
  for (auto& [first, game] : games)
    result.push_back(std::move(game));
  return result;
}

std::optional<u32> FindStateDisc(const Game& game, std::string_view serial, std::string_view media_path,
                                 u32 subimage_index)
{
  const auto has = [&game](u32 number) {
    return std::any_of(game.discs.begin(), game.discs.end(), [number](const Disc& d) { return d.number == number; });
  };

  const std::string_view file_name = FileName(media_path);
  if (StringUtil::EndsWithNoCase(file_name, ".m3u"))
  {
    if (has(subimage_index + 1))
      return subimage_index + 1;
    return std::nullopt;
  }

  if (!serial.empty())
  {
    const Disc* found = nullptr;
    u32 count = 0;
    for (const Disc& disc : game.discs)
    {
      if (disc.serial == serial)
      {
        found = &disc;
        count++;
      }
    }
    if (count == 1)
      return found->number;
  }

  for (const Disc& disc : game.discs)
  {
    if (!file_name.empty() && StringUtil::EqualNoCase(FileName(disc.path), file_name))
      return disc.number;
  }

  return std::nullopt;
}

std::optional<u32> MatchStateGame(const Game& game, std::string_view serial, std::string_view title,
                                  std::string_view media_path, u32 subimage_index)
{
  const std::string_view file_name = FileName(media_path);
  const auto disc = [&]() {
    return FindStateDisc(game, serial, media_path, subimage_index).value_or(0);
  };

  // A name (a playlist's or a disc's) only when no serial says otherwise: the state records none, or the game has none
  // known. Another game's file can share a name ("game.m3u", "disc1.chd" in a folder per game).
  const bool known_serial =
    !game.key.empty() ||
    std::any_of(game.discs.begin(), game.discs.end(), [](const Disc& d) { return !d.serial.empty(); });
  const bool by_name = !file_name.empty() && (serial.empty() || !known_serial);

  if (by_name && game.playlist && StringUtil::EndsWithNoCase(file_name, ".m3u") &&
      StringUtil::EqualNoCase(FileName(game.path), file_name))
  {
    return disc();
  }

  if (!serial.empty() &&
      (serial == game.key ||
       std::any_of(game.discs.begin(), game.discs.end(), [serial](const Disc& d) { return d.serial == serial; })))
  {
    return disc();
  }

  if (by_name)
  {
    if (StringUtil::EqualNoCase(FileName(game.path), file_name))
      return disc();
    for (const Disc& d : game.discs)
    {
      if (StringUtil::EqualNoCase(FileName(d.path), file_name))
        return d.number;
    }
  }

  // A PSF's state records no serial and no media: only its title, which the core takes from its file's title. Two
  // PSFs differ by it. The state's header keeps 127 bytes of it (SAVE_STATE_HEADER::title, 128 with its terminator).
  constexpr size_t STATE_TITLE_BYTES = 127;
  if (serial.empty() && media_path.empty() && game.key.empty() && game.discs.empty() &&
      title == Path::GetFileTitle(game.path).substr(0, STATE_TITLE_BYTES))
  {
    return 0u;
  }

  return std::nullopt;
}

} // namespace AndroidDiscGroups
