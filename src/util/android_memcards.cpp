// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "android_memcards.h"

#include "common/string_util.h"

std::optional<std::string> AndroidMemcards::PlaylistCardTitle(std::string_view playlist_title,
                                                              std::string_view database_title,
                                                              bool database_card_exists, bool use_playlist_title)
{
  if (database_title.empty() || playlist_title == database_title || !use_playlist_title || database_card_exists)
    return std::nullopt;
  return std::string(playlist_title);
}

std::string AndroidMemcards::OtherCardName(std::string_view file_name)
{
  std::string_view name = file_name;
  if (name.size() > 4 && StringUtil::EqualNoCase(name.substr(name.size() - 4), ".mcd"))
    name.remove_suffix(4);
  if (name.size() > 2 && name.ends_with("_1"))
    name.remove_suffix(2);
  return std::string(name);
}
