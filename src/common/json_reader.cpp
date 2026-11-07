// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "json_reader.h"
#include "string_util.h"

#include "fmt/format.h"

#include <cmath>

namespace JsonReader {

const Value* Value::Find(std::string_view key) const
{
  if (type != Type::Object)
    return nullptr;
  for (size_t i = 0; i < names.size(); i++)
  {
    if (names[i] == key)
      return &values[i];
  }
  return nullptr;
}

std::optional<s64> Value::NumberAt(std::string_view key) const
{
  const Value* value = Find(key);
  return (value && value->type == Type::Number) ? std::optional<s64>(value->number) : std::nullopt;
}

std::optional<std::string> Value::StringAt(std::string_view key) const
{
  const Value* value = Find(key);
  return (value && value->type == Type::String) ? std::optional<std::string>(value->string) : std::nullopt;
}

std::optional<bool> Value::BoolAt(std::string_view key) const
{
  const Value* value = Find(key);
  return (value && value->type == Type::Bool) ? std::optional<bool>(value->boolean) : std::nullopt;
}

namespace {

class Parser
{
public:
  explicit Parser(std::string_view text) : m_text(text) {}

  std::optional<Value> Parse()
  {
    Value value;
    if (!ParseValue(&value, 0))
      return std::nullopt;
    SkipSpace();
    if (m_pos != m_text.size())
      return std::nullopt;
    return value;
  }

private:
  static constexpr u32 MAX_DEPTH = 16;

  void SkipSpace()
  {
    while (m_pos < m_text.size() &&
           (m_text[m_pos] == ' ' || m_text[m_pos] == '\t' || m_text[m_pos] == '\n' || m_text[m_pos] == '\r'))
    {
      m_pos++;
    }
  }

  bool Consume(char c)
  {
    SkipSpace();
    if (m_pos < m_text.size() && m_text[m_pos] == c)
    {
      m_pos++;
      return true;
    }
    return false;
  }

  bool ConsumeWord(std::string_view word)
  {
    if (m_text.substr(m_pos, word.size()) != word)
      return false;
    m_pos += word.size();
    return true;
  }

  bool ParseValue(Value* value, u32 depth)
  {
    if (depth > MAX_DEPTH)
      return false;

    SkipSpace();
    if (m_pos >= m_text.size())
      return false;

    const char c = m_text[m_pos];
    if (c == '{')
      return ParseObject(value, depth);
    if (c == '[')
      return ParseArray(value, depth);
    if (c == '"')
    {
      value->type = Value::Type::String;
      return ParseString(&value->string);
    }
    if (c == '-' || (c >= '0' && c <= '9'))
      return ParseNumber(value);
    if (ConsumeWord("true") || ConsumeWord("false"))
    {
      value->type = Value::Type::Bool;
      value->boolean = (c == 't');
      return true;
    }
    if (ConsumeWord("null"))
    {
      value->type = Value::Type::Null;
      return true;
    }
    return false;
  }

  bool ParseObject(Value* value, u32 depth)
  {
    value->type = Value::Type::Object;
    m_pos++;
    if (Consume('}'))
      return true;

    do
    {
      SkipSpace();
      std::string key;
      if (m_pos >= m_text.size() || m_text[m_pos] != '"' || !ParseString(&key) || !Consume(':'))
        return false;
      Value member;
      if (!ParseValue(&member, depth + 1))
        return false;
      value->names.push_back(std::move(key));
      value->values.push_back(std::move(member));
    } while (Consume(','));

    return Consume('}');
  }

  bool ParseArray(Value* value, u32 depth)
  {
    value->type = Value::Type::Array;
    m_pos++;
    if (Consume(']'))
      return true;

    do
    {
      Value item;
      if (!ParseValue(&item, depth + 1))
        return false;
      value->items.push_back(std::move(item));
    } while (Consume(','));

    return Consume(']');
  }

  bool SkipDigits()
  {
    const size_t start = m_pos;
    while (m_pos < m_text.size() && m_text[m_pos] >= '0' && m_text[m_pos] <= '9')
      m_pos++;
    return m_pos != start;
  }

  bool ParseNumber(Value* value)
  {
    const size_t start = m_pos;
    if (m_text[m_pos] == '-')
      m_pos++;
    if (!SkipDigits())
      return false;

    bool whole = true;
    if (m_pos < m_text.size() && m_text[m_pos] == '.')
    {
      m_pos++;
      if (!SkipDigits())
        return false;
      whole = false;
    }
    if (m_pos < m_text.size() && (m_text[m_pos] == 'e' || m_text[m_pos] == 'E'))
    {
      m_pos++;
      if (m_pos < m_text.size() && (m_text[m_pos] == '+' || m_text[m_pos] == '-'))
        m_pos++;
      if (!SkipDigits())
        return false;
      whole = false;
    }

    const std::string_view text = m_text.substr(start, m_pos - start);
    value->type = Value::Type::Number;
    if (whole)
    {
      const std::optional<s64> number = StringUtil::FromChars<s64>(text);
      if (!number.has_value())
        return false;
      value->number = number.value();
      return true;
    }

    const std::optional<double> number = StringUtil::FromChars<double>(text);
    if (!number.has_value() || !std::isfinite(number.value()) || std::fabs(number.value()) >= 9.2e18)
      return false;
    value->number = static_cast<s64>(number.value());
    return true;
  }

  bool ParseHex4(u32* code)
  {
    if (m_pos + 4 > m_text.size())
      return false;
    const std::optional<u32> value = StringUtil::FromChars<u32>(m_text.substr(m_pos, 4), 16);
    if (!value.has_value())
      return false;
    *code = value.value();
    m_pos += 4;
    return true;
  }

  bool ParseString(std::string* out)
  {
    m_pos++; // the opening quote
    while (m_pos < m_text.size())
    {
      const char c = m_text[m_pos++];
      if (c == '"')
        return true;
      if (static_cast<unsigned char>(c) < 0x20)
        return false;
      if (c != '\\')
      {
        out->push_back(c);
        continue;
      }

      if (m_pos >= m_text.size())
        return false;
      const char escape = m_text[m_pos++];
      switch (escape)
      {
        case '"':
        case '\\':
        case '/':
          out->push_back(escape);
          break;
        case 'b':
          out->push_back('\b');
          break;
        case 'f':
          out->push_back('\f');
          break;
        case 'n':
          out->push_back('\n');
          break;
        case 'r':
          out->push_back('\r');
          break;
        case 't':
          out->push_back('\t');
          break;
        case 'u':
        {
          u32 code;
          if (!ParseHex4(&code))
            return false;
          if (code >= 0xD800 && code < 0xDC00)
          {
            u32 low;
            if (!ConsumeWord("\\u") || !ParseHex4(&low) || low < 0xDC00 || low >= 0xE000)
              return false;
            code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00);
          }
          else if (code >= 0xDC00 && code < 0xE000)
          {
            return false;
          }
          StringUtil::EncodeAndAppendUTF8(*out, static_cast<char32_t>(code));
          break;
        }
        default:
          return false;
      }
    }
    return false;
  }

  std::string_view m_text;
  size_t m_pos = 0;
};

} // namespace

std::optional<Value> Parse(std::string_view text)
{
  return Parser(text).Parse();
}

void AppendString(std::string* out, std::string_view value)
{
  out->push_back('"');
  for (const char c : value)
  {
    switch (c)
    {
      case '"':
        out->append("\\\"");
        break;
      case '\\':
        out->append("\\\\");
        break;
      case '\n':
        out->append("\\n");
        break;
      case '\r':
        out->append("\\r");
        break;
      case '\t':
        out->append("\\t");
        break;
      default:
        if (static_cast<unsigned char>(c) < 0x20)
          out->append(fmt::format("\\u{:04x}", static_cast<unsigned>(c)));
        else
          out->push_back(c);
        break;
    }
  }
  out->push_back('"');
}

} // namespace JsonReader
