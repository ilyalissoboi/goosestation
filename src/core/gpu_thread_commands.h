// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "gpu_presenter.h"
#include "gpu_types.h"

#include "util/gpu_texture.h"
#include "util/imgui_draw_copy.h"
#include "util/gpu_thread.h"
#include "util/gpu_thread_queue.h"

#include "common/timer.h"
#include "common/types.h"

#ifdef _MSC_VER
#pragma warning(push)
#pragma warning(disable : 4200) // zero-sized array in struct/union
#endif

/// The core's commands on the GPU thread's stream, after GPUThread's own.
enum class GPUThreadCommandType : u8
{
  UpdateSWDisplay = GPUThreadQueue::FIRST_USER_TYPE,

  // The hardware renderer's, from GPU_HWFrontend to GPU_HW. Each carries the GPUSTAT bits it was issued under (mask,
  // interlaced rendering, the active line) as GPUBackendCommandParameters in its header's params.
  HWFlushRender,
  HWSetDrawState,
  HWSetDrawingArea,
  HWFillVRAM,
  HWUpdateVRAM,
  HWLoadVRAM, // a state's VRAM: straight to the GPU, as GPU_HWFrontend::DoState() had it
  HWCopyVRAM,
  HWDrawPolygon,
  HWDrawRectangle,
  HWDrawLine,
  HWUpdateDisplay,
  HWBufferSwapped,

  // The frame's end.
  Present,
  SubmitPresent,

  MaxCount
};

/// The software renderer's frame, copied out of VRAM by the emulation thread, for the GPU thread to upload and show.
struct GPUThreadUpdateSWDisplayCommand : public GPUThreadCommand
{
  enum Mode : u8
  {
    MODE_CLEAR,       // the display is off: nothing shown
    MODE_PROGRESSIVE, // the frame as it is
    MODE_INTERLACED,  // one field, deinterlaced
    MODE_VRAM,        // the whole of VRAM
  };

  u16 width;
  u16 height;
  u32 stride;
  GPUTexture::Format format;
  Mode mode;
  u8 field;
  bool chroma_smoothing; // 24-bit with chroma smoothing on
  u8 pixels[0];
};

/// The draw mode, palette and texture window as the GPU has them, sent whenever they differ from what was last sent,
/// with the page and window changes the GPU flagged since.
struct GPUThreadHWSetDrawStateCommand : public GPUThreadCommand
{
  enum : u8
  {
    EVENT_PAGE_CHANGED = 1,
    EVENT_WINDOW_CHANGED = 2,
  };

  GPUDrawModeReg mode_reg;
  GPUTexturePaletteReg palette_reg;
  GPUTextureWindow texture_window;
  u8 events;
};

/// The drawing area as the GPU has it, sent whenever it differs from what was last sent.
struct GPUThreadHWSetDrawingAreaCommand : public GPUThreadCommand
{
  GPUDrawingArea area;
  s32 clamped_area[4]; // left, top, right, bottom (exclusive), as GPU::SetClampedDrawingArea() makes it
};

struct GPUThreadHWFillVRAMCommand : public GPUThreadCommand
{
  u16 x;
  u16 y;
  u16 width;
  u16 height;
  u32 color;
};

/// The words written, copied in; set and check mask are the transfer's, in params.
struct GPUThreadHWUpdateVRAMCommand : public GPUThreadCommand
{
  u16 x;
  u16 y;
  u16 width;
  u16 height;
  u16 data[0];
};

struct GPUThreadHWCopyVRAMCommand : public GPUThreadCommand
{
  u16 src_x;
  u16 src_y;
  u16 dst_x;
  u16 dst_y;
  u16 width;
  u16 height;
};

/// A polygon's vertices with the drawing offset applied: native for the texture page and culling, x/y/w as PGXP left
/// them (or native, with w 1, without PGXP).
struct GPUThreadHWDrawPolygonCommand : public GPUThreadCommand
{
  struct Vertex
  {
    float x, y, w;
    s32 native_x, native_y;
    u32 color;
    u16 texcoord;
  };

  GPURenderCommand rc;
  u8 num_vertices;
  bool valid_w; // PGXP: every vertex has a w
  Vertex vertices[0];
};

struct GPUThreadHWDrawRectangleCommand : public GPUThreadCommand
{
  GPURenderCommand rc;
  s32 x;
  s32 y;
  u16 width;
  u16 height;
  u16 texcoord;
  u32 color;
};

/// A line or poly-line's vertices, with the drawing offset applied.
struct GPUThreadHWDrawLineCommand : public GPUThreadCommand
{
  struct Vertex
  {
    s32 x, y;
    u32 color;
  };

  GPURenderCommand rc;
  u16 num_vertices;
  Vertex vertices[0];
};

struct GPUThreadHWUpdateDisplayCommand : public GPUThreadCommand
{
  GPUDisplayParams params;
  bool show_vram;
};

/// A present: the display (if shown) and the UI's frame, ended and counted as presented.
struct GPUThreadPresentCommand : public GPUThreadCommand
{
  ImGuiDrawCopy* ui;                 // owned by the command
  Common::Timer::Value input_poll_time;  // when the input the frame read was polled
  Common::Timer::Value frame_start_time; // when the frame's emulation started
  bool show_display;                 // the game's display under the UI; else the UI alone
  bool skip_present;                 // BeginPresent(skip) without the display
  bool explicit_present;             // optimal pacing: submitted by the SubmitPresent that follows
  Common::Timer::Value deadline;     // locked to the screen: the refresh's deadline it's due by (else 0)
  Common::Timer::Value period;         // the screen's refresh period (with deadline)
  Common::Timer::Value offset;         // how far past the timeline's deadline it was aimed
  bool ran_long;                       // its frame's own work took longer than planned (late whatever the aim)
};

namespace GPUThreadCommands {

/// The GPU thread's handler for the core's commands.
void Handle(const GPUThreadCommand* cmd);

/// Pushes a present (and counts it with GPUThread::FramePushed()).
void PushPresent(std::unique_ptr<ImGuiDrawCopy> ui, Common::Timer::Value input_poll_time,
                 Common::Timer::Value frame_start_time, bool show_display, bool skip_present, bool explicit_present,
                 Common::Timer::Value deadline = 0, Common::Timer::Value period = 0, Common::Timer::Value offset = 0,
                 bool ran_long = false);

/// Locked to the screen: how long after the timeline's deadline frames should be done by, from the presents' feedback
/// (the screen's real cutoff, less a safety margin); 0 without feedback.
Common::Timer::Value GetCutoffOffset();

/// How many presents' feedback reached the cutoff tracker since start (for the tests).
u64 GetCutoffReports();

/// Forgets what the feedback taught (the lock starts again: another screen, another rate).
void ResetCutoffOffset();

/// Presents that had a deadline (frames locked to the screen) since start, and those done by it.
void GetDeadlineStats(u64* presents, u64* made);

/// Pushes the submit of an explicit present, after the throttle.
void PushSubmitPresent();

/// On the GPU thread, before the window changes under the device: the batch being built drawn, and an explicit
/// present waiting for its submit submitted to the swap chain it began on.
void BeforeWindowChange();

/// On the GPU thread, after the window changed: the renderer's state bound again.
void AfterWindowChange();

/// The GPU time the GPU thread's presents measured since the last call, and how many presents it took.
void GetAndResetGPUTime(float* time_ms, u32* presents);

/// Input latency since the last call: the time from each presented frame's input poll until its present finished,
/// summed, and how many frames.
void GetAndResetInputLatency(double* total_ms, u32* frames);

/// From the start of the last presented frame until its present finished (the pre-frame sleep's active time).
Common::Timer::Value GetLastPresentActiveTime();

/// A command of type and size (header included), its header filled, for GPUThread::PushCommand().
template<typename T>
ALWAYS_INLINE T* Allocate(GPUThreadCommandType type, u32 size = sizeof(T))
{
  return static_cast<T*>(GPUThread::AllocateCommand(static_cast<u8>(type), size));
}

} // namespace GPUThreadCommands

#ifdef _MSC_VER
#pragma warning(pop)
#endif
