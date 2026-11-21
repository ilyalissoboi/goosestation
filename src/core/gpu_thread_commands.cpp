// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "gpu_thread_commands.h"
#include "gpu_hw.h"
#include "gpu_sw.h"

#include "gpu_presenter.h"

#include "util/gpu_device.h"
#include "util/gpu_thread.h"

#include "common/assert.h"

#include <atomic>
#include <mutex>

namespace GPUThreadCommands {
static void HandlePresent(const GPUThreadPresentCommand* cmd);

// Measured by the GPU thread's presents, read by the emulation thread's counters.
static std::mutex s_gpu_time_mutex;
static float s_gpu_time_ms = 0.0f;
static u32 s_gpu_time_presents = 0;

// Whether the last present began, for the explicit present's submit.
static bool s_last_present_done = false;

// Input latency and the last frame's active time, from the GPU thread's presents.
static std::mutex s_latency_mutex;
static double s_latency_total_ms = 0.0;
static u32 s_latency_frames = 0;
static std::atomic<Common::Timer::Value> s_last_present_active_time{0};
} // namespace GPUThreadCommands

void GPUThreadCommands::Handle(const GPUThreadCommand* cmd)
{
  switch (static_cast<GPUThreadCommandType>(cmd->type))
  {
    case GPUThreadCommandType::UpdateSWDisplay:
      GPU_SW::UpdateDisplayOnGPUThread(static_cast<const GPUThreadUpdateSWDisplayCommand*>(cmd));
      break;

    case GPUThreadCommandType::HWFlushRender:
    case GPUThreadCommandType::HWSetDrawState:
    case GPUThreadCommandType::HWSetDrawingArea:
    case GPUThreadCommandType::HWFillVRAM:
    case GPUThreadCommandType::HWUpdateVRAM:
    case GPUThreadCommandType::HWLoadVRAM:
    case GPUThreadCommandType::HWCopyVRAM:
    case GPUThreadCommandType::HWDrawPolygon:
    case GPUThreadCommandType::HWDrawRectangle:
    case GPUThreadCommandType::HWDrawLine:
    case GPUThreadCommandType::HWUpdateDisplay:
    case GPUThreadCommandType::HWBufferSwapped:
      g_gpu_hw->HandleCommand(cmd);
      break;

    case GPUThreadCommandType::Present:
      HandlePresent(static_cast<const GPUThreadPresentCommand*>(cmd));
      break;

    case GPUThreadCommandType::SubmitPresent:
      if (s_last_present_done)
        g_gpu_device->SubmitPresent();
      break;

    default:
      UnreachableCode();
      break;
  }
}

void GPUThreadCommands::HandlePresent(const GPUThreadPresentCommand* cmd)
{
  const std::unique_ptr<ImGuiDrawCopy> ui(cmd->ui);

  bool do_present;
  if (cmd->show_display)
    do_present = g_gpu_presenter->PresentDisplay();
  else
    do_present = g_gpu_device->BeginPresent(cmd->skip_present);

  if (do_present)
  {
    g_gpu_device->RenderImGui(ui->Get());
    g_gpu_device->EndPresent(cmd->explicit_present);

    if (g_gpu_device->IsGPUTimingEnabled())
    {
      const float time = g_gpu_device->GetAndResetAccumulatedGPUTime();
      std::unique_lock lock(s_gpu_time_mutex);
      s_gpu_time_ms += time;
      s_gpu_time_presents++;
    }
  }

  s_last_present_done = do_present;

  const Common::Timer::Value now = Common::Timer::GetCurrentValue();
  if (do_present && cmd->input_poll_time != 0)
  {
    std::unique_lock lock(s_latency_mutex);
    s_latency_total_ms += Common::Timer::ConvertValueToMilliseconds(now - cmd->input_poll_time);
    s_latency_frames++;
  }
  if (cmd->frame_start_time != 0)
    s_last_present_active_time.store(now - cmd->frame_start_time, std::memory_order_relaxed);

  GPUThread::FramePresented();
}

void GPUThreadCommands::PushPresent(std::unique_ptr<ImGuiDrawCopy> ui, Common::Timer::Value input_poll_time,
                                    Common::Timer::Value frame_start_time, bool show_display, bool skip_present,
                                    bool explicit_present)
{
  GPUThreadPresentCommand* cmd = Allocate<GPUThreadPresentCommand>(GPUThreadCommandType::Present);
  cmd->ui = ui.release();
  cmd->input_poll_time = input_poll_time;
  cmd->frame_start_time = frame_start_time;
  cmd->show_display = show_display;
  cmd->skip_present = skip_present;
  cmd->explicit_present = explicit_present;
  GPUThread::FramePushed();
  GPUThread::PushCommand(cmd);
}

void GPUThreadCommands::PushSubmitPresent()
{
  GPUThread::PushCommand(Allocate<GPUThreadCommand>(GPUThreadCommandType::SubmitPresent));
}

void GPUThreadCommands::GetAndResetGPUTime(float* time_ms, u32* presents)
{
  std::unique_lock lock(s_gpu_time_mutex);
  *time_ms = s_gpu_time_ms;
  *presents = s_gpu_time_presents;
  s_gpu_time_ms = 0.0f;
  s_gpu_time_presents = 0;
}

void GPUThreadCommands::GetAndResetInputLatency(double* total_ms, u32* frames)
{
  std::unique_lock lock(s_latency_mutex);
  *total_ms = s_latency_total_ms;
  *frames = s_latency_frames;
  s_latency_total_ms = 0.0;
  s_latency_frames = 0;
}

Common::Timer::Value GPUThreadCommands::GetLastPresentActiveTime()
{
  return s_last_present_active_time.load(std::memory_order_relaxed);
}
