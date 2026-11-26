// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "gpu_pipeline_batch.h"

#include "common/assert.h"
#include "common/error.h"

#include "xxhash.h"

#include <cstring>

GPUPipelineBatch::Handle GPUPipelineBatch::AddShader(GPUShaderStage stage, GPUShaderLanguage language,
                                                     std::string source, const char* entry_point)
{
  m_shader_requests++;

  const std::string_view entry(entry_point ? entry_point : "main");
  u64 hash = XXH3_64bits(source.data(), source.size());
  hash ^= XXH3_64bits(entry.data(), entry.size()) * 31;
  hash ^= (static_cast<u64>(stage) << 56) ^ (static_cast<u64>(language) << 48);

  const auto [begin, end] = m_shader_lookup.equal_range(hash);
  for (auto it = begin; it != end; ++it)
  {
    const ShaderEntry& e = m_shaders[it->second];
    if (e.stage == stage && e.language == language && e.entry_point == entry && e.source == source)
      return it->second;
  }

  const Handle handle = static_cast<Handle>(m_shaders.size());
  m_shaders.push_back(ShaderEntry{stage, language, std::move(source), std::string(entry)});
  m_shader_lookup.emplace(hash, handle);
  return handle;
}

void GPUPipelineBatch::AddPipeline(const GPUPipeline::GraphicsConfig& config, Handle vs, Handle fs, Handle gs,
                                   GPUPipeline** slot)
{
  DebugAssert(vs < m_shaders.size() && fs < m_shaders.size() && (gs == NONE || gs < m_shaders.size()));

  // Every field the pipeline is made from, with the shaders by handle: equal keys make the same pipeline.
  std::string key;
  const auto put = [&key](const void* data, size_t size) { key.append(static_cast<const char*>(data), size); };
  put(&config.layout, sizeof(config.layout));
  put(&config.primitive, sizeof(config.primitive));
  const u32 num_attributes = static_cast<u32>(config.input_layout.vertex_attributes.size());
  put(&num_attributes, sizeof(num_attributes));
  for (const GPUPipeline::VertexAttribute& attr : config.input_layout.vertex_attributes)
    put(&attr.key, sizeof(attr.key));
  // The stride means nothing without attributes, and some callers leave it unset then.
  const u32 stride = num_attributes ? config.input_layout.vertex_stride : 0;
  put(&stride, sizeof(stride));
  put(&config.rasterization.key, sizeof(config.rasterization.key));
  put(&config.depth.key, sizeof(config.depth.key));
  put(&config.blend.key, sizeof(config.blend.key));
  put(&vs, sizeof(vs));
  put(&fs, sizeof(fs));
  put(&gs, sizeof(gs));
  put(config.color_formats, sizeof(config.color_formats));
  put(&config.depth_format, sizeof(config.depth_format));
  put(&config.samples, sizeof(config.samples));
  const u8 per_sample = config.per_sample_shading ? 1 : 0;
  put(&per_sample, sizeof(per_sample));
  put(&config.render_pass_flags, sizeof(config.render_pass_flags));

  const auto [it, inserted] = m_pipeline_lookup.try_emplace(std::move(key), static_cast<u32>(m_pipelines.size()));
  if (inserted)
  {
    PipelineEntry& e = m_pipelines.emplace_back();
    e.config = config;
    e.attributes.assign(config.input_layout.vertex_attributes.begin(), config.input_layout.vertex_attributes.end());
    e.config.input_layout.vertex_stride = stride;
    e.config.vertex_shader = nullptr;
    e.config.fragment_shader = nullptr;
    e.config.geometry_shader = nullptr;
    e.vs = vs;
    e.fs = fs;
    e.gs = gs;
  }

  m_slots.emplace_back(slot, it->second);
}

bool GPUPipelineBatch::Build(GPUDevice& device, u32 threads, const std::function<void(u32, u32)>& progress,
                             Error* error)
{
  const u32 num_shaders = static_cast<u32>(m_shaders.size());
  const u32 num_pipelines = static_cast<u32>(m_pipelines.size());
  const u32 total = num_shaders + num_pipelines;

  device.BeginCompilePass(threads);

  std::vector<GPUDevice::ShaderRequest> requests;
  requests.reserve(num_shaders);
  for (const ShaderEntry& e : m_shaders)
    requests.push_back(GPUDevice::ShaderRequest{e.stage, e.language, e.source, e.entry_point.c_str()});

  std::vector<std::unique_ptr<GPUShader>> shaders(num_shaders);
  bool ok = device.CreateShaders(
    requests, shaders, threads,
    [&progress, total](u32 done) {
      if (progress)
        progress(done, total);
    },
    error);

  std::vector<std::unique_ptr<GPUPipeline>> pipelines(num_pipelines);
  if (ok)
  {
    std::vector<GPUPipeline::GraphicsConfig> configs;
    configs.reserve(num_pipelines);
    for (const PipelineEntry& e : m_pipelines)
    {
      GPUPipeline::GraphicsConfig& config = configs.emplace_back(e.config);
      config.input_layout.vertex_attributes = e.attributes;
      config.vertex_shader = shaders[e.vs].get();
      config.fragment_shader = shaders[e.fs].get();
      config.geometry_shader = (e.gs != NONE) ? shaders[e.gs].get() : nullptr;
    }

    ok = device.CreatePipelines(
      configs, pipelines, threads,
      [&progress, total, num_shaders](u32 done) {
        if (progress)
          progress(num_shaders + done, total);
      },
      error);
  }

  device.EndCompilePass();

  // Everything is destroyed here, on the calling thread: the backends queue destruction on the GPU thread's state.
  shaders.clear();
  if (!ok)
    return false;

  for (const auto& [slot, index] : m_slots)
    *slot = pipelines[index].get();

  m_built = std::move(pipelines);
  return true;
}

std::vector<std::unique_ptr<GPUPipeline>> GPUPipelineBatch::TakePipelines()
{
  return std::move(m_built);
}
