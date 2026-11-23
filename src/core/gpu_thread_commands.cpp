// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "gpu_thread_commands.h"
#include "gpu_hw.h"
#include "gpu_sw.h"

#include "frame_pacing.h"
#include "gpu_presenter.h"

#include "util/gpu_device.h"
#include "util/gpu_thread.h"

#include "common/assert.h"
#include "common/log.h"

Log_SetChannel(GPUThreadCommands);

#include <array>
#include <atomic>
#include <mutex>

namespace GPUThreadCommands {
static void HandlePresent(const GPUThreadPresentCommand* cmd);

// Measured by the GPU thread's presents, read by the emulation thread's counters.
static std::mutex s_gpu_time_mutex;
static float s_gpu_time_ms = 0.0f;
static u32 s_gpu_time_presents = 0;

// An explicit present began and not yet submitted.
static bool s_submit_pending = false;

// Input latency and the last frame's active time, from the GPU thread's presents.
static std::mutex s_latency_mutex;
static double s_latency_total_ms = 0.0;
static u32 s_latency_frames = 0;
static std::atomic<Common::Timer::Value> s_last_present_active_time{0};

// Frames locked to the screen: presents with a deadline, and those done by it.
static std::atomic<u64> s_deadline_presents{0};
static std::atomic<u64> s_deadline_made{0};

// The screen's real cutoff, from the presents' feedback (on the GPU thread), and the targets the presents aimed at.
static FramePacing::CutoffTracker s_cutoff_tracker;
static std::atomic<s64> s_cutoff_offset{0};
static std::atomic_bool s_cutoff_reset{false};
static std::atomic<u64> s_cutoff_reports{0};
struct PresentTarget
{
  u64 id;
  Common::Timer::Value frame_start_time;
  Common::Timer::Value period;
  s64 offset;
  bool ran_long;
};
static std::array<PresentTarget, 16> s_present_targets = {};
static void TrackCutoff(const GPUThreadPresentCommand* cmd, bool presented);

// Present waits (desktop, pacing stage 2b), on the GPU thread: which present to wait for, the presents counted since
// the waits started over, the last one waited for and when it was shown, and what the waits must start over on.
static constexpr double PRESENT_WAIT_TIMEOUT_MS = 100.0;
static FramePacing::PresentWaitDepth s_wait_depth;
static u64 s_waited_presents = 0;
static u64 s_last_waited = 0;
static Common::Timer::Value s_last_shown = 0;
static const GPUDevice* s_wait_device = nullptr;
static GPUVSyncMode s_wait_vsync_mode = GPUVSyncMode::Disabled;
static std::atomic_bool s_wait_reset{false};
// The timeline they make, read by the emulation thread.
static std::mutex s_present_timeline_mutex;
static FramePacing::PresentTimeline s_present_timeline;
static void WaitForPresent(const GPUThreadPresentCommand* cmd);
static void ResetPresentWaits();
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
      if (s_submit_pending)
      {
        s_submit_pending = false;
        g_gpu_device->SubmitPresent();
      }
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
    WaitForPresent(cmd);

    if (g_gpu_device->IsGPUTimingEnabled())
    {
      const float time = g_gpu_device->GetAndResetAccumulatedGPUTime();
      std::unique_lock lock(s_gpu_time_mutex);
      s_gpu_time_ms += time;
      s_gpu_time_presents++;
    }
  }

  s_submit_pending = do_present && cmd->explicit_present;

  const Common::Timer::Value now = Common::Timer::GetCurrentValue();
  if (do_present && cmd->input_poll_time != 0)
  {
    std::unique_lock lock(s_latency_mutex);
    s_latency_total_ms += Common::Timer::ConvertValueToMilliseconds(now - cmd->input_poll_time);
    s_latency_frames++;
  }
  if (cmd->frame_start_time != 0)
    s_last_present_active_time.store(now - cmd->frame_start_time, std::memory_order_relaxed);
  TrackCutoff(cmd, do_present);

  if (cmd->deadline != 0 && do_present)
  {
    s_deadline_presents.fetch_add(1, std::memory_order_relaxed);
    if (now <= cmd->deadline)
      s_deadline_made.fetch_add(1, std::memory_order_relaxed);
  }

  GPUThread::FramePresented();
}

void GPUThreadCommands::PushPresent(std::unique_ptr<ImGuiDrawCopy> ui, Common::Timer::Value input_poll_time,
                                    Common::Timer::Value frame_start_time, bool show_display, bool skip_present,
                                    bool explicit_present, Common::Timer::Value deadline,
                                    Common::Timer::Value period, s64 offset, bool ran_long, u32 refreshes)
{
  GPUThreadPresentCommand* cmd = Allocate<GPUThreadPresentCommand>(GPUThreadCommandType::Present);
  cmd->ui = ui.release();
  cmd->input_poll_time = input_poll_time;
  cmd->frame_start_time = frame_start_time;
  cmd->show_display = show_display;
  cmd->skip_present = skip_present;
  cmd->explicit_present = explicit_present;
  cmd->deadline = deadline;
  cmd->period = period;
  cmd->offset = offset;
  cmd->ran_long = ran_long;
  cmd->refreshes = std::max(refreshes, 1u);
  GPUThread::FramePushed();
  GPUThread::PushCommand(cmd);
  GPUThread::Publish(); // the frame is drawn while the next is emulated
}

void GPUThreadCommands::BeforeWindowChange()
{
  if (g_gpu_hw)
    g_gpu_hw->FlushRenderForDeviceChange();

  if (s_submit_pending)
  {
    s_submit_pending = false;
    if (g_gpu_device)
      g_gpu_device->SubmitPresent();
  }
}

void GPUThreadCommands::AfterWindowChange()
{
  s_wait_reset.store(true, std::memory_order_release);
  if (g_gpu_hw)
    g_gpu_hw->RestoreDeviceContext();
}

void GPUThreadCommands::TrackCutoff(const GPUThreadPresentCommand* cmd, bool presented)
{
  if (s_cutoff_reset.exchange(false, std::memory_order_acq_rel))
  {
    s_cutoff_tracker.Reset();
    s_present_targets = {};
    s_cutoff_offset.store(0, std::memory_order_release);
  }

  // Where presents are waited on, the waits' times teach the tracker (WaitForPresent()).
  if (!g_gpu_device->HasPresentFeedback() || g_gpu_device->HasPresentWait())
    return;

  // This present's target, for when its feedback comes back.
  if (presented && cmd->deadline != 0 && cmd->frame_start_time != 0)
  {
    const u64 id = g_gpu_device->GetLastPresentId();
    if (id != 0)
      s_present_targets[id % s_present_targets.size()] =
        PresentTarget{id, cmd->frame_start_time, cmd->period, cmd->offset, cmd->ran_long};
  }

  std::array<GPUDevice::PresentFeedback, 16> feedback;
  const u32 count = g_gpu_device->FetchPresentFeedback(feedback);
  bool changed = false;
  for (u32 i = 0; i < count; i++)
  {
    const PresentTarget& target = s_present_targets[feedback[i].id % s_present_targets.size()];
    if (target.id != feedback[i].id || target.frame_start_time == 0 || feedback[i].present_time < target.frame_start_time)
      continue; // not locked to the screen, or forgotten

    s_cutoff_tracker.Report(feedback[i].present_time - target.frame_start_time, target.offset, target.period,
                            target.ran_long);
    changed = true;
    s_cutoff_reports.fetch_add(1, std::memory_order_relaxed);
  }
  if (changed)
    s_cutoff_offset.store(s_cutoff_tracker.SignedOffset(), std::memory_order_release);
}

void GPUThreadCommands::ResetPresentWaits()
{
  s_wait_depth.Reset();
  s_waited_presents = 0;
  s_last_waited = 0;
  s_last_shown = 0;
  std::unique_lock lock(s_present_timeline_mutex);
  s_present_timeline.Reset();
}

void GPUThreadCommands::WaitForPresent(const GPUThreadPresentCommand* cmd)
{
  // Only presents that block on the screen and reach it now (an explicit present waits for its submit, and optimal
  // frame pacing throttles itself).
  const GPUVSyncMode vsync_mode = g_gpu_device->GetVSyncMode();
  if (cmd->explicit_present || vsync_mode != GPUVSyncMode::FIFO || !g_gpu_device->HasPresentWait())
  {
    if (s_wait_device)
    {
      ResetPresentWaits();
      s_wait_device = nullptr;
    }
    return;
  }

  // Another device, swap chain or window: the presents and the screen's refreshes start over.
  if (s_wait_reset.exchange(false, std::memory_order_acq_rel) || s_wait_device != g_gpu_device.get() ||
      s_wait_vsync_mode != vsync_mode)
  {
    ResetPresentWaits();
    ResetCutoffOffset();
    s_wait_device = g_gpu_device.get();
    s_wait_vsync_mode = vsync_mode;
  }

  // This present's aim, for when it's shown.
  const u64 id = ++s_waited_presents;
  s_present_targets[id % s_present_targets.size()] =
    (cmd->deadline != 0 && cmd->frame_start_time != 0) ?
      PresentTarget{id, cmd->frame_start_time, cmd->period, cmd->offset, cmd->ran_long} :
      PresentTarget{};

  const u32 depth = s_wait_depth.Depth();
  if (depth == FramePacing::PresentWaitDepth::NO_WAIT)
  {
    s_wait_depth.NotWaited();
    return;
  }

  // Already waited for (the depth just went up): nothing to wait for this time.
  const u64 waited = (id > depth) ? (id - depth) : 0;
  if (waited == 0 || waited <= s_last_waited)
    return;

  const float refresh_hz = g_gpu_device->GetWindowInfo().surface_refresh_rate;
  const Common::Timer::Value shown =
    g_gpu_device->WaitForPresent(depth, Common::Timer::ConvertMillisecondsToValue(PRESENT_WAIT_TIMEOUT_MS));

  Common::Timer::Value period;
  {
    DisplayTimeline timeline;
    std::unique_lock lock(s_present_timeline_mutex);
    period = s_present_timeline.Get(Common::Timer::GetCurrentValue(), &timeline) ?
               timeline.period :
               Common::Timer::ConvertSecondsToValue(1.0 / ((refresh_hz > 0.0f) ? refresh_hz : 60.0f));
    if (shown != 0)
      s_present_timeline.Add(shown, refresh_hz);
  }

  if (shown == 0)
  {
    s_wait_depth.Report(0, period, 1);
    return;
  }

  if (s_last_shown != 0 && shown > s_last_shown)
    s_wait_depth.Report(shown - s_last_shown, period, cmd->refreshes * static_cast<u32>(waited - s_last_waited));
  s_last_waited = waited;
  s_last_shown = shown;

  // The waited present's aim teaches the cutoff tracker where the screen takes frames.
  const PresentTarget& target = s_present_targets[waited % s_present_targets.size()];
  if (target.id == waited && target.frame_start_time != 0 && shown >= target.frame_start_time)
  {
    s_cutoff_tracker.Report(shown - target.frame_start_time, target.offset, target.period, target.ran_long);
    s_cutoff_offset.store(s_cutoff_tracker.SignedOffset(), std::memory_order_release);
    s_cutoff_reports.fetch_add(1, std::memory_order_relaxed);
  }
}

bool GPUThreadCommands::GetPresentTimeline(DisplayTimeline* timeline)
{
  std::unique_lock lock(s_present_timeline_mutex);
  return s_present_timeline.Get(Common::Timer::GetCurrentValue(), timeline);
}

u64 GPUThreadCommands::GetCutoffReports()
{
  return s_cutoff_reports.load(std::memory_order_relaxed);
}

s64 GPUThreadCommands::GetCutoffOffset()
{
  return s_cutoff_offset.load(std::memory_order_acquire);
}

void GPUThreadCommands::ResetCutoffOffset()
{
  s_cutoff_offset.store(0, std::memory_order_release);
  s_cutoff_reset.store(true, std::memory_order_release);
}

void GPUThreadCommands::GetDeadlineStats(u64* presents, u64* made)
{
  *presents = s_deadline_presents.load(std::memory_order_relaxed);
  *made = s_deadline_made.load(std::memory_order_relaxed);
}

void GPUThreadCommands::PushSubmitPresent()
{
  GPUThread::PushCommand(Allocate<GPUThreadCommand>(GPUThreadCommandType::SubmitPresent));
  GPUThread::Publish();
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
