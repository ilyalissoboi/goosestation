// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/gpu_device.h"
#include "util/gpu_pipeline_batch.h"

#include <gtest/gtest.h>

#include <cctype>
#include <algorithm>
#include <functional>
#include <unordered_map>
#include <span>
#include <string>
#include <vector>

namespace {

using Batch = GPUPipelineBatch;

constexpr GPUPipeline::VertexAttribute ATTRIBUTES[] = {
  GPUPipeline::VertexAttribute::Make(0, GPUPipeline::VertexAttribute::Semantic::Position, 0,
                                     GPUPipeline::VertexAttribute::Type::Float, 4, 0),
  GPUPipeline::VertexAttribute::Make(1, GPUPipeline::VertexAttribute::Semantic::Color, 0,
                                     GPUPipeline::VertexAttribute::Type::UNorm8, 4, 16),
};

GPUPipeline::GraphicsConfig BaseConfig()
{
  GPUPipeline::GraphicsConfig c = {};
  c.layout = GPUPipeline::Layout::SingleTextureAndUBO;
  c.primitive = GPUPipeline::Primitive::Triangles;
  c.input_layout.vertex_attributes = ATTRIBUTES;
  c.input_layout.vertex_stride = 20;
  c.rasterization = GPUPipeline::RasterizationState::GetNoCullState();
  c.depth = GPUPipeline::DepthState::GetNoTestsState();
  c.blend = GPUPipeline::BlendState::GetNoBlendingState();
  c.SetTargetFormats(GPUTexture::Format::RGBA8);
  c.samples = 1;
  c.per_sample_shading = false;
  c.render_pass_flags = GPUPipeline::NoRenderPassFlags;
  return c;
}

struct Fixture
{
  Batch batch;
  Batch::Handle vs;
  Batch::Handle fs;
  Batch::Handle fs2;
  GPUPipeline* slots[2] = {};

  Fixture()
  {
    vs = batch.AddShader(GPUShaderStage::Vertex, GPUShaderLanguage::GLSL, "vs");
    fs = batch.AddShader(GPUShaderStage::Fragment, GPUShaderLanguage::GLSL, "fs");
    fs2 = batch.AddShader(GPUShaderStage::Fragment, GPUShaderLanguage::GLSL, "fs2");
  }

  u32 PipelinesFor(const GPUPipeline::GraphicsConfig& a, const GPUPipeline::GraphicsConfig& b,
                   Batch::Handle a_fs, Batch::Handle b_fs)
  {
    batch.AddPipeline(a, vs, a_fs, Batch::NONE, &slots[0]);
    batch.AddPipeline(b, vs, b_fs, Batch::NONE, &slots[1]);
    return batch.GetUniquePipelineCount();
  }
};

TEST(PipelineBatch, SameSourceIsOneShader)
{
  Batch batch;
  const Batch::Handle a = batch.AddShader(GPUShaderStage::Fragment, GPUShaderLanguage::GLSL, "void main() {}");
  EXPECT_EQ(batch.AddShader(GPUShaderStage::Fragment, GPUShaderLanguage::GLSL, "void main() {}"), a);
  EXPECT_NE(batch.AddShader(GPUShaderStage::Vertex, GPUShaderLanguage::GLSL, "void main() {}"), a);
  EXPECT_NE(batch.AddShader(GPUShaderStage::Fragment, GPUShaderLanguage::GLSLES, "void main() {}"), a);
  EXPECT_NE(batch.AddShader(GPUShaderStage::Fragment, GPUShaderLanguage::GLSL, "void main() {}", "other"), a);
  EXPECT_NE(batch.AddShader(GPUShaderStage::Fragment, GPUShaderLanguage::GLSL, "void main() { }"), a);
  EXPECT_EQ(batch.GetUniqueShaderCount(), 5u);
}

TEST(PipelineBatch, EqualConfigsAreOnePipeline)
{
  Fixture f;
  EXPECT_EQ(f.PipelinesFor(BaseConfig(), BaseConfig(), f.fs, f.fs), 1u);
  EXPECT_EQ(f.batch.GetSlotCount(), 2u);
}

TEST(PipelineBatch, ShaderPointersInTheConfigAreIgnored)
{
  Fixture f;
  GPUPipeline::GraphicsConfig a = BaseConfig();
  GPUPipeline::GraphicsConfig b = BaseConfig();
  a.fragment_shader = reinterpret_cast<GPUShader*>(0x1000);
  b.fragment_shader = reinterpret_cast<GPUShader*>(0x2000);
  EXPECT_EQ(f.PipelinesFor(a, b, f.fs, f.fs), 1u);
}

struct FieldCase
{
  const char* name;
  std::function<void(GPUPipeline::GraphicsConfig&)> change;
};

class PipelineBatchField : public ::testing::TestWithParam<FieldCase>
{
};

TEST_P(PipelineBatchField, AnyFieldMakesItDifferent)
{
  Fixture f;
  GPUPipeline::GraphicsConfig b = BaseConfig();
  GetParam().change(b);
  EXPECT_EQ(f.PipelinesFor(BaseConfig(), b, f.fs, f.fs), 2u) << GetParam().name;
}

constexpr GPUPipeline::VertexAttribute OTHER_ATTRIBUTES[] = {
  GPUPipeline::VertexAttribute::Make(0, GPUPipeline::VertexAttribute::Semantic::Position, 0,
                                     GPUPipeline::VertexAttribute::Type::Float, 4, 0),
  GPUPipeline::VertexAttribute::Make(1, GPUPipeline::VertexAttribute::Semantic::Color, 0,
                                     GPUPipeline::VertexAttribute::Type::UNorm8, 4, 20),
};

INSTANTIATE_TEST_SUITE_P(
  PipelineBatch, PipelineBatchField,
  ::testing::Values(
    FieldCase{"layout", [](auto& c) { c.layout = GPUPipeline::Layout::MultiTextureAndUBO; }},
    FieldCase{"primitive", [](auto& c) { c.primitive = GPUPipeline::Primitive::Lines; }},
    FieldCase{"attribute", [](auto& c) { c.input_layout.vertex_attributes = OTHER_ATTRIBUTES; }},
    FieldCase{"attribute count",
              [](auto& c) { c.input_layout.vertex_attributes = std::span(ATTRIBUTES).subspan(0, 1); }},
    FieldCase{"stride", [](auto& c) { c.input_layout.vertex_stride = 24; }},
    FieldCase{"rasterization", [](auto& c) { c.rasterization.cull_mode = GPUPipeline::CullMode::Back; }},
    FieldCase{"depth", [](auto& c) { c.depth = GPUPipeline::DepthState::GetAlwaysWriteState(); }},
    FieldCase{"blend", [](auto& c) { c.blend = GPUPipeline::BlendState::GetAlphaBlendingState(); }},
    FieldCase{"blend constant", [](auto& c) { c.blend.constant = 0x00808080u; }},
    FieldCase{"colour format 0", [](auto& c) { c.color_formats[0] = GPUTexture::Format::RGBA16F; }},
    FieldCase{"colour format 1", [](auto& c) { c.color_formats[1] = GPUTexture::Format::R32F; }},
    FieldCase{"depth format", [](auto& c) { c.depth_format = GPUTexture::Format::D16; }},
    FieldCase{"samples", [](auto& c) { c.samples = 4; }},
    FieldCase{"per-sample shading", [](auto& c) { c.per_sample_shading = true; }},
    FieldCase{"render-pass flags", [](auto& c) { c.render_pass_flags = GPUPipeline::ColorFeedbackLoop; }}),
  [](const ::testing::TestParamInfo<FieldCase>& info) {
    std::string name = info.param.name;
    for (char& ch : name)
      ch = std::isalnum(static_cast<unsigned char>(ch)) ? ch : '_';
    return name;
  });

TEST(PipelineBatch, EachShaderHandleMakesItDifferent)
{
  {
    Fixture f;
    EXPECT_EQ(f.PipelinesFor(BaseConfig(), BaseConfig(), f.fs, f.fs2), 2u);
  }
  {
    Fixture f;
    const Batch::Handle gs = f.batch.AddShader(GPUShaderStage::Geometry, GPUShaderLanguage::GLSL, "gs");
    f.batch.AddPipeline(BaseConfig(), f.vs, f.fs, Batch::NONE, &f.slots[0]);
    f.batch.AddPipeline(BaseConfig(), f.vs, f.fs, gs, &f.slots[1]);
    EXPECT_EQ(f.batch.GetUniquePipelineCount(), 2u);
  }
  {
    Fixture f;
    const Batch::Handle vs2 = f.batch.AddShader(GPUShaderStage::Vertex, GPUShaderLanguage::GLSL, "vs2");
    f.batch.AddPipeline(BaseConfig(), f.vs, f.fs, Batch::NONE, &f.slots[0]);
    f.batch.AddPipeline(BaseConfig(), vs2, f.fs, Batch::NONE, &f.slots[1]);
    EXPECT_EQ(f.batch.GetUniquePipelineCount(), 2u);
  }
}

TEST(InputLayoutKey, OutlivesTheAttributesItWasMadeFrom)
{
  // A cache keyed by an input layout must not point into memory that goes away (a batch's own copy of the
  // attributes, freed after the build).
  std::unordered_map<GPUPipeline::InputLayoutKey, int, GPUPipeline::InputLayoutKeyHash> cache;
  {
    std::vector<GPUPipeline::VertexAttribute> temporary(std::begin(ATTRIBUTES), std::end(ATTRIBUTES));
    cache.emplace(GPUPipeline::InputLayoutKey(GPUPipeline::InputLayout{temporary, 20}), 1);
    std::fill(temporary.begin(), temporary.end(), OTHER_ATTRIBUTES[1]);
  }

  const GPUPipeline::InputLayout same{ATTRIBUTES, 20};
  const GPUPipeline::InputLayout other{OTHER_ATTRIBUTES, 20};
  EXPECT_EQ(cache.count(GPUPipeline::InputLayoutKey(same)), 1u);
  EXPECT_EQ(cache.count(GPUPipeline::InputLayoutKey(other)), 0u);
  EXPECT_EQ(cache.count(GPUPipeline::InputLayoutKey(GPUPipeline::InputLayout{ATTRIBUTES, 24})), 0u);
}

} // namespace
