// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#pragma once

#include "gpu_presenter.h"
#include "gpu_types.h"
#include "gpu_texture_cache.h"
#include "texture_replacements.h"

#include "util/gpu_device.h"

#include "common/dimensional_array.h"
#include "common/gsvector.h"

#include <limits>
#include <unordered_map>
#include <tuple>
#include <utility>

class Error;

struct GPUThreadCommand;
struct GPUThreadHWDrawPolygonCommand;
struct GPUThreadHWDrawRectangleCommand;
struct GPUThreadHWDrawLineCommand;
struct Settings;
class StateWrapper;

/// The hardware renderer, on the GPU thread: batches and draws what GPU_HWFrontend sends it. It reads nothing of the
/// emulation thread's GPU but the commands, and g_gpu_settings.
class GPU_HW final
{
public:
  enum class BatchRenderMode : u8
  {
    TransparencyDisabled,
    TransparentAndOpaque,
    OnlyOpaque,
    OnlyTransparent,
    ShaderBlend
  };

  enum class BatchTextureMode : u8
  {
    Palette4Bit,
    Palette8Bit,
    Direct16Bit,
    Disabled,

    SpritePalette4Bit,
    SpritePalette8Bit,
    SpriteDirect16Bit,

    /// A page with texture replacements, per real mode; only with the texture cache on. Texels the replacements cover
    /// come from the page as drawn with them, the rest from VRAM as in the real mode. Sprites have their own, as the
    /// other modes do, so they keep sprites' coordinate rounding.
    ReplacedPalette4Bit,
    ReplacedPalette8Bit,
    ReplacedDirect16Bit,
    SpriteReplacedPalette4Bit,
    SpriteReplacedPalette8Bit,
    SpriteReplacedDirect16Bit,

    MaxCount,

    SpriteStart = SpritePalette4Bit,
    ReplacedStart = ReplacedPalette4Bit,
    SpriteReplacedStart = SpriteReplacedPalette4Bit,
  };

  static constexpr bool IsReplacedTextureMode(BatchTextureMode mode)
  {
    return (mode >= BatchTextureMode::ReplacedStart && mode < BatchTextureMode::MaxCount);
  }
  static constexpr bool IsSpriteTextureMode(BatchTextureMode mode)
  {
    return ((mode >= BatchTextureMode::SpriteStart && mode < BatchTextureMode::ReplacedStart) ||
            mode >= BatchTextureMode::SpriteReplacedStart);
  }
  /// Palette4Bit, Palette8Bit or Direct16Bit for a textured mode, sprite or replaced; Disabled otherwise.
  static constexpr BatchTextureMode GetBaseTextureMode(BatchTextureMode mode)
  {
    if (mode == BatchTextureMode::Disabled)
      return mode;
    const u8 m = static_cast<u8>(mode);
    if (mode >= BatchTextureMode::SpriteReplacedStart)
      return static_cast<BatchTextureMode>(m - static_cast<u8>(BatchTextureMode::SpriteReplacedStart));
    if (mode >= BatchTextureMode::ReplacedStart)
      return static_cast<BatchTextureMode>(m - static_cast<u8>(BatchTextureMode::ReplacedStart));
    if (mode >= BatchTextureMode::SpriteStart)
      return static_cast<BatchTextureMode>(m - static_cast<u8>(BatchTextureMode::SpriteStart));
    return mode;
  }
  static_assert(static_cast<u8>(BatchTextureMode::Palette4Bit) == static_cast<u8>(GPUTextureMode::Palette4Bit) &&
                static_cast<u8>(BatchTextureMode::Palette8Bit) == static_cast<u8>(GPUTextureMode::Palette8Bit) &&
                static_cast<u8>(BatchTextureMode::Direct16Bit) == static_cast<u8>(GPUTextureMode::Direct16Bit));

  GPU_HW();
  ~GPU_HW();

  /// The resolution scale in use, for the emulation thread (GPU_HWFrontend's display resolutions).
  static u32 GetPublishedResolutionScale();

  bool Initialize(const GPUDisplayParams& params);

  /// Before GPU::Reset(): drops the batch being built.
  void BeginReset();
  /// After GPU::Reset() and the draw state it leaves.
  void Reset(bool clear_vram);

  void HandleCommand(const GPUThreadCommand* cmd);

  void UpdateSettings(const Settings& old_settings, const GPUDisplayParams& params);
  u32 CalculateResolutionScale(const GPUDisplayParams& params) const;
  ALWAYS_INLINE u32 GetResolutionScale() const { return m_resolution_scale; }

  /// Rebinds the renderer's state after anything else used the device.
  void RestoreDeviceContext();

  /// Draws the batch being built, before something else (a window change) uses the device.
  void FlushRenderForDeviceChange();

  /// Everything before it drawn, the rect read back into g_vram.
  void ReadVRAM(u32 x, u32 y, u32 width, u32 height);

  /// Rewind and runahead states: VRAM copied into or out of a texture of the state's own.
  bool DoHostTextureState(GPUTexture** host_texture, bool reading);

  /// After a state's VRAM is loaded. readback_vram: the state was a host texture, so VRAM is read back for the cache.
  void OnStateLoaded(bool readback_vram);

  /// With software readbacks, g_vram is VRAM as drawn, and what rebuilds restore from.
  void SetSoftwareReadbacks(bool enabled);

  void DrawRendererStats();

private:
  enum : u32
  {
    MAX_BATCH_VERTEX_COUNTER_IDS = 65536 - 2,
    MAX_VERTICES_FOR_RECTANGLE = 6 * (((MAX_PRIMITIVE_WIDTH + (TEXTURE_PAGE_WIDTH - 1)) / TEXTURE_PAGE_WIDTH) + 1u) *
                                 (((MAX_PRIMITIVE_HEIGHT + (TEXTURE_PAGE_HEIGHT - 1)) / TEXTURE_PAGE_HEIGHT) + 1u),
    NUM_TEXTURE_MODES = static_cast<u32>(BatchTextureMode::MaxCount),
  };
  enum : u8
  {
    TEXPAGE_DIRTY_DRAWN_RECT = (1 << 0),
    TEXPAGE_DIRTY_WRITTEN_RECT = (1 << 1),
  };

  static_assert(GPUDevice::MIN_TEXEL_BUFFER_ELEMENTS >= (VRAM_WIDTH * VRAM_HEIGHT));

  struct BatchVertex
  {
    float x;
    float y;
    float z;
    float w;
    u32 color;
    u32 texpage;
    u16 u; // 16-bit texcoords are needed for 256 extent rectangles
    u16 v;
    u32 uv_limits;

    void Set(float x_, float y_, float z_, float w_, u32 color_, u32 texpage_, u16 packed_texcoord, u32 uv_limits_);
    void Set(float x_, float y_, float z_, float w_, u32 color_, u32 texpage_, u16 u_, u16 v_, u32 uv_limits_);
    static u32 PackUVLimits(u32 min_u, u32 max_u, u32 min_v, u32 max_v);
    void SetUVLimits(u32 min_u, u32 max_u, u32 min_v, u32 max_v);
  };

  struct BatchConfig
  {
    BatchTextureMode texture_mode = BatchTextureMode::Disabled;
    GPUTransparencyMode transparency_mode = GPUTransparencyMode::Disabled;
    bool dithering = false;
    bool interlacing = false;
    bool set_mask_while_drawing = false;
    bool check_mask_before_draw = false;
    bool use_depth_buffer = false;
    bool sprite_mode = false;

    /// The page as drawn with its replacements (coverage in alpha), for the replaced modes; bound at slot 1.
    GPUTexture* source = nullptr;

    // Returns the render mode for this batch.
    BatchRenderMode GetRenderMode() const;
  };

  struct BatchUBOData
  {
    u32 u_texture_window[4]; // and_x, and_y, or_x, or_y
    float u_src_alpha_factor;
    float u_dst_alpha_factor;
    u32 u_interlaced_displayed_field;
    u32 u_set_mask_while_drawing;
  };

  /// The draw mode, palette and texture window as GPU_HWFrontend last sent them, with the changes flagged since.
  struct DrawMode
  {
    GPUDrawModeReg mode_reg;
    GPUTexturePaletteReg palette_reg;
    GPUTextureWindow texture_window;
    bool texture_page_changed;
    bool texture_window_changed;

    ALWAYS_INLINE bool IsTexturePageChanged() const { return texture_page_changed; }
    ALWAYS_INLINE void SetTexturePageChanged() { texture_page_changed = true; }
    ALWAYS_INLINE void ClearTexturePageChangedFlag() { texture_page_changed = false; }

    ALWAYS_INLINE bool IsTextureWindowChanged() const { return texture_window_changed; }
    ALWAYS_INLINE void SetTextureWindowChanged() { texture_window_changed = true; }
    ALWAYS_INLINE void ClearTextureWindowChangedFlag() { texture_window_changed = false; }
  };

  struct RendererStats
  {
    u32 num_batches;
    u32 num_vram_read_texture_updates;
    u32 num_uniform_buffer_updates;
  };

  static constexpr GSVector4i VRAM_SIZE_RECT = GSVector4i::cxpr(0, 0, VRAM_WIDTH, VRAM_HEIGHT);
  static constexpr GSVector4i INVALID_RECT =
    GSVector4i::cxpr(std::numeric_limits<s32>::max(), std::numeric_limits<s32>::max(), std::numeric_limits<s32>::min(),
                     std::numeric_limits<s32>::min());

  /// Returns true if a depth buffer should be created.
  GPUTexture::Format GetDepthBufferFormat() const;

  bool CreateBuffers();
  void ClearFramebuffer();
  void DestroyBuffers();

  bool CompilePipelines(Error* error);
  void DestroyPipelines();

  void LoadVertices();

  void PrintSettingsToLog();
  void CheckSettings();

  void UpdateVRAMReadTexture(bool drawn, bool written);
  void UpdateDepthBufferFromMaskBit();
  void CopyAndClearDepthBuffer();
  void ClearDepthBuffer();
  void SetScissor();
  void SetVRAMRenderTarget();
  void DeactivateROV();
  void MapGPUBuffer(u32 required_vertices, u32 required_indices);
  void UnmapGPUBuffer(u32 used_vertices, u32 used_indices);
  void DrawBatchVertices(BatchRenderMode render_mode, u32 num_indices, u32 base_index, u32 base_vertex);

  GPUDownsampleMode GetDownsampleMode(u32 resolution_scale) const;

  bool IsUsingMultisampling() const;
  bool IsUsingDownsampling() const;

  void SetFullVRAMDirtyRectangle();
  void ClearVRAMDirtyRectangle();

  void AddWrittenRectangle(const GSVector4i rect);
  void AddDrawnRectangle(const GSVector4i rect);
  void AddUnclampedDrawnRectangle(const GSVector4i rect);
  void SetTexPageChangedOnOverlap(const GSVector4i update_rect);

  void CheckForTexPageOverlap(GSVector4i uv_rect);

  /// Creates, updates or drops the texture cache for the current settings; seed_from_gpu reads VRAM back first.
  void UpdateTextureCache(bool seed_from_gpu);

  /// A textured primitive's UV rect (page texels, right/bottom exclusive), for dumping.
  void AddDrawnTextureRect(GSVector4i uv_rect);

  /// The page drawn with a source's replacements, composed on first use and kept within MaxHashCacheVRAMUsageMB.
  GPUTexture* GetReplacementSource(const GPUTextureCache::Source& source);
  void ClearReplacementSources();

  /// Whether a batch texture mode's pipelines are compiled.
  bool IsTextureModeCompiled(BatchTextureMode mode) const;

  bool IsFlushed() const;
  void EnsureVertexBufferSpace(u32 required_vertices, u32 required_indices);
  void EnsureVertexBufferSpaceForCurrentCommand();
  void ResetBatchVertexDepth();

  /// Returns the value to be written to the depth buffer for the current operation for mask bit emulation.
  float GetCurrentNormalizedVertexDepth() const;

  /// Returns if the draw needs to be broken into opaque/transparent passes.
  bool NeedsTwoPassRendering() const;

  /// Returns true if the draw is going to use shader blending/framebuffer fetch.
  bool NeedsShaderBlending(GPUTransparencyMode transparency, BatchTextureMode texture, bool check_mask) const;

  /// VRAM at native resolution, for a rebuild or the texture cache: g_vram with software readbacks, else read back
  /// into a buffer of this renderer's own (which g_vram's other writers never touch).
  const u16* ReadBackVRAMShadow();
  void ReadVRAMInto(u32 x, u32 y, u32 width, u32 height, u16* shadow);

  void FillVRAM(u32 x, u32 y, u32 width, u32 height, u32 color);
  void UpdateVRAM(u32 x, u32 y, u32 width, u32 height, const void* data, bool set_mask, bool check_mask);
  void CopyVRAM(u32 src_x, u32 src_y, u32 dst_x, u32 dst_y, u32 width, u32 height);
  void SetupBatch(GPURenderCommand rc, u32 num_line_vertices);
  void DrawPolygon(const GPUThreadHWDrawPolygonCommand* cmd);
  void DrawRectangle(const GPUThreadHWDrawRectangleCommand* cmd);
  void DrawLine(const GPUThreadHWDrawLineCommand* cmd);
  void FlushRender();
  void UpdateDisplay();

  ALWAYS_INLINE bool IsInterlacedRenderingEnabled() const { return m_params.interlaced_rendering; }
  ALWAYS_INLINE u32 GetActiveLineLSB() const { return m_params.active_line_lsb; }

  void UpdateVRAMOnGPU(u32 x, u32 y, u32 width, u32 height, const void* data, u32 data_pitch, bool set_mask,
                       bool check_mask, const GSVector4i bounds);
  bool BlitVRAMReplacementTexture(const TextureReplacements::ReplacementImage* tex, u32 dst_x, u32 dst_y, u32 width,
                                  u32 height);

  /// Expands a line into two triangles.
  void DrawLine(const GSVector4 bounds, u32 col0, u32 col1, float depth);

  /// Handles quads with flipped texture coordinate directions.
  void HandleFlippedQuadTextureCoordinates(BatchVertex* vertices);
  bool IsPossibleSpritePolygon(const BatchVertex* vertices) const;
  bool ExpandLineTriangles(BatchVertex* vertices);

  /// Computes polygon U/V boundaries, and for overlap with the current texture page.
  void ComputePolygonUVLimits(BatchVertex* vertices, u32 num_vertices);

  /// Sets the depth test flag for PGXP depth buffering.
  void SetBatchDepthBuffer(bool enabled);
  void CheckForDepthClear(const BatchVertex* vertices, u32 num_vertices);
  void SetBatchSpriteMode(bool enabled);

  void UpdateDownsamplingLevels();

  void DownsampleFramebuffer();
  void DownsampleFramebufferAdaptive(GPUTexture* source, u32 left, u32 top, u32 width, u32 height);
  void DownsampleFramebufferBoxFilter(GPUTexture* source, u32 left, u32 top, u32 width, u32 height);

  std::unique_ptr<GPUTexture> m_vram_texture;
  std::unique_ptr<GPUTexture> m_vram_depth_texture;
  std::unique_ptr<GPUTexture> m_vram_depth_copy_texture;
  std::unique_ptr<GPUTexture> m_vram_read_texture;
  std::unique_ptr<GPUTexture> m_vram_readback_texture;
  std::unique_ptr<GPUDownloadTexture> m_vram_readback_download_texture;
  std::unique_ptr<GPUTexture> m_vram_replacement_texture;

  std::unique_ptr<GPUTextureBuffer> m_vram_upload_buffer;
  std::unique_ptr<GPUTexture> m_vram_write_texture;

  // What GPU_HWFrontend sent last: the draw state, drawing area, the GPUSTAT bits of the command being handled, and
  // what the screen shows.
  DrawMode m_draw_mode = {};
  GPUDrawingArea m_drawing_area = {};
  GSVector4i m_clamped_drawing_area = {};
  bool m_drawing_area_changed = true;
  GPUBackendCommandParameters m_params = {};
  GPURenderCommand m_render_command = {}; // the draw being batched
  u32 m_line_vertex_count = 0;
  GPUDisplayParams m_display_params = {};

  bool m_software_readbacks = false;
  std::unique_ptr<u16[]> m_vram_shadow;

  std::unique_ptr<GPUTextureCache> m_texture_cache;
  GPUTextureCache::DrawKey m_texture_cache_key = {};
  u32 m_texture_cache_reload_count = 0;
  bool m_texture_cache_dumping = false;
  bool m_replaced_pipelines = false;

  /// One composed page per draw key, replaced when what it shows changes; a null texture remembers a failure.
  struct ReplacementSourceTexture
  {
    u64 id;
    u64 last_used;
    std::unique_ptr<GPUTexture> texture;
  };
  std::unordered_map<u64, ReplacementSourceTexture> m_replacement_sources;
  u64 m_replacement_source_counter = 0;
  /// Draws a replacement into a composed page, its alpha turned into the coverage marker.
  GPUPipeline* m_replacement_compose_pipeline = nullptr;

  BatchVertex* m_batch_vertex_ptr = nullptr;
  u16* m_batch_index_ptr = nullptr;
  u32 m_batch_base_vertex = 0;
  u32 m_batch_base_index = 0;
  u16 m_batch_vertex_count = 0;
  u16 m_batch_index_count = 0;
  u16 m_batch_vertex_space = 0;
  u16 m_batch_index_space = 0;
  s32 m_current_depth = 0;
  float m_last_depth_z = 1.0f;

  u8 m_resolution_scale = 1;
  u8 m_multisamples = 1;

  GPUTextureFilter m_texture_filtering = GPUTextureFilter::Nearest;
  GPUTextureFilter m_sprite_texture_filtering = GPUTextureFilter::Nearest;
  GPULineDetectMode m_line_detect_mode = GPULineDetectMode::Disabled;
  GPUDownsampleMode m_downsample_mode = GPUDownsampleMode::Disabled;
  GPUWireframeMode m_wireframe_mode = GPUWireframeMode::Disabled;

  bool m_supports_dual_source_blend : 1 = false;
  bool m_supports_framebuffer_fetch : 1 = false;
  bool m_true_color : 1 = true;
  bool m_pgxp_depth_buffer : 1 = false;
  bool m_clamp_uvs : 1 = false;
  bool m_compute_uv_range : 1 = false;
  bool m_allow_sprite_mode : 1 = false;
  bool m_allow_shader_blend : 1 = false;
  bool m_prefer_shader_blend : 1 = false;
  bool m_use_rov_for_shader_blend : 1 = false;
  bool m_write_mask_as_depth : 1 = false;
  bool m_depth_was_copied : 1 = false;
  bool m_texture_window_active : 1 = false;
  bool m_rov_active : 1 = false;

  u8 m_texpage_dirty = 0;

  BatchConfig m_batch;

  // Changed state
  bool m_batch_ubo_dirty = true;
  BatchUBOData m_batch_ubo_data = {};

  // Bounding box of VRAM area that the GPU has drawn into.
  GSVector4i m_vram_dirty_draw_rect = INVALID_RECT;
  GSVector4i m_vram_dirty_write_rect = INVALID_RECT;
  GSVector4i m_current_uv_rect = INVALID_RECT;
  s32 m_current_texture_page_offset[2] = {};

  GPUPipeline* m_wireframe_pipeline = nullptr;

  // [wrapped][interlaced]
  DimensionalArray<GPUPipeline*, 2, 2> m_vram_fill_pipelines{};

  // [depth_test]
  std::array<GPUPipeline*, 2> m_vram_write_pipelines{};
  std::array<GPUPipeline*, 2> m_vram_copy_pipelines{};

  GPUPipeline* m_vram_readback_pipeline = nullptr;
  GPUPipeline* m_vram_update_depth_pipeline = nullptr;
  GPUPipeline* m_vram_write_replacement_pipeline = nullptr;

  std::array<GPUPipeline*, 3> m_vram_extract_pipeline{}; // [24bit, 2=depth]
  std::unique_ptr<GPUTexture> m_vram_extract_texture;
  std::unique_ptr<GPUTexture> m_vram_extract_depth_texture;
  GPUPipeline* m_copy_depth_pipeline = nullptr;

  std::unique_ptr<GPUTexture> m_downsample_texture;
  GPUPipeline* m_downsample_first_pass_pipeline = nullptr;
  GPUPipeline* m_downsample_mid_pass_pipeline = nullptr;
  GPUPipeline* m_downsample_blur_pass_pipeline = nullptr;
  GPUPipeline* m_downsample_composite_pass_pipeline = nullptr;
  std::unique_ptr<GPUSampler> m_downsample_lod_sampler;
  std::unique_ptr<GPUSampler> m_downsample_composite_sampler;
  u32 m_downsample_scale_or_levels = 0;

  // [depth_test][transparency_mode][render_mode][texture_mode][dithering][interlacing][check_mask]
  DimensionalArray<GPUPipeline*, 2, 2, 2, NUM_TEXTURE_MODES, 5, 5, 2> m_batch_pipelines{};

  // Owns every pipeline above: one built pipeline can fill several of their slots.
  std::vector<std::unique_ptr<GPUPipeline>> m_pipeline_storage;
};

/// The GPU thread's hardware renderer, while one is in use.
extern std::unique_ptr<GPU_HW> g_gpu_hw;
