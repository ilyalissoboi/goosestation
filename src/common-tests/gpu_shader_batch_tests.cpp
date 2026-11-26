// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/gpu_device.h"
#include "util/gpu_shader_batch.h"
#include "util/gpu_shader_cache.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/path.h"

#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <cstdlib>
#include <filesystem>
#include <mutex>
#include <random>
#include <string>
#include <thread>
#include <vector>

namespace {

// A shader that remembers which thread destroyed it.
struct DestroyLog
{
  std::mutex lock;
  std::vector<std::thread::id> threads;
};

class FakeShader final : public GPUShader
{
public:
  FakeShader(GPUShaderStage stage, std::string text, DestroyLog* log)
    : GPUShader(stage), m_text(std::move(text)), m_log(log)
  {
  }
  ~FakeShader() override
  {
    if (m_log)
    {
      std::unique_lock lock(m_log->lock);
      m_log->threads.push_back(std::this_thread::get_id());
    }
  }
  void SetDebugName(std::string_view) override {}

  const std::string& GetText() const { return m_text; }

private:
  std::string m_text;
  DestroyLog* m_log;
};

void Jitter()
{
  thread_local std::mt19937 rng(std::random_device{}());
  std::this_thread::sleep_for(std::chrono::microseconds(std::uniform_int_distribution<int>(0, 2000)(rng)));
}

class ShaderBatch : public ::testing::Test
{
protected:
  static constexpr u32 COUNT = 200;

  void SetUp() override
  {
    std::error_code ec;
    m_dir = (std::filesystem::temp_directory_path(ec) / ("common-tests-shader-batch-" + std::to_string(std::rand())))
              .generic_string();
    ASSERT_TRUE(FileSystem::CreateDirectory(m_dir.c_str(), true));
    for (u32 i = 0; i < COUNT; i++)
      m_sources.push_back("void main() { /* shader " + std::to_string(i) + " */ }\n");
    for (const std::string& s : m_sources)
      m_requests.push_back({GPUShaderStage::Fragment, GPUShaderLanguage::GLSL, s, "main"});
  }

  void TearDown() override { FileSystem::RecursiveDeleteDirectory(m_dir.c_str()); }

  std::string CachePath(const char* name) const { return Path::Combine(m_dir, name); }

  // Builds every request into a cache at base, returning whether it succeeded.
  bool Build(const std::string& base, u32 threads, std::vector<std::unique_ptr<GPUShader>>* out = nullptr,
             Error* error = nullptr)
  {
    GPUShaderCache cache;
    if (!cache.Open(base, 1) && !cache.Create())
      return false;
    std::vector<std::unique_ptr<GPUShader>> local(m_requests.size());
    std::vector<std::unique_ptr<GPUShader>>& shaders = out ? *out : local;
    shaders.resize(m_requests.size());
    const bool ok = GPUShaderBatch::Run(cache, m_requests, shaders, threads, FromBinary(), FromSource(), {}, error);
    cache.Close();
    return ok;
  }

  GPUShaderBatch::FromBinary FromBinary()
  {
    return [this](const GPUShaderBatch::Request& req, std::span<const u8> binary,
                  Error* error) -> std::unique_ptr<GPUShader> {
      m_from_binary.fetch_add(1);
      Jitter();
      std::string text(reinterpret_cast<const char*>(binary.data()), binary.size());
      if (text == m_reject_binary)
      {
        Error::SetStringView(error, "rejected binary");
        return {};
      }
      return std::make_unique<FakeShader>(req.stage, std::move(text), &m_destroyed);
    };
  }

  GPUShaderBatch::FromSource FromSource()
  {
    return [this](const GPUShaderBatch::Request& req, GPUShaderCache::ShaderBinary* binary,
                  Error* error) -> std::unique_ptr<GPUShader> {
      m_from_source.fetch_add(1);
      Jitter();
      const std::string text(req.source);
      for (const std::string& f : m_fail_sources)
      {
        if (text == f)
        {
          Error::SetStringFmt(error, "failed: {}", text);
          return {};
        }
      }
      if (binary)
      {
        binary->resize(text.size());
        std::memcpy(binary->data(), text.data(), text.size());
      }
      return std::make_unique<FakeShader>(req.stage, text, &m_destroyed);
    };
  }

  std::string m_dir;
  std::vector<std::string> m_sources;
  std::vector<GPUShaderBatch::Request> m_requests;
  std::vector<std::string> m_fail_sources;
  std::string m_reject_binary;
  std::atomic<u32> m_from_binary{0};
  std::atomic<u32> m_from_source{0};
  DestroyLog m_destroyed;
};

TEST_F(ShaderBatch, TheCacheFileIsTheSameWhateverTheTiming)
{
  ASSERT_TRUE(Build(CachePath("one"), 1));
  const std::optional<std::string> idx = FileSystem::ReadFileToString(Path::Combine(m_dir, "one.idx").c_str());
  const std::optional<std::string> bin = FileSystem::ReadFileToString(Path::Combine(m_dir, "one.bin").c_str());
  ASSERT_TRUE(idx.has_value() && bin.has_value());
  ASSERT_FALSE(bin->empty());

  for (int run = 0; run < 3; run++)
  {
    const std::string base = CachePath(("eight" + std::to_string(run)).c_str());
    ASSERT_TRUE(Build(base, 8));
    EXPECT_EQ(FileSystem::ReadFileToString((base + ".idx").c_str()), idx) << "run " << run;
    EXPECT_EQ(FileSystem::ReadFileToString((base + ".bin").c_str()), bin) << "run " << run;
  }
}

TEST_F(ShaderBatch, AWarmRunUsesBinariesAndWritesNothing)
{
  const std::string base = CachePath("warm");
  ASSERT_TRUE(Build(base, 8));
  const std::optional<std::string> idx = FileSystem::ReadFileToString((base + ".idx").c_str());
  const std::optional<std::string> bin = FileSystem::ReadFileToString((base + ".bin").c_str());

  m_from_binary = 0;
  m_from_source = 0;
  std::vector<std::unique_ptr<GPUShader>> shaders;
  ASSERT_TRUE(Build(base, 8, &shaders));
  EXPECT_EQ(m_from_binary.load(), COUNT);
  EXPECT_EQ(m_from_source.load(), 0u);
  for (u32 i = 0; i < COUNT; i++)
    EXPECT_EQ(static_cast<FakeShader*>(shaders[i].get())->GetText(), m_sources[i]);
  EXPECT_EQ(FileSystem::ReadFileToString((base + ".idx").c_str()), idx);
  EXPECT_EQ(FileSystem::ReadFileToString((base + ".bin").c_str()), bin);
}

TEST_F(ShaderBatch, TheFirstFailureInOrderIsReported)
{
  m_fail_sources = {m_sources[30], m_sources[60]};
  std::vector<std::unique_ptr<GPUShader>> shaders;
  Error error;
  EXPECT_FALSE(Build(CachePath("fail"), 8, &shaders, &error));
  EXPECT_EQ(error.GetDescription(), "failed: " + m_sources[30]);

  // Nothing was destroyed on a worker: whatever was made is the caller's to destroy.
  {
    std::unique_lock lock(m_destroyed.lock);
    EXPECT_TRUE(m_destroyed.threads.empty());
  }
  shaders.clear();
  std::unique_lock lock(m_destroyed.lock);
  for (const std::thread::id& id : m_destroyed.threads)
    EXPECT_EQ(id, std::this_thread::get_id());
}

TEST_F(ShaderBatch, ARejectedBinaryClearsTheCacheAndRebuilds)
{
  const std::string base = CachePath("rejected");
  ASSERT_TRUE(Build(base, 8));
  const std::optional<std::string> idx = FileSystem::ReadFileToString((base + ".idx").c_str());
  const std::optional<std::string> bin = FileSystem::ReadFileToString((base + ".bin").c_str());

  m_reject_binary = m_sources[42];
  m_from_source = 0;
  std::vector<std::unique_ptr<GPUShader>> shaders;
  ASSERT_TRUE(Build(base, 8, &shaders));
  EXPECT_GE(m_from_source.load(), 1u);
  EXPECT_EQ(static_cast<FakeShader*>(shaders[42].get())->GetText(), m_sources[42]);

  // The cache was cleared and holds every binary again, in request order: the same bytes as a fresh build.
  EXPECT_EQ(FileSystem::ReadFileToString((base + ".idx").c_str()), idx);
  EXPECT_EQ(FileSystem::ReadFileToString((base + ".bin").c_str()), bin);
}

} // namespace
