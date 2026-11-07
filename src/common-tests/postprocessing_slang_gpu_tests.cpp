// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// The slang stage on a real GPU device, offscreen. Opt-in: GOOSESTATION_GPU_TEST_API=Metal|Vulkan|OpenGL picks the
// renderer, and GOOSESTATION_LIBRASHADER_PATH the library; without both, every test here is skipped (CI runs none).

#include "util/gpu_device.h"
#include "util/librashader_loader.h"
#include "util/postprocessing.h"
#include "util/postprocessing_shader_slang.h"

#include "core/settings.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/memory_settings_interface.h"
#include "common/path.h"

#include <gtest/gtest.h>

#include <array>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>
#include <vector>

namespace {

constexpr u32 WIDTH = 64;
constexpr u32 HEIGHT = 48;
const GSVector4i PICTURE(8, 8, 56, 40); // 48x32, symmetric about the middle row, so it's the same flipped
constexpr u32 NATIVE_WIDTH = 24;        // 2x: the box filter
constexpr u32 NATIVE_HEIGHT = 16;

constexpr u32 GREEN = 0xFF00FF00u; // RGBA8 in memory, read as a little-endian u32
constexpr u32 RED_QUARTER = 0xFF000040u;
constexpr u32 BLUE_QUARTER = 0xFF400000u;

constexpr const char* VERTEX_STAGE = "#pragma stage vertex\n"
                                     "layout(location = 0) in vec4 Position;\n"
                                     "layout(location = 1) in vec2 TexCoord;\n"
                                     "layout(location = 0) out vec2 vTexCoord;\n"
                                     "void main() { gl_Position = global.MVP * Position; vTexCoord = TexCoord; }\n";

// Multiplies the picture by TEST_GAIN.
const std::string GAIN_SHADER = std::string("#version 450\n"
                                            "#pragma parameter TEST_GAIN \"Test gain\" 1.0 0.0 2.0 0.1\n"
                                            "layout(push_constant) uniform Push { float TEST_GAIN; } params;\n"
                                            "layout(std140, set = 0, binding = 0) uniform UBO { mat4 MVP; } global;\n") +
                                VERTEX_STAGE +
                                "#pragma stage fragment\n"
                                "layout(location = 0) in vec2 vTexCoord;\n"
                                "layout(location = 0) out vec4 FragColor;\n"
                                "layout(set = 0, binding = 2) uniform sampler2D Source;\n"
                                "void main() { FragColor = texture(Source, vTexCoord) * params.TEST_GAIN; }\n";

// Ignores the picture: red over the top half (as slang shaders see it), blue below.
const std::string PROBE_SHADER = std::string("#version 450\n"
                                             "layout(std140, set = 0, binding = 0) uniform UBO { mat4 MVP; } global;\n") +
                                 VERTEX_STAGE +
                                 "#pragma stage fragment\n"
                                 "layout(location = 0) in vec2 vTexCoord;\n"
                                 "layout(location = 0) out vec4 FragColor;\n"
                                 "layout(set = 0, binding = 2) uniform sampler2D Source;\n"
                                 "void main() { FragColor = (vTexCoord.y < 0.5) ? vec4(1.0, 0.0, 0.0, 1.0) :\n"
                                 "                                               vec4(0.0, 0.0, 1.0, 1.0); }\n";

std::optional<RenderAPI> TestAPI()
{
  const char* name = std::getenv("GOOSESTATION_GPU_TEST_API");
  if (!name)
    return std::nullopt;
  if (std::strcmp(name, "Metal") == 0)
    return RenderAPI::Metal;
  if (std::strcmp(name, "Vulkan") == 0)
    return RenderAPI::Vulkan;
  if (std::strcmp(name, "OpenGL") == 0)
    return RenderAPI::OpenGL;
  return std::nullopt;
}

// The input picture: green outside the picture rect, a quarter red over its top half and a quarter blue below.
std::vector<u32> InputPixels()
{
  std::vector<u32> pixels(WIDTH * HEIGHT, GREEN);
  const s32 middle = (PICTURE.top + PICTURE.bottom) / 2;
  for (s32 y = PICTURE.top; y < PICTURE.bottom; y++)
  {
    for (s32 x = PICTURE.left; x < PICTURE.right; x++)
      pixels[y * WIDTH + x] = (y < middle) ? RED_QUARTER : BLUE_QUARTER;
  }
  return pixels;
}

std::vector<u32> Reversed(const std::vector<u32>& pixels)
{
  std::vector<u32> out(pixels.size());
  for (u32 y = 0; y < HEIGHT; y++)
    std::memcpy(&out[(HEIGHT - 1 - y) * WIDTH], &pixels[y * WIDTH], WIDTH * sizeof(u32));
  return out;
}

bool Near(u32 a, u32 b)
{
  for (u32 shift = 0; shift < 32; shift += 8)
  {
    const s32 ca = (a >> shift) & 0xFF, cb = (b >> shift) & 0xFF;
    if (std::abs(ca - cb) > 3)
      return false;
  }
  return true;
}

class SlangGPU : public ::testing::Test
{
protected:
  void SetUp() override
  {
    const std::optional<RenderAPI> api = TestAPI();
    if (!api.has_value())
      GTEST_SKIP() << "GOOSESTATION_GPU_TEST_API isn't Metal, Vulkan or OpenGL";
    if (!Librashader::GetAvailability().available)
      GTEST_SKIP() << "librashader isn't loadable: " << Librashader::GetAvailability().reason;

    g_gpu_device = GPUDevice::CreateDeviceForAPI(api.value());
    ASSERT_TRUE(g_gpu_device);
    Error error;
    ASSERT_TRUE(g_gpu_device->Create({}, {}, 0, false, GPUVSyncMode::Disabled, false, false, std::nullopt, {}, &error))
      << error.GetDescription();

    std::error_code ec;
    m_root = (std::filesystem::temp_directory_path(ec) / ("common-tests-slang-gpu-" + std::to_string(std::rand())))
               .generic_string();
    WriteFile("p/gain.slangp", "shaders = 1\nshader0 = gain.slang\n");
    WriteFile("p/gain.slang", GAIN_SHADER);
    WriteFile("p/probe.slangp", "shaders = 1\nshader0 = probe.slang\n");
    WriteFile("p/probe.slang", PROBE_SHADER);
  }

  void TearDown() override
  {
    m_shader.reset();
    m_textures.clear();
    if (g_gpu_device)
    {
      g_gpu_device->Destroy();
      g_gpu_device.reset();
    }
    if (!m_root.empty())
      FileSystem::RecursiveDeleteDirectory(m_root.c_str());
  }

  void WriteFile(const std::string& relative, std::string_view contents)
  {
    const std::filesystem::path p = std::filesystem::path(m_root) / relative;
    std::error_code ec;
    std::filesystem::create_directories(p.parent_path(), ec);
    ASSERT_TRUE(FileSystem::WriteStringToFile(p.generic_string().c_str(), contents));
  }

  GPUTexture* Texture(GPUTexture::Type type, const std::vector<u32>* pixels)
  {
    m_textures.push_back(g_gpu_device->CreateTexture(WIDTH, HEIGHT, 1, 1, 1, type, GPUTexture::Format::RGBA8,
                                                     pixels ? pixels->data() : nullptr, WIDTH * sizeof(u32)));
    return m_textures.back().get();
  }

  // Loads the preset as a stage, with TEST_GAIN at gain, compiled for the test's target.
  void LoadStage(const char* name, float gain)
  {
    m_shader = std::make_unique<PostProcessing::SlangShader>();
    Error error;
    ASSERT_TRUE(m_shader->LoadFromFile(name, Path::Combine(m_root, name), false, &error)) << error.GetDescription();
    MemorySettingsInterface si;
    si.SetFloatValue("Stage", "TEST_GAIN", gain);
    m_shader->LoadOptions(si, "Stage");
    ASSERT_TRUE(m_shader->CompilePipeline(GPUTexture::Format::RGBA8, WIDTH, HEIGHT, nullptr));
  }

  std::vector<u32> Run(GPUTexture* input, const PostProcessing::PictureInfo& picture)
  {
    GPUTexture* output = Texture(GPUTexture::Type::RenderTarget, nullptr);
    EXPECT_TRUE(m_shader->Apply(input, nullptr, output, GSVector4i(0, 0, WIDTH, HEIGHT), WIDTH, HEIGHT, NATIVE_WIDTH,
                                NATIVE_HEIGHT, WIDTH, HEIGHT, picture));

    std::vector<u32> pixels(WIDTH * HEIGHT);
    std::unique_ptr<GPUDownloadTexture> dl = g_gpu_device->CreateDownloadTexture(WIDTH, HEIGHT, output->GetFormat());
    EXPECT_TRUE(dl);
    if (!dl)
      return pixels;
    output->MakeReadyForSampling();
    dl->CopyFromTexture(0, 0, output, 0, 0, WIDTH, HEIGHT, 0, 0, true);
    dl->Flush();
    EXPECT_TRUE(dl->ReadTexels(0, 0, WIDTH, HEIGHT, pixels.data(), WIDTH * sizeof(u32)));
    return pixels;
  }

  static PostProcessing::PictureInfo Picture(bool rows_top_first)
  {
    PostProcessing::PictureInfo picture;
    picture.rect = PICTURE;
    picture.native_width = NATIVE_WIDTH;
    picture.native_height = NATIVE_HEIGHT;
    picture.rows_top_first = rows_top_first;
    return picture;
  }

  static u32 At(const std::vector<u32>& pixels, u32 x, u32 y) { return pixels[y * WIDTH + x]; }

  std::string m_root;
  std::unique_ptr<PostProcessing::SlangShader> m_shader;
  std::vector<std::unique_ptr<GPUTexture>> m_textures;
};

} // namespace

TEST_F(SlangGPU, ThePresetShadesThePictureAndLeavesTheRest)
{
  LoadStage("p/gain.slangp", 2.0f);
  const std::vector<u32> input = InputPixels();
  const std::vector<u32> out = Run(Texture(GPUTexture::Type::Texture, &input), Picture(true));

  EXPECT_EQ(At(out, 2, 2), GREEN);
  EXPECT_EQ(At(out, 60, 44), GREEN);
  EXPECT_PRED2(Near, At(out, 32, 12), 0xFF000080u) << std::hex << At(out, 32, 12);
  EXPECT_PRED2(Near, At(out, 32, 36), 0xFF800000u) << std::hex << At(out, 32, 36);
}

TEST_F(SlangGPU, AChangedParameterReachesTheRunningChain)
{
  LoadStage("p/gain.slangp", 2.0f);
  const std::vector<u32> input = InputPixels();
  GPUTexture* tex = Texture(GPUTexture::Type::Texture, &input);
  Run(tex, Picture(true));

  MemorySettingsInterface si;
  si.SetFloatValue("Stage", "TEST_GAIN", 1.0f);
  m_shader->LoadOptions(si, "Stage");
  const std::vector<u32> out = Run(tex, Picture(true));
  EXPECT_PRED2(Near, At(out, 32, 12), RED_QUARTER) << std::hex << At(out, 32, 12);
}

TEST_F(SlangGPU, TheTopOfThePresetsPictureIsTheTop)
{
  LoadStage("p/probe.slangp", 1.0f);
  const std::vector<u32> input = InputPixels();
  const std::vector<u32> out = Run(Texture(GPUTexture::Type::Texture, &input), Picture(true));
  EXPECT_EQ(At(out, 32, 10), 0xFF0000FFu) << std::hex << At(out, 32, 10);
  EXPECT_EQ(At(out, 32, 38), 0xFFFF0000u) << std::hex << At(out, 32, 38);
}

TEST_F(SlangGPU, TexturesStoredBottomRowFirstComeOutTheSameWay)
{
  // The display chain on OpenGL: the chain's textures hold the picture's bottom row first.
  LoadStage("p/probe.slangp", 1.0f);
  const std::vector<u32> input = Reversed(InputPixels());
  const std::vector<u32> out = Reversed(Run(Texture(GPUTexture::Type::Texture, &input), Picture(false)));
  EXPECT_EQ(At(out, 2, 2), GREEN);
  EXPECT_EQ(At(out, 32, 10), 0xFF0000FFu) << std::hex << At(out, 32, 10);
  EXPECT_EQ(At(out, 32, 38), 0xFFFF0000u) << std::hex << At(out, 32, 38);
}

TEST_F(SlangGPU, TheGamesOwnFrameIsShadedWhereThePictureGoes)
{
  LoadStage("p/gain.slangp", 2.0f);
  const std::vector<u32> frame = InputPixels();
  PostProcessing::PictureInfo picture = Picture(true);
  picture.game_texture = Texture(GPUTexture::Type::Texture, &frame);
  picture.game_view = PICTURE;
  const std::vector<u32> input(WIDTH * HEIGHT, 0xFFFFFFFFu); // not read: the stage takes the game's frame
  const std::vector<u32> out = Run(Texture(GPUTexture::Type::Texture, &input), picture);

  EXPECT_EQ(At(out, 2, 2) & 0x00FFFFFFu, 0u) << std::hex << At(out, 2, 2);
  EXPECT_PRED2(Near, At(out, 32, 12), 0xFF000080u) << std::hex << At(out, 32, 12);
  EXPECT_PRED2(Near, At(out, 32, 36), 0xFF800000u) << std::hex << At(out, 32, 36);
}
