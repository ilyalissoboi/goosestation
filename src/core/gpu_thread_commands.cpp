// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "gpu_thread_commands.h"
#include "gpu_hw.h"
#include "gpu_sw.h"

#include "gpu_presenter.h"

#include "util/gpu_device.h"
#include "util/gpu_thread.h"

#include "common/assert.h"

#include <mutex>

namespace GPUThreadCommands {
static void HandlePresent(const GPUThreadPresentCommand* cmd);

// Measured by the GPU thread's presents, read by the emulation thread's counters.
static std::mutex s_gpu_time_mutex;
static float s_gpu_time_ms = 0.0f;
static u32 s_gpu_time_presents = 0;

// Whether the last present began, for the explicit present's submit.
static bool s_last_present_done = false;
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
  GPUThread::FramePresented();
}

void GPUThreadCommands::PushPresent(std::unique_ptr<ImGuiDrawCopy> ui, Common::Timer::Value input_poll_time,
                                    bool show_display, bool skip_present, bool explicit_present)
{
  GPUThreadPresentCommand* cmd = Allocate<GPUThreadPresentCommand>(GPUThreadCommandType::Present);
  cmd->ui = ui.release();
  cmd->input_poll_time = input_poll_time;
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
