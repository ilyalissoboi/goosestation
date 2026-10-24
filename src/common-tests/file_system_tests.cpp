// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#include "common/error.h"
#include "common/file_system.h"
#include "common/path.h"
#include "common/string_util.h"

#include "fmt/format.h"

#include <gtest/gtest.h>

#ifdef _WIN32

#include "common/windows_headers.h"

TEST(FileSystem, GetWin32Path)
{
  ASSERT_EQ(FileSystem::GetWin32Path("test.txt"), L"test.txt");
  ASSERT_EQ(FileSystem::GetWin32Path("D:\\test.txt"), L"\\\\?\\D:\\test.txt");
  ASSERT_EQ(FileSystem::GetWin32Path("C:\\foo"), L"\\\\?\\C:\\foo");
  ASSERT_EQ(FileSystem::GetWin32Path("C:\\foo\\bar\\..\\baz"), L"\\\\?\\C:\\foo\\baz");
  ASSERT_EQ(FileSystem::GetWin32Path("\\\\foo\\bar\\baz"), L"\\\\?\\UNC\\foo\\bar\\baz");
  ASSERT_EQ(FileSystem::GetWin32Path("\\\\foo\\bar\\baz\\sub\\.."), L"\\\\?\\UNC\\foo\\bar\\baz");
  ASSERT_EQ(FileSystem::GetWin32Path("ŻąłóРстуぬねのはen🍪⟑η∏☉ⴤℹ︎∩₲ ₱⟑♰⫳🐱"), L"ŻąłóРстуぬねのはen🍪⟑η∏☉ⴤℹ︎∩₲ ₱⟑♰⫳🐱");
  ASSERT_EQ(FileSystem::GetWin32Path("C:\\ŻąłóРстуぬねのはen🍪⟑η∏☉ⴤℹ︎∩₲ ₱⟑♰⫳🐱"),
            L"\\\\?\\C:\\ŻąłóРстуぬねのはen🍪⟑η∏☉ⴤℹ︎∩₲ ₱⟑♰⫳🐱");
  ASSERT_EQ(FileSystem::GetWin32Path("\\\\foo\\bar\\ŻąłóРстуぬねのはen🍪⟑η∏☉ⴤℹ︎∩₲ ₱⟑♰⫳🐱"),
            L"\\\\?\\UNC\\foo\\bar\\ŻąłóРстуぬねのはen🍪⟑η∏☉ⴤℹ︎∩₲ ₱⟑♰⫳🐱");
  ASSERT_EQ(FileSystem::GetWin32Path("C:\\ŻąłóРстуぬね\\のはen🍪\\⟑η∏☉ⴤ\\..\\ℹ︎∩₲ ₱⟑♰⫳🐱"),
            L"\\\\?\\C:\\ŻąłóРстуぬね\\のはen🍪\\ℹ︎∩₲ ₱⟑♰⫳🐱");
  ASSERT_EQ(FileSystem::GetWin32Path("\\\\foo\\bar\\ŻąłóРстуぬねのはen🍪\\⟑η∏☉ⴤ\\..\\ℹ︎∩₲ ₱⟑♰⫳🐱"),
            L"\\\\?\\UNC\\foo\\bar\\ŻąłóРстуぬねのはen🍪\\ℹ︎∩₲ ₱⟑♰⫳🐱");
}

TEST(FileSystem, CreateDirectoryRecursiveOnSystemDrive)
{
  // Creating the drive itself fails with ERROR_ACCESS_DENIED on the system drive, so it has to be skipped.
  wchar_t temp[MAX_PATH + 1];
  ASSERT_NE(GetTempPathW(MAX_PATH + 1, temp), 0u);
  const std::string base = Path::Combine(StringUtil::WideStringToUTF8String(temp),
                                         fmt::format("common-tests-mkdir-{}", GetCurrentProcessId()));
  const std::string nested = Path::Combine(Path::Combine(base, "a"), "b");

  Error error;
  ASSERT_TRUE(FileSystem::CreateDirectory(nested.c_str(), true, &error)) << error.GetDescription();
  EXPECT_TRUE(FileSystem::DirectoryExists(nested.c_str()));
  EXPECT_TRUE(FileSystem::EnsureDirectoryExists(nested.c_str(), true));
  EXPECT_TRUE(FileSystem::RecursiveDeleteDirectory(base.c_str()));
}

#endif
