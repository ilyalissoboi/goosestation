// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#pragma once

#include "gpu.h"
#include "gpu_sw_backend.h"
#include "gpu_thread_commands.h"

#include "util/gpu_device.h"

#include "common/heap_array.h"

#include <memory>

namespace Threading {
class Thread;
}

class GPUTexture;

class GPU_SW final : public GPU
{
public:
  GPU_SW();
  ~GPU_SW() override;

  ALWAYS_INLINE const GPU_SW_Backend& GetBackend() const { return m_backend; }

  const Threading::Thread* GetSWThread() const override;
  bool IsHardwareRenderer() const override;

  bool Initialize() override;
  bool DoState(StateWrapper& sw, GPUTexture** host_texture, bool update_display) override;
  void Reset(bool clear_vram) override;
  void UpdateSettings(const Settings& old_settings) override;

  /// On the GPU thread: uploads and shows a frame the emulation thread copied out.
  static void UpdateDisplayOnGPUThread(const GPUThreadUpdateSWDisplayCommand* cmd);

protected:
  void ReadVRAM(u32 x, u32 y, u32 width, u32 height) override;
  void FillVRAM(u32 x, u32 y, u32 width, u32 height, u32 color) override;
  void UpdateVRAM(u32 x, u32 y, u32 width, u32 height, const void* data, bool set_mask, bool check_mask) override;
  void CopyVRAM(u32 src_x, u32 src_y, u32 dst_x, u32 dst_y, u32 width, u32 height) override;
  void FlushRender() override;
  void UpdateCLUT(GPUTexturePaletteReg reg, bool clut_is_8bit) override;

  template<GPUTexture::Format display_format>
  void CopyOut15Bit(u8* dst_ptr, u32 dst_stride, u32 src_x, u32 src_y, u32 width, u32 height, u32 line_skip);

  template<GPUTexture::Format display_format>
  void CopyOut24Bit(u8* dst_ptr, u32 dst_stride, u32 src_x, u32 src_y, u32 skip_x, u32 width, u32 height,
                    u32 line_skip);

  /// Copies a frame out of VRAM, converted to the display format, into dst.
  void CopyOut(u8* dst, u32 dst_stride, u32 src_x, u32 src_y, u32 skip_x, u32 width, u32 height, u32 line_skip,
               bool is_24bit);
  u32 GetCopyOutStride(u32 width, bool is_24bit) const;

  /// Hands the GPU thread a frame copied out of VRAM, and what to do with it.
  void PushDisplay(GPUThreadUpdateSWDisplayCommand::Mode mode, u32 src_x, u32 src_y, u32 skip_x, u32 width,
                   u32 height, u32 line_skip, bool is_24bit, u8 field, bool chroma_smoothing);

  void UpdateDisplay() override;

  void DispatchRenderCommand() override;

  void FillBackendCommandParameters(GPUBackendCommand* cmd) const;
  void FillDrawCommand(GPUBackendDrawCommand* cmd, GPURenderCommand rc) const;

  static GPUTexture* GetDisplayTexture(u32 width, u32 height, GPUTexture::Format format);

  GPUTexture::Format m_16bit_display_format = GPUTexture::Format::RGB565;
  GPUTexture::Format m_24bit_display_format = GPUTexture::Format::RGBA8;

  GPU_SW_Backend m_backend;
};
