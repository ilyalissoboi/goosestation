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

#ifndef _WIN32

#include <cstdlib>
#include <dirent.h>

namespace {

// A fresh directory under $TMPDIR (or /tmp), deleted with its contents when the test ends.
class TempDirectory
{
public:
  TempDirectory()
  {
    const char* tmpdir = std::getenv("TMPDIR");
    std::string templ = Path::Combine((tmpdir && *tmpdir) ? tmpdir : "/tmp", "common-tests-fs-XXXXXX");
    if (mkdtemp(templ.data()))
      m_path = std::move(templ);
  }

  ~TempDirectory()
  {
    if (!m_path.empty())
      EXPECT_TRUE(FileSystem::RecursiveDeleteDirectory(m_path.c_str()));
  }

  bool IsValid() const { return !m_path.empty(); }
  const std::string& GetPath() const { return m_path; }
  std::string PathFor(std::string_view name) const { return Path::Combine(m_path, name); }

private:
  std::string m_path;
};

} // namespace

TEST(FileSystem, CreateDirectory)
{
  const TempDirectory tmp;
  ASSERT_TRUE(tmp.IsValid());

  const std::string path = tmp.PathFor("sub");
  EXPECT_FALSE(FileSystem::DirectoryExists(path.c_str()));

  Error error;
  ASSERT_TRUE(FileSystem::CreateDirectory(path.c_str(), false, &error)) << error.GetDescription();
  EXPECT_TRUE(FileSystem::DirectoryExists(path.c_str()));
}

TEST(FileSystem, WriteAndReadString)
{
  const TempDirectory tmp;
  ASSERT_TRUE(tmp.IsValid());

  const std::string path = tmp.PathFor("file.txt");
  static constexpr std::string_view contents = "GooseStation\nfile system test\n";

  Error error;
  ASSERT_TRUE(FileSystem::WriteStringToFile(path.c_str(), contents, &error)) << error.GetDescription();

  const std::optional<std::string> read = FileSystem::ReadFileToString(path.c_str(), &error);
  ASSERT_TRUE(read.has_value()) << error.GetDescription();
  EXPECT_EQ(read.value(), contents);
}

TEST(FileSystem, StatFileSize)
{
  const TempDirectory tmp;
  ASSERT_TRUE(tmp.IsValid());

  const std::string path = tmp.PathFor("sized.bin");
  const std::string contents(1234, 'x');
  ASSERT_TRUE(FileSystem::WriteStringToFile(path.c_str(), contents));

  FILESYSTEM_STAT_DATA sd;
  ASSERT_TRUE(FileSystem::StatFile(path.c_str(), &sd));
  EXPECT_EQ(sd.Size, static_cast<s64>(contents.size()));
  EXPECT_EQ(sd.Attributes & FILESYSTEM_FILE_ATTRIBUTE_DIRECTORY, 0u);
}

TEST(FileSystem, FindFiles)
{
  const TempDirectory tmp;
  ASSERT_TRUE(tmp.IsValid());

  ASSERT_TRUE(FileSystem::WriteStringToFile(tmp.PathFor("a.txt").c_str(), "a"));
  ASSERT_TRUE(FileSystem::WriteStringToFile(tmp.PathFor("b.txt").c_str(), "bb"));
  ASSERT_TRUE(FileSystem::CreateDirectory(tmp.PathFor("dir").c_str(), false));

  FileSystem::FindResultsArray results;
  ASSERT_TRUE(FileSystem::FindFiles(tmp.GetPath().c_str(), "*", FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_SORT_BY_NAME,
                                    &results));
  ASSERT_EQ(results.size(), 2u);
  EXPECT_EQ(results[0].FileName, tmp.PathFor("a.txt"));
  EXPECT_EQ(results[0].Size, 1);
  EXPECT_EQ(results[1].FileName, tmp.PathFor("b.txt"));
  EXPECT_EQ(results[1].Size, 2);
}

TEST(FileSystem, RenameAndDelete)
{
  const TempDirectory tmp;
  ASSERT_TRUE(tmp.IsValid());

  const std::string old_path = tmp.PathFor("old.txt");
  const std::string new_path = tmp.PathFor("new.txt");
  ASSERT_TRUE(FileSystem::WriteStringToFile(old_path.c_str(), "moved"));

  Error error;
  ASSERT_TRUE(FileSystem::RenamePath(old_path.c_str(), new_path.c_str(), &error)) << error.GetDescription();
  EXPECT_FALSE(FileSystem::FileExists(old_path.c_str()));
  EXPECT_TRUE(FileSystem::FileExists(new_path.c_str()));

  ASSERT_TRUE(FileSystem::DeleteFile(new_path.c_str(), &error)) << error.GetDescription();
  EXPECT_FALSE(FileSystem::FileExists(new_path.c_str()));
}

static size_t CountOpenDescriptors()
{
#ifdef __ANDROID__
  DIR* dir = opendir("/proc/self/fd");
#else
  DIR* dir = opendir("/dev/fd");
#endif
  if (!dir)
    return 0;

  size_t count = 0;
  while (readdir(dir))
    count++;
  closedir(dir);
  return count;
}

TEST(FileSystem, CreateAtomicRenamedFileClosesTempDescriptor)
{
  const TempDirectory tmp;
  ASSERT_TRUE(tmp.IsValid());

  const size_t before = CountOpenDescriptors();
  ASSERT_NE(before, 0u);
  for (int i = 0; i < 50; i++)
  {
    Error error;
    ASSERT_TRUE(FileSystem::WriteAtomicRenamedFile(tmp.PathFor(fmt::format("file{}.bin", i)), "data", 4, &error))
      << error.GetDescription();
  }

  EXPECT_EQ(CountOpenDescriptors(), before);
}

#endif
