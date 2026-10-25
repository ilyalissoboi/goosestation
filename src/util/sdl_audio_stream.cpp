// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#include "audio_stream.h"

#include "common/assert.h"
#include "common/error.h"
#include "common/log.h"

#include <SDL3/SDL.h>

#include <vector>

Log_SetChannel(SDLAudioStream);

namespace {
class SDLAudioStream final : public AudioStream
{
public:
  SDLAudioStream(u32 sample_rate, const AudioStreamParameters& parameters);
  ~SDLAudioStream();

  void SetPaused(bool paused) override;

  bool OpenDevice(Error* error);
  void CloseDevice();

protected:
  ALWAYS_INLINE bool IsOpen() const { return (m_stream != nullptr); }

  static void AudioCallback(void* userdata, SDL_AudioStream* stream, int additional_amount, int total_amount);

  SDL_AudioStream* m_stream = nullptr;
  std::vector<SampleType> m_read_buffer;
};
} // namespace

static bool InitializeSDLAudio(Error* error)
{
  static bool initialized = false;
  if (initialized)
    return true;

  // May as well keep it alive until the process exits.
  if (!SDL_InitSubSystem(SDL_INIT_AUDIO))
  {
    Error::SetStringFmt(error, "SDL_InitSubSystem(SDL_INIT_AUDIO) failed: {}", SDL_GetError());
    return false;
  }

  std::atexit([]() { SDL_QuitSubSystem(SDL_INIT_AUDIO); });

  initialized = true;
  return true;
}

SDLAudioStream::SDLAudioStream(u32 sample_rate, const AudioStreamParameters& parameters)
  : AudioStream(sample_rate, parameters)
{
}

SDLAudioStream::~SDLAudioStream()
{
  if (IsOpen())
    SDLAudioStream::CloseDevice();
}

std::unique_ptr<AudioStream> AudioStream::CreateSDLAudioStream(u32 sample_rate, const AudioStreamParameters& parameters,
                                                               Error* error)
{
  if (!InitializeSDLAudio(error))
    return {};

  std::unique_ptr<SDLAudioStream> stream = std::make_unique<SDLAudioStream>(sample_rate, parameters);
  if (!stream->OpenDevice(error))
    stream.reset();

  return stream;
}

bool SDLAudioStream::OpenDevice(Error* error)
{
  DebugAssert(!IsOpen());

  static constexpr const std::array<SampleReader, static_cast<size_t>(AudioExpansionMode::Count)> sample_readers = {{
    // Disabled
    &StereoSampleReaderImpl,
    // StereoLFE
    &SampleReaderImpl<AudioExpansionMode::StereoLFE, READ_CHANNEL_FRONT_LEFT, READ_CHANNEL_FRONT_RIGHT,
                      READ_CHANNEL_LFE>,
    // Quadraphonic
    &SampleReaderImpl<AudioExpansionMode::Quadraphonic, READ_CHANNEL_FRONT_LEFT, READ_CHANNEL_FRONT_RIGHT,
                      READ_CHANNEL_REAR_LEFT, READ_CHANNEL_REAR_RIGHT>,
    // QuadraphonicLFE
    &SampleReaderImpl<AudioExpansionMode::QuadraphonicLFE, READ_CHANNEL_FRONT_LEFT, READ_CHANNEL_FRONT_RIGHT,
                      READ_CHANNEL_LFE, READ_CHANNEL_REAR_LEFT, READ_CHANNEL_REAR_RIGHT>,
    // Surround51
    &SampleReaderImpl<AudioExpansionMode::Surround51, READ_CHANNEL_FRONT_LEFT, READ_CHANNEL_FRONT_RIGHT,
                      READ_CHANNEL_FRONT_CENTER, READ_CHANNEL_LFE, READ_CHANNEL_REAR_LEFT, READ_CHANNEL_REAR_RIGHT>,
    // Surround71
    &SampleReaderImpl<AudioExpansionMode::Surround71, READ_CHANNEL_FRONT_LEFT, READ_CHANNEL_FRONT_RIGHT,
                      READ_CHANNEL_FRONT_CENTER, READ_CHANNEL_LFE, READ_CHANNEL_SIDE_LEFT, READ_CHANNEL_SIDE_RIGHT,
                      READ_CHANNEL_REAR_LEFT, READ_CHANNEL_REAR_RIGHT>,
  }};

  const SDL_AudioSpec spec = {
    .format = SDL_AUDIO_S16,
    .channels = static_cast<int>(m_output_channels),
    .freq = static_cast<int>(m_sample_rate),
  };
  const u32 buffer_frames = GetBufferSizeForMS(
    m_sample_rate, (m_parameters.output_latency_ms == 0) ? m_parameters.buffer_ms : m_parameters.output_latency_ms);

  // SDL3 takes the device buffer size as a hint, and may pick another.
  SDL_SetHint(SDL_HINT_AUDIO_DEVICE_SAMPLE_FRAMES, fmt::format("{}", buffer_frames).c_str());
  m_stream = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, AudioCallback, this);
  if (!m_stream)
  {
    Error::SetStringFmt(error, "SDL_OpenAudioDeviceStream() failed: {}", SDL_GetError());
    return false;
  }

  SDL_AudioSpec obtained_spec = {};
  int obtained_frames = 0;
  SDL_GetAudioDeviceFormat(SDL_GetAudioStreamDevice(m_stream), &obtained_spec, &obtained_frames);
  DEV_LOG("Requested {} frame buffer, got {} frame buffer", buffer_frames, obtained_frames);

  BaseInitialize(sample_readers[static_cast<size_t>(m_parameters.expansion_mode)]);
  SDL_ResumeAudioStreamDevice(m_stream);

  return true;
}

void SDLAudioStream::SetPaused(bool paused)
{
  if (m_paused == paused)
    return;

  if (paused)
    SDL_PauseAudioStreamDevice(m_stream);
  else
    SDL_ResumeAudioStreamDevice(m_stream);

  m_paused = paused;
}

void SDLAudioStream::CloseDevice()
{
  SDL_DestroyAudioStream(m_stream);
  m_stream = nullptr;
}

void SDLAudioStream::AudioCallback(void* userdata, SDL_AudioStream* stream, int additional_amount, int total_amount)
{
  SDLAudioStream* const this_ptr = static_cast<SDLAudioStream*>(userdata);
  const u32 frame_size = sizeof(SampleType) * this_ptr->m_output_channels;
  const u32 num_frames = static_cast<u32>(additional_amount) / frame_size;
  if (num_frames == 0)
    return;

  // Only grows, so the audio thread rarely allocates.
  const size_t num_samples = static_cast<size_t>(num_frames) * this_ptr->m_output_channels;
  if (this_ptr->m_read_buffer.size() < num_samples)
    this_ptr->m_read_buffer.resize(num_samples);

  this_ptr->ReadFrames(this_ptr->m_read_buffer.data(), num_frames);
  SDL_PutAudioStreamData(stream, this_ptr->m_read_buffer.data(), static_cast<int>(num_frames * frame_size));
}
