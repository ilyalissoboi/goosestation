// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "types.h"

#include "util/gpu_texture.h"

#include "common/gsvector.h"
#include "common/types.h"

#include <array>
#include <memory>
#include <string>
#include <vector>

class Error;
class GPUPipeline;
class MediaCapture;

struct Settings;

/// What the screen shows, taken by the emulation thread's GPU when the display updates or is presented: the presenter
/// on the GPU thread reads nothing of the GPU's state but this.
struct GPUDisplayParams
{
  u16 display_width;
  u16 display_height;
  u16 display_origin_left;
  u16 display_origin_top;
  u16 display_vram_left;
  u16 display_vram_top;
  u16 display_vram_width;
  u16 display_vram_height;
  u16 display_vram_start_x; // the CRTC's X, where 24-bit lines start
  float display_aspect_ratio;
  bool display_disabled;
  bool is_24bit;
  bool interlaced;
  bool vertical_resolution;
  bool console_is_pal;
  u8 interlaced_field;
};

/// The display on the GPU thread: the frame a renderer hands it (deinterlaced, chroma-smoothed), post-processing,
/// the draw to the window, screenshots and media capture's frames.
class GPUPresenter
{
public:
  enum : u32
  {
    DEINTERLACE_BUFFER_COUNT = 4,
  };

  GPUPresenter();
  ~GPUPresenter();

  /// Where the picture goes in a window_width x window_height target, and the whole display area around it.
  static void CalculateDrawRect(const GPUDisplayParams& params, const Settings& settings, s32 window_width,
                                s32 window_height, bool apply_rotation, bool apply_aspect_ratio,
                                GSVector4i* display_rect, GSVector4i* draw_rect);

  bool Initialize(Error* error);
  void UpdateSettings(const Settings& old_settings);

  ALWAYS_INLINE const GPUDisplayParams& GetDisplayParams() const { return m_display_params; }
  void SetDisplayParams(const GPUDisplayParams& params);

  ALWAYS_INLINE GPUTexture* GetDisplayTexture() const { return m_display_texture; }
  ALWAYS_INLINE GPUTexture* GetDisplayDepthBuffer() const { return m_display_depth_buffer; }
  ALWAYS_INLINE s32 GetDisplayTextureViewX() const { return m_display_texture_view_x; }
  ALWAYS_INLINE s32 GetDisplayTextureViewY() const { return m_display_texture_view_y; }
  ALWAYS_INLINE s32 GetDisplayTextureViewWidth() const { return m_display_texture_view_width; }
  ALWAYS_INLINE s32 GetDisplayTextureViewHeight() const { return m_display_texture_view_height; }

  void ClearDisplayTexture();
  void SetDisplayTexture(GPUTexture* texture, GPUTexture* depth_texture, s32 view_x, s32 view_y, s32 view_width,
                         s32 view_height);

  bool Deinterlace(u32 field, u32 line_skip);
  bool ApplyChromaSmoothing();
  void DestroyDeinterlaceTextures();

  /// Draws the current display texture, with any post-processing, into the window's present.
  bool PresentDisplay();

  /// Draws the display, optionally with post-processing, into target (the window when nullptr).
  bool RenderDisplay(GPUTexture* target, const GSVector4i display_rect, const GSVector4i draw_rect, bool postfx);

  /// Sends the current frame to media capture.
  bool SendDisplayToMediaCapture(MediaCapture* cap);

  /// Screenshot bounds for a mode.
  void CalculateScreenshotSize(DisplayScreenshotMode mode, u32* width, u32* height, GSVector4i* display_rect,
                               GSVector4i* draw_rect) const;

  /// Renders the display, optionally with post-processing, to a buffer.
  bool RenderScreenshotToBuffer(u32 width, u32 height, const GSVector4i display_rect, const GSVector4i draw_rect,
                                bool postfx, std::vector<u32>* out_pixels, u32* out_stride,
                                GPUTexture::Format* out_format);

  /// Saves a screenshot.
  bool RenderScreenshotToFile(std::string filename, DisplayScreenshotMode mode, u8 quality, bool compress_on_thread,
                              bool show_osd_message);

  /// Saves the current display texture as it is.
  bool WriteDisplayTextureToFile(std::string filename, bool compress_on_thread = false);

private:
  bool CompileDisplayPipelines(bool display, bool deinterlace, bool chroma_smoothing);
  bool DeinterlaceExtractField(u32 dst_bufidx, GPUTexture* src, u32 x, u32 y, u32 width, u32 height, u32 line_skip);
  bool DeinterlaceSetTargetSize(u32 width, u32 height, bool preserve);

  GPUDisplayParams m_display_params = {};

  u32 m_current_deinterlace_buffer = 0;
  std::unique_ptr<GPUPipeline> m_deinterlace_pipeline;
  std::unique_ptr<GPUPipeline> m_deinterlace_extract_pipeline;
  std::array<std::unique_ptr<GPUTexture>, DEINTERLACE_BUFFER_COUNT> m_deinterlace_buffers;
  std::unique_ptr<GPUTexture> m_deinterlace_texture;

  std::unique_ptr<GPUPipeline> m_chroma_smoothing_pipeline;
  std::unique_ptr<GPUTexture> m_chroma_smoothing_texture;

  std::unique_ptr<GPUPipeline> m_display_pipeline;
  GPUTexture* m_display_texture = nullptr;
  GPUTexture* m_display_depth_buffer = nullptr;
  s32 m_display_texture_view_x = 0;
  s32 m_display_texture_view_y = 0;
  s32 m_display_texture_view_width = 0;
  s32 m_display_texture_view_height = 0;
};

/// The GPU thread's presenter; made and destroyed with the emulation thread's GPU.
extern std::unique_ptr<GPUPresenter> g_gpu_presenter;
