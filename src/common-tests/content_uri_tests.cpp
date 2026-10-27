// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "common/error.h"
#include "common/file_system.h"
#include "common/path.h"

#include "fmt/format.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

#ifndef _WIN32

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {

constexpr std::string_view R = "content://fake/tree/root";

std::string Uri(std::string_view relative)
{
  return fmt::format("{}/{}", R, relative);
}

bool HasErrno(const Error& error, int err)
{
  return error.GetType() == Error::Type::Errno && error.GetDescription().starts_with(fmt::format("errno {}:", err));
}

// Serves R from a local directory, the way the Android provider serves a granted tree.
class FakeProvider final : public FileSystem::ContentProvider
{
public:
  explicit FakeProvider(std::string root) : m_root(std::move(root)) {}

  void SetServePipes(bool serve_pipes) { m_serve_pipes = serve_pipes; }

  int OpenForReading(std::string_view uri, Error* error) override
  {
    std::string local;
    if (!ToLocal(uri, &local))
    {
      Error::SetErrno(error, ENOENT);
      return -1;
    }

    if (m_serve_pipes)
    {
      int fds[2];
      if (pipe(fds) != 0)
      {
        Error::SetErrno(error, errno);
        return -1;
      }
      close(fds[1]);
      return fds[0];
    }

    const int fd = open(local.c_str(), O_RDONLY);
    if (fd < 0)
      Error::SetErrno(error, errno);
    return fd;
  }

  bool Stat(std::string_view uri, FILESYSTEM_STAT_DATA* sd) override
  {
    std::string local;
    struct stat st;
    if (!ToLocal(uri, &local) || stat(local.c_str(), &st) != 0)
      return false;

    sd->CreationTime = st.st_ctime;
    sd->ModificationTime = st.st_mtime;
    sd->Size = S_ISREG(st.st_mode) ? st.st_size : 0;
    sd->Attributes = S_ISDIR(st.st_mode) ? FILESYSTEM_FILE_ATTRIBUTE_DIRECTORY : 0;
    return true;
  }

  bool ListDirectory(std::string_view uri, FileSystem::FindResultsArray* entries, Error* error) override
  {
    std::string local;
    DIR* dir = ToLocal(uri, &local) ? opendir(local.c_str()) : nullptr;
    if (!dir)
    {
      Error::SetErrno(error, ENOTDIR);
      return false;
    }

    while (const struct dirent* ent = readdir(dir))
    {
      const std::string_view name = ent->d_name;
      if (name == "." || name == "..")
        continue;

      FILESYSTEM_STAT_DATA sd;
      if (!Stat(fmt::format("{}/{}", uri, name), &sd))
        continue;

      FILESYSTEM_FIND_DATA fd;
      fd.FileName = name;
      fd.CreationTime = sd.CreationTime;
      fd.ModificationTime = sd.ModificationTime;
      fd.Size = sd.Size;
      fd.Attributes = sd.Attributes;
      entries->push_back(std::move(fd));
    }

    closedir(dir);
    return true;
  }

private:
  bool ToLocal(std::string_view uri, std::string* local) const
  {
    if (!uri.starts_with(R) || (uri.size() > R.size() && uri[R.size()] != '/'))
      return false;

    *local = m_root;
    local->append(uri.substr(R.size()));
    return true;
  }

  std::string m_root;
  bool m_serve_pipes = false;
};

std::vector<std::string> SortedNames(const FileSystem::FindResultsArray& results)
{
  std::vector<std::string> names;
  for (const FILESYSTEM_FIND_DATA& fd : results)
    names.push_back(fd.FileName);
  std::sort(names.begin(), names.end());
  return names;
}

} // namespace

// A temporary directory served as R by a FakeProvider for the length of each test.
class ContentURI : public ::testing::Test
{
protected:
  void SetUp() override
  {
    const char* tmpdir = std::getenv("TMPDIR");
    std::string templ = Path::Combine((tmpdir && *tmpdir) ? tmpdir : "/tmp", "common-tests-content-XXXXXX");
    ASSERT_NE(mkdtemp(templ.data()), nullptr);
    m_root = std::move(templ);
    m_provider = std::make_unique<FakeProvider>(m_root);
    FileSystem::SetContentProvider(m_provider.get());
  }

  void TearDown() override
  {
    FileSystem::SetContentProvider(nullptr);
    if (!m_root.empty())
      EXPECT_TRUE(FileSystem::RecursiveDeleteDirectory(m_root.c_str()));
  }

  std::string Local(std::string_view relative) const { return Path::Combine(m_root, relative); }

  void WriteLocal(std::string_view relative, std::string_view contents) const
  {
    const std::string path = Local(relative);
    ASSERT_TRUE(FileSystem::CreateDirectory(std::string(Path::GetDirectory(path)).c_str(), true));
    ASSERT_TRUE(FileSystem::WriteStringToFile(path.c_str(), contents));
  }

  std::string m_root;
  std::unique_ptr<FakeProvider> m_provider;
};

TEST_F(ContentURI, IsContentURI)
{
  EXPECT_TRUE(FileSystem::IsContentURI("content://a/tree/b"));
  EXPECT_FALSE(FileSystem::IsContentURI("/content/x"));
  EXPECT_FALSE(FileSystem::IsContentURI(""));
}

TEST_F(ContentURI, PathsKeepTheTreeRoot)
{
  EXPECT_TRUE(Path::IsAbsolute(Uri("x.cue")));
  EXPECT_EQ(Path::Canonicalize(Uri("a/./b/../c.cue")), Uri("a/c.cue"));
  EXPECT_EQ(Path::Canonicalize(Uri("../x")), Uri("x"));
  EXPECT_EQ(Path::Canonicalize(std::string(R)), R);
  EXPECT_EQ(Path::RealPath(Uri("a/./b/../../../c.cue")), Uri("c.cue"));
  EXPECT_EQ(Path::Combine(R, "sub"), Uri("sub"));
}

TEST_F(ContentURI, CueOpensItsBin)
{
  static constexpr std::string_view bytes = "track one bytes";
  WriteLocal("FF7/disc 1 (Track 1).bin", bytes);

  const std::string bin = Path::BuildRelativePath(Uri("FF7/disc 1.cue"), "disc 1 (Track 1).bin");
  Error error;
  FileSystem::ManagedCFilePtr fp(FileSystem::OpenCFile(bin.c_str(), "rb", &error));
  ASSERT_TRUE(fp) << error.GetDescription();

  const std::optional<std::string> read = FileSystem::ReadFileToString(fp.get(), &error);
  ASSERT_TRUE(read.has_value()) << error.GetDescription();
  EXPECT_EQ(read.value(), bytes);
}

TEST_F(ContentURI, M3UFindsItsCue)
{
  WriteLocal("set/game (Disc 2).cue", "cue");
  EXPECT_TRUE(FileSystem::FileExists(Path::BuildRelativePath(Uri("set/game.m3u"), "game (Disc 2).cue").c_str()));
}

TEST_F(ContentURI, SbiMdfAndPpfSiblingsExist)
{
  WriteLocal("g/x.sbi", "sbi");
  WriteLocal("g/x.ppf", "ppf");

  const std::string img = Uri("g/x.img");
  EXPECT_TRUE(FileSystem::FileExists(Path::ReplaceExtension(img, "sbi").c_str()));
  EXPECT_FALSE(FileSystem::FileExists(Path::ReplaceExtension(img, "mdf").c_str()));
  EXPECT_TRUE(FileSystem::FileExists(
    Path::BuildRelativePath(img, Path::ReplaceExtension(Path::GetFileName(img), "ppf")).c_str()));
}

TEST_F(ContentURI, ChdFindsItsParent)
{
  WriteLocal("c/child.chd", "abc");
  WriteLocal("c/parent.chd", "abcde");

  FileSystem::FindResultsArray r;
  const std::string dir(Path::GetDirectory(Uri("c/child.chd")));
  ASSERT_TRUE(FileSystem::FindFiles(dir.c_str(), "*.*", FILESYSTEM_FIND_FILES, &r));
  std::sort(r.begin(), r.end(), [](const auto& a, const auto& b) { return a.FileName < b.FileName; });
  ASSERT_EQ(r.size(), 2u);
  EXPECT_EQ(r[0].FileName, Uri("c/child.chd"));
  EXPECT_EQ(r[0].Size, 3);
  EXPECT_EQ(r[1].FileName, Uri("c/parent.chd"));
  EXPECT_EQ(r[1].Size, 5);
}

TEST_F(ContentURI, NamesThatLookLikeURISyntax)
{
  WriteLocal("Disc?/Final Fantasy #1 (100%).cue", "FILE \"Final Fantasy #1 (100%).bin\" BINARY\n");
  WriteLocal("Disc?/Final Fantasy #1 (100%).bin", "bin bytes");

  const std::string cue = Uri("Disc?/Final Fantasy #1 (100%).cue");
  EXPECT_EQ(Path::Canonicalize(cue), cue);

  Error error;
  FileSystem::ManagedCFilePtr cue_fp(FileSystem::OpenCFile(cue.c_str(), "rb", &error));
  ASSERT_TRUE(cue_fp) << error.GetDescription();

  const std::string bin = Path::BuildRelativePath(cue, "Final Fantasy #1 (100%).bin");
  EXPECT_TRUE(FileSystem::FileExists(bin.c_str()));
  FileSystem::ManagedCFilePtr bin_fp(FileSystem::OpenCFile(bin.c_str(), "rb", &error));
  ASSERT_TRUE(bin_fp) << error.GetDescription();
  EXPECT_EQ(FileSystem::ReadFileToString(bin_fp.get()).value_or(""), "bin bytes");
}

TEST_F(ContentURI, StatAndExistence)
{
  WriteLocal("sub/a.bin", "1234");

  EXPECT_TRUE(FileSystem::FileExists(Uri("sub/a.bin").c_str()));
  EXPECT_FALSE(FileSystem::FileExists(Uri("sub").c_str()));
  EXPECT_FALSE(FileSystem::FileExists(Uri("missing.bin").c_str()));
  EXPECT_TRUE(FileSystem::DirectoryExists(Uri("sub").c_str()));
  EXPECT_TRUE(FileSystem::DirectoryExists(std::string(R).c_str()));
  EXPECT_FALSE(FileSystem::DirectoryExists(Uri("sub/a.bin").c_str()));
  EXPECT_TRUE(FileSystem::IsRealDirectory(Uri("sub").c_str()));
  EXPECT_EQ(FileSystem::GetPathFileSize(Uri("sub/a.bin").c_str()), 4);
  EXPECT_EQ(FileSystem::GetPathFileSize(Uri("missing.bin").c_str()), -1);

  FILESYSTEM_STAT_DATA sd;
  ASSERT_TRUE(FileSystem::StatFile(Uri("sub/a.bin").c_str(), &sd));
  EXPECT_EQ(sd.Size, 4);
  EXPECT_EQ(sd.Attributes & FILESYSTEM_FILE_ATTRIBUTE_DIRECTORY, 0u);

  struct stat st;
  ASSERT_TRUE(FileSystem::StatFile(Uri("sub/a.bin").c_str(), &st));
  EXPECT_TRUE(S_ISREG(st.st_mode));
  EXPECT_EQ(st.st_size, 4);
  ASSERT_TRUE(FileSystem::StatFile(Uri("sub").c_str(), &st));
  EXPECT_TRUE(S_ISDIR(st.st_mode));
}

TEST_F(ContentURI, FindFilesHonoursFlags)
{
  WriteLocal("sub/a.bin", "a");
  WriteLocal("b.cue", "b");
  WriteLocal(".hidden", "h");

  const std::string root(R);
  FileSystem::FindResultsArray r;
  ASSERT_TRUE(FileSystem::FindFiles(
    root.c_str(), "*", FILESYSTEM_FIND_RECURSIVE | FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_RELATIVE_PATHS, &r));
  EXPECT_EQ(SortedNames(r), (std::vector<std::string>{"b.cue", "sub/a.bin"}));

  ASSERT_TRUE(FileSystem::FindFiles(root.c_str(), "*",
                                    FILESYSTEM_FIND_RECURSIVE | FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_RELATIVE_PATHS |
                                      FILESYSTEM_FIND_HIDDEN_FILES,
                                    &r));
  EXPECT_EQ(SortedNames(r), (std::vector<std::string>{".hidden", "b.cue", "sub/a.bin"}));

  ASSERT_TRUE(FileSystem::FindFiles(root.c_str(), "*", FILESYSTEM_FIND_FOLDERS, &r));
  EXPECT_EQ(SortedNames(r), (std::vector<std::string>{Uri("sub")}));

  ASSERT_TRUE(FileSystem::FindFiles(root.c_str(), "*.cue", FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_KEEP_ARRAY, &r));
  EXPECT_EQ(SortedNames(r), (std::vector<std::string>{Uri("b.cue"), Uri("sub")}));

  ASSERT_TRUE(FileSystem::FindFiles(
    root.c_str(), "*", FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_FOLDERS | FILESYSTEM_FIND_SORT_BY_NAME, &r));
  ASSERT_EQ(r.size(), 2u);
  EXPECT_EQ(r[0].FileName, Uri("sub"));
  EXPECT_EQ(r[1].FileName, Uri("b.cue"));

  EXPECT_FALSE(FileSystem::FindFiles(Uri("missing").c_str(), "*", FILESYSTEM_FIND_FILES, &r));
}

TEST_F(ContentURI, WritesFailReadOnly)
{
  WriteLocal("x.bin", "original");

  Error error;
  EXPECT_EQ(FileSystem::OpenCFile(Uri("x.bin").c_str(), "wb", &error), nullptr);
  EXPECT_TRUE(HasErrno(error, EROFS)) << error.GetDescription();

  error.Clear();
  EXPECT_FALSE(FileSystem::CreateDirectory(Uri("new").c_str(), false, &error));
  EXPECT_TRUE(HasErrno(error, EROFS)) << error.GetDescription();

  error.Clear();
  EXPECT_FALSE(FileSystem::DeleteFile(Uri("x.bin").c_str(), &error));
  EXPECT_TRUE(HasErrno(error, EROFS)) << error.GetDescription();

  EXPECT_EQ(FileSystem::ReadFileToString(Local("x.bin").c_str()).value_or(""), "original");
  EXPECT_FALSE(FileSystem::DirectoryExists(Local("new").c_str()));
  EXPECT_FALSE(FileSystem::DirectoryExists("content:"));
}

TEST_F(ContentURI, EveryWriterFailsReadOnly)
{
  WriteLocal("x.bin", "original");
  WriteLocal("dir/y.bin", "y");
  const std::string x = Uri("x.bin");
  const std::string dir = Uri("dir");

  for (const char* mode : {"w", "r+b", "ab", "rb+", "w+b"})
  {
    Error error;
    EXPECT_EQ(FileSystem::OpenCFile(x.c_str(), mode, &error), nullptr) << mode;
    EXPECT_TRUE(HasErrno(error, EROFS)) << mode << ": " << error.GetDescription();
    error.Clear();
    EXPECT_FALSE(FileSystem::OpenManagedSharedCFile(x.c_str(), mode, FileSystem::FileShareMode::DenyNone, &error));
    EXPECT_TRUE(HasErrno(error, EROFS)) << mode << ": " << error.GetDescription();
  }

  Error error;
  EXPECT_FALSE(FileSystem::EnsureDirectoryExists(Uri("new/deeper").c_str(), true, &error));
  EXPECT_TRUE(HasErrno(error, EROFS)) << error.GetDescription();
  error.Clear();
  EXPECT_FALSE(FileSystem::CreateDirectory(Uri("new/deeper").c_str(), true, &error));
  EXPECT_TRUE(HasErrno(error, EROFS)) << error.GetDescription();
  error.Clear();
  EXPECT_FALSE(FileSystem::RenamePath(x.c_str(), Uri("z.bin").c_str(), &error));
  EXPECT_TRUE(HasErrno(error, EROFS)) << error.GetDescription();
  error.Clear();
  EXPECT_FALSE(FileSystem::RenamePath(Local("x.bin").c_str(), Uri("z.bin").c_str(), &error));
  EXPECT_TRUE(HasErrno(error, EROFS)) << error.GetDescription();
  error.Clear();
  EXPECT_EQ(FileSystem::OpenExistingOrCreateCFile(x.c_str(), -1, &error), nullptr);
  EXPECT_TRUE(HasErrno(error, EROFS)) << error.GetDescription();
  error.Clear();
  EXPECT_FALSE(FileSystem::CreateAtomicRenamedFile(x, "wb", &error));
  EXPECT_TRUE(HasErrno(error, EROFS)) << error.GetDescription();
  error.Clear();
  EXPECT_FALSE(FileSystem::WriteAtomicRenamedFile(x, "new", 3, &error));
  EXPECT_TRUE(HasErrno(error, EROFS)) << error.GetDescription();
  error.Clear();
  EXPECT_FALSE(FileSystem::WriteStringToFile(x.c_str(), "new", &error));
  EXPECT_TRUE(HasErrno(error, EROFS)) << error.GetDescription();

  EXPECT_FALSE(FileSystem::DeleteDirectory(dir.c_str()));
  EXPECT_FALSE(FileSystem::RecursiveDeleteDirectory(dir.c_str()));
  EXPECT_FALSE(FileSystem::CopyFilePath(Local("x.bin").c_str(), Uri("copy.bin").c_str(), true));

  // Reading from content:// to a local file is fine.
  EXPECT_TRUE(FileSystem::CopyFilePath(x.c_str(), Local("copy.bin").c_str(), true));
  EXPECT_EQ(FileSystem::ReadFileToString(Local("copy.bin").c_str()).value_or(""), "original");

  EXPECT_EQ(FileSystem::ReadFileToString(Local("x.bin").c_str()).value_or(""), "original");
  EXPECT_TRUE(FileSystem::FileExists(Local("dir/y.bin").c_str()));
  EXPECT_FALSE(FileSystem::FileExists(Local("z.bin").c_str()));
  EXPECT_FALSE(FileSystem::DirectoryExists(Local("new").c_str()));
  EXPECT_FALSE(FileSystem::DirectoryExists("content:"));

  FileSystem::FindResultsArray r;
  ASSERT_TRUE(FileSystem::FindFiles(m_root.c_str(), "*", FILESYSTEM_FIND_FILES | FILESYSTEM_FIND_HIDDEN_FILES, &r));
  EXPECT_EQ(SortedNames(r), (std::vector<std::string>{Local("copy.bin"), Local("x.bin")}));
}

TEST_F(ContentURI, UnseekableDescriptorIsRefused)
{
  WriteLocal("x.bin", "bytes");
  m_provider->SetServePipes(true);

  Error error;
  EXPECT_EQ(FileSystem::OpenCFile(Uri("x.bin").c_str(), "rb", &error), nullptr);
  EXPECT_NE(error.GetDescription().find("can't be read in place"), std::string::npos) << error.GetDescription();
}

TEST_F(ContentURI, NoProviderMeansMissing)
{
  WriteLocal("x.bin", "bytes");
  FileSystem::SetContentProvider(nullptr);

  EXPECT_FALSE(FileSystem::FileExists(Uri("x.bin").c_str()));
  EXPECT_FALSE(FileSystem::DirectoryExists(std::string(R).c_str()));

  Error error;
  EXPECT_EQ(FileSystem::OpenCFile(Uri("x.bin").c_str(), "rb", &error), nullptr);
  EXPECT_TRUE(HasErrno(error, ENOENT)) << error.GetDescription();

  FileSystem::FindResultsArray r;
  EXPECT_FALSE(FileSystem::FindFiles(std::string(R).c_str(), "*", FILESYSTEM_FIND_FILES, &r));
}

#endif // _WIN32
