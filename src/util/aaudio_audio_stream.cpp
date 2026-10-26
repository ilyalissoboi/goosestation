// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "audio_stream.h"

#include "common/assert.h"
#include "common/error.h"
#include "common/log.h"
#include "common/threading.h"

#include <aaudio/AAudio.h>

#include <algorithm>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <utility>

Log_SetChannel(AAudioAudioStream);

namespace {
class AAudioAudioStream final : public AudioStream
{
public:
  AAudioAudioStream(u32 sample_rate, const AudioStreamParameters& parameters);
  ~AAudioAudioStream() override;

  void SetPaused(bool paused) override;

  bool Initialize(Error* error);

private:
  bool OpenStream(Error* error);
  void CloseStream();
  bool StartStream(Error* error);

  void ReopenThreadEntryPoint();

  static aaudio_data_callback_result_t DataCallback(AAudioStream* stream, void* userdata, void* audio_data,
                                                    int32_t num_frames);
  static void ErrorCallback(AAudioStream* stream, void* userdata, aaudio_result_t error);

  // Guards m_stream and m_paused against the reopen thread.
  std::mutex m_stream_mutex;
  AAudioStream* m_stream = nullptr;

  // The error callback may not close the stream itself, so a disconnected stream is reopened on this thread.
  std::thread m_reopen_thread;
  std::mutex m_reopen_mutex;
  std::condition_variable m_reopen_cv;
  AAudioStream* m_reopen_stream = nullptr;
  bool m_reopen_thread_shutdown = false;
};
} // namespace

AAudioAudioStream::AAudioAudioStream(u32 sample_rate, const AudioStreamParameters& parameters)
  : AudioStream(sample_rate, parameters)
{
}

AAudioAudioStream::~AAudioAudioStream()
{
  if (m_reopen_thread.joinable())
  {
    {
      std::unique_lock lock(m_reopen_mutex);
      m_reopen_thread_shutdown = true;
    }
    m_reopen_cv.notify_one();
    m_reopen_thread.join();
  }

  std::unique_lock lock(m_stream_mutex);
  CloseStream();
}

std::unique_ptr<AudioStream> AudioStream::CreateAAudioAudioStream(u32 sample_rate,
                                                                  const AudioStreamParameters& parameters, Error* error)
{
  std::unique_ptr<AAudioAudioStream> stream = std::make_unique<AAudioAudioStream>(sample_rate, parameters);
  if (!stream->Initialize(error))
    stream.reset();

  return stream;
}

bool AAudioAudioStream::Initialize(Error* error)
{
  // Expansion is disabled on Android, so the output is always stereo.
  BaseInitialize(&StereoSampleReaderImpl);

  std::unique_lock lock(m_stream_mutex);
  if (!OpenStream(error) || !StartStream(error))
  {
    CloseStream();
    return false;
  }

  lock.unlock();
  m_reopen_thread = std::thread(&AAudioAudioStream::ReopenThreadEntryPoint, this);
  return true;
}

bool AAudioAudioStream::OpenStream(Error* error)
{
  DebugAssert(!m_stream);

  AAudioStreamBuilder* builder;
  aaudio_result_t res = AAudio_createStreamBuilder(&builder);
  if (res != AAUDIO_OK)
  {
    Error::SetStringFmt(error, "AAudio_createStreamBuilder() failed: {}", AAudio_convertResultToText(res));
    return false;
  }

  AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
  AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
  AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
  AAudioStreamBuilder_setChannelCount(builder, static_cast<int32_t>(m_output_channels));
  AAudioStreamBuilder_setSampleRate(builder, static_cast<int32_t>(m_sample_rate));
  AAudioStreamBuilder_setDataCallback(builder, DataCallback, this);
  AAudioStreamBuilder_setErrorCallback(builder, ErrorCallback, this);

  res = AAudioStreamBuilder_openStream(builder, &m_stream);
  AAudioStreamBuilder_delete(builder);
  if (res != AAUDIO_OK)
  {
    m_stream = nullptr;
    Error::SetStringFmt(error, "AAudioStreamBuilder_openStream() failed: {}", AAudio_convertResultToText(res));
    return false;
  }

  // AAudio either honours explicit values or fails the open; check anyway, since ReadFrames writes this exact layout.
  const int32_t got_rate = AAudioStream_getSampleRate(m_stream);
  const int32_t got_channels = AAudioStream_getChannelCount(m_stream);
  const aaudio_format_t got_format = AAudioStream_getFormat(m_stream);
  if (got_rate != static_cast<int32_t>(m_sample_rate) || got_channels != static_cast<int32_t>(m_output_channels) ||
      got_format != AAUDIO_FORMAT_PCM_I16)
  {
    Error::SetStringFmt(error,
                        "AAudio opened a {} Hz, {} channel, format {} stream instead of {} Hz, {} channel, 16-bit",
                        got_rate, got_channels, static_cast<int>(got_format), m_sample_rate, m_output_channels);
    CloseStream();
    return false;
  }

  // Two bursts is the lowest latency that doesn't glitch; otherwise aim for the configured latency.
  const int32_t burst_frames = AAudioStream_getFramesPerBurst(m_stream);
  int32_t buffer_frames = burst_frames * 2;
  if (!m_parameters.output_latency_minimal)
  {
    const u32 latency_ms =
      (m_parameters.output_latency_ms == 0) ? m_parameters.buffer_ms : m_parameters.output_latency_ms;
    buffer_frames = std::max(buffer_frames, static_cast<int32_t>(GetBufferSizeForMS(m_sample_rate, latency_ms)));
  }

  // Clamped to the stream's capacity by AAudio; returns the size actually set.
  const aaudio_result_t set_frames = AAudioStream_setBufferSizeInFrames(m_stream, buffer_frames);
  DEV_LOG("Opened AAudio stream: {} Hz, burst {} frames, requested buffer {} frames, got {}", got_rate, burst_frames,
          buffer_frames, (set_frames >= 0) ? set_frames : AAudioStream_getBufferSizeInFrames(m_stream));
  return true;
}

void AAudioAudioStream::CloseStream()
{
  if (!m_stream)
    return;

  // Stops the stream, and no callback runs after it returns.
  const aaudio_result_t res = AAudioStream_close(m_stream);
  if (res != AAUDIO_OK)
    ERROR_LOG("AAudioStream_close() failed: {}", AAudio_convertResultToText(res));

  m_stream = nullptr;
}

bool AAudioAudioStream::StartStream(Error* error)
{
  const aaudio_result_t res = AAudioStream_requestStart(m_stream);
  if (res != AAUDIO_OK)
  {
    Error::SetStringFmt(error, "AAudioStream_requestStart() failed: {}", AAudio_convertResultToText(res));
    return false;
  }

  return true;
}

void AAudioAudioStream::SetPaused(bool paused)
{
  std::unique_lock lock(m_stream_mutex);
  if (m_paused == paused)
    return;

  // With no stream (a reopen failed), only the state is recorded; a later reopen honours it.
  if (m_stream)
  {
    const aaudio_result_t res = paused ? AAudioStream_requestPause(m_stream) : AAudioStream_requestStart(m_stream);
    if (res != AAUDIO_OK)
    {
      ERROR_LOG("AAudioStream_request{}() failed: {}", paused ? "Pause" : "Start", AAudio_convertResultToText(res));
    }
  }

  m_paused = paused;
}

void AAudioAudioStream::ReopenThreadEntryPoint()
{
  Threading::SetNameOfCurrentThread("AAudio Reopen");

  std::unique_lock lock(m_reopen_mutex);
  for (;;)
  {
    m_reopen_cv.wait(lock, [this]() { return m_reopen_thread_shutdown || m_reopen_stream; });
    if (m_reopen_thread_shutdown)
      return;

    AAudioStream* const disconnected_stream = std::exchange(m_reopen_stream, nullptr);
    lock.unlock();

    {
      std::unique_lock stream_lock(m_stream_mutex);

      // A stale request, for a stream that's already been replaced.
      if (m_stream == disconnected_stream)
      {
        INFO_LOG("AAudio stream disconnected, reopening it on the new output device.");
        CloseStream();

        Error error;
        if (!OpenStream(&error) || (!m_paused && !StartStream(&error)))
        {
          ERROR_LOG("Failed to reopen the AAudio stream: {}", error.GetDescription());
          CloseStream();
        }
      }
    }

    lock.lock();
  }
}

aaudio_data_callback_result_t AAudioAudioStream::DataCallback(AAudioStream* stream, void* userdata, void* audio_data,
                                                              int32_t num_frames)
{
  AAudioAudioStream* const this_ptr = static_cast<AAudioAudioStream*>(userdata);
  this_ptr->ReadFrames(static_cast<SampleType*>(audio_data), static_cast<u32>(num_frames));
  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AAudioAudioStream::ErrorCallback(AAudioStream* stream, void* userdata, aaudio_result_t error)
{
  // Headphones plugged in or out, a USB or Bluetooth device going away: the stream is dead and must be reopened.
  if (error != AAUDIO_ERROR_DISCONNECTED)
  {
    ERROR_LOG("AAudio stream error: {}", AAudio_convertResultToText(error));
    return;
  }

  AAudioAudioStream* const this_ptr = static_cast<AAudioAudioStream*>(userdata);
  {
    std::unique_lock lock(this_ptr->m_reopen_mutex);
    this_ptr->m_reopen_stream = stream;
  }
  this_ptr->m_reopen_cv.notify_one();
}
