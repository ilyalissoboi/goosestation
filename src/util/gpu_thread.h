// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "gpu_texture.h"
#include "gpu_thread_queue.h"

#include "common/types.h"

#include <functional>
#include <memory>

namespace Threading {
class Thread;
}

enum class RenderAPI : u32;

/// The thread that owns the GPU device and does every piece of work with it: commands pushed by the emulation thread
/// in one stream, and closures. Without a thread of its own ("inline"), the thread that called Start() owns the
/// device and runs each command as it's pushed. Before Start(), any thread may use the device (tools and tests).
namespace GPUThread {

/// GPUThread's own command types, below GPUThreadQueue::FIRST_USER_TYPE.
enum : u8
{
  COMMAND_SYNC = 1,
  COMMAND_RUN_CLOSURE = 2,
  COMMAND_RECYCLE_TEXTURE = 3,
};

bool Start(bool use_thread);

/// Runs what's queued, then joins the thread. Nothing may be pushed afterwards until the next Start().
void Stop();

bool IsUsingThread();

/// True on the device's owner: the GPU thread, the thread that called Start() inline, or anyone before Start().
bool IsOnThread();

/// The GPU thread, for its CPU time; nullptr inline.
const Threading::Thread* GetThread();

/// A command of type (at least GPUThreadQueue::FIRST_USER_TYPE) and size bytes, header included, for PushCommand().
void* AllocateCommand(u8 type, u32 size);

/// Queues the command. Threaded, commands go to the thread in batches: at Publish(), at any closure, Sync() or wait
/// for frames, or once enough of them wait.
void PushCommand(void* cmd);

/// Takes the commands pushed so far to the thread now (a present, so its frame is drawn while the next is emulated).
void Publish();

using CommandHandler = void (*)(const GPUThreadCommand* cmd);
void SetCommandHandler(CommandHandler handler);

/// Runs fn on the GPU thread, in order with the commands pushed before it. At once on the GPU thread itself.
void RunOnThread(std::function<void()> fn);

/// As RunOnThread(), and returns once fn has run.
void RunOnThreadAndWait(std::function<void()> fn);

/// From any thread: runs fn at the GPU thread's next command boundary, ahead of queued work, or at once when it's
/// idle. Inline, it runs at the owner's next pushed command.
void RunOnThreadPriority(std::function<void()> fn, bool wait);

/// Returns once everything pushed before it has run. spin: busy-waits rather than sleeping.
void Sync(bool spin);

/// The emulation thread counts each present it pushes; the GPU thread, each it has finished.
void FramePushed();
void FramePresented();
u32 GetQueuedFrames();

/// Returns once at most max_queued pushed presents are unfinished.
void WaitForQueuedFrames(u32 max_queued);

/// How many presents may be unfinished when the emulation thread polls input for the next frame: one, or none while
/// presents block on the screen (so a blocking present can't add a frame of lag).
constexpr u32 MaxFramesAhead(bool vsync_blocking)
{
  return vsync_blocking ? 0u : 1u;
}

/// What other threads may know about the device, as it was when the GPU thread last published it.
struct DeviceInfo
{
  bool has_device = false;
  bool has_surface = false;
  RenderAPI render_api = static_cast<RenderAPI>(0);
  s32 window_width = 0;
  s32 window_height = 0;
  float window_scale = 1.0f;
  float refresh_rate = 0.0f;
  GPUTexture::Format window_format = GPUTexture::Format::Unknown;
  bool lower_left_origin = false;
  bool explicit_present = false;
  bool vsync_blocking = false;
  bool exclusive_fullscreen = false;
  bool gpu_timing = false;
  bool debug_device = false;
  bool allow_present_throttle = false;
  bool has_present_wait = false;
  bool automatic_vsync = false;
  u32 max_texture_size = 0;
  u32 max_multisamples = 1;
};

DeviceInfo GetDeviceInfo();

/// On the GPU thread: takes a new DeviceInfo from g_gpu_device (or "no device").
void PublishDeviceInfo();

/// Recycles a texture on the GPU thread, after the work already queued (which may still draw it).
struct TextureDeleter
{
  void operator()(GPUTexture* tex) const;
};
using TexturePtr = std::unique_ptr<GPUTexture, TextureDeleter>;

/// Makes a texture on the GPU thread, and waits for it.
TexturePtr CreateTexture(u32 width, u32 height, GPUTexture::Format format, const void* data, u32 data_pitch);

/// Tests: called in place of recycling a texture, on the GPU thread.
void SetRecycleHookForTest(void (*hook)(GPUTexture* tex));

} // namespace GPUThread
