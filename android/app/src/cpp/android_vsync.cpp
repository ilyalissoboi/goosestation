// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "android_vsync.h"
#include "jni_classes.h"

#include "core/frame_pacing.h"
#include "core/host.h"
#include "core/gpu_thread_commands.h"
#include "core/system.h"

#include "common/log.h"
#include "common/timer.h"

#include <android/choreographer.h>
#include <android/looper.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>

Log_SetChannel(AndroidVsync);

namespace AndroidVsync {
namespace {

// Loaded by name: postVsyncCallback and its frame data are Android 13 (API 33), postFrameCallback64 Android 10 (29).
struct FrameData;
using GetInstanceFn = AChoreographer* (*)();
using FrameCallback64 = void (*)(int64_t frame_time_nanos, void* data);
using PostFrameCallback64Fn = void (*)(AChoreographer*, FrameCallback64, void*);
using VsyncCallback = void (*)(const FrameData* data, void* user);
using PostVsyncCallbackFn = int (*)(AChoreographer*, VsyncCallback, void*);
using GetFrameTimeFn = int64_t (*)(const FrameData*);
using GetTimelinesLengthFn = size_t (*)(const FrameData*);
using GetPreferredIndexFn = size_t (*)(const FrameData*);
using GetTimelineTimeFn = int64_t (*)(const FrameData*, size_t);

struct Api
{
  GetInstanceFn get_instance = nullptr;
  PostFrameCallback64Fn post_frame_callback64 = nullptr;
  PostVsyncCallbackFn post_vsync_callback = nullptr;
  GetFrameTimeFn frame_time = nullptr;
  GetTimelinesLengthFn timelines_length = nullptr;
  GetPreferredIndexFn preferred_index = nullptr;
  GetTimelineTimeFn expected_present = nullptr;
  GetTimelineTimeFn deadline = nullptr;
};

Api s_api;
bool s_api_loaded = false;

std::mutex s_mutex;
std::condition_variable s_cv;
std::thread s_thread;
ALooper* s_looper = nullptr;
std::atomic_bool s_stopping{false};

// The latest refreshes: guarded by s_mutex.
DisplayTimeline s_timeline;
Common::Timer::Value s_last_tick = 0;
int64_t s_last_frame_time = 0;

bool LoadApi()
{
  if (s_api_loaded)
    return (s_api.get_instance != nullptr);

  s_api_loaded = true;
  void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
  if (!lib)
    return false;

  s_api.get_instance = reinterpret_cast<GetInstanceFn>(dlsym(lib, "AChoreographer_getInstance"));
  s_api.post_frame_callback64 =
    reinterpret_cast<PostFrameCallback64Fn>(dlsym(lib, "AChoreographer_postFrameCallback64"));
  s_api.post_vsync_callback = reinterpret_cast<PostVsyncCallbackFn>(dlsym(lib, "AChoreographer_postVsyncCallback"));
  s_api.frame_time = reinterpret_cast<GetFrameTimeFn>(dlsym(lib, "AChoreographerFrameCallbackData_getFrameTimeNanos"));
  s_api.timelines_length =
    reinterpret_cast<GetTimelinesLengthFn>(dlsym(lib, "AChoreographerFrameCallbackData_getFrameTimelinesLength"));
  s_api.preferred_index = reinterpret_cast<GetPreferredIndexFn>(
    dlsym(lib, "AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex"));
  s_api.expected_present = reinterpret_cast<GetTimelineTimeFn>(
    dlsym(lib, "AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos"));
  s_api.deadline = reinterpret_cast<GetTimelineTimeFn>(
    dlsym(lib, "AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos"));

  if (!s_api.frame_time || !s_api.timelines_length || !s_api.preferred_index || !s_api.expected_present ||
      !s_api.deadline)
  {
    s_api.post_vsync_callback = nullptr;
  }
  if (!s_api.get_instance || (!s_api.post_vsync_callback && !s_api.post_frame_callback64))
    s_api.get_instance = nullptr;

  INFO_LOG("Choreographer: {}", s_api.post_vsync_callback ? "frame timelines" :
                                (s_api.get_instance ? "refresh times only" : "not available"));
  return (s_api.get_instance != nullptr);
}

void Post();

// The refresh period from the gaps between ticks (a skipped tick left out, a new rate followed).
FramePacing::PeriodEstimator s_period;

Common::Timer::Value SmoothedPeriod(int64_t frame_time)
{
  if (s_last_frame_time != 0 && frame_time > s_last_frame_time)
    s_period.Add(static_cast<Common::Timer::Value>(frame_time - s_last_frame_time));
  s_last_frame_time = frame_time;
  return s_period.Period();
}

void OnVsync(const FrameData* data, void*)
{
  {
    std::unique_lock lock(s_mutex);
    const size_t count = s_api.timelines_length(data);
    const size_t index = s_api.preferred_index(data);
    const int64_t present = s_api.expected_present(data, index);
    const int64_t deadline = s_api.deadline(data, index);
    // The refresh period from tick to tick (a skipped tick left out): the timelines' own spacing can be two refreshes
    // at 120 Hz. Only before the second tick, the timelines'.
    Common::Timer::Value period = SmoothedPeriod(s_api.frame_time(data));
    if (period == 0 && index + 1 < count)
      period = static_cast<Common::Timer::Value>(s_api.expected_present(data, index + 1) - present);

    s_timeline.vsync_time = static_cast<Common::Timer::Value>(present);
    s_timeline.period = period;
    s_timeline.deadline_lead = static_cast<Common::Timer::Value>(std::max<int64_t>(present - deadline, 0));
    s_last_tick = Common::Timer::GetCurrentValue();
  }
  Post();
}

void OnFrame(int64_t frame_time_nanos, void*)
{
  {
    std::unique_lock lock(s_mutex);
    s_timeline.period = SmoothedPeriod(frame_time_nanos);
    s_timeline.vsync_time = static_cast<Common::Timer::Value>(frame_time_nanos);
    s_timeline.deadline_lead = s_timeline.period / 2; // no deadline before Android 13: half a refresh's margin
    s_last_tick = Common::Timer::GetCurrentValue();
  }
  Post();
}

void Post()
{
  if (s_stopping.load(std::memory_order_acquire))
    return;

  AChoreographer* choreographer = s_api.get_instance();
  if (s_api.post_vsync_callback)
    s_api.post_vsync_callback(choreographer, &OnVsync, nullptr);
  else
    s_api.post_frame_callback64(choreographer, &OnFrame, nullptr);
}

void ThreadMain()
{
  ALooper* looper = ALooper_prepare(0);
  ALooper_acquire(looper);
  {
    std::unique_lock lock(s_mutex);
    s_looper = looper;
  }
  s_cv.notify_all();

  Post();
  while (!s_stopping.load(std::memory_order_acquire))
    ALooper_pollOnce(-1, nullptr, nullptr, nullptr);

  {
    std::unique_lock lock(s_mutex);
    s_looper = nullptr;
  }
  ALooper_release(looper);
}

} // namespace
} // namespace AndroidVsync

bool AndroidVsync::Start()
{
  std::unique_lock lock(s_mutex);
  if (s_thread.joinable())
    return true;
  if (!LoadApi())
    return false;

  s_stopping.store(false, std::memory_order_release);
  s_timeline = {};
  s_last_tick = 0;
  s_last_frame_time = 0;
  s_period.Reset();
  s_thread = std::thread(&ThreadMain);
  s_cv.wait(lock, []() { return s_looper != nullptr; });
  return true;
}

void AndroidVsync::Stop()
{
  std::thread thread;
  {
    std::unique_lock lock(s_mutex);
    if (!s_thread.joinable())
      return;
    s_stopping.store(true, std::memory_order_release);
    if (s_looper)
      ALooper_wake(s_looper);
    thread = std::move(s_thread);
  }
  thread.join();

  std::unique_lock lock(s_mutex);
  s_timeline = {};
  s_last_tick = 0;
}

bool AndroidVsync::Get(DisplayTimeline* timeline)
{
  static constexpr double STALE_MS = 100.0;

  std::unique_lock lock(s_mutex);
  if (s_last_tick == 0 || s_timeline.period == 0 ||
      Common::Timer::ConvertValueToMilliseconds(Common::Timer::GetCurrentValue() - s_last_tick) > STALE_MS)
  {
    return false;
  }

  *timeline = s_timeline;
  return true;
}

void AndroidVsync::SetWindowFrameRate(ANativeWindow* window, float rate)
{
  using SetFrameRateFn = int32_t (*)(ANativeWindow*, float, int8_t);
  static const SetFrameRateFn set_frame_rate = []() {
    // libnativewindow has the ANativeWindow functions (libandroid only some of them).
    void* lib = dlopen("libnativewindow.so", RTLD_NOW | RTLD_LOCAL);
    return lib ? reinterpret_cast<SetFrameRateFn>(dlsym(lib, "ANativeWindow_setFrameRate")) : nullptr;
  }();
  if (!set_frame_rate || !window)
    return;

  // ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE: a game's frames come at their own fixed rate.
  static constexpr int8_t FIXED_SOURCE = 1;
  const int32_t result = set_frame_rate(window, rate, FIXED_SOURCE);
  INFO_LOG("Asked for {:.3f} Hz for the surface{}", rate, (result == 0) ? "" : " (refused)");
}

bool Host::GetDisplayTimeline(DisplayTimeline* timeline)
{
  return AndroidVsync::Get(timeline);
}

extern "C" {

NATIVE_LIBRARY_FUNCTION(void, startVsyncTimelineForTest)(JNIEnv*, jobject)
{
  AndroidVsync::Start();
}

NATIVE_LIBRARY_FUNCTION(void, stopVsyncTimelineForTest)(JNIEnv*, jobject)
{
  AndroidVsync::Stop();
}

NATIVE_LIBRARY_FUNCTION(jlongArray, getVsyncTimelineForTest)(JNIEnv* env, jobject)
{
  DisplayTimeline timeline;
  if (!AndroidVsync::Get(&timeline))
    return nullptr;

  const jlong values[3] = {static_cast<jlong>(timeline.vsync_time), static_cast<jlong>(timeline.period),
                           static_cast<jlong>(timeline.deadline_lead)};
  jlongArray array = env->NewLongArray(3);
  env->SetLongArrayRegion(array, 0, 3, values);
  return array;
}

NATIVE_LIBRARY_FUNCTION(jlongArray, getFramePacingStatsForTest)(JNIEnv* env, jobject)
{
  u64 presents = 0, made = 0;
  const u32 refreshes = System::GetDisplayLockStats(&presents, &made);
  const jlong values[5] = {static_cast<jlong>(presents), static_cast<jlong>(made), static_cast<jlong>(refreshes),
                           static_cast<jlong>(GPUThreadCommands::GetCutoffReports()),
                           static_cast<jlong>(GPUThreadCommands::GetCutoffOffset())};
  jlongArray array = env->NewLongArray(5);
  env->SetLongArrayRegion(array, 0, 5, values);
  return array;
}

} // extern "C"
