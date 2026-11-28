// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "patches.h"
#include "host.h"

#include "common/error.h"
#include "common/log.h"
#include "common/string_util.h"

#include "fmt/format.h"

#include <charconv>

Log_SetChannel(Patches);

namespace Patches {

// The core's group for codes without one.
static constexpr std::string_view UNGROUPED = "Ungrouped";

static std::optional<u32> ParseUnsigned(std::string_view str)
{
  u32 value = 0;
  const auto [ptr, ec] = std::from_chars(str.data(), str.data() + str.size(), value);
  if (ec != std::errc() || ptr != str.data() + str.size())
    return std::nullopt;
  return value;
}

// `N:M`, both above zero and fitting u16.
static bool ParseRatio(std::string_view str, u16* numerator, u16* denominator)
{
  const size_t colon = str.find(':');
  if (colon == std::string_view::npos)
    return false;
  const std::optional<u32> n = ParseUnsigned(StringUtil::StripWhitespace(str.substr(0, colon)));
  const std::optional<u32> d = ParseUnsigned(StringUtil::StripWhitespace(str.substr(colon + 1)));
  if (!n.has_value() || !d.has_value() || n.value() == 0 || d.value() == 0 || n.value() > 0xFFFF ||
      d.value() > 0xFFFF)
  {
    return false;
  }
  *numerator = static_cast<u16>(n.value());
  *denominator = static_cast<u16>(d.value());
  return true;
}

std::string CodeKey(std::string_view group, std::string_view name)
{
  if (group.empty() || group == UNGROUPED)
    return std::string(name);
  return fmt::format("{}\\{}", group, name);
}

static std::string MapKey(std::string_view serial, std::string_view code_key)
{
  return fmt::format("{}\t{}", serial, code_key);
}

// A line's third field: the kind, then a ratio, `overclock=N` and `choice=<title>` (the rest of the line).
static std::optional<Mark> ParseMarkField(std::string_view field)
{
  field = StringUtil::StripWhitespace(field);
  const size_t space = field.find(' ');
  const std::string_view kind = field.substr(0, space);
  std::string_view rest = (space == std::string_view::npos) ? std::string_view() : field.substr(space + 1);

  Mark mark;
  if (kind == "widescreen")
  {
    mark.kind = Kind::Widescreen;
    mark.needs.disable_widescreen_rendering = true;
    mark.needs.aspect_numerator = 16;
    mark.needs.aspect_denominator = 9;
  }
  else if (kind == "framerate")
  {
    mark.kind = Kind::FrameRate;
  }
  else if (kind == "displaymode")
  {
    mark.kind = Kind::DisplayMode;
  }
  else
  {
    return std::nullopt;
  }

  while (!(rest = StringUtil::StripWhitespace(rest)).empty())
  {
    if (rest.starts_with("choice="))
    {
      mark.choice = std::string(StringUtil::StripWhitespace(rest.substr(7)));
      if (mark.choice.empty())
        return std::nullopt;
      break;
    }

    const size_t next = rest.find(' ');
    const std::string_view token = rest.substr(0, next);
    rest = (next == std::string_view::npos) ? std::string_view() : rest.substr(next + 1);
    if (token.starts_with("overclock="))
    {
      const std::optional<u32> percent = ParseUnsigned(token.substr(10));
      if (!percent.has_value() || percent.value() < 10 || percent.value() > 1000)
        return std::nullopt;
      mark.needs.overclock_percent = percent.value();
    }
    else if (mark.kind == Kind::Widescreen &&
             ParseRatio(token, &mark.needs.aspect_numerator, &mark.needs.aspect_denominator))
    {
    }
    else
    {
      return std::nullopt;
    }
  }

  mark.needs.may_need_overclock = (mark.kind == Kind::FrameRate && mark.needs.overclock_percent == 0);
  return mark;
}

Marks Marks::Parse(std::string_view text, Error* error)
{
  Marks marks;
  bool reported = false;
  u32 line_number = 0;
  while (!text.empty())
  {
    const size_t eol = text.find('\n');
    std::string_view line = text.substr(0, eol);
    text = (eol == std::string_view::npos) ? std::string_view() : text.substr(eol + 1);
    line_number++;

    if (!line.empty() && line.back() == '\r')
      line.remove_suffix(1);
    if (StringUtil::StripWhitespace(line).empty() || line.front() == ';')
      continue;

    const size_t tab1 = line.find('\t');
    const size_t tab2 = (tab1 == std::string_view::npos) ? tab1 : line.find('\t', tab1 + 1);
    std::optional<Mark> mark;
    if (tab2 != std::string_view::npos && tab1 > 0 && tab2 > tab1 + 1)
      mark = ParseMarkField(line.substr(tab2 + 1));
    if (!mark.has_value())
    {
      if (!reported)
      {
        Error::SetStringFmt(error, "chtdb_patches.txt line {} can't be read: '{}'", line_number, line);
        reported = true;
      }
      WARNING_LOG("Skipping patch mark line {}: '{}'", line_number, line);
      continue;
    }

    marks.m_marks.insert_or_assign(MapKey(line.substr(0, tab1), line.substr(tab1 + 1, tab2 - tab1 - 1)),
                                   std::move(mark.value()));
  }

  return marks;
}

const Mark* Marks::Find(std::string_view serial, std::string_view group, std::string_view name) const
{
  const auto it = m_marks.find(MapKey(serial, CodeKey(group, name)));
  return (it != m_marks.end()) ? &it->second : nullptr;
}

const Marks& BundledMarks()
{
  static const Marks s_marks = []() {
    const std::optional<std::string> text = Host::ReadResourceFileToString("chtdb_patches.txt", false);
    if (!text.has_value())
    {
      WARNING_LOG("chtdb_patches.txt is missing: no database code is a patch.");
      return Marks();
    }

    Error error;
    Marks marks = Marks::Parse(text.value(), &error);
    if (error.IsValid())
      ERROR_LOG("{}", error.GetDescription());
    INFO_LOG("{} patch marks.", marks.Count());
    return marks;
  }();
  return s_marks;
}

Needs NeedsFromOverrides(const CheatCode& code)
{
  Needs needs;
  for (const auto& [key, value] : code.overrides)
  {
    if (StringUtil::EqualNoCase(key, "OverrideAspectRatio"))
    {
      if (!ParseRatio(value, &needs.aspect_numerator, &needs.aspect_denominator))
        needs.aspect_numerator = needs.aspect_denominator = 0;
    }
    else if (StringUtil::EqualNoCase(key, "OverrideCPUOverclock"))
    {
      const std::optional<u32> percent = ParseUnsigned(StringUtil::StripWhitespace(value));
      needs.overclock_percent = (percent.has_value() && percent.value() >= 10 && percent.value() <= 1000) ?
                                  percent.value() :
                                  0;
    }
    else if (StringUtil::EqualNoCase(key, "DisableWidescreenRendering"))
    {
      needs.disable_widescreen_rendering = StringUtil::EqualNoCase(StringUtil::StripWhitespace(value), "true");
    }
  }
  return needs;
}

std::optional<Mark> Classify(std::string_view serial, CheatCode& code, const Marks& marks)
{
  if (const Mark* mark = marks.Find(serial, code.group, code.description))
  {
    code.patch = true;
    return *mark;
  }

  if (!code.patch)
    return std::nullopt;

  Mark mark;
  mark.needs = NeedsFromOverrides(code);
  if (mark.needs.aspect_numerator != 0)
    mark.kind = Kind::Widescreen;
  else if (mark.needs.overclock_percent != 0)
    mark.kind = Kind::FrameRate;
  return mark;
}

} // namespace Patches
