// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/librashader_loader.h"

#include <gtest/gtest.h>

#include <cstdlib>
#include <string>
#include <string_view>

namespace {

bool EndsWith(std::string_view str, std::string_view suffix)
{
  return str.size() >= suffix.size() && str.substr(str.size() - suffix.size()) == suffix;
}

// Sets an environment variable for the test's lifetime, restoring the previous value afterwards.
class ScopedEnv
{
public:
  ScopedEnv(const char* name, const char* value) : m_name(name)
  {
    if (const char* old = std::getenv(name))
      m_old = old;
    Set(value);
  }
  ~ScopedEnv() { Set(m_old.empty() ? nullptr : m_old.c_str()); }

private:
  void Set(const char* value)
  {
#ifdef _WIN32
    _putenv_s(m_name, value ? value : "");
#else
    if (value)
      setenv(m_name, value, 1);
    else
      unsetenv(m_name);
#endif
  }

  const char* m_name;
  std::string m_old;
};

} // namespace

TEST(LibrashaderLoader, MissingLibraryIsUnavailableWithReason)
{
  const Librashader::Availability a = Librashader::LoadFromPath("/nonexistent/librashader.so");
  EXPECT_FALSE(a.available);
  EXPECT_NE(a.reason.find("/nonexistent/librashader.so"), std::string::npos) << a.reason;
}

TEST(LibrashaderLoader, EmptyPathIsUnavailable)
{
  const Librashader::Availability a = Librashader::LoadFromPath("");
  EXPECT_FALSE(a.available);
  EXPECT_EQ(a.reason, "librashader path is empty");
}

TEST(LibrashaderLoader, WrongLibraryIsUnavailableBecauseSymbolsAreMissing)
{
  // A library every platform has, which is certainly not librashader.
#if defined(_WIN32)
  const char* other = "kernel32.dll";
#elif defined(__APPLE__)
  const char* other = "/usr/lib/libz.dylib";
#elif defined(__ANDROID__)
  const char* other = "libz.so";
#else
  const char* other = "libz.so.1";
#endif
  const Librashader::Availability a = Librashader::LoadFromPath(other);
  EXPECT_FALSE(a.available);
  EXPECT_EQ(a.reason, "librashader is missing symbol libra_instance_abi_version");
}

TEST(LibrashaderLoader, DefaultPathHasPlatformFileName)
{
  ScopedEnv env("GOOSESTATION_LIBRASHADER_PATH", nullptr);
  const std::string path = Librashader::GetDefaultLibraryPath();
#if defined(_WIN32)
  EXPECT_TRUE(EndsWith(path, "librashader.dll")) << path;
#elif defined(__APPLE__)
  EXPECT_TRUE(EndsWith(path, "librashader.dylib")) << path;
#else
  EXPECT_TRUE(EndsWith(path, "librashader.so")) << path;
#endif
}

TEST(LibrashaderLoader, EnvironmentOverrideWinsOverDefaultPath)
{
  ScopedEnv env("GOOSESTATION_LIBRASHADER_PATH", "/somewhere/else/librashader.test");
  EXPECT_EQ(Librashader::GetDefaultLibraryPath(), "/somewhere/else/librashader.test");
}

TEST(LibrashaderLoader, DescribeNullErrorDoesNotCrash)
{
  EXPECT_EQ(Librashader::DescribeAndFreeError(nullptr), "unknown librashader error");
}

TEST(LibrashaderLoader, LoadsTheBuiltLibraryWhenPresent)
{
  const char* path = std::getenv("GOOSESTATION_LIBRASHADER_PATH");
  if (!path || !*path)
    GTEST_SKIP() << "GOOSESTATION_LIBRASHADER_PATH isn't set";

  const Librashader::Availability a = Librashader::LoadFromPath(path);
  EXPECT_TRUE(a.available) << a.reason;
  EXPECT_TRUE(a.reason.empty());
  EXPECT_TRUE(Librashader::GetAvailability().available) << Librashader::GetAvailability().reason;
}
