// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// The slang stage on a real GPU device, offscreen. Opt-in: GOOSESTATION_GPU_TEST_API picks the renderer (see
// gpu_test_device.h), and GOOSESTATION_LIBRASHADER_PATH the library; without both, every test here is skipped (CI runs
// none).

#include "gpu_test_device.h"

#include "util/gpu_device.h"
#include "util/librashader_loader.h"
#include "util/postprocessing.h"
#include "util/image.h"
#include "util/postprocessing_shader_fx.h"
#include "util/postprocessing_shader_glsl.h"
#include "util/postprocessing_shader_slang.h"
#ifdef ENABLE_VULKAN
#include "util/vulkan_device.h"
#endif

#include "core/settings.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/memory_settings_interface.h"
#include "common/path.h"

#include "fmt/format.h"

#include <gtest/gtest.h>

#include <algorithm>
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

// A bezel: magenta over the whole viewport but a box in the middle (a quarter of each side in), which shows the picture
// (Source) stretched over the box.
const std::string FRAME_SHADER = std::string("#version 450\n"
                                             "layout(std140, set = 0, binding = 0) uniform UBO { mat4 MVP; } global;\n") +
                                 VERTEX_STAGE +
                                 "#pragma stage fragment\n"
                                 "layout(location = 0) in vec2 vTexCoord;\n"
                                 "layout(location = 0) out vec4 FragColor;\n"
                                 "layout(set = 0, binding = 2) uniform sampler2D Source;\n"
                                 "void main() {\n"
                                 "  vec2 uv = (vTexCoord - vec2(0.25)) * 2.0;\n"
                                 "  FragColor = (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) ?\n"
                                 "    vec4(1.0, 0.0, 1.0, 1.0) : texture(Source, uv);\n"
                                 "}\n";

// Writes the aspect ratio it's given (OriginalAspect, with the original-aspect uniforms) halved into red; 0 without.
const std::string ASPECT_SHADER = std::string("#version 450\n"
                                              "layout(std140, set = 0, binding = 0) uniform UBO { mat4 MVP;\n"
                                              "#ifdef _HAS_ORIGINALASPECT_UNIFORMS\n"
                                              "  float OriginalAspect;\n"
                                              "#endif\n"
                                              "} global;\n") +
                                  VERTEX_STAGE +
                                  "#pragma stage fragment\n"
                                  "layout(location = 0) in vec2 vTexCoord;\n"
                                  "layout(location = 0) out vec4 FragColor;\n"
                                  "layout(set = 0, binding = 2) uniform sampler2D Source;\n"
                                  "void main() {\n"
                                  "#ifdef _HAS_ORIGINALASPECT_UNIFORMS\n"
                                  "  FragColor = vec4(global.OriginalAspect * 0.5, 0.0, 0.0, 1.0);\n"
                                  "#else\n"
                                  "  FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
                                  "#endif\n"
                                  "}\n";

constexpr u32 MAGENTA = 0xFFFF00FFu;

// The picture with alpha 0, as koko-aio's last pass writes it.
const std::string TRANSPARENT_SHADER = std::string("#version 450\n"
                                                   "layout(std140, set = 0, binding = 0) uniform UBO { mat4 MVP; } "
                                                   "global;\n") +
                                       VERTEX_STAGE +
                                       "#pragma stage fragment\n"
                                       "layout(location = 0) in vec2 vTexCoord;\n"
                                       "layout(location = 0) out vec4 FragColor;\n"
                                       "layout(set = 0, binding = 2) uniform sampler2D Source;\n"
                                       "void main() { FragColor = vec4(texture(Source, vTexCoord).rgb, 0.0); }\n";

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
    if (!GPUTestDevice::API().has_value())
      GTEST_SKIP() << "GOOSESTATION_GPU_TEST_API isn't set";
    if (!Librashader::GetAvailability().available)
      GTEST_SKIP() << "librashader isn't loadable: " << Librashader::GetAvailability().reason;

    Error error;
    ASSERT_TRUE(GPUTestDevice::Create({}, &error)) << error.GetDescription();

    std::error_code ec;
    // Native separators: Windows' extended-length paths, which FileSystem uses for absolute ones, take no '/'.
    m_root = Path::ToNativePath(
      (std::filesystem::temp_directory_path(ec) / ("common-tests-slang-gpu-" + std::to_string(std::rand())))
        .generic_string());
    WriteFile("p/gain.slangp", "shaders = 1\nshader0 = gain.slang\n");
    WriteFile("p/gain.slang", GAIN_SHADER);
    WriteFile("p/probe.slangp", "shaders = 1\nshader0 = probe.slang\n");
    WriteFile("p/probe.slang", PROBE_SHADER);
    // One viewport-sized pass, as bezels end.
    WriteFile("bezel/frame.slangp", "shaders = 1\nshader0 = frame.slang\nscale_type0 = viewport\n");
    WriteFile("bezel/frame.slang", FRAME_SHADER);
    WriteFile("bezel/aspect.slangp", "shaders = 1\nshader0 = aspect.slang\nscale_type0 = viewport\n");
    WriteFile("bezel/aspect.slang", ASPECT_SHADER);
    WriteFile("p/transparent.slangp", "shaders = 1\nshader0 = transparent.slang\n");
    WriteFile("p/transparent.slang", TRANSPARENT_SHADER);
    WriteFile("bezel/transparent.slangp", "shaders = 1\nshader0 = ../p/transparent.slang\nscale_type0 = viewport\n");
  }

  void TearDown() override
  {
    m_shader.reset();
    m_textures.clear();
    if (g_gpu_device)
      PostProcessing::Shutdown(); // the shared samplers go with the device
    GPUTestDevice::Destroy();
    if (!m_root.empty())
      FileSystem::RecursiveDeleteDirectory(m_root.c_str());
  }

  void WriteFile(const std::string& relative, std::string_view contents)
  {
    const std::string path = Path::ToNativePath(Path::Combine(m_root, relative));
    std::error_code ec;
    std::filesystem::create_directories(std::filesystem::path(path).parent_path(), ec);
    ASSERT_TRUE(FileSystem::WriteStringToFile(path.c_str(), contents));
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
    ASSERT_TRUE(m_shader->LoadFromFile(name, Path::ToNativePath(Path::Combine(m_root, name)), false, &error))
      << error.GetDescription();
    MemorySettingsInterface si;
    si.SetFloatValue("Stage", "TEST_GAIN", gain);
    m_shader->LoadOptions(si, "Stage");
    ASSERT_TRUE(m_shader->CompilePipeline(GPUTexture::Format::RGBA8, WIDTH, HEIGHT, nullptr));
  }

  std::vector<u32> Run(GPUTexture* input, const PostProcessing::PictureInfo& picture, u32 width = WIDTH,
                       u32 height = HEIGHT)
  {
    m_textures.push_back(g_gpu_device->CreateTexture(width, height, 1, 1, 1, GPUTexture::Type::RenderTarget,
                                                     GPUTexture::Format::RGBA8));
    GPUTexture* output = m_textures.back().get();
    EXPECT_TRUE(m_shader->Apply(input, nullptr, output, GSVector4i(0, 0, width, height), width, height, NATIVE_WIDTH,
                                NATIVE_HEIGHT, width, height, picture));

    std::vector<u32> pixels(width * height);
    std::unique_ptr<GPUDownloadTexture> dl = g_gpu_device->CreateDownloadTexture(width, height, output->GetFormat());
    EXPECT_TRUE(dl);
    if (!dl)
      return pixels;
    output->MakeReadyForSampling();
    dl->CopyFromTexture(0, 0, output, 0, 0, width, height, 0, 0, true);
    dl->Flush();
    EXPECT_TRUE(dl->ReadTexels(0, 0, width, height, pixels.data(), width * sizeof(u32)));
    return pixels;
  }

  // The display chain's picture at the given display aspect ratio (0: unknown, as the internal chain's).
  static PostProcessing::PictureInfo PictureAt(float aspect)
  {
    PostProcessing::PictureInfo picture = Picture(true);
    picture.aspect_ratio = aspect;
    return picture;
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

TEST_F(SlangGPU, AGamePictureLargerThanTheTargetIsCroppedNotSqueezed)
{
  // Integer scaling in a small window: an 80x64 picture from (0, 0) on the 64x48 target, red over its top half.
  constexpr u32 PW = 80, PH = 64;
  std::vector<u32> frame(PW * PH);
  for (u32 y = 0; y < PH; y++)
    std::fill_n(&frame[y * PW], PW, (y < PH / 2) ? RED_QUARTER : BLUE_QUARTER);
  std::unique_ptr<GPUTexture> game = g_gpu_device->CreateTexture(PW, PH, 1, 1, 1, GPUTexture::Type::Texture,
                                                                 GPUTexture::Format::RGBA8, frame.data(), PW * 4);

  LoadStage("p/gain.slangp", 1.0f);
  PostProcessing::PictureInfo picture;
  picture.rect = GSVector4i(0, 0, PW, PH);
  picture.native_width = PW / 2;
  picture.native_height = PH / 2;
  picture.game_texture = game.get();
  picture.game_view = GSVector4i(0, 0, PW, PH);
  const std::vector<u32> input(WIDTH * HEIGHT, 0xFFFFFFFFu);
  const std::vector<u32> out = Run(Texture(GPUTexture::Type::Texture, &input), picture);

  // Cropped, target row 28 is the picture's row 28 (red); squeezed, it would be row 37 (blue).
  EXPECT_PRED2(Near, At(out, 32, 28), RED_QUARTER) << std::hex << At(out, 32, 28);
  EXPECT_PRED2(Near, At(out, 32, 40), BLUE_QUARTER) << std::hex << At(out, 32, 40);
}

TEST_F(SlangGPU, PresetsFromAPackRun)
{
  // GOOSESTATION_GPU_TEST_PRESETS: absolute .slangp paths separated by '|', e.g. from libretro's slang-shaders.
  const char* list = std::getenv("GOOSESTATION_GPU_TEST_PRESETS");
  if (!list || !*list)
    GTEST_SKIP() << "GOOSESTATION_GPU_TEST_PRESETS isn't set";

  const std::vector<u32> input = InputPixels();
  GPUTexture* tex = Texture(GPUTexture::Type::Texture, &input);
  std::string_view rest(list);
  while (!rest.empty())
  {
    const size_t sep = rest.find('|');
    const std::string path(rest.substr(0, sep));
    rest = (sep == std::string_view::npos) ? std::string_view() : rest.substr(sep + 1);
    SCOPED_TRACE(path);

    m_shader = std::make_unique<PostProcessing::SlangShader>();
    Error error;
    ASSERT_TRUE(m_shader->LoadFromFile(path, path, false, &error)) << error.GetDescription();
    // librashader's own words when the preset doesn't compile here (the stage only shows them on screen).
    ASSERT_TRUE(g_gpu_device->CreateSlangChain(path, false, &error)) << error.GetDescription();
    ASSERT_TRUE(m_shader->CompilePipeline(GPUTexture::Format::RGBA8, WIDTH, HEIGHT, nullptr));

    std::vector<u32> out;
    for (int frame = 0; frame < 3; frame++)
      out = Run(tex, Picture(true));

    EXPECT_EQ(At(out, 2, 2), GREEN);
    u32 lit = 0;
    for (s32 y = PICTURE.top; y < PICTURE.bottom; y++)
    {
      for (s32 x = PICTURE.left; x < PICTURE.right; x++)
        lit += (At(out, x, y) & 0x00FFFFFFu) != 0;
    }
    EXPECT_GT(lit, 0u) << "the preset drew nothing";
  }
}

TEST_F(SlangGPU, PresetsFromAPackKeepDrawingFrameAfterFrame)
{
  // GOOSESTATION_GPU_TEST_PRESETS as above: each preset runs a dozen frames at a small screen's size and the last is
  // drawn (on Metal, the objects librashader makes for a frame once went before the GPU used them, losing frames).
  const char* list = std::getenv("GOOSESTATION_GPU_TEST_PRESETS");
  if (!list || !*list)
    GTEST_SKIP() << "GOOSESTATION_GPU_TEST_PRESETS isn't set";

  constexpr u32 W = 256, H = 144;
  const GSVector4i rect(32, 0, 224, 144);
  std::vector<u32> pixels(W * H, GREEN);
  for (s32 y = rect.top; y < rect.bottom; y++)
    std::fill(&pixels[y * W + rect.left], &pixels[y * W + rect.right], (y < 72) ? RED_QUARTER : BLUE_QUARTER);
  std::unique_ptr<GPUTexture> input = g_gpu_device->CreateTexture(W, H, 1, 1, 1, GPUTexture::Type::Texture,
                                                                  GPUTexture::Format::RGBA8, pixels.data(), W * 4);
  PostProcessing::PictureInfo picture = Picture(true);
  picture.rect = rect;
  picture.native_width = 320;
  picture.native_height = 240;

  std::string_view rest(list);
  while (!rest.empty())
  {
    const size_t sep = rest.find('|');
    const std::string path(rest.substr(0, sep));
    rest = (sep == std::string_view::npos) ? std::string_view() : rest.substr(sep + 1);
    SCOPED_TRACE(path);

    m_shader = std::make_unique<PostProcessing::SlangShader>();
    Error error;
    ASSERT_TRUE(m_shader->LoadFromFile(path, path, false, &error)) << error.GetDescription();
    ASSERT_TRUE(m_shader->CompilePipeline(GPUTexture::Format::RGBA8, W, H, nullptr));
    // Frames in flight, as while playing (reading one back waits for the GPU), then the last read back.
    m_textures.push_back(g_gpu_device->CreateTexture(W, H, 1, 1, 1, GPUTexture::Type::RenderTarget,
                                                     GPUTexture::Format::RGBA8));
    for (int frame = 0; frame < 11; frame++)
    {
      ASSERT_TRUE(m_shader->Apply(input.get(), nullptr, m_textures.back().get(), GSVector4i(0, 0, W, H), W, H, 320,
                                  240, W, H, picture));
    }
    const std::vector<u32> out = Run(input.get(), picture, W, H);
    EXPECT_EQ(out[2 * W + 2], GREEN) << std::hex << out[2 * W + 2];
    u32 lit = 0;
    for (s32 y = rect.top; y < rect.bottom; y++)
    {
      for (s32 x = rect.left; x < rect.right; x++)
        lit += (out[y * W + x] & 0x00FFFFFFu) != 0;
    }
    EXPECT_GT(lit, 0u) << "the preset drew nothing";
  }
}

#ifdef ENABLE_VULKAN
// librashader reuses a frame's temporary objects frames_in_flight frames later, so two chain frames may never share a
// command buffer (a screenshot or video capture renders the display a second time before the frame is presented).
TEST_F(SlangGPU, ASecondFrameBeforeASubmitSubmitsTheFirst)
{
  if (g_gpu_device->GetRenderAPI() != RenderAPI::Vulkan)
    GTEST_SKIP() << "Vulkan only";

  LoadStage("p/gain.slangp", 2.0f);
  const std::vector<u32> input = InputPixels();
  GPUTexture* tex = Texture(GPUTexture::Type::Texture, &input);
  GPUTexture* out = Texture(GPUTexture::Type::RenderTarget, nullptr);
  const GSVector4i full(0, 0, WIDTH, HEIGHT);

  ASSERT_TRUE(m_shader->Apply(tex, nullptr, out, full, WIDTH, HEIGHT, NATIVE_WIDTH, NATIVE_HEIGHT, WIDTH, HEIGHT,
                              Picture(true)));
  const u64 before = VulkanDevice::GetInstance().GetCurrentFenceCounter();
  ASSERT_TRUE(m_shader->Apply(tex, nullptr, out, full, WIDTH, HEIGHT, NATIVE_WIDTH, NATIVE_HEIGHT, WIDTH, HEIGHT,
                              Picture(true)));
  EXPECT_GT(VulkanDevice::GetInstance().GetCurrentFenceCounter(), before);
}
#endif

TEST_F(SlangGPU, ABezelDrawsOverTheWholeScreen)
{
  LoadStage("bezel/frame.slangp", 1.0f);
  const std::vector<u32> input = InputPixels();
  const std::vector<u32> out = Run(Texture(GPUTexture::Type::Texture, &input), PictureAt(4.0f / 3.0f));
  EXPECT_TRUE(m_shader->DrewWholeScreen());
  EXPECT_EQ(At(out, 2, 2), MAGENTA) << std::hex << At(out, 2, 2);
  EXPECT_EQ(At(out, 61, 45), MAGENTA) << std::hex << At(out, 61, 45);
  // The box shows the picture: red over its top half, blue below.
  EXPECT_PRED2(Near, At(out, 32, 16), RED_QUARTER) << std::hex << At(out, 32, 16);
  EXPECT_PRED2(Near, At(out, 32, 32), BLUE_QUARTER) << std::hex << At(out, 32, 32);
}

TEST_F(SlangGPU, ABezelReceivesTheAspect)
{
  LoadStage("bezel/aspect.slangp", 1.0f);
  const std::vector<u32> input = InputPixels();
  GPUTexture* tex = Texture(GPUTexture::Type::Texture, &input);
  std::vector<u32> out = Run(tex, PictureAt(4.0f / 3.0f));
  EXPECT_PRED2(Near, At(out, 2, 2), 0xFF0000AAu) << std::hex << At(out, 2, 2);
  // Auto with a small crop: still 4:3, told the picture's own shape.
  out = Run(tex, PictureAt(1.31f));
  EXPECT_TRUE(m_shader->DrewWholeScreen());
  EXPECT_PRED2(Near, At(out, 2, 2), 0xFF0000A7u) << std::hex << At(out, 2, 2);
}

TEST_F(SlangGPU, ANonFourThreePictureFallsBackIntoThePicture)
{
  LoadStage("bezel/frame.slangp", 1.0f);
  const std::vector<u32> input = InputPixels();
  const std::vector<u32> out = Run(Texture(GPUTexture::Type::Texture, &input), PictureAt(16.0f / 9.0f));
  EXPECT_FALSE(m_shader->DrewWholeScreen());
  EXPECT_EQ(At(out, 2, 2), GREEN) << std::hex << At(out, 2, 2);
  EXPECT_EQ(At(out, 60, 44), GREEN) << std::hex << At(out, 60, 44);
  // Inside the picture, its own frame and box, squeezed into the picture as before.
  EXPECT_EQ(At(out, 10, 10), MAGENTA) << std::hex << At(out, 10, 10);
}

TEST_F(SlangGPU, AStageDrawingIntoThePictureIsUnchanged)
{
  LoadStage("p/gain.slangp", 2.0f);
  const std::vector<u32> input = InputPixels();
  GPUTexture* tex = Texture(GPUTexture::Type::Texture, &input);
  const std::vector<u32> unknown = Run(tex, PictureAt(0.0f));
  const std::vector<u32> four_three = Run(tex, PictureAt(4.0f / 3.0f));
  EXPECT_FALSE(m_shader->DrewWholeScreen());
  EXPECT_EQ(unknown, four_three);
  EXPECT_EQ(At(four_three, 2, 2), GREEN);
}

TEST_F(SlangGPU, AWholeScreenStageFillsAnyTargetShape)
{
  // A portrait window: the preset's viewport is the whole of it.
  constexpr u32 W = 48, H = 64;
  LoadStage("bezel/frame.slangp", 1.0f);
  const std::vector<u32> input = InputPixels();
  PostProcessing::PictureInfo picture = PictureAt(4.0f / 3.0f);
  picture.rect = GSVector4i(0, 14, 48, 50);
  const std::vector<u32> out = Run(Texture(GPUTexture::Type::Texture, &input), picture, W, H);
  for (const auto [x, y] : {std::pair<u32, u32>{1, 1}, {46, 1}, {1, 62}, {46, 62}})
    EXPECT_EQ(out[y * W + x], MAGENTA) << x << "," << y << " " << std::hex << out[y * W + x];
}

TEST_F(SlangGPU, ThePlacementFollowsTheAspectEachFrame)
{
  LoadStage("bezel/frame.slangp", 1.0f);
  const std::vector<u32> input = InputPixels();
  GPUTexture* tex = Texture(GPUTexture::Type::Texture, &input);
  EXPECT_EQ(At(Run(tex, PictureAt(4.0f / 3.0f)), 2, 2), MAGENTA);
  // Nothing of the whole-screen frame is left in the bars.
  EXPECT_EQ(At(Run(tex, PictureAt(16.0f / 9.0f)), 2, 2), GREEN);
  EXPECT_EQ(At(Run(tex, PictureAt(4.0f / 3.0f)), 2, 2), MAGENTA);
}

TEST_F(SlangGPU, TheSwitchOffKeepsABezelInThePicture)
{
  LoadStage("bezel/frame.slangp", 1.0f);
  MemorySettingsInterface si;
  si.SetBoolValue("Stage", PostProcessing::Config::WHOLE_SCREEN_KEY, false);
  m_shader->LoadOptions(si, "Stage");
  ASSERT_TRUE(m_shader->CompilePipeline(GPUTexture::Format::RGBA8, WIDTH, HEIGHT, nullptr));
  const std::vector<u32> input = InputPixels();
  const std::vector<u32> out = Run(Texture(GPUTexture::Type::Texture, &input), PictureAt(4.0f / 3.0f));
  EXPECT_FALSE(m_shader->DrewWholeScreen());
  EXPECT_EQ(At(out, 2, 2), GREEN) << std::hex << At(out, 2, 2);

  // And on again, a stage that isn't in a bezel folder.
  LoadStage("p/probe.slangp", 1.0f);
  si.SetBoolValue("Stage", PostProcessing::Config::WHOLE_SCREEN_KEY, true);
  m_shader->LoadOptions(si, "Stage");
  ASSERT_TRUE(m_shader->CompilePipeline(GPUTexture::Format::RGBA8, WIDTH, HEIGHT, nullptr));
  const std::vector<u32> probe = Run(Texture(GPUTexture::Type::Texture, &input), PictureAt(4.0f / 3.0f));
  EXPECT_TRUE(m_shader->DrewWholeScreen());
  EXPECT_EQ(At(probe, 2, 2), 0xFF0000FFu) << std::hex << At(probe, 2, 2);
}

TEST_F(SlangGPU, TheGamesOwnFrameFeedsAWholeScreenBezelUncropped)
{
  // An 80x64 game picture, larger than the 64x48 target (integer scaling in a small window), red over its top half:
  // a bezel gets all of it, as integer scaling doesn't apply under a bezel.
  constexpr u32 PW = 80, PH = 64;
  std::vector<u32> frame(PW * PH);
  for (u32 y = 0; y < PH; y++)
    std::fill_n(&frame[y * PW], PW, (y < PH / 2) ? RED_QUARTER : BLUE_QUARTER);
  std::unique_ptr<GPUTexture> game = g_gpu_device->CreateTexture(PW, PH, 1, 1, 1, GPUTexture::Type::Texture,
                                                                 GPUTexture::Format::RGBA8, frame.data(), PW * 4);

  LoadStage("bezel/frame.slangp", 1.0f);
  PostProcessing::PictureInfo picture = PictureAt(4.0f / 3.0f);
  picture.rect = GSVector4i(0, 0, PW, PH);
  picture.native_width = PW / 2;
  picture.native_height = PH / 2;
  picture.game_texture = game.get();
  picture.game_view = GSVector4i(0, 0, PW, PH);
  const std::vector<u32> input(WIDTH * HEIGHT, 0xFFFFFFFFu);
  const std::vector<u32> out = Run(Texture(GPUTexture::Type::Texture, &input), picture);

  // Box row 26 is 60% down the box: the whole picture's row 38 (blue); cropped to the target it would be row 29 (red).
  EXPECT_PRED2(Near, At(out, 32, 26), BLUE_QUARTER) << std::hex << At(out, 32, 26);
  EXPECT_EQ(At(out, 2, 2), MAGENTA) << std::hex << At(out, 2, 2);
}

TEST_F(SlangGPU, APresetsTransparentOutputIsDrawnOpaque)
{
  // koko-aio writes alpha 0, which a window or a capture with alpha shows as a blank (white) picture: the stage's
  // result is drawn opaque, as the game's picture is, in the picture and over the whole screen.
  const std::vector<u32> input = InputPixels();
  GPUTexture* tex = Texture(GPUTexture::Type::Texture, &input);

  LoadStage("p/transparent.slangp", 1.0f);
  std::vector<u32> out = Run(tex, PictureAt(4.0f / 3.0f));
  EXPECT_PRED2(Near, At(out, 32, 12), RED_QUARTER) << std::hex << At(out, 32, 12);
  EXPECT_EQ(At(out, 2, 2), GREEN);

  LoadStage("bezel/transparent.slangp", 1.0f);
  out = Run(tex, PictureAt(4.0f / 3.0f));
  EXPECT_TRUE(m_shader->DrewWholeScreen());
  EXPECT_EQ(At(out, 2, 2) >> 24, 0xFFu) << std::hex << At(out, 2, 2);
  EXPECT_EQ(At(out, 32, 24) >> 24, 0xFFu) << std::hex << At(out, 32, 24);
}

namespace {

// Halves the picture, drawn into final_rect as GLSL stages are.
constexpr const char* HALF_GLSL = "void main() { SetOutput(Sample() * 0.5); }\n";

} // namespace

// A chain of stages through PostProcessing::Chain, as the display draws it, into a WIDTH x HEIGHT target.
static std::vector<u32> RunChain(std::vector<std::unique_ptr<PostProcessing::Shader>> stages, GPUTexture* input,
                                 const PostProcessing::PictureInfo& picture)
{
  PostProcessing::Chain chain("TestChain");
  chain.SetStagesForTest(std::move(stages));
  EXPECT_TRUE(chain.CheckTargets(GPUTexture::Format::RGBA8, WIDTH, HEIGHT));
  std::unique_ptr<GPUTexture> output =
    g_gpu_device->CreateTexture(WIDTH, HEIGHT, 1, 1, 1, GPUTexture::Type::RenderTarget, GPUTexture::Format::RGBA8);
  EXPECT_TRUE(chain.Apply(input, nullptr, output.get(), picture.rect, WIDTH, HEIGHT, NATIVE_WIDTH, NATIVE_HEIGHT,
                          picture));
  std::vector<u32> pixels(WIDTH * HEIGHT);
  std::unique_ptr<GPUDownloadTexture> dl = g_gpu_device->CreateDownloadTexture(WIDTH, HEIGHT, output->GetFormat());
  output->MakeReadyForSampling();
  dl->CopyFromTexture(0, 0, output.get(), 0, 0, WIDTH, HEIGHT, 0, 0, true);
  dl->Flush();
  EXPECT_TRUE(dl->ReadTexels(0, 0, WIDTH, HEIGHT, pixels.data(), WIDTH * sizeof(u32)));
  chain.DestroyTextures();
  return pixels;
}

TEST_F(SlangGPU, AStageAfterABezelKeepsTheBars)
{
  std::vector<std::unique_ptr<PostProcessing::Shader>> stages;
  for (const char* name : {"bezel/frame.slangp", "p/gain.slangp"})
  {
    auto slang = std::make_unique<PostProcessing::SlangShader>();
    Error error;
    ASSERT_TRUE(slang->LoadFromFile(name, Path::ToNativePath(Path::Combine(m_root, name)), false, &error));
    MemorySettingsInterface si;
    si.SetFloatValue("Stage", "TEST_GAIN", 2.0f);
    slang->LoadOptions(si, "Stage");
    stages.push_back(std::move(slang));
    if (stages.size() == 1)
    {
      auto glsl = std::make_unique<PostProcessing::GLSLShader>();
      ASSERT_TRUE(glsl->LoadFromString("half", HALF_GLSL, &error)) << error.GetDescription();
      stages.push_back(std::move(glsl));
    }
  }

  const std::vector<u32> input = InputPixels();
  const std::vector<u32> out =
    RunChain(std::move(stages), Texture(GPUTexture::Type::Texture, &input), PictureAt(4.0f / 3.0f));
  // The bezel's magenta, halved by the GLSL stage and doubled by the slang one, in the bars too.
  EXPECT_PRED2(Near, At(out, 2, 2), 0xFFFE00FEu) << std::hex << At(out, 2, 2);
  EXPECT_PRED2(Near, At(out, 61, 45), 0xFFFE00FEu) << std::hex << At(out, 61, 45);
  EXPECT_PRED2(Near, At(out, 32, 16), RED_QUARTER) << std::hex << At(out, 32, 16);
}

TEST_F(SlangGPU, ANonBezelChainIsUnchanged)
{
  std::vector<std::unique_ptr<PostProcessing::Shader>> stages;
  Error error;
  auto glsl = std::make_unique<PostProcessing::GLSLShader>();
  ASSERT_TRUE(glsl->LoadFromString("half", HALF_GLSL, &error)) << error.GetDescription();
  stages.push_back(std::move(glsl));
  auto slang = std::make_unique<PostProcessing::SlangShader>();
  ASSERT_TRUE(slang->LoadFromFile("p/gain.slangp", Path::ToNativePath(Path::Combine(m_root, "p/gain.slangp")), false,
                                  &error));
  MemorySettingsInterface si;
  si.SetFloatValue("Stage", "TEST_GAIN", 2.0f);
  slang->LoadOptions(si, "Stage");
  stages.push_back(std::move(slang));

  const std::vector<u32> input = InputPixels();
  const std::vector<u32> out =
    RunChain(std::move(stages), Texture(GPUTexture::Type::Texture, &input), PictureAt(4.0f / 3.0f));
  // As before bezels: the GLSL stage draws only the picture (the bars cleared), the slang stage shades the picture.
  EXPECT_EQ(At(out, 2, 2), 0u) << std::hex << At(out, 2, 2);
  EXPECT_PRED2(Near, At(out, 32, 12), RED_QUARTER) << std::hex << At(out, 32, 12);
  EXPECT_PRED2(Near, At(out, 32, 36), BLUE_QUARTER) << std::hex << At(out, 32, 36);
}

namespace {

// The frame dump's target and console picture.
constexpr u32 DUMP_W = 256, DUMP_H = 144;
constexpr u32 DUMP_NATIVE_W = 320, DUMP_NATIVE_H = 240;
constexpr int DUMP_FRAMES = 12;

const std::string DUMP_GLSL = "void main()\n"
                              "{\n"
                              "  float4 c = Sample();\n"
                              "  SetOutput(float4(c.rgb * 0.75 + float3(0.1, 0.0, 0.05), c.a));\n"
                              "}\n";

const std::string DUMP_FX = R"(
texture BackBufferTex : COLOR;
sampler BackBuffer { Texture = BackBufferTex; };
void FullscreenVS(in uint id : SV_VertexID, out float4 position : SV_Position, out float2 texcoord : TEXCOORD)
{
  texcoord.x = (id == 2) ? 2.0 : 0.0;
  texcoord.y = (id == 1) ? 2.0 : 0.0;
  position = float4(texcoord * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
}
float4 TintPS(float4 position : SV_Position, float2 texcoord : TEXCOORD) : SV_Target
{
  return float4(tex2D(BackBuffer, texcoord).gbr, 1.0);
}
technique Tint { pass { VertexShader = FullscreenVS; PixelShader = TintPS; } }
)";

// Where the picture sits for a dump case, and how it reaches the chain.
struct DumpPlacement
{
  const char* name;
  GSVector4i rect;
  u32 turns; // quarter turns clockwise of the console's picture
  bool from_game; // the first stage reads the game's own frame, as the presenter does unrotated
};

// The console's picture: GOOSESTATION_GPU_TEST_PICTURE (any image, scaled nearest) or a pattern with a gradient, a
// grid and a marked top-left corner, so a flip, a turn or a shift shows.
std::vector<u32> DumpNativePicture()
{
  std::vector<u32> px(DUMP_NATIVE_W * DUMP_NATIVE_H);
  RGBA8Image image;
  const char* path = std::getenv("GOOSESTATION_GPU_TEST_PICTURE");
  const bool loaded = path && *path && image.LoadFromFile(path);
  for (u32 y = 0; y < DUMP_NATIVE_H; y++)
  {
    for (u32 x = 0; x < DUMP_NATIVE_W; x++)
    {
      u32 c;
      if (loaded)
      {
        c = image.GetPixel(x * image.GetWidth() / DUMP_NATIVE_W, y * image.GetHeight() / DUMP_NATIVE_H) | 0xFF000000u;
      }
      else
      {
        const bool grid = (x % 32) == 0 || (y % 32) == 0;
        const bool corner = x < 40 && y < 30;
        c = 0xFF000000u | ((x * 255 / DUMP_NATIVE_W) & 0xFF) | (((y * 255 / DUMP_NATIVE_H) & 0xFF) << 8) |
            ((grid ? 0xFFu : (corner ? 0xC0u : 0x30u)) << 16);
      }
      px[y * DUMP_NATIVE_W + x] = c;
    }
  }
  return px;
}

// The picture turned quarter-turns clockwise; *w and *h are its new size.
std::vector<u32> Turn(const std::vector<u32>& px, u32 turns, u32* w, u32* h)
{
  std::vector<u32> cur = px;
  for (u32 t = 0; t < turns; t++)
  {
    std::vector<u32> next(cur.size());
    for (u32 y = 0; y < *h; y++)
      for (u32 x = 0; x < *w; x++)
        next[x * *h + (*h - 1 - y)] = cur[y * *w + x];
    std::swap(*w, *h);
    cur = std::move(next);
  }
  return cur;
}

// The window-sized input the presenter draws: black, and the (turned) picture scaled nearest into rect, clipped.
std::vector<u32> DrawnInput(const std::vector<u32>& pic, u32 pw, u32 ph, const GSVector4i& rect)
{
  std::vector<u32> out(DUMP_W * DUMP_H, 0xFF000000u);
  for (s32 y = std::max(rect.top, 0); y < std::min<s32>(rect.bottom, DUMP_H); y++)
  {
    for (s32 x = std::max(rect.left, 0); x < std::min<s32>(rect.right, DUMP_W); x++)
    {
      const u32 sx = static_cast<u32>((x - rect.left) * static_cast<s32>(pw) / rect.width());
      const u32 sy = static_cast<u32>((y - rect.top) * static_cast<s32>(ph) / rect.height());
      out[y * DUMP_W + x] = pic[sy * pw + sx];
    }
  }
  return out;
}

} // namespace

TEST_F(SlangGPU, DumpChainFrames)
{
  // GOOSESTATION_GPU_TEST_DUMP: a folder for the frames, compared before and after a change (raw RGBA, plus PNG to
  // look at). GOOSESTATION_GPU_TEST_PRESETS: absolute .slangp paths separated by '|'.
  const char* dump = std::getenv("GOOSESTATION_GPU_TEST_DUMP");
  if (!dump || !*dump)
    GTEST_SKIP() << "GOOSESTATION_GPU_TEST_DUMP isn't set";
  ASSERT_TRUE(FileSystem::EnsureDirectoryExists(dump, true));
  Log::SetConsoleOutputParams(true, false); // a stage that fails to build says why
  const std::string api = GPUDevice::RenderAPIToString(g_gpu_device->GetRenderAPI());

  struct StageSpec
  {
    std::string label;
    std::string slang_path; // empty: GLSL or FX by label
  };
  WriteFile("dump-tint.fx", DUMP_FX);
  std::vector<StageSpec> specs = {{"glsl", {}}, {"fx", {}}};
  if (const char* list = std::getenv("GOOSESTATION_GPU_TEST_PRESETS"))
  {
    std::string_view rest(list);
    while (!rest.empty())
    {
      const size_t sep = rest.find('|');
      const std::string path(rest.substr(0, sep));
      rest = (sep == std::string_view::npos) ? std::string_view() : rest.substr(sep + 1);
      std::string label(Path::GetFileTitle(path));
      specs.push_back({std::move(label), path});
    }
  }

  const auto make_stage = [&](const StageSpec& spec) -> std::unique_ptr<PostProcessing::Shader> {
    Error error;
    if (spec.label == "glsl" && spec.slang_path.empty())
    {
      auto s = std::make_unique<PostProcessing::GLSLShader>();
      EXPECT_TRUE(s->LoadFromString("dump-tint", DUMP_GLSL, &error)) << error.GetDescription();
      return s;
    }
    if (spec.label == "fx" && spec.slang_path.empty())
    {
      auto s = std::make_unique<PostProcessing::ReShadeFXShader>();
      // A ReShade stage reads its file again when it's built.
      EXPECT_TRUE(s->LoadFromFile("dump-tint", Path::Combine(m_root, "dump-tint.fx"), false, &error))
        << error.GetDescription();
      return s;
    }
    auto s = std::make_unique<PostProcessing::SlangShader>();
    EXPECT_TRUE(s->LoadFromFile(spec.slang_path, spec.slang_path, false, &error)) << error.GetDescription();
    return s;
  };

  const DumpPlacement placements[] = {
    {"centred", GSVector4i(32, 0, 224, 144), 0, true},
    {"centred-drawn", GSVector4i(32, 0, 224, 144), 0, false},
    {"integer", GSVector4i(-32, -48, 288, 192), 0, true},
    {"cropped", GSVector4i(38, 12, 218, 132), 0, false},
    {"turned180", GSVector4i(32, 0, 224, 144), 2, false},
    {"turned90", GSVector4i(74, 0, 182, 144), 1, false},
  };

  const std::vector<u32> native = DumpNativePicture();
  std::unique_ptr<GPUTexture> game = g_gpu_device->CreateTexture(
    DUMP_NATIVE_W, DUMP_NATIVE_H, 1, 1, 1, GPUTexture::Type::Texture, GPUTexture::Format::RGBA8, native.data(),
    DUMP_NATIVE_W * sizeof(u32));
  ASSERT_TRUE(game);

  // Each stage alone, then every stage as one chain.
  std::vector<std::vector<size_t>> chains;
  for (size_t i = 0; i < specs.size(); i++)
    chains.push_back({i});
  if (specs.size() > 1)
  {
    chains.emplace_back();
    for (size_t i = 0; i < specs.size(); i++)
      chains.back().push_back(i);
  }

  for (const std::vector<size_t>& chain_specs : chains)
  {
#ifdef __APPLE__
    // ReShade FX's GLSL uses binding layouts, which Apple's OpenGL 4.1 lacks (not new here).
    const bool skip_fx = (g_gpu_device->GetRenderAPI() == RenderAPI::OpenGL);
#else
    const bool skip_fx = false;
#endif
    std::string chain_label = (chain_specs.size() > 1) ? std::string("all") : specs[chain_specs[0]].label;
    if (skip_fx && chain_label == "fx")
      continue;

    for (const DumpPlacement& place : placements)
    {
      SCOPED_TRACE(chain_label + " " + place.name);
      PostProcessing::Chain chain("DumpChain");
      std::vector<std::unique_ptr<PostProcessing::Shader>> stages;
      for (size_t i : chain_specs)
      {
        if (skip_fx && specs[i].label == "fx")
          continue;
        stages.push_back(make_stage(specs[i]));
      }
      chain.SetStagesForTest(std::move(stages));
      ASSERT_TRUE(chain.CheckTargets(GPUTexture::Format::RGBA8, DUMP_W, DUMP_H));

      u32 pw = DUMP_NATIVE_W, ph = DUMP_NATIVE_H;
      const std::vector<u32> turned = Turn(native, place.turns, &pw, &ph);
      const std::vector<u32> drawn = DrawnInput(turned, pw, ph, place.rect);
      std::unique_ptr<GPUTexture> input =
        g_gpu_device->CreateTexture(DUMP_W, DUMP_H, 1, 1, 1, GPUTexture::Type::Texture, GPUTexture::Format::RGBA8,
                                    drawn.data(), DUMP_W * sizeof(u32));
      std::unique_ptr<GPUTexture> output = g_gpu_device->CreateTexture(
        DUMP_W, DUMP_H, 1, 1, 1, GPUTexture::Type::RenderTarget, GPUTexture::Format::RGBA8);
      ASSERT_TRUE(input && output);

      PostProcessing::PictureInfo picture;
      picture.rect = place.rect;
      picture.native_width = pw;
      picture.native_height = ph;
      picture.rows_top_first = true;
      if (place.from_game)
      {
        picture.game_texture = game.get();
        picture.game_view = GSVector4i(0, 0, DUMP_NATIVE_W, DUMP_NATIVE_H);
      }

      std::vector<u32> pixels(DUMP_W * DUMP_H);
      // Enough frames for presets that start from their own history; on Metal a new chain's first frames can come
      // out black or wrong (not settled why: librashader's LUT uploads, or its history, starting late).
      for (int frame = 0; frame < DUMP_FRAMES; frame++)
      {
        ASSERT_TRUE(chain.Apply(input.get(), nullptr, output.get(), place.rect, DUMP_NATIVE_W, DUMP_NATIVE_H,
                                DUMP_NATIVE_W, DUMP_NATIVE_H, picture));
      }
      std::unique_ptr<GPUDownloadTexture> dl =
        g_gpu_device->CreateDownloadTexture(DUMP_W, DUMP_H, GPUTexture::Format::RGBA8);
      ASSERT_TRUE(dl);
      output->MakeReadyForSampling();
      dl->CopyFromTexture(0, 0, output.get(), 0, 0, DUMP_W, DUMP_H, 0, 0, true);
      dl->Flush();
      ASSERT_TRUE(dl->ReadTexels(0, 0, DUMP_W, DUMP_H, pixels.data(), DUMP_W * sizeof(u32)));

      const std::string base = Path::Combine(dump, fmt::format("{}-{}-{}", api, chain_label, place.name));
      ASSERT_TRUE(FileSystem::WriteBinaryFile((base + ".rgba").c_str(), pixels.data(), pixels.size() * sizeof(u32)));
      for (u32& p : pixels)
        p |= 0xFF000000u;
      RGBA8Image(DUMP_W, DUMP_H, pixels.data()).SaveToFile((base + ".png").c_str());

      chain.DestroyTextures();
    }
  }
}

TEST_F(SlangGPU, BezelFramesAtTheScreensSize)
{
  // GOOSESTATION_GPU_TEST_SHOTS: a folder for PNGs of each preset in GOOSESTATION_GPU_TEST_PRESETS drawn as the display
  // chain draws it on a 1920x1080 screen, from the game's own frame (GOOSESTATION_GPU_TEST_PICTURE, else a pattern):
  // a 4:3 picture and a 16:9 one. For looking at, and for the numbers the bezels' checks read.
  const char* shots = std::getenv("GOOSESTATION_GPU_TEST_SHOTS");
  const char* list = std::getenv("GOOSESTATION_GPU_TEST_PRESETS");
  if (!shots || !*shots || !list || !*list)
    GTEST_SKIP() << "GOOSESTATION_GPU_TEST_SHOTS or GOOSESTATION_GPU_TEST_PRESETS isn't set";
  ASSERT_TRUE(FileSystem::EnsureDirectoryExists(shots, true));
  Log::SetConsoleOutputParams(true, false);
  const std::string api = GPUDevice::RenderAPIToString(g_gpu_device->GetRenderAPI());

  constexpr u32 W = 1920, H = 1080;
  const std::vector<u32> native = DumpNativePicture();
  std::unique_ptr<GPUTexture> game = g_gpu_device->CreateTexture(
    DUMP_NATIVE_W, DUMP_NATIVE_H, 1, 1, 1, GPUTexture::Type::Texture, GPUTexture::Format::RGBA8, native.data(),
    DUMP_NATIVE_W * sizeof(u32));
  std::unique_ptr<GPUTexture> input =
    g_gpu_device->CreateTexture(W, H, 1, 1, 1, GPUTexture::Type::RenderTarget, GPUTexture::Format::RGBA8);
  std::unique_ptr<GPUTexture> output =
    g_gpu_device->CreateTexture(W, H, 1, 1, 1, GPUTexture::Type::RenderTarget, GPUTexture::Format::RGBA8);
  ASSERT_TRUE(game && input && output);

  struct Shape
  {
    const char* name;
    float aspect;
    GSVector4i rect;
  };
  const Shape shapes[] = {
    {"4x3", 4.0f / 3.0f, GSVector4i(240, 0, 1680, 1080)},
    {"16x9", 16.0f / 9.0f, GSVector4i(0, 0, 1920, 1080)},
  };

  std::string_view rest(list);
  while (!rest.empty())
  {
    const size_t sep = rest.find('|');
    const std::string path(rest.substr(0, sep));
    rest = (sep == std::string_view::npos) ? std::string_view() : rest.substr(sep + 1);
    SCOPED_TRACE(path);

    for (const Shape& shape : shapes)
    {
      // The stage is named as the pack installs it, so a bezel is one by its folder.
      std::string name = path;
      std::replace(name.begin(), name.end(), '\\', '/');
      if (const size_t at = name.find("/bezel/"); at != std::string::npos)
        name = name.substr(at + 1);
      auto stage = std::make_unique<PostProcessing::SlangShader>();
      Error error;
      ASSERT_TRUE(stage->LoadFromFile(name, path, false, &error)) << error.GetDescription();
      PostProcessing::Chain chain("ShotChain");
      std::vector<std::unique_ptr<PostProcessing::Shader>> stages;
      stages.push_back(std::move(stage));
      chain.SetStagesForTest(std::move(stages));
      ASSERT_TRUE(chain.CheckTargets(GPUTexture::Format::RGBA8, W, H));

      PostProcessing::PictureInfo picture;
      picture.rect = shape.rect;
      picture.native_width = DUMP_NATIVE_W;
      picture.native_height = DUMP_NATIVE_H;
      picture.game_texture = game.get();
      picture.game_view = GSVector4i(0, 0, DUMP_NATIVE_W, DUMP_NATIVE_H);
      picture.rows_top_first = true;
      picture.aspect_ratio = shape.aspect;
      for (int frame = 0; frame < DUMP_FRAMES; frame++)
      {
        ASSERT_TRUE(chain.Apply(input.get(), nullptr, output.get(), shape.rect, DUMP_NATIVE_W, DUMP_NATIVE_H,
                                DUMP_NATIVE_W, DUMP_NATIVE_H, picture));
      }

      std::vector<u32> pixels(W * H);
      std::unique_ptr<GPUDownloadTexture> dl = g_gpu_device->CreateDownloadTexture(W, H, GPUTexture::Format::RGBA8);
      ASSERT_TRUE(dl);
      output->MakeReadyForSampling();
      dl->CopyFromTexture(0, 0, output.get(), 0, 0, W, H, 0, 0, true);
      dl->Flush();
      ASSERT_TRUE(dl->ReadTexels(0, 0, W, H, pixels.data(), W * sizeof(u32)));
      chain.DestroyTextures();

      // The share of the side bars (the screen outside the 4:3 picture) that isn't black: a bezel draws there.
      u32 lit = 0, bar = 0;
      for (u32 y = 0; y < H; y += 4)
      {
        for (u32 x = 0; x < W; x += 4)
        {
          if (x >= 240 && x < 1680)
            continue;
          bar++;
          lit += (pixels[y * W + x] & 0x00FFFFFFu) > 0x00101010u;
        }
      }
      std::printf("SHOT %s %s %s bars lit %.0f%%\n", api.c_str(), Path::GetFileTitle(path).data(), shape.name,
                  100.0 * lit / std::max(bar, 1u));
      const std::string file =
        Path::Combine(shots, fmt::format("{}-{}-{}.png", api, Path::GetFileTitle(path), shape.name));
      RGBA8Image(W, H, pixels.data()).SaveToFile(file.c_str());
    }
  }
}
