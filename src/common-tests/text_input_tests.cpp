// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/text_input.h"
#include <gtest/gtest.h>

TEST(TextInput, FirstLine)
{
  ASSERT_EQ(TextInput::GetFirstLine("abc"), "abc");
  ASSERT_EQ(TextInput::GetFirstLine("abc\n"), "abc");
  ASSERT_EQ(TextInput::GetFirstLine("abc\r\ndef"), "abc");
  ASSERT_EQ(TextInput::GetFirstLine("\r\n\nkey\n"), "key");
  ASSERT_EQ(TextInput::GetFirstLine("  spaced  \n"), "  spaced  ");
  ASSERT_EQ(TextInput::GetFirstLine(""), "");
  ASSERT_EQ(TextInput::GetFirstLine("\n\n"), "");
}

TEST(TextInput, SteamKeyboardEnabled)
{
  ASSERT_FALSE(TextInput::IsSteamKeyboardEnabled(nullptr, nullptr));
  ASSERT_TRUE(TextInput::IsSteamKeyboardEnabled("1", nullptr));
  ASSERT_TRUE(TextInput::IsSteamKeyboardEnabled(nullptr, "1"));
  ASSERT_FALSE(TextInput::IsSteamKeyboardEnabled("0", "1")); // SDL's variable wins
  ASSERT_TRUE(TextInput::IsSteamKeyboardEnabled("", "1"));   // empty means unset
  ASSERT_FALSE(TextInput::IsSteamKeyboardEnabled(nullptr, "0"));
  ASSERT_FALSE(TextInput::IsSteamKeyboardEnabled("false", nullptr));
  ASSERT_FALSE(TextInput::IsSteamKeyboardEnabled("FALSE", nullptr));
  ASSERT_FALSE(TextInput::IsSteamKeyboardEnabled("", ""));
}

TEST(TextInput, SteamKeyboardURL)
{
  ASSERT_EQ(TextInput::GetSteamKeyboardShowURL({10, 20, 300, 40, false, false}),
            "steam://open/keyboard?XPosition=10&YPosition=20&Width=300&Height=40&Mode=0");
  ASSERT_EQ(TextInput::GetSteamKeyboardShowURL({0, 0, 1280, 30, true, false}),
            "steam://open/keyboard?XPosition=0&YPosition=0&Width=1280&Height=30&Mode=3");
}
