// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "gpu.h"

#include <memory>

class GPU_SW_Backend;
struct GPUBackendCommand;
struct GPUBackendDrawCommand;
struct GPUThreadCommand;

/// The hardware renderer's side on the emulation thread: what the PS1 sees (ticks, with the culling they depend on,
/// and PGXP's vertices) and the software readback renderer stay here; the drawing goes to GPU_HW on the GPU thread,
/// as commands carrying everything it needs.
class GPU_HWFrontend final : public GPU
{
public:
  GPU_HWFrontend();
  ~GPU_HWFrontend() override;

  const Threading::Thread* GetSWThread() const override;
  bool IsHardwareRenderer() const override;

  bool Initialize() override;
  void Reset(bool clear_vram) override;
  bool DoState(StateWrapper& sw, GPUTexture** host_texture, bool update_display) override;

  void RestoreDeviceContext() override;

  void UpdateSettings(const Settings& old_settings) override;
  void UpdateResolutionScale() override;
  std::tuple<u32, u32> GetEffectiveDisplayResolution(bool scaled = true) override;
  std::tuple<u32, u32> GetFullDisplayResolution(bool scaled = true) override;

  void UpdateDisplay() override;
  void FlushRender() override;

protected:
  void ReadVRAM(u32 x, u32 y, u32 width, u32 height) override;
  void FillVRAM(u32 x, u32 y, u32 width, u32 height, u32 color) override;
  void UpdateVRAM(u32 x, u32 y, u32 width, u32 height, const void* data, bool set_mask, bool check_mask) override;
  void CopyVRAM(u32 src_x, u32 src_y, u32 dst_x, u32 dst_y, u32 width, u32 height) override;
  void DispatchRenderCommand() override;
  void UpdateCLUT(GPUTexturePaletteReg reg, bool clut_is_8bit) override;
  void DrawRendererStats() override;
  void OnBufferSwapped() override;

private:
  GPUBackendCommandParameters GetCommandParameters() const;
  void FillBackendCommandParameters(GPUBackendCommand* cmd) const;
  void FillDrawCommand(GPUBackendDrawCommand* cmd, GPURenderCommand rc) const;

  /// Sends the draw state and drawing area to GPU_HW where they differ from what it last had.
  void SyncDrawState();

  void DispatchPolygon(GPURenderCommand rc);
  void DispatchRectangle(GPURenderCommand rc);
  void DispatchLine(GPURenderCommand rc);
  void DispatchPolyLine(GPURenderCommand rc);

  void UpdateSoftwareRenderer(bool copy_vram_from_hw);

  std::unique_ptr<GPU_SW_Backend> m_sw_renderer;

  // What GPU_HW last had.
  GPUDrawModeReg m_sent_mode_reg = {};
  GPUTexturePaletteReg m_sent_palette_reg = {};
  GPUTextureWindow m_sent_texture_window = {};
  GPUDrawingArea m_sent_drawing_area = {};
  bool m_draw_state_sent = false;
  bool m_sw_drawing_area_pending = false;
};
