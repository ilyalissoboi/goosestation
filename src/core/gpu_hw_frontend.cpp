// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#include "gpu_hw_frontend.h"
#include "cpu_pgxp.h"
#include "gpu_hw.h"
#include "gpu_sw_backend.h"
#include "gpu_thread_commands.h"
#include "settings.h"

#include "util/gpu_thread.h"
#include "util/state_wrapper.h"

#include "common/assert.h"
#include "common/log.h"

#include <array>
#include <cstring>

Log_SetChannel(GPU_HW);

namespace {

template<typename T>
ALWAYS_INLINE T* NewCommand(GPUThreadCommandType type, GPUBackendCommandParameters params, u32 size = sizeof(T))
{
  T* cmd = GPUThreadCommands::Allocate<T>(type, size);
  cmd->params = params.bits;
  return cmd;
}

// The rect a polygon's triangle covers, as GPU_HW culls it: max exclusive.
ALWAYS_INLINE GSVector4i TriangleRect(const GPUThreadHWDrawPolygonCommand::Vertex& a,
                                      const GPUThreadHWDrawPolygonCommand::Vertex& b,
                                      const GPUThreadHWDrawPolygonCommand::Vertex& c)
{
  const GSVector2 af = GSVector2(a.x, a.y);
  const GSVector2 bf = GSVector2(b.x, b.y);
  const GSVector2 cf = GSVector2(c.x, c.y);
  const GSVector2 min_pos = bf.min(cf).min(af);
  const GSVector2 max_pos = bf.max(cf).max(af);
  return GSVector4i(GSVector4(min_pos).upld(GSVector4(max_pos))).add32(GSVector4i::cxpr(0, 0, 1, 1));
}

} // namespace

GPU_HWFrontend::GPU_HWFrontend() = default;

GPU_HWFrontend::~GPU_HWFrontend()
{
  GPUThread::RunOnThreadAndWait([]() { g_gpu_hw.reset(); });

  if (m_sw_renderer)
  {
    m_sw_renderer->Shutdown();
    m_sw_renderer.reset();
  }
}

const Threading::Thread* GPU_HWFrontend::GetSWThread() const
{
  return m_sw_renderer ? m_sw_renderer->GetThread() : nullptr;
}

bool GPU_HWFrontend::IsHardwareRenderer() const
{
  return true;
}

bool GPU_HWFrontend::Initialize()
{
  if (!GPU::Initialize())
    return false;

  bool created = false;
  GPUThread::RunOnThreadAndWait([&created, params = GetDisplayParams()]() {
    g_gpu_hw = std::make_unique<GPU_HW>();
    created = g_gpu_hw->Initialize(params);
    if (!created)
      g_gpu_hw.reset();
  });
  if (!created)
    return false;

  UpdateSoftwareRenderer(false);
  return true;
}

GPUBackendCommandParameters GPU_HWFrontend::GetCommandParameters() const
{
  GPUBackendCommandParameters params;
  params.bits = 0;
  params.check_mask_before_draw = m_GPUSTAT.check_mask_before_draw;
  params.set_mask_while_drawing = m_GPUSTAT.set_mask_while_drawing;
  params.active_line_lsb = m_crtc_state.active_line_lsb;
  params.interlaced_rendering = m_GPUSTAT.SkipDrawingToActiveField();
  return params;
}

void GPU_HWFrontend::FillBackendCommandParameters(GPUBackendCommand* cmd) const
{
  cmd->params.bits = GetCommandParameters().bits;
}

void GPU_HWFrontend::FillDrawCommand(GPUBackendDrawCommand* cmd, GPURenderCommand rc) const
{
  FillBackendCommandParameters(cmd);
  cmd->rc.bits = rc.bits;
  cmd->draw_mode.bits = m_draw_mode.mode_reg.bits;
  cmd->palette.bits = m_draw_mode.palette_reg.bits;
  cmd->window = m_draw_mode.texture_window;
}

void GPU_HWFrontend::SyncDrawState()
{
  const bool events = (m_draw_mode.IsTexturePageChanged() || m_draw_mode.IsTextureWindowChanged());
  if (!m_draw_state_sent || events || m_draw_mode.mode_reg.bits != m_sent_mode_reg.bits ||
      m_draw_mode.palette_reg.bits != m_sent_palette_reg.bits ||
      std::memcmp(&m_draw_mode.texture_window, &m_sent_texture_window, sizeof(m_sent_texture_window)) != 0)
  {
    GPUThreadHWSetDrawStateCommand* cmd = GPUThreadCommands::Allocate<GPUThreadHWSetDrawStateCommand>(
      GPUThreadCommandType::HWSetDrawState);
    cmd->mode_reg.bits = m_draw_mode.mode_reg.bits;
    cmd->palette_reg.bits = m_draw_mode.palette_reg.bits;
    cmd->texture_window = m_draw_mode.texture_window;
    cmd->events = (m_draw_mode.IsTexturePageChanged() ? GPUThreadHWSetDrawStateCommand::EVENT_PAGE_CHANGED : 0) |
                  (m_draw_mode.IsTextureWindowChanged() ? GPUThreadHWSetDrawStateCommand::EVENT_WINDOW_CHANGED : 0);
    GPUThread::PushCommand(cmd);

    m_draw_mode.ClearTexturePageChangedFlag();
    m_draw_mode.ClearTextureWindowChangedFlag();
    m_sent_mode_reg.bits = m_draw_mode.mode_reg.bits;
    m_sent_palette_reg.bits = m_draw_mode.palette_reg.bits;
    m_sent_texture_window = m_draw_mode.texture_window;
    m_draw_state_sent = true;
  }

  if (m_drawing_area_changed)
  {
    // The clamped area is also what the ticks of the next draw are counted in.
    m_drawing_area_changed = false;
    SetClampedDrawingArea();

    GPUThreadHWSetDrawingAreaCommand* cmd = GPUThreadCommands::Allocate<GPUThreadHWSetDrawingAreaCommand>(
      GPUThreadCommandType::HWSetDrawingArea);
    cmd->area = m_drawing_area;
    GSVector4i::store<false>(cmd->clamped_area, m_clamped_drawing_area);
    GPUThread::PushCommand(cmd);
    m_sent_drawing_area = m_drawing_area;
    m_sw_drawing_area_pending = true;
  }
}

void GPU_HWFrontend::Reset(bool clear_vram)
{
  GPUThread::RunOnThread([]() { g_gpu_hw->BeginReset(); });

  GPU::Reset(clear_vram);

  if (m_sw_renderer)
    m_sw_renderer->Reset();

  SyncDrawState();
  GPUThread::RunOnThread([clear_vram]() { g_gpu_hw->Reset(clear_vram); });
}

bool GPU_HWFrontend::DoState(StateWrapper& sw, GPUTexture** host_texture, bool update_display)
{
  // Need to download local VRAM copy before calling the base class, because it serializes this.
  if (m_sw_renderer)
  {
    m_sw_renderer->Sync(true);
  }
  else if (sw.IsWriting() && !host_texture)
  {
    // If SW renderer readbacks aren't enabled, the CLUT won't be populated, which means it'll be invalid if the user
    // loads this state with software instead of hardware renderers. So force-update the CLUT.
    ReadVRAM(0, 0, VRAM_WIDTH, VRAM_HEIGHT);
    if (IsCLUTValid())
      GPU::ReadCLUT(g_gpu_clut, GPUTexturePaletteReg{Truncate16(m_current_clut_reg_bits)}, m_current_clut_is_8bit);
  }

  if (!GPU::DoState(sw, host_texture, update_display))
    return false;

  bool result = true;
  if (host_texture)
  {
    const bool reading = sw.IsReading();
    GPUThread::RunOnThreadAndWait(
      [host_texture, reading, &result]() { result = g_gpu_hw->DoHostTextureState(host_texture, reading); });
    if (!result)
      return false;
  }
  else if (sw.IsReading())
  {
    // The state's VRAM, copied: the software readback renderer may write g_vram while the GPU thread uploads it.
    GPUThreadHWUpdateVRAMCommand* cmd = NewCommand<GPUThreadHWUpdateVRAMCommand>(
      GPUThreadCommandType::HWLoadVRAM, GPUBackendCommandParameters{},
      sizeof(GPUThreadHWUpdateVRAMCommand) + VRAM_WIDTH * VRAM_HEIGHT * sizeof(u16));
    cmd->x = 0;
    cmd->y = 0;
    cmd->width = VRAM_WIDTH;
    cmd->height = VRAM_HEIGHT;
    std::memcpy(cmd->data, g_vram, VRAM_WIDTH * VRAM_HEIGHT * sizeof(u16));
    GPUThread::PushCommand(cmd);
  }

  if (sw.IsReading())
  {
    SyncDrawState();
    const bool host_texture_state = (host_texture != nullptr);
    const bool readback_vram = (host_texture_state && !m_sw_renderer);
    GPUThread::RunOnThreadAndWait([readback_vram]() { g_gpu_hw->OnStateLoaded(readback_vram); });
  }

  return result;
}

void GPU_HWFrontend::RestoreDeviceContext()
{
  GPUThread::RunOnThread([]() { g_gpu_hw->RestoreDeviceContext(); });
}

void GPU_HWFrontend::UpdateSettings(const Settings& old_settings)
{
  GPU::UpdateSettings(old_settings);

  // With software readbacks, g_vram is VRAM as drawn so far, which GPU_HW restores from when it rebuilds.
  if (m_sw_renderer)
    m_sw_renderer->Sync(true);
  GPUThread::RunOnThreadAndWait(
    [old_settings, params = GetDisplayParams()]() { g_gpu_hw->UpdateSettings(old_settings, params); });
  UpdateSoftwareRenderer(true);
}

void GPU_HWFrontend::UpdateResolutionScale()
{
  bool changed = false;
  GPUThread::RunOnThreadAndWait([&changed, params = GetDisplayParams()]() {
    changed = (g_gpu_hw->CalculateResolutionScale(params) != g_gpu_hw->GetResolutionScale());
  });
  if (changed)
    UpdateSettings(g_settings);
}

std::tuple<u32, u32> GPU_HWFrontend::GetEffectiveDisplayResolution(bool scaled /* = true */)
{
  const u32 scale = scaled ? GPU_HW::GetPublishedResolutionScale() : 1u;
  return std::make_tuple(m_crtc_state.display_vram_width * scale, m_crtc_state.display_vram_height * scale);
}

std::tuple<u32, u32> GPU_HWFrontend::GetFullDisplayResolution(bool scaled /* = true */)
{
  const u32 scale = scaled ? GPU_HW::GetPublishedResolutionScale() : 1u;
  return std::make_tuple(m_crtc_state.display_width * scale, m_crtc_state.display_height * scale);
}

void GPU_HWFrontend::UpdateDisplay()
{
  GPUThreadHWUpdateDisplayCommand* cmd =
    GPUThreadCommands::Allocate<GPUThreadHWUpdateDisplayCommand>(GPUThreadCommandType::HWUpdateDisplay);
  cmd->params = GetDisplayParams();
  cmd->show_vram = g_settings.debugging.show_vram;
  GPUThread::PushCommand(cmd);
}

void GPU_HWFrontend::FlushRender()
{
  GPUThread::PushCommand(GPUThreadCommands::Allocate<GPUThreadCommand>(GPUThreadCommandType::HWFlushRender));
}

void GPU_HWFrontend::ReadVRAM(u32 x, u32 y, u32 width, u32 height)
{
  if (m_sw_renderer)
  {
    m_sw_renderer->Sync(false);
    return;
  }

  // The answer is needed now: everything before it is drawn, then VRAM read back into g_vram.
  GPUThread::RunOnThreadAndWait([x, y, width, height]() { g_gpu_hw->ReadVRAM(x, y, width, height); });
}

void GPU_HWFrontend::FillVRAM(u32 x, u32 y, u32 width, u32 height, u32 color)
{
  if (m_sw_renderer)
  {
    GPUBackendFillVRAMCommand* cmd = m_sw_renderer->NewFillVRAMCommand();
    FillBackendCommandParameters(cmd);
    cmd->x = static_cast<u16>(x);
    cmd->y = static_cast<u16>(y);
    cmd->width = static_cast<u16>(width);
    cmd->height = static_cast<u16>(height);
    cmd->color = color;
    m_sw_renderer->PushCommand(cmd);
  }

  SyncDrawState();
  GPUThreadHWFillVRAMCommand* cmd =
    NewCommand<GPUThreadHWFillVRAMCommand>(GPUThreadCommandType::HWFillVRAM, GetCommandParameters());
  cmd->x = static_cast<u16>(x);
  cmd->y = static_cast<u16>(y);
  cmd->width = static_cast<u16>(width);
  cmd->height = static_cast<u16>(height);
  cmd->color = color;
  GPUThread::PushCommand(cmd);
}

void GPU_HWFrontend::UpdateVRAM(u32 x, u32 y, u32 width, u32 height, const void* data, bool set_mask, bool check_mask)
{
  const u32 num_words = width * height;
  if (m_sw_renderer)
  {
    GPUBackendUpdateVRAMCommand* cmd = m_sw_renderer->NewUpdateVRAMCommand(num_words);
    FillBackendCommandParameters(cmd);
    cmd->params.set_mask_while_drawing = set_mask;
    cmd->params.check_mask_before_draw = check_mask;
    cmd->x = static_cast<u16>(x);
    cmd->y = static_cast<u16>(y);
    cmd->width = static_cast<u16>(width);
    cmd->height = static_cast<u16>(height);
    std::memcpy(cmd->data, data, sizeof(u16) * num_words);
    m_sw_renderer->PushCommand(cmd);
  }

  SyncDrawState();
  GPUBackendCommandParameters params = GetCommandParameters();
  params.set_mask_while_drawing = set_mask;
  params.check_mask_before_draw = check_mask;
  GPUThreadHWUpdateVRAMCommand* cmd = NewCommand<GPUThreadHWUpdateVRAMCommand>(
    GPUThreadCommandType::HWUpdateVRAM, params, sizeof(GPUThreadHWUpdateVRAMCommand) + num_words * sizeof(u16));
  cmd->x = static_cast<u16>(x);
  cmd->y = static_cast<u16>(y);
  cmd->width = static_cast<u16>(width);
  cmd->height = static_cast<u16>(height);
  std::memcpy(cmd->data, data, sizeof(u16) * num_words);
  GPUThread::PushCommand(cmd);
}

void GPU_HWFrontend::CopyVRAM(u32 src_x, u32 src_y, u32 dst_x, u32 dst_y, u32 width, u32 height)
{
  if (m_sw_renderer)
  {
    GPUBackendCopyVRAMCommand* cmd = m_sw_renderer->NewCopyVRAMCommand();
    FillBackendCommandParameters(cmd);
    cmd->src_x = static_cast<u16>(src_x);
    cmd->src_y = static_cast<u16>(src_y);
    cmd->dst_x = static_cast<u16>(dst_x);
    cmd->dst_y = static_cast<u16>(dst_y);
    cmd->width = static_cast<u16>(width);
    cmd->height = static_cast<u16>(height);
    m_sw_renderer->PushCommand(cmd);
  }

  SyncDrawState();
  GPUThreadHWCopyVRAMCommand* cmd =
    NewCommand<GPUThreadHWCopyVRAMCommand>(GPUThreadCommandType::HWCopyVRAM, GetCommandParameters());
  cmd->src_x = static_cast<u16>(src_x);
  cmd->src_y = static_cast<u16>(src_y);
  cmd->dst_x = static_cast<u16>(dst_x);
  cmd->dst_y = static_cast<u16>(dst_y);
  cmd->width = static_cast<u16>(width);
  cmd->height = static_cast<u16>(height);
  GPUThread::PushCommand(cmd);
}

void GPU_HWFrontend::UpdateCLUT(GPUTexturePaletteReg reg, bool clut_is_8bit)
{
  // Not done in HW, but need to forward through to SW if using that for readbacks
  if (m_sw_renderer)
  {
    GPUBackendUpdateCLUTCommand* cmd = m_sw_renderer->NewUpdateCLUTCommand();
    FillBackendCommandParameters(cmd);
    cmd->reg.bits = reg.bits;
    cmd->clut_is_8bit = clut_is_8bit;
    m_sw_renderer->PushCommand(cmd);
  }
}

void GPU_HWFrontend::DispatchRenderCommand()
{
  const GPURenderCommand rc{m_render_command.bits};

  SyncDrawState();

  // The software readback renderer gets a new drawing area with the draw that follows it, as GPU_HW gave it.
  if (m_sw_drawing_area_pending)
  {
    m_sw_drawing_area_pending = false;
    if (m_sw_renderer)
    {
      GPUBackendSetDrawingAreaCommand* cmd = m_sw_renderer->NewSetDrawingAreaCommand();
      cmd->new_area = m_drawing_area;
      m_sw_renderer->PushCommand(cmd);
    }
  }

  switch (rc.primitive)
  {
    case GPUPrimitive::Polygon:
      DispatchPolygon(rc);
      break;

    case GPUPrimitive::Rectangle:
      DispatchRectangle(rc);
      break;

    case GPUPrimitive::Line:
      if (rc.polyline)
        DispatchPolyLine(rc);
      else
        DispatchLine(rc);
      break;

    default:
      UnreachableCode();
      break;
  }
}

void GPU_HWFrontend::DispatchPolygon(GPURenderCommand rc)
{
  using Vertex = GPUThreadHWDrawPolygonCommand::Vertex;

  const bool textured = rc.texture_enable;
  const bool raw_texture = textured && rc.raw_texture_enable;
  const bool shaded = rc.shading_enable;
  const bool pgxp = g_settings.gpu_pgxp_enable;
  const u32 first_color = rc.color_for_first_vertex;
  const u32 num_vertices = rc.quad_polygon ? 4 : 3;

  GPUThreadHWDrawPolygonCommand* cmd = NewCommand<GPUThreadHWDrawPolygonCommand>(
    GPUThreadCommandType::HWDrawPolygon, GetCommandParameters(),
    sizeof(GPUThreadHWDrawPolygonCommand) + sizeof(Vertex) * num_vertices);
  cmd->rc.bits = rc.bits;
  cmd->num_vertices = static_cast<u8>(num_vertices);

  std::array<GSVector2i, 4> native_positions;
  bool valid_w = g_settings.gpu_pgxp_texture_correction;
  for (u32 i = 0; i < num_vertices; i++)
  {
    Vertex& v = cmd->vertices[i];
    const u32 vert_color = (shaded && i > 0) ? (FifoPop() & UINT32_C(0x00FFFFFF)) : first_color;
    const u64 maddr_and_pos = m_fifo.Pop();
    const GPUVertexPosition vp{Truncate32(maddr_and_pos)};
    v.texcoord = textured ? Truncate16(FifoPop()) : 0;
    v.color = raw_texture ? UINT32_C(0x00808080) : vert_color;
    v.native_x = native_positions[i].x = m_drawing_offset.x + vp.x;
    v.native_y = native_positions[i].y = m_drawing_offset.y + vp.y;
    v.x = static_cast<float>(v.native_x);
    v.y = static_cast<float>(v.native_y);
    v.w = 1.0f;

    if (pgxp)
    {
      valid_w &= CPU::PGXP::GetPreciseVertex(Truncate32(maddr_and_pos >> 32), vp.bits, v.native_x, v.native_y,
                                             m_drawing_offset.x, m_drawing_offset.y, &v.x, &v.y, &v.w);
    }
  }

  if (pgxp && !valid_w)
  {
    for (u32 i = 0; i < num_vertices; i++)
    {
      Vertex& v = cmd->vertices[i];
      if (g_settings.gpu_pgxp_disable_2d)
      {
        v.x = static_cast<float>(v.native_x);
        v.y = static_cast<float>(v.native_y);
      }
      v.w = 1.0f;
    }
  }
  cmd->valid_w = valid_w;

  if (m_sw_renderer)
  {
    GPUBackendDrawPolygonCommand* sw_cmd = m_sw_renderer->NewDrawPolygonCommand(num_vertices);
    FillDrawCommand(sw_cmd, rc);
    for (u32 i = 0; i < num_vertices; i++)
    {
      GPUBackendDrawPolygonCommand::Vertex* vert = &sw_cmd->vertices[i];
      vert->x = cmd->vertices[i].native_x;
      vert->y = cmd->vertices[i].native_y;
      vert->texcoord = cmd->vertices[i].texcoord;
      vert->color = cmd->vertices[i].color;
    }
    m_sw_renderer->PushCommand(sw_cmd);
  }

  // The ticks, for what GPU_HW will draw: culled the way it culls, on the positions it draws.
  const GSVector4i draw_rect_012 = TriangleRect(cmd->vertices[0], cmd->vertices[1], cmd->vertices[2]);
  const GSVector4i clamped_draw_rect_012 = draw_rect_012.rintersect(m_clamped_drawing_area);
  const bool first_tri_culled = (draw_rect_012.width() > MAX_PRIMITIVE_WIDTH ||
                                 draw_rect_012.height() > MAX_PRIMITIVE_HEIGHT || clamped_draw_rect_012.rempty());
  if (!first_tri_culled)
  {
    AddDrawTriangleTicks(native_positions[0], native_positions[1], native_positions[2], rc.shading_enable,
                         rc.texture_enable, rc.transparency_enable);
  }

  if (rc.quad_polygon)
  {
    const GSVector4i draw_rect_123 = TriangleRect(cmd->vertices[1], cmd->vertices[2], cmd->vertices[3]);
    const GSVector4i clamped_draw_rect_123 = draw_rect_123.rintersect(m_clamped_drawing_area);
    const bool second_tri_culled = (draw_rect_123.width() > MAX_PRIMITIVE_WIDTH ||
                                    draw_rect_123.height() > MAX_PRIMITIVE_HEIGHT || clamped_draw_rect_123.rempty());
    if (!second_tri_culled)
    {
      AddDrawTriangleTicks(native_positions[2], native_positions[1], native_positions[3], rc.shading_enable,
                           rc.texture_enable, rc.transparency_enable);
    }
  }

  GPUThread::PushCommand(cmd);
}

void GPU_HWFrontend::DispatchRectangle(GPURenderCommand rc)
{
  const u32 color = (rc.texture_enable && rc.raw_texture_enable) ? UINT32_C(0x00808080) : rc.color_for_first_vertex;
  const GPUVertexPosition vp{FifoPop()};
  const s32 pos_x = TruncateGPUVertexPosition(m_drawing_offset.x + vp.x);
  const s32 pos_y = TruncateGPUVertexPosition(m_drawing_offset.y + vp.y);

  const auto [texcoord_x, texcoord_y] = UnpackTexcoord(rc.texture_enable ? Truncate16(FifoPop()) : 0);
  u32 rectangle_width;
  u32 rectangle_height;
  switch (rc.rectangle_size)
  {
    case GPUDrawRectangleSize::R1x1:
      rectangle_width = 1;
      rectangle_height = 1;
      break;
    case GPUDrawRectangleSize::R8x8:
      rectangle_width = 8;
      rectangle_height = 8;
      break;
    case GPUDrawRectangleSize::R16x16:
      rectangle_width = 16;
      rectangle_height = 16;
      break;
    default:
    {
      const u32 width_and_height = FifoPop();
      rectangle_width = (width_and_height & VRAM_WIDTH_MASK);
      rectangle_height = ((width_and_height >> 16) & VRAM_HEIGHT_MASK);
    }
    break;
  }

  GPUThreadHWDrawRectangleCommand* cmd =
    NewCommand<GPUThreadHWDrawRectangleCommand>(GPUThreadCommandType::HWDrawRectangle, GetCommandParameters());
  cmd->rc.bits = rc.bits;
  cmd->x = pos_x;
  cmd->y = pos_y;
  cmd->width = static_cast<u16>(rectangle_width);
  cmd->height = static_cast<u16>(rectangle_height);
  cmd->texcoord = (static_cast<u16>(texcoord_y) << 8) | static_cast<u16>(texcoord_x);
  cmd->color = color;
  GPUThread::PushCommand(cmd);

  const GSVector4i rect =
    GSVector4i(pos_x, pos_y, pos_x + static_cast<s32>(rectangle_width), pos_y + static_cast<s32>(rectangle_height));
  const GSVector4i clamped_rect = m_clamped_drawing_area.rintersect(rect);
  if (clamped_rect.rempty()) [[unlikely]]
    return;

  AddDrawRectangleTicks(clamped_rect, rc.texture_enable, rc.transparency_enable);

  if (m_sw_renderer)
  {
    GPUBackendDrawRectangleCommand* sw_cmd = m_sw_renderer->NewDrawRectangleCommand();
    FillDrawCommand(sw_cmd, rc);
    sw_cmd->color = color;
    sw_cmd->x = pos_x;
    sw_cmd->y = pos_y;
    sw_cmd->width = static_cast<u16>(rectangle_width);
    sw_cmd->height = static_cast<u16>(rectangle_height);
    sw_cmd->texcoord = cmd->texcoord;
    m_sw_renderer->PushCommand(sw_cmd);
  }
}

void GPU_HWFrontend::DispatchLine(GPURenderCommand rc)
{
  u32 start_color, end_color;
  GPUVertexPosition start_pos, end_pos;
  if (rc.shading_enable)
  {
    start_color = rc.color_for_first_vertex;
    start_pos.bits = FifoPop();
    end_color = FifoPop() & UINT32_C(0x00FFFFFF);
    end_pos.bits = FifoPop();
  }
  else
  {
    start_color = end_color = rc.color_for_first_vertex;
    start_pos.bits = FifoPop();
    end_pos.bits = FifoPop();
  }

  const GSVector4i vstart_pos = GSVector4i(start_pos.x + m_drawing_offset.x, start_pos.y + m_drawing_offset.y);
  const GSVector4i vend_pos = GSVector4i(end_pos.x + m_drawing_offset.x, end_pos.y + m_drawing_offset.y);

  GPUThreadHWDrawLineCommand* cmd = NewCommand<GPUThreadHWDrawLineCommand>(
    GPUThreadCommandType::HWDrawLine, GetCommandParameters(),
    sizeof(GPUThreadHWDrawLineCommand) + sizeof(GPUThreadHWDrawLineCommand::Vertex) * 2);
  cmd->rc.bits = rc.bits;
  cmd->num_vertices = 2;
  cmd->vertices[0] = {vstart_pos.x, vstart_pos.y, start_color};
  cmd->vertices[1] = {vend_pos.x, vend_pos.y, end_color};
  GPUThread::PushCommand(cmd);

  const GSVector4i bounds = vstart_pos.xyxy(vend_pos);
  const GSVector4i rect =
    vstart_pos.min_i32(vend_pos).xyxy(vstart_pos.max_i32(vend_pos)).add32(GSVector4i::cxpr(0, 0, 1, 1));
  const GSVector4i clamped_rect = rect.rintersect(m_clamped_drawing_area);
  if (rect.width() > MAX_PRIMITIVE_WIDTH || rect.height() > MAX_PRIMITIVE_HEIGHT || clamped_rect.rempty())
    return;

  AddDrawLineTicks(clamped_rect, rc.shading_enable);

  if (m_sw_renderer)
  {
    GPUBackendDrawLineCommand* sw_cmd = m_sw_renderer->NewDrawLineCommand(2);
    FillDrawCommand(sw_cmd, rc);
    GSVector4i::storel(&sw_cmd->vertices[0], bounds);
    sw_cmd->vertices[0].color = start_color;
    GSVector4i::storeh(&sw_cmd->vertices[1], bounds);
    sw_cmd->vertices[1].color = end_color;
    m_sw_renderer->PushCommand(sw_cmd);
  }
}

void GPU_HWFrontend::DispatchPolyLine(GPURenderCommand rc)
{
  const u32 num_vertices = GetPolyLineVertexCount();
  const bool shaded = rc.shading_enable;

  GPUThreadHWDrawLineCommand* cmd = NewCommand<GPUThreadHWDrawLineCommand>(
    GPUThreadCommandType::HWDrawLine, GetCommandParameters(),
    sizeof(GPUThreadHWDrawLineCommand) + sizeof(GPUThreadHWDrawLineCommand::Vertex) * num_vertices);
  cmd->rc.bits = rc.bits;
  cmd->num_vertices = static_cast<u16>(num_vertices);

  GPUBackendDrawLineCommand* sw_cmd = nullptr;
  if (m_sw_renderer)
  {
    sw_cmd = m_sw_renderer->NewDrawLineCommand(num_vertices);
    FillDrawCommand(sw_cmd, rc);
  }

  u32 buffer_pos = 0;
  const GPUVertexPosition start_vp{m_blit_buffer[buffer_pos++]};
  GSVector4i start_pos = GSVector4i(start_vp.x + m_drawing_offset.x, start_vp.y + m_drawing_offset.y);
  u32 start_color = rc.color_for_first_vertex;
  cmd->vertices[0] = {start_pos.x, start_pos.y, start_color};
  if (sw_cmd)
  {
    GSVector4i::storel(&sw_cmd->vertices[0].x, start_pos);
    sw_cmd->vertices[0].color = start_color;
  }

  for (u32 i = 1; i < num_vertices; i++)
  {
    const u32 end_color = shaded ? (m_blit_buffer[buffer_pos++] & UINT32_C(0x00FFFFFF)) : start_color;
    const GPUVertexPosition vp{m_blit_buffer[buffer_pos++]};
    const GSVector4i end_pos = GSVector4i(m_drawing_offset.x + vp.x, m_drawing_offset.y + vp.y);
    const GSVector4i rect =
      start_pos.min_i32(end_pos).xyxy(start_pos.max_i32(end_pos)).add32(GSVector4i::cxpr(0, 0, 1, 1));
    const GSVector4i clamped_rect = rect.rintersect(m_clamped_drawing_area);
    if (rect.width() <= MAX_PRIMITIVE_WIDTH && rect.height() <= MAX_PRIMITIVE_HEIGHT && !clamped_rect.rempty())
      AddDrawLineTicks(clamped_rect, rc.shading_enable);

    cmd->vertices[i] = {end_pos.x, end_pos.y, end_color};
    if (sw_cmd)
    {
      GSVector4i::storel(&sw_cmd->vertices[i], end_pos);
      sw_cmd->vertices[i].color = end_color;
    }

    start_pos = end_pos;
    start_color = end_color;
  }

  GPUThread::PushCommand(cmd);
  if (sw_cmd)
    m_sw_renderer->PushCommand(sw_cmd);
}

void GPU_HWFrontend::DrawRendererStats()
{
  // ImGui on the GPU thread, while this thread waits for it.
  GPUThread::RunOnThreadAndWait([]() { g_gpu_hw->DrawRendererStats(); });
}

void GPU_HWFrontend::OnBufferSwapped()
{
  GPUThread::PushCommand(GPUThreadCommands::Allocate<GPUThreadCommand>(GPUThreadCommandType::HWBufferSwapped));
}

void GPU_HWFrontend::UpdateSoftwareRenderer(bool copy_vram_from_hw)
{
  const bool current_enabled = (m_sw_renderer != nullptr);
  const bool new_enabled = g_settings.gpu_use_software_renderer_for_readbacks;
  if (current_enabled == new_enabled)
    return;

  if (!new_enabled)
  {
    if (m_sw_renderer)
      m_sw_renderer->Shutdown();
    m_sw_renderer.reset();
    GPUThread::RunOnThread([]() { g_gpu_hw->SetSoftwareReadbacks(false); });
    return;
  }

  std::unique_ptr<GPU_SW_Backend> sw_renderer = std::make_unique<GPU_SW_Backend>();
  if (!sw_renderer->Initialize(true))
    return;

  // We need to fill in the SW renderer's VRAM with the current state for hot toggles.
  if (copy_vram_from_hw)
  {
    FlushRender();
    ReadVRAM(0, 0, VRAM_WIDTH, VRAM_HEIGHT);

    // Sync the drawing area and CLUT.
    GPUBackendSetDrawingAreaCommand* clip_cmd = sw_renderer->NewSetDrawingAreaCommand();
    clip_cmd->new_area = m_drawing_area;
    sw_renderer->PushCommand(clip_cmd);

    if (IsCLUTValid())
    {
      GPUBackendUpdateCLUTCommand* clut_cmd = sw_renderer->NewUpdateCLUTCommand();
      FillBackendCommandParameters(clut_cmd);
      clut_cmd->reg.bits = static_cast<u16>(m_current_clut_reg_bits);
      clut_cmd->clut_is_8bit = m_current_clut_is_8bit;
      sw_renderer->PushCommand(clut_cmd);
    }
  }

  m_sw_renderer = std::move(sw_renderer);
  GPUThread::RunOnThread([]() { g_gpu_hw->SetSoftwareReadbacks(true); });
}

std::unique_ptr<GPU> GPU::CreateHardwareRenderer()
{
  std::unique_ptr<GPU_HWFrontend> gpu(std::make_unique<GPU_HWFrontend>());
  if (!gpu->Initialize())
    return nullptr;

  return gpu;
}
