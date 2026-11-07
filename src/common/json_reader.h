// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "types.h"

#include <optional>
#include <string>
#include <string_view>
#include <vector>

/// A strict little JSON reader. The core is built without exceptions and rapidyaml's errors abort, so damaged or
/// hostile text (a file on disk, a server's answer) is read with this, which only ever fails.
namespace JsonReader {

struct Value
{
  enum class Type : u8
  {
    Null,
    Bool,
    Number,
    String,
    Array,
    Object,
  };

  Type type = Type::Null;
  bool boolean = false;
  /// The number's whole part (a fraction or an exponent is accepted, and applied before truncating).
  s64 number = 0;
  std::string string;
  std::vector<Value> items;
  // An object's members, as two lists: libstdc++ won't hold a pair of a type still being defined.
  std::vector<std::string> names;
  std::vector<Value> values;

  const Value* Find(std::string_view key) const;
  std::optional<s64> NumberAt(std::string_view key) const;
  std::optional<std::string> StringAt(std::string_view key) const;
  std::optional<bool> BoolAt(std::string_view key) const;
};

/// The whole text as one value, nested at most 16 deep; nullopt if it isn't JSON.
std::optional<Value> Parse(std::string_view text);

/// Appends value as a quoted JSON string.
void AppendString(std::string* out, std::string_view value);

} // namespace JsonReader
