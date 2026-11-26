// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "gpu_device.h"

#include "common/types.h"

#include <functional>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

class Error;

/// A set of shaders and pipelines described first and built together. Shaders with the same stage, language, entry
/// point and source are built once, and so are pipelines whose whole configuration matches (with shaders compared by
/// handle). Build makes every distinct shader, then every distinct pipeline, on several threads, and fills each slot.
class GPUPipelineBatch
{
public:
  using Handle = u32;
  static constexpr Handle NONE = ~0u;

  Handle AddShader(GPUShaderStage stage, GPUShaderLanguage language, std::string source,
                   const char* entry_point = "main");

  /// The config's shader pointers are ignored: vs, fs and gs name the shaders (gs may be NONE).
  void AddPipeline(const GPUPipeline::GraphicsConfig& config, Handle vs, Handle fs, Handle gs, GPUPipeline** slot);

  u32 GetUniqueShaderCount() const { return static_cast<u32>(m_shaders.size()); }
  u32 GetUniquePipelineCount() const { return static_cast<u32>(m_pipelines.size()); }
  u32 GetSlotCount() const { return static_cast<u32>(m_slots.size()); }
  u32 GetShaderRequestCount() const { return m_shader_requests; }

  /// progress(done, total) runs on the calling thread.
  bool Build(GPUDevice& device, u32 threads, const std::function<void(u32, u32)>& progress, Error* error);

  /// The built pipelines, which the slots point into.
  std::vector<std::unique_ptr<GPUPipeline>> TakePipelines();

private:
  struct ShaderEntry
  {
    GPUShaderStage stage;
    GPUShaderLanguage language;
    std::string source;
    std::string entry_point;
  };

  struct PipelineEntry
  {
    GPUPipeline::GraphicsConfig config;
    std::vector<GPUPipeline::VertexAttribute> attributes;
    Handle vs;
    Handle fs;
    Handle gs;
  };

  std::vector<ShaderEntry> m_shaders;
  std::unordered_multimap<u64, Handle> m_shader_lookup;
  u32 m_shader_requests = 0;

  std::vector<PipelineEntry> m_pipelines;
  std::unordered_map<std::string, u32> m_pipeline_lookup;
  std::vector<std::pair<GPUPipeline**, u32>> m_slots;

  std::vector<std::unique_ptr<GPUPipeline>> m_built;
};
