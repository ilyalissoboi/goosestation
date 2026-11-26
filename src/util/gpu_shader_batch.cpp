// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "gpu_shader_batch.h"
#include "gpu_device.h"

#include "common/error.h"
#include "common/job_runner.h"
#include "common/log.h"

#include <optional>
#include <vector>

Log_SetChannel(GPUDevice);

bool GPUShaderBatch::Run(GPUShaderCache& cache, std::span<const Request> requests,
                         std::span<std::unique_ptr<GPUShader>> out, u32 threads, const FromBinary& from_binary,
                         const FromSource& from_source, const std::function<void(u32)>& on_progress, Error* error)
{
  const u32 count = static_cast<u32>(requests.size());
  const bool use_cache = cache.IsOpen();

  // Every lookup first, on this thread: the workers never touch the cache.
  std::vector<GPUShaderCache::CacheIndexKey> keys;
  std::vector<std::optional<GPUShaderCache::ShaderBinary>> binaries(count);
  if (use_cache)
  {
    keys.reserve(count);
    for (u32 i = 0; i < count; i++)
    {
      const Request& req = requests[i];
      keys.push_back(GPUShaderCache::GetCacheKey(req.stage, req.language, req.source, req.entry_point));
      binaries[i] = cache.Lookup(keys.back());
    }
  }

  std::vector<GPUShaderCache::ShaderBinary> new_binaries(count);
  std::vector<Error> errors(count);
  std::vector<u8> rejected(count, 0);

  const bool ok = Threading::RunJobs(
    count, threads,
    [&](u32 i) {
      const Request& req = requests[i];
      if (binaries[i].has_value())
      {
        if ((out[i] = from_binary(req, binaries[i]->cspan(), &errors[i])))
          return true;

        // The driver changed: the cache is cleared afterwards, and this one built again.
        rejected[i] = 1;
        binaries[i].reset();
      }

      out[i] = from_source(req, use_cache ? &new_binaries[i] : nullptr, &errors[i]);
      return static_cast<bool>(out[i]);
    },
    on_progress);

  // The error is the first failing request's, whichever worker got there first.
  if (!ok)
  {
    for (u32 i = 0; i < count; i++)
    {
      if (!out[i] && errors[i].IsValid())
      {
        if (error)
          *error = std::move(errors[i]);
        break;
      }
    }
  }

  if (use_cache)
  {
    bool any_rejected = false;
    for (const u8 r : rejected)
      any_rejected |= (r != 0);

    if (any_rejected)
    {
      ERROR_LOG("Failed to create shader from binary (driver changed?). Clearing cache.");
      cache.Clear();
    }

    // The new binaries in request order (after a clear, every binary), so the file is the same however the work fell.
    for (u32 i = 0; i < count && cache.IsOpen(); i++)
    {
      const GPUShaderCache::ShaderBinary* binary = nullptr;
      if (!new_binaries[i].empty())
        binary = &new_binaries[i];
      else if (any_rejected && binaries[i].has_value() && !binaries[i]->empty())
        binary = &binaries[i].value();
      if (binary && !cache.Insert(keys[i], binary->data(), static_cast<u32>(binary->size())))
        cache.Close();
    }
  }

  return ok;
}
