// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "gpu_shader_cache.h"

#include "common/types.h"

#include <functional>
#include <memory>
#include <span>
#include <string_view>

class Error;
class GPUShader;

/// Builds many shaders at once. Only the calling thread touches the shader cache: it looks every shader up first, the
/// workers create them from their binaries or sources, and the caller then writes the new binaries in request order,
/// so the cache's files don't depend on which worker finished first.
namespace GPUShaderBatch {

struct Request
{
  GPUShaderStage stage;
  GPUShaderLanguage language;
  std::string_view source;
  const char* entry_point = "main";
};

using FromBinary = std::function<std::unique_ptr<GPUShader>(const Request& request, std::span<const u8> binary,
                                                            Error* error)>;
using FromSource = std::function<std::unique_ptr<GPUShader>(const Request& request,
                                                            GPUShaderCache::ShaderBinary* out_binary, Error* error)>;

/// Fills out[i] for every request. On failure, error is the first failing request's, in request order, and whatever
/// was made is left in out for the caller to destroy.
bool Run(GPUShaderCache& cache, std::span<const Request> requests, std::span<std::unique_ptr<GPUShader>> out,
         u32 threads, const FromBinary& from_binary, const FromSource& from_source,
         const std::function<void(u32)>& on_progress, Error* error);

} // namespace GPUShaderBatch
