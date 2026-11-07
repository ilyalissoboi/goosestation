// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "common/json_reader.h"

#include <gtest/gtest.h>

#include <string>

using JsonReader::Value;

TEST(JsonReader, ParsesObjectsArraysStringsBoolsAndNull)
{
  const std::optional<Value> v = JsonReader::Parse(R"( {"s":"text","n":42,"t":true,"f":false,"z":null,"a":[1,"two",[]]} )");
  ASSERT_TRUE(v.has_value());
  ASSERT_EQ(v->type, Value::Type::Object);
  EXPECT_EQ(v->StringAt("s"), "text");
  EXPECT_EQ(v->NumberAt("n"), 42);
  EXPECT_EQ(v->BoolAt("t"), true);
  EXPECT_EQ(v->BoolAt("f"), false);
  ASSERT_NE(v->Find("z"), nullptr);
  EXPECT_EQ(v->Find("z")->type, Value::Type::Null);
  const Value* a = v->Find("a");
  ASSERT_NE(a, nullptr);
  ASSERT_EQ(a->type, Value::Type::Array);
  ASSERT_EQ(a->items.size(), 3u);
  EXPECT_EQ(a->items[0].number, 1);
  EXPECT_EQ(a->items[1].string, "two");
  EXPECT_EQ(a->items[2].type, Value::Type::Array);
  EXPECT_EQ(v->Find("missing"), nullptr);
}

TEST(JsonReader, UnicodeEscapesBecomeUtf8)
{
  const std::optional<Value> v = JsonReader::Parse(R"({"e":"\u00e9","g":"\ud83e\udebf"})");
  ASSERT_TRUE(v.has_value());
  EXPECT_EQ(v->StringAt("e"), "\xc3\xa9");
  EXPECT_EQ(v->StringAt("g"), "\xf0\x9f\xaa\xbf");
}

TEST(JsonReader, FractionsAndExponentsAreAccepted)
{
  const std::optional<Value> v = JsonReader::Parse(R"({"a":1.5,"b":-2e3,"c":0.25E+1,"d":7})");
  ASSERT_TRUE(v.has_value());
  EXPECT_EQ(v->NumberAt("a"), 1);
  EXPECT_EQ(v->NumberAt("b"), -2000);
  EXPECT_EQ(v->NumberAt("c"), 2);
  EXPECT_EQ(v->NumberAt("d"), 7);
  EXPECT_FALSE(JsonReader::Parse("1.").has_value());
  EXPECT_FALSE(JsonReader::Parse("1e").has_value());
  EXPECT_FALSE(JsonReader::Parse("-").has_value());
}

TEST(JsonReader, RejectsTrailingGarbageAndDeepNesting)
{
  EXPECT_FALSE(JsonReader::Parse("{} x").has_value());
  EXPECT_FALSE(JsonReader::Parse(R"({"a":})").has_value());
  EXPECT_FALSE(JsonReader::Parse("").has_value());
  EXPECT_TRUE(JsonReader::Parse(std::string(16, '[') + std::string(16, ']')).has_value());
  EXPECT_FALSE(JsonReader::Parse(std::string(18, '[') + std::string(18, ']')).has_value());
}

TEST(JsonReader, AppendStringEscapesQuotesBackslashesAndControls)
{
  std::string out;
  JsonReader::AppendString(&out, "a\"b\\c\nd\x01");
  EXPECT_EQ(out, R"("a\"b\\c\nd\u0001")");

  const std::string original = "shaders_slang/retro crisis/4K Flat/\"x\"\t\\y.slangp";
  std::string json = "{\"v\":";
  JsonReader::AppendString(&json, original);
  json += "}";
  const std::optional<Value> v = JsonReader::Parse(json);
  ASSERT_TRUE(v.has_value());
  EXPECT_EQ(v->StringAt("v"), original);
}
