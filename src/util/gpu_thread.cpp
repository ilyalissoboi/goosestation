// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "gpu_thread.h"
#include "gpu_device.h"

#include "common/align.h"
#include "common/assert.h"
#include "common/log.h"
#include "common/threading.h"

#include <atomic>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

Log_SetChannel(GPUThread);

namespace GPUThread {
namespace {

// Room for the largest single command (a software renderer frame, a whole VRAM upload) with space to spare.
constexpr u32 QUEUE_SIZE = 16 * 1024 * 1024;

// How long the thread spins for more work before it sleeps.
constexpr u64 SPIN_NS = 1000000;

/// Signalled once; waited for by one thread.
struct Signal
{
  std::mutex mutex;
  std::condition_variable cv;
  bool done = false;

  void Post()
  {
    std::unique_lock lock(mutex);
    done = true;
    cv.notify_one();
  }

  void Wait()
  {
    std::unique_lock lock(mutex);
    cv.wait(lock, [this]() { return done; });
  }
};

struct SyncCommand : GPUThreadCommand
{
  Signal* signal;
};

struct ClosureCommand : GPUThreadCommand
{
  std::function<void()>* fn;
  Signal* signal;
};

struct RecycleTextureCommand : GPUThreadCommand
{
  GPUTexture* texture;
};

struct PriorityClosure
{
  std::function<void()> fn;
  Signal* signal;
};

std::unique_ptr<GPUThreadQueue> s_queue;
Threading::Thread s_thread;
bool s_started = false;
bool s_use_thread = false;
std::atomic<std::thread::id> s_owner{};
CommandHandler s_handler = nullptr;
void (*s_recycle_hook)(GPUTexture*) = nullptr;

// Commands run where they're pushed (inline, or pushed on the GPU thread) live here, one buffer per nesting level.
std::vector<std::vector<u8>> s_direct_buffers;
u32 s_direct_depth = 0;

std::mutex s_priority_mutex;
std::deque<PriorityClosure> s_priority;
std::atomic<u32> s_priority_count{0};

std::atomic<u32> s_queued_frames{0};
std::mutex s_frames_mutex;
std::condition_variable s_frames_cv;

std::mutex s_info_mutex;
DeviceInfo s_info;

void RunPriorityClosures()
{
  while (s_priority_count.load(std::memory_order_acquire) > 0)
  {
    PriorityClosure closure;
    {
      std::unique_lock lock(s_priority_mutex);
      if (s_priority.empty())
        break;
      closure = std::move(s_priority.front());
      s_priority.pop_front();
      s_priority_count.fetch_sub(1, std::memory_order_acq_rel);
    }

    closure.fn();
    if (closure.signal)
      closure.signal->Post();
  }
}

void RecycleTexture(GPUTexture* tex)
{
  if (s_recycle_hook)
    s_recycle_hook(tex);
  else if (g_gpu_device)
    g_gpu_device->RecycleTexture(std::unique_ptr<GPUTexture>(tex));
  else
    delete tex;
}

void HandleCommand(const GPUThreadCommand* cmd)
{
  switch (cmd->type)
  {
    case COMMAND_SYNC:
      static_cast<const SyncCommand*>(cmd)->signal->Post();
      break;

    case COMMAND_RUN_CLOSURE:
    {
      const ClosureCommand* ccmd = static_cast<const ClosureCommand*>(cmd);
      (*ccmd->fn)();
      delete ccmd->fn;
      if (ccmd->signal)
        ccmd->signal->Post();
    }
    break;

    case COMMAND_RECYCLE_TEXTURE:
      RecycleTexture(static_cast<const RecycleTextureCommand*>(cmd)->texture);
      break;

    default:
      DebugAssert(cmd->type >= GPUThreadQueue::FIRST_USER_TYPE && s_handler);
      s_handler(cmd);
      break;
  }

  RunPriorityClosures();
}

void ThreadMain()
{
  Threading::SetNameOfCurrentThread("GPU Thread");
  for (;;)
  {
    RunPriorityClosures();
    if (!s_queue->WaitForWork(SPIN_NS))
      break;
    RunPriorityClosures();
    s_queue->Drain(&HandleCommand);
  }
  RunPriorityClosures();
}

} // namespace
} // namespace GPUThread

bool GPUThread::Start(bool use_thread)
{
  Assert(!s_started);
  s_queue = std::make_unique<GPUThreadQueue>(QUEUE_SIZE);
  s_use_thread = use_thread;
  s_queued_frames.store(0, std::memory_order_release);
  s_started = true;

  if (!use_thread)
  {
    s_owner.store(std::this_thread::get_id(), std::memory_order_release);
    return true;
  }

  Signal started;
  if (!s_thread.Start([&started]() {
        s_owner.store(std::this_thread::get_id(), std::memory_order_release);
        started.Post();
        ThreadMain();
      }))
  {
    ERROR_LOG("Failed to start the GPU thread.");
    s_started = false;
    s_use_thread = false;
    s_queue.reset();
    return false;
  }

  started.Wait();
  INFO_LOG("GPU thread started.");
  return true;
}

void GPUThread::Stop()
{
  if (!s_started)
    return;

  if (s_use_thread)
  {
    s_queue->Shutdown();
    s_thread.Join();
    INFO_LOG("GPU thread stopped.");
  }
  else
  {
    RunPriorityClosures();
  }

  s_started = false;
  s_use_thread = false;
  s_owner.store(std::thread::id(), std::memory_order_release);
  s_queue.reset();
  s_queued_frames.store(0, std::memory_order_release);
  s_frames_cv.notify_all();
}

bool GPUThread::IsUsingThread()
{
  return s_use_thread;
}

bool GPUThread::IsOnThread()
{
  const std::thread::id owner = s_owner.load(std::memory_order_acquire);
  return (owner == std::thread::id() || owner == std::this_thread::get_id());
}

const Threading::Thread* GPUThread::GetThread()
{
  return s_use_thread ? &s_thread : nullptr;
}

void* GPUThread::AllocateCommand(u8 type, u32 size)
{
  if (!s_use_thread || IsOnThread())
  {
    // Run where it's pushed: a buffer of this nesting level's own, since a command may push others as it runs.
    if (s_direct_buffers.size() <= s_direct_depth)
      s_direct_buffers.resize(s_direct_depth + 1);
    std::vector<u8>& buffer = s_direct_buffers[s_direct_depth++];
    const u32 aligned = Common::AlignUpPow2(size, GPUThreadQueue::ALIGNMENT);
    if (buffer.size() < aligned)
      buffer.resize(aligned);

    GPUThreadCommand* cmd = reinterpret_cast<GPUThreadCommand*>(buffer.data());
    cmd->size = aligned;
    cmd->type = type;
    cmd->params = 0;
    cmd->reserved = 0;
    return cmd;
  }

  return s_queue->Allocate(type, size);
}

void GPUThread::PushCommand(void* cmd)
{
  if (!s_use_thread || IsOnThread())
  {
    DebugAssert(s_direct_depth > 0);
    HandleCommand(static_cast<const GPUThreadCommand*>(cmd));
    s_direct_depth--;
    return;
  }

  s_queue->Push();
}

void GPUThread::SetCommandHandler(CommandHandler handler)
{
  s_handler = handler;
}

void GPUThread::RunOnThread(std::function<void()> fn)
{
  if (IsOnThread())
  {
    fn();
    return;
  }

  ClosureCommand* cmd = static_cast<ClosureCommand*>(AllocateCommand(COMMAND_RUN_CLOSURE, sizeof(ClosureCommand)));
  cmd->fn = new std::function<void()>(std::move(fn));
  cmd->signal = nullptr;
  PushCommand(cmd);
}

void GPUThread::RunOnThreadAndWait(std::function<void()> fn)
{
  if (IsOnThread())
  {
    fn();
    return;
  }

  Signal signal;
  ClosureCommand* cmd = static_cast<ClosureCommand*>(AllocateCommand(COMMAND_RUN_CLOSURE, sizeof(ClosureCommand)));
  cmd->fn = new std::function<void()>(std::move(fn));
  cmd->signal = &signal;
  PushCommand(cmd);
  signal.Wait();
}

void GPUThread::RunOnThreadPriority(std::function<void()> fn, bool wait)
{
  if (IsOnThread())
  {
    fn();
    return;
  }

  Signal signal;
  {
    std::unique_lock lock(s_priority_mutex);
    s_priority.push_back(PriorityClosure{std::move(fn), wait ? &signal : nullptr});
    s_priority_count.fetch_add(1, std::memory_order_acq_rel);
  }

  if (s_use_thread)
    s_queue->WakeConsumer();

  if (wait)
    signal.Wait();
}

void GPUThread::Sync(bool spin)
{
  if (IsOnThread())
    return;

  Signal signal;
  SyncCommand* cmd = static_cast<SyncCommand*>(AllocateCommand(COMMAND_SYNC, sizeof(SyncCommand)));
  cmd->signal = &signal;
  PushCommand(cmd);
  if (spin)
  {
    while (!s_queue->IsEmpty())
      std::this_thread::yield();
  }
  signal.Wait();
}

void GPUThread::FramePushed()
{
  s_queued_frames.fetch_add(1, std::memory_order_acq_rel);
}

void GPUThread::FramePresented()
{
  {
    std::unique_lock lock(s_frames_mutex);
    const u32 queued = s_queued_frames.load(std::memory_order_acquire);
    if (queued > 0)
      s_queued_frames.store(queued - 1, std::memory_order_release);
  }
  s_frames_cv.notify_all();
}

u32 GPUThread::GetQueuedFrames()
{
  return s_queued_frames.load(std::memory_order_acquire);
}

void GPUThread::WaitForQueuedFrames(u32 max_queued)
{
  if (s_queued_frames.load(std::memory_order_acquire) <= max_queued)
    return;

  std::unique_lock lock(s_frames_mutex);
  s_frames_cv.wait(lock, [max_queued]() { return s_queued_frames.load(std::memory_order_acquire) <= max_queued; });
}

GPUThread::DeviceInfo GPUThread::GetDeviceInfo()
{
  std::unique_lock lock(s_info_mutex);
  return s_info;
}

void GPUThread::PublishDeviceInfo()
{
  DeviceInfo info;
  if (g_gpu_device)
  {
    const WindowInfo& wi = g_gpu_device->GetWindowInfo();
    info.has_device = true;
    info.has_surface = g_gpu_device->HasSurface();
    info.render_api = g_gpu_device->GetRenderAPI();
    info.window_width = g_gpu_device->GetWindowWidth();
    info.window_height = g_gpu_device->GetWindowHeight();
    info.window_scale = g_gpu_device->GetWindowScale();
    info.refresh_rate = wi.surface_refresh_rate;
    info.window_format = g_gpu_device->GetWindowFormat();
    info.lower_left_origin = g_gpu_device->UsesLowerLeftOrigin();
    info.explicit_present = g_gpu_device->GetFeatures().explicit_present;
    info.vsync_blocking = g_gpu_device->IsVSyncModeBlocking();
    info.exclusive_fullscreen = g_gpu_device->SupportsExclusiveFullscreen();
    info.gpu_timing = g_gpu_device->IsGPUTimingEnabled();
    info.debug_device = g_gpu_device->IsDebugDevice();
    info.max_texture_size = g_gpu_device->GetMaxTextureSize();
    info.max_multisamples = g_gpu_device->GetMaxMultisamples();
  }

  std::unique_lock lock(s_info_mutex);
  s_info = info;
}

void GPUThread::TextureDeleter::operator()(GPUTexture* tex) const
{
  if (!tex)
    return;

  if (!s_started || IsOnThread())
  {
    RecycleTexture(tex);
    return;
  }

  RecycleTextureCommand* cmd =
    static_cast<RecycleTextureCommand*>(AllocateCommand(COMMAND_RECYCLE_TEXTURE, sizeof(RecycleTextureCommand)));
  cmd->texture = tex;
  PushCommand(cmd);
}

GPUThread::TexturePtr GPUThread::CreateTexture(u32 width, u32 height, GPUTexture::Format format, const void* data,
                                               u32 data_pitch)
{
  GPUTexture* tex = nullptr;
  RunOnThreadAndWait([&]() {
    tex = g_gpu_device ?
            g_gpu_device->FetchTexture(width, height, 1, 1, 1, GPUTexture::Type::Texture, format, data, data_pitch)
              .release() :
            nullptr;
  });
  return TexturePtr(tex);
}

void GPUThread::SetRecycleHookForTest(void (*hook)(GPUTexture* tex))
{
  s_recycle_hook = hook;
}
